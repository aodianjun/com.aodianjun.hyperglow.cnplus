package com.eza.hyperglow.root.aod

import com.eza.hyperglow.root.surface.PlacementRect
import kotlin.math.roundToInt

internal data class AodSurfaceRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 横屏(非全屏)画布 rect 的宽高交换(纯函数):以 rect 中心为锚交换宽与高,再整体钳进
 * [rootWidth]×[rootHeight]。竖屏放置算法给出的歌词 rect 通常「宽≥高」(贴在时钟下方);
 * 横屏逻辑帧 ow=视图高、oh=视图宽,旋转 90° 后用户在横持视角看到的画布反而「高>宽」,
 * 歌词行在视觉横向上变得很短。交换后画布在用户视角呈横宽形,行布局空间与横屏语义一致。
 * 中心锚定保证交换前后画布覆盖同一块屏幕区域(仍避开时钟所在一侧),钳制兜底极端尺寸。
 */
internal fun swapAodSurfaceRectForLandscape(
    rect: AodSurfaceRect,
    rootWidth: Int,
    rootHeight: Int
): AodSurfaceRect {
    val newWidth = rect.height.coerceIn(0, rootWidth)
    val newHeight = rect.width.coerceIn(0, rootHeight)
    val centerX = (rect.left + rect.right) / 2
    val centerY = (rect.top + rect.bottom) / 2
    val left = (centerX - newWidth / 2).coerceIn(0, (rootWidth - newWidth).coerceAtLeast(0))
    val top = (centerY - newHeight / 2).coerceIn(0, (rootHeight - newHeight).coerceAtLeast(0))
    return AodSurfaceRect(left, top, left + newWidth, top + newHeight)
}

internal fun calculateAodSurfaceRect(
    rootWidth: Int,
    rootHeight: Int,
    stockBottom: Int,
    margin: Int,
    desiredWidth: Int,
    desiredHeight: Int,
    translationX: Int = 0,
    safeBottom: Int? = null,
    anchor: String = "below_stock_clock",
    verticalBias: Float = 0.5f
): AodSurfaceRect {
    val boundedWidth = desiredWidth.coerceIn(0, rootWidth.coerceAtLeast(0))
    val maxLeft = (rootWidth - boundedWidth).coerceAtLeast(0)
    val left = ((rootWidth - boundedWidth) / 2 + translationX).coerceIn(0, maxLeft)
    val visibleBottom = (minOf(rootHeight, safeBottom ?: rootHeight) - margin).coerceAtLeast(0)
    val safeTop = (stockBottom + margin).coerceIn(0, visibleBottom)
    val height = desiredHeight.coerceIn(0, visibleBottom - safeTop)
    val top = when (anchor) {
        "screen_center" -> safeTop + (visibleBottom - safeTop - height) / 2
        "screen_bottom_safe" -> visibleBottom - height
        "custom_vertical_bias" -> safeTop +
            ((visibleBottom - safeTop - height) * verticalBias.coerceIn(0f, 1f)).roundToInt()
        else -> safeTop
    }
    return AodSurfaceRect(left, top, left + boundedWidth, top + height)
}

internal fun stockBottomInRoot(rootWindowY: Int, childWindowY: Int, childHeight: Int): Int =
    childWindowY - rootWindowY + childHeight

internal fun hasUsableAodRootSize(width: Int, height: Int): Boolean = width > 0 && height > 0

internal fun aodSceneSafeCanvas(
    rootWidth: Int,
    rootHeight: Int,
    clockTop: Int,
    lyricTopSafe: Int,
    margin: Int,
    zone: AodSceneZone
): PlacementRect = if (zone == AodSceneZone.CLOCK_BOTTOM) {
    val top = lyricTopSafe.coerceIn(0, rootHeight)
    val bottom = (clockTop - margin).coerceIn(top, rootHeight)
    PlacementRect(0f, top.toFloat(), rootWidth.toFloat(), bottom.toFloat())
} else {
    PlacementRect(0f, 0f, rootWidth.toFloat(), rootHeight.toFloat())
}

internal fun aodPlacementMaxHeightFraction(
    configuredFraction: Float,
    zone: AodSceneZone
): Float = if (zone == AodSceneZone.CLOCK_BOTTOM) 1f else configuredFraction
