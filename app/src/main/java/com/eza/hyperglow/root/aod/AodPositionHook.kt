package com.eza.hyperglow.root.aod

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.hierarchyField
import com.eza.hyperglow.root.readHierarchyField
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.Collections
import java.util.WeakHashMap

internal data class ControllerState(
    var lastStockTranslationX: Int? = null,
    var lastStockTranslationY: Float? = null,
    var managedStep: Int = -1,
    var currentManagedDecision: AodClockPlacementDecision? = null,
    var pendingManagedDecision: AodClockPlacementDecision? = null
)

/**
 * controller 更替重建时的新状态播种:继承跨 controller 生命周期的时钟锚定
 * (issue #33);无继承值时锚定字段留空,由 stockResolution 以本次请求值锚定。
 */
internal fun seedControllerState(inheritedX: Int?, inheritedY: Float?): ControllerState =
    ControllerState(lastStockTranslationX = inheritedX, lastStockTranslationY = inheritedY)

/**
 * 位置决策路由(纯函数,锁定优先级契约):suppressStockAodContent > 时钟钉住
 * (pinClockVisible,issue #26/#33)> managed 防烧屏位移。返回 true 走 managed
 * 位移;false 走原厂透传(freeze 由调用方按 pin/hold 决定)。
 */
internal fun routesToManagedPath(
    suppressActive: Boolean,
    stockWidgetControlActive: Boolean,
    pinClockVisible: Boolean
): Boolean = !suppressActive && stockWidgetControlActive && !pinClockVisible

/** 冻结时的钉住 Y:锚定值 + 一次性用户偏移(锚定基准不逐帧累积);未冻结原样透传。 */
internal fun pinnedClockAppliedY(
    anchorY: Float,
    offsetPx: Int,
    freeze: Boolean,
    requestedY: Float
): Float = if (freeze) anchorY + offsetPx else requestedY

/**
 * 是否需要把实际渲染视图(targetView.translationY)强制钉到决策的应用 Y。
 *
 * issue #39:锚定/冻结场景下 `updateTranslation` 的入参替换疑似不被效果层采纳,
 * 系统时钟仍随防烧屏在往复区间自由移动,导致 appliedY 已算出但渲染位置不跟随。
 * 仅当 STOCK 时钟被钉住(Y 被改写成非请求值)时才做强制写回——managed 位移与普通
 * 透传(applied==requested)不触碰,避免干扰已有稳定行为。X 恒透传,无需写回。
 */
internal fun needsClockYWriteback(decision: AodClockPlacementDecision): Boolean =
    decision.zone == AodSceneZone.STOCK &&
        decision.appliedTranslationY != decision.requestedTranslationY

/**
 * 系统时钟 STOCK 直通决策(纯函数,锁定 issue #39 契约)。
 *
 * [requestedY] 是本帧系统请求的防烧屏漂移值,[appliedY] 是经 [pinnedClockAppliedY]
 * 钉住后实际下发的值,二者必须分别落到 decision 的 requested/applied 字段。此前把
 * appliedY 同时写进两个字段,使 [needsClockYWriteback] 恒为 false,冻结时渲染层写回
 * (`pinRenderedClockY`)永不触发——appliedY 虽已算出,效果层仍随防烧屏自由移动(issue #39)。
 * 未冻结时 appliedY == requestedY,行为不变。
 */
internal fun stockClockDecision(
    requestedX: Int,
    requestedY: Float,
    appliedY: Float,
    geometry: AodClockGeometry,
    zoneChanged: Boolean
): AodClockPlacementDecision {
    val top = (appliedY + geometry.viewTop).toInt()
    return AodClockPlacementDecision(
        requestedTranslationX = requestedX,
        requestedTranslationY = requestedY,
        appliedTranslationX = requestedX,
        appliedTranslationY = appliedY,
        clockTop = top,
        clockBottom = top + geometry.viewHeight,
        lyricTopSafe = top.coerceAtLeast(0),
        zone = AodSceneZone.STOCK,
        zoneChanged = zoneChanged,
        overridden = false
    )
}

