package com.eza.hyperglow.aod

import android.content.Context
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.bridge.SpicyBridgeState
import com.eza.hyperglow.bridge.SpicyBridgeStore
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.plugin.PluginPipeline
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricProducers
import com.eza.hyperglow.producer.SongArtworkRepository
import com.eza.hyperglow.root.projection.currentProcessUserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal data class FallbackRefreshSession(
    val producerId: String,
    val generation: Int,
    val trackUri: String,
    val status: String
)

internal data class ProjectionSessionIdentity(
    val producerId: String,
    val generation: Int,
    val trackUri: String
) {
    companion object {
        /** [SpicyBridgeState] 适配——保留供 bridge 包测试使用。 */
        fun from(state: SpicyBridgeState) = ProjectionSessionIdentity(
            producerId = state.producerId,
            generation = state.generation,
            trackUri = state.trackUri
        )

        /** [LyricProducerState] 适配——Phase 3 引擎切换后的主入口。 */
        fun from(state: LyricProducerState) = ProjectionSessionIdentity(
            producerId = state.producerId,
            generation = state.generation,
            trackUri = state.trackUri
        )
    }
}

internal data class ProjectionPublicationToken(
    val generation: Long,
    val session: ProjectionSessionIdentity
)

/**
 * Guards projection publication against stale/in-flight state. Phase 3: operates on
 * [LyricProducerState] (the producer boundary); the document-reference checks the Spicy path
 * needed are gone because producers now select the active row before emitting (spec clause 8).
 */
internal class ProjectionPublicationGuard {
    private var generation = 0L
    private var activeSession: ProjectionSessionIdentity? = null

    @Synchronized
    fun begin(state: LyricProducerState?): ProjectionPublicationToken? {
        generation++
        activeSession = state?.let(ProjectionSessionIdentity::from)
        return activeSession?.let { ProjectionPublicationToken(generation, it) }
    }

    @Synchronized
    fun current(state: LyricProducerState): ProjectionPublicationToken? {
        val session = ProjectionSessionIdentity.from(state)
        if (session != activeSession) return null
        return ProjectionPublicationToken(generation, session)
    }

    @Synchronized
    fun invalidate() {
        generation++
        activeSession = null
    }

    /**
     * True only when [candidate] is still the arbiter's current active state ([current]) and the
     * token matches the live generation/session. The document checks the Spicy path needed are
     * dropped: the active row is now part of [LyricProducerState] itself, so state-reference
     * equality is sufficient to reject a layout built from superseded state.
     */
    @Synchronized
    fun canPublish(
        token: ProjectionPublicationToken,
        candidate: LyricProducerState,
        current: LyricProducerState?
    ): Boolean = token.generation == generation &&
        token.session == activeSession &&
        candidate === current &&
        ProjectionSessionIdentity.from(candidate) == token.session
}

internal class ProjectionReleaseGate {
    private var generation = 0L

    @Synchronized
    fun schedule(): Long = ++generation

    @Synchronized
    fun cancel() {
        generation++
    }

    @Synchronized
    fun isCurrent(token: Long): Boolean = token == generation
}

/**
 * [AodProjectionEngine.classifyStaleSample] 的处置结果:同会话过时样本软跳过(由 collect
 * 送达的当前样本驱动投影),会话失效才释放(发布 visible=false 的 Hidden)。
 */
internal enum class StaleSampleAction { SoftSkip, Release }

object AodProjectionEngine {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var scheduler: Job? = null
    private var transitionKeepAlive: Job? = null
    private var fallbackSession: FallbackRefreshSession? = null

    init {
        // 歌曲图片异步补帧:出帧时把帧挂到同曲目已发布快照上重播(见 AodStateBridge.publishArtwork)。
        SongArtworkRepository.onFrameReady = { trackGeneration, frame ->
            AodStateBridge.publishArtwork(trackGeneration, frame.jpeg, frame.key)
        }
    }
    private var releaseJob: Job? = null
    private var pauseConfirmJob: Job? = null
    private var pendingPauseSession: ProjectionSessionIdentity? = null
    private var confirmedPauseSession: ProjectionSessionIdentity? = null
    private var sessionKey = ""
    private var lastKeepAliveAt = 0L
    private var lastCustomizationPublishAt = 0L
    private var started = false
    private var appContext: Context? = null
    private val publicationGuard = ProjectionPublicationGuard()
    private val releaseGate = ProjectionReleaseGate()
    private val powerSessionPolicy = AodPowerSessionPolicy()
    private val metadataIntroPolicy = SongMetadataIntroPolicy()

