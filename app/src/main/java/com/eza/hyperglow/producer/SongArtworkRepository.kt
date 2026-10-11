package com.eza.hyperglow.producer

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.eza.hyperglow.AppLog
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 歌曲图片 JPEG 编码上限:超过即拒绝出帧(有界渲染器契约,与 wire 传输上限同值)。 */
internal const val MAX_ARTWORK_JPEG_BYTES = 24 * 1024

/** 缩图最长边(px):封面只在歌曲信息左侧小图展示,超大原图一律先降采样再编码。 */
private const val MAX_ARTWORK_SIDE_PX = 192

/** 同一曲目失败后的重试间隔(避免每次投影发布都撞一次取图)。 */
private const val RETRY_THROTTLE_MS = 5_000L

/**
 * 帧缓存条数上限:封面只在歌曲信息左侧单槽小图展示,缓存只为「同曲目重复发布」与
 * 歌单前后跳转服务;64 条覆盖一次连续播放的最近使用窗口,更老的条目重取一次即可。
 */
internal const val MAX_ARTWORK_FRAME_ENTRIES = 64

/**
 * 帧缓存总权重上限(字节):单帧 JPEG 硬上限 24KiB,64 条最坏 1.5MiB,所以 1MiB 预算
 * 在「大帧」时先于条数上限触发、在典型 8–16KiB 小帧时由条数上限兜底——两个上限都真正
 * 生效。修复前 frames 无任何淘汰:500 首 ≈ 12MiB 且随播放时长继续增长。
 */
internal const val MAX_ARTWORK_FRAME_BYTES = 1 * 1024 * 1024

/**
 * 失败表条数上限:失败表只为 [RETRY_THROTTLE_MS] 的节流服务,128 条 ≈ 128 个不同曲目的
 * 节流窗口(每条约百字节);超限按时间淘汰最旧,避免换歌/取图失败风暴下无界增长。
 */
internal const val MAX_ARTWORK_ATTEMPT_ENTRIES = 128

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
 * 帧的缓存权重(字节):JPEG 字节 + 键/曲目/歌手/包名四个字符串的 UTF-16 载荷。
 * 字符串来自系统播放窗口元数据,长度不可控,必须计入预算口径。
 */
internal fun artworkFrameWeightBytes(frame: ArtworkFrame): Int =
    frame.jpeg.size + (
        frame.key.length + frame.title.length +
            frame.artist.length + frame.sourcePackage.length
        ) * 2

/**
 * 有界帧缓存:条数上限 [maxEntries] + 总权重上限 [maxBytes],超限按 LRU(最久未用)淘汰。
 * [LinkedHashMap] 用 access-order:表头即最久未用条目,`get` 命中同时刷新使用序,所以
 * 「最近用过的帧」优先保留。
 *
 * 刚写入的条目永不淘汰:单帧超预算时宁可让它独占缓存,也不让「出帧了却没有缓存」把同曲目
 * 拖回每 5s 重取一次——生产不可达(单帧 ≤ 24KiB ≪ 1MiB),只为守住
 * [SongArtworkRepository.frameFor] 的命中语义。
 */
internal class ArtworkFrameCache(
    private val maxEntries: Int = MAX_ARTWORK_FRAME_ENTRIES,
    private val maxBytes: Int = MAX_ARTWORK_FRAME_BYTES
) {
    private val frames = LinkedHashMap<String, ArtworkFrame>(16, 0.75f, /* accessOrder = */ true)
    private var totalBytes = 0

    /** 命中同时刷新使用序(LRU 语义的一部分);未命中返回 null。 */
    @Synchronized
    fun get(requestKey: String): ArtworkFrame? = frames[requestKey]

    @Synchronized
    fun put(requestKey: String, frame: ArtworkFrame) {
        frames.remove(requestKey)?.let { totalBytes -= artworkFrameWeightBytes(it) }
        frames[requestKey] = frame
        totalBytes += artworkFrameWeightBytes(frame)
        val iterator = frames.entries.iterator()
        while (iterator.hasNext() && overBudget()) {
            val candidate = iterator.next()
            // 迭代顺序 = 使用序,刚写入的条目在尾部;轮到它说明已没有更旧的条目可淘汰。
            if (candidate.key == requestKey) continue
            iterator.remove()
            totalBytes -= artworkFrameWeightBytes(candidate.value)
        }
    }

    @Synchronized
    fun clear() {
        frames.clear()
        totalBytes = 0
    }

    private fun overBudget(): Boolean = totalBytes > maxBytes || frames.size > maxEntries
}

