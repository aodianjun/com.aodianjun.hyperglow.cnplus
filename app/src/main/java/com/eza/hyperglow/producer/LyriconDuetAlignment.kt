package com.eza.hyperglow.producer

import io.github.proify.lyricon.lyric.model.RichLyricLine

/**
 * Lyricon 行 → 对唱左右分侧的映射与取值（分侧语义见 [resolveDuetAlignment]）。
 *
 * 与 [LyriconLyricProducer.refreshRenderModes] 同生命周期：起始与切歌时重算整首快照，
 * `emit()` 侧只按活动行索引取值——身份排序与「首个歌手」依赖整首出现顺序，不能按单行现算。
 */

/**
 * 重算整首对唱快照，结果存入 [LyriconLyricProducer.duetResolvedAlignedRight]。
 *
 * 演唱者身份来自行级元数据（`agent`/`amll:agent`/`vocal`/`amll:vocal` 键族，与 HyperLyric 同键名，
 * 见 [duetAgentId]/[duetAgentType]）；源已显式标记 `isAlignedRight` 的行恒保留该值。
 * 无身份信息的曲目结果与源值逐行相等——即对既有行为零影响。
 */
@Synchronized
internal fun LyriconLyricProducer.refreshDuetAlignment(lyrics: List<RichLyricLine>) {
    val lines = lyrics.map { line ->
        DuetLine(
            agentId = duetAgentId(line.metadata),
            agentType = duetAgentType(line.metadata),
            sourceAlignedRight = line.isAlignedRight
        )
    }
    duetResolvedAlignedRight = resolveDuetAlignment(lines, enabled = true).toBooleanArray()
}

/**
 * 活动行是否右对齐。无快照（尚未收到歌）或索引越界恒为 false。
 * 是否真正按右对齐绘制由渲染侧的「对唱分侧」开关决定（见 root/aod/duetAlignedRight）。
 */
internal fun LyriconLyricProducer.activeAlignedRight(lineIndex: Int): Boolean {
    if (lineIndex < 0) return false
    return duetResolvedAlignedRight?.getOrNull(lineIndex) ?: false
}
