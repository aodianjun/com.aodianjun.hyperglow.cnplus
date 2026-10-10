package com.eza.hyperglow.root.aod

import android.graphics.Canvas
import android.graphics.Paint

/**
 * 长间奏倒计时圆点的绘制适配层（android.graphics；数学见 [InterludeDots] 纯函数）。
 *
 * 逐点绘制：底色圆（未唱色 @[INTERLUDE_DOT_BACKGROUND_ALPHA]，乘本点渐隐系数）+
 * 高亮圆（已唱色，透明度 = 点亮缓动 × 渐隐系数）。圆点簇的左起点由调用方按本面对齐
 * 解析（实机 [com.eza.hyperglow.root.aod.AodLyricCanvasView.alignedStart]、预览同式），
 * 本类只负责在 [startX] 起按 半径×2 + 间距 依次落点。
 */
internal class InterludeDotsRenderer {
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /**
     * @param textSize 主行字号（px）：半径/间距按它取倍率，与主行文字观感一致。
     * @param progress 窗口进度（0..1，[interludeDotsProgress] 产出）。
     * @param startX 圆点簇左起点（px，逻辑帧坐标）。
     * @param centerY 圆点中心 Y（px，取歌词行块垂直中心）。
     * @param backgroundColorArgb 底色（未唱色）；不透明度按本点渐隐系数取
     *   [INTERLUDE_DOT_BACKGROUND_ALPHA] 的缩放。
     * @param highlightColorArgb 高亮色（已唱色）。
     */
    fun draw(
        canvas: Canvas,
        textSize: Float,
        progress: Float,
        startX: Float,
        centerY: Float,
        backgroundColorArgb: Int,
        highlightColorArgb: Int
    ) {
        if (textSize <= 0f) return
        val gap = textSize * INTERLUDE_DOT_GAP_TEXT_SIZE_FACTOR
        backgroundPaint.color = backgroundColorArgb
        highlightPaint.color = highlightColorArgb
        var cursorX = startX
        repeat(INTERLUDE_DOT_COUNT) { index ->
            val exitAlpha = interludeDotExitAlpha(progress, index)
            val lighting = interludeDotLightingProgress(progress, index)
            val radius = textSize * INTERLUDE_DOT_RADIUS_TEXT_SIZE_FACTOR *
                interludeDotScale(lighting)
            val centerX = cursorX + radius
            backgroundPaint.alpha = (INTERLUDE_DOT_BACKGROUND_ALPHA * exitAlpha).toInt()
            if (backgroundPaint.alpha > 0) {
                canvas.drawCircle(centerX, centerY, radius, backgroundPaint)
            }
            val highlightAlpha =
                (MAX_PAINT_ALPHA * interludeDotSmoothStep(lighting) * exitAlpha).toInt()
            if (highlightAlpha > 0) {
                highlightPaint.alpha = highlightAlpha
                canvas.drawCircle(centerX, centerY, radius, highlightPaint)
            }
            cursorX += radius * 2f + gap
        }
    }

    private companion object {
        const val MAX_PAINT_ALPHA = 255f
    }
}
