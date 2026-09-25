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

    @Test
    fun wordReset_mayDisorder_sortRestoresTimelineOrder() {
        // 词时序与行时序交叉的脏源:行1 标 0-3000 词在 1500-2500,行2 标 1000-5000
        // 词在 1100-1200 → 锚定后行1 start=1500 反而在行2 start=1100 之后。
        val lines = listOf(
            ElrcParser.TimedLine(0, 3000, "A", words(1500L to 2500L)),
            ElrcParser.TimedLine(1000, 5000, "B", words(1100L to 1200L))
        )
        val retimed = LyricTimelineSanitizer.resetLineTimestampsFromWords(lines)
        assertEquals(1500L, retimed[0].startMs)
        assertEquals(1100L, retimed[1].startMs)
        val ordered = LyricTimelineSanitizer.sortedByTimeline(retimed)
        assertEquals("B", ordered[0].text)
        assertEquals(1100L, ordered[0].startMs)
        assertEquals("A", ordered[1].text)
        assertEquals(1500L, ordered[1].startMs)
    }

    @Test
    fun disorderedInputMissesTruncation_sortedInputCatchesIt() {
        // 词锚定后的乱序形状:后行 B(1100-1550) 的尾越过前行 A(1500-2500) 的头 50ms。
        // 直接喂重叠仲裁:内层 break 假设升序,A 对 B 算出的 overlap 方向不对、提前退出,
        // 漏掉 B→A 的 50ms 截断;先 stable 排序再仲裁才逮住。
        val a = ElrcParser.TimedLine(1500, 2500, "A", null)
        val b = ElrcParser.TimedLine(1100, 1550, "B", null)
        val missed = LyricTimelineSanitizer.sanitizeUnintentionalOverlaps(listOf(a, b))
        assertEquals(1550L, missed[1].endMs)
        val ordered = LyricTimelineSanitizer.sortedByTimeline(listOf(a, b))
        val caught = LyricTimelineSanitizer.sanitizeUnintentionalOverlaps(ordered)
        assertEquals("B", caught[0].text)
        assertEquals(1500L, caught[0].endMs)
        assertEquals(2500L, caught[1].endMs)
    }

    @Test
    fun sortedByTimeline_sortedInputReturnsSameInstance() {
        val lines = listOf(line(0, 1000), line(1000, 2000))
        assertSame(lines, LyricTimelineSanitizer.sortedByTimeline(lines))
    }

    @Test
    fun sortedByTimeline_tiesKeepOriginalRelativeOrder() {
        // 稳定排序:同 startMs 平局保原相对序(AMLL 用 originalIndex 同义)。
        val first = line(1000, 2000, "first")
        val second = line(1000, 3000, "second")
        val later = line(500, 900, "later")
        val ordered = LyricTimelineSanitizer.sortedByTimeline(listOf(first, second, later))
        assertEquals("later", ordered[0].text)
        assertEquals("first", ordered[1].text)
        assertEquals("second", ordered[2].text)
    }

    @Test
    fun snapshotRows_reorderKeepsLanesWithTheirRows() {
        // 词锚定打乱序时,translation/roma/role 必须跟着各自的行走,不能因重排串行。
        val rows = listOf(
            LyricSongRow(0, 3000, "A", translation = "ta", roma = "ra", words = words(1500L to 2500L)),
            LyricSongRow(1000, 5000, "B", translation = "tb", roma = "rb", words = words(1100L to 1200L))
        )
        val out = LyricTimelineSanitizer.sanitizeSnapshotRows(rows)
        assertEquals("B", out[0].text)
        assertEquals("tb", out[0].translation)
        assertEquals("rb", out[0].roma)
        assertEquals("A", out[1].text)
        assertEquals("ta", out[1].translation)
        assertEquals("ra", out[1].roma)
    }
}
