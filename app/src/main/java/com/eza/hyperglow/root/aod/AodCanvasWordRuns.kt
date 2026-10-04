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

/**
 * 缺省词表区间补全:词表未携带原文区间时(插件词表、部分生产者),按词文本在行文本中
 * **顺序**定位补出区间——词间被跳过的空白即原文分隔符,[authoredWordSeparator] 据此取到
 * 真实词距(空白实测宽),不再回退到固定兜底 gap。兜底 gap 只在区间缺失时生效,而它被计入
 * 布局宽(换行点/行宽/对齐)却不被整段绘制路径(静态/共享扫光)与预览画出——同一句歌词的
 * 词距会随渲染路径变化、换行点也与预览不一致;补全区间后布局宽与绘制文本同源。
 *
 * 定位规则(游标只向前):
 *  - 词文本原样命中游标处(含词自带的首尾空白)→ 区间即命中段,自带空白计入词宽;
 *  - 否则跳过游标处空白后按去首尾空白的词文本命中 → 跳过的空白计入分隔符;
 *  - 纯空白词(上游词表的独立分隔词)认领游标处空白段为区间,布局层随后按空白过滤,
 *    其宽度由相邻词间分隔符还原;无可认领空白时原样放行、不推进游标;
 *  - 任一词在预期位置找不到(词表与行文本不一致)→ 整体放弃、原样返回(调用方沿用兜底词距);
 *  - 已带有效区间的词原样保留,游标随其末端推进(混合词表只补缺失项)。
 */
internal fun alignMissingWordOffsets(text: String, words: List<AodCanvasWord>): List<AodCanvasWord> {
    if (words.isEmpty()) return words
    if (words.all { transportedWordOffset(text, it) != null }) return words
    var cursor = 0
    val out = ArrayList<AodCanvasWord>(words.size)
    for (word in words) {
        val existing = transportedWordOffset(text, word)
        if (existing != null) {
            out += word
            cursor = maxOf(cursor, existing.last + 1)
            continue
        }
        val token = word.text
        if (token.isBlank()) {
            // 纯空白词(上游词表的独立分隔词):区间取游标处的空白段;无可认领空白时原样放行、
            // 不推进游标。布局层随后按空白过滤,其宽度由相邻词间分隔符还原。
            var end = cursor
            while (end < text.length && text[end].isWhitespace()) end++
            out += if (end > cursor) word.copy(sourceStart = cursor, sourceEnd = end) else word
            cursor = end
            continue
        }
        val rawMatch = text.startsWith(token, cursor)
        val start: Int
        val length: Int
        if (rawMatch) {
            start = cursor
            length = token.length
        } else {
            var index = cursor
            while (index < text.length && text[index].isWhitespace()) index++
            val trimmed = token.trim()
            if (trimmed.isEmpty() || !text.startsWith(trimmed, index)) return words
            start = index
            length = trimmed.length
        }
        out += word.copy(sourceStart = start, sourceEnd = start + length)
        cursor = start + length
    }
    return out
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
