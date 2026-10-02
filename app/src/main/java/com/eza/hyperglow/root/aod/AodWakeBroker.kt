package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

/**
 * 唤醒请求为何无法服务。三个缺引用的状态是值得具名入日志的故障;
 * [INTERACTIVE] 是常规抑制,永远不会走到上报路径(上游 99ba119)。
 */
internal enum class AodWakeAvailability(val wireValue: String) {
    READY("ready"),
    INTERACTIVE("interactive"),
    NO_HOST("no_host"),
    NO_METHOD("no_method"),
    NO_POWER_MANAGER("no_power_manager");

    val isFault: Boolean get() = this != READY && this != INTERACTIVE
}

/**
 * 缺引用先于交互性上报:在死宿主上服务的请求是缺陷,而亮屏被抑制的请求不是。
 * 故障排在前,才让「一个原因」描述每一次被拒的全部理由。
 */
internal fun resolveAodWakeAvailability(
    hostCaptured: Boolean,
    methodResolved: Boolean,
    powerManagerResolved: Boolean,
    interactive: Boolean
): AodWakeAvailability = when {
    !hostCaptured -> AodWakeAvailability.NO_HOST
    !methodResolved -> AodWakeAvailability.NO_METHOD
    !powerManagerResolved -> AodWakeAvailability.NO_POWER_MANAGER
    interactive -> AodWakeAvailability.INTERACTIVE
    else -> AodWakeAvailability.READY
}

/**
 * broker 持有的引用是否值得替换。活宿主只在实例不同时收编,重复同一实例不再重复记日志;
 * null 永远不清掉系统仍在使用的引用。
 */
internal fun shouldAdoptAodWakeReference(
    current: Any?,
    candidate: Any?
): Boolean = candidate != null && candidate !== current

/**
 * 同一原因只上报一次,新的不同原因不被旧原因压掉。此前的单一全局闩锁只报了第一个故障,
 * 「先丢宿主再丢电源管理器」与「从来就没有宿主」在日志里长得一模一样。
 */
internal fun shouldReportAodWakeUnavailable(
    reason: AodWakeAvailability,
    lastReported: AodWakeAvailability?
): Boolean = reason.isFault && reason != lastReported

/**
 * 拒绝随附的安装原因。只有「缺宿主」有安装故事:另两个故障说的是 broker 手里的引用,
 * 安装无法影响。
 */
internal fun aodWakeUnavailableDetail(
    reason: AodWakeAvailability,
    installSkips: String
): String = if (reason == AodWakeAvailability.NO_HOST) {
    " install=" + installSkips.ifEmpty { "none" }
} else {
    ""
}

