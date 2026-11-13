package com.eza.hyperglow.root.aod

import com.eza.hyperglow.aod.INTERLUDE_COUNTDOWN_DELAY_MS
import kotlin.math.abs

/**
 * 长间奏倒计时圆点（纯函数，实机画布与 App 内预览同源）。
 *
 * 参考 HyperLyric（limczhh/HyperLyric）CountdownDotsRenderer：
 * - 3 个圆点；半径 = 0.25 × 主行字号，间距 = 0.34 × 主行字号；
 * - 进度逐点点亮（smoothstep 缓动），每点点亮时放大 40%（1.0 → 1.4 倍）；
 * - 进度越过 60%（3/5）后逐点渐隐，最右先隐；圆点颜色：底色 = 未唱色 @128
 *   （默认即参考实现的「白 @128」），高亮 = 已唱色（默认白）。
 *
 * 本文件只放可离线单测的数学；android.graphics 绘制适配层见 [InterludeDotsRenderer]。
 */

/** 圆点个数（参考 HyperLyric DOT_COUNT 同值）。 */
internal const val INTERLUDE_DOT_COUNT = 3

/** 圆点半径相对主行字号的倍率（参考 HyperLyric RADIUS_TEXT_SIZE_FACTOR 同值）。 */
internal const val INTERLUDE_DOT_RADIUS_TEXT_SIZE_FACTOR = 0.25f

/** 圆点间距相对主行字号的倍率（参考 HyperLyric DOT_GAP_TEXT_SIZE_FACTOR 同值）。 */
internal const val INTERLUDE_DOT_GAP_TEXT_SIZE_FACTOR = 0.34f

/** 圆点点亮时的放大比例（1.0 → 1.4 倍；参考 HyperLyric SCALE_AMOUNT 同值）。 */
internal const val INTERLUDE_DOT_SCALE_AMOUNT = 0.4f

/** 渐隐段起点（进度 3/5 = 60% 后逐点渐隐；参考 HyperLyric FADE_START_PROGRESS 同值）。 */
internal const val INTERLUDE_DOT_FADE_START_PROGRESS = 3f / 5f

/** 圆点底色不透明度（参考 HyperLyric「白 @128」；作用于未唱色）。 */
internal const val INTERLUDE_DOT_BACKGROUND_ALPHA = 128

/**
 * 本面生效的倒计时窗口（`first..last` 闭区间记法，跨度 = last − first；已按「显示下一行」
 * 映射延迟）：
 * - [enabled] = 本面「长间奏倒计时圆点」开关；
 * - [showsNextLine] = 本面「显示下一行歌词」或「辅助文字显示第二行歌词」开启——
 *   该面已在屏幕上展示下一行，圆点无需为上一行保留 1s 停留，从间奏起点即开始
 *   （语义对应参考实现开了歌词预览即从 end 处开始；两开关都关时保留
 *   [INTERLUDE_COUNTDOWN_DELAY_MS] 延迟，与参考实现默认档一致）。
 *
 * null = 本面不显示（开关关闭 / 无长间奏 / 延迟后窗口退化）。
 */
internal fun interludeDotsWindow(
    interludeStartMs: Long,
    interludeEndMs: Long,
    enabled: Boolean,
    showsNextLine: Boolean
): LongRange? {
    if (!enabled) return null
    if (interludeStartMs <= 0L || interludeEndMs <= interludeStartMs) return null
    val delayMs = if (showsNextLine) 0L else INTERLUDE_COUNTDOWN_DELAY_MS
    val startMs = interludeStartMs + delayMs
    if (startMs >= interludeEndMs) return null
    return startMs..interludeEndMs
}

/**
 * 倒计时是否已在当前（前向投影）位置生效：窗口起点之后即绘制。
 * 不设终点上界——终点由渐隐段自然归零（进度 100% 时透明度为 0），
 * 旧内容在新行快照到达前保持「圆点已隐」而不是把上一行文本倒带回来。
 */
internal fun interludeDotsActive(window: LongRange?, positionMs: Long): Boolean =
    window != null && positionMs >= window.first

/** 窗口内进度（0..1，越界钳制）；窗口退化时 0。 */
internal fun interludeDotsProgress(positionMs: Long, window: LongRange): Float {
    val span = window.last - window.first
    if (span <= 0L) return 0f
    return ((positionMs - window.first).toFloat() / span.toFloat()).coerceIn(0f, 1f)
}

/** 圆点缓动（smoothstep，参考 HyperLyric 同式）。 */
internal fun interludeDotSmoothStep(value: Float): Float {
    val v = value.coerceIn(0f, 1f)
    return v * v * (3f - 2f * v)
}

/**
 * 单点「点亮进度」：总进度先按渐隐段起点归一（60% 前完成全部点亮），再按点序切分；
 * 每点在轮到它之前为 0，轮到时 0 → 1 点亮。
 */
internal fun interludeDotLightingProgress(progress: Float, index: Int): Float {
    val lighting = (progress / INTERLUDE_DOT_FADE_START_PROGRESS).coerceIn(0f, 1f)
    return (lighting * INTERLUDE_DOT_COUNT - index).coerceIn(0f, 1f)
}