internal object AodPositionHook {
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
        val update = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(CONTROLLER_CLASS, "updateTranslation", "boolean", "int", "float")
        ) ?: return
        val updatePosition = SymbolResolver.resolveMethod(
            classLoader,
            FEATURE_ID,
            SymbolRequest.method(DOZE_HOST_CLASS, "updatePosition")
        ) ?: return
        if (!hookedClassLoaders.add(classLoader)) return
        HookRegistry.hook(module, FEATURE_ID, update, PositionHooker)
        HookRegistry.hook(module, FEATURE_ID, updatePosition, PositionCompletionHooker)
        HookLogger.i(TAG, "AOD position hook installed")
    }

    fun observeAodRoot(root: Any) {
        val controller = readHierarchyField(root, "mPositionController") ?: return
        synchronized(controllerStates) {
            controllerStateFor(controller)
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
            if (decision != null && needsClockYWriteback(decision)) {
                // issue #39/#66:updateTranslation 的入参替换与 targetView 写回均未被效果层
                // 采纳(controller.mTranslationY 恒为既有值),时钟仍随防烧屏位移。这里把
                // 权威字段 controller.mTranslationY 与渲染视图 translationY 一并钉到应用值,
                // 并回读确认写入落到显示层,使锚定/冻结真正生效。
                if (controller != null) logStockClockReadback(controller, decision)
                pinRenderedClockY(controller, decision.appliedTranslationY, decision.requestedTranslationY)
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

    /**
     * 跨 controller 生命周期继承的时钟锚定(issue #33):controllerStates 以弱引用
     * controller 为 key,controller 更替即丢锚,新状态会以"当前(可能已下移)请求值"
     * 就地重锚,防下移从此失效。锚定值镜像保存在本单例中,controller 重建时继承;
     * AOD 真正会话结束(显示完全关闭)时清零 —— 跨会话重新锚定到当前系统位置仍
     * 是设计行为。
     *
     * 注意:清零必须绑定"真实会话结束"(AOD 显示 OFF),而非每次 surface attach/detach
     * 重建(旋转、LinkageTransition 会触发高频重建)。同一 AOD 会话内的重建若清锚,
     * 锚点会落到已漂移的请求值,防下移失效、旋转回竖屏回不到原位(issue #36)。
     */
    @Volatile
    private var inheritedAnchorX: Int? = null
    @Volatile
    private var inheritedAnchorY: Float? = null
    private var lastPinnedLogKey = ""
    private var lastManagedPinSkipLogged = false
    private var lastRenderedPinKey = ""
    private var lastStockReadbackKey = ""
    private var lastPinPostFrameKey = ""

    private var lastAnchorResetElapsedMs = Long.MIN_VALUE

    /** 仅当 AOD 真正退出(显示完全关闭且持续过脉冲窗口,见 AodPowerCoordinator)时清空跨 controller 锚定。 */
    fun resetStockAnchor(cause: String) {
        inheritedAnchorX = null
        inheritedAnchorY = null
        lastPinnedLogKey = ""
        lastManagedPinSkipLogged = false
        lastRenderedPinKey = ""
        lastStockReadbackKey = ""
        // issue #62:记录距上次清锚的间隔,脉冲抖动触发的高频清锚可直接从间隔暴露。
        val now = SystemClock.elapsedRealtime()
        val interval = if (lastAnchorResetElapsedMs == Long.MIN_VALUE) {
            "first"
        } else {
            "${now - lastAnchorResetElapsedMs}ms"
        }
        lastAnchorResetElapsedMs = now
        HookLogger.i(TAG, "Stock anchor reset ($cause, interval=$interval)")
    }

    /**
     * 取 controller 对应状态;controller 首次出现(或被 GC 后重建)时播种继承锚定并
     * 记录一次,替代裸 getOrPut 的静默重置(issue #33 建议三)。
     */
    private fun controllerStateFor(controller: Any): ControllerState {
        controllerStates[controller]?.let { return it }
        val seeded = seedControllerState(inheritedAnchorX, inheritedAnchorY)
        controllerStates[controller] = seeded
        HookLogger.i(
            TAG,
            "Position state rebuilt; anchor " +
                (if (inheritedAnchorY != null) "inherited y=$inheritedAnchorY" else "seeded from request")
        )
        return seeded
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
        // 时钟钉住激活时 managed 位移被锚定优先级接管(issue #33 建议二):
        // 调度器按"managed 不可用"处理,退避后回退到原厂几何。
        if (pinClockVisible) {
            if (!lastManagedPinSkipLogged) {
                lastManagedPinSkipLogged = true
                HookLogger.i(TAG, "Managed advance skipped: clock pin active")
            }
            return false
        }
        lastManagedPinSkipLogged = false
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
            // managed 建立决策时同步镜像锚定(issue #33 建议一)。
            inheritedAnchorX = stockX
            inheritedAnchorY = stockY
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
            val state = controllerStateFor(controller)
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
            if (routesToManagedPath(
                    suppressActive = false,
                    stockWidgetControlActive = AodSurfaceController.isStockWidgetControlActive(),
                    pinClockVisible = pinClockVisible
                )
            ) {
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
                            stockClockDecision(
                                requestedX,
                                requestedY,
                                requestedY,
                                geometry,
                                zoneChanged = true
                            )
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
                // 原厂透传(或 pin 激活时对 managed 的接管)。pinClockVisible 时把 Y 钉在
                // 上次锚定位置,令系统时钟在关闭「实时跟随系统时钟」时不随防烧屏沉降下移,
                // 同时保留时钟显示(issue #26);pin 与 managed 位移互斥,锚定优先
                // (issue #33 建议二),pin 释放后 managed 从 step 0 重排。
                if (pinClockVisible && state.currentManagedDecision != null) {
                    HookLogger.i(TAG, "Managed displacement bypassed by clock pin")
                }
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
        // 锚定镜像到跨 controller 生命周期持有者(issue #33 建议一)。
        inheritedAnchorX = state.lastStockTranslationX
        inheritedAnchorY = anchorY
        val appliedY = pinnedClockAppliedY(anchorY, clockYOffsetPx, freeze, requestedY)
        if (freeze && appliedY != requestedY) {
            // 钉住生效现场,仅变化时记录(建议三):请求 Y 持续漂移而应用 Y 被钉住。
            val key = "a=" + Math.round(anchorY) + " o=" + clockYOffsetPx
            if (key != lastPinnedLogKey) {
                lastPinnedLogKey = key
                HookLogger.i(
                    TAG,
                    "Stock clock pinned anchorY=$anchorY appliedY=$appliedY " +
                        "requestedY=$requestedY offset=$clockYOffsetPx"
                )
            }
        }
        return PositionResolution(
            stockClockDecision(requestedX, requestedY, appliedY, geometry, zoneChanged)
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

    /**
     * 按声明类型把数值写进字段(issue #66 根因:mTranslationY 在设备固件里是 int,
     * setFloat 会抛 IllegalArgumentException,写入从未生效)。int/long 取整后窄化写,
     * float/double 原样写,其余类型返回 false 交由调用方记日志。
     */
    private fun writeNumberField(controller: Any, name: String, value: Float): Boolean {
        val field = requireField(controller, name)
        return when (field.type) {
            java.lang.Integer.TYPE -> {
                field.setInt(controller, Math.round(value)); true
            }
            java.lang.Long.TYPE -> {
                field.setLong(controller, Math.round(value).toLong()); true
            }
            java.lang.Float.TYPE -> {
                field.setFloat(controller, value); true
            }
            java.lang.Double.TYPE -> {
                field.setDouble(controller, value.toDouble()); true
            }
            else -> false
        }
    }

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

    /**
     * 把系统时钟钉到锚点(issue #66 根治)。
     *
     * 系统防烧屏定位公式(反编译 AODUpdatePositionController 确认):
     *     f = mTranslationYStep * step - mViewTop + mTranslationY
     *     targetView.setTranslationY(f)
     * 其中 step 即 updateTranslation(z, i, f) 的步进索引 i,随整分钟位移递增;
     * mTranslationY 是设备固件里的 int 字段(此前 setFloat 抛 IllegalArgumentException,
     * 写入从未生效,日志 controllerY=231.0->null 可证)。
     *
     * 要让时钟锁定在锚点 appliedY,只需令 f = appliedY,反解出应写入的权威字段:
     *     mTranslationY = appliedY + mViewTop - mTranslationYStep * step
     * 这样系统下一帧自算的 f 恒等于 appliedY,无需再事后改 view,也绕开了「hook 在系统
     * 下发位移后才纠正、天然落后一拍」的问题。translationY 同步写一次作为即时呈现
     * (本帧系统在 proceed 时已按旧 f 画过),后续帧由权威字段驱动。
     *
     * 写入按声明类型分派(int/long/float/double),避免 ROM 改类型时静默失败;写后同线程
     * 回读 + 跳过一帧回读,确认落到显示层。
     *
     * 安全性:pin 激活时 managed 位移互斥绕过(routesToManagedPath / advanceManagedPosition
     * 均因 pinClockVisible 提前 return),mTranslationY(geometry.baseTranslationY)仅被
     * managed 路径读取,故 pin 期间改写它不会干扰 managed 逻辑。
     */
    private fun pinRenderedClockY(
        controller: Any?,
        appliedY: Float,
        requestedY: Float,
        burnInStep: Int,
        geometry: AodClockGeometry?
    ) {
        val target = targetViewRef.get() ?: return
        val pin = Runnable {
            val live = targetViewRef.get() ?: return@Runnable
            val beforeView = live.translationY
            var beforeController: Float? = null
            var afterController: Float? = null
            var fieldDesc: String? = null
            if (controller != null) {
                runCatching {
                    val field = requireField(controller, "mTranslationY")
                    beforeController = runCatching { field.getFloat(controller) }.getOrNull()
                    // 反解 f = appliedY 所需的 mTranslationY 基准值;缺几何/步进时退化为
                    // 直接写 appliedY(对应 step*step 项为 0 的旧行为)。
                    val baseTarget = if (geometry != null) {
                        appliedY + geometry.viewTop - geometry.translationYStep * burnInStep
                    } else {
                        appliedY
                    }
                    val wrote = writeNumberField(controller, "mTranslationY", baseTarget)
                    afterController = runCatching { field.getFloat(controller) }.getOrNull()
                    fieldDesc = field.declaringClass.name + "#" + field.name +
                        " type=" + field.type.name +
                        " final=" + java.lang.reflect.Modifier.isFinal(field.modifiers) +
                        " base=" + Math.round(baseTarget) +
                        " step=" + burnInStep +
                        " write=" + (if (!wrote) "unsupported" else if (afterController != null &&
                            kotlin.math.abs(afterController!! - baseTarget) < 0.5f
                        ) "ok" else "noop")
                }.onFailure { fieldDesc = "err=" + it.javaClass.simpleName + ":" + it.message }
            }
            if (live.translationY != appliedY) live.translationY = appliedY
            // 与写入同一线程立即回读,消除跨线程时序误判(写发生在主线程,回读亦须同线程)。
            val afterView = live.translationY
            val key = "y=" + Math.round(appliedY) + " c=" + afterController +
                " v=" + Math.round(afterView) + " r=" + Math.round(requestedY)
            if (key != lastRenderedPinKey) {
                lastRenderedPinKey = key
                HookLogger.i(
                    TAG,
                    "Rendered clock pinned appliedY=$appliedY requestedY=$requestedY " +
                        "controllerY=$beforeController->$afterController " +
                        "viewY=$beforeView->$afterView " +
                        "y=${live.y} top=${live.top} " +
                        "topMargin=${(live.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin} " +
                        "paddingTop=${live.paddingTop} " +
                        "field=$fieldDesc view=${live.javaClass.name}"
                )
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            pin.run()
        } else {
            mainHandler.post(pin)
        }
        // 跳过一帧 layout 后于主线程回读:确认权威字段驱动的 f 是否锁定在 appliedY。
        mainHandler.postDelayed({
            logPinPostFrame(controller, appliedY, requestedY)
        }, PIN_POST_FRAME_DELAY_MS)
    }

    /**
     * pin 写回后跳过一帧,在主线程回读显示层各候选定位量(issue #66 建议一/二)。
     *
     * 上一版在 hook 调用线程回读,既无法排除「跨线程时序」也无法观测「下一帧 layout 是否
     * 覆盖」。此处在写回完成 + 越过至少一帧 layout 后,同线程读 translationY/y/top/
     * topMargin/paddingTop 与 mTranslationY:
     *  - translationY 已被改回非 appliedY → 系统在下帧覆盖,需换写入目标(top/topMargin);
     *  - translationY 仍是 appliedY 但视觉在新位置 → translationY 不参与最终定位,权威在 top;
     *  - mTranslationY 与写入值的关系 → 区分「反射写入无效」与「写入后被覆盖」。
     * 仅值变化时记录,避免整分钟位移周期外刷屏。
     */
    private fun logPinPostFrame(controller: Any?, appliedY: Float, requestedY: Float) {
        val live = targetViewRef.get() ?: return
        val stored = controller?.let {
            runCatching { readFloatField(it, "mTranslationY") }.getOrNull()
        }
        val topMargin = (live.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin
        val key = "t=" + Math.round(live.translationY) +
            " y=" + Math.round(live.y) +
            " top=" + live.top +
            " tm=" + topMargin +
            " c=" + stored
        if (key == lastPinPostFrameKey) return
        lastPinPostFrameKey = key
        HookLogger.i(
            TAG,
            "Rendered clock post-frame appliedY=$appliedY requestedY=$requestedY " +
                "translationY=${live.translationY} y=${live.y} top=${live.top} " +
                "topMargin=$topMargin paddingTop=${live.paddingTop} " +
                "controllerY=$stored attached=${live.isAttachedToWindow}"
        )
    }

    /**
     * issue #39 建议一:`proceed` 之后回读 controller 的 `mTranslationY`,确认入参替换是否
     * 被系统采纳。此前日志只能看到「下发了什么」,看不到「系统实际存了什么」,是最大盲区。
     * 仅 STOCK 被钉住时回读,取值变化时记录一次,避免刷屏。
     */
    private fun logStockClockReadback(controller: Any, decision: AodClockPlacementDecision) {
        val stored = runCatching { readFloatField(controller, "mTranslationY") }.getOrNull() ?: return
        val key = "f=" + Math.round(stored) +
            " a=" + Math.round(decision.appliedTranslationY) +
            " r=" + Math.round(decision.requestedTranslationY)
        if (key != lastStockReadbackKey) {
            lastStockReadbackKey = key
            HookLogger.i(
                TAG,
                "Stock clock read-back controller mTranslationY=$stored " +
                    "appliedY=${decision.appliedTranslationY} requestedY=${decision.requestedTranslationY}"
            )
        }
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
    // pin 写回后跳过一帧 layout 再回读(issue #66 建议一):略大于 60fps 一帧,
    // 使「系统是否在下一帧 layout 覆盖 translationY」可被观测。
    private const val PIN_POST_FRAME_DELAY_MS = 32L
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
