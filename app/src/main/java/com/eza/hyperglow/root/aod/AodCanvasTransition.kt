package com.eza.hyperglow.root.aod

import android.graphics.Color
import kotlin.math.roundToInt

/** 换行动画入场基准时长(历史档;退场/入场共用同一 elapsed,入场较长者收尾)。 */
internal const val ENTER_TRANSITION_MS = 210L

/** 换行动画退场基准时长(历史档)。 */
internal const val EXIT_TRANSITION_MS = 130L

/**
 * 换行动画速率档 → 时长倍率:Slow 1.5×、Fast 0.6×、Normal/未知 1×。
 * 只等比缩放各档的退场/入场时长(见 [enterTransitionMs] / [exitTransitionMs]),
 * 帧配方与缓动曲线不动;速率词表见
 * [com.eza.hyperglow.customization.LINE_TRANSITION_SPEEDS],预览与实机共用这一份倍率。
 */
internal fun lineTransitionDurationScale(speed: String): Float = when (speed) {
    "Slow" -> 1.5f
    "Fast" -> 0.6f
    else -> 1f
}

// ---------------------------------------------------------------------------
// HyperLyric 复刻:预设配方表(退场→换字→进场序列)
// ---------------------------------------------------------------------------

/**
 * daimajia AndroidAnimations 2.4 动画器的变换种类([techFrame] 展开各自关键帧)。
 * 变换语义逐项对照库源:fading_exits / fading_entrances / sliders / flippers /
 * rotating_exits / rotating_entrances / zooming_exits / zooming_entrances,
 * 以及 HyperLyric 自带 LandingSoft(scale 1.2→1 + 淡入,Glider QuintEaseOut)。
 */
internal enum class LineTransitionTech {
    FADE_OUT,
    FADE_OUT_LEFT,
    FADE_OUT_RIGHT,
    FADE_OUT_UP,
    FADE_OUT_DOWN,
    SLIDE_OUT_LEFT,
    SLIDE_OUT_RIGHT,
    FLIP_OUT_X,
    FLIP_OUT_Y,
    ROTATE_OUT,
    ZOOM_OUT,
    FADE_IN,
    FADE_IN_LEFT,
    FADE_IN_RIGHT,
    FADE_IN_UP,
    FADE_IN_DOWN,
    SLIDE_IN_LEFT,
    SLIDE_IN_RIGHT,
    FLIP_IN_X,
    FLIP_IN_Y,
    ROTATE_IN,
    ZOOM_IN,
    ZOOM_IN_LEFT,
    ZOOM_IN_RIGHT,
    LANDING_SOFT
}

/** 预设缓动(同式移植):FastOutLinearIn / FastOutSlowIn / Android OvershootInterpolator / Glider QuintEaseOut。 */
internal enum class LineTransitionEase {
    FAST_OUT_LINEAR_IN,
    FAST_OUT_SLOW_IN,
    OVERSHOOT_1_6,
    OVERSHOOT_1_5,
    OVERSHOOT_2_0,
    OVERSHOOT_1_0,
    QUINT_OUT
}

/** 预设的单段配置:技术 + 时长 + 缓动(对应 YoYoPresets 的 AnimConfig)。 */
internal data class LineTransitionPreset(
    val outTech: LineTransitionTech,
    val outMs: Long,
    val outEase: LineTransitionEase,
    val inTech: LineTransitionTech,
    val inMs: Long,
    val inEase: LineTransitionEase
)

/**
 * HyperLyric「歌词切换动画」25 个预设(id 原名,顺序同其设置页):
 * 退场→换字→进场的序列式过渡,时长/缓动与 YoYoPresets 逐项一致
 * (退场统一 FastOutLinearIn;柔缓着陆档入场用其自带 QuintEaseOut)。
 */
