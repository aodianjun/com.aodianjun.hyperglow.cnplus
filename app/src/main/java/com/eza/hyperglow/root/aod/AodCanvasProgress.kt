package com.eza.hyperglow.root.aod

internal fun splitContinuousFill(progress: Float, lineWidths: List<Float>): List<Float> {
    val totalWidth = lineWidths.sumOf { it.coerceAtLeast(0f).toDouble() }.toFloat()
    if (totalWidth <= 0f) return lineWidths.map { 0f }
    var precedingWidth = 0f
    return lineWidths.map { width ->
        val safeWidth = width.coerceAtLeast(0f)
        val local = continuousFillAt(progress, totalWidth, precedingWidth, safeWidth)
        precedingWidth += safeWidth
        local
    }
}

/**
 * 横向进度档的行进度解析(与 LyricGlowRenderer.fillMode 同源,纯函数可单测):
 * - "Left to right (main only)"(行级):按累计宽度把整块进度分摊到各行 —— 各行依次
 *   从左到右扫光(ARCHITECTURE「行级的从左到右近似」);
 * - "Left to right (whole block)"(整块兼容):所有可见行同时以同一进度 X 方向扫描。
 * 其余取值(旧词表/未知值)落入行级逐行推进,与上游归一一致。
 */
internal fun horizontalRowProgress(
    fillMode: String,
    eased: Float,
    lineWidths: List<Float>
): List<Float> =
    if (fillMode == LyricGlowRenderer.FILL_LEFT_TO_RIGHT_WHOLE_BLOCK) {
        lineWidths.map { eased }
    } else {
        splitContinuousFill(eased, lineWidths)
    }

/**
 * 整块扫光总进度:行级时间有效时用行区间;纯逐字源回退到全局首词→末词范围。
 */
internal fun unifiedBlockProgress(
    positionMs: Long,
    lineStartMs: Long,
    lineEndMs: Long,
    words: List<AodCanvasWord>
): Float {
    if (lineEndMs > lineStartMs) return blockProgressNormalized(positionMs, lineStartMs, lineEndMs)
    var startMs = Long.MAX_VALUE
    var endMs = Long.MIN_VALUE
    words.forEach { word ->
        if (word.startMs >= 0L && word.endMs > word.startMs) {
            if (word.startMs < startMs) startMs = word.startMs
            if (word.endMs > endMs) endMs = word.endMs
        }
    }
    if (startMs != Long.MAX_VALUE && endMs > startMs) {
        return blockProgressNormalized(positionMs, startMs, endMs)
    }
    return blockProgressNormalized(positionMs, lineStartMs, lineEndMs)
}

private fun blockProgressNormalized(positionMs: Long, startMs: Long, endMs: Long): Float =
    if (endMs <= startMs) {
        if (positionMs >= endMs) 1f else 0f
    } else {
        ((positionMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
    }

internal fun continuousFillAt(
    progress: Float,
    totalWidth: Float,
    precedingWidth: Float,
    width: Float
): Float = if (width <= 0f || totalWidth <= 0f) {
    0f
} else {
    ((progress.coerceIn(0f, 1f) * totalWidth - precedingWidth) / width).coerceIn(0f, 1f)
}
