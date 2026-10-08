package com.eza.hyperglow.plugin

import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.ProducerRenderModes
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 声部槽位(见 VoiceSlotAssignment)与桥侧钉行(见 [PluginSongBridge.enrichState])。
 *
 * owner 2026-10-08:对唱曲目「每行钉在一个声部上」——槽位 0 恒是第一行、槽位 1 恒是第二行,
 * 每行文本只在本声部下一句开始时变;源里没有演唱者身份(`ttm:agent`)时由宿主自动分配默认
 * 两个声部;单声部行表(所有非 BG 行落槽位 0)保持改动前的行为。
 *
 * 真机形态取自《乐鸣东方》:v1 `乐鸣东方` 104.547–108.999、v2 `天地为引 归墟为依`
 * 105.686–120.557(TTML),生产者(LRC)把两句顺序铺成 104.570–108.350 / 108.350–119.910——
 * 108.350 是生产者当前句的翻转点,钉槽位前屏上两行在这里整体互换。
 */
class PluginVoiceSlotTest {

    private fun renderModes() = ProducerRenderModes(
        weight = "Medium", textSize = "normal", textSizeCustom = 100,
        secondary = "Main only", animation = "Karaoke fill", glow = "Off",
        lineSyncFill = "Top to bottom", overflow = "Wrap", transition = "Fade up",
        font = "spotify"
    )

    private fun state(positionMs: Long, line: String) = LyricProducerState(
        producerId = "lyricinfo",
        generation = 1,
        sequence = 1L,
        status = "ready",
        trackUri = "lyricinfo:song",
        title = "song", artist = "", album = "", imageId = "",
        line = line, romanizedLine = "", translatedLine = "",
        lineIndex = 0, positionMs = positionMs, durationMs = 300_000L,
        sampledAtElapsedMs = 0L, speed = 1f, playing = true,
        receivedAtElapsedMs = 0L, words = null, renderModes = renderModes()
    )

    private fun row(
        begin: Long,
        end: Long,
        text: String,
        role: String = "LEAD",
        agent: String? = null
    ): PluginLyricLine {
        val values = LinkedHashMap<String, String?>()
        values[PluginSongBridge.META_ROLE] = role
        agent?.let { values["agent"] = it }
        return PluginLyricLine(
            begin = begin, end = end, duration = end - begin,
            metadata = PluginMetadata(values = values),
            text = text
        )
    }

    /** 复刻生产字段集:`PluginPipeline.diff()` 把 LYRICS 从 songFields 里滤掉,REPLACE 只标行级字段。 */
    private fun patched(rows: List<PluginLyricLine>, st: LyricProducerState) = PatchedSong(
        sessionKey = PluginSongBridge.sessionKey(st),
        song = PluginSong(lyrics = rows),
        changedSongFields = emptySet<PluginSongField>(),
        changedLyricFields = setOf(PluginLyricField.TEXT, PluginLyricField.WORDS)
    )

    // ---- 槽位分配(纯函数) ----

    /** 有 `ttm:agent` 时按首见顺序映射:第一个不同 agent → 0,第二个 → 1,再多的也归 1。 */
    @Test
    fun agentsMapToSlotsInFirstSeenOrder() {
        val rows = listOf(
            row(0L, 10_000L, "v1 第一句", agent = "v1"),
            row(0L, 10_000L, "v2 第一句", agent = "v2"),
            row(10_000L, 20_000L, "v1 第二句", agent = "v1"),
            row(20_000L, 30_000L, "v3 的句子", agent = "v3")
        )
        assertEquals(listOf(0, 1, 0, 1), assignVoiceSlots(rows))
    }

