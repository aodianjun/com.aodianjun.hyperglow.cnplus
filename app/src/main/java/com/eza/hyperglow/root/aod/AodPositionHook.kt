package com.eza.hyperglow.root.aod

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.hierarchyField
import com.eza.hyperglow.root.readHierarchyField
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

internal object AodPositionHook {
    private data class ControllerState(
        var lastStockTranslationX: Int? = null,
        var lastStockTranslationY: Float? = null,
        var managedStep: Int = -1,
        var currentManagedDecision: AodClockPlacementDecision? = null,
        var pendingManagedDecision: AodClockPlacementDecision? = null
    )

    private data class PositionResolution(val decision: AodClockPlacementDecision)

    private data class ManagedAdvance(
        val controller: Any,
        val previousStep: Int,
        val previousDecision: AodClockPlacementDecision?,
        val attemptedStep: Int,
        val attemptedDecision: AodClockPlacementDecision
    )

    private data class StockRestore(
        val controller: Any,
        val stockX: Int,
        val stockY: Float,
        val managedStep: Int,
        val managedDecision: AodClockPlacementDecision
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private const val FEATURE_ID = "aod-position"
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val controllerStates = WeakHashMap<Any, ControllerState>()
    private var lastControllerRef = WeakReference<Any>(null)
    private var targetViewRef = WeakReference<View>(null)
    private val targetViewScratch = Rect()
    private val targetRootLocation = IntArray(2)

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val controller = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull() ?: return
        val update = controller.getDeclaredMethod(
            "updateTranslation",
            Boolean::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Float::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val updatePosition = classLoader.loadClass(DOZE_HOST_CLASS)
            .getDeclaredMethod("updatePosition").apply { isAccessible = true }
        if (!hookedClassLoaders.add(classLoader)) return
        HookRegistry.hook(module, FEATURE_ID, update, PositionHooker)
        HookRegistry.hook(module, FEATURE_ID, updatePosition, PositionCompletionHooker)
        HookLogger.i(TAG, "AOD position hook installed")
    }

    fun observeAodRoot(root: Any) {
        val controller = readHierarchyField(root, "mPositionController") ?: return
        synchronized(controllerStates) {
            controllerStates.getOrPut(controller) { ControllerState() }
            lastControllerRef = WeakReference(controller)
        }
        captureTargetView(controller)
    }

    fun renderedTargetBoundsInRoot(root: ViewGroup): AodRenderedClockBounds? {
        val target = targetViewRef.get() ?: return null
        if (!root.isAttachedToWindow || !target.isAttachedToWindow ||
            target.windowToken != root.windowToken ||
            target.visibility != View.VISIBLE || target.width <= 0 || target.height <= 0 ||
            root.width <= 0 || root.height <= 0 || effectiveAlpha(target) <= MIN_VISIBLE_ALPHA
        ) return null
        if (!target.getGlobalVisibleRect(targetViewScratch) || targetViewScratch.height() <= 0) {
            return null
        }
        root.getLocationInWindow(targetRootLocation)
        val top = (targetViewScratch.top - targetRootLocation[1]).coerceIn(0, root.height)
        val bottom = (targetViewScratch.bottom - targetRootLocation[1]).coerceIn(top, root.height)
        return AodRenderedClockBounds(top, bottom).takeIf { it.height > 0 }
    }

    fun isLinkageMode(): Boolean = synchronized(controllerStates) {
        val controller = lastControllerRef.get() ?: return@synchronized false
        runCatching { readIntField(controller, "mMode") == LINKAGE_MODE }.getOrDefault(false)
    }

    private object PositionHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val controller = chain.thisObject
            if (controller != null) captureTargetView(controller)
            val requestedX = (chain.args.getOrNull(1) as? Number)?.toInt()
            val requestedY = (chain.args.getOrNull(2) as? Number)?.toFloat()
            val animated = chain.args.firstOrNull() as? Boolean ?: false
            val resolution = if (controller != null && requestedX != null && requestedY != null) {
                resolveDecision(controller, requestedX, requestedY)
            } else {
                null
            }
            val decision = resolution?.decision
            val appliedAnimated = shouldAnimateAodPosition(
                requested = animated,
                overridden = decision?.overridden == true,
                placementChanged = decision?.zoneChanged == true
            )
            val result = if (decision != null) {
                chain.proceed(
                    arrayOf<Any>(
                        appliedAnimated,
                        decision.appliedTranslationX,
                        decision.appliedTranslationY
                    )
                )
            } else {
                chain.proceed()
            }
            val translationX = decision?.appliedTranslationX?.toFloat()
                ?: requestedX?.toFloat()
                ?: return result
            val translationY = decision?.appliedTranslationY ?: requestedY ?: return result
            AodSurfaceController.onStockPositionUpdated(
                translationX,
                translationY,
                readFodSafeBottom(controller),
                decision?.clockTop,
                decision?.clockBottom,
                decision?.lyricTopSafe,
                decision?.zone ?: AodSceneZone.STOCK,
                decision?.zoneChanged == true,
                appliedAnimated &&
                    (decision?.overridden == true || decision?.zoneChanged == true)
            )
            return result
        }
    }

    private object PositionCompletionHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            AodSurfaceController.onStockPositionSettled()
            return result
        }
    }

    /** suppressStockAodContent 激活时的直通标记(见 AodSurfaceController / AodSurfaceHook)。 */
    @Volatile
    private var suppressActive = false
    /** 歌词时段冻结系统组件束(不做 managed 位移,也不让系统时钟沉降漂移)。 */
    @Volatile
    private var holdStockPosition = false
    /**
     * 按住系统时钟可见但钉住其位置:关闭「实时跟随系统时钟」时置位。与 [holdStockPosition]
     * 不同 —— 此处不停用 managed 位移,也不隐藏时钟,只在原厂透传路径把 Y 固定在上次
     * 锚定位置,令时钟不随防烧屏沉降下移(见 issue #26)。
     */
    @Volatile
    private var pinClockVisible = false
    /**
     * 自定义系统时钟钉住位置的垂直偏移(px)。仅当关闭「实时跟随系统时钟」且正在渲染
     * AOD(pinClock 激活)时,叠加到被钉住的时钟 Y 上。默认 0(不偏移)。
     */
    @Volatile
    private var clockYOffsetPx = 0

    fun setSuppressActive(active: Boolean) {
        suppressActive = active
        if (active) abandonManagedSession()
    }

    fun setHoldStockPosition(active: Boolean) {
        holdStockPosition = active
    }

    fun setIntegralClockPin(active: Boolean) {
        pinClockVisible = active
    }

    fun setClockYOffset(px: Int) {
        clockYOffsetPx = px
    }

    fun isSuppressActive(): Boolean = suppressActive

    fun restoreStockTranslation() {
        val restore = synchronized(controllerStates) {
            val controller = lastControllerRef.get() ?: return@synchronized null
            val state = controllerStates[controller] ?: return@synchronized null
            val decision = state.currentManagedDecision ?: return@synchronized null
            val x = state.lastStockTranslationX ?: return@synchronized null
            val y = state.lastStockTranslationY ?: return@synchronized null
            StockRestore(controller, x, y, state.managedStep, decision)
        } ?: return
        mainHandler.post {
            runCatching {
                restore.controller.javaClass.getDeclaredMethod(
                    "updateTranslation",
                    Boolean::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType
                ).apply { isAccessible = true }.invoke(
                    restore.controller,
                    true,
                    restore.stockX,
                    restore.stockY
                )
            }.onFailure {
                synchronized(controllerStates) {
                    controllerStates[restore.controller]?.let { state ->
                        if (shouldClearFailedManagedRestore(
                                state.managedStep,
                                state.currentManagedDecision,
                                restore.managedStep,
                                restore.managedDecision
                            )
                        ) {
                            state.managedStep = -1
                            state.currentManagedDecision = null
                            state.pendingManagedDecision = null
                        }
                    }
                }
                HookLogger.w(TAG, "Stock clock restoration failed", it)
            }
        }
    }

    fun advanceManagedPosition(pattern: String, animated: Boolean = true): Boolean {
        val advance = synchronized(controllerStates) {
            val controller = lastControllerRef.get() ?: return@synchronized null
            val state = controllerStates[controller] ?: return@synchronized null
            val geometry = readClockGeometry(controller) ?: return@synchronized null
            val natural = naturalAodTranslation(
                geometry,
                readIntField(controller, "mAodMoveCurrent")
            )
            val stockX = state.lastStockTranslationX ?: natural?.x ?: return@synchronized null
            val stockY = state.lastStockTranslationY ?: natural?.y ?: return@synchronized null
            state.lastStockTranslationX = stockX
            state.lastStockTranslationY = stockY
            val previousStep = state.managedStep
            val previousDecision = state.currentManagedDecision
            val nextStep = previousStep + 1
            val decision = managedAodClockDecision(pattern, nextStep, stockX, stockY, geometry)
                ?: return@synchronized null
            state.managedStep = nextStep
            state.currentManagedDecision = decision
            state.pendingManagedDecision = decision
            ManagedAdvance(
                controller,
                previousStep,
                previousDecision,
                nextStep,
                decision
            )
        } ?: return false
        return runCatching {
            advance.controller.javaClass.getDeclaredMethod(
                "updateTranslation",
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Float::class.javaPrimitiveType
            ).apply { isAccessible = true }.invoke(
                advance.controller,
                animated,
                advance.attemptedDecision.appliedTranslationX,
                advance.attemptedDecision.appliedTranslationY
            )
            true
        }.getOrElse {
            synchronized(controllerStates) {
                controllerStates[advance.controller]?.let { state ->
                    if (shouldRollbackFailedManagedAdvance(
                            state.managedStep,
                            state.currentManagedDecision,
                            advance.attemptedStep,
                            advance.attemptedDecision
                        )
                    ) {
                        state.managedStep = advance.previousStep
                        state.currentManagedDecision = advance.previousDecision
                    }
                    if (state.pendingManagedDecision == advance.attemptedDecision) {
                        state.pendingManagedDecision = null
                    }
                }
            }
            HookLogger.w(TAG, "Managed clock movement failed", it)
            false
        }
    }

    fun restartManagedPattern() {
        synchronized(controllerStates) {
            val controller = lastControllerRef.get() ?: return
            controllerStates[controller]?.apply {
                managedStep = -1
                currentManagedDecision = null
                pendingManagedDecision = null
            }
        }
    }

    fun hasManagedPosition(): Boolean = synchronized(controllerStates) {
        val controller = lastControllerRef.get() ?: return@synchronized false
        controllerStates[controller]?.currentManagedDecision != null
    }

    fun abandonManagedSession() {
        synchronized(controllerStates) {
            val controller = lastControllerRef.get() ?: return
            controllerStates[controller]?.apply {
                managedStep = -1
                currentManagedDecision = null
                pendingManagedDecision = null
            }
        }
    }

    private fun resolveDecision(
        controller: Any,
        requestedX: Int,
        requestedY: Float
    ): PositionResolution? {
        val geometry = readClockGeometry(controller) ?: return null
        return synchronized(controllerStates) {
            val state = controllerStates.getOrPut(controller) { ControllerState() }
            state.pendingManagedDecision?.let { pending ->
                state.pendingManagedDecision = null
                return@synchronized PositionResolution(pending)
            }
            state.lastStockTranslationX = requestedX
            lastControllerRef = WeakReference(controller)
            if (suppressActive) {
                // suppressStockAodContent 直通:系统组件束被抑制为 GONE 后无需再做
                // managed 位移;hold 时冻结库存挂钩位,防系统时钟沉降把布局继续下拖。
                state.managedStep = -1
                state.currentManagedDecision = null
                state.pendingManagedDecision = null
                return@synchronized stockResolution(
                    state, requestedX, requestedY, geometry,
                    freeze = holdStockPosition, zoneChanged = false
                )
            }
            if (AodSurfaceController.isStockWidgetControlActive()) {
                val current = state.currentManagedDecision
                if (current != null) {
                    val refreshed = managedAodClockDecision(
                        AodSurfaceController.managedBurnInPattern(),
                        state.managedStep.coerceAtLeast(0),
                        requestedX,
                        requestedY,
                        geometry
                    )
                    if (refreshed == null) {
                        state.managedStep = -1
                        state.currentManagedDecision = null
                        PositionResolution(
                            stockDecision(requestedX, requestedY, geometry, zoneChanged = true)
                        )
                    } else {
                        val placementChanged = managedAodPlacementChanged(current, refreshed)
                        val resolved = refreshed.copy(zoneChanged = placementChanged)
                        state.currentManagedDecision = resolved
                        PositionResolution(resolved)
                    }
                } else {
                    managedAodClockDecision(
                        AodSurfaceController.managedBurnInPattern(),
                        0,
                        requestedX,
                        requestedY,
                        geometry
                    )?.also {
                        state.managedStep = 0
                        state.currentManagedDecision = it
                    }?.let(::PositionResolution)
                }
            } else {
                // 原厂透传。pinClockVisible 时把 Y 钉在上次锚定位置,令系统时钟在关闭
                // 「实时跟随系统时钟」时不随防烧屏沉降下移,同时保留时钟显示(issue #26)。
                val zoneChanged = state.currentManagedDecision != null
                state.managedStep = -1
                state.currentManagedDecision = null
                state.pendingManagedDecision = null
                return@synchronized stockResolution(
                    state, requestedX, requestedY, geometry,
                    freeze = pinClockVisible, zoneChanged = zoneChanged
                )
            }
        }
    }

    /**
     * 系统时钟库存直通决策。freeze 时把 Y 钉在上次锚定值,令时钟不随请求的防烧屏
     * drift 下移;首次(尚无锚定值)以本次请求值作为锚定起点。
     */
    private fun stockResolution(
        state: ControllerState,
        requestedX: Int,
        requestedY: Float,
        geometry: AodClockGeometry,
        freeze: Boolean,
        zoneChanged: Boolean
    ): PositionResolution {
        // 锚定起点取上次锚定值(防烧屏沉降时时钟被钉住);freeze 时把该原始锚定值保留在
        // lastStockTranslationY,仅对本次应用到时钟的 Y 叠加用户自定义偏移,避免逐帧累积。
        val anchorY = state.lastStockTranslationY ?: requestedY
        state.lastStockTranslationY = anchorY
        val appliedY = if (freeze) anchorY + clockYOffsetPx else requestedY
        return PositionResolution(stockDecision(requestedX, appliedY, geometry, zoneChanged))
    }

    private fun stockDecision(
        requestedX: Int,
        requestedY: Float,
        geometry: AodClockGeometry,
        zoneChanged: Boolean
    ): AodClockPlacementDecision {
        val top = (requestedY + geometry.viewTop).toInt()
        return AodClockPlacementDecision(
            requestedTranslationX = requestedX,
            requestedTranslationY = requestedY,
            appliedTranslationX = requestedX,
            appliedTranslationY = requestedY,
            clockTop = top,
            clockBottom = top + geometry.viewHeight,
            lyricTopSafe = top.coerceAtLeast(0),
            zone = AodSceneZone.STOCK,
            zoneChanged = zoneChanged,
            overridden = false
        )
    }

    private fun readClockGeometry(controller: Any): AodClockGeometry? = runCatching {
        AodClockGeometry(
            mode = readIntField(controller, "mMode"),
            baseTranslationY = readFloatField(controller, "mTranslationY"),
            translationYStep = readFloatField(controller, "mTranslationYStep"),
            viewTop = readIntField(controller, "mViewTop"),
            viewHeight = readIntField(controller, "mViewHeight"),
            translationXStep = readIntField(controller, "mTranslationX")
        )
    }.getOrNull()

    /** Throws when the field is absent anywhere in the hierarchy; every caller reads under a guard. */
    private fun requireField(owner: Any, name: String) =
        hierarchyField(owner.javaClass, name) ?: throw NoSuchFieldException(name)

    private fun readIntField(controller: Any, name: String): Int =
        requireField(controller, name).getInt(controller)

    private fun readFloatField(controller: Any, name: String): Float =
        requireField(controller, name).getFloat(controller)

    private fun readFodSafeBottom(controller: Any?): Int? = runCatching {
        controller ?: return null
        val shown = requireField(controller, "mIsGxzwIconShow").getBoolean(controller)
        val y = requireField(controller, "mGxzwIconY").getInt(controller)
        y.takeIf { shown && it > 0 }
    }.getOrNull()

    private fun captureTargetView(controller: Any) {
        val target = readHierarchyField(controller, "mTargetView") as? View
        val previous = targetViewRef.get()
        if (previous === target) return
        targetViewRef = WeakReference(target)
        HookLogger.i(TAG, "AOD position target captured=${target?.javaClass?.name}")
    }

    private fun effectiveAlpha(view: View): Float {
        var alpha = view.alpha
        var parent = view.parent
        while (parent is View) {
            if (parent.visibility != View.VISIBLE) return 0f
            alpha *= parent.alpha
            if (alpha <= MIN_VISIBLE_ALPHA) return alpha
            parent = parent.parent
        }
        return alpha
    }

    private const val CONTROLLER_CLASS = "com.miui.aod.AODUpdatePositionController"
    private const val DOZE_HOST_CLASS = "com.miui.aod.DozeHost"
    private const val LINKAGE_MODE = 3
    private const val MIN_VISIBLE_ALPHA = 0.02f
    private const val TAG = "AodPositionHook"
}

internal fun shouldRollbackFailedManagedAdvance(
    currentStep: Int,
    currentDecision: AodClockPlacementDecision?,
    attemptedStep: Int,
    attemptedDecision: AodClockPlacementDecision
): Boolean = currentStep == attemptedStep && currentDecision == attemptedDecision

internal fun shouldClearFailedManagedRestore(
    currentStep: Int,
    currentDecision: AodClockPlacementDecision?,
    restoreStep: Int,
    restoreDecision: AodClockPlacementDecision
): Boolean = currentStep == restoreStep && currentDecision == restoreDecision

internal fun shouldAnimateAodPosition(
    requested: Boolean,
    overridden: Boolean,
    placementChanged: Boolean
): Boolean = requested && (!overridden || placementChanged)
