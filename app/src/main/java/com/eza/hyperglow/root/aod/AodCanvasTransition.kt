package com.eza.hyperglow.root.aod

import android.graphics.Color
import kotlin.math.roundToInt

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
