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
 * Contract (see `.archcore/lyricon-integration/lyric-producer-contract.spec.md`):
 * 1. Exposes exactly one [active] flow; at most one producer's state is visible at any instant.
 * 2. WHEN the selected producer is CONNECTED/RECONNECTED and non-stale, forward its state.
 * 3. WHEN the selected producer is DISCONNECTED or its state is stale, clear `active` to null
 *    and MAY fall back to the next connected producer.
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

    /** The currently selected source. Drives which producer is forwarded when healthy. */
    val preference: StateFlow<LyricSource> get() = mutablePreference.asStateFlow()

    private val mutableActive = MutableStateFlow<LyricProducerState?>(null)
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

    private suspend fun arbitrateLoop(context: Context) {
        // Collect both producers' connection and state, recomputing `active` on any change.
        // We re-read .value on each tick rather than combine() to keep the staleness check
        // time-aware (combine would not re-emit purely due to elapsed time).
        var lastActiveSignature: String? = null
        while (scope.isActive) {
            val next = computeActiveOnce()
            // Only write on actual change to avoid redundant StateFlow emissions.
            val sig = next?.let { "${it.producerId}:${it.generation}:${it.sequence}" }
            if (sig != lastActiveSignature) {
                AppLog.i(
                    "LyricProducerArbiter",
                    "active changed: ${lastActiveSignature ?: "null"} -> ${sig ?: "null"}"
                )
                mutableActive.value = next
                lastActiveSignature = sig
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
            fallbackState(pref)
        } else {
            val preferredConn = preferred.connection.value
            val preferredState = preferred.state.value
            when {
                preferredConn == ProducerConnection.CONNECTED ||
                    preferredConn == ProducerConnection.RECONNECTED -> {
                    if (preferredState != null && !isStale(preferredState)) {
                        AppLog.i(
                            "LyricProducerArbiter",
                            "select: pref=$pref conn=$preferredConn producer=${preferredState.producerId} " +
                                "gen=${preferredState.generation} seq=${preferredState.sequence} " +
                                "age=${ageSeconds(now, preferredState)}s"
                        )
                        preferredState
                    } else {
                        // Preferred connected but no/stale state: try fallback before null.
                        val reason = when {
                            preferredState == null -> "nullState"
                            isStale(preferredState) -> "stale(age=${ageSeconds(now, preferredState)}s)"
                            else -> "unknown"
                        }
                        AppLog.i(
                            "LyricProducerArbiter",
                            "select: pref=$pref conn=$preferredConn but $reason -> fallback"
                        )
                        fallbackState(pref)
                    }
                }
                else -> {
                    // Preferred disconnected/timeout: fall back to the other producer.
                    AppLog.i(
                        "LyricProducerArbiter",
                        "select: pref=$pref conn=$preferredConn (not connected) -> fallback"
                    )
                    fallbackState(pref)
                }
            }
        }
        // 无可用歌词源时,输出一次全源汇总,便于定位是哪一环断了(播放器未上报进度 /
        // 源 stale / 未连接)。按诊断签名去重,只在画面变化时记录,避免每 100ms 刷屏。
        if (result == null) logSourceSummaryOnce(pref, now)
        return result
    }

    private fun fallbackState(excluded: LyricSource): LyricProducerState? {
        // Try every other producer in enum order (SPICY, LYRICON, SUPERLYRIC, LYRICINFO),
        // returning the first that is connected and has non-stale state.
        for (otherSource in LyricSource.entries) {
            if (otherSource == excluded) continue
            val other = producer(otherSource) ?: continue
            val otherConn = other.connection.value
            if (otherConn != ProducerConnection.CONNECTED &&
                otherConn != ProducerConnection.RECONNECTED
            ) {
                AppLog.i(
                    "LyricProducerArbiter",
                    "fallback: $otherSource conn=$otherConn (not connected) -> skip"
                )
                continue
            }
            val otherState = other.state.value
            if (otherState == null) {
                AppLog.i(
                    "LyricProducerArbiter",
                    "fallback: $otherSource connected but nullState -> skip"
                )
                continue
            }
            val now = clock()
            if (isStale(otherState)) {
                AppLog.i(
                    "LyricProducerArbiter",
                    "fallback: $otherSource stale(age=${ageSeconds(now, otherState)}s) -> skip"
                )
                continue
            }
            AppLog.i(
                "LyricProducerArbiter",
                "fallback: $otherSource producer=${otherState.producerId} " +
                    "gen=${otherState.generation} seq=${otherState.sequence} " +
                    "age=${ageSeconds(now, otherState)}s"
            )
            return otherState
        }
        AppLog.i("LyricProducerArbiter", "fallback: no connected non-stale producer -> null")
        return null
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
        if (signature != lastSourceSummary) {
            lastSourceSummary = signature
            AppLog.i("LyricProducerArbiter", "sources stalled: $signature")
        }
    }

    private fun ageSeconds(now: Long, state: LyricProducerState): Long =
        (now - state.receivedAtElapsedMs) / 1000L

    private fun staleSweepLoop() = scope.launch {
        // Independently clear `active` if the currently-forwarded state goes stale between
        // arbitration ticks (e.g. producer stopped emitting but didn't disconnect).
        while (scope.isActive) {
            val current = mutableActive.value
            if (current != null && isStale(current)) {
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

    companion object {
        /** How often the arbitration loop re-evaluates which producer is active. */
        private const val ARBITRATION_TICK_MS = 100L
        /** How often the stale-sweep checks the forwarded state. */
        private const val STALE_SWEEP_TICK_MS = 500L
    }
}
