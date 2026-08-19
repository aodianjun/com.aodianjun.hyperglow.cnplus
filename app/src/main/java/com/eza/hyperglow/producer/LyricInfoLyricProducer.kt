package com.eza.hyperglow.producer

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import com.eza.hyperglow.AppLog
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [LyricProducer] that reads lyrics injected by the LyricInfo Xposed module.
 *
 * LyricInfo hooks music apps to write a JSON payload into `MediaMetadata.extras.lyricInfo`
 * (elrc/lrc format). Any app with notification access can read it. This producer:
 * 1. Registers a [MediaSessionManager.OnActiveSessionsChangedListener] scoped to the app's
 *    [LyricInfoNotificationListener], which is how a third-party app reads other apps' sessions.
 * 2. Picks the active session whose `MediaMetadata` carries a `lyricInfo` extra.
 * 3. Parses the elrc/lrc payload via [ElrcParser] and selects the active line by position.
 * 4. Polls [MediaController.playbackState] for position and extrapolates while playing.
 *
 * Requires the user to grant notification access (ACTION_NOTIFICATION_LISTENER_SETTINGS) and
 * the LyricInfo module to be active in the music app. Until a session with `lyricInfo` is seen,
 * [connection] stays [ProducerConnection.DISCONNECTED] and [state] stays null, so the arbiter
 * falls back automatically.
 *
 * Threading: session callbacks arrive on the main thread; the position poll runs on
 * [Dispatchers.Default]. [MutableStateFlow] is thread-safe.
 *
 * @param clock injectable monotonic clock (millis); defaults to [SystemClock.elapsedRealtime].
 *   Injected in unit tests so emissions run without Android's [SystemClock].
 */
class LyricInfoLyricProducer(
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : LyricProducer {

    override val id: LyricSource = LyricSource.LYRICINFO

    private val mutableConnection = MutableStateFlow(ProducerConnection.DISCONNECTED)
    override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()

    private val mutableState = MutableStateFlow<LyricProducerState?>(null)
    override val state: StateFlow<LyricProducerState?> = mutableState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var started = false
    private var contextRef: Context? = null
    private var manager: MediaSessionManager? = null

    // --- Ingress state, updated by session callbacks / position poll; read by emit(). ---
    @Volatile private var controller: MediaController? = null
    @Volatile private var timedLines: List<ElrcParser.TimedLine> = emptyList()
    @Volatile private var translationLines: List<ElrcParser.TimedLine> = emptyList()
    @Volatile private var title: String = ""
    @Volatile private var artist: String = ""
    @Volatile private var album: String = ""
    @Volatile private var durationMs: Long = 0L

    // --- Position extrapolation state ---
    @Volatile private var lastRealPositionMs: Long = 0L
    @Volatile private var lastRealPositionClockMs: Long = -1L
    @Volatile private var lastPlaybackSpeed: Float = 0f
    @Volatile private var isPlayingState: Boolean = false
    @Volatile private var currentPositionMs: Long = 0L
    /** True while currentPositionMs is being advanced by extrapolation (stale/frozen ps). */
    @Volatile private var extrapolating: Boolean = false

    // Session/sequence for arbiter dedup (producerId:generation:sequence).
    @Volatile private var generation: Int = 0
    @Volatile private var sequence: Long = 0L

    private val sessionListener = object : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(sessions: List<MediaController>?) {
            refreshSessions(sessions.orEmpty())
        }
    }

    override fun start(context: Context) {
        if (started) {
            AppLog.i("LyricInfoLyricProducer", "start: already started (no-op)")
            return
        }
        started = true
        val ctx = context.applicationContext
        contextRef = ctx
        manager = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val component = ComponentName(ctx, LyricInfoNotificationListener::class.java)
        runCatching {
            manager?.addOnActiveSessionsChangedListener(sessionListener, component)
        }.onFailure {
            AppLog.w("LyricInfoLyricProducer", "addOnActiveSessionsChangedListener failed", it)
        }
        val sessions = runCatching { manager?.getActiveSessions(component) ?: emptyList() }
            .getOrDefault(emptyList())
        refreshSessions(sessions)
        scope.launch { positionLoop() }
        AppLog.i("LyricInfoLyricProducer", "start: done (sessions=${sessions.size})")
    }

    override fun stop() {
        if (!started) {
            AppLog.i("LyricInfoLyricProducer", "stop: not started (no-op)")
            return
        }
        started = false
        runCatching { manager?.removeOnActiveSessionsChangedListener(sessionListener) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
        scope.cancel()
        mutableConnection.value = ProducerConnection.DISCONNECTED
        mutableState.value = null
        AppLog.i("LyricInfoLyricProducer", "stop: done")
    }

    /**
     * Called by [LyricInfoNotificationListener] once the user grants notification access and the
     * listener connects. Re-queries active sessions (which are now visible cross-app) and, if a
     * session with `lyricInfo` is present, switches to it immediately.
     */
    fun onListenerConnected() {
        if (!started) return
        val ctx = contextRef ?: return
        val component = ComponentName(ctx, LyricInfoNotificationListener::class.java)
        val sessions = runCatching { manager?.getActiveSessions(component) ?: emptyList() }
            .getOrDefault(emptyList())
        AppLog.i("LyricInfoLyricProducer", "onListenerConnected: sessions=${sessions.size}")
        refreshSessions(sessions)
    }

    private fun refreshSessions(sessions: List<MediaController>) {
        val picked = sessions.firstOrNull { it.metadata?.getString(LYRIC_INFO_KEY) != null }
        if (picked == null) {
            if (controller != null) {
                controller?.unregisterCallback(controllerCallback)
                controller = null
                timedLines = emptyList()
                translationLines = emptyList()
                mutableState.value = null
            }
            if (mutableConnection.value != ProducerConnection.DISCONNECTED) {
                AppLog.i("LyricInfoLyricProducer", "no session with lyricInfo -> DISCONNECTED")
                mutableConnection.value = ProducerConnection.DISCONNECTED
            }
            return
        }
        if (controller !== picked) {
            controller?.unregisterCallback(controllerCallback)
            controller = picked
            picked.registerCallback(controllerCallback)
        }
        if (mutableConnection.value != ProducerConnection.CONNECTED) {
            AppLog.i("LyricInfoLyricProducer", "session with lyricInfo -> CONNECTED")
            mutableConnection.value = ProducerConnection.CONNECTED
        }
        updateFromController(picked)
    }

    private fun updateFromController(c: MediaController) {
        val meta = c.metadata ?: return
        val lyricInfo = meta.getString(LYRIC_INFO_KEY) ?: return
        val payload = runCatching { lyricInfoJson.decodeFromString<LyricInfoPayload>(lyricInfo) }
            .onFailure { AppLog.w("LyricInfoLyricProducer", "decode lyricInfo failed", it) }
            .getOrNull() ?: return
        val newTitle = payload.songName.orEmpty()
        if (newTitle != title || payload.artist != artist) {
            generation++
            // 旧歌的外推/容差状态不得带进新歌:换歌后第一条真实位置无条件接受。
            extrapolating = false
            AppLog.i(
                "LyricInfoLyricProducer",
                "song changed: title=$newTitle artist=${payload.artist}"
            )
        }
        title = newTitle
        artist = payload.artist.orEmpty()
        album = payload.album.orEmpty()
        durationMs = meta.getLong(MEDIA_METADATA_KEY_DURATION).coerceAtLeast(0L)
        timedLines = ElrcParser.parse(payload.lyric.orEmpty())
        translationLines = ElrcParser.parse(payload.translation.orEmpty())
        val ps = c.playbackState
        if (ps != null) {
            applyPlaybackState(ps)
        }
        emit()
    }

    private fun applyPlaybackState(ps: PlaybackState) {
        val now = clock()
        // MIUI 息屏冻结播放器进程后，playbackState 非 null 但 position 卡在冻结时刻
        //（lastPositionUpdateTime 不再推进）。继续采用 ps.position 会把外推位置每 250ms
        // 拉回旧值，歌词永远停在最后一句。检测到 stale 改为按播放速率外推。
        val updateTime = ps.lastPositionUpdateTime
        if (updateTime > 0L && now - updateTime > STALE_POSITION_MS &&
            lastPlaybackSpeed > 0f && lastRealPositionClockMs >= 0L
        ) {
            currentPositionMs =
                lastRealPositionMs + ((now - lastRealPositionClockMs) * lastPlaybackSpeed).toLong()
            extrapolating = true
            return
        }
        // Stale→恢复（抬起手机、切通道回退）时，MediaSession position 可能短暂落后于
        // 外推值（共享内存/回调延迟）。Lyricon 通道的 monotonicResume 在 1..300ms 容差内
        // 保持外推值以避免行回退闪烁；本通道此前无条件接受 ps.position，抬起解冻时行
        // 会回跳几秒。对齐同样的容差保护。
        val monotonicResume = isMonotonicExtrapolationResume(
            wasExtrapolating = extrapolating,
            extrapolatedPositionMs = currentPositionMs,
            realPositionMs = ps.position
        )
        if (monotonicResume) {
            // 保持单调外推值，把外推时钟重新锚定到它。
            lastRealPositionMs = currentPositionMs
        } else {
            lastRealPositionMs = ps.position
            currentPositionMs = ps.position
        }
        lastRealPositionClockMs = now
        lastPlaybackSpeed = ps.playbackSpeed
        isPlayingState = ps.state == PlaybackState.STATE_PLAYING
        extrapolating = false
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            controller?.let(::updateFromController)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state != null) applyPlaybackState(state)
            emit()
        }
    }

    private suspend fun positionLoop() {
        while (scope.isActive) {
            val c = controller
            val now = clock()
            if (c != null) {
                val ps = c.playbackState
                if (ps != null) {
                    applyPlaybackState(ps)
                } else if (lastPlaybackSpeed > 0f && lastRealPositionClockMs >= 0L) {
                    // No fresh playback state: extrapolate from the last real position.
                    currentPositionMs =
                        lastRealPositionMs + ((now - lastRealPositionClockMs) * lastPlaybackSpeed).toLong()
                    extrapolating = true
                }
                emit()
            }
            delay(POSITION_POLL_MS)
        }
    }

    /**
     * Build and emit a [LyricProducerState] from the current ingress fields: select the active
     * line for [currentPositionMs], rebuild the per-word cache only when the line changes.
     */
    private fun emit() {
        val c = controller ?: run { mutableState.value = null; return }
        if (timedLines.isEmpty()) {
            // Connected session but no parseable lyrics yet: emit metadata-only.
            emitTrack(null)
            return
        }
        val active = ElrcParser.activeLineAt(timedLines, currentPositionMs)
        val translationText = active?.let { a ->
            translationLines.firstOrNull { it.startMs == a.startMs }?.text.orEmpty()
        }.orEmpty()
        val words = active?.words?.takeIf { it.isNotEmpty() }
        val lyricKind = when {
            active == null -> LyricKind.NONE
            words != null -> LyricKind.SYLLABLE
            else -> LyricKind.LINE
        }
        val nextLine = timedLines
            .firstOrNull { it.startMs > currentPositionMs }
            ?.text
            .orEmpty()
        val nextLineStartMs = timedLines
            .asSequence()
            .map { it.startMs }
            .filter { it > currentPositionMs }
            .minOrNull()
        val now = clock()
        sequence++
        mutableState.value = LyricProducerState(
            producerId = PRODUCER_ID,
            generation = generation,
            sequence = sequence,
            status = "ready",
            trackUri = "lyricinfo:$title",
            title = title,
            artist = artist,
            album = album,
            imageId = "",
            line = active?.text.orEmpty(),
            romanizedLine = "",
            translatedLine = translationText,
            lineIndex = active?.let { a -> timedLines.indexOf(a) } ?: -1,
            positionMs = currentPositionMs,
            durationMs = durationMs,
            sampledAtElapsedMs = now,
            speed = if (isPlayingState) lastPlaybackSpeed else 0f,
            playing = isPlayingState,
            receivedAtElapsedMs = now,
            words = words,
            renderModes = defaultRenderModes(),
            lyricKind = lyricKind,
            alignedRight = false,
            lineStartMs = active?.startMs ?: 0L,
            lineEndMs = active?.endMs ?: 0L,
            ruby = emptyList(),
            layoutGroups = emptyList(),
            hasTimedLyrics = timedLines.any { it.endMs > it.startMs },
            nextLineStartMs = nextLineStartMs,
            nextLine = nextLine
        )
    }

    private fun emitTrack(active: ElrcParser.TimedLine?) {
        val now = clock()
        sequence++
        mutableState.value = LyricProducerState(
            producerId = PRODUCER_ID,
            generation = generation,
            sequence = sequence,
            status = "ready",
            trackUri = "lyricinfo:$title",
            title = title,
            artist = artist,
            album = album,
            imageId = "",
            line = active?.text.orEmpty(),
            romanizedLine = "",
            translatedLine = "",
            lineIndex = -1,
            positionMs = currentPositionMs,
            durationMs = durationMs,
            sampledAtElapsedMs = now,
            speed = if (isPlayingState) lastPlaybackSpeed else 0f,
            playing = isPlayingState,
            receivedAtElapsedMs = now,
            words = null,
            renderModes = defaultRenderModes(),
            lyricKind = LyricKind.NONE,
            alignedRight = false,
            lineStartMs = 0L,
            lineEndMs = 0L,
            ruby = emptyList(),
            layoutGroups = emptyList(),
            hasTimedLyrics = false,
            nextLineStartMs = null,
            nextLine = ""
        )
    }

    companion object {
        private const val PRODUCER_ID = "lyricinfo"
        private const val LYRIC_INFO_KEY = "lyricInfo"
        private const val MEDIA_METADATA_KEY_DURATION = "android.media.metadata.DURATION"
        private const val POSITION_POLL_MS = 250L
        /** PlaybackState position 多久未更新视为 stale（播放器进程被冻结）。 */
        private const val STALE_POSITION_MS = 2_000L

        private val lyricInfoJson = Json { ignoreUnknownKeys = true }

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

/**
 * Stale→恢复（抬起手机、通道回退）时是否保持单调外推值:真实位置仅小幅落后(容差内)
 * 视为共享内存/回调延迟,保持外推值避免行回退闪烁;大幅落后(seek/换歌/真回退)按真实
 * 位置处理。与 Lyricon 通道的 monotonicResume 同一容差语义。
 */
internal fun isMonotonicExtrapolationResume(
    wasExtrapolating: Boolean,
    extrapolatedPositionMs: Long,
    realPositionMs: Long,
    toleranceMs: Long = 300L
): Boolean = wasExtrapolating &&
    (extrapolatedPositionMs - realPositionMs) in 1..toleranceMs

/** JSON shape written into `MediaMetadata.extras.lyricInfo` by the LyricInfo module. */
@Serializable
internal data class LyricInfoPayload(
    val songName: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val songId: String? = null,
    val lyric: String? = null,
    val format: String? = null,
    val translation: String? = null
)