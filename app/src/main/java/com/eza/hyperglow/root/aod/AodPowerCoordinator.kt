package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.projection.LyricKeepAliveSignal
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricSurfaceKind
import com.eza.hyperglow.root.projection.SystemUiLyricSubscriber

internal object AodPowerCoordinator : SystemUiLyricSubscriber {
    override val surfaceKind = LyricSurfaceKind.AOD

    private val mainHandler = Handler(Looper.getMainLooper())
    private var surfaceAttached = false
    private var aodDisplayOff = false
    private var aodEnabled = false
    private var keepAliveRequested = false
    private var graceEligible = false
    private var graceActive = false
    private var lastWakeSignal = Long.MIN_VALUE
    private var lastRetriedSignal = Long.MIN_VALUE
    private var projectionVisible = false
    private var lifetimeActive = false
    private var lifetimeActiveSinceElapsedMs = Long.MIN_VALUE
    private var hideRaceRecoveryPending = false
    /** What last moved the guard. Reported with the transition so a release names its own cause. */
    private var guardCause = "init"
    private val graceExpiry = Runnable {
        if (!graceActive) return@Runnable
        graceActive = false
        graceEligible = false
        keepAliveRequested = false
        guardCause = "grace-expired"
        HookLogger.i(TAG, "AOD power grace expired")
        updateLifetimeGuard()
    }

    fun onSurfaceAttached() {
        if (surfaceAttached) return
        surfaceAttached = true
        HookLogger.i(TAG, "Surface attached (lifetime guard eligible)")
        guardCause = "surface-attached"
        updateLifetimeGuard()
    }

    fun onSurfaceDetached() {
        aodDisplayOff = false
        if (!surfaceAttached) return
        surfaceAttached = false
        HookLogger.i(TAG, "Surface detached (lifetime guard released)")
        guardCause = "surface-detached"
        updateLifetimeGuard()
    }

    /**
     * A hide already in flight when keepalive intent arrives cannot be suppressed, so a presenting
     * AOD can vanish moments after the guard activates. That single race is recoverable by
     * re-asserting the wake identity once the doze state has settled. Display power is not otherwise
     * ours to override: a sensor/pocket pause, a deliberate sleep, and an expired session all reach
     * the same off edge, and re-waking those fights Xiaomi in a loop. Recovery is therefore armed
     * only by an inactive-to-active guard edge observed while AOD was still presenting, and is
     * consumed by the first off edge inside the bounded hide-animation window.
     */
    fun onAodDisplayState(state: Int) {
        val off = isAodDisplayOffState(state)
        if (off == aodDisplayOff) return
        aodDisplayOff = off
        if (!off || lastWakeSignal == Long.MIN_VALUE) return
        if (!shouldRecoverRacedAodHide(
                surfaceAttached = surfaceAttached,
                keepAliveRequested = keepAliveRequested,
                recoveryPending = hideRaceRecoveryPending,
                lifetimeActiveSinceElapsedMs = lifetimeActiveSinceElapsedMs,
                nowElapsedMs = SystemClock.elapsedRealtime()
            )
        ) return
        hideRaceRecoveryPending = false
        HookLogger.i(TAG, "AOD hide race recovery signal=$lastWakeSignal")
        dispatchWake(signal = lastWakeSignal, allowed = aodEnabled, forceRetry = true)
    }

    override fun onLyricSnapshot(snapshot: LyricSnapshot) {
        projectionVisible = snapshot.visible
        guardCause = "snapshot visible=${snapshot.visible} playing=${snapshot.playbackActive} " +
            "keepAlive=${snapshot.keepAlive} grace=$graceActive"
        if (snapshot.visible) {
            aodEnabled = snapshot.aodEnabled
            keepAliveRequested = snapshot.aodEnabled && snapshot.playbackActive && snapshot.keepAlive
            graceEligible = hasPersistentAodPowerIntent(snapshot)
            cancelGrace()
        } else if (shouldStartAodPowerGrace(
                aodEnabled = aodEnabled,
                playbackActive = snapshot.playbackActive,
                keepAliveRequested = keepAliveRequested,
                graceEligible = graceEligible
            )
        ) {
            startGrace()
        } else if (shouldHoldGraceAcrossPauseRetention(
                graceActive = graceActive,
                playbackActive = snapshot.playbackActive,
                pauseRetentionEligible = snapshot.pauseRetentionEligible
            )
        ) {
            // 切歌间隙:app 侧 pause confirm 窗口比播放器的 false→true 间隙短,提交的
            // 暂停驻留边(playbackActive=false)会落在本窗口内。grace 的存在意义正是跨过
            // 歌曲边界的瞬态(新歌的可见快照到达后恢复 keepalive);真暂停则由 grace 的
            // 有界定时器(PAUSED_AOD_KEEP_ALIVE_MS)到期释放。间隙中途不得判死刑。
            guardCause = "song-gap retention grace=$graceActive"
        } else {
            cancelGrace()
            keepAliveRequested = false
            graceEligible = false
        }
        updateLifetimeGuard()
        dispatchWake(
            snapshot.wakeSignal,
            snapshot.aodEnabled && snapshot.visible && snapshot.playbackActive
        )
    }

