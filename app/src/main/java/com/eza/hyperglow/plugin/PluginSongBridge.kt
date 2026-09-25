package com.eza.hyperglow.plugin

import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricSongSnapshot
import com.eza.hyperglow.producer.LyricWord
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginMediaInfo
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginWord

/**
 * Spicy 文档 ↔ PluginSong 的双向桥（纯函数）。
 *
 * 出向（[fromDocument]）：把 Spicy 整首文档映射为 HyperLyric 插件快照。行角色
 * （LEAD/伴奏等）放进 [PluginLyricLine.metadata]，因为 API 的行模型没有 role 字段，
 * 而回向选择活动行时必须复刻 Spicy 的 primaryRowAt 语义。
 *
 * 回向（[enrichState]）：用插件处理后的快照覆盖生产者逐行状态的**内容字段**
 * （line/translatedLine/romanizedLine/words/nextLine），时间轴字段（lineStartMs/
 * lineEndMs/positionMs 等）一律保留生产者权威值——插件 REPLACE 改时间轴时仅按
 * 新时间轴重选活动行，不改采样语义。
 */
object PluginSongBridge {

    /** 文档行角色进 metadata 的键名（内部约定，不进插件 API）。 */
    private const val META_ROLE = "role"

    fun fromDocument(document: SpicyBridgeDocument, state: LyricProducerState): PluginSong =
        PluginSong(
            id = state.trackUri.ifEmpty { null },
            name = state.title.ifEmpty { null },
            artist = state.artist.ifEmpty { null },
            album = state.album.ifEmpty { null },
            duration = state.durationMs,
            metadata = PluginMetadata(
                values = mapOf(
                    "producerId" to state.producerId,
                    "provider" to document.provider,
                    "language" to document.language.ifEmpty { null }
                )
            ),
            lyrics = document.rows.map { row ->
                PluginLyricLine(
                    begin = row.startMs,
                    end = row.endMs,
                    duration = (row.endMs - row.startMs).coerceAtLeast(0L),
                    isAlignedRight = row.alignedRight,
                    metadata = PluginMetadata(values = mapOf(META_ROLE to row.role)),
                    text = row.text,
                    words = row.words.takeIf { it.isNotEmpty() }?.map { word ->
                        PluginWord(
                            begin = word.startMs,
                            end = word.endMs,
                            duration = (word.endMs - word.startMs).coerceAtLeast(0L),
                            text = word.text
                        )
                    },
                    secondary = row.romanized.ifEmpty { null },
                    translation = row.translated.ifEmpty { null },
                    roma = row.romanized.ifEmpty { null }
                )
            }
        )

    /**
     * 整首快照（Lyricon/LyricInfo 直读内存行数组，或 SuperLyric 经 LineStreamAggregator
     * 聚合）→ PluginSong。与 [fromDocument] 镜像：行角色进 metadata，回向
     * [selectActiveRow] 复刻同一窗口语义。duration 取快照值——SuperLyric 聚合快照的
     * 时长是已见行的最大 endMs，不受其 durationMs 字段被挪用为行结束时间的影响。
     */
    fun fromSnapshot(state: LyricProducerState, snapshot: LyricSongSnapshot): PluginSong =
        PluginSong(
            id = state.trackUri.ifEmpty { null },
            name = state.title.ifEmpty { null },
            artist = state.artist.ifEmpty { null },
            album = state.album.ifEmpty { null },
            duration = snapshot.durationMs,
            metadata = PluginMetadata(
                values = mapOf("producerId" to state.producerId)
            ),
            lyrics = snapshot.rows.map { row ->
                PluginLyricLine(
                    begin = row.startMs,
                    end = row.endMs,
                    duration = (row.endMs - row.startMs).coerceAtLeast(0L),
                    isAlignedRight = false,
                    metadata = PluginMetadata(values = mapOf(META_ROLE to row.role)),
                    text = row.text,
                    words = row.words?.takeIf { it.isNotEmpty() }?.map { word ->
                        PluginWord(
                            begin = word.startMs,
                            end = word.endMs,
                            duration = (word.endMs - word.startMs).coerceAtLeast(0L),
                            text = word.text
                        )
                    },
                    secondary = row.roma.ifEmpty { null },
                    translation = row.translation.ifEmpty { null },
                    roma = row.roma.ifEmpty { null }
                )
            }
        )

    fun mediaInfo(state: LyricProducerState): PluginMediaInfo = PluginMediaInfo(
        title = state.title.ifEmpty { null },
        artist = state.artist.ifEmpty { null },
        album = state.album.ifEmpty { null },
        duration = state.durationMs.takeIf { it > 0L }
    )