/** 单点半径倍率：未点亮 1.0，点亮过程放大至 1 + [INTERLUDE_DOT_SCALE_AMOUNT]。 */
internal fun interludeDotScale(lightingProgress: Float): Float =
    1f + INTERLUDE_DOT_SCALE_AMOUNT * interludeDotSmoothStep(lightingProgress)

/** 单点半径（px）。 */
internal fun interludeDotRadius(textSize: Float, lightingProgress: Float): Float =
    textSize * INTERLUDE_DOT_RADIUS_TEXT_SIZE_FACTOR * interludeDotScale(lightingProgress)

/**
 * 单点渐隐系数（1 = 不隐，0 = 全隐）：进度越过 60% 后按「最右先隐」逐点渐隐，
 * 每个点的渐隐窗口为整段渐隐的 1/N（参考 HyperLyric dotExitAlpha 同式）。
 */
internal fun interludeDotExitAlpha(progress: Float, index: Int): Float {
    val fadeStart = INTERLUDE_DOT_FADE_START_PROGRESS
    if (progress <= fadeStart) return 1f
    val fadeProgress = ((progress - fadeStart) / (1f - fadeStart)).coerceIn(0f, 1f)
    val dotFade = (fadeProgress * INTERLUDE_DOT_COUNT - (INTERLUDE_DOT_COUNT - 1 - index))
        .coerceIn(0f, 1f)
    return 1f - interludeDotSmoothStep(dotFade)
}

/** 圆点簇最大宽度（px，全部点亮放大后；用于对齐基线）。 */
internal fun interludeDotsMaxWidth(textSize: Float): Float {
    val maxRadius = textSize * INTERLUDE_DOT_RADIUS_TEXT_SIZE_FACTOR *
        (1f + INTERLUDE_DOT_SCALE_AMOUNT)
    val gap = textSize * INTERLUDE_DOT_GAP_TEXT_SIZE_FACTOR
    return maxRadius * 2f * INTERLUDE_DOT_COUNT + gap * (INTERLUDE_DOT_COUNT - 1)
}

/**
 * 当前进度下的圆点簇实宽（px）：每点按自己的点亮态取半径，用于对齐——
 * 与参考实现 dotsWidth 同式（圆点放大时簇宽同步变化，居中/右对齐随之跟进）。
 */
internal fun interludeDotsWidth(textSize: Float, progress: Float): Float {
    val gap = textSize * INTERLUDE_DOT_GAP_TEXT_SIZE_FACTOR
    var width = gap * (INTERLUDE_DOT_COUNT - 1)
    repeat(INTERLUDE_DOT_COUNT) { index ->
        width += interludeDotRadius(textSize, interludeDotLightingProgress(progress, index)) * 2f
    }
    return width
}

/**
 * 内容字段解析的生效窗口（mapper 已按本面解析完毕；0/0 = 无）。
 */
internal fun interludeDotsWindowOf(startMs: Long, endMs: Long): LongRange? =
    if (startMs > 0L && endMs > startMs) startMs..endMs else null

/**
 * 圆点簇在逻辑帧内的水平起点（start/center/end 三档，与 [com.eza.hyperglow.root.aod.alignedStart] 同语义）：
 * 独立纯函数供预览复用（预览不持有画布的 alignedStart 状态）。
 */
internal fun interludeDotsStartX(
    widthPx: Float,
    frameWidthPx: Float,
    padLeftPx: Float,
    padRightPx: Float,
    alignment: String
): Float {
    val available = (frameWidthPx - padLeftPx - padRightPx).coerceAtLeast(0f)
    val offset = when (alignment) {
        "center" -> (available - widthPx) / 2f
        "end" -> available - widthPx
        else -> 0f
    }
    return padLeftPx + offset.coerceAtLeast(0f)
}

/** 浮点近似相等（预览/画布共用的小工具，供断言与漂移保护使用）。 */
internal fun interludeDotsNearlyEquals(a: Float, b: Float, tolerance: Float = 0.0001f): Boolean =
    abs(a - b) <= tolerance

/**
 * 圆点动画是否仍需要帧循环（纯函数，画布帧调度与单测共用）：窗口存在、位置已进窗口、
 * 且进度尚未走完（未到 1）时为真。窗口内但位置已越过终点即停止——圆点已全部隐去，
 * 再续帧只是空转（与并发行加入淡入结束即归零同式的"动画自带终止条件"）。
 *
 * 位置越界（position 早于窗口起点 / 已过终点）都不续帧：前者交回原有上一行滞留/下一行
 * 预览呈现，后者保持"圆点已隐"，不把上一行文本倒带回来。
 */
internal fun interludeDotsAnimating(
    window: LongRange?,
    positionMs: Long,
    speed: Float = 1f
): Boolean {
    if (window == null || speed <= 0f) return false
    if (positionMs < window.first) return false
    return interludeDotsProgress(positionMs, window) < 1f
}
