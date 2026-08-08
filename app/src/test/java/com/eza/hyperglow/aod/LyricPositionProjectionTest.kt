package com.eza.hyperglow.aod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [extrapolationReliable] — the screen-off extrapolation guard that prevents
 * AOD from showing a stale last line when the source (e.g. NetEase Cloud Music) stops writing
 * position and the song ends/replays/switches.
 */
class LyricPositionProjectionTest {

    private fun reliable(
        positionMs: Long = 10_000L,
        sampledAtElapsedMs: Long = 1_000L,
        speed: Float = 1f,
        playing: Boolean = true,
        durationMs: Long = 180_000L,
        now: Long = 2_000L
    ) = extrapolationReliable(positionMs, sampledAtElapsedMs, speed, playing, durationMs, now)

    @Test
    fun notPlaying_isAlwaysReliable() {
        assertTrue(reliable(playing = false))
        // Even a large clock gap is irrelevant when not playing (no extrapolation).
        assertTrue(reliable(playing = false, sampledAtElapsedMs = 0L, now = 999_999L))
    }

    @Test
    fun noExtrapolation_isReliable() {
        assertTrue(reliable(now = 1_000L)) // now == sampledAtElapsedMs
    }

    @Test
    fun playing_withinBounds_isReliable() {
        // 1s extrapolation at 1.0x → +1000ms, well inside the 180s song.
        assertTrue(reliable(now = 2_000L))
    }

    @Test
    fun extrapolationCrossingSongEnd_isUnreliable() {
        // position near the end + extrapolation pushes past durationMs → song likely ended/replayed.
        assertFalse(
            reliable(
                positionMs = 179_000L,
                sampledAtElapsedMs = 0L,
                durationMs = 180_000L,
                now = 5_000L
            )
        )
    }

    @Test
    fun extrapolationAgeExceedingSongDuration_isUnreliable() {
        // speed < 1 so position hasn't crossed the end, but extrapolation has outlived the whole
        // song → the source has definitely stopped updating.
        assertFalse(
            reliable(
                positionMs = 0L,
                sampledAtElapsedMs = 0L,
                speed = 0.5f,
                durationMs = 100_000L,
                now = 200_000L
            )
        )
    }

    @Test
    fun unknownDuration_skipsGuardsAndStaysReliable() {
        assertTrue(reliable(durationMs = 0L, now = 999_999L))
    }

    @Test
    fun extrapolationAtExactSongEnd_isUnreliable() {
        // position + extrapolation == durationMs (not >) → boundary stays reliable.
        assertTrue(
            reliable(
                positionMs = 0L,
                sampledAtElapsedMs = 0L,
                durationMs = 10_000L,
                now = 10_000L
            )
        )
    }
}