package com.eza.hyperglow.root.aod

import android.graphics.Color
import kotlin.math.roundToInt

/** 换行动画入场时长(两条时间线共用同一 elapsed,入场较长者决定总长)。 */
internal const val ENTER_TRANSITION_MS = 210L

/** 换行动画退场时长。 */
internal const val EXIT_TRANSITION_MS = 130L

// 各换行动画模式的运动参数(dp / 缩放比),预览与实机共用这一份配方。
private const val FADE_UP_DP = 14f
private const val SLIDE_DP = 28f
private const val ZOOM_EXIT_SCALE_GAIN = 0.06f
private const val ZOOM_ENTER_SCALE_FROM = 0.92f

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
 * 退场帧(progress 0→1,对应 [EXIT_TRANSITION_MS]):
 * - `Fade up` 淡出并上移 14dp(历史默认,逐像素不变)
 * - `Crossfade` 纯淡出
 * - `Slide up` 上滑淡出:透明度按 1-p² 保持更久,位移加倍
 * - `Slide left` 左滑淡出:横向 28dp
 * - `Zoom` 淡出并轻微放大
 * - 未知值退化为 `Fade up`(与 wire 归一化兜底一致)
 */
internal fun lineTransitionExitFrame(mode: String, progress: Float): LineTransitionFrame {
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
 * 入场帧(progress 0→1,对应 [ENTER_TRANSITION_MS]):
 * - `Fade up` 淡入并从 14dp 下方升至原位(历史默认,逐像素不变)
 * - `Crossfade` 纯淡入
 * - `Slide up` 上滑淡入:透明度提前拉满,自 28dp 下方滑入
 * - `Slide left` 左滑淡入:自 28dp 右侧滑入
 * - `Zoom` 自 0.92 放大至 1 淡入
 * - 未知值退化为 `Fade up`
 */
internal fun lineTransitionEnterFrame(mode: String, progress: Float): LineTransitionFrame {
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

internal fun isExitTransitionExpired(startedAtMs: Long, nowMs: Long, durationMs: Long): Boolean =
    startedAtMs > 0L && nowMs - startedAtMs >= durationMs

/** 换行退场缓动:起步慢、加速离场,避免线性滑出显得僵硬。 */
internal fun transitionExitEasing(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    return t * t * t
}

/** 换行入场缓动:起步快、减速落位,收尾无弹跳(AOD 小字场景保持克制)。 */
internal fun transitionEnterEasing(progress: Float): Float {
    val t = progress.coerceIn(0f, 1f)
    val u = 1f - t
    return 1f - u * u * u
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
