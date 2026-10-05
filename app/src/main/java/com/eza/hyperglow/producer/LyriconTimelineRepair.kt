package com.eza.hyperglow.producer

import com.eza.hyperglow.AppLog
import io.github.proify.lyricon.lyric.model.RichLyricLine

/**
 * Lyricon 行时间轴可信化修复(P0,ingest 侧)。
 *
 * 背景(2026-09-28 真机定论):部分曲目(实测《讲男讲女》id=64374)的行时间戳与网易云自己
 * 显示的时间轴(LRC/播放器)逐行偏差 -15.1s~+5.7s,典型形状是「乐器间隙吞进行窗」——
 * 行首被贴到上一行末尾、行窗拉长到十几秒(1.6s/字,正常 0.3~0.5s/字),下一句提前十几秒
 * 上屏;疑似逐字合成/强制对齐把词铺满间隙的产物(数据来自 lyricon 提供方,CN+ 侧兜底)。
 *
 * 修复策略(只动「明显损坏」的行,正常行零变化):
 * 1. 行窗/词窗基准统一走 [LyricTimelineNormalizer](三类口径的单一共享副本):
 *    词窗超出行窗 → 行窗扩到词窗并集;行窗远大于文本可唱估时([LyricTimelineNormalizer.grossWindowMs])
 *    且词级跨距可信 → 行窗向词对齐(首词 begin/末词 end,即快照路径
 *    resetLineTimestampsFromWords 的同一语义),词级卡拉OK保留;正常拖尾原样透传。
 * 2. 词级跨距同样失真(或无词)→ 丢弃词级(不显示假逐字);行首贴附上一行末尾且行尾
 *    自带(非 end=next.begin 的链式形状)时,把行首钳到 end-估时——直接治「提前上屏」。
 *    (链式形状的长窗是 LRC 间隙的正常表达——间隙挂在上一行尾部,行首即真实起唱点,
 *    不得钳,否则会把正常行推后十几秒。)
 *    钳制仅在全曲 ≥[MIN_GROSS_LINES_FOR_CLAMP] 个损坏行时启用:实测损坏是整首系统性的
 *    (逐字合成把每个间隙吞进行窗),慢歌真实长音则是孤立的长窗行——孤立者原样保留,
 *    防误伤(收尾长音被钳到只在行尾出现)。
 *
 * 修复后按 begin 稳定排序(行首后移可能与后行交叉;平局保原相对序)。
 */
internal object LyriconTimelineRepair {

    /** 行首贴附判定容差:行首不晚于上一行末尾 + 该值即视为间隙填进头部。 */
    internal const val HEAD_ATTACH_TOLERANCE_MS = 250L

    /** 行尾自带判定容差:行尾早于下一行开头 - 该值才算自带窗口(链式 end=next.begin 不算)。 */
    internal const val TAIL_FREE_TOLERANCE_MS = 200L

    /**
     * 触发行首钳制所需的全曲 gross 行数下限。真实损坏是整首系统性的(实测《讲男讲女》
     * 27 行大面积偏差),慢歌真实长音则孤立出现——多发才钳,孤立长窗行零变化。
     */
    internal const val MIN_GROSS_LINES_FOR_CLAMP = 2

    /**
     * 修复整首行窗口。入参须已按 begin 升序([Song.normalize] 输出即是);
     * 出参稳定按 begin 升序。无损坏行时返回输入实例。
     */
    fun repair(lines: List<RichLyricLine>): List<RichLyricLine> {
        if (lines.isEmpty()) return lines
        val grossCount = lines.count {
            LyricTimelineNormalizer.grossWindowMs(it.text, it.end - it.begin)
        }
        var changed = false
        var wordAnchored = 0
        var expanded = 0
        var clamped = 0
        var wordsDropped = 0
        var grossUntouched = 0
        val out = ArrayList<RichLyricLine>(lines.size)
        for (index in lines.indices) {
            val line = lines[index]
            val words = line.words.orEmpty()
            // ① 词窗超出行窗 → 并集扩展;② 远超估时且词窗可信 → 向词窗对齐;③ 原样。
            val normalized = LyricTimelineNormalizer.normalizeLineWindow(
                beginMs = line.begin,
                endMs = line.end,
                wordBeginMs = words.minOfOrNull { it.begin },
                wordEndMs = words.maxOfOrNull { it.end },
                estimatedSingMs = LyricTimelineNormalizer.estimatedSingMs(line.text)
            )
            if (normalized.beginMs != line.begin || normalized.endMs != line.end) {
                if (LyricTimelineNormalizer.grossWindowMs(line.text, line.end - line.begin)) {
                    wordAnchored++
                } else {
                    expanded++
                }
                out += line.copy(begin = normalized.beginMs, end = normalized.endMs)
                changed = true
                continue
            }
            val estimated = LyricTimelineNormalizer.estimatedSingMs(line.text)
            val wordSpanMs = if (words.isEmpty()) 0L else words.maxOf { it.end } - words.minOf { it.begin }
            val wordsPlausible = words.isNotEmpty() &&
                LyricTimelineNormalizer.plausibleWordSpanMs(estimated, wordSpanMs)
            if (!LyricTimelineNormalizer.grossWindowMs(estimated, line.end - line.begin)) {
                out += line
                continue
            }
            if (wordsPlausible) {
                // gross 行且词窗可信:词锚定结果与行窗逐值相同(上一步归一未动)→ 零变化,词级保留。
                wordAnchored++
                out += line
                continue
            }
            val previousEnd = lines.getOrNull(index - 1)?.end
            val nextBegin = lines.getOrNull(index + 1)?.begin
            val headAttached = previousEnd != null &&
                line.begin <= previousEnd + HEAD_ATTACH_TOLERANCE_MS
            val tailFree = nextBegin == null ||
                line.end < nextBegin - TAIL_FREE_TOLERANCE_MS
            val repairedBegin = if (
                grossCount >= MIN_GROSS_LINES_FOR_CLAMP &&
                headAttached && tailFree && estimated > 0L
            ) {
                (line.end - estimated).coerceIn(line.begin, line.end)
            } else {
                line.begin
            }
            if (repairedBegin != line.begin) clamped++
            // 假词级恒丢(不显示假逐字);钳制被护栏跳过时整行零变化,原样透传。
            val repairedWords = if (words.isEmpty()) line.words else null
            if (repairedWords !== line.words) wordsDropped++
            if (repairedBegin == line.begin && repairedWords === line.words) {
                grossUntouched++
                out += line
            } else {
                out += line.copy(begin = repairedBegin, words = repairedWords)
                changed = true
            }
        }
        // 一行日志/首歌(非逐帧):真机排障区分「数据没有损坏行」与「有损坏但护栏跳过钳制」。
        if (grossCount > 0) {
            AppLog.i(
                "LyriconTimelineRepair",
                "repair: rows=${lines.size} gross=$grossCount wordAnchored=$wordAnchored " +
                    "expanded=$expanded clamped=$clamped wordsDropped=$wordsDropped " +
                    "grossUntouched=$grossUntouched"
            )
        }
        if (!changed) return lines
        return out.sortedBy { it.begin }
    }
}
