package com.eza.hyperglow.root.aod

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_PORTRAIT
import com.eza.hyperglow.aod.DEFAULT_CANVAS_PADDING_PERCENT
import com.eza.hyperglow.root.HookLogger
import kotlin.math.max
import kotlin.math.roundToInt

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

internal data class AodCanvasLayoutGroup(
    val start: Int,
    val end: Int,
    val kind: String,
    val keepTogether: Boolean,
    val confidence: Double
)

internal data class RubySpanGeometry(
    val spanX: Float,
    val spanWidth: Float,
    val baseX: Float,
    val baseWidth: Float,
    val extraWidth: Float,
    val rubyCenterX: Float
)

internal data class MetadataLayoutBounds(
    val metadataBaseline: Float,
    val lyricStart: Float,
    val lyricEnd: Float
)

internal enum class SpotlightWordState { SUNG, ACTIVE, UNSUNG }

internal data class AodCanvasContent(
    val trackGeneration: Long,
    val metadata: String,
    val original: String,
    val romanized: String,
    val translated: String,
    val nextLine: String = "",
    val alignedRight: Boolean,
    val lineLevelSync: Boolean,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val positionMs: Long,
    val sampledAtElapsedMs: Long,
    val speed: Float,
    val words: List<AodCanvasWord>,
    val ruby: List<AodCanvasRuby>,
    val layoutGroups: List<AodCanvasLayoutGroup>,
    val weight: String,
    val textSizeMode: String,
    val textSizeCustom: Int,
    val secondaryMode: String,
    val animationMode: String,
    val glowMode: String,
    val motionMode: String,
    val lineSyncFillMode: String,
    val overflowMode: String,
    val transitionMode: String,
    val fontFamily: String,
    val alignmentMode: String,
    val metadataVisible: Boolean,
    val metadataAnchor: String,
    val metadataSizePercent: Int = 100,
    val adaptiveSectioning: Boolean,
    val palette: Map<String, String>,
    val secondaryTextBright: Boolean = true,
    val lyricLineLimit: Int = 3,
    val showNextLine: Boolean = false
)

internal data class AodCanvasLineIdentity(
    val trackGeneration: Long,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val original: String
)

internal fun aodCanvasLineIdentity(content: AodCanvasContent): AodCanvasLineIdentity =
    AodCanvasLineIdentity(
        content.trackGeneration,
        content.lineStartMs,
        content.lineEndMs,
        content.original
    )

internal data class AodResolvedPalette(
    val primaryText: Int,
    val secondaryText: Int,
    val metadataText: Int,
    val nextLineText: Int,
    val sungText: Int,
    val unsungText: Int,
    val glow: Int,
    val accent: Int
)

internal fun resolveAodPalette(tokens: Map<String, String>): AodResolvedPalette =
    AodResolvedPalette(
        primaryText = resolvePaletteColor(tokens["primaryText"], Color.WHITE),
        secondaryText = resolvePaletteColor(tokens["secondaryText"], Color.WHITE),
        metadataText = resolvePaletteColor(tokens["metadataText"], 0xFFB3B3B3.toInt()),
        nextLineText = resolvePaletteColor(tokens["nextLineText"], Color.WHITE),
        sungText = resolvePaletteColor(tokens["sungText"], Color.WHITE),
        unsungText = resolvePaletteColor(tokens["unsungText"], Color.WHITE),
        glow = resolvePaletteColor(tokens["glow"], Color.WHITE),
        accent = resolvePaletteColor(tokens["accent"], Color.WHITE)
    )

private fun resolvePaletteColor(token: String?, fallback: Int): Int = when {
    token == "dimmed" -> opaqueRgb(
        (((fallback ushr 16) and 0xFF) * 0.72f).roundToInt(),
        (((fallback ushr 8) and 0xFF) * 0.72f).roundToInt(),
        ((fallback and 0xFF) * 0.72f).roundToInt()
    )
    // 自定义字体颜色:"#RRGGBB" 形式的 token(解析失败时回退默认色)
    else -> parseOpaqueColorOrNull(token) ?: fallback
}

/** 解析 "#RGB"/"#RRGGBB"/"#AARRGGBB" 为不透明 ARGB;非色值 token 返回 null(纯函数,可单测)。 */
internal fun parseOpaqueColorOrNull(token: String?): Int? {
    if (token == null || token.length !in intArrayOf(4, 7, 9) || token[0] != '#') return null
    val hex = token.substring(1)
    for (c in hex) {
        if (Character.digit(c, 16) < 0) return null
    }
    return when (hex.length) {
        3 -> {
            val r = Character.digit(hex[0], 16)
            val g = Character.digit(hex[1], 16)
            val b = Character.digit(hex[2], 16)
            opaqueRgb(r * 17, g * 17, b * 17)
        }
        6 -> opaqueRgb(
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16)
        )
        else -> opaqueRgb(
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16),
            hex.substring(6, 8).toInt(16)
        )
    }
}

private fun opaqueRgb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or
        (red.coerceIn(0, 255) shl 16) or
        (green.coerceIn(0, 255) shl 8) or
        blue.coerceIn(0, 255)

internal fun splitContinuousFill(progress: Float, lineWidths: List<Float>): List<Float> {
    val totalWidth = lineWidths.sumOf { it.coerceAtLeast(0f).toDouble() }.toFloat()
    if (totalWidth <= 0f) return lineWidths.map { 0f }
    var precedingWidth = 0f
    return lineWidths.map { width ->
        val safeWidth = width.coerceAtLeast(0f)
        val local = continuousFillAt(progress, totalWidth, precedingWidth, safeWidth)
        precedingWidth += safeWidth
        local
    }
}

/**
 * 是否走共享 LyricGlowRenderer 预览管线:
 * 行级同步源、无词级时间源、或开启发光 —— 与预览同源渲染;
 * 仅"逐字时间源 + 关闭发光 + 非行级同步"保留逐字卡拉OK路径。
 */
internal fun usesPreviewGlowPipeline(
    animationMode: String,
    timed: Boolean,
    lineLevelSync: Boolean,
    glowMode: String
): Boolean = animationMode != "Minimal" && (glowMode != "Off" || lineLevelSync || !timed)

/** 整块扫光总进度:行级时间有效时用行区间;纯逐字源回退到全局首词→末词范围。 */
internal fun unifiedBlockProgress(
    positionMs: Long,
    lineStartMs: Long,
    lineEndMs: Long,
    words: List<AodCanvasWord>
): Float {
    if (lineEndMs > lineStartMs) return blockProgressNormalized(positionMs, lineStartMs, lineEndMs)
    var startMs = Long.MAX_VALUE
    var endMs = Long.MIN_VALUE
    words.forEach { word ->
        if (word.startMs >= 0L && word.endMs > word.startMs) {
            if (word.startMs < startMs) startMs = word.startMs
            if (word.endMs > endMs) endMs = word.endMs
        }
    }
    if (startMs != Long.MAX_VALUE && endMs > startMs) {
        return blockProgressNormalized(positionMs, startMs, endMs)
    }
    return blockProgressNormalized(positionMs, lineStartMs, lineEndMs)
}

private fun blockProgressNormalized(positionMs: Long, startMs: Long, endMs: Long): Float =
    if (endMs <= startMs) {
        if (positionMs >= endMs) 1f else 0f
    } else {
        ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }

internal fun continuousFillAt(
    progress: Float,
    totalWidth: Float,
    precedingWidth: Float,
    width: Float
): Float = if (width <= 0f || totalWidth <= 0f) {
    0f
} else {
    ((progress.coerceIn(0f, 1f) * totalWidth - precedingWidth) / width).coerceIn(0f, 1f)
}

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

internal fun baseTextSizeSp(text: String): Float = when {
    text.codePointCount(0, text.length) >= 30 -> 23f
    text.codePointCount(0, text.length) >= 22 -> 24f
    text.codePointCount(0, text.length) >= 14 -> 26f
    else -> 28f
} * LIVE_CARD_SIZE_MULTIPLIER

internal fun textSizeModeMultiplier(mode: String, custom: Int): Float = when (mode) {
    "small" -> 0.9f
    "large" -> 1.2f
    "xlarge" -> 1.5f
    "custom" -> (custom / 100f).coerceIn(0f, 5f)
    else -> 1f
}

internal fun normalizeAodOverflow(mode: String): String =
    if (mode == "Clip") "Clip" else "Wrap"

internal fun rubyReservation(baseTextSizePx: Float, rubyAscent: Float): Float =
    -rubyAscent + baseTextSizePx * 0.12f

internal fun rubySpanGeometry(
    baseX: Float,
    baseWidth: Float,
    rubyWidth: Float
): RubySpanGeometry {
    val spanWidth = max(baseWidth, rubyWidth)
    val spanX = baseX - (spanWidth - baseWidth) / 2f
    return RubySpanGeometry(
        spanX = spanX,
        spanWidth = spanWidth,
        baseX = baseX,
        baseWidth = baseWidth,
        extraWidth = 0f,
        rubyCenterX = baseX + baseWidth / 2f
    )
}

internal fun rubyTopShift(rubyClipTop: Float, paddingTop: Float): Float =
    max(0f, paddingTop - rubyClipTop)

internal fun metadataLayoutBounds(
    anchor: String,
    height: Float,
    paddingTop: Float,
    paddingBottom: Float,
    metadataAscent: Float,
    metadataDescent: Float,
    gap: Float
): MetadataLayoutBounds {
    val metadataBaseline = if (anchor == "bottom") {
        height - paddingBottom - metadataDescent
    } else {
        paddingTop - metadataAscent
    }
    return if (anchor == "bottom") {
        MetadataLayoutBounds(metadataBaseline, paddingTop, metadataBaseline + metadataAscent - gap)
    } else {
        MetadataLayoutBounds(metadataBaseline, metadataBaseline + metadataDescent + gap, height - paddingBottom)
    }
}

internal fun metadataTextSizeMultiplier(percent: Int): Float =
    percent.coerceIn(50, 200) / 100f

internal fun metadataWidgetHeightDp(percent: Int): Float =
    22f + 14f * metadataTextSizeMultiplier(percent)

internal fun originalLineBaseline(
    rowBaseline: Float,
    lineIndex: Int,
    lineHeight: Float,
    precedingRuby: Float,
    rubyHeight: Float,
    lineGap: Float = 0f
): Float = rowBaseline + lineIndex * lineHeight + precedingRuby + rubyHeight +
    lineIndex * lineGap

internal fun originalRowHeight(
    lineHeight: Float,
    lineCount: Int,
    rubyHeight: Float,
    lineGap: Float = 0f
): Float = lineHeight * lineCount + rubyHeight + (lineCount - 1).coerceAtLeast(0) * lineGap

internal fun safeSecondaryLineHeight(ascent: Float, descent: Float, bottom: Float): Float =
    descent - ascent + max(0f, bottom - descent)

internal fun rubyDrawCenterX(lineStartX: Float, rubyCenterX: Float): Float =
    lineStartX + rubyCenterX

internal fun spotlightBrightness(progress: Float): Float {
    val eased = kotlin.math.sin(progress.coerceIn(0f, 1f) * Math.PI.toFloat() / 2f)
    return 0.42f + 0.58f * eased * eased
}

internal fun spotlightAlpha(progress: Float, state: SpotlightWordState): Float = when (state) {
    SpotlightWordState.SUNG -> steadyTextAlpha(1f)
    SpotlightWordState.UNSUNG -> steadyTextAlpha(0.35f)
    SpotlightWordState.ACTIVE -> max(
        steadyTextAlpha(0.35f),
        steadyTextAlpha(1f) * spotlightBrightness(progress)
    )
}

internal fun normalizeAodMotion(mode: String): String = "Fluid"

internal fun normalizeAodAnimation(mode: String): String = when (mode) {
    "Minimal" -> "Minimal"
    else -> "Gradient"
}

internal data class EffectiveCadenceInputs(
    val attached: Boolean,
    val sceneActive: Boolean,
    val ownVisible: Boolean,
    val windowVisible: Boolean,
    val aggregatedVisible: Boolean,
    val effectiveAlpha: Float,
    val timedOrTransitionActive: Boolean,
    val handoffActive: Boolean = false,
    val verifiedDozeHost: Boolean = false
)

internal fun isEffectiveCadenceActive(inputs: EffectiveCadenceInputs): Boolean =
    isEffectiveCadenceActive(
        attached = inputs.attached,
        sceneActive = inputs.sceneActive,
        ownVisible = inputs.ownVisible,
        windowVisible = inputs.windowVisible,
        aggregatedVisible = inputs.aggregatedVisible,
        effectiveAlpha = inputs.effectiveAlpha,
        timedOrTransitionActive = inputs.timedOrTransitionActive,
        handoffActive = inputs.handoffActive,
        verifiedDozeHost = inputs.verifiedDozeHost
    )

private fun isEffectiveCadenceActive(
    attached: Boolean,
    sceneActive: Boolean,
    ownVisible: Boolean,
    windowVisible: Boolean,
    aggregatedVisible: Boolean,
    effectiveAlpha: Float,
    timedOrTransitionActive: Boolean,
    handoffActive: Boolean,
    verifiedDozeHost: Boolean
): Boolean =
    attached &&
        sceneActive &&
        ownVisible &&
        timedOrTransitionActive &&
        (verifiedDozeHost ||
            windowVisible &&
            aggregatedVisible &&
            (handoffActive || effectiveAlpha > 0f))

internal enum class CadenceChange { NONE, START, STOP }

internal class EffectiveCadenceGate {
    private var active = false

    fun update(nextActive: Boolean): CadenceChange = when {
        nextActive && !active -> {
            active = true
            CadenceChange.START
        }
        !nextActive && active -> {
            active = false
            CadenceChange.STOP
        }
        else -> CadenceChange.NONE
    }
}

internal fun frameIntervalForTiming(
    contentVisible: Boolean,
    timingActive: Boolean,
    exitTransitionActive: Boolean = false
): Long = if (contentVisible && (timingActive || exitTransitionActive)) 16L else 0L

