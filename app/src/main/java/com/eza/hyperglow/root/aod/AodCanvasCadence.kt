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

/**
 * 时序激活期的帧间隔。默认 16ms(≈60fps);[powerSaverActive] 时降到
 * [POWER_SAVER_FRAME_INTERVAL_MS]——电量低(未充电)或设备过热时,逐字扫光以粗粒度
 * 步进仍可读,而绘制功耗大幅下降。退场过渡很短,始终保持 16ms 保证完整淡出。
 */
internal fun frameIntervalForTiming(
    contentVisible: Boolean,
    timingActive: Boolean,
    exitTransitionActive: Boolean = false,
    powerSaverActive: Boolean = false
): Long = when {
    !contentVisible -> 0L
    exitTransitionActive -> 16L
    !timingActive -> 0L
    powerSaverActive -> POWER_SAVER_FRAME_INTERVAL_MS
    else -> 16L
}
