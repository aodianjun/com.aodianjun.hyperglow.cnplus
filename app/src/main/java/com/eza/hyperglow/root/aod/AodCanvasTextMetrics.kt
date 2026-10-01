package com.eza.hyperglow.root.aod

import kotlin.math.max
import kotlin.math.min
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

/** 注音字号 = 主歌词字号 × 比例(实机 setContent 与预览共用同一纯函数,杜绝两套换算漂移)。 */
internal fun rubyTextSizePx(baseTextSizePx: Float): Float = baseTextSizePx * 0.46f

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

/**
 * 元数据文本块的视觉高(px):首末行基线间距 + (descent - ascent)。与 [metadataTextCenterY]
 * 同一文本盒定义,图片槽记账/歌词避让共用。
 */
internal fun metadataBlockHeightPx(
    lineCount: Int,
    lineHeight: Float,
    ascent: Float,
    descent: Float
): Float = (lineCount - 1).coerceAtLeast(0) * lineHeight + (descent - ascent)

/**
 * 元数据文本块的垂直中心(px):首末行基线中点 + (ascent + descent) / 2。
 * ascent 为负,故中线落在基线中点之上(文本的视觉中线);歌曲图片槽与文本块同心中线。
 * (经典居中式的反向求解:由基线求盒中心是 `+`,由中心求基线才是 `-`。)
 */
internal fun metadataTextCenterY(
    firstBaseline: Float,
    lastBaseline: Float,
    ascent: Float,
    descent: Float
): Float = (firstBaseline + lastBaseline) / 2f + (ascent + descent) / 2f

/**
 * 元数据带几何:带高默认等于文本块高;歌曲图片槽激活时取「块高 vs 槽边长」的大者
 * (与预览歌曲信息 Row 的 CenterVertically 同语义),文本块在带内垂直居中,图片与文本
 * 同心中线,歌词从带沿外让出 [gap] —— 图片高于文本块时既不被内容裁剪框切掉、也不压歌词。
 * [blockHeight] / [bandHeight] <= 0 时分别退化为单行块高 / 等于块高(图片关闭时行为不变)。
 */
internal fun metadataLayoutBounds(
    anchor: String,
    height: Float,
    paddingTop: Float,
    paddingBottom: Float,
    metadataAscent: Float,
    metadataDescent: Float,
    gap: Float,
    blockHeight: Float = 0f,
    bandHeight: Float = 0f
): MetadataLayoutBounds {
    val block = if (blockHeight > 0f) blockHeight else metadataDescent - metadataAscent
    val band = max(block, if (bandHeight > 0f) bandHeight else block)
    return if (anchor == "bottom") {
        val bandBottom = height - paddingBottom
        // 底部锚点时 metadataBaseline 是末行基线(见 metadataLineBaseline)。
        val metadataBaseline = bandBottom - (band - block) / 2f - metadataDescent
        MetadataLayoutBounds(metadataBaseline, paddingTop, bandBottom - band - gap)
    } else {
        val metadataBaseline = paddingTop + (band - block) / 2f - metadataAscent
        MetadataLayoutBounds(metadataBaseline, paddingTop + band + gap, height - paddingBottom)
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

/** 副文本音标行字号(sp):主字号 baseSp 的 0.48 倍。14sp 可读性下限按 baseSp 等比封顶
 *  (min(14, baseSp×0.62)):主行被字号档/LIVE_CARD_SIZE_MULTIPLIER 压小后,下限不得把辅助
 *  形态抬到与主行同大,否则「辅助文字」视觉失效(2026-10-01 真机:0.68 缩放下 aux/main≈0.9)。 */
internal fun secondaryReadingTextSizeSp(baseSp: Float): Float =
    max(min(14f, baseSp * 0.62f), round(baseSp * 0.48f))

/** 副文本翻译行字号(sp):音标行再小 1sp。13sp 下限同样按 baseSp 等比封顶。 */
internal fun secondaryTranslationTextSizeSp(baseSp: Float): Float =
    max(min(13f, baseSp * 0.62f - 1f), round(baseSp * 0.48f) - 1f)

/** 下一行歌词字号(sp):固定 15sp,不随字号档位缩放。 */
internal fun nextLineTextSizeSp(): Float = 15f

/**
 * 元数据小部件的静态高度预算(dp)。基准为历史两行预算;[extraLines] 为超出两行的
 * 切片行数(歌名/歌手/专辑换行分隔符下选满 3 部分时为 1),每行按一行等比高度追加。
 */
internal fun metadataWidgetHeightDp(percent: Int, extraLines: Int = 0): Float =
    22f + 14f * metadataTextSizeMultiplier(percent) * (1 + extraLines.coerceAtLeast(0))

// --- 歌曲图片几何(实机 AodLyricCanvasView 与预览 PreviewComponents 同源) ---

/** 歌曲图片槽边长系数:随歌曲信息字号缩放(1.6 倍字号)。 */
internal const val ARTWORK_SIDE_TEXT_RATIO = 1.6f

/** 歌曲图片与信息文本之间的间距(dp)。 */
internal const val ARTWORK_TEXT_GAP_DP = 6f

/** 圆形封面匀速旋转一圈的时长(ms),实机与预览同源。 */
internal const val ARTWORK_SPIN_PERIOD_MS = 12_000L

/** 歌曲图片槽边长(px):歌曲信息字号 × [ARTWORK_SIDE_TEXT_RATIO],随字号百分比同步缩放。 */
internal fun artworkSidePx(metadataTextSizePx: Float): Float =
    metadataTextSizePx * ARTWORK_SIDE_TEXT_RATIO

/** 歌曲图片+信息文本组的前置宽度(px):图片槽 + 间距,文本块整体右移该值让出左槽。 */
internal fun artworkLeadingPx(metadataTextSizePx: Float, density: Float): Float =
    artworkSidePx(metadataTextSizePx) + ARTWORK_TEXT_GAP_DP * density

/** 圆形封面旋转角(度):按经过时间匀速推进,跨帧连续;非圆形/未开旋转传 0。 */
internal fun artworkSpinDegrees(spin: Boolean, nowElapsedMs: Long): Float {
    if (!spin) return 0f
    val period = ARTWORK_SPIN_PERIOD_MS.toFloat()
    return ((nowElapsedMs % ARTWORK_SPIN_PERIOD_MS) * 360f / period) % 360f
}

/**
 * 旋转生效值(实机节拍门与绘制共用):旋转开关开启即转;音乐暂停驻留期间默认停转
 * (驻留期无逐帧开销),仅 [SurfaceProfile.artworkSpinWhenPaused] 开启的曲面继续旋转。
 */
internal fun artworkSpinEffective(spin: Boolean, spinWhenPaused: Boolean, playbackPaused: Boolean): Boolean =
    spin && (!playbackPaused || spinWhenPaused)

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
