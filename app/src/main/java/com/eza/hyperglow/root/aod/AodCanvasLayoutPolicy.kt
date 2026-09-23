package com.eza.hyperglow.root.aod

internal data class AodCanvasVerticalBounds(val top: Float, val bottom: Float)

internal fun unionAodCanvasVerticalBounds(
    first: AodCanvasVerticalBounds?,
    second: AodCanvasVerticalBounds?
): AodCanvasVerticalBounds? = when {
    first == null -> second
    second == null -> first
    else -> AodCanvasVerticalBounds(
        top = minOf(first.top, second.top),
        bottom = maxOf(first.bottom, second.bottom)
    )
}

internal fun edgeSafeAlignedStart(
    canvasWidth: Float,
    paddingLeft: Float,
    paddingRight: Float,
    visualLeft: Float,
    visualRight: Float,
    alignment: String,
    safetyInset: Float = 0f
): Float {
    val leftEdge = paddingLeft + safetyInset
    val rightEdge = canvasWidth - paddingRight - safetyInset
    return when (alignment) {
        "end" -> rightEdge - visualRight
        "center" -> (leftEdge + rightEdge - visualLeft - visualRight) / 2f
        else -> leftEdge - visualLeft
    }
}

internal fun sharedBlockClipBottom(progress: Float, top: Float, bottom: Float): Float =
    top + (bottom - top).coerceAtLeast(0f) * progress.coerceIn(0f, 1f)

internal fun shouldUseSharedLineLevelSweep(
    lineLevelSync: Boolean,
    hasOriginalLines: Boolean,
    animationMode: String,
    lineStartMs: Long,
    lineEndMs: Long
): Boolean = lineLevelSync && hasOriginalLines && animationMode != "Minimal" &&
    lineEndMs > lineStartMs

internal fun hasActiveCanvasTiming(
    lineLevelSync: Boolean,
    lineSyncFillMode: String,
    lineStartMs: Long,
    lineEndMs: Long,
    words: List<AodCanvasWord>,
    speed: Float = 1f
): Boolean {
    if (speed <= 0f) return false
    if (lineLevelSync && resolvedLineSyncFillMode(true, lineSyncFillMode) == "None") return false
    if (lineEndMs > lineStartMs) return true
    return words.any { it.endMs > it.startMs }
}

internal fun resolvedLineSyncFillMode(lineLevelSync: Boolean, configuredMode: String): String =
    if (!lineLevelSync) configuredMode
    else when (configuredMode) {
        // 行级同步时固定水平扫光，与预览（PreviewAnimatedLyric 整行从左到右渐进点亮）一致。
        // "None"/"Top to bottom" 等对歌词行几乎看不出水平扫光，统一归一到主行水平扫光。
        "Left to right (whole block)" -> "Left to right (whole block)"
        else -> "Left to right (main only)"
    }

internal fun resolvedLyricLayoutLineLimit(
    configuredLimit: Int,
    originalLength: Int,
    wordCount: Int
): Int = if (configuredLimit in 1..5) {
    configuredLimit
} else {
    maxOf(originalLength, wordCount, 1)
}

internal enum class AodCanvasVerticalAlignment { TOP, CENTER }