    /**
     * The single ingress: [LyricProducers.arbiter].[LyricProducerArbiter.active]. Phase 3 engine
     * switch — the engine no longer reads `SpicyBridgeStore.state` / `SpicyBridgeDocumentStore`
     * for projection (spec clause 30). A background loop still expires stale Spicy bridge state
     * to keep that store's lifecycle intact, but it does not feed projection.
     */
    @Synchronized
    fun start(context: Context) {
        if (started) return
        appContext = context.applicationContext
        started = true
        publishCustomizationIfDue(SystemClock.elapsedRealtime())
        scope.launch {
            LyricProducers.arbiter.active.collect(::handleState)
        }
        scope.launch {
            while (true) {
                delay(1_000L)
                SpicyBridgeStore.expireIfStale()
            }
        }
    }

    @Synchronized
    private fun handleState(state: LyricProducerState?) {
        val publicationToken = publicationGuard.begin(state)
        if (state == null) {
            stopScheduler()
            cancelPauseConfirmation()
            scheduleRelease()
            return
        }
        cancelRelease()
        if (!isCurrentActive(state)) {
            // 引用不等只说明样本被新实例替代,不说明会话失效:arbiter 播放中每 ~100ms tick
            // 写新实例(签名含递增 sequence),收集协程读到 S_n 后 arbiter 已写 S_{n+1} 是
            // 常态。同会话(producerId/generation/trackUri 相同)软跳过——当前样本会经
            // collect 送达并由其驱动投影,过时样本交给 canPublish 的 token 校验拒绝。
            // 只有换歌/换源或 active 已清空才走原释放路径:releaseNow 发布 visible=false
            // 的 Hidden,而 Xiaomi 的 hide 不可逆,误判即终态隐藏。
            if (classifyStaleSample(state, LyricProducers.arbiter.active.value) ==
                StaleSampleAction.SoftSkip
            ) return
            cancelPauseConfirmation()
            releaseNow(playbackActive = false)
            return
        }
        if (!state.playing) {
            val session = ProjectionSessionIdentity.from(state)
            if (!shouldOpenPauseGrace(session, confirmedPauseSession)) {
                cancelPendingPauseConfirmation()
                releaseNow(playbackActive = false, pauseRetentionEligible = true)
            } else if (isPlayingTransportGap(state)) {
                cancelPendingPauseConfirmation()
                releaseNow(playbackActive = true)
            } else {
                schedulePauseConfirmation(session)
            }
            return
        }
        cancelPauseConfirmation()
        if (shouldShowPlaybackFallback(state.status, state.playing)) {
            stopScheduler()
            project(state, publicationToken = requireNotNull(publicationToken))
            startStatusKeepAlive(state)
            return
        }
        if (state.status != "ready") {
            releaseNow(playbackActive = state.playing)
            return
        }
        stopStatusKeepAlive()
        ensureScheduler(state)
        project(state, publicationToken = requireNotNull(publicationToken))
    }

    /**
     * Whether [state] is still the arbiter's current active state. Replaces the Spicy path's
     * `SpicyBridgeStore.isCurrentActive`: the arbiter clears `active` on disconnect/staleness, so
     * reference equality against [LyricProducerArbiter.active].value is the authoritative check.
     */
    private fun isCurrentActive(state: LyricProducerState): Boolean =
        LyricProducers.arbiter.active.value === state

