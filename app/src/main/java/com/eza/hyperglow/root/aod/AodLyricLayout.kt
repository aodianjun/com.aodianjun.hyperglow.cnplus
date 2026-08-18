package com.eza.hyperglow.root.aod

// 职责：AOD 歌词纯布局计算——原文/逐词/注音行构建、次行折行、行堆叠定位，均为无 View 依赖的显式参数化函数。

import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.max

internal data class AodLyricLayoutInput(
    val content: AodCanvasContent,
    val originalPaint: Paint,
    val romanizedPaint: Paint,
    val rubyPaint: Paint,
    val alignment: AodLyricCanvasView.Alignment,
    val verticalAlignment: AodCanvasVerticalAlignment,
    val width: Int,
    val height: Int,
    val paddingLeft: Int,
    val paddingTop: Int,
    val paddingRight: Int,
    val paddingBottom: Int,
    val density: Float
)

internal enum class RowKind { METADATA, ORIGINAL, ROMANIZED, TRANSLATED, NEXT_LINE }

internal data class PlacedWord(
    val word: AodCanvasWord,
    val width: Float,
    val gapAfter: Float,
    val offset: IntRange?
)

internal data class OriginalLine(
    val text: String,
    val words: List<PlacedWord>,
    val width: Float,
    val startX: Float,
    val charStart: Int?,
    val charEnd: Int?,
    val ruby: List<RubyPlacement> = emptyList(),
    val rubyHeight: Float = 0f,
    val textRuns: List<OriginalTextRun> = emptyList()
)

internal data class TextLine(
    val text: String,
    val width: Float,
    val startX: Float,
    val timedSegments: List<SecondaryTimedSegment> = emptyList()
)

internal data class BaseRun(val x: Float, val width: Float)

internal data class RubyPlacement(
    val baseStart: Int,
    val baseEnd: Int,
    val baseX: Float,
    val baseWidth: Float,
    val spanX: Float,
    val spanWidth: Float,
    val extraWidth: Float,
    val baseOffset: Float,
    val rubyCenterX: Float,
    val reading: String
)

internal data class Row(
    val kind: RowKind,
    val text: String,
    val paint: Paint,
    val height: Float,
    val gapBefore: Float,
    val lines: List<TextLine>,
    val lineHeight: Float
)

internal data class PositionedRow(val row: Row, val baseline: Float, val animate: Boolean)

internal data class OriginalLayout(
    val lines: List<OriginalLine>,
    val lineHeight: Float,
    val lineGap: Float,
    val timed: Boolean
) {
    val lineCount: Int
        get() = lines.size
    val rubyHeight: Float
        get() = lines.sumOf { it.rubyHeight.toDouble() }.toFloat()
    private val totalLineWidth = lines.sumOf { it.width.coerceAtLeast(0f).toDouble() }.toFloat()
    private val precedingWidths = FloatArray(lines.size).also { values ->
        var preceding = 0f
        lines.forEachIndexed { index, line ->
            values[index] = preceding
            preceding += line.width.coerceAtLeast(0f)
        }
    }

    fun continuousFill(progress: Float, lineIndex: Int): Float {
        val width = lines[lineIndex].width.coerceAtLeast(0f)
        if (width == 0f || totalLineWidth <= 0f) return 0f
        return ((progress.coerceIn(0f, 1f) * totalLineWidth - precedingWidths[lineIndex]) / width)
            .coerceIn(0f, 1f)
    }
}

internal data class LayoutState(
    val rows: List<PositionedRow>,
    val original: OriginalLayout
)

internal fun buildOriginalLayout(input: AodLyricLayoutInput): OriginalLayout {
    val content = input.content
    val originalPaint = input.originalPaint
    val words = coalesceRubyWords(
        content.original,
        content.words.filter { it.text.isNotBlank() },
        content.ruby
    )
    val lines = if (words.isEmpty()) {
        if (content.adaptiveSectioning) layoutTextByGroups(input)
        else wrapText(input, content.original, originalPaint)
    } else {
        layoutWordLines(input, words, 8f * input.density)
    }
    val metrics = originalPaint.fontMetrics
    return OriginalLayout(
        assignRuby(input, lines),
        metrics.descent - metrics.ascent + 2f * input.density,
        ORIGINAL_LINE_GAP_DP * input.density,
        words.isNotEmpty()
    )
}

