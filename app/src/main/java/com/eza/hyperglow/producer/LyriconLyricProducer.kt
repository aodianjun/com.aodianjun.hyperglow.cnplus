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
                previousSongLastPositionMs = -1L
                seekRejectPositionMs = -1L
                seekClockMs = 0L
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
                previousSongLastPositionMs = -1L
                seekRejectPositionMs = -1L
                seekClockMs = 0L
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
            // When resuming playback after a pause, reset the extrapolation clock so we don't
            // jump forward by the entire pause duration on the next stalled position callback.
            if (isPlaying && lastRealPositionClockMs >= 0L) {
                lastRealPositionClockMs = clock()
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
                // Ignore the stale value; extrapolate from the last real position regardless of
                // isPlayingState. The playing flag is unreliable (MediaSession jitter between
                // PLAYING↔BUFFERING can leave it stuck at false), and the real position clock
                // is the only trustworthy signal. When the player is truly paused the shared
                // memory position is frozen and the extrapolated position drifts harmlessly
                // (the line stays the same within a typical pause), corrected on resume.
                if (lastRealPositionClockMs >= 0L) {
                    val elapsed = now - lastRealPositionClockMs
                    currentPositionMs = lastRealPositionMs + elapsed
                    if (!extrapolating) {
                        extrapolating = true
                        AppLog.i(
                            "LyriconLyricProducer",
                            "residual position rejected ($position ms matches previous song); " +
                                "extrapolating from ${lastRealPositionMs}ms -> ${currentPositionMs}ms"
                        )
                    }
                }
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
                if (lastRealPositionClockMs >= 0L) {
                    val elapsed = now - lastRealPositionClockMs
                    currentPositionMs = lastRealPositionMs + elapsed
                    if (!extrapolating) {
                        extrapolating = true
                        AppLog.i(
                            "LyriconLyricProducer",
                            "seek residual rejected ($position ms matches pre-seek); " +
                                "extrapolating from ${lastRealPositionMs}ms -> ${currentPositionMs}ms"
                        )
                    }
                }
                recomputeAndEmit()
                return
            }
            if (position != lastRealPositionMs) {
                // Real position update from shared memory.
                // Accept wrap-around: when the song loops (single-track repeat), the shared
                // memory position resets to 0 while our extrapolated position may be at/beyond
                // duration. Treat a significantly lower position as a wrap-around rather than
                // rejecting it.
                val wasExtrapolating = extrapolating
                // When the player's position stream resumes after a stall it can briefly report a
                // value slightly *below* the position we extrapolated to (shared-memory latency /
                // stall-to-resume race). NetEase's ~60 Hz feed stalls and resumes constantly, so
                // snapping backward on every such resume rewinds the active line and makes it
                // flicker back and forth across a boundary. Within a small tolerance we keep the
                // monotonic extrapolated value (re-basing the extrapolation clock on it) so the
                // line advances smoothly; only a materially-lower real position (seek, song
                // wrap-around, or a genuine pause) is honored as a rewind.
                val realBehindMs = currentPositionMs - position
                val monotonicResume = wasExtrapolating &&
                    realBehindMs in 1..EXTRAPOLATION_RESUME_TOLERANCE_MS
                if (monotonicResume) {
                    lastRealPositionMs = currentPositionMs
                    lastRealPositionClockMs = now
                    lastRealPositionUpdateMs = now
                } else {
                    lastRealPositionMs = position
                    lastRealPositionClockMs = now
                    lastRealPositionUpdateMs = now
                    currentPositionMs = position
                }
                // A different value means the player has started writing the new song's progress.
                // Disable residual filtering — subsequent positions are from the new song.
                previousSongLastPositionMs = -1L
                // A real (different) position means the player has written the post-seek value;
                // stop rejecting the pre-seek position.
                seekRejectPositionMs = -1L
                if (wasExtrapolating && !monotonicResume) {
                    extrapolating = false
                    AppLog.i(
                        "LyriconLyricProducer",
                        "position resumed: pos=${position}ms (extrapolation stopped)"
                    )
                }
            } else if (lastRealPositionClockMs >= 0L) {
                // Position stalled (shared-memory writer frozen by MIUI screen-off). Extrapolate
                // from the last real position using wall-clock elapsed time. This keeps lyrics
                // advancing during AOD when the player process is frozen.
                //
                // Un-gated from isPlayingState: MediaSession jitter between PLAYING↔BUFFERING
                // can leave the flag stuck at false while the song is actually playing, causing
                // the lyrics to freeze permanently. The real position clock is the authoritative
                // signal. When the player is truly paused, the shared memory position is frozen
                // and the extrapolated drift is corrected on resume.
                //
                // Un-capped from duration: when a song loops (single-track repeat), the shared
                // memory position resets to 0 but our extrapolation would be capped at duration,
                // freezing the line at the end. Letting it exceed allows the real position to
                // correct it when the loop restarts.
                val elapsed = now - lastRealPositionClockMs
                currentPositionMs = lastRealPositionMs + elapsed
                val duration = currentSong?.duration ?: 0L
                // Stale detection: if we haven't seen a real position update for too long, the
                // shared-memory writer may be completely dead (not just screen-off frozen).
                // Log a warning so the arbiter can consider falling back to another producer.
                if (lastRealPositionUpdateMs >= 0L &&
                    now - lastRealPositionUpdateMs > STALE_POSITION_THRESHOLD_MS
                ) {
                    if (lastRealPositionUpdateMs != Long.MAX_VALUE) {
                        lastRealPositionUpdateMs = Long.MAX_VALUE // one-shot log
                        val staleSec = (now - lastRealPositionClockMs) / 1000
                        AppLog.w(
                            "LyriconLyricProducer",
                            "position stale for ${staleSec}s (last real=${lastRealPositionMs}ms " +
                                "extrapolated=${currentPositionMs}ms duration=${duration}ms)" +
                                if (duration > 0L && currentPositionMs > duration) {
                                    " — song may have looped"
                                } else {
                                    " — shared-memory writer may be dead"
                                }
                        )
                    }
                }
                if (!extrapolating) {
                    extrapolating = true
                    AppLog.i(
                        "LyriconLyricProducer",
                        "position stalled, extrapolating: base=${lastRealPositionMs}ms " +
                            "elapsed=${elapsed}ms -> ${currentPositionMs}ms"
                    )
                }
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
            // A seek is a deliberate position change — clear residual filtering so the new
            // position is accepted even if it coincidentally matches the previous song's last.
            previousSongLastPositionMs = -1L
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

        // 歌曲边界处理:息屏后数据源(如网易云)停止写位置,外推会越过歌曲时长继续累加。
        //
        // 旧实现用模运算把位置回绕到时长内(pos % duration),但这会让位置在 [0, duration) 间
        // 反复循环累加:每次回绕到 ~0ms 时 findTargetIndex 选不到行、活动行被清空,而投影层
        // 因 sampledAtElapsedMs==now 又把回绕后的低位置判为「回到开头」的有效位置
        // (extrapolationReliable 判定可信),于是行被反复选中/清空 → AOD '🎶' 占位闪烁 +
        // SystemUI 对相同占位 state 无去重的重建风暴(错误清单 #2/#3/#4)。
        //
        // 正确语义:外推一旦越过歌曲时长,说明当前这首歌已播完,之后不再有更多行。此时应
        // 清空活动行并结束外推,让投影层稳定显示占位;同时保持位置不变以触发状态去重,
        // 避免 60Hz 重复投递。等数据源写回真实位置(重播/切歌)或 onSongChanged 到来时再校正。
        val duration = song.duration
        if (extrapolating && duration > 0L && pos >= duration) {
            currentPositionMs = duration
            extrapolating = false
            if (currentLineIndex != -1) {
                currentLineIndex = -1
                cachedWords = null
            }
            AppLog.i(
                "LyriconLyricProducer",
                "extrapolation reached song end: pos=${pos}ms capped=${duration}ms " +
                    "(duration=${duration}ms); holding stable placeholder"
            )
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

        /**
         * When the player's position stream resumes after a stall, how far below our extrapolated
         * position it may be before we treat it as a real rewind (seek / wrap-around / pause)
         * rather than resume-stage jitter. The Lyricon feed from NetEase is delivered in bursts
         * (~60 Hz with frequent stall/resume), so a small backward drift is normal and must not
         * rewind the active line. Any drop beyond this (a genuine seek or the song resetting to
         * 0 on wrap-around) is honored as a rewind.
         */
        private const val EXTRAPOLATION_RESUME_TOLERANCE_MS = 300L

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
