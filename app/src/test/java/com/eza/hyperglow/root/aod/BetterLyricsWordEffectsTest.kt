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
}
