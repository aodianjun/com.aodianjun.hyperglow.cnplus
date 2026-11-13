package com.eza.hyperglow.aod

/**
 * 长间奏判定与倒计时窗口（纯函数，投影层与 App 内预览同源）。
 *
 * 参考 HyperLyric（limczhh/HyperLyric）「歌词长间奏显示倒计时圆点」方案
 * （SongPreprocessor.interludeCountdowns / CountdownDotsRenderer）：
 * - 长间奏 = 本行 end 与下一行 start 之间的空隙 ≥ [MIN_INTERLUDE_GAP_MS]（4s）；
 * - 倒计时窗口从本行 end 延迟 [INTERLUDE_COUNTDOWN_DELAY_MS]（1s）开始，到下一行 start 结束。
 *   延迟的存在是为「上一行」保留 1s 停留（HyperLyric 无歌词预览时同值）；渲染面可按本面
 *   「显示下一行歌词」开关把延迟映射为 0（见 root.aod.interludeDotsWindow），语义对应
 *   HyperLyric 开了歌词预览即从 end 处开始。
 *
 * 本文件只做「空隙 → 窗口」的判定与取值，不携带任何渲染/策略细节：
 * - 是否上屏由各面 per-surface 开关决定（渲染映射层解析）；
 * - 与既有「开场/间奏大元数据」引导（[SongMetadataIntroPolicy]）的取舍由投影层决定：
 *   大元数据引导显示期间不下发窗口（既有元数据行为零改动，见 [projectToDisplay]）。
 *
 * 「本行 end」的取值（投影层没有整首行表，只能取生产者状态里已经给出的窗口）：
 * - 空档期生产者保持上一行为活动行（Lyricon / LyricInfo），此时 [lineEndMs] 即上一行 end；
 * - 无活动行但状态仍携带一条占位行窗口（Spicy 的空白间奏行覆盖该位置，行文本为空）时，
 *   该窗口即间奏区间，起点取窗口起点（连续文档下等于上一唱词行的结束）。
 * - 两者都不成立（如 Spicy 文档在行间留洞、无覆盖行）时返回 null：不臆造空隙起点。
 */
internal const val MIN_INTERLUDE_GAP_MS = 4_000L

/** 上一行结束到倒计时圆点出现之间的延迟（参考 HyperLyric 同值）。 */
internal const val INTERLUDE_COUNTDOWN_DELAY_MS = 1_000L

/**
 * 长间奏窗口（原始区间，不含延迟）：`first..last` = 上一行 end .. 下一行 start
 * （闭区间记法，跨度 = last − first；与画布既有 auxKaraokeWindow 同记法）。
 * null = 无长间奏（空隙不足 4s / 缺任一端点 / 外推不可信）。
 *
 * @param lineStartMs 生产者状态的当前行窗口起点（无活动行时为占位行窗口起点，无则为 0）。
 * @param lineEndMs 生产者状态的当前行窗口终点（空档期即上一行 end）。
 * @param hasActiveLine 是否有活动歌词行（[projectToDisplay] 同判据）。
 * @param nextLineStartMs 下一行起点（[com.eza.hyperglow.producer.LyricProducerState.nextLineStartMs]）。
 * @param extrapolationReliable 外推是否可信；不可信时（数据源停写/越界）不启动倒计时——
 *   与空档预览同口径，不能让过期快照驱动一段假的间奏动画。
 */
internal fun interludeSpan(
    lineStartMs: Long,
    lineEndMs: Long,
    hasActiveLine: Boolean,
    nextLineStartMs: Long?,
    extrapolationReliable: Boolean = true
): LongRange? {
    if (!extrapolationReliable) return null
    val gapStartMs = when {
        hasActiveLine && lineEndMs > 0L -> lineEndMs
        !hasActiveLine && lineStartMs > 0L && lineEndMs > lineStartMs -> lineStartMs
        else -> return null
    }
    val gapEndMs = nextLineStartMs ?: return null
    if (gapEndMs - gapStartMs < MIN_INTERLUDE_GAP_MS) return null
    return gapStartMs..gapEndMs
}
