package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import com.eza.hyperglow.aod.MAX_AOD_BRIGHTNESS
import com.eza.hyperglow.aod.MIN_AOD_BRIGHTNESS
import com.eza.hyperglow.root.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * AOD 亮度 hook(上游 b0254d5)。
 *
 * 小米在 DOZE_AOD 状态下会把 doze 亮度压到极低(如 1/255),歌词在这种亮度下不可读。
 * 本 hook 拦截 [com.miui.aod.doze.MiuiDozeBrightnessTimeoutAdapter.setDozeScreenBrightness],
 * 当歌词 guard 激活且处于精确 DOZE_AOD 状态时,把低于可读亮度的请求钳制到可读值;
 * 其余状态(退出 AOD、脉冲、暂停)保持小米原有亮度权威,不做干预。
 */
object AodBrightnessHook {
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val adapterClass = runCatching { classLoader.loadClass(ADAPTER_CLASS) }.getOrNull()
            ?: return
        val controllerClass = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull()
            ?: return
        val stateClass = runCatching { classLoader.loadClass(STATE_CLASS) }.getOrNull()
            ?: return
        val adapterMethod = adapterClass.getDeclaredMethod(
            SET_BRIGHTNESS_METHOD,
            Int::class.javaPrimitiveType
        ).apply { isAccessible = true }
        val transitionMethod = controllerClass.getDeclaredMethod(
            TRANSITION_METHOD,
            stateClass,
            stateClass
        ).apply { isAccessible = true }
        val readableBrightness = resolveBrightnessOn(classLoader)
        if (!hookedClassLoaders.add(classLoader)) return

        AodBrightnessController.registerTarget(readableBrightness)
        module.deoptimize(adapterMethod)
        module.hook(adapterMethod).intercept(BrightnessHooker)
        module.deoptimize(transitionMethod)
        module.hook(transitionMethod).intercept(TransitionHooker)
        val constructorHooker = AdapterConstructorHooker(adapterMethod)
        for (constructor in adapterClass.declaredConstructors) {
            constructor.isAccessible = true
            module.hook(constructor).intercept(constructorHooker)
        }
        HookLogger.i(
            TAG,
            "AOD brightness hooks installed constructors=${adapterClass.declaredConstructors.size} " +
                "methods=2 target=$readableBrightness"
        )
    }

    /** 从 CommonUtils.BRIGHTNESS_ON 读取系统认可的可读亮度,取不到时退回 255。 */
    private fun resolveBrightnessOn(classLoader: ClassLoader): Int {
        val value = runCatching {
            classLoader.loadClass(COMMON_UTILS_CLASS)
                .getDeclaredField(BRIGHTNESS_ON_FIELD)
                .apply { isAccessible = true }
                .get(null)
        }.getOrNull()
        if (value is Int && value > 0) return value
        HookLogger.w(
            TAG,
            "CommonUtils.$BRIGHTNESS_ON_FIELD unavailable or non-positive; " +
                "using fallback=$FALLBACK_BRIGHTNESS_ON"
        )
        return FALLBACK_BRIGHTNESS_ON
    }

    private class AdapterConstructorHooker(private val brightnessMethod: Method) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            chain.thisObject?.let { adapter ->
                AodBrightnessController.registerAdapter(adapter, brightnessMethod)
            }
            return result
        }
    }

    private object TransitionHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            AodBrightnessController.noteDozeState(chain.args.getOrNull(1)?.toString())
            return chain.proceed()
        }
    }

    private object BrightnessHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (chain.args.size != 1) return chain.proceed()
            val requested = chain.args.firstOrNull() as? Int ?: return chain.proceed()
            val resolved = AodBrightnessController.resolveBrightnessRequest(requested)
            return chain.proceed(arrayOf(resolved))
        }
    }

    private const val ADAPTER_CLASS = "com.miui.aod.doze.MiuiDozeBrightnessTimeoutAdapter"
    private const val CONTROLLER_CLASS = "com.miui.aod.doze.MiuiDozeScreenBrightnessController"
    private const val STATE_CLASS = "com.miui.aod.doze.DozeMachine\$State"
    private const val COMMON_UTILS_CLASS = "com.miui.aod.utils.CommonUtils"
    private const val SET_BRIGHTNESS_METHOD = "setDozeScreenBrightness"
    private const val TRANSITION_METHOD = "transitionTo"
    private const val BRIGHTNESS_ON_FIELD = "BRIGHTNESS_ON"
    private const val FALLBACK_BRIGHTNESS_ON = 255
    private const val TAG = "AodBrightnessHook"
}

