package com.eza.hyperglow.producer

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.eza.hyperglow.AppLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 歌曲图片 JPEG 编码上限:超过即拒绝出帧(有界渲染器契约,与 wire 传输上限同值)。 */
internal const val MAX_ARTWORK_JPEG_BYTES = 24 * 1024

/** 缩图最长边(px):封面只在歌曲信息左侧小图展示,超大原图一律先降采样再编码。 */
private const val MAX_ARTWORK_SIDE_PX = 192

/**
 * 已校对的歌曲图片帧:取自系统播放窗口(媒体会话 MediaMetadata)的专辑图,
 * 经 [resolveVerifiedArtworkSession] 校对为「当前播放的音乐软件」的当前曲目后,
 * 降采样并压成有界 JPEG。[key] 是同曲目稳定键(包名+曲目身份),供渲染侧按帧缓存。
 */
internal data class ArtworkFrame(
    val key: String,
    val jpeg: ByteArray,
    val title: String,
    val artist: String,
    val sourcePackage: String
) {
    override fun equals(other: Any?): Boolean =
        other is ArtworkFrame && other.key == key && other.jpeg.contentEquals(jpeg)

    override fun hashCode(): Int = 31 * key.hashCode() + jpeg.contentHashCode()
}

/**
 * 歌曲图片仓库(app 进程):从系统播放窗口(MediaSessionManager 活动会话)取专辑图,
 * 包名/曲目双校对通过后缓存为 [ArtworkFrame]。
 *
 * 取图侧校对口径(与 [resolveVerifiedArtworkSession] 同文档):
 * - 在播会话(PlaybackState PLAYING/BUFFERING)= 当前播放的音乐软件;
 * - 会话曲目必须与当前歌词曲目同曲,系统播放窗口滞留的旧封面直接拒绝;
 * - 任何歧义拒绝出帧——预览/实机都不显示,而不是显示错的图。
 *
 * 并发:[ensure] 单线程串行取图,同一曲目重复调用幂等(节流窗口内不重取);
 * 取图失败静默降级为无封面,不影响歌词管线。日志走 AppLog(日志契约)。
 */
internal object SongArtworkRepository {
    private const val TAG = "SongArtwork"

