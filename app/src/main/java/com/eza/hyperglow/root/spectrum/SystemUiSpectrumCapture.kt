package com.eza.hyperglow.root.spectrum

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.eza.hyperglow.aod.SpectrumFrame
import com.eza.hyperglow.root.HookLogger

/**
 * SystemUI/miui.aod 侧唯一的 loopback 采集会话。
 *
 * AOD/锁屏画布各自登记需求；任意一面需要时启动一次 AudioPolicy 回环，全部释放后停止。
 * 采集线程只把帧投递到主线程，画布仍在原线程消费。失败后保留需求并按固定间隔重试，
 * 重试期间画布走合成律动兜底，不会空白。
 */
internal object SystemUiSpectrumCapture {
    private const val RETRY_DELAY_MS = 10_000L
    private val lock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashMap<String, (SpectrumFrame) -> Unit>()
    private val retryRunnable = Runnable { startIfNeeded() }
    private var appContext: Context? = null
    private var session: PlaybackCapture? = null
    private var generation = 0L
    private var lastFailureAt = 0L

    fun setDemand(
        owner: String,
        demand: Boolean,
        context: Context?,
        listener: (SpectrumFrame) -> Unit
    ) {
        var toStart: PlaybackCapture? = null
        var toStop: PlaybackCapture? = null
        synchronized(lock) {
            if (demand) {
                listeners[owner] = listener
                val hostContext = context?.applicationContext ?: context
                hostContext?.let { appContext = it }
            } else {
                listeners.remove(owner)
            }
            if (listeners.isEmpty()) {
                if (session != null) {
                    toStop = session
                    session = null
                    generation++
                    mainHandler.removeCallbacks(retryRunnable)
                }
            } else if (session == null && appContext != null) {
                toStart = createSessionLocked()
            }
        }
        toStop?.stop()
        toStart?.let(::startSession)
    }

    fun clear(owner: String) {
        setDemand(owner, false, null) {}
    }

    private fun createSessionLocked(): PlaybackCapture? {
        val now = SystemClock.elapsedRealtime()
        if (lastFailureAt != 0L && now - lastFailureAt < RETRY_DELAY_MS) {
            scheduleRetryLocked()
            return null
        }
        mainHandler.removeCallbacks(retryRunnable)
        val gen = ++generation
        val capture = PlaybackCapture(
            appContext!!,
            object : PlaybackCapture.Listener {
                override fun onFrame(frame: SpectrumFrame) {
                    mainHandler.post {
                        val snapshot = synchronized(lock) { listeners.values.toList() }
                        snapshot.forEach { it(frame) }
                    }
                }

                override fun onUnavailable(reason: String) {
                    HookLogger.w(
                        LoopbackCaptureContract.TAG,
                        "loopback unavailable: $reason"
                    )
                    synchronized(lock) {
                        if (generation == gen) {
                            session = null
                            lastFailureAt = SystemClock.elapsedRealtime()
                            scheduleRetryLocked()
                        }
                    }
                }
            }
        )
        session = capture
        HookLogger.i(
            LoopbackCaptureContract.TAG,
            "session start listeners=${listeners.size}"
        )
        return capture
    }

    private fun startSession(capture: PlaybackCapture) {
        runCatching { capture.start() }.onFailure { error ->
            HookLogger.w(
                LoopbackCaptureContract.TAG,
                "loopback start failed",
                error
            )
            synchronized(lock) {
                if (session === capture) {
                    session = null
                    lastFailureAt = SystemClock.elapsedRealtime()
                    scheduleRetryLocked()
                }
            }
        }
    }

    private fun startIfNeeded() {
        var toStart: PlaybackCapture? = null
        synchronized(lock) {
            if (listeners.isEmpty() || session != null || appContext == null) return
            toStart = createSessionLocked()
        }
        toStart?.let(::startSession)
    }

    private fun scheduleRetryLocked() {
        if (listeners.isEmpty() || session != null) return
        val now = SystemClock.elapsedRealtime()
        val delay = (lastFailureAt + RETRY_DELAY_MS - now).coerceAtLeast(0L)
        mainHandler.removeCallbacks(retryRunnable)
        mainHandler.postDelayed(retryRunnable, delay)
    }
}
