package com.eza.hyperglow.plugin

import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件层行窗/词窗归一测试（188 缺口的插件层钉子，接入
 * [PluginChainMerger.normalizeMergedTimeline]）。
 *
 * 合并语义（[PluginChainMerger.mergeWithReason]）按插件声明整份覆盖词表（文本 + 时间戳）后，
 * 合并文档的行窗必须与插件词窗自洽：插件词窗越行窗 → 行窗扩到并集；插件行窗远超可唱估时
 * 且词级跨距可信 → 向词对齐；正常拖尾逐值不动；未声明 WORDS（宿主词表）时合并结果原样
 * 返回——宿主词表已在生产者 ingest 归一，插件层不得重复归一宿主词窗。
 */
class PluginTimelineNormalizeTest {

    private fun words(vararg spans: Pair<Long, Long>): List<PluginWord> =
        spans.map { (begin, end) ->
            PluginWord(begin = begin, end = end, duration = end - begin, text = "字")
        }

    private fun hostSong(row: PluginLyricLine) = PluginSong(
        id = "superlyric:song",
        name = "song",
        artist = "artist",
        album = "album",
        duration = 60_000L,
        lyrics = listOf(row)
    )

    private fun wordsResult(
        row: PluginLyricLine,
        mode: PluginLyricsUpdateMode = PluginLyricsUpdateMode.REPLACE
    ) = PluginSongResult(
        song = PluginSong(id = "superlyric:song", lyrics = listOf(row)),
        changedFields = setOf(PluginSongField.LYRICS),
        lyricsUpdateMode = mode,
        changedLyricFields = setOf(PluginLyricField.WORDS)
    )

    private fun merge(current: PluginSong, result: PluginSongResult): PluginSong =
        PluginChainMerger.mergeWithReason(current, result).song!!

    @Test
    fun pluginWordsBeyondLineWindowExpandMergedLineWindow() {
        // ① 插件词窗尾部越出行窗 1s:行窗 [1000,4000]、词窗并集 [1200,5000] →
        // 合并后行窗扩到并集 [1000,5000](原行首保留),词表原样。
        val current = hostSong(
            PluginLyricLine(begin = 1_000, end = 4_000, text = "六字歌词文本")
        )
        val merged = merge(
            current,
            wordsResult(
                PluginLyricLine(
                    begin = 1_000, end = 4_000, text = "六字歌词文本",
                    words = words(1_200L to 3_000L, 3_000L to 5_000L)
                )
            )
        )
        val normalized = PluginChainMerger.normalizeMergedTimeline(merged, pluginDeclaredWords = true)
        val row = normalized.lyrics!!.single()
        assertEquals(1_000L, row.begin)
        assertEquals(5_000L, row.end)
        assertEquals(2, row.words?.size)
        // 归一后行窗覆盖词窗并集(不再自相矛盾)。
        assertTrue(row.words!!.maxOf { it.end } <= row.end)
    }

    @Test
    fun pluginGrossWindowAnchorsToPlausiblePluginWords() {
        // ② 插件行窗 17.85s 对 10 字估时 3.5s(远超)且词窗跨距 3.642s 可信 → 向词对齐。
        val current = hostSong(PluginLyricLine(begin = 0L, end = 20_000L, text = "旧行"))
        val merged = merge(
            current,
            wordsResult(
                PluginLyricLine(
                    begin = 20_210, end = 38_060, text = "或有可能慢慢地去摸索",
                    words = words(35_358L to 36_500L, 36_500L to 39_000L)
                )
            )
        )
        val normalized = PluginChainMerger.normalizeMergedTimeline(merged, pluginDeclaredWords = true)
        val row = normalized.lyrics!!.single()
        assertEquals(35_358L, row.begin)
        assertEquals(39_000L, row.end)
    }

    @Test
    fun pluginNormalTailKeepsMergedWindowByteIdentical() {
        // 钉子(③):插件行窗 8000ms、词窗并集 [3000,6000]、9 字估时 3150ms 是正常拖尾形状
        // (8000 ≤ 3150×2+2000 = 8300,恰好不满足 gross 的严格大于)→ 行窗两端逐值原样,
        // 且返回原实例(逐字节不动,不产生新的行表)。
        val current = hostSong(PluginLyricLine(begin = 0L, end = 4_000L, text = "旧行"))
        val merged = merge(
            current,
            wordsResult(
                PluginLyricLine(
                    begin = 0L, end = 8_000L, text = "未花光心智人便透支",
                    words = words(3_000L to 4_500L, 4_500L to 6_000L)
                )
            )
        )
        val normalized = PluginChainMerger.normalizeMergedTimeline(merged, pluginDeclaredWords = true)
        val row = normalized.lyrics!!.single()
        assertEquals(0L, row.begin)
        assertEquals(8_000L, row.end)
        assertSame(merged, normalized)
    }

    @Test
    fun hostWordsWithoutWordsDeclarationStayUntouched() {
        // 未声明 WORDS(宿主词表)时行为不变:宿主词窗越行窗也原样返回——宿主词表已在生产者
        // ingest 过同一道门,插件层不做 DTO 对比推断、不重复归一宿主词窗。
        val contradictory = PluginLyricLine(
            begin = 1_000, end = 4_000, text = "六字歌词文本",
            words = words(1_200L to 3_000L, 3_000L to 5_000L)
        )
        val current = hostSong(contradictory)
        val patched = PluginSongResult(
            song = PluginSong(
                id = "superlyric:song",
                lyrics = listOf(contradictory.copy(text = "改过的六字文本"))
            ),
            changedFields = setOf(PluginSongField.LYRICS),
            lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
            changedLyricFields = setOf(PluginLyricField.TEXT)
        )
        val merged = merge(current, patched)
        val normalized = PluginChainMerger.normalizeMergedTimeline(merged, pluginDeclaredWords = false)
        assertSame(merged, normalized)
        val row = normalized.lyrics!!.single()
        assertEquals(1_000L, row.begin)
        assertEquals(4_000L, row.end)
        assertEquals("改过的六字文本", row.text)
        assertEquals(2, row.words?.size)
    }
}
