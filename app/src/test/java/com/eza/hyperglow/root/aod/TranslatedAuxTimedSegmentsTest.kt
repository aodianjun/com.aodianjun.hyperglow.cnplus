package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻译辅助行逐字段的取舍（[translatedTimedSegments]，实机 `translatedTimedLines` 与单测同源）。
 *
 * 钉住三条边界：片段文本直接相连构成整行译文（不另插分隔符）、空文本片段不占位、
 * 全零窗（占位词形态）整组拒绝——零窗段被 `timedWordProgress` 判成恒亮，整行会呈静态全亮，
 * 此时回落行窗口 + 行内几何合成才是正确观感。
 */
class TranslatedAuxTimedSegmentsTest {

    private fun word(text: String, startMs: Long, endMs: Long) =
        AodCanvasWord(text, "", startMs, endMs, true)

    private val width = { text: String -> text.length.toFloat() }

    @Test
    fun segmentsKeepOrderTextAndTimingWithoutSeparators() {
        val segments = translatedTimedSegments(
            listOf(
                word("我", 0L, 600L),
                word(" 爱", 600L, 1_200L),
                word(" 你", 1_200L, 2_000L)
            ),
            width
        )!!

        assertEquals(listOf("我", " 爱", " 你"), segments.map { it.text })
        assertEquals(0L, segments.first().startMs)
        assertEquals(2_000L, segments.last().endMs)
        assertEquals(1_200L, segments[2].startMs)
        assertEquals(0f, segments.first().gapAfter, 0f)
        // 直接相连即整行译文:片段自带的分隔符原样保留,这里既不另插也不删除。
        assertEquals("我 爱 你", segments.joinToString("") { it.text })
    }

    @Test
    fun blankFragmentsDoNotTakeSpace() {
        val segments = translatedTimedSegments(
            listOf(word("", 0L, 100L), word("好", 100L, 400L)),
            width
        )!!

        assertEquals(listOf("好"), segments.map { it.text })
    }

    @Test
    fun allZeroWindowFallsBackToLineLevelSynthesis() {
        assertNull(
            translatedTimedSegments(
                listOf(word("占位", 0L, 0L), word("词", 500L, 500L)),
                width
            )
        )
    }

    @Test
    fun singleValidWindowKeepsWholeSegmentSet() {
        val segments = translatedTimedSegments(
            listOf(word("占位", 0L, 0L), word("好", 100L, 400L)),
            width
        )!!

        assertEquals(listOf("占位", "好"), segments.map { it.text })
        assertEquals(0L, segments.first().endMs)
    }

    @Test
    fun emptyInputsProduceNoTimedLine() {
        assertNull(translatedTimedSegments(emptyList(), width))
        assertNull(translatedTimedSegments(listOf(word("", 0L, 500L)), width))
        assertTrue(
            translatedTimedSegments(listOf(word("", 0L, 500L)), width).isNullOrEmpty()
        )
    }
}
