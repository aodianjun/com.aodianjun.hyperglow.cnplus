package com.example.hyperglow.aitranslationwords

import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FragmentTimingTest {

    private fun line(
        begin: Long = 0L,
        end: Long = 0L,
        words: List<PluginWord>? = null,
    ) = PluginLyricLine(begin = begin, end = end, text = "x", words = words)

    private fun word(text: String, begin: Long, end: Long) =
        PluginWord(begin = begin, end = end, duration = end - begin, text = text)

    @Test
    fun noWordsMeansNoWordTiming() {
        assertNull(timedTokenWindows(line()))
        assertNull(timedTokens(line()))
    }

    @Test
    fun allZeroWindowsMeanNoWordTiming() {
        // 逐行源会给每行一个整行词（begin==end==0）：不是逐字时间轴。
        val row = line(words = listOf(word("整行", 0, 0)))
        assertNull(timedTokenWindows(row))
    }

    @Test
    fun blankWordsAreDroppedAndRealWindowsWin() {
        val row = line(
            words = listOf(
                word("hello", 0, 100),
                word("   ", 100, 100),
                word("world", 100, 200),
            )
        )
        assertEquals(
            listOf(TokenWindow(0, 100), TokenWindow(100, 200)),
            timedTokenWindows(row),
        )
        assertEquals(listOf("hello", "world"), timedTokens(row)?.map { it.text })
    }

    @Test
    fun onlyBlankWordWithWindowStillCountsAsUntimed() {
        // 有效窗口必须属于一个文本非空的词；占位词有窗口不算逐字。
        val row = line(words = listOf(word("", 0, 500), word("hi", 100, 100)))
        assertNull(timedTokenWindows(row))
    }

    @Test
    fun alignRequiresEqualLengths() {
        val result = alignFragmentsToTokens(
            fragments = listOf("你", "好"),
            tokens = listOf(TokenWindow(0, 100)),
            lineStartMs = 0,
            lineEndMs = 200,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun alignMapsEachFragmentToItsTokenWindow() {
        val result = alignFragmentsToTokens(
            fragments = listOf("你好", "世界"),
            tokens = listOf(TokenWindow(0, 100), TokenWindow(100, 250)),
            lineStartMs = 0,
            lineEndMs = 250,
        )
        assertEquals(
            listOf(
                TimedFragment("你好", 0, 100),
                TimedFragment("世界", 100, 250),
            ),
            result,
        )
    }

    @Test
    fun emptyFragmentIsSkipped() {
        val result = alignFragmentsToTokens(
            fragments = listOf("", "世界"),
            tokens = listOf(TokenWindow(0, 100), TokenWindow(100, 200)),
            lineStartMs = 0,
            lineEndMs = 200,
        )
        assertEquals(listOf(TimedFragment("世界", 100, 200)), result)
    }

    @Test
    fun invalidWindowAppendsToPreviousFragment() {
        val result = alignFragmentsToTokens(
            fragments = listOf("你好", "世界"),
            tokens = listOf(TokenWindow(0, 100), TokenWindow(100, 100)),
            lineStartMs = 0,
            lineEndMs = 200,
        )
        assertEquals(listOf(TimedFragment("你好世界", 0, 100)), result)
    }

    @Test
    fun invalidWindowBeforeAnyFragmentBecomesPrefixOfNext() {
        val result = alignFragmentsToTokens(
            fragments = listOf("你好", "世界"),
            tokens = listOf(TokenWindow(50, 50), TokenWindow(100, 200)),
            lineStartMs = 0,
            lineEndMs = 200,
        )
        assertEquals(listOf(TimedFragment("你好世界", 100, 200)), result)
    }

    @Test
    fun noValidWindowFallsBackToLineWindow() {
        val result = alignFragmentsToTokens(
            fragments = listOf("你好", "世界"),
            tokens = listOf(TokenWindow(0, 0), TokenWindow(0, 0)),
            lineStartMs = 0,
            lineEndMs = 500,
        )
        assertEquals(listOf(TimedFragment("你好世界", 0, 500)), result)
    }

    @Test
    fun noValidWindowAndNoLineWindowKeepsTextWithDegenerateWindow() {
        // 退化行窗也不丢文本:渲染侧对「全零窗」回落行窗口合成整行译文,丢掉文本会让辅助行短一截。
        val result = alignFragmentsToTokens(
            fragments = listOf("你好", "世界"),
            tokens = listOf(TokenWindow(0, 0), TokenWindow(0, 0)),
            lineStartMs = 0,
            lineEndMs = 0,
        )
        assertEquals(listOf(TimedFragment("你好世界", 0, 0)), result)
    }

    @Test
    fun identicalAdjacentWindowsAreMerged() {
        val result = alignFragmentsToTokens(
            fragments = listOf("你", "好", "世界"),
            tokens = listOf(
                TokenWindow(0, 100),
                TokenWindow(0, 100),
                TokenWindow(100, 200),
            ),
            lineStartMs = 0,
            lineEndMs = 200,
        )
        assertEquals(
            listOf(
                TimedFragment("你好", 0, 100),
                TimedFragment("世界", 100, 200),
            ),
            result,
        )
    }

    @Test
    fun windowsAreClampedToNonNegative() {
        val result = alignFragmentsToTokens(
            fragments = listOf("你"),
            tokens = listOf(TokenWindow(-500, 100)),
            lineStartMs = -500,
            lineEndMs = 100,
        )
        assertEquals(listOf(TimedFragment("你", 0, 100)), result)
    }

    @Test
    fun lineWindowFallbackIsClamped() {
        val result = alignFragmentsToTokens(
            fragments = listOf("你"),
            tokens = listOf(TokenWindow(0, 0)),
            lineStartMs = -200,
            lineEndMs = 50,
        )
        assertEquals(listOf(TimedFragment("你", 0, 50)), result)
    }
}