    /**
     * 用处理后的快照富化生产者状态。返回原实例（引用相等，保持引擎的
     * identity 校验语义）当：会话不匹配、无行、或插件实际没改任何相关字段。
     */
    fun enrichState(state: LyricProducerState, patched: PatchedSong): LyricProducerState {
        if (patched.sessionKey != sessionKey(state)) return state
        val rows = patched.song.lyrics ?: return state
        if (rows.isEmpty()) return state
        if (!patched.changedAnything()) return state
        val active = selectActiveRow(rows, state.positionMs) ?: return state

        var enriched = state
        if (PluginLyricField.TEXT in patched.changedLyricFields) {
            enriched = enriched.copy(line = keepUnlessBlank(active.text, enriched.line))
        }
        if (PluginLyricField.TRANSLATION in patched.changedLyricFields) {
            enriched = enriched.copy(
                translatedLine = keepUnlessBlank(active.translation, enriched.translatedLine)
            )
        }
        if (PluginLyricField.ROMA in patched.changedLyricFields) {
            enriched = enriched.copy(
                romanizedLine = keepUnlessBlank(active.roma, enriched.romanizedLine)
            )
        }
        if (PluginLyricField.WORDS in patched.changedLyricFields) {
            val patchedWords = active.words?.map { word ->
                LyricWord(
                    text = word.text.orEmpty(),
                    romanized = "",
                    startMs = word.begin,
                    endMs = word.end,
                    boundaryAfter = true
                )
            }?.takeIf { it.isNotEmpty() }
            // 空词表不覆盖生产者词级时间轴:逐字卡拉OK会因此整体失效。
            if (patchedWords != null) enriched = enriched.copy(words = patchedWords)
        }
        if (PluginLyricField.TEXT in patched.changedLyricFields) {
            enriched = enriched.copy(
                nextLine = keepUnlessBlank(nextLeadText(rows, active), enriched.nextLine)
            )
        }
        if (PluginSongField.NAME in patched.changedSongFields) {
            enriched = enriched.copy(title = patched.song.name ?: state.title)
        }
        if (PluginSongField.ARTIST in patched.changedSongFields) {
            enriched = enriched.copy(artist = patched.song.artist ?: state.artist)
        }
        if (PluginSongField.ALBUM in patched.changedSongFields) {
            enriched = enriched.copy(album = patched.song.album ?: state.album)
        }
        return enriched
    }

    /** 复刻 SpicyBridgeDocument.primaryRowAt：窗口内 LEAD 优先（更晚 start 胜出），否则首个其它行。 */
    private fun selectActiveRow(rows: List<PluginLyricLine>, positionMs: Long): PluginLyricLine? {
        var lead: PluginLyricLine? = null
        var other: PluginLyricLine? = null
        for (row in rows) {
            if (positionMs < row.begin || positionMs >= row.end) continue
            if (row.metadata?.values?.get(META_ROLE) == "LEAD") {
                if (lead == null || row.begin >= lead.begin) lead = row
            } else if (other == null) {
                other = row
            }
        }
        return lead ?: other
    }

    private fun nextLeadText(rows: List<PluginLyricLine>, active: PluginLyricLine): String? {
        val candidates = rows.filter {
            it.metadata?.values?.get(META_ROLE) == "LEAD" &&
                it.begin >= active.end && !it.text.isNullOrEmpty()
        }
        return candidates.minByOrNull { it.begin }?.text
    }

    /**
     * 插件侧空内容不覆盖非空生产者值。回向是"用插件结果增强显示"，不是"用插件结果替换
     * 显示"：插件缺字段、输出空行或快照只聚合到半截时抹掉正在显示的歌词 = AOD 歌词消失。
     */
    private fun keepUnlessBlank(pluginValue: String?, producerValue: String): String =
        pluginValue?.takeIf { it.isNotBlank() } ?: producerValue

    fun sessionKey(state: LyricProducerState): String =
        "${state.producerId}:${state.generation}:${state.trackUri}"
}

/** 插件链对当前会话的处理结果（供 [PluginPipeline] 缓存、[PluginSongBridge.enrichState] 消费）。 */
data class PatchedSong(
    val sessionKey: String,
    val song: PluginSong,
    val changedSongFields: Set<PluginSongField>,
    val changedLyricFields: Set<PluginLyricField>
) {
    fun changedAnything(): Boolean =
        changedSongFields.isNotEmpty() || changedLyricFields.isNotEmpty()
}
