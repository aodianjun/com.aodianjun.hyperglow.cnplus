package com.eza.hyperglow.root.aod

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import com.eza.hyperglow.effect.EFFECT_ALIGN_CENTER
import com.eza.hyperglow.effect.EFFECT_ALIGN_RIGHT
import com.eza.hyperglow.effect.EFFECT_STYLE_STROKE
import kotlin.math.roundToInt
import com.eza.hyperglow.effect.EffectBox
import com.eza.hyperglow.effect.EffectPathSegment

/**
 * 盒模型统一渲染器:把 [EffectVm.collectBoxes] 输出的 [EffectBox] 列表绘制到 Canvas。
 *
 * 实机([com.eza.hyperglow.root.aod.AodLyricCanvasView])与 App 预览
 * ([com.eza.hyperglow.ui.PreviewComponents])共用本渲染器,保证三端观感一致。
 *
 * 绘制语义与直绘 [AndroidEffectRenderSink] 逐值对齐:
 * - 文字 Paint 从调用方 Paint **复制**(保留字体/字距等测量态),每盒只覆盖
 *   字号/颜色/透明度/对齐/渐变/阴影,不污染调用方;
 * - 图元用独立画笔,fill/stroke/描边宽/渐变/阴影按盒样式应用;线段强制描边;
 * - 每盒 save→concat 盒矩阵→绘制→restore(矩阵非单位阵时),坐标仍是程序坐标空间;
 * - 钳制沿用直绘常量:字号 1..512、阴影半径/偏移、描边宽、路径段数。
 *
 * 仅绘制线程使用。
 */
/** 内置装饰素材提供者:按编号返回解码后的位图(未知/越界返回 null)。 */
internal fun interface EffectSpriteProvider {
    fun bitmap(spriteId: Int): android.graphics.Bitmap?
}

/**
 * 内置素材 provider:从模块 APK 的 assets/deco/<id>.png 解码并缓存。
 * 传入模块包上下文(SystemUI 侧用 createPackageContext,App 内预览用 app context)。
 * 编号越界或解码失败返回 null(渲染器跳过该盒)。
 */
internal fun builtInSpriteProvider(context: android.content.Context): EffectSpriteProvider {
    val cache = HashMap<Int, android.graphics.Bitmap>()
    return EffectSpriteProvider { id ->
        if (id < 0 || id >= com.eza.hyperglow.effect.BUILT_IN_DECOR_SPRITES) {
            null
        } else {
            cache[id] ?: runCatching {
                context.assets.open("deco/" + id + ".png").use { stream ->
                    android.graphics.BitmapFactory.decodeStream(stream)
                }
            }.getOrNull()?.also { cache[id] = it }
        }
    }
}

/**
 * 实机(SystemUI)侧 provider:用户导入贴图(64..255,配置回调装进
 * [CustomSpriteRegistry])优先,其余编号走内置素材(0..63)。
 */
internal fun hookSpriteProvider(context: android.content.Context): EffectSpriteProvider {
    val builtIn = builtInSpriteProvider(context)
    return EffectSpriteProvider { id ->
        if (id >= com.eza.hyperglow.root.customization.SpriteWire.MIN_SPRITE_ID) {
            CustomSpriteRegistry.bitmap(id)
        } else {
            builtIn.bitmap(id)
        }
    }
}

/**
 * App 内预览 provider:用户导入贴图直接读 app 私有存储(不经过跨进程通道),
 * 其余编号走内置素材。保证预览与锁屏/AOD 看到同一张图。
 */
internal fun appSpriteProvider(context: android.content.Context): EffectSpriteProvider {
    val builtIn = builtInSpriteProvider(context)
    val appContext = context.applicationContext ?: context
    return EffectSpriteProvider { id ->
        if (id >= com.eza.hyperglow.customization.DecorSpriteStore.MIN_ID) {
            com.eza.hyperglow.customization.DecorSpriteStore.bitmap(appContext, id)
        } else {
            builtIn.bitmap(id)
        }
    }
}

