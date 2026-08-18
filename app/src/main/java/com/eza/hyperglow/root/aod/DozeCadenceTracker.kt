package com.eza.hyperglow.root.aod

// 职责：Doze 节奏诊断统计——跟踪回调/绘制计数与绘制间隔峰值，按窗口周期性输出日志。

import android.os.SystemClock
import com.eza.hyperglow.root.HookLogger

internal class DozeCadenceTracker(private val enabled: Boolean) {
    private var cadenceWindowStartedAt = 0L
    private var cadenceCallbackCount = 0
    private var cadenceDrawCount = 0
    private var cadenceMaxDrawGapMs = 0L
    private var cadenceLastDrawAt = 0L

    fun recordDozeCadenceCallback() {
        if (!enabled || !HookLogger.traceEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (cadenceWindowStartedAt == 0L) cadenceWindowStartedAt = now
        cadenceCallbackCount++
        if (now - cadenceWindowStartedAt < CADENCE_DIAGNOSTIC_WINDOW_MS) return
        HookLogger.i(
            CADENCE_DIAGNOSTIC_TAG,
            "callbacks=$cadenceCallbackCount draws=$cadenceDrawCount " +
                "maxDrawGapMs=$cadenceMaxDrawGapMs"
        )
        cadenceWindowStartedAt = now
        cadenceCallbackCount = 0
        cadenceDrawCount = 0
        cadenceMaxDrawGapMs = 0L
    }

    fun recordDozeDraw() {
        if (!enabled || !HookLogger.traceEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (cadenceLastDrawAt > 0L) {
            cadenceMaxDrawGapMs = maxOf(cadenceMaxDrawGapMs, now - cadenceLastDrawAt)
        }
        cadenceLastDrawAt = now
        cadenceDrawCount++
    }

    companion object {
        private const val CADENCE_DIAGNOSTIC_WINDOW_MS = 10_000L
        private const val CADENCE_DIAGNOSTIC_TAG = "AodCanvasCadence"
    }
}
