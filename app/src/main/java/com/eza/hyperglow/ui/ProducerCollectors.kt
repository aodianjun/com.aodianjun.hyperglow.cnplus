package com.eza.hyperglow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.eza.hyperglow.aod.XiaomiRuntimeSupportState
import com.eza.hyperglow.customization.composeSongMetadata
import com.eza.hyperglow.producer.ArtworkFrame
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricProducers
import com.eza.hyperglow.producer.LyricSource
import com.eza.hyperglow.producer.ProducerConnection
import com.eza.hyperglow.producer.SongArtworkRepository
import com.eza.hyperglow.producer.isSameTrackIdentity
import com.eza.hyperglow.producer.stripDuetMarker
import com.eza.hyperglow.producer.stripDuetMarkerWords
import com.eza.hyperglow.root.projection.LyricLayoutGroup
import com.eza.hyperglow.root.projection.LyricRuby
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricWord

// --- Phase 3 UI: lyric source + live status + Lyricon setup hint ---

/**
 * Collects the arbiter's [active] state in a Compose-stable way. Returns null before the
 * arbiter is started (e.g. in previews) — `collectAsState` is still invoked unconditionally
 * so Compose's remember-slot invariants hold.
 */
@Composable
internal fun collectActiveState(): androidx.compose.runtime.State<LyricProducerState?> {
    val arbiter = LyricProducers.arbiterOrNull()
    val flow = remember(arbiter) {
        arbiter?.active
            ?: kotlinx.coroutines.flow.MutableStateFlow<LyricProducerState?>(null)
    }
    return flow.collectAsState()
}

@Composable
internal fun collectPreference(): androidx.compose.runtime.State<LyricSource> {
    val arbiter = LyricProducers.arbiterOrNull()
    val flow = remember(arbiter) {
        arbiter?.preference
            ?: kotlinx.coroutines.flow.MutableStateFlow(LyricSource.SPICY)
    }
    return flow.collectAsState()
}

@Composable
internal fun collectActiveSource(): androidx.compose.runtime.State<LyricSource?> {
    val arbiter = LyricProducers.arbiterOrNull()
    val flow = remember(arbiter) {
        arbiter?.activeSource
            ?: kotlinx.coroutines.flow.MutableStateFlow<LyricSource?>(null)
    }
    return flow.collectAsState()
}

@Composable
internal fun collectConnection(source: LyricSource): androidx.compose.runtime.State<ProducerConnection> {
    val arbiter = LyricProducers.arbiterOrNull()
    val flow = remember(arbiter, source) {
        arbiter?.connection(source)
            ?: kotlinx.coroutines.flow.MutableStateFlow(ProducerConnection.DISCONNECTED)
    }
    return flow.collectAsState()
}

/**
 * 实时歌词快照:从进程内 arbiter(可靠地在 app 进程内填充,与 LiveStatusSection 同源)读取
 * 当前歌词源上报的 [LyricProducerState],映射成预览所需的 [LyricSnapshot]。无实时数据时返回
 * null,由调用方回退到静态示例快照。
 *
 * 歌曲信息按 [metadataParts]/[metadataSeparators](外观文档全局配置)用与实机投影层相同的
 * [composeSongMetadata] 组装,保证预览与实机所见即所得。歌曲图片同样与实机同源:
 * 订阅 [SongArtworkRepository.current](出帧后驱动重组),取与当前曲目同曲的已校对帧。
 *
 * 注意:不从这里读 SystemUiLyricProjection —— 那是 SystemUI 侧投影,app 进程内并不保证
 * 被喂入实时快照,会导致预览不更新。
 */
@Composable
internal fun collectLiveSnapshot(
    metadataParts: String,
    metadataSeparators: String,
    duetMarkers: Boolean = true
): LyricSnapshot? {
    val active by collectActiveState()
    // 封面帧出帧后驱动重组(帧到达前字段为空,预览不显示,与实机 fail-closed 一致)。
    val artworkFrame by SongArtworkRepository.current.collectAsState()
    return active?.toPreviewSnapshot(metadataParts, metadataSeparators, duetMarkers, artworkFrame)
}

private fun LyricProducerState.toPreviewSnapshot(
    metadataParts: String,
    metadataSeparators: String,
    duetMarkers: Boolean,
    artworkFrame: ArtworkFrame?
): LyricSnapshot {
    val frame = artworkFrame?.takeIf {
        isSameTrackIdentity(title, artist, it.title, it.artist)
    }
    return LyricSnapshot(
    revision = sequence,
    trackGeneration = generation.toLong(),
    updatedAtElapsedMs = sampledAtElapsedMs,
    visible = true,
    original = if (duetMarkers) stripDuetMarker(line) else line,
    romanized = romanizedLine,
    translated = translatedLine,
    nextLine = if (duetMarkers) stripDuetMarker(nextLine) else nextLine,
    metadata = composeSongMetadata(
        title = title,
        artist = artist,
        album = album,
        parts = metadataParts,
        separators = metadataSeparators
    ).ifBlank { "HyperGlow" },
    alignedRight = alignedRight,
    lineLevelSync = words == null,
    lineStartMs = lineStartMs,
    lineEndMs = lineEndMs,
    durationMs = durationMs,
    positionMs = positionMs,
    sampledAtElapsedMs = sampledAtElapsedMs,
    speed = speed,
    words = (if (duetMarkers) stripDuetMarkerWords(words ?: emptyList()) else words ?: emptyList()).map {
        LyricWord(
            text = it.text,
            romanized = it.romanized,
            startMs = it.startMs,
            endMs = it.endMs,
            boundaryAfter = it.boundaryAfter,
            sourceStart = it.sourceStart,
            sourceEnd = it.sourceEnd
        )
    },
    ruby = ruby.map { LyricRuby(it.start, it.end, it.reading) },
    layoutGroups = layoutGroups.map {
        LyricLayoutGroup(it.start, it.end, it.kind, it.keepTogether, it.confidence)
    },
    artworkJpeg = frame?.jpeg ?: ByteArray(0),
    artworkKey = frame?.key ?: ""
    )
}

// 判断模块当前是否处于可用的运行状态(与 runtimeProfileAvailable 一致)。
internal fun resolveModuleWorking(state: XiaomiRuntimeSupportState): Boolean =
    state == XiaomiRuntimeSupportState.AVAILABLE ||
        state == XiaomiRuntimeSupportState.VERIFIED_PROFILE ||
        state == XiaomiRuntimeSupportState.VERIFIED_PROFILE_MISSING_SYMBOLS ||
        state == XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE
