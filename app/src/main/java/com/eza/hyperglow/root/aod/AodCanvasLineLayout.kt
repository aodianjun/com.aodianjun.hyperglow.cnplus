package com.eza.hyperglow.root.aod

internal fun rubyLineIndex(start: Int, lineStarts: List<Int>, lineEnds: List<Int>): Int? =
    lineStarts.indices.firstOrNull { index -> start >= lineStarts[index] && start < lineEnds[index] }

internal fun lexicalGroupIds(
    wordOffsets: List<IntRange?>,
    groups: List<AodCanvasLayoutGroup>
): List<Int?> = wordOffsets.map { offset ->
    if (offset == null) null else groups.indexOfFirst { group ->
        group.keepTogether && group.end > offset.first && group.start <= offset.last
    }.takeIf { it >= 0 }
}

internal fun coveredLayoutRanges(text: String, groups: List<AodCanvasLayoutGroup>): List<IntRange> {
    val valid = groups.asSequence()
        .filter { it.keepTogether && it.start >= 0 && it.end > it.start && it.end <= text.length }
        .sortedBy { it.start }
        .toList()
    val ranges = ArrayList<IntRange>()
    var cursor = 0
    var groupIndex = 0
    while (cursor < text.length) {
        while (groupIndex < valid.size && valid[groupIndex].end <= cursor) groupIndex++
        val group = valid.getOrNull(groupIndex)
        if (group != null && group.start < cursor) {
            groupIndex++
            continue
        }
        if (group != null && group.start == cursor) {
            ranges += group.start until group.end
            cursor = group.end
            groupIndex++
            continue
        }
        val stop = group?.start?.coerceAtLeast(cursor) ?: text.length
        while (cursor < stop) {
            while (cursor < stop && text[cursor].isWhitespace()) cursor++
            val start = cursor
            while (cursor < stop && !text[cursor].isWhitespace()) cursor++
            if (cursor > start) ranges += start until cursor
        }
    }
    return ranges
}

internal fun balancedChunkRanges(widths: List<Float>, available: Float, maxLines: Int): List<IntRange> {
    if (widths.isEmpty() || maxLines <= 0) return emptyList()
    if (widths.size == 1 || available <= 0f) return listOf(widths.indices)
    val greedy = ArrayList<IntRange>()
    var start = 0
    var width = 0f
    widths.forEachIndexed { index, item ->
        if (index > start && width + item > available) {
            greedy += start until index
            start = index
            width = 0f
        }
        width += item
    }
    greedy += start until widths.size
    val lineCount = greedy.size.coerceAtMost(maxLines)
    if (lineCount <= 1) return listOf(widths.indices)
    val cappedGreedy = if (greedy.size <= maxLines) greedy else ArrayList<IntRange>(maxLines).apply {
        addAll(greedy.take(maxLines - 1))
        add(greedy[maxLines - 1].first until widths.size)
    }

    val prefix = FloatArray(widths.size + 1)
    widths.indices.forEach { index -> prefix[index + 1] = prefix[index] + widths[index] }
    val target = prefix.last() / lineCount
    val infinity = Float.POSITIVE_INFINITY
    val costs = Array(lineCount + 1) { FloatArray(widths.size + 1) { infinity } }
    val previous = Array(lineCount + 1) { IntArray(widths.size + 1) { -1 } }
    costs[0][0] = 0f
    for (line in 1..lineCount) {
        for (end in line..widths.size) {
            for (candidate in line - 1 until end) {
                val lineWidth = prefix[end] - prefix[candidate]
                val allowOverflow = line == lineCount && end == widths.size && greedy.size > maxLines
                if (lineWidth > available && !allowOverflow) continue
                val previousCost = costs[line - 1][candidate]
                if (!previousCost.isFinite()) continue
                val delta = lineWidth - target
                val cost = previousCost + delta * delta
                if (cost < costs[line][end]) {
                    costs[line][end] = cost
                    previous[line][end] = candidate
                }
            }
        }
    }
    if (previous[lineCount][widths.size] < 0) return cappedGreedy
    val result = ArrayList<IntRange>(lineCount)
    var line = lineCount
    var end = widths.size
    while (line > 0) {
        val candidate = previous[line][end]
        result += candidate until end
        end = candidate
        line--
    }
    result.reverse()
    return result
}

