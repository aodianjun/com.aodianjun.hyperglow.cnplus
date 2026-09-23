package com.eza.hyperglow.root.aod

import android.graphics.Canvas

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

    /** 完整落在视口内：内容无裁切（「务必完整可见」的通过条件，issue #61）。 */
    fun fitsWithin(viewWidth: Int, viewHeight: Int): Boolean =
        minX >= 0f && minY >= 0f && maxX <= viewWidth.toFloat() && maxY <= viewHeight.toFloat()
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