internal fun layoutTextByGroups(input: AodLyricLayoutInput): List<OriginalLine> {
    val content = input.content
    val ranges = coveredLayoutRanges(content.original, content.layoutGroups)
    if (ranges.isEmpty()) return wrapText(input, content.original, input.originalPaint)
    val synthetic = ranges.mapIndexed { index, range ->
        val nextStart = ranges.getOrNull(index + 1)?.first ?: range.last + 1
        val boundaryAfter = index < ranges.lastIndex && content.original
            .substring(range.last + 1, nextStart).any { it.isWhitespace() }
        AodCanvasWord(
            content.original.substring(range.first, range.last + 1),
            "",
            0L,
            0L,
            boundaryAfter,
            range.first,
            range.last + 1
        )
    }
    return layoutWordLines(input, synthetic, 8f * input.density)
}

internal fun layoutWordLines(
    input: AodLyricLayoutInput,
    words: List<AodCanvasWord>,
    gap: Float
): List<OriginalLine> {
    val content = input.content
    val originalPaint = input.originalPaint
    val available = (input.width - input.paddingLeft - input.paddingRight).coerceAtLeast(1).toFloat()
    val maxLines = lyricLayoutLineLimit(input, words.size)
    val offsets = wordOffsets(input, words)
    val placed = words.mapIndexed { index, word ->
        val wordWidth = originalPaint.measureText(word.text)
        val gapAfter = if (index == words.lastIndex) {
            0f
        } else {
            authoredWordSeparator(content.original, word, words[index + 1])
                ?.let(originalPaint::measureText)
                ?: aodWordGapAfter(word.boundaryAfter, gap)
        }
        PlacedWord(word, wordWidth, gapAfter, offsets[index])
    }
    if (content.overflowMode != "Wrap") {
        return listOf(wordLine(input, placed))
    }
    if (!content.adaptiveSectioning) {
        return legacyAttachedWordLineRanges(
            words,
            placed.map(PlacedWord::width),
            placed.map(PlacedWord::gapAfter),
            available,
            maxLines
        ).map { range ->
            val lineWords = range.map(placed::get)
            wordLine(input, lineWords)
        }
    }
    val groupIds = lexicalGroupIds(offsets, content.layoutGroups)
    val chunks = ArrayList<List<PlacedWord>>()
    var index = 0
    while (index < placed.size) {
        val groupId = groupIds[index]
        var end = index + 1
        if (groupId != null) while (end < placed.size && groupIds[end] == groupId) end++
        val chunk = placed.subList(index, end)
        val chunkWidth = chunk.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
        if (chunkWidth > available && chunk.size > 1) chunk.forEach { chunks += listOf(it) }
        else chunks += chunk.toList()
        index = end
    }
    val chunkWidths = chunks.map { chunk ->
        chunk.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
    }
    val lines = balancedChunkRanges(chunkWidths, available, maxLines).map { range ->
        val lineWords = range.flatMap { chunks[it] }
        wordLine(input, lineWords)
    }
    return lines.ifEmpty { listOf(originalLine(input, "", 0f, null, null)) }
}

internal fun wordLine(input: AodLyricLayoutInput, words: List<PlacedWord>): OriginalLine {
    val original = input.content.original
    val mapped = words.mapNotNull { word -> word.offset?.let { it.first to it.last + 1 } }
    val offsets = mapped.takeIf { it.size == words.size }
    val start = offsets?.minOf { it.first }
    val end = offsets?.maxOf { it.second }
    val text = if (start != null && end != null && start >= 0 && end <= original.length) {
        original.substring(start, end)
    } else {
        buildString {
            words.forEachIndexed { index, placed ->
                append(placed.word.text)
                if (index < words.lastIndex && placed.gapAfter > 0f) append(' ')
            }
        }
    }
    val width = words.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat() -
        (words.lastOrNull()?.gapAfter ?: 0f)
    return originalLine(
        input,
        text,
        width,
        start,
        end
    )
        .copy(words = words)
}

