package com.eza.hyperglow.root.aod

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.eza.hyperglow.effect.EffectColors
import com.eza.hyperglow.effect.EffectRenderSink
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * [EffectRenderSink] 的 Android 实现:把沙箱效果程序的受控绘制原语映射到 [Canvas] + [Paint]。
 *
 * 文字使用调用方 Paint 的**副本**(保留字体/字距等测量态),只覆盖字号/颜色/透明度/对齐,
 * 并默认清空 shader/阴影层——避免效果程序的文字继承上一条 DSL 绘制留下的扫光/辉光状态,
 * 也避免程序改字号后污染调用方后续行的测量。程序显式调用 `setGradient`/`setShadow` 时
 * 才保留对应状态。
 *
 * 绘制状态(颜色/透明度/字号/渐变/阴影/描边)随指令线性推进,不跨帧保留;save 深度、
 * 路径点数有硬上限,防止恶意/错误程序耗尽 native 栈或内存。仅绘制线程(主线程)使用。
 */
internal class AndroidEffectRenderSink(
    private val canvas: Canvas,
    paint: Paint,
    colors: EffectColors
) : EffectRenderSink {
    /** 文字 Paint 副本:效果程序不能改写调用方的测量态。 */
    private val textPaint = Paint(paint)

    /** 图元画笔:矩形/圆/路径默认 FILL、线段默认 STROKE,共用同一实例。 */
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 路径累积器:pathMove/pathLine/pathQuad/pathCubic/pathClose 写入,drawPath 消费后复位。 */
    private val path = Path()

    /** VM 开始前的 Canvas 保存深度;restore 不得越过它。 */
    private val baseSaveCount = canvas.saveCount

    private var pathPoints = 0
    private var color = colors.unsung
    private var alpha = 1f
    private var textSize = textPaint.textSize.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
    private var textAlign = Paint.Align.LEFT
    private var strokeWidth = 1f
    private var shapeStyle = Paint.Style.FILL
    private var gradient: Shader? = null
    private var shadowRadius = 0f
    private var shadowDx = 0f
    private var shadowDy = 0f
    private var shadowColor = 0
    private var shadowSet = false

    override fun save() {
        if (canvas.saveCount - baseSaveCount < MAX_SAVE_DEPTH) {
            canvas.save()
        }
    }

    override fun restore() {
        if (canvas.saveCount > baseSaveCount) {
            canvas.restore()
        }
    }

    /** VM 结束后由宿主调用:把程序留下的未配对 save 全部弹掉。 */
    fun restoreAll() {
        while (canvas.saveCount > baseSaveCount) {
            canvas.restore()
        }
    }

    override fun translate(x: Float, y: Float) {
        canvas.translate(x, y)
    }

    override fun rotate(deg: Float) {
        canvas.rotate(deg)
    }

    override fun scale(sx: Float, sy: Float) {
        canvas.scale(sx, sy)
    }

    override fun setColor(argb: Int) {
        color = argb
    }

    override fun setAlpha(alpha: Float) {
        this.alpha = alpha.coerceIn(0f, 1f)
    }

    override fun setTextSize(px: Float) {
        textSize = px.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
    }

    override fun setTextAlign(align: Int) {
        textAlign = when (align) {
            1 -> Paint.Align.CENTER
            2 -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
    }

    override fun setGradient(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        color0: Int,
        color1: Int
    ) {
        gradient = runCatching {
            LinearGradient(x0, y0, x1, y1, color0, color1, Shader.TileMode.CLAMP)
        }.getOrNull()
    }

    override fun clearGradient() {
        gradient = null
    }

    override fun setShadow(radius: Float, dx: Float, dy: Float, color: Int) {
        shadowRadius = radius.coerceIn(0f, 256f)
        shadowDx = dx.coerceIn(-256f, 256f)
        shadowDy = dy.coerceIn(-256f, 256f)
        shadowColor = color
        shadowSet = true
    }

    override fun clearShadow() {
        shadowSet = false
    }

    override fun setStrokeWidth(width: Float) {
        strokeWidth = width.coerceIn(0f, 128f)
    }

    override fun setFill() {
        shapeStyle = Paint.Style.FILL
    }

    override fun setStroke() {
        shapeStyle = Paint.Style.STROKE
    }

    override fun drawText(text: String, x: Float, y: Float) {
        textPaint.textSize = textSize
        textPaint.color = color
        textPaint.alpha = alphaInt()
        textPaint.textAlign = textAlign
        textPaint.shader = gradient
        if (shadowSet) {
            textPaint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        } else {
            textPaint.clearShadowLayer()
        }
        canvas.drawText(text, x, y, textPaint)
    }

    override fun drawRect(x: Float, y: Float, w: Float, h: Float) {
        applyShapePaint()
        canvas.drawRect(x, y, x + w.coerceAtLeast(0f), y + h.coerceAtLeast(0f), shapePaint)
    }

    override fun drawCircle(cx: Float, cy: Float, r: Float) {
        applyShapePaint()
        canvas.drawCircle(cx, cy, r.coerceAtLeast(0f), shapePaint)
    }

    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        shapeStyle = Paint.Style.STROKE
        applyShapePaint()
        canvas.drawLine(x1, y1, x2, y2, shapePaint)
    }

    override fun drawRoundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float) {
        applyShapePaint()
        canvas.drawRoundRect(
            x,
            y,
            x + w.coerceAtLeast(0f),
            y + h.coerceAtLeast(0f),
            rx,
            ry,
            shapePaint
        )
    }

    override fun drawOval(x: Float, y: Float, w: Float, h: Float) {
        applyShapePaint()
        canvas.drawOval(
            x,
            y,
            x + w.coerceAtLeast(0f),
            y + h.coerceAtLeast(0f),
            shapePaint
        )
    }

    override fun drawArc(x: Float, y: Float, w: Float, h: Float, start: Float, sweep: Float) {
        applyShapePaint()
        canvas.drawArc(
            x,
            y,
            x + w.coerceAtLeast(0f),
            y + h.coerceAtLeast(0f),
            start,
            sweep,
            false,
            shapePaint
        )
    }

    override fun pathMove(x: Float, y: Float) {
        if (pathPoints >= MAX_PATH_POINTS) return
        pathPoints++
        path.moveTo(x, y)
    }

    override fun pathLine(x: Float, y: Float) {
        if (pathPoints >= MAX_PATH_POINTS) return
        pathPoints++
        path.lineTo(x, y)
    }

    override fun pathQuad(cx: Float, cy: Float, x: Float, y: Float) {
        if (pathPoints >= MAX_PATH_POINTS) return
        pathPoints++
        path.quadTo(cx, cy, x, y)
    }

    override fun pathCubic(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
        if (pathPoints >= MAX_PATH_POINTS) return
        pathPoints++
        path.cubicTo(c1x, c1y, c2x, c2y, x, y)
    }

    override fun pathClose() {
        if (pathPoints >= MAX_PATH_POINTS) return
        pathPoints++
        path.close()
    }

    override fun drawPath() {
        applyShapePaint()
        canvas.drawPath(path, shapePaint)
        path.reset()
        pathPoints = 0
    }

    override fun clipRect(x: Float, y: Float, w: Float, h: Float) {
        canvas.clipRect(x, y, x + w.coerceAtLeast(0f), y + h.coerceAtLeast(0f))
    }

    override fun measureText(text: String): Float {
        textPaint.textSize = textSize
        textPaint.textAlign = textAlign
        return textPaint.measureText(text)
    }

    /** 把当前颜色/透明度/样式/描边/渐变/阴影同步到图元画笔。 */
    private fun applyShapePaint() {
        shapePaint.color = color
        shapePaint.alpha = alphaInt()
        shapePaint.style = shapeStyle
        shapePaint.strokeWidth = max(1f, strokeWidth)
        shapePaint.shader = gradient
        if (shadowSet) {
            shapePaint.setShadowLayer(shadowRadius, shadowDx, shadowDy, shadowColor)
        } else {
            shapePaint.clearShadowLayer()
        }
    }

    private fun alphaInt(): Int = (alpha * 255f).roundToInt().coerceIn(0, 255)

    private companion object {
        const val MIN_TEXT_SIZE = 1f
        const val MAX_TEXT_SIZE = 512f

        /** save 深度上限:程序最多嵌套 16 层;超出部分静默忽略。 */
        const val MAX_SAVE_DEPTH = 16

        /** 单条路径最多累积 4096 个点/段,防止 100k 步内耗尽内存。 */
        const val MAX_PATH_POINTS = 4_096
    }
}
