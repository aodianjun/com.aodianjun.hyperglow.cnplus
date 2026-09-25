package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun refreshRateCapRaisesActiveFrameRate() {
        // issue #68 #12:0=跟随现有 16ms;60/90/120 档对应 16/11/8ms。
        assertEquals(
            16L,
            frameIntervalForTiming(contentVisible = true, timingActive = true, refreshRateCapHz = 0)
        )
        assertEquals(
            16L,
            frameIntervalForTiming(contentVisible = true, timingActive = true, refreshRateCapHz = 60)
        )
        assertEquals(
            11L,
            frameIntervalForTiming(contentVisible = true, timingActive = true, refreshRateCapHz = 90)
        )
        assertEquals(
            8L,
            frameIntervalForTiming(contentVisible = true, timingActive = true, refreshRateCapHz = 120)
        )
    }

    @Test
    fun unknownRefreshRateCapFallsBackToDefaultInterval() {
        assertEquals(16L, frameIntervalForRefreshRateCap(144))
        assertEquals(16L, frameIntervalForRefreshRateCap(-1))
        assertEquals(
            16L,
            frameIntervalForTiming(contentVisible = true, timingActive = true, refreshRateCapHz = 30)
        )
    }

    @Test
    fun powerSaverStillWinsOverRefreshRateCap() {
        assertEquals(
            POWER_SAVER_FRAME_INTERVAL_MS,
            frameIntervalForTiming(
                contentVisible = true,
                timingActive = true,
                powerSaverActive = true,
                refreshRateCapHz = 120
            )
        )
    }

    @Test
    fun invisibleContentStopsFramesAtAnyCap() {
        assertEquals(
            0L,
            frameIntervalForTiming(contentVisible = false, timingActive = true, refreshRateCapHz = 120)
        )
    }

    @Test
    fun exitTransitionFollowsCapButNeverPowerSaver() {
        assertEquals(
            8L,
            frameIntervalForTiming(
                contentVisible = true,
                timingActive = false,
                exitTransitionActive = true,
                powerSaverActive = true,
                refreshRateCapHz = 120
            )
        )
        assertEquals(
            16L,
            frameIntervalForTiming(
                contentVisible = true,
                timingActive = false,
                exitTransitionActive = true,
                refreshRateCapHz = 0
            )
        )
    }

    @Test
    fun framePeriodNanosMatchesCapSteps() {
        assertEquals(1_000_000_000L / 60L, framePeriodNanos(60))
        assertEquals(1_000_000_000L / 90L, framePeriodNanos(90))
        assertEquals(1_000_000_000L / 120L, framePeriodNanos(120))
        assertEquals(DEFAULT_FRAME_INTERVAL_MS * NANOS_PER_MS, framePeriodNanos(0))
        assertEquals(DEFAULT_FRAME_INTERVAL_MS * NANOS_PER_MS, framePeriodNanos(30))
    }

    @Test
    fun isFrameDueHonorsHalfMillisecondTolerance() {
        val deadline = 1_000_000_000L
        assertFalse(isFrameDue(deadline - FRAME_DUE_TOLERANCE_NANOS - 1L, deadline))
        assertTrue(isFrameDue(deadline - FRAME_DUE_TOLERANCE_NANOS, deadline))
        assertTrue(isFrameDue(deadline, deadline))
        assertTrue(isFrameDue(deadline + 5_000_000L, deadline))
    }

    @Test
    fun advanceDeadlineKeepsPhaseAlignedWithinOnePeriod() {
        val period = 16_000_000L
        val deadline = 1_000_000_000L
        // 正常迟到(< 一个周期):推进一个周期,相位保持对齐原 deadline 序列。
        assertEquals(deadline + period, advanceFrameDeadline(deadline, period, deadline + 3_000_000L))
        // 恰好落在下一周期边界:同样推进到下一周期。
        assertEquals(
            deadline + period * 2,
            advanceFrameDeadline(deadline, period, deadline + period)
        )
    }

    @Test
    fun advanceDeadlineSkipsMissedPeriodsAfterSuspend() {
        val period = 16_000_000L
        val deadline = 1_000_000_000L
        // issue #68 #20:挂起恢复落后多个周期时跳过已过周期,不补帧追赶,且仍相位对齐。
        val now = deadline + period * 10 + 5_000_000L
        val next = advanceFrameDeadline(deadline, period, now)
        assertEquals(deadline + period * 11, next)
        assertTrue(next > now)
        assertEquals(0L, (next - deadline) % period)
    }

    @Test
    fun advanceDeadlineWithNonPositivePeriodReturnsNow() {
        assertEquals(123L, advanceFrameDeadline(1L, 0L, 123L))
        assertEquals(123L, advanceFrameDeadline(1L, -5L, 123L))
    }
}
