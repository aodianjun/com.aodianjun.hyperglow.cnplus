package com.eza.hyperglow.customization

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricTimeOffsetTest {

    @Test
    fun normalizeKeepsNullAndInVocabularyValues() {
        assertEquals(0, LyricTimeOffset.normalize(null))
        assertEquals(0, LyricTimeOffset.normalize(0))
        assertEquals(250, LyricTimeOffset.normalize(250))
        assertEquals(-250, LyricTimeOffset.normalize(-250))
    }

    @Test
    fun normalizeClampsToRangeAndQuantizesToStep() {
        assertEquals(LyricTimeOffset.MAX_OFFSET_MS, LyricTimeOffset.normalize(9_999))
        assertEquals(LyricTimeOffset.MIN_OFFSET_MS, LyricTimeOffset.normalize(-9_999))
        // 四舍五入到最近 50ms 档(与 HyperLyric 滑杆粒度一致)。
        assertEquals(100, LyricTimeOffset.normalize(77))
        assertEquals(-100, LyricTimeOffset.normalize(-77))
        assertEquals(50, LyricTimeOffset.normalize(74))
        assertEquals(LyricTimeOffset.MAX_OFFSET_MS, LyricTimeOffset.normalize(4_990))
        assertEquals(LyricTimeOffset.MIN_OFFSET_MS, LyricTimeOffset.normalize(-4_990))
    }

    @Test
    fun displayPositionShiftsAndFloorsAtZero() {
        // 正偏移延后(位置回退),负偏移提前(位置前移),下限 0。
        assertEquals(9_800L, LyricTimeOffset.displayPositionMs(10_000L, 200))
        assertEquals(10_200L, LyricTimeOffset.displayPositionMs(10_000L, -200))
        assertEquals(0L, LyricTimeOffset.displayPositionMs(100L, 5_000))
        assertEquals(0L, LyricTimeOffset.displayPositionMs(0L, 0))
        assertEquals(7L, LyricTimeOffset.displayPositionMs(7L, 0))
    }

    @Test
    fun displayMsShiftsLineAndWordTimestamps() {
        assertEquals(4_750L, LyricTimeOffset.displayMs(5_000L, 250))
        assertEquals(5_250L, LyricTimeOffset.displayMs(5_000L, -250))
        assertEquals(0L, LyricTimeOffset.displayMs(100L, 250))
    }
}
