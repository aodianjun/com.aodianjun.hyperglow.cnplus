package com.eza.hyperglow.producer

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.SceneCompiler
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.extensions.TimingNavigator
import io.github.proify.lyricon.subscriber.ActivePlayerListener
import io.github.proify.lyricon.subscriber.ConnectionListener
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
import io.github.proify.lyricon.subscriber.ProviderInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [LyricProducer] backed by the lyricon subscriber SDK.
 *
 * Position delivery: the SDK polls its `SharedMemory` position buffer internally at ~60 Hz and
 * delivers each distinct value via [ActivePlayerListener.onPositionChanged] on `Dispatchers.Default`.
 * There is no public `SharedMemory`/`ByteBuffer` accessor, so this producer does NOT poll memory
 * itself — it consumes `onPositionChanged` directly (per spec clause 6, the position IS sourced
 * from SharedMemory, just delivered via the SDK's callback).
 *
 * Active-line selection: uses the SDK's [TimingNavigator] (binary search + sequential cache)
 * over the normalized song lyrics. [RichLyricLine] implements `ILyricTiming`, so
 * `TimingNavigator<RichLyricLine>` finds the current line for a position in O(log n).
 *
 * Render modes: the lyricon `Song` carries no render-mode fields, so per spec clause 5 they are
 * sourced from [CustomizationRepository.loadCompiled] (the AOD [CompiledSurfaceProfile]).
 * Snapshot is cached and refreshed on song change — never read at 60 Hz.
 *
 * Contract (see `.archcore/lyricon-integration/lyric-producer-contract.spec.md`):
 * - Requires API >= 27 (O_MR1). Below that, `LyriconFactory.createSubscriber` returns
 *   `EmptyLyriconSubscriber`, so this producer is a no-op (spec: API<27 → no-op).
 * - Requires lyricon's Xposed module active in SystemUI; its absence MUST NOT crash HyperGlow.
 *   Until connected, [connection] stays DISCONNECTED and [state] stays null, so the arbiter
 *   falls back to the Spicy producer automatically.
 * - The `spotify:track:` constraint is NOT imposed here (it stays in SpicyBridgeStateReducer);
 *   the track identity is a synthetic `lyricon:<songId|songName>`.
 *
 * Threading: all [ActivePlayerListener] callbacks arrive on Binder threads or `Dispatchers.Default`
 * (never main). [MutableStateFlow] is thread-safe, so emitting from any callback is safe.
 *
 * @param clock injectable monotonic clock (millis); defaults to [SystemClock.elapsedRealtime].
 *   Injected in unit tests so [emit] can run without Android's [SystemClock].
 */
class LyriconLyricProducer(
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : LyricProducer {

    override val id: LyricSource = LyricSource.LYRICON

    private val mutableConnection = MutableStateFlow(ProducerConnection.DISCONNECTED)
    override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()

    private val mutableState = MutableStateFlow<LyricProducerState?>(null)
    override val state: StateFlow<LyricProducerState?> = mutableState.asStateFlow()

    private var subscriber: LyriconSubscriber? = null
    private var contextRef: Context? = null
    private var started = false

    // --- Position-feed watchdog ---
    // The 12:26 capture: onPositionChanged stopped firing entirely (the arbiter later logged
    // stale age=519s) while [connection] stayed CONNECTED — the SDK's callback path can die
    // silently (binder drop / internal poller stall) without any disconnect event. Before this
    // watchdog the only recovery was an app restart. The watchdog force-rebuilds the active
    // player subscription, mirroring SuperLyricLyricProducer's FORCE_RE_REGISTER pattern.
    @Volatile private var lastPositionCallbackElapsedMs: Long = -1L
    @Volatile private var lastForcedResubscribeElapsedMs: Long = 0L
    private val watchdogScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- Ingress state, updated by playerListener; read by emit(). @Volatile for cross-thread. ---
    @Volatile private var currentSong: Song? = null
    @Volatile private var navigator: TimingNavigator<RichLyricLine>? = null
    @Volatile private var currentPositionMs: Long = 0L
    @Volatile private var isPlayingState: Boolean = false
    @Volatile private var currentLineIndex: Int = -1
    @Volatile private var cachedWords: List<LyricWord>? = null
    @Volatile private var renderModesSnapshot: ProducerRenderModes = defaultRenderModes()

    // --- Position extrapolation state ---
    // When the player process is frozen by MIUI screen-off, the shared-memory position stops
    // updating but onPositionChanged keeps firing at ~60 Hz with the same stalled value. To keep
    // lyrics advancing, we extrapolate: currentPositionMs = lastRealPosition + elapsed wall-clock.
    @Volatile private var lastRealPositionMs: Long = 0L
    @Volatile private var lastRealPositionClockMs: Long = -1L
    @Volatile private var extrapolating: Boolean = false

    // --- Extrapolation budget / unknown position ---
    // When the position source goes silent while playing, extrapolation advances the lyric for at
    // most MAX_EXTRAPOLATION_MS. Past that the writer is treated as dead (not merely screen-off
    // frozen): position is marked unknown and the active line is cleared so we never extrapolate
    // all the way to the song end over a long stall (the 19:33 capture extrapolated ~3m39s past
    // the real paused position and landed on the last line).
    @Volatile private var positionUnknown: Boolean = false

    // --- Stale detection ---
    // If no real position update arrives for STALE_THRESHOLD_MS, the shared-memory writer
    // may be completely dead (not just stalled). Log a warning so the arbiter can consider
    // falling back to another producer.
    @Volatile private var lastRealPositionUpdateMs: Long = -1L

    // --- Residual position rejection (song change) ---
    // After onSongChanged, the shared memory may still hold the previous song's position for a
    // long time until the player writes the new song's progress. Without filtering, the first
    // onPositionChanged with the stale value overwrites our reset (stale != 0 → "resumed" branch).
    // We reject any position that exactly matches the previous song's last position until a
    // different (real) position arrives — there is no fixed window, because NetEase's outro +
    // intro can leave the position source silent past any window, and the first value on resume
    // is still the old song's position.
    @Volatile private var previousSongLastPositionMs: Long = -1L

    // --- Seek residual position rejection ---
    // After onSeekTo, the shared memory may still return the pre-seek position for a short
    // window until the player writes the new progress. Without filtering, that stale value
    // (old != seek target) is accepted by the "resumed" branch and undoes the seek target,
    // so the active line snaps back to the old position. We reject any position that exactly
    // matches the pre-seek position within a window after the seek. Once a different (real)
    // position arrives, filtering stops.
    @Volatile private var seekRejectPositionMs: Long = -1L
    @Volatile private var seekClockMs: Long = 0L

    // --- Pause-stale residual rejection (issue #10) ---
    // When playback pauses while the position source is already stalled (AOD Doze), the shared
    // memory still holds the *pre-pause stale* value (e.g. 16665ms while actual playback had
    // reached 23435ms via extrapolation). We re-base the position base onto the displayed
    // (extrapolated) pause point on pause; the stale value would otherwise keep arriving at 60 Hz
    // and — now differing from the re-based base — be mistaken for a genuine post-pause "resume"
    // rewind, snapping the lyric back to an older line. Reject any value matching the pre-pause
    // stale base until a different (real) position arrives.
    @Volatile private var pauseStaleRejectMs: Long = -1L

    // Session/sequence for arbiter dedup (producerId:generation:sequence).
    @Volatile private var generation: Int = 0
    @Volatile private var sequence: Long = 0L

    internal val connectionListener = object : ConnectionListener {
        override fun onConnected(s: LyriconSubscriber) {
            AppLog.i("LyriconLyricProducer", "connected")
            mutableConnection.value = ProducerConnection.CONNECTED
        }

        override fun onReconnected(s: LyriconSubscriber) {
            AppLog.i("LyriconLyricProducer", "reconnected")
            mutableConnection.value = ProducerConnection.RECONNECTED
        }

        override fun onDisconnected(s: LyriconSubscriber) {
            AppLog.i("LyriconLyricProducer", "disconnected")
            mutableConnection.value = ProducerConnection.DISCONNECTED
            mutableState.value = null
        }

        override fun onConnectTimeout(s: LyriconSubscriber) {
            AppLog.w("LyriconLyricProducer", "connect timeout")
            mutableConnection.value = ProducerConnection.CONNECT_TIMEOUT
            mutableState.value = null
        }
    }

    internal val playerListener = object : ActivePlayerListener {
        override fun onActiveProviderChanged(providerInfo: ProviderInfo?) {
            AppLog.i("LyriconLyricProducer", "provider=${providerInfo?.providerPackageName}")
            if (providerInfo == null) {
                // No active player: clear state, let arbiter fall back / go idle.
                currentSong = null
                navigator = null
                currentLineIndex = -1
                cachedWords = null
                currentPositionMs = 0L
                lastRealPositionMs = 0L
                lastRealPositionClockMs = clock()
                lastRealPositionUpdateMs = -1L
                extrapolating = false
                positionUnknown = false
                previousSongLastPositionMs = -1L
                seekRejectPositionMs = -1L
                seekClockMs = 0L
                pauseStaleRejectMs = -1L
                mutableState.value = null
            }
        }

        override fun onSongChanged(song: Song?) {
            if (song == null) {
                AppLog.i("LyriconLyricProducer", "onSongChanged: null (cleared)")
                currentSong = null
                navigator = null
                currentLineIndex = -1
                cachedWords = null
                currentPositionMs = 0L
                lastRealPositionMs = 0L
                lastRealPositionClockMs = clock()
                lastRealPositionUpdateMs = -1L
                extrapolating = false
                positionUnknown = false
                previousSongLastPositionMs = -1L
                seekRejectPositionMs = -1L
                seekClockMs = 0L
                pauseStaleRejectMs = -1L
                mutableState.value = null
                return
            }
            AppLog.i(
                "LyriconLyricProducer",
                "onSongChanged: id=${song.id} name=${song.name} artist=${song.artist} " +
                    "duration=${song.duration}ms lines=${song.lyrics?.size ?: 0}"
            )
            // normalize() deep-copies and sorts lyrics by begin (asc, required by TimingNavigator),
            // dropping invalid lines. Safe to call on the SDK's instance (it doesn't mutate it).
            val normalized = song.normalize()
            currentSong = normalized
            generation++
            val lyrics = normalized.lyrics
            navigator = if (!lyrics.isNullOrEmpty()) {
                TimingNavigator(lyrics.toTypedArray())
            } else {
                null
            }
            currentLineIndex = -1
            cachedWords = null
            // Reset position tracking for the new song. The shared memory may still hold the
            // previous song's position until the player writes the new one, which caused the
            // active line to jump to a stale index (e.g. idx=64 on song change).
            //
            // Capture the previous song's last position so onPositionChanged can reject the
            // residual value (it will keep arriving at ~60 Hz until the player writes new progress).
            // Enable extrapolation from 0 so lyrics advance during the write gap if playing.
            previousSongLastPositionMs = lastRealPositionMs
            currentPositionMs = 0L
            lastRealPositionMs = 0L
            lastRealPositionClockMs = clock()
            extrapolating = false
            positionUnknown = false
            seekRejectPositionMs = -1L
            seekClockMs = 0L
            pauseStaleRejectMs = -1L
            refreshRenderModes()
            emit()
        }

        override fun onReceiveText(text: String?) {
            // Plain-text lyrics (no timestamps). Out of scope for karaoke AOD; ignore.
            AppLog.i("LyriconLyricProducer", "onReceiveText: len=${text?.length} (ignored)")
        }

        override fun onPlaybackStateChanged(isPlaying: Boolean) {
            AppLog.i("LyriconLyricProducer", "onPlaybackStateChanged: playing=$isPlaying")
            isPlayingState = isPlaying
            if (isPlaying) {
                // When resuming playback after a pause, reset the extrapolation clock so we don't
                // jump forward by the entire pause duration on the next stalled position callback.
                if (lastRealPositionClockMs >= 0L) {
                    lastRealPositionClockMs = clock()
                }
                // issue #10 追加实测(暂停→继续):位置源在 AOD 下可能早已陈旧(stalled),暂停时
                // 的 lastRealPositionMs 滞后于媒体真实位置(实测差 ~6.7s)。继续播放后若直接
                // 从陈旧基准重新外推,歌词行会跳回更早的行再爬行。暂停瞬间我们已把基准
                // re-base 到展示位置(见 advanceExtrapolation),此处把 extrapolating 复位,
                // 让继续后的首个真实位置回调走"真实更新"路径覆盖基准,而不是被
                // monotonicResume 容差吞掉继续沿用陈旧外推值。
                extrapolating = false
            } else {
                // When paused, freeze extrapolation: the real position is frozen, so
                // currentPositionMs must stop advancing too. Previously a long pause kept
                // extrapolating the lyric all the way to the song end.
                if (extrapolating) {
                    // 暂停即真实停点(issue #10 追加实测):位置源在 AOD 下可能早已 stalled,媒体
                    // 真实暂停点≈此刻展示位置(外推位置),而旧 lastRealPositionMs 是陈旧基准。
                    // 把展示位置固化为新基准;若写端仍冻结,它还会持续发陈旧值,记录该陈旧值
                    // 以便 onPositionChanged 拒绝,防止暂停/继续瞬间歌词行跳回更早的行。
                    pauseStaleRejectMs = lastRealPositionMs
                    lastRealPositionMs = currentPositionMs
                    lastRealPositionClockMs = clock()
                    extrapolating = false
                    AppLog.i(
                        "LyriconLyricProducer",
                        "pause: extrapolation frozen, re-based to ${lastRealPositionMs}ms " +
                            "(rejecting stale ${pauseStaleRejectMs}ms)"
                    )
                }
            }
            // Re-emit so the engine sees the new playing/speed without waiting for next position.
            emit()
        }

        override fun onPositionChanged(position: Long) {
            // High-frequency (~60 Hz) callback on Dispatchers.Default. This IS the SharedMemory
            // position, delivered by the SDK's internal poller. Compute the active line and emit.
            val now = clock()
            // Feed heartbeat for the position-silence watchdog (see maybeResubscribeOnSilence).
            lastPositionCallbackElapsedMs = now
            // Reject residual values from the previous song: after onSongChanged, the shared
            // memory may keep returning the old position until the player writes new progress.
            // The residual matches the previous song's last position exactly (same bytes in memory).
            // We keep rejecting it until a different (real) position arrives — not just within a
            // fixed window — because NetEase's outro + intro can leave the position source silent
            // for the whole prelude (~60s) and the first value on resume is still the old song's
            // position. Accepting it would locate an old-song position against the new lyric table.
            val isResidual = previousSongLastPositionMs >= 0L &&
                position == previousSongLastPositionMs
            if (isResidual) {
                // Reject the stale value: advance by wall-clock extrapolation from the last real
                // position (freezing when paused / marking unknown once the budget is exceeded),
                // instead of advancing regardless of the playing flag — that is what previously
                // let a long pause extrapolate all the way to the song end.
                advanceExtrapolation(now, "residual ${position}ms matches previous song")
                recomputeAndEmit()
                return
            }
            // Reject the pre-seek stale value that lingers right after a seek. The old value
            // (still in shared memory) != the seek target, so without this it would be accepted
            // by the "resumed" branch below and snap the active line back to the old position.
            val isSeekResidual = seekRejectPositionMs >= 0L &&
                (now - seekClockMs) < SEEK_RESIDUAL_REJECTION_WINDOW_MS &&
                position == seekRejectPositionMs
            if (isSeekResidual) {
                advanceExtrapolation(now, "seek residual ${position}ms matches pre-seek")
                recomputeAndEmit()
                return
            }
            // Reject the pre-pause stale value (issue #10): after a pause we re-base the position
            // onto the displayed (extrapolated) pause point, but the shared memory may keep
            // delivering the *older* stale value at 60 Hz while the writer is still frozen. That
            // value now differs from the re-based base and would otherwise be accepted as a real
            // post-pause "resume", snapping the lyric back to a stale line. Reject until a
            // different (real) position arrives.
            val isPauseStaleResidual = pauseStaleRejectMs >= 0L &&
                position == pauseStaleRejectMs
            if (isPauseStaleResidual) {
                advanceExtrapolation(now, "pause-stale residual ${position}ms")
                recomputeAndEmit()
                return
            }
            if (position != lastRealPositionMs) {
                // Real position update from shared memory. The line index is driven exclusively by
                // real positions (issue #10), so on resume we simply re-base onto the real value and
                // let recomputeAndEmit re-select the line — no tolerance that keeps a guessed
                // (extrapolated) position in charge, otherwise the resume would re-align onto the
                // stale/extrapolated base and the lyric would stay off by the stall duration.
                val wasExtrapolating = extrapolating
                lastRealPositionMs = position
                lastRealPositionClockMs = now
                lastRealPositionUpdateMs = now
                currentPositionMs = position
                // A different value means the player has started writing the new song's progress.
                // Disable residual filtering — subsequent positions are from the new song.
                previousSongLastPositionMs = -1L
                // A real (different) position means the player has written the post-seek value;
                // stop rejecting the pre-seek position.
                seekRejectPositionMs = -1L
                // A real (different) position means the writer is alive again: stop rejecting the
                // pre-pause stale value (issue #10).
                pauseStaleRejectMs = -1L
                // A real value also means the position source is alive again: clear the
                // unknown-position marker so recomputeAndEmit re-selects the active line.
                positionUnknown = false
                if (wasExtrapolating) {
                    extrapolating = false
                    AppLog.i(
                        "LyriconLyricProducer",
                        "position resumed: pos=${position}ms (extrapolation stopped)"
                    )
                }
            } else if (lastRealPositionClockMs >= 0L) {
                // Position stalled (shared-memory writer frozen by MIUI screen-off). Advance via
                // wall-clock extrapolation — freeze when paused; past the budget keep advancing
                // while within the song (Doze writer freeze, playback active), and only declare
                // the writer dead (mark position unknown) once past the song end or without a
                // known duration.
                advanceExtrapolation(now, "position stalled")
            }
            recomputeAndEmit()
        }

        override fun onSeekTo(position: Long) {
            AppLog.i("LyriconLyricProducer", "onSeekTo: pos=${position}ms old=${lastRealPositionMs}ms")
            val now = clock()
            // Record the pre-seek position so onPositionChanged can reject the stale shared-memory
            // value that lingers right after the seek (old != seek target would otherwise be
            // accepted as a "real" update and snap the active line back to the old position).
            seekRejectPositionMs = lastRealPositionMs
            seekClockMs = now
            lastRealPositionMs = position
            lastRealPositionClockMs = now
            lastRealPositionUpdateMs = now
            currentPositionMs = position
            extrapolating = false
            // A seek is a deliberate position change — the position is known again.
            positionUnknown = false
            // A seek is a deliberate position change — clear residual filtering so the new
            // position is accepted even if it coincidentally matches the previous song's last.
            previousSongLastPositionMs = -1L
            pauseStaleRejectMs = -1L
            // Seek invalidates the navigator's sequential cache (playback jumped).
            navigator?.resetCache()
            currentLineIndex = -1
            cachedWords = null
            recomputeAndEmit()
        }

        override fun onDisplayTranslationChanged(isDisplayTranslation: Boolean) {
            // HyperGlow controls translation display via its own CustomizationRepository; ignore
            // the lyricon-side toggle to avoid double-toggling.
            AppLog.i("LyriconLyricProducer", "onDisplayTranslationChanged: $isDisplayTranslation (ignored, owned by HyperGlow)")
        }

        override fun onDisplayRomaChanged(isDisplayRoma: Boolean) {
            // Same as above: romanization display is owned by HyperGlow's render modes.
            AppLog.i("LyriconLyricProducer", "onDisplayRomaChanged: $isDisplayRoma (ignored, owned by HyperGlow)")
        }
    }

    override fun start(context: Context) {
        if (started) {
            AppLog.i("LyriconLyricProducer", "start: already started (no-op)")
            return
        }
        started = true
        contextRef = context.applicationContext
        AppLog.i("LyriconLyricProducer", "start: api=${Build.VERSION.SDK_INT}")

        // API < 27: LyriconFactory returns EmptyLyriconSubscriber (no-op). Per spec, this
        // producer MUST be a no-op below API 27, so we skip registration entirely.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            AppLog.i("LyriconLyricProducer", "start: API < 27, producer is no-op")
            return
        }

        AppLog.i("LyriconLyricProducer", "start: creating subscriber")
        val sub = LyriconFactory.createSubscriber(context.applicationContext)
        subscriber = sub
        sub.addConnectionListener(connectionListener)
        val subscribed = sub.subscribeActivePlayer(playerListener)
        AppLog.i("LyriconLyricProducer", "start: subscribeActivePlayer=$subscribed")
        refreshRenderModes()
        sub.register()
        AppLog.i("LyriconLyricProducer", "start: registered with central service")
        // Position-silence watchdog: recover the callback path if it dies mid-playback.
        watchdogScope.launch { positionWatchdogLoop() }
    }

    override fun stop() {
        if (!started) {
            AppLog.i("LyriconLyricProducer", "stop: not started (no-op)")
            return
        }
        started = false
        AppLog.i("LyriconLyricProducer", "stop: unregistering")
        watchdogScope.cancel()
        subscriber?.let { sub ->
            runCatching {
                sub.unsubscribeActivePlayer(playerListener)
                sub.removeConnectionListener(connectionListener)
                sub.unregister()
                sub.destroy()
            }.onFailure { AppLog.w("LyriconLyricProducer", "stop: cleanup error", it) }
        }
        subscriber = null
        mutableConnection.value = ProducerConnection.DISCONNECTED
        mutableState.value = null
        AppLog.i("LyriconLyricProducer", "stop: done")
    }

    /**
     * Watchdog loop: rebuild the active-player subscription when the ~60 Hz position feed goes
     * silent while playing. See [shouldForceResubscribePositionFeed] for the decision rule and
     * [maybeResubscribeOnPositionSilence] for the recovery action.
     */
    private suspend fun positionWatchdogLoop() {
        while (watchdogScope.isActive) {
            delay(POSITION_WATCHDOG_POLL_MS)
            maybeResubscribeOnPositionSilence()
        }
    }

    /**
     * Recovery action for a silent position feed: unsubscribe + re-subscribe the active player
     * listener, which re-arms the SDK's internal poller/callback registration. Idempotent-safe
     * via the cooldown in the decision function; failures are logged and retried after cooldown.
     */
    private fun maybeResubscribeOnPositionSilence() {
        val sub = subscriber ?: return
        val last = lastPositionCallbackElapsedMs
        if (last < 0L) return // never saw a position callback: nothing to compare yet
        val now = clock()
        val silenceMs = now - last
        if (!shouldForceResubscribePositionFeed(
                silenceMs = silenceMs,
                playing = isPlayingState,
                sinceLastAttemptMs = now - lastForcedResubscribeElapsedMs
            )
        ) {
            return
        }
        lastForcedResubscribeElapsedMs = now
        // Re-anchor the heartbeat so the same silence doesn't re-trigger before the next poll.
        lastPositionCallbackElapsedMs = now
        AppLog.w(
            "LyriconLyricProducer",
            "position feed silent for ${silenceMs}ms while playing; rebuilding subscription"
        )
        runCatching {
            sub.unsubscribeActivePlayer(playerListener)
            sub.subscribeActivePlayer(playerListener)
        }.onFailure { AppLog.w("LyriconLyricProducer", "forced resubscribe failed", it) }
    }

    /**
     * Advance [currentPositionMs] by wall-clock extrapolation from [lastRealPositionMs].
     *
     * The extrapolated position is a *display hint only* — since issue #10 the lyric line index
     * is driven exclusively by real positions: while the shared-memory writer is stalled
     * (extrapolating == true) [recomputeAndEmit] locks the current line and never advances it from
     * the guessed position. Extrapolation only feeds [currentPositionMs] so the glow/progress can
     * keep moving; it must never cause the line to jump ahead of actual playback (issue #10) nor
     * vanish (issue #3).
     *
     * - Paused: freeze everything and re-base [lastRealPositionMs] onto the displayed position
     *   (issue #10: the shared-memory writer can be stale long before the pause event; a pause is
     *   a real stop point, so the resume must continue from there instead of re-crawling from a
     *   stale base).
     * - Playing, duration known: clamp the display position at the song duration (issue #9) and
     *   keep `extrapolating == true` — the locked line is held until a real position resumes.
     * - Playing, no duration known (e.g. LRC line-level sources): over the budget the writer is
     *   treated as dead — mark [positionUnknown] so [recomputeAndEmit] clears the active line.
     */
    private fun advanceExtrapolation(now: Long, reason: String) {
        if (!isPlayingState) {
            if (extrapolating) {
                extrapolating = false
                // 暂停即真实停点:把当前展示位置固化为新基准(issue #10 追加实测)。位置源
                // 在 AOD 下可能早已陈旧(stalled),媒体真实暂停点≈此刻展示的外推位置;若保留
                // 陈旧的 lastRealPositionMs,暂停/继续瞬间歌词行会跳回更早的行再重新爬行。
                lastRealPositionMs = currentPositionMs
                lastRealPositionClockMs = now
                AppLog.i(
                    "LyriconLyricProducer",
                    "pause: extrapolation frozen ($reason), re-based to ${currentPositionMs}ms"
                )
            } else {
                // 未在推进:展示位置即暂停点,保持不动(幂等)。
                currentPositionMs = lastRealPositionMs
            }
            return
        }
        if (lastRealPositionClockMs < 0L) return
        val sinceRealMs = now - lastRealPositionClockMs
        val duration = currentSong?.duration ?: 0L
        val projected = lastRealPositionMs + sinceRealMs
        // Stale one-shot warning (writer may be dead) — observability only, does not gate behavior.
        if (lastRealPositionUpdateMs >= 0L &&
            now - lastRealPositionUpdateMs > STALE_POSITION_THRESHOLD_MS &&
            lastRealPositionUpdateMs != Long.MAX_VALUE
        ) {
            lastRealPositionUpdateMs = Long.MAX_VALUE // one-shot log
            val staleSec = (now - lastRealPositionClockMs) / 1000
            AppLog.w(
                "LyriconLyricProducer",
                "position stale for ${staleSec}s (last real=${lastRealPositionMs}ms " +
                    "extrapolated=${currentPositionMs}ms duration=${duration}ms)" +
                    if (duration > 0L && projected > duration) {
                        " — song may have looped"
                    } else {
                        " — shared-memory writer may be dead"
                    }
            )
        }
        if (!extrapolating) {
            extrapolating = true
            AppLog.i(
                "LyriconLyricProducer",
                "position stalled, extrapolating ($reason): base=${lastRealPositionMs}ms " +
                    "elapsed=${sinceRealMs}ms -> ${currentPositionMs}ms"
            )
        }
        // 歌词行索引只由真实位置驱动(issue #10):stalled 期间行已在 recomputeAndEmit 锁定,
        // 这里仅推进展示位置 positionMs(供发光/进度平滑),绝不因外推位置推进/清空歌词行。
        if (duration > 0L) {
            // 有时长:展示位置外推并钳在歌尾(issue #9),保持 extrapolating=true → 锁行;
            // 不置 positionUnknown、不清行 —— 歌词停在最后真实位置所在行,恢复后对齐。
            currentPositionMs = if (projected >= duration) duration else projected
            return
        }
        // 无时长信息(如 LRC 行级源):写端停更超过预算按死亡处理 —— positionUnknown 使
        // recomputeAndEmit 清空活动行(这类源没有"歌曲时长内继续外推"的语义)。
        if (sinceRealMs > MAX_EXTRAPOLATION_MS) {
            if (!positionUnknown) {
                extrapolating = false
                positionUnknown = true
                currentPositionMs = lastRealPositionMs + MAX_EXTRAPOLATION_MS
                AppLog.w(
                    "LyriconLyricProducer",
                    "extrapolation exceeded ${MAX_EXTRAPOLATION_MS}ms ($reason); marking position unknown"
                )
            }
            return
        }
        currentPositionMs = projected
    }

    /**
     * Find the active line for [currentPositionMs] via [TimingNavigator], rebuild the per-word
     * cache only when the line changes, then emit a fresh [LyricProducerState].
     *
     * Called at ~60 Hz from [onPositionChanged]; the word-list allocation is amortized by
     * caching across position-only updates within the same line.
     */
    private fun recomputeAndEmit() {
        val nav = navigator ?: return emit() // no lyrics yet; emit metadata-only state
        val song = currentSong ?: return
        val pos = currentPositionMs
        val duration = song.duration

        // 位置未知(外推超过预算且数据源尚未恢复):清空活动行,稳定显示占位,而不是把歌词
        // 一路推进到歌尾。等真实位置恢复或 onSongChanged / onSeekTo 到来清除 positionUnknown
        // 后再重新选行。
        if (positionUnknown) {
            if (currentLineIndex != -1) {
                currentLineIndex = -1
                cachedWords = null
            }
            emit()
            return
        }

        // 位置源 stalled(外推中):歌词行索引只由真实位置驱动(issue #10)。
        //
        // stalled 时真实播放位置不可知——外推位置只是"墙钟猜测"。用猜测位置推进歌词行会让
        // AOD 显示超前于真实播放的歌词(真实位置可能根本没动),恢复瞬间又跳回;还会在长冻结
        // 时误触歌尾清行(#3 的"歌词消失"、#9 的"卡占位")。因此外推期间锁定当前歌词行:
        // 不清空、不推进、不因外推越过歌尾而占位。展示位置 positionMs 允许在本行内继续
        // 推进(供行内发光平滑),但被钳制在当前行尾 —— 永不越入下一行/歌尾,保证行文本与
        // 发光进度自洽。真实位置恢复(position resumed / seek / song change)后重新选行对齐。
        if (extrapolating) {
            if (currentLineIndex >= 0) {
                val lockedLine = navigator?.source?.getOrNull(currentLineIndex)
                if (lockedLine != null && currentPositionMs > lockedLine.end) {
                    currentPositionMs = lockedLine.end
                }
            } else {
                // 无活动行(间奏/词前):展示位置停在实际真实位置(可被歌尾钳制),避免外推
                // 污染 nextLine 预览,也避免真实越界 base 越过歌尾展示(issue #9)。
                currentPositionMs = if (duration > 0L) {
                    minOf(lastRealPositionMs, duration)
                } else {
                    lastRealPositionMs
                }
            }
            emit()
            return
        }

        // 歌曲边界处理:真实位置(非外推)越过歌曲时长——当前这首歌确实已播完/数据源写了
        // 越界值(Doze 下共享内存残留/未回绕的循环累计 base,issue #9 日志中 base 远超
        // duration 的场景)。统一钳制 + 清行 + 稳定占位,且只在状态实际变化时记日志,
        // 避免 60Hz 每帧 "越界→清空→占位" 重建循环刷屏。
        // (外推路径不会到这里:extrapolating 时已在上方锁行返回,位置由
        // advanceExtrapolation 钳在 duration。)
        if (duration > 0L && pos >= duration) {
            currentPositionMs = duration
            val changed = currentLineIndex != -1
            if (currentLineIndex != -1) {
                currentLineIndex = -1
                cachedWords = null
            }
            if (changed) {
                AppLog.i(
                    "LyriconLyricProducer",
                    "position reached song end: pos=${pos}ms capped=${duration}ms " +
                        "(duration=${duration}ms); holding stable placeholder"
                )
            }
            emit()
            return
        }

        val idx = nav.findTargetIndex(currentPositionMs)
        if (idx < 0) {
            // Before the first line: no current line yet.
            if (currentLineIndex != -1) {
                currentLineIndex = -1
                cachedWords = null
            }
            emit()
            return
        }
        // 最后一句歌词唱完后（position 越过其 end，歌曲进入尾奏/纯器乐段落），清空活动行
        // 让投影显示 🎶 占位，而不是把最后一句滞留到歌曲结束。TimingNavigator 选择的是
        // 「最后一条 begin <= pos」的行，不检查 end，所以这里显式兜住结尾。
        if (idx == nav.source.size - 1 && currentPositionMs >= nav.source[idx].end) {
            if (currentLineIndex != -1) {
                currentLineIndex = -1
                cachedWords = null
            }
            emit()
            return
        }
        if (idx != currentLineIndex) {
            currentLineIndex = idx
            val line = nav.source[idx]
            cachedWords = line.toLyricWords()
            AppLog.i(
                "LyriconLyricProducer",
                "line changed: idx=$idx begin=${line.begin} end=${line.end} text=${line.text?.take(24)}"
            )
        }
        emit()
    }

    /**
     * Build and emit a [LyricProducerState] from the current ingress fields. Cheap: reuses
     * [cachedWords] (only rebuilt on line change) and [renderModesSnapshot] (only rebuilt on
     * song change). Safe to call at 60 Hz.
     */
    private fun emit() {
        val song = currentSong ?: run { mutableState.value = null; return }
        val now = clock()
        val lyrics = song.lyrics
        val line = currentLineIndex.let { idx ->
            if (idx < 0) null else navigator?.source?.getOrNull(idx)
        }
        // Active-row fields (spec clause 6: producer computes the active line before emitting).
        // lyricKind is per-active-line when a line is active; otherwise song-level, so the engine
        // can still tell "timed lyrics exist, between lines" (INTERLUDE) from "no lyrics" (NONE).
        val hasTimedLyrics = !lyrics.isNullOrEmpty() &&
            lyrics.any { it.end > it.begin }
        val lyricKind = when {
            lyrics.isNullOrEmpty() -> LyricKind.NONE
            line != null -> if (!line.words.isNullOrEmpty()) LyricKind.SYLLABLE else LyricKind.LINE
            else -> if (lyrics.any { !it.words.isNullOrEmpty() }) LyricKind.SYLLABLE
                else LyricKind.LINE
        }
        val nextLineStartMs = lyrics
            ?.asSequence()
            ?.map { it.begin }
            ?.filter { it > currentPositionMs }
            ?.minOrNull()
        val nextLineText = lyrics
            ?.asSequence()
            ?.firstOrNull { it.begin > currentPositionMs }
            ?.text
            .orEmpty()
        sequence++
        mutableState.value = LyricProducerState(
            producerId = PRODUCER_ID,
            generation = generation,
            sequence = sequence,
            status = "ready",
            trackUri = "lyricon:${song.id ?: song.name}",
            title = song.name.orEmpty(),
            artist = song.artist.orEmpty(),
            album = "",
            imageId = "",
            line = line?.text.orEmpty(),
            romanizedLine = line?.roma.orEmpty(),
            translatedLine = line?.translation.orEmpty(),
            lineIndex = currentLineIndex,
            positionMs = currentPositionMs,
            durationMs = song.duration,
            sampledAtElapsedMs = now,
            speed = if (isPlayingState) 1f else 0f,
            playing = isPlayingState,
            receivedAtElapsedMs = now,
            words = cachedWords,
            renderModes = renderModesSnapshot,
            lyricKind = lyricKind,
            // Lyricon carries no alignment / ruby / layout-group concepts; defaults are correct.
            alignedRight = false,
            lineStartMs = line?.begin ?: 0L,
            lineEndMs = line?.end ?: 0L,
            ruby = emptyList(),
            layoutGroups = emptyList(),
            hasTimedLyrics = hasTimedLyrics,
            nextLineStartMs = nextLineStartMs,
            nextLine = nextLineText
        )
    }

    /**
     * Refresh [renderModesSnapshot] from the AOD [CompiledSurfaceProfile]. Called on start and
     * song change — NOT at 60 Hz (compile is non-trivial). Per spec clause 5, the lyricon `Song`
     * carries no render modes, so they are sourced from HyperGlow's own customization.
     */
    @Synchronized
    private fun refreshRenderModes() {
        val ctx = contextRef ?: run {
            renderModesSnapshot = defaultRenderModes()
            return
        }
        renderModesSnapshot = runCatching {
            val compiled = CustomizationRepository.loadCompiled(ctx)
            val profile = compiled.profiles[SceneCompiler.SURFACE_AOD]
            profile?.toProducerRenderModes() ?: defaultRenderModes()
        }.onFailure {
            AppLog.w("LyriconLyricProducer", "refreshRenderModes failed, using defaults", it)
        }.getOrDefault(defaultRenderModes())
    }

    private fun CompiledSurfaceProfile.toProducerRenderModes() = ProducerRenderModes(
        weight = weight,
        textSize = textSize,
        textSizeCustom = textSizeCustom,
        secondary = secondaryMode,
        animation = animation,
        glow = glow,
        lineSyncFill = lineSyncFillMode,
        overflow = overflow,
        transition = transition.id,
        font = fontFamily
    )

    private fun RichLyricLine.toLyricWords(): List<LyricWord>? = words?.map { w ->
        // io.github.proify.lyricon.lyric.model.LyricWord has begin/end/text.
        // boundaryAfter is a Spicy-specific concept; default false (the engine treats word
        // boundaries from begin/end timing). roma is line-level (RichLyricLine.ruma), not per-word.
        LyricWord(
            text = w.text.orEmpty(),
            romanized = "",
            startMs = w.begin,
            endMs = w.end,
            boundaryAfter = false
        )
    }

    companion object {
        private const val PRODUCER_ID = "lyricon"

        /**
         * Window after [onSeekTo] during which incoming positions that exactly match the pre-seek
         * position are rejected as lingering shared-memory values. Short (the player writes the
         * post-seek position within a second or two); generous enough to cover the write gap.
         */
        private const val SEEK_RESIDUAL_REJECTION_WINDOW_MS = 3_000L

        /**
         * If no real position update arrives from shared memory for this duration, the writer
         * is considered completely dead (not just screen-off frozen). A one-shot warning is
         * logged so the arbiter can consider falling back to another producer.
         */
        private const val STALE_POSITION_THRESHOLD_MS = 15_000L

        /**
         * Wall-clock budget after which a silent position source is treated with suspicion while
         * playing. Past this: extrapolation CONTINUES while it stays within the song duration
         * (Doze freezes the shared-memory writer for the whole song while playback continues —
         * the canonical AOD scenario, see issue #3); only once the extrapolation passes the song
         * end (or the duration is unknown) is the writer declared dead and [positionUnknown] set
         * (the active line is cleared) instead of fabricating progress past the song.
         */
        private const val MAX_EXTRAPOLATION_MS = 45_000L

        /** Poll interval for the position-silence watchdog. */
        private const val POSITION_WATCHDOG_POLL_MS = 5_000L

        /**
         * No `onPositionChanged` at all for this long while playing → the SDK's callback path is
         * dead (not merely a frozen shared-memory writer, which still fires callbacks with the
         * stalled value at ~60 Hz). See [shouldForceResubscribePositionFeed].
         */
        internal const val POSITION_SILENCE_RESUBSCRIBE_MS = 20_000L

        /** Minimum gap between two forced resubscribes, so a persistent failure doesn't hammer IPC. */
        internal const val RESUBSCRIBE_COOLDOWN_MS = 30_000L

        /** Default render modes when customization is unavailable; matches SpicyBridgeState defaults. */
        private fun defaultRenderModes() = ProducerRenderModes(
            weight = "Medium",
            textSize = "normal",
            textSizeCustom = 100,
            secondary = "Main only",
            animation = "Karaoke fill",
            glow = "Off",
            lineSyncFill = "Top to bottom",
            overflow = "Wrap",
            transition = "Fade up",
            font = "spotify"
        )
    }
}

/**
 * Decision rule for the position-silence watchdog. Fires only when ALL hold:
 * - `playing`: during a real pause the position stream going quiet is expected, not a fault;
 * - silence beyond [LyriconLyricProducer.POSITION_SILENCE_RESUBSCRIBE_MS]: the ~60 Hz feed
 *   stopping entirely (a frozen writer still fires callbacks with the stalled value, so total
 *   silence means the callback path itself died — the 12:26 capture sat at age=519s);
 * - the previous attempt is older than [LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS] so a
 *   persistent failure retries at most once per cooldown window instead of hammering IPC every
 *   poll.
 */
internal fun shouldForceResubscribePositionFeed(
    silenceMs: Long,
    playing: Boolean,
    sinceLastAttemptMs: Long
): Boolean = playing &&
    silenceMs > LyriconLyricProducer.POSITION_SILENCE_RESUBSCRIBE_MS &&
    sinceLastAttemptMs > LyriconLyricProducer.RESUBSCRIBE_COOLDOWN_MS
