package com.eza.hyperglow.aod

import android.os.Bundle
import android.os.RemoteCallbackList
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.customization.CompiledCustomization
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
    val metadata: String = "",
    val alignedRight: Boolean = false,
    val lineLevelSync: Boolean = false,
    val lineStartMs: Long = 0L,
    val lineEndMs: Long = 0L,
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val sampledAtElapsedMs: Long = 0L,
    val speed: Float = 1f,
    val words: List<AodDisplayWord> = emptyList(),
    val ruby: List<AodDisplayRuby> = emptyList(),
    val layoutGroups: List<AodDisplayLayoutGroup> = emptyList(),
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
    val secondaryAlignment: String = "auto",
    val metadataVisible: Boolean = true,
    val metadataAnchor: String = "top",
    val adaptiveSectioning: Boolean = true
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
        latestConfiguration?.let { configuration ->
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
        val bundle = CompiledCustomizationBundleCodec.toBundle(configuration, userId, experimentalMode)
        lastConfigurationHash = configuration.hash
        latestConfiguration = bundle
        val count = callbacks.beginBroadcast()
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
    val metadata = state.metadata.normalizeAodWireText(AodStateWireLimits.MAX_METADATA_CHARS)
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
    val (budgetWords, budgetRuby, budgetGroups) = fitAodEnhancementBudget(
        baseTexts = listOf(original, romanized, translated, nextLine, metadata),
        styleTexts = styleTokens(state),
        words = words,
        ruby = ruby,
        groups = layoutGroups
    )
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
        metadata = metadata,
        lineStartMs = lineStart,
        lineEndMs = lineEnd,
        durationMs = duration,
        positionMs = position,
        sampledAtElapsedMs = state.sampledAtElapsedMs.coerceAtLeast(0L),
        speed = state.speed.takeIf {
            it.isFinite() && it in 0f..AodStateWireLimits.MAX_PLAYBACK_SPEED
        } ?: 1f,
        words = budgetWords,
        ruby = budgetRuby,
        layoutGroups = budgetGroups,
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
        secondaryAlignment = normalizeAodAlignment(state.secondaryAlignment),
        metadataAnchor = normalizeAodMetadataAnchor(state.metadataAnchor)
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
            aodDebugShowCanvasFrame = aodDebugShowCanvasFrame,
            aodCanvasPaddingPortraitXPercent = aodCanvasPaddingPortraitXPercent,
            aodCanvasPaddingPortraitYPercent = aodCanvasPaddingPortraitYPercent,
            aodCanvasPaddingLandscapeXPercent = aodCanvasPaddingLandscapeXPercent,
            aodCanvasPaddingLandscapeYPercent = aodCanvasPaddingLandscapeYPercent,
            original = original,
            romanized = romanized,
            translated = translated,
            nextLine = nextLine,
            metadata = metadata,
            alignedRight = alignedRight,
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
            secondaryAlignment = secondaryAlignment,
            metadataVisible = metadataVisible,
            metadataAnchor = metadataAnchor,
            adaptiveSectioning = adaptiveSectioning
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
 * 增强数据（词/注音/布局组）的聚合文本预算裁剪。
 *
 * [AodStateWireCodec] 的 isValidSnapshot 按 UTF-8 字节总额把关
 * （[AodStateWireLimits.MAX_AGGREGATE_TEXT_UTF8_BYTES]），超限直接拒收整包、静默降级为
 * Hidden——整句歌词会因为词级数据超长而整体消失。词/注音/布局组是可选增强（STYLE_GUIDE:
 * 可选内容按序降级），这里按校验侧同一计数顺序（行文本 → 样式 → 词 → 注音 → 布局组）
 * 只装下最长前缀，行文本永远保留；口径与顺序必须与 isValidSnapshot 的 Utf8Budget 一致。
 */
private fun fitAodEnhancementBudget(
    baseTexts: List<String>,
    styleTexts: List<String>,
    words: List<AodDisplayWord>,
    ruby: List<AodDisplayRuby>,
    groups: List<AodDisplayLayoutGroup>
): Triple<List<AodDisplayWord>, List<AodDisplayRuby>, List<AodDisplayLayoutGroup>> {
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
    val keptRuby = ruby.takeWhile { accept(it.reading) }
    val keptGroups = groups.takeWhile { accept(it.kind) }
    return Triple(keptWords, keptRuby, keptGroups)
}
