package com.eza.hyperglow.aod

import android.os.Bundle
import android.os.RemoteCallbackList
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.customization.CompiledCustomizationBundleCodec
import kotlin.math.abs

data class AodDisplayState(
    val visible: Boolean,
    val playbackActive: Boolean = false,
    val pauseRetentionEligible: Boolean = false,
    val userId: Int = 0,
    val trackGeneration: Long = 0L,
    val aodEnabled: Boolean = true,
    val lockscreenEnabled: Boolean = false,
    val keepAlive: Boolean = false,
    val positionFollowingEnabled: Boolean = false,
    val burnInPattern: String = "static_bottom",
    val burnInIntervalMs: Long = 60_000L,
    val suppressStockAodContent: Boolean = false,
    val aodRotateWithDevice: Boolean = false,
    val aodRotationMode: String = AOD_ROTATION_MODE_PORTRAIT,
    val aodRotationSettleMs: Long = 1_000L,
    val aodCanvasAnchorLandscape: Float = 0.5f,
    val aodLandscapeTextScale: Float = 1f,
    val aodLandscapeHideStock: Boolean = false,
    val aodLandscapeFullscreen: Boolean = false,
    val aodLandscapeFullscreenSafeMarginPercent: Float = DEFAULT_FULLSCREEN_SAFE_MARGIN_PERCENT,
    val aodDebugShowCanvasFrame: Boolean = false,
    val aodCanvasPaddingPortraitXPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    val aodCanvasPaddingPortraitYPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    val aodCanvasPaddingLandscapeXPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    val aodCanvasPaddingLandscapeYPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    val wakeSignal: Long = 0L,
    val original: String = "",
    val romanized: String = "",
    val translated: String = "",
    val nextLine: String = "",
    /** 下一行歌词的辅助文字(音标/翻译);「显示第二行辅助文字」消费。 */
    val nextLineRomanized: String = "",
    val nextLineTranslated: String = "",
    /** 文档级默认组装值(按文档级 metadataParts/metadataSeparators 组装),降级/旧消费方兜底。 */
    val metadata: String = "",
    /** 原始歌名/歌手/专辑:随快照下发,由各渲染面按自己的「歌曲信息内容」重新组装(per-surface)。 */
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /**
     * 大元数据引导态:为真时歌词主行位置显示的应是各面按本面「歌曲信息内容」组装的歌曲信息,
     * 由渲染面替换 [original] 的占位符(见 root/aod/LyricCanvasMapper)。
     */
    val largeMetadata: Boolean = false,
    /** 对唱分侧(元数据身份版);标记识别版见 [alignedRightMarkers],由渲染面按本面开关选用。 */
    val alignedRight: Boolean = false,
    /** 对唱分侧(标记识别版);「识别对唱标记」开启的面取本值,否则取 [alignedRight]。 */
    val alignedRightMarkers: Boolean = false,
    val lineLevelSync: Boolean = false,
    val lineStartMs: Long = 0L,
    val lineEndMs: Long = 0L,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val sampledAtElapsedMs: Long = 0L,
    val speed: Float = 1f,
    val words: List<AodDisplayWord> = emptyList(),
    /**
     * 插件提供的逐字翻译词表(词级译文 + 时间窗,见 `PluginLyricField.TRANSLATION_WORDS`):
     * 翻译辅助行按真实词窗点亮;空表 = 无词级数据,渲染侧回落行窗口合成。
     */
    val translationWords: List<AodDisplayWord> = emptyList(),
    val ruby: List<AodDisplayRuby> = emptyList(),
    val layoutGroups: List<AodDisplayLayoutGroup> = emptyList(),
    /** 对唱并发行(仅息屏消费);null = 无并发行或「显示并发歌词(对唱)」已关。 */
    val duetLine: AodDisplayDuetLine? = null,
    val weight: String = "Medium",
    val textSizeMode: String = "normal",
    val textSizeCustom: Int = 100,
    val secondaryMode: String = "Main only",
    val animationMode: String = "Gradient",
    val glowMode: String = "Off",
    val motionMode: String = "Fluid",
    val lineSyncFillMode: String = "Top to bottom",
    val overflowMode: String = "Wrap",
    val transitionMode: String = "Fade up",
    val fontFamily: String = "noto",
    val alignmentMode: String = "auto",
    val metadataVisible: Boolean = true,
    val metadataAnchor: String = "top",
    val adaptiveSectioning: Boolean = true,
    /** 歌曲图片帧(有界 JPEG,空数组=无封面),经包名/曲目校对后才会非空;见 SongArtworkRepository。 */
    val artworkJpeg: ByteArray = ByteArray(0),
    /** 封面稳定键(包名+曲目身份);空串=无封面,渲染侧按帧缓存解码位图。 */
    val artworkKey: String = ""
)

data class AodDisplayWord(
    val text: String,
    val romanized: String,
    val startMs: Long,
    val endMs: Long,
    val boundaryAfter: Boolean,
    val sourceStart: Int = -1,
    val sourceEnd: Int = -1
)

data class AodDisplayRuby(val start: Int, val end: Int, val reading: String)

data class AodDisplayLayoutGroup(
    val start: Int,
    val end: Int,
    val kind: String,
    val keepTogether: Boolean,
    val confidence: Double
)

fun shouldRepublish(lastPublished: AodDisplayState?, next: AodDisplayState): Boolean {
    if (lastPublished == null) return true
    // 切歌（trackGeneration 变化）时强制重发，让 SystemUI 立即收到新歌的 metadata/标题。
    // 显式声明这条不变量：无论位置增量多大，generation 变化都必须产生一次快照。
    if (lastPublished.trackGeneration != next.trackGeneration) return true
    if (lastPublished.copy(positionMs = 0L, sampledAtElapsedMs = 0L) !=
        next.copy(positionMs = 0L, sampledAtElapsedMs = 0L)
    ) return true
    val expectedPosition = lastPublished.positionMs +
        ((next.sampledAtElapsedMs - lastPublished.sampledAtElapsedMs) * lastPublished.speed).toLong()
    return abs(next.positionMs - expectedPosition) > 750L
}

/**
 * 对唱并发行(仅息屏消费):主行播放窗口与另一唱词行重叠 ≥1s 时同时显示的那行
 * (见 [AodDisplayState.duetLine])。v1 不携带 ruby/layoutGroups(三个接入源中只有
 * Spicy 可产,normalize 侧还要为文本 trim 重算区间)。
 */
data class AodDisplayDuetLine(
    val text: String,
    val romanized: String = "",
    val translated: String = "",
    /** 对唱分侧(元数据身份版);标记识别版见 [alignedRightMarkers]。 */
    val alignedRight: Boolean = false,
    /** 对唱分侧(标记识别版);由渲染面按本面「识别对唱标记」开关选用。 */
    val alignedRightMarkers: Boolean = false,
    /** 和声行(插件行 role=BG 的 x-bg 回声):渲染面走辅助行车道,不与对唱同款。 */
    val harmony: Boolean = false,
    val lineStartMs: Long = 0L,
    val lineEndMs: Long = 0L,
    val words: List<AodDisplayWord> = emptyList()
)

object AodStateBridge {
    private val callbacks = RemoteCallbackList<IAodLyricCallback>()
    private var currentRevision = 0L
    private var latestMessage: AodStateWireMessage = AodStateWireMessage.Hidden(
        revision = 0L,
        userId = 0,
        updatedAtElapsedMs = SystemClock.elapsedRealtime(),
        keepAlive = false,
        wakeSignal = 0L
    )
    private var latest = AodStateWireBundleCodec.toBundle(
        requireNotNull(AodStateWireCodec.encode(latestMessage))
    )
    private var latestConfiguration: Bundle? = null
    private var lastConfigurationHash = ""
    private var lastPublished: AodDisplayState? = null
    private var lastFullPublishAtElapsedMs = Long.MIN_VALUE

    @Synchronized
    fun register(callback: IAodLyricCallback) {
        callbacks.register(callback)
        val cached = latestConfiguration
        // 新订阅端接入时的补推路径:缓存为空则完全静默跳过。hook 进程重启后配置迟迟
        // 不生效的场景,要靠这条日志区分「补推了」还是「根本没得补推」。
        AppLog.w(
            TAG,
            "Callback registered; cached configuration " +
                (if (cached != null) "replayed" else "absent")
        )
        cached?.let { configuration ->
            try {
                callback.onConfiguration(Bundle(configuration))
            } catch (error: Exception) {
                AppLog.w(TAG, "Initial configuration delivery failed", error)
            }
        }
        val replayEnvelope = AodStateWireCodec.encode(latestMessage) ?: return
        try {
            callback.onState(AodStateWireBundleCodec.toBundle(replayEnvelope))
        } catch (error: Exception) {
            AppLog.w(TAG, "Initial state delivery failed", error)
        }
    }

    @Synchronized
    fun unregister(callback: IAodLyricCallback) {
        callbacks.unregister(callback)
    }

    @Synchronized
    fun hasSystemUiCallback(): Boolean = callbacks.registeredCallbackCount > 0

    @Synchronized
    fun publish(state: AodDisplayState) {
        val publishedState = normalizeAodDisplayState(state)
        if (!shouldRepublish(lastPublished, publishedState)) return
        lastPublished = publishedState
        currentRevision++
        val publication = encodeNormalizedAodStatePublication(
            state = publishedState,
            revision = currentRevision,
            updatedAtElapsedMs = SystemClock.elapsedRealtime()
        )
        latestMessage = publication.message
        latest = AodStateWireBundleCodec.toBundle(publication.envelope)
        lastFullPublishAtElapsedMs = publication.message.updatedAtElapsedMs
        broadcast(latest)
    }

    @Synchronized
    fun publishConfiguration(
        configuration: CompiledCustomization,
        userId: Int,
        experimentalMode: Boolean = false
    ) {
        if (configuration.hash == lastConfigurationHash) return
        val animationSummary = configuration.profiles.entries.joinToString(",") { (surface, profile) ->
            "$surface=${profile.animation}"
        }
        val bundle = CompiledCustomizationBundleCodec.toBundle(configuration, userId, experimentalMode)
        lastConfigurationHash = configuration.hash
        latestConfiguration = bundle
        val count = callbacks.beginBroadcast()
        AppLog.w(
            TAG,
            "Configuration publish hash=${configuration.hash.take(8)} userId=$userId " +
                "callbacks=$count anim=[$animationSummary]"
        )
        try {
            for (index in 0 until count) {
                try {
                    callbacks.getBroadcastItem(index).onConfiguration(Bundle(bundle))
                } catch (error: Exception) {
                    AppLog.w(TAG, "Configuration delivery failed", error)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    @Synchronized
    fun refreshVisibleState() {
        val current = latestMessage as? AodStateWireMessage.Snapshot ?: return
        val updatedAt = SystemClock.elapsedRealtime()
        val refreshed = refreshAodStateWireSnapshot(current, updatedAt)
        latestMessage = refreshed
        val republish = shouldRepublishFullSnapshot(updatedAt, lastFullPublishAtElapsedMs)
        val message: AodStateWireMessage = if (republish) refreshed else AodStateWireMessage.KeepAlive(
            revision = current.revision,
            userId = current.userId,
            updatedAtElapsedMs = updatedAt,
            keepAlive = current.keepAlive,
            wakeSignal = current.wakeSignal,
            playbackActive = current.playbackActive,
            pauseRetentionEligible = current.pauseRetentionEligible
        )
        val envelope = AodStateWireCodec.encode(message) ?: return
        if (republish) lastFullPublishAtElapsedMs = updatedAt
        broadcast(AodStateWireBundleCodec.toBundle(envelope))
    }

    @Synchronized
    fun hasVisibleState(): Boolean = latestMessage is AodStateWireMessage.Snapshot

    /**
     * 歌曲图片出帧后的补挂:封面是异步取图,首次发布快照时可能还没有帧。帧到达后若仍是
     * 同一曲目([trackGeneration] 匹配),把帧挂到已发布的快照上重播一次;曲目已切换则
     * 丢弃(新曲目的封面由新发布流程携带)。保持 revision 不变、只推进 updatedAt,
     * 消费侧按「同 revision 更新鲜」放行(见 SystemUiLyricProjection 的新鲜度门)。
     */
    @Synchronized
    fun publishArtwork(trackGeneration: Long, artworkJpeg: ByteArray, artworkKey: String) {
        if (trackGeneration <= 0L || artworkJpeg.isEmpty()) return
        if (artworkJpeg.size > AodStateWireLimits.MAX_ARTWORK_BYTES) return
        val key = artworkKey.normalizeAodWireText(AodStateWireLimits.MAX_ARTWORK_KEY_CHARS)
        if (key.isEmpty()) return
        val current = latestMessage as? AodStateWireMessage.Snapshot ?: return
        if (current.value.trackGeneration != trackGeneration) return
        if (current.value.artworkKey == key) return
        val updated = current.copy(
            updatedAtElapsedMs = SystemClock.elapsedRealtime(),
            value = current.value.copy(artworkJpeg = ArtworkJpeg(artworkJpeg), artworkKey = key)
        )
        val envelope = AodStateWireCodec.encode(updated) ?: return
        latestMessage = updated
        latest = AodStateWireBundleCodec.toBundle(envelope)
        lastPublished = lastPublished?.copy(artworkJpeg = artworkJpeg, artworkKey = key)
        broadcast(latest)
    }

    private fun broadcast(state: Bundle) {
        val count = callbacks.beginBroadcast()
        try {
            for (index in 0 until count) {
                try {
                    callbacks.getBroadcastItem(index).onState(Bundle(state))
                } catch (error: Exception) {
                    AppLog.w(TAG, "State delivery failed", error)
                }
            }
        } finally {
            callbacks.finishBroadcast()
        }
    }

    private const val TAG = "AodStateBridge"
}

internal data class AodStatePublication(
    val message: AodStateWireMessage,
    val envelope: AodStateWireEnvelope
)

/**
 * 心跳是否应改为携带完整快照。
 *
 * KeepAlive 只能续期消费端仍持有的投影:已过期的投影没有可匹配的 revision,之后的所有心跳
 * 都会被拒绝(SystemUiLyricProjection 对无快照的心跳直接丢弃),歌词一直黑屏直到恰好切词触发
 * 一次全量发布。按慢节奏定期重发完整快照,把恢复时间收敛到有界窗口内,而不用为每一拍支付
 * 全量 payload(上游 cc1f62f)。
 */
internal fun shouldRepublishFullSnapshot(
    nowElapsedMs: Long,
    lastFullPublishAtElapsedMs: Long,
    intervalMs: Long = FULL_SNAPSHOT_REPUBLISH_MS
): Boolean = lastFullPublishAtElapsedMs == Long.MIN_VALUE ||
    nowElapsedMs - lastFullPublishAtElapsedMs >= intervalMs

/** 相对消费端新鲜度窗口的三拍余量。 */
internal const val FULL_SNAPSHOT_REPUBLISH_MS = 4_500L

internal fun refreshAodStateWireSnapshot(
    snapshot: AodStateWireMessage.Snapshot,
    updatedAtElapsedMs: Long
): AodStateWireMessage.Snapshot = snapshot.copy(
    updatedAtElapsedMs = updatedAtElapsedMs.coerceAtLeast(snapshot.updatedAtElapsedMs)
)

private const val AOD_STATE_BRIDGE_TAG = "AodStateBridge"

internal fun encodeNormalizedAodStatePublication(
    state: AodDisplayState,
    revision: Long,
    updatedAtElapsedMs: Long
): AodStatePublication {
    val intendedMessage = state.toWireMessage(revision, updatedAtElapsedMs)
    val intendedEnvelope = AodStateWireCodec.encode(intendedMessage)
    val deliveredMessage = if (intendedEnvelope == null) {
        AppLog.w(
            AOD_STATE_BRIDGE_TAG,
            "AOD 状态编码失败，回退为 Hidden: visible=${state.visible} " +
                "durationMs=${state.durationMs} lineStartMs=${state.lineStartMs} " +
                "lineEndMs=${state.lineEndMs} positionMs=${state.positionMs} " +
                "words=${state.words.size} originalLength=${state.original.length} revision=$revision"
        )
        AodStateWireMessage.Hidden(
            revision = revision,
            userId = state.userId,
            updatedAtElapsedMs = updatedAtElapsedMs,
            keepAlive = false,
            wakeSignal = state.wakeSignal,
            playbackActive = state.playbackActive,
            pauseRetentionEligible = state.pauseRetentionEligible
        )
    } else {
        intendedMessage
    }
    val envelope = intendedEnvelope ?: requireNotNull(AodStateWireCodec.encode(deliveredMessage))
    return AodStatePublication(deliveredMessage, envelope)
}

internal fun normalizeAodDisplayState(state: AodDisplayState): AodDisplayState {
    val original = state.original.normalizeAodWireText(AodStateWireLimits.MAX_LYRIC_CHARS)
    val trimOffset = state.original.length - state.original.trimStart().length
    val romanized = state.romanized.normalizeAodWireText(AodStateWireLimits.MAX_LYRIC_CHARS)
    val translated = state.translated.normalizeAodWireText(AodStateWireLimits.MAX_LYRIC_CHARS)
    val nextLine = state.nextLine.normalizeAodWireText(AodStateWireLimits.MAX_LYRIC_CHARS)
    val nextLineRomanized = state.nextLineRomanized.normalizeAodWireText(
        AodStateWireLimits.MAX_LYRIC_CHARS
    )
    val nextLineTranslated = state.nextLineTranslated.normalizeAodWireText(
        AodStateWireLimits.MAX_LYRIC_CHARS
    )
    val metadata = state.metadata.normalizeAodWireText(AodStateWireLimits.MAX_METADATA_CHARS)
    // 原始歌名/歌手/专辑:随快照下发给渲染面按本面「歌曲信息内容」重新组装。
    val title = state.title.normalizeAodWireText(AodStateWireLimits.MAX_METADATA_CHARS)
    val artist = state.artist.normalizeAodWireText(AodStateWireLimits.MAX_METADATA_CHARS)
    val album = state.album.normalizeAodWireText(AodStateWireLimits.MAX_METADATA_CHARS)
    val effectiveVisible = state.visible && original.isNotEmpty()
    val baseDuration = state.durationMs.coerceIn(0L, AodStateWireLimits.MAX_MEDIA_DURATION_MS)
    val duration = if (effectiveVisible && baseDuration <= 0L) {
        maxOf(
            state.lineStartMs,
            state.lineEndMs,
            state.positionMs,
            state.words.maxOfOrNull { it.endMs } ?: 0L,
            1L
        ).coerceIn(1L, AodStateWireLimits.MAX_MEDIA_DURATION_MS)
    } else {
        baseDuration
    }
    val lineStart = state.lineStartMs.coerceAtLeast(0L).let {
        if (duration > 0L) it.coerceAtMost(duration) else it
    }
    val lineEnd = state.lineEndMs.coerceAtLeast(lineStart).let {
        if (duration > 0L) it.coerceAtMost(duration) else it
    }
    val position = state.positionMs.coerceAtLeast(0L).let {
        if (duration > 0L) it.coerceAtMost(duration) else it
    }
    val words = state.words.asSequence()
        .take(AodStateWireLimits.MAX_WORDS)
        .map { word ->
            val range = trimAodSourceRange(
                sourceTextLength = state.original.length,
                trimmedTextLength = original.length,
                trimOffset = trimOffset,
                start = word.sourceStart,
                end = word.sourceEnd
            )
            val startMs = word.startMs.coerceAtLeast(0L).let {
                if (duration > 0L) it.coerceAtMost(duration) else it
            }
            word.copy(
                text = word.text.sanitizeUtf16().takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS),
                romanized = word.romanized.sanitizeUtf16()
                    .takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS),
                startMs = startMs,
                endMs = word.endMs.coerceAtLeast(startMs).let {
                    if (duration > 0L) it.coerceAtMost(duration) else it
                },
                sourceStart = range.first,
                sourceEnd = range.second
            )
        }
        .toList()
    // 插件逐字翻译词表:与 words 同一道钳制(条数上限/时间钳到歌长/文本净化);片段不指向
    // 原文,source 范围恒 -1(与并发行词表同口径);空文本片段整条丢弃(不贡献译文文本)。
    val translationWords = state.translationWords.asSequence()
        .take(AodStateWireLimits.MAX_WORDS)
        .mapNotNull { word ->
            val text = word.text.sanitizeUtf16().takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS)
            if (text.isEmpty()) return@mapNotNull null
            val startMs = word.startMs.coerceAtLeast(0L).let {
                if (duration > 0L) it.coerceAtMost(duration) else it
            }
            word.copy(
                text = text,
                romanized = "",
                startMs = startMs,
                endMs = word.endMs.coerceAtLeast(startMs).let {
                    if (duration > 0L) it.coerceAtMost(duration) else it
                },
                boundaryAfter = true,
                sourceStart = -1,
                sourceEnd = -1
            )
        }
        .toList()
    val ruby = state.ruby.asSequence()
        .take(AodStateWireLimits.MAX_RUBY)
        .mapNotNull { item ->
            val start = (item.start - trimOffset).coerceAtLeast(0)
            val end = (item.end - trimOffset).coerceAtMost(original.length)
            if (end <= start) null else item.copy(
                start = start,
                end = end,
                reading = item.reading.sanitizeUtf16().takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS)
            )
        }
        .toList()
    val layoutGroups = state.layoutGroups.asSequence()
        .take(AodStateWireLimits.MAX_LAYOUT_GROUPS)
        .mapNotNull { group ->
            val start = (group.start - trimOffset).coerceAtLeast(0)
            val end = (group.end - trimOffset).coerceAtMost(original.length)
            // 非有限置信度（NaN/±Inf）直接丢弃:coerceIn 对 NaN 不生效,
            // 而 isValidSnapshot 会因此拒收整包(布局组是可选增强,不该连累行文本)。
            val confidence = group.confidence.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
            if (end <= start || confidence == null) null else group.copy(
                start = start,
                end = end,
                kind = group.kind.sanitizeUtf16().takeUtf16Prefix(AodStateWireLimits.MAX_METADATA_CHARS),
                confidence = confidence
            )
        }
.toList()
    // 对唱并发行:文本钳制、时间窗/词级时间钳到歌长、词级 source 范围弃用(画布并发行
    // 不走逐字扫光路径);ruby/layoutGroups v1 不携带。条数超限/文本为空整条丢弃
    // (并发行是可选增强,不应连累主行发布)。
    HookLogger.iThrottled("duet-bridge", 5_000L, "AodStateBridge") {
        val d = state.duetLine
        "Duet bridge: in=${d?.text?.take(16)} inWin=${d?.lineStartMs}..${d?.lineEndMs} " +
            "duration=$duration out=${d != null && d.text.isNotBlank()}"
    }
    val duetLine = state.duetLine?.let { line ->
        val duetText = line.text.normalizeAodWireText(AodStateWireLimits.MAX_LYRIC_CHARS)
        if (duetText.isEmpty()) {
            null
        } else {
            AodDisplayDuetLine(
                text = duetText,
                romanized = line.romanized.normalizeAodWireText(AodStateWireLimits.MAX_LYRIC_CHARS),
                translated = line.translated.normalizeAodWireText(AodStateWireLimits.MAX_LYRIC_CHARS),
                alignedRight = line.alignedRight,
                alignedRightMarkers = line.alignedRightMarkers,
                harmony = line.harmony,
                lineStartMs = line.lineStartMs.coerceAtLeast(0L).let {
                    if (duration > 0L) it.coerceAtMost(duration) else it
                },
                lineEndMs = line.lineEndMs.coerceAtLeast(line.lineStartMs.coerceAtLeast(0L)).let {
                    if (duration > 0L) it.coerceAtMost(duration) else it
                },
                words = line.words.asSequence().take(AodStateWireLimits.MAX_WORDS).map { word ->
                    val wordStart = word.startMs.coerceAtLeast(0L).let {
                        if (duration > 0L) it.coerceAtMost(duration) else it
                    }
                    word.copy(
                        text = word.text.sanitizeUtf16().takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS),
                        romanized = word.romanized.sanitizeUtf16()
                            .takeUtf16Prefix(AodStateWireLimits.MAX_LYRIC_CHARS),
                        startMs = wordStart,
                        endMs = word.endMs.coerceAtLeast(wordStart).let {
                            if (duration > 0L) it.coerceAtMost(duration) else it
                        },
                        sourceStart = -1,
                        sourceEnd = -1
                    )
                }.toList()
            )
        }
    }
    val fitted = fitAodEnhancementBudget(
        baseTexts = listOf(
            original,
            romanized,
            translated,
            nextLine,
            nextLineRomanized,
            nextLineTranslated,
            metadata,
            title,
            artist,
            album
        ),
        styleTexts = styleTokens(state),
        words = words,
        translationWords = translationWords,
        ruby = ruby,
        groups = layoutGroups
    )
    // 歌曲图片:超限/半截帧整帧丢弃(fail-closed:宁可不显示,不显示超界或残缺帧)。
    val artworkJpeg = state.artworkJpeg.takeIf {
        it.isNotEmpty() && it.size <= AodStateWireLimits.MAX_ARTWORK_BYTES
    } ?: ByteArray(0)
    val artworkKey = if (artworkJpeg.isEmpty()) {
        ""
    } else {
        state.artworkKey.normalizeAodWireText(AodStateWireLimits.MAX_ARTWORK_KEY_CHARS)
    }
    return state.copy(
        visible = effectiveVisible,
        pauseRetentionEligible = state.pauseRetentionEligible &&
            !state.visible && !state.playbackActive,
        userId = state.userId.coerceAtLeast(0),
        trackGeneration = state.trackGeneration.coerceAtLeast(0L),
        burnInPattern = normalizeAodBurnInPattern(state.burnInPattern),
        burnInIntervalMs = normalizeAodBurnInInterval(state.burnInIntervalMs),
        aodRotationMode = normalizeAodRotationMode(state.aodRotationMode),
        aodRotationSettleMs = normalizeAodRotationSettleMs(state.aodRotationSettleMs),
        aodCanvasAnchorLandscape = normalizeAodCanvasAnchor(state.aodCanvasAnchorLandscape),
        aodLandscapeTextScale = normalizeAodLandscapeTextScale(state.aodLandscapeTextScale),
        aodLandscapeFullscreenSafeMarginPercent = normalizeAodFullscreenSafeMarginPercent(
            state.aodLandscapeFullscreenSafeMarginPercent
        ),
        aodCanvasPaddingPortraitXPercent = normalizeAodCanvasPaddingPercent(
            state.aodCanvasPaddingPortraitXPercent
        ),
        aodCanvasPaddingPortraitYPercent = normalizeAodCanvasPaddingPercent(
            state.aodCanvasPaddingPortraitYPercent
        ),
        aodCanvasPaddingLandscapeXPercent = normalizeAodCanvasPaddingPercent(
            state.aodCanvasPaddingLandscapeXPercent
        ),
        aodCanvasPaddingLandscapeYPercent = normalizeAodCanvasPaddingPercent(
            state.aodCanvasPaddingLandscapeYPercent
        ),
        original = original,
        romanized = romanized,
        translated = translated,
        nextLine = nextLine,
        nextLineRomanized = nextLineRomanized,
        nextLineTranslated = nextLineTranslated,
        metadata = metadata,
        title = title,
        artist = artist,
        album = album,
        largeMetadata = state.largeMetadata,
        alignedRightMarkers = state.alignedRightMarkers,
        lineStartMs = lineStart,
        lineEndMs = lineEnd,
        durationMs = duration,
        positionMs = position,
        sampledAtElapsedMs = state.sampledAtElapsedMs.coerceAtLeast(0L),
        speed = state.speed.takeIf {
            it.isFinite() && it in 0f..AodStateWireLimits.MAX_PLAYBACK_SPEED
        } ?: 1f,
        words = fitted.words,
        translationWords = fitted.translationWords,
        ruby = fitted.ruby,
        layoutGroups = fitted.groups,
        duetLine = duetLine,
        weight = normalizeAodWeight(state.weight),
        textSizeMode = normalizeAodTextSize(state.textSizeMode),
        textSizeCustom = state.textSizeCustom.coerceIn(0, 500),
        secondaryMode = normalizeAodSecondary(state.secondaryMode),
        animationMode = normalizeAodAnimation(state.animationMode),
        glowMode = normalizeAodGlow(state.glowMode),
        motionMode = "Fluid",
        lineSyncFillMode = normalizeAodLineSyncFill(state.lineSyncFillMode.trim()),
        overflowMode = normalizeAodOverflow(state.overflowMode),
        transitionMode = normalizeAodTransition(state.transitionMode.trim()),
        fontFamily = normalizeAodFontFamily(state.fontFamily),
        alignmentMode = normalizeAodAlignment(state.alignmentMode),
        metadataAnchor = normalizeAodMetadataAnchor(state.metadataAnchor),
        artworkJpeg = artworkJpeg,
        artworkKey = artworkKey
    )
}

private fun AodDisplayState.toWireMessage(
    revision: Long,
    updatedAtElapsedMs: Long
): AodStateWireMessage = if (!visible) {
    AodStateWireMessage.Hidden(
        revision = revision,
        userId = userId,
        updatedAtElapsedMs = updatedAtElapsedMs,
        keepAlive = keepAlive,
        wakeSignal = wakeSignal,
        playbackActive = playbackActive,
        pauseRetentionEligible = pauseRetentionEligible
    )
} else {
    AodStateWireMessage.Snapshot(
        revision = revision,
        userId = userId,
        updatedAtElapsedMs = updatedAtElapsedMs,
        keepAlive = keepAlive,
        wakeSignal = wakeSignal,
        playbackActive = playbackActive,
        pauseRetentionEligible = pauseRetentionEligible,
        value = AodStateWireSnapshot(
            trackGeneration = trackGeneration,
            aodEnabled = aodEnabled,
            lockscreenEnabled = lockscreenEnabled,
            positionFollowingEnabled = positionFollowingEnabled,
            burnInPattern = burnInPattern,
            burnInIntervalMs = burnInIntervalMs,
            suppressStockAodContent = suppressStockAodContent,
            aodRotateWithDevice = aodRotateWithDevice,
            aodRotationMode = aodRotationMode,
            aodRotationSettleMs = aodRotationSettleMs,
            aodCanvasAnchorLandscape = aodCanvasAnchorLandscape,
            aodLandscapeTextScale = aodLandscapeTextScale,
            aodLandscapeHideStock = aodLandscapeHideStock,
            aodLandscapeFullscreen = aodLandscapeFullscreen,
            aodLandscapeFullscreenSafeMarginPercent = aodLandscapeFullscreenSafeMarginPercent,
            aodDebugShowCanvasFrame = aodDebugShowCanvasFrame,
            aodCanvasPaddingPortraitXPercent = aodCanvasPaddingPortraitXPercent,
            aodCanvasPaddingPortraitYPercent = aodCanvasPaddingPortraitYPercent,
            aodCanvasPaddingLandscapeXPercent = aodCanvasPaddingLandscapeXPercent,
            aodCanvasPaddingLandscapeYPercent = aodCanvasPaddingLandscapeYPercent,
            original = original,
            romanized = romanized,
            translated = translated,
            nextLine = nextLine,
            nextLineRomanized = nextLineRomanized,
            nextLineTranslated = nextLineTranslated,
            metadata = metadata,
            title = title,
            artist = artist,
            album = album,
            largeMetadata = largeMetadata,
            alignedRight = alignedRight,
            alignedRightMarkers = alignedRightMarkers,
            lineLevelSync = lineLevelSync,
            lineStartMs = lineStartMs,
            lineEndMs = lineEndMs,
            durationMs = durationMs,
            positionMs = positionMs,
            sampledAtElapsedMs = sampledAtElapsedMs,
            speed = speed,
            words = words.map { word ->
                AodStateWireWord(
                    text = word.text,
                    romanized = word.romanized,
                    startMs = word.startMs,
                    endMs = word.endMs,
                    boundaryAfter = word.boundaryAfter,
                    sourceStart = word.sourceStart,
                    sourceEnd = word.sourceEnd
                )
            },
            translationWords = translationWords.map { word ->
                AodStateWireWord(
                    text = word.text,
                    romanized = word.romanized,
                    startMs = word.startMs,
                    endMs = word.endMs,
                    boundaryAfter = word.boundaryAfter,
                    sourceStart = word.sourceStart,
                    sourceEnd = word.sourceEnd
                )
            },
            ruby = ruby.map { item ->
                AodStateWireRuby(item.start, item.end, item.reading)
            },
            layoutGroups = layoutGroups.map { group ->
                AodStateWireLayoutGroup(
                    start = group.start,
                    end = group.end,
                    kind = group.kind,
                    keepTogether = group.keepTogether,
                    confidence = group.confidence
                )
            },
            weight = weight,
            textSizeMode = textSizeMode,
            textSizeCustom = textSizeCustom,
            secondaryMode = secondaryMode,
            animationMode = animationMode,
            glowMode = glowMode,
            motionMode = motionMode,
            lineSyncFillMode = lineSyncFillMode,
            overflowMode = overflowMode,
            transitionMode = transitionMode,
            fontFamily = fontFamily,
            alignmentMode = alignmentMode,
            metadataVisible = metadataVisible,
            metadataAnchor = metadataAnchor,
            adaptiveSectioning = adaptiveSectioning,
            artworkJpeg = ArtworkJpeg(artworkJpeg),
            artworkKey = artworkKey,
            duetLine = duetLine?.let { line ->
                AodStateWireDuetLine(
                    text = line.text,
                    romanized = line.romanized,
                    translated = line.translated,
                    alignedRight = line.alignedRight,
                    alignedRightMarkers = line.alignedRightMarkers,
                    harmony = line.harmony,
                    lineStartMs = line.lineStartMs,
                    lineEndMs = line.lineEndMs,
                    words = line.words.map { word ->
                        AodStateWireWord(
                            text = word.text,
                            romanized = word.romanized,
                            startMs = word.startMs,
                            endMs = word.endMs,
                            boundaryAfter = word.boundaryAfter,
                            sourceStart = word.sourceStart,
                            sourceEnd = word.sourceEnd
                        )
                    }
                )
            }
        )
    )
}

internal fun trimAodSourceRange(
    sourceTextLength: Int,
    trimmedTextLength: Int,
    trimOffset: Int,
    start: Int,
    end: Int
): Pair<Int, Int> {
    if (start == -1 && end == -1) return -1 to -1
    if (start < 0 || start >= end || end > sourceTextLength) return -1 to -1
    val trimmedStart = (start - trimOffset).coerceAtLeast(0)
    val trimmedEnd = (end - trimOffset).coerceAtMost(trimmedTextLength)
    return if (trimmedStart < trimmedEnd) trimmedStart to trimmedEnd else -1 to -1
}

/** 快照携带的样式 token（与 [toWireMessage]/[AodStateWireCodec] 校验侧同一顺序与归一）。 */
private fun styleTokens(state: AodDisplayState): List<String> = listOf(
    normalizeAodBurnInPattern(state.burnInPattern),
    normalizeAodWeight(state.weight),
    normalizeAodTextSize(state.textSizeMode),
    normalizeAodSecondary(state.secondaryMode),
    normalizeAodAnimation(state.animationMode),
    normalizeAodGlow(state.glowMode),
    "Fluid",
    normalizeAodLineSyncFill(state.lineSyncFillMode.trim()),
    normalizeAodOverflow(state.overflowMode),
    normalizeAodTransition(state.transitionMode.trim()),
    normalizeAodFontFamily(state.fontFamily),
    normalizeAodAlignment(state.alignmentMode),
    normalizeAodMetadataAnchor(state.metadataAnchor)
)

/**
 * 增强数据（词/逐字翻译词表/注音/布局组）的聚合文本预算裁剪。
 *
 * [AodStateWireCodec] 的 isValidSnapshot 按 UTF-8 字节总额把关
 * （[AodStateWireLimits.MAX_AGGREGATE_TEXT_UTF8_BYTES]），超限直接拒收整包、静默降级为
 * Hidden——整句歌词会因为词级数据超长而整体消失。这些字段都是可选增强（STYLE_GUIDE:
 * 可选内容按序降级），这里按校验侧同一计数顺序（行文本 → 样式 → 词 → 逐字翻译词 →
 * 注音 → 布局组）只装下最长前缀，行文本永远保留；口径与顺序必须与 isValidSnapshot 的
 * Utf8Budget 一致。
 */
private data class FittedAodEnhancements(
    val words: List<AodDisplayWord>,
    val translationWords: List<AodDisplayWord>,
    val ruby: List<AodDisplayRuby>,
    val groups: List<AodDisplayLayoutGroup>
)

private fun fitAodEnhancementBudget(
    baseTexts: List<String>,
    styleTexts: List<String>,
    words: List<AodDisplayWord>,
    translationWords: List<AodDisplayWord>,
    ruby: List<AodDisplayRuby>,
    groups: List<AodDisplayLayoutGroup>
): FittedAodEnhancements {
    var used = 0
    fun accept(vararg values: String): Boolean {
        var extra = 0
        for (value in values) extra += aodUtf8Bytes(value)
        if (used + extra > AodStateWireLimits.MAX_AGGREGATE_TEXT_UTF8_BYTES) return false
        used += extra
        return true
    }
    // 行文本与样式是内容本身，必装（normalizeAodWireText 已压进各自字符上限）。
    for (text in baseTexts + styleTexts) accept(text)
    val keptWords = words.takeWhile { accept(it.text, it.romanized) }
    val keptTranslationWords = translationWords.takeWhile { accept(it.text, it.romanized) }
    val keptRuby = ruby.takeWhile { accept(it.reading) }
    val keptGroups = groups.takeWhile { accept(it.kind) }
    return FittedAodEnhancements(keptWords, keptTranslationWords, keptRuby, keptGroups)
}
