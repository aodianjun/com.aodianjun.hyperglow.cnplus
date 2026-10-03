package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.ARTWORK_SIZE_DEFAULT_DP
import com.eza.hyperglow.customization.normalizeArtworkSizeDp
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
 * [artworkHeightDp] 为歌曲图片槽边长(dp,0=无图片):图片与文本块同高入账,预算不低于
 * 图片槽 + [ARTWORK_WIDGET_VERTICAL_PADDING_DP],否则大尺寸自定义图片会被小部件裁切。
 */
internal fun metadataWidgetHeightDp(
    percent: Int,
    extraLines: Int = 0,
    artworkHeightDp: Float = 0f
): Float {
    val textBudget =
        22f + 14f * metadataTextSizeMultiplier(percent) * (1 + extraLines.coerceAtLeast(0))
    return if (artworkHeightDp > 0f) {
        max(textBudget, artworkHeightDp + ARTWORK_WIDGET_VERTICAL_PADDING_DP)
    } else {
        textBudget
    }
}

// --- 歌曲图片几何(实机 AodLyricCanvasView 与预览 PreviewComponents 同源) ---

/** 歌曲图片槽边长系数:随歌曲信息字号缩放(1.6 倍字号)。 */
internal const val ARTWORK_SIDE_TEXT_RATIO = 1.6f

/** 歌曲图片与信息文本之间的间距(dp)。 */
internal const val ARTWORK_TEXT_GAP_DP = 6f

/** 歌曲图片与元数据组件上下边距的预留(dp):静态高度预算按图片槽边长追加该余量。 */
internal const val ARTWORK_WIDGET_VERTICAL_PADDING_DP = 8f

/** 圆形封面匀速旋转一圈的时长(ms),实机与预览同源。 */
internal const val ARTWORK_SPIN_PERIOD_MS = 12_000L

/**
 * 歌曲图片槽边长(px):[adaptiveScale] 开启(默认)时随歌曲信息字号等比缩放
 * (字号 × [ARTWORK_SIDE_TEXT_RATIO],历史行为);关闭时取固定自定义边长
 * [customSizeDp](dp × [density]),不随字号变化。
 */
internal fun artworkSidePx(
    metadataTextSizePx: Float,
    density: Float = 1f,
    adaptiveScale: Boolean = true,
    customSizeDp: Int = ARTWORK_SIZE_DEFAULT_DP
): Float = if (adaptiveScale) {
    metadataTextSizePx * ARTWORK_SIDE_TEXT_RATIO
} else {
    normalizeArtworkSizeDp(customSizeDp) * density
}

/** 歌曲图片+信息文本组的前置宽度(px):图片槽 + 间距,文本块整体右移该值让出左槽。 */
internal fun artworkLeadingPx(
    metadataTextSizePx: Float,
    density: Float = 1f,
    adaptiveScale: Boolean = true,
    customSizeDp: Int = ARTWORK_SIZE_DEFAULT_DP
): Float = artworkSidePx(metadataTextSizePx, density, adaptiveScale, customSizeDp) +
    ARTWORK_TEXT_GAP_DP * density

/**
 * 歌曲图片槽边长(dp)估算:静态高度预算用,与 [artworkSidePx] 同源。
 * 自适应时 = 歌曲信息字号(sp) × [ARTWORK_SIDE_TEXT_RATIO] × 字体缩放;自定义时取固定边长。
 */
internal fun artworkSideDp(
    metadataSizePercent: Int,
    fontScale: Float,
    adaptiveScale: Boolean,
    customSizeDp: Int
): Float = if (adaptiveScale) {
    metadataTextSizeSp(metadataSizePercent) * ARTWORK_SIDE_TEXT_RATIO * fontScale.coerceIn(0.5f, 2f)
} else {
    normalizeArtworkSizeDp(customSizeDp).toFloat()
}

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

