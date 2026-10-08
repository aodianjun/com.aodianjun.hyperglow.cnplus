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
 * 之后、判定仍是同一条「共享窗口 ≥ 1s」规则、和声身份(role=BG → `harmony`)随行下发、
 * 且不影响未替换歌词的会话。
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

    /**
     * 复刻生产字段集：`PluginPipeline.diff()` 会把 LYRICS 从 songFields 里过滤掉，REPLACE 整表
     * 替换只按新表内容标 `changedLyricFields`（TEXT/WORDS…）。用例必须用这套真实字段，否则
     * 会像 #223 首版那样「测试通过、真机不生效」（当时用 `setOf(PluginSongField.LYRICS)`，
     * 而生产环境永远不会出现该组合）。
     */
    private fun patched(
        rows: List<PluginLyricLine>,
        st: LyricProducerState,
        lyricFields: Set<PluginLyricField> = setOf(PluginLyricField.TEXT, PluginLyricField.WORDS),
        songFields: Set<PluginSongField> = emptySet()
    ) = PatchedSong(
        sessionKey = PluginSongBridge.sessionKey(st),
        song = PluginSong(lyrics = rows),
        changedSongFields = songFields,
        changedLyricFields = lyricFields
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
        val out = PluginSongBridge.enrichState(st, patched(rows, st))
        val duet = out.duetLine
        assertEquals("harmony", duet?.text)
        assertEquals(4_000L, duet?.lineStartMs)
        assertEquals(6_000L, duet?.lineEndMs)
        assertEquals(1, duet?.words?.size)
        // 和声身份随行下发:渲染侧据此走辅助行车道(小字号辅助行),不再堆主行同款大字行。
        assertEquals(true, duet?.harmony)
    }

    /**
     * 不同演唱者的对唱行(时间窗重叠、非 BG 角色)不带和声标记:渲染侧保持主行同款并排——
     * 和声与对唱的分野是行角色,不是文本是否相同(重合文本的对唱不得被降级成辅助行)。
     * 位置取对唱行开唱前(预加入分支),活动行仍是主行。
     */
    @Test
    fun overlappingLeadRowStaysConcurrentWithoutHarmonyFlag() {
        val st = state(positionMs = 3_000L)
        val rows = listOf(
            row(0L, 10_000L, "main line", "LEAD"),
            row(4_000L, 6_000L, "second singer", "LEAD")
        )
        val out = PluginSongBridge.enrichState(st, patched(rows, st))
        val duet = out.duetLine
        assertEquals("second singer", duet?.text)
        assertEquals(false, duet?.harmony)
    }

    /** 普通顺序行表（首尾相接）不产生并发行——与生产者侧同一判定，不误报相邻行。 */
    @Test
    fun sequentialPluginRowsProduceNoDuetLine() {
        val st = state(positionMs = 4_500L)
        val rows = listOf(
            row(0L, 5_000L, "first", "LEAD"),
            row(5_000L, 10_000L, "second", "LEAD")
        )
        val out = PluginSongBridge.enrichState(st, patched(rows, st))
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
        val out = PluginSongBridge.enrichState(st, patched(rows, st))
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
        val out = PluginSongBridge.enrichState(st, patched(rows, st))
        assertNull(out.duetLine)
    }

    /** 未声明 LYRICS 的插件结果（如只改翻译）不动生产者的并发行候选。 */
    @Test
    fun nonLyricsPluginResultKeepsProducerCandidate() {
        val st = state(positionMs = 4_500L).copy(
            duetLine = LyricDuetLine(text = "producer duet", lineStartMs = 1_000L, lineEndMs = 9_000L)
        )
        val rows = listOf(row(0L, 10_000L, "main", "LEAD"))
        val out = PluginSongBridge.enrichState(st, patched(rows, st, setOf(PluginLyricField.TRANSLATION)))
        assertEquals("producer duet", out.duetLine?.text)
    }

    /**
     * 位置落在插件行表的**间隙**里时（实测：生产者行 170.2–190.8s、插件 TTML 该句只有
     * 170.5–175.7s，其后到 190.6s 是空白段，状态位置 184.8s）仍按**行身份**认行，
     * 并挂上同句和声行——与 HyperLyric 的呈现边界模型一致。
     */
    @Test
    fun gapPositionResolvesLineAndHarmonyByIdentity() {
        val st = state(positionMs = 184_800L).copy(line = "人间百相 总让我神往", words = null)
        val rows = listOf(
            row(170_500L, 175_700L, "人间百相总让我神往", "LEAD",
                words = listOf(PluginWord(begin = 170_500L, end = 172_000L, duration = 1_500L, text = "人间"))),
            row(170_500L, 175_700L, "(人间百相总让我神往)", "BG"),
            row(190_600L, 197_200L, "唤长风燃云苍", "LEAD")
        )
        val out = PluginSongBridge.enrichState(st, patched(rows, st))
        // 文本归一化后命中同一句 → 内容按插件行回填（含词表，位置间隙不再整段跳过）
        assertEquals("人间百相总让我神往", out.line)
        assertEquals(1, out.words?.size)
        // 同句和声行挂到活动行上（不要求位置落在和声自己的窗口内）
        assertEquals("(人间百相总让我神往)", out.duetLine?.text)
        assertEquals(170_500L, out.duetLine?.lineStartMs)
        assertEquals(175_700L, out.duetLine?.lineEndMs)
        assertEquals(true, out.duetLine?.harmony)
    }

    /** 别的句子的和声行不会被挂到当前活动行上（按行身份配对，不是见到 BG 就拿）。 */
    @Test
    fun harmonyRowOfAnotherLineIsNotAttached() {
        val st = state(positionMs = 1_000L).copy(line = "第一句")
        val rows = listOf(
            row(0L, 5_000L, "第一句", "LEAD"),
            row(5_000L, 10_000L, "第二句", "LEAD"),
            row(5_000L, 10_000L, "(第二句)", "BG")
        )
        val out = PluginSongBridge.enrichState(st, patched(rows, st))
        assertNull(out.duetLine)
    }

    /**
     * 和声行不被晋级成主行（owner 2026-10-07）：主行窗口比插件行粗（实测同一句生产者
     * 20.6s vs 插件 5.2s），换行间隙里只有和声行覆盖位置——此时主行必须保持生产者那一句
     * （按文本回退找同句 LEAD 行），不能取和声行的文本（那正是「第二行的歌词还没唱完就
     * 换到第一行」）。真机形态：《乐鸣东方》主行 86.7–89.7 + 和声 88.6–90.5，位置 89.7 落
     * 在两句之间的间隙里；和声行故意排在行表最前，钉住「不按行表顺序认主行」。
     */
    @Test
    fun harmonyRowNeverBecomesTheMainLineDuringGap() {
        val st = state(positionMs = 89_700L).copy(line = "少年狂")
        val rows = listOf(
            row(88_600L, 90_500L, "(少年狂)", "BG"),
            row(86_700L, 89_700L, "少年狂", "LEAD"),
            row(90_400L, 97_400L, "唤炽心无双千秋同所向", "LEAD")
        )
        val out = PluginSongBridge.enrichState(st, patched(rows, st))
        assertEquals("少年狂", out.line)
        assertEquals("(少年狂)", out.duetLine?.text)
        assertEquals(true, out.duetLine?.harmony)
    }
}
