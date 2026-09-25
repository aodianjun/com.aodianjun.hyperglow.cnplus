package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 布局引擎纯核心测试:测量经 [TextMeasurePort] 注入(等宽假测量),
 * 锁定预览/实机共用的断行算法与行数门控,防止两端再次各自演化。
 */
class LyricLayoutEngineTest {

    /** 等宽假测量:每字符 10f;breakAt 返回可容纳字符数。 */
    private fun mono(charWidth: Float = 10f) = TextMeasurePort(
        measure = { it.length * charWidth },
        breakAt = { text, maxWidth -> (maxWidth / charWidth).toInt().coerceIn(0, text.length) }
    )

    @Test
    fun wrapTextBreaksAtMeasurementBoundary() {
        val result = layoutOriginalLines(
            original = "abcdefghij",
            words = emptyList(),
            ruby = emptyList(),
            layoutGroups = emptyList(),
            metrics = mono(),
            availableWidth = 50f,
            lineLimit = 5,
            wordGapPx = 10f,
            wrap = true,
            adaptiveSectioning = false
        )
        assertEquals(listOf("abcde", "fghij"), result.lines.map { it.text })
        assertEquals(0 to 5, result.lines[0].charStart to result.lines[0].charEnd)
        assertEquals(5 to 10, result.lines[1].charStart to result.lines[1].charEnd)
        assertEquals(false, result.timed)
    }

    @Test
    fun lineLimitCapsLineCountAndZeroMeansUnbounded() {
        val capped = layoutOriginalLines(
            "abcdefghij", emptyList(), emptyList(), emptyList(), mono(),
            availableWidth = 50f, lineLimit = 1, wordGapPx = 10f, wrap = true, adaptiveSectioning = false
        )
        assertEquals(listOf("abcde"), capped.lines.map { it.text })
        // lineLimit=0 = 不限行数(与实机 resolvedLyricLayoutLineLimit 口径一致)。
        val unbounded = layoutOriginalLines(
            "abcdefghij", emptyList(), emptyList(), emptyList(), mono(),
            availableWidth = 50f, lineLimit = 0, wordGapPx = 10f, wrap = true, adaptiveSectioning = false
        )
        assertEquals(2, unbounded.lines.size)
    }

    @Test
    fun clipOverflowKeepsSingleLine() {
        val result = layoutOriginalLines(
            "abcdefghij", emptyList(), emptyList(), emptyList(), mono(),
            availableWidth = 50f, lineLimit = 5, wordGapPx = 10f, wrap = false, adaptiveSectioning = false
        )
        assertEquals(listOf("abcdefghij"), result.lines.map { it.text })
    }

    @Test
    fun wordLinesSplitOnAttachedRanges() {
        val words = listOf(
            AodCanvasWord("aaa", "", 0L, 100L, boundaryAfter = true, sourceStart = 0, sourceEnd = 3),
            AodCanvasWord("bbb", "", 100L, 200L, boundaryAfter = true, sourceStart = 4, sourceEnd = 7),
            AodCanvasWord("ccc", "", 200L, 300L, boundaryAfter = true, sourceStart = 8, sourceEnd = 11)
        )
        val result = layoutOriginalLines(
            "aaa bbb ccc", words, emptyList(), emptyList(), mono(),
            availableWidth = 70f, lineLimit = 5, wordGapPx = 10f, wrap = true, adaptiveSectioning = false
        )
        assertEquals(true, result.timed)
        assertEquals(2, result.lines.size)
        assertEquals(listOf("aaa bbb", "ccc"), result.lines.map { it.text })
        assertEquals(listOf(2, 1), result.lines.map { it.words.size })
    }

    @Test
    fun secondaryWrapCapsTwoLinesAndBalancesTokens() {
        val lines = layoutSecondaryLines(
            text = "aa bb cc dd",
            metrics = mono(),
            availableWidth = 30f,
            preferredLines = 1,
            wrap = true,
            adaptiveSectioning = true
        )
        assertEquals(2, lines.size)
        assertEquals("aa", lines[0].text)
        assertEquals("bb cc dd", lines[1].text)
    }

    @Test
    fun secondarySingleLineWhenWrapDisabled() {
        val lines = layoutSecondaryLines(
            text = "aa bb cc dd",
            metrics = mono(),
            availableWidth = 30f,
            preferredLines = 1,
            wrap = false,
            adaptiveSectioning = true
        )
        assertEquals(1, lines.size)
        assertEquals("aa bb cc dd", lines[0].text)
    }

    @Test
    fun metadataSplitsOnSeparatorAndCapsTwoLines() {
        val lines = layoutMetadataLines(
            text = "Song Title · The Artist Name",
            metrics = mono(),
            availableWidth = 50f
        )
        assertEquals(listOf("Song", "Title"), lines.map { it.text })
        assertTrue(lines.size <= MAX_SECONDARY_LAYOUT_LINES)
    }
}
