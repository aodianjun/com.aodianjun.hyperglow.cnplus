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

    /**
     * Parses `lrc` (elrc or plain lrc) into sorted [TimedLine]s. Lines without a leading
     * timestamp are ignored. `defaultLineDurationMs` fills the last line's end.
     */
    fun parse(lrc: String, defaultLineDurationMs: Long = 4_000L): List<TimedLine> {
        val raws = lrc.split("\n").flatMap(::parseRawLine)
        if (raws.isEmpty()) return emptyList()
        val sorted = raws.sortedBy { it.startMs }
        return sorted.mapIndexed { i, raw ->
            val endMs = sorted.getOrNull(i + 1)?.startMs ?: (raw.startMs + defaultLineDurationMs)
            val words = raw.words?.map { word ->
                if (word.endMs > word.startMs) word else word.copy(endMs = endMs)
            }
            TimedLine(raw.startMs, endMs, raw.text, words)
        }
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
        val markers = WORD_REGEX.findAll(text).toList()
        if (markers.isEmpty()) return Pair(text.trim(), emptyList())
        val clean = StringBuilder()
        val words = mutableListOf<LyricWord>()
        for (i in markers.indices) {
            val m = markers[i]
            val wordStart = m.range.last + 1
            val wordEnd = if (i + 1 < markers.size) markers[i + 1].range.first else text.length
            val wordText = text.substring(wordStart, wordEnd)
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