internal val LINE_TRANSITION_PRESETS: Map<String, LineTransitionPreset> = linkedMapOf(
    "fade_out_fade_in" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN, 300L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_up_fade_in_up" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_UP, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_UP, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_down_fade_in_down" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_DOWN, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_DOWN, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_left_fade_in_right" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_RIGHT, 450L, LineTransitionEase.OVERSHOOT_1_6
    ),
    "fade_out_left_fade_in_up" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_UP, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_left_zoom_in" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN, 400L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_left_landing" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.LANDING_SOFT, 700L, LineTransitionEase.QUINT_OUT
    ),
    "fade_out_right_fade_in_left" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_LEFT, 450L, LineTransitionEase.OVERSHOOT_1_6
    ),
    "fade_out_right_fade_in_up" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_UP, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_right_zoom_in" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_RIGHT, 200L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN, 400L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_right_landing" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.LANDING_SOFT, 700L, LineTransitionEase.QUINT_OUT
    ),
    "fade_out_left_zoom_in_right" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_LEFT, 250L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN_RIGHT, 600L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "fade_out_right_zoom_in_left" to LineTransitionPreset(
        LineTransitionTech.FADE_OUT_RIGHT, 250L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN_LEFT, 600L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "slide_out_left_slide_in_right" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.SLIDE_IN_RIGHT, 450L, LineTransitionEase.OVERSHOOT_2_0
    ),
    "slide_out_left_fade_in_up" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_UP, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "slide_out_left_zoom_in" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "slide_out_left_landing" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_LEFT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.LANDING_SOFT, 700L, LineTransitionEase.QUINT_OUT
    ),
    "slide_out_right_slide_in_left" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.SLIDE_IN_LEFT, 450L, LineTransitionEase.OVERSHOOT_1_5
    ),
    "slide_out_right_fade_in_up" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FADE_IN_UP, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "slide_out_right_zoom_in" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "slide_out_right_landing" to LineTransitionPreset(
        LineTransitionTech.SLIDE_OUT_RIGHT, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.LANDING_SOFT, 700L, LineTransitionEase.QUINT_OUT
    ),
    "flip_out_x_flip_in_x" to LineTransitionPreset(
        LineTransitionTech.FLIP_OUT_X, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FLIP_IN_X, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "flip_out_y_flip_in_y" to LineTransitionPreset(
        LineTransitionTech.FLIP_OUT_Y, 300L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.FLIP_IN_Y, 450L, LineTransitionEase.FAST_OUT_SLOW_IN
    ),
    "rotate_out_rotate_in" to LineTransitionPreset(
        LineTransitionTech.ROTATE_OUT, 200L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ROTATE_IN, 600L, LineTransitionEase.OVERSHOOT_1_0
    ),
    "zoom_out_zoom_in" to LineTransitionPreset(
        LineTransitionTech.ZOOM_OUT, 200L, LineTransitionEase.FAST_OUT_LINEAR_IN,
        LineTransitionTech.ZOOM_IN, 400L, LineTransitionEase.FAST_OUT_SLOW_IN
    )
)

/**
 * 模式 → 预设配方:HyperLyric 预设 id 直接查表;#95 短名档按别名归到同配方预设
 * (与 normalizeLineTransition 的别名一致);历史档返回 null 走原帧配方。
 */
internal fun lineTransitionPreset(mode: String): LineTransitionPreset? =
    LINE_TRANSITION_PRESETS[mode] ?: when (mode) {
        "Fade left" -> LINE_TRANSITION_PRESETS["fade_out_left_fade_in_right"]
        "Landing" -> LINE_TRANSITION_PRESETS["fade_out_left_landing"]
        "Slide swap" -> LINE_TRANSITION_PRESETS["slide_out_left_slide_in_right"]
        else -> null
    }

/** 参考 HyperLyric 的档为序列式:退场完成后再入场;历史档退场/入场叠加。 */
internal fun isSequentialLineTransition(mode: String): Boolean =
    lineTransitionPreset(mode) != null

/**
 * 入场基准时长:历史档 210ms;HyperLyric 预设按各自 inMs(300/400/450/600/700ms)。
 * 速档缩放后:历史 315/210/126ms,预设同倍率换算。
 */
internal fun enterTransitionMs(mode: String, speed: String): Long {
    val base = lineTransitionPreset(mode)?.inMs ?: ENTER_TRANSITION_MS
    return (base * lineTransitionDurationScale(speed)).toLong()
}

/**
 * 退场基准时长:历史档 130ms;HyperLyric 预设按各自 outMs(200/250/300ms)。
 * 速档缩放后:历史 195/130/78ms,预设同倍率换算。
 */
