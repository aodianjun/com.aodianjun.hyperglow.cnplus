package com.eza.hyperglow.producer

import com.eza.hyperglow.AppLog
import com.eza.hyperglow.customization.CustomizationRepository
import io.github.proify.lyricon.lyric.model.RichLyricLine

/**
 * Lyricon 行 → 对唱左右分侧的映射与取值（分侧语义见 [resolveDuetAlignment]）。
 *
 * 与 [LyriconLyricProducer.refreshRenderModes] 同生命周期：起始与切歌时重算整首快照，
 * `emit()` 侧只按活动行索引取值——身份排序与「首个歌手」依赖整首出现顺序，不能按单行现算。
 *
 * 快照存两套(元数据身份版 / 标记识别版),[activeAlignedRight] 按文档级开关
 * 「识别对唱标记」([com.eza.hyperglow.customization.CustomizationDocument.duetMarkers]) 选用:
 * 开关只决定行首「（男）/（女）/（合）」演唱者标记是否作为身份输入(「（副歌）」等段落标记
 * 始终不作身份),源显式 `isAlignedRight` 两套都恒优先。开关变更即时生效(设置保存经
 * [LyricProducers.onCustomizationChanged] 重算,或随下次切歌)。
 */

/**
 * 刷新「识别对唱标记」开关缓存(文档级 duetMarkers;无上下文/读取失败按开启)。
 */
@Synchronized
internal fun LyriconLyricProducer.refreshDuetMarkerPolicy() {
    duetMarkersEnabled = runCatching {
        contextRef?.let { CustomizationRepository.loadCompiled(it).duetMarkers } ?: true
    }.getOrDefault(true)
}

/**
 * 重算整首对唱快照,结果存入 [LyriconLyricProducer.duetResolvedAlignedRight](元数据身份版)
 * 与 [LyriconLyricProducer.duetMarkerResolvedAlignedRight](标记识别版)。
 *
 * 演唱者身份来自行级元数据（`agent`/`amll:agent`/`vocal`/`amll:vocal` 键族,与 HyperLyric 同键名,
 * 见 [duetAgentId]/[duetAgentType]）;无元数据时,标记识别版以行首「（男）/（女）/（合）」
 * 演唱者文本标记兜底([parseDuetMarker],见 [duetMarkerAgentId];「（副歌）」等段落标记
 * 只剥离显示、不产出身份)。源已显式标记 `isAlignedRight` 的行恒保留该值。无身份信息的曲目
 * 两套结果与源值逐行相等——即对既有行为零影响。
 */
@Synchronized
internal fun LyriconLyricProducer.refreshDuetAlignment(lyrics: List<RichLyricLine>) {
    refreshDuetMarkerPolicy()
    val metadataLines = lyrics.map { line ->
        DuetLine(
            agentId = duetAgentId(line.metadata),
            agentType = duetAgentType(line.metadata),
            sourceAlignedRight = line.isAlignedRight
        )
    }
    duetResolvedAlignedRight = resolveDuetAlignment(metadataLines, enabled = true).toBooleanArray()
    var markerFallbackRows = 0
    val markerLines = lyrics.map { line ->
        val marker = parseDuetMarker(line.text.orEmpty())
        val metadataId = duetAgentId(line.metadata)
        val agentId = metadataId ?: marker?.let(::duetMarkerAgentId)
        if (metadataId == null && agentId != null) markerFallbackRows++
        DuetLine(
            agentId = agentId,
            agentType = duetAgentType(line.metadata),
            sourceAlignedRight = line.isAlignedRight
        )
    }
    val markerResolved = resolveDuetAlignment(markerLines, enabled = true).toBooleanArray()
    duetMarkerResolvedAlignedRight = markerResolved
    // 一行日志/次重算(切歌或设置变更,非逐帧):真机排障区分「无身份输入」与「已分侧」。
    AppLog.i(
        "LyriconDuetAlignment",
        "refresh: rows=${lyrics.size} identityRows=${markerLines.count { it.agentId != null }} " +
            "markerFallback=$markerFallbackRows rightRows=${markerResolved.count { it }} " +
            "markersEnabled=$duetMarkersEnabled"
    )
}

/**
 * 活动行的**元数据身份版**分侧。无快照（尚未收到歌）或索引越界恒为 false。
 * 是否真正按右对齐绘制由渲染侧的「对唱分侧」开关决定（见 root/aod/duetAlignedRight）。
 */
internal fun LyriconLyricProducer.identityAlignedRight(lineIndex: Int): Boolean {
    if (lineIndex < 0) return false
    return duetResolvedAlignedRight?.getOrNull(lineIndex) ?: false
}

/**
 * 活动行的**标记识别版**分侧（元数据身份缺失时以行首标记兜底）；无标记版快照时回落身份版。
 * 两套随状态下发，各渲染面按自己面的「识别对唱标记」开关选用。
 */
internal fun LyriconLyricProducer.markerAlignedRight(lineIndex: Int): Boolean {
    if (lineIndex < 0) return false
    return duetMarkerResolvedAlignedRight?.getOrNull(lineIndex) ?: identityAlignedRight(lineIndex)
}

/**
 * 兼容旧入口：按**文档级**「识别对唱标记」开关在两套之间选用。
 * 新的按面独立链路不再依赖它做显示决策（改为状态里同时下发两套），保留供既有测试/调用。
 */
internal fun LyriconLyricProducer.activeAlignedRight(lineIndex: Int): Boolean =
    if (duetMarkersEnabled) markerAlignedRight(lineIndex) else identityAlignedRight(lineIndex)

// 「外部设置变更」重算入口已收拢为 [LyriconLyricProducer.onCustomizationChanged] 成员覆写
// (歌词时间偏移缓存随同刷新,见 LyricTimeOffsetPolicy.kt)。
