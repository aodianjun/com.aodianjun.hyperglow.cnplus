package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BetterLyricsWordEffectsTest {
    @Test
    fun longSyllableThresholdMatchesReferenceSevenHundredMillis() {
        assertTrue(isLongKaraokeSyllable(700L))
        assertTrue(isLongKaraokeSyllable(1_500L))
        assertFalse(isLongKaraokeSyllable(699L))
        assertFalse(isLongKaraokeSyllable(0L))
    }

    @Test
    fun longSyllablesScaleToBetterLyricsPeakOnlyInBetterLyricsMode() {
        assertEquals(1.15f, karaokeScalePeak(betterLyrics = true, longSyllable = true))
        // 短音节与非 BetterLyrics 档沿用基础峰值(既有逐字卡拉OK观感不变)。
        assertEquals(1.0505f, karaokeScalePeak(betterLyrics = true, longSyllable = false))
        assertEquals(1.0505f, karaokeScalePeak(betterLyrics = false, longSyllable = true))
    }

    @Test
    fun scaleCurveRisesToPeakThenFallsBackToOne() {
        assertEquals(0.95, karaokeScaleAt(0f, 1.15f).toDouble(), 1e-5)
        assertEquals(1.15, karaokeScaleAt(0.7f, 1.15f).toDouble(), 1e-5)
        assertEquals(1.0, karaokeScaleAt(1f, 1.15f).toDouble(), 1e-5)
    }

    @Test
    fun unsungSyllableSinksByTenPercentOfLineHeightWithOnePixelFloor() {
        assertEquals(10.0, karaokeFloatSinkPx(100f).toDouble(), 1e-4)
        assertEquals(1.0, karaokeFloatSinkPx(0f).toDouble(), 1e-4)
    }

    @Test
    fun floatOffsetSinksUnplayedRisesWhilePlayingAndRestsWhenSung() {
        // 未唱恒下沉;已唱完归零。
        assertEquals(10.0, karaokeFloatOffsetPx(0f, 1_000L, 10f).toDouble(), 1e-4)
        assertEquals(0.0, karaokeFloatOffsetPx(1f, 1_000L, 10f).toDouble(), 1e-4)
        // 长词 1000ms:上浮段占 45%(450/1000),演唱进度 0.45 时刚好回到基线,半程回一半。
        assertEquals(0.0, karaokeFloatOffsetPx(0.45f, 1_000L, 10f).toDouble(), 1e-4)
        assertEquals(5.0, karaokeFloatOffsetPx(0.225f, 1_000L, 10f).toDouble(), 1e-4)
        // 短词(≤450ms)整段用于上浮:半程回到一半。
        assertEquals(5.0, karaokeFloatOffsetPx(0.5f, 300L, 10f).toDouble(), 1e-4)
    }

    @Test
    fun syntheticCharTimeWindowSplitsBlockSpanByGeometry() {
        // 整块 1000ms/总宽 100px:0..10px 的字符占前 100ms,50..60px 占中段 500..600ms。
        assertEquals(0L until 100L, syntheticCharTimeWindow(0L, 1_000L, 100f, 0f, 10f))
        assertEquals(500L until 600L, syntheticCharTimeWindow(0L, 1_000L, 100f, 50f, 10f))
        // 带块起点偏移:窗口整体平移。
        assertEquals(1_200L until 1_300L, syntheticCharTimeWindow(1_000L, 2_000L, 100f, 20f, 10f))
        // 前缀贴近块尾/零宽字符:钳制到块内且不越过块尾。
        assertEquals(950L until 1_000L, syntheticCharTimeWindow(0L, 1_000L, 100f, 95f, 10f))
        assertEquals(1_000L until 1_000L, syntheticCharTimeWindow(0L, 1_000L, 100f, 100f, 0f))
    }

    @Test
    fun syntheticBlocksSplitCjkPerCharAndWesternPerWord() {
        // 中文逐字成块、西文按词成块(含词内标点)、空白跳过。
        assertEquals(listOf(0 until 1, 1 until 2, 2 until 3), syntheticKaraokeBlocks("蝴蝶飞"))
        assertEquals(listOf(0 until 5, 6 until 11), syntheticKaraokeBlocks("hello world"))
        assertEquals(listOf(0 until 5, 6 until 7, 7 until 8), syntheticKaraokeBlocks("hello 你好"))
        assertEquals(listOf(0 until 5), syntheticKaraokeBlocks("don't"))
        assertEquals(listOf(0 until 1, 2 until 3), syntheticKaraokeBlocks("你 好"))
        assertEquals(emptyList<IntRange>(), syntheticKaraokeBlocks("   "))
    }
}
