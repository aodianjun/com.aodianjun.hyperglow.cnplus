package com.eza.hyperglow.root.lockscreen

import android.view.View
import kotlin.math.abs
import kotlin.math.roundToInt

internal data class LockscreenNotificationCandidate(
    val className: String,
    val top: Int,
    val bottom: Int = top + 1,
    val left: Int = 0,
    val right: Int = 0
)

internal data class LockscreenNotificationClipBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
)

internal data class LockscreenNotificationBounds(
    val top: Int,
    val bottom: Int,
    val left: Int = 0,
    val right: Int = 0
)

internal data class LockscreenNotificationGeometry(
    val effectiveBounds: LockscreenNotificationBounds?,
    val cachedBounds: LockscreenNotificationBounds?
)

/**
 * Notification rows animate while the keyguard settles (fade in/out, expand/collapse), so their
 * union bounds can jitter by a few px every frame. The lyric card's placement is derived from
 * these bounds, so following every frame makes the card "jump" during the animation. Only adopt a
 * new bounds value once it differs from the last applied one by more than this dead-band (dp).
 */
internal const val NOTIFICATION_GEOMETRY_DEAD_BAND_DP = 8f

private val LOCKSCREEN_NOTIFICATION_CONTENT_CLASSES = setOf(
    "ExpandableNotificationRow",
    "MiuiMediaHeaderView",
    "ZenModeView"
)

internal fun isLockscreenNotificationContentClass(className: String): Boolean =
    className.substringAfterLast('.') in LOCKSCREEN_NOTIFICATION_CONTENT_CLASSES

internal fun shouldIncludeLockscreenNotificationChild(
    visibility: Int,
    alpha: Float,
    linkageActive: Boolean
): Boolean = visibility != View.GONE &&
    (linkageActive || visibility == View.VISIBLE && alpha > MIN_VISIBLE_ALPHA)

internal fun lockscreenNotificationCandidateFromLayout(
    className: String,
    stackLeft: Int,
    stackTop: Int,
    childX: Float,
    childY: Float,
    layoutWidth: Int,
    layoutHeight: Int,
    actualHeight: Int,
    clipTopAmount: Int,
    clipBottomAmount: Int,
    clipBounds: LockscreenNotificationClipBounds?
): LockscreenNotificationCandidate? {
    val effectiveHeight = actualHeight.takeIf { it > 0 } ?: layoutHeight
    if (layoutWidth <= 0 || effectiveHeight <= 0) return null
    val visibleLeft = maxOf(0, clipBounds?.left ?: 0).coerceAtMost(layoutWidth)
    val visibleRight = minOf(layoutWidth, clipBounds?.right ?: layoutWidth)
        .coerceAtLeast(visibleLeft)
    val visibleTop = maxOf(clipTopAmount.coerceAtLeast(0), clipBounds?.top ?: 0)
        .coerceAtMost(effectiveHeight)
    val visibleBottom = minOf(
        effectiveHeight - clipBottomAmount.coerceAtLeast(0),
        clipBounds?.bottom ?: effectiveHeight
    ).coerceIn(visibleTop, effectiveHeight)
    if (visibleRight <= visibleLeft || visibleBottom <= visibleTop) return null
    val childLeft = stackLeft + childX.roundToInt()
    val childTop = stackTop + childY.roundToInt()
    return LockscreenNotificationCandidate(
        className = className,
        top = childTop + visibleTop,
        bottom = childTop + visibleBottom,
        left = childLeft + visibleLeft,
        right = childLeft + visibleRight
    )
}

internal fun topmostLockscreenNotificationTop(
    candidates: List<LockscreenNotificationCandidate>,
    hostHeight: Int
): Int? = lockscreenNotificationBounds(candidates, hostHeight)?.top

internal fun lockscreenNotificationBounds(
    candidates: List<LockscreenNotificationCandidate>,
    hostHeight: Int
): LockscreenNotificationBounds? {
    val visible = candidates.asSequence()
        .filter { isLockscreenNotificationContentClass(it.className) }
        .mapNotNull {
            val top = it.top.coerceIn(0, hostHeight)
            val bottom = it.bottom.coerceIn(0, hostHeight)
            if (bottom > top) {
                LockscreenNotificationBounds(top, bottom, it.left, it.right)
            } else {
                null
            }
        }
        .toList()
    if (visible.isEmpty()) return null
    return LockscreenNotificationBounds(
        visible.minOf { it.top },
        visible.maxOf { it.bottom },
        visible.map { it.left }.filter { it > 0 }.minOrNull() ?: 0,
        visible.map { it.right }.filter { it > 0 }.maxOrNull() ?: 0
    )
}

internal fun resolveLockscreenNotificationGeometry(
    hasNotification: Boolean,
    current: LockscreenNotificationBounds?,
    lastValid: LockscreenNotificationBounds?,
    hostHeight: Int
): LockscreenNotificationGeometry {
    if (!hasNotification) return LockscreenNotificationGeometry(null, null)
    val retained = current ?: lastValid
    return LockscreenNotificationGeometry(
        effectiveBounds = retained ?: LockscreenNotificationBounds(0, hostHeight),
        cachedBounds = retained
    )
}

/**
 * Dead-band stabilizer for the notification union bounds. Notification rows animate while the
 * keyguard settles, so their bounds can jitter by a few px every frame; the lyric card's placement
 * is derived from them, so following every frame makes the card "jump". Only adopt a new value once
 * it differs from the last applied one by at least [deadBandPx] in either top or bottom; otherwise
 * keep the last applied (stable) value. A null [current] clears the bounds as before.
 */
internal fun stabilizeNotificationBounds(
    current: LockscreenNotificationBounds?,
    lastApplied: LockscreenNotificationBounds?,
    deadBandPx: Int
): LockscreenNotificationBounds? {
    if (current == null) return null
    val last = lastApplied ?: return current
    val topDrift = abs(current.top - last.top)
    val bottomDrift = abs(current.bottom - last.bottom)
    return if (topDrift < deadBandPx && bottomDrift < deadBandPx) last else current
}