    /** 同一曲目失败后的重试间隔(避免每次投影发布都撞一次取图)。 */
    private const val RETRY_THROTTLE_MS = 5_000L

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hyperglow-artwork").apply { isDaemon = true }
    }
    private val pending = HashSet<String>()
    private val lastAttemptAt = HashMap<String, Long>()
    private val frames = HashMap<String, ArtworkFrame>()
    private val currentFrame = MutableStateFlow<ArtworkFrame?>(null)

    /** 当前已校对封面帧;预览组合订阅它,出帧后驱动重组。 */
    val current: StateFlow<ArtworkFrame?> = currentFrame

    /**
     * 出帧回调 [trackGeneration] 为请求发起时的曲目代号,供投影桥把帧补挂到同一曲目的
     * 快照上;曲目已切换时消费侧按代号不匹配自行丢弃。
     */
    @Volatile
    var onFrameReady: ((trackGeneration: Long, frame: ArtworkFrame) -> Unit)? = null

    /**
     * 确保当前曲目有已校对封面帧(幂等):[title] 非空才可能出帧;异步取图,结果经
     * [current] 与 [onFrameReady] 发布。无通知访问权限时 getActiveSessions 抛安全
     * 异常,runCatching 兜底为无封面(与 LyricInfo 生产者同权限模型)。
     */
    fun ensure(context: Context, trackGeneration: Long, title: String, artist: String) {
        if (title.isBlank()) return
        val requestKey = requestKey(title, artist)
        synchronized(pending) {
            if (requestKey in pending) return
            if (frameFor(title, artist) != null) return
            val now = android.os.SystemClock.elapsedRealtime()
            val last = lastAttemptAt[requestKey] ?: 0L
            if (last != 0L && now - last < RETRY_THROTTLE_MS) return
            lastAttemptAt[requestKey] = now
            pending += requestKey
        }
        val appContext = context.applicationContext ?: context
        executor.execute {
            val frame = runCatching { fetch(appContext, title, artist) }
                .onFailure { AppLog.w(TAG, "artwork fetch failed", it) }
                .getOrNull()
            synchronized(pending) { pending -= requestKey }
            if (frame != null) {
                synchronized(frames) { frames[requestKey] = frame }
                currentFrame.value = frame
                onFrameReady?.invoke(trackGeneration, frame)
                AppLog.i(TAG, "artwork frame ready pkg=${frame.sourcePackage} key=${frame.key}")
            }
        }
    }

    /**
     * 与当前曲目([title]/[artist])同曲的已校对封面帧;对不上返回 null(不显示)。
     */
    fun frameFor(title: String, artist: String): ArtworkFrame? {
        if (title.isBlank()) return null
        currentFrame.value?.let { current ->
            if (isSameTrackIdentity(title, artist, current.title, current.artist)) return current
        }
        synchronized(frames) { frames[requestKey(title, artist)] }?.let { return it }
        return null
    }

    private fun requestKey(title: String, artist: String): String =
        "${title.trim().lowercase()} ${artist.trim().lowercase()}"

    private fun fetch(context: Context, title: String, artist: String): ArtworkFrame? {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val component = ComponentName(context, LyricInfoNotificationListener::class.java)
        val sessions = runCatching { manager.getActiveSessions(component) }.getOrNull() ?: return null
        val samples = sessions.map { controller ->
            ArtworkSessionSample(
                packageName = controller.packageName.orEmpty(),
                playing = controller.playbackState?.state?.let { it in PLAYING_STATES } ?: false,
                title = controller.metadata
                    ?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                artist = controller.metadata
                    ?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            )
        }
        val chosen = resolveVerifiedArtworkSession(samples, title, artist) ?: return null
        val controller = sessions.firstOrNull {
            it.packageName == chosen.packageName &&
                it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty() == chosen.title
        } ?: sessions.firstOrNull { it.packageName == chosen.packageName } ?: return null
        val bitmap = artworkBitmap(controller.metadata) ?: return null
        val jpeg = encodeBoundedJpeg(bitmap) ?: return null
        return ArtworkFrame(
            // 稳定键必须有界(≤ wire 键上限 64 字符):包名截断 + 曲目身份哈希,
            // 长歌名/歌手名不会把键撑爆、更不会连累快照被拒收。
            key = boundedFrameKey(chosen.packageName, chosen.title, chosen.artist),
            jpeg = jpeg,
            title = chosen.title,
            artist = chosen.artist,
            sourcePackage = chosen.packageName
        )
    }

    /** 同曲目键稳定、跨曲目可区分,长度恒 ≤ 41 字符(< wire MAX_ARTWORK_KEY_CHARS)。 */
    private fun boundedFrameKey(packageName: String, title: String, artist: String): String {
        val identity = "${title.trim().lowercase()} ${artist.trim().lowercase()}"
        val hash = identity.hashCode().toUInt().toString(16)
        return "${packageName.take(32)}|$hash"
    }

    private fun artworkBitmap(metadata: MediaMetadata?): Bitmap? {
        if (metadata == null) return null
        return metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    }

    /**
     * 降采样到 [MAX_ARTWORK_SIDE_PX] 内后压 JPEG:质量阶梯递减,始终以
     * [MAX_ARTWORK_JPEG_BYTES] 为硬上限,超限直接放弃出帧(有界渲染器契约)。
     */
    private fun encodeBoundedJpeg(source: Bitmap): ByteArray? {
        val scaled = scaleToSide(source, MAX_ARTWORK_SIDE_PX)
        for (quality in intArrayOf(85, 70, 55)) {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
            val bytes = out.toByteArray()
            if (bytes.size <= MAX_ARTWORK_JPEG_BYTES) return bytes
        }
        return null
    }

    private fun scaleToSide(source: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxSide || longest <= 0) return source
        val scale = maxSide.toFloat() / longest
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(source, width, height, true)
        if (scaled !== source) source.recycle()
        return scaled
    }

    private val PLAYING_STATES = setOf(
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING
    )
}