    override fun onLyricKeepAlive(signal: LyricKeepAliveSignal) {
        guardCause = "keepalive playing=${signal.playbackActive} keepAlive=${signal.keepAlive} " +
            "grace=$graceActive"
        if (!signal.playbackActive &&
            !shouldHoldGraceAcrossPauseRetention(
                graceActive = graceActive,
                playbackActive = signal.playbackActive,
                pauseRetentionEligible = signal.pauseRetentionEligible
            )
        ) {
            cancelGrace()
            keepAliveRequested = false
            graceEligible = false
        } else if (
            signal.keepAlive &&
            shouldAcceptKeepAliveHeartbeat(
                projectionVisible = projectionVisible,
                graceActive = graceActive
            )
        ) {
            keepAliveRequested = aodEnabled
            cancelGrace()
        } else if (!graceActive) {
            keepAliveRequested = false
            graceEligible = false
        }
        updateLifetimeGuard()
        dispatchWake(
            signal = signal.wakeSignal,
            allowed = aodEnabled,
            forceRetry = shouldRetryDetachedAodWake(
                surfaceAttached = surfaceAttached,
                keepAliveRequested = keepAliveRequested,
                signal = signal.wakeSignal,
                lastRetriedSignal = lastRetriedSignal
            )
        )
    }

    override fun onLyricProjectionDisconnected() = clear("projection-disconnected")

    /**
     * 投影内容 stale:app 侧在播放中但有一段时间没推新快照/心跳。这里不能直接当会话结束
     * 把 guard 关掉——否则前奏/间奏无歌词、或 Lyricon 息屏后位置源停更时,AOD 会被直接
     * 关闭且后续 keepalive 无法恢复(因为 keepAliveRequested/aodEnabled 已被清零)。
     *
     * 正确行为:当已有 keepAlive 请求时保留 keepAliveRequested/aodEnabled 状态,只更新 cause
     * 让日志可见;否则按正常会话结束清理。播放确实已结束时 app 侧会通过新的隐藏快照
     * (playbackActive=false)或 disconnected 事件来关闭 guard;真暂停也有 grace 的有界定时器兜底。
     */
    override fun onLyricProjectionStale() {
        if (shouldRetainAodPowerOnProjectionStale(keepAliveRequested)) {
            guardCause = "projection-stale-retained"
            updateLifetimeGuard()
        } else {
            clear("projection-stale")
        }
    }

    private fun clear(cause: String) {
        cancelGrace()
        keepAliveRequested = false
        graceEligible = false
        aodEnabled = false
        aodDisplayOff = false
        hideRaceRecoveryPending = false
        projectionVisible = false
        lastWakeSignal = Long.MIN_VALUE
        lastRetriedSignal = Long.MIN_VALUE
        guardCause = cause
        updateLifetimeGuard()
    }

    private fun updateLifetimeGuard() {
        val active = shouldActivateAodPowerLifetime(
            surfaceAttached = surfaceAttached,
            keepAliveRequested = keepAliveRequested,
            capabilityAvailable = XiaomiCapabilityResolver.hasCapability(
                XiaomiCapability.AOD_LIFETIME_GUARD
            )
        )
        if (active != lifetimeActive) {
            lifetimeActive = active
            lifetimeActiveSinceElapsedMs = if (active) SystemClock.elapsedRealtime() else Long.MIN_VALUE
            hideRaceRecoveryPending = active && !aodDisplayOff
        }
        AodLifetimeController.noteGuardCause(guardCause)
        AodLifetimeController.setLyricActive(active)
    }

    private fun dispatchWake(signal: Long, allowed: Boolean, forceRetry: Boolean = false) {
        val newSignal = isNewAodWakeSignal(lastWakeSignal, signal)
        if (!allowed || (!newSignal && !forceRetry)) return
        if (forceRetry) lastRetriedSignal = signal
        val accepted = AodWakeBroker.requestWake(signal)
        if (newSignal && accepted) lastWakeSignal = signal
        HookLogger.i(
            TAG,
            "AOD wake requested signal=$signal attached=$surfaceAttached " +
                "displayOff=$aodDisplayOff retry=$forceRetry accepted=$accepted"
        )
    }

