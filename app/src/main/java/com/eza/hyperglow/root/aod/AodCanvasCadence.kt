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

internal fun frameIntervalForTiming(
    contentVisible: Boolean,
    timingActive: Boolean,
    exitTransitionActive: Boolean = false
): Long = if (contentVisible && (timingActive || exitTransitionActive)) 16L else 0L
