package com.eza.hyperglow.producer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isMonotonicExtrapolationResume] 单测:LyricInfo 通道 stale→恢复的回跳保护。
 *
 * 09:53 故障链问题 2:抬起手机/通道回退后,MediaSession position 短暂落后于外推值,
 * 无条件接受会把行拉回几秒。容差内(1..300ms)保持单调外推,大幅落后按真实位置处理。
 */
class LyricInfoPositionResumeTest {

    @Test
    fun slightlyBehindRealPositionKeepsMonotonicExtrapolation() {
        // 外推 10_300ms,真实 10_100ms:落后 200ms,在容差内,保持外推。
        assertTrue(
            isMonotonicExtrapolationResume(
                wasExtrapolating = true,
                extrapolatedPositionMs = 10_300L,
                realPositionMs = 10_100L
            )
        )
    }

    @Test
    fun materiallyBehindRealPositionIsHonoredAsRewind() {
        // 落后 5s:seek/换歌/真回退,按真实位置处理。
        assertFalse(
            isMonotonicExtrapolationResume(
                wasExtrapolating = true,
                extrapolatedPositionMs = 10_300L,
                realPositionMs = 5_300L
            )
        )
    }

    @Test
    fun notExtrapolatingNeverHolds() {
        // 只有外推中(stale/冻结期)才适用;正常流式更新无条件接受真实位置。
        assertFalse(
            isMonotonicExtrapolationResume(
                wasExtrapolating = false,
                extrapolatedPositionMs = 10_300L,
                realPositionMs = 10_100L
            )
        )
    }

    @Test
    fun atOrAheadOfRealPositionIsNotAResumeHold() {
        // 真实位置与外推持平(差 0)或领先:无回跳,无需保护。
        assertFalse(
            isMonotonicExtrapolationResume(
                wasExtrapolating = true,
                extrapolatedPositionMs = 10_000L,
                realPositionMs = 10_000L
            )
        )
        assertFalse(
            isMonotonicExtrapolationResume(
                wasExtrapolating = true,
                extrapolatedPositionMs = 10_000L,
                realPositionMs = 10_200L
            )
        )
    }

    @Test
    fun toleranceBoundaryIsInclusive() {
        // 恰好落后 300ms(容差上界)保持;301ms 按真实位置处理。
        assertTrue(
            isMonotonicExtrapolationResume(
                wasExtrapolating = true,
                extrapolatedPositionMs = 10_300L,
                realPositionMs = 10_000L
            )
        )
        assertFalse(
            isMonotonicExtrapolationResume(
                wasExtrapolating = true,
                extrapolatedPositionMs = 10_301L,
                realPositionMs = 10_000L
            )
        )
    }
}
