package com.eza.hyperglow.effect

import kotlinx.serialization.Serializable

/**
 * 盒模型输出(EffectProgram.outputMode == "boxes")的纯数据结果。
 *
 * 与直绘模式([EffectVm.render])的区别:程序不再直接往 Canvas 画,而是输出一组**定位盒**,
 * 由宿主(实机 [com.eza.hyperglow.root.aod.AodLyricCanvasView] / 预览
 * [com.eza.hyperglow.ui.PreviewComponents])用统一渲染器绘制。盒是可序列化数据,可编辑、
 * 可测量(overdraw 估算)、可缓存,且三端共用同一份求值与绘制逻辑。
 *
 * 信任边界:盒只含基础类型/字符串/数值,**不含** Android `Paint`/`Path`/`Shader` 对象,
 * 也不能指定类、资源、方法、路径、URL。所有数值在收集期已被预算与钳制约束
 * (见 [EffectBoxCollector])。
 *
 * 坐标约定:与直绘一致——`x`/`baseline` 为逻辑画布像素,文字盒的 y 是**基线**(同 drawText);
 * 形状盒用左上角 + 宽高。变换在 emit 时由收集器从矩阵栈烘焙进盒,渲染器不再维护矩阵栈。
 */

/** 文字对齐:0=left, 1=center, 2=right(与 [android.graphics.Paint.Align] 序号一致)。 */
const val EFFECT_ALIGN_LEFT = 0
const val EFFECT_ALIGN_CENTER = 1
const val EFFECT_ALIGN_RIGHT = 2

/** 图元填充/描边。 */
const val EFFECT_STYLE_FILL = 0
const val EFFECT_STYLE_STROKE = 1

/**
 * 线性渐变参数(可空字段表示无渐变)。六参数对应 LinearGradient(x0,y0,x1,y1,color0,color1)。
 * 用值类而非 Android Shader,满足纯数据约束。
 */
@Serializable
data class EffectGradient(
    val x0: Float = 0f,
    val y0: Float = 0f,
    val x1: Float = 0f,
    val y1: Float = 0f,
    val color0: Int = 0,
    val color1: Int = 0
)

/** 阴影参数;`set=false` 表示无阴影(对应 clearShadow)。 */
@Serializable
data class EffectShadow(
    val set: Boolean = false,
    val radius: Float = 0f,
    val dx: Float = 0f,
    val dy: Float = 0f,
    val color: Int = 0
)

/**
 * 绘制样式快照。盒在 emit 时捕获当时程序设置的全部绘制状态,渲染器据此重建 Paint。
 * 与 [com.eza.hyperglow.root.aod.AndroidEffectRenderSink] 的状态字段一一对应。
 */
@Serializable
data class EffectPaintState(
    val color: Int = 0,
    /** 0..1;渲染期转 0..255 alpha。 */
    val alpha: Float = 1f,
    val textSize: Float = 0f,
    val align: Int = EFFECT_ALIGN_LEFT,
    val style: Int = EFFECT_STYLE_FILL,
    val strokeWidth: Float = 1f,
    val gradient: EffectGradient? = null,
    val shadow: EffectShadow = EffectShadow()
)

/**
 * emit 时从矩阵栈烘焙出的 2D 仿射变换(完整矩阵,行主序 [m0 m1 m2; m3 m4 m5],列向量点变换:
 * x' = m0·x + m1·y + m2; y' = m3·x + m4·y + m5)。
 *
 * 存完整矩阵而不是平移/旋转/缩放三元组:rotate→scale→rotate 一类序列会产生错切,
 * 分解式无法无损表达;矩阵随盒下发后渲染器直接 concat,与直绘的 Canvas 矩阵语义逐值一致。
 */
@Serializable
data class EffectTransform(
    val m0: Float = 1f,
    val m1: Float = 0f,
    val m2: Float = 0f,
    val m3: Float = 0f,
    val m4: Float = 1f,
    val m5: Float = 0f
) {
    /** 程序坐标空间里的平移分量(第三列)。 */
    val translateX: Float get() = m2
    val translateY: Float get() = m5

    val isIdentity: Boolean
        get() = m0 == 1f && m1 == 0f && m2 == 0f && m3 == 0f && m4 == 1f && m5 == 0f
}

/** 路径段。kind: 0=move, 1=line, 2=quad, 3=cubic, 4=close。坐标为段自带参数。 */
@Serializable
data class EffectPathSegment(
    val kind: Int = 0,
    val x0: Float = 0f,
    val y0: Float = 0f,
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val x3: Float = 0f,
    val y3: Float = 0f
) {
    companion object {
        const val MOVE = 0
        const val LINE = 1
        const val QUAD = 2
        const val CUBIC = 3
        const val CLOSE = 4
    }
}

/**
 * 定位盒。sealed 便于序列化与穷尽处理;每盒 = 几何 + 样式 + 变换。
 *
 * 命名带 `Effect` 前缀避免与渲染层其它 Box 类型混淆;序列化用 @Serializable 多态,
 * 文档里不出现 Android 类名。
 */
