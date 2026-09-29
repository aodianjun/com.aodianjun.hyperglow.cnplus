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
 * 1. 行窗远大于文本可唱估时([grossWindowMs])且词级跨距可信 → 行窗向词对齐
 *    (首词 begin/末词 end,即快照路径 resetLineTimestampsFromWords 的同一语义),
 *    词级卡拉OK保留;
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

    /** 每字可唱时长估计(中速粤语/国语,宽松取 350ms)。 */
    internal const val MS_PER_CHAR = 350L

    /** 行窗 > 估时×[GROSS_WINDOW_MULTIPLIER] + [WINDOW_SLACK_MS] 视为「间隙吞进行窗」。 */
    internal const val GROSS_WINDOW_MULTIPLIER = 2L

    /** 行窗合理性宽限(短行/换气不误伤)。 */
    internal const val WINDOW_SLACK_MS = 2_000L

    /** 词锚定档:词跨距 ≤ 估时×[WORD_ANCHOR_MULTIPLIER] + [WORD_SPREAD_SLACK_MS] 视为真实词级。 */
    internal const val WORD_ANCHOR_MULTIPLIER = 3L

    /** 词跨距可信宽限(慢歌长音不误伤:整句长音是真,铺满间隙的合成词是假)。 */
    internal const val WORD_SPREAD_SLACK_MS = 4_000L

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
     * 文本可唱估时:剥行首标记后按可见字符数(空白不计,标点随行计入偏保守)× [MS_PER_CHAR]。
     * 标记(（男）/（副歌）等)不发声,计入会把钳制目标与 gross 判定整体推偏约一秒;
     * 纯标记行(（间奏）等)按 0 字计,否则长间奏行窗被误判成损坏行。
     */
    internal fun estimatedSingMs(text: CharSequence?): Long {
        val visible = stripDuetMarkerRun(text?.toString() ?: "")
        return visible.count { !it.isWhitespace() } * MS_PER_CHAR
    }

    /** 行窗是否明显大于文本可唱估时(损坏形状)。 */
    internal fun grossWindowMs(text: CharSequence?, windowMs: Long): Boolean {
        val estimated = estimatedSingMs(text)
        return estimated > 0L && windowMs > estimated * GROSS_WINDOW_MULTIPLIER + WINDOW_SLACK_MS
    }

    /**
     * 修复整首行窗口。入参须已按 begin 升序([Song.normalize] 输出即是);
     * 出参稳定按 begin 升序。无损坏行时返回输入实例。
     */
    fun repair(lines: List<RichLyricLine>): List<RichLyricLine> {
        if (lines.isEmpty()) return lines
        val grossCount = lines.count { grossWindowMs(it.text, it.end - it.begin) }
        var changed = false
        var wordAnchored = 0
        var clamped = 0
        var wordsDropped = 0
        var grossUntouched = 0
        val out = ArrayList<RichLyricLine>(lines.size)
        for (index in lines.indices) {
            val line = lines[index]
            val windowMs = line.end - line.begin
            if (!grossWindowMs(line.text, windowMs)) {
                out += line
                continue
            }
            val estimated = estimatedSingMs(line.text)
            val words = line.words.orEmpty()
            val wordSpanMs = if (words.isEmpty()) 0L else words.maxOf { it.end } - words.minOf { it.begin }
            val wordsPlausible = words.isNotEmpty() &&
                wordSpanMs <= estimated * WORD_ANCHOR_MULTIPLIER + WORD_SPREAD_SLACK_MS
            if (wordsPlausible) {
                wordAnchored++
                val begin = words.minOf { it.begin }
                val end = maxOf(begin + 1L, words.maxOf { it.end })
                if (begin == line.begin && end == line.end) {
                    out += line
                } else {
                    out += line.copy(begin = begin, end = end)
                    changed = true
                }
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
                    "clamped=$clamped wordsDropped=$wordsDropped grossUntouched=$grossUntouched"
            )
        }
        if (!changed) return lines
        return out.sortedBy { it.begin }
    }
}