internal fun wrapText(input: AodLyricLayoutInput, text: String, paint: Paint): List<OriginalLine> {
    if (text.isBlank()) return emptyList()
    val available = (input.width - input.paddingLeft - input.paddingRight).coerceAtLeast(1).toFloat()
    if (input.content.overflowMode != "Wrap") {
        return listOf(originalLine(input, text, paint.measureText(text), 0, text.length))
    }
    val maxLines = lyricLayoutLineLimit(input)
    val lines = ArrayList<OriginalLine>(maxLines)
    var remaining = text
    var charStart = 0
    while (remaining.isNotEmpty() && lines.size < maxLines) {
        val count = paint.breakText(remaining, true, available, null).coerceAtLeast(1)
        val line = remaining.take(count)
        lines += originalLine(input, line, paint.measureText(line), charStart, charStart + line.length)
        remaining = remaining.drop(count)
        charStart += count
    }
    return lines
}

internal fun lyricLayoutLineLimit(
    input: AodLyricLayoutInput,
    wordCount: Int = input.content.words.size
): Int = resolvedLyricLayoutLineLimit(
    input.content.lyricLineLimit,
    input.content.original.length,
    wordCount
)

internal fun transliterationLines(
    input: AodLyricLayoutInput,
    originalLayout: OriginalLayout
): List<TextLine>? {
    val content = input.content
    val romanizedPaint = input.romanizedPaint
    if (originalLayout.lines.isEmpty() || originalLayout.lines.any { it.words.isEmpty() }) return null
    val available = (input.width - input.paddingLeft - input.paddingRight).coerceAtLeast(1).toFloat()
    val sourceWords = originalLayout.lines.flatMap { it.words }.map { it.word }
    if (sourceWords.isEmpty()) return null
    val spaceWidth = romanizedPaint.measureText(" ")
    val timedIndexes = timedRomanizedWordIndexes(sourceWords)
    val segments = timedIndexes.mapIndexed { renderedIndex, sourceIndex ->
        val word = sourceWords[sourceIndex]
        val text = word.romanized.trim()
        val nextSourceIndex = timedIndexes.getOrNull(renderedIndex + 1)
        SecondaryTimedSegment(
            text = text,
            width = romanizedPaint.measureText(text),
            gapAfter = if (nextSourceIndex != null && word.boundaryAfter) spaceWidth else 0f,
            startMs = word.startMs,
            endMs = word.endMs
        )
    }
    if (segments.isEmpty()) return null
    return secondaryTimedVisualRanges(
        segments,
        available,
        MAX_SECONDARY_LINES,
        wrap = content.adaptiveSectioning && content.overflowMode == "Wrap"
    ).map { range ->
        val lineSegments = range.map(segments::get).mapIndexed { index, segment ->
            if (index == range.count() - 1) segment.copy(gapAfter = 0f) else segment
        }
        val text = buildString {
            lineSegments.forEach { segment ->
                append(segment.text)
                if (segment.gapAfter > 0f) append(' ')
            }
        }
        val lineWidth = lineSegments.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
        return@map textLine(input, text, lineWidth, romanizedPaint).copy(timedSegments = lineSegments)
    }
}

internal fun wrapSecondaryText(
    input: AodLyricLayoutInput,
    text: String,
    paint: Paint,
    preferredLines: Int
): List<TextLine> {
    val content = input.content
    if (!content.adaptiveSectioning || content.overflowMode != "Wrap") {
        return listOf(textLine(input, text, paint.measureText(text), paint))
    }
    val available = (input.width - input.paddingLeft - input.paddingRight).coerceAtLeast(1).toFloat()
    val tokens = secondaryTokens(text).flatMap { token ->
        if (paint.measureText(token) <= available) {
            listOf(token)
        } else {
            val pieces = ArrayList<String>()
            var remaining = token
            while (remaining.isNotEmpty()) {
                val count = paint.breakText(remaining, true, available, null).coerceAtLeast(1)
                pieces += remaining.take(count)
                remaining = remaining.drop(count)
            }
            pieces
        }
    }
    if (tokens.isEmpty()) return emptyList()
    val maxLines = if (paint.measureText(text) > available) {
        maxOf(preferredLines, MAX_SECONDARY_LINES)
    } else {
        preferredLines
    }.coerceIn(1, MAX_SECONDARY_LINES)
    return balancedTokenLineTexts(
        tokens,
        tokens.map(paint::measureText),
        paint.measureText(" "),
        available,
        maxLines
    ).map { line -> textLine(input, line, paint.measureText(line), paint) }
}

