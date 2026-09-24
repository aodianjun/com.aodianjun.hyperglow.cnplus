package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ElrcParser] — the elrc/lrc parsing used by [LyricInfoLyricProducer].
 *
 * Covers plain LRC, enhanced LRC (elrc) word timing, repeated-line timestamps, unsorted input
 * normalization, and active-line selection by position.
 */
class ElrcParserTest {

    @Test
    fun parsesPlainLrc_withMultipleTimestampsAndInterleavedMetadata() {
        val lrc = listOf(
            "[ar:Artist]",
            "[ti:Title]",
            "前奏...",
            "[00:01.500]Hello world",
            "[00:05.000][00:05.500]Repeat me"
        ).joinToString("\n")

        val lines = ElrcParser.parse(lrc)

        assertEquals(3, lines.size)
        assertEquals(1_500L, lines[0].startMs)
        assertEquals("Hello world", lines[0].text)
        // Plain LRC carries no word timing -> empty (non-null) words list.
        assertTrue(lines[0].words!!.isEmpty())
        // Repeated timestamp line expands into two TimedLines.
        assertEquals(5_000L, lines[1].startMs)
        assertEquals("Repeat me", lines[1].text)
        assertEquals(5_500L, lines[2].startMs)
        assertEquals("Repeat me", lines[2].text)
    }

    @Test
    fun parsesEnhancedLrc_withWordTiming() {
        val lrc = "[00:01.000]<00:01.000>He<00:01.500>llo<00:02.000>World"

        val lines = ElrcParser.parse(lrc)

        assertEquals(1, lines.size)
        val line = lines[0]
        assertEquals("HelloWorld", line.text)
        val words = line.words
        assertTrue(words != null)
        assertEquals(3, words!!.size)
        assertEquals("He", words[0].text)
        assertEquals(1_000L, words[0].startMs)
        assertEquals(1_500L, words[0].endMs)
        assertEquals("llo", words[1].text)
        assertEquals(1_500L, words[1].startMs)
        assertEquals(2_000L, words[1].endMs)
        assertEquals("World", words[2].text)
    }

    @Test
    fun lineEnds_fillFromNextLineStart_orDefaultDuration() {
        val lrc = "[00:01.000]First\n[00:05.000]Second"
        val lines = ElrcParser.parse(lrc)

        assertEquals(2, lines.size)
        // First line's end = second line's start.
        assertEquals(5_000L, lines[0].endMs)
        // Last line's end = start + default duration.
        assertEquals(5_000L + 4_000L, lines[1].endMs)

        val custom = ElrcParser.parse(lrc, defaultLineDurationMs = 2_000L)
        assertEquals(5_000L + 2_000L, custom[1].endMs)
    }

    @Test
    fun sortsUnsortedInputByStartTime() {
        val lrc = "[00:05.000]Later\n[00:01.000]Earlier"
        val lines = ElrcParser.parse(lrc)

        assertEquals(2, lines.size)
        assertEquals("Earlier", lines[0].text)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals("Later", lines[1].text)
        assertEquals(5_000L, lines[1].startMs)
    }

    @Test
    fun handlesFractionVariants_twoAndOneDigit() {
        // ".5" → 500ms, ".05" → 50ms.
        val lrc = "[00:01.5]A\n[00:02.05]B\n[00:03.5]C"
        val lines = ElrcParser.parse(lrc)

        assertEquals(1_500L, lines[0].startMs)
        assertEquals(2_050L, lines[1].startMs)
        assertEquals(3_500L, lines[2].startMs)
    }

    @Test
    fun ignoresLinesWithoutLeadingTimestamp_andEmptyInput() {
        assertEquals(emptyList<ElrcParser.TimedLine>(), ElrcParser.parse("no timestamp here"))
        assertEquals(emptyList<ElrcParser.TimedLine>(), ElrcParser.parse(""))
    }

    // --- activeLineAt ---

    @Test
    fun activeLineAt_returnsLastLineAtOrBeforePosition() {
        val lines = ElrcParser.parse("[00:01.000]One\n[00:05.000]Two\n[00:10.000]Three")

        assertEquals("One", ElrcParser.activeLineAt(lines, 1_000)?.text)
        assertEquals("One", ElrcParser.activeLineAt(lines, 4_999)?.text)
        assertEquals("Two", ElrcParser.activeLineAt(lines, 5_000)?.text)
        assertEquals("Three", ElrcParser.activeLineAt(lines, 99_000)?.text)
    }

