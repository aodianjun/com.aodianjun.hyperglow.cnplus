package com.eza.hyperglow.producer

/**
 * Parses Enhanced LRC (elrc) and plain LRC lyric text into producer-agnostic timed lines,
 * as published by the LyricInfo Xposed module in `MediaMetadata.extras.lyricInfo`.
 *
 * Supported syntax (both are produced by LyricInfo):
 * - Plain LRC: `[mm:ss.xxx]text`, possibly with multiple leading timestamps for repeated lines.
 * - Enhanced LRC: `[mm:ss.xxx]<mm:ss.xxx>word<mm:ss.xxx>word` — the `<...>` markers give each
 *   word's start time; the text between markers is that word.
 *
 * A [TimedLine] carries a [startMs]/[endMs] and, when word timing is present, a
 * [LyricWord] list (per-word karaoke). Words are sorted ascending by start; the last word's
 * end is filled from the line's end.
 */
object ElrcParser {

    /** A single timed lyric line. */
    data class TimedLine(
        val startMs: Long,
        val endMs: Long,
        val text: String,
        val words: List<LyricWord>?
    )

    private val TIME_REGEX = Regex("""^\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3})?)]""")
    private val WORD_REGEX = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3})?)>""")

    // 零宽不可见字符(U+200B 零宽空格/U+2060 词连接符/U+FEFF BOM):部分歌词源会混入,
    // 会污染逐字高亮的词界与文本比对,行/词文本统一剥离(与 Bridge LyricTextSanitizer 同集合)。
    private val IGNORABLE_CHARS_REGEX = Regex("[\u200B\u2060\uFEFF]")

    /**
     * Parses `lrc` (elrc or plain lrc) into sorted [TimedLine]s. Lines without a leading
     * timestamp are ignored. `defaultLineDurationMs` fills the last line's end.
     *
     * 词级时间轴经 [shouldDowngradeWordTiming] 判定可疑时整行降级为行级(words 清空),
     * 脏逐字数据以错误的词界高亮不如安静回退行级。
     */
    fun parse(lrc: String, defaultLineDurationMs: Long = 4_000L): List<TimedLine> {
        // 占位行(仅空白/零宽字符)不渲染任何内容,直接丢弃(issue #68 #16),
        // 避免产生空活动行;时间轴由相邻真实行衔接。
        val raws = lrc.split("\n").flatMap(::parseRawLine).filterNot { isPlaceholderOnly(it.text) }
        if (raws.isEmpty()) return emptyList()
        val sorted = raws.sortedBy { it.startMs }
        return sorted.mapIndexed { i, raw ->
            val endMs = sorted.getOrNull(i + 1)?.startMs ?: (raw.startMs + defaultLineDurationMs)
            val words = when {
                raw.words == null -> null
                shouldDowngradeWordTiming(raw.words.map { it.startMs }) -> emptyList()
                else -> raw.words.map { word ->
                    if (word.endMs > word.startMs) word else word.copy(endMs = endMs)
                }
            }
            TimedLine(raw.startMs, endMs, raw.text, words)
        }
    }

    /**
     * 占位行判定(Bridge LyricTextSanitizer.isPlaceholderOnly 同语义,issue #68 #16):
     * 只有空白与零宽不可见字符、没有任何可见字形的行返回 true。空串视为占位行。
     */
    internal fun isPlaceholderOnly(text: String): Boolean {
        val codePoints = text.codePoints().iterator()
        while (codePoints.hasNext()) {
            val codePoint = codePoints.next()
            if (codePoint == 0x200B || codePoint == 0x2060 || codePoint == 0xFEFF) continue
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) return false
        }
        return true
    }

    private data class RawLine(val startMs: Long, val text: String, val words: List<LyricWord>?)

    private fun parseRawLine(raw: String): List<RawLine> {
        var text = raw
        val starts = mutableListOf<Long>()
        while (true) {
            val m = TIME_REGEX.find(text) ?: break
            starts.add(toMs(m.groupValues))
            text = text.substring(m.range.last + 1)
        }
        if (starts.isEmpty()) return emptyList()
        val (cleanText, words) = parseWords(text)
        return starts.map { RawLine(it, cleanText, words) }
    }

    private fun parseWords(text: String): Pair<String, List<LyricWord>> {
        val sanitized = IGNORABLE_CHARS_REGEX.replace(text, "")
        val markers = WORD_REGEX.findAll(sanitized).toList()
        if (markers.isEmpty()) return Pair(sanitized.trim(), emptyList())
        val clean = StringBuilder()
        val words = mutableListOf<LyricWord>()
        for (i in markers.indices) {
            val m = markers[i]
            val wordStart = m.range.last + 1
            val wordEnd = if (i + 1 < markers.size) markers[i + 1].range.first else sanitized.length
            val wordText = sanitized.substring(wordStart, wordEnd)
            clean.append(wordText)
            val wend = if (i + 1 < markers.size) toMs(markers[i + 1].groupValues) else toMs(m.groupValues)
            words.add(LyricWord(wordText.trim(), "", toMs(m.groupValues), wend, false))
        }
        return Pair(clean.toString(), words)
    }

    private fun toMs(g: List<String>): Long {
        val min = g[1].toLong()
        val sec = g[2].toLong()
        val fracStr = g.getOrNull(3).orEmpty()
        val frac = if (fracStr.isEmpty()) 0L else fracStr.padEnd(3, '0').substring(0, 3).toLong()
        return min * 60_000L + sec * 1_000L + frac
    }

    /**
     * 词级时间轴可疑降级判定(纯函数;启发与 ColorOS Live Lyrics Bridge 的
     * LyricTimingRepair 对齐,issue #68):
     * - 词起点非严格递增(乱序或同刻)→ 词时间轴整体不可信;
     * - 存在 ≥[SUSPICIOUS_WORD_GAP_MS] 的行内间隙,且(词数 ≤4 或 最大间隙 ≥ 跨度 2/3)
     *   → 伪逐字形态(整句一个词标 + 稀疏点缀),真实的两段式长句间隙占比不会这么高。
     * 返回 true 时调用方应放弃词级、按行级渲染。
     */
    internal fun shouldDowngradeWordTiming(wordStartsMs: List<Long>): Boolean {
        if (wordStartsMs.size < 2) return false
        var maxGap = 0L
        var strictlyIncreasing = true
        for (i in 1 until wordStartsMs.size) {
            val prev = wordStartsMs[i - 1]
            val cur = wordStartsMs[i]
            if (cur <= prev) strictlyIncreasing = false else maxGap = maxOf(maxGap, cur - prev)
        }
        if (!strictlyIncreasing) return true
        val span = wordStartsMs.last() - wordStartsMs.first()
        if (span <= 0L) return false
        return maxGap >= SUSPICIOUS_WORD_GAP_MS &&
            (wordStartsMs.size <= 4 || maxGap * 3 >= span * 2)
    }

    private const val SUSPICIOUS_WORD_GAP_MS = 8_000L

    /**
     * Selects the active [TimedLine] for [positionMs] (the last line whose start is <= position,
     * or the first line when before the first timestamp). Returns null when there are no lines.
     */
    fun activeLineAt(lines: List<TimedLine>, positionMs: Long): TimedLine? {
        if (lines.isEmpty()) return null
        var active = lines[0]
        for (line in lines) {
            if (line.startMs <= positionMs) active = line else break
        }
        return active
    }
}