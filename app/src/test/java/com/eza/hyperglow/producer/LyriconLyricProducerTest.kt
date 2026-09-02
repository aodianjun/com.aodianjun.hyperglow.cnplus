package com.eza.hyperglow.producer

import io.github.proify.lyricon.lyric.model.LyricWord as LyriconLyricWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.SubscriberInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [LyriconLyricProducer]: SDK callback → boundary mapping, and the
 * [TimingNavigator]-driven active-line computation (Phase 3).
 *
 * The listener callbacks are driven directly via the producer's internal listener objects. A
 * fixed fake clock (`{ 0L }`) is injected so [LyriconLyricProducer.emit] can run without
 * Android's [android.os.SystemClock].
 *
 * (Listener callback params of type [LyriconSubscriber] are ignored by the producer's callback
 * bodies — only the connection/state transitions matter — so we pass an unchecked null
 * reference, which is safe because the parameter is never dereferenced.)
 */
class LyriconLyricProducerTest {

    private val producer = LyriconLyricProducer { 0L }

    /**
     * Empty [LyriconSubscriber] stub. ConnectionListener callbacks receive a non-null
     * [LyriconSubscriber] (the SDK declares the param non-nullable), and the producer's callback
     * bodies never dereference it — but Kotlin's non-null assertion on a `null as` cast throws
     * NPE at the call site. A real (no-op) instance satisfies the contract without depending on
     * Mockito/MockK (not on the test classpath).
     */
    private val unusedSubscriber: LyriconSubscriber = object : LyriconSubscriber {
        override val subscriberInfo: SubscriberInfo = SubscriberInfo("test", "test")
        override fun addConnectionListener(listener: ConnectionListener) {}
        override fun removeConnectionListener(listener: ConnectionListener) {}
        override fun subscribeActivePlayer(listener: ActivePlayerListener): Boolean = false
        override fun unsubscribeActivePlayer(listener: ActivePlayerListener): Boolean = false
        override fun register() {}
        override fun unregister() {}
        override fun destroy() {}
    }

    private fun line(begin: Long, end: Long, text: String, words: List<LyriconLyricWord>? = null) =
        RichLyricLine(
            begin = begin,
            end = end,
            text = text,
            words = words,
            translation = "t-$text",
            roma = "r-$text"
        )

    private fun word(begin: Long, end: Long, text: String) = LyriconLyricWord(
        begin = begin, end = end, text = text
    )

    /** A 3-line song with a gap between line 0 and line 1 to test "previous line" behavior. */
    private fun threeLineSong(): Song = Song(
        id = "song-1",
        name = "Test Song",
        artist = "Test Artist",
        duration = 8_000L,
        lyrics = listOf(
            line(1_000, 3_000, "first", listOf(word(1_000, 1_500, "first"))),
            line(3_500, 5_000, "second", listOf(word(3_500, 4_000, "sec"), word(4_000, 5_000, "ond"))),
            line(5_000, 7_000, "third", words = null)
        )
    )

    // --- Connection listener mapping ---

    @Test
    fun initialConnectionIsDisconnectedAndStateIsNull() {
        assertEquals(ProducerConnection.DISCONNECTED, producer.connection.value)
        assertNull(producer.state.value)
    }

    @Test
    fun connectionListener_onConnected_mapsToConnected() {
        producer.connectionListener.onConnected(unusedSubscriber)
        assertEquals(ProducerConnection.CONNECTED, producer.connection.value)
    }

    @Test
    fun connectionListener_onReconnected_mapsToReconnected() {
        producer.connectionListener.onReconnected(unusedSubscriber)
        assertEquals(ProducerConnection.RECONNECTED, producer.connection.value)
    }

    @Test
    fun connectionListener_onDisconnected_mapsToDisconnectedAndClearsState() {
        producer.playerListener.onSongChanged(threeLineSong())
        assertTrue("song change emits state", producer.state.value != null)

        producer.connectionListener.onDisconnected(unusedSubscriber)

        assertEquals(ProducerConnection.DISCONNECTED, producer.connection.value)
        assertNull(producer.state.value)
    }

    @Test
    fun connectionListener_onConnectTimeout_mapsToConnectTimeoutAndClearsState() {
        producer.connectionListener.onConnectTimeout(unusedSubscriber)
        assertEquals(ProducerConnection.CONNECT_TIMEOUT, producer.connection.value)
        assertNull(producer.state.value)
    }

    // --- Active-line computation (Phase 3: TimingNavigator-driven) ---

    @Test
    fun onSongChanged_emitsMetadataOnlyStateBeforeFirstPosition() {
        producer.playerListener.onSongChanged(threeLineSong())

        val state = producer.state.value
        assertNotNull(state)
        assertEquals("lyricon", state!!.producerId)
        assertEquals("Test Song", state.title)
        assertEquals("Test Artist", state.artist)
        assertEquals(8_000L, state.durationMs)
        assertEquals("lyricon:song-1", state.trackUri)
        // No position yet → lineIndex -1, empty line, no words.
        assertEquals(-1, state.lineIndex)
        assertEquals("", state.line)
        assertNull(state.words)
        assertEquals(1, state.generation) // incremented on song change
    }

    @Test
    fun positionBeforeFirstLine_yieldsNoActiveLine() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(500L) // before line 0 (begin=1000)

        val state = producer.state.value!!
        assertEquals(-1, state.lineIndex)
        assertEquals("", state.line)
        assertNull(state.words)
        assertEquals(500L, state.positionMs)
    }

    @Test
    fun positionWithinFirstLine_selectsLine0WithItsWords() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(2_000L) // within line 0 [1000,3000]

        val state = producer.state.value!!
        assertEquals(0, state.lineIndex)
        assertEquals("first", state.line)
        assertEquals("r-first", state.romanizedLine)
        assertEquals("t-first", state.translatedLine)
        val words = state.words
        assertNotNull(words)
        assertEquals(1, words!!.size)
        assertEquals("first", words[0].text)
        assertEquals(1_000L, words[0].startMs)
        assertEquals(1_500L, words[0].endMs)
    }

    @Test
    fun positionInGapBetweenLines_showsPreviousLine() {
        // Gap: line 0 ends at 3000, line 1 begins at 3500. Position 3200 is in the gap.
        // findTargetIndex returns the last line with begin <= pos → index 0.
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(3_200L)

        val state = producer.state.value!!
        assertEquals(0, state.lineIndex)
        assertEquals("first", state.line)
    }

    @Test
    fun positionWithinSecondLine_selectsLine1WithTwoWords() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(4_200L) // within line 1 [3500,5000]

        val state = producer.state.value!!
        assertEquals(1, state.lineIndex)
        assertEquals("second", state.line)
        val words = state.words
        assertNotNull(words)
        assertEquals(2, words!!.size)
        assertEquals("sec", words[0].text)
        assertEquals(3_500L, words[0].startMs)
        assertEquals("ond", words[1].text)
        assertEquals(4_000L, words[1].startMs)
        assertEquals(5_000L, words[1].endMs)
    }

    @Test
    fun positionWithinLineWithoutWords_emitsNullWords() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(6_000L) // within line 2 [5000,7000], words=null

        val state = producer.state.value!!
        assertEquals(2, state.lineIndex)
        assertEquals("third", state.line)
        assertNull(state.words)
    }

    @Test
    fun positionAfterLastLine_clearsActiveLineInOutro() {
        // 最后一句歌词唱完后（position 越过其 end，进入尾奏/纯器乐段落），活动行应被清空
        // 显示 🎶 占位，而不是把最后一句滞留到歌曲结束。
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(8_500L) // beyond end of last line (7000)

        val state = producer.state.value!!
        assertEquals(-1, state.lineIndex)
        assertEquals("", state.line)
    }

    @Test
    fun lineChange_updatesWordsAndLineIndex() {
        producer.playerListener.onSongChanged(threeLineSong())
        // Line 0
        producer.playerListener.onPositionChanged(2_000L)
        val s0 = producer.state.value!!
        assertEquals(0, s0.lineIndex)
        assertEquals(1, s0.words!!.size)

        // Advance to line 1
        producer.playerListener.onPositionChanged(4_200L)
        val s1 = producer.state.value!!
        assertEquals(1, s1.lineIndex)
        assertEquals("second", s1.line)
        assertEquals(2, s1.words!!.size)
        // Sequence increments on each emit.
        assertTrue("sequence advances", s1.sequence > s0.sequence)
    }

    @Test
    fun positionOnlyUpdateWithinSameLine_reusesCachedWords() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(4_200L) // line 1
        val words1 = producer.state.value!!.words

        producer.playerListener.onPositionChanged(4_800L) // still line 1
        val words2 = producer.state.value!!.words

        // Same line → same cached word list instance (no reallocation).
        assertTrue("words cached across position-only update", words1 === words2)
        assertEquals(4_800L, producer.state.value!!.positionMs)
    }

    @Test
    fun seekTo_resetsNavigatorCacheAndRecomputesLine() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(4_200L) // line 1
        assertEquals(1, producer.state.value!!.lineIndex)

        // Seek back into line 0's range.
        producer.playerListener.onSeekTo(2_000L)

        val state = producer.state.value!!
        assertEquals(0, state.lineIndex)
        assertEquals("first", state.line)
        assertEquals(2_000L, state.positionMs)
    }

    @Test
    fun onPlaybackStateChanged_reEmitsWithUpdatedPlayingFlag() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(2_000L)
        val before = producer.state.value!!
        assertEquals(false, before.playing) // default

        producer.playerListener.onPlaybackStateChanged(true)

        val after = producer.state.value!!
        assertEquals(true, after.playing)
        assertEquals(1f, after.speed, 0.0001f)
        // Same line (position unchanged) → same words cached.
        assertEquals(before.words, after.words)
    }

    @Test
    fun onPlaybackStateChanged_pausingSetsSpeedToZero() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPlaybackStateChanged(false)

        val state = producer.state.value!!
        assertEquals(false, state.playing)
        assertEquals(0f, state.speed, 0.0001f)
    }

    @Test
    fun onSongChangedNull_clearsSongAndState() {
        producer.playerListener.onSongChanged(threeLineSong())
        assertTrue(producer.state.value != null)

        producer.playerListener.onSongChanged(null)

        assertNull(producer.state.value)
    }

    @Test
    fun onActiveProviderChangedNull_clearsState() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onActiveProviderChanged(null)
        assertNull(producer.state.value)
    }

    @Test
    fun songChange_incrementsGeneration() {
        producer.playerListener.onSongChanged(threeLineSong())
        val gen1 = producer.state.value!!.generation

        producer.playerListener.onSongChanged(threeLineSong())
        val gen2 = producer.state.value!!.generation

        assertTrue("generation increments on song change", gen2 > gen1)
    }

    @Test
    fun songWithNoLyrics_emitsMetadataOnlyStateOnPosition() {
        val noLyrics = Song(id = "nolyrics", name = "No Lyrics", artist = "A", duration = 1_000L)
        producer.playerListener.onSongChanged(noLyrics)
        producer.playerListener.onPositionChanged(500L)

        val state = producer.state.value!!
        assertEquals("No Lyrics", state.title)
        assertEquals(-1, state.lineIndex)
        assertEquals("", state.line)
        assertNull(state.words)
    }

    @Test
    fun producerId_isLyricon() {
        assertEquals(LyricSource.LYRICON, producer.id)
    }

    @Test
    fun trackUriIsSyntheticLyriconPrefix() {
        producer.playerListener.onSongChanged(threeLineSong())
        assertEquals("lyricon:song-1", producer.state.value!!.trackUri)
    }

    @Test
    fun trackUriFallsBackToNameWhenIdAbsent() {
        val noId = Song(id = null, name = "Fallback Name", artist = "A", duration = 1_000L)
        producer.playerListener.onSongChanged(noId)
        assertEquals("lyricon:Fallback Name", producer.state.value!!.trackUri)
    }

    // --- Phase 3: active-row fields (lyricKind / lineStartMs / lineEndMs / hasTimedLyrics /
    //     nextLineStartMs / ruby / layoutGroups / alignedRight) populated by emit() ---

    @Test
    fun rowFields_populatedFromActiveSyllableLine() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(2_000L) // line 0 [1000,3000], has words

        val state = producer.state.value!!
        assertEquals(LyricKind.SYLLABLE, state.lyricKind)
        assertEquals(true, state.hasTimedLyrics)
        assertEquals(1_000L, state.lineStartMs)
        assertEquals(3_000L, state.lineEndMs)
        // Next line begins at 3500 → nextLineStartMs.
        assertEquals(3_500L, state.nextLineStartMs)
        // Lyricon carries no alignment / ruby / layout-group concepts.
        assertEquals(false, state.alignedRight)
        assertTrue(state.ruby.isEmpty())
        assertTrue(state.layoutGroups.isEmpty())
    }

    @Test
    fun rowFields_populatedFromLineLevelLineWithoutWords() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(6_000L) // line 2 [5000,7000], words=null

        val state = producer.state.value!!
        assertEquals(LyricKind.LINE, state.lyricKind)
        assertEquals(5_000L, state.lineStartMs)
        assertEquals(7_000L, state.lineEndMs)
        // Line 2 is the last → no next line.
        assertNull(state.nextLineStartMs)
        // words null for a line-level line.
        assertNull(state.words)
        // hasTimedLyrics is song-level (other lines have timing).
        assertEquals(true, state.hasTimedLyrics)
    }

    @Test
    fun noLyricsSong_emitsNoneKindAndNoTimedLyrics() {
        val noLyrics = Song(id = "nolyrics", name = "No Lyrics", artist = "A", duration = 1_000L)
        producer.playerListener.onSongChanged(noLyrics)
        producer.playerListener.onPositionChanged(500L)

        val state = producer.state.value!!
        assertEquals(LyricKind.NONE, state.lyricKind)
        assertEquals(false, state.hasTimedLyrics)
        assertNull(state.nextLineStartMs)
        assertEquals(0L, state.lineStartMs)
        assertEquals(0L, state.lineEndMs)
    }

    @Test
    fun beforeFirstLine_lyricKindIsSongLevelAndNextLineStartIsFirstBegin() {
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(500L) // before line 0 (begin=1000)

        val state = producer.state.value!!
        assertEquals(-1, state.lineIndex)
        // No active line, but the song has timed lyrics with words → song-level SYLLABLE so the
        // engine can tell "timed lyrics exist, between lines" (INTERLUDE) from "no lyrics".
        assertEquals(LyricKind.SYLLABLE, state.lyricKind)
        assertEquals(true, state.hasTimedLyrics)
        assertEquals(1_000L, state.nextLineStartMs) // first line begins at 1000
        assertEquals(0L, state.lineStartMs) // no active row
        assertEquals(0L, state.lineEndMs)
    }

    @Test
    fun nextLineStartMs_advancesAsPositionMovesThroughSong() {
        producer.playerListener.onSongChanged(threeLineSong())

        producer.playerListener.onPositionChanged(2_000L) // before line 1 (3500)
        assertEquals(3_500L, producer.state.value!!.nextLineStartMs)

        producer.playerListener.onPositionChanged(4_200L) // before line 2 (5000)
        assertEquals(5_000L, producer.state.value!!.nextLineStartMs)

        producer.playerListener.onPositionChanged(6_000L) // past the last line's begin
        assertNull(producer.state.value!!.nextLineStartMs)
    }

    @Test
    fun songWithOnlyLineLevelLyrics_songLevelKindIsLineBeforeFirstPosition() {
        // All lines have words=null → song-level kind is LINE (not SYLLABLE) before first line.
        val lineLevel = Song(
            id = "ll", name = "LL", artist = "A", duration = 5_000L,
            lyrics = listOf(
                line(1_000, 2_000, "a", words = null),
                line(2_500, 3_500, "b", words = null)
            )
        )
        producer.playerListener.onSongChanged(lineLevel)
        producer.playerListener.onPositionChanged(500L)

        val state = producer.state.value!!
        assertEquals(LyricKind.LINE, state.lyricKind)
        assertEquals(true, state.hasTimedLyrics) // lines have end > begin
        assertEquals(1_000L, state.nextLineStartMs)

        // Within a line-level line, kind stays LINE.
        producer.playerListener.onPositionChanged(1_500L)
        assertEquals(LyricKind.LINE, producer.state.value!!.lyricKind)
    }

    // --- Position extrapolation (screen-off stall recovery) ---

    @Test
    fun positionStall_locksActiveLine_whilePlaying() {
        // issue #10(位置源冻结时外推超前):位置源 stalled 时真实位置不可知,外推只是猜测,
        // 绝不能用猜测位置推进歌词行(否则 AOD 歌词超前于真实播放、恢复瞬间又跳回)。
        // 歌词行索引只由真实位置驱动:stalled 期间行锁定,展示位置最多走完当前行。
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        // Real position update at clock=10000, within line 0 [1000,3000].
        producer.playerListener.onPositionChanged(2_000L)
        assertEquals(0, producer.state.value!!.lineIndex)
        assertEquals(2_000L, producer.state.value!!.positionMs)

        // Position stalls (shared-memory writer frozen by screen-off), wall-clock advances 1.5s.
        // The guessed position (3500) would select line 1 — but the line must stay locked on the
        // last *real* line (line 0); display position may only advance to the current line's end.
        clockValue = 11_500L
        producer.playerListener.onPositionChanged(2_000L) // same stalled value

        val state = producer.state.value!!
        assertEquals(0, state.lineIndex)             // locked, NOT advanced by the guess
        assertEquals("first", state.line)
        assertEquals(3_000L, state.positionMs)        // clamped to line 0's end, not 3500
    }

    @Test
    fun positionStall_doesNotExtrapolateWhenPaused() {
        // Pause freezes extrapolation: the real position is frozen, so the lyric position must
        // stop advancing too. A long pause must not drag the line forward (previously it kept
        // extrapolating and eventually reached the song end).
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(2_000L) // line 0 [1000,3000]

        // Pause, then a stalled callback arrives much later — the line must stay frozen.
        producer.playerListener.onPlaybackStateChanged(false)
        clockValue = 11_500L
        producer.playerListener.onPositionChanged(2_000L) // stalled while paused

        assertEquals(0, producer.state.value!!.lineIndex)
        assertEquals("first", producer.state.value!!.line)
        assertEquals(2_000L, producer.state.value!!.positionMs) // frozen, not extrapolated
    }

    @Test
    fun positionResume_selectsRealLine_andKeepsMonotonicDisplay() {
        // issue #10:stalled 期间行锁定在最后真实行;真实位置恢复后按真实位置重新选行。
        // 位置源恢复后首个真实值(即使略低于此前展示位)驱动行选择 —— 行索引不再跟随
        // 墙钟猜测,因此不会出现"外推超前 → 恢复瞬间跳回"。
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(2_000L) // line 0, real

        // Stall for 1.5s: line stays locked on line 0 (real position 2000), display clamped.
        clockValue = 11_500L
        producer.playerListener.onPositionChanged(2_000L)
        assertEquals(0, producer.state.value!!.lineIndex)

        // Resume with a real position inside line 1 [3500,5000] → the line is re-selected from the
        // real value (no longer held back by extrapolation).
        clockValue = 11_600L
        producer.playerListener.onPositionChanged(3_600L)

        val state = producer.state.value!!
        assertEquals(1, state.lineIndex)
        assertEquals("second", state.line)
        assertEquals(3_600L, state.positionMs)
    }

    @Test
    fun positionResume_materiallyBehind_isHonoredAsRewind() {
        // A real position that drops well below the locked line (seek / wrap-around / genuine
        // pause) must be honored as a rewind and re-select the line from the real value.
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(2_000L) // line 0, real

        // Stall for 1.5s: line stays locked on line 0.
        clockValue = 11_500L
        producer.playerListener.onPositionChanged(2_000L)
        assertEquals(0, producer.state.value!!.lineIndex)

        // A genuine rewind into line 0 territory is honored from the real value.
        clockValue = 11_600L
        producer.playerListener.onPositionChanged(2_500L)

        val state = producer.state.value!!
        assertEquals(0, state.lineIndex)
        assertEquals("first", state.line)
        assertEquals(2_500L, state.positionMs)
    }

    @Test
    fun positionResumed_stopsExtrapolationAndUsesRealValue() {
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(2_000L) // line 0, real

        // Stall for 1.5s: line stays locked on line 0.
        clockValue = 11_500L
        producer.playerListener.onPositionChanged(2_000L)
        assertEquals(0, producer.state.value!!.lineIndex)

        // Real position resumes (player process unfrozen) into line 1 → re-selected from real value.
        clockValue = 11_600L
        producer.playerListener.onPositionChanged(3_800L) // line 1 [3500,5000]

        val state = producer.state.value!!
        assertEquals(1, state.lineIndex)
        assertEquals("second", state.line)
        assertEquals(3_800L, state.positionMs)
    }

    @Test
    fun positionExtrapolation_pastSongEnd_locksActiveLine() {
        // issue #10(位置源冻结时外推超前):外推位置只是猜测,不能用来推进/清空歌词行。
        // 写端长冻结(90s,远超 45s 预算)时,行锁定在最后真实位置所在行,不清空、不占位、
        // 不推进;展示位置钳制到当前行尾。真实位置恢复后重新选行。
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong()) // duration=8000
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(6_000L) // line 2 [5000,7000]
        assertEquals(2, producer.state.value!!.lineIndex)

        // Stall for 90s: projected 96s ≥ duration 8s, but the line is held (not cleared).
        clockValue = 100_000L
        producer.playerListener.onPositionChanged(6_000L)

        val state = producer.state.value!!
        assertEquals(2, state.lineIndex)               // locked on the last real line
        assertEquals("third", state.line)
        assertEquals(7_000L, state.positionMs)         // clamped to the locked line's end
    }

    @Test
    fun positionExtrapolation_longStallWithinSong_locksLine() {
        // issue #3 + #10 统一语义:长 stall 不因猜测位置清空/占位,歌词保持显示在最后真实行。
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong().copy(duration = 180_000L))
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(6_000L) // line 2 [5000,7000]
        assertEquals(2, producer.state.value!!.lineIndex)

        // Stall for 60s (past the 45s budget, projected 66s << 180s) → line stays locked.
        clockValue = 10_000L + 60_000L
        producer.playerListener.onPositionChanged(6_000L)

        var state = producer.state.value!!
        assertEquals(2, state.lineIndex)
        assertEquals("third", state.line)
        assertEquals(7_000L, state.positionMs)

        // Stall even longer → still stable, no churn, line never cleared.
        clockValue = 10_000L + 100_000L
        producer.playerListener.onPositionChanged(6_000L)
        state = producer.state.value!!
        assertEquals(2, state.lineIndex)
        assertEquals(7_000L, state.positionMs)

        // Real position eventually resumes (screen-on) → line re-selected from the real value.
        clockValue = 10_000L + 100_500L
        producer.playerListener.onPositionChanged(3_000L) // line 0 [1000,3000]
        state = producer.state.value!!
        assertEquals(3_000L, state.positionMs)
        assertEquals(0, state.lineIndex)
    }

    @Test
    fun positionExtrapolation_pastSongEnd_holdsStableLockedLine() {
        // issue #9 + #10:长 stall 越过歌尾后不得死循环清空/占位,行保持锁定且位置稳定,
        // 避免 60Hz 重复投递与 SystemUI 无去重的重建风暴。
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong()) // duration=8000
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(6_000L) // line 2
        assertEquals(2, producer.state.value!!.lineIndex)

        // Stall past the song end → line locked, position stable at the line's end.
        clockValue = 100_000L
        producer.playerListener.onPositionChanged(6_000L)
        assertEquals(2, producer.state.value!!.lineIndex)

        // A much later stalled callback must not churn the state.
        clockValue = 200_000L
        producer.playerListener.onPositionChanged(6_000L)

        val state = producer.state.value!!
        assertEquals(2, state.lineIndex)
        assertEquals("third", state.line)
        assertEquals(7_000L, state.positionMs) // stable, held at the locked line's end
    }

    @Test
    fun positionExtrapolation_stallAcrossSongEnd_holdsLockedLine() {
        // issue #9 核心死循环回归防护:写入端在歌曲中段冻结、外推越过歌尾。行必须保持锁定,
        // 不得重置 extrapolating 后每帧重走 "越界→清空→占位"。
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong()) // duration=8000
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(6_000L) // line 2
        assertEquals(2, producer.state.value!!.lineIndex)

        // Freeze for 3s: projected 9_000 ≥ duration 8_000 → line held, position at line end.
        clockValue = 13_000L
        producer.playerListener.onPositionChanged(6_000L)
        var state = producer.state.value!!
        assertEquals(7_000L, state.positionMs)
        assertEquals(2, state.lineIndex)

        // Later stalled callbacks: fully stable, no per-frame churn.
        clockValue = 14_000L
        producer.playerListener.onPositionChanged(6_000L)
        state = producer.state.value!!
        assertEquals(7_000L, state.positionMs)
        assertEquals(2, state.lineIndex)

        // Far past the 45s budget → same stable hold.
        clockValue = 50_000L
        producer.playerListener.onPositionChanged(6_000L)
        state = producer.state.value!!
        assertEquals(7_000L, state.positionMs)
        assertEquals(2, state.lineIndex)
    }

    @Test
    fun realPositionBeyondDuration_isCappedToStablePlaceholder() {
        // issue #9 日志铁证:Doze 下共享内存写入端交付的真实位置基准本身已远超歌曲时长
        // (base=424922ms, duration=172913ms),且两个越界值交替到达。每个越界值都必须
        // 钳制到 duration 并稳定占位,不得随每帧越界值抖动重建;真实位置恢复到时长内
        // (亮屏/回绕)后歌词立即恢复。
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong()) // duration=8000
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(5_000L) // line 2 [5000,7000]
        assertEquals(2, producer.state.value!!.lineIndex)

        // An out-of-range real position arrives → capped at duration, line cleared.
        clockValue = 10_050L
        producer.playerListener.onPositionChanged(20_000L)
        var state = producer.state.value!!
        assertEquals(8_000L, state.positionMs)
        assertEquals(-1, state.lineIndex)

        // A second out-of-range value alternates in (the two interleaved bases in the log)
        // → still stably capped at duration, no per-frame jitter.
        clockValue = 10_100L
        producer.playerListener.onPositionChanged(19_000L)
        state = producer.state.value!!
        assertEquals(8_000L, state.positionMs)
        assertEquals(-1, state.lineIndex)

        // A stalled callback on the out-of-range base (extrapolation base already past the end)
        // → must not re-enter the extrapolation flip loop, stays capped.
        clockValue = 10_150L
        producer.playerListener.onPositionChanged(19_000L)
        state = producer.state.value!!
        assertEquals(8_000L, state.positionMs)
        assertEquals(-1, state.lineIndex)

        // Real position resumes within the song (screen-on / wrap-around) → lyrics return.
        clockValue = 10_200L
        producer.playerListener.onPositionChanged(3_600L) // line 1 [3500,5000]
        state = producer.state.value!!
        assertEquals(3_600L, state.positionMs)
        assertEquals(1, state.lineIndex)
    }

    @Test
    fun lastLineClearsAfterItsEnd_entersInstrumentalOutro() {
        // 最后一句歌词唱完后（position 越过其 end 但歌曲仍在尾奏/纯器乐段落），活动行应被
        // 清空显示 🎶 占位，而不是把最后一句滞留到歌曲结束。
        producer.playerListener.onSongChanged(threeLineSong()) // duration=8000, last line end=7000
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(7_500L) // > 7000 (last line end), < 8000 (duration)

        val state = producer.state.value!!
        assertEquals(-1, state.lineIndex)
        assertEquals("", state.line)
    }

    // --- Position-silence watchdog (12:26 capture: callback path died, age=519s) ---

    @Test
    fun watchdog_firesOnTotalSilenceWhilePlaying() {
        // 12:26 故障链:onPositionChanged 完全停发(冻结的写入器仍会以 ~60Hz 回调旧值,
        // 所以"完全静默"= 回调路径本身死了),connection 却停在 CONNECTED。播放中静默
        // 超过阈值必须触发强制重建订阅,而不是等用户重启 app。
        assertTrue(
            shouldForceResubscribePositionFeed(
                silenceMs = 25_000L,
                playing = true,
                sinceLastAttemptMs = 60_000L
            )
        )
    }

    @Test
    fun watchdog_ignoresSilenceWhilePaused() {
        // 真暂停时位置流安静是预期行为,不是故障:不得重建订阅。
        assertFalse(
            shouldForceResubscribePositionFeed(
                silenceMs = 25_000L,
                playing = false,
                sinceLastAttemptMs = 60_000L
            )
        )
    }

    @Test
    fun watchdog_toleratesBriefSilenceWhilePlaying() {
        // 短于阈值的静默(正常的数据突发间隙)不得触发。
        assertFalse(
            shouldForceResubscribePositionFeed(
                silenceMs = LyriconLyricProducer.POSITION_SILENCE_RESUBSCRIBE_MS,
                playing = true,
                sinceLastAttemptMs = 60_000L
            )
        )
    }

    @Test
    fun watchdog_respectsCooldownBetweenAttempts() {
        // 上次尝试后仍在冷却窗口内:即使静默持续也不得反复轰炸 IPC,每个冷却窗口至多重试一次。
        assertFalse(
            shouldForceResubscribePositionFeed(
                silenceMs = 519_000L,
                playing = true,
                sinceLastAttemptMs = LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS
            )
        )
    }

    @Test
    fun positionExtrapolation_afterSongEnd_realPositionRestoresLine() {
        // 外推期间行锁定在最后真实行(issue #10)后,一旦真实位置恢复(亮屏 writer 恢复),
        // 应重新选中正确行 —— 而不是从被外推污染的位置选行。
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong()) // duration=8000
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(6_000L) // line 2 (within [5000,7000])
        assertEquals(2, producer.state.value!!.lineIndex)

        // 长 stall(90s):行保持锁定在最后真实行(line 2),不清空、不占位。
        clockValue = 100_000L
        producer.playerListener.onPositionChanged(6_000L)
        assertEquals(2, producer.state.value!!.lineIndex)
        assertEquals("third", producer.state.value!!.line)

        // 真实位置恢复(亮屏),从真实值重新选中正确行。
        clockValue = 100_200L
        producer.playerListener.onPositionChanged(4_000L)

        val state = producer.state.value!!
        assertEquals(1, state.lineIndex) // [3500,5000] contains 4000
        assertEquals(4_000L, state.positionMs)
    }

    // --- Song change position reset ---

    @Test
    fun onSongChanged_resetsPositionToZero() {
        var clockValue = 5_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPositionChanged(6_000L) // line 2
        assertEquals(2, producer.state.value!!.lineIndex)

        // Switch to a new song → position must reset, not carry over from previous song.
        clockValue = 5_100L
        producer.playerListener.onSongChanged(threeLineSong())

        val state = producer.state.value!!
        assertEquals(0L, state.positionMs)
        assertEquals(-1, state.lineIndex)
        assertEquals("", state.line)
    }

    // --- Residual position rejection (song change) ---

    @Test
    fun residualPreviousSongPosition_isRejectedAfterSongChange() {
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        // Song A: advance to position 64861 (would be line 2 in our 3-line song, idx=2).
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(6_000L)
        assertEquals(2, producer.state.value!!.lineIndex)

        // Switch to a new song. Shared memory still holds 6000 from the previous song.
        clockValue = 10_100L
        producer.playerListener.onSongChanged(threeLineSong())

        // The residual 6000 arrives — must be rejected, not accepted as the new song's position.
        // With playback active, we extrapolate from 0 instead.
        clockValue = 10_200L
        producer.playerListener.onPositionChanged(6_000L) // residual

        val state = producer.state.value!!
        // Extrapolated: 0 + (10200 - 10100) = 100ms → before first line → idx=-1.
        assertEquals(-1, state.lineIndex)
        assertEquals("", state.line)
        assertTrue(
            "extrapolated position must be small, not 6000",
            state.positionMs < 1_000L
        )

        // A real position update (different from 6000) is accepted and disables filtering.
        clockValue = 10_300L
        producer.playerListener.onPositionChanged(1_500L) // real, line 0 [1000,3000]

        val state2 = producer.state.value!!
        assertEquals(0, state2.lineIndex)
        assertEquals("first", state2.line)
        assertEquals(1_500L, state2.positionMs)
    }

    @Test
    fun residualRejection_persistsPastWindow_untilRealPositionArrives() {
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(6_000L)

        // 切歌在 clock=10000。
        producer.playerListener.onSongChanged(threeLineSong())

        // 即使远超旧的时间窗口(60s)，残留的 6000 仍必须被拒绝：否则会用旧歌位置在新歌词表
        // 定位出错误行。拒绝后从 0 外推。
        clockValue = 10_000L + 60_001L
        producer.playerListener.onPositionChanged(6_000L)

        var state = producer.state.value!!
        // 行锁定且无活动行(新歌起点),展示位置停在真实基准 0 —— 残留旧位置 6000 未被接受。
        assertEquals(-1, state.lineIndex)
        assertEquals("", state.line)
        assertEquals(0L, state.positionMs)

        // 真实新歌位置(不同于残留 6000)到达后，恢复接受。
        clockValue = 10_000L + 61_000L
        producer.playerListener.onPositionChanged(1_500L) // line 0 [1000,3000]
        state = producer.state.value!!
        assertEquals(0, state.lineIndex)
        assertEquals("first", state.line)
        assertEquals(1_500L, state.positionMs)
    }

    @Test
    fun seekTo_clearsResidualRejection() {
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(6_000L)

        clockValue = 10_100L
        producer.playerListener.onSongChanged(threeLineSong())

        // Seek deliberately to 6000 — should be honored even right after song change.
        producer.playerListener.onSeekTo(6_000L)

        val state = producer.state.value!!
        assertEquals(2, state.lineIndex)
        assertEquals(6_000L, state.positionMs)
    }

    @Test
    fun seekTo_rejectsPreSeekResidualPosition() {
        // 拖动进度条后,共享内存可能短暂回传 seek 前的旧位置。该旧值 != seek 目标,若被当作
        // 真实位置接受会把歌词打回旧位置。必须在 seek 后的窗口内拒绝它。
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(4_200L) // line 1
        assertEquals(1, producer.state.value!!.lineIndex)

        // Seek forward to 6000 (line 2). Immediately after, shared memory still returns 4200.
        clockValue = 10_100L
        producer.playerListener.onSeekTo(6_000L)
        assertEquals(2, producer.state.value!!.lineIndex)
        assertEquals(6_000L, producer.state.value!!.positionMs)

        // Stale pre-seek value 4200 arrives → must be rejected, not snapped back to line 1.
        clockValue = 10_200L
        producer.playerListener.onPositionChanged(4_200L)
        val state = producer.state.value!!
        assertEquals(2, state.lineIndex) // stays on seek target
        assertEquals(6_000L + (10_200L - 10_100L), state.positionMs) // extrapolated from 6000
    }

    @Test
    fun seekTo_residualRejection_stopsWhenRealPositionArrives() {
        // 一旦 seek 后的真实位置(不同于 pre-seek)到达,拒绝停止,恢复接受共享内存位置。
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(4_200L) // line 1

        clockValue = 10_100L
        producer.playerListener.onSeekTo(6_000L) // line 2

        // 拒绝 stale pre-seek value。
        clockValue = 10_200L
        producer.playerListener.onPositionChanged(4_200L)
        assertEquals(2, producer.state.value!!.lineIndex)

        // 真实 post-seek 位置到达(可能略有偏移,如 6100)→ 接受。
        clockValue = 10_300L
        producer.playerListener.onPositionChanged(6_100L)
        val state = producer.state.value!!
        assertEquals(2, state.lineIndex)
        assertEquals(6_100L, state.positionMs)
    }

    // --- Playback state change extrapolation reset ---

    @Test
    fun resumeAfterPause_doesNotJumpForwardByPauseDuration() {
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(2_000L) // line 0, real

        // Pause for a long time.
        producer.playerListener.onPlaybackStateChanged(false)
        clockValue = 100_000L // 90s later

        // Resume. Without resetting the extrapolation clock, the next stalled callback would
        // extrapolate to 2000 + 90000 = 92000 (way past song end). After the fix, the clock
        // resets on resume, so extrapolation starts from 0 elapsed.
        producer.playerListener.onPlaybackStateChanged(true)
        clockValue = 100_100L // 100ms after resume
        producer.playerListener.onPositionChanged(2_000L) // stalled

        val state = producer.state.value!!
        // Extrapolated: 2000 + 100 = 2100ms → still in line 0 [1000,3000].
        assertEquals(0, state.lineIndex)
        assertEquals("first", state.line)
        assertTrue(
            "position must be ~2100, not jumped by pause duration",
            state.positionMs < 3_000L
        )
    }

    @Test
    fun pauseDuringStall_resumeKeepsCurrentLine_notStaleBase() {
        // issue #10 追加实测(暂停→继续,网易云《灰色鹦鹉》):位置源在 AOD 下早已 stalled,
        // Lyricon 的 lastRealPositionMs 滞后于媒体真实位置(~6.7s)。旧实现暂停只冻结外推、
        // 暂停期间 stalled 回调又把展示位置拉回陈旧的 lastRealPositionMs,继续后从陈旧基准
        // 重新外推 → 歌词行跳回更早的行再爬行。修复:暂停瞬间把展示位置 re-base 为基准,
        // 后续 stalled 回调拒绝陈旧基准值,继续后行保持在暂停行。
        var clockValue = 10_000L
        val producer = LyriconLyricProducer { clockValue }

        // 模拟 issue 场景:位置源陈旧在 2000(line 0),但外推展示已到 ~3800(line 1)。
        producer.playerListener.onSongChanged(threeLineSong())
        producer.playerListener.onPlaybackStateChanged(true)
        producer.playerListener.onPositionChanged(2_000L) // line 0, real (stale source value)
        assertEquals(0, producer.state.value!!.lineIndex)

        // Position source stalls while playback continues → extrapolation reaches line 1's range,
        // but the line index stays locked on the last real line (line 0) per issue #10.
        clockValue = 11_800L // 1.8s later
        producer.playerListener.onPositionChanged(2_000L) // still stalled at 2000
        assertEquals(0, producer.state.value!!.lineIndex)
        assertEquals("first", producer.state.value!!.line)

        // User pauses (media really is at ~3800 — the displayed extrapolated position).
        producer.playerListener.onPlaybackStateChanged(false)
        // The pre-pause base (2000) is now stale; the pause re-based onto the displayed ~3000
        // (clamped to line 0's end). Stalled callbacks after pause must not yank it back to 2000.
        clockValue = 12_000L
        producer.playerListener.onPositionChanged(2_000L) // stale still arriving
        assertEquals(0, producer.state.value!!.lineIndex)
        assertEquals("first", producer.state.value!!.line)

        // Resume: extrapolation must continue from the pause point (~3000), NOT crawl back from
        // the stale 2000 base. A resumed stall keeps the line locked on line 0 without a jump back.
        clockValue = 13_000L
        producer.playerListener.onPlaybackStateChanged(true)
        clockValue = 13_100L
        producer.playerListener.onPositionChanged(2_000L) // stale still arriving (writer frozen)
        val state = producer.state.value!!
        assertEquals(0, state.lineIndex)         // NOT re-crawled to an older line
        assertTrue("position must be >= 3000 (pause point), not 2000 stale",
            state.positionMs >= 3_000L)
    }
}
