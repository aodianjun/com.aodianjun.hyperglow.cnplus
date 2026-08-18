package com.eza.hyperglow.root.aod

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

/**
 * 歌词扫光/发光的共享渲染核心。
 * 预览(PreviewAnimatedLyric)与实机 AOD(AodLyricCanvasView.drawOriginal)调用同一实现,
 * 一致性由构造保证:光带占比、光晕半径、dim 底透明度与三层绘制顺序只在此定义一次,
 * 彻底消除此前"两份手工同步的拷贝"造成的预览/实机效果漂移。
 *
 * 三层绘制(整块进度按行宽加权分摊到各行,splitContinuousFill):
 * Pass 1 未唱 dim 底 —— 基色以 30% 不透明度整块绘制;
 * Pass 2 光晕 —— clip 到各行已扫区域,sung 色文字 + glow 色阴影,光从文字背后透出;
 * Pass 3 扫光亮部 —— 每行 LinearGradient[sung→middle→transparent, CLAMP],
 *          band 左侧常亮(已唱),光锋柔和过渡,右侧未唱区保持 dim 底。
 */
internal class LyricGlowRow(
    val left: Float,
    val width: Float,
    val baseline: Float,
    val drawText: (Canvas, Paint) -> Unit
)

internal object LyricGlowRenderer {
    /** 扫光带宽度占行宽比例(相对含 band 余量的总推进距离)。 */
    const val SWEEP_BAND_FRACTION = 0.4f

    /** 光晕阴影半径占字号比例。 */
    const val HALO_RADIUS_FRACTION = 0.36f

    /** 未唱 dim 底不透明度。 */
    const val UNSUNG_BASE_ALPHA = (255 * 0.30f).toInt()

    private const val SWEEP_MIDDLE_ALPHA = 184
    private const val SWEEP_MIDDLE_STOP = 0.45f

    fun draw(
        canvas: Canvas,
        paint: Paint,
        rows: List<LyricGlowRow>,
        progress: Float,
        sungColor: Int,
        dimBaseColor: Int,
        glowColor: Int,
        glowEnabled: Boolean
    ) {
        if (rows.isEmpty()) return
        val fm = paint.fontMetrics
        val haloRadius = paint.textSize * HALO_RADIUS_FRACTION
        val rowProgress = splitContinuousFill(progress, rows.map { it.width })

        // Pass 1: 未唱 dim 底 —— 整块绘制
        paint.shader = null
        paint.setShadowLayer(0f, 0f, 0f, 0)
        paint.color = dimBaseColor
        paint.alpha = UNSUNG_BASE_ALPHA
        rows.forEach { it.drawText(canvas, paint) }

        // Pass 2: 光晕层 —— clip 到该行已扫区域,sung 文字 + glow 色阴影
        if (glowEnabled) {
            rows.forEachIndexed { index, row ->
                val lp = rowProgress[index]
                if (lp <= 0f) return@forEachIndexed
                val band = (row.width * SWEEP_BAND_FRACTION).coerceAtLeast(1f)
                canvas.save()
                canvas.clipRect(
                    row.left - band,
                    row.baseline + fm.ascent - haloRadius,
                    row.left + (row.width + band) * lp,
                    row.baseline + fm.descent + haloRadius
                )
                paint.shader = null
                paint.color = sungColor
                paint.alpha = 255
                paint.setShadowLayer(paint.textSize * HALO_RADIUS_FRACTION, 0f, 0f, glowColor)
                row.drawText(canvas, paint)
                paint.setShadowLayer(0f, 0f, 0f, 0)
                canvas.restore()
            }
        }

        // Pass 3: 扫光亮部 —— band 左侧 CLAMP 常亮,右侧渐隐
        val transparent = Color.argb(0, Color.red(sungColor), Color.green(sungColor), Color.blue(sungColor))
        val middle = Color.argb(
            SWEEP_MIDDLE_ALPHA, Color.red(sungColor), Color.green(sungColor), Color.blue(sungColor)
        )
        rows.forEachIndexed { index, row ->
            val lp = rowProgress[index]
            if (lp <= 0f) return@forEachIndexed
            val band = (row.width * SWEEP_BAND_FRACTION).coerceAtLeast(1f)
            val sweepStart = row.left - band + (row.width + band) * lp
            val sweepEnd = sweepStart + band
            paint.shader = LinearGradient(
                sweepStart, 0f, sweepEnd, 0f,
                intArrayOf(sungColor, middle, transparent),
                floatArrayOf(0f, SWEEP_MIDDLE_STOP, 1f),
                Shader.TileMode.CLAMP
            )
            paint.color = sungColor
            paint.alpha = 255
            row.drawText(canvas, paint)
            paint.shader = null
        }
    }
}
