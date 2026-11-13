package com.eza.hyperglow.effect

import kotlin.math.cos
import kotlin.math.sin

/**
 * 文本测量抽象:盒模式的 `measureText` 仍需文本测量,由宿主提供实现(计入 draw 预算)。
 * 实机由 Paint 副本实现,预览由 TextPaint 实现;纯 Kotlin 层不依赖 Android。
 */
fun interface EffectTextMeasurer {
    fun measure(text: String, textSizePx: Float, align: Int): Float
}

/**
 * 盒收集器:实现 [EffectRenderSink],把直绘指令翻译成 [EffectBox] 数据而非调用 Canvas。
 *
 * - **状态捕获**:setColor/setTextSize/setGradient/setShadow/setStrokeWidth/setFill/setStroke/
 *   setTextAlign 更新当前 [EffectPaintState];emit 时快照进盒。
 * - **变换累积**:save/restore/translate/rotate/scale 维护一个仿射矩阵栈(深度上限
 *   [MAX_EFFECT_BOX_SAVE_DEPTH]);每次 draw* 时把当前矩阵烘焙为盒上的 [EffectTransform]。
 *   因为盒模型把「绘制」延后到宿主,矩阵必须在收集期就应用到几何/或作为变换随盒下发——
 *   这里选择**随盒下发变换**(保留 pivot 语义,渲染器对每盒 save→transform→draw→restore),
 *   而不是预变换坐标,这样旋转/缩放仍绕盒原点,与直绘观感一致。
 * - **路径**:pathMove/Line/Quad/Cubic/Close 累积段;drawPath 时打包成 PathBox 并清空。
 * - **预算**:盒数、单路径段数、总路径段数、文本长度均设硬上限;超限置 failed,
 *   收集失败返回 null(不提交部分盒)。
 *
 * 仅绘制线程使用;每次 collectBoxes 调用使用全新实例,无跨帧状态。
 */