    /**
     * [handleState] 收到「引用已非当前」样本时的处置判定(纯函数,便于单测)。
     *
     * 会话身份由 [ProjectionSessionIdentity] 决定:同一会话的新实例只是位置/内容更新
     * (arbiter 每 ~100ms tick 写新引用),不是会话失效——软跳过,当前样本会经 collect
     * 送达并由其驱动投影;只有 [current] 为 null(active 已清空)或会话不同(换歌/换源)
     * 才是真失效,需要释放。
     *
     * 调用方保证 [sample] 已被引用相等判为「非当前」;实例相同不属于本函数,防御性返回
     * [StaleSampleAction.SoftSkip](同一实例必然同会话,绝不释放——Hidden 不可逆)。
     */
    internal fun classifyStaleSample(
        sample: LyricProducerState,
        current: LyricProducerState?
    ): StaleSampleAction = when {
        current == null -> StaleSampleAction.Release
        ProjectionSessionIdentity.from(current) == ProjectionSessionIdentity.from(sample) ->
            StaleSampleAction.SoftSkip
        else -> StaleSampleAction.Release
    }

    @Synchronized
    private fun ensureScheduler(state: LyricProducerState) {
        val key = "${state.producerId}:${state.generation}:${state.trackUri}"
        if (scheduler?.isActive == true && sessionKey == key) return
        stopScheduler()
        val expectedSession = ProjectionSessionIdentity.from(state)
        sessionKey = key
        scheduler = scope.launch {
            while (true) {
                val current = LyricProducers.arbiter.active.value ?: break
                if (!isCurrentActive(current) ||
                    current.status != "ready" || !current.playing ||
                    ProjectionSessionIdentity.from(current) != expectedSession
                ) break
                val publicationToken = publicationGuard.current(current) ?: break
                val now = SystemClock.elapsedRealtime()
                project(current, now, publicationToken)
                if (keepAliveDue(lastKeepAliveAt, now)) {
                    AodStateBridge.refreshVisibleState()
                    lastKeepAliveAt = now
                }
                delay(100L)
            }
        }
    }

    @Synchronized
    private fun startStatusKeepAlive(state: LyricProducerState) {
        if (!AodStateBridge.hasVisibleState()) return
        val expected = fallbackRefreshSession(state)
        if (transitionKeepAlive?.isActive == true && fallbackSession == expected) return
        stopStatusKeepAlive()
        fallbackSession = expected
        transitionKeepAlive = scope.launch {
            while (true) {
                delay(FALLBACK_REFRESH_INTERVAL_MS)
                val current = LyricProducers.arbiter.active.value ?: break
                if (!isCurrentActive(current) || !canRefreshFallback(expected, current)) break
                val publicationToken = publicationGuard.current(current) ?: break
                val now = SystemClock.elapsedRealtime()
                project(current, now, publicationToken)
                if (keepAliveDue(lastKeepAliveAt, now)) {
                    AodStateBridge.refreshVisibleState()
                    lastKeepAliveAt = now
                }
            }
        }
    }

    @Synchronized
    private fun stopStatusKeepAlive() {
        transitionKeepAlive?.cancel()
        transitionKeepAlive = null
        fallbackSession = null
    }

    /**
     * A non-playing edge is provisional. Spotify reports the old generation as not playing about a
     * second before the next track arrives, so an immediate pause classification releases AOD
     * lifetime and replays Xiaomi's hide in the middle of a song change. The edge is published as a
     * still-playing transport gap first; only a producer that is still non-playing on the same
     * session after the bounded window becomes real pause retention.
     *
     * The window must cover the whole track-change gap: NetEase observed 0.96 s of playing=false
     * plus lyric-load time before the new track's playing=true (09:53 capture), and the player can
     * re-emit a late onStop for the old track on top. A 1.5 s window let the OLD session's pause
     * confirm commit visible=false while the new track was already loading — the systemui guard
     * died, Xiaomi closed the AOD surface, and every snapshot the module published during the
     * intro had no surface to draw on. 5 s covers the observed gap; the cost is that a genuine
     * pause now enters retention (and hides for pauseShowContent=off users) 5 s in, during which
     * the transport-gap freeze keeps presenting.
     */
    @Synchronized
    private fun schedulePauseConfirmation(session: ProjectionSessionIdentity) {
        if (pauseConfirmJob?.isActive == true && pendingPauseSession == session) return
        cancelPendingPauseConfirmation()
        pendingPauseSession = session
        releaseNow(playbackActive = true)
        pauseConfirmJob = scope.launch {
            delay(PAUSE_CONFIRM_MS)
            confirmPause(session)
        }
    }

