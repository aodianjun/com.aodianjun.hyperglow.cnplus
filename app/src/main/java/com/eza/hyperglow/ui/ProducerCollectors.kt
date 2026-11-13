package com.eza.hyperglow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import com.eza.hyperglow.aod.XiaomiRuntimeSupportState
import com.eza.hyperglow.aod.PLAYING_PLACEHOLDER
import com.eza.hyperglow.aod.interludeSpan
import com.eza.hyperglow.customization.composeSongMetadata
import com.eza.hyperglow.plugin.PluginPipeline
import com.eza.hyperglow.producer.ArtworkFrame
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricProducers
import com.eza.hyperglow.producer.LyricSource
import com.eza.hyperglow.producer.ProducerConnection
import com.eza.hyperglow.producer.SongArtworkRepository
import com.eza.hyperglow.producer.classifyCreditLines
import com.eza.hyperglow.producer.isSameTrackIdentity
import com.eza.hyperglow.producer.stripDuetMarker
import com.eza.hyperglow.producer.stripDuetMarkerWords
import com.eza.hyperglow.root.projection.LyricDuetLine
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
 * 已暂停的传输同样返回 null:仲裁器有意保留暂停时的冻结状态(见 `isFaulted`,暂停的位置
 * 流天然静默,不算故障),因此 `active` 在暂停后会一直停在暂停前那句歌词上——预览跟着
 * 冻在一句旧歌词上,而不是回退到演示歌词。判据见 [presentsLivePreview]。
 *
 * 歌曲信息按 [metadataParts]/[metadataSeparators](外观文档全局配置)用与实机投影层相同的
 * [composeSongMetadata] 组装,保证预览与实机所见即所得。歌曲图片同样与实机同源:
 * 订阅 [SongArtworkRepository.current](出帧后驱动重组),取与当前曲目同曲的已校对帧。
 *
 * 注意:不从这里读 SystemUiLyricProjection —— 那是 SystemUI 侧投影,app 进程内并不保证
 * 被喂入实时快照,会导致预览不更新。
 *
 * 读到的原始状态先过 [PluginPipeline.enrich]:并发行/和声(插件行表里的 role=BG 回声与对唱
 * 行)以及插件补的译文/音标只存在于富化后的状态里——实机在投影前同步跑这一步
 * (AodProjectionEngine.project → PluginPipeline.enrich → projectToDisplay),预览若读原始
 * active 就整条漏掉(真机 2026-10-07 的「预览没有并发/和声」)。enrich 无插件结果时原样返回
 * 同一实例,不改变任何字段。
 */
@Composable
internal fun collectLiveSnapshot(
    metadataParts: String,
    metadataSeparators: String,
    duetMarkers: Boolean = true,
    hideAlbumWhenSameAsTitle: Boolean = false,
    hideCreditLines: Boolean = false
): LyricSnapshot? {
    val active by collectActiveState()
    // 封面帧出帧后驱动重组(帧到达前字段为空,预览不显示,与实机 fail-closed 一致)。
    val artworkFrame by SongArtworkRepository.current.collectAsState()
    val state = active?.takeIf { presentsLivePreview(it) } ?: return null
    return PluginPipeline.enrich(state).toPreviewSnapshot(
        metadataParts,
        metadataSeparators,
        duetMarkers,
        hideAlbumWhenSameAsTitle,
        artworkFrame,
        hideCreditLines
    )
}

/**
 * 预览是否呈现这份实时状态:只有**正在播放**的状态才接管预览。
 *
 * 背景:仲裁器刻意不清暂停时的冻结状态(暂停的位置流不推进是正常现象,而 AOD 要保留
 * 最后一句歌词),所以 `active` 在暂停后长期非 null 且内容停在暂停那一刻。若预览照单
 * 全收,就会一直显示那句旧歌词(用户看到的「预览卡住不变」),而不是回退到循环播放的
 * 演示歌词。这里把「在播」作为预览接管判据,与概览页「正在播放」/`projection state`
 * 的 paused 判定同一口径(见 LyricSourceComponents)。
 *
 * 锁屏/AOD 实机侧不受本函数影响——那是投影层的保留语义,本函数只服务于 App 内预览。
 * SuperLyric 以「有无当前行」派生 playing,暂停后该位仍为真,故其预览最长冻结到
 * 12 秒的 stale 窗口结束(届时仲裁器清 `active`,预览回退演示);这是既有派生语义,
 * 修正它属于生产者改动,会牵动实机保留行为,不在此处变更。
 */
