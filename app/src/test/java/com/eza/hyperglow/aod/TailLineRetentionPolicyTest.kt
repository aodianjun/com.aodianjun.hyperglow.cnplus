package com.eza.hyperglow.aod

import com.eza.hyperglow.producer.LyricKind
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.ProducerRenderModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 尾奏保留策略:producer 清行后由投影层补回最后一行,并标记 `tailEligible`
 * (处于尾奏上下文,不代表延迟已到点)。延迟由 [tailTimeoutActive] 按面计算。
 */
class TailLineRetentionPolicyTest {

    private fun renderModes() = ProducerRenderModes(
        weight = "Medium",
        textSize = "normal",
        textSizeCustom = 100,
        secondary = "Main only",
        animation = "Gradient",
        glow = "Off",
        lineSyncFill = "Top to bottom",
        overflow = "Wrap",
        transition = "Fade up",
        font = "spotify"
    )

    private fun state(
        line: String = "",
        translatedLine: String = "",
        lineIndex: Int = -1,
        lineStartMs: Long = 0L,
        lineEndMs: Long = 0L,
        nextLine: String = "",
        nextLineStartMs: Long? = null,
        positionMs: Long = 0L,
        sampledAtElapsedMs: Long = 0L,
        speed: Float = 1f,
        playing: Boolean = true,
        status: String = "ready",
        hasTimedLyrics: Boolean = true,
        lyricKind: LyricKind = LyricKind.SYLLABLE,
        trackUri: String = "spotify:track:one",
        generation: Int = 1,
        durationMs: Long = 180_000L
    ) = LyricProducerState(
        producerId = "test",
        generation = generation,
        sequence = 1L,
        status = status,
        trackUri = trackUri,
        title = "Title",
        artist = "Artist",
        album = "",
        imageId = "",
        line = line,
        romanizedLine = "",
        translatedLine = translatedLine,
        lineIndex = lineIndex,
        positionMs = positionMs,
        durationMs = durationMs,
        sampledAtElapsedMs = sampledAtElapsedMs,
        speed = speed,
        playing = playing,
        receivedAtElapsedMs = sampledAtElapsedMs,
        words = null,
        renderModes = renderModes(),
        lyricKind = lyricKind,
        lineStartMs = lineStartMs,
        lineEndMs = lineEndMs,
        hasTimedLyrics = hasTimedLyrics,
        nextLineStartMs = nextLineStartMs,
        nextLine = nextLine
    )

    /** 最后一行(无下一行)在 [lineStartMs]..[lineEndMs] 内、位置 [positionMs]。 */
    private fun lastLine(positionMs: Long = 9_000L, sampledAtElapsedMs: Long = 9_000L) = state(
        line = "last line",
        translatedLine = "最后一句",
        lineIndex = 3,
        lineStartMs = 5_000L,
        lineEndMs = 10_000L,
        positionMs = positionMs,
        sampledAtElapsedMs = sampledAtElapsedMs
    )

    /** producer 已清行后的 state。 */
    private fun cleared(positionMs: Long = 10_500L, sampledAtElapsedMs: Long = 10_500L) = state(
        line = "",
        lineIndex = -1,
        positionMs = positionMs,
        sampledAtElapsedMs = sampledAtElapsedMs
    )

    @Test
    fun clearedLineKeepsLastLineAndMarksTailEligible() {
        val policy = TailLineRetentionPolicy()
        val last = lastLine()
        val primed = policy.apply(last)
        assertTrue(primed.tailEligible)
        assertEquals(last, primed.state)

        // 行结束后仍保留最后一行;tailEligible 只表示上下文,不随位置变化。
        val during = policy.apply(cleared())
        assertEquals("last line", during.state.line)
        assertEquals("最后一句", during.state.translatedLine)
        assertEquals(10_500L, during.state.positionMs)
        assertEquals(10_500L, during.state.sampledAtElapsedMs)
        assertTrue(during.tailEligible)

        val later = policy.apply(cleared(positionMs = 99_000L, sampledAtElapsedMs = 99_000L))
        assertEquals("last line", later.state.line)
        assertTrue(later.tailEligible)
    }

