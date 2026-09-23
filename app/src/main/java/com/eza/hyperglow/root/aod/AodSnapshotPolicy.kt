package com.eza.hyperglow.root.aod

import com.eza.hyperglow.root.projection.LyricRetentionAnchor
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.edgeFor
import com.eza.hyperglow.root.projection.freezeAt
import com.eza.hyperglow.root.projection.pauseLingerRemainingMs

/** draw wake 锁脉冲的可观测结局;AOD 冻结类问题的现场取证就靠它区分失败层次。 */
internal enum class AodDrawWakePulseResult {
    SUCCESS,
    MISSING_WAKE_LOCK,
    MISSING_METHOD,
    INVOCATION_FAILED
}

/** 去重规则:结果不变就不重复记录,保持 renew 循环每 2.75s 一次的静默。 */
internal fun shouldLogDrawWakePulseResult(
    previous: AodDrawWakePulseResult?,
    current: AodDrawWakePulseResult
): Boolean = previous != current

internal fun shouldRenderAodSnapshot(
    sceneActive: Boolean,
    snapshotVisible: Boolean,
    featureEnabled: Boolean,
    profileEnabled: Boolean,
    transitionFailed: Boolean,
    spotifyAuthorized: Boolean = true
): Boolean = sceneActive && snapshotVisible && spotifyAuthorized && featureEnabled &&
    profileEnabled && !transitionFailed

internal fun isNewAodWakeSignal(previous: Long, incoming: Long): Boolean =
    incoming != 0L && incoming != previous

/**
 * 是否应抑制系统息屏内容的纯函数。
 *
 * 两种情况命中即抑制:
 *  1. [suppressBase] 全局「隐藏系统息屏内容」开启;
 *  2. [landscapeHideStock] 开启 且 当前处于横屏步进([landscapeStep]) 且 歌词随设备旋转
 *     ([rotateWithDevice]) —— 用于实现「横屏自动隐藏系统息屏内容,回落竖屏自动恢复」。
 */
internal fun shouldHideStockAodContent(
    suppressBase: Boolean,
    landscapeStep: Boolean,
    rotateWithDevice: Boolean,
    landscapeHideStock: Boolean
): Boolean = suppressBase || (landscapeStep && rotateWithDevice && landscapeHideStock)

internal fun retainedAodSnapshotAfterUpdate(
    incoming: LyricSnapshot,
    lastVisible: LyricSnapshot?,
    retained: LyricSnapshot?,
    anchor: LyricRetentionAnchor?,
    mediaPlayerPresent: Boolean,
    nowElapsedMs: Long,
    pauseLingerMs: Long = 5_000L,
    pauseRetentionEnabled: Boolean = true
): LyricSnapshot? = when {
    incoming.visible -> null
    !mediaPlayerPresent -> null
    // 「暂停时显示歌曲信息、歌词」关闭时,暂停驻留边按终止态处理:不冻结歌词,
    // 避免任何设置组合下暂停仍漏出歌曲信息驻留。
    incoming.pauseRetentionEligible && pauseRetentionEnabled -> {
        val pauseAtElapsedMs = anchor.edgeFor(
            pauseRetentionEligible = true,
            fallbackElapsedMs = incoming.updatedAtElapsedMs.coerceIn(0L, nowElapsedMs)
        )
        val candidate = retained?.takeIf { it.pauseRetentionEligible } ?: lastVisible?.freezeAt(
            pauseAtElapsedMs,
            keepAliveWhileFrozen = false
        )?.copy(playbackActive = false, pauseRetentionEligible = true)
        candidate?.takeIf {
            pauseLingerRemainingMs(it.sampledAtElapsedMs, pauseLingerMs, nowElapsedMs) != null
        }
    }
    incoming.playbackActive -> {
        val gapAtElapsedMs = anchor.edgeFor(
            pauseRetentionEligible = false,
            fallbackElapsedMs = nowElapsedMs
        )
        val candidate = retained?.takeIf { it.playbackActive } ?: lastVisible?.freezeAt(
            gapAtElapsedMs,
            keepAliveWhileFrozen = lastVisible.keepAlive
        )?.copy(playbackActive = true, pauseRetentionEligible = false)
        // 仍在播放的隐藏边是传输间隙,间隙自身的界就是电源宽限。没有它,producer 持续重发
        // 间隙时冻结歌词会活过所有计时器,因为没有任何东西给一条从不停歇的快照计时。
        candidate?.takeIf { nowElapsedMs - gapAtElapsedMs < PAUSED_AOD_KEEP_ALIVE_MS }
            ?.let { expirePausedAodKeepAlive(it, nowElapsedMs) }
    }
    else -> null
}

internal fun expirePausedAodKeepAlive(
    retained: LyricSnapshot,
    nowElapsedMs: Long
): LyricSnapshot {
    if (!retained.keepAlive) return retained
    val pausedForMs = (nowElapsedMs - retained.sampledAtElapsedMs).coerceAtLeast(0L)
    return if (pausedForMs >= PAUSED_AOD_KEEP_ALIVE_MS) {
        retained.copy(keepAlive = false)
    } else {
        retained
    }
}

internal const val PAUSED_AOD_KEEP_ALIVE_MS = 30_000L

internal fun smoothAodRevealProgress(progress: Float): Float {
    val value = progress.coerceIn(0f, 1f)
    return value * value * (3f - 2f * value)
}

internal fun shouldRetryManagedAodPosition(attempts: Int, maximumAttempts: Int): Boolean =
    attempts < maximumAttempts
