package com.eza.hyperglow.root

import android.util.Log
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.DiagnosticLoggingRuntime
import io.github.libxposed.api.XposedModule

object HookLogger {
    private const val TAG = "HyperGlow"
    var module: XposedModule? = null
    val traceEnabled: Boolean
        get() = DiagnosticLoggingRuntime.enabled

    fun i(area: String, message: String) {
        if (!traceEnabled) return
        Log.i(TAG, "[$area] $message")
        module?.log(Log.INFO, TAG, "[$area] $message")
    }

    private val throttle = LogThrottle()

    /**
     * 节流版 info(issue #68 #10):同一 [key] 在 [windowMs] 毫秒内只输出一次;窗口内
     * 被抑制的条数在下一条输出时以 "[suppressed=N]" 后缀补报,降噪不吞掉频次信息。
     * [message] 是 lambda:仅在实际放行时才构造字符串(热路径友好)。一次性事件
     * 仍用 [i];w/e 永不节流。
     */
    fun iThrottled(key: String, windowMs: Long, area: String, message: () -> String) {
        if (windowMs <= 0L) {
            i(area, message())
            return
        }
        if (!throttle.shouldLog(key, windowMs)) return
        val suppressed = throttle.drainSuppressed(key)
        val text = message()
        i(area, if (suppressed > 0) "$text [suppressed=$suppressed]" else text)
    }

    /**
     * Finite boot-path evidence. It must not depend on bridge-delivered diagnostic configuration,
     * since this path is also used to diagnose a bridge which never connects.
     */
    fun bootstrap(area: String, stage: String) {
        if (!BuildConfig.TRACE_LOGGING_AVAILABLE) return
        val message = "[$area] bootstrap=$stage"
        runCatching {
            Log.i(TAG, message)
            module?.log(Log.INFO, TAG, message)
        }
    }

    fun w(area: String, message: String, error: Throwable? = null) {
        Log.w(TAG, "[$area] $message", error)
        module?.log(Log.WARN, TAG, "[$area] $message", error)
    }

    fun e(area: String, message: String, error: Throwable? = null) {
        Log.e(TAG, "[$area] $message", error)
        module?.log(Log.ERROR, TAG, "[$area] $message", error)
    }
}