    /**
     * 没有身份时按**与已分配行的重叠**判定:共享窗口 ≥1s(两位演唱者同时在场)取对面槽位,
     * **不重叠就是独唱、回槽位 0**;共享窗口不足 1s 不算并发(与生产者的并发判定同一门槛)。
     * 同一张表两次调用结果相同(确定性)。
     *
     * 刻意钉住「不重叠 → 槽位 0」而不是「沿用上一行槽位」:链式交替一旦在对唱段翻到槽位 1,
     * 后面所有独唱句都会滞留在槽位 1、槽位 0 整段空掉(真机实测《乐鸣东方》2:43–3:32)。
     */
    @Test
    fun unagentedRowsAlternateOnlyOnRealOverlap() {
        val rows = listOf(
            row(0L, 10_000L, "a"),
            row(8_000L, 14_000L, "b"),      // 共享 2s → 对面槽位
            row(14_000L, 20_000L, "c"),     // 首尾相接、无重叠 → 独唱,回槽位 0
            row(19_500L, 25_000L, "d")      // 共享 500ms < 1s → 仍算独唱,回槽位 0
        )
        val slots = assignVoiceSlots(rows)
        assertEquals(listOf(0, 1, 0, 0), slots)
        assertEquals(slots, assignVoiceSlots(rows))
    }

    /**
     * owner 报的现场:对唱段之后的独唱句必须回到槽位 0,不能滞留在槽位 1。
     * 真机形态(自动分配):`乐鸣东方` 104.547–108.999(v1) 与 `天地为引 归墟为依` 105.686–120.557
     * (v2) 重叠 → 0/1;紧接着的 `我随雨唤醒庙堂` 119.910–123.600 只与 v2 那一句重叠 647ms
     * (<1s)——它其实是 v1 的下一句,必须回槽位 0;此前的链式交替把它留在槽位 1,连带后面
     * 近 50s 的独唱句全堆在第二行、第一行整段空掉。
     */
    @Test
    fun soloRowAfterADuetSectionReturnsToThePrimarySlot() {
        val rows = listOf(
            row(104_547L, 108_999L, "乐鸣东方"),
            row(105_686L, 120_557L, "天地为引 归墟为依"),   // 共享 3.3s → 槽位 1
            row(119_910L, 123_600L, "我随雨唤醒庙堂"),       // 共享 647ms → 独唱 → 槽位 0
            row(123_600L, 127_220L, "唱花肆意生长")          // 首尾相接 → 槽位 0
        )
        assertEquals(listOf(0, 1, 0, 0), assignVoiceSlots(rows))
    }

    /** BG 行(role=BG 的 x-bg 和声)拿不到槽位,也不参与重叠判定(它走辅助行车道,与声部无关)。 */
    @Test
    fun backgroundRowsTakeNoSlotAndDoNotBreakTheChain() {
        val rows = listOf(
            row(0L, 10_000L, "a"),
            row(4_000L, 6_000L, "echo", role = "BG"),
            row(8_000L, 14_000L, "b")
        )
        assertEquals(listOf(0, null, 1), assignVoiceSlots(rows))
    }

    /** 跟在带身份行后面的无身份行按同一重叠规则判定(并发取对面槽位;独唱回槽位 0)。 */
    @Test
    fun agentlessSoloRowReturnsToThePrimarySlot() {
        val rows = listOf(
            row(0L, 10_000L, "a", agent = "v1"),
            row(8_000L, 14_000L, "b"),      // 与 v1 行共享 2s → 对面槽位
            row(20_000L, 26_000L, "c")      // 不重叠 → 独唱,回槽位 0
        )
        assertEquals(listOf(0, 1, 0), assignVoiceSlots(rows))
    }

    // ---- 桥侧钉行 ----