internal object AodWakeBroker {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )
    private val recordedInstallSkips = Collections.synchronizedSet(mutableSetOf<String>())

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
    // 按「不同原因」各报一次的去重闩锁(替代原单一布尔:第二个故障曾被静默)。
    private var unavailableReason: AodWakeAvailability? = null
    private var installRetryCount = 0
    private var installRetryAttempted = false
    private const val FEATURE_ID = "aod-wake"
    private var lyriconWatchdogScheduled = false
    private var lyriconWakeLogged = false
    // 反射读取 SystemUI 内 Lyricon 中心服务(io.github.proify.lyricon.central)的活动播放态,
    // 替代之前基于 AudioManager 的全局音乐检测:仅当 Lyricon 自身报告正在播放时才兜底唤醒 AOD。
    private var lyriconActivePlayers: Any? = null
    private var lyriconActiveIsPlayingField: java.lang.reflect.Field? = null
    private var lyriconResolveLogged = false
    private var lyriconResolveFailedLogged = false
    private var lyriconClassLoader: ClassLoader? = null
    private var lastLyriconResolveAttemptAtElapsedMs = Long.MIN_VALUE
    // isLyriconPlaybackActive() 会在快照/看门狗热路径上被调用,反射读字段本身很便宜,
    // 但仍做个极短的读缓存,避免高频快照时反复进入反射路径。
    private var lyriconPlayingCached = false
    private var lyriconPlayingCachedAtElapsedMs = Long.MIN_VALUE

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
            noteInstallSkip("triggers_class")
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
            noteInstallSkip("m_host")
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
            noteInstallSkip("m_context")
            HookLogger.w(TAG, "mContext field not found; scheduling retry")
            scheduleInstallRetry(module, classLoader)
            return
        }
        if (dozeHostClass == null) {
            noteInstallSkip("doze_host")
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
            noteInstallSkip("fire_aod_state")
            HookLogger.w(
                TAG,
                "No fireAodState method found (tried ${FIRE_AOD_STATE_METHOD_NAMES.joinToString()}); scheduling retry"
            )
            scheduleInstallRetry(module, classLoader)
            return
        }
        // 安装时即登记唤醒方法,而不是只经构造器接缝:早于本 hook 创建的插件实例永远不跑
        // 构造器,只靠构造器登记的唤醒方法会在此后整个进程里恒为 null,收编也无从服务
        // (上游 99ba119)。
        noteWakeMethod(fireAodState)
        if (!hookedClassLoaders.add(classLoader)) return
        installRetryCount = 0
        installRetryAttempted = false
        for (constructor in triggersClass.declaredConstructors) {
            constructor.isAccessible = true
            HookRegistry.hook(
                module,
                FEATURE_ID,
                constructor,
                DozeTriggersConstructorHooker(hostField, contextField)
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

    /**
     * 记录一次安装为何退场,但不立刻评判:`install` 按 classloader 逐个跑,健康 ROM 上
     * 看不到 AOD dex 的 loader 退场、同一 dex 上稍后的 loader 成功并捕获宿主——看到就报
     * 是假警报。这份记录只在「真的因无宿主被拒」时读取,那才是答案有用的时刻,并随拒绝
     * 原因一并上报(上游 99ba119)。
     */
    private fun noteInstallSkip(reason: String) {
        if (recordedInstallSkips.add(reason)) {
            HookLogger.i(TAG, "AOD wake broker install skipped reason=$reason")
        }
    }

    @Synchronized
    private fun installSkipSummary(): String =
        if (recordedInstallSkips.isEmpty()) {
            "none"
        } else {
            recordedInstallSkips.sorted().joinToString("+")
        }

    /**
     * 由进程上下文预置电源管理器。在 SystemUI `onCreate` 播种,交互性判断不再依赖
     * 「观测到 AOD 插件构造器」——Lyricon 看门狗在从未见到 DozeTriggers 实例的进程里
     * 也能工作(上游 99ba119)。
     */
    @Synchronized
    fun observeContext(context: android.content.Context) {
        if (powerManager != null) return
        powerManager = runCatching {
            context.getSystemService(PowerManager::class.java)
        }.getOrNull()
    }

    /**
     * 收编由既有 hook 移交给 broker 的活 `DozeHost`。构造器接缝只在 hook 安装之后创建的
     * 插件实例上触发;ROM 预加载或复用其 AOD 插件时,唤醒路径会在整个进程里死掉,而
     * wake-broker capability 仍能从符号解析出来。系统自己在调用的实例即已被使用验证,
     * 它填入与构造器接缝同一份引用(上游 99ba119;CN+ 保持 WeakReference 以免
     * DozeTriggers$TriggerReceiver 泄漏)。
     */
    @Synchronized
    fun adoptHost(candidate: Any?, source: String) {
        if (!shouldAdoptAodWakeReference(hostRef?.get(), candidate)) return
        val live = candidate ?: return
        hostRef = java.lang.ref.WeakReference(live)
        unavailableReason = null
        HookLogger.i(
            TAG,
            "AOD wake host captured class=${live.javaClass.name} source=$source"
        )
    }

    @Synchronized
    private fun noteWakeMethod(method: Method) {
        if (fireAodStateMethod == null) fireAodStateMethod = method
    }

    @Synchronized
    private fun readState(): AodWakeState = AodWakeState(
        host = hostRef?.get(),
        method = fireAodStateMethod,
        powerManager = powerManager
    )

    private fun resolveAvailability(state: AodWakeState): AodWakeAvailability =
        resolveAodWakeAvailability(
            hostCaptured = state.host != null,
            methodResolved = state.method != null,
            powerManagerResolved = state.powerManager != null,
            interactive = state.powerManager?.isInteractive == true
        )

    /**
     * 同一原因只报一次,后来的不同原因仍要报。单一全局闩锁丢掉第二个故障:它读作
     * 「已经知道」,而缺的是另一个引用。
     */
    @Synchronized
    private fun noteUnavailable(state: AodWakeState, source: String, deferred: Boolean) {
        val reason = resolveAvailability(state)
        if (!shouldReportAodWakeUnavailable(reason, unavailableReason)) return
        unavailableReason = reason
        // 安装原因随拒绝一起走。「no host」单独一条说不清宿主是「从未移交」还是
        // 「安装器从未绑定」,而这正是现场报告必须回答的问题。
        val detail = aodWakeUnavailableDetail(reason, installSkipSummary())
        HookLogger.w(
            TAG,
            "AOD wake unavailable reason=${reason.wireValue} source=$source deferred=$deferred$detail"
        )
    }

    private data class AodWakeState(
        val host: Any?,
        val method: Method?,
        val powerManager: PowerManager?
    )

    fun requestWake(signal: Long): Boolean = enqueueWake(signal, "lyrics", urgent = false)

    /** 紧急路径:仅当 AOD 被系统真正关闭(如 hide race recovery、surface 重挂)需要立刻重新
     *  拉起时才用。与常规歌词续期的宽去抖隔离,不因每句歌词 goto 冲掉恢复时机。 */
    fun requestEmergencyWake(signal: Long): Boolean = enqueueWake(signal, "emergency", urgent = true)

    fun requestPickupWake(): Boolean = enqueueWake(
        signal = SystemClock.elapsedRealtime().coerceAtLeast(1L),
        source = "pickup",
        urgent = true
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
        lyriconClassLoader = classLoader
        lastLyriconResolveAttemptAtElapsedMs = SystemClock.elapsedRealtime()
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

    /**
     * Lyricon 中心服务的播放真值。app 侧快照在切歌 BUFFERING/位置未知窗口会短暂报
     * playbackActive=false(issue #6),但 Lyricon 自己的 activeIsPlaying 仍是播放中;
     * 渲染续期与看门狗用它兜底,避免画布唤醒续期在缓冲窗口被关掉后无法自动恢复。
     */
    fun isLyriconPlaybackActive(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (lyriconPlayingCachedAtElapsedMs != Long.MIN_VALUE &&
            now - lyriconPlayingCachedAtElapsedMs < LYRICON_STATE_CACHE_MS
        ) return lyriconPlayingCached
        lyriconPlayingCachedAtElapsedMs = now
        lyriconPlayingCached = readLyriconPlaying()
        return lyriconPlayingCached
    }

    private fun readLyriconPlaying(): Boolean {
        val coordinator = lyriconActivePlayers
        val isPlayingField = lyriconActiveIsPlayingField
        if (coordinator == null || isPlayingField == null) {
            // Lyricon 中心类可能在动态 classloader 中晚于本模块安装才出现,首次解析失败后
            // 周期性用最近的 loader 重试,而不是永远放弃。
            val loader = lyriconClassLoader ?: return false
            val now = SystemClock.elapsedRealtime()
            if (lastLyriconResolveAttemptAtElapsedMs != Long.MIN_VALUE &&
                now - lastLyriconResolveAttemptAtElapsedMs < LYRICON_RESOLVE_RETRY_INTERVAL_MS
            ) return false
            resolveLyriconState(loader)
            return false
        }
        return runCatching { isPlayingField.get(coordinator) as? Boolean ?: false }
            .getOrDefault(false)
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
            if (!isLyriconPlaybackActive()) {
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

    private fun enqueueWake(signal: Long, source: String, urgent: Boolean = false): Boolean {
        if (signal == 0L || !XiaomiCapabilityResolver.hasCapability(
                XiaomiCapability.AOD_WAKE_BROKER
            )
        ) return false
        val state = readState()
        if (resolveAvailability(state) != AodWakeAvailability.READY) {
            noteUnavailable(state, source, deferred = false)
            return false
        }
        mainHandler.post {
            val now = SystemClock.elapsedRealtime()
            if (lastRequestElapsedMs != Long.MIN_VALUE &&
                now - lastRequestElapsedMs <
                (if (urgent) MIN_REQUEST_INTERVAL_MS else REGULAR_WAKE_MIN_INTERVAL_MS)
            ) {
                HookLogger.i(
                    TAG,
                    "AOD wake debounced source=$source urgent=$urgent signal=$signal"
                )
                return@post
            }
            val dispatched = readState()
            if (resolveAvailability(dispatched) != AodWakeAvailability.READY) {
                noteUnavailable(dispatched, source, deferred = true)
                return@post
            }
            val wakeHost = dispatched.host ?: return@post
            val method = dispatched.method ?: return@post
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
        private val contextField: java.lang.reflect.Field
    ) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            try {
                val owner = chain.thisObject ?: return result
                val host = hostField.get(owner) ?: return result
                val context = contextField.get(owner) as? android.content.Context ?: return result
                AodWakeBroker.observeContext(context)
                AodWakeBroker.adoptHost(host, "constructor")
                AodWakeBroker.startLyriconWatchdog()
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
    /** 常规歌词续期唤醒的最小间隔:歌词行 goto 每 2~5s 一次,若每次都 fireAodState 会让
     *  AOD 反复唤醒、smartHide 被抑制,表现为"时钟/歌词位置乱跳"。常规续期保持 AOD 足以
     *  用绘制 wake lock(pulseDrawWakeLock)完成,这里仅对必需的唤醒做宽去抖。 */
    private const val REGULAR_WAKE_MIN_INTERVAL_MS = 8_000L
    /** Lyricon 播放态轮询间隔:略小于投影 15s 的 stale 窗口,确保在 MIUI 关掉 AOD 之前重新断言。 */
    private const val LYRICON_POLL_INTERVAL_MS = 10_000L
    /** Lyricon 播放真值读缓存时长:快照热路径频繁查询,不必每次都进反射。 */
    private const val LYRICON_STATE_CACHE_MS = 500L
    /** Lyricon 中心类解析失败后的重试间隔(晚加载的动态 classloader 场景)。 */
    private const val LYRICON_RESOLVE_RETRY_INTERVAL_MS = 5_000L
    private const val MAX_INSTALL_RETRIES = 3
    private const val INSTALL_RETRY_BASE_DELAY_MS = 2_000L
    /** Fallback host field names for MIUI version compatibility. */
    private val HOST_FIELD_NAMES = listOf("mHost", "mDozeHost")
    /** Fallback fireAodState method names for MIUI version compatibility. */
    private val FIRE_AOD_STATE_METHOD_NAMES = listOf("fireAodState", "triggerAodState")
    private const val TAG = "AodWakeBroker"
}
