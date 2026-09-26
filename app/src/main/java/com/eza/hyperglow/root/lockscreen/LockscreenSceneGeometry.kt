package com.eza.hyperglow.root.lockscreen

import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.root.aod.metadataWidgetHeightDp
import com.eza.hyperglow.root.aod.textSizeModeMultiplier
import com.eza.hyperglow.root.surface.PlacementRect
import kotlin.math.roundToInt

internal data class LockscreenSceneRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal const val TOP_MARGIN_DP = 16f

internal const val NOTIFICATION_GAP_DP = 8f

internal const val BOTTOM_RESERVE_DP = 120f

internal const val MIN_WIDTH_DP = 160f

internal const val MIN_HEIGHT_DP = 72f

internal const val PROGRESS_HEIGHT_DP = 4f

internal const val PROGRESS_GAP_DP = 10f

private const val PRIMARY_BLOCK_HEIGHT_DP = 80f

private const val SECONDARY_BLOCK_HEIGHT_DP = 48f

internal const val REVERSE_ANCHOR_MINIMUM_DELAY_MS = 48L

internal const val REVERSE_ANCHOR_QUIET_PERIOD_MS = 32L

internal const val REVERSE_ANCHOR_FALLBACK_DEADLINE_MS = 240L

internal const val REVERSE_ANCHOR_PROBE_MS = 16L

internal const val LOCKSCREEN_ENTRY_SLIDE_DP = 20f

internal data class LockscreenLayoutResult(
    val rect: LockscreenSceneRect,
    val profile: CompiledSurfaceProfile,
    val progressVisible: Boolean
)

internal fun largestLockscreenFreeRegion(
    rootWidth: Int,
    rootHeight: Int,
    clockBottom: Int,
    margin: Int,
    bottomReserve: Int,
    notificationBounds: LockscreenNotificationBounds?
): PlacementRect {
    val safeTop = (clockBottom + margin).coerceIn(0, rootHeight)
    val safeBottom = (rootHeight - bottomReserve).coerceIn(safeTop, rootHeight)
    if (notificationBounds == null) {
        return PlacementRect(0f, safeTop.toFloat(), rootWidth.toFloat(), safeBottom.toFloat())
    }
    val above = PlacementRect(
        0f,
        safeTop.toFloat(),
        rootWidth.toFloat(),
        (notificationBounds.top - margin).coerceIn(safeTop, safeBottom).toFloat()
    )
    val below = PlacementRect(
        0f,
        (notificationBounds.bottom + margin).coerceIn(safeTop, safeBottom).toFloat(),
        rootWidth.toFloat(),
        safeBottom.toFloat()
    )
    return if (below.height > above.height) below else above
}

internal fun lockscreenCardRegionAfterNotifications(
    rootWidth: Int,
    rootHeight: Int,
    clockBottom: Int,
    topMargin: Int,
    notificationGap: Int,
    bottomReserve: Int,
    notificationBounds: LockscreenNotificationBounds?
): PlacementRect {
    val safeTop = (clockBottom + topMargin).coerceIn(0, rootHeight)
    val safeBottom = (rootHeight - bottomReserve).coerceIn(safeTop, rootHeight)
    val contentTop = notificationBounds?.bottom?.plus(notificationGap)
        ?.coerceIn(safeTop, safeBottom)
        ?: safeTop
    return PlacementRect(
        0f,
        contentTop.toFloat(),
        rootWidth.toFloat(),
        safeBottom.toFloat()
    )
}

internal fun estimatedLockscreenSceneHeight(
    profile: CompiledSurfaceProfile,
    density: Float,
    fontScale: Float = 1f
): Float {
    val textScale = textSizeModeMultiplier(profile.textSize, profile.textSizeCustom) *
        fontScale.coerceIn(0.8f, 1.5f)
    // 辅助文字行数估算:模式行(音译/翻译)+「辅助文字显示第二行歌词」的一行。
    val secondaryRows = (when (profile.secondaryMode) {
        "Both" -> 2
        "Transliteration", "Translation" -> 1
        else -> 0
    }) + if (profile.secondaryNextLine) 1 else 0
    val metadataHeight = if (profile.metadataVisible &&
        profile.widgets.any { it.type == "metadata" }
    ) metadataWidgetHeightDp(profile.metadataSizePercent) else 0f
    val progressHeight = if (profile.widgets.any { it.type == "media_progress" }) {
        PROGRESS_HEIGHT_DP + PROGRESS_GAP_DP
    } else {
        0f
    }
    val cardPadding = if (profile.backgroundStyle == "card") 24f else 0f
    val primaryBlockHeight = if (profile.lyricLineLimit == 0) {
        Float.POSITIVE_INFINITY
    } else {
        PRIMARY_BLOCK_HEIGHT_DP * profile.lyricLineLimit.coerceIn(1, 5) / 3f
    }
    return (primaryBlockHeight * textScale +
        SECONDARY_BLOCK_HEIGHT_DP * secondaryRows * textScale.coerceAtMost(1.25f) +
        metadataHeight + progressHeight + cardPadding) * density
}