internal fun presentsLivePreview(state: LyricProducerState): Boolean = state.playing

internal fun LyricProducerState.toPreviewSnapshot(
    metadataParts: String,
    metadataSeparators: String,
    duetMarkers: Boolean,
    hideAlbumWhenSameAsTitle: Boolean,
    artworkFrame: ArtworkFrame?,
    hideCreditLines: Boolean = false
): LyricSnapshot {
    val frame = artworkFrame?.takeIf {
        isSameTrackIdentity(title, artist, it.title, it.artist)
    }
    // 「不显示非歌词内容」与实机投影层共用同一分类函数(预览即实机):活动行是制作名单时
    // 按「无活动行」处理——有真实下一行则提前显示下一行(与实机空档预览同口径),否则留空;
    // 下一行是名单时清空「下一行」槽位,名单不会从主行搬到下一行。
    // 开关关闭时 classifyCreditLines 恒返回 false,以下分支与改动前逐字段一致。
    val creditFlags = classifyCreditLines(line, nextLine, hideCreditLines)
    val effectiveHasActiveLine =
        lineIndex >= 0 && line.isNotBlank() && !creditFlags.lineIsCredit
    val previewNextLine = creditFlags.lineIsCredit && !creditFlags.nextLineIsCredit &&
        nextLine.trim().isNotEmpty()
    // 长间奏窗口:与实机投影层同一判据函数(本行 end → 下一行 start,≥4s),让
    // 「长间奏显示倒计时圆点」在连上实时歌词源时与实机同拍(预览即实机)。面级开关与
    // 「显示下一行」的延迟映射不在这里做——由渲染侧按本面 profile 解析(见 LyricCanvasMapper)。
    val interlude = interludeSpan(
        lineStartMs = lineStartMs,
        lineEndMs = lineEndMs,
        hasActiveLine = effectiveHasActiveLine,
        nextLineStartMs = nextLineStartMs
    )
    return LyricSnapshot(
    revision = sequence,
    trackGeneration = generation.toLong(),
    updatedAtElapsedMs = sampledAtElapsedMs,
    visible = true,
    original = when {
        // 空档预览:主行显示下一行(实机同口径,名单不当主行)。
        previewNextLine -> if (duetMarkers) stripDuetMarker(nextLine) else nextLine
        // 名单行无真实下一行可顶替时,与实机同为「播放中占位符」,不留空白。
        creditFlags.lineIsCredit -> PLAYING_PLACEHOLDER
        else -> if (duetMarkers) stripDuetMarker(line) else line
    },
    romanized = if (previewNextLine) nextLineRomanized else romanizedLine,
    translated = if (previewNextLine) nextLineTranslated else translatedLine,
    nextLine = when {
        previewNextLine || creditFlags.nextLineIsCredit -> ""
        else -> if (duetMarkers) stripDuetMarker(nextLine) else nextLine
    },
    // 下一行辅助文字原样透传(与实机 projectToDisplay 同口径,不剥对唱标记);
    // 「显示第二行辅助文字」预览行构建直接消费这两个字段,缺了预览就少第四行。
    nextLineRomanized = if (previewNextLine || creditFlags.nextLineIsCredit) "" else nextLineRomanized,
    nextLineTranslated = if (previewNextLine || creditFlags.nextLineIsCredit) "" else nextLineTranslated,
    metadata = composeSongMetadata(
        title = title,
        artist = artist,
        album = album,
        parts = metadataParts,
        separators = metadataSeparators,
        hideAlbumWhenSameAsTitle = hideAlbumWhenSameAsTitle
    ).ifBlank { "HyperGlow" },
    // 本面「识别对唱标记」开启取标记识别版分侧,否则取元数据身份版(与实机按面选用同口径)。
    alignedRight = if (duetMarkers) alignedRightMarkers else alignedRight,
    alignedRightMarkers = alignedRightMarkers,
    // 行级同步判据与投影层同源(AodStateProjector.projectToDisplay:hasActiveLine && !showLargeMetadata):
    // 有活动行且非大元数据引导态。预览不建模换歌引导态,故取「有活动行」即实机稳态。
    // 此前用 `words == null`(无逐字时间)当判据,逐字源被判成非行级同步 → 主行渲染路径落到
    // 整块横扫,与实机(带行窗的逐字源走共享逐行扫光)不一致;演示快照恒写 true,只有连上
    // 实时歌词源才看得出来。活动行被判为名单时同步关闭(与实机的 effectiveHasActiveLine 同口径)。
    lineLevelSync = effectiveHasActiveLine,
    // 空档预览时行窗口取下一行起点(退化窗 → 进度恒 0 = 未唱),与实机 projectToDisplay 同口径。
    lineStartMs = if (effectiveHasActiveLine || !previewNextLine) lineStartMs else nextLineStartMs ?: 0L,
    lineEndMs = if (effectiveHasActiveLine || !previewNextLine) lineEndMs else nextLineStartMs ?: 0L,
    durationMs = durationMs,
    positionMs = positionMs,
    sampledAtElapsedMs = sampledAtElapsedMs,
    speed = speed,
    words = if (!effectiveHasActiveLine) emptyList() else
        (if (duetMarkers) stripDuetMarkerWords(words ?: emptyList()) else words ?: emptyList()).map {
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
    ruby = if (!effectiveHasActiveLine) emptyList() else ruby.map { LyricRuby(it.start, it.end, it.reading) },
    layoutGroups = if (!effectiveHasActiveLine) emptyList() else layoutGroups.map {
        LyricLayoutGroup(it.start, it.end, it.kind, it.keepTogether, it.confidence)
    },
    artworkJpeg = frame?.jpeg ?: ByteArray(0),
    artworkKey = frame?.key ?: "",
    // 对唱并发行:与主行同口径按本面「识别对唱标记」剥离文本/词表并选分侧,剥空(纯标记行)
    // 整条丢弃;是否上屏由渲染侧按本面「显示并发歌词(对唱)」门控(与实机 LyricCanvasMapper 同源)。
    duetLine = if (!effectiveHasActiveLine) null else toPreviewDuetLine(duetMarkers),
    interludeStartMs = interlude?.first ?: 0L,
    interludeEndMs = interlude?.last ?: 0L
    )
}

internal fun LyricProducerState.toPreviewDuetLine(duetMarkers: Boolean): LyricDuetLine? =
    duetLine?.let { line ->
        val duetText = if (duetMarkers) stripDuetMarker(line.text) else line.text
        if (duetText.isBlank()) {
            null
        } else {
            LyricDuetLine(
                text = duetText,
                romanized = line.romanized,
                translated = line.translated,
                alignedRight = if (duetMarkers) line.alignedRightMarkers else line.alignedRight,
                alignedRightMarkers = line.alignedRightMarkers,
                harmony = line.harmony,
                lineStartMs = line.lineStartMs,
                lineEndMs = line.lineEndMs,
                // 逐字卡拉OK按词绘制,标记剥离必须与并发行文本同源,否则标记残留/错位。
                words = (if (duetMarkers) stripDuetMarkerWords(line.words) else line.words).map {
                    LyricWord(
                        text = it.text,
                        romanized = it.romanized,
                        startMs = it.startMs,
                        endMs = it.endMs,
                        boundaryAfter = it.boundaryAfter,
                        sourceStart = it.sourceStart,
                        sourceEnd = it.sourceEnd
                    )
                }
            )
        }
    }

// 判断模块当前是否处于可用的运行状态(与 runtimeProfileAvailable 一致)。
internal fun resolveModuleWorking(state: XiaomiRuntimeSupportState): Boolean =
    state == XiaomiRuntimeSupportState.AVAILABLE ||
        state == XiaomiRuntimeSupportState.VERIFIED_PROFILE ||
        state == XiaomiRuntimeSupportState.VERIFIED_PROFILE_MISSING_SYMBOLS ||
        state == XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE
