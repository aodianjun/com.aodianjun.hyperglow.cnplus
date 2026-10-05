package com.eza.hyperglow.producer

import android.content.Context
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.aod.AodRenderPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Selects which [LyricProducer] feeds the projection pipeline, enforcing the single-active-
 * producer invariant from the contract spec.
 *
 * Contract (see `docs/LYRIC_PRODUCER_CONTRACT.md`):
 * 1. Exposes exactly one [active] flow; at most one producer's state is visible at any instant.
 * 2. WHEN the selected producer is CONNECTED/RECONNECTED and non-stale, forward its state.
 * 3. WHEN the selected producer is DISCONNECTED or its state is stale, clear `active` to null
 *    and MAY fall back to the next connected producer. The fallback path uses the same fault
 *    predicate as selection ([isFaulted]): a stale-but-paused candidate is a valid frozen state
 *    and is forwarded when it carries content (non-blank line or word timing).
 * 4. WHEN the user changes preference, stop emitting the previous producer's state within one
 *    frame and begin emitting the newly selected producer's state only after it reports
 *    CONNECTED.
 *
 * The arbiter is the only entry point projection consumers (AodProjectionEngine) should read;
 * they MUST NOT read SpicyBridgeStore.state directly.
 *
 * Threading: all producer state observations happen on [Dispatchers.Default]; [active] is a
 * [StateFlow] so collectors are race-free. [setPreference] is thread-safe.
 *
 * @param clock injectable monotonic clock (millis); defaults to [SystemClock.elapsedRealtime].
 *   Injected in unit tests so staleness can be advanced without Android.
 */
