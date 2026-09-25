package com.eza.hyperglow.root.aod

import android.graphics.Paint
import android.graphics.Rect

/**
 * 歌词布局引擎 —— 预览(PreviewComponents)与实机(AodLyricCanvasView)共用的换行/测量核心,
 * 与 LyricGlowRenderer/字号公式同思路:折行断点、词行划分、行数门控只此一份,
 * 从构造上保证两端断行一致。定位(对齐 X)单独走 [lineStartX],由调用方代入各自几何。
 *
 * 纯核心以 [TextMeasurePort] 注入测量,可脱离 android.graphics 在 JVM 单测;
 * Paint 适配入口仅供渲染侧调用。
 */

// ---- 行/词几何常量(实机与预览共享;两端像素级一致的来源) ----

/** 主歌词行高附加(dp,叠加在 ascent..descent 上)。 */
internal const val LYRIC_LINE_EXTRA_HEIGHT_DP = 2f

/** 主歌词行间距(dp,行与行之间)。 */
internal const val LYRIC_LINE_GAP_DP = 4f

/** 词行布局的词间 gap(dp,仅 boundaryAfter 的词后生效)。 */
internal const val LYRIC_WORD_GAP_DP = 8f

/** 副文本(音标/翻译/下一行)与歌曲信息的最大换行行数。 */
internal const val MAX_SECONDARY_LAYOUT_LINES = 2

/** 行块上方留白(dp):主歌词行/副文本行/下一行(歌曲信息为 0)。 */
internal const val ROW_GAP_BEFORE_ORIGINAL_DP = 8f
internal const val ROW_GAP_BEFORE_SECONDARY_DP = 2f
internal const val ROW_GAP_BEFORE_NEXT_LINE_DP = 4f

/** 歌曲信息与歌词块的间距(dp)。 */
internal const val METADATA_LYRIC_GAP_DP = 10f

/** 右对齐末端安全内缩(dp,与实机 alignedStart 一致)。 */
internal const val END_EDGE_SAFETY_DP = 4f

// ---- 输出类型 ----

/** 主歌词一行:换行/测量结果(words 供逐字/音标后处理复用)。 */
internal data class LyricLayoutLine(
    val text: String,
    val width: Float,
    val charStart: Int?,
    val charEnd: Int?,
    val words: List<PlacedWord> = emptyList()
)

/** 副文本/歌曲信息一行:换行/测量结果。 */
internal data class LyricLayoutTextLine(val text: String, val width: Float)

/** 主歌词布局结果:行 + 是否走词级时间布局(词非空)。 */
internal data class LyricLayoutResult(
    val lines: List<LyricLayoutLine>,
    val timed: Boolean
)

/** 文本测量端口:measure=整串宽;breakAt=在 maxWidth 内可容纳的字符数。 */
internal class TextMeasurePort(
    val measure: (String) -> Float,
    val breakAt: (String, Float) -> Int
)

private fun Paint.measurePort(): TextMeasurePort =
    TextMeasurePort(measure = { measureText(it) }, breakAt = { text, maxWidth -> breakText(text, true, maxWidth, null) })

// ---- Paint 适配入口(实机/预览渲染侧) ----

internal fun layoutOriginalLines(
    original: String,
    words: List<AodCanvasWord>,
    ruby: List<AodCanvasRuby>,
    layoutGroups: List<AodCanvasLayoutGroup>,
    paint: Paint,
    availableWidth: Float,
    lineLimit: Int,
    wordGapPx: Float,
    wrap: Boolean,
    adaptiveSectioning: Boolean
): LyricLayoutResult = layoutOriginalLines(
    original, words, ruby, layoutGroups, paint.measurePort(),
    availableWidth, lineLimit, wordGapPx, wrap, adaptiveSectioning
)

internal fun layoutSecondaryLines(
    text: String,
    paint: Paint,
    availableWidth: Float,
    preferredLines: Int,
    wrap: Boolean,
    adaptiveSectioning: Boolean
): List<LyricLayoutTextLine> = layoutSecondaryLines(
    text, paint.measurePort(), availableWidth, preferredLines, wrap, adaptiveSectioning
)