internal fun textLine(
    input: AodLyricLayoutInput,
    text: String,
    width: Float,
    paint: Paint,
    lineAlignment: AodLyricCanvasView.Alignment = input.alignment
): TextLine {
    val visual = visualExtents(text, paint, width)
    return TextLine(text, width, alignedStart(input, width, lineAlignment, visual.first, visual.second))
}

internal fun originalLine(
    input: AodLyricLayoutInput,
    text: String,
    width: Float,
    charStart: Int?,
    charEnd: Int?
): OriginalLine {
    val visual = visualExtents(text, input.originalPaint, width)
    return OriginalLine(
        text,
        emptyList(),
        width,
        alignedStart(input, width, input.alignment, visual.first, visual.second),
        charStart,
        charEnd
    )
}

internal fun wordOffsets(
    input: AodLyricLayoutInput,
    words: List<AodCanvasWord>
): List<IntRange?> =
    words.map { transportedWordOffset(input.content.original, it) }

internal fun assignRuby(
    input: AodLyricLayoutInput,
    lines: List<OriginalLine>
): List<OriginalLine> {
    val content = input.content
    val originalPaint = input.originalPaint
    val rubyPaint = input.rubyPaint
    return lines.map { line ->
        val lineStart = line.charStart
        val lineEnd = line.charEnd
        if (lineStart == null || lineEnd == null) return@map line

        val placements = content.ruby.asSequence()
            .filter { segment ->
                segment.start >= 0 && segment.end > segment.start &&
                    segment.end <= content.original.length &&
                    segment.start < lineEnd && segment.end > lineStart
            }
            .sortedBy { it.start }
            .mapNotNull { segment ->
                val baseStart = maxOf(segment.start, lineStart)
                val baseEnd = minOf(segment.end, lineEnd)
                val baseRun = measureBaseRun(input, line, baseStart, baseEnd) ?: return@mapNotNull null
                val geometry = rubySpanGeometry(
                    baseRun.x,
                    baseRun.width,
                    rubyPaint.measureText(segment.reading)
                )
                RubyPlacement(
                    baseStart = baseStart,
                    baseEnd = baseEnd,
                    baseX = geometry.baseX,
                    baseWidth = geometry.baseWidth,
                    spanX = geometry.spanX,
                    spanWidth = geometry.spanWidth,
                    extraWidth = 0f,
                    baseOffset = 0f,
                    rubyCenterX = geometry.rubyCenterX,
                    reading = segment.reading
                )
            }
            .toList()
        val rubyHeight = if (placements.isEmpty()) 0f else {
            rubyReservation(originalPaint.textSize, rubyPaint.fontMetrics.ascent)
        }
        val baseVisual = visualExtents(line.text, originalPaint, line.width)
        val visualLeft = minOf(
            baseVisual.first,
            placements.minOfOrNull { it.spanX } ?: baseVisual.first
        )
        val visualRight = maxOf(
            baseVisual.second,
            placements.maxOfOrNull { it.spanX + it.spanWidth } ?: baseVisual.second
        )
        line.copy(
            startX = alignedStart(input, line.width, input.alignment, visualLeft, visualRight),
            ruby = placements,
            rubyHeight = rubyHeight,
            textRuns = originalTextRuns(
                line.text.length,
                placements.map { placement ->
                    OriginalTextRun(
                        (placement.baseStart - lineStart).coerceIn(0, line.text.length),
                        (placement.baseEnd - lineStart).coerceIn(0, line.text.length),
                        placement.baseX
                    )
                }
            ) { end -> originalPaint.measureText(line.text, 0, end) }
        )
    }
}