class LyricProducerArbiter(
    private val producers: Map<LyricSource, LyricProducer>,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    /**
     * The single active producer state consumed by projection. Null means "no current lyrics"
     * (no producer connected, or the connected producer's state is stale).
     *
     * Invariant: at every emission, this equals exactly one of the producers' current state
     * (the active one) or null — never a mix.
     */
    val active: StateFlow<LyricProducerState?> get() = mutableActive.asStateFlow()

    /**
     * The source currently feeding [active] — the selected source when healthy, or the fallback
     * source it fell back to when the selected one is disconnected/stale. Null when no lyrics
     * are active. UI uses this to label "now playing" with the source actually in use.
     */
    val activeSource: StateFlow<LyricSource?> get() = mutableActiveSource.asStateFlow()

    /** The currently selected source. Drives which producer is forwarded when healthy. */
    val preference: StateFlow<LyricSource> get() = mutablePreference.asStateFlow()

    private val mutableActive = MutableStateFlow<LyricProducerState?>(null)
    private val mutableActiveSource = MutableStateFlow<LyricSource?>(null)
    private val mutablePreference = MutableStateFlow(LyricSource.SPICY)

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var arbitrationJob: Job? = null
    private var staleSweepJob: Job? = null
    private var started = false

    /**
     * Start observing producers. Idempotent. Called once at app startup
     * (see HyperGlowApplication, wired in Phase 2 integration).
     */
    fun start(context: Context) {
        if (started) {
            AppLog.i("LyricProducerArbiter", "start: already started (no-op)")
            return
        }
        started = true
        // Restore the persisted preference so the user's source choice survives restarts.
        // Spec: preference is "persisted in user preferences"; this was previously doc-only.
        val restored = AodRenderPreferences.readLyricSource(context.applicationContext)
        if (restored != mutablePreference.value) {
            AppLog.i("LyricProducerArbiter", "restored preference: $restored")
            mutablePreference.value = restored
        }
        producers.values.forEach { it.start(context) }
        arbitrationJob = scope.launch { arbitrateLoop(context) }
        staleSweepJob = scope.launch { staleSweepLoop() }
        AppLog.i("LyricProducerArbiter", "started")
    }

    /** Stop observing and clear active state. Idempotent. */
    fun stop() {
        if (!started) {
            AppLog.i("LyricProducerArbiter", "stop: not started (no-op)")
            return
        }
        started = false
        arbitrationJob?.cancel(); arbitrationJob = null
        staleSweepJob?.cancel(); staleSweepJob = null
        producers.values.forEach { it.stop() }
        mutableActive.value = null
        scope.cancel()
        AppLog.i("LyricProducerArbiter", "stopped")
    }

    /**
     * Change the preferred source. Per spec: stops emitting the previous producer's state
     * within one frame; begins emitting the newly selected producer's state only after it
     * reports CONNECTED. The next arbitration tick applies the switch. The choice is
     * persisted via [AodRenderPreferences] so it survives process restarts.
     */
    fun setPreference(source: LyricSource, context: Context? = null) {
        if (mutablePreference.value == source) {
            AppLog.i("LyricProducerArbiter", "setPreference: already $source (no-op)")
            return
        }
        AppLog.i("LyricProducerArbiter", "preference ${mutablePreference.value} -> $source")
        context?.applicationContext?.let { AodRenderPreferences.writeLyricSource(it, source) }
        // Clear immediately so a stale state from the old producer cannot leak during the
        // gap before the new producer reports CONNECTED (spec: stop within one frame).
        mutableActive.value = null
        mutablePreference.value = source
    }

    /**
     * The live connection state of [source]'s producer. Exposed so the UI can show whether
     * the selected (or fallback) source is actually connected — e.g. whether the Lyricon
     * Xposed module is active in SystemUI.
     */
    fun connection(source: LyricSource): StateFlow<ProducerConnection>? =
        producer(source)?.connection

    /**
     * 「重启歌词源」:强制重建**全部**已注册源(SPICY/LYRICON/SUPERLYRIC/LYRICINFO)的
     * 订阅/回调链路(见 [LyricProducer.restart])。无 root、即时生效,用于源卡死时在不
     * 重启应用的前提下恢复。只重建订阅,不清空 [active]——重建期间保留当前显示,新状态
     * 到达后自然覆盖,避免闪空。
     *
     * 逐源重建而不是只重建 [preference] 选中的那一个:实际喂歌词的可能是回退源(选中源
     * 未连接/停滞时仲裁器把 `active` 让给其他源),而默认首选 SPICY 是外部推送、应用侧
     * 无可重建订阅——只按选中源重启会出现「点了重启歌词源,卡死的却是回退源」的空转
     * (真机日志实证:pref=SPICY,屏上歌词实际来自 SUPERLYRIC 回退)。重建幂等且健康源
     * 重建无副作用,故全量覆盖。
     *
     * 单源失败不阻断其余源:契约要求 [LyricProducer.restart] 不向调用方抛异常,这里仍
     * 逐源兜底,保证一个源出错不会让后面的源失去重建机会,也不会冒泡给 UI 调用方。
     */
    fun restartAll() {
        LyricSource.entries.forEach { source ->
            val target = producer(source) ?: return@forEach
            AppLog.i("LyricProducerArbiter", "restartAll: $source")
            runCatching { target.restart() }
                .onFailure { AppLog.w("LyricProducerArbiter", "restartAll: $source failed", it) }
        }
    }

    /**
     * The full-song snapshot of [source]'s producer — the plugin chain's whole-track input
     * for non-Spicy sources. Null when the producer has no full-track data (pure line-stream
     * sources; the host-side LineStreamAggregator substitutes for those). See
     * [LyricProducer.fullSongSnapshot].
     */
    fun fullSongSnapshot(source: LyricSource): LyricSongSnapshot? =
        producer(source)?.fullSongSnapshot()

    private suspend fun arbitrateLoop(context: Context) {
        // Collect both producers' connection and state, recomputing `active` on any change.
        // We re-read .value on each tick rather than combine() to keep the staleness check
        // time-aware (combine would not re-emit purely due to elapsed time).
        var lastActiveSignature: String? = null
        // 来源身份(producerId:generation):生产者每次发射都递增 sequence(位置推进),
        // 签名随之变化——若按签名记切换日志,同一生产者的例行转发也会每秒刷数十行
        // "active changed"(2026-09-28 真机:3.5 分钟 1731 行,冲垮 logcat 与
        // diagnostic-trace.log 的取证历史)。只有身份变化(换源/换歌)才算切换事件。
        var lastActiveIdentity: String? = null
        while (scope.isActive) {
            val next = computeActiveOnce()
            // Only write on actual change to avoid redundant StateFlow emissions; a cleared
            // active must also be re-published even when the signature is unchanged
            // (stale-sweep may have cleared a frozen state; see shouldPublishActive).
            val sig = next?.let { "${it.producerId}:${it.generation}:${it.sequence}" }
            if (shouldPublishActive(sig, lastActiveSignature, mutableActive.value == null)) {
                val identity = next?.let { "${it.producerId}:${it.generation}" }
                if (sig == lastActiveSignature) {
                    // staleSweep 清空后签名未变(冻结态):必须补发,否则 active 永久卡死在 null。
                    AppLog.i("LyricProducerArbiter", "active re-published after clear: $sig")
                } else if (identity != lastActiveIdentity) {
                    AppLog.i(
                        "LyricProducerArbiter",
                        "active changed: ${lastActiveSignature ?: "null"} -> ${sig ?: "null"}"
                    )
                }
                mutableActive.value = next
                lastActiveSignature = sig
                lastActiveIdentity = identity
            }
            delay(ARBITRATION_TICK_MS)
        }
    }

    /**
     * Single arbitration pass: returns the state that should be `active` right now, based on
     * the current preference, both producers' connection/state, and the staleness clock.
     *
     * Exposed for unit tests so the selection/fallback/switch logic can be verified
     * deterministically without driving the async loop.
     */
    internal fun computeActiveOnce(): LyricProducerState? {
        val pref = mutablePreference.value
        val preferred = producer(pref)
        val now = clock()
        val result = if (preferred == null) {
            // Preference names a source with no producer registered: fall back.
            fallbackState(pref, fallbackDiagnosticEnabled(pref, "noProducer", now))
        } else {
            val preferredConn = preferred.connection.value
            val preferredState = preferred.state.value
            val preferredUsable = (preferredConn == ProducerConnection.CONNECTED ||
                preferredConn == ProducerConnection.RECONNECTED) &&
                preferredState != null &&
                !isFaulted(preferredState)
                // 暂停时位置流天然静默(state.playing=false):冻结状态仍有效,不应判 stale
                // 后 fallback 到无歌词行源把 AOD 歌词清掉。播放中静默(stale+playing)仍
                // 走 fallback,交给 LyriconLyricProducer 的 watchdog 重建订阅。
                // (故障谓词与 staleSweepLoop 的清空条件同源,见 isFaulted。)
            if (preferredUsable) {
                // 首选源可用。若它只带行级歌词（无词级时间戳），却存在另一个已连接、非 stale
                // 且带词级时间戳的源（如 SuperLyric 的逐字卡拉OK），优先切到带词级数据的源，
                // 避免"普通 LRC 无词级时间→单词动画缺失"的时有时无。有词级数据时仍用首选源。
                val timed = timedState()
                when {
                    timed != null && !hasWordTiming(preferredState) -> timed
                    // 冻结态让位:首选源只是「暂停不算故障」而保持可用时,若另一个源正在播放且
                    // 带歌词内容,让位给它 —— 0.3.120 真机:Lyricon 回调链死亡后冻结态长期
                    // 霸占首选位,SuperLyric 逐句收词却永远上不了屏(「当前歌词源暂无曲目」
                    // 常驻)。只让位给「在播且有内容」的源:暂停时的冻结歌词保持显示,绝不
                    // 让位给无歌词内容的源把 AOD 歌词清掉(暂停保留语义的初衷)。
                    isStale(preferredState) -> fresherPlayingContentState(pref) ?: preferredState
                    else -> preferredState
                }
            } else {
                // Preferred disconnected/stale/no-state: fall back.
                val reason = when {
                    preferredState == null -> "nullState"
                    isStale(preferredState) -> "stale(age=${ageSeconds(now, preferredState)}s)"
                    else -> "notConnected($preferredConn)"
                }
                // 去抖签名用不含 age 的类别(reason 文本里的 age 每秒都在变,进签名会让
                // 停滞期间无法去抖;见 fallbackDiagnosticEnabled)。
                val reasonKey = when {
                    preferredState == null -> "nullState"
                    isStale(preferredState) -> "stale"
                    else -> "notConnected($preferredConn)"
                }
                val logDetail = fallbackDiagnosticEnabled(pref, reasonKey, now)
                if (logDetail) {
                    AppLog.i(
                        "LyricProducerArbiter",
                        "select: pref=$pref conn=$preferredConn but $reason -> fallback"
                    )
                }
                fallbackState(pref, logDetail)
            }
        }
        // 无可用歌词源时,输出一次全源汇总,便于定位是哪一环断了(播放器未上报进度 /
        // 源 stale / 未连接)。按诊断签名去重,只在画面变化时记录,避免每 100ms 刷屏。
        if (result == null) logSourceSummaryOnce(pref, now)
        // Track which source actually produced `active` (selected when healthy, fallback
        // otherwise). StateFlow de-dupes equal enums, so no redundant emission on replay.
        mutableActiveSource.value = result?.let { r ->
            LyricSource.entries.firstOrNull { source -> producer(source)?.state?.value === r }
        }
        return result
    }

    /**
     * 是否带词级时间戳（逐字卡拉OK）：有非空 words 且存在 endMs > startMs。
     */
    private fun hasWordTiming(state: LyricProducerState): Boolean =
        state.words?.any { it.endMs > it.startMs } == true

    /**
     * 返回一个已连接、非 stale 且带词级时间戳的源的状态；多个命中时按 enum 顺序取最早
     * （SuperLyric 优先于 lyricinfo）。无则返回 null。
     */
    private fun timedState(): LyricProducerState? =
        LyricSource.entries.firstNotNullOfOrNull { source ->
            val p = producer(source) ?: return@firstNotNullOfOrNull null
            val conn = p.connection.value
            if (conn != ProducerConnection.CONNECTED &&
                conn != ProducerConnection.RECONNECTED
            ) return@firstNotNullOfOrNull null
            val st = p.state.value ?: return@firstNotNullOfOrNull null
            if (isStale(st)) return@firstNotNullOfOrNull null
            st.takeIf(::hasWordTiming)
        }

    /**
     * 回退候选判定与既有故障谓词同源(见 [isFaulted]):`stale && playing` 才是故障,一律跳过;
     * `stale && !playing` 是冻结暂停态、不是故障——首选路径已按此放行(见 [computeActiveOnce]),
     * 回退路径此前却无条件跳过任何 stale 源,同一冻结态「当首选可用、当回退不可用」。
     * 2026-10-05 真机:首选 LyricInfo 被非音乐过滤(断连),Lyricon 回调链死在暂停时刻、
     * 冻结在带行的暂停态(age=1930s),回退路径把它跳过 → `active` 恒 null、屏上无歌词,
     * 尽管该源手里就有一行有效歌词。
     *
     * 冻结暂停态是**兜底档**:先按枚举顺序取健康候选(已连接、非 stale,历史规则),没有才收
     * 首个带内容的冻结暂停候选——绝不让一条冻结行压过正在播的活源(0.3.120 真机教训:
     * Lyricon 回调链死后冻结态霸占选中位,SuperLyric 逐句收词却上不了屏)。「带内容」
     * (非空歌词行或词级时间戳)与「冻结态让位」同门槛,空态绝不回退上台清屏。
     *
     * [logDetail] 由 [fallbackDiagnosticEnabled] 去抖:停滞画面未变时不逐 tick 重打整条链。
     */
    private fun fallbackState(excluded: LyricSource, logDetail: Boolean): LyricProducerState? {
        // Try every other producer in enum order (SPICY, LYRICON, SUPERLYRIC, LYRICINFO):
        // healthy (connected, non-stale) candidates win; the first content-bearing frozen-paused
        // candidate is held as a last resort.
        var frozen: LyricProducerState? = null
        for (otherSource in LyricSource.entries) {
            if (otherSource == excluded) continue
            val other = producer(otherSource) ?: continue
            val otherConn = other.connection.value
            if (otherConn != ProducerConnection.CONNECTED &&
                otherConn != ProducerConnection.RECONNECTED
            ) {
                if (logDetail) AppLog.i(
                    "LyricProducerArbiter",
                    "fallback: $otherSource conn=$otherConn (not connected) -> skip"
                )
                continue
            }
            val otherState = other.state.value
            if (otherState == null) {
                if (logDetail) AppLog.i(
                    "LyricProducerArbiter",
                    "fallback: $otherSource connected but nullState -> skip"
                )
                continue
            }
            val now = clock()
            if (isFaulted(otherState)) {
                if (logDetail) AppLog.i(
                    "LyricProducerArbiter",
                    "fallback: $otherSource stale-playing(age=${ageSeconds(now, otherState)}s) -> skip"
                )
                continue
            }
            if (!isStale(otherState)) {
                if (logDetail) AppLog.i(
                    "LyricProducerArbiter",
                    "fallback: $otherSource producer=${otherState.producerId} " +
                        "gen=${otherState.generation} seq=${otherState.sequence} " +
                        "age=${ageSeconds(now, otherState)}s"
                )
                return otherState
            }
            if (otherState.line.isBlank() && !otherState.hasTimedLyrics) {
                if (logDetail) AppLog.i(
                    "LyricProducerArbiter",
                    "fallback: $otherSource stale-paused contentless(age=${ageSeconds(now, otherState)}s) -> skip"
                )
                continue
            }
            if (frozen == null) frozen = otherState
        }
        if (frozen != null) {
            if (logDetail) AppLog.i(
                "LyricProducerArbiter",
                "fallback: frozen-paused(age=${ageSeconds(clock(), frozen)}s) " +
                    "producer=${frozen.producerId} gen=${frozen.generation} " +
                    "seq=${frozen.sequence} -> use (no healthy source)"
            )
            return frozen
        }
        if (logDetail) AppLog.i("LyricProducerArbiter", "fallback: no connected usable producer -> null")
        return null
    }

    /**
     * 停滞诊断去抖:首选源持续不可用时,回退链每 tick 逐源打日志(select + 每源判定 + 最终
     * null),按 10Hz 的仲裁循环约 60 行/秒——停滞持续多久就刷多久。2026-10-05 真机:
     * LyricInfo 被非音乐过滤、Lyricon 冻结在暂停态、SuperLyric nullState,停滞 32 分钟,
     * 512KB 的诊断镜像(带一次轮转)被整段刷掉,现场只剩最后几秒。签名只含「画面会不会变」
     * 的结构量(pref / 首选不可用类别 / 各源连接 / 状态身份与故障位),不含每秒都变的 age;
     * 签名变化立即记录,未变则每 [FALLBACK_DIAG_HEARTBEAT_MS] 补一条,证明停滞仍在持续。
     */
    private var lastFallbackDiagSignature: String? = null
    private var lastFallbackDiagAtMs: Long = 0L

    private fun fallbackDiagnosticEnabled(pref: LyricSource, reasonKey: String, now: Long): Boolean {
        val signature = buildString {
            append(pref).append('|').append(reasonKey)
            LyricSource.entries.forEach { source ->
                val p = producer(source) ?: return@forEach
                append('|').append(source).append(':').append(p.connection.value).append(':')
                val st = p.state.value
                if (st == null) {
                    append("null")
                } else {
                    append(st.producerId).append(':').append(st.generation).append(':')
                        .append(
                            when {
                                isFaulted(st) -> "fault"
                                isStale(st) -> "stale"
                                else -> "fresh"
                            }
                        )
                }
            }
        }
        val verbose = shouldLogFallbackDiagnostic(
            signature = signature,
            lastSignature = lastFallbackDiagSignature,
            sinceLastLogMs = now - lastFallbackDiagAtMs
        )
        if (verbose) {
            lastFallbackDiagSignature = signature
            lastFallbackDiagAtMs = now
        }
        return verbose
    }

    /**
     * Consolidates every producer's connection / staleness / playback position into ONE line so an
     * empty-lyrics screen shows the whole chain at a glance. Emitted only when the picture changes
     * (see [computeActiveOnce]), so a stalled stream is diagnosable without spamming 10 lines/sec.
     */
    private var lastSourceSummary: String? = null

    private fun logSourceSummaryOnce(pref: LyricSource, now: Long) {
        val parts = LyricSource.entries.map { source ->
            val p = producer(source)
            val conn = p?.connection?.value
            val st = p?.state?.value
            val health = when {
                conn == null -> "-"
                st == null -> "nullState"
                isStale(st) -> "stale(${ageSeconds(now, st)}s)"
                else -> "ok(${ageSeconds(now, st)}s)"
            }
            val progress = st?.let { "pos=${it.positionMs}/${it.durationMs} play=${if (it.playing) 1 else 0}" }
                ?: ""
            val lineInfo = st?.line?.takeIf { it.isNotEmpty() }?.let { " line=\"${it.take(24)}\"" } ?: ""
            "$source[$conn/$health $progress$lineInfo]"
        }
        val signature = "pref=$pref " + parts.joinToString(" ")
        // 去抖只看结构(连接/故障类别/位置/行文本):年龄每秒都变,含年龄会让停滞期间
        // 1 行/秒持续刷屏。结构未变时只在画面首次变化留一条;停滞心跳由回退链日志
        // (fallbackDiagnosticEnabled)每 30 秒提供。
        val structural = "pref=$pref " + LyricSource.entries.joinToString(" ") { source ->
            val p = producer(source)
            val conn = p?.connection?.value
            val st = p?.state?.value
            val health = when {
                conn == null -> "-"
                st == null -> "nullState"
                isFaulted(st) -> "faulted"
                isStale(st) -> "stale"
                else -> "ok"
            }
            val progress = st?.let { "pos=${it.positionMs}/${it.durationMs} play=${if (it.playing) 1 else 0}" }
                ?: ""
            val lineInfo = st?.line?.takeIf { it.isNotEmpty() }?.let { " line=\"${it.take(24)}\"" } ?: ""
            "$source[$conn/$health $progress$lineInfo]"
        }
        if (structural != lastSourceSummary) {
            lastSourceSummary = structural
            AppLog.i("LyricProducerArbiter", "sources stalled: $signature")
        }
    }

    private fun ageSeconds(now: Long, state: LyricProducerState): Long =
        (now - state.receivedAtElapsedMs) / 1000L

    private fun staleSweepLoop() = scope.launch {
        // Independently clear `active` when the currently-forwarded state is faulted between
        // arbitration ticks (e.g. producer stopped emitting mid-playback and didn't
        // disconnect). Fault semantics match the selector's usability rule (isFaulted): a
        // paused frozen state is not faulted and must stay forwarded — clearing it here while
        // the selector still deems it usable dead-locks active=null (0.3.120 device capture).
        while (scope.isActive) {
            val current = mutableActive.value
            if (current != null && isFaulted(current)) {
                AppLog.i("LyricProducerArbiter", "active state went stale, clearing")
                mutableActive.value = null
            }
            delay(STALE_SWEEP_TICK_MS)
        }
    }

    private fun producer(source: LyricSource): LyricProducer? = producers[source]

    private fun isStale(state: LyricProducerState): Boolean {
        // Uniform staleness: now - receivedAtElapsedMs > staleAfterMs.
        // receivedAtElapsedMs uses the same clock (SystemClock.elapsedRealtime in production).
        val now = clock()
        return now - state.receivedAtElapsedMs > state.staleAfterMs
    }
    /**
     * 「故障」谓词(选源与 staleSweep 共用):播放中的 stale 才算故障;暂停时位置流天然
     * 静默,冻结状态仍有效(保持 AOD 歌词)。清空方与保留方必须同一谓词——语义不一致会
     * 造成 active 死锁:sweep 清掉一个选源仍视为可用的冻结态后,sig 去重让它永远不被
     * 重新发布(0.3.120 真机「当前歌词源暂无曲目」常驻)。
     */
    internal fun isFaulted(state: LyricProducerState): Boolean = isStale(state) && state.playing

    /**
     * 冻结态让位候选:另一个已连接、非 stale、确实在播且带歌词内容(词级时间戳或非空
     * 歌词行)的源状态;无则 null。「带内容」门槛延续暂停保留语义的初衷——绝不让位给
     * 无歌词内容的源把 AOD 歌词清掉。
     */
    private fun fresherPlayingContentState(excluded: LyricSource): LyricProducerState? =
        LyricSource.entries.firstNotNullOfOrNull { source ->
            if (source == excluded) return@firstNotNullOfOrNull null
            val p = producer(source) ?: return@firstNotNullOfOrNull null
            val conn = p.connection.value
            if (conn != ProducerConnection.CONNECTED &&
                conn != ProducerConnection.RECONNECTED
            ) return@firstNotNullOfOrNull null
            val st = p.state.value ?: return@firstNotNullOfOrNull null
            if (isStale(st) || !st.playing) return@firstNotNullOfOrNull null
            st.takeIf { it.hasTimedLyrics || it.line.isNotBlank() }
        }

    companion object {
        /** How often the arbitration loop re-evaluates which producer is active. */
        private const val ARBITRATION_TICK_MS = 100L
        /** How often the stale-sweep checks the forwarded state. */
        private const val STALE_SWEEP_TICK_MS = 500L
    }
}

/**
 * 仲裁发布判定:签名变化即发布;staleSweep 清空后(active 为 null)即使签名未变也必须
 * 补发,否则冻结态永远回不来——0.3.120 真机「当前歌词源暂无曲目」常驻的死锁面之一。
 */
internal fun shouldPublishActive(
    nextSignature: String?,
    lastSignature: String?,
    activeIsNull: Boolean
): Boolean = nextSignature != lastSignature || (activeIsNull && nextSignature != null)

/**
 * 停滞诊断去抖判定:结构签名变化 → 立即记录;签名未变(停滞画面原样)只在心跳周期补一条,
 * 既保留「仍在停滞」的现场,又不让 10Hz 的仲裁循环把诊断镜像刷掉。纯函数便于单测。
 */
internal fun shouldLogFallbackDiagnostic(
    signature: String,
    lastSignature: String?,
    sinceLastLogMs: Long,
    heartbeatMs: Long = FALLBACK_DIAG_HEARTBEAT_MS
): Boolean = signature != lastSignature || sinceLastLogMs >= heartbeatMs

/** 停滞诊断心跳周期:结构签名未变时至少每 30 秒留一条现场。 */
internal const val FALLBACK_DIAG_HEARTBEAT_MS = 30_000L