    @Synchronized
    private fun confirmPause(session: ProjectionSessionIdentity) {
        if (pendingPauseSession != session) return
        pauseConfirmJob = null
        pendingPauseSession = null
        val current = LyricProducers.arbiter.active.value
        if (!shouldCommitPauseRetention(
                pendingSession = session,
                current = current,
                currentActive = current != null && isCurrentActive(current)
            )
        ) return
        confirmedPauseSession = session
        releaseNow(playbackActive = false, pauseRetentionEligible = true)
    }

    @Synchronized
    private fun cancelPendingPauseConfirmation() {
        pauseConfirmJob?.cancel()
        pauseConfirmJob = null
        pendingPauseSession = null
    }

    @Synchronized
    private fun cancelPauseConfirmation() {
        cancelPendingPauseConfirmation()
        confirmedPauseSession = null
    }

    @Synchronized
    private fun scheduleRelease() {
        if (releaseJob?.isActive == true) return
        val releaseToken = releaseGate.schedule()
        releaseJob = scope.launch {
            delay(TRANSITION_GRACE_MS)
            releaseIfCurrent(releaseToken)
        }
    }

    @Synchronized
    private fun releaseIfCurrent(releaseToken: Long) {
        if (!releaseGate.isCurrent(releaseToken)) return
        val current = LyricProducers.arbiter.active.value
        if (current != null && isCurrentActive(current) && current.playing) return
        releaseJob = null
        releaseNow(playbackActive = false)
    }

    @Synchronized
    private fun cancelRelease() {
        releaseGate.cancel()
        releaseJob?.cancel()
        releaseJob = null
    }

    @Synchronized
    private fun releaseNow(
        playbackActive: Boolean = false,
        pauseRetentionEligible: Boolean = false
    ) {
        cancelRelease()
        publicationGuard.invalidate()
        // 发布代次递增必须排在 Hidden 之前：在途投影（验证已过、提交未到）的旧可见快照
        // 会被 AodStateBridge.publishIfCurrent 在 bridge 锁内拒绝，不再覆盖这次 release。
        AodStateBridge.invalidatePublications()
        stopStatusKeepAlive()
        stopScheduler()
        if (!playbackActive) powerSessionPolicy.clear()
        AodStateBridge.publish(
            AodDisplayState(
                visible = false,
                playbackActive = playbackActive,
                pauseRetentionEligible = pauseRetentionEligible,
                userId = currentProcessUserId()
            )
        )
    }

    /**
     * Maps [state] to [AodDisplayState] via the pure [projectToDisplay] function (spec clause 8:
     * projection MUST NOT re-select the active line — producers do that). Publication is gated on
     * the state still being the arbiter's current active state and the token matching.
     *
     * 提交前的发布代次采样必须排在最终验证（[ProjectionPublicationGuard.canPublish]）**之前**：
     * 验证与提交分属 guard/bridge 两把锁，暂停确认或延迟释放可在两者之间插入 release；
     * 采样之后的任何 release 都会让 [AodStateBridge.publishIfCurrent] 在 bridge 锁内拒绝
     * 这次旧提交（TOCTOU 窗口关闭）。不能只靠 canPublish——它已经返回、锁已释放。
     */
    private fun project(
        state: LyricProducerState,
        now: Long = SystemClock.elapsedRealtime(),
        publicationToken: ProjectionPublicationToken
    ) {
        val publicationEpoch = AodStateBridge.currentPublicationEpoch()
        publishCustomizationIfDue(now)
        val prefs = appContext?.let(AodRenderPreferences::read) ?: AodRenderConfig()
        val compiled = appContext?.let(CustomizationRepository::loadCompiled)
        // 插件富化在投影前同步查表:无插件结果时原样返回同一实例(保持下方
        // isCurrentActive 的引用相等校验),有结果时仅覆盖内容字段。
        val effectiveState = PluginPipeline.enrich(state)
        // 歌曲图片:任一曲面(锁屏/息屏)显示开关打开时按曲目拉取已校对封面帧
        // (包名/曲目校对不过=无封面)。取图为异步,本次投影先带已有帧,出帧后经
        // onFrameReady 补挂重播;各曲面是否展示由自己的 profile 决定。
        val artwork = if (compiled?.profiles?.values?.any { it.artworkVisible } == true) {
            appContext?.let { context ->
                SongArtworkRepository.ensure(
                    context,
                    trackGeneration(effectiveState),
                    effectiveState.title,
                    effectiveState.artist
                )
            }
            SongArtworkRepository.frameFor(effectiveState.title, effectiveState.artist)
        } else {
            null
        }
        val projectedState = projectToDisplay(
            state = effectiveState,
            now = now,
            prefs = prefs,
            compiled = compiled,
            metadataIntroPolicy = metadataIntroPolicy,
            powerSessionPolicy = powerSessionPolicy,
            userId = currentProcessUserId(),
            artworkJpeg = artwork?.jpeg ?: ByteArray(0),
            artworkKey = artwork?.key ?: ""
        )
        if (!publicationGuard.canPublish(
                token = publicationToken,
                candidate = state,
                current = LyricProducers.arbiter.active.value
            ) || !isCurrentActive(state) || !state.playing
        ) return
        logRenderModeProbe(compiled, projectedState)
        AodStateBridge.publishIfCurrent(publicationEpoch, projectedState)
    }

