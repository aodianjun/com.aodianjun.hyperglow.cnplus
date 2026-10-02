package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Test

class BetterLyricsWordEffectsTest {
    @Test
    fun longWordsScaleToBetterLyricsPeakOnlyInBetterLyricsMode() {
        assertEquals(1.15f, wordKaraokeScalePeak(betterLyrics = true, wordDurationMs = 700L))
        assertEquals(1.15f, wordKaraokeScalePeak(betterLyrics = true, wordDurationMs = 1_500L))
    }

    @Test
    fun shortWordsAndOtherModesKeepBaseScalePeak() {
        assertEquals(1.0505f, wordKaraokeScalePeak(betterLyrics = true, wordDurationMs = 699L))
        assertEquals(1.0505f, wordKaraokeScalePeak(betterLyrics = true, wordDurationMs = 0L))
        assertEquals(1.0505f, wordKaraokeScalePeak(betterLyrics = false, wordDurationMs = 5_000L))
    }
}
