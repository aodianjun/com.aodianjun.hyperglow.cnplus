package com.eza.hyperglow.root.aod

internal data class AodCanvasWord(
    val text: String,
    val romanized: String,
    val startMs: Long,
    val endMs: Long,
    val boundaryAfter: Boolean,
    val sourceStart: Int = -1,
    val sourceEnd: Int = -1
)

internal data class AodCanvasRuby(val start: Int, val end: Int, val reading: String)

/** 布局后的词:测量宽/词距/原文区间(词行布局与逐字/音标几何共用,布局引擎输出携带)。 */
internal data class PlacedWord(
    val word: AodCanvasWord,
    val width: Float,
    val gapAfter: Float,
    val offset: IntRange?
)

internal data class OriginalTextRun(val start: Int, val end: Int, val x: Float)

internal fun originalTextRuns(
    textLength: Int,
    rubyBaseRuns: List<OriginalTextRun>,
    widthBefore: (Int) -> Float
): List<OriginalTextRun> {
    if (textLength <= 0) return emptyList()
    if (rubyBaseRuns.isEmpty()) return listOf(OriginalTextRun(0, textLength, 0f))
    val runs = ArrayList<OriginalTextRun>(rubyBaseRuns.size * 2 + 1)
    var cursor = 0
    rubyBaseRuns.forEach { rubyRun ->
        val start = rubyRun.start.coerceIn(cursor, textLength)
        val end = rubyRun.end.coerceIn(start, textLength)
        if (cursor < start) runs += OriginalTextRun(cursor, start, widthBefore(cursor))
        if (start < end) runs += OriginalTextRun(start, end, rubyRun.x)
        cursor = end
    }
    if (cursor < textLength) runs += OriginalTextRun(cursor, textLength, widthBefore(cursor))
    return runs
}

internal fun transportedWordOffset(text: String, word: AodCanvasWord): IntRange? =
    if (word.sourceStart >= 0 && word.sourceStart < word.sourceEnd && word.sourceEnd <= text.length) {
        word.sourceStart until word.sourceEnd
    } else {
        null
    }

internal fun aodWordGapAfter(boundaryAfter: Boolean, gap: Float): Float =
    if (boundaryAfter) gap else 0f

internal fun attachedWordRanges(words: List<AodCanvasWord>): List<IntRange> {
    if (words.isEmpty()) return emptyList()
    val ranges = ArrayList<IntRange>()
    var start = 0
    words.forEachIndexed { index, word ->
        if (word.boundaryAfter || index == words.lastIndex) {
            ranges += start until index + 1
            start = index + 1
        }
    }
    return ranges
}

internal fun authoredWordSeparator(
    text: String,
    current: AodCanvasWord,
    next: AodCanvasWord
): String? {
    val currentRange = transportedWordOffset(text, current) ?: return null
    val nextRange = transportedWordOffset(text, next) ?: return null
    val currentEnd = currentRange.last + 1
    if (currentEnd > nextRange.first) return null
    return text.substring(currentEnd, nextRange.first).takeIf { separator ->
        separator.all(Char::isWhitespace)
    }
}

internal fun coalesceRubyWords(
    text: String,
    words: List<AodCanvasWord>,
    ruby: List<AodCanvasRuby>
): List<AodCanvasWord> {
    val crossings = ruby.asSequence()
        .filter { segment -> segment.start >= 0 && segment.start < segment.end && segment.end <= text.length }
        .mapNotNull { segment ->
        val covered = words.indices.filter { index ->
            val range = transportedWordOffset(text, words[index])
            range != null && segment.start < range.last + 1 && segment.end > range.first
        }
        if (covered.size > 1 && (covered.first()..covered.last()).all {
                transportedWordOffset(text, words[it]) != null
            }) covered.first()..covered.last() else null
    }.sortedBy { it.first }
        .toList()
    if (crossings.isEmpty()) return words

    val merged = ArrayList<IntRange>()
    crossings.forEach { range ->
        if (merged.isEmpty() || range.first > merged.last().last + 1) merged += range
        else merged[merged.lastIndex] = merged.last().first..maxOf(merged.last().last, range.last)
    }
    val output = ArrayList<AodCanvasWord>()
    var index = 0
    while (index < words.size) {
        val range = merged.firstOrNull { it.first == index }
        if (range == null) {
            output += words[index++]
            continue
        }
        val first = words[range.first]
        val last = words[range.last]
        val start = first.sourceStart
        val end = last.sourceEnd
        output += AodCanvasWord(
            text.substring(start, end),
            joinedRomanizedWords(words.subList(range.first, range.last + 1).map { it.romanized to it.boundaryAfter }),
            first.startMs,
            maxOf(first.startMs + 1L, last.endMs),
            last.boundaryAfter,
            start,
            end
        )
        index = range.last + 1
    }
    return output
}
