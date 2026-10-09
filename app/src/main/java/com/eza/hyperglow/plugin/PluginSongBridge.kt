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
import kotlin.math.abs

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
    internal const val META_ROLE = "role"

    /** 和声行角色值(与 amll-ttml 的 TtmlMapper.ROLE_BG 同一约定)。 */
    internal const val ROLE_BG = "BG"

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
     *
     * 对唱行表按**声部槽位**选行(owner 2026-10-08「每行钉在一个声部上」,见 [assignVoiceSlots]):
     * 主行恒取槽位 0 的活动行、并发行恒取槽位 1 的活动行——源里声明了 `ttm:agent` 按身份映射,
     * 没声明时由宿主自动分配默认两个声部;单声部行表(所有非 BG 行都落槽位 0)走与改动前
     * 逐字节同路。注意行内容钉在槽位后,生产者下发的行窗(lineStartMs/lineEndMs)仍可能属于
     * **另一声部**(生产者行表是顺序铺开的,实测《乐鸣东方》两行窗口整体错位),主行的扫光因此
     * 依赖插件下发的逐词窗口——带词表的行本来就走词级卡拉OK,行窗只作无词表的兜底。
     */
    fun enrichState(state: LyricProducerState, patched: PatchedSong): LyricProducerState {
        if (patched.sessionKey != sessionKey(state)) return state
        val rows = patched.song.lyrics ?: return state
        if (rows.isEmpty()) return state
        if (!patched.changedAnything()) return state
        // 声部槽位:对唱行表里两位演唱者的行窗互相重叠,而生产者行表是顺序的——不钉槽位时
        // 生产者当前句一翻(实测《乐鸣东方》108.350s),屏上两行整体互换,正在读的那一行跳到
        // 另一个位置。槽位 0 = 第一声部(主行),槽位 1 = 第二声部(并发行车道)。
        val slots = assignVoiceSlots(rows)
        val hasSecondVoice = slots.any { it == VOICE_SLOT_SECONDARY }
        // 单声部行表(绝大多数曲目):主行候选集就是整张表,与钉槽位前逐字节同路——统一形状
        // 不能顺手把老路径也换成槽位,这是本次改动的最大回归面。
        val mainRows = if (hasSecondVoice) {
            rows.filterIndexed { index, _ -> slots[index] == VOICE_SLOT_PRIMARY }
        } else {
            rows
        }
        // **并发段**判据(owner 2026-10-09「进入和退出并发的时候歌词排序有问题」):两位
        // 演唱者**此刻都在场**才算并发——槽位 0 与槽位 1 都取得到当前行。只有第一声部在场、
        // 第二声部不在场时,按槽位钉行的三条(候选集收窄到槽位 0、按位置取、槽位 0 兜底保持)
        // 会连成一条错误链:候选集收窄后生产者正在唱的那一句(实测音频已在唱 v2 的
        // `天地为引 归巢为依`,而它落在槽位 1)不在候选集里;位置落在两个声部行窗的错位缝隙
        // 里也取不到行;最后主行兜底又把槽位 0 上一句一直保持到第二声部开口——实测《乐鸣
        // 东方》109.000–119.700 整 10.7s 主行冻在 `乐鸣东方` 不动(退出并发时同形,
        // 154.300–155.800 冻 1.5s)。并发段外不存在「被另一声部顶替」的可能,把候选集
        // 放开到整张表、由行身份决定,才不会冻行。
        val concurrent = hasSecondVoice &&
            voiceSlotRowIndexAt(rows, slots, VOICE_SLOT_PRIMARY, state.positionMs) >= 0 &&
            voiceSlotRowIndexAt(rows, slots, VOICE_SLOT_SECONDARY, state.positionMs) >= 0
        // 活动行:**先按行身份**(生产者当前那一句在候选行里找同一句),位置只作兜底。
        // 顺序不可颠倒:对唱曲目的插件行表里两个声部的行互相重叠,而插件 TTML 的行窗与
        // 生产者行窗存在错位(实测 v1 长行 147.4–154.3 横跨 v2 的两句),按位置取会落到
        // 另一声部的行上——主行就显示第二声部的文本、屏上第二行「跳」到第一行
        // (owner 2026-10-08 真机反馈)。行身份来自生产者,是「现在唱的是哪一句」的唯一
        // 权威;位置仅用于在同句的多次出现之间挑覆盖本次位置的那一次。
        val identityRows = if (concurrent) mainRows else rows
        val mainFallback = if (concurrent) {
            // 并发段里本声部这一句唱完、下一句还没到:保留本声部最后一行。不退回生产者行——
            // 生产者的当前句可能正是另一声部的行(退回就是上次修的互换)。
            rows.getOrNull(
                voiceSlotLastEndedRowIndex(rows, slots, VOICE_SLOT_PRIMARY, state.positionMs)
            )
        } else {
            null
        }
        val active = activeRowByText(identityRows, state.line, state.positionMs)
            ?: selectActiveRow(identityRows, state.positionMs)
            ?: mainFallback
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
        // ②双声部行表取**槽位 1 的当前行**(只随第二声部自己的行窗变,不再随主行/生产者
        // 当前句重选——屏上第二行的内容因此不随主行换行互换,owner 2026-10-08);
        // ③单声部行表退回既有的纯时间窗重叠判定(对唱并发行,与改动前逐字节同路)。
        // 绘制侧仍按和声自己的时间窗门控(见 AodLyricCanvasView.drawDuetOriginal);和声身份
        // 随行进入 [LyricDuetLine.harmony],渲染侧据此走辅助行车道。
        if (replacedLyricRows(patched)) {
            val primaryIndex = rows.indexOf(active)
            // 诊断探针:插件替换歌词后记录行表构成与并发行挂载结果(真机判定和声行有没有被认出来)。
            HookLogger.iThrottled("duet-attach", 5_000L, "PluginSongBridge") {
                val bg = rows.count(::isHarmonyRow)
                val acc = accompanimentRowIndex(rows, rows.indexOf(active))
                val pick = if (acc >= 0) rows[acc].text?.take(20) else "(none)"
                "Duet attach: rows=${rows.size} bg=$bg twoVoice=$hasSecondVoice " +
                    "active=${active.text?.take(16)} primaryIdx=${rows.indexOf(active)} " +
                    "accIdx=$acc pick=$pick"
            }
            val accompanimentIndex = accompanimentRowIndex(rows, primaryIndex)
            val duetIndex = when {
                accompanimentIndex >= 0 -> accompanimentIndex
                // 并发段才用槽位 1 的当前行;两位演唱者不在同一时刻在场时并发行离场(与
                // [concurrent] 同一判据——进入/退出并发时第二行不该还挂在已经唱完的那一句上,
                // 实测《乐鸣东方》229.800–230.700 主行已在唱 `乐鸣东方`,第二行却仍挂着
                // v2 的 `万物皆有声 随风作乐章`)。
                concurrent ->
                    voiceSlotRowIndexAt(rows, slots, VOICE_SLOT_SECONDARY, state.positionMs)
                hasSecondVoice -> -1
                else -> selectDuetLineIndex(
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
                        // 和声身份取自被选中行自己的角色,而不是「走的哪条选取分支」:
                        // accompanimentRowIndex 已保证 role=BG 才会走到这里,时间窗回退选中的
                        // 是不同演唱者的对唱行(渲染侧与主行同款并排)。
                        harmony = isHarmonyRow(row),
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

    /**
     * 复刻 SpicyBridgeDocument.primaryRowAt：窗口内 LEAD 优先（更晚 start 胜出），否则首个其它行。
     * 和声行（role=BG）不参与主行选择——它是并发行车道的内容：主行的窗口比插件行粗
     * （实测同一句生产者 20.6s vs 插件 5.2s），换行间隙里只有和声行覆盖位置，取到它
     * 就等于把第二行（和声）的文本显示到第一行（owner 2026-10-07 真机反馈）。
     */
    private fun selectActiveRow(rows: List<PluginLyricLine>, positionMs: Long): PluginLyricLine? {
        var lead: PluginLyricLine? = null
        var other: PluginLyricLine? = null
        for (row in rows) {
            if (positionMs < row.begin || positionMs >= row.end) continue
            if (isHarmonyRow(row)) continue
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
     * 按行身份选活动行:生产者当前显示的那一句在插件行表里找同一句。行身份是「现在唱的
     * 是哪一句」的权威——插件行窗与生产者行窗会错位(对唱段实测 v1 长行 147.4–154.3 横跨
     * v2 的两句 148.7–151.8 / 152.2–155.8),按位置取会在错位窗口里落到另一声部的行上。
     *
     * 两侧的空白/标点常有差异(如「人间百相 总让我神往」vs「人间百相总让我神往」),
     * 归一化后比较。同一句在行表里可能多次出现(副歌重复):取覆盖本次位置的哪一次,
     * 都不覆盖时取时间上最近的那一次——不能恒取行表里的第一次,否则词级时间轴会跳回
     * 第一段副歌。找不到返回 null(调用方回退按位置取,保持生产者状态不动)。
     *
     * 和声行(role=BG)同样排除:归一化会抹掉回声行的括号,按文本回退时不得把和声行
     * 认成主行(见 [selectActiveRow])。
     */
    private fun activeRowByText(
        rows: List<PluginLyricLine>,
        producerLine: String,
        positionMs: Long
    ): PluginLyricLine? {
        val needle = normalizeLyricText(producerLine)
        if (needle.isEmpty()) return null
        val matches = rows.filter {
            !isHarmonyRow(it) && normalizeLyricText(it.text.orEmpty()) == needle
        }
        if (matches.isEmpty()) return null
        return matches.firstOrNull { positionMs >= it.begin && positionMs < it.end }
            ?: matches.minByOrNull { abs(it.begin - positionMs) }
    }

    /** 和声行判据(role=BG,AMLL TTML 的 x-bg 回声):只走并发行车道,不参与主行选择。 */
    private fun isHarmonyRow(row: PluginLyricLine): Boolean =
        row.metadata?.values?.get(META_ROLE) == ROLE_BG

    /**
     * 行文本归一化:去空白与常见中英标点,只留实义字符;再把**旧字形/异体字**折到同一个
     * 代表字上(见 [VARIANT_FOLD])。
     *
     * 为什么要折:行身份匹配拿「生产者的当前句」与「插件行」逐码位比,而 Kotlin/Java 的
     * 字符串相等走 Unicode 码位、**不做兼容等价**。两侧歌词来源不同(LRC 由上传者手抄、
     * TTML 由曲库编排),同一个字常一个是旧字形、一个是规范字形——实测《乐鸣东方》生产者
     * `天地为引 归巣为依` 用 U+5DE3 巣,TTML 用 U+5DE2 巢,其余码位全同。逐码位比因此
     * 整体失配,`activeRowByText` 返回 null,主行退回按位置取/槽位兜底——并发段刚进入或
     * 刚退出的那一刻正是它最需要命中行身份的时刻。
     *
     * 只折真机数据里实际出现的那几组,不铺开成通用的模糊匹配或转写层:泛化的音近/形近
     * 匹配会把不同句子认成同一句,反而破坏行身份的权威。
     */
    private fun normalizeLyricText(text: String): String =
        buildString(text.length) {
            for (ch in text) {
                if (ch.isWhitespace() ||
                    ch in "（）()【】[]「」『』《》〈〉<>·、,，。.！!？?~～-—…:：;；\"'“”‘’"
                ) continue
                append(VARIANT_FOLD[ch] ?: ch)
            }
        }

    /**
     * 旧字形/异体字 → 规范字形(只收真机数据里实测出现的组;键 = 生产者侧写成的字形,
     * 值 = 曲库 TTML 用的字形,归一化的比较键取后者)。
     */
    private val VARIANT_FOLD: Map<Char, Char> = mapOf(
        // 巢:《乐鸣东方》「天地为引 归巣/巢为依」——生产者(LRC)写旧字形 巣 U+5DE3,
        // 曲库 TTML 写规范字形 巢 U+5DE2。
        '\u5DE3' to '\u5DE2'
    )

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
                    isHarmonyRow(row) &&
                    !row.text.isNullOrBlank() &&
                    minOf(primary.end, row.end) - maxOf(primary.begin, row.begin) >= MIN_CONCURRENT_OVERLAP_MS
            }
            .maxByOrNull { it.value.begin }
            ?.index
            ?: -1
    }

    /**
     * 插件是否改动了**歌词行本身**（文本/词表/时间轴/角色等行级字段）。
     *
     * 不能判 `PluginSongField.LYRICS in changedSongFields`：`PluginPipeline.diff()` 会显式把
     * LYRICS 从 songFields 里过滤掉（见其 `songFields.filter { it != PluginSongField.LYRICS }`），
     * 行级变化一律走 `changedLyricFields`（REPLACE 整表替换时按新表内容标 TEXT/WORDS 等）。
     * 只改译文/罗马音的 PATCH 不算（TRANSLATION/TRANSLATION_WORDS/ROMA 属内容字段）——那种
     * 情况下行表仍是生产者的，不能借机清掉生产者已算出的并发行候选。
     */
    private fun replacedLyricRows(patched: PatchedSong): Boolean =
        PluginSongField.LYRICS in patched.changedSongFields ||
            patched.changedLyricFields.any { it in ROW_LEVEL_LYRIC_FIELDS }

    /** 行级歌词字段：出现任一即视为行表被插件改写（与 [replacedLyricRows] 配套）。 */
    private val ROW_LEVEL_LYRIC_FIELDS = setOf(
        PluginLyricField.TEXT,
        PluginLyricField.WORDS,
        PluginLyricField.BEGIN,
        PluginLyricField.END,
        PluginLyricField.DURATION,
        PluginLyricField.IS_ALIGNED_RIGHT,
        PluginLyricField.METADATA,
        PluginLyricField.SECONDARY,
        PluginLyricField.SECONDARY_WORDS
    )

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
