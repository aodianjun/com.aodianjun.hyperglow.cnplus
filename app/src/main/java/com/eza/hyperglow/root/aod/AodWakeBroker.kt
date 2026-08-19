package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

internal object AodWakeBroker {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    // Use WeakReference for the host to avoid leaking DozeTriggers$TriggerReceiver.
    // The DozeHost is managed by MIUI and can be torn down / recreated. Holding a strong
    // reference prevents GC of the entire DozeTriggers tree, which leaves its inner
    // TriggerReceiver (BroadcastReceiver) registered — causing IntentReceiverLeaked.
    // A WeakReference still allows the cached host to survive across short-lived AOD
    // plugin teardowns (the usual case), while letting the GC clean up stale instances.
    private var hostRef: java.lang.ref.WeakReference<Any>? = null
    private var fireAodStateMethod: Method? = null
    private var powerManager: PowerManager? = null
    private var lastRequestElapsedMs = Long.MIN_VALUE
    private var unavailableLogged = false
    private var installRetryCount = 0
    private var installRetryAttempted = false
    private var lyriconWatchdogScheduled = false
    private var lyriconWakeLogged = false
    // 反射读取 SystemUI 内 Lyricon 中心服务(io.github.proify.lyricon.central)的活动播放态,
    // 替代之前基于 AudioManager 的全局音乐检测:仅当 Lyricon 自身报告正在播放时才兜底唤醒 AOD。
    private var lyriconActivePlayers: Any? = null
    private var lyriconActiveIsPlayingField: java.lang.reflect.Field? = null
    private var lyriconResolveLogged = false
    private var lyriconResolveFailedLogged = false

    fun install(module: XposedModule, classLoader: ClassLoader) {
        resolveLyriconState(classLoader)
        val dozeHostClass = runCatching { classLoader.loadClass(DOZE_HOST_CLASS) }.getOrNull()
        // The MIUI AOD doze-trigger class has been relocated across ROM versions (e.g. from the
        // `com.miui.aod.doze` package to the AOSP `com.android.systemui.doze` package in HyperOS
        // DEV). Locate the first candidate whose cached host is the MIUI DozeHost we wake, so a
        // refactor of the package path does not silently break the wake seam.
        val triggersClass = TRIGGER_CLASS_CANDIDATES.asSequence().mapNotNull { triggersName ->
            runCatching { classLoader.loadClass(triggersName) }.getOrNull()
        }.firstOrNull { candidate ->
            HOST_FIELD_NAMES.any { name ->
                runCatching {
                    val field = candidate.getDeclaredField(name)
                    dozeHostClass == null || dozeHostClass.isAssignableFrom(field.type)
                }.getOrDefault(false)
            }
        }
        if (triggersClass == null) {
            HookLogger.w(TAG, "DozeTriggers class not found; will retry on next classloader")
            return
        }
        // Try primary and fallback field names for MIUI version compatibility.
        val hostField = HOST_FIELD_NAMES.firstNotNullOfOrNull { name ->
            runCatching {
                triggersClass.getDeclaredField(name).apply {
                    if (dozeHostClass != null && !dozeHostClass.isAssignableFrom(type)) {
                        throw NoSuchFieldException(name)
                    }
                    isAccessible = true
                }
            }.getOrNull()
        }
        if (hostField == null) {
            HookLogger.w(
                TAG,
                "No host field found (tried ${HOST_FIELD_NAMES.joinToString()}); scheduling retry"
            )
            scheduleInstallRetry(module, classLoader)
            return
        }
        val contextField = runCatching {
            triggersClass.getDeclaredField("mContext").apply { isAccessible = true }
        }.getOrNull()
        if (contextField == null) {
            HookLogger.w(TAG, "mContext field not found; scheduling retry")
            scheduleInstallRetry(module, classLoader)
            return
        }
        if (dozeHostClass == null) {
            HookLogger.w(TAG, "DozeHost class not found; scheduling retry")
            scheduleInstallRetry(module, classLoader)
            return
        }
        // Try primary and fallback method names for fireAodState.
        val fireAodState = FIRE_AOD_STATE_METHOD_NAMES.firstNotNullOfOrNull { name ->
            runCatching {
                dozeHostClass.getDeclaredMethod(
                    name,
                    Boolean::class.javaPrimitiveType,
                    String::class.java
                ).apply { isAccessible = true }
            }.getOrNull()
        }
        if (fireAodState == null) {
            HookLogger.w(
                TAG,
                "No fireAodState method found (tried ${FIRE_AOD_STATE_METHOD_NAMES.joinToString()}); scheduling retry"
            )
            scheduleInstallRetry(module, classLoader)
            return
        }
        if (!hookedClassLoaders.add(classLoader)) return
        installRetryCount = 0
        installRetryAttempted = false
        for (constructor in triggersClass.declaredConstructors) {
            constructor.isAccessible = true
            module.hook(constructor).intercept(
                DozeTriggersConstructorHooker(hostField, contextField, fireAodState)
            )
        }
        HookLogger.i(
            TAG,
            "AOD wake broker hook installed triggers=${triggersClass.name} hostField=${hostField.name} " +
                "fireAodState=${fireAodState.name} constructors=${triggersClass.declaredConstructors.size}"
        )
    }

