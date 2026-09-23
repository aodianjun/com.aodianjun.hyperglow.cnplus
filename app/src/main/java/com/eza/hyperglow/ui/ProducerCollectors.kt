package com.eza.hyperglow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.eza.hyperglow.aod.XiaomiRuntimeSupportState
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricProducers
import com.eza.hyperglow.producer.LyricSource
import com.eza.hyperglow.producer.ProducerConnection
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
 * 注意:不从这里读 SystemUiLyricProjection —— 那是 SystemUI 侧投影,app 进程内并不保证
 * 被喂入实时快照,会导致预览不更新。
 */
@Composable
internal fun collectLiveSnapshot(): LyricSnapshot? {
    val active by collectActiveState()
    return active?.toPreviewSnapshot()
}

private fun LyricProducerState.toPreviewSnapshot(): LyricSnapshot = LyricSnapshot(
    revision = sequence,
    trackGeneration = generation.toLong(),
    updatedAtElapsedMs = sampledAtElapsedMs,
    visible = true,
    original = line,
    romanized = romanizedLine,
    translated = translatedLine,
    nextLine = nextLine,
    metadata = listOfNotNull(
        title.takeIf { it.isNotBlank() },
        artist.takeIf { it.isNotBlank() }
    ).joinToString(" · ").ifBlank { "HyperGlow" },
    alignedRight = alignedRight,
    lineLevelSync = words == null,
    lineStartMs = lineStartMs,
    lineEndMs = lineEndMs,
    durationMs = durationMs,
    positionMs = positionMs,
    sampledAtElapsedMs = sampledAtElapsedMs,
    speed = speed,
    words = (words ?: emptyList()).map {
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
    }
)

// 判断模块当前是否处于可用的运行状态(与 runtimeProfileAvailable 一致)。
internal fun resolveModuleWorking(state: XiaomiRuntimeSupportState): Boolean =
    state == XiaomiRuntimeSupportState.AVAILABLE ||
        state == XiaomiRuntimeSupportState.VERIFIED_PROFILE ||
        state == XiaomiRuntimeSupportState.VERIFIED_PROFILE_MISSING_SYMBOLS ||
        state == XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE
