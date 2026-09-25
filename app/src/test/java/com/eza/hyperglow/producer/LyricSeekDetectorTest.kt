package com.eza.hyperglow.producer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LyricSeekDetector] — 倒退恒跳转、播放容差 max(150ms, 50%)、暂停容差
 * 150ms、800ms 信任上限、首推建基线、判定后 rebase。
 */
class LyricSeekDetectorTest {

    @Test
    fun firstPushBuildsBaselineOnly() {
        val detector = LyricSeekDetector { 1_000L }
        assertFalse(detector.detect(5_000L, isPlaying = true))
        assertFalse(detector.detect(5_100L, isPlaying = true))
    }

    @Test
    fun backwardPositionIsAlwaysSeek() {
        var wall = 1_000L
        val detector = LyricSeekDetector { wall }
        assertFalse(detector.detect(5_000L, isPlaying = true))
        wall = 1_100L
        assertTrue(detector.detect(4_000L, isPlaying = true))
    }

    @Test
    fun playingWithinDriftToleranceNotSeek() {
        var wall = 1_000L
        val detector = LyricSeekDetector { wall }
        detector.detect(5_000L, isPlaying = true)
        wall = 2_000L
        // 媒体推进 1000ms = 物理推进 1000ms → 容差 max(150, 500) 内。
        assertFalse(detector.detect(6_000L, isPlaying = true))
    }

    @Test
    fun playingForwardJumpDetected() {
        var wall = 1_000L
        val detector = LyricSeekDetector { wall }
        detector.detect(5_000L, isPlaying = true)
        wall = 1_100L
        // 播放中物理推进 100ms,媒体却推进 3000ms → 跳转。
        assertTrue(detector.detect(8_000L, isPlaying = true))
    }

    @Test
    fun pausedAdvanceBeyond150msIsSeek() {
        var wall = 1_000L
        val detector = LyricSeekDetector { wall }
        detector.detect(5_000L, isPlaying = false)
        wall = 1_500L
        assertFalse(detector.detect(5_100L, isPlaying = false))
        assertTrue(detector.detect(5_300L, isPlaying = false))
    }

    @Test
    fun wallGapClampedToTrustedSpan() {
        var wall = 1_000L
        val detector = LyricSeekDetector { wall }
        detector.detect(5_000L, isPlaying = true)
        // 挂起 60s 后恢复,媒体几乎没走:物理增量被钳到 800ms,推进 20ms 不是跳转。
        wall = 61_000L
        assertFalse(detector.detect(5_020L, isPlaying = true))
    }

    @Test
    fun afterSeekNextNormalPushIsNotFlagged() {
        var wall = 1_000L
        val detector = LyricSeekDetector { wall }
        detector.detect(5_000L, isPlaying = true)
        wall = 1_100L
        assertTrue(detector.detect(20_000L, isPlaying = true))
        wall = 1_200L
        assertFalse(detector.detect(20_100L, isPlaying = true))
    }
}
