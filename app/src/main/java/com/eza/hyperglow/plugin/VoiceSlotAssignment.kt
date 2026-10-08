package com.eza.hyperglow.plugin

import com.eza.hyperglow.producer.MIN_CONCURRENT_OVERLAP_MS
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine

/**
 * 对唱行表的**声部槽位**(纯函数:无状态、确定性、全量)。
 *
 * 为什么需要槽位(owner 2026-10-08 决定「每行钉在一个声部上」):对唱曲目里两位演唱者的行窗
 * 互相重叠(AMLL TTML 规范 §6 规则 4:「不同演唱者的 `<p>` 或 `<span>` 时间戳可以重叠,但
 * 同一演唱者的…不能重叠」),而生产者(网易云 LRC)的行表是**顺序铺开**的——实测《乐鸣东方》
 * v1 `乐鸣东方` 104.547–108.999、v2 `天地为引 归墟为依` 105.686–120.557,生产者却排成
 * 104.570–108.350 / 108.350–119.910。此前「主行 = 生产者当前句、并发行 = 另一重叠行」的选法
 * 在 108.350(生产者当前句翻转)把屏上两行整体互换:owner 正在读的那一行跳到另一个位置。
 * 钉槽位后,槽位 0 恒是第一声部(第一行)、槽位 1 恒是第二声部(并发行车道),每行的文本只在
 * 本声部的下一句开始时变——生产者行表怎么排都不再决定两行的内容归属。
 *
 * 槽位分配(身份优先;源里没有身份时由宿主自动分配默认两个声部,owner:「遇到这种并发没有
 * 身份的,自己拿到 ttml 就自动分配一下默认的两个」):
 * 1. **身份**:行 metadata 的 `agent`(amll-ttml 的 TtmlMapper 把 `ttm:agent` 的 xml:id 原样
 *    带出,规范 §7.2)非空时按**首见顺序**映射——第一个不同 agent → 槽位 0,第二个 → 槽位 1,
 *    再多的也归 1(屏上只有两行);
 * 2. **自动分配**:无 agent 的行——以及整张表都没有 agent 时——按**时间序**(begin 升序,同
 *    begin 保持表内顺序)交替:与上一行共享窗口 ≥ [MIN_CONCURRENT_OVERLAP_MS] 的行取对面槽位
 *    (两句同时在场 = 两位演唱者),否则沿用上一行槽位(顺序铺开 = 同一声部);跟在带身份行
 *    后面的无身份行按同一条规则接续它;
 * 3. **BG 行不参与**(role=BG 的 x-bg 和声走辅助行车道,与声部无关):既不拿槽位,也不打断
 *    交替链;
 * 4. **全量**:每个非 BG 行恰好得到一个槽位。
 */

/** 槽位 0:第一声部——屏上第一行(主行)。 */
internal const val VOICE_SLOT_PRIMARY = 0

/** 槽位 1:第二声部——并发行车道(屏上第二行)。 */
internal const val VOICE_SLOT_SECONDARY = 1

/** 演唱者身份键:与 amll-ttml 的 TtmlMapper.META_AGENT 同一约定(宿主侧只认这一个键名)。 */
private const val META_AGENT = "agent"

/**
 * 行表 → 每行的槽位(下标与 [rows] 一一对应;BG 行为 null)。判定见文件头注释。
 */
internal fun assignVoiceSlots(rows: List<PluginLyricLine>): List<Int?> {
    val slots = MutableList<Int?>(rows.size) { null }
    val agentSlots = LinkedHashMap<String, Int>()
    var previous = -1
    // 时间序 = begin 升序;同 begin 按表内下标定序(显式兜底,结果与排序稳定性无关 ⇒ 确定)。
    for (index in rows.indices.sortedWith(compareBy({ rows[it].begin }, { it }))) {
        val row = rows[index]
        if (isBackgroundRow(row)) continue
        val agent = row.metadata?.values?.get(META_AGENT)?.takeIf { it.isNotBlank() }
        slots[index] = when {
            agent != null -> agentSlot(agentSlots, agent)
            previous < 0 -> VOICE_SLOT_PRIMARY
            sharesConcurrentWindow(rows[previous], row) -> 1 - slots[previous]!!
            else -> slots[previous]!!
        }
        previous = index
    }
    return slots
}

