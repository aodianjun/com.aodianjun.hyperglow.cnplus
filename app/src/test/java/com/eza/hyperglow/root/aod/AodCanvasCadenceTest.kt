package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [frameIntervalForTiming] — cadence selection including the
 * power-saver downgrade (battery low while discharging, or thermal throttling).
 */
class AodCanvasCadenceTest {

    @Test
    fun inactiveContentStopsFrames_regardlessOfPowerSaver() {
        assertEquals(0L, frameIntervalForTiming(contentVisible = false, timingActive = true))
        assertEquals(
            0L,
            frameIntervalForTiming(contentVisible = false, timingActive = true, powerSaverActive = true)
        )
    }

    @Test
    fun activeTimingRunsAt16msByDefault() {
        assertEquals(16L, frameIntervalForTiming(contentVisible = true, timingActive = true))
    }

    @Test
    fun powerSaverDowngradesTimingTo200ms() {
        assertEquals(
            200L,
            frameIntervalForTiming(contentVisible = true, timingActive = true, powerSaverActive = true)
        )
        assertEquals(
            POWER_SAVER_FRAME_INTERVAL_MS,
            frameIntervalForTiming(contentVisible = true, timingActive = true, powerSaverActive = true)
        )
    }

    @Test
    fun exitTransitionKeeps16msEvenUnderPowerSaver() {
        assertEquals(
            16L,
            frameIntervalForTiming(
                contentVisible = true,
                timingActive = false,
                exitTransitionActive = true,
                powerSaverActive = true
            )
        )
    }

    @Test
    fun staticContentWithoutTimingStopsFrames() {
        assertEquals(0L, frameIntervalForTiming(contentVisible = true, timingActive = false))
        assertEquals(
            0L,
            frameIntervalForTiming(
                contentVisible = true,
                timingActive = false,
                exitTransitionActive = false,
                powerSaverActive = true
            )
        )
    }
}