internal fun exitTransitionMs(mode: String, speed: String): Long {
    val base = lineTransitionPreset(mode)?.outMs ?: EXIT_TRANSITION_MS
    return (base * lineTransitionDurationScale(speed)).toLong()
}

/**
 * 过渡总时长:历史档退场/入场共用 elapsed 叠加(入场较长者收尾);
 * 序列档为退场+入场串接,冻结旧层的清理与帧过期判定按总时长收口。
 */
internal fun lineTransitionTotalMs(mode: String, speed: String): Long {
    val exitMs = exitTransitionMs(mode, speed)
    val enterMs = enterTransitionMs(mode, speed)
    return if (isSequentialLineTransition(mode)) exitMs + enterMs else maxOf(exitMs, enterMs)
}

/** 退场进度 0..1(自过渡起点计时,历史档与序列档同一公式)。 */
internal fun lineTransitionExitProgress(elapsedMs: Long, mode: String, speed: String): Float =
    (elapsedMs / exitTransitionMs(mode, speed).toFloat()).coerceIn(0f, 1f)

/** 入场进度 0..1:序列档退场期间恒 0(新行层不可见),退场完成后才开始推进。 */
internal fun lineTransitionEnterProgress(elapsedMs: Long, mode: String, speed: String): Float {
    val enterElapsed = if (isSequentialLineTransition(mode)) {
        elapsedMs - exitTransitionMs(mode, speed)
    } else {
        elapsedMs
    }
    return (enterElapsed / enterTransitionMs(mode, speed).toFloat()).coerceIn(0f, 1f)
}

// 各换行动画模式的运动参数(dp / 缩放比),预览与实机共用这一份配方。
private const val FADE_UP_DP = 14f
private const val SLIDE_DP = 28f
private const val ZOOM_EXIT_SCALE_GAIN = 0.06f
private const val ZOOM_ENTER_SCALE_FROM = 0.92f

// HyperLyric / daimajia 参考参数:Fade 族位移为行块宽(高)的 1/4;Slide 族整宽(高);
// ZoomInLeft/Right 的中间停点 48px 按 xxhdpi 折算 16dp;柔缓着陆自 1.2 收落。
private const val HYPER_DRIFT_FRACTION = 0.25f
private const val HYPER_NUDGE_DP = 16f
private const val LANDING_SCALE_FROM = 1.2f

/**
 * 换行动画单行的边界(px):[topPx]/[bottomPx] 为行盒上下沿(与 [AodCanvasVerticalBounds]
 * 同式:top = 基线 + ascent、bottom = top + 行高),[animated] 标记该行是否随换行进退
 * (歌曲信息行恒 false)。
 */
internal data class AodCanvasRowBox(
    val topPx: Float,
    val bottomPx: Float,
    val animated: Boolean
)

/**
 * 换行动画的行块高度(px):参与换行的歌词行(主歌词 + 辅助文字 + 下一行)的包围盒高
 * = max(bottomPx) − min(topPx)。
 *
 * 参考实现把位移施加在「歌词行视图」上(daimajia AndroidAnimations 2.4 各动画器的 target):
 * Fade 族抬起量为 `target.getHeight()/4`、Slide 族整宽,基准是该视图自身尺寸,而不是渲染
 * 画布的内容裁剪框——内容框按 surface 高度定高,可能远大于行块(行块只占其中几条行盒),
 * 拿它当基准会把竖向漂移放大数倍(真机实测:内容框高约 677px → 漂移约 169px,而 1/4
 * 行块高只有约 50px),表现为旧行整块扫过歌曲信息行、新行自数行之外升起。
 * 空块或非法边界回落 [fallbackPx](调用方传内容框高),保持零除安全。
 */
internal fun animatedBlockHeightPx(rows: List<AodCanvasRowBox>, fallbackPx: Float): Float {
    var top = Float.POSITIVE_INFINITY
    var bottom = Float.NEGATIVE_INFINITY
    rows.forEach { box ->
        if (!box.animated) return@forEach
        if (box.topPx < top) top = box.topPx
        if (box.bottomPx > bottom) bottom = box.bottomPx
    }
    val height = if (top.isFinite() && bottom.isFinite() && bottom > top) bottom - top else 0f
    return if (height > 0f) height else fallbackPx
}