    private fun startGrace() {
        if (graceActive) return
        graceActive = true
        mainHandler.removeCallbacks(graceExpiry)
        mainHandler.postDelayed(graceExpiry, PAUSED_AOD_KEEP_ALIVE_MS)
        HookLogger.i(TAG, "AOD power grace started")
    }

    private fun cancelGrace() {
        graceActive = false
        mainHandler.removeCallbacks(graceExpiry)
    }

    private const val TAG = "AodPowerCoordinator"
}

/**
 * Grace eligibility follows validated keepalive intent, not lyric timing. `Keep unsynced songs
 * active` produces persistent keepalive without timed rows, and those sessions need the same
 * protection from transient producer gaps at a song boundary.
 */
internal fun hasPersistentAodPowerIntent(snapshot: LyricSnapshot): Boolean =
    snapshot.playbackActive && snapshot.keepAlive

internal fun shouldStartAodPowerGrace(
    aodEnabled: Boolean,
    playbackActive: Boolean,
    keepAliveRequested: Boolean,
    graceEligible: Boolean
): Boolean = aodEnabled && playbackActive && keepAliveRequested && graceEligible

/**
 * Song-boundary gap: the app-side pause confirmation window (1.5 s) can be shorter than the
 * player's false→true gap at a track change (observed 0.96 s of false plus lyric load time), so
 * the committed pause-retention edge (`playbackActive=false`, `pauseRetentionEligible=true`)
 * lands while grace is still protecting the boundary. Grace must survive that edge: the new
 * track's visible snapshot re-arms keepalive, and a genuine pause is bounded by the grace timer.
 * Without this, a sub-second track change kills the guard and the AOD closes mid-playback.
 */
internal fun shouldHoldGraceAcrossPauseRetention(
    graceActive: Boolean,
    playbackActive: Boolean,
    pauseRetentionEligible: Boolean
): Boolean = graceActive && !playbackActive && pauseRetentionEligible

/**
 * Projection stale while we already have a live keepalive request should NOT be treated as a
 * session end. Clearing `keepAliveRequested`/`aodEnabled` on stale would close the AOD during
 * lyric-less intros/interludes or when the Lyricon position source stops updating after screen-off
 * (issue #5), and subsequent keepalives could no longer resurrect the guard because aodEnabled had
 * been zeroed.
 *
 * If there was no keepalive request in the first place, stale is an honest end-of-session and we
 * fall back to [clear].
 */
internal fun shouldRetainAodPowerOnProjectionStale(keepAliveRequested: Boolean): Boolean =
    keepAliveRequested

/**
 * A heartbeat cannot turn a hidden transport grace back into an unbounded active session.
 * 心跳只能维持正在呈现的 AOD:靠心跳续期隐藏歌词的宽限窗口会把恢复余量拉成无限会话。
 */
internal fun shouldAcceptKeepAliveHeartbeat(
    projectionVisible: Boolean,
    graceActive: Boolean
): Boolean = projectionVisible

internal fun shouldRetryDetachedAodWake(
    surfaceAttached: Boolean,
    keepAliveRequested: Boolean,
    signal: Long,
    lastRetriedSignal: Long
): Boolean = !surfaceAttached && keepAliveRequested &&
    isNewAodWakeSignal(lastRetriedSignal, signal)

/**
 * Bounded to the one unwinnable race: keepalive intent landing after Xiaomi's policy hide has
 * already run, so the presenting AOD disappears while the guard is nominally active. Every other
 * powered-off AOD belongs to Xiaomi. The armed flag is one-shot per guard activation and is never
 * armed when AOD was already dark, which is what separates the race from a sensor pause, a
 * deliberate sleep, or a session that simply ended.
 */
internal fun shouldRecoverRacedAodHide(
    surfaceAttached: Boolean,
    keepAliveRequested: Boolean,
    recoveryPending: Boolean,
    lifetimeActiveSinceElapsedMs: Long,
    nowElapsedMs: Long
): Boolean = surfaceAttached && keepAliveRequested && recoveryPending &&
    lifetimeActiveSinceElapsedMs != Long.MIN_VALUE &&
    nowElapsedMs - lifetimeActiveSinceElapsedMs <= HIDE_RACE_RECOVERY_WINDOW_MS

internal fun isAodDisplayOffState(state: Int): Boolean = state == 1

/** Bounds recovery to Xiaomi's hide animation; the captured race lost the panel 1.79 s in. */
internal const val HIDE_RACE_RECOVERY_WINDOW_MS = 2_500L

internal fun shouldActivateAodPowerLifetime(
    surfaceAttached: Boolean,
    keepAliveRequested: Boolean,
    capabilityAvailable: Boolean
): Boolean = surfaceAttached && keepAliveRequested && capabilityAvailable