internal fun measureBaseRun(
    input: AodLyricLayoutInput,
    line: OriginalLine,
    start: Int,
    end: Int
): BaseRun? {
    if (start >= end) return null
    val lineStart = line.charStart ?: return null
    val originalPaint = input.originalPaint
    if (line.words.isEmpty()) {
        val localStart = (start - lineStart).coerceIn(0, line.text.length)
        val localEnd = (end - lineStart).coerceIn(localStart, line.text.length)
        val prefixWidth = originalPaint.measureText(line.text, 0, localStart)
        return BaseRun(
            prefixWidth,
            originalPaint.measureText(line.text, localStart, localEnd)
        )
    }

    var x = 0f
    var firstX: Float? = null
    var lastX = 0f
    line.words.forEach { placed ->
        val offset = placed.offset
        if (offset != null) {
            val wordStart = offset.first
            val wordEnd = offset.last + 1
            val overlapStart = maxOf(start, wordStart)
            val overlapEnd = minOf(end, wordEnd)
            if (overlapStart < overlapEnd) {
                val localStart = overlapStart - wordStart
                val localEnd = overlapEnd - wordStart
                val runStart = x + originalPaint.measureText(placed.word.text, 0, localStart)
                val runEnd = x + originalPaint.measureText(placed.word.text, 0, localEnd)
                if (firstX == null) firstX = runStart
                lastX = runEnd
            }
        }
        x += placed.width + placed.gapAfter
    }
    val baseX = firstX ?: return null
    return BaseRun(baseX, (lastX - baseX).coerceAtLeast(0f))
}

internal fun row(
    input: AodLyricLayoutInput,
    kind: RowKind,
    text: String,
    paint: Paint,
    gap: Float,
    allowWrap: Boolean = true
): Row {
    val lines = if (allowWrap) {
        wrapSecondaryText(input, text, paint, MAX_SECONDARY_LINES)
    } else {
        listOf(textLine(input, text, paint.measureText(text), paint, alignmentFor(input, kind)))
    }
    return rowWithLines(kind, text, paint, gap, lines)
}

internal fun rowWithLines(
    kind: RowKind,
    text: String,
    paint: Paint,
    gap: Float,
    lines: List<TextLine>
): Row {
    val metrics = paint.fontMetrics
    val lineHeight = safeSecondaryLineHeight(metrics.ascent, metrics.descent, metrics.bottom)
    return Row(kind, text, paint, lineHeight * lines.size, gap, lines, lineHeight)
}

internal fun positionRows(
    input: AodLyricLayoutInput,
    rows: List<Row>,
    originalLayout: OriginalLayout
): List<PositionedRow> {
    val content = input.content
    val density = input.density
    val positioned = ArrayList<PositionedRow>(rows.size)
    val metadata = rows.firstOrNull { it.kind == RowKind.METADATA }
    if (metadata != null) {
        val anchor = when (content.metadataAnchor) {
            "bottom" -> "bottom"
            else -> "top"
        }
        val metadataBounds = metadataLayoutBounds(
            anchor,
            input.height.toFloat(),
            input.paddingTop.toFloat(),
            input.paddingBottom.toFloat(),
            metadata.paint.fontMetrics.ascent,
            metadata.paint.fontMetrics.descent,
            10f * density
        )
        val metadataBaseline = metadataBounds.metadataBaseline
        positioned += PositionedRow(metadata, metadataBaseline, false)
        val lyricRows = rows.filterNot { it.kind == RowKind.METADATA }
        val gap = 10f * density
        if (anchor == "bottom") {
            var bottom = metadataBounds.lyricEnd
            lyricRows.asReversed().forEach { row ->
                bottom -= row.height
                positioned += PositionedRow(row, bottom - row.paint.fontMetrics.ascent, true)
                bottom -= row.gapBefore
            }
            positioned.sortBy { it.baseline }
        } else {
            var top = metadataBounds.lyricStart
            lyricRows.forEach { row ->
                top += row.gapBefore
                positioned += PositionedRow(row, top - row.paint.fontMetrics.ascent, true)
                top += row.height
            }
        }
    } else {
        val total = rows.sumOf { (it.height + it.gapBefore).toDouble() }.toFloat()
        val topPadding = input.paddingTop.toFloat()
        val bottomPadding = input.height - input.paddingBottom
        val available = (bottomPadding - topPadding).coerceAtLeast(0f)
        var top = if (input.verticalAlignment == AodCanvasVerticalAlignment.TOP) {
            topPadding
        } else {
            topPadding + max(0f, (available - total) / 2f)
        }
        rows.forEach { row ->
            top += row.gapBefore
            positioned += PositionedRow(row, top - row.paint.fontMetrics.ascent, true)
            top += row.height
        }
    }
    val original = positioned.firstOrNull { it.row.kind == RowKind.ORIGINAL }
    val firstLine = originalLayout.lines.firstOrNull()
    if (original == null || firstLine == null || firstLine.rubyHeight <= 0f) return positioned
    val firstBaseBaseline = original.baseline + firstLine.rubyHeight
    val top = rubyClipTop(firstBaseBaseline, input.originalPaint.fontMetrics.ascent, firstLine.rubyHeight)
    val shift = rubyTopShift(top, input.paddingTop.toFloat())
    return if (shift == 0f) positioned else positioned.map {
        if (it.row.kind == RowKind.METADATA) it else it.copy(baseline = it.baseline + shift)
    }
}