@Serializable
sealed class EffectBox {
    abstract val paint: EffectPaintState
    abstract val transform: EffectTransform

    /** 文字盒:text 绘制在 (x, baseline),y 为基线。 */
    @Serializable
    data class TextBox(
        val text: String = "",
        val x: Float = 0f,
        val baseline: Float = 0f,
        override val paint: EffectPaintState = EffectPaintState(),
        override val transform: EffectTransform = EffectTransform()
    ) : EffectBox()

    /** 矩形:左上角 (x,y) + 宽高。 */
    @Serializable
    data class RectBox(
        val x: Float = 0f,
        val y: Float = 0f,
        val width: Float = 0f,
        val height: Float = 0f,
        override val paint: EffectPaintState = EffectPaintState(),
        override val transform: EffectTransform = EffectTransform()
    ) : EffectBox()

    /** 圆角矩形。 */
    @Serializable
    data class RoundRectBox(
        val x: Float = 0f,
        val y: Float = 0f,
        val width: Float = 0f,
        val height: Float = 0f,
        val rx: Float = 0f,
        val ry: Float = 0f,
        override val paint: EffectPaintState = EffectPaintState(),
        override val transform: EffectTransform = EffectTransform()
    ) : EffectBox()

    /** 圆:圆心 + 半径。 */
    @Serializable
    data class CircleBox(
        val cx: Float = 0f,
        val cy: Float = 0f,
        val radius: Float = 0f,
        override val paint: EffectPaintState = EffectPaintState(),
        override val transform: EffectTransform = EffectTransform()
    ) : EffectBox()

    /** 线段。 */
    @Serializable
    data class LineBox(
        val x1: Float = 0f,
        val y1: Float = 0f,
        val x2: Float = 0f,
        val y2: Float = 0f,
        override val paint: EffectPaintState = EffectPaintState(),
        override val transform: EffectTransform = EffectTransform()
    ) : EffectBox()

    /** 椭圆(外接矩形)。 */
    @Serializable
    data class OvalBox(
        val x: Float = 0f,
        val y: Float = 0f,
        val width: Float = 0f,
        val height: Float = 0f,
        override val paint: EffectPaintState = EffectPaintState(),
        override val transform: EffectTransform = EffectTransform()
    ) : EffectBox()

    /** 圆弧(外接矩形 + 起始角/扫过角)。 */
    @Serializable
    data class ArcBox(
        val x: Float = 0f,
        val y: Float = 0f,
        val width: Float = 0f,
        val height: Float = 0f,
        val startAngle: Float = 0f,
        val sweepAngle: Float = 0f,
        override val paint: EffectPaintState = EffectPaintState(),
        override val transform: EffectTransform = EffectTransform()
    ) : EffectBox()

    /**
     * 贴图盒:内置装饰素材(assets/deco/<id>.png)。id 是**固定注册表编号**,
     * 文档里只出现数字,不出现任何路径/URL —— 信任边界不变。
     * 素材为白色透明 PNG,渲染时按盒的 paint 颜色着色(colorFilter SRC_IN)。
     */
    @Serializable
    data class SpriteBox(
        val spriteId: Int = 0,
        val x: Float = 0f,
        val y: Float = 0f,
        val width: Float = 0f,
        val height: Float = 0f,
        override val paint: EffectPaintState = EffectPaintState(),
        override val transform: EffectTransform = EffectTransform()
    ) : EffectBox()

    /** 路径:有序段列表 + 图元样式。 */
    @Serializable
    data class PathBox(
        val segments: List<EffectPathSegment> = emptyList(),
        override val paint: EffectPaintState = EffectPaintState(),
        override val transform: EffectTransform = EffectTransform()
    ) : EffectBox()
}

// ---- 预算常量(盒模式专用;与 EffectProgram.kt 的直绘预算并列) ----

/** 单帧最多输出的盒数。超出即收集失败(不返回部分盒)。 */
const val MAX_EFFECT_BOXES = 512

/** 单个路径盒最多累积的段数。 */
const val MAX_EFFECT_PATH_POINTS_PER_BOX = 256

/** 单帧所有路径盒累计段数上限。 */
const val MAX_EFFECT_TOTAL_PATH_POINTS = 4_096

/** 单个文字盒的最大字符数。 */
const val MAX_EFFECT_BOX_TEXT_CHARS = 512

/** 矩阵栈最大嵌套深度(与 AndroidEffectRenderSink.MAX_SAVE_DEPTH 一致)。 */
const val MAX_EFFECT_BOX_SAVE_DEPTH = 16

/** 内置装饰素材的编号上限(0..63 为内置;64..255 预留用户导入)。超出范围的绘制被静默丢弃。 */
const val MAX_EFFECT_SPRITE_ID = 255

/** 当前内置素材数量(assets/deco/0.png .. 15.png)。 */
const val BUILT_IN_DECOR_SPRITES = 16