internal data class AodLandscapeFrame(val ow: Int, val oh: Int)

/**
 * 逻辑横屏框:旋转 90° 后逻辑宽 = 视口高、逻辑高 = 视口宽,
 * 刚性变换(rotate + 平移)把逻辑框精确映射回竖屏视口。
 */
internal fun aodLandscapeLogicalFrame(viewWidth: Int, viewHeight: Int): AodLandscapeFrame =
    AodLandscapeFrame(ow = viewHeight, oh = viewWidth)

internal data class AodCanvasFrameLayout(
    val ow: Int,
    val oh: Int,
    val padLeft: Int,
    val padRight: Int,
    val padTop: Int,
    val padBottom: Int
) {
    val clipLeft: Int get() = padLeft
    val clipTop: Int get() = padTop
    val clipRight: Int get() = ow - padRight
    val clipBottom: Int get() = oh - padBottom

    /** 裁剪矩形是否在某条边上为空/反长(left>=right 或 top>=bottom),即会整屏裁空。 */
    val clipRectValid: Boolean get() = clipLeft < clipRight && clipTop < clipBottom
}

/**
 * 横屏逻辑框 + 四周 padding。padding 是逻辑帧的百分比(0-20%),因此必须除以 100
 * (此前直接把百分比数值当倍数相乘,导致 2% 被当作 200%,裁剪矩形变成空 → 横屏歌词整屏空白)。
 * X 轴(padLeft/Right)相对逻辑宽 ow(=视口高),Y 轴(padTop/Bottom)相对逻辑高 oh(=视口宽)。
 */
internal fun aodLandscapeFrameLayout(
    viewWidth: Int,
    viewHeight: Int,
    paddingXPercent: Float,
    paddingYPercent: Float
): AodCanvasFrameLayout {
    val frame = aodLandscapeLogicalFrame(viewWidth, viewHeight)
    val padLeft = Math.round(viewHeight * (paddingXPercent / 100f)).toInt()
    val padRight = padLeft
    val padTop = Math.round(viewWidth * (paddingYPercent / 100f)).toInt()
    val padBottom = padTop
    return AodCanvasFrameLayout(frame.ow, frame.oh, padLeft, padRight, padTop, padBottom)
}

/**
 * 横屏刚性变换参数（与 [beginRotationTransform] 的 Canvas 调用一一对应：rotate → translate → scale）。
 *
 * Canvas 的 pre-concat 语义下最终映射为 S·T·R —— 对逻辑点先缩放、再平移、最后旋转。实测与之
 * 相符：issue #57 现场 `Landscape bounds` 的角点数值可用该组合逐点复算（见单测回归）。因此：
 *  - [scalePivotX]/[scalePivotY] 位于**逻辑帧坐标系**，必须取逻辑帧中心 (oh/2, ow/2)；
 *  - [translateX]/[translateY] 也位于逻辑帧坐标系，取 (d, -d)：x/y 分量**不同号**，
 *    使 R∘T 把逻辑帧精确铺满视口（scale=1 时四角逐点重合）；
 *  - 旋转枢轴 [viewPivotX]/[viewPivotY] 位于**视口坐标系**（视口中心）。
 *
 * issue #57：此前 x/y 共用同一平移量 translate(d, d)，并在 scale≠1 时除以 scale 补偿，两者都与
 * 90° 旋转后的实际映射不符 —— 内容整体被推出画布（inside=false，屏幕零输出）。
 */
internal data class LandscapeRotationTransform(
    val degrees: Float,
    val viewPivotX: Float,
    val viewPivotY: Float,
    val translateX: Float,
    val translateY: Float,
    val scale: Float,
    val scalePivotX: Float,
    val scalePivotY: Float
)

/** 映射后的逻辑矩形在视口坐标系中的包围盒，附带覆盖自检（自检日志与单测共用）。 */
internal data class LandscapeMappedBounds(
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float
) {
    /** 完全覆盖视口：内容有余量（缩放后的正常形态）。 */
    fun covers(viewWidth: Int, viewHeight: Int): Boolean =
        minX <= 0f && minY <= 0f && maxX >= viewWidth.toFloat() && maxY >= viewHeight.toFloat()

    /** 与视口至少相交：非零输出的必要条件。 */
    fun hits(viewWidth: Int, viewHeight: Int): Boolean =
        maxX > 0f && minX < viewWidth.toFloat() && maxY > 0f && minY < viewHeight.toFloat()
}

/**
 * 横屏变换参数推导（纯函数）。PORTRAIT / 非法尺寸返回 null（不做变换）。
 * 逻辑帧：ow = 视口高、oh = 视口宽（旋转 90° 后宽高交换）。
 */
internal fun landscapeRotationTransform(
    viewWidth: Int,
    viewHeight: Int,
    rotationStep: AodOrientationStep,
    scale: Float
): LandscapeRotationTransform? {
    if (rotationStep == AodOrientationStep.PORTRAIT) return null
    if (viewWidth <= 0 || viewHeight <= 0) return null
    val degrees = when (rotationStep) {
        AodOrientationStep.LANDSCAPE -> 90f
        AodOrientationStep.REVERSE_LANDSCAPE -> -90f
        AodOrientationStep.PORTRAIT -> return null
    }
    val effectiveScale = if (scale.isFinite() && scale > 0f) scale else 1f
    val d = (viewWidth - viewHeight) / 2f
    return LandscapeRotationTransform(
        degrees = degrees,
        viewPivotX = viewWidth / 2f,
        viewPivotY = viewHeight / 2f,
        // 逻辑帧坐标系内的平移：x 取 d、y 取 -d（两个方向同理，二者只差旋转符号）。
        translateX = d,
        translateY = -d,
        scale = effectiveScale,
        // 逻辑帧中心 = (ow/2, oh/2) = (视口高/2, 视口宽/2)。
        scalePivotX = viewHeight / 2f,
        scalePivotY = viewWidth / 2f
    )
}

/**
 * 逻辑点 → 视口点映射（纯函数），与 Canvas 的 S·T·R 组合严格同构：先绕逻辑帧中心缩放、
 * 再在逻辑帧坐标系内平移、最后绕视口中心旋转。自检日志与单测共用。
 */
internal fun mapLandscapeLogicalPoint(
    transform: LandscapeRotationTransform,
    x: Float,
    y: Float
): Pair<Float, Float> {
    val scaledX = transform.scalePivotX + (x - transform.scalePivotX) * transform.scale
    val scaledY = transform.scalePivotY + (y - transform.scalePivotY) * transform.scale
    val translatedX = scaledX + transform.translateX
    val translatedY = scaledY + transform.translateY
    val radians = Math.toRadians(transform.degrees.toDouble())
    val cos = kotlin.math.cos(radians).toFloat()
    val sin = kotlin.math.sin(radians).toFloat()
    val dx = translatedX - transform.viewPivotX
    val dy = translatedY - transform.viewPivotY
    return Pair(
        transform.viewPivotX + dx * cos - dy * sin,
        transform.viewPivotY + dx * sin + dy * cos
    )
}

/** 逻辑矩形四角映射后的包围盒（自检日志用）。 */
internal fun mapLandscapeLogicalRect(
    transform: LandscapeRotationTransform,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float
): LandscapeMappedBounds {
    val corners = listOf(
        mapLandscapeLogicalPoint(transform, left, top),
        mapLandscapeLogicalPoint(transform, right, top),
        mapLandscapeLogicalPoint(transform, right, bottom),
        mapLandscapeLogicalPoint(transform, left, bottom)
    )
    return LandscapeMappedBounds(
        minX = corners.minOf { it.first },
        minY = corners.minOf { it.second },
        maxX = corners.maxOf { it.first },
        maxY = corners.maxOf { it.second }
    )
}

internal fun isExitTransitionExpired(startedAtMs: Long, nowMs: Long, durationMs: Long): Boolean =
    startedAtMs > 0L && nowMs - startedAtMs >= durationMs

internal fun rubyClipTop(baseBaseline: Float, baseAscent: Float, rubyHeight: Float): Float =
    baseBaseline + baseAscent - rubyHeight

internal fun shouldStartLineTransition(
    lineChanged: Boolean,
    transitionMode: String,
    handoffActive: Boolean,
    resuming: Boolean = false
): Boolean = lineChanged && transitionMode != "None" && !handoffActive && !resuming

internal fun isSongChangeMetadataPlaceholder(
    original: String,
    metadata: String,
    lineStartMs: Long,
    lineEndMs: Long,
    hasTimedWords: Boolean
): Boolean = metadata.isNotBlank() && original == metadata &&
    lineEndMs <= lineStartMs && !hasTimedWords

internal fun shouldMorphSongChangeMetadata(
    previousOriginal: String,
    previousMetadata: String,
    previousLineStartMs: Long,
    previousLineEndMs: Long,
    previousHasTimedWords: Boolean,
    nextMetadata: String,
    nextMetadataVisible: Boolean
): Boolean = nextMetadataVisible && previousMetadata == nextMetadata &&
    isSongChangeMetadataPlaceholder(
        previousOriginal,
        previousMetadata,
        previousLineStartMs,
        previousLineEndMs,
        previousHasTimedWords
    )

internal fun interpolateAodColor(start: Int, end: Int, progress: Float): Int {
    val value = progress.coerceIn(0f, 1f)
    fun channel(from: Int, to: Int): Int = (from + (to - from) * value).roundToInt()
    return Color.argb(
        channel(Color.alpha(start), Color.alpha(end)),
        channel(Color.red(start), Color.red(end)),
        channel(Color.green(start), Color.green(end)),
        channel(Color.blue(start), Color.blue(end))
    )
}

internal data class AodCanvasVerticalBounds(val top: Float, val bottom: Float)

internal fun unionAodCanvasVerticalBounds(
    first: AodCanvasVerticalBounds?,
    second: AodCanvasVerticalBounds?
): AodCanvasVerticalBounds? = when {
    first == null -> second
    second == null -> first
    else -> AodCanvasVerticalBounds(
        top = minOf(first.top, second.top),
        bottom = maxOf(first.bottom, second.bottom)
    )
}

internal fun edgeSafeAlignedStart(
    canvasWidth: Float,
    paddingLeft: Float,
    paddingRight: Float,
    visualLeft: Float,
    visualRight: Float,
    alignment: String,
    safetyInset: Float = 0f
): Float {
    val leftEdge = paddingLeft + safetyInset
    val rightEdge = canvasWidth - paddingRight - safetyInset
    return when (alignment) {
        "end" -> rightEdge - visualRight
        "center" -> (leftEdge + rightEdge - visualLeft - visualRight) / 2f
        else -> leftEdge - visualLeft
    }
}

internal fun sharedBlockClipBottom(progress: Float, top: Float, bottom: Float): Float =
    top + (bottom - top).coerceAtLeast(0f) * progress.coerceIn(0f, 1f)

internal fun shouldUseSharedLineLevelSweep(
    lineLevelSync: Boolean,
    hasOriginalLines: Boolean,
    animationMode: String,
    lineStartMs: Long,
    lineEndMs: Long
): Boolean = lineLevelSync && hasOriginalLines && animationMode != "Minimal" &&
    lineEndMs > lineStartMs

internal fun hasActiveCanvasTiming(
    lineLevelSync: Boolean,
    lineSyncFillMode: String,
    lineStartMs: Long,
    lineEndMs: Long,
    words: List<AodCanvasWord>,
    speed: Float = 1f
): Boolean {
    if (speed <= 0f) return false
    if (lineLevelSync && resolvedLineSyncFillMode(true, lineSyncFillMode) == "None") return false
    if (lineEndMs > lineStartMs) return true
    return words.any { it.endMs > it.startMs }
}

internal fun resolvedLineSyncFillMode(lineLevelSync: Boolean, configuredMode: String): String =
    if (!lineLevelSync) configuredMode
    else when (configuredMode) {
        // 行级同步时固定水平扫光，与预览（PreviewAnimatedLyric 整行从左到右渐进点亮）一致。
        // "None"/"Top to bottom" 等对歌词行几乎看不出水平扫光，统一归一到主行水平扫光。
        "Left to right (whole block)" -> "Left to right (whole block)"
        else -> "Left to right (main only)"
    }

internal fun resolvedLyricLayoutLineLimit(
    configuredLimit: Int,
    originalLength: Int,
    wordCount: Int
): Int = if (configuredLimit in 1..5) {
    configuredLimit
} else {
    maxOf(originalLength, wordCount, 1)
}

internal enum class AodCanvasVerticalAlignment { TOP, CENTER }

private const val LIVE_CARD_SIZE_MULTIPLIER = 0.68f
private const val AOD_DIMMING_BOOST = 1.6f // Sanctioned AOD dimming delta; preserves hardware contrast.

/** 横屏全屏化自适应放缩:期望占据可用高度(0..1)的比例。 */
private const val FULLSCREEN_FILL_RATIO = 0.85f
/** 横屏全屏化放缩比下限(不缩小,避免单行歌词被抬得过小)。 */
private const val FULLSCREEN_MIN_SCALE = 1.0f
/** 横屏全屏化放缩比上限(避免单行歌词放得过大溢出屏幕/超出可读观感)。 */
private const val FULLSCREEN_MAX_SCALE = 1.7f

/**
 * 横屏全屏化的自适应放缩比(纯函数):让内容高度按 [fillRatio] 铺满 [availableHeight],
 * 但钳制在 [minScale]..[maxScale],不越界、单行时也不放得过小。非法输入返回 [minScale]。
 */
internal fun fullscreenAutoScale(
    contentHeight: Float,
    availableHeight: Float,
    fillRatio: Float,
    minScale: Float,
    maxScale: Float
): Float {
    if (contentHeight <= 0f || availableHeight <= 0f || !fillRatio.isFinite() ||
        fillRatio <= 0f
    ) {
        return minScale
    }
    return (availableHeight / contentHeight * fillRatio).coerceIn(minScale, maxScale)
}

