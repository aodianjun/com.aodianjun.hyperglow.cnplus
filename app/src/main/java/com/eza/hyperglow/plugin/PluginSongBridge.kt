package com.eza.hyperglow.plugin

import com.eza.hyperglow.bridge.SpicyBridgeDocument
import com.eza.hyperglow.producer.DuetLineWindow
import com.eza.hyperglow.producer.LyricDuetLine
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricSongSnapshot
import com.eza.hyperglow.producer.LyricWord
import com.eza.hyperglow.producer.MIN_CONCURRENT_OVERLAP_MS
import com.eza.hyperglow.producer.selectDuetLineIndex
import com.eza.hyperglow.root.HookLogger
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
 * （line/translatedLine/romanizedLine/words/nextLine/nextLineRomanized/nextLineTranslated），
 * 时间轴字段（lineStartMs/
 * lineEndMs/positionMs 等）一律保留生产者权威值——插件 REPLACE 改时间轴时仅按
 * 新时间轴重选活动行，不改采样语义。
 */
object PluginSongBridge {

    /** 文档行角色进 metadata 的键名（内部约定，不进插件 API）。 */
    private const val META_ROLE = "role"

    /** 和声行角色值(与 amll-ttml 的 TtmlMapper.ROLE_BG 同一约定)。 */
    private const val ROLE_BG = "BG"

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
                    isAlignedRight = row.alignedRight,
                    metadata = PluginMetadata(values = mapOf(META_ROLE to row.role)),
                    text = row.text,
                    words = toPluginWords(row.words),
                    secondary = row.roma.ifEmpty { null },
                    // 翻译冗余对随行过桥:文本兜底取文 + 词表原样携带(见 LyricSongRow.effectiveTranslation)。
                    translation = row.effectiveTranslation().ifEmpty { null },
                    translationWords = toPluginWords(row.translationWords),
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
        // 活动行:优先按位置取;位置落在插件行表的**间隙**里时按行身份(文本)回退。
        // 插件 TTML 的行窗比生产者行窗细(实测同一句 5.2s vs 20.5s),位置采样又按行跳,
        // 因此「位置恰好落在插件行内」并不可靠——按「是哪一句」认行才与 HyperLyric 的
        // 呈现边界模型一致(宿主拿到已解析行后按身份决定显示,而不是按位置恰好命中)。
        val active = selectActiveRow(rows, state.positionMs)
            ?: activeRowByText(rows, state.line)
            ?: return state

