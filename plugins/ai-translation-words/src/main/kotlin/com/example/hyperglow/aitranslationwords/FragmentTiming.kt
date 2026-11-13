package com.example.hyperglow.aitranslationwords

import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine

/**
 * 源词时间窗（毫秒）。语义上是半开区间 `[startMs, endMs)`——与宿主对词窗口的
 * 判据一致（`end > begin` 才算真实窗口）；刻意不用 `LongRange`，避免
 * `until` / `..` 的闭区间歧义。
 */
internal data class TokenWindow(val startMs: Long, val endMs: Long)

/** 源词：tokens 原文 + 时间窗，一一对应（空白文本的词已在 [timedTokens] 里剔除）。 */
internal data class TimedToken(val text: String, val window: TokenWindow)

/** 对齐结果：译文片段 + 它继承的词时间窗。 */
internal data class TimedFragment(val text: String, val startMs: Long, val endMs: Long)

/**
 * 该行的逐字时间轴；没有可用词时间时返回 null。
 *
 * 判据与宿主一致：`words` 非空、至少一个**文本非空**的词、且这些词里至少一个
 * `end > begin`（只有逐行源才会给出全零窗口，此时整行按无词时间处理）。
 * 文本为空的词（占位/间奏标记）不参与对齐，也不会进 tokens——它们没有可唱的
 * 片段，混进来只会让片段与窗口错位。
 */
internal fun timedTokens(line: PluginLyricLine): List<TimedToken>? {
    val words = line.words ?: return null
    val tokens = words
        .filter { !it.text.isNullOrBlank() }
        .map { TimedToken(it.text!!, TokenWindow(it.begin, it.end)) }
    if (tokens.isEmpty()) return null
    if (tokens.none { it.window.endMs > it.window.startMs }) return null
    return tokens
}

/** [timedTokens] 的窗口视图（纯函数，便于对齐逻辑单测）。 */
internal fun timedTokenWindows(line: PluginLyricLine): List<TokenWindow>? =
    timedTokens(line)?.map { it.window }

/**
 * 把译文片段按源词顺序贴回词时间窗（纯函数）。
 *
 * 规则：
 * - `fragments.size != tokens.size` 时返回空表——长度不符说明模型没有按 tokens 对齐，
 *   宁可不产出逐字效果，也不能让片段错位点亮；
 * - 空串片段不产出（模型用空串表示「该词无独立片段」）；
 * - 时间窗无效（`end <= start`）的片段**不丢文本**：并入前一个已产出片段；前面没有
 *   已产出片段时先记为前缀，贴到下一个产出片段；整行都没有有效窗口时退回整行窗口
 *   `[lineStartMs, lineEndMs)`（退化窗也照样产出，文本优先）；
 * - 相邻片段若窗口完全相同则合并为一个（同窗同时长，文本相接）；
 * - 所有输出窗口做钳制：`startMs = max(0, start)`、`endMs = max(start, end)`。
 */
internal fun alignFragmentsToTokens(
    fragments: List<String>,
    tokens: List<TokenWindow>,
    lineStartMs: Long,
    lineEndMs: Long,
): List<TimedFragment> {
    if (fragments.size != tokens.size) return emptyList()

    val out = ArrayList<TimedFragment>(fragments.size)
    // 窗口无效、且前面还没有产出片段时暂存的文本（贴给下一个产出片段）。
    var pendingPrefix = ""

    for (i in fragments.indices) {
        val text = fragments[i]
        if (text.isEmpty()) continue
        val window = tokens[i]
        if (window.endMs <= window.startMs) {
            val last = out.lastOrNull()
            if (last != null) {
                out[out.size - 1] = last.copy(text = last.text + text)
            } else {
                pendingPrefix += text
            }
            continue
        }
        val start = maxOf(0L, window.startMs)
        val end = maxOf(start, window.endMs)
        val merged = pendingPrefix + text
        pendingPrefix = ""
        val last = out.lastOrNull()
        if (last != null && last.startMs == start && last.endMs == end) {
            out[out.size - 1] = last.copy(text = last.text + merged)
        } else {
            out.add(TimedFragment(merged, start, end))
        }
    }

    if (pendingPrefix.isNotEmpty()) {
        // 整行都没有有效词窗时退回行窗口；行窗口本身退化（0 长）也照样产出——文本绝不能丢，
        // 渲染侧对「全零窗」有兜底（回落行窗口合成整行译文），丢掉文本反而会让辅助行比译文短一截。
        val start = maxOf(0L, lineStartMs)
        val end = maxOf(start, lineEndMs)
        out.add(TimedFragment(pendingPrefix, start, end))
    }
    return out
}