/**
 * 横屏全屏化放缩的长轴上限(纯函数):短轴填充驱动的缩放同时作用于长轴,
 * 内容最长行放大后不得超出可用逻辑宽度,否则行两端被裁出画布(issue #51)。
 * 内容为空/非法输入时不设限(返回 [maxScale]);行已占满时钳到 [minScale],不缩小。
 */
internal fun fullscreenWidthCapScale(
    maxLineWidth: Float,
    availableWidth: Float,
    minScale: Float,
    maxScale: Float
): Float {
    if (maxLineWidth <= 0f || availableWidth <= 0f) return maxScale
    return (availableWidth / maxLineWidth).coerceIn(minScale, maxScale)
}

/** 横屏全屏化放缩决议:最终倍数 + 长轴上限 + 溢出轴自检(none/h/w)。 */
internal data class FullscreenScaleDecision(
    val scale: Float,
    val widthCap: Float,
    val overflowAxes: String
)

/**
 * 横屏全屏化自适应放缩(纯函数):短轴按 [fillRatio] 铺满 [availableHeight],再以
 * [fullscreenWidthCapScale] 限制长轴,保证放大后的内容仍落在画布内(issue #51)。
 * [FullscreenScaleDecision.overflowAxes] 供日志自检:放大后 contentHeight 超
 * availableHeight 记 'h',maxLineWidth 超 availableWidth 记 'w',均未越界为 "none"。
 */
internal fun resolveFullscreenLandscapeScale(
    contentHeight: Float,
    availableHeight: Float,
    maxLineWidth: Float,
    availableWidth: Float,
    fillRatio: Float,
    minScale: Float,
    maxScale: Float
): FullscreenScaleDecision {
    val heightDriven = fullscreenAutoScale(
        contentHeight, availableHeight, fillRatio, minScale, maxScale
    )
    val widthCap = fullscreenWidthCapScale(maxLineWidth, availableWidth, minScale, maxScale)
    val scale = minOf(heightDriven, widthCap)
    val overflow = buildString {
        if (contentHeight * scale > availableHeight + 1f) append('h')
        if (maxLineWidth * scale > availableWidth + 1f) append('w')
    }
    return FullscreenScaleDecision(scale, widthCap, overflow.ifEmpty { "none" })
}

/**
 * 横屏全屏的垂直居中偏移:把垂直占 [preOffsetTop, preOffsetTop + blockHeight] 的内容块,
 * 在可用高度 [padTop, padTop + availableHeight] 内整体居中。返回需叠加到每行 baseline 的偏移;
 * 内容块高于可用区间时不缩小、也不再上移(保持原顶部,避免裁切)。
 */
internal fun fullscreenBlockCenterOffset(
    blockTop: Float,
    blockHeight: Float,
    availableHeight: Float,
    padTop: Float
): Float = max(0f, (availableHeight - blockHeight) / 2f) - (blockTop - padTop)

/**
 * 横屏内容块的锚定偏移(纯函数,issue #63):把垂直占 [blockTop, blockTop + blockHeight]
 * 的内容块,在可用区间 [padTop, padTop + availableHeight] 内按 [anchor] 整体摆放
 * (0=顶、0.5=居中、1=底)。anchor=0.5 时与 [fullscreenBlockCenterOffset] 逐点等价;
 * 内容块高于可用区间时不缩小、也不再上移(保持原顶部,避免裁切)。
 */
internal fun landscapeBlockAnchorOffset(
    blockTop: Float,
    blockHeight: Float,
    availableHeight: Float,
    padTop: Float,
    anchor: Float
): Float = max(0f, availableHeight - blockHeight) * anchor.coerceIn(0f, 1f) - (blockTop - padTop)

private fun steadyTextAlpha(factor: Float): Float = if (factor < 0.5f) {
    max(0.35f * AOD_DIMMING_BOOST, 0.55f)
} else {
    minOf(1f, 0.85f * AOD_DIMMING_BOOST)
}

internal fun staticSecondaryTextFactor(bright: Boolean): Float = if (bright) 1f else 0.35f

/** Dimmed preview alpha for the upcoming next lyric line(与预览 0.45 alpha 对齐). */
internal fun staticNextLineTextFactor(): Float = 0.45f