/**
 * 换行动画单帧参数:alpha 直接作图层透明度;translate* 为 dp 位移(调用方乘 density);
 * scale 为绕内容中心的放缩比;rotation* 为绕内容中心的角度(度)。
 * 退场/入场共用同一数据形状,由方向不同的两个函数产出。
 */
internal data class LineTransitionFrame(
    val alpha: Float,
    val translateXDp: Float = 0f,
    val translateYDp: Float = 0f,
    val scale: Float = 1f,
    val rotationDeg: Float = 0f,
    val rotationXDeg: Float = 0f,
    val rotationYDeg: Float = 0f
)

/**
 * 退场帧(progress 0→1,对应 [exitTransitionMs]):
 * - 历史档:`Fade up` 淡出并上移 14dp(历史默认,逐像素不变)、`Crossfade` 纯淡出、
 *   `Slide up`/`Slide left` 上/左滑淡出(透明度 1-p² 保持更久、位移 28dp)、
 *   `Zoom` 淡出并轻微放大;未知值退化为 `Fade up`(与 wire 归一化兜底一致)。
 * - HyperLyric 预设档(含 #95 短名别名):按 [LINE_TRANSITION_PRESETS] 的退场技术
 *   展开 daimajia 关键帧([techFrame]),progress 已过退场缓动。
 */
internal fun lineTransitionExitFrame(
    mode: String,
    progress: Float,
    blockWidthDp: Float = 0f,
    blockHeightDp: Float = 0f
): LineTransitionFrame {
    val preset = lineTransitionPreset(mode)
    if (preset != null) {
        return techFrame(preset.outTech, progress.coerceAtLeast(0f), blockWidthDp, blockHeightDp)
    }
    val p = progress.coerceIn(0f, 1f)
    return when (mode) {
        "Crossfade", "None" -> LineTransitionFrame(alpha = 1f - p)
        "Slide up" -> LineTransitionFrame(alpha = 1f - p * p, translateYDp = -SLIDE_DP * p)
        "Slide left" -> LineTransitionFrame(alpha = 1f - p * p, translateXDp = -SLIDE_DP * p)
        "Zoom" -> LineTransitionFrame(alpha = 1f - p, scale = 1f + ZOOM_EXIT_SCALE_GAIN * p)
        else -> LineTransitionFrame(alpha = 1f - p, translateYDp = -FADE_UP_DP * p)
    }
}

/**
 * 入场帧(progress 0→1,对应 [enterTransitionMs]):
 * - 历史档:`Fade up` 淡入并从 14dp 下方升至原位(历史默认,逐像素不变)、
 *   `Crossfade` 纯淡入、`Slide up`/`Slide left` 自下方/右侧 28dp 滑入(透明度
 *   提前拉满)、`Zoom` 自 0.92 放大至 1;未知值退化为 `Fade up`。
 * - HyperLyric 预设档(含 #95 短名别名):按入场技术展开 daimajia 关键帧;
 *   过冲缓动可使 progress 短暂 >1,位移/缩放/角度保留越过量,alpha 钳制 1。
 */
internal fun lineTransitionEnterFrame(
    mode: String,
    progress: Float,
    blockWidthDp: Float = 0f,
    blockHeightDp: Float = 0f
): LineTransitionFrame {
    val preset = lineTransitionPreset(mode)
    if (preset != null) {
        return techFrame(preset.inTech, progress.coerceAtLeast(0f), blockWidthDp, blockHeightDp)
    }
    val p = progress.coerceIn(0f, 1f)
    return when (mode) {
        "Crossfade", "None" -> LineTransitionFrame(alpha = p)
        "Slide up" -> LineTransitionFrame(
            alpha = minOf(1f, p * 1.6f),
            translateYDp = SLIDE_DP * (1f - p)
        )
        "Slide left" -> LineTransitionFrame(
            alpha = minOf(1f, p * 1.6f),
            translateXDp = SLIDE_DP * (1f - p)
        )
        "Zoom" -> LineTransitionFrame(
            alpha = p,
            scale = ZOOM_ENTER_SCALE_FROM + (1f - ZOOM_ENTER_SCALE_FROM) * p
        )
        else -> LineTransitionFrame(alpha = p, translateYDp = FADE_UP_DP * (1f - p))
    }
}