internal fun layoutMetadataLines(
    text: String,
    paint: Paint,
    availableWidth: Float
): List<LyricLayoutTextLine> = layoutMetadataLines(text, paint.measurePort(), availableWidth)

// ---- 纯核心(测量经 TextMeasurePort,可 JVM 单测) ----

/**
 * 主歌词换行/词行布局(与实机 buildOriginalLayout 同一决策树,不含 ruby 注入):
 * 词非空走词行布局;词空时 adaptiveSectioning 走分组均衡,否则整段折行。
 * 行数门控沿用各路径原有口径(词行=合并后词数、分组=组合块数、整段=原始词数)。
 */
internal fun layoutOriginalLines(
    original: String,
    words: List<AodCanvasWord>,
    ruby: List<AodCanvasRuby>,
    layoutGroups: List<AodCanvasLayoutGroup>,
    metrics: TextMeasurePort,
    availableWidth: Float,
    lineLimit: Int,
    wordGapPx: Float,
    wrap: Boolean,
    adaptiveSectioning: Boolean
): LyricLayoutResult {
    val coalesced = coalesceRubyWords(original, words.filter { it.text.isNotBlank() }, ruby)
    val lines = if (coalesced.isEmpty()) {
        if (adaptiveSectioning) {
            layoutTextByGroups(original, layoutGroups, metrics, availableWidth, lineLimit, wordGapPx, wrap, words.size)
        } else {
            wrapTextLines(original, metrics, availableWidth, lineLimit, wrap, words.size)
        }
    } else {
        layoutWordLines(original, coalesced, metrics, availableWidth, lineLimit, wordGapPx, wrap, adaptiveSectioning, layoutGroups)
    }
    return LyricLayoutResult(lines, coalesced.isNotEmpty())
}

/**
 * 副文本(音标/翻译/下一行)token 均衡换行(与实机 wrapSecondaryText 同算法):
 * 非 Wrap 或关 sectioning 时单行;否则按 token 均衡到 preferredLines(超宽时至少
 * [MAX_SECONDARY_LAYOUT_LINES])行,超宽 token 先 CJK 避头尾切片。
 */
internal fun layoutSecondaryLines(
    text: String,
    metrics: TextMeasurePort,
    availableWidth: Float,
    preferredLines: Int,
    wrap: Boolean,
    adaptiveSectioning: Boolean
): List<LyricLayoutTextLine> {
    if (!adaptiveSectioning || !wrap) {
        return listOf(LyricLayoutTextLine(text, metrics.measure(text)))
    }
    val tokens = secondaryTokens(text).flatMap { token -> splitOversizeToken(token, metrics, availableWidth) }
    if (tokens.isEmpty()) return emptyList()
    val maxLines = if (metrics.measure(text) > availableWidth) {
        maxOf(preferredLines, MAX_SECONDARY_LAYOUT_LINES)
    } else {
        preferredLines
    }.coerceIn(1, MAX_SECONDARY_LAYOUT_LINES)
    return balancedTokenLineTexts(
        tokens,
        tokens.map(metrics.measure),
        metrics.measure(" "),
        availableWidth,
        maxLines
    ).map { line -> LyricLayoutTextLine(line, metrics.measure(line)) }
}

/**
 * 歌曲信息(歌名/歌手)专用换行(与实机 wrapMetadataText 同算法):按 '·'/换行拆段,
 * 单行放不下就 token 换行,合计最多 [MAX_SECONDARY_LAYOUT_LINES] 行(不受歌词偏好门控)。
 */
internal fun layoutMetadataLines(
    text: String,
    metrics: TextMeasurePort,
    availableWidth: Float
): List<LyricLayoutTextLine> {
    val segments = text.split('\n', '·')
    val out = ArrayList<LyricLayoutTextLine>()
    for (segment in segments) {
        val clean = segment.trim()
        if (clean.isEmpty()) continue
        if (out.size >= MAX_SECONDARY_LAYOUT_LINES) break
        if (metrics.measure(clean) <= availableWidth) {
            out += LyricLayoutTextLine(clean, metrics.measure(clean))
            continue
        }
        val tokens = secondaryTokens(clean).flatMap { token -> splitOversizeToken(token, metrics, availableWidth) }
        if (tokens.isEmpty()) continue
        val wrapped = balancedTokenLineTexts(
            tokens,
            tokens.map(metrics.measure),
            metrics.measure(" "),
            availableWidth,
            (MAX_SECONDARY_LAYOUT_LINES - out.size).coerceAtLeast(1)
        )
        out += wrapped.map { line -> LyricLayoutTextLine(line, metrics.measure(line)) }
    }
    return out
}

