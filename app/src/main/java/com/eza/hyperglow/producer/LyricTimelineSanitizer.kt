package com.eza.hyperglow.producer

/**
 * Timeline normalization over parsed [ElrcParser.TimedLine]s, applied after parsing and
 * before the lines reach the projector (issue #68 section 13, items #17/#18).
 *
 * 行间重叠仲裁:当前行 endMs 越过下一行 startMs 时按阈值判定"有意重叠"(合唱/轮唱)或
 * "脏时间轴",后者截断当前行 endMs,防止多行同亮/高亮跳动——与 #69 的词级降级同属
 * "数据形状可疑→收敛"的 fail-closed 层。
 *
 * 行/词时间戳对齐:词时间存在时行时间向词对齐(首词 start/末词 end);单词起止全零而
 * 行时间非零时反向回填词(行级歌词被源伪装成单词级的形状)。
 *
 * Thresholds mirror the published semantics of AMLL's optimize-lyric pass; this is an
 * independent GPL-3.0 implementation (no AGPL code copied).
 */
object LyricTimelineSanitizer {

    /** 行间重叠 ≥ 此值视为有意重叠,保留。 */
    internal const val INTENTIONAL_OVERLAP_MS = 500L

    /** 介于 100–500ms 的重叠,超过下一行时长的该比例才算有意。 */
    internal const val MIN_INTENTIONAL_OVERLAP_MS = 100L
    internal const val INTENTIONAL_OVERLAP_RATIO = 0.1

    /**
     * Non-intentional overlap cleanup. [lines] must be sorted ascending by startMs
     * ([ElrcParser.parse] output already is). Cascading: an intentional overlap with the
     * next line does not stop the scan — a later line may still truncate (A–B intentional,
     * A–C incidental → A still cut to C.startMs). Returns the input list unchanged when
     * nothing was truncated.
     */
    fun sanitizeUnintentionalOverlaps(lines: List<ElrcParser.TimedLine>): List<ElrcParser.TimedLine> {
        if (lines.size < 2) return lines
        var changed = false
        val out = lines.toMutableList()
        for (i in 0 until out.size - 1) {
            val line = out[i]
            for (j in i + 1 until out.size) {
                val next = out[j]
                val overlap = line.endMs - next.startMs
                if (overlap <= 0) break
                val nextDuration = next.endMs - next.startMs
                val intentional = overlap >= INTENTIONAL_OVERLAP_MS ||
                    (overlap > MIN_INTENTIONAL_OVERLAP_MS &&
                        overlap > nextDuration * INTENTIONAL_OVERLAP_RATIO)
                if (intentional) continue
                out[i] = line.copy(endMs = next.startMs)
                changed = true
                break
            }
        }
        return if (changed) out else lines
    }

    /**
     * Word-anchored line timestamps: with words present the line spans its first word's
     * start and last word's end; a single word stamped 0/0 against a non-zero line is
     * backfilled from the line instead (the parser-patch case). Returns the input list
     * unchanged when nothing was retimed (same instance).
     */
    fun resetLineTimestampsFromWords(lines: List<ElrcParser.TimedLine>): List<ElrcParser.TimedLine> {
        if (lines.isEmpty()) return lines
        var changed = false
        val out = lines.map { line ->
            val words = line.words
            val retimed = when {
                words.isNullOrEmpty() -> line
                words.size == 1 && words[0].startMs == 0L && words[0].endMs == 0L &&
                    (line.startMs != 0L || line.endMs != 0L) ->
                    line.copy(words = listOf(words[0].copy(startMs = line.startMs, endMs = line.endMs)))
                else -> line.copy(startMs = words.first().startMs, endMs = words.last().endMs)
            }
            if (retimed !== line && retimed != line) changed = true
            retimed
        }
        return if (changed) out else lines
    }

    /**
     * 词锚定后按 startMs 稳定排序(issue #68 #18 修复:AMLL sortLyricLines 同位)。
     * [resetLineTimestampsFromWords] 把行时间戳拉到词时间戳后,词时序与行时序交叉的源
     * 会打乱升序——下游 [sanitizeUnintentionalOverlaps] 与选行(activeLineAt)都靠
     * "升序 + break 提前退出"工作,乱序输入会漏截断、选错行。同 startMs 保持原相对
     * 顺序(稳定排序,与 AMLL 用 originalIndex 平局同义)。已有序时返回输入实例。
     */
    fun sortedByTimeline(lines: List<ElrcParser.TimedLine>): List<ElrcParser.TimedLine> {
        for (i in 1 until lines.size) {
            if (lines[i - 1].startMs > lines[i].startMs) {
                return lines.sortedBy { it.startMs }
            }
        }
        return lines
    }

    /**
     * 快照出口的同一套规整(issue #68 #17/#18,#75 合并后接入逐行源/整首快照):
     * [LyricSongRow] 与 [ElrcParser.TimedLine] 共享 startMs/endMs/words 三个被规整字段,
     * 先降到 [ElrcParser.TimedLine] 跑同一套「词级对齐 → 时间序稳定排序 → 重叠仲裁」,
     * 再把结果拷回行(translation/roma/role 原样保留)。行与规整结果**成对**随排序
     * 一起置换(sortedBy 稳定,平局保原相对序),回填按位对应不串行。
     */
    fun sanitizeSnapshotRows(rows: List<LyricSongRow>): List<LyricSongRow> {
        if (rows.isEmpty()) return rows
        val asLines = rows.map { ElrcParser.TimedLine(it.startMs, it.endMs, it.text, it.words) }
        val retimed = resetLineTimestampsFromWords(asLines)
        val orderedPairs = rows.zip(retimed).sortedBy { it.second.startMs }
        val orderedRows = orderedPairs.map { it.first }
        val ordered = orderedPairs.map { it.second }
        val sanitized = sanitizeUnintentionalOverlaps(ordered)
        var changed = false
        val out = orderedRows.zip(sanitized) { row, line ->
            if (row.startMs == line.startMs && row.endMs == line.endMs && row.words == line.words) {
                row
            } else {
                changed = true
                row.copy(startMs = line.startMs, endMs = line.endMs, words = line.words)
            }
        }
        val orderChanged = orderedRows.zip(rows).any { (a, b) -> a !== b }
        return if (changed || orderChanged) out else rows
    }
}
