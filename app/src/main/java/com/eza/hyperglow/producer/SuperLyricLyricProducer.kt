package com.eza.hyperglow.producer

import android.content.Context
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
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
 * [LyricProducer] backed by the SuperLyric Xposed module, consumed through its Binder API
 * ([com.hchen.superlyricapi.SuperLyricHelper]).
 *
 * The SuperLyric module (system-service level, "super_lyric") publishes the *active lyric line*
 * in near-realtime via Binder. This producer registers an [ISuperLyricReceiver] that receives
 * each pushed line ([ISuperLyricReceiver.onLyric]) and maps it into a [LyricProducerState].
 *
 * Unlike Lyricon/Spicy, SuperLyric only pushes the active line (plus optional translation /
 * secondary), NOT the full lyric document. Consequently the row-level fields (`lineIndex`,
 * `nextLineStartMs`, `nextLine`) are not available and stay at defaults — projection renders the
 * pushed line directly.
 *
 * Contract (see `.archcore/lyricon-integration/lyric-producer-contract.spec.md`):
 * - Requires the SuperLyric module active in the system service. Its absence MUST NOT crash
 *   HyperGlow: until the service is reachable, [connection] stays [ProducerConnection.DISCONNECTED]
 *   and [state] stays null, so the arbiter falls back automatically.
 * - [SuperLyricHelper.isAvailable] is polled periodically to track connection; any delivered
 *   [ISuperLyricReceiver.onLyric] additionally confirms a live connection.
 * - The `spotify:track:` constraint is NOT imposed here (it stays in SpicyBridgeStateReducer).
 *
 * Threading: Binder callbacks arrive on Binder threads; [MutableStateFlow] is thread-safe, so
 * emitting from any callback is safe.
 *
 * @param clock injectable monotonic clock (millis); defaults to [SystemClock.elapsedRealtime].
 *   Injected in unit tests so emissions run without Android's [SystemClock].
 */