/** 超宽 token 切片(切点走 CJK 避头尾);不超宽原样返回。 */
private fun splitOversizeToken(
    token: String,
    metrics: TextMeasurePort,
    availableWidth: Float
): List<String> {
    if (metrics.measure(token) <= availableWidth) return listOf(token)
    val pieces = ArrayList<String>()
    var remaining = token
    while (remaining.isNotEmpty()) {
        val count = metrics.breakAt(remaining, availableWidth).coerceAtLeast(1)
        // CJK 避头尾(issue #68 #2):超宽 token 的切片不走禁则断点。
        val breakEnd = if (count < remaining.length) {
            adjustForCjkLineBreak(remaining, 0, count, remaining.length)
        } else {
            count
        }
        pieces += remaining.take(breakEnd)
        remaining = remaining.drop(breakEnd)
    }
    return pieces
}

/** 整段折行(与实机 wrapText 同算法):非 Wrap 单行;否则 breakText+CJK 断点循环到行数上限。 */
private fun wrapTextLines(
    text: String,
    metrics: TextMeasurePort,
    availableWidth: Float,
    lineLimit: Int,
    wrap: Boolean,
    rawWordCount: Int
): List<LyricLayoutLine> {
    if (text.isBlank()) return emptyList()
    if (!wrap) {
        return listOf(LyricLayoutLine(text, metrics.measure(text), 0, text.length))
    }
    val maxLines = resolvedLyricLayoutLineLimit(lineLimit, text.length, rawWordCount)
    val lines = ArrayList<LyricLayoutLine>(maxLines)
    var remaining = text
    var charStart = 0
    while (remaining.isNotEmpty() && lines.size < maxLines) {
        val count = metrics.breakAt(remaining, availableWidth).coerceAtLeast(1)
        // CJK 避头尾(issue #68 #2):断点落在禁则字符上时回退;整段放得下的末行不调。
        val fitEnd = charStart + count
        val breakEnd = if (fitEnd < text.length) {
            adjustForCjkLineBreak(text, charStart, fitEnd, text.length)
        } else {
            fitEnd
        }
        val line = text.substring(charStart, breakEnd)
        lines += LyricLayoutLine(line, metrics.measure(line), charStart, breakEnd)
        remaining = text.substring(breakEnd)
        charStart = breakEnd
    }
    return lines
}

/** 分组均衡折行(与实机 layoutTextByGroups 同算法):组范围合成词后走词行布局。 */
private fun layoutTextByGroups(
    text: String,
    groups: List<AodCanvasLayoutGroup>,
    metrics: TextMeasurePort,
    availableWidth: Float,
    lineLimit: Int,
    wordGapPx: Float,
    wrap: Boolean,
    rawWordCount: Int
): List<LyricLayoutLine> {
    val ranges = coveredLayoutRanges(text, groups)
    if (ranges.isEmpty()) return wrapTextLines(text, metrics, availableWidth, lineLimit, wrap, rawWordCount)
    val synthetic = ranges.mapIndexed { index, range ->
        val nextStart = ranges.getOrNull(index + 1)?.first ?: range.last + 1
        val boundaryAfter = index < ranges.lastIndex && text
            .substring(range.last + 1, nextStart).any { it.isWhitespace() }
        AodCanvasWord(
            text.substring(range.first, range.last + 1),
            "",
            0L,
            0L,
            boundaryAfter,
            range.first,
            range.last + 1
        )
    }
    return layoutWordLines(text, synthetic, metrics, availableWidth, lineLimit, wordGapPx, wrap, true, groups)
}

