package com.example.hyperglow.aisegment

import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineWindowsTest {

    private fun row(
        text: String,
        begin: Long = 1_000L,
        end: Long = 4_000L,
        words: List<PluginWord>? = null,
        translation: String? = null,
    ) = PluginLyricLine(
        begin = begin,
        end = end,
        duration = end - begin,
        isAlignedRight = false,
        metadata = PluginMetadata(values = mapOf("role" to "LEAD")),
        text = text,
        words = words,
        translation = translation,
    )

    @Test
    fun splitProducesProportionalChildWindows() {
        val children = LineWindows.splitRow(row("一二三四五六七"), listOf("一二", "三四五六七"))
        assertEquals(2, children.size)
        // 显示长度 2/7 → 末子行收口 row.end;首子行 end = begin + window·2/7
        assertEquals(1_000L, children[0].begin)
        assertEquals(1_000L + 3_000L * 2 / 7, children[0].end)
        assertEquals(children[0].end, children[1].begin)
        assertEquals(4_000L, children[1].end)
        // 子行首尾相接、整体覆盖源行窗口
        assertEquals(4_000L - 1_000L, children.sumOf { it.end - it.begin })
    }

    @Test
    fun sourceWordsLandInTheChildContainingTheirFirstChar() {
        val words = listOf(
            PluginWord(1_000L, 1_800L, 800L, "一二"),
            PluginWord(1_800L, 2_600L, 800L, "三四"),
            PluginWord(2_600L, 4_000L, 1_400L, "五六七"),
        )
        val children = LineWindows.splitRow(row("一二三四五六七", words = words), listOf("一二", "三四五六七"))
        // 词首字符位置:一二→0(子行0), 三四→2(子行1), 五六七→4(子行1)
        assertEquals(listOf("一二"), children[0].words.map { it.text })
        assertEquals(listOf("三四", "五六七"), children[1].words.map { it.text })
        // 词时间戳钳制进子行窗口
        children[0].words.forEach {
            assertTrue(it.begin >= children[0].begin && it.end <= children[0].end)
        }
        children[1].words.forEach {
            assertTrue(it.begin >= children[1].begin && it.end <= children[1].end)
        }
    }

    @Test
    fun synthesizesTokenWordsWhenSourceHasNoWordTimeline() {
        val children = LineWindows.splitRow(row("一二三四五六七"), listOf("一二三四五六七"))
        val words = children.single().words
        // CJK 逐字合成 7 个词块,时间单调递增且覆盖窗口
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "七"), words.map { it.text })
        assertEquals(1_000L, words.first().begin)
        assertEquals(4_000L, words.last().end)
        for (i in 1 until words.size) {
            assertTrue(words[i].begin >= words[i - 1].end - 1L)
        }
    }

    @Test
    fun toPluginLinesKeepsLeadRoleAndInheritsAlignment() {
        val row = row("一二三", translation = "one two three")
        val children = LineWindows.splitRow(row, listOf("一", "二三"))
        val lines = LineWindows.toPluginLines(row, children)
        assertEquals(2, lines.size)
        lines.forEach { line ->
            assertEquals("LEAD", line.metadata?.values?.get("role"))
        }
        // 翻译由处理器跟随首个子行,toPluginLines 不带
        assertNull(lines[0].translation)
    }
}