    @Test
    fun newLineArrivalClearsRetention() {
        val policy = TailLineRetentionPolicy()
        policy.apply(lastLine())
        assertTrue(policy.apply(cleared()).tailEligible)

        val next = state(
            line = "next line",
            lineIndex = 4,
            lineStartMs = 20_000L,
            lineEndMs = 24_000L,
            nextLine = "after",
            nextLineStartMs = 30_000L,
            positionMs = 21_000L,
            sampledAtElapsedMs = 21_000L
        )
        val arrived = policy.apply(next)
        assertFalse(arrived.tailEligible)
        assertEquals("next line", arrived.state.line)

        // 新行结束后保留的是新行,而不是更早的旧行。
        val clearedAgain = policy.apply(
            cleared(positionMs = 24_500L, sampledAtElapsedMs = 24_500L)
        )
        assertEquals("next line", clearedAgain.state.line)
        assertTrue(clearedAgain.tailEligible)
    }

    @Test
    fun trackChangeClearsRetention() {
        val policy = TailLineRetentionPolicy()
        policy.apply(lastLine())
        assertTrue(policy.apply(cleared()).tailEligible)

        val otherTrack = cleared().copy(trackUri = "spotify:track:two", generation = 2)
        val result = policy.apply(otherTrack)
        assertFalse(result.tailEligible)
        assertEquals("", result.state.line)
    }

    @Test
    fun pausedStateDoesNotRetain() {
        val policy = TailLineRetentionPolicy()
        policy.apply(lastLine())
        val result = policy.apply(cleared().copy(playing = false))
        assertFalse(result.tailEligible)
        assertEquals("", result.state.line)

        // 暂停(speed<=0)时有效位置不前进,由共享判定函数保证。
        val paused = lastLine(positionMs = 9_000L, sampledAtElapsedMs = 0L).copy(speed = 0f)
        assertEquals(9_000L, effectivePositionMs(paused, nowElapsedMs = 999_999L))
    }

    @Test
    fun beginOnlyLineIsEligible() {
        val policy = TailLineRetentionPolicy()
        val beginOnly = state(
            line = "begin only",
            lineIndex = 3,
            lineStartMs = 5_000L,
            lineEndMs = 0L,
            positionMs = 5_000L,
            sampledAtElapsedMs = 5_000L
        )
        val primed = policy.apply(beginOnly)
        assertTrue(primed.tailEligible)

        val retained = policy.apply(cleared(positionMs = 5_000L, sampledAtElapsedMs = 5_000L))
        assertEquals("begin only", retained.state.line)
        assertTrue(retained.tailEligible)
        // 只有 begin 的行:lineStart + (delay + 5)s。
        assertEquals(9_000L, tailDeadlineMs(1_000L, 0L, 3))
    }

    @Test
    fun pureMusicAndNoLyricsAreExcluded() {
        val policy = TailLineRetentionPolicy()
        policy.apply(lastLine())
        val excluded = policy.apply(cleared(), allowTail = false)
        assertFalse(excluded.tailEligible)
        assertEquals("", excluded.state.line)

        val noLyricsState = cleared().copy(lyricKind = LyricKind.NONE)
        val noLyrics = policy.apply(noLyricsState, allowTail = false)
        assertFalse(noLyrics.tailEligible)
    }

    @Test
    fun lastLineStillPresentUsesSecondaryPath() {
        val policy = TailLineRetentionPolicy()
        val last = lastLine(positionMs = 9_500L, sampledAtElapsedMs = 9_500L)
        val result = policy.apply(last)
        assertTrue(result.tailEligible)
        // 次路径不替换 state(内容仍是 producer 当前行)。
        assertEquals(last, result.state)
    }

    @Test
    fun nonLastLineWithNextLineDoesNotTrigger() {
        val policy = TailLineRetentionPolicy()
        val middle = state(
            line = "middle",
            lineIndex = 2,
            lineStartMs = 5_000L,
            lineEndMs = 9_000L,
            nextLine = "next",
            nextLineStartMs = 10_000L,
            positionMs = 20_000L,
            sampledAtElapsedMs = 20_000L
        )
        val result = policy.apply(middle)
        assertFalse(result.tailEligible)
    }

    @Test
    fun clearedWithoutPreviousLineIsNotRetained() {
        val policy = TailLineRetentionPolicy()
        val result = policy.apply(cleared())
        assertFalse(result.tailEligible)
        assertEquals("", result.state.line)
    }

