package com.eza.hyperglow.root.aod

import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.roundToInt

/**
 * AMLL 样式倒计时圆点的 Canvas 适配层。
 * 演出编排由 [AmllInterludeTimeline] 决定；单色绘制（取已唱色），
 * 每个圆点的不透明度 = 快照 dotOpacity × 颜色自带 alpha。
 */
internal class AmllInterludeDotsRenderer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(
        canvas: Canvas,
        textSize: Float,
        snapshot: AmllDotsSnapshot,
        startX: Float,
        centerY: Float,
        colorArgb: Int
    ) {
        if (textSize <= 0f || !snapshot.isVisible) return
        paint.color = colorArgb
        val baseColorAlpha = scaledAlpha(colorArgb, 1f)
        repeat(AmllInterludeTimeline.DOT_COUNT) { index ->
            val opacity = snapshot.dotOpacity(index)
            if (opacity <= 0f) return@repeat
            val centerX = startX +
                AmllInterludeTimeline.dotCenterXEm(index, snapshot.scale) * textSize
            val radius = AmllInterludeTimeline.dotRadiusEm(snapshot.scale) * textSize
            paint.alpha = (baseColorAlpha * opacity).roundToInt().coerceIn(0, 255)
            canvas.drawCircle(centerX, centerY, radius, paint)
        }
    }
}
