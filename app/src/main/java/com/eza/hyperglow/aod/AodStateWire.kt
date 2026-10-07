package com.eza.hyperglow.aod

import android.os.Bundle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

internal object AodStateWireLimits {
    const val MAX_LYRIC_CHARS = 500
    const val MAX_METADATA_CHARS = 200
    const val MAX_STYLE_CHARS = 200
    const val MAX_WORDS = 128
    const val MAX_RUBY = 128
    const val MAX_LAYOUT_GROUPS = 256
    const val MAX_AGGREGATE_TEXT_UTF8_BYTES = 48 * 1024
    // 编码体上限 = 文本聚合预算 48KB + 歌曲图片 JPEG 预算 24KB + 结构开销余量。
    // 不含封面时文本上限行为不变;含封面时两账分计,任一超限都拒收整包。
    const val MAX_ENCODED_BODY_BYTES = 96 * 1024
    const val MAX_MEDIA_DURATION_MS = 24L * 60L * 60L * 1000L
    const val MAX_PLAYBACK_SPEED = 4f
    const val MAX_ARTWORK_KEY_CHARS = 64

    /** 歌曲图片 JPEG 上限:与 producer.MAX_ARTWORK_JPEG_BYTES 同源(取图侧编码上限)。 */
    const val MAX_ARTWORK_BYTES = com.eza.hyperglow.producer.MAX_ARTWORK_JPEG_BYTES
}

internal object AodStateWireContract {
    const val PROTOCOL_VERSION = 2
    const val KIND_SNAPSHOT = 1
    const val KIND_HIDDEN = 2
    const val KIND_KEEPALIVE = 3
}

internal data class AodStateWireWord(
    val text: String,
    val romanized: String,
    val startMs: Long,
    val endMs: Long,
    val boundaryAfter: Boolean,
    val sourceStart: Int,
    val sourceEnd: Int
)

internal data class AodStateWireRuby(
    val start: Int,
    val end: Int,
    val reading: String
)

internal data class AodStateWireLayoutGroup(
    val start: Int,
    val end: Int,
    val kind: String,
    val keepTogether: Boolean,
    val confidence: Double
)

/**
 * 歌曲图片 JPEG 帧字节的按内容比较包装:wire 快照依赖 data class 整对象相等做回环/
 * 去重判定,裸 ByteArray 的 equals 只按引用比较会让「同帧不同实例」误判为不同;
 * 包装后相等性与帧内容一致,与实例无关。
 */
internal class ArtworkJpeg(val bytes: ByteArray) {
    val size: Int
        get() = bytes.size

    fun isEmpty(): Boolean = bytes.isEmpty()

    override fun equals(other: Any?): Boolean =
        other is ArtworkJpeg && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ArtworkJpeg(${bytes.size} bytes)"

    companion object {
        val EMPTY = ArtworkJpeg(ByteArray(0))
    }
}

/**
 * 对唱并发行(仅息屏消费);快照携带时其词级条数与主行共享 MAX_WORDS 上限
 * (isValidSnapshot 求和校验),文本走同一聚合 UTF-8 预算。
 */
internal data class AodStateWireDuetLine(
    val text: String,
    val romanized: String = "",
    val translated: String = "",
    val alignedRight: Boolean = false,
    /** 标记识别版分侧(v6 起);渲染面按本面「识别对唱标记」开关选用。 */
    val alignedRightMarkers: Boolean = false,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val words: List<AodStateWireWord> = emptyList()
)

internal data class AodStateWireSnapshot(
    val trackGeneration: Long,
    val aodEnabled: Boolean,
    val lockscreenEnabled: Boolean,
    val positionFollowingEnabled: Boolean,
    val burnInPattern: String,
    val burnInIntervalMs: Long,
    val suppressStockAodContent: Boolean,
    val aodRotateWithDevice: Boolean,
    val aodRotationMode: String,
    val aodRotationSettleMs: Long,
    val aodCanvasAnchorLandscape: Float,
    val aodLandscapeTextScale: Float,
    val aodLandscapeHideStock: Boolean,
    val aodLandscapeFullscreen: Boolean,
    val aodLandscapeFullscreenSafeMarginPercent: Float = DEFAULT_FULLSCREEN_SAFE_MARGIN_PERCENT,
    val aodDebugShowCanvasFrame: Boolean = false,
    val aodCanvasPaddingPortraitXPercent: Float,
    val aodCanvasPaddingPortraitYPercent: Float,
    val aodCanvasPaddingLandscapeXPercent: Float,
    val aodCanvasPaddingLandscapeYPercent: Float,
    val original: String,
    val romanized: String,
    val translated: String,
    val nextLine: String,
    /** 下一行歌词的辅助文字(音标/翻译);「显示第二行辅助文字」消费。 */
    val nextLineRomanized: String = "",
    val nextLineTranslated: String = "",
    val metadata: String,
    /** 原始歌名/歌手/专辑(v6 起):渲染面按本面「歌曲信息内容」重新组装,实现 per-surface 独立。 */
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** 大元数据引导态(v6 起):渲染面用本面组装后的歌曲信息替换 [original] 的占位符。 */
    val largeMetadata: Boolean = false,
    val alignedRight: Boolean,
    /** 标记识别版分侧(v6 起);「识别对唱标记」开启的面取本值,否则取 [alignedRight]。 */
    val alignedRightMarkers: Boolean = false,
    val lineLevelSync: Boolean,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val durationMs: Long,
    val positionMs: Long,
    val sampledAtElapsedMs: Long,
    val speed: Float,
    val words: List<AodStateWireWord>,
    /**
     * 插件提供的逐字翻译词表(v8 起,见 `PluginLyricField.TRANSLATION_WORDS`):翻译辅助行
     * 按真实词窗点亮;空表 = 无词级数据,渲染侧回落行窗口合成。片段不指向原文,
     * source 范围恒 -1(与并发行词表同口径)。
     */
    val translationWords: List<AodStateWireWord> = emptyList(),
    val ruby: List<AodStateWireRuby>,
    val layoutGroups: List<AodStateWireLayoutGroup>,
    val weight: String,
    val textSizeMode: String,
    val textSizeCustom: Int,
    val secondaryMode: String,
    val animationMode: String,
    val glowMode: String,
    val motionMode: String,
    val lineSyncFillMode: String,
    val overflowMode: String,
    val transitionMode: String,
    val fontFamily: String,
    val alignmentMode: String,
    val metadataVisible: Boolean,
    val metadataAnchor: String,
    val adaptiveSectioning: Boolean,
    /**
     * 歌曲图片帧(有界 JPEG,空=无封面):仅经包名/曲目校对的「当前播放的音乐软件」
     * 封面会走到这里(见 SongArtworkRepository),校对不过就是空——不显示,不显示错的图。
     */
    val artworkJpeg: ArtworkJpeg = ArtworkJpeg.EMPTY,
    /** 封面稳定键(包名+曲目身份),渲染侧按帧缓存解码位图;空串=无封面。 */
    val artworkKey: String = "",
    /** 对唱并发行(仅息屏消费);null = 无并发行或「显示并发歌词(对唱)」已关。 */
    val duetLine: AodStateWireDuetLine? = null
)

internal sealed interface AodStateWireMessage {
    val revision: Long
    val userId: Int
    val updatedAtElapsedMs: Long
    val keepAlive: Boolean
    val wakeSignal: Long
    val playbackActive: Boolean
    val pauseRetentionEligible: Boolean

    data class Snapshot(
        override val revision: Long,
        override val userId: Int,
        override val updatedAtElapsedMs: Long,
        override val keepAlive: Boolean,
        override val wakeSignal: Long,
        override val playbackActive: Boolean = false,
        override val pauseRetentionEligible: Boolean = false,
        val value: AodStateWireSnapshot
    ) : AodStateWireMessage

    data class Hidden(
        override val revision: Long,
        override val userId: Int,
        override val updatedAtElapsedMs: Long,
        override val keepAlive: Boolean,
        override val wakeSignal: Long,
        override val playbackActive: Boolean = false,
        override val pauseRetentionEligible: Boolean = false
    ) : AodStateWireMessage

    data class KeepAlive(
        override val revision: Long,
        override val userId: Int,
        override val updatedAtElapsedMs: Long,
        override val keepAlive: Boolean,
        override val wakeSignal: Long,
        override val playbackActive: Boolean = false,
        override val pauseRetentionEligible: Boolean = false
    ) : AodStateWireMessage
}

internal data class AodStateWireEnvelope(
    val protocol: Int,
    val kind: Int,
    val revision: Long,
    val userId: Int,
    val updatedAtElapsedMs: Long,
    val keepAlive: Boolean,
    val wakeSignal: Long,
    val body: ByteArray?,
    val playbackActive: Boolean = false,
    val pauseRetentionEligible: Boolean = false
)

/**
 * Decode 把「值」与「拒绝它的闸门名」一并给出,一个实现同时回答两件事:拆成两套会复制
 * 闸门顺序,而两处顺序一旦漂移比没有原因更糟。
 */
internal sealed interface AodStateWireDecodeOutcome {
    val messageOrNull: AodStateWireMessage?
    val rejectReason: String?

    data class Decoded(val message: AodStateWireMessage) : AodStateWireDecodeOutcome {
        override val messageOrNull: AodStateWireMessage get() = message
        override val rejectReason: String? get() = null
    }

    data class Rejected(override val rejectReason: String) : AodStateWireDecodeOutcome {
        override val messageOrNull: AodStateWireMessage? get() = null
    }
}

internal object AodStateWireCodec {
    fun encode(message: AodStateWireMessage): AodStateWireEnvelope? {
        if (!validEnvelopeScalars(message.revision, message.userId, message.updatedAtElapsedMs)) {
            return null
        }
        return when (message) {
            is AodStateWireMessage.Snapshot -> {
                val body = encodeSnapshotBody(message.value) ?: return null
                AodStateWireEnvelope(
                    protocol = AodStateWireContract.PROTOCOL_VERSION,
                    kind = AodStateWireContract.KIND_SNAPSHOT,
                    revision = message.revision,
                    userId = message.userId,
                    updatedAtElapsedMs = message.updatedAtElapsedMs,
                    keepAlive = message.keepAlive,
                    wakeSignal = message.wakeSignal,
                    body = body,
                    playbackActive = message.playbackActive,
                    pauseRetentionEligible = message.pauseRetentionEligible
                )
            }
            is AodStateWireMessage.Hidden -> AodStateWireEnvelope(
                protocol = AodStateWireContract.PROTOCOL_VERSION,
                kind = AodStateWireContract.KIND_HIDDEN,
                revision = message.revision,
                userId = message.userId,
                updatedAtElapsedMs = message.updatedAtElapsedMs,
                keepAlive = message.keepAlive,
                wakeSignal = message.wakeSignal,
                body = null,
                playbackActive = message.playbackActive,
                pauseRetentionEligible = message.pauseRetentionEligible
            )
            is AodStateWireMessage.KeepAlive -> AodStateWireEnvelope(
                protocol = AodStateWireContract.PROTOCOL_VERSION,
                kind = AodStateWireContract.KIND_KEEPALIVE,
                revision = message.revision,
                userId = message.userId,
                updatedAtElapsedMs = message.updatedAtElapsedMs,
                keepAlive = message.keepAlive,
                wakeSignal = message.wakeSignal,
                body = null,
                playbackActive = message.playbackActive,
                pauseRetentionEligible = message.pauseRetentionEligible
            )
        }
    }

    fun decode(envelope: AodStateWireEnvelope): AodStateWireMessage? =
        decodeOutcome(envelope).messageOrNull

    /**
     * 具名被拒闸门。裸 null 让「app 与 hook 版本错位」和「载荷损坏」无法区分:前者常见于
     * 应用升级后 hook 进程尚未重启,会自愈,根本不需要诊断;后者才需要。
     */
    fun decodeRejectReason(envelope: AodStateWireEnvelope): String? =
        decodeOutcome(envelope).rejectReason