internal fun legacyWordLineRanges(
    wordWidths: List<Float>,
    gapAfters: List<Float>,
    available: Float,
    maxLines: Int
): List<IntRange> {
    if (wordWidths.isEmpty() || wordWidths.size != gapAfters.size || maxLines <= 0) return emptyList()
    val lines = ArrayList<IntRange>(maxLines)
    var start = 0
    var currentWidth = 0f
    wordWidths.forEachIndexed { index, wordWidth ->
        if (index > start && currentWidth + wordWidth > available && lines.size < maxLines - 1) {
            lines += start until index
            start = index
            currentWidth = 0f
        }
        currentWidth += wordWidth + gapAfters[index]
    }
    lines += start until wordWidths.size
    return lines
}

internal fun legacyAttachedWordLineRanges(
    words: List<AodCanvasWord>,
    wordWidths: List<Float>,
    gapAfters: List<Float>,
    available: Float,
    maxLines: Int
): List<IntRange> {
    if (words.size != wordWidths.size || words.size != gapAfters.size) return emptyList()
    val chunks = attachedWordRanges(words)
    if (chunks.isEmpty()) return emptyList()
    val chunkWidths = chunks.map { range ->
        range.sumOf { index -> (wordWidths[index] + gapAfters[index]).toDouble() }.toFloat() -
            gapAfters[range.last]
    }
    val chunkGaps = chunks.map { range -> gapAfters[range.last] }
    return legacyWordLineRanges(chunkWidths, chunkGaps, available, maxLines).map { line ->
        chunks[line.first].first..chunks[line.last].last
    }
}

internal fun secondaryTokens(text: String): List<String> =
    text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

internal data class SecondaryTimedSegment(
    val text: String,
    val width: Float,
    val gapAfter: Float,
    val startMs: Long,
    val endMs: Long
)

internal fun secondaryTimedLineRanges(
    segments: List<SecondaryTimedSegment>,
    available: Float,
    maxLines: Int
): List<IntRange> = balancedChunkRanges(
    segments.map { it.width + it.gapAfter },
    available,
    maxLines
)

internal fun secondaryTimedVisualRanges(
    segments: List<SecondaryTimedSegment>,
    available: Float,
    maxLines: Int,
    wrap: Boolean
): List<IntRange> = when {
    segments.isEmpty() || maxLines <= 0 -> emptyList()
    !wrap -> listOf(segments.indices)
    else -> secondaryTimedLineRanges(segments, available, maxLines)
}

internal fun timedRomanizedWordIndexes(words: List<AodCanvasWord>): List<Int> =
    words.indices.filter { words[it].romanized.isNotBlank() }

internal fun secondaryTimedProgress(positionMs: Long, startMs: Long, endMs: Long): Float =
    timedWordProgress(positionMs, startMs, endMs)

internal fun timedWordProgress(positionMs: Long, startMs: Long, endMs: Long): Float = when {
    endMs <= startMs -> if (positionMs >= endMs) 1f else 0f
    positionMs <= startMs -> 0f
    positionMs >= endMs -> 1f
    else -> (positionMs - startMs).toFloat() / (endMs - startMs).toFloat()
}

internal data class GradientSweepZone(val start: Float, val end: Float)

internal fun gradientSweepZone(
    progress: Float,
    extent: Float,
    bandFraction: Float = 0.4f
): GradientSweepZone {
    val safeExtent = extent.coerceAtLeast(0f)
    val band = (safeExtent * bandFraction.coerceIn(0.1f, 1f)).coerceAtLeast(1f)
    val start = -band + (safeExtent + band) * progress.coerceIn(0f, 1f)
    return GradientSweepZone(start, start + band)
}

internal fun balancedTokenLineTexts(
    tokens: List<String>,
    tokenWidths: List<Float>,
    spaceWidth: Float,
    available: Float,
    maxLines: Int
): List<String> {
    if (tokens.isEmpty() || tokens.size != tokenWidths.size) return emptyList()
    val effectiveWidths = tokenWidths.map { it + spaceWidth }
    return balancedChunkRanges(effectiveWidths, available + spaceWidth, maxLines)
        .map { range -> range.joinToString(" ") { tokens[it] } }
}

internal fun joinedRomanizedWords(words: List<Pair<String, Boolean>>): String = buildString {
    words.forEachIndexed { index, (text, boundaryAfter) ->
        if (text.isBlank()) return@forEachIndexed
        append(text)
        if (boundaryAfter && words.drop(index + 1).any { it.first.isNotBlank() }) append(' ')
    }
}
