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

// ---------------------------------------------------------------------------
// 「BetterLyrics」逐字发光档(参考 jayfunc/BetterLyrics):词级效果的纯判定。
// ---------------------------------------------------------------------------

/** BetterLyrics 档的长词阈值:词时长不低于此值才放大/发光(参考其 LongDurationSyllable 700ms)。 */
internal const val BETTER_LYRICS_LONG_WORD_MS = 700L

/**
 * BetterLyrics 档长词播放中的放大峰值(参考其 LyricsScaleEffectAmount 115%:
 * LyricsAnimator 长音节播放中放大到 1.15、唱完回落 1.0)。
 */
internal const val BETTER_LYRICS_LONG_WORD_SCALE_PEAK = 1.15f

/** 逐字卡拉OK基础峰值(Gradient 档与 BetterLyrics 档短词沿用,既有行为不变)。 */
internal const val WORD_KARAOKE_BASE_SCALE_PEAK = 1.0505f

/**
 * 单次播放的词放大峰值:BetterLyrics 档长词(≥[BETTER_LYRICS_LONG_WORD_MS])放大到
 * [BETTER_LYRICS_LONG_WORD_SCALE_PEAK],其余沿用 [WORD_KARAOKE_BASE_SCALE_PEAK]。
 * 纯函数,可单测。
 */
internal fun wordKaraokeScalePeak(betterLyrics: Boolean, wordDurationMs: Long): Float =
    if (betterLyrics && wordDurationMs >= BETTER_LYRICS_LONG_WORD_MS) {
        BETTER_LYRICS_LONG_WORD_SCALE_PEAK
    } else {
        WORD_KARAOKE_BASE_SCALE_PEAK
    }