    /**
     * 乐鸣东方形态(带 `ttm:agent`):生产者当前句在 108.350 从 v1 那一句翻到 v2 那一句,
     * 两行不得互换——槽位 0 恒是 v1 的行(行 1),槽位 1 恒是 v2 的行(行 2)。
     * 第三段:108.999 之后 v1 这一句唱完、下一句还没到,主行保留 v1 这一句(不退回生产者
     * 当前句,退回就是互换),并发行继续按 v2 自己的窗口在场。
     */
    @Test
    fun pinnedRowsSurviveTheProducerLineFlip() {
        val rows = listOf(
            row(104_547L, 108_999L, "乐鸣东方", agent = "v1"),
            row(105_686L, 120_557L, "天地为引 归墟为依", agent = "v2")
        )
        assertEquals(listOf(0, 1), assignVoiceSlots(rows))

        // 翻转前:生产者当前句 = v1 那一句。
        val before = state(positionMs = 107_000L, line = "乐鸣东方")
        val beforeOut = PluginSongBridge.enrichState(before, patched(rows, before))
        assertEquals("乐鸣东方", beforeOut.line)
        assertEquals("天地为引 归墟为依", beforeOut.duetLine?.text)

        // 翻转后(108.350+):生产者当前句 = v2 那一句,两行不得互换。
        val after = state(positionMs = 108_400L, line = "天地为引 归墟为依")
        val afterOut = PluginSongBridge.enrichState(after, patched(rows, after))
        assertEquals("乐鸣东方", afterOut.line)
        assertEquals("天地为引 归墟为依", afterOut.duetLine?.text)
        // 并发行带自己的行窗(105.686–120.557),不是生产者铺开的 108.350–119.910。
        assertEquals(105_686L, afterOut.duetLine?.lineStartMs)
        assertEquals(120_557L, afterOut.duetLine?.lineEndMs)

        // 108.999 之后:v1 这一句已唱完,主行保留它直到 v1 下一句开始。
        val held = state(positionMs = 110_000L, line = "天地为引 归墟为依")
        val heldOut = PluginSongBridge.enrichState(held, patched(rows, held))
        assertEquals("乐鸣东方", heldOut.line)
        assertEquals("天地为引 归墟为依", heldOut.duetLine?.text)
    }

    /**
     * 没有身份(无 `ttm:agent`)的并发行:宿主自动分配默认两个声部——按时间序交替即可,
     * 两行同样钉住(owner:「遇到这种并发没有身份的,自己拿到 ttml 就自动分配一下默认的两个」)。
     */
    @Test
    fun identityLessDuetStillGetsTwoStableSlots() {
        val rows = listOf(
            row(104_547L, 108_999L, "乐鸣东方"),
            row(105_686L, 120_557L, "天地为引 归墟为依")
        )
        assertEquals(listOf(0, 1), assignVoiceSlots(rows))

        val before = state(positionMs = 107_000L, line = "乐鸣东方")
        val beforeOut = PluginSongBridge.enrichState(before, patched(rows, before))
        assertEquals("乐鸣东方", beforeOut.line)
        assertEquals("天地为引 归墟为依", beforeOut.duetLine?.text)

        val after = state(positionMs = 108_400L, line = "天地为引 归墟为依")
        val afterOut = PluginSongBridge.enrichState(after, patched(rows, after))
        assertEquals("乐鸣东方", afterOut.line)
        assertEquals("天地为引 归墟为依", afterOut.duetLine?.text)
    }

    /**
     * 单声部行表(所有非 BG 行落槽位 0)走改动前的老路:主行按行身份回填、并发行仍按
     * 纯时间窗重叠判定选出——「同一 agent 的两行重叠」这种混音形态也保持老行为,这是
     * 「单声部曲目逐字节不变」的结构性保证(老路径根本没被换成槽位)。
     */
    @Test
    fun singleVoiceTableKeepsTheLegacySelection() {
        val rows = listOf(
            row(0L, 10_000L, "长行", agent = "v1"),
            row(8_000L, 12_000L, "重叠句", agent = "v1")
        )
        assertEquals(listOf(0, 0), assignVoiceSlots(rows))

        val st = state(positionMs = 9_000L, line = "长行")
        val out = PluginSongBridge.enrichState(st, patched(rows, st))
        assertEquals("长行", out.line)
        // 老路:selectDuetLineIndex 选出的重叠行(槽位 1 为空时并发行仍由它决定)。
        assertEquals("重叠句", out.duetLine?.text)
    }