/**
 * 取图尝试节流表:记录每个曲目键的最近一次取图时刻,窗口内不重复取图。
 * 条数上限 [maxEntries],超限按时间淘汰最旧条目——失败表只服务节流,更早的条目没有保留
 * 价值,无界增长只会随换歌无限膨胀(修复前正是如此)。
 */
internal class ArtworkAttemptThrottle(
    private val throttleMs: Long = RETRY_THROTTLE_MS,
    private val maxEntries: Int = MAX_ARTWORK_ATTEMPT_ENTRIES,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private val lastAttemptAt = HashMap<String, Long>()

    /** 节流窗口内返回 false(本次不取图);允许时记录时刻并返回 true。 */
    @Synchronized
    fun tryAcquire(requestKey: String): Boolean {
        val now = clock()
        val last = lastAttemptAt[requestKey]
        if (last != null && now - last < throttleMs) return false
        lastAttemptAt[requestKey] = now
        while (lastAttemptAt.size > maxEntries) {
            val oldest = lastAttemptAt.minByOrNull { it.value } ?: break
            lastAttemptAt.remove(oldest.key)
        }
        return true
    }

    @Synchronized
    fun clear() {
        lastAttemptAt.clear()
    }
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
 *
 * 容量:帧缓存按字节 LRU + 条数上限淘汰([ArtworkFrameCache]),失败表按条数上限淘汰
 * 最旧([ArtworkAttemptThrottle])——两者都随换歌持续增长,必须有界。
 */
internal object SongArtworkRepository {
    private const val TAG = "SongArtwork"

    private val defaultExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hyperglow-artwork").apply { isDaemon = true }
    }
    private val pending = HashSet<String>()
    private val frames = ArtworkFrameCache()
    private val attempts = ArtworkAttemptThrottle(clock = { elapsedRealtime() })
    private val currentFrame = MutableStateFlow<ArtworkFrame?>(null)

    /** 当前已校对封面帧;预览组合订阅它,出帧后驱动重组。 */
    val current: StateFlow<ArtworkFrame?> = currentFrame

    /**
     * 出帧回调 [trackGeneration] 为请求发起时的曲目代号,供投影桥把帧补挂到同一曲目的
     * 快照上;曲目已切换时消费侧按代号不匹配自行丢弃。
     */
    @Volatile
    var onFrameReady: ((trackGeneration: Long, frame: ArtworkFrame) -> Unit)? = null

    /** 取图实现缝:默认走系统播放窗口校对取图;单测替换为替身以避免真实 MediaSession。 */
    @Volatile
    internal var fetchArtwork: (Context, String, String) -> ArtworkFrame? =
        { context, title, artist -> fetch(context, title, artist) }

    /** 时钟缝:默认 [android.os.SystemClock.elapsedRealtime],决定 [attempts] 的节流窗口。 */
    @Volatile
    internal var elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() }

    /** 执行器缝:默认单线程守护执行器([ensure] 串行取图);单测替换为手动驱动。 */
    @Volatile
    internal var artworkExecutor: Executor = defaultExecutor

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
            if (!attempts.tryAcquire(requestKey)) return
            pending += requestKey
        }
        val appContext = context.applicationContext ?: context
        artworkExecutor.execute {
            val frame = runCatching { fetchArtwork(appContext, title, artist) }
                .onFailure { AppLog.w(TAG, "artwork fetch failed", it) }
                .getOrNull()
            synchronized(pending) { pending -= requestKey }
            if (frame != null) {
                frames.put(requestKey, frame)
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
        return frames.get(requestKey(title, artist))
    }

    /**
     * 单测专用:清空在途集合/帧缓存/失败表/当前帧,并把 fetch/时钟/执行器三个注入缝复位
     * 为生产默认,避免单例跨用例状态泄漏。生产代码不调用。
     */
    internal fun resetForTest() {
        synchronized(pending) { pending.clear() }
        frames.clear()
        attempts.clear()
        currentFrame.value = null
        onFrameReady = null
        fetchArtwork = { context, title, artist -> fetch(context, title, artist) }
        elapsedRealtime = { android.os.SystemClock.elapsedRealtime() }
        artworkExecutor = defaultExecutor
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
