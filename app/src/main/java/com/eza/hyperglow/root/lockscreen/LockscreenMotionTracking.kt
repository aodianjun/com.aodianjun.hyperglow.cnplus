package com.eza.hyperglow.root.lockscreen

import java.lang.reflect.Method

internal class LockscreenMotionChangeTracker {
    private var fingerprint: Long? = null

    fun update(next: Long): Boolean {
        val previous = fingerprint
        fingerprint = next
        return previous != null && previous != next
    }

    fun clear() {
        fingerprint = null
    }
}

internal data class NotificationMotionMethods(
    val contentClassName: String?,
    val actualHeight: Method?,
    val clipTopAmount: Method?,
    val clipBottomAmount: Method?
)