    /** 单声部行表里插件行表对不上生产者当前句、位置也不在任何行内:保持生产者状态不动(引用相等)。 */
    @Test
    fun singleVoiceTableKeepsProducerStateWhenNothingMatches() {
        val rows = listOf(
            row(0L, 5_000L, "第一句"),
            row(5_000L, 10_000L, "第二句")
        )
        val st = state(positionMs = 20_000L, line = "生产者自己的句子")
        val out = PluginSongBridge.enrichState(st, patched(rows, st))
        assertSame(st, out)
    }

    /**
     * 第二声部自己两句之间的小停顿(真机形态:148.651–151.783 与 152.223–155.802,间隔 440ms)
     * 不塌行,也不被主行带着走:停顿里并发行保留上一句,下一句开始时才换——只因为第二声部
     * 自己换行;主行(v1 长行 147.444–154.300)全程不变,哪怕生产者的当前句已经翻到 v2 那一句。
     */
    @Test
    fun duetLaneAdvancesOnlyWhenItsOwnVoiceAdvances() {
        val rows = listOf(
            row(147_444L, 154_300L, "乐鸣东方", agent = "v1"),
            row(148_651L, 151_783L, "哈啊 流水破开寒霜", agent = "v2"),
            row(152_223L, 155_802L, "哈啊 金石击起辉光", agent = "v2")
        )
        val slots = assignVoiceSlots(rows)
        assertEquals(listOf(0, 1, 1), slots)

        // 停顿中:并发行仍是 v2 上一句。
        assertEquals(1, voiceSlotRowIndexAt(rows, slots, VOICE_SLOT_SECONDARY, 151_900L))
        val gap = state(positionMs = 151_900L, line = "哈啊 流水破开寒霜")
        val gapOut = PluginSongBridge.enrichState(gap, patched(rows, gap))
        assertEquals("乐鸣东方", gapOut.line)
        assertEquals("哈啊 流水破开寒霜", gapOut.duetLine?.text)

        // 下一句开始:并发行换到新句(生产者当前句也是这一句,但它落在行 2,不是行 1)。
        assertEquals(2, voiceSlotRowIndexAt(rows, slots, VOICE_SLOT_SECONDARY, 152_400L))
        val next = state(positionMs = 152_400L, line = "哈啊 金石击起辉光")
        val nextOut = PluginSongBridge.enrichState(next, patched(rows, next))
        assertEquals("乐鸣东方", nextOut.line)
        assertEquals("哈啊 金石击起辉光", nextOut.duetLine?.text)
    }

    /**
     * 第二声部停下(下一句在 1s 之外)后并发行离场,主行接着走第一声部自己的下一句——
     * 两行的在场与否各由本声部的行窗决定(真机形态:v2 天地为引 105.686–120.557 唱完后,
     * v1 从 119.759 起独唱,并发行清空)。
     */
    @Test
    fun duetLaneLeavesWhenItsVoiceStops() {
        val rows = listOf(
            row(104_547L, 108_999L, "乐鸣东方", agent = "v1"),
            row(105_686L, 120_557L, "天地为引 归墟为依", agent = "v2"),
            row(119_759L, 123_127L, "我随雨唤醒庙堂", agent = "v1")
        )
        assertEquals(listOf(0, 1, 0), assignVoiceSlots(rows))

        // 两句重叠的尾部:v1 的下一句 119.759 已经开口,生产者(LRC)此刻仍报 v2 那一句——
        // 两行各就各位:行 1 = v1 的当前句(与生产者当前句不同,这正是钉行的效果),
        // 行 2 = v2 那一句。
        val both = state(positionMs = 119_900L, line = "天地为引 归墟为依")
        val bothOut = PluginSongBridge.enrichState(both, patched(rows, both))
        assertEquals("我随雨唤醒庙堂", bothOut.line)
        assertEquals("天地为引 归墟为依", bothOut.duetLine?.text)

        // v2 唱完:并发行离场,主行仍是 v1 的当前句。
        val solo = state(positionMs = 121_500L, line = "我随雨唤醒庙堂")
        val soloOut = PluginSongBridge.enrichState(solo, patched(rows, solo))
        assertEquals("我随雨唤醒庙堂", soloOut.line)
        assertNull(soloOut.duetLine)
    }
}