/**
 * 技术 → 帧:daimajia 各动画器的关键帧逐项展开([p] 为已缓动进度,可 >1 过冲外插)。
 * ObjectAnimator 多值语义 = 均匀分段关键帧([sample]);位移 dp 以该层行块自身宽/高为基准
 * (见 [animatedBlockHeightPx]:退场层用旧行块、入场层用新行块)。
 */
private fun techFrame(
    tech: LineTransitionTech,
    p: Float,
    blockWidthDp: Float,
    blockHeightDp: Float
): LineTransitionFrame {
    val q = p.coerceAtLeast(0f)
    val w = blockWidthDp
    val h = blockHeightDp
    val driftX = HYPER_DRIFT_FRACTION * w
    val driftY = HYPER_DRIFT_FRACTION * h
    return when (tech) {
        LineTransitionTech.FADE_OUT -> frameOf(q, alpha = floatArrayOf(1f, 0f))
        LineTransitionTech.FADE_OUT_LEFT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateX = floatArrayOf(0f, -driftX)
        )
        LineTransitionTech.FADE_OUT_RIGHT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateX = floatArrayOf(0f, driftX)
        )
        LineTransitionTech.FADE_OUT_UP -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateY = floatArrayOf(0f, -driftY)
        )
        LineTransitionTech.FADE_OUT_DOWN -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateY = floatArrayOf(0f, driftY)
        )
        LineTransitionTech.SLIDE_OUT_LEFT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateX = floatArrayOf(0f, -w)
        )
        LineTransitionTech.SLIDE_OUT_RIGHT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            translateX = floatArrayOf(0f, w)
        )
        LineTransitionTech.FLIP_OUT_X -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            rotationX = floatArrayOf(0f, 90f)
        )
        LineTransitionTech.FLIP_OUT_Y -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            rotationY = floatArrayOf(0f, 90f)
        )
        LineTransitionTech.ROTATE_OUT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f),
            rotation = floatArrayOf(0f, 200f)
        )
        LineTransitionTech.ZOOM_OUT -> frameOf(
            q,
            alpha = floatArrayOf(1f, 0f, 0f),
            scale = floatArrayOf(1f, 0.3f, 0f)
        )
        LineTransitionTech.FADE_IN -> frameOf(q, alpha = floatArrayOf(0f, 1f))
        LineTransitionTech.FADE_IN_LEFT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateX = floatArrayOf(-driftX, 0f)
        )
        LineTransitionTech.FADE_IN_RIGHT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateX = floatArrayOf(driftX, 0f)
        )
        LineTransitionTech.FADE_IN_UP -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateY = floatArrayOf(driftY, 0f)
        )
        LineTransitionTech.FADE_IN_DOWN -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateY = floatArrayOf(-driftY, 0f)
        )
        LineTransitionTech.SLIDE_IN_LEFT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateX = floatArrayOf(-w, 0f)
        )
        LineTransitionTech.SLIDE_IN_RIGHT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            translateX = floatArrayOf(w, 0f)
        )
        LineTransitionTech.FLIP_IN_X -> frameOf(
            q,
            alpha = floatArrayOf(0.25f, 0.5f, 0.75f, 1f),
            rotationX = floatArrayOf(90f, -15f, 15f, 0f)
        )
        LineTransitionTech.FLIP_IN_Y -> frameOf(
            q,
            alpha = floatArrayOf(0.25f, 0.5f, 0.75f, 1f),
            rotationY = floatArrayOf(90f, -15f, 15f, 0f)
        )
        LineTransitionTech.ROTATE_IN -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            rotation = floatArrayOf(-200f, 0f)
        )
        LineTransitionTech.ZOOM_IN -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            scale = floatArrayOf(0.45f, 1f)
        )
        LineTransitionTech.ZOOM_IN_LEFT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f, 1f),
            translateX = floatArrayOf(-w, HYPER_NUDGE_DP, 0f),
            scale = floatArrayOf(0.1f, 0.475f, 1f)
        )
        LineTransitionTech.ZOOM_IN_RIGHT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f, 1f),
            translateX = floatArrayOf(w, -HYPER_NUDGE_DP, 0f),
            scale = floatArrayOf(0.1f, 0.475f, 1f)
        )
        LineTransitionTech.LANDING_SOFT -> frameOf(
            q,
            alpha = floatArrayOf(0f, 1f),
            scale = floatArrayOf(LANDING_SCALE_FROM, 1f)
        )
    }
}