    private fun scheduleInstallRetry(module: XposedModule, classLoader: ClassLoader) {
        if (installRetryCount >= MAX_INSTALL_RETRIES) {
            HookLogger.e(TAG, "Max install retries ($MAX_INSTALL_RETRIES) reached; giving up")
            return
        }
        installRetryCount++
        installRetryAttempted = true
        val delayMs = INSTALL_RETRY_BASE_DELAY_MS * installRetryCount
        HookLogger.i(TAG, "Scheduling install retry ${installRetryCount}/$MAX_INSTALL_RETRIES in ${delayMs}ms")
        mainHandler.postDelayed({ install(module, classLoader) }, delayMs)
    }

    fun requestWake(signal: Long): Boolean = enqueueWake(signal, "lyrics")

    fun requestPickupWake(): Boolean = enqueueWake(
        signal = SystemClock.elapsedRealtime().coerceAtLeast(1L),
        source = "pickup"
    )

    /**
     * 仅针对 Lyricon 歌词源的「息屏仍在播放」兜底唤醒。
     *
     * 之前的实现用 `AudioManager.isMusicActive()` 探测全局 music stream,只要任意 App 出声且
     * 息屏就强制 AOD,语义过宽。这里改为直接反射 SystemUI 内 Lyricon 中心服务
     * (`io.github.proify.lyricon.central.CentralRuntime`) 的 `ActivePlayerCoordinator`,仅当
     * Lyricon 自己报告「存在活动播放器且正在播放」时才在息屏时重新断言 AOD 显示,避免把
     * 非 Lyricon 的音频会话误判成需要保持 AOD 的会话。
     */
    private fun resolveLyriconState(classLoader: ClassLoader) {
        if (lyriconActivePlayers != null && lyriconActiveIsPlayingField != null) return
        runCatching {
            val centralClass = classLoader.loadClass(LYRICON_CENTRAL_RUNTIME_CLASS)
            val instance = centralClass.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            val coordinator = centralClass.getDeclaredField("activePlayers").apply { isAccessible = true }.get(instance)
            val isPlayingField = coordinator.javaClass.getDeclaredField("activeIsPlaying").apply { isAccessible = true }
            lyriconActivePlayers = coordinator
            lyriconActiveIsPlayingField = isPlayingField
            if (!lyriconResolveLogged) {
                lyriconResolveLogged = true
                HookLogger.i(TAG, "Lyricon central runtime resolved coordinator=${coordinator.javaClass.name}")
            }
        }.onFailure { error ->
            if (!lyriconResolveFailedLogged) {
                lyriconResolveFailedLogged = true
                HookLogger.w(TAG, "Lyricon central runtime not found; Lyricon wake watchdog disabled", error)
            }
        }
    }

    private fun requestLyriconWake(): Boolean = enqueueWake(
        signal = SystemClock.elapsedRealtime().coerceAtLeast(1L),
        source = "lyricon-playback"
    )

    private fun startLyriconWatchdog() {
        if (lyriconWatchdogScheduled) return
        lyriconWatchdogScheduled = true
        HookLogger.i(TAG, "Lyricon watchdog started interval=${LYRICON_POLL_INTERVAL_MS}ms")
        mainHandler.postDelayed(lyriconPoller, LYRICON_POLL_INTERVAL_MS)
    }

    private val lyriconPoller = object : Runnable {
        override fun run() {
            mainHandler.postDelayed(this, LYRICON_POLL_INTERVAL_MS)
            val activePower = powerManager ?: return
            if (activePower.isInteractive) return
            if (!isLyriconPlaying()) {
                lyriconWakeLogged = false
                return
            }
            if (!lyriconWakeLogged) {
                lyriconWakeLogged = true
                HookLogger.i(TAG, "Lyricon active playback while screen off; forcing AOD wake")
            }
            requestLyriconWake()
        }
    }