    @Test
    fun activeLineAt_beforeFirstTimestamp_returnsFirstLine() {
        val lines = ElrcParser.parse("[00:05.000]Start")
        assertEquals("Start", ElrcParser.activeLineAt(lines, 0)?.text)
    }

    @Test
    fun activeLineAt_emptyReturnsNull() {
        assertNull(ElrcParser.activeLineAt(emptyList(), 1_000))
    }

    // --- activeLinePastEndOrNull (结尾歌词尾奏清空) ---

    @Test
    fun activeLinePastEndOrNull_clearsAfterLastLineEnd() {
        // 最后一行 end = 10000 + 4000(defaultLineDurationMs) = 14000。
        val lines = ElrcParser.parse("[00:01.000]One\n[00:05.000]Two\n[00:10.000]Three")

        assertEquals("Three", activeLinePastEndOrNull(lines, 10_000L)?.text)
        assertEquals("Three", activeLinePastEndOrNull(lines, 13_999L)?.text)
        assertNull(activeLinePastEndOrNull(lines, 14_000L)) // 越过最后一行 end
        assertNull(activeLinePastEndOrNull(lines, 99_000L))
    }

    @Test
    fun activeLinePastEndOrNull_keepsLineWithinInterlude() {
        // 中间行之间的间奏（position 超过上一行 end 但未到下一行 begin）仍返回上一句，
        // 与 activeLineAt 的「最后一条 start <= pos」语义一致，仅兜住真正的结尾。
        val lines = ElrcParser.parse("[00:01.000]One\n[00:05.000]Two\n[00:10.000]Three")
        assertEquals("One", activeLinePastEndOrNull(lines, 4_999L)?.text)
    }

    // --- 零宽字符剥离(Bridge LyricTextSanitizer 同集合,issue #68) ---

    @Test
    fun stripsZeroWidthCharacters_fromLineAndWordText() {
        val lrc = "[00:01.000]Hel\u200Blo\uFEFF\n[00:05.000]<00:05.000>W\u206Bo"
        val lines = ElrcParser.parse(lrc)

        assertEquals("Hello", lines[0].text)
        assertTrue(lines[0].words!!.isEmpty())
        assertEquals("Wo", lines[1].text)
        assertEquals("Wo", lines[1].words!![0].text)
    }

    // --- 词级时间轴可疑降级(Bridge LyricTimingRepair 同启发) ---

    @Test
    fun downgradesNonIncreasingWordTiming_toLineLevel() {
        // 词起点乱序(09.000 < 10.000):词时间轴整体不可信 → 降级为行级。
        val lines = ElrcParser.parse("[00:10.000]<00:10.000>A<00:09.000>B")

        assertEquals(1, lines.size)
        assertEquals("AB", lines[0].text)
        assertTrue(lines[0].words!!.isEmpty())
    }

    @Test
    fun downgradesSuspiciousInlineWordGap_toLineLevel() {
        // 4 词、单个 9s 行内间隙且占比 9*3 >= 11*2:伪逐字形态 → 降级。
        val lines = ElrcParser.parse(
            "[00:10.000]<00:10.000>前<00:11.000>奏<00:20.000>间<00:21.000>奏"
        )

        assertTrue(lines[0].words!!.isEmpty())
    }

    @Test
    fun keepsDenseWordTiming_withLongTailNote() {
        // 7 词、9s 尾间隙但占比 9*3=27 < 15*2=30:真实的两段式长句,保留逐字。
        val starts = listOf(0L, 1_000L, 2_000L, 3_000L, 4_000L, 5_000L, 6_000L, 15_000L)
        assertFalse(ElrcParser.shouldDowngradeWordTiming(starts))
    }

    @Test
    fun shouldDowngradeWordTiming_predicateContract() {
        // 单词不判;乱序/同刻判;8s+ 间隙且词数 <=4 判;短间隙不判。
        assertFalse(ElrcParser.shouldDowngradeWordTiming(listOf(0L)))
        assertTrue(ElrcParser.shouldDowngradeWordTiming(listOf(3_000L, 1_000L)))
        assertTrue(ElrcParser.shouldDowngradeWordTiming(listOf(1_000L, 1_000L)))
        assertTrue(ElrcParser.shouldDowngradeWordTiming(listOf(0L, 9_000L)))
        assertFalse(ElrcParser.shouldDowngradeWordTiming(listOf(0L, 1_000L)))
    }
}