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

/**
 * DP 最小二乘均衡分块:行数取贪心所需行数(封顶 [maxLines],不因加权增加),各块宽尽量贴近
 * 平均目标宽;整体放不下时末行允许超宽(超宽组合块先拆词的兜底)。
 *
 * [breakAfter] 与 [widths] 逐位对应:第 i 位为 true 表示第 i 块以子句标点结尾,断点落在其
 * 后的行按 [PUNCTUATION_BREAK_BONUS_FRACTION] 折扣代价 —— 在不增加行数的前提下优先在
 * 子句标点后断行。空表(或全 false)时结果与加权前逐值一致。
 */
internal fun balancedChunkRanges(
    widths: List<Float>,
    available: Float,
    maxLines: Int,
    breakAfter: List<Boolean> = emptyList()
): List<IntRange> {
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
    // 行尾落在子句标点后时读起来像完整短语,按平方均衡目标宽的一小部分折扣该行代价。
    // 行数已是最小值、且每行仍须放得下,折扣只会重排同一紧凑布局内的断点。
    val breakBonus = if (target > 0f) {
        PUNCTUATION_BREAK_BONUS_FRACTION * target * target
    } else {
        0f
    }
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
                val bonus = if (breakBonus > 0f && line < lineCount &&
                    breakAfter.getOrElse(end - 1) { false }
                ) {
                    breakBonus
                } else {
                    0f
                }
                val cost = previousCost + delta * delta - bonus
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

/**
 * 行尾落在子句标点后时,该断行读起来像完整短语,其行代价按平方均衡目标宽的
 * 此比例折扣。大到能压过几乎等宽的任意断点,小到严重参差的短语断行仍会输给
 * 均衡断行。
 */
internal const val PUNCTUATION_BREAK_BONUS_FRACTION = 0.2f

/**
 * [text] 是否以子句标点结尾(读起来像自然的断行点):跳过行尾空白与收尾
 * 引号/括号后,末字符落在句读标点集内。标点集照上游,涵盖中英日句读。
 */
internal fun endsWithClausePunctuation(text: String): Boolean {
    var end = text.length
    while (end > 0 && (text[end - 1].isWhitespace() || text[end - 1] in "\"'’”«»()]}」』】》〉")) end--
    if (end <= 0) return false
    return text[end - 1] in ",.;:!?…，。！？：；、"
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
    // 超宽附着块(整块排不下,如非 Spicy 源把整行词标为附着)按词拆开:否则该块独占一行仍超宽、
    // 被画布裁剪(与均衡路径「超宽组合块先拆词」同式)。放得下的附着块保持整块不拆。
    val chunks = attachedWordRanges(words).flatMap { range ->
        val chunkWidth = range.sumOf { index -> (wordWidths[index] + gapAfters[index]).toDouble() }
            .toFloat() - gapAfters[range.last]
        if (chunkWidth > available && range.count() > 1) range.map { it..it } else listOf(range)
    }
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
    maxLines,
    segments.map { endsWithClausePunctuation(it.text) }
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

/**
 * 翻译辅助行的逐字段(纯函数,实机 `AodLyricCanvasView.translatedTimedLines` 与单测共用)。
 *
 * 片段文本**直接相连**即整行译文——西文词间的空格由来源写在片段内,这里不另插分隔符;
 * 空文本片段不占位。两条拒绝条件都回落到「行窗口 + 行内几何合成」：
 * - **片段重建不出整行译文**（拼接 ≠ [expectedText]）：辅助行的显示文本恒取 `translated`，
 *   若按片段画就会在逐字开关开/关之间显示两份不同文本——宁可不要逐字效果；
 *   插件侧按协议保证一致，源侧词表（Lyricon SDK）可能与文本不同；
 * - **整组都是零窗**（占位词形态）：零窗段被 [timedWordProgress] 判成恒亮，整行会呈静态全亮
 *   （与 [isTimedKaraokeWord] 的判据同源）。
 *
 * [measureText] 为行内字体测量(实机传 `translatedPaint::measureText`)。
 */
internal fun translatedTimedSegments(
    words: List<AodCanvasWord>,
    expectedText: String,
    measureText: (String) -> Float
): List<SecondaryTimedSegment>? {
    if (words.isEmpty()) return null
    if (words.joinToString("") { it.text } != expectedText) return null
    val segments = words.mapNotNull { word ->
        if (word.text.isEmpty()) return@mapNotNull null
        SecondaryTimedSegment(
            text = word.text,
            width = measureText(word.text),
            gapAfter = 0f,
            startMs = word.startMs,
            endMs = word.endMs
        )
    }
    if (segments.isEmpty()) return null
    if (segments.none { isTimedKaraokeWord(it.startMs, it.endMs) }) return null
    return segments
}

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
    return balancedChunkRanges(
        effectiveWidths,
        available + spaceWidth,
        maxLines,
        tokens.map(::endsWithClausePunctuation)
    ).map { range -> range.joinToString(" ") { tokens[it] } }
}

internal fun joinedRomanizedWords(words: List<Pair<String, Boolean>>): String = buildString {
    words.forEachIndexed { index, (text, boundaryAfter) ->
        if (text.isBlank()) return@forEachIndexed
        append(text)
        if (boundaryAfter && words.drop(index + 1).any { it.first.isNotBlank() }) append(' ')
    }
}