    private var lastRenderProbeKey = ""

    /**
     * 诊断探针(配置下发排查):发布快照的渲染模式取值来源(编译 profile 覆盖 vs
     * renderModes 兜底)。只在值变化时留痕,与 SystemUI 侧 AodSurfaceController 的
     * 「Render profile probe」配对,定位「配置已下发但画布仍渲染旧档」断在哪一跳。
     */
    private fun logRenderModeProbe(compiled: CompiledCustomization?, state: AodDisplayState) {
        val profile = compiled?.profiles?.get(SceneCompiler.SURFACE_AOD)
        val key = "profile=${profile != null} anim=${state.animationMode} glow=${state.glowMode} " +
            "profileAnim=${profile?.animation} profileGlow=${profile?.glow}"
        if (key == lastRenderProbeKey) return
        lastRenderProbeKey = key
        AppLog.w("AodProjectionEngine", "Render mode probe: $key")
    }

    private fun publishCustomizationIfDue(now: Long) {
        if (now - lastCustomizationPublishAt < CUSTOMIZATION_REFRESH_MS) return
        val context = appContext ?: return
        lastCustomizationPublishAt = now
        AodStateBridge.publishConfiguration(
            RuntimeCustomization.loadCompiled(context),
            currentProcessUserId(),
            experimentalMode = AodRenderPreferences.read(context).experimentalMode
        )
    }

    @Synchronized
    private fun stopScheduler() {
        scheduler?.cancel()
        scheduler = null
        sessionKey = ""
        lastKeepAliveAt = 0L
    }

    fun projectedPosition(state: LyricProducerState, now: Long): Long =
        projectedPosition(
            positionMs = state.positionMs,
            sampledAtElapsedMs = state.sampledAtElapsedMs,
            speed = state.speed,
            playing = state.playing,
            durationMs = state.durationMs,
            now = now
        )

    fun keepAliveDue(lastAt: Long, now: Long): Boolean =
        lastAt <= 0L || now - lastAt >= KEEP_ALIVE_INTERVAL_MS

    internal fun keepAliveIntervalMs(): Long = KEEP_ALIVE_INTERVAL_MS

    internal fun fallbackRefreshSession(state: LyricProducerState) = FallbackRefreshSession(
        state.producerId,
        state.generation,
        state.trackUri,
        state.status
    )

    internal fun canRefreshFallback(
        expected: FallbackRefreshSession,
        current: LyricProducerState
    ): Boolean = current.playing &&
        shouldShowPlaybackFallback(current.status, current.playing) &&
        fallbackRefreshSession(current) == expected

    internal fun fallbackRefreshIntervalMs(): Long = FALLBACK_REFRESH_INTERVAL_MS

    fun shouldShowPlaybackFallback(status: String, playing: Boolean): Boolean =
        playing && (status == "loading" || status == "no_lyrics")

    internal fun isPlayingTransportGap(state: LyricProducerState): Boolean =
        !state.playing && state.status == "loading"

    internal fun pauseConfirmWindowMs(): Long = PAUSE_CONFIRM_MS

