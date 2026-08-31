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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * [LyricProducer] that reads lyrics injected by the LyricInfo Xposed module.
 *
 * LyricInfo hooks music apps to write a JSON payload into `MediaMetadata.extras.lyricInfo`
 * (elrc/lrc format). Any app with notification access can read it. This producer:
 * 1. Registers a [MediaSessionManager.OnActiveSessionsChangedListener] scoped to the app's
 *    [LyricInfoNotificationListener], which is how a third-party app reads other apps' sessions.
 * 2. Picks the active session whose `MediaMetadata` carries a `lyricInfo` extra; if none, falls
 *    back to any active media session so that playback metadata (title/artist/position) is still
 *    available when the lyric injection module is absent or when another producer (Lyricon) dies.
 * 3. Parses the elrc/lrc payload via [ElrcParser] and selects the active line by position.
 * 4. Polls [MediaController.playbackState] for position and extrapolates while playing.
 *
 * Requires the user to grant notification access (ACTION_NOTIFICATION_LISTENER_SETTINGS).
 * Until a session is seen, [connection] stays [ProducerConnection.DISCONNECTED] and [state] stays
 * null, so the arbiter falls back automatically.
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
        val picked = pickMediaSession(sessions)
        if (picked == null) {
            if (controller != null) {
                controller?.unregisterCallback(controllerCallback)
                controller = null
                timedLines = emptyList()
                translationLines = emptyList()
                mutableState.value = null
            }
            if (mutableConnection.value != ProducerConnection.DISCONNECTED) {
                AppLog.i("LyricInfoLyricProducer", "no active media session -> DISCONNECTED")
                mutableConnection.value = ProducerConnection.DISCONNECTED
            }
            return
        }
        val hasLyrics = picked.metadata?.getString(LYRIC_INFO_KEY) != null
        if (controller !== picked) {
            controller?.unregisterCallback(controllerCallback)
            controller = picked
            picked.registerCallback(controllerCallback)
        }
        if (mutableConnection.value != ProducerConnection.CONNECTED) {
            AppLog.i(
                "LyricInfoLyricProducer",
                "active media session -> CONNECTED (lyrics=${hasLyrics})"
            )
            mutableConnection.value = ProducerConnection.CONNECTED
        }
        updateFromController(picked)
    }

    private fun updateFromController(c: MediaController) {
        val meta = c.metadata ?: return
        val lyricInfo = meta.getString(LYRIC_INFO_KEY)
        val payload = lyricInfo?.let(::parseLyricInfoPayload)
        // Derive title/artist from lyric payload when available, otherwise read from MediaMetadata
        // so a session without LyricInfo injection still surfaces track metadata.
        // 精简版(Lite)原生逐行 payload 例外:songName 携带当前歌词行、artist 是
        // "歌名 - 歌手"复合串(ColorOS 锁屏岛原生协议按行复用字段),不能当曲目元数据——
        // 否则歌曲信息带上歌词,且 songName 每行都变会误触发 song changed 重置外推状态。
        // 检测到该格式时改用系统 MediaMetadata 的干净 title/artist。
        val metaTitle = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val metaArtist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
        val nativePerLine = isNativePerLinePayload(payload, metaTitle)
        val newTitle = if (nativePerLine && metaTitle.isNotBlank()) metaTitle
            else payload?.songName?.takeIf { it.isNotBlank() } ?: metaTitle
        val newArtist = if (nativePerLine && metaArtist.isNotBlank()) metaArtist
            else payload?.artist?.takeIf { it.isNotBlank() } ?: metaArtist
        val newAlbum = payload?.album?.takeIf { it.isNotBlank() }
            ?: meta.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM).orEmpty()
        if (newTitle != title || newArtist != artist) {
            generation++
            // 旧歌的外推/容差状态不得带进新歌:换歌后第一条真实位置无条件接受。
            extrapolating = false
            AppLog.i(
                "LyricInfoLyricProducer",
                "song changed: title=$newTitle artist=$newArtist"
            )
        }
        title = newTitle
        artist = newArtist
        album = newAlbum
        durationMs = meta.getLong(MEDIA_METADATA_KEY_DURATION).coerceAtLeast(0L)
        timedLines = ElrcParser.parse(payload?.lyric.orEmpty())
        // 完整版翻译在 translation;QQ 音乐精简版原生输出在 transLyric,两者取其一。
        translationLines = ElrcParser.parse(
            payload?.translation?.takeIf { it.isNotBlank() }
                ?: payload?.transLyric.orEmpty()
        )
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
        // 最后一句歌词唱完后（position 越过其 end，歌曲进入尾奏/纯器乐段落），清空活动行
        // 让投影显示 🎶 占位。activeLineAt 返回「最后一条 start <= pos」的行，不检查 end，
        // 这里显式兜住结尾，避免最后一句在尾奏期间长期滞留。
        val active = activeLinePastEndOrNull(timedLines, currentPositionMs)
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
        internal const val LYRIC_INFO_KEY = "lyricInfo"
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
/**
 * Select the session this producer should follow. Prefer one that carries the `lyricInfo` extra
 * (injected lyrics); if none exists, fall back to any active media session so playback metadata
 * and MediaSession position are still available when the lyric injection module is absent or when
 * another producer (Lyricon) dies — the recovery path for issue #5.
 */