internal class EffectBoxCollector(
    private val measurer: EffectTextMeasurer,
    /** 初始文字/图元颜色:与直绘 AndroidEffectRenderSink 一致,取 colors.unsung。 */
    initialColor: Int = 0,
    /** 初始字号:与直绘 sink 一致,取宿主主 Paint 的当前字号(context.textSize)。 */
    initialTextSize: Float = 0f
) : EffectRenderSink {

    private val boxes = ArrayList<EffectBox>()
    private var failed = false

    // 当前绘制状态(与 AndroidEffectRenderSink 字段一一对应)。
    private var color = initialColor
    private var alpha = 1f
    private var textSize = initialTextSize.coerceIn(1f, 512f)
    private var align = EFFECT_ALIGN_LEFT
    private var style = EFFECT_STYLE_FILL
    private var strokeWidth = 1f
    private var gradient: EffectGradient? = null
    private var shadow = EffectShadow()

    // 路径累积。
    private val pathSegments = ArrayList<EffectPathSegment>()
    private var totalPathPoints = 0

    // 仿射矩阵栈:每项是相对于「未变换画布」的累积矩阵。栈底为单位矩阵。
    private val matrixStack = ArrayList<FloatArray>().apply { add(identityMatrix()) }

    /** 当前累积变换(栈顶)。 */
    private val currentMatrix: FloatArray
        get() = matrixStack.last()

    fun isFailed(): Boolean = failed

    /** 收集成功时返回盒列表;失败/超预算返回 null(不提交部分盒)。 */
    fun result(): List<EffectBox>? = if (failed) null else boxes

    // ---- 状态捕获 ----

    override fun setColor(argb: Int) { color = argb }
    override fun setAlpha(alpha: Float) { this.alpha = alpha.coerceIn(0f, 1f) }
    override fun setTextSize(px: Float) { textSize = px.coerceIn(1f, 512f) }
    override fun setTextAlign(align: Int) {
        this.align = when (align) {
            EFFECT_ALIGN_CENTER -> EFFECT_ALIGN_CENTER
            EFFECT_ALIGN_RIGHT -> EFFECT_ALIGN_RIGHT
            else -> EFFECT_ALIGN_LEFT
        }
    }
    override fun setStrokeWidth(width: Float) { strokeWidth = width.coerceIn(0f, 128f) }
    override fun setFill() { style = EFFECT_STYLE_FILL }
    override fun setStroke() { style = EFFECT_STYLE_STROKE }

    override fun setGradient(x0: Float, y0: Float, x1: Float, y1: Float, color0: Int, color1: Int) {
        gradient = EffectGradient(x0, y0, x1, y1, color0, color1)
    }
    override fun clearGradient() { gradient = null }
    override fun setShadow(radius: Float, dx: Float, dy: Float, color: Int) {
        shadow = EffectShadow(
            set = true,
            radius = radius.coerceIn(0f, 256f),
            dx = dx.coerceIn(-256f, 256f),
            dy = dy.coerceIn(-256f, 256f),
            color = color
        )
    }
    override fun clearShadow() { shadow = EffectShadow() }

    private fun paintSnapshot(): EffectPaintState = EffectPaintState(
        color = color,
        alpha = alpha,
        textSize = textSize,
        align = align,
        style = style,
        strokeWidth = strokeWidth,
        gradient = gradient,
        shadow = shadow
    )

    // ---- 变换(矩阵栈) ----

    override fun save() {
        if (matrixStack.size < MAX_EFFECT_BOX_SAVE_DEPTH) {
            matrixStack.add(currentMatrix.copyOf())
        }
    }

    override fun restore() {
        if (matrixStack.size > 1) matrixStack.removeAt(matrixStack.lastIndex)
    }

    override fun translate(x: Float, y: Float) {
        val cx = x.coerceIn(-10_000f, 10_000f)
        val cy = y.coerceIn(-10_000f, 10_000f)
        multiplyTop(translationMatrix(cx, cy))
    }

    override fun rotate(deg: Float) {
        multiplyTop(rotationMatrix(deg.coerceIn(-7_200f, 7_200f)))
    }

    override fun scale(sx: Float, sy: Float) {
        val x = sx.coerceIn(0.01f, 100f)
        val y = sy.coerceIn(0.01f, 100f)
        multiplyTop(scaleMatrix(x, y))
    }

    override fun clipRect(x: Float, y: Float, w: Float, h: Float) {
        // 盒模型的裁剪由宿主对整帧统一管理;程序级 clipRect 在盒模式下不逐盒生效
        // (盒可被自由摆放,逐盒裁剪语义与 MV 排版冲突)。保留为 no-op 并计入 draw 预算
        // (由 VM 侧 EFFECT_DRAW_OPS 统计),避免程序误依赖裁剪而产出不可见盒。
    }

    /**
     * 把当前矩阵快照成随盒下发的 [EffectTransform](完整 6 元素,不分解)。
     * rotate→scale→rotate 一类序列会产生错切,分解成平移/旋转/缩放会失真;
     * 直接存矩阵,渲染器 concat 后与直绘的 Canvas 矩阵语义逐值一致。
     */
    private fun bakedTransform(): EffectTransform {
        val m = currentMatrix
        return EffectTransform(
            m0 = m[0], m1 = m[1], m2 = m[2],
            m3 = m[3], m4 = m[4], m5 = m[5]
        )
    }

    // ---- 绘制 → 盒 ----

    private fun canEmit(): Boolean {
        if (failed) return false
        if (boxes.size >= MAX_EFFECT_BOXES) {
            failed = true
            return false
        }
        return true
    }

    override fun drawText(text: String, x: Float, y: Float) {
        if (!canEmit()) return
        if (text.length > MAX_EFFECT_BOX_TEXT_CHARS) { failed = true; return }
        boxes += EffectBox.TextBox(
            text = text,
            x = x,
            baseline = y,
            paint = paintSnapshot(),
            transform = bakedTransform()
        )
    }

    override fun drawRect(x: Float, y: Float, w: Float, h: Float) {
        if (!canEmit()) return
        boxes += EffectBox.RectBox(x, y, w.coerceAtLeast(0f), h.coerceAtLeast(0f),
            paintSnapshot(), bakedTransform())
    }

    override fun drawRoundRect(x: Float, y: Float, w: Float, h: Float, rx: Float, ry: Float) {
        if (!canEmit()) return
        boxes += EffectBox.RoundRectBox(x, y, w.coerceAtLeast(0f), h.coerceAtLeast(0f),
            rx.coerceAtLeast(0f), ry.coerceAtLeast(0f), paintSnapshot(), bakedTransform())
    }

    override fun drawCircle(cx: Float, cy: Float, r: Float) {
        if (!canEmit()) return
        boxes += EffectBox.CircleBox(cx, cy, r.coerceAtLeast(0f), paintSnapshot(), bakedTransform())
    }

    override fun drawLine(x1: Float, y1: Float, x2: Float, y2: Float) {
        if (!canEmit()) return
        // 线段强制描边(与 AndroidEffectRenderSink.drawLine 一致)。
        val saved = style
        style = EFFECT_STYLE_STROKE
        boxes += EffectBox.LineBox(x1, y1, x2, y2, paintSnapshot(), bakedTransform())
        style = saved
    }

    override fun drawOval(x: Float, y: Float, w: Float, h: Float) {
        if (!canEmit()) return
        boxes += EffectBox.OvalBox(x, y, w.coerceAtLeast(0f), h.coerceAtLeast(0f),
            paintSnapshot(), bakedTransform())
    }

    override fun drawArc(x: Float, y: Float, w: Float, h: Float, start: Float, sweep: Float) {
        if (!canEmit()) return
        boxes += EffectBox.ArcBox(x, y, w.coerceAtLeast(0f), h.coerceAtLeast(0f), start, sweep,
            paintSnapshot(), bakedTransform())
    }

    override fun drawSprite(spriteId: Int, x: Float, y: Float, w: Float, h: Float) {
        if (!canEmit()) return
        // 编号超出注册表范围 → 静默丢弃(未知素材不整帧失败)
        if (spriteId < 0 || spriteId > MAX_EFFECT_SPRITE_ID) return
        boxes += EffectBox.SpriteBox(spriteId, x, y, w.coerceAtLeast(0f), h.coerceAtLeast(0f),
            paintSnapshot(), bakedTransform())
    }

    override fun pathMove(x: Float, y: Float) {
        if (pathSegments.size >= MAX_EFFECT_PATH_POINTS_PER_BOX) { failed = true; return }
        pathSegments += EffectPathSegment(EffectPathSegment.MOVE, x0 = x, y0 = y)
    }

    override fun pathLine(x: Float, y: Float) {
        if (pathSegments.size >= MAX_EFFECT_PATH_POINTS_PER_BOX) { failed = true; return }
        pathSegments += EffectPathSegment(EffectPathSegment.LINE, x0 = x, y0 = y)
    }

    override fun pathQuad(cx: Float, cy: Float, x: Float, y: Float) {
        if (pathSegments.size >= MAX_EFFECT_PATH_POINTS_PER_BOX) { failed = true; return }
        pathSegments += EffectPathSegment(EffectPathSegment.QUAD, x0 = cx, y0 = cy, x1 = x, y1 = y)
    }

    override fun pathCubic(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
        if (pathSegments.size >= MAX_EFFECT_PATH_POINTS_PER_BOX) { failed = true; return }
        pathSegments += EffectPathSegment(
            EffectPathSegment.CUBIC, x0 = c1x, y0 = c1y, x1 = c2x, y1 = c2y, x2 = x, y2 = y
        )
    }

    override fun pathClose() {
        if (pathSegments.size >= MAX_EFFECT_PATH_POINTS_PER_BOX) { failed = true; return }
        pathSegments += EffectPathSegment(EffectPathSegment.CLOSE)
    }

    override fun drawPath() {
        if (!canEmit()) return
        totalPathPoints += pathSegments.size
        if (totalPathPoints > MAX_EFFECT_TOTAL_PATH_POINTS) { failed = true; return }
        boxes += EffectBox.PathBox(pathSegments.toList(), paintSnapshot(), bakedTransform())
        pathSegments.clear()
    }

    override fun measureText(text: String): Float =
        measurer.measure(text, textSize, align)

    // ---- 矩阵工具 ----
    // 2D 仿射,行主序 [m0 m1 m2; m3 m4 m5],列向量点变换:
    // x' = m0·x + m1·y + m2; y' = m3·x + m4·y + m5。

    private fun identityMatrix() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)

    private fun translationMatrix(tx: Float, ty: Float) = floatArrayOf(1f, 0f, tx, 0f, 1f, ty)

    private fun rotationMatrix(deg: Float): FloatArray {
        val rad = Math.toRadians(deg.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        // x' = c·x - s·y ; y' = s·x + c·y
        return floatArrayOf(c, -s, 0f, s, c, 0f)
    }

    private fun scaleMatrix(sx: Float, sy: Float) = floatArrayOf(sx, 0f, 0f, 0f, sy, 0f)

    /** 栈顶矩阵右乘 [rhs](post-concat,与 Canvas.translate/rotate/scale 的后乘语义一致)。 */
    private fun multiplyTop(rhs: FloatArray) {
        val m = currentMatrix
        val a0 = m[0]; val a1 = m[1]; val a2 = m[2]
        val a3 = m[3]; val a4 = m[4]; val a5 = m[5]
        val b0 = rhs[0]; val b1 = rhs[1]; val b2 = rhs[2]
        val b3 = rhs[3]; val b4 = rhs[4]; val b5 = rhs[5]
        matrixStack[matrixStack.lastIndex] = floatArrayOf(
            a0 * b0 + a1 * b3,
            a0 * b1 + a1 * b4,
            a0 * b2 + a1 * b5 + a2,
            a3 * b0 + a4 * b3,
            a3 * b1 + a4 * b4,
            a3 * b2 + a4 * b5 + a5
        )
    }
}
