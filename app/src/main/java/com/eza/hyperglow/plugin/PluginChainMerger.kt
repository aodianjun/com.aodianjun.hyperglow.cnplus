package com.eza.hyperglow.plugin

import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult

/**
 * 处理器结果的宿主侧校验与合并（纯函数，HyperLyric 合并语义）。
 *
 * 规则要点：
 * - `changedFields`/`changedLyricFields` 是权威声明，宿主绝不做 DTO 对比推断；
 * - PATCH 必须保持行数与行索引不变，只覆盖声明过的行字段（含显式置 null 清空）；
 * - REPLACE 整表替换，行时间轴必须单调合法（begin>=0, end>=begin）；
 * - 任何结构违规返回 null：宿主丢弃该结果、保留当前快照、继续后续处理器。
 */
object PluginChainMerger {

    fun merge(current: PluginSong, result: PluginSongResult): PluginSong? {
        if (PluginSongField.LYRICS !in result.changedFields) {
            return copyTopLevel(current, result)
        }
        val candidateRows = result.song.lyrics ?: return null
        val mergedRows = when (result.lyricsUpdateMode) {
            PluginLyricsUpdateMode.PATCH -> {
                val currentRows = current.lyrics ?: return null
                if (candidateRows.size != currentRows.size) return null
                candidateRows.mapIndexed { index, candidate ->
                    patchRow(currentRows[index], candidate, result.changedLyricFields)
                }
            }
            PluginLyricsUpdateMode.REPLACE -> {
                if (candidateRows.isEmpty()) return null
                candidateRows.forEachIndexed { index, row ->
                    if (row.begin < 0L || row.end < row.begin) {
                        return null
                    }
                }
                candidateRows.map { patchRow(it, it, result.changedLyricFields) }
            }
        }
        return copyTopLevel(current, result).copy(lyrics = mergedRows)
    }

    /** 按 changedFields 拷贝顶层字段（含 metadata 整体替换）；未声明字段保留 current。 */
    private fun copyTopLevel(current: PluginSong, result: PluginSongResult): PluginSong {
        val candidate = result.song
        return current.copy(
            id = if (PluginSongField.ID in result.changedFields) candidate.id else current.id,
            name = if (PluginSongField.NAME in result.changedFields) candidate.name else current.name,
            artist = if (PluginSongField.ARTIST in result.changedFields) candidate.artist else current.artist,
            album = if (PluginSongField.ALBUM in result.changedFields) candidate.album else current.album,
            duration = if (PluginSongField.DURATION in result.changedFields) {
                candidate.duration
            } else {
                current.duration
            },
            metadata = if (PluginSongField.METADATA in result.changedFields) {
                candidate.metadata
            } else {
                current.metadata
            },
            lyrics = if (PluginSongField.LYRICS in result.changedFields) {
                candidate.lyrics
            } else {
                current.lyrics
            }
        )
    }

    private fun patchRow(
        base: com.lidesheng.hyperlyric.plugin.api.PluginLyricLine,
        candidate: com.lidesheng.hyperlyric.plugin.api.PluginLyricLine,
        changed: Set<PluginLyricField>
    ): com.lidesheng.hyperlyric.plugin.api.PluginLyricLine = base.copy(
        begin = if (PluginLyricField.BEGIN in changed) candidate.begin else base.begin,
        end = if (PluginLyricField.END in changed) candidate.end else base.end,
        duration = if (PluginLyricField.DURATION in changed) candidate.duration else base.duration,
        isAlignedRight = if (PluginLyricField.IS_ALIGNED_RIGHT in changed) {
            candidate.isAlignedRight
        } else {
            base.isAlignedRight
        },
        metadata = if (PluginLyricField.METADATA in changed) candidate.metadata else base.metadata,
        text = if (PluginLyricField.TEXT in changed) candidate.text else base.text,
        words = if (PluginLyricField.WORDS in changed) candidate.words else base.words,
        secondary = if (PluginLyricField.SECONDARY in changed) candidate.secondary else base.secondary,
        secondaryWords = if (PluginLyricField.SECONDARY_WORDS in changed) {
            candidate.secondaryWords
        } else {
            base.secondaryWords
        },
        translation = if (PluginLyricField.TRANSLATION in changed) {
            candidate.translation
        } else {
            base.translation
        },
        translationWords = if (PluginLyricField.TRANSLATION_WORDS in changed) {
            candidate.translationWords
        } else {
            base.translationWords
        },
        roma = if (PluginLyricField.ROMA in changed) candidate.roma else base.roma
    )
}