/** 由关键帧停点组帧:alpha 钳制 0..1,其余通道保留过冲越过量。 */
private fun frameOf(
    p: Float,
    alpha: FloatArray,
    translateX: FloatArray = floatArrayOf(0f),
    translateY: FloatArray = floatArrayOf(0f),
    scale: FloatArray = floatArrayOf(1f),
    rotation: FloatArray = floatArrayOf(0f),
    rotationX: FloatArray = floatArrayOf(0f),
    rotationY: FloatArray = floatArrayOf(0f)
): LineTransitionFrame = LineTransitionFrame(
    alpha = sample(p, alpha).coerceIn(0f, 1f),
    translateXDp = sample(p, translateX),
    translateYDp = sample(p, translateY),
    scale = sample(p, scale),
    rotationDeg = sample(p, rotation),
    rotationXDeg = sample(p, rotationX),
    rotationYDeg = sample(p, rotationY)
)

/** 多段均匀关键帧采样(ObjectAnimator 多值语义):2 停点线性(可过冲外插),多停点分段线性。 */
private fun sample(p: Float, stops: FloatArray): Float {
    if (stops.size == 1) return stops[0]
    val last = stops.size - 1
    val x = p * last
    val i = x.toInt()
    return when {
        i < 0 -> stops[0]
        i >= last -> {
            if (p <= 1f) {
                stops[last]
            } else {
                val t = x - (last - 1)
                stops[last - 1] + (stops[last] - stops[last - 1]) * t
            }
        }
        else -> stops[i] + (stops[i + 1] - stops[i]) * (x - i)
    }
}

internal fun isExitTransitionExpired(startedAtMs: Long, nowMs: Long, durationMs: Long): Boolean =
    startedAtMs > 0L && nowMs - startedAtMs >= durationMs

/** 换行退场缓动(历史档):起步慢、加速离场,避免线性滑出显得僵硬。 */
internal fun transitionExitEasing(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return t * t * t
}

/** 换行入场缓动(历史档):起步快、减速落位,收尾无弹跳(AOD 小字场景保持克制)。 */
internal fun transitionEnterEasing(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    val u = 1f - t
    return 1f - u * u * u
}

/**
 * 退场缓动按档分派:历史档沿用 [transitionExitEasing](cubic easeIn);
 * HyperLyric 预设统一 FastOutLinearIn(0.4,0,1,1)——起步即带速度地加速离场。
 */
internal fun lineTransitionExitEasing(mode: String, progress: Float): Float =
    lineTransitionPreset(mode)?.let { applyLineTransitionEase(it.outEase, progress) }
        ?: transitionExitEasing(progress)

/**
 * 入场缓动按档分派:历史档沿用 [transitionEnterEasing] 无弹跳;
 * HyperLyric 预设按配方(过冲 OvershootInterpolator 1.6/1.5/2.0/1.0 落位、
 * 柔缓着陆 QuintEaseOut、其余 FastOutSlowIn)。
 */
internal fun lineTransitionEnterEasing(mode: String, progress: Float): Float =
    lineTransitionPreset(mode)?.let { applyLineTransitionEase(it.inEase, progress) }
        ?: transitionEnterEasing(progress)

private fun applyLineTransitionEase(ease: LineTransitionEase, progress: Float): Float =
    when (ease) {
        LineTransitionEase.FAST_OUT_LINEAR_IN -> fastOutLinearInEase(progress)
        LineTransitionEase.FAST_OUT_SLOW_IN -> fastOutSlowInEase(progress)
        LineTransitionEase.OVERSHOOT_1_6 -> overshootEase(progress, 1.6f)
        LineTransitionEase.OVERSHOOT_1_5 -> overshootEase(progress, 1.5f)
        LineTransitionEase.OVERSHOOT_2_0 -> overshootEase(progress, 2.0f)
        LineTransitionEase.OVERSHOOT_1_0 -> overshootEase(progress, 1.0f)
        LineTransitionEase.QUINT_OUT -> quintOutEase(progress)
    }

