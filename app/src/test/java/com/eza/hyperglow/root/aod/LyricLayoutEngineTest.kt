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
    fun missingWordOffsetsUseAuthoredSeparatorInsteadOfFallbackGap() {
        // 插件词表不带原文区间:按行文本对齐补全后词距取原文空格实测宽(mono=10f),
        // 不再回退 wordGapPx(8f)——布局宽与整段绘制文本/预览同源(50f 而非 48f)。
        val words = listOf(
            AodCanvasWord("aa", "", 0L, 100L, boundaryAfter = true),
            AodCanvasWord("bb", "", 100L, 200L, boundaryAfter = true)
        )
        val result = layoutOriginalLines(
            "aa bb", words, emptyList(), emptyList(), mono(),
            availableWidth = 500f, lineLimit = 5, wordGapPx = 8f, wrap = true, adaptiveSectioning = false
        )
        assertEquals(1, result.lines.size)
        assertEquals(50f, result.lines[0].width, 0.0001f)
        assertEquals("aa bb", result.lines[0].text)
        assertEquals(0 until 2, result.lines[0].words[0].offset)
        assertEquals(3 until 5, result.lines[0].words[1].offset)
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
    fun metadataSplitsOnLineBreaksAndCapsThreeLines() {
        // 换行分隔符:每个切片一行;歌名/歌手/专辑最多 MAX_METADATA_LAYOUT_LINES(3) 行。
        // 宽度 100f 保证每个切片(最长 "Artist"=60f)放得下一行,不触发超宽 token 切片。
        val three = layoutMetadataLines(
            text = "Song\nArtist\nAlbum",
            metrics = mono(),
            availableWidth = 100f
        )
        assertEquals(listOf("Song", "Artist", "Album"), three.map { it.text })
        val four = layoutMetadataLines(
            text = "One\nTwo\nThree\nFour",
            metrics = mono(),
            availableWidth = 100f
        )
        assertEquals(MAX_METADATA_LAYOUT_LINES, four.size)
        assertTrue(four.size <= MAX_METADATA_LAYOUT_LINES)
    }

    @Test
    fun metadataInlineMiddleDotIsNotALineBoundary() {
        // 行内分隔符可能含 '·'(如 " · "),不再被当作行边界;放得下时保持单行。
        val lines = layoutMetadataLines(
            text = "Song · Artist",
            metrics = mono(),
            availableWidth = 200f
        )
        assertEquals(listOf("Song · Artist"), lines.map { it.text })
    }
}
