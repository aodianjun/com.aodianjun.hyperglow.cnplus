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
 * 是否走共享 LyricGlowRenderer 预览管线:
 * 行级同步源、无词级时间源、或开启发光 —— 与预览同源渲染;
 * 仅"逐字时间源 + 关闭发光 + 非行级同步"保留逐字卡拉OK路径。
 */
internal fun usesPreviewGlowPipeline(
    animationMode: String,
    timed: Boolean,
    lineLevelSync: Boolean,
    glowMode: String
): Boolean = animationMode != "Minimal" && (glowMode != "Off" || lineLevelSync || !timed)

/** 整块扫光总进度:行级时间有效时用行区间;纯逐字源回退到全局首词→末词范围。 */
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