/** Android OvershootInterpolator 同式:进度越过 1 后回弹落位,tension 越大越过越多。 */
internal fun overshootEase(progress: Float, tension: Float): Float {
    val t = progress.coerceAtLeast(0f) - 1f
    return t * t * ((tension + 1f) * t + tension) + 1f
}

/** 五次方缓出(1-(1-t)⁵,Glider Skill.QuintEaseOut 同式),柔缓着陆的收落曲线。 */
internal fun quintOutEase(progress: Float): Float {
    val u = 1f - progress.coerceIn(0f, 1f)
    return 1f - u * u * u * u * u
}

/** FastOutLinearIn 等价曲线:cubic-bezier(0.4, 0, 1, 1),与 Android 同名插值器同参。 */
internal fun fastOutLinearInEase(progress: Float): Float =
    cubicBezierEase(progress.coerceIn(0f, 1f), 0.4f, 0f, 1f, 1f)

/** FastOutSlowIn 等价曲线:cubic-bezier(0.4, 0, 0.2, 1),与 Android 同名插值器同参。 */
internal fun fastOutSlowInEase(progress: Float): Float =
    cubicBezierEase(progress.coerceIn(0f, 1f), 0.4f, 0f, 0.2f, 1f)

/** cubic-bezier(x1,y1,x2,y2) 在 t 处的取值:反解 x(u)=t 后求 y(u)(Newton 迭代 6 步)。 */
internal fun cubicBezierEase(t: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val target = t.coerceIn(0f, 1f)
    var u = target
    repeat(6) {
        val dx = bezierSlope(u, x1, x2)
        if (dx > 1e-6f) {
            u = (u - (bezierCurve(u, x1, x2) - target) / dx).coerceIn(0f, 1f)
        }
    }
    return bezierCurve(u, y1, y2)
}

private fun bezierCurve(u: Float, p1: Float, p2: Float): Float {
    val v = 1f - u
    return 3f * v * v * u * p1 + 3f * v * u * u * p2 + u * u * u
}

private fun bezierSlope(u: Float, p1: Float, p2: Float): Float {
    val v = 1f - u
    return 3f * v * v * p1 + 6f * v * u * (p2 - p1) + 3f * u * u * (1f - p2)
}

internal fun rubyClipTop(baseBaseline: Float, baseAscent: Float, rubyHeight: Float): Float =
    baseBaseline + baseAscent - rubyHeight

internal fun shouldStartLineTransition(
    lineChanged: Boolean,
    transitionMode: String,
    handoffActive: Boolean,
    resuming: Boolean = false
): Boolean = lineChanged && transitionMode != "None" && !handoffActive && !resuming

internal fun isSongChangeMetadataPlaceholder(
    original: String,
    metadata: String,
    lineStartMs: Long,
    lineEndMs: Long,
    hasTimedWords: Boolean
): Boolean = metadata.isNotBlank() && original == metadata &&
    lineEndMs <= lineStartMs && !hasTimedWords

internal fun shouldMorphSongChangeMetadata(
    previousOriginal: String,
    previousMetadata: String,
    previousLineStartMs: Long,
    previousLineEndMs: Long,
    previousHasTimedWords: Boolean,
    nextMetadata: String,
    nextMetadataVisible: Boolean
): Boolean = nextMetadataVisible && previousMetadata == nextMetadata &&
    isSongChangeMetadataPlaceholder(
        previousOriginal,
        previousMetadata,
        previousLineStartMs,
        previousLineEndMs,
        previousHasTimedWords
    )

internal fun interpolateAodColor(start: Int, end: Int, progress: Float): Int {
    val value = progress.coerceIn(0f, 1f)
    fun channel(from: Int, to: Int): Int = (from + (to - from) * value).roundToInt()
    return Color.argb(
        channel(Color.alpha(start), Color.alpha(end)),
        channel(Color.red(start), Color.red(end)),
        channel(Color.green(start), Color.green(end)),
        channel(Color.blue(start), Color.blue(end))
    )
}