    private fun decodeOutcome(envelope: AodStateWireEnvelope): AodStateWireDecodeOutcome {
        if (envelope.protocol != AodStateWireContract.PROTOCOL_VERSION) {
            return AodStateWireDecodeOutcome.Rejected("protocol_mismatch")
        }
        if (!validEnvelopeScalars(
                envelope.revision,
                envelope.userId,
                envelope.updatedAtElapsedMs
            )
        ) {
            return AodStateWireDecodeOutcome.Rejected("invalid_scalars")
        }
        return when (envelope.kind) {
            AodStateWireContract.KIND_SNAPSHOT -> {
                val body = envelope.body
                    ?: return AodStateWireDecodeOutcome.Rejected("missing_body")
                val snapshot = decodeSnapshotBody(body)
                    ?: return AodStateWireDecodeOutcome.Rejected("undecodable_body")
                AodStateWireDecodeOutcome.Decoded(
                    AodStateWireMessage.Snapshot(
                        revision = envelope.revision,
                        userId = envelope.userId,
                        updatedAtElapsedMs = envelope.updatedAtElapsedMs,
                        keepAlive = envelope.keepAlive,
                        wakeSignal = envelope.wakeSignal,
                        playbackActive = envelope.playbackActive,
                        pauseRetentionEligible = envelope.pauseRetentionEligible,
                        value = snapshot
                    )
                )
            }
            AodStateWireContract.KIND_HIDDEN -> AodStateWireDecodeOutcome.Decoded(
                AodStateWireMessage.Hidden(
                    revision = envelope.revision,
                    userId = envelope.userId,
                    updatedAtElapsedMs = envelope.updatedAtElapsedMs,
                    keepAlive = envelope.keepAlive,
                    wakeSignal = envelope.wakeSignal,
                    playbackActive = envelope.playbackActive,
                    pauseRetentionEligible = envelope.pauseRetentionEligible
                )
            )
            AodStateWireContract.KIND_KEEPALIVE -> AodStateWireDecodeOutcome.Decoded(
                AodStateWireMessage.KeepAlive(
                    revision = envelope.revision,
                    userId = envelope.userId,
                    updatedAtElapsedMs = envelope.updatedAtElapsedMs,
                    keepAlive = envelope.keepAlive,
                    wakeSignal = envelope.wakeSignal,
                    playbackActive = envelope.playbackActive,
                    pauseRetentionEligible = envelope.pauseRetentionEligible
                )
            )
            else -> AodStateWireDecodeOutcome.Rejected("unknown_kind")
        }
    }

