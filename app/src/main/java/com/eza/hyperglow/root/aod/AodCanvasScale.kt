package com.eza.hyperglow.root.aod

import kotlin.math.max

internal const val LIVE_CARD_SIZE_MULTIPLIER = 0.68f

private const val AOD_DIMMING_BOOST = 1.6f // Sanctioned AOD dimming delta; preserves hardware contrast.

/** 横屏全屏化自适应放缩:期望占据可用高度(0..1)的比例。 */
internal const val FULLSCREEN_FILL_RATIO = 0.85f

/** 横屏全屏化放缩比下限(不缩小,避免单行歌词被抬得过小)。 */
internal const val FULLSCREEN_MIN_SCALE = 1.0f

/** 横屏全屏化放缩比上限(避免单行歌词放得过大溢出屏幕/超出可读观感)。 */
internal const val FULLSCREEN_MAX_SCALE = 1.7f

/**
 * 普通横屏(未全屏化)放缩比上限:beginRotationTransform 在 scale=1 时已把逻辑帧精确
 * 铺满画布(逻辑帧 == 旋转后的画布),任何 >1 的缩放都会把帧内内容推出画布框被裁
 * (issue #61)。要放大必须同步放大画布 rect,即走「横屏全屏」路径。
 */
internal const val LANDSCAPE_FRAME_MAX_SCALE = 1.0f

/**
 * 横屏全屏化的安全边界(纯函数):四周各留出画布短边 [marginPercent]% 的空白,保证自适应
 * 放大铺满后内容不贴屏幕边缘(圆角/挖孔/观感)。非法输入或非正百分比返回 0(不留白)。
 */
internal fun fullscreenSafeInset(shortSide: Float, marginPercent: Float): Float {
    if (!shortSide.isFinite() || shortSide <= 0f || !marginPercent.isFinite() || marginPercent <= 0f) {
        return 0f
    }
    return shortSide * (marginPercent / 100f)
}

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
 * 普通横屏(未开启「横屏全屏」)的放缩比(纯函数,issue #61):逻辑帧尺寸 == 旋转后的
 * 画布尺寸,scale=1 已精确铺满;用户倍数 [userScale] 与 fit 系数 [fitScale] 只决定
 * 「是否需要缩小」,上限恒为 [LANDSCAPE_FRAME_MAX_SCALE] —— 任何 >1 的缩放都会在
 * 已铺满的基座上再放大,帧内内容必然溢出画布框被裁。下限沿用 FULLSCREEN_MIN_SCALE
 * (不缩小),故结果恒为 1.0;两个输入保留用于诊断留痕。
 */
internal fun landscapeFrameFitScale(userScale: Float, fitScale: Float): Float =
    minOf(userScale, fitScale).coerceIn(FULLSCREEN_MIN_SCALE, LANDSCAPE_FRAME_MAX_SCALE)

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
 * 横屏内容安全区(纯函数):在逻辑帧高 [oh] 内扣掉画布内边距 [padTop]/[padBottom] 后,
 * 再上下各让出 [safeInset],得到内容块锚定与自适应缩放共用的垂直区间
 * [LandscapeSafeRegion.top, +height]。上下让出量相同 ⇒ 区间中心恒等于逻辑帧中心 oh/2,
 * 这是「横屏全屏化后歌词在正中间」的几何前提;安全边界非正/非法时退化为无让出。
 */
internal data class LandscapeSafeRegion(val top: Float, val height: Float)

internal fun landscapeSafeRegion(
    oh: Int,
    padTop: Int,
    padBottom: Int,
    safeInset: Float
): LandscapeSafeRegion {
    val inset = if (safeInset.isFinite() && safeInset > 0f) safeInset else 0f
    val height = ((oh - padTop - padBottom).toFloat() - inset * 2f).coerceAtLeast(0f)
    return LandscapeSafeRegion(padTop + inset, height)
}

/**
 * 横屏全屏的垂直居中偏移:把垂直占 [blockTop, blockTop + blockHeight] 的内容块,
 * 在可用高度 [regionTop, regionTop + availableHeight] 内整体居中。返回需叠加到每行 baseline 的偏移;
 * 内容块高于可用区间时不缩小、也不再上移(保持原顶部,避免裁切)。
 */
internal fun fullscreenBlockCenterOffset(
    blockTop: Float,
    blockHeight: Float,
    availableHeight: Float,
    regionTop: Float
): Float = max(0f, (availableHeight - blockHeight) / 2f) - (blockTop - regionTop)

/**
 * 横屏内容块的锚定偏移(纯函数,issue #63):把垂直占 [blockTop, blockTop + blockHeight] 的内容块,
 * 在可用区间 [regionTop, regionTop + availableHeight] 内按 [anchor] 整体摆放
 * (0=顶、0.5=居中、1=底)。anchor=0.5 时与 [fullscreenBlockCenterOffset] 逐点等价;
 * 内容块高于可用区间时不缩小、也不再上移(保持原顶部,避免裁切)。
 *
 * [regionTop]/[availableHeight] 必须取自同一个安全区([landscapeSafeRegion])——只把安全边界从
 * 高度里减掉、区间起点却仍取画布内边距时,整块会偏向一侧 safeInset 像素(全屏化再经 scale
 * 放大该偏移,现场即「歌词不在正中间」)。
 */
internal fun landscapeBlockAnchorOffset(
    blockTop: Float,
    blockHeight: Float,
    availableHeight: Float,
    regionTop: Float,
    anchor: Float
): Float = max(0f, availableHeight - blockHeight) * anchor.coerceIn(0f, 1f) - (blockTop - regionTop)

internal fun steadyTextAlpha(factor: Float): Float = if (factor < 0.5f) {
    max(0.35f * AOD_DIMMING_BOOST, 0.55f)
} else {
    minOf(1f, 0.85f * AOD_DIMMING_BOOST)
}

internal fun staticSecondaryTextFactor(bright: Boolean): Float = if (bright) 1f else 0.35f

/** Dimmed preview alpha for the upcoming next lyric line(与预览 0.45 alpha 对齐). */
internal fun staticNextLineTextFactor(): Float = 0.45f
