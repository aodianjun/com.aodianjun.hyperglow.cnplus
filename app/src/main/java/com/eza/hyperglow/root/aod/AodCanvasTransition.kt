package com.eza.hyperglow.root.aod

import android.graphics.Color
import kotlin.math.roundToInt

/** 换行动画入场时长(两条时间线共用同一 elapsed,入场较长者决定总长)。 */
internal const val ENTER_TRANSITION_MS = 210L

/** 换行动画退场时长。 */
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

/**
 * 参考 HyperLyric(limczhh/HyperLyric)「歌词切换动画」的序列式三档:
 * 退场完成后才换字入场([isSequentialLineTransition]);历史档保持退场/入场叠加。
 */
private val SEQUENTIAL_MODES = setOf("Fade left", "Landing", "Slide swap")

internal fun isSequentialLineTransition(mode: String): Boolean = mode in SEQUENTIAL_MODES

/**
 * 入场基准时长:历史档 210ms;参考 HyperLyric 档 450ms(柔缓着陆 700ms)。
 * 速档缩放后:历史 315/210/126ms,Fade left 与 Slide swap 675/450/270ms,Landing 1050/700/420ms。
 */
internal fun enterTransitionMs(mode: String, speed: String): Long {
    val base = when (mode) {
        "Fade left", "Slide swap" -> 450L
        "Landing" -> 700L
        else -> ENTER_TRANSITION_MS
    }
    return (base * lineTransitionDurationScale(speed)).toLong()
}

/**
 * 退场基准时长:历史档 130ms;参考 HyperLyric 档 300ms。
 * 速档缩放后:历史 195/130/78ms,参考档 450/300/180ms。
 */