/** 词行布局(与实机 layoutWordLines 同算法):测宽/词距 → 按溢出偏好选单行/legacy/分组均衡。 */
private fun layoutWordLines(
    original: String,
    words: List<AodCanvasWord>,
    metrics: TextMeasurePort,
    availableWidth: Float,
    lineLimit: Int,
    wordGapPx: Float,
    wrap: Boolean,
    adaptiveSectioning: Boolean,
    layoutGroups: List<AodCanvasLayoutGroup>
): List<LyricLayoutLine> {
    val maxLines = resolvedLyricLayoutLineLimit(lineLimit, original.length, words.size)
    val offsets = words.map { transportedWordOffset(original, it) }
    val placed = words.mapIndexed { index, word ->
        val wordWidth = metrics.measure(word.text)
        val gapAfter = if (index == words.lastIndex) {
            0f
        } else {
            authoredWordSeparator(original, word, words[index + 1])
                ?.let(metrics.measure)
                ?: aodWordGapAfter(word.boundaryAfter, wordGapPx)
        }
        PlacedWord(word, wordWidth, gapAfter, offsets[index])
    }
    if (!wrap) {
        return listOf(wordLine(original, placed))
    }
    if (!adaptiveSectioning) {
        return legacyAttachedWordLineRanges(
            words,
            placed.map(PlacedWord::width),
            placed.map(PlacedWord::gapAfter),
            availableWidth,
            maxLines
        ).map { range ->
            wordLine(original, range.map(placed::get))
        }
    }
    val groupIds = lexicalGroupIds(offsets, layoutGroups)
    val chunks = ArrayList<List<PlacedWord>>()
    var index = 0
    while (index < placed.size) {
        val groupId = groupIds[index]
        var end = index + 1
        if (groupId != null) while (end < placed.size && groupIds[end] == groupId) end++
        val chunk = placed.subList(index, end)
        val chunkWidth = chunk.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
        if (chunkWidth > availableWidth && chunk.size > 1) chunk.forEach { chunks += listOf(it) }
        else chunks += chunk.toList()
        index = end
    }
    val chunkWidths = chunks.map { chunk ->
        chunk.sumOf { (it.width + it.gapAfter).toDouble() }.toFloat()
    }
    val lines = balancedChunkRanges(chunkWidths, availableWidth, maxLines).map { range ->
        wordLine(original, range.flatMap { chunks[it] })
    }
    return lines.ifEmpty { listOf(LyricLayoutLine("", 0f, null, null)) }
}

/** 词行合成一行(与实机 wordLine 同算法):文本取原文区间,缺失时按词拼接;宽为词宽+词距。 */
private fun wordLine(original: String, words: List<PlacedWord>): LyricLayoutLine {
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
    return LyricLayoutLine(text, width, start, end, words)
}

// ---- 定位辅助(对齐 X;两端代入各自画布几何) ----

/** 与实机 visualExtents 同算法:文本视觉外沿(左取 ≤0 的越界,右取 advance 与字形右沿较大者)。 */
internal fun visualExtents(text: String, paint: Paint, advanceWidth: Float): Pair<Float, Float> {
    if (text.isEmpty()) return 0f to advanceWidth
    val bounds = Rect()
    paint.getTextBounds(text, 0, text.length, bounds)
    return minOf(0f, bounds.left.toFloat()) to maxOf(advanceWidth, bounds.right.toFloat())
}

/**
 * 行对齐 X(与实机 alignedStart 同算法):按 start/center/end 解析到画布逻辑坐标,
 * 含右对齐末端安全内缩。[canvasWidth]/[padLeft]/[padRight] 为调用方几何(实机=逻辑帧,
 * 预览=预览列宽)。
 */
internal fun lineStartX(
    text: String,
    textWidth: Float,
    paint: Paint,
    alignment: String,
    canvasWidth: Float,
    padLeft: Float,
    padRight: Float,
    safetyInsetDp: Float = END_EDGE_SAFETY_DP,
    density: Float = 1f
): Float {
    val visual = visualExtents(text, paint, textWidth)
    return edgeSafeAlignedStart(
        canvasWidth = canvasWidth,
        paddingLeft = padLeft,
        paddingRight = padRight,
        visualLeft = visual.first,
        visualRight = visual.second,
        alignment = alignment,
        safetyInset = if (alignment == "end") safetyInsetDp * density else 0f
    )
}