    private fun isLyriconPlaying(): Boolean {
        val coordinator = lyriconActivePlayers ?: return false
        val isPlayingField = lyriconActiveIsPlayingField ?: return false
        return runCatching { isPlayingField.get(coordinator) as? Boolean ?: false }
            .getOrDefault(false)
    }

    private fun enqueueWake(signal: Long, source: String): Boolean {
        if (signal == 0L || !XiaomiCapabilityResolver.hasCapability(
                XiaomiCapability.AOD_WAKE_BROKER
            )
        ) return false
        val wakeHost = hostRef?.get()
        val method = fireAodStateMethod
        val wakePowerManager = powerManager
        if (wakeHost == null || method == null || wakePowerManager == null ||
            wakePowerManager.isInteractive
        ) {
            if (!unavailableLogged &&
                (wakeHost == null || method == null || wakePowerManager == null)
            ) {
                unavailableLogged = true
                HookLogger.w(TAG, "AOD wake host unavailable source=$source")
            }
            return false
        }
        mainHandler.post {
            val now = SystemClock.elapsedRealtime()
            if (lastRequestElapsedMs != Long.MIN_VALUE &&
                now - lastRequestElapsedMs < MIN_REQUEST_INTERVAL_MS
            ) return@post
            val wakeHost = hostRef?.get()
            val method = fireAodStateMethod
            val wakePowerManager = powerManager
            if (wakeHost == null || method == null || wakePowerManager == null) {
                if (!unavailableLogged) {
                    unavailableLogged = true
                    HookLogger.w(TAG, "AOD wake host unavailable source=$source")
                }
                return@post
            }
            if (wakePowerManager.isInteractive) return@post
            try {
                method.invoke(wakeHost, true, WAKE_REASON)
                lastRequestElapsedMs = now
                HookLogger.i(
                    TAG,
                    "AOD wake dispatched signal=$signal source=$source reason=$WAKE_REASON"
                )
            } catch (error: Exception) {
                (error as? java.lang.reflect.InvocationTargetException)
                    ?.cause
                    ?.let { if (it is Error) throw it }
                HookLogger.w(TAG, "AOD wake dispatch failed", error)
            }
        }
        return true
    }

    private class DozeTriggersConstructorHooker(
        private val hostField: java.lang.reflect.Field,
        private val contextField: java.lang.reflect.Field,
        private val fireAodState: Method
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            try {
                val owner = chain.thisObject ?: return result
                val host = hostField.get(owner) ?: return result
                val context = contextField.get(owner) as? android.content.Context ?: return result
                val powerManager = context.getSystemService(PowerManager::class.java) ?: return result
                AodWakeBroker.hostRef = java.lang.ref.WeakReference(host)
                fireAodStateMethod = fireAodState
                AodWakeBroker.powerManager = powerManager
                unavailableLogged = false
                AodWakeBroker.startLyriconWatchdog()
                HookLogger.i(TAG, "AOD wake host captured class=${host.javaClass.name}")
            } catch (error: Exception) {
                HookLogger.w(TAG, "AOD wake host capture failed", error)
            }
            return result
        }
    }

    private const val DOZE_HOST_CLASS = "com.miui.aod.DozeHost"
    private const val LYRICON_CENTRAL_RUNTIME_CLASS = "io.github.proify.lyricon.central.CentralRuntime"
    // The doze-trigger class has been relocated across ROM versions; AOD wake must match the
    // package actually present in the running SystemUI loader.
    private val TRIGGER_CLASS_CANDIDATES = listOf(
        "com.miui.aod.doze.DozeTriggers",
        "com.android.systemui.doze.DozeTriggers",
        "com.miui.aod.DozeTriggers"
    )
    private const val WAKE_REASON = "reason_keycode_goto"
    private const val MIN_REQUEST_INTERVAL_MS = 750L
    /** Lyricon 播放态轮询间隔:略小于投影 15s 的 stale 窗口,确保在 MIUI 关掉 AOD 之前重新断言。 */
    private const val LYRICON_POLL_INTERVAL_MS = 10_000L
    private const val MAX_INSTALL_RETRIES = 3
    private const val INSTALL_RETRY_BASE_DELAY_MS = 2_000L
    /** Fallback host field names for MIUI version compatibility. */
    private val HOST_FIELD_NAMES = listOf("mHost", "mDozeHost")
    /** Fallback fireAodState method names for MIUI version compatibility. */
    private val FIRE_AOD_STATE_METHOD_NAMES = listOf("fireAodState", "triggerAodState")
    private const val TAG = "AodWakeBroker"
}
