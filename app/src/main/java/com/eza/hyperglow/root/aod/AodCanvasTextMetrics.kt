package com.eza.hyperglow.root.aod

import kotlin.math.max
import kotlin.math.round

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

/**
 * 预览(PreviewComponents)与实机(AodLyricCanvasView)共用的字号/字号档换算 ——
 * 与 LyricGlowRenderer 同思路:公式只此一份,杜绝预览与实机各写一套造成显示漂移。
 */

/** 歌曲信息字号(sp):14sp 基准 × 用户百分比(50%~200%)。 */
internal fun metadataTextSizeSp(percent: Int): Float =
    14f * metadataTextSizeMultiplier(percent)

/** 副文本音标行字号(sp):主字号 baseSp 的 0.48 倍,带 14sp 下限。 */
internal fun secondaryReadingTextSizeSp(baseSp: Float): Float =
    max(14f, round(baseSp * 0.48f))

/** 副文本翻译行字号(sp):音标行再小 1sp,带 13sp 下限。 */
internal fun secondaryTranslationTextSizeSp(baseSp: Float): Float =
    max(13f, round(baseSp * 0.48f) - 1f)

/** 下一行歌词字号(sp):固定 15sp,不随字号档位缩放。 */
internal fun nextLineTextSizeSp(): Float = 15f

/**
 * 元数据小部件的静态高度预算(dp)。基准为历史两行预算;[extraLines] 为超出两行的
 * 切片行数(歌名/歌手/专辑换行分隔符下选满 3 部分时为 1),每行按一行等比高度追加。
 */
internal fun metadataWidgetHeightDp(percent: Int, extraLines: Int = 0): Float =
    22f + 14f * metadataTextSizeMultiplier(percent) * (1 + extraLines.coerceAtLeast(0))

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

/**
 * 内容自然堆叠高度(AodLyricCanvasView.measureContentStack 与锁屏卡片自适应高度同源):
 * 画布上下 padding + 各行(行高 + 行前距)之和 + 元数据行与歌词之间的间距
 * ([metadataGapPx];无元数据行时传 0)。与 AodLyricCanvasView.positionRows 的顶锚排版
 * 同式:元数据多行时行高公式已含多出行高(positionRows 的 metadataExtraHeight 避让
 * 与 rowWithLines 的 height = n * lineHeight 同账),无需另补。
 */
internal fun contentStackHeightPx(
    rowHeightsPx: List<Float>,
    rowGapsBeforePx: List<Float>,
    metadataGapPx: Float,
    padTopPx: Float,
    padBottomPx: Float
): Float {
    var stack = metadataGapPx
    for (index in rowHeightsPx.indices) {
        stack += rowHeightsPx[index] + rowGapsBeforePx.getOrElse(index) { 0f }
    }
    return padTopPx + padBottomPx + stack
}
