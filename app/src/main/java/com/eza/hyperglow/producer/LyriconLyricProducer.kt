package com.eza.hyperglow.producer

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.os.Build
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song
import io.github.proify.lyricon.lyric.model.extensions.TimingNavigator
import io.github.proify.lyricon.subscriber.LyriconFactory
import io.github.proify.lyricon.subscriber.LyriconSubscriber
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
    internal val clock: () -> Long = SystemClock::elapsedRealtime
) : LyricProducer {

    override val id: LyricSource = LyricSource.LYRICON

    internal val mutableConnection = MutableStateFlow(ProducerConnection.DISCONNECTED)
    override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()

    internal val mutableState = MutableStateFlow<LyricProducerState?>(null)
    override val state: StateFlow<LyricProducerState?> = mutableState.asStateFlow()

    private var subscriber: LyriconSubscriber? = null
    internal var contextRef: Context? = null
    private var started = false

    // --- Position-feed watchdog ---
    // The 12:26 capture: onPositionChanged stopped firing entirely (the arbiter later logged
    // stale age=519s) while [connection] stayed CONNECTED — the SDK's callback path can die
    // silently (binder drop / internal poller stall) without any disconnect event. Before this
    // watchdog the only recovery was an app restart. The watchdog force-rebuilds the active
    // player subscription, mirroring SuperLyricLyricProducer's FORCE_RE_REGISTER pattern.
    @Volatile internal var lastPositionCallbackElapsedMs: Long = -1L
    @Volatile private var lastForcedResubscribeElapsedMs: Long = 0L
    private val watchdogScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // --- Song-feed watchdog (issue #64) ---
    // 位置通道活跃(回调持续、值在推进)但歌曲通道已丢(onSongChanged 不再到达,
    // currentSong 长期为空)的「半死」状态:位置静默看门狗覆盖不到(它只看回调是否
    // 完全停发),故障时只能靠重启恢复。这里跟踪歌曲缺席起点、provider 切换后等歌
    // 宽限起点、位置值推进时刻,供 maybeResubscribeOnSongFeed 判定强制重建订阅;
    // 重建后 SDK 会对当前在播歌曲补发 onSongChanged(与重启等效的恢复路径,见 #56)。
    @Volatile internal var songAbsentSinceMs: Long = -1L
    @Volatile internal var providerSyncPendingSinceMs: Long = -1L
    @Volatile internal var lastPositionFeedValueMs: Long = -1L
    @Volatile internal var lastAdvancingPositionClockMs: Long = -1L
    // 去重日志:每个「无歌」纪元只输出一次 position-dropped 告警,加载歌曲后复位。
    @Volatile internal var noSongDropLogged: Boolean = false

    // --- MediaSession stop detection (issue #27) ---
    // Lyricon 的 `onPlaybackStateChanged(false)` 对「会话仍 active 但已停止」的播放器(如
    // 网易云:active=true 且保留 metadata,仅 state 变 null)不会触发,导致 producer 一直
    // 当作在播,AOD / 概览长期显示旧曲目。这里周期性检查活动播放器对应 MediaSession 的真实
    // 播放状态:STATE_NONE / STATE_STOPPED / null 判定为「已停止」,按 onSongChanged(null)
    // 清空曲目;STATE_PAUSED 判定为「暂停」保留(现有暂停驻留链路负责后续超时清除)。
    @Volatile internal var activeProviderPackage: String? = null
    @Volatile private var stopConverged = false
    @Volatile private var stoppedStreak = 0
    private var mediaSessionManager: MediaSessionManager? = null
    private var notificationListenerComponent: ComponentName? = null

    // --- Ingress state, updated by playerListener; read by emit(). @Volatile for cross-thread. ---
    @Volatile internal var currentSong: Song? = null
    @Volatile internal var navigator: TimingNavigator<RichLyricLine>? = null
    @Volatile internal var currentPositionMs: Long = 0L
    @Volatile internal var isPlayingState: Boolean = false
    @Volatile internal var currentLineIndex: Int = -1
    @Volatile internal var cachedWords: List<LyricWord>? = null
    @Volatile internal var renderModesSnapshot: ProducerRenderModes = defaultRenderModes()

    // --- Position extrapolation state ---
    // When the player process is frozen by MIUI screen-off, the shared-memory position stops
    // updating but onPositionChanged keeps firing at ~60 Hz with the same stalled value. To keep
    // lyrics advancing, we extrapolate: currentPositionMs = lastRealPosition + elapsed wall-clock.
    @Volatile internal var lastRealPositionMs: Long = 0L
    @Volatile internal var lastRealPositionClockMs: Long = -1L
    @Volatile internal var extrapolating: Boolean = false

    // --- Extrapolation budget / unknown position ---
    // When the position source goes silent while playing, extrapolation advances the lyric for at
    // most MAX_EXTRAPOLATION_MS. Past that the writer is treated as dead (not merely screen-off
    // frozen): position is marked unknown and the active line is cleared so we never extrapolate
    // all the way to the song end over a long stall (the 19:33 capture extrapolated ~3m39s past
    // the real paused position and landed on the last line).
    @Volatile internal var positionUnknown: Boolean = false

    // --- Stale detection ---
    // If no real position update arrives for STALE_THRESHOLD_MS, the shared-memory writer
    // may be completely dead (not just stalled). Log a warning so the arbiter can consider
    // falling back to another producer.
    @Volatile internal var lastRealPositionUpdateMs: Long = -1L

    // --- Residual position rejection (song change) ---
    // After onSongChanged, the shared memory may still hold the previous song's position for a
    // long time until the player writes the new song's progress. Without filtering, the first
    // onPositionChanged with the stale value overwrites our reset (stale != 0 → "resumed" branch).
    // We reject any position that exactly matches the previous song's last position until a
    // different (real) position arrives — there is no fixed window, because NetEase's outro +
    // intro can leave the position source silent past any window, and the first value on resume
    // is still the old song's position.
    @Volatile internal var previousSongLastPositionMs: Long = -1L

    // --- Seek residual position rejection ---
    // After onSeekTo, the shared memory may still return the pre-seek position for a short
    // window until the player writes the new progress. Without filtering, that stale value
    // (old != seek target) is accepted by the "resumed" branch and undoes the seek target,
    // so the active line snaps back to the old position. We reject any position that exactly
    // matches the pre-seek position within a window after the seek. Once a different (real)
    // position arrives, filtering stops.
    @Volatile internal var seekRejectPositionMs: Long = -1L
    @Volatile internal var seekClockMs: Long = 0L

    // --- Pause-stale residual rejection (issue #10) ---
    // 播放在位置源已 stalled(AOD Doze 冻结)时暂停:共享内存仍持有暂停前的陈旧值(实测
    // 陈旧 16665ms,而媒体真实暂停点已达 23435ms)。暂停时我们把基准 re-base 到展示
    // (外推)位置 —— 即媒体真实暂停点;写入端若仍冻结,会以 ~60Hz 持续回传该陈旧值,
    // 它 != re-base 后的新基准,会被误当成暂停后的真实更新,把歌词行拉回更早的行。
    // 因此记录该陈旧值并拒绝,直到出现不同的(真实)位置。
    @Volatile internal var pauseStaleRejectMs: Long = -1L

    // --- Post-song-change position plausibility gate (issue #11) ---
    // 切歌后共享内存写入端的 base 元组可能仍是旧歌时间线(Doze 冻结了 base 更新,位置按
    // "base + 墙钟 × 速度" 公式续算):残留值 ≈ 切歌时旧歌时间线位置(≈旧歌时长),此后与
    // 真实位置同速推进、恒定偏移。旧歌比新歌长 → 残留越界(实测 487520ms > 188718ms,
    // 钳到歌尾清行导致整首无歌词);旧歌比新歌短 → 残留落在新歌时长内,被当真实值接受
    // 会让歌词整段错位。门控:切歌后首个真实位置必须 ≤ 切歌后墙钟 × 观测速率 + 容差
    // —— 新歌从切歌时刻起播,位置不可能更多;残留因恒定偏移(≈旧歌时长,远大于容差)
    // 被持续拒绝,期间从基准 0 外推(新歌正确推进,歌尾仍按 issue #9 钳制收尾)。首个
    // 可信值或 onSeekTo 后开门,恢复正常信任(wrap-around/seek/loop 均走既有逻辑)。
    @Volatile internal var songStartGateOpen = true
    @Volatile internal var songStartClockMs = 0L
    // 残留的推进速率(累计 Δpos/Δwall):残留与真实位置同速推进,其增量给出真实倍速,
    // 用于上界防止 1.25x~3x 倍速用户的真实位置在容差耗尽后被 1x 上界误拒。冻结残留
    // (Δpos=0,暂停型)不更新速率。
    @Volatile internal var gateRateX = 1.0
    @Volatile internal var gateRateAnchorPosMs = -1L
    @Volatile internal var gateRateAnchorClockMs = 0L
    // 首个被门控拒绝的残留值:冻结型残留会以 ~60Hz 重复回传同一值,即使上界随墙钟
    // 增长追上该值后也必须继续拒绝(暂停状态跳歌的场景)。
    @Volatile internal var gateFrozenRejectMs = -1L

    // issue #56:(重)连接后 SDK 会对「当前正在播放的歌」补发一次 onSongChanged。只有本次
    // 连接会话内已经见过歌时,后续 onSongChanged 才按「切歌」处理(归零 + 关闸);首次补发
    // 按「重同步」处理 —— 否则歌中途的真实位置会被合理性门控当残留拒绝,歌词从第 1 句
    // 重新开始,整条时间轴平移「已播时长」。
    @Volatile internal var songSeenSinceSubscribe = false

    // issue #56 建议四:门控拒绝路径留一条去重日志(position/bound/sinceStart/duration/歌名),
    // 便于现场直接判定「旧时间线残留」还是「中途订阅被误拒」。
    @Volatile internal var gateRejectLogged = false

    // Session/sequence for arbiter dedup (producerId:generation:sequence).

    @Volatile internal var generation: Int = 0
    @Volatile internal var sequence: Long = 0L

    internal val connectionListener = createConnectionListener()

    internal val playerListener = createPlayerListener()

    override fun start(context: Context) {
        if (started) {
            AppLog.i("LyriconLyricProducer", "start: already started (no-op)")
            return
        }
        started = true
        contextRef = context.applicationContext
        // issue #64:复位歌曲侧看门狗状态 —— 上一次运行遗留的缺席纪元/等歌宽限不应
        // 影响本次会话(新订阅建立后 SDK 会重新补发 onSongChanged,见 #56)。
        songAbsentSinceMs = -1L
        providerSyncPendingSinceMs = -1L
        lastPositionFeedValueMs = -1L
        lastAdvancingPositionClockMs = -1L
        noSongDropLogged = false
        AppLog.i("LyriconLyricProducer", "start: api=${Build.VERSION.SDK_INT}")

        // API < 27: LyriconFactory returns EmptyLyriconSubscriber (no-op). Per spec, this
        // producer MUST be a no-op below API 27, so we skip registration entirely.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            AppLog.i("LyriconLyricProducer", "start: API < 27, producer is no-op")
            return
        }

        // Issue #27: cross-app MediaSession query needs notification access (granted → the
        // LyricInfoNotificationListener is bound). Best-effort: without it detection is skipped.
        mediaSessionManager = contextRef?.getSystemService(MediaSessionManager::class.java)
        notificationListenerComponent = contextRef?.let { ComponentName(it, LyricInfoNotificationListener::class.java) }

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
        // MediaSession stop detector: clear tracks whose session reports stopped (issue #27).
        watchdogScope.launch { stopDetectionLoop() }
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

    /** Issue #27: a brand-new provider/song re-arms the stop detector. */
    internal fun resetStopDetection() {
        stopConverged = false
        stoppedStreak = 0
    }

    /**
     * Clear all song/lyrics/position ingress and emit a null state — the same idempotent teardown
     * used by `onSongChanged(null)` / `onActiveProviderChanged(null)`. Backs the MediaSession stop
     * detector (issue #27) so a stale-active-but-stopped player is fully released.
     */
    internal fun resetToIdle(reason: String) {
        lastRealPositionClockMs = clock()
        resetStopDetection()
        currentSong = null
        navigator = null
        currentLineIndex = -1
        cachedWords = null
        currentPositionMs = 0L
        lastRealPositionMs = 0L
        lastRealPositionUpdateMs = -1L
        extrapolating = false
        positionUnknown = false
        previousSongLastPositionMs = -1L
        seekRejectPositionMs = -1L
        seekClockMs = 0L
        pauseStaleRejectMs = -1L
        songStartGateOpen = true
        gateRateAnchorPosMs = -1L
        gateRateX = 1.0
        gateFrozenRejectMs = -1L
        // issue #64:进入无歌纪元 —— 起点只在首次缺席时记录,重复的清空(幂等路径)
        // 不推迟看门狗;provider 等歌宽限视为已被本次 SDK 回调应答,一并清除。
        if (songAbsentSinceMs < 0L) songAbsentSinceMs = lastRealPositionClockMs
        providerSyncPendingSinceMs = -1L
        mutableState.value = null
        AppLog.i("LyriconLyricProducer", "resetToIdle: $reason")
    }

    /**
     * Issue #27 watchdog loop: periodically check the active player's real MediaSession playback
     * state. Lyricon's `onPlaybackStateChanged(false)` never fires for a session that stays
     * "active" while stopped (NetEase quirk: active=true, metadata retained, state=null), so the
     * producer would otherwise keep reporting a stale playing track forever. Once the matched
     * session reports a definitively-stopped state for [STOP_CONFIRMATIONS] consecutive samples,
     * release the track like `onSongChanged(null)`.
     */
    private suspend fun stopDetectionLoop() {
        while (watchdogScope.isActive) {
            delay(STOP_DETECT_INTERVAL_MS)
            maybeClearOnStoppedPlayer()
        }
    }

    private fun maybeClearOnStoppedPlayer() {
        if (stopConverged) return
        val pkg = activeProviderPackage ?: return
        val song = currentSong ?: return
        // Safety: never clear a track that some session is genuinely still playing (e.g. the SDK
        // delivered a new app's song while `activeProviderPackage` still points at the stopped
        // old app). If any actively-playing session carries this song's title, it's live.
        if (anySessionActiveFor(song.name)) {
            stoppedStreak = 0
            return
        }
        val state = readActivePlayerPlaybackState(pkg) ?: return
        if (classifyActivePlayerPlayback(state) == ActivePlayerPlayback.STOPPED) {
            if (++stoppedStreak >= STOP_CONFIRMATIONS) {
                stopConverged = true
                AppLog.i(
                    "LyriconLyricProducer",
                    "active player $pkg session stopped (state=$state); clearing stale track"
                )
                isPlayingState = false
                resetToIdle("media-session-stopped (pkg=$pkg, state=$state)")
            }
        } else {
            stoppedStreak = 0
        }
    }

    /** True when some active MediaSession is playing and its metadata title matches [title]. */
    private fun anySessionActiveFor(title: String?): Boolean {
        val manager = mediaSessionManager ?: return false
        val component = notificationListenerComponent ?: return false
        if (title.isNullOrBlank()) return false
        return runCatching {
            manager.getActiveSessions(component).any { controller ->
                classifyActivePlayerPlayback(controller.playbackState?.state) ==
                    ActivePlayerPlayback.PLAYING &&
                    controller.metadata?.description?.title == title
            }
        }.getOrDefault(false)
    }

    /**
     * Read the active player's MediaSession [android.media.session.PlaybackState.getState].
     * Requires notification access so [MediaSessionManager.getActiveSessions] can see cross-app
     * sessions; returns null when unavailable (no match / no permission / query error) so the
     * detector safely no-ops rather than guessing.
     */
    private fun readActivePlayerPlaybackState(packageName: String): Int? {
        val manager = mediaSessionManager ?: return null
        val component = notificationListenerComponent ?: return null
        return runCatching {
            manager.getActiveSessions(component)
                .firstOrNull { it.packageName == packageName }
                ?.playbackState
                ?.state
        }.getOrNull()
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
            maybeResubscribeOnSongFeed()
        }
    }

    /**
     * Recovery action for a silent position feed: unsubscribe + re-subscribe the active player
     * listener, which re-arms the SDK's internal poller/callback registration. Idempotent-safe
     * via the cooldown in the decision function; failures are logged and retried after cooldown.
     */
    private fun maybeResubscribeOnPositionSilence() {
        if (subscriber == null) return
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
        // Re-anchor the heartbeat so the same silence doesn't re-trigger before the next poll.
        lastPositionCallbackElapsedMs = now
        forceResubscribeActivePlayer("position feed silent for ${silenceMs}ms while playing")
    }

    /**
     * issue #64:「位置在推、歌曲缺失」半死状态的恢复动作。触发条件(全部成立,见
     * [shouldForceResubscribeSongFeed]):播放中 + 位置值仍在推进(证明播放器真在播、
     * SDK 位置通道活着)+ 歌曲缺席超阈值(或 provider 切换后等歌超宽限)+ 冷却期外。
     * 强制重建订阅后 SDK 会对当前在播歌曲补发 onSongChanged,与重启等效(见 #56)。
     */
    private fun maybeResubscribeOnSongFeed() {
        if (subscriber == null) return
        val now = clock()
        val songAbsentMs = songAbsentSinceMs.let { if (it < 0L) -1L else now - it }
        val providerPendingMs = providerSyncPendingSinceMs.let { if (it < 0L) -1L else now - it }
        val positionAdvancing = lastAdvancingPositionClockMs >= 0L &&
            now - lastAdvancingPositionClockMs < SONG_FEED_POSITION_FRESH_MS
        if (!shouldForceResubscribeSongFeed(
                playing = isPlayingState,
                songAbsentMs = songAbsentMs,
                providerSyncPendingMs = providerPendingMs,
                positionAdvancing = positionAdvancing,
                sinceLastAttemptMs = now - lastForcedResubscribeElapsedMs
            )
        ) {
            return
        }
        // 重锚缺席/宽限起点:一次失败的重建不会在冷却后按同一纪元反复重试刷屏;
        // 若歌曲通道真的恢复,onSongChanged 会把它们清成 -1(见 issue #64)。
        if (songAbsentSinceMs >= 0L) songAbsentSinceMs = now
        if (providerSyncPendingSinceMs >= 0L) providerSyncPendingSinceMs = now
        forceResubscribeActivePlayer(
            "song feed missing while position advancing " +
                "(absent=${songAbsentMs}ms providerPending=${providerPendingMs}ms)"
        )
    }

    /**
     * Shared forced-resubscribe action: rebuild the active-player subscription so the SDK
     * re-arms its poller and re-delivers the current song/state. Applies the shared attempt
     * timestamp so every watchdog respects the same cooldown window.
     */
    private fun forceResubscribeActivePlayer(reason: String) {
        val sub = subscriber ?: return
        lastForcedResubscribeElapsedMs = clock()
        AppLog.w("LyriconLyricProducer", "$reason; rebuilding subscription")
        runCatching {
            sub.unsubscribeActivePlayer(playerListener)
            sub.subscribeActivePlayer(playerListener)
        }.onFailure { AppLog.w("LyriconLyricProducer", "forced resubscribe failed", it) }
    }

    /**
     * 整首歌快照：直接取内存里的排序行数组（与 TimingNavigator 同源，onSongChanged 时
     * 已按 begin 排序），行自带真实 begin/end 与可选 translation/roma/words。trackUri 与
     * emit() 的构造保持同一表达式，保证会话三元组校验成立。实现是纯读 + 一次映射，
     * 管线只在新会话首次进入插件链时调用。
     */
    override fun fullSongSnapshot(): LyricSongSnapshot? {
        val song = currentSong ?: return null
        val lines = navigator?.source ?: return null
        if (lines.isEmpty()) return null
        return LyricSongSnapshot(
            producerId = PRODUCER_ID,
            generation = generation,
            trackUri = "lyricon:${song.id ?: song.name}",
            durationMs = song.duration,
            rows = LyricTimelineSanitizer.sanitizeSnapshotRows(
                lines.map { line ->
                    LyricSongRow(
                        startMs = line.begin,
                        endMs = line.end,
                        text = line.text.orEmpty(),
                        translation = line.translation.orEmpty(),
                        roma = line.roma.orEmpty(),
                        words = line.toLyricWords()?.takeIf { it.isNotEmpty() }
                    )
                }
            )
        )
    }

    companion object {
        internal const val PRODUCER_ID = "lyricon"

        /**
         * Window after [onSeekTo] during which incoming positions that exactly match the pre-seek
         * position are rejected as lingering shared-memory values. Short (the player writes the
         * post-seek position within a second or two); generous enough to cover the write gap.
         */
        internal const val SEEK_RESIDUAL_REJECTION_WINDOW_MS = 3_000L

        /**
         * If no real position update arrives from shared memory for this duration, the writer
         * is considered completely dead (not just screen-off frozen). A one-shot warning is
         * logged so the arbiter can consider falling back to another producer.
         */
        internal const val STALE_POSITION_THRESHOLD_MS = 15_000L

        /**
         * Wall-clock budget after which a silent position source is treated with suspicion while
         * playing. Past this: extrapolation CONTINUES while it stays within the song duration
         * (Doze freezes the shared-memory writer for the whole song while playback continues —
         * the canonical AOD scenario, see issue #3); only once the extrapolation passes the song
         * end (or the duration is unknown) is the writer declared dead and [positionUnknown] set
         * (the active line is cleared) instead of fabricating progress past the song.
         */
        internal const val MAX_EXTRAPOLATION_MS = 45_000L

        /** Poll interval for the position-silence watchdog. */
        private const val POSITION_WATCHDOG_POLL_MS = 5_000L

        /**
         * issue #27: poll interval for the MediaSession stop detector. Multiple matched samples
         * are required before clearing (see [STOP_CONFIRMATIONS]) so a transient state blink on a
         * song change (the transport-gap non-playing edge) doesn't wipe the track.
         */
        internal const val STOP_DETECT_INTERVAL_MS = 4_000L

        /** issue #27: consecutive stopped samples before the track is cleared as stale. */
        internal const val STOP_CONFIRMATIONS = 3

        /**
         * No `onPositionChanged` at all for this long while playing → the SDK's callback path is
         * dead (not merely a frozen shared-memory writer, which still fires callbacks with the
         * stalled value at ~60 Hz). See [shouldForceResubscribePositionFeed].
         */
        internal const val POSITION_SILENCE_RESUBSCRIBE_MS = 20_000L

        /** Minimum gap between two forced resubscribes, so a persistent failure doesn't hammer IPC. */
        internal const val RESUBSCRIBE_COOLDOWN_MS = 30_000L

        /**
         * issue #64:位置通道活跃但歌曲数据长期缺席(半死)时,强制重建订阅前的缺席阈值。
         * 正常切歌/(重)连补发的 onSongChanged 在数秒内到达;播放中 30s 无歌且位置值仍在
         * 推进即异常。必须明显大于正常切歌间隙,并大于 [PROVIDER_SYNC_GRACE_MS]。
         */
        internal const val SONG_ABSENCE_RESUBSCRIBE_MS = 30_000L

        /**
         * issue #64:provider 切换后等待 SDK 补发 onSongChanged 的宽限期;超过仍未到(且
         * 位置在推进、播放中)判定歌曲通道半死,由歌曲侧看门狗强制重建订阅。
         */
        internal const val PROVIDER_SYNC_GRACE_MS = 10_000L

        /**
         * issue #64:判定「位置仍在推进」的新鲜度窗口 —— 窗口内有变化的位置值才证明播放器
         * 真在播。已停止/被冻结的播放器只会重复同一值,不应期待新歌,看门狗不得触发。
         */
        internal const val SONG_FEED_POSITION_FRESH_MS = 10_000L

        /**
         * When the player's position stream resumes after a stall, how far below our extrapolated
         * position it may be before we treat it as a real rewind (seek / wrap-around / pause)
         * rather than resume-stage jitter. The Lyricon feed from NetEase is delivered in bursts
         * (~60 Hz with frequent stall/resume), so a small backward drift is normal and must not
         * rewind the active line. Any drop beyond this (a genuine seek or the song resetting to
         * 0 on wrap-around) is honored as a rewind.
         */
        internal const val EXTRAPOLATION_RESUME_TOLERANCE_MS = 300L

        /**
         * issue #11: tolerance for the post-song-change plausibility bound — covers song-change
         * detection lag (SDK poll interval) and position base timestamp skew. Must stay well
         * below the typical old-timeline residual offset (≈ the previous song's duration) so
         * residuals are rejected, and well above the detection lag so genuine positions pass.
         */
        internal const val SONG_START_PLAUSIBILITY_TOLERANCE_MS = 10_000L

        /**
         * issue #11: a real position may slightly exceed the metadata duration near the song's
         * true end (metadata underestimates the audio); within this tolerance it is capped to
         * the duration, beyond it the writer is deemed untrustworthy (stale timeline) and the
         * value is treated as stalled (extrapolate from the last good base).
         */
        internal const val BEYOND_DURATION_TOLERANCE_MS = 2_000L

        /** issue #11: clamp for the residual-advance rate estimate (NetEase speed range). */
        internal const val GATE_RATE_MIN_X = 0.5
        internal const val GATE_RATE_MAX_X = 3.0

        /** issue #11: minimum wall-time span before a rate sample is trusted (div-noise guard). */
        internal const val GATE_RATE_MIN_DELTA_MS = 500L

        /** Default render modes when customization is unavailable; matches SpicyBridgeState defaults. */
        internal fun defaultRenderModes() = ProducerRenderModes(
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
