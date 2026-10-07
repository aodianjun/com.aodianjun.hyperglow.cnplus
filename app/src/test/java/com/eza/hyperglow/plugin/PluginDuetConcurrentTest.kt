package com.eza.hyperglow.plugin

import com.eza.hyperglow.producer.LyricDuetLine
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.ProducerRenderModes
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 插件链替换歌词后按**最终行表**重选并发行（[PluginSongBridge.enrichState] 的 LYRICS 分支）。
 *
 * AMLL TTML 的对唱 agent 行与 x-bg 和声行只存在于插件返回的行表里（生产者行表在 REPLACE
 * 后不被采纳，见类注释），而生产者侧的候选只从它自己的行表选——源行表首尾相接（网易云 LRC
 * 常态，`end == next.begin`）时恒为 -1，并发行永远不出现。本组用例钉住：重选发生在插件链
 * 之后、判定仍是同一条「共享窗口 ≥ 1s」规则、且不影响未替换歌词的会话。
 */
class PluginDuetConcurrentTest {

    private fun renderModes() = ProducerRenderModes(
        weight = "Medium", textSize = "normal", textSizeCustom = 100,
        secondary = "Main only", animation = "Karaoke fill", glow = "Off",
        lineSyncFill = "Top to bottom", overflow = "Wrap", transition = "Fade up",
        font = "spotify"
    )

    private fun state(positionMs: Long) = LyricProducerState(
        producerId = "lyricinfo",
        generation = 1,
        sequence = 1L,
        status = "ready",
        trackUri = "lyricinfo:song",
        title = "song", artist = "", album = "", imageId = "",
        line = "main", romanizedLine = "", translatedLine = "",
        lineIndex = 0, positionMs = positionMs, durationMs = 30_000L,
        sampledAtElapsedMs = 0L, speed = 1f, playing = true,
        receivedAtElapsedMs = 0L, words = null, renderModes = renderModes()
    )

    private fun row(
        begin: Long,
        end: Long,
        text: String,
        role: String,
        words: List<PluginWord>? = null
    ) = PluginLyricLine(
        begin = begin, end = end, duration = end - begin,
        metadata = PluginMetadata(values = mapOf("role" to role)),
        text = text, words = words
    )

    private fun patched(
        rows: List<PluginLyricLine>,
        st: LyricProducerState,
        changedSongFields: Set<PluginSongField>
    ) = PatchedSong(
        sessionKey = PluginSongBridge.sessionKey(st),
        song = PluginSong(lyrics = rows),
        changedSongFields = changedSongFields,
        changedLyricFields = setOf(PluginLyricField.TEXT, PluginLyricField.WORDS)
    )

    /** AMLL TTML 形态：x-bg 和声行嵌在主行窗口内 → 与主行共享窗口 ≥1s → 并发行。 */
    @Test
    fun harmonyRowNestedInMainLineBecomesDuetLine() {
        val st = state(positionMs = 4_500L)
        val rows = listOf(
            row(0L, 10_000L, "main line", "LEAD"),
            row(
                4_000L, 6_000L, "harmony", "BG",
                words = listOf(PluginWord(begin = 4_000L, end = 5_000L, duration = 1_000L, text = "har"))
            ),
            row(10_000L, 14_000L, "next line", "LEAD")
        )
        val out = PluginSongBridge.enrichState(st, patched(rows, st, setOf(PluginSongField.LYRICS)))
        val duet = out.duetLine
        assertEquals("harmony", duet?.text)
        assertEquals(4_000L, duet?.lineStartMs)
        assertEquals(6_000L, duet?.lineEndMs)
        assertEquals(1, duet?.words?.size)
    }

    /** 普通顺序行表（首尾相接）不产生并发行——与生产者侧同一判定，不误报相邻行。 */
    @Test
    fun sequentialPluginRowsProduceNoDuetLine() {
        val st = state(positionMs = 4_500L)
        val rows = listOf(
            row(0L, 5_000L, "first", "LEAD"),
            row(5_000L, 10_000L, "second", "LEAD")
        )
        val out = PluginSongBridge.enrichState(st, patched(rows, st, setOf(PluginSongField.LYRICS)))
        assertNull(out.duetLine)
    }

    /** 共享窗口短于 1s 不算并发（避免行尾一瞬间闪出双行段）。 */
    @Test
    fun shortOverlapIsNotADuetLine() {
        val st = state(positionMs = 9_500L)
        val rows = listOf(
            row(0L, 10_000L, "main", "LEAD"),
            row(9_500L, 12_000L, "tail", "LEAD")
        )
        val out = PluginSongBridge.enrichState(st, patched(rows, st, setOf(PluginSongField.LYRICS)))
        assertNull(out.duetLine)
    }

    /** 插件替换歌词且最终行表无重叠时，清掉生产者留下的陈旧候选（避免与替换后的行表不一致）。 */
    @Test
    fun replacingLyricsWithoutOverlapClearsStaleCandidate() {
        val st = state(positionMs = 4_500L).copy(
            duetLine = LyricDuetLine(text = "stale", lineStartMs = 1_000L, lineEndMs = 2_000L)
        )
        val rows = listOf(
            row(0L, 10_000L, "main", "LEAD"),
            row(10_000L, 20_000L, "next", "LEAD")
        )
        val out = PluginSongBridge.enrichState(st, patched(rows, st, setOf(PluginSongField.LYRICS)))
        assertNull(out.duetLine)
    }

    /** 未声明 LYRICS 的插件结果（如只改翻译）不动生产者的并发行候选。 */
    @Test
    fun nonLyricsPluginResultKeepsProducerCandidate() {
        val st = state(positionMs = 4_500L).copy(
            duetLine = LyricDuetLine(text = "producer duet", lineStartMs = 1_000L, lineEndMs = 9_000L)
        )
        val rows = listOf(row(0L, 10_000L, "main", "LEAD"))
        val out = PluginSongBridge.enrichState(st, patched(rows, st, emptySet()))
        assertEquals("producer duet", out.duetLine?.text)
    }
}