    /**
     * The still-playing grace opens once per session. A producer that keeps publishing while paused
     * must not reopen it through either a confirmation window or a `loading` transport gap,
     * otherwise a real pause flaps between keepalive and retention for as long as it lasts.
     */
    internal fun shouldOpenPauseGrace(
        session: ProjectionSessionIdentity,
        confirmedSession: ProjectionSessionIdentity?
    ): Boolean = confirmedSession != session

    /**
     * True only when the producer is still non-playing on the same session after the confirmation
     * window. A resumed producer or a new session means the non-playing edge was a song change.
     */
    internal fun shouldCommitPauseRetention(
        pendingSession: ProjectionSessionIdentity,
        current: LyricProducerState?,
        currentActive: Boolean
    ): Boolean = current != null && currentActive && !current.playing &&
        ProjectionSessionIdentity.from(current) == pendingSession

    fun staticPlaybackPlaceholder(status: String): String? =
        PLAYING_PLACEHOLDER.takeIf { status == "no_lyrics" }

    internal fun playbackFallback(status: String, line: String, metadata: String): String? =
        if (status == "loading") metadata.takeIf { it.isNotBlank() }
        else line.takeIf { it.isNotBlank() }

    // --- Document-level helpers (retained for Spicy document tests; not called by project()
    // after the Phase 3 switch — producers now select the active row before emitting). ---

    fun isTimedDocumentType(type: String): Boolean =
        type.equals("Line", ignoreCase = true) || type.equals("Word", ignoreCase = true) ||
            type.equals("Syllable", ignoreCase = true)

    internal fun hasActualLyricTiming(document: SpicyBridgeDocument): Boolean =
        // 间奏行带时间窗但不是唱词:只有间奏的文档不计「有计时」,否则没有唱词源的歌会把
        // AOD keepalive 钉在间奏场景(上游 99ba119)。
        isTimedDocumentType(document.type) && document.rows.any {
            it.role != "INTERLUDE" && it.endMs > it.startMs
        }

    internal fun shouldKeepAodAlive(
        playing: Boolean,
        aodEnabled: Boolean,
        keepAwake: Boolean,
        keepAwakeUnsynced: Boolean,
        hasTimedLyrics: Boolean
    ): Boolean = playing && aodEnabled && keepAwake &&
        (hasTimedLyrics || keepAwakeUnsynced)

    fun isLineLevelDocumentType(type: String): Boolean =
        type.equals("Line", ignoreCase = true)

    fun isEffectiveLineLevelSync(type: String, wordCount: Int): Boolean =
        isLineLevelDocumentType(type) ||
            (type.equals("Word", ignoreCase = true) ||
                type.equals("Syllable", ignoreCase = true)) && wordCount <= 0

    /** Delegates to [AodStateProjector.sessionWakeSignal] (LyricProducerState overload). */
    internal fun sessionWakeSignal(state: LyricProducerState, hasTimedLyrics: Boolean): Long =
        com.eza.hyperglow.aod.sessionWakeSignal(state, hasTimedLyrics)

    /** Delegates to [AodStateProjector.trackGeneration] (LyricProducerState overload). */
    internal fun trackGeneration(state: LyricProducerState): Long =
        com.eza.hyperglow.aod.trackGeneration(state)

    /**
     * 消费端(SystemUiLyricProjection)在 [com.eza.hyperglow.root.projection.LYRIC_SNAPSHOT_FRESH_MS]
     * (5 秒)收不到任何消息就丢弃投影。4 秒一拍时单次心跳迟到(如 Binder 竞争超过 1 秒)就足以
     * 丢掉投影,撤回 AOD lifetime guard,歌曲中途掉回 stock clock。1.5 秒一拍让一个 5 秒窗口
     * 内容纳三拍,单拍迟到由余量吸收(上游 cc1f62f)。
     */
    private const val KEEP_ALIVE_INTERVAL_MS = 1_500L
    private const val FALLBACK_REFRESH_INTERVAL_MS = 1_000L
    private const val TRANSITION_GRACE_MS = 1_500L
    internal const val PAUSE_CONFIRM_MS = 5_000L
    private const val CUSTOMIZATION_REFRESH_MS = 1_000L
}