/**
 * 逐字动画档归一化(画布内部副本)。**必须与 aod/AodRenderPreferences 的同名函数逐值一致**:
 * 画布(package com.eza.hyperglow.root.aod)调用时按同包解析到本函数,不会走 aod 包版本——
 * 本副本曾只认 Minimal(其余一律回落 Gradient),导致 BetterLyrics 档「配置/快照/映射全链
 * 正确、画布渲染恒 Gradient」(表现即「设置只在预览生效、实机不变」;预览不走画布故正常)。
 * 新增动画档时两处必须同步(单测 AodCanvasTextMetricsTest 断言二者一致防漂移)。
 */
internal fun normalizeAodAnimation(mode: String): String = when (mode) {
    "Minimal" -> "Minimal"
    "BetterLyrics" -> "BetterLyrics"
    else -> "Gradient"
}

/**
 * 内容自然堆叠高度(AodLyricCanvasView.measureContentStack 与锁屏卡片自适应高度同源):
 * 画布上下 padding + 各行(行高 + 行前距)之和 + 元数据行与歌词之间的间距
 * ([metadataGapPx];无元数据行时传 0)。与 AodLyricCanvasView.positionRows 的顶锚排版
 * 同式:元数据多行时行高公式已含多出行高(positionRows 的 metadataExtraHeight 避让
 * 与 rowWithLines 的 height = n * lineHeight 同账),无需另补。
 *
 * [padTopPx]/[padBottomPx] 传「画布内边距 + 效果余量」(见 [canvasEffectEdgeNeeds]):
 * 与 positionRows 同一取值,卡片高度才与内容放置同步长高。
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

/**
 * 画布上下「效果余量」(纯函数):歌词绘制会越出行盒的外扩量 —— 辉光光晕半径
 * (字号 × [LyricGlowRenderer.HALO_RADIUS_FRACTION],与光晕裁剪矩形 [haloClipRect]
 * 同一取值)与「BetterLyrics」档未唱字下沉量(行高 × [KARAOKE_FLOAT_SINK_FRACTION])。
 *
 * 内容块顶/底贴住内容裁剪框时,这部分外扩会被切平(现场即「歌词刚好叠到画布边缘被裁切」:
 * 竖屏画布上下内边距为 0,锁屏卡片又按实测内容定高,块沿与裁剪沿必然重合)。故内容块两端
 * 要按 [canvasEffectEdgeNeeds] 让出余量:放置内缩、自适应卡片同步长高。两项效果都关闭时
 * 无外扩,返回 0(既有布局逐像素不变)。
 */
internal fun canvasEffectAllowancePx(
    textSizePx: Float,
    lineHeightPx: Float,
    glowEnabled: Boolean,
    floatSinkActive: Boolean
): Float {
    val halo = if (glowEnabled && textSizePx.isFinite() && textSizePx > 0f) {
        textSizePx * LyricGlowRenderer.HALO_RADIUS_FRACTION
    } else {
        0f
    }
    val sink = if (floatSinkActive && lineHeightPx.isFinite() && lineHeightPx > 0f) {
        karaokeFloatSinkPx(lineHeightPx)
    } else {
        0f
    }
    return max(halo, sink)
}

/** 内容块上下要补的效果余量(px):顶部 [topPx]、底部 [bottomPx]。 */
internal data class CanvasEffectEdgeNeeds(val topPx: Float, val bottomPx: Float)

/**
 * 内容块两端要补的效果余量(纯函数)。只有会画出外扩的行(主行、带行窗的辅助行)才需要,
 * 且两端既有留白已经提供的部分不重复计入:
 *  - 顶部:首行行前距([topRowGapBeforePx])就是块沿到裁剪沿的现成间距,只补差额 ——
 *    默认字号下 8dp 行前距已覆盖 6.8dp 光晕半径,内容位置零变化;
 *  - 底部:块尾(末行盒底)与裁剪沿之间没有留白,外扩量全额计入。
 * 放置(AodLyricCanvasView.positionRows)与自适应卡片高度测量(measureContentStack)
 * 取同一结果,卡片长高与内容内缩才同步。
 */