object AodBrightnessController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var guardActive = false
    private var boostEnabled = true
    private var dozeStateName: String? = null
    private var activeAdapter = WeakReference<Any>(null)
    private var adapterSetBrightness = WeakReference<Method>(null)
    private var lastRawBrightness: Int? = null
    private var readableBrightness = DEFAULT_READABLE_BRIGHTNESS
    private var brightnessOverrideEnabled = false
    private var brightnessOverrideLevel = DEFAULT_READABLE_BRIGHTNESS
    private var pendingResubmit: Runnable? = null

    @Synchronized
    fun registerAdapter(adapter: Any, brightnessMethod: Method) {
        pendingResubmit?.let(mainHandler::removeCallbacks)
        pendingResubmit = null
        activeAdapter = WeakReference(adapter)
        adapterSetBrightness = WeakReference(brightnessMethod)
        lastRawBrightness = null
        dozeStateName = null
        HookLogger.i(TAG, "AOD brightness adapter captured")
    }

    @Synchronized
    fun registerTarget(target: Int) {
        if (target <= 0) return
        readableBrightness = target
    }

    /** App 端「AOD 亮度增强」开关,随 CompiledCustomization 下发;关闭后原样透传系统亮度。 */
    @Synchronized
    fun setBoostEnabled(enabled: Boolean) {
        if (boostEnabled == enabled) return
        boostEnabled = enabled
        HookLogger.i(TAG, "AOD brightness boost enabled=$enabled")
        // 开关变化后重放最近一次原始请求,让新策略立即对当前 doze 亮度生效。
        scheduleResubmitLocked(requestedGuardState = guardActive)
    }

    /**
     * 自定义亮度覆写:enabled=true 时把 doze 亮度钳到 [level];false 时退回按场景自动化。
     * 仅当 boost(总开关)开启时才有意义,但覆写状态独立记录,便于总开关与模式分别下发。
     */
    @Synchronized
    fun setBrightnessOverride(enabled: Boolean, level: Int) {
        val normalizedLevel = level.coerceIn(MIN_AOD_BRIGHTNESS, MAX_AOD_BRIGHTNESS)
        if (brightnessOverrideEnabled == enabled && brightnessOverrideLevel == normalizedLevel) {
            return
        }
        brightnessOverrideEnabled = enabled
        brightnessOverrideLevel = normalizedLevel
        HookLogger.i(
            TAG,
            "AOD brightness override enabled=$enabled level=$normalizedLevel"
        )
        scheduleResubmitLocked(requestedGuardState = guardActive)
    }

    /**
     * 丢弃属于本代 module class loader 的待重放任务,避免热重载退役旧 loader 后任务跳过
     * 新代码直接触发(上游 HookRegistry hot-reload 配套)。
     */
    @Synchronized
    fun cancelPendingForReload() {
        pendingResubmit?.let(mainHandler::removeCallbacks)
        pendingResubmit = null
    }

    @Synchronized
    fun noteDozeState(stateName: String?) {
        val changed = dozeStateName != stateName
        dozeStateName = stateName
        if (changed && stateName == "DOZE_AOD" && guardActive) {
            // 小米可能在状态切换完成前就发出首个低亮度请求。进入精确 DOZE_AOD 后重放该原始
            // 请求,让第一帧 AOD 歌词以可读亮度呈现(此时 dozeStateName 已知,可正确钳制)。
            scheduleResubmitLocked(requestedGuardState = true)
        }
    }

    @Synchronized
    fun resolveBrightnessRequest(requested: Int): Int {
        lastRawBrightness = requested
        val resolved = resolveAodBrightnessRequest(
            requestedBrightness = requested,
            readableBrightness = readableBrightness,
            lyricGuardActive = guardActive,
            dozeStateName = dozeStateName,
            boostEnabled = boostEnabled,
            brightnessOverrideEnabled = brightnessOverrideEnabled,
            brightnessOverrideLevel = brightnessOverrideLevel
        )
        if (resolved != requested) {
            HookLogger.i(
                TAG,
                "AOD brightness clamped raw=$requested resolved=$resolved state=$dozeStateName"
            )
        }
        return resolved
    }

    @Synchronized
    fun setLyricGuardActive(active: Boolean) {
        if (guardActive == active) return
        guardActive = active
        scheduleResubmitLocked(active)
    }

    private fun scheduleResubmitLocked(requestedGuardState: Boolean) {
        pendingResubmit?.let(mainHandler::removeCallbacks)
        pendingResubmit = null
        val raw = lastRawBrightness
        val adapter = activeAdapter.get()
        val method = adapterSetBrightness.get()
        if (raw == null || raw < 0 || adapter == null || method == null) return
        val adapterRef = WeakReference(adapter)
        val resubmit = object : Runnable {
            override fun run() {
                try {
                    val currentAdapter = adapterRef.get()
                    val allowed = synchronized(this@AodBrightnessController) {
                        pendingResubmit === this &&
                            guardActive == requestedGuardState &&
                            currentAdapter != null &&
                            activeAdapter.get() === currentAdapter &&
                            adapterSetBrightness.get() === method
                    }
                    if (!allowed || currentAdapter == null) return
                    try {
                        method.invoke(currentAdapter, raw)
                        HookLogger.i(
                            TAG,
                            "AOD brightness raw request re-submitted raw=$raw " +
                                "guard=$requestedGuardState"
                        )
                    } catch (error: Exception) {
                        (error as? InvocationTargetException)?.cause
                            ?.let { if (it is Error) throw it }
                        HookLogger.w(TAG, "AOD brightness re-submit failed", error)
                    }
                } finally {
                    synchronized(this@AodBrightnessController) {
                        if (pendingResubmit === this) pendingResubmit = null
                    }
                }
            }
        }
        pendingResubmit = resubmit
        mainHandler.post(resubmit)
    }

    private const val DEFAULT_READABLE_BRIGHTNESS = 255
    private const val TAG = "AodBrightnessController"
}

internal fun resolveAodBrightnessRequest(
    requestedBrightness: Int,
    readableBrightness: Int,
    lyricGuardActive: Boolean,
    dozeStateName: String?,
    boostEnabled: Boolean = true,
    brightnessOverrideEnabled: Boolean = false,
    brightnessOverrideLevel: Int = MAX_AOD_BRIGHTNESS
): Int {
    // 总开关关闭:原样透传系统亮度。
    if (!boostEnabled) return requestedBrightness
    val eligible = lyricGuardActive &&
        dozeStateName == "DOZE_AOD" &&
        requestedBrightness > 0 &&
        readableBrightness > 0
    if (!eligible) return requestedBrightness
    return if (brightnessOverrideEnabled) {
        brightnessOverrideLevel.coerceIn(MIN_AOD_BRIGHTNESS, MAX_AOD_BRIGHTNESS)
    } else {
        requestedBrightness.coerceAtLeast(readableBrightness)
    }
}