    @Test
    fun lineWithoutAnyTimingIsNotRetained() {
        val policy = TailLineRetentionPolicy()
        val noTiming = state(
            line = "no timing",
            lineIndex = 3,
            lineStartMs = 0L,
            lineEndMs = 0L,
            positionMs = 5_000L,
            sampledAtElapsedMs = 5_000L
        )
        // 次路径要求 lineEnd > lineStart 或 lineStart > 0;两者皆 0 不进入尾奏。
        assertFalse(policy.apply(noTiming).tailEligible)
        // 主路径同样要求保留行有可用时间。
        val result = policy.apply(cleared(positionMs = 20_000L, sampledAtElapsedMs = 20_000L))
        assertFalse(result.tailEligible)
        assertEquals("", result.state.line)
    }

    @Test
    fun seekBackBeforeRetainedLineClearsRetention() {
        val policy = TailLineRetentionPolicy()
        policy.apply(lastLine())
        // 清行态但位置回到最后一行之前(seek 回退):不把旧行顶回来。
        val seekBack = cleared(positionMs = 2_000L, sampledAtElapsedMs = 2_000L)
        val result = policy.apply(seekBack)
        assertFalse(result.tailEligible)
        assertEquals("", result.state.line)
    }

    @Test
    fun resetDropsRetainedLine() {
        val policy = TailLineRetentionPolicy()
        policy.apply(lastLine())
        policy.reset()
        val result = policy.apply(cleared())
        assertFalse(result.tailEligible)
        assertEquals("", result.state.line)
    }

    @Test
    fun tailTimeoutActiveIsPerSurfaceAndHonoursDeadline() {
        // 同一快照(lineEnd=10_000),position=13_000:delay=3 已到点,delay=10 未到点。
        assertTrue(
            tailTimeoutActive(
                tailEligible = true,
                lineStartMs = 5_000L,
                lineEndMs = 10_000L,
                positionMs = 13_000L,
                sampledAtElapsedMs = 13_000L,
                speed = 1f,
                nowElapsedMs = 13_000L,
                delaySeconds = 3
            )
        )
        assertFalse(
            tailTimeoutActive(
                tailEligible = true,
                lineStartMs = 5_000L,
                lineEndMs = 10_000L,
                positionMs = 13_000L,
                sampledAtElapsedMs = 13_000L,
                speed = 1f,
                nowElapsedMs = 13_000L,
                delaySeconds = 10
            )
        )
        // 20_000 时两面都到点。
        assertTrue(
            tailTimeoutActive(
                tailEligible = true,
                lineStartMs = 5_000L,
                lineEndMs = 10_000L,
                positionMs = 20_000L,
                sampledAtElapsedMs = 20_000L,
                speed = 1f,
                nowElapsedMs = 20_000L,
                delaySeconds = 10
            )
        )
        // 非尾奏上下文直接 false。
        assertFalse(
            tailTimeoutActive(
                tailEligible = false,
                lineStartMs = 5_000L,
                lineEndMs = 10_000L,
                positionMs = 99_000L,
                sampledAtElapsedMs = 99_000L,
                speed = 1f,
                nowElapsedMs = 99_000L,
                delaySeconds = 3
            )
        )
    }

    @Test
    fun tailTimeoutActiveHandlesBeginOnlyAndPause() {
        // 只有 begin:lineStart 1_000 + (3 + 5)s = 9_000。
        assertFalse(
            tailTimeoutActive(
                tailEligible = true,
                lineStartMs = 1_000L,
                lineEndMs = 1_000L,
                positionMs = 8_999L,
                sampledAtElapsedMs = 8_999L,
                speed = 1f,
                nowElapsedMs = 8_999L,
                delaySeconds = 3
            )
        )
        assertTrue(
            tailTimeoutActive(
                tailEligible = true,
                lineStartMs = 1_000L,
                lineEndMs = 1_000L,
                positionMs = 9_000L,
                sampledAtElapsedMs = 9_000L,
                speed = 1f,
                nowElapsedMs = 9_000L,
                delaySeconds = 3
            )
        )
        // speed<=0 时位置不前进:停在 5_000 永远到不了 13_000。
        assertFalse(
            tailTimeoutActive(
                tailEligible = true,
                lineStartMs = 5_000L,
                lineEndMs = 10_000L,
                positionMs = 5_000L,
                sampledAtElapsedMs = 0L,
                speed = 0f,
                nowElapsedMs = 999_999L,
                delaySeconds = 3
            )
        )
    }
}