    private fun encodeSnapshotBody(snapshot: AodStateWireSnapshot): ByteArray? {
        if (!isValidSnapshot(snapshot)) return null
        return try {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeInt(BODY_MAGIC)
                output.writeInt(BODY_VERSION)
                output.writeInt(snapshot.words.size)
                output.writeInt(snapshot.ruby.size)
                output.writeInt(snapshot.layoutGroups.size)
                output.writeLong(snapshot.trackGeneration)
                output.writeStrictBoolean(snapshot.aodEnabled)
                output.writeStrictBoolean(snapshot.lockscreenEnabled)
                output.writeStrictBoolean(snapshot.positionFollowingEnabled)
                output.writeBoundedString(snapshot.burnInPattern)
                output.writeLong(snapshot.burnInIntervalMs)
                output.writeStrictBoolean(snapshot.suppressStockAodContent)
                output.writeStrictBoolean(snapshot.aodRotateWithDevice)
                output.writeBoundedString(snapshot.aodRotationMode)
                output.writeLong(snapshot.aodRotationSettleMs)
                output.writeFloat(snapshot.aodCanvasAnchorLandscape)
                output.writeFloat(snapshot.aodLandscapeTextScale)
                output.writeStrictBoolean(snapshot.aodLandscapeHideStock)
                output.writeStrictBoolean(snapshot.aodLandscapeFullscreen)
                output.writeFloat(snapshot.aodLandscapeFullscreenSafeMarginPercent)
                output.writeStrictBoolean(snapshot.aodDebugShowCanvasFrame)
                output.writeFloat(snapshot.aodCanvasPaddingPortraitXPercent)
                output.writeFloat(snapshot.aodCanvasPaddingPortraitYPercent)
                output.writeFloat(snapshot.aodCanvasPaddingLandscapeXPercent)
                output.writeFloat(snapshot.aodCanvasPaddingLandscapeYPercent)
                output.writeBoundedString(snapshot.original)
                output.writeBoundedString(snapshot.romanized)
                output.writeBoundedString(snapshot.translated)
                output.writeBoundedString(snapshot.nextLine)
                output.writeBoundedString(snapshot.nextLineRomanized)
                output.writeBoundedString(snapshot.nextLineTranslated)
                output.writeBoundedString(snapshot.metadata)
                // v7:原始歌名/歌手/专辑 + 大元数据引导态 + 标记识别版分侧(per-surface 内容链路)。
                output.writeBoundedString(snapshot.title)
                output.writeBoundedString(snapshot.artist)
                output.writeBoundedString(snapshot.album)
                output.writeStrictBoolean(snapshot.largeMetadata)
                output.writeStrictBoolean(snapshot.alignedRight)
                output.writeStrictBoolean(snapshot.alignedRightMarkers)
                output.writeStrictBoolean(snapshot.lineLevelSync)
                output.writeLong(snapshot.lineStartMs)
                output.writeLong(snapshot.lineEndMs)
                output.writeLong(snapshot.durationMs)
                output.writeLong(snapshot.positionMs)
                output.writeLong(snapshot.sampledAtElapsedMs)
                output.writeFloat(snapshot.speed)
                output.writeBoundedString(snapshot.weight)
                output.writeBoundedString(snapshot.textSizeMode)
                output.writeInt(snapshot.textSizeCustom)
                output.writeBoundedString(snapshot.secondaryMode)
                output.writeBoundedString(snapshot.animationMode)
                output.writeBoundedString(snapshot.glowMode)
                output.writeBoundedString(snapshot.motionMode)
                output.writeBoundedString(snapshot.lineSyncFillMode)
                output.writeBoundedString(snapshot.overflowMode)
                output.writeBoundedString(snapshot.transitionMode)
                output.writeBoundedString(snapshot.fontFamily)
                output.writeBoundedString(snapshot.alignmentMode)
                output.writeStrictBoolean(snapshot.metadataVisible)
                output.writeBoundedString(snapshot.metadataAnchor)
                output.writeStrictBoolean(snapshot.adaptiveSectioning)
                snapshot.words.forEach { word ->
                    output.writeBoundedString(word.text)
                    output.writeBoundedString(word.romanized)
                    output.writeLong(word.startMs)
                    output.writeLong(word.endMs)
                    output.writeStrictBoolean(word.boundaryAfter)
                    output.writeInt(word.sourceStart)
                    output.writeInt(word.sourceEnd)
                }
                // v8:插件逐字翻译词表(条数 + 载荷);与主行词表同一上限与聚合文本预算。
                output.writeInt(snapshot.translationWords.size)
                snapshot.translationWords.forEach { word ->
                    output.writeBoundedString(word.text)
                    output.writeBoundedString(word.romanized)
                    output.writeLong(word.startMs)
                    output.writeLong(word.endMs)
                    output.writeStrictBoolean(word.boundaryAfter)
                    output.writeInt(word.sourceStart)
                    output.writeInt(word.sourceEnd)
                }
                snapshot.ruby.forEach { ruby ->
                    output.writeInt(ruby.start)
                    output.writeInt(ruby.end)
                    output.writeBoundedString(ruby.reading)
                }
                snapshot.layoutGroups.forEach { group ->
                    output.writeInt(group.start)
                    output.writeInt(group.end)
                    output.writeBoundedString(group.kind)
                    output.writeStrictBoolean(group.keepTogether)
                    output.writeDouble(group.confidence)
                }
                output.writeInt(snapshot.artworkJpeg.size)
                output.write(snapshot.artworkJpeg.bytes)
                output.writeBoundedString(snapshot.artworkKey)
                // v4:对唱并发行(存在位 + 载荷);文本走聚合文本预算(isValidSnapshot 校验)。
                val duet = snapshot.duetLine
                output.writeStrictBoolean(duet != null)
                duet?.let { line ->
                    output.writeInt(line.words.size)
                    output.writeBoundedString(line.text)
                    output.writeBoundedString(line.romanized)
                    output.writeBoundedString(line.translated)
                    output.writeStrictBoolean(line.alignedRight)
                    output.writeStrictBoolean(line.alignedRightMarkers)
                    output.writeLong(line.lineStartMs)
                    output.writeLong(line.lineEndMs)
                    line.words.forEach { word ->
                        output.writeBoundedString(word.text)
                        output.writeBoundedString(word.romanized)
                        output.writeLong(word.startMs)
                        output.writeLong(word.endMs)
                        output.writeStrictBoolean(word.boundaryAfter)
                        output.writeInt(word.sourceStart)
                        output.writeInt(word.sourceEnd)
                    }
                }
            }
            bytes.toByteArray().takeIf {
                it.isNotEmpty() && it.size <= AodStateWireLimits.MAX_ENCODED_BODY_BYTES
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSnapshotBody(body: ByteArray): AodStateWireSnapshot? {
        if (body.isEmpty() || body.size > AodStateWireLimits.MAX_ENCODED_BODY_BYTES) return null
        return try {
            val input = DataInputStream(ByteArrayInputStream(body))
            if (input.readInt() != BODY_MAGIC || input.readInt() != BODY_VERSION) return null
            val wordCount = input.readBoundedCount(AodStateWireLimits.MAX_WORDS) ?: return null
            val rubyCount = input.readBoundedCount(AodStateWireLimits.MAX_RUBY) ?: return null
            val layoutCount = input.readBoundedCount(AodStateWireLimits.MAX_LAYOUT_GROUPS) ?: return null
            val budget = Utf8Budget()
            val trackGeneration = input.readLong()
            val aodEnabled = input.readStrictBoolean() ?: return null
            val lockscreenEnabled = input.readStrictBoolean() ?: return null
            val positionFollowingEnabled = input.readStrictBoolean() ?: return null
            val burnInPattern = input.readBoundedString(
                AodStateWireLimits.MAX_STYLE_CHARS,
                allowEmpty = false,
                budget = budget
            ) ?: return null
            val burnInIntervalMs = input.readLong()
            val suppressStockAodContent = input.readStrictBoolean() ?: return null
            val aodRotateWithDevice = input.readStrictBoolean() ?: return null
            val aodRotationMode = input.readStyleString(budget) ?: return null
            val aodRotationSettleMs = input.readLong()
            val aodCanvasAnchorLandscape = input.readFloat()
            val aodLandscapeTextScale = input.readFloat()
            val aodLandscapeHideStock = input.readStrictBoolean() ?: return null
            val aodLandscapeFullscreen = input.readStrictBoolean() ?: return null
            val aodLandscapeFullscreenSafeMarginPercent = input.readFloat()
            val aodDebugShowCanvasFrame = input.readStrictBoolean() ?: return null
            val aodCanvasPaddingPortraitXPercent = input.readFloat()
            val aodCanvasPaddingPortraitYPercent = input.readFloat()
            val aodCanvasPaddingLandscapeXPercent = input.readFloat()
            val aodCanvasPaddingLandscapeYPercent = input.readFloat()
            val original = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS,
                allowEmpty = false,
                budget = budget
            ) ?: return null
            val romanized = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val translated = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val nextLine = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val nextLineRomanized = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val nextLineTranslated = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val metadata = input.readBoundedString(
                AodStateWireLimits.MAX_METADATA_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val title = input.readBoundedString(
                AodStateWireLimits.MAX_METADATA_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val artist = input.readBoundedString(
                AodStateWireLimits.MAX_METADATA_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val album = input.readBoundedString(
                AodStateWireLimits.MAX_METADATA_CHARS,
                allowEmpty = true,
                budget = budget
            ) ?: return null
            val largeMetadata = input.readStrictBoolean() ?: return null
            val alignedRight = input.readStrictBoolean() ?: return null
            val alignedRightMarkers = input.readStrictBoolean() ?: return null
            val lineLevelSync = input.readStrictBoolean() ?: return null
            val lineStartMs = input.readLong()
            val lineEndMs = input.readLong()
            val durationMs = input.readLong()
            val positionMs = input.readLong()
            val sampledAtElapsedMs = input.readLong()
            val speed = input.readFloat()
            val weight = input.readStyleString(budget) ?: return null
            val textSizeMode = input.readStyleString(budget) ?: return null
            val textSizeCustom = input.readInt()
            val secondaryMode = input.readStyleString(budget) ?: return null
            val animationMode = input.readStyleString(budget) ?: return null
            val glowMode = input.readStyleString(budget) ?: return null
            val motionMode = input.readStyleString(budget) ?: return null
            val lineSyncFillMode = input.readStyleString(budget) ?: return null
            val overflowMode = input.readStyleString(budget) ?: return null
            val transitionMode = input.readStyleString(budget) ?: return null
            val fontFamily = input.readStyleString(budget) ?: return null
            val alignmentMode = input.readStyleString(budget) ?: return null
            val metadataVisible = input.readStrictBoolean() ?: return null
            val metadataAnchor = input.readStyleString(budget) ?: return null
            val adaptiveSectioning = input.readStrictBoolean() ?: return null
            val words = ArrayList<AodStateWireWord>(wordCount)
            repeat(wordCount) {
                val text = input.readBoundedString(
                    AodStateWireLimits.MAX_LYRIC_CHARS,
                    allowEmpty = true,
                    budget = budget
                ) ?: return null
                val wordRomanized = input.readBoundedString(
                    AodStateWireLimits.MAX_LYRIC_CHARS,
                    allowEmpty = true,
                    budget = budget
                ) ?: return null
                words += AodStateWireWord(
                    text = text,
                    romanized = wordRomanized,
                    startMs = input.readLong(),
                    endMs = input.readLong(),
                    boundaryAfter = input.readStrictBoolean() ?: return null,
                    sourceStart = input.readInt(),
                    sourceEnd = input.readInt()
                )
            }
            val translationWordCount =
                input.readBoundedCount(AodStateWireLimits.MAX_WORDS) ?: return null
            val translationWords = ArrayList<AodStateWireWord>(translationWordCount)
            repeat(translationWordCount) {
                val text = input.readBoundedString(
                    AodStateWireLimits.MAX_LYRIC_CHARS,
                    allowEmpty = true,
                    budget = budget
                ) ?: return null
                val wordRomanized = input.readBoundedString(
                    AodStateWireLimits.MAX_LYRIC_CHARS,
                    allowEmpty = true,
                    budget = budget
                ) ?: return null
                translationWords += AodStateWireWord(
                    text = text,
                    romanized = wordRomanized,
                    startMs = input.readLong(),
                    endMs = input.readLong(),
                    boundaryAfter = input.readStrictBoolean() ?: return null,
                    sourceStart = input.readInt(),
                    sourceEnd = input.readInt()
                )
            }
            val ruby = ArrayList<AodStateWireRuby>(rubyCount)
            repeat(rubyCount) {
                ruby += AodStateWireRuby(
                    start = input.readInt(),
                    end = input.readInt(),
                    reading = input.readBoundedString(
                        AodStateWireLimits.MAX_LYRIC_CHARS,
                        allowEmpty = true,
                        budget = budget
                    ) ?: return null
                )
            }
            val layoutGroups = ArrayList<AodStateWireLayoutGroup>(layoutCount)
            repeat(layoutCount) {
                layoutGroups += AodStateWireLayoutGroup(
                    start = input.readInt(),
                    end = input.readInt(),
                    kind = input.readBoundedString(
                        AodStateWireLimits.MAX_METADATA_CHARS,
                        allowEmpty = true,
                        budget = budget
                    ) ?: return null,
                    keepTogether = input.readStrictBoolean() ?: return null,
                    confidence = input.readDouble()
                )
            }
            val artworkSize = input.readInt()
            if (artworkSize < 0 ||
                artworkSize > AodStateWireLimits.MAX_ARTWORK_BYTES ||
                artworkSize > input.available()
            ) return null
            val artworkBytes = ByteArray(artworkSize)
            input.readFully(artworkBytes)
            val artworkJpeg = ArtworkJpeg(artworkBytes)
            // 封面键走独立预算:不占文本聚合预算(编码侧 fitAodEnhancementBudget 不为它
            // 留头寸),仅按字符上限+UTF-8 预算自检,两端口径一致。
            val artworkKey = input.readBoundedString(
                AodStateWireLimits.MAX_ARTWORK_KEY_CHARS,
                allowEmpty = true,
                budget = Utf8Budget()
            ) ?: return null
            val hasDuet = input.readStrictBoolean() ?: return null
            val duetLine = if (hasDuet) decodeDuetLine(input, budget) ?: return null else null
            if (input.available() != 0) return null
            AodStateWireSnapshot(
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
                words = words.toList(),
                translationWords = translationWords.toList(),
                ruby = ruby.toList(),
                layoutGroups = layoutGroups.toList(),
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
                artworkJpeg = artworkJpeg,
                artworkKey = artworkKey,
                duetLine = duetLine
            ).takeIf(::isValidSnapshot)
        } catch (_: Exception) {
            null
        }
    }

    /** 对唱并发行解码(v4 起):文本/副文本走聚合文本预算,词级条数与主行共享上限。 */
    private fun decodeDuetLine(
        input: DataInputStream,
        budget: Utf8Budget
    ): AodStateWireDuetLine? {
        return try {
            val wordCount = input.readBoundedCount(AodStateWireLimits.MAX_WORDS) ?: return null
            val text = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS, allowEmpty = false, budget = budget
            ) ?: return null
            val romanized = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS, allowEmpty = true, budget = budget
            ) ?: return null
            val translated = input.readBoundedString(
                AodStateWireLimits.MAX_LYRIC_CHARS, allowEmpty = true, budget = budget
            ) ?: return null
            val alignedRight = input.readStrictBoolean() ?: return null
            val alignedRightMarkers = input.readStrictBoolean() ?: return null
            val lineStartMs = input.readLong()
            val lineEndMs = input.readLong()
            val words = ArrayList<AodStateWireWord>(wordCount)
            repeat(wordCount) {
                words += AodStateWireWord(
                    text = input.readBoundedString(
                        AodStateWireLimits.MAX_LYRIC_CHARS, allowEmpty = true, budget = budget
                    ) ?: return null,
                    romanized = input.readBoundedString(
                        AodStateWireLimits.MAX_LYRIC_CHARS, allowEmpty = true, budget = budget
                    ) ?: return null,
                    startMs = input.readLong(),
                    endMs = input.readLong(),
                    boundaryAfter = input.readStrictBoolean() ?: return null,
                    sourceStart = input.readInt(),
                    sourceEnd = input.readInt()
                )
            }
            AodStateWireDuetLine(
                text = text,
                romanized = romanized,
                translated = translated,
                alignedRight = alignedRight,
                alignedRightMarkers = alignedRightMarkers,
                lineStartMs = lineStartMs,
                lineEndMs = lineEndMs,
                words = words.toList()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun isValidSnapshot(snapshot: AodStateWireSnapshot): Boolean {
        if (snapshot.words.size > AodStateWireLimits.MAX_WORDS ||
            snapshot.translationWords.size > AodStateWireLimits.MAX_WORDS ||
            snapshot.ruby.size > AodStateWireLimits.MAX_RUBY ||
            snapshot.layoutGroups.size > AodStateWireLimits.MAX_LAYOUT_GROUPS
        ) return false
        if (snapshot.trackGeneration < 0L || snapshot.lineStartMs < 0L ||
            snapshot.lineEndMs < snapshot.lineStartMs ||
            snapshot.durationMs !in 1L..AodStateWireLimits.MAX_MEDIA_DURATION_MS ||
            snapshot.lineEndMs > snapshot.durationMs ||
            snapshot.positionMs !in 0L..snapshot.durationMs ||
            snapshot.sampledAtElapsedMs < 0L || !snapshot.speed.isFinite() ||
            snapshot.speed !in 0f..AodStateWireLimits.MAX_PLAYBACK_SPEED ||
            snapshot.textSizeCustom !in 0..500
        ) return false
        if (snapshot.burnInPattern != normalizeAodBurnInPattern(snapshot.burnInPattern) ||
            snapshot.burnInIntervalMs != normalizeAodBurnInInterval(snapshot.burnInIntervalMs) ||
            snapshot.aodRotationMode != normalizeAodRotationMode(snapshot.aodRotationMode) ||
            snapshot.aodRotationSettleMs != normalizeAodRotationSettleMs(snapshot.aodRotationSettleMs) ||
            snapshot.aodCanvasAnchorLandscape != normalizeAodCanvasAnchor(snapshot.aodCanvasAnchorLandscape) ||
            snapshot.aodLandscapeTextScale != normalizeAodLandscapeTextScale(snapshot.aodLandscapeTextScale) ||
            snapshot.aodLandscapeFullscreenSafeMarginPercent !=
                normalizeAodFullscreenSafeMarginPercent(
                    snapshot.aodLandscapeFullscreenSafeMarginPercent
                ) ||
            snapshot.aodCanvasPaddingPortraitXPercent != normalizeAodCanvasPaddingPercent(
                snapshot.aodCanvasPaddingPortraitXPercent
            ) ||
            snapshot.aodCanvasPaddingPortraitYPercent != normalizeAodCanvasPaddingPercent(
                snapshot.aodCanvasPaddingPortraitYPercent
            ) ||
            snapshot.aodCanvasPaddingLandscapeXPercent != normalizeAodCanvasPaddingPercent(
                snapshot.aodCanvasPaddingLandscapeXPercent
            ) ||
            snapshot.aodCanvasPaddingLandscapeYPercent != normalizeAodCanvasPaddingPercent(
                snapshot.aodCanvasPaddingLandscapeYPercent
            ) ||
            snapshot.original != snapshot.original.trim() ||
            snapshot.romanized != snapshot.romanized.trim() ||
            snapshot.translated != snapshot.translated.trim() ||
            snapshot.nextLine != snapshot.nextLine.trim() ||
            snapshot.nextLineRomanized != snapshot.nextLineRomanized.trim() ||
            snapshot.nextLineTranslated != snapshot.nextLineTranslated.trim() ||
            snapshot.metadata != snapshot.metadata.trim() ||
            snapshot.title != snapshot.title.trim() ||
            snapshot.artist != snapshot.artist.trim() ||
            snapshot.album != snapshot.album.trim() ||
            snapshot.weight != normalizeAodWeight(snapshot.weight) ||
            snapshot.textSizeMode != normalizeAodTextSize(snapshot.textSizeMode) ||
            snapshot.secondaryMode != normalizeAodSecondary(snapshot.secondaryMode) ||
            snapshot.animationMode != normalizeAodAnimation(snapshot.animationMode) ||
            snapshot.glowMode != normalizeAodGlow(snapshot.glowMode) ||
            snapshot.motionMode != "Fluid" ||
            snapshot.lineSyncFillMode != normalizeAodLineSyncFill(snapshot.lineSyncFillMode) ||
            snapshot.overflowMode != normalizeAodOverflow(snapshot.overflowMode) ||
            snapshot.transitionMode != normalizeAodTransition(snapshot.transitionMode) ||
            snapshot.fontFamily != normalizeAodFontFamily(snapshot.fontFamily) ||
            snapshot.alignmentMode != normalizeAodAlignment(snapshot.alignmentMode) ||
            snapshot.metadataAnchor != normalizeAodMetadataAnchor(snapshot.metadataAnchor)
        ) return false
        val budget = Utf8Budget()
        if (!budget.accept(snapshot.original, AodStateWireLimits.MAX_LYRIC_CHARS, false) ||
            !budget.accept(snapshot.romanized, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
            !budget.accept(snapshot.translated, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
            !budget.accept(snapshot.nextLine, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
            !budget.accept(snapshot.nextLineRomanized, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
            !budget.accept(snapshot.nextLineTranslated, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
            !budget.accept(snapshot.metadata, AodStateWireLimits.MAX_METADATA_CHARS, true) ||
            !budget.accept(snapshot.title, AodStateWireLimits.MAX_METADATA_CHARS, true) ||
            !budget.accept(snapshot.artist, AodStateWireLimits.MAX_METADATA_CHARS, true) ||
            !budget.accept(snapshot.album, AodStateWireLimits.MAX_METADATA_CHARS, true)
        ) return false
        val styles = listOf(
            snapshot.burnInPattern,
            snapshot.weight,
            snapshot.textSizeMode,
            snapshot.secondaryMode,
            snapshot.animationMode,
            snapshot.glowMode,
            snapshot.motionMode,
            snapshot.lineSyncFillMode,
            snapshot.overflowMode,
            snapshot.transitionMode,
            snapshot.fontFamily,
            snapshot.alignmentMode,
            snapshot.metadataAnchor
        )
        if (styles.any { !budget.accept(it, AodStateWireLimits.MAX_STYLE_CHARS, false) }) return false
        for (word in snapshot.words) {
            if (!budget.accept(word.text, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
                !budget.accept(word.romanized, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
                word.startMs < 0L || word.endMs < word.startMs ||
                word.endMs > snapshot.durationMs ||
                !validSourceRange(word.sourceStart, word.sourceEnd, snapshot.original.length)
            ) return false
        }
        // 插件逐字翻译词表:条数与主行词表共享上限;文本入同一聚合 UTF-8 预算;时间窗
        // 不得越歌长(与主行同口径)。片段不指向原文,source 范围不校验(与并发行词表同口径)。
        // 计数顺序与投影侧 fitAodEnhancementBudget 一致(词 → 逐字翻译词 → 注音 → 布局组)。
        for (word in snapshot.translationWords) {
            if (!budget.accept(word.text, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
                !budget.accept(word.romanized, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
                word.startMs < 0L || word.endMs < word.startMs ||
                word.endMs > snapshot.durationMs
            ) return false
        }
        for (ruby in snapshot.ruby) {
            if (!budget.accept(ruby.reading, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
                ruby.start < 0 || ruby.end <= ruby.start || ruby.end > snapshot.original.length
            ) return false
        }
        for (group in snapshot.layoutGroups) {
            if (!budget.accept(group.kind, AodStateWireLimits.MAX_METADATA_CHARS, true) ||
                group.start < 0 || group.end <= group.start ||
                group.end > snapshot.original.length || !group.confidence.isFinite() ||
                group.confidence !in 0.0..1.0
            ) return false
        }
        // 歌曲图片:有界 JPEG + 规范键;图与键必须同有同无(半截帧拒收)。键走独立预算
        // (与 decode 侧一致,不占文本聚合预算)。
        if (snapshot.artworkJpeg.size > AodStateWireLimits.MAX_ARTWORK_BYTES ||
            (snapshot.artworkJpeg.isEmpty() != snapshot.artworkKey.isEmpty()) ||
            snapshot.artworkKey != snapshot.artworkKey.trim() ||
            snapshot.artworkKey.length > AodStateWireLimits.MAX_ARTWORK_KEY_CHARS ||
            !Utf8Budget().accept(snapshot.artworkKey, AodStateWireLimits.MAX_ARTWORK_KEY_CHARS, true)
        ) return false
        // 对唱并发行:词级条数与主行共享上限;文本/副文本入同一聚合 UTF-8 预算;
        // 时间窗/词级时间不得越歌长(与主行同口径,超限整包拒收)。
        snapshot.duetLine?.let { duet ->
            if (snapshot.words.size + duet.words.size > AodStateWireLimits.MAX_WORDS) return false
            if (duet.text.isBlank() || duet.text != duet.text.trim() ||
                duet.romanized != duet.romanized.trim() ||
                duet.translated != duet.translated.trim() ||
                duet.lineStartMs < 0L || duet.lineEndMs < duet.lineStartMs ||
                duet.lineEndMs > snapshot.durationMs
            ) return false
            if (!budget.accept(duet.text, AodStateWireLimits.MAX_LYRIC_CHARS, false) ||
                !budget.accept(duet.romanized, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
                !budget.accept(duet.translated, AodStateWireLimits.MAX_LYRIC_CHARS, true)
            ) return false
            for (word in duet.words) {
                if (!budget.accept(word.text, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
                    !budget.accept(word.romanized, AodStateWireLimits.MAX_LYRIC_CHARS, true) ||
                    word.startMs < 0L || word.endMs < word.startMs ||
                    word.endMs > snapshot.durationMs
                ) return false
            }
        }
        return true
    }

    private fun validEnvelopeScalars(revision: Long, userId: Int, updatedAt: Long): Boolean =
        revision >= 0L && userId >= 0 && updatedAt >= 0L

    private fun validSourceRange(start: Int, end: Int, sourceLength: Int): Boolean =
        start == -1 && end == -1 || start >= 0 && end > start && end <= sourceLength

    private class Utf8Budget {
        private var used = 0

        fun accept(value: String, maxChars: Int, allowEmpty: Boolean): Boolean {
            if ((!allowEmpty && value.isEmpty()) || value.length > maxChars ||
                !value.isWellFormedUtf16()
            ) return false
            val size = value.toByteArray(Charsets.UTF_8).size
            if (size > AodStateWireLimits.MAX_AGGREGATE_TEXT_UTF8_BYTES - used) return false
            used += size
            return true
        }

        fun acceptBytes(size: Int): Boolean {
            if (size < 0 || size > AodStateWireLimits.MAX_AGGREGATE_TEXT_UTF8_BYTES - used) {
                return false
            }
            used += size
            return true
        }
    }

    private fun DataOutputStream.writeStrictBoolean(value: Boolean) = writeByte(if (value) 1 else 0)

    private fun DataOutputStream.writeBoundedString(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBoundedCount(maximum: Int): Int? =
        readInt().takeIf { it in 0..maximum }

    private fun DataInputStream.readStrictBoolean(): Boolean? = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> null
    }

    private fun DataInputStream.readStyleString(budget: Utf8Budget): String? = readBoundedString(
        AodStateWireLimits.MAX_STYLE_CHARS,
        allowEmpty = false,
        budget = budget
    )

    private fun DataInputStream.readBoundedString(
        maxChars: Int,
        allowEmpty: Boolean,
        budget: Utf8Budget
    ): String? {
        val size = readInt()
        if (size < 0 || size > maxChars * MAX_UTF8_BYTES_PER_UTF16_CHAR ||
            size > available() || !budget.acceptBytes(size)
        ) return null
        val bytes = ByteArray(size)
        readFully(bytes)
        val value = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        return value.takeIf { (allowEmpty || it.isNotEmpty()) && it.length <= maxChars }
    }

    private const val BODY_MAGIC = 0x414F4453

    /** v8:词表区追加插件逐字翻译词表(translationWords,条数+载荷,翻译辅助行按真实词窗点亮);
     *  v7:metadata 区追加原始 title/artist/album + largeMetadata + 标记识别版分侧(主行与并发行),
     *  让渲染面按本面「歌曲信息内容」/「识别对唱标记」独立组装与选侧(per-surface);
     *  v6:样式区追加 aodLandscapeFullscreenSafeMarginPercent(横屏全屏化安全边界);
     *  v5:行文本区追加 nextLineRomanized/nextLineTranslated(下一行辅助文字);
     *  v4:对照尾部追加对唱并发行(duetLine,存在性+载荷);v3 追加歌曲图片帧。 */
    private const val BODY_VERSION = 8
    private const val MAX_UTF8_BYTES_PER_UTF16_CHAR = 4
}

internal fun normalizeAodLineSyncFill(value: String): String = when (value) {
    "None",
    "Top to bottom",
    "Left to right",
    "Left to right (sentence)",
    "Left to right (main only)",
    "Left to right (whole block)" -> value
    else -> "Top to bottom"
}

/**
 * 换行动画词表归一化:画布六种模式原样放行;旧词表小写形态(场景过渡 preset id 曾
 * 混入本通道)映射到等价模式 —— 特别是 `"none"` 必须落到 `"None"`(关闭动画),
 * 不能再被兜底成 `"Fade up"`。未知值兜底 `"Fade up"`(fail-safe,与历史行为一致)。
 */
internal fun normalizeAodTransition(value: String): String = when (value) {
    "Fade up", "Crossfade", "Slide up", "Slide left", "Zoom", "None" -> value
    "continuity" -> "Fade up"
    "crossfade" -> "Crossfade"
    "none" -> "None"
    else -> "Fade up"
}

internal fun String.takeUtf16Prefix(maxChars: Int): String {
    if (length <= maxChars) return this
    val prefix = take(maxChars)
    return if (prefix.lastOrNull()?.isHighSurrogate() == true) prefix.dropLast(1) else prefix
}

private fun String.isWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        val current = this[index]
        when {
            current.isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            current.isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}

/**
 * 孤立代理项替换为 U+FFFD（等长），成对代理项原样保留。
 * [isWellFormedUtf16] 拒收任一孤立代理项，而歌词文本来自播放器/插件/JSON 解析，
 * 无法保证良构——一个非法码点就足以让整个快照被拒收、降级为 Hidden。
 */
internal fun String.sanitizeUtf16(): String {
    if (isWellFormedUtf16()) return this
    val out = StringBuilder(length)
    var index = 0
    while (index < length) {
        val current = this[index]
        val next = if (index + 1 < length) this[index + 1] else ' '
        when {
            current.isHighSurrogate() && next.isLowSurrogate() -> {
                out.append(current).append(next)
                index += 2
            }
            current.isHighSurrogate() || current.isLowSurrogate() -> {
                out.append(REPLACEMENT_CHARACTER)
                index++
            }
            else -> {
                out.append(current)
                index++
            }
        }
    }
    return out.toString()
}

/**
 * 文本字段的 wire 规整：孤立代理项替换 → 去首尾空白 → 按 [maxChars] 截断（不切开代理对）
 * → 截断后二次去尾空白。最后一步不可省——[isValidSnapshot] 要求各文本字段 `== trim()`，
 * 截断落点正好停在空白上会破坏该不变量并让快照被拒收。
 */
internal fun String.normalizeAodWireText(maxChars: Int): String =
    sanitizeUtf16().trim().takeUtf16Prefix(maxChars).trim()

/** UTF-8 字节计数，与 [AodStateWireCodec] 校验侧的聚合预算同口径。 */
internal fun aodUtf8Bytes(value: String): Int = value.toByteArray(Charsets.UTF_8).size

private const val REPLACEMENT_CHARACTER = '\uFFFD'

internal object AodStateWireBundleCodec {
    fun toBundle(envelope: AodStateWireEnvelope): Bundle = Bundle().apply {
        putInt(KEY_PROTOCOL, envelope.protocol)
        putInt(KEY_KIND, envelope.kind)
        putLong(KEY_REVISION, envelope.revision)
        putInt(KEY_USER_ID, envelope.userId)
        putLong(KEY_UPDATED_AT, envelope.updatedAtElapsedMs)
        putBoolean(KEY_KEEP_ALIVE, envelope.keepAlive)
        putLong(KEY_WAKE_SIGNAL, envelope.wakeSignal)
        putBoolean(KEY_PLAYBACK_ACTIVE, envelope.playbackActive)
        putBoolean(KEY_PAUSE_RETENTION_ELIGIBLE, envelope.pauseRetentionEligible)
        envelope.body?.let { putByteArray(KEY_BODY, it) }
    }

    fun snapshotFromBundle(bundle: Bundle): AodStateWireMessage? =
        AodStateWireCodec.decode(envelopeFromBundle(bundle))

    /**
     * 抽出信封与解码分离,使被拒时能报出闸门名。Bundle 仍在这里解成自有标量/字节
     * (Binder 还持有它的时候)。
     */
    fun envelopeFromBundle(bundle: Bundle): AodStateWireEnvelope {
        val kind = bundle.getInt(KEY_KIND, 0)
        val envelope = AodStateWireEnvelope(
            protocol = bundle.getInt(KEY_PROTOCOL, 0),
            kind = kind,
            revision = bundle.getLong(KEY_REVISION, -1L),
            userId = bundle.getInt(KEY_USER_ID, -1),
            updatedAtElapsedMs = bundle.getLong(KEY_UPDATED_AT, -1L),
            keepAlive = bundle.getBoolean(KEY_KEEP_ALIVE, false),
            wakeSignal = bundle.getLong(KEY_WAKE_SIGNAL, 0L),
            body = if (kind == AodStateWireContract.KIND_SNAPSHOT) {
                bundle.getByteArray(KEY_BODY)
            } else {
                null
            },
            playbackActive = bundle.getBoolean(KEY_PLAYBACK_ACTIVE, false),
            pauseRetentionEligible = bundle.getBoolean(KEY_PAUSE_RETENTION_ELIGIBLE, false)
        )
        return envelope
    }

    private const val KEY_PROTOCOL = "stateProtocol"
    private const val KEY_KIND = "stateKind"
    private const val KEY_REVISION = "revision"
    private const val KEY_USER_ID = "userId"
    private const val KEY_UPDATED_AT = "updatedAtElapsed"
    private const val KEY_KEEP_ALIVE = "keepAlive"
    private const val KEY_WAKE_SIGNAL = "wakeSignal"
    private const val KEY_PLAYBACK_ACTIVE = "playbackActive"
    private const val KEY_PAUSE_RETENTION_ELIGIBLE = "pauseRetentionEligible"
    private const val KEY_BODY = "stateBody"
}
