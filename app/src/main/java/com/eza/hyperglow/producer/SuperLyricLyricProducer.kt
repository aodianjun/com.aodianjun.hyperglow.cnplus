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

    internal val receiver = object : ISuperLyricReceiver.Stub() {
        override fun onLyric(publisher: String?, data: SuperLyricData?) {
            AppLog.i(
                "SuperLyricLyricProducer",
                "onLyric: publisher=$publisher title=${data?.title} lyric=${data?.lyric?.text?.take(24)}"
            )
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
     * Build and emit a [LyricProducerState] from an active [SuperLyricLine] (or null to keep
     * only metadata). The pushed line is the active row; projection renders it directly.
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
            nextLineStartMs = null,
            nextLine = ""
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