internal fun maximumLockscreenClockBottom(hostHeight: Int, candidates: List<Int>): Int =
    candidates.maxOrNull()?.coerceIn(0, hostHeight) ?: 0

internal fun preferredLockscreenClockBottom(
    hostHeight: Int,
    preferred: Int,
    fallbacks: List<Int>
): Int = preferred.takeIf { it > 0 }?.coerceIn(0, hostHeight)
    ?: maximumLockscreenClockBottom(hostHeight, fallbacks)

internal fun frameLayoutGeometryChanged(
    currentWidth: Int,
    currentHeight: Int,
    currentLeft: Int,
    currentTop: Int,
    width: Int,
    height: Int,
    left: Int,
    top: Int
): Boolean = currentWidth != width || currentHeight != height ||
    currentLeft != left || currentTop != top

internal class LockscreenAnchorStabilityGate(
    private val minimumDelayMs: Long,
    private val quietPeriodMs: Long,
    private val fallbackDeadlineMs: Long
) {
    private var startedAt = Long.MIN_VALUE
    private var changedAt = Long.MIN_VALUE
    private var candidate: LockscreenSceneRect? = null
    private var expected: LockscreenSceneRect? = null

    fun start(nowElapsedMs: Long, expectedRect: LockscreenSceneRect?) {
        startedAt = nowElapsedMs
        changedAt = nowElapsedMs
        candidate = null
        expected = expectedRect
    }

    fun observe(rect: LockscreenSceneRect, nowElapsedMs: Long): Boolean {
        val elapsed = nowElapsedMs - startedAt
        if (elapsed < minimumDelayMs) return false
        expected?.let {
            if (rect == it) return true
            if (elapsed < fallbackDeadlineMs) return false
        }
        if (candidate != rect) {
            candidate = rect
            changedAt = nowElapsedMs
        }
        return nowElapsedMs - changedAt >= quietPeriodMs || elapsed >= fallbackDeadlineMs
    }

    fun clear() {
        startedAt = Long.MIN_VALUE
        changedAt = Long.MIN_VALUE
        candidate = null
        expected = null
    }
}

internal class LockscreenSettledRectTracker(
    private val quietPeriodMs: Long
) {
    private var candidate: LockscreenSceneRect? = null
    private var changedAt = Long.MIN_VALUE
    private var settled: LockscreenSceneRect? = null

    fun observe(rect: LockscreenSceneRect, nowElapsedMs: Long) {
        if (candidate != rect) {
            candidate = rect
            changedAt = nowElapsedMs
            return
        }
        promote(nowElapsedMs)
    }

    fun settledRect(nowElapsedMs: Long): LockscreenSceneRect? {
        promote(nowElapsedMs)
        return settled
    }

    fun clear() {
        candidate = null
        changedAt = Long.MIN_VALUE
        settled = null
    }

    private fun promote(nowElapsedMs: Long) {
        val current = candidate ?: return
        if (nowElapsedMs - changedAt >= quietPeriodMs) settled = current
    }
}

internal fun calculateLockscreenSceneRect(
    rootWidth: Int,
    rootHeight: Int,
    clockBottom: Int,
    topMargin: Int,
    bottomReserve: Int,
    desiredWidth: Int,
    notificationTop: Int? = null,
    anchor: String = "below_stock_clock",
    verticalBias: Float = 0.5f,
    maximumHeight: Int? = null
): LockscreenSceneRect {
    val width = desiredWidth.coerceIn(0, rootWidth.coerceAtLeast(0))
    val left = ((rootWidth - width) / 2).coerceAtLeast(0)
    val safeTop = (clockBottom + topMargin).coerceIn(0, rootHeight.coerceAtLeast(0))
    val normalBottom = (rootHeight - bottomReserve).coerceAtLeast(0)
    val collisionBottom = notificationTop?.minus(topMargin) ?: normalBottom
    val safeBottom = minOf(normalBottom, collisionBottom).coerceAtLeast(safeTop)
    val availableHeight = safeBottom - safeTop
    val height = (maximumHeight ?: availableHeight).coerceIn(0, availableHeight)
    val top = when (anchor) {
        "screen_center" -> safeTop + (availableHeight - height) / 2
        "screen_bottom_safe" -> safeBottom - height
        "custom_vertical_bias" -> safeTop +
            ((availableHeight - height) * verticalBias.coerceIn(0f, 1f)).roundToInt()
        else -> safeTop
    }
    val bottom = top + height
    return LockscreenSceneRect(left, top, left + width, bottom)
}