internal fun exitTransitionMs(mode: String, speed: String): Long {
    val base = if (isSequentialLineTransition(mode)) 300L else EXIT_TRANSITION_MS
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

// 参考 HyperLyric 档的运动参数:Fade 族横移为行块宽的 1/4(daimajia FadeOutLeft/FadeInRight
// 同参),Slide swap 整宽滑出/滑入(daimajia SlideOutLeft/SlideInRight 同参),着陆自 1.2 收落。
private const val HYPER_DRIFT_FRACTION = 0.25f
private const val LANDING_SCALE_FROM = 1.2f

/**
 * 换行动画单帧参数:alpha 直接作图层透明度;translate* 为 dp 位移(调用方乘 density);
 * scale 为绕内容中心的放缩比。退场/入场共用同一数据形状,由方向不同的两个函数产出。
 */
internal data class LineTransitionFrame(
    val alpha: Float,
    val translateXDp: Float = 0f,
    val translateYDp: Float = 0f,
    val scale: Float = 1f
)

/**
 * 退场帧(progress 0→1,对应 [exitTransitionMs]):
 * - `Fade up` 淡出并上移 14dp(历史默认,逐像素不变)
 * - `Crossfade` 纯淡出
 * - `Slide up` 上滑淡出:透明度按 1-p² 保持更久,位移加倍
 * - `Slide left` 左滑淡出:横向 28dp
 * - `Zoom` 淡出并轻微放大
 * - `Fade left` / `Landing` 参考 HyperLyric FadeOutLeft:淡出并左移 1/4 行块宽
 * - `Slide swap` 参考 HyperLyric SlideOutLeft:淡出并整宽滑出
 * - 未知值退化为 `Fade up`(与 wire 归一化兜底一致)
 */
internal fun lineTransitionExitFrame(
    mode: String,
    progress: Float,
    blockWidthDp: Float = 0f
): LineTransitionFrame {
    val p = progress.coerceIn(0f, 1f)
    return when (mode) {
        "Crossfade", "None" -> LineTransitionFrame(alpha = 1f - p)
        "Slide up" -> LineTransitionFrame(alpha = 1f - p * p, translateYDp = -SLIDE_DP * p)
        "Slide left" -> LineTransitionFrame(alpha = 1f - p * p, translateXDp = -SLIDE_DP * p)
        "Zoom" -> LineTransitionFrame(alpha = 1f - p, scale = 1f + ZOOM_EXIT_SCALE_GAIN * p)
        "Fade left", "Landing" -> LineTransitionFrame(
            alpha = 1f - p,
            translateXDp = -HYPER_DRIFT_FRACTION * blockWidthDp * p
        )
        "Slide swap" -> LineTransitionFrame(alpha = 1f - p, translateXDp = -blockWidthDp * p)
        else -> LineTransitionFrame(alpha = 1f - p, translateYDp = -FADE_UP_DP * p)
    }
}

/**
 * 入场帧(progress 0→1,对应 [enterTransitionMs]):
 * - `Fade up` 淡入并从 14dp 下方升至原位(历史默认,逐像素不变)
 * - `Crossfade` 纯淡入
 * - `Slide up` 上滑淡入:透明度提前拉满,自 28dp 下方滑入
 * - `Slide left` 左滑淡入:自 28dp 右侧滑入
 * - `Zoom` 自 0.92 放大至 1 淡入
 * - `Fade left` 参考 HyperLyric FadeInRight:自 1/4 行块宽右侧淡入落位
 * - `Landing` 参考 HyperLyric LandingSoft:自 1.2 收落至 1 并淡入
 * - `Slide swap` 参考 HyperLyric SlideInRight:自整宽右侧滑入落位
 * - 未知值退化为 `Fade up`
 *
 * 参考档的 progress 由过冲缓动产生(可短暂 >1 表示越过落位点回弹):
 * 位移/缩放保留越过量,alpha 钳制 1;历史档无过冲。
 */
internal fun lineTransitionEnterFrame(
    mode: String,
    progress: Float,
    blockWidthDp: Float = 0f
): LineTransitionFrame {
    return when (mode) {
        "Crossfade", "None" -> {
            val p = progress.coerceIn(0f, 1f)
            LineTransitionFrame(alpha = p)
        }
        "Slide up" -> {
            val p = progress.coerceIn(0f, 1f)
            LineTransitionFrame(alpha = minOf(1f, p * 1.6f), translateYDp = SLIDE_DP * (1f - p))
        }
        "Slide left" -> {
            val p = progress.coerceIn(0f, 1f)
            LineTransitionFrame(alpha = minOf(1f, p * 1.6f), translateXDp = SLIDE_DP * (1f - p))
        }
        "Zoom" -> {
            val p = progress.coerceIn(0f, 1f)
            LineTransitionFrame(
                alpha = p,
                scale = ZOOM_ENTER_SCALE_FROM + (1f - ZOOM_ENTER_SCALE_FROM) * p
            )
        }
        "Fade left" -> {
            val p = progress.coerceAtLeast(0f)
            LineTransitionFrame(
                alpha = p.coerceIn(0f, 1f),
                translateXDp = HYPER_DRIFT_FRACTION * blockWidthDp * (1f - p)
            )
        }
        "Landing" -> {
            val p = progress.coerceAtLeast(0f)
            LineTransitionFrame(
                alpha = p.coerceIn(0f, 1f),
                scale = LANDING_SCALE_FROM + (1f - LANDING_SCALE_FROM) * p
            )
        }
        "Slide swap" -> {
            val p = progress.coerceAtLeast(0f)
            LineTransitionFrame(
                alpha = p.coerceIn(0f, 1f),
                translateXDp = blockWidthDp * (1f - p)
            )
        }
        else -> {
            val p = progress.coerceIn(0f, 1f)
            LineTransitionFrame(alpha = p, translateYDp = FADE_UP_DP * (1f - p))
        }
    }
}

internal fun isExitTransitionExpired(startedAtMs: Long, nowMs: Long, durationMs: Long): Boolean =
    startedAtMs > 0L && nowMs - startedAtMs >= durationMs

/** 换行退场缓动:起步慢、加速离场,避免线性滑出显得僵硬。 */
internal fun transitionExitEasing(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return t * t * t
}

/** 换行入场缓动:起步快、减速落位,收尾无弹跳(AOD 小字场景保持克制,历史档沿用)。 */
internal fun transitionEnterEasing(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    val u = 1f - t
    return 1f - u * u * u
}

/**
 * 退场缓动按档分派:历史档沿用 [transitionExitEasing];
 * 参考 HyperLyric 档用 FastOutLinearIn(0.4,0,1,1)——起步即带速度地加速离场。
 */
internal fun lineTransitionExitEasing(mode: String, progress: Float): Float = when (mode) {
    "Fade left", "Landing", "Slide swap" -> fastOutLinearInEase(progress)
    else -> transitionExitEasing(progress)
}

/**
 * 入场缓动按档分派(参考 HyperLyric):
 * - `Fade left` / `Slide swap` 过冲落位(Android OvershootInterpolator 1.6 / 2.0 同式),
 *   进度短暂 >1 表示越过落位点再回弹;
 * - `Landing` 五次方缓出(Glider QuintEaseOut 同式)柔缓收落;
 * - 历史档沿用 [transitionEnterEasing] 无弹跳。
 */
internal fun lineTransitionEnterEasing(mode: String, progress: Float): Float = when (mode) {
    "Fade left" -> overshootEase(progress, 1.6f)
    "Slide swap" -> overshootEase(progress, 2.0f)
    "Landing" -> quintOutEase(progress)
    else -> transitionEnterEasing(progress)
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
