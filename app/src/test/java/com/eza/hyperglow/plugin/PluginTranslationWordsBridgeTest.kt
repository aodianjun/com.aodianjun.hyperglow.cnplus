package com.eza.hyperglow.plugin

import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricWord
import com.eza.hyperglow.producer.ProducerRenderModes
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 插件逐字翻译词表（`PluginLyricField.TRANSLATION_WORDS`）过桥（[PluginSongBridge.enrichState]）。
 *
 * 插件按「唱第 i 个原词时显示的译文片段」回传词表，宿主把活动行的词表映射成生产者的
 * `translationWords`，供息屏翻译辅助行按真实词窗点亮（渲染侧见 AodLyricCanvasView
 * 的 translatedTimedLines）。本组用例钉住三条边界：只取活动行、空词表不覆盖已有词表、
 * 未声明该字段时原样保留（与 keepUnlessBlank 同一「增强而非替换」口径）。
 */
class PluginTranslationWordsBridgeTest {

    private fun renderModes() = ProducerRenderModes(
        weight = "Medium", textSize = "normal", textSizeCustom = 100,
        secondary = "Both", animation = "BetterLyrics", glow = "Off",
        lineSyncFill = "Top to bottom", overflow = "Wrap", transition = "Fade up",
        font = "noto"
    )

    private fun state(
        positionMs: Long,
        translationWords: List<LyricWord> = emptyList()
    ) = LyricProducerState(
        producerId = "spicy",
        generation = 1,
        sequence = 1L,
        status = "ready",
        trackUri = "spicy:song",
        title = "song", artist = "", album = "", imageId = "",
        line = "main", romanizedLine = "", translatedLine = "主",
        lineIndex = 0, positionMs = positionMs, durationMs = 30_000L,
        sampledAtElapsedMs = 0L, speed = 1f, playing = true,
        receivedAtElapsedMs = 0L, words = null, renderModes = renderModes(),
        translationWords = translationWords
    )

    private fun row(
        begin: Long,
        end: Long,
        text: String,
        translation: String? = null,
        translationWords: List<PluginWord>? = null
    ) = PluginLyricLine(
        begin = begin, end = end, duration = end - begin,
        metadata = PluginMetadata(values = mapOf("role" to "LEAD")),
        text = text, translation = translation, translationWords = translationWords
    )

    private fun patched(
        rows: List<PluginLyricLine>,
        st: LyricProducerState,
        changedLyricFields: Set<PluginLyricField>
    ) = PatchedSong(
        sessionKey = PluginSongBridge.sessionKey(st),
        song = PluginSong(lyrics = rows),
        changedSongFields = setOf(PluginSongField.LYRICS),
        changedLyricFields = changedLyricFields
    )

    @Test
    fun activeRowTranslationWordsAreMappedWithTiming() {
        val st = state(positionMs = 500L)
        val words = listOf(
            PluginWord(begin = 0L, end = 600L, duration = 600L, text = "我"),
            PluginWord(begin = 600L, end = 1_200L, duration = 600L, text = "爱"),
            PluginWord(begin = 1_200L, end = 2_000L, duration = 800L, text = "你")
        )
        val rows = listOf(
            row(0L, 2_000L, "I love you", translation = "我爱你", translationWords = words),
            row(2_000L, 4_000L, "next line", translation = "下一行")
        )
        val out = PluginSongBridge.enrichState(
            st,
            patched(
                rows, st,
                setOf(PluginLyricField.TRANSLATION, PluginLyricField.TRANSLATION_WORDS)
            )
        )
        assertEquals(3, out.translationWords.size)
        assertEquals(listOf("我", "爱", "你"), out.translationWords.map { it.text })
        assertEquals(0L, out.translationWords[0].startMs)
        assertEquals(1_200L, out.translationWords[2].startMs)
        assertEquals(2_000L, out.translationWords[2].endMs)
        // 词表文本直接相连即整行译文(西文词间的空格由插件写在片段内,宿主不另插分隔符)。
        assertEquals("我爱你", out.translationWords.joinToString("") { it.text })
    }

    /** 非活动行的词表不参与:位置在第二行时取第二行的词表。 */
    @Test
    fun onlyActiveRowWordsAreApplied() {
        val st = state(positionMs = 2_500L)
        val rows = listOf(
            row(0L, 2_000L, "first", translationWords = listOf(PluginWord(0L, 2_000L, 2_000L, "甲"))),
            row(2_000L, 4_000L, "second", translationWords = listOf(PluginWord(2_000L, 4_000L, 2_000L, "乙")))
        )
        val out = PluginSongBridge.enrichState(
            st,
            patched(rows, st, setOf(PluginLyricField.TRANSLATION_WORDS))
        )
        assertEquals(listOf("乙"), out.translationWords.map { it.text })
    }

    /** 声明了字段但词表为空/缺失:保留生产者已有词表,不被抹掉。 */
    @Test
    fun blankPluginWordsDoNotWipeExistingProducerWords() {
        val existing = listOf(LyricWord("旧", "", 0L, 1_000L, true))
        val st = state(positionMs = 500L, translationWords = existing)
        val rows = listOf(row(0L, 2_000L, "main", translation = "主"))
        val out = PluginSongBridge.enrichState(
            st,
            patched(rows, st, setOf(PluginLyricField.TRANSLATION_WORDS))
        )
        assertEquals(existing, out.translationWords)
    }

    /** 未声明 TRANSLATION_WORDS:生产者已有词表原样保留(与未声明 WORDS 同口径)。 */
    @Test
    fun undeclaredTranslationWordsKeepProducerValue() {
        val existing = listOf(LyricWord("旧", "", 0L, 1_000L, true))
        val st = state(positionMs = 500L, translationWords = existing)
        val rows = listOf(
            row(
                0L, 2_000L, "main", translation = "主",
                translationWords = listOf(PluginWord(0L, 2_000L, 2_000L, "新"))
            )
        )
        val out = PluginSongBridge.enrichState(
            st,
            patched(rows, st, setOf(PluginLyricField.TRANSLATION))
        )
        assertTrue(out.translationWords == existing)
    }
}