class SuperLyricLyricProducer(
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : LyricProducer {

    override val id: LyricSource = LyricSource.SUPERLYRIC

    private val mutableConnection = MutableStateFlow(ProducerConnection.DISCONNECTED)
    override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()

    private val mutableState = MutableStateFlow<LyricProducerState?>(null)
    override val state: StateFlow<LyricProducerState?> = mutableState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var started = false

    // Session/sequence for arbiter dedup (producerId:generation:sequence).
    private var generation: Int = 0
    private var sequence: Long = 0L

    // Monotonic time of the last delivered lyric (0 = never received / not playing). Used as a
    // heartbeat: if lyrics stop being delivered while we were previously receiving them, we force
    // re-register to recover the IPC callback path (guards against service-side silent drops).
    private var lastLyricElapsedMs: Long = 0L
    private var lastForceReRegisterElapsed: Long = 0L

    internal val receiver = object : ISuperLyricReceiver.Stub() {
        override fun onLyric(publisher: String?, data: SuperLyricData?) {
            AppLog.i(
                "SuperLyricLyricProducer",
                "onLyric: publisher=$publisher title=${data?.title} lyric=${data?.lyric?.text?.take(24)}"
            )
            lastLyricElapsedMs = clock()
            mutableConnection.value = ProducerConnection.CONNECTED
            if (data == null || data.hasLyric().not()) {
                // No lyric payload: keep metadata but clear the active line.
                emit(null, data)
                return
            }
            emit(data.getLyric(), data)
        }

        override fun onStop(publisher: String?, data: SuperLyricData?) {
            AppLog.i("SuperLyricLyricProducer", "onStop: publisher=$publisher")
            // Playback paused/stopped: clear the active state so the arbiter can fall back / idle.
            lastLyricElapsedMs = 0L
            mutableState.value = null
        }
    }

    override fun start(context: Context) {
        if (started) {
            AppLog.i("SuperLyricLyricProducer", "start: already started (no-op)")
            return
        }
        started = true
        AppLog.i("SuperLyricLyricProducer", "start: registering receiver")
        ensureReceiverRegistered()
        // Poll the service availability so the arbiter can fall back when SuperLyric is absent.
        scope.launch { availabilityLoop() }
    }

    override fun stop() {
        if (!started) {
            AppLog.i("SuperLyricLyricProducer", "stop: not started (no-op)")
            return
        }
        started = false
        runCatching { SuperLyricHelper.unregisterReceiver(receiver) }
            .onFailure { AppLog.w("SuperLyricLyricProducer", "stop: unregister error", it) }
        scope.cancel()
        mutableConnection.value = ProducerConnection.DISCONNECTED
        mutableState.value = null
        AppLog.i("SuperLyricLyricProducer", "stop: done")
    }

    /**
     * Registers [receiver] with SuperLyric, tolerating the SuperLyric module being absent. The
     * API's `registerReceiver` throws `IllegalStateException` when its manager is not attached
     * (module inactive / IPC not yet bound), so this MUST NOT be called unguarded — doing so
     * crashes the app at startup on devices without SuperLyric. Registration is retried from the
     * availability loop once the service becomes reachable.
     */
    private fun ensureReceiverRegistered() {
        val already = runCatching { SuperLyricHelper.isReceiverRegistered(receiver) }
            .getOrDefault(false)
        if (already) {
            if (mutableConnection.value != ProducerConnection.CONNECTED &&
                mutableConnection.value != ProducerConnection.RECONNECTED
            ) {
                mutableConnection.value = ProducerConnection.CONNECTED
            }
            return
        }
        val ok = runCatching {
            SuperLyricHelper.registerReceiver(receiver)
            SuperLyricHelper.isReceiverRegistered(receiver)
        }.getOrDefault(false)
        if (ok) {
            mutableConnection.value = ProducerConnection.CONNECTED
            AppLog.i("SuperLyricLyricProducer", "receiver registered")
        } else {
            AppLog.w(
                "SuperLyricLyricProducer",
                "receiver registration deferred; SuperLyric manager not attached"
            )
        }
    }

    private suspend fun availabilityLoop() {
        while (scope.isActive) {
            val available = runCatching { SuperLyricHelper.isAvailable() }.getOrDefault(false)
            if (available) {
                ensureReceiverRegistered()
                maybeForceReRegisterOnStall()
            } else {
                if (mutableConnection.value != ProducerConnection.DISCONNECTED) {
                    AppLog.i("SuperLyricLyricProducer", "service gone -> DISCONNECTED")
                    mutableConnection.value = ProducerConnection.DISCONNECTED
                    mutableState.value = null
                }
            }
            delay(AVAILABILITY_POLL_MS)
        }
    }

    /**
     * Recovers from a "frozen lyrics" state: if we have previously received lyrics (so the user is
     * clearly playing) but no `onLyric` has arrived for a long time, the service-side IPC delivery
     * may have been silently dropped while `isReceiverRegistered` still reports registered. Force a
     * fresh unregister+register to re-arm the callback path.
     */
    private fun maybeForceReRegisterOnStall() {
        val last = lastLyricElapsedMs
        if (last <= 0L) return // never received lyrics / not playing: nothing to stall on
        val now = clock()
        val stalledFor = now - last
        if (stalledFor < STALL_RECOVERY_MS) return
        if (now - lastForceReRegisterElapsed < FORCE_RE_REGISTER_COOLDOWN_MS) return
        lastForceReRegisterElapsed = now
        val ok = runCatching {
            SuperLyricHelper.unregisterReceiver(receiver)
            SuperLyricHelper.registerReceiver(receiver)
            SuperLyricHelper.isReceiverRegistered(receiver)
        }.getOrDefault(false)
        if (ok) {
            mutableConnection.value = ProducerConnection.RECONNECTED
            AppLog.i(
                "SuperLyricLyricProducer",
                "force re-register after $stalledFor ms without lyric (recovered)"
            )
        } else {
            AppLog.w(
                "SuperLyricLyricProducer",
                "force re-register failed after $stalledFor ms stall; will retry"
            )
        }
    }

    /**
     * Build and emit a [LyricProducerState] from an active [SuperLyricLine] (or null to keep
     * only metadata). The pushed line is the active row; projection renders it directly.
     *
     * Next-line: the SDK's `secondary` field IS the next line to display (decompiled
     * SuperLyricApi-3.4: `getSecondary()` alongside `getLyric()`/`getTranslation()`). The 12:34
     * capture showed the arbiter falling back to this channel after Lyricon went silent, but
     * `nextLine` was hard-coded empty → the "next line" render path
     * (`AodLyricCanvasView: content.nextLine.isNotBlank()`) never drew. When the publisher
     * doesn't push secondary, `nextLine` stays blank (honest absence), and `nextLineStartMs`
     * approximates to the active line's end (the next line starts ≈ when this one ends).
     *
     * Staleness: unlike Lyricon's ~60 Hz position feed, this channel only pushes ON LINE CHANGE.
     * Long interludes (instrumental bridges, spaced lyrics) legitimately produce >3 s gaps, which
     * the arbiter's uniform 3 s staleness misreads as a dead source, flapping the fallback chain
     * mid-song. A 12 s window tolerates line gaps while still falling back on a genuinely dead
     * publisher well inside a typical verse.
     */
    private fun emit(line: SuperLyricLine?, data: SuperLyricData?) {
        val now = clock()
        if (line == null) {
            if (data == null) {
                mutableState.value = null
                return
            }
            // Metadata-only push (no active line): still surface the track so the UI has a title.
        }
        val words = line?.words?.map { it.toLyricWord() }
            ?.takeIf { it.isNotEmpty() }
        // SuperLyric pushes only the active line, so per-word timing implies SYLLABLE karaoke.
        val lyricKind = when {
            line == null -> LyricKind.NONE
            words != null -> LyricKind.SYLLABLE
            else -> LyricKind.LINE
        }
        val secondary = data?.takeIf { it.hasSecondary() }?.secondary
        sequence++
        mutableState.value = LyricProducerState(
            producerId = PRODUCER_ID,
            generation = generation,
            sequence = sequence,
            status = "ready",
            trackUri = "superlyric:${data?.title.orEmpty()}",
            title = data?.title.orEmpty(),
            artist = data?.artist.orEmpty(),
            album = data?.album.orEmpty(),
            imageId = "",
            line = line?.text.orEmpty(),
            romanizedLine = "",
            translatedLine = data?.translation?.text.orEmpty(),
            lineIndex = 0,
            positionMs = line?.startTime ?: 0L,
            durationMs = line?.endTime ?: 0L,
            sampledAtElapsedMs = now,
            speed = 1f,
            playing = line != null,
            receivedAtElapsedMs = now,
            words = words,
            renderModes = defaultRenderModes(),
            lyricKind = lyricKind,
            alignedRight = false,
            lineStartMs = line?.startTime ?: 0L,
            lineEndMs = line?.endTime ?: 0L,
            ruby = emptyList(),
            layoutGroups = emptyList(),
            hasTimedLyrics = line != null,
            nextLineStartMs = secondary?.startTime ?: line?.endTime?.takeIf { it > 0L },
            nextLine = secondary?.text.orEmpty(),
            staleAfterMs = LINE_EVENT_STALE_AFTER_MS
        )
    }

    private fun SuperLyricWord.toLyricWord() = LyricWord(
        text = word,
        romanized = "",
        startMs = startTime,
        endMs = endTime,
        boundaryAfter = false
    )

    companion object {
        private const val PRODUCER_ID = "superlyric"
        private const val AVAILABILITY_POLL_MS = 5_000L

        /**
         * Staleness override for this channel: SuperLyric pushes only on line change, so a
         * >3 s gap is a normal interlude, not a dead source. See [emit].
         */
        internal const val LINE_EVENT_STALE_AFTER_MS = 12_000L

        /** No `onLyric` for this long while playing → the callback path is likely dropped. */
        private const val STALL_RECOVERY_MS = 60_000L

        /** Minimum gap between two forced re-registers, so a persistent stall doesn't hammer IPC. */
        private const val FORCE_RE_REGISTER_COOLDOWN_MS = 30_000L

        /** Default render modes when customization is unavailable; matches the other producers. */
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