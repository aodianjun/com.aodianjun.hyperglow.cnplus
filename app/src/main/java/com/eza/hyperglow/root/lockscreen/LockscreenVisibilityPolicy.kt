package com.eza.hyperglow.root.lockscreen

import com.eza.hyperglow.root.projection.LYRIC_SNAPSHOT_FRESH_MS
import com.eza.hyperglow.root.projection.LyricRetentionAnchor
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.edgeFor
import com.eza.hyperglow.root.projection.freezeAt
import com.eza.hyperglow.root.projection.isAuthorizedForPresentation
import com.eza.hyperglow.root.projection.pauseLingerRemainingMs
import com.eza.hyperglow.root.aod.PAUSED_AOD_KEEP_ALIVE_MS

internal data class LockscreenVisibilityInputs(
    val featureEnabled: Boolean,
    val supported: Boolean,
    val defaultTheme: Boolean,
    val primaryDisplay: Boolean,
    val keyguardShowing: Boolean,
    val bouncerShowing: Boolean,
    val freshSnapshot: Boolean,
    val usableArea: Boolean
)

/**
 * Lockscreen freshness tolerance while media is actively playing. The shared 5 s lyric-freshness
 * window is tighter than typical producer keepalive spacing, which makes the surface flap between
 * snapshots (visible -> stale -> hidden -> next keepalive -> visible). While playback is active the
 * line text and projected position are still valid well beyond 5 s, so a looser window keeps the
 * card stable without retaining stale content after playback actually stops.
 */
internal const val LOCKSCREEN_PLAYBACK_FRESH_MS = 15_000L

internal fun shouldShowLockscreen(inputs: LockscreenVisibilityInputs): Boolean =
    inputs.featureEnabled &&
        inputs.supported &&
        inputs.defaultTheme &&
        inputs.primaryDisplay &&
        inputs.keyguardShowing &&
        !inputs.bouncerShowing &&
        inputs.freshSnapshot &&
        inputs.usableArea

internal fun shouldRenderLockscreenSnapshot(
    snapshot: LyricSnapshot?,
    profileEnabled: Boolean,
    transitionFailed: Boolean,
    nowElapsedMs: Long
): Boolean {
    val current = snapshot ?: return false
    val freshMs = if (current.playbackActive) LOCKSCREEN_PLAYBACK_FRESH_MS else LYRIC_SNAPSHOT_FRESH_MS
    return current.visible &&
        current.isAuthorizedForPresentation() &&
        current.lockscreenEnabled &&
        profileEnabled &&
        !transitionFailed &&
        !current.metadata.startsWith("AOD DEMO") &&
        nowElapsedMs - current.updatedAtElapsedMs <= freshMs
}

internal fun freezeLockscreenSnapshot(
    snapshot: LyricSnapshot,
    nowElapsedMs: Long
): LyricSnapshot = snapshot.freezeAt(nowElapsedMs, keepAliveWhileFrozen = false).copy(
    playbackActive = false,
    pauseRetentionEligible = true
)

internal fun resolveLockscreenMediaSnapshot(
    latest: LyricSnapshot?,
    retained: LyricSnapshot?,
    mediaPlayerPresent: Boolean,
    transitionSourceActive: Boolean = false
): LyricSnapshot? = if (transitionSourceActive) {
    latest?.takeIf { it.visible } ?: retained
} else if (!mediaPlayerPresent) {
    null
} else {
    latest?.takeIf { it.visible } ?: retained
}

internal fun retainedLockscreenSnapshotAfterUpdate(
    incoming: LyricSnapshot,
    lastVisible: LyricSnapshot?,
    retained: LyricSnapshot?,
    anchor: LyricRetentionAnchor?,
    nowElapsedMs: Long,
    pauseLingerMs: Long = 5_000L,
    pauseRetentionEnabled: Boolean = true
): LyricSnapshot? = if (incoming.visible) {
    null
} else if (incoming.pauseRetentionEligible && pauseRetentionEnabled) {
    // 与 AOD 侧同一开关:「暂停时显示歌曲信息、歌词」关闭时,暂停边按终止态处理,
    // 锁屏立即清除歌曲信息与歌词,不再无条件驻留。
    val pauseAtElapsedMs = anchor.edgeFor(
        pauseRetentionEligible = true,
        fallbackElapsedMs = incoming.updatedAtElapsedMs.coerceIn(0L, nowElapsedMs)
    )
    val candidate = retained ?: lastVisible?.let {
        freezeLockscreenSnapshot(it, pauseAtElapsedMs)
    }
    candidate?.takeIf {
        pauseLingerRemainingMs(it.sampledAtElapsedMs, pauseLingerMs, nowElapsedMs) != null
    }
} else if (incoming.playbackActive) {
    val gapAtElapsedMs = anchor.edgeFor(
        pauseRetentionEligible = false,
        fallbackElapsedMs = nowElapsedMs
    )
    // 仍在播放的隐藏边是传输间隙,间隙自身的界就是电源宽限(同 AOD 侧),
    // 防止 producer 持续重发间隙时冻结歌词活过所有计时器。
    (retained?.takeIf { it.playbackActive } ?: lastVisible?.freezeAt(
        gapAtElapsedMs,
        keepAliveWhileFrozen = false
    )?.copy(playbackActive = true, pauseRetentionEligible = false))
        ?.takeIf { nowElapsedMs - gapAtElapsedMs < PAUSED_AOD_KEEP_ALIVE_MS }
} else {
    null
}

internal fun shouldKeepLockscreenAwake(
    enabled: Boolean,
    visible: Boolean,
    bouncerShowing: Boolean,
    mediaPlayerPresent: Boolean,
    playbackSpeed: Float
): Boolean = enabled && visible && !bouncerShowing && mediaPlayerPresent && playbackSpeed > 0f

internal fun shouldReuseLockscreenHost(
    currentHost: Any?,
    candidateHost: Any?,
    surfaceAttached: Boolean
): Boolean = currentHost === candidateHost && surfaceAttached

internal class LatestFrameRequestGate {
    private var pending = false

    fun request(): Boolean {
        if (pending) return false
        pending = true
        return true
    }

    fun consume(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }

    fun cancel() {
        pending = false
    }
}