internal fun verticalBounds(
    state: LayoutState,
    viewHeight: Int
): AodCanvasVerticalBounds? {
    if (state.rows.isEmpty()) return null
    var top = Float.POSITIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    state.rows.forEach { positioned ->
        val rowTop = positioned.baseline + positioned.row.paint.fontMetrics.ascent
        top = minOf(top, rowTop)
        bottom = maxOf(bottom, rowTop + positioned.row.height)
    }
    if (!top.isFinite() || !bottom.isFinite() || bottom <= top) return null
    return AodCanvasVerticalBounds(
        top.coerceIn(0f, viewHeight.toFloat()),
        bottom.coerceIn(0f, viewHeight.toFloat())
    )
}

internal fun alignmentFor(
    input: AodLyricLayoutInput,
    kind: RowKind
): AodLyricCanvasView.Alignment = if (kind == RowKind.METADATA) {
    when (input.content.alignmentMode) {
        "start" -> AodLyricCanvasView.Alignment.START
        "center" -> AodLyricCanvasView.Alignment.CENTER
        "end" -> AodLyricCanvasView.Alignment.END
        else -> AodLyricCanvasView.Alignment.START
    }
} else {
    input.alignment
}

internal fun alignedStart(
    input: AodLyricLayoutInput,
    textWidth: Float,
    lineAlignment: AodLyricCanvasView.Alignment = input.alignment,
    visualLeft: Float = 0f,
    visualRight: Float = textWidth
): Float = edgeSafeAlignedStart(
    canvasWidth = input.width.toFloat(),
    paddingLeft = input.paddingLeft.toFloat(),
    paddingRight = input.paddingRight.toFloat(),
    visualLeft = visualLeft,
    visualRight = visualRight,
    alignment = when (lineAlignment) {
        AodLyricCanvasView.Alignment.START -> "start"
        AodLyricCanvasView.Alignment.CENTER -> "center"
        AodLyricCanvasView.Alignment.END -> "end"
    },
    safetyInset = if (lineAlignment == AodLyricCanvasView.Alignment.END) END_EDGE_SAFETY_DP * input.density else 0f
)

internal fun visualExtents(text: String, paint: Paint, advanceWidth: Float): Pair<Float, Float> {
    if (text.isEmpty()) return 0f to advanceWidth
    val bounds = Rect()
    paint.getTextBounds(text, 0, text.length, bounds)
    return minOf(0f, bounds.left.toFloat()) to maxOf(advanceWidth, bounds.right.toFloat())
}

private const val MAX_SECONDARY_LINES = 2
private const val ORIGINAL_LINE_GAP_DP = 4f
private const val END_EDGE_SAFETY_DP = 4f
