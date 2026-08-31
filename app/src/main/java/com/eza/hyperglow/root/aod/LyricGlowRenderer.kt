package com.eza.hyperglow.root.aod

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader

/**
 * 歌词扫光/发光的共享渲染核心 —— 预览(PreviewAnimatedLyric)与实机
 * (AodLyricCanvasView,同时服务 AOD 与锁屏两个表面)调用同一实现,
 * 一致性由构造保证:光带占比、缓动曲线、光晕半径、dim 底透明度、渐变 stops
 * 与三层绘制顺序只在此定义一次,彻底消除"两份手工同步的拷贝"造成的漂移。
 *
 * 三层绘制(整块进度按行宽加权分摊到各行,splitContinuousFill):
 * Pass 1 未唱 dim 底 —— sung 色 30% 不透明度整块绘制;
 * Pass 2 光晕 —— clip 到各行已扫区域,sung 色文字 + glow 色阴影,光从文字背后透出;
 * Pass 3 扫光亮部 —— 每行 LinearGradient[sung→sung→白色峰值高光→dim 拖尾→transparent,
 *          CLAMP],band 左侧常亮(已唱),光锋带白色峰值高亮,后缘长拖尾,行首/行尾
 *          easeInOut 减速。
 */
internal class LyricGlowRow(
    val left: Float,
    val width: Float,
    val baseline: Float,
    val drawText: (Canvas, Paint) -> Unit
)

internal object LyricGlowRenderer {
    /** 扫光带宽度占行宽比例(相对含 band 余量的总推进距离)。 */
    const val SWEEP_BAND_FRACTION = 0.28f

    /** 光晕阴影半径占字号比例。 */
    const val HALO_RADIUS_FRACTION = 0.36f

    /** 未唱 dim 底不透明度(sung 色自身 alpha 通道)。 */
    const val DIM_BASE_ALPHA = (255 * 0.30f).toInt()

    /** Pass 3 扫光拖尾(渐隐前缘)的过渡不透明度。 */
    private const val SWEEP_TAIL_ALPHA = 150

    /** 5 段渐变 stop 位置:常亮区→高光峰→拖尾→渐隐。 */
    private val SWEEP_STOPS = floatArrayOf(0f, 0.30f, 0.48f, 0.72f, 1f)

    fun draw(
        canvas: Canvas,
        paint: Paint,
        rows: List<LyricGlowRow>,
        progress: Float,
        sungColor: Int,
        glowColor: Int,
        glowEnabled: Boolean
    ) {
        if (rows.isEmpty()) return
        val fm = paint.fontMetrics
        val haloRadius = paint.textSize * HALO_RADIUS_FRACTION
        // 行首/行尾 easeInOut 减速:与预览演示的扫光节奏一致。
        val rowProgress = splitContinuousFill(
            easeInOutCubic(progress.coerceIn(0f, 1f)),
            rows.map { it.width }
        )

        // Pass 1: 未唱 dim 底 —— sung 色 30% 不透明度整块绘制(alpha 内嵌于 color)
        val dimColor = Color.argb(
            DIM_BASE_ALPHA,
            Color.red(sungColor),
            Color.green(sungColor),
            Color.blue(sungColor)
        )
        paint.shader = null
        paint.setShadowLayer(0f, 0f, 0f, 0)
        paint.color = dimColor
        rows.forEach { it.drawText(canvas, paint) }

        // Pass 2: 光晕层 —— clip 到该行已扫区域,sung 文字 + glow 色阴影
        if (glowEnabled) {
            rows.forEachIndexed { index, row ->
                val lp = rowProgress[index]
                if (lp <= 0f) return@forEachIndexed
                val clip = haloClipRect(row, lp, fm.ascent, fm.descent, haloRadius)
                canvas.save()
                canvas.clipRect(
                    clip.left,
                    clip.top,
                    clip.right,
                    clip.bottom
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

        // Pass 3: 扫光亮部 —— band 左侧 CLAMP 常亮,白色峰值高光,后缘长拖尾渐隐
        val red = Color.red(sungColor)
        val green = Color.green(sungColor)
        val blue = Color.blue(sungColor)
        val transparent = Color.argb(0, red, green, blue)
        val tail = Color.argb(SWEEP_TAIL_ALPHA, red, green, blue)
        val peak = Color.argb(255, (red + 255 * 3) / 4, (green + 255 * 3) / 4, (blue + 255 * 3) / 4)
        rows.forEachIndexed { index, row ->
            val lp = rowProgress[index]
            if (lp <= 0f) return@forEachIndexed
            val sweepStart = sweepGradientStart(row.left, row.width, lp)
            val sweepEnd = sweepStart + sweepBandWidth(row.width)
            paint.shader = LinearGradient(
                sweepStart, 0f, sweepEnd, 0f,
                intArrayOf(sungColor, sungColor, peak, tail, transparent),
                SWEEP_STOPS,
                Shader.TileMode.CLAMP
            )
            paint.color = sungColor
            paint.alpha = 255
            row.drawText(canvas, paint)
            paint.shader = null
        }
    }
}

/** easeInOut 三次缓动(与预览演示扫光同曲线):两端减速,中段加速。纯函数,可单测。 */
internal fun easeInOutCubic(p: Float): Float = if (p < 0.5f) {
    4f * p * p * p
} else {
    val v = -2f * p + 2f
    1f - v * v * v / 2f
}

/** 扫光几何纯函数的矩形返回值（四边浮点，避免在纯 JVM 单测中触碰 android.graphics.RectF）。 */
internal data class HaloClipBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
)

/** 扫光带宽度：行宽 × 占比，下限 1px 保证极窄行也有可感知的渐变过渡。 */
internal fun sweepBandWidth(rowWidth: Float): Float =
    (rowWidth * LyricGlowRenderer.SWEEP_BAND_FRACTION).coerceAtLeast(1f)

/** 扫光渐变起点：band 从行左外沿起推进 (rowWidth + band) × lp，光锋落在已扫区右缘。 */
internal fun sweepGradientStart(rowLeft: Float, rowWidth: Float, rowProgress: Float): Float {
    val band = sweepBandWidth(rowWidth)
    return rowLeft - band + (rowWidth + band) * rowProgress
}

/** Pass 2 光晕裁剪矩形：已扫前缀（右侧到 left+(width+band)×lp）上下各外扩 haloRadius。 */
internal fun haloClipRect(
    row: LyricGlowRow,
    rowProgress: Float,
    ascent: Float,
    descent: Float,
    haloRadius: Float
): HaloClipBounds {
    val band = sweepBandWidth(row.width)
    return HaloClipBounds(
        left = row.left - band,
        top = row.baseline + ascent - haloRadius,
        right = row.left + (row.width + band) * rowProgress,
        bottom = row.baseline + descent + haloRadius
    )
}