internal object EffectBoxRenderer {

    /** 把盒列表绘制到 [canvas]。[baseTextPaint] 提供字体等测量态基线;贴图盒需要 [sprites]。 */
    fun draw(
        canvas: Canvas,
        boxes: List<EffectBox>,
        baseTextPaint: Paint,
        sprites: EffectSpriteProvider? = null
    ) {
        if (boxes.isEmpty()) return
        val textPaint = Paint(baseTextPaint)
        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val matrix = Matrix()
        for (box in boxes) {
            drawBox(canvas, box, textPaint, shapePaint, matrix, sprites)
        }
    }

    private fun drawBox(
        canvas: Canvas,
        box: EffectBox,
        textPaint: Paint,
        shapePaint: Paint,
        matrix: Matrix,
        sprites: EffectSpriteProvider?
    ) {
        val transform = box.transform
        val transformed = !transform.isIdentity
        if (transformed) {
            matrix.setValues(
                floatArrayOf(
                    transform.m0, transform.m1, transform.m2,
                    transform.m3, transform.m4, transform.m5,
                    0f, 0f, 1f
                )
            )
            canvas.save()
            canvas.concat(matrix)
        }
        when (box) {
            is EffectBox.TextBox -> {
                applyTextPaint(textPaint, box.paint)
                canvas.drawText(box.text, box.x, box.baseline, textPaint)
            }
            is EffectBox.RectBox -> {
                applyShapePaint(shapePaint, box.paint)
                canvas.drawRect(
                    box.x, box.y,
                    box.x + box.width.coerceAtLeast(0f),
                    box.y + box.height.coerceAtLeast(0f),
                    shapePaint
                )
            }
            is EffectBox.RoundRectBox -> {
                applyShapePaint(shapePaint, box.paint)
                canvas.drawRoundRect(
                    box.x, box.y,
                    box.x + box.width.coerceAtLeast(0f),
                    box.y + box.height.coerceAtLeast(0f),
                    box.rx.coerceAtLeast(0f), box.ry.coerceAtLeast(0f),
                    shapePaint
                )
            }
            is EffectBox.CircleBox -> {
                applyShapePaint(shapePaint, box.paint)
                canvas.drawCircle(box.cx, box.cy, box.radius.coerceAtLeast(0f), shapePaint)
            }
            is EffectBox.LineBox -> {
                // 与直绘一致:线段强制描边。
                applyShapePaint(shapePaint, box.paint, forceStroke = true)
                canvas.drawLine(box.x1, box.y1, box.x2, box.y2, shapePaint)
            }
            is EffectBox.OvalBox -> {
                applyShapePaint(shapePaint, box.paint)
                canvas.drawOval(
                    box.x, box.y,
                    box.x + box.width.coerceAtLeast(0f),
                    box.y + box.height.coerceAtLeast(0f),
                    shapePaint
                )
            }
            is EffectBox.ArcBox -> {
                applyShapePaint(shapePaint, box.paint)
                canvas.drawArc(
                    box.x, box.y,
                    box.x + box.width.coerceAtLeast(0f),
                    box.y + box.height.coerceAtLeast(0f),
                    box.startAngle, box.sweepAngle, false, shapePaint
                )
            }
            is EffectBox.SpriteBox -> {
                val bitmap = sprites?.bitmap(box.spriteId)
                if (bitmap != null) {
                    applySpritePaint(shapePaint, box.paint)
                    val dst = android.graphics.RectF(
                        box.x, box.y,
                        box.x + box.width.coerceAtLeast(0f),
                        box.y + box.height.coerceAtLeast(0f)
                    )
                    canvas.drawBitmap(bitmap, null, dst, shapePaint)
                }
            }
            is EffectBox.PathBox -> {
                applyShapePaint(shapePaint, box.paint)
                val path = android.graphics.Path()
                for (segment in box.segments) {
                    when (segment.kind) {
                        EffectPathSegment.MOVE -> path.moveTo(segment.x0, segment.y0)
                        EffectPathSegment.LINE -> path.lineTo(segment.x0, segment.y0)
                        EffectPathSegment.QUAD ->
                            path.quadTo(segment.x0, segment.y0, segment.x1, segment.y1)
                        EffectPathSegment.CUBIC -> path.cubicTo(
                            segment.x0, segment.y0, segment.x1, segment.y1, segment.x2, segment.y2
                        )
                        EffectPathSegment.CLOSE -> path.close()
                    }
                }
                canvas.drawPath(path, shapePaint)
            }
        }
        if (transformed) canvas.restore()
    }

