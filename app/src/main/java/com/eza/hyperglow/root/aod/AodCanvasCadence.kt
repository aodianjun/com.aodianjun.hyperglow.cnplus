package com.eza.hyperglow.root.aod

internal data class EffectiveCadenceInputs(
    val attached: Boolean,
    val sceneActive: Boolean,
    val ownVisible: Boolean,
    val windowVisible: Boolean,
    val aggregatedVisible: Boolean,
    val effectiveAlpha: Float,
    val timedOrTransitionActive: Boolean,
    val handoffActive: Boolean = false,
    val verifiedDozeHost: Boolean = false
)

internal fun isEffectiveCadenceActive(inputs: EffectiveCadenceInputs): Boolean =
    isEffectiveCadenceActive(
        attached = inputs.attached,
        sceneActive = inputs.sceneActive,
        ownVisible = inputs.ownVisible,
        windowVisible = inputs.windowVisible,
        aggregatedVisible = inputs.aggregatedVisible,
        effectiveAlpha = inputs.effectiveAlpha,
        timedOrTransitionActive = inputs.timedOrTransitionActive,
        handoffActive = inputs.handoffActive,
        verifiedDozeHost = inputs.verifiedDozeHost
    )

internal fun isEffectiveCadenceActive(
    attached: Boolean,
    sceneActive: Boolean,
    ownVisible: Boolean,
    windowVisible: Boolean,
    aggregatedVisible: Boolean,
    effectiveAlpha: Float,
    timedOrTransitionActive: Boolean,
    handoffActive: Boolean,
    verifiedDozeHost: Boolean
): Boolean =
    attached &&
        sceneActive &&
        ownVisible &&
        timedOrTransitionActive &&
        (verifiedDozeHost ||
            windowVisible &&
            aggregatedVisible &&
            (handoffActive || effectiveAlpha > 0f))

internal enum class CadenceChange { NONE, START, STOP }

internal class EffectiveCadenceGate {
    private var active = false

    fun update(nextActive: Boolean): CadenceChange = when {
        nextActive && !active -> {
            active = true
            CadenceChange.START
        }
        !nextActive && active -> {
            active = false
            CadenceChange.STOP
        }
        else -> CadenceChange.NONE
    }
}

/** 省电激活时逐字动画 tick 的降帧间隔:保持行进可见但功耗显著降低。 */
internal const val POWER_SAVER_FRAME_INTERVAL_MS = 200L

/** 时序激活期的默认帧间隔(≈60fps)。 */
internal const val DEFAULT_FRAME_INTERVAL_MS = 16L

/**
 * 纯行级扫光的帧间隔上限：约 24fps。行级扫光仍连续、可读，但不必跟 60fps；
 * 省电档 200ms 和用户更低的上限仍优先（取较大间隔）。
 */
internal const val LINE_SWEEP_FRAME_INTERVAL_MS = 41L // 1000ms / 24 ≈ 41.67ms

/**
 * 内容分级帧间隔：[lineSweepOnly] 且不在换场时，把基础间隔钳到
 * [LINE_SWEEP_FRAME_INTERVAL_MS]；换场/逐字/圆点/旋转仍走基础间隔。
 * 基础间隔为 0（不续帧）时保持 0。
 */
internal fun lineSweepFrameIntervalMs(
    baseIntervalMs: Long,
    lineSweepOnly: Boolean,
    transitionActive: Boolean
): Long {
    if (baseIntervalMs <= 0L) return baseIntervalMs
    if (!lineSweepOnly || transitionActive) return baseIntervalMs
    return maxOf(baseIntervalMs, LINE_SWEEP_FRAME_INTERVAL_MS)
}

internal const val NANOS_PER_MS = 1_000_000L
internal const val NANOS_PER_SECOND = 1_000_000_000L

/** deadline 到期判定容差(0.5ms):吸收 postDelayed 取整误差,防止相位漂移。 */
internal const val FRAME_DUE_TOLERANCE_NANOS = 500_000L

/**
 * 渲染刷新率上限档位对应的帧周期(ns)。issue #68 #12,借鉴 Bridge
 * LyricRefreshRatePolicy:0=跟随现有行为(16ms);30/60/90/120 为用户可选上限档。
 * 30fps 档给「文字扫光仍可读、但想明显降绘制功耗」的场景；未知档位回落到默认周期,
 * 避免异常偏好值把帧率推到不可控区间。
 */
internal fun framePeriodNanos(refreshRateCapHz: Int): Long = when (refreshRateCapHz) {
    120 -> NANOS_PER_SECOND / 120L
    90 -> NANOS_PER_SECOND / 90L
    60 -> NANOS_PER_SECOND / 60L
    30 -> NANOS_PER_SECOND / 30L
    else -> DEFAULT_FRAME_INTERVAL_MS * NANOS_PER_MS
}

/** [refreshRateCapHz] 档位对应的帧间隔(ms),由 [framePeriodNanos] 派生保持一致。 */
internal fun frameIntervalForRefreshRateCap(refreshRateCapHz: Int): Long =
    framePeriodNanos(refreshRateCapHz) / NANOS_PER_MS

/** deadline 到期判定:[toleranceNanos] 容差内视为到期。 */
internal fun isFrameDue(
    nowNanos: Long,
    deadlineNanos: Long,
    toleranceNanos: Long = FRAME_DUE_TOLERANCE_NANOS
): Boolean = nowNanos + toleranceNanos >= deadlineNanos

/**
 * 整数周期推进 deadline(借鉴 LyricRefreshRatePolicy.advanceDeadline):相位对齐防漂移;
 * 落后多个周期(如息屏挂起恢复)时跳过已过周期,不做补帧追赶。
 */
internal fun advanceFrameDeadline(deadlineNanos: Long, periodNanos: Long, nowNanos: Long): Long {
    if (periodNanos <= 0L) return nowNanos
    var next = deadlineNanos + periodNanos
    if (next <= nowNanos) {
        val missed = (nowNanos - next) / periodNanos + 1L
        next += missed * periodNanos
    }
    return next
}

/**
 * 时序激活期的帧间隔。默认 16ms(≈60fps),[refreshRateCapHz] 可上调上限档
 * (30/60/90/120,0=跟随现有行为);[powerSaverActive] 时降到
 * [POWER_SAVER_FRAME_INTERVAL_MS]——电量低(未充电)或设备过热时,逐字扫光以粗粒度
 * 步进仍可读,而绘制功耗大幅下降。退场过渡很短,不降到省电档,保证完整淡出。
 */
internal fun frameIntervalForTiming(
    contentVisible: Boolean,
    timingActive: Boolean,
    exitTransitionActive: Boolean = false,
    powerSaverActive: Boolean = false,
    refreshRateCapHz: Int = 0
): Long = when {
    !contentVisible -> 0L
    exitTransitionActive -> frameIntervalForRefreshRateCap(refreshRateCapHz)
    !timingActive -> 0L
    powerSaverActive -> POWER_SAVER_FRAME_INTERVAL_MS
    else -> frameIntervalForRefreshRateCap(refreshRateCapHz)
}