internal fun pickMediaSession(sessions: List<MediaController>): MediaController? =
    sessions.firstOrNull { it.metadata?.getString(LyricInfoLyricProducer.LYRIC_INFO_KEY) != null }
        ?: sessions.firstOrNull()

/**
 * Select the active line for [positionMs]; returns null once the position has passed the final
 * line's end (lyrics finished, song is in its instrumental outro) so projection shows the 🎶
 * placeholder instead of leaving the last line stuck on screen until the song ends.
 */
internal fun activeLinePastEndOrNull(
    lines: List<ElrcParser.TimedLine>,
    positionMs: Long
): ElrcParser.TimedLine? {
    val active = ElrcParser.activeLineAt(lines, positionMs)
    val last = lines.lastOrNull() ?: return active
    return active?.takeUnless { positionMs >= last.endMs }
}

internal fun isMonotonicExtrapolationResume(
    wasExtrapolating: Boolean,
    extrapolatedPositionMs: Long,
    realPositionMs: Long,
    toleranceMs: Long = 300L
): Boolean = wasExtrapolating &&
    (extrapolatedPositionMs - realPositionMs) in 1..toleranceMs

/**
 * JSON shape written into `MediaMetadata.extras.lyricInfo` by the LyricInfo module.
 *
 * 完整版字段:songName/artist/album/songId/lyric/format/translation。
 * 精简版(Lite)是播放器原生输出,字段集随播放器而变(QQ 音乐用 transLyric 携带翻译,
 * 还有 noLyric/lyricType/txtlyric 等),且 songId 等可能是数字类型——因此不用严格
 * data-class 反序列化(类型不匹配会让整个 payload 解析失败),改为宽松提取。
 */
internal data class LyricInfoPayload(
    val songName: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val songId: String? = null,
    val lyric: String? = null,
    val format: String? = null,
    val translation: String? = null,
    val transLyric: String? = null
)

/**
 * 宽松解析 lyricInfo JSON:字段缺失/类型不匹配(数字、boolean)一律降级为 null,
 * 绝不因原生变体格式差异丢掉整个 payload(歌词是最关键字段)。
 */
internal fun parseLyricInfoPayload(raw: String): LyricInfoPayload? = runCatching {
    val obj = lyricInfoJson.parseToJsonElement(raw).let { it as? JsonObject } ?: return null
    // 字符串/数字/boolean 原始值都按文本接受(精简版 songId 可能是数字);
    // null 字面量(JsonNull)与对象/数组降级为 null。
    fun text(key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    LyricInfoPayload(
        songName = text("songName"),
        artist = text("artist"),
        album = text("album"),
        songId = text("songId"),
        lyric = text("lyric"),
        format = text("format"),
        translation = text("translation"),
        transLyric = text("transLyric")
    )
}.onFailure {
    AppLog.w("LyricInfoLyricProducer", "decode lyricInfo failed", it)
}.getOrNull()

/**
 * 判断是否为精简版(Lite)原生逐行 lyricInfo payload(纯函数,可单测)。
 *
 * 原生格式(ColorOS 锁屏岛协议)按行更新 MediaMetadata 并复用字段:songName 携带
 * 当前歌词行,artist 是"歌名 - 歌手"复合串(实测 logcat 证据)。两个信号任一命中
 * 即判定,命中后调用方应改用系统 MediaMetadata 的干净 title/artist:
 * 1. songName 与 lyric 解析出的唯一一行文本一致(songName 即当前歌词行);
 * 2. artist 包含系统 MediaMetadata 的干净歌名(复合串组成部分)。
 * 完整版 payload(artist 为纯歌手名、lyric 为整首多行)不会命中任一信号。
 */
internal fun isNativePerLinePayload(
    payload: LyricInfoPayload?,
    metadataTitle: String
): Boolean {
    if (payload == null) return false
    val lyricLines = ElrcParser.parse(payload.lyric.orEmpty())
    if (lyricLines.size == 1 && payload.songName == lyricLines[0].text) return true
    if (metadataTitle.isNotBlank() && payload.artist.orEmpty().contains(metadataTitle)) return true
    return false
}