    /** 贴图画笔:白色素材按盒颜色着色(SRC_IN),alpha/阴影同步。 */
    private fun applySpritePaint(
        paint: Paint,
        state: com.eza.hyperglow.effect.EffectPaintState
    ) {
        paint.color = state.color
        paint.alpha = ((state.alpha.coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.colorFilter = android.graphics.PorterDuffColorFilter(
            state.color, android.graphics.PorterDuff.Mode.SRC_IN
        )
        if (state.shadow.set) {
            paint.setShadowLayer(
                state.shadow.radius.coerceIn(0f, 256f),
                state.shadow.dx.coerceIn(-256f, 256f),
                state.shadow.dy.coerceIn(-256f, 256f),
                state.shadow.color
            )
        } else {
            paint.clearShadowLayer()
        }
    }

    /** 把盒样式同步到文字画笔(仅覆盖程序可控状态,字体/字距保留调用方测量态)。 */
    private fun applyTextPaint(paint: Paint, state: com.eza.hyperglow.effect.EffectPaintState) {
        paint.textSize = state.textSize.coerceIn(1f, 512f)
        paint.color = state.color
        paint.alpha = ((state.alpha.coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
        paint.textAlign = when (state.align) {
            EFFECT_ALIGN_CENTER -> Paint.Align.CENTER
            EFFECT_ALIGN_RIGHT -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
        paint.shader = state.gradient?.let { gradient ->
            runCatching {
                LinearGradient(
                    gradient.x0, gradient.y0, gradient.x1, gradient.y1,
                    gradient.color0, gradient.color1,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }.getOrNull()
        }
        if (state.shadow.set) {
            paint.setShadowLayer(
                state.shadow.radius.coerceIn(0f, 256f),
                state.shadow.dx.coerceIn(-256f, 256f),
                state.shadow.dy.coerceIn(-256f, 256f),
                state.shadow.color
            )
        } else {
            paint.clearShadowLayer()
        }
    }

    /** 把盒样式同步到图元画笔。[forceStroke] 供线段使用(与直绘 drawLine 一致)。 */
    private fun applyShapePaint(
        paint: Paint,
        state: com.eza.hyperglow.effect.EffectPaintState,
        forceStroke: Boolean = false
    ) {
        paint.color = state.color
        paint.alpha = ((state.alpha.coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
        paint.style = if (forceStroke || state.style == EFFECT_STYLE_STROKE) {
            Paint.Style.STROKE
        } else {
            Paint.Style.FILL
        }
        paint.strokeWidth = maxOf(1f, state.strokeWidth.coerceIn(0f, 128f))
        paint.shader = state.gradient?.let { gradient ->
            runCatching {
                LinearGradient(
                    gradient.x0, gradient.y0, gradient.x1, gradient.y1,
                    gradient.color0, gradient.color1,
                    android.graphics.Shader.TileMode.CLAMP
                )
            }.getOrNull()
        }
        if (state.shadow.set) {
            paint.setShadowLayer(
                state.shadow.radius.coerceIn(0f, 256f),
                state.shadow.dx.coerceIn(-256f, 256f),
                state.shadow.dy.coerceIn(-256f, 256f),
                state.shadow.color
            )
        } else {
            paint.clearShadowLayer()
        }
    }
}
