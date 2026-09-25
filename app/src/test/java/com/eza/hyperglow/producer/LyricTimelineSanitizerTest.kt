package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [LyricTimelineSanitizer] — 非刻意重叠清洗(500ms/100ms/10% 三档)与
 * 行时间戳词级对齐。级联示例(A-B 有意、A-C 顺带截断)为 issue #68 第 13 节的判定表用例。
 */
class LyricTimelineSanitizerTest {

    private fun line(startMs: Long, endMs: Long, text: String = "t") =
        ElrcParser.TimedLine(startMs, endMs, text, null)

    private fun words(vararg spans: Pair<Long, Long>) = spans.map { (s, e) ->
        LyricWord("w", "", s, e, false)
    }

    @Test
    fun cascadeExample_intentionalThenIncidentalTruncates() {
        // A 0–3000、B 1000–2500:重叠 500ms ≥ 500 → 有意,保留;A–C 2950:重叠 50ms → 截断。
        val out = LyricTimelineSanitizer.sanitizeUnintentionalOverlaps(
            listOf(line(0, 3000, "A"), line(1000, 2500, "B"), line(2950, 3950, "C"))
        )
        assertEquals(2950L, out[0].endMs)
        assertEquals(2500L, out[1].endMs)
        assertEquals(3950L, out[2].endMs)
    }

    @Test
    fun smallOverlapTruncated() {
        val out = LyricTimelineSanitizer.sanitizeUnintentionalOverlaps(
            listOf(line(0, 3000, "A"), line(2980, 5000, "B"))
        )
        assertEquals(2980L, out[0].endMs)
    }

    @Test
    fun midOverlap_keptWhenBeyondTenPercentOfNext_truncatedOtherwise() {
        // 重叠 200ms、下一行时长 1200ms:200 > 120(10%) → 有意保留。
        val kept = LyricTimelineSanitizer.sanitizeUnintentionalOverlaps(
            listOf(line(0, 2000, "A"), line(1800, 3000, "B"))
        )
        assertEquals(2000L, kept[0].endMs)
        // 重叠 200ms、下一行时长 3000ms:200 ≤ 300(10%) → 截断。
        val cut = LyricTimelineSanitizer.sanitizeUnintentionalOverlaps(
            listOf(line(0, 2000, "A"), line(1800, 4800, "B"))
        )
        assertEquals(1800L, cut[0].endMs)
    }

    @Test
    fun noOverlapReturnsSameInstance() {
        val lines = listOf(line(0, 1000), line(1000, 2000))
        assertSame(lines, LyricTimelineSanitizer.sanitizeUnintentionalOverlaps(lines))
    }

    @Test
    fun wordReset_multiWordAnchorsLineToWordSpan() {
        val lines = listOf(
            ElrcParser.TimedLine(900, 5000, "ab", words(1000L to 2000L, 2000L to 4000L))
        )
        val out = LyricTimelineSanitizer.resetLineTimestampsFromWords(lines)
        assertEquals(1000L, out[0].startMs)
        assertEquals(4000L, out[0].endMs)
    }

    @Test
    fun wordReset_singleZeroWordBackfilledFromLine() {
        val lines = listOf(
            ElrcParser.TimedLine(1000, 4000, "x", words(0L to 0L))
        )
        val out = LyricTimelineSanitizer.resetLineTimestampsFromWords(lines)
        assertEquals(1000L, out[0].words!![0].startMs)
        assertEquals(4000L, out[0].words!![0].endMs)
        assertEquals(1000L, out[0].startMs)
    }

    @Test
    fun wordReset_noWordsUnchanged() {
        val lines = listOf(line(1000, 4000))
        assertSame(lines, LyricTimelineSanitizer.resetLineTimestampsFromWords(lines))
    }

    @Test
    fun snapshotRows_overlapTruncatedKeepingSupplementalLanes() {
        // 快照行走同一套规整;translation/roma/role 原样保留。
        val rows = listOf(
            LyricSongRow(0, 3000, "A", translation = "ta", roma = "ra", role = "LEAD"),
            LyricSongRow(2980, 5000, "B")
        )
        val out = LyricTimelineSanitizer.sanitizeSnapshotRows(rows)
        assertEquals(2980L, out[0].endMs)
        assertEquals("ta", out[0].translation)
        assertEquals("ra", out[0].roma)
        assertEquals("LEAD", out[0].role)
        assertEquals(5000L, out[1].endMs)
    }

    @Test
    fun snapshotRows_wordAnchoringApplies() {
        val rows = listOf(
            LyricSongRow(900, 5000, "ab", words = words(1000L to 2000L, 2000L to 4000L))
        )
        val out = LyricTimelineSanitizer.sanitizeSnapshotRows(rows)
        assertEquals(1000L, out[0].startMs)
        assertEquals(4000L, out[0].endMs)
    }

    @Test
    fun snapshotRows_cleanInputReturnsSameInstance() {
        val rows = listOf(
            LyricSongRow(0, 1000, "A"),
            LyricSongRow(1000, 2000, "B")
        )
        assertSame(rows, LyricTimelineSanitizer.sanitizeSnapshotRows(rows))
    }
}