/**
 * 槽位 [slot] 在 [positionMs] 处的「当前行」下标;该声部此刻不在场时返回 -1。
 * 三段(与主行解析同一形状,但只看本声部自己的行):
 * 1. **覆盖行**:position ∈ [begin, end) 的行(同一声部的行按规范不重叠,防御性取 begin 最晚的一行);
 * 2. **行间保持**:上一行已唱完而下一行马上开始(行间隔 < [MIN_CONCURRENT_OVERLAP_MS])时保留上一行
 *    ——对唱段里同一声部两句之间的小停顿(实测 440ms)不塌行;本行文本仍只在自己下一次开口时
 *    才变,所以「第二行唱完自己换行」的观感不受影响;
 * 3. 其余(这一句唱完、下一句在 1s 之外,或本声部还没开口):-1 = 该声部不在场。
 */
internal fun voiceSlotRowIndexAt(
    rows: List<PluginLyricLine>,
    slots: List<Int?>,
    slot: Int,
    positionMs: Long
): Int {
    val covering = coveringRowIndex(rows, slots, slot, positionMs)
    if (covering >= 0) return covering
    val ended = voiceSlotLastEndedRowIndex(rows, slots, slot, positionMs)
    if (ended < 0) return -1
    val nextBegin = nextRowBegin(rows, slots, slot, positionMs)
    if (nextBegin == Long.MAX_VALUE) return -1
    return if (nextBegin - rows[ended].end < MIN_CONCURRENT_OVERLAP_MS) ended else -1
}

/**
 * 槽位 [slot] 里**已唱完**的最后一行的下标(end ≤ [positionMs],同有取结束最晚的一行);无 -1。
 *
 * 主行用它做兜底:本声部这一句唱完、下一句还没到,主行保留这一句——主行不能空,更不能退回
 * 生产者的当前句(生产者的当前句可能正是另一声部的行,退回就是这次要修的互换)。并发行不走
 * 这条兜底(见 [voiceSlotRowIndexAt] 的行间隔门槛),因为它只在两位演唱者并发时在场。
 */
internal fun voiceSlotLastEndedRowIndex(
    rows: List<PluginLyricLine>,
    slots: List<Int?>,
    slot: Int,
    positionMs: Long
): Int {
    var best = -1
    for (index in rows.indices) {
        if (slots.getOrNull(index) != slot) continue
        val row = rows[index]
        if (row.end > positionMs) continue
        if (best < 0 || row.end >= rows[best].end) best = index
    }
    return best
}

/** 首见顺序的 agent → 槽位表:第一个不同 agent 居 0,其余(第二、第三…个)都归 1。 */
private fun agentSlot(agentSlots: LinkedHashMap<String, Int>, agent: String): Int {
    agentSlots[agent]?.let { return it }
    val slot = if (agentSlots.isEmpty()) VOICE_SLOT_PRIMARY else VOICE_SLOT_SECONDARY
    agentSlots[agent] = slot
    return slot
}

/** 共享窗口 ≥ [MIN_CONCURRENT_OVERLAP_MS] 即「同时在场」(与 producer/DuetConcurrent 同一判定)。 */
private fun sharesConcurrentWindow(first: PluginLyricLine, second: PluginLyricLine): Boolean =
    minOf(first.end, second.end) - maxOf(first.begin, second.begin) >= MIN_CONCURRENT_OVERLAP_MS

/** 和声行判据(role=BG,与 [PluginSongBridge] 的同一约定)。 */
private fun isBackgroundRow(row: PluginLyricLine): Boolean =
    row.metadata?.values?.get(PluginSongBridge.META_ROLE) == PluginSongBridge.ROLE_BG

/** 槽位 [slot] 覆盖 [positionMs] 的行下标(同有取 begin 最晚的一行);无 -1。 */
private fun coveringRowIndex(
    rows: List<PluginLyricLine>,
    slots: List<Int?>,
    slot: Int,
    positionMs: Long
): Int {
    var best = -1
    for (index in rows.indices) {
        if (slots.getOrNull(index) != slot) continue
        val row = rows[index]
        if (positionMs < row.begin || positionMs >= row.end) continue
        if (best < 0 || row.begin >= rows[best].begin) best = index
    }
    return best
}

/** 槽位 [slot] 里 begin > [positionMs] 的最早起点;无则 [Long.MAX_VALUE]。 */
private fun nextRowBegin(
    rows: List<PluginLyricLine>,
    slots: List<Int?>,
    slot: Int,
    positionMs: Long
): Long {
    var best = Long.MAX_VALUE
    for (index in rows.indices) {
        if (slots.getOrNull(index) != slot) continue
        val begin = rows[index].begin
        if (begin > positionMs && begin < best) best = begin
    }
    return best
}