        var enriched = state
        if (PluginLyricField.TEXT in patched.changedLyricFields) {
            enriched = enriched.copy(line = keepUnlessBlank(active.text, enriched.line))
        }
        if (PluginLyricField.TRANSLATION in patched.changedLyricFields ||
            PluginLyricField.TRANSLATION_WORDS in patched.changedLyricFields
        ) {
            // 翻译冗余对:文本与词表任一被声明变化都按兜底取文回填——只给词表的插件结果
            // (如 AI 翻译同给 TRANSLATION/TRANSLATION_WORDS)不能因为文本缺位被丢掉。
            enriched = enriched.copy(
                translatedLine = keepUnlessBlank(effectiveTranslation(active), enriched.translatedLine)
            )
        }
        if (PluginLyricField.ROMA in patched.changedLyricFields) {
            enriched = enriched.copy(
                romanizedLine = keepUnlessBlank(active.roma, enriched.romanizedLine)
            )
        }
        // 对唱左右分侧:插件显式声明改了该字段才覆盖(未声明时保留生产者自己的
        // 演唱者身份推导结果,见 producer/resolveDuetAlignment)。
        if (PluginLyricField.IS_ALIGNED_RIGHT in patched.changedLyricFields) {
            // 插件显式对齐回写两套一起覆盖(插件不区分标记身份,两版等价)。
            enriched = enriched.copy(
                alignedRight = active.isAlignedRight,
                alignedRightMarkers = active.isAlignedRight
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
        if (PluginLyricField.TRANSLATION_WORDS in patched.changedLyricFields) {
            // 插件提供的逐字翻译词表(词级译文 + 时间窗):整表替换,词表文本即译文片段,
            // 直接相连构成整行译文(分隔符由插件写在片段内)。空表不覆盖已有词表
            // (与 keepUnlessBlank 同口径:插件没给词表不该抹掉已给的词级时间)。
            val patchedWords = active.translationWords?.map { word ->
                LyricWord(
                    text = word.text.orEmpty(),
                    romanized = "",
                    startMs = word.begin,
                    endMs = word.end,
                    boundaryAfter = true
                )
            }?.takeIf { it.isNotEmpty() }
            if (patchedWords != null) enriched = enriched.copy(translationWords = patchedWords)
        }
        if (PluginLyricField.TEXT in patched.changedLyricFields ||
            PluginLyricField.TRANSLATION in patched.changedLyricFields ||
            PluginLyricField.TRANSLATION_WORDS in patched.changedLyricFields ||
            PluginLyricField.ROMA in patched.changedLyricFields
        ) {
            // 下一行组(文本/音标/翻译)随插件行表回填:「辅助文字显示第二行歌词」消费下一行
            // 的音标/翻译——行表里有就给,且与 nextLine 取同一行(四行呈现的第三/四行描述
            // 同一句)。各字段按声明的变化集合分别回填(keepUnlessBlank:插件空值不覆盖
            // 生产者非空值,增强而非替换)。
            val nextLeadRow = rows.asSequence()
                .filter {
                    it.metadata?.values?.get(META_ROLE) == "LEAD" &&
                        it.begin >= active.end && !it.text.isNullOrEmpty()
                }
                .minByOrNull { it.begin }
            if (PluginLyricField.TEXT in patched.changedLyricFields) {
                enriched = enriched.copy(
                    nextLine = keepUnlessBlank(nextLeadRow?.text, enriched.nextLine)
                )
            }
            if (PluginLyricField.TRANSLATION in patched.changedLyricFields ||
                PluginLyricField.TRANSLATION_WORDS in patched.changedLyricFields
            ) {
                enriched = enriched.copy(
                    nextLineTranslated = keepUnlessBlank(
                        nextLeadRow?.let { effectiveTranslation(it) },
                        enriched.nextLineTranslated
                    )
                )
            }
            if (PluginLyricField.ROMA in patched.changedLyricFields) {
                enriched = enriched.copy(
                    nextLineRomanized = keepUnlessBlank(nextLeadRow?.roma, enriched.nextLineRomanized)
                )
            }
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
        // 并发行重选:插件 REPLACE 后的行表才是最终行表——AMLL TTML 的对唱 agent 行与
        // x-bg 和声行只存在于这里(见 amll-ttml 的 TtmlMapper),而生产者的候选只从它自己
        // 的行表选(见 selectDuetLineIndex 的三个调用点),源行表首尾相接(网易云 LRC 常态,
        // end == next.begin)时恒为 -1,并发行永远不出现。
        //
        // 选取顺序参照 HyperLyric 的呈现边界:①**同句和声行**(role=BG 且与活动行共享窗口
        // ≥1s)——AMLL 的 x-bg 和声与主行同窗,HyperLyric 把它折进父行的 secondary 车道
        // 随父行一起显示;CN+ 的插件把它映射成独立行,所以这里按行身份把它挂回活动行,
        // 不要求位置落在和声自己的窗口内(父行窗口常远长于和声,如 20.5s vs 5.2s);
        // ②没有同句和声时退回既有的纯时间窗重叠判定(对唱并发行)。
        // 绘制侧仍按和声自己的时间窗门控(见 AodLyricCanvasView.drawDuetOriginal)。
        if (PluginSongField.LYRICS in patched.changedSongFields) {
            val primaryIndex = rows.indexOf(active)
            // 诊断探针:插件替换歌词后记录行表构成与并发行挂载结果——真机判定「和声行有没有被
            // 插件输出、有没有被认成同句和声」。与 duet-draw 探针配套:attach 有、draw 无 = 画布
            // 门控问题;attach 无 = 行表/配对问题。
            HookLogger.iThrottled("duet-attach", 5_000L, "PluginSongBridge") {
                val bg = rows.count { it.metadata?.values?.get(META_ROLE) == ROLE_BG }
                val acc = accompanimentRowIndex(rows, rows.indexOf(active))
                val pick = if (acc >= 0) rows[acc].text?.take(20) else "(none)"
                "Duet attach: rows=${rows.size} bg=$bg active=${active.text?.take(16)} " +
                    "primaryIdx=${rows.indexOf(active)} accIdx=$acc pick=$pick"
            }
            val accompanimentIndex = accompanimentRowIndex(rows, primaryIndex)
            val duetIndex = if (accompanimentIndex >= 0) {
                accompanimentIndex
            } else {
                selectDuetLineIndex(
                    windows = rows.map { DuetLineWindow(it.begin, it.end, it.text.isNullOrBlank()) },
                    primaryIndex = primaryIndex,
                    positionMs = state.positionMs
                )
            }
            val duetRow = rows.getOrNull(duetIndex)
            enriched = enriched.copy(
                duetLine = duetRow?.let { row ->
                    LyricDuetLine(
                        text = row.text.orEmpty(),
                        romanized = row.roma.orEmpty(),
                        translated = effectiveTranslation(row).orEmpty(),
                        alignedRight = row.isAlignedRight,
                        alignedRightMarkers = row.isAlignedRight,
                        lineStartMs = row.begin,
                        lineEndMs = row.end,
                        words = row.words?.map { word ->
                            LyricWord(
                                text = word.text.orEmpty(),
                                romanized = "",
                                startMs = word.begin,
                                endMs = word.end,
                                boundaryAfter = true
                            )
                        }.orEmpty()
                    )
                }
            )
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

    private fun nextLeadRow(rows: List<PluginLyricLine>, active: PluginLyricLine): PluginLyricLine? {
        val candidates = rows.filter {
            it.metadata?.values?.get(META_ROLE) == "LEAD" &&
                it.begin >= active.end && !it.text.isNullOrEmpty()
        }
        return candidates.minByOrNull { it.begin }
    }

    /**
     * 按行身份回退选活动行:生产者当前显示的那一句在插件行表里找同一句。
     * 两侧的空白/标点常有差异(如「人间百相 总让我神往」vs「人间百相总让我神往」),
     * 归一化后比较。找不到返回 null(调用方保持生产者状态不动)。
     */
    private fun activeRowByText(rows: List<PluginLyricLine>, producerLine: String): PluginLyricLine? {
        val needle = normalizeLyricText(producerLine)
        if (needle.isEmpty()) return null
        return rows.firstOrNull { normalizeLyricText(it.text.orEmpty()) == needle }
    }

    /** 行文本归一化:去空白与常见中英标点,只留实义字符。 */
    private fun normalizeLyricText(text: String): String =
        text.filterNot { ch ->
            ch.isWhitespace() || ch in "（）()【】[]「」『』《》〈〉<>·、,，。.！!？?~～-—…:：;；\"'“”‘’"
        }

    /**
     * 活动行的**同句和声行**下标(role=BG 且与活动行共享窗口 ≥ [MIN_CONCURRENT_OVERLAP_MS]);
     * 无则 -1。AMLL TTML 的 x-bg 和声与主行同窗(实测窗口完全相同),HyperLyric 把它折进
     * 父行的 secondary 车道随父行显示;CN+ 的插件映射成独立行,故在此按行身份挂回活动行。
     * 不要求位置落在和声窗口内——父行窗口常远长于和声本身,位置采样又是按行跳的。
     */
    private fun accompanimentRowIndex(rows: List<PluginLyricLine>, primaryIndex: Int): Int {
        if (primaryIndex !in rows.indices) return -1
        val primary = rows[primaryIndex]
        return rows.withIndex()
            .filter { (index, row) ->
                index != primaryIndex &&
                    row.metadata?.values?.get(META_ROLE) == ROLE_BG &&
                    !row.text.isNullOrBlank() &&
                    minOf(primary.end, row.end) - maxOf(primary.begin, row.begin) >= MIN_CONCURRENT_OVERLAP_MS
            }
            .maxByOrNull { it.value.begin }
            ?.index
            ?: -1
    }

    /**
     * 插件侧空内容不覆盖非空生产者值。回向是"用插件结果增强显示"，不是"用插件结果替换
     * 显示"：插件缺字段、输出空行或快照只聚合到半截时抹掉正在显示的歌词 = AOD 歌词消失。
     */
    private fun keepUnlessBlank(pluginValue: String?, producerValue: String): String =
        pluginValue?.takeIf { it.isNotBlank() } ?: producerValue

    /** 生产者 [LyricWord] → 插件 [PluginWord]；空词表按 null 过桥（API 语义同原映射）。 */
    private fun toPluginWords(words: List<LyricWord>?): List<PluginWord>? =
        words?.takeIf { it.isNotEmpty() }?.map { word ->
            PluginWord(
                begin = word.startMs,
                end = word.endMs,
                duration = (word.endMs - word.startMs).coerceAtLeast(0L),
                text = word.text
            )
        }

    /**
     * 插件行的翻译取文兜底（与 [LyricSongRow.effectiveTranslation] 同规则）：文本非空优先，
     * 否则由 translationWords 拼出。插件 API 的 translation/translationWords 是冗余对，
     * 只读文本字段会把只带词表的结果丢掉。
     */
    private fun effectiveTranslation(row: PluginLyricLine): String? =
        row.translation?.takeIf { it.isNotBlank() }
            ?: row.translationWords?.takeIf { it.isNotEmpty() }?.joinToString("") { it.text.orEmpty() }

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