internal fun canvasEffectEdgeNeeds(
    topRowOverdrawPx: Float,
    topRowGapBeforePx: Float,
    bottomRowOverdrawPx: Float
): CanvasEffectEdgeNeeds {
    val top = if (topRowOverdrawPx.isFinite()) topRowOverdrawPx else 0f
    val gap = if (topRowGapBeforePx.isFinite()) topRowGapBeforePx else 0f
    val bottom = if (bottomRowOverdrawPx.isFinite()) bottomRowOverdrawPx else 0f
    return CanvasEffectEdgeNeeds(
        topPx = max(0f, top - max(0f, gap)),
        bottomPx = max(0f, bottom)
    )
}

/**
 * 「安全栅栏」自检结果(纯函数,见 [effectClipCheckPx]):两个越界量,均为 0 = 内容块与
 * 其绘制外扩都落在内容裁剪框内。
 */
internal data class CanvasEffectClipCheck(
    /** 行盒越界量(px):内容本身放不下(行数/字号/高度上限),既有路径由裁剪兜底。 */
    val rowOverflowPx: Float,
    /** 绘制外扩越界量(px):行盒在框内、但辉光/下沉会越界被切平 —— 效果余量算漏了。 */
    val effectOverflowPx: Float
) {
    val clean: Boolean get() = rowOverflowPx <= 0f && effectOverflowPx <= 0f
}

/**
 * 自检容差(px):余量算准的形状恰好相切,浮点累积误差会留下 ~1e-5 的残差;亚像素差异
 * 也不构成可见裁切(与横屏越界自检的 1px 容差同量级)。容差内视为在框内。
 */
internal const val EFFECT_CLIP_CHECK_TOLERANCE_PX = 1f

/**
 * 「安全栅栏」自检(纯函数):把最终摆放的内容块与内容裁剪框对一遍,分别给出**行盒**
 * 与**绘制外扩**的越界量(超过 [EFFECT_CLIP_CHECK_TOLERANCE_PX] 才算越界,否则为 0)。
 *
 * 修法([canvasEffectEdgeNeeds])保证已知形状(主行/辅助行的辉光与下沉)恒为 clean ——
 * 本函数的价值在**未知形状**:新增效果或新增行种类若没同步进余量,块沿会重新贴住裁剪沿,
 * 此时行盒仍在框内、只有外扩越界(即 [effectOverflowPx] > 0),现场以 W 级日志留痕而不是
 * 静默被切平。行盒越界是「内容放不下」,与余量无关,单独报以免误判。
 * 输入含非有限值(未布局/未测量)时视为 clean,不产生噪声。
 */
internal fun effectClipCheckPx(
    blockTopPx: Float,
    blockBottomPx: Float,
    topOverdrawPx: Float,
    bottomOverdrawPx: Float,
    clipTopPx: Float,
    clipBottomPx: Float
): CanvasEffectClipCheck {
    if (!blockTopPx.isFinite() || !blockBottomPx.isFinite() || !topOverdrawPx.isFinite() ||
        !bottomOverdrawPx.isFinite() || !clipTopPx.isFinite() || !clipBottomPx.isFinite()
    ) {
        return CanvasEffectClipCheck(0f, 0f)
    }
    val rowOverflow = max(
        (clipTopPx - blockTopPx).coerceAtLeast(0f),
        (blockBottomPx - clipBottomPx).coerceAtLeast(0f)
    )
    if (rowOverflow > EFFECT_CLIP_CHECK_TOLERANCE_PX) return CanvasEffectClipCheck(rowOverflow, 0f)
    val effectOverflow = max(
        (clipTopPx - (blockTopPx - max(0f, topOverdrawPx))).coerceAtLeast(0f),
        ((blockBottomPx + max(0f, bottomOverdrawPx)) - clipBottomPx).coerceAtLeast(0f)
    )
    return CanvasEffectClipCheck(
        0f,
        if (effectOverflow > EFFECT_CLIP_CHECK_TOLERANCE_PX) effectOverflow else 0f
    )
}