/** Bounded Spicy live-card renderer adapted for Xiaomi AOD. */
internal class AodLyricCanvasView(
    context: Context,
    private val useDozeHandlerCadence: Boolean = false
) : View(context) {
    enum class Alignment { START, CENTER, END }

    private var content = AodCanvasContent(
        trackGeneration = 0L,
        metadata = "",
        original = "",
        romanized = "",
        translated = "",
        alignedRight = false,
        lineLevelSync = false,
        lineStartMs = 0,
        lineEndMs = 0,
        positionMs = 0,
        sampledAtElapsedMs = 0,
        speed = 1f,
        words = emptyList(),
        ruby = emptyList(),
        layoutGroups = emptyList(),
        weight = "Medium",
        textSizeMode = "normal",
        textSizeCustom = 100,
        secondaryMode = "Main only",
        animationMode = "Gradient",
        glowMode = "Off",
        motionMode = "Fluid",
        lineSyncFillMode = "Top to bottom",
        overflowMode = "Wrap",
        transitionMode = "Fade up",
        fontFamily = "noto",
        alignmentMode = "auto",
        metadataVisible = true,
        metadataAnchor = "top",
        metadataSizePercent = 100,
        adaptiveSectioning = true,
        palette = emptyMap(),
        secondaryTextBright = true,
        lyricLineLimit = 3
    )
    private var resolvedPalette = resolveAodPalette(emptyMap())
    private var alignment = Alignment.START
    private var layout = LayoutState(emptyList(), OriginalLayout(emptyList(), 0f, 0f, false))
    private var exitSnapshot: CanvasSnapshot? = null
    private var transitionStartedAt = 0L
    private var handoffActive = false
    private var suppressNextLineTransition = false
    private var timingEffectEnabled = false
    private var lastDrawAtElapsedMs = 0L
    private var cadenceWindowStartedAt = 0L
    private var cadenceCallbackCount = 0
    private var cadenceDrawCount = 0
    private var cadenceMaxDrawGapMs = 0L
    private var cadenceLastDrawAt = 0L
    private var verticalAlignment = AodCanvasVerticalAlignment.TOP

    // ---- AOD 画布随设备旋转(刚性绘制变换)配置 ----
    @Volatile
    private var rotationEnabled = false
    @Volatile
    private var rotationMode = AOD_ROTATION_MODE_PORTRAIT
    @Volatile
    private var rotationStep = AodOrientationStep.PORTRAIT
    private var landscapeTextScale = 1f
    private var landscapeAnchor = 0.5f
    private var landscapeFullscreen = false
    private var debugShowCanvasFrame = false
    private var paddingPortraitXPercent = DEFAULT_CANVAS_PADDING_PERCENT
    private var paddingPortraitYPercent = DEFAULT_CANVAS_PADDING_PERCENT
    private var paddingLandscapeXPercent = DEFAULT_CANVAS_PADDING_PERCENT
    private var paddingLandscapeYPercent = DEFAULT_CANVAS_PADDING_PERCENT

    // ---- 逻辑横屏框:布局/裁剪全部以逻辑宽高与逻辑内边距计算,
    //      非竖屏步进时交换视口宽高,绘制期由 beginRotationTransform 映射回视口 ----
    @Volatile
    private var ow = 0
    @Volatile
    private var oh = 0
    @Volatile
    private var padLeft = 0
    @Volatile
    private var padRight = 0
    @Volatile
    private var padTop = 0
    @Volatile
    private var padBottom = 0

    private fun recomputeLogicalFrame() {
        val landscape = rotationEnabled && rotationStep != AodOrientationStep.PORTRAIT
        if (landscape) {
            val layout = aodLandscapeFrameLayout(
                viewWidth = width,
                viewHeight = height,
                paddingXPercent = paddingLandscapeXPercent,
                paddingYPercent = paddingLandscapeYPercent
            )
            ow = layout.ow
            oh = layout.oh
            padLeft = layout.padLeft
            padRight = layout.padRight
            padTop = layout.padTop
            padBottom = layout.padBottom
            // issue #41 诊断:记录横屏逻辑帧参数,便于真机核对宽高交换/padding 是否符合预期。
            val key = "$width x $height s=$rotationStep f=$landscapeFullscreen " +
                "ow=$ow oh=$oh pL=$padLeft pT=$padTop pR=$padRight pB=$padBottom"
            if (key != lastLandscapeFrameKey) {
                lastLandscapeFrameKey = key
                HookLogger.i("AodLyricCanvasView", "Landscape frame: $key")
            }
        } else {
            ow = width
            oh = height
            padLeft = paddingLeft
            padRight = paddingRight
            padTop = paddingTop
            padBottom = paddingBottom
        }
    }

    private var invalidClipRectWarned = false
    private var lastCenteredLogKey = ""
    private var lastLandscapeFrameKey = ""
    private var lastAutoScaleLogKey = ""
    private var lastRowLayoutLogKey = ""
    private var lastTransformLogKey = ""
    private var lastRotationBoundsKey = ""

    /**
     * 裁剪防呆:padding 异常(left>=right 或 top>=bottom)会让 clipRect 变成空矩形,
     * 直接把该路径整屏裁空且日志完全静默。此处记录一次 warn 并退化为"不裁剪"(全帧),
     * 避免再次出现静默的整屏空白。
     */
    private fun lyricClipBounds(left: Int, top: Int, right: Int, bottom: Int): IntArray {
        if (left < right && top < bottom) return intArrayOf(left, top, right, bottom)
        if (!invalidClipRectWarned) {
            invalidClipRectWarned = true
            HookLogger.w(
                "AodLyricCanvasView",
                "Lyric clip rect invalid (l=$left t=$top r=$right b=$bottom); degrading to full frame"
            )
        }
        return intArrayOf(0, 0, ow, oh)
    }

    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val fontContext = runCatching {
        context.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY)
    }.getOrNull()
    private val metadataPaint = paint(14f, 0xB3FFFFFF.toInt(), Typeface.NORMAL)
    private val originalPaint = paint(27f, Color.WHITE, Typeface.NORMAL)
    private val romanizedPaint = paint(17f, Color.WHITE, Typeface.NORMAL)
    private val translatedPaint = paint(17f, Color.WHITE, Typeface.ITALIC)
    private val nextLinePaint = paint(15f, 0x59FFFFFF.toInt(), Typeface.NORMAL).apply {
        textAlign = Paint.Align.LEFT
    }
    private val rubyPaint = paint(11f, 0xB3FFFFFF.toInt(), Typeface.NORMAL).apply {
        textAlign = Paint.Align.CENTER
    }
    private val debugFramePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAAFF5252.toInt() // 画布边界(逻辑帧 ow×oh)
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val debugClipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xAA4CAF50.toInt() // 内容裁剪区
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private var currentRenderStyle = captureRenderStyle()
    private var contentBoundsChangedListener: (() -> Unit)? = null
    private val typefaceCache = HashMap<TypefaceKey, Typeface>(3)
    private var sceneActive = false
    private var aggregatedVisible = false
    private val cadenceGate = EffectiveCadenceGate()
    private val frame = object : Runnable {
        override fun run() {
            if (!effectiveCadenceActive()) {
                syncCadence()
                return
            }
            recordDozeCadenceCallback()
            if (exitSnapshot != null && isExitTransitionExpired(
                    transitionStartedAt,
                    SystemClock.elapsedRealtime(),
                    ENTER_TRANSITION_MS
                )
            ) {
                transitionStartedAt = 0L
                exitSnapshot = null
                contentBoundsChangedListener?.invoke()
            }
            invalidate()
            val interval = frameInterval()
            if (interval > 0L) scheduleFrame(this, interval)
            else {
                cadenceGate.update(false)
                removeCallbacks(this)
            }
        }
    }

    init {
        setLayerType(LAYER_TYPE_NONE, null)
    }

    fun setContent(incomingContent: AodCanvasContent) {
        val nextContent = incomingContent.copy(
            animationMode = normalizeAodAnimation(incomingContent.animationMode),
            motionMode = normalizeAodMotion(incomingContent.motionMode),
            overflowMode = normalizeAodOverflow(incomingContent.overflowMode)
        )
        val lineChanged = this.content.original.isNotBlank() &&
            aodCanvasLineIdentity(this.content) != aodCanvasLineIdentity(nextContent)
        val resuming = suppressNextLineTransition
        suppressNextLineTransition = false
        if (shouldStartLineTransition(
                lineChanged,
                nextContent.transitionMode,
                handoffActive,
                resuming
            )
        ) {
            exitSnapshot = CanvasSnapshot(content, layout, currentRenderStyle)
            transitionStartedAt = SystemClock.elapsedRealtime()
        } else if (resuming || nextContent.transitionMode == "None") {
            exitSnapshot = null
            transitionStartedAt = 0L
        }
        this.content = nextContent
        timingEffectEnabled = hasActiveCanvasTiming(
            nextContent.lineLevelSync,
            nextContent.lineSyncFillMode,
            nextContent.lineStartMs,
            nextContent.lineEndMs,
            nextContent.words,
            nextContent.speed
        )
        resolvedPalette = resolveAodPalette(nextContent.palette)
        alignment = when (nextContent.alignmentMode) {
            "start" -> Alignment.START
            "center" -> Alignment.CENTER
            "end" -> Alignment.END
            else -> if (nextContent.alignedRight) Alignment.END else Alignment.START
        }
        val sizeScale = textSizeModeMultiplier(nextContent.textSizeMode, nextContent.textSizeCustom)
        val baseSp = baseTextSizeSp(nextContent.original) * sizeScale
        val typeface = resolveTypeface(nextContent.fontFamily, nextContent.weight)
        originalPaint.typeface = typeface
        if (nextContent.fontFamily != "auto") {
            val regularTypeface = resolveTypeface(nextContent.fontFamily, "Regular")
            metadataPaint.typeface = regularTypeface
            romanizedPaint.typeface = regularTypeface
            translatedPaint.typeface = Typeface.create(regularTypeface, Typeface.ITALIC)
            nextLinePaint.typeface = regularTypeface
            rubyPaint.typeface = regularTypeface
        } else {
            metadataPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            romanizedPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            translatedPaint.typeface = Typeface.create("sans-serif", Typeface.ITALIC)
            nextLinePaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            rubyPaint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        originalPaint.textSize = baseSp * scaledDensity
        metadataPaint.textSize = 14f * metadataTextSizeMultiplier(
            nextContent.metadataSizePercent
        ) * scaledDensity
        romanizedPaint.textSize = max(14f, kotlin.math.round(baseSp * 0.48f)) * scaledDensity
        translatedPaint.textSize = max(13f, kotlin.math.round(baseSp * 0.48f) - 1f) * scaledDensity
        rubyPaint.textSize = originalPaint.textSize * 0.46f
        currentRenderStyle = captureRenderStyle()
        rebuildLayout()
        syncCadence()
        invalidate()
    }

    fun stop() {
        cadenceGate.update(false)
        removeCallbacks(frame)
        exitSnapshot = null
        transitionStartedAt = 0L
        suppressNextLineTransition = true
        contentBoundsChangedListener?.invoke()
    }

    fun setContentBoundsChangedListener(listener: (() -> Unit)?) {
        contentBoundsChangedListener = listener
        listener?.invoke()
    }

    fun setVerticalAlignment(alignment: AodCanvasVerticalAlignment) {
        if (verticalAlignment == alignment) return
        verticalAlignment = alignment
        rebuildLayout()
        invalidate()
    }

    /**
     * 配置画布的旋转/横屏参数(AodSurfaceController 在快照变化时调用)。
     * 全部为绘制期参数,不触发布局重测,只在 [onDraw] 内做刚性变换。
     */
    fun updateOrientation(
        rotate: Boolean,
        mode: String,
        landscapeTextScale: Float,
        landscapeAnchor: Float,
        landscapeFullscreen: Boolean,
        debugShowCanvasFrame: Boolean,
        paddingPortraitXPercent: Float,
        paddingPortraitYPercent: Float,
        paddingLandscapeXPercent: Float,
        paddingLandscapeYPercent: Float
    ) {
        val changed = rotationEnabled != rotate ||
            rotationMode != mode ||
            this.landscapeTextScale != landscapeTextScale ||
            this.landscapeAnchor != landscapeAnchor ||
            this.landscapeFullscreen != landscapeFullscreen ||
            this.debugShowCanvasFrame != debugShowCanvasFrame ||
            this.paddingPortraitXPercent != paddingPortraitXPercent ||
            this.paddingPortraitYPercent != paddingPortraitYPercent ||
            this.paddingLandscapeXPercent != paddingLandscapeXPercent ||
            this.paddingLandscapeYPercent != paddingLandscapeYPercent
        rotationEnabled = rotate
        rotationMode = mode
        this.landscapeTextScale = landscapeTextScale
        this.landscapeAnchor = landscapeAnchor
        this.landscapeFullscreen = landscapeFullscreen
        this.debugShowCanvasFrame = debugShowCanvasFrame
        this.paddingPortraitXPercent = paddingPortraitXPercent
        this.paddingPortraitYPercent = paddingPortraitYPercent
        this.paddingLandscapeXPercent = paddingLandscapeXPercent
        this.paddingLandscapeYPercent = paddingLandscapeYPercent
        recomputeLogicalFrame()
        if (!rotate && rotationStep != AodOrientationStep.PORTRAIT) {
            rotationStep = AodOrientationStep.PORTRAIT
        }
        if (changed) {
            rebuildLayout()
            invalidate()
        }
    }

    /** 由 AodOrientationMonitor 驱动的刚性步进;PORTRAIT 时不做变换。 */
    fun setRotationStep(step: AodOrientationStep) {
        if (rotationStep == step) return
        val previous = rotationStep
        rotationStep = step
        recomputeLogicalFrame()
        HookLogger.i(
            "AodLyricCanvasView",
            "rotation step ${previous.name}->${step.name} enabled=$rotationEnabled"
        )
        if (!rotationEnabled) {
            rotationStep = AodOrientationStep.PORTRAIT
        }
        rebuildLayout()
        invalidate()
    }

    /** 当前的横屏文本缩放等参数是否生效的查询,仅供布局层参考。 */
    fun currentRotationEnabled(): Boolean = rotationEnabled

    fun visibleContentVerticalBounds(): AodCanvasVerticalBounds? =
        unionAodCanvasVerticalBounds(
            verticalBounds(layout),
            exitSnapshot?.layout?.let(::verticalBounds)
        )

    fun setHandoffActive(active: Boolean) {
        handoffActive = active
        if (active) {
            exitSnapshot = null
            transitionStartedAt = 0L
        }
        syncCadence()
    }

    fun setSceneActive(active: Boolean) {
        if (sceneActive == active) return
        sceneActive = active
        syncCadence()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        aggregatedVisible = isShown
        syncCadence()
    }

    override fun onDetachedFromWindow() {
        stop()
        aggregatedVisible = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncCadence()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncCadence()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        aggregatedVisible = isVisible
        syncCadence()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        recomputeLogicalFrame()
        rebuildLayout()
    }

    override fun setPadding(left: Int, top: Int, right: Int, bottom: Int) {
        super.setPadding(left, top, right, bottom)
        recomputeLogicalFrame()
        rebuildLayout()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rotationSave = beginRotationTransform(canvas, applyScale = true)
        try {
            drawOrientedContent(canvas)
        } finally {
            if (rotationSave != NO_ROTATION_SAVE) canvas.restoreToCount(rotationSave)
        }
        // issue #44:调试边框独立于全屏缩放绘制。边框与内容共用 scale 时,scale>1 会把本就
        // 铺满 view 的逻辑帧边界推出可视区,恰好最需要看边界时反而不可见;这里用不含 scale
        // 的变换单独描边框,使其始终落在逻辑帧/视口上。
        if (debugShowCanvasFrame) {
            val frameSave = beginRotationTransform(canvas, applyScale = false)
            try {
                drawDebugCanvasFrame(canvas)
            } finally {
                if (frameSave != NO_ROTATION_SAVE) canvas.restoreToCount(frameSave)
            }
        }
    }

    private val NO_ROTATION_SAVE = -1

    /** 是否处于「横屏全屏化」激活状态(启用旋转 且 非竖屏 且 开启横屏全屏开关)。 */
    private fun fullscreenLandscapeActive(): Boolean =
        rotationEnabled &&
        rotationStep != AodOrientationStep.PORTRAIT &&
        landscapeFullscreen

    /** 是否处于横屏步进(启用旋转 且 当前非竖屏),含全屏与非全屏。 */
    private fun landscapeActive(): Boolean =
        rotationEnabled && rotationStep != AodOrientationStep.PORTRAIT

    /**
     * 绘制期生效的横屏放缩比。
     *  - 横屏全屏化:改用 [computeFullscreenAutoScale] 自动铺满,不再使用手动倍数;
     *  - 普通横屏:以用户的横向放缩倍数 [landscapeTextScale] 为上限,但若该倍数会让内容
     *    越出画布框则钳制到「恰好铺满不越界」——见 [computeLandscapeTextScale]。
     */
    private fun effectiveLandscapeScale(): Float {
        val landscape = rotationEnabled && rotationStep != AodOrientationStep.PORTRAIT
        if (!landscape) return 1f
        return if (fullscreenLandscapeActive()) {
            computeFullscreenAutoScale()
        } else {
            computeLandscapeTextScale()
        }
    }

    /**
     * 普通横屏(未开启「横屏全屏」)的 fit-to-frame 放缩比。beginRotationTransform 在
     * scale=1 时已把逻辑帧精确铺满画布;若直接采用用户的 [landscapeTextScale]>1,等于在
     * 已铺满的基座上再放大,内容必然溢出画布框(issue #54:关闭横屏全屏时歌词落在框外)。
     * 因此以用户倍数作上限,垂直接堆叠可用高、水平取最长行可用宽,取两者较小者把一个「恰好
     * 铺满不越界」的系数钳为最终缩放。
     */
    private fun computeLandscapeTextScale(): Float {
        val bounds = verticalBounds(layout) ?: return landscapeTextScale
        val contentHeight = (bounds.bottom - bounds.top).coerceAtLeast(1f)
        val availableHeight = ((oh - padTop - padBottom).toFloat()).coerceAtLeast(1f)
        val maxLineWidth = widestContentLineWidth(layout)
        val availableWidth = ((ow - padLeft - padRight).toFloat()).coerceAtLeast(1f)
        val fitScale = minOf(
            availableHeight / contentHeight,
            availableWidth / maxLineWidth.coerceAtLeast(1f)
        )
        val scale = minOf(landscapeTextScale, fitScale)
            .coerceIn(FULLSCREEN_MIN_SCALE, FULLSCREEN_MAX_SCALE)
        // issue #54:记录用户倍数、fit 系数与最终缩放、内容包围盒 vs 可用框,便于直接判定是否越界。
        val key = "u=$landscapeTextScale fit=$fitScale s=$scale " +
            "c=${contentHeight.roundToInt()} ah=${availableHeight.roundToInt()} " +
            "lw=${maxLineWidth.roundToInt()} aw=${availableWidth.roundToInt()}"
        if (key != lastAutoScaleLogKey) {
            lastAutoScaleLogKey = key
            HookLogger.i("AodLyricCanvasView", "Landscape text-scale: $key")
        }
        return scale
    }

    /**
     * 横屏全屏化的自适应放缩比:按当前内容实际占高([verticalBounds])计算一个尽量铺满
     * 但又不超过可用高度(不越界)的倍数,避免单行歌词被放得过大溢出;再以最长行宽限制
     * 长轴,保证放大后整行仍在画布内(issue #51)。钳制在
     * [FULLSCREEN_MIN_SCALE]..[FULLSCREEN_MAX_SCALE],内容已铺满时不再缩小。
     */
    private fun computeFullscreenAutoScale(): Float {
        val bounds = verticalBounds(layout) ?: return landscapeTextScale
        val contentHeight = (bounds.bottom - bounds.top).coerceAtLeast(1f)
        val availableHeight = ((oh - padTop - padBottom).toFloat()).coerceAtLeast(1f)
        // 长轴输入:最长行宽与可用逻辑宽。行按 available 换行,故 maxLineWidth <= ow,
        // widthCap 不会把内容缩小;它只在短轴填充倍数会让长行两端越界时介入。
        val maxLineWidth = widestContentLineWidth(layout)
        val availableWidth = ((ow - padLeft - padRight).toFloat()).coerceAtLeast(1f)
        val decision = resolveFullscreenLandscapeScale(
            contentHeight = contentHeight,
            availableHeight = availableHeight,
            maxLineWidth = maxLineWidth,
            availableWidth = availableWidth,
            fillRatio = FULLSCREEN_FILL_RATIO,
            minScale = FULLSCREEN_MIN_SCALE,
            maxScale = FULLSCREEN_MAX_SCALE
        )
        // issue #41/#44/#51:记录横屏全屏自适应缩放的实际输入输出,便于真机核对
        // contentHeight 与 maxLineWidth 是否越界(of=none/h/w)、缩放结果是否被长轴上限截断。
        val key = "rot=$rotationStep c=${contentHeight.roundToInt()} " +
            "a=${availableHeight.roundToInt()} s=${decision.scale} " +
            "lw=${maxLineWidth.roundToInt()} aw=${availableWidth.roundToInt()} " +
            "cap=${decision.widthCap} of=${decision.overflowAxes}"
        if (key != lastAutoScaleLogKey) {
            lastAutoScaleLogKey = key
            HookLogger.i("AodLyricCanvasView", "Landscape auto-scale: $key")
        }
        return decision.scale
    }

    /** 当前布局中最长行的逻辑宽度(原文/副行/元数据取最大),用于全屏放缩的长轴上限。 */
    private fun widestContentLineWidth(state: LayoutState): Float {
        var maxWidth = 0f
        state.original.lines.forEach { line -> maxWidth = maxOf(maxWidth, line.width) }
        state.rows.forEach { positioned ->
            positioned.row.lines.forEach { line -> maxWidth = maxOf(maxWidth, line.width) }
        }
        return maxWidth
    }

    /**
     * 绘制期生效的垂直对齐:横屏时行块改由 [landscapeAnchor] 锚定(见 positionRows),
     * 竖直对齐只在竖屏路径生效,这里直接返回外部设定值。
     * (此前横屏全屏在此强制 CENTER;issue #63 起横屏两方向统一走锚定,默认 0.5 等价居中。)
     */
    private fun effectiveVerticalAlignment(): AodCanvasVerticalAlignment = verticalAlignment

    /**
     * 刚性绘制变换:绕视图中心旋转画布坐标系,把竖屏逻辑框整体转成横屏显示。
     * 横屏时叠加 [landscapeTextScale] 对内容做整体缩放。PORTRAIT / 未启用时直接直通。
     * [applyScale] 为 false 时跳过缩放,只做旋转+平移——用于独立绘制调试边框,
     * 使其不受全屏缩放影响(issue #44)。
     */
    private fun beginRotationTransform(canvas: Canvas, applyScale: Boolean): Int {
        if (!rotationEnabled || rotationStep == AodOrientationStep.PORTRAIT) {
            return NO_ROTATION_SAVE
        }
        val scale = if (applyScale) effectiveLandscapeScale() else 1f
        // 变换参数集中在 [landscapeRotationTransform]:平移取 (d,-d) 且缩放枢轴取逻辑帧中心,
        // 与 90° 旋转后的实际映射一致(issue #57);两个旋转方向共用同一组参数。
        val transform = landscapeRotationTransform(width, height, rotationStep, scale)
            ?: return NO_ROTATION_SAVE
        val save = canvas.save()
        canvas.rotate(transform.degrees, transform.viewPivotX, transform.viewPivotY)
        canvas.translate(transform.translateX, transform.translateY)
        if (applyScale) {
            // issue #41/#44/#57:记录旋转/平移/缩放参数,便于真机核对方向与量级是否正确。
            val key = "rot=$rotationStep w=$width h=$height tx=${transform.translateX} " +
                "ty=${transform.translateY} scale=${transform.scale} " +
                "sp=(${transform.scalePivotX},${transform.scalePivotY}) " +
                "fs=${fullscreenLandscapeActive()}"
            if (key != lastTransformLogKey) {
                lastTransformLogKey = key
                HookLogger.i("AodLyricCanvasView", "Landscape transform: $key")
            }
            if (kotlin.math.abs(transform.scale - 1f) > 0.001f) {
                canvas.scale(
                    transform.scale,
                    transform.scale,
                    transform.scalePivotX,
                    transform.scalePivotY
                )
            }
            // issue #55/#57 自检:把逻辑帧与内容裁剪区经同一变换映射到视口,输出包围盒与
            // 覆盖判定;任何「整屏零输出」都会以 W 级日志直接暴露,无需靠截图反推。
            logRotationTransformBounds(transform)
        }
        return save
    }
    /**
     * issue #55 自检日志:用与 [beginRotationTransform] 完全相同的 pre-concat 顺序重建矩阵
     * (rotate → translate(t) → scale),把内容裁剪区四个角映射到设备坐标,输出其包围盒及
     * 是否落在画布 (0,0)-(width,height) 内。当旋转+平移+缩放把内容推出可视区时,这里会
     * 直接报越界,无需再靠截图或反推日志判断。
     */
    /**
     * issue #55/#57 自检日志:用与 [beginRotationTransform] 完全相同的变换参数把「逻辑帧」与
     * 「内容裁剪区」映射到视口坐标,输出包围盒,并判定覆盖(cover=铺满且有余量)与相交
     * (hit=非零输出)。变换把内容推出画布时 hit=false —— 以 W 级留痕,第一时间暴露「零输出」。
     */
    private fun logRotationTransformBounds(transform: LandscapeRotationTransform) {
        val clip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        val clipBounds = mapLandscapeLogicalRect(
            transform,
            clip[0].toFloat(),
            clip[1].toFloat(),
            clip[2].toFloat(),
            clip[3].toFloat()
        )
        val frameBounds = mapLandscapeLogicalRect(transform, 0f, 0f, ow.toFloat(), oh.toFloat())
        val hit = clipBounds.hits(width, height)
        val cover = frameBounds.covers(width, height)
        val key = "clip=(${clip[0]},${clip[1]})-(${clip[2]},${clip[3]}) " +
            "bounds=(${clipBounds.minX.roundToInt()},${clipBounds.minY.roundToInt()})-" +
            "(${clipBounds.maxX.roundToInt()},${clipBounds.maxY.roundToInt()}) " +
            "view=${width}x$height cover=$cover hit=$hit"
        if (key != lastRotationBoundsKey) {
            lastRotationBoundsKey = key
            if (hit) {
                HookLogger.i("AodLyricCanvasView", "Landscape bounds: $key")
            } else {
                HookLogger.w(
                    "AodLyricCanvasView",
                    "Landscape bounds: $key (content outside canvas; check translate/scale)"
                )
            }
        }
    }

    /**
     * 调试开关:在画布上描出边界。红色 = 逻辑帧边界(ow×oh),绿色 = 内容裁剪区
     * (padLeft..clipRight, padTop..clipBottom)。用不含 scale 的旋转+平移变换绘制,
     * 不受全屏缩放影响(issue #44)——缩放时内容放大,边框仍恒定落在逻辑帧/视口上。
     */
    private fun drawDebugCanvasFrame(canvas: Canvas) {
        if (!debugShowCanvasFrame) return
        canvas.drawRect(0f, 0f, ow.toFloat(), oh.toFloat(), debugFramePaint)
        canvas.drawRect(
            padLeft.toFloat(),
            padTop.toFloat(),
            (ow - padRight).toFloat(),
            (oh - padBottom).toFloat(),
            debugClipPaint
        )
    }

    private fun drawOrientedContent(canvas: Canvas) {
        super.onDraw(canvas)
        lastDrawAtElapsedMs = SystemClock.elapsedRealtime()
        recordDozeDraw()
        syncCadence()
        val snapshot = exitSnapshot
        if (snapshot == null) {
            drawMetadata(canvas, layout)
            drawRows(canvas, layout, content, 1f, 0f)
            return
        }
        val elapsed = (SystemClock.elapsedRealtime() - transitionStartedAt).coerceAtLeast(0L)
        val exitProgress = (elapsed / EXIT_TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        val enterProgress = (elapsed / ENTER_TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        val metadataMorph = shouldMorphSongChangeMetadata(
            previousOriginal = snapshot.content.original,
            previousMetadata = snapshot.content.metadata,
            previousLineStartMs = snapshot.content.lineStartMs,
            previousLineEndMs = snapshot.content.lineEndMs,
            previousHasTimedWords = snapshot.content.words.any { it.endMs > it.startMs },
            nextMetadata = content.metadata,
            nextMetadataVisible = content.metadataVisible
        ) && canDrawMetadataMorph(snapshot)
        if (metadataMorph) {
            drawMetadataMorph(canvas, snapshot, enterProgress)
        } else if (snapshot.content.metadata != content.metadata ||
            snapshot.content.metadataVisible != content.metadataVisible ||
            snapshot.content.metadataAnchor != content.metadataAnchor
        ) {
            drawMetadata(canvas, snapshot.layout, 1f - exitProgress, snapshot.renderStyle)
            drawMetadata(canvas, layout, enterProgress)
        } else {
            drawMetadata(canvas, layout)
        }
        drawRows(
            canvas,
            snapshot.layout,
            snapshot.content,
            1f - exitProgress,
            if (content.transitionMode == "Fade up") -14f * density * exitProgress else 0f,
            snapshot.renderStyle,
            skipOriginal = metadataMorph
        )
        drawRows(canvas, layout, content, enterProgress, if (content.transitionMode == "Fade up") 14f * density * (1f - enterProgress) else 0f)
        if (enterProgress >= 1f) {
            transitionStartedAt = 0L
            exitSnapshot = null
            contentBoundsChangedListener?.invoke()
        }
    }

    private fun drawRows(
        canvas: Canvas,
        drawLayout: LayoutState,
        drawContent: AodCanvasContent,
        alpha: Float,
        translateY: Float,
        renderStyle: RenderStyleSnapshot? = null,
        skipOriginal: Boolean = false
    ) {
        if (alpha <= 0f || drawLayout.rows.none {
                it.row.kind != RowKind.METADATA && (!skipOriginal || it.row.kind != RowKind.ORIGINAL)
            }
        ) return
        val savedContent = content
        val savedLayout = layout
        if (renderStyle != null) applyRenderStyle(renderStyle)
        content = drawContent
        layout = drawLayout
        val layer = if (alpha < 1f || translateY != 0f) {
            val save = canvas.saveLayerAlpha(0f, 0f, ow.toFloat(), oh.toFloat(), (255f * alpha).toInt())
            canvas.translate(0f, translateY)
            save
        } else canvas.save()
        // 所有歌词绘制路径(原文/注音/翻译/逐字扫光/发光块)共享这一处逻辑裁剪:
        // 即使整词不可分或动画越界超出其测量宽度,也强制限制在周围 padding 框内,
        // 取代原先逐 drawText 的 clip,成为唯一统一边界。
        val frameClip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        canvas.clipRect(frameClip[0], frameClip[1], frameClip[2], frameClip[3])
        val sharedLineLevelSweep = shouldUseSharedLineLevelSweep(
            drawContent.lineLevelSync,
            drawLayout.original.lines.isNotEmpty(),
            drawContent.animationMode,
            drawContent.lineStartMs,
            drawContent.lineEndMs
        )
        if (sharedLineLevelSweep) {
            drawSharedLineLevelRows(canvas, drawLayout.rows)
        } else {
            var rowIndex = 0
            while (rowIndex < drawLayout.rows.size) {
                val row = drawLayout.rows[rowIndex]
                when (row.row.kind) {
                    RowKind.METADATA -> Unit
                    RowKind.ORIGINAL -> if (!skipOriginal) drawOriginal(canvas, row.baseline)
                    else -> drawText(canvas, row.row, row.baseline)
                }
                rowIndex++
            }
        }
        canvas.restoreToCount(layer)
        content = savedContent
        layout = savedLayout
        if (renderStyle != null) applyRenderStyle(currentRenderStyle)
    }

    private fun drawSharedLineLevelRows(canvas: Canvas, rows: List<PositionedRow>) {
        val original = rows.firstOrNull { it.row.kind == RowKind.ORIGINAL } ?: return
        // 副行(音标/翻译/下一行)静态绘制,与预览的静态 Text 行一致,不参与扫光。
        drawSecondaryRowsStatic(canvas, rows, bright = content.secondaryTextBright)
        drawOriginalRubyRows(canvas, original.baseline, bright = true)
        // 主行发光统一委托共享渲染核心 LyricGlowRenderer —— 与预览(PreviewAnimatedLyric)
        // 同一份配方:dim 底、光晕、easeInOut 扫光带,杜绝行级同步路径另走一套旧实现。
        drawOriginalGlowBlock(canvas, original.baseline, layout.original, lineProgress())
    }

    private fun drawSecondaryRowsStatic(
        canvas: Canvas,
        rows: List<PositionedRow>,
        bright: Boolean,
        keepShader: Boolean = false
    ) {
        var rowIndex = 0
        while (rowIndex < rows.size) {
            val positioned = rows[rowIndex]
            if (positioned.row.kind == RowKind.ORIGINAL ||
                positioned.row.kind == RowKind.METADATA
            ) {
                rowIndex++
                continue
            }
            if (positioned.row.kind == RowKind.NEXT_LINE) {
                // 下一行歌词颜色走独立的 nextLineText token(与预览/非行级同步路径一致),
                // 不能混用 secondaryText,否则"下一行歌词颜色"设置对该路径完全无效。
                setTextAlpha(
                    positioned.row.paint,
                    staticNextLineTextFactor(),
                    1f,
                    resolvedPalette.nextLineText
                )
            } else {
                setTextAlpha(
                    positioned.row.paint,
                    staticSecondaryTextFactor(bright),
                    1f,
                    resolvedPalette.secondaryText
                )
            }
            if (!keepShader) positioned.row.paint.shader = null
            positioned.row.paint.clearShadowLayer()
            var lineIndex = 0
            while (lineIndex < positioned.row.lines.size) {
                val line = positioned.row.lines[lineIndex]
                canvas.drawText(
                    line.text,
                    line.startX,
                    positioned.baseline + lineIndex * positioned.row.lineHeight,
                    positioned.row.paint
                )
                lineIndex++
            }
            rowIndex++
        }
    }

    private fun drawOriginalRubyRows(canvas: Canvas, baseline: Float, bright: Boolean) {
        var precedingRuby = 0f
        layout.original.lines.forEachIndexed { lineIndex, line ->
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                layout.original.lineHeight,
                precedingRuby,
                line.rubyHeight,
                layout.original.lineGap
            )
            if (line.ruby.isNotEmpty()) drawRuby(canvas, line, lineBaseline, bright)
            precedingRuby += line.rubyHeight
        }
    }

    private fun captureRenderStyle(): RenderStyleSnapshot = RenderStyleSnapshot(
        metadataPaint = Paint(metadataPaint),
        originalPaint = Paint(originalPaint),
        romanizedPaint = Paint(romanizedPaint),
        translatedPaint = Paint(translatedPaint),
        rubyPaint = Paint(rubyPaint),
        palette = resolvedPalette,
        alignment = alignment
    )

    private fun applyRenderStyle(style: RenderStyleSnapshot) {
        metadataPaint.set(style.metadataPaint)
        originalPaint.set(style.originalPaint)
        romanizedPaint.set(style.romanizedPaint)
        translatedPaint.set(style.translatedPaint)
        rubyPaint.set(style.rubyPaint)
        resolvedPalette = style.palette
        alignment = style.alignment
    }

    private fun drawMetadata(
        canvas: Canvas,
        drawLayout: LayoutState,
        alpha: Float = 1f,
        renderStyle: RenderStyleSnapshot? = null
    ) {
        if (alpha <= 0f) return
        val metadata = drawLayout.rows.firstOrNull { it.row.kind == RowKind.METADATA } ?: return
        if (renderStyle != null) applyRenderStyle(renderStyle)
        canvas.save()
        val metadataClip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        canvas.clipRect(metadataClip[0], metadataClip[1], metadataClip[2], metadataClip[3])
        metadata.row.paint.color = resolvedPalette.metadataText
        metadata.row.paint.alpha = (255f * alpha.coerceIn(0f, 1f)).roundToInt()
        metadata.row.lines.forEachIndexed { index, line ->
            // 底部锚点时行向上排（末行贴近屏幕底），顶部锚点向下排。
            val lineBaseline = if (content.metadataAnchor == "bottom") {
                metadata.baseline - (metadata.row.lines.size - 1 - index) * metadata.row.lineHeight
            } else {
                metadata.baseline + index * metadata.row.lineHeight
            }
            canvas.drawText(
                line.text,
                line.startX,
                lineBaseline,
                metadata.row.paint
            )
        }
        canvas.restore()
        if (renderStyle != null) applyRenderStyle(currentRenderStyle)
    }

    private fun canDrawMetadataMorph(snapshot: CanvasSnapshot): Boolean =
        snapshot.layout.original.lines.size == 1 &&
            snapshot.layout.rows.count { it.row.kind == RowKind.ORIGINAL } == 1 &&
            layout.rows.firstOrNull { it.row.kind == RowKind.METADATA }
                ?.row?.lines?.size == 1

    private fun drawMetadataMorph(
        canvas: Canvas,
        snapshot: CanvasSnapshot,
        progress: Float
    ) {
        val sourceRow = snapshot.layout.rows.firstOrNull {
            it.row.kind == RowKind.ORIGINAL
        } ?: return
        val sourceLine = snapshot.layout.original.lines.singleOrNull() ?: return
        val destinationRow = layout.rows.firstOrNull {
            it.row.kind == RowKind.METADATA
        } ?: return
        val destinationLine = destinationRow.row.lines.singleOrNull() ?: return
        val value = progress.coerceIn(0f, 1f)
        val paint = Paint(
            if (value < 0.5f) snapshot.renderStyle.originalPaint
            else currentRenderStyle.metadataPaint
        ).apply {
            textSize = snapshot.renderStyle.originalPaint.textSize +
                (currentRenderStyle.metadataPaint.textSize -
                    snapshot.renderStyle.originalPaint.textSize) * value
            color = interpolateAodColor(
                snapshot.renderStyle.palette.primaryText,
                currentRenderStyle.palette.metadataText,
                value
            )
            alpha = 255
            shader = null
            clearShadowLayer()
        }
        val x = sourceLine.startX + (destinationLine.startX - sourceLine.startX) * value
        val y = sourceRow.baseline + (destinationRow.baseline - sourceRow.baseline) * value
        canvas.save()
        val morphClip = lyricClipBounds(padLeft, padTop, ow - padRight, oh - padBottom)
        canvas.clipRect(morphClip[0], morphClip[1], morphClip[2], morphClip[3])
        canvas.drawText(content.metadata, x, y, paint)
        canvas.restore()
    }

    private fun rebuildLayout() {
        val originalLayout = buildOriginalLayout()
        val rows = ArrayList<Row>(4)
        val hasTimedWords = content.words.any { it.endMs > it.startMs }
        val metadataPlaceholder = isSongChangeMetadataPlaceholder(
            content.original,
            content.metadata,
            content.lineStartMs,
            content.lineEndMs,
            hasTimedWords
        )
        if (content.metadataVisible && content.metadata.isNotBlank() && !metadataPlaceholder) {
            rows += rowWithLines(
                RowKind.METADATA,
                content.metadata,
                metadataPaint,
                0f,
                wrapMetadataText(content.metadata, metadataPaint)
            )
        }
        if (content.original.isNotBlank()) {
            val metrics = originalPaint.fontMetrics
            val lineHeight = metrics.descent - metrics.ascent + 2f * density
            rows += Row(
                RowKind.ORIGINAL,
                content.original,
                originalPaint,
                originalRowHeight(
                    lineHeight,
                    originalLayout.lineCount,
                    originalLayout.rubyHeight,
                    originalLayout.lineGap
                ),
                8f * density,
                emptyList(),
                lineHeight
            )
        }
        val showReading = content.secondaryMode == "Transliteration" || content.secondaryMode == "Both"
        val showTranslation = content.secondaryMode == "Translation" || content.secondaryMode == "Both"
        if (showReading && content.romanized.isNotBlank()) {
            val lines = transliterationLines(originalLayout)
                ?: wrapSecondaryText(content.romanized, romanizedPaint, originalLayout.lineCount)
            rows += rowWithLines(RowKind.ROMANIZED, content.romanized, romanizedPaint, 2f * density, lines)
        }
        if (showTranslation && content.translated.isNotBlank()) {
            rows += rowWithLines(
                RowKind.TRANSLATED,
                content.translated,
                translatedPaint,
                2f * density,
                wrapSecondaryText(content.translated, translatedPaint, originalLayout.lineCount)
            )
        }
        if (content.showNextLine && content.nextLine.isNotBlank()) {
            rows += rowWithLines(
                RowKind.NEXT_LINE,
                content.nextLine,
                nextLinePaint,
                4f * density,
                wrapSecondaryText(content.nextLine, nextLinePaint, 1)
            )
        }
        layout = LayoutState(positionRows(rows, originalLayout), originalLayout)
        contentBoundsChangedListener?.invoke()
    }

    private fun verticalBounds(state: LayoutState): AodCanvasVerticalBounds? {
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
            top.coerceIn(0f, oh.toFloat()),
            bottom.coerceIn(0f, oh.toFloat())
        )
    }

    private fun rowWithLines(
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

    private fun positionRows(rows: List<Row>, originalLayout: OriginalLayout): List<PositionedRow> {
        val positioned = ArrayList<PositionedRow>(rows.size)
        val metadata = rows.firstOrNull { it.kind == RowKind.METADATA }
        if (metadata != null) {
            val anchor = when (content.metadataAnchor) {
                "bottom" -> "bottom"
                else -> "top"
            }
            val metadataBounds = metadataLayoutBounds(
                anchor,
                oh.toFloat(),
                padTop.toFloat(),
                padBottom.toFloat(),
                metadata.paint.fontMetrics.ascent,
                metadata.paint.fontMetrics.descent,
                10f * density
            )
            val metadataBaseline = metadataBounds.metadataBaseline
            positioned += PositionedRow(metadata, metadataBaseline, false)
            val lyricRows = rows.filterNot { it.kind == RowKind.METADATA }
            val gap = 10f * density
            // 元数据被换行成多行时，其实际占高超过单行基线；歌词起点需按多出的高度避让。
            val metadataExtraHeight = (metadata.lines.size - 1).coerceAtLeast(0) *
                metadata.lineHeight
            if (anchor == "bottom") {
                var bottom = metadataBounds.lyricEnd - metadataExtraHeight
                lyricRows.asReversed().forEach { row ->
                    bottom -= row.height
                    positioned += PositionedRow(row, bottom - row.paint.fontMetrics.ascent, true)
                    bottom -= row.gapBefore
                }
                positioned.sortBy { it.baseline }
            } else {
                var top = metadataBounds.lyricStart + metadataExtraHeight
                lyricRows.forEach { row ->
                    top += row.gapBefore
                    positioned += PositionedRow(row, top - row.paint.fontMetrics.ascent, true)
                    top += row.height
                }
            }
        } else {
            val total = rows.sumOf { (it.height + it.gapBefore).toDouble() }.toFloat()
            val topPadding = padTop.toFloat()
            val bottomPadding = oh - padBottom
            val available = (bottomPadding - topPadding).coerceAtLeast(0f)
            // issue #63:横屏时行堆叠轴经 90° 旋转映射为画布的视觉横轴,沿用竖屏的 TOP 会让
            // 内容整块贴向画布一侧(现场实测偏约 185px)。横屏统一按 landscapeAnchor 锚定
            // (默认 0.5=居中);竖屏维持原 TOP/CENTER 语义。
            var top = if (landscapeActive()) {
                topPadding + max(0f, available - total) * landscapeAnchor.coerceIn(0f, 1f)
            } else if (effectiveVerticalAlignment() == AodCanvasVerticalAlignment.TOP) {
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
        // issue #41:横屏全屏时元数据分支走 anchor 排版,会把整块锚到 padTop/padBottom,
        // 完全不做居中(居中只在无元数据分支生效)。这里对整块(元数据+歌词)统一锚定。
        // issue #63 起扩展到全部横屏(含非全屏),锚点取 landscapeAnchor(默认 0.5=居中)。
        if (metadata != null) anchorLandscapeContentBlock(positioned)
        // issue #41/#44/#63:记录横屏整块(元数据+歌词)的最终占位范围与锚定参数,便于真机核对
        // 内容是否被正确锚定/居中、是否偏靠一侧/越界被裁。覆盖 oh/pad/total→block 全链路。
        if (landscapeActive()) {
            var minTop = Float.POSITIVE_INFINITY
            var maxBottom = Float.NEGATIVE_INFINITY
            positioned.forEach { p ->
                val t = p.baseline + p.row.paint.fontMetrics.ascent
                val b = p.baseline + p.row.height
                if (t < minTop) minTop = t
                if (b > maxBottom) maxBottom = b
            }
            val key = "rot=$rotationStep ow=$ow oh=$oh padT=$padTop padB=$padBottom" +
                if (metadata != null) " md=1" else " md=0" +
                " block=${minTop.roundToInt()}..${maxBottom.roundToInt()} " +
                "anchor=$landscapeAnchor fs=${fullscreenLandscapeActive()}"
            if (key != lastRowLayoutLogKey) {
                lastRowLayoutLogKey = key
                HookLogger.i("AodLyricCanvasView", "Landscape row layout: $key")
            }
        }
        val original = positioned.firstOrNull { it.row.kind == RowKind.ORIGINAL }
        val firstLine = originalLayout.lines.firstOrNull()
        if (original == null || firstLine == null || firstLine.rubyHeight <= 0f) return positioned
        val firstBaseBaseline = original.baseline + firstLine.rubyHeight
        val top = rubyClipTop(firstBaseBaseline, originalPaint.fontMetrics.ascent, firstLine.rubyHeight)
        val shift = rubyTopShift(top, paddingTop.toFloat())
        return if (shift == 0f) positioned else positioned.map {
            if (it.row.kind == RowKind.METADATA) it else it.copy(baseline = it.baseline + shift)
        }
    }

    /**
     * issue #41/#63:横屏(全屏与非全屏)时把整块(元数据+歌词)按 [landscapeAnchor] 锚定。
     * 元数据分支此前只用 metadataAnchor 锚定到 padTop/padBottom,完全不做居中——而无元数据
     * 分支会居中/锚定,导致有元数据时不居中、挤向画布一侧。anchor=0.5 与旧全屏居中行为
     * 逐点等价;仅在横屏激活时生效(需自适应 scale 也读完预缩放布局),竖屏不受影响。
     */
    private fun anchorLandscapeContentBlock(positioned: MutableList<PositionedRow>) {
        if (!landscapeActive() || positioned.isEmpty()) return
        var minTop = Float.POSITIVE_INFINITY
        var maxBottom = Float.NEGATIVE_INFINITY
        positioned.forEach { p ->
            val top = p.baseline + p.row.paint.fontMetrics.ascent
            val bottom = p.baseline + p.row.height
            if (top < minTop) minTop = top
            if (bottom > maxBottom) maxBottom = bottom
        }
        if (!minTop.isFinite() || !maxBottom.isFinite() || maxBottom <= minTop) return
        val available = (oh - padTop - padBottom).coerceAtLeast(0)
        val blockHeight = maxBottom - minTop
        val offset = landscapeBlockAnchorOffset(
            blockTop = minTop,
            blockHeight = blockHeight,
            availableHeight = available.toFloat(),
            padTop = padTop.toFloat(),
            anchor = landscapeAnchor
        )
        if (offset == 0f) return
        val shifted = positioned.map { it.copy(baseline = it.baseline + offset) }
        positioned.clear()
        positioned.addAll(shifted)
        if (lastCenteredLogKey != offset.toString()) {
            lastCenteredLogKey = offset.toString()
            HookLogger.i(
                "AodLyricCanvasView",
                "Landscape content block anchored minTop=$minTop " +
                    "blockH=$blockHeight avail=$available anchor=$landscapeAnchor offset=$offset"
            )
        }
    }

    private fun drawOriginal(canvas: Canvas, baseline: Float) {
        val originalLayout = layout.original
        val lines = originalLayout.lines
        // Minimal 模式：静态全亮，无扫光/发光（timed / untimed 通用）。
        if (content.animationMode == "Minimal") {
            var precedingRuby = 0f
            var lineIndex = 0
            while (lineIndex < lines.size) {
                val line = lines[lineIndex]
                val lineBaseline = originalLineBaseline(
                    baseline,
                    lineIndex,
                    originalLayout.lineHeight,
                    precedingRuby,
                    line.rubyHeight,
                    originalLayout.lineGap
                )
                val lineClipSave = clipOriginalLine(canvas, lineBaseline, line.rubyHeight)
                if (line.ruby.isNotEmpty()) {
                    drawRuby(canvas, line, lineBaseline)
                }
                originalPaint.shader = null
                originalPaint.setShadowLayer(0f, 0f, 0f, 0)
                setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
                drawOriginalText(canvas, line, lineBaseline)
                if (lineClipSave != -1) canvas.restoreToCount(lineClipSave)
                precedingRuby += line.rubyHeight
                lineIndex++
            }
            return
        }
        // 行级歌词(无逐字时间戳,LRC):同样统一走共享渲染管线,与预览同源。
        if (!originalLayout.timed) {
            drawOriginalGlowBlock(canvas, baseline, originalLayout, lineProgress())
            return
        }
        // 逐字卡拉OK路径：仅"逐字时间源 + 关闭发光 + 非行级同步"保留，
        // 其余全部走共享 LyricGlowRenderer 统一管线（与预览同源，杜绝效果漂移）。
        if (!usesPreviewGlowPipeline(
                content.animationMode,
                originalLayout.timed,
                content.lineLevelSync,
                content.glowMode
            )
        ) {
            drawWordKaraoke(canvas, baseline, originalLayout)
            return
        }
        // 统一预览管线：dim 底 + 光晕(发光开启时) + 扫光带。
        // 整块进度：行级时间优先，纯逐字源回退全局词范围（unifiedBlockProgress）。
        drawOriginalGlowBlock(
            canvas,
            baseline,
            originalLayout,
            unifiedBlockProgress(
                projectedPosition(),
                content.lineStartMs,
                content.lineEndMs,
                content.words
            )
        )
    }

    /**
     * 共享发光渲染管线入口(与预览 PreviewAnimatedLyric 同源):
     * 构建整块行集合并委托 LyricGlowRenderer —— dim 底、光晕、easeInOut 扫光带
     * 的配方只此一份,AOD/锁屏/预览三端由构造保证一致。
     */
    private fun drawOriginalGlowBlock(
        canvas: Canvas,
        baseline: Float,
        originalLayout: OriginalLayout,
        progress: Float
    ) {
        val lines = originalLayout.lines
        val glowRows = ArrayList<LyricGlowRow>(lines.size)
        var precedingRuby = 0f
        var lineIndex = 0
        // 外层统一裁剪（非 Wrap 溢出模式），替代原先逐行 clip，与预览整块绘制一致。
        val outerClip = clipOriginalBlock(canvas, baseline, originalLayout)
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                originalLayout.lineHeight,
                precedingRuby,
                line.rubyHeight,
                originalLayout.lineGap
            )
            if (line.ruby.isNotEmpty()) {
                drawRuby(canvas, line, lineBaseline)
            }
            val capturedBaseline = lineBaseline
            glowRows += LyricGlowRow(
                left = line.startX,
                width = line.width,
                baseline = capturedBaseline,
                drawText = { c, p -> drawLineTextForGlow(c, line, capturedBaseline, p) }
            )
            precedingRuby += line.rubyHeight
            lineIndex++
        }
        LyricGlowRenderer.draw(
            canvas = canvas,
            paint = originalPaint,
            rows = glowRows,
            progress = progress,
            sungColor = resolvedPalette.sungText,
            glowColor = resolvedPalette.glow,
            glowEnabled = content.glowMode != "Off"
        )
        if (outerClip != -1) canvas.restoreToCount(outerClip)
    }

    /** 统一管线的整块裁剪：非 Wrap 溢出模式时裁剪到内边距区域（含首行 ruby 顶部余量）。 */
    private fun clipOriginalBlock(canvas: Canvas, baseline: Float, originalLayout: OriginalLayout): Int {
        if (content.overflowMode == "Wrap") return -1
        val firstLine = originalLayout.lines.firstOrNull() ?: return -1
        val firstLineBaseline = originalLineBaseline(
            baseline,
            0,
            originalLayout.lineHeight,
            0f,
            firstLine.rubyHeight,
            originalLayout.lineGap
        )
        val save = canvas.save()
        val top = max(
            padTop.toFloat(),
            rubyClipTop(
                firstLineBaseline,
                originalPaint.fontMetrics.ascent,
                firstLine.rubyHeight
            )
        ).toInt()
        val clip = lyricClipBounds(padLeft, top, ow - padRight, oh - padBottom)
        canvas.clipRect(clip[0].toFloat(), clip[1].toFloat(), clip[2].toFloat(), clip[3].toFloat())
        return save
    }

    /** 按行布局绘制整行文字（含 ruby 分段），供共享 LyricGlowRenderer 的行回调使用。 */
    private fun drawLineTextForGlow(canvas: Canvas, line: OriginalLine, baseline: Float, paint: Paint) {
        if (line.ruby.isEmpty() || line.textRuns.isEmpty()) {
            canvas.drawText(line.text, line.startX, baseline, paint)
        } else {
            var index = 0
            while (index < line.textRuns.size) {
                val run = line.textRuns[index]
                canvas.drawText(line.text, run.start, run.end, line.startX + run.x, baseline, paint)
                index++
            }
        }
    }

    /** 逐字卡拉OK路径（逐字源+关发光+非行级同步）：词级缩放/位移 + 词内扫光渐变。 */
    private fun drawWordKaraoke(canvas: Canvas, baseline: Float, originalLayout: OriginalLayout) {
        val lines = originalLayout.lines
        val position = projectedPosition()
        var precedingRuby = 0f
        var lineIndex = 0
        while (lineIndex < lines.size) {
            val line = lines[lineIndex]
            val lineBaseline = originalLineBaseline(
                baseline,
                lineIndex,
                originalLayout.lineHeight,
                precedingRuby,
                line.rubyHeight,
                originalLayout.lineGap
            )
            val lineClipSave = clipOriginalLine(canvas, lineBaseline, line.rubyHeight)
            if (line.ruby.isNotEmpty()) {
                drawRuby(canvas, line, lineBaseline)
            }
            var x = 0f
            var wordIndex = 0
            while (wordIndex < line.words.size) {
                val placed = line.words[wordIndex]
                val word = placed.word
                val width = placed.width
                val wordX = line.startX + x
                val progress = timedWordProgress(position, word.startMs, word.endMs)
                val active = position >= word.startMs && position < word.endMs
                val sung = position >= word.endMs
                val scale = if (active) scaleSpline(progress) else if (!sung) 0.95f else 1f
                val y = if (active) yOffsetSpline(progress) * originalPaint.textSize
                else if (!sung) 0.01f * originalPaint.textSize else 0f
                canvas.save()
                val wordBaseline = lineBaseline
                canvas.scale(scale, scale, wordX + ow / 2f, wordBaseline)
                originalPaint.shader = null
                setTextAlpha(
                    originalPaint,
                    if (sung) 1f else 0.35f,
                    1f,
                    if (sung) resolvedPalette.sungText else resolvedPalette.unsungText
                )
                canvas.drawText(word.text, wordX, wordBaseline + y, originalPaint)
                if (active) {
                    setTextAlpha(originalPaint, 1f, 1f, resolvedPalette.sungText)
                    applyWordSweepShader(
                        originalPaint,
                        resolvedPalette.sungText,
                        origin = wordX,
                        progress = progress,
                        extent = width
                    )
                    canvas.drawText(word.text, wordX, wordBaseline + y, originalPaint)
                    originalPaint.shader = null
                }
                canvas.restore()
                x += width + placed.gapAfter
                wordIndex++
            }
            if (lineClipSave != -1) canvas.restoreToCount(lineClipSave)
            precedingRuby += line.rubyHeight
            lineIndex++
        }
    }

    /**
     * 逐字卡拉OK路径的词内扫光渐变:
     * 与共享 LyricGlowRenderer Pass 3 同形状([sung→middle→transparent, CLAMP]),
     * 每词绝对坐标构建,不复用缓存 shader,调用方负责置空。
     */
    private fun applyWordSweepShader(
        paint: Paint,
        color: Int,
        origin: Float,
        progress: Float,
        extent: Float
    ) {
        val safeExtent = extent.coerceAtLeast(0f)
        val band = (safeExtent * LyricGlowRenderer.SWEEP_BAND_FRACTION).coerceAtLeast(1f)
        val start = origin - band + (safeExtent + band) * progress.coerceIn(0f, 1f)
        val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        val middle = Color.argb(184, Color.red(color), Color.green(color), Color.blue(color))
        paint.shader = LinearGradient(
            start, 0f, start + band, 0f,
            intArrayOf(color, middle, transparent),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun drawRuby(
        canvas: Canvas,
        line: OriginalLine,
        baseBaseline: Float,
        bright: Boolean = true
    ) {
        rubyPaint.color = resolvedPalette.secondaryText
        rubyPaint.alpha = (255f * steadyTextAlpha(if (bright) 1f else 0.35f)).toInt()
        val gap = line.rubyHeight + rubyPaint.fontMetrics.ascent
        val baseline = baseBaseline + originalPaint.fontMetrics.ascent -
            gap - rubyPaint.fontMetrics.descent
        var index = 0
        while (index < line.ruby.size) {
            val placement = line.ruby[index]
            canvas.drawText(
                placement.reading,
                rubyDrawCenterX(line.startX, placement.rubyCenterX),
                baseline,
                rubyPaint
            )
            index++
        }
    }

    private fun clipOriginalLine(canvas: Canvas, baseBaseline: Float, rubyHeight: Float): Int {
        if (content.overflowMode == "Wrap") return -1
        val save = canvas.save()
        val top = max(padTop.toFloat(), rubyClipTop(baseBaseline, originalPaint.fontMetrics.ascent, rubyHeight)).toInt()
        val clip = lyricClipBounds(padLeft, top, ow - padRight, oh - padBottom)
        canvas.clipRect(clip[0].toFloat(), clip[1].toFloat(), clip[2].toFloat(), clip[3].toFloat())
        return save
    }

    private fun frameInterval(): Long = frameIntervalForTiming(
        effectiveCadenceActive(),
        timingActive = true
    )

    private fun effectiveCadenceActive(): Boolean = isEffectiveCadenceActive(
        attached = isAttachedToWindow,
        sceneActive = sceneActive,
        ownVisible = visibility == VISIBLE,
        windowVisible = windowVisibility == VISIBLE,
        aggregatedVisible = aggregatedVisible && isShown,
        effectiveAlpha = effectiveAlpha(),
        timedOrTransitionActive = timingEffectActive() || exitSnapshot != null,
        handoffActive = handoffActive,
        verifiedDozeHost = useDozeHandlerCadence
    )

    private fun timingEffectActive(): Boolean = timingEffectEnabled

    /** 渲染停摆看门狗用:最后一次真实 onDraw 的 elapsedRealtime 时刻,0 表示尚未绘制过。 */
    fun lastDrawAtElapsedMs(): Long = lastDrawAtElapsedMs

    /** 渲染停摆看门狗用:当前内容是否带行级时间轴(播放中本应持续重绘)。 */
    fun isTimingEffectActive(): Boolean = timingEffectEnabled

    private fun effectiveAlpha(): Float {
        var value = alpha * transitionAlpha
        var ancestor = parent as? View
        while (ancestor != null) {
            value *= ancestor.alpha * ancestor.transitionAlpha
            if (value == 0f) return value
            ancestor = ancestor.parent as? View
        }
        return value
    }

    private fun syncCadence() {
        when (cadenceGate.update(effectiveCadenceActive())) {
            CadenceChange.START -> {
                removeCallbacks(frame)
                scheduleFrame(frame, 0L)
            }
            CadenceChange.STOP -> removeCallbacks(frame)
            CadenceChange.NONE -> Unit
        }
    }

    private fun scheduleFrame(action: Runnable, delayMs: Long) {
        if (useDozeHandlerCadence) postDelayed(action, delayMs)
        else postOnAnimation(action)
    }

    private fun recordDozeCadenceCallback() {
        if (!useDozeHandlerCadence || !HookLogger.traceEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (cadenceWindowStartedAt == 0L) cadenceWindowStartedAt = now
        cadenceCallbackCount++
        if (now - cadenceWindowStartedAt < CADENCE_DIAGNOSTIC_WINDOW_MS) return
        HookLogger.i(
            CADENCE_DIAGNOSTIC_TAG,
            "callbacks=$cadenceCallbackCount draws=$cadenceDrawCount " +
                "maxDrawGapMs=$cadenceMaxDrawGapMs"
        )
        cadenceWindowStartedAt = now
        cadenceCallbackCount = 0
        cadenceDrawCount = 0
        cadenceMaxDrawGapMs = 0L
    }

    private fun recordDozeDraw() {
        if (!useDozeHandlerCadence || !HookLogger.traceEnabled) return
        val now = SystemClock.elapsedRealtime()
        if (cadenceLastDrawAt > 0L) {
            cadenceMaxDrawGapMs = maxOf(cadenceMaxDrawGapMs, now - cadenceLastDrawAt)
        }
        cadenceLastDrawAt = now
        cadenceDrawCount++
    }

    private fun buildOriginalLayout(): OriginalLayout {
        val words = coalesceRubyWords(
            content.original,
            content.words.filter { it.text.isNotBlank() },
            content.ruby
        )
        val lines = if (words.isEmpty()) {
            if (content.adaptiveSectioning) layoutTextByGroups()
            else wrapText(content.original, originalPaint)
        } else {
            layoutWordLines(words, 8f * density)
        }
        val metrics = originalPaint.fontMetrics
        return OriginalLayout(
            assignRuby(lines),
            metrics.descent - metrics.ascent + 2f * density,
            ORIGINAL_LINE_GAP_DP * density,
            words.isNotEmpty()
        )
    }

    private fun layoutTextByGroups(): List<OriginalLine> {
        val ranges = coveredLayoutRanges(content.original, content.layoutGroups)
        if (ranges.isEmpty()) return wrapText(content.original, originalPaint)
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
        return layoutWordLines(synthetic, 8f * density)
    }

    private fun layoutWordLines(words: List<AodCanvasWord>, gap: Float): List<OriginalLine> {
        val available = (ow - padLeft - padRight).coerceAtLeast(1).toFloat()
        val maxLines = lyricLayoutLineLimit(words.size)
        val offsets = wordOffsets(words)
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
            return listOf(wordLine(placed))
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
                wordLine(lineWords)
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
            wordLine(lineWords)
        }
        return lines.ifEmpty { listOf(originalLine("", 0f, null, null)) }
    }

    private fun wordLine(words: List<PlacedWord>): OriginalLine {
        val mapped = words.mapNotNull { word -> word.offset?.let { it.first to it.last + 1 } }
        val offsets = mapped.takeIf { it.size == words.size }
        val start = offsets?.minOf { it.first }
        val end = offsets?.maxOf { it.second }
        val text = if (start != null && end != null && start >= 0 && end <= content.original.length) {
            content.original.substring(start, end)
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
            text,
            width,
            start,
            end
        )
            .copy(words = words)
    }

    private fun wrapText(text: String, paint: Paint): List<OriginalLine> {
        if (text.isBlank()) return emptyList()
        val available = (ow - padLeft - padRight).coerceAtLeast(1).toFloat()
        if (content.overflowMode != "Wrap") {
            return listOf(originalLine(text, paint.measureText(text), 0, text.length))
        }
        val maxLines = lyricLayoutLineLimit()
        val lines = ArrayList<OriginalLine>(maxLines)
        var remaining = text
        var charStart = 0
        while (remaining.isNotEmpty() && lines.size < maxLines) {
            val count = paint.breakText(remaining, true, available, null).coerceAtLeast(1)
            val line = remaining.take(count)
            lines += originalLine(line, paint.measureText(line), charStart, charStart + line.length)
            remaining = remaining.drop(count)
            charStart += count
        }
        return lines
    }

    private fun lyricLayoutLineLimit(wordCount: Int = content.words.size): Int =
        resolvedLyricLayoutLineLimit(
            content.lyricLineLimit,
            content.original.length,
            wordCount
        )

    private fun transliterationLines(originalLayout: OriginalLayout): List<TextLine>? {
        if (originalLayout.lines.isEmpty() || originalLayout.lines.any { it.words.isEmpty() }) return null
        val available = (ow - padLeft - padRight).coerceAtLeast(1).toFloat()
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
            textLine(text, lineWidth, romanizedPaint).copy(timedSegments = lineSegments)
        }
    }

    private fun wrapSecondaryText(text: String, paint: Paint, preferredLines: Int): List<TextLine> {
        if (!content.adaptiveSectioning || content.overflowMode != "Wrap") {
            return listOf(textLine(text, paint.measureText(text), paint))
        }
        val available = (ow - padLeft - padRight).coerceAtLeast(1).toFloat()
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
        ).map { line -> textLine(line, paint.measureText(line), paint) }
    }

    /**
     * 歌曲信息（歌名/歌手）专用换行：只要单行超出可用宽度就自动换行，最多
     * [MAX_SECONDARY_LINES] 行（不受歌词 sectioning/overflow 偏好门控，与歌词换行解耦）。
     */
    private fun wrapMetadataText(text: String, paint: Paint): List<TextLine> {
        val available = (ow - padLeft - padRight).coerceAtLeast(1).toFloat()
        // 歌名/歌手已由投影层按行拆分:保留这些硬换行作为独立行,仅对其中仍超宽的行做 token 换行。
        val segments = text.split('\n', '·')
        val out = ArrayList<TextLine>()
        for (segment in segments) {
            val clean = segment.trim()
            if (clean.isEmpty()) continue
            if (out.size >= MAX_SECONDARY_LINES) break
            if (paint.measureText(clean) <= available) {
                out += textLine(clean, paint.measureText(clean), paint, alignmentFor(RowKind.METADATA))
                continue
            }
            val tokens = secondaryTokens(clean).flatMap { token ->
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
            if (tokens.isEmpty()) continue
            val wrapped = balancedTokenLineTexts(
                tokens,
                tokens.map(paint::measureText),
                paint.measureText(" "),
                available,
                (MAX_SECONDARY_LINES - out.size).coerceAtLeast(1)
            )
            out += wrapped.map { line ->
                textLine(line, paint.measureText(line), paint, alignmentFor(RowKind.METADATA))
            }
        }
        return out
    }

    private fun textLine(
        text: String,
        width: Float,
        paint: Paint,
        lineAlignment: Alignment = alignment
    ): TextLine {
        val visual = visualExtents(text, paint, width)
        return TextLine(text, width, alignedStart(width, lineAlignment, visual.first, visual.second))
    }

    private fun originalLine(text: String, width: Float, charStart: Int?, charEnd: Int?): OriginalLine {
        val visual = visualExtents(text, originalPaint, width)
        return OriginalLine(
            text,
            emptyList(),
            width,
            alignedStart(width, alignment, visual.first, visual.second),
            charStart,
            charEnd
        )
    }

    private fun wordOffsets(words: List<AodCanvasWord>): List<IntRange?> =
        words.map { transportedWordOffset(content.original, it) }

    private fun assignRuby(lines: List<OriginalLine>): List<OriginalLine> = lines.map { line ->
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
                val baseRun = measureBaseRun(line, baseStart, baseEnd) ?: return@mapNotNull null
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
            startX = alignedStart(line.width, alignment, visualLeft, visualRight),
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

    private fun measureBaseRun(line: OriginalLine, start: Int, end: Int): BaseRun? {
        if (start >= end) return null
        val lineStart = line.charStart ?: return null
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

    private fun drawOriginalText(
        canvas: Canvas,
        line: OriginalLine,
        baseline: Float,
        glow: Float = 0f
    ) {
        if (line.ruby.isEmpty()) {
            drawGlowHalo(canvas, line.text, 0, line.text.length, line.startX, baseline, originalPaint, glow)
            canvas.drawText(line.text, line.startX, baseline, originalPaint)
            return
        }
        if (line.textRuns.isEmpty()) {
            drawGlowHalo(canvas, line.text, 0, line.text.length, line.startX, baseline, originalPaint, glow)
            canvas.drawText(line.text, line.startX, baseline, originalPaint)
            return
        }
        var index = 0
        while (index < line.textRuns.size) {
            val run = line.textRuns[index]
            val runX = line.startX + run.x
            drawGlowHalo(canvas, line.text, run.start, run.end, runX, baseline, originalPaint, glow)
            canvas.drawText(
                line.text,
                run.start,
                run.end,
                runX,
                baseline,
                originalPaint
            )
            index++
        }
    }

    private fun drawText(canvas: Canvas, row: Row, baseline: Float) {
        // 水平 padding 边界已由 drawRows 顶层的共享逻辑裁剪统一施加,无需逐行再次 clip。
        var lineIndex = 0
        while (lineIndex < row.lines.size) {
            val line = row.lines[lineIndex]
            val lineBaseline = baseline + lineIndex * row.lineHeight
            if (row.kind == RowKind.METADATA) {
                row.paint.color = resolvedPalette.metadataText
                row.paint.alpha = 255
                canvas.drawText(line.text, line.startX, lineBaseline, row.paint)
            } else if (row.kind == RowKind.NEXT_LINE) {
                drawNextLine(canvas, row.paint, line.text, line.startX, lineBaseline)
            } else {
                drawSecondaryLine(canvas, row.paint, line.text, line.startX, lineBaseline)
            }
            lineIndex++
        }
    }

    private fun alignmentFor(kind: RowKind): Alignment = if (kind == RowKind.METADATA) {
        when (content.alignmentMode) {
            "start" -> Alignment.START
            "center" -> Alignment.CENTER
            "end" -> Alignment.END
            else -> Alignment.START
        }
    } else {
        alignment
    }

    private fun alignedStart(
        textWidth: Float,
        lineAlignment: Alignment = alignment,
        visualLeft: Float = 0f,
        visualRight: Float = textWidth
    ): Float = edgeSafeAlignedStart(
        // 对齐基准必须是逻辑帧(ow/padLeft/padRight):横屏时 ow=视口高、pad 为逻辑内边距,
        // startX 落在逻辑坐标系(0..ow)里;若误用视口宽 width,长行居中会得到负坐标,
        // 经 beginRotationTransform 的 rotate+scale 后整行移出可视区(issue #51:
        // 横屏全屏歌词不可见)。竖屏时 ow==width、padLeft==paddingLeft,行为不变。
        canvasWidth = ow.toFloat(),
        paddingLeft = padLeft.toFloat(),
        paddingRight = padRight.toFloat(),
        visualLeft = visualLeft,
        visualRight = visualRight,
        alignment = when (lineAlignment) {
            Alignment.START -> "start"
            Alignment.CENTER -> "center"
            Alignment.END -> "end"
        },
        safetyInset = if (lineAlignment == Alignment.END) END_EDGE_SAFETY_DP * density else 0f
    )

    private fun visualExtents(text: String, paint: Paint, advanceWidth: Float): Pair<Float, Float> {
        if (text.isEmpty()) return 0f to advanceWidth
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        return minOf(0f, bounds.left.toFloat()) to maxOf(advanceWidth, bounds.right.toFloat())
    }

    private fun projectedPosition(): Long {
        val elapsed = (SystemClock.elapsedRealtime() - content.sampledAtElapsedMs).coerceAtLeast(0L)
        return content.positionMs + (elapsed * content.speed).toLong()
    }

    private fun lineProgress(): Float = progress(projectedPosition(), content.lineStartMs, content.lineEndMs)

    private fun progress(position: Long, start: Long, end: Long): Float =
        if (end <= start) if (position >= end) 1f else 0f
        else ((position - start).toFloat() / (end - start)).coerceIn(0f, 1f)

    private fun scaleSpline(t: Float): Float = if (t <= 0.7f) lerp(0.95f, 1.0505f, t / 0.7f)
    else lerp(1.0505f, 1f, (t - 0.7f) / 0.3f)

    private fun yOffsetSpline(t: Float): Float = if (t <= 0.9f) lerp(0.01f, -(1f / 60f), t / 0.9f)
    else lerp(-(1f / 60f), 0f, (t - 0.9f) / 0.1f)

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t.coerceIn(0f, 1f)

    private fun setTextAlpha(
        paint: Paint,
        factor: Float,
        brightness: Float,
        color: Int = resolvedPalette.primaryText
    ) {
        paint.color = color
        paint.alpha = (255f * (steadyTextAlpha(factor) * brightness).coerceIn(0f, 1f)).toInt()
    }

    private fun drawGlowHalo(
        canvas: Canvas,
        text: String,
        start: Int,
        end: Int,
        x: Float,
        y: Float,
        paint: Paint,
        glow: Float
    ) {
        // 文字本体不发光: 禁用辉光层
        return
        if (glow <= 0.02f || end <= start) return
        val intensity = glow.coerceIn(0f, 1f)
        val glowColor = resolvedPalette.glow
        val savedShader = paint.shader
        val savedColor = paint.color
        val savedAlpha = paint.alpha
        // 柔和光晕：单独绘制一个带模糊阴影的发光层，shader 置空以规避
        // 硬件加速下 shadow+shader 同置导致发光丢失的问题。
        paint.shader = null
        paint.color = glowColor
        paint.alpha = (GLOW_HALO_ALPHA * intensity).roundToInt().coerceIn(0, 255)
        paint.setShadowLayer(
            paint.textSize * GLOW_HALO_RADIUS * intensity,
            0f,
            0f,
            glowColor
        )
        canvas.drawText(text, start, end, x, y, paint)
        paint.setShadowLayer(0f, 0f, 0f, 0)
        paint.color = savedColor
        paint.alpha = savedAlpha
        paint.shader = savedShader
    }

    private fun drawSecondaryLine(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        baseline: Float
    ) {
        setTextAlpha(
            paint,
            staticSecondaryTextFactor(content.secondaryTextBright),
            1f,
            resolvedPalette.secondaryText
        )
        paint.shader = null
        paint.clearShadowLayer()
        canvas.drawText(text, x, baseline, paint)
    }

    private fun drawNextLine(
        canvas: Canvas,
        paint: Paint,
        text: String,
        x: Float,
        baseline: Float
    ) {
        paint.color = resolvedPalette.nextLineText
        paint.alpha = (255f * staticNextLineTextFactor()).toInt()
        paint.shader = null
        paint.clearShadowLayer()
        canvas.drawText(text, x, baseline, paint)
    }

    private fun paint(sizeSp: Float, color: Int, weight: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizeSp * scaledDensity
        setColor(color)
        typeface = Typeface.create("sans-serif", weight)
        isSubpixelText = true
    }

    private fun resolveTypeface(family: String, weight: String): Typeface {
        val key = TypefaceKey(family, weight)
        typefaceCache[key]?.let { return it }
        val asset = if (family == "noto") {
            "fonts/NotoSans-" + when (weight) {
                "Bold" -> "Bold"
                "Medium" -> "Medium"
                else -> "Regular"
            } + ".ttf"
        } else if (family == "apple") {
            if (weight == "Regular") "fonts/lyrics_medium.ttf" else "fonts/sf-pro-display-bold.ttf"
        } else if (weight == "Bold") {
            "fonts/sf-pro-display-bold.ttf"
        } else {
            "fonts/spotifymix-medium.ttf"
        }
        val typeface = runCatching {
            Typeface.createFromAsset(fontContext?.assets ?: context.assets, asset)
        }.getOrElse {
            val fallback = if (family == "apple") "sans-serif" else "sans-serif-medium"
            Typeface.create(fallback, if (weight == "Bold") Typeface.BOLD else Typeface.NORMAL)
        }
        typefaceCache[key] = typeface
        return typeface
    }

    private enum class RowKind { METADATA, ORIGINAL, ROMANIZED, TRANSLATED, NEXT_LINE }
    private data class Row(
        val kind: RowKind,
        val text: String,
        val paint: Paint,
        val height: Float,
        val gapBefore: Float,
        val lines: List<TextLine>,
        val lineHeight: Float
    )
    private data class PlacedWord(
        val word: AodCanvasWord,
        val width: Float,
        val gapAfter: Float,
        val offset: IntRange?
    )
    private data class OriginalLine(
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
    private data class TextLine(
        val text: String,
        val width: Float,
        val startX: Float,
        val timedSegments: List<SecondaryTimedSegment> = emptyList()
    )
    private data class BaseRun(val x: Float, val width: Float)
    private data class RubyPlacement(
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
    private data class CanvasSnapshot(
        val content: AodCanvasContent,
        val layout: LayoutState,
        val renderStyle: RenderStyleSnapshot
    )
    private data class RenderStyleSnapshot(
        val metadataPaint: Paint,
        val originalPaint: Paint,
        val romanizedPaint: Paint,
        val translatedPaint: Paint,
        val rubyPaint: Paint,
        val palette: AodResolvedPalette,
        val alignment: Alignment
    )
    private data class PositionedRow(val row: Row, val baseline: Float, val animate: Boolean)
    private data class OriginalLayout(
        val lines: List<OriginalLine>,
        val lineHeight: Float,
        val lineGap: Float,
        val timed: Boolean
    ) {
        val lineCount: Int
            get() = lines.size
        val rubyHeight: Float
            get() = lines.sumOf { it.rubyHeight.toDouble() }.toFloat()
    }

    private data class LayoutState(
        val rows: List<PositionedRow>,
        val original: OriginalLayout
    )
    private data class TypefaceKey(val family: String, val weight: String)

    companion object {
        private const val MAX_SECONDARY_LINES = 2
        private const val ENTER_TRANSITION_MS = 210L
        private const val EXIT_TRANSITION_MS = 130L
        private const val ORIGINAL_LINE_GAP_DP = 4f
        private const val END_EDGE_SAFETY_DP = 4f
        private const val CADENCE_DIAGNOSTIC_WINDOW_MS = 10_000L
        private const val CADENCE_DIAGNOSTIC_TAG = "AodCanvasCadence"
        private const val GLOW_HALO_ALPHA = 235
        private const val GLOW_HALO_RADIUS = 0.52f
    }
}
