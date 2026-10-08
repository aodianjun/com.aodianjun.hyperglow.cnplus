package com.example.hyperglow.amllttml

import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.core.model.synced.SyncedLine
import com.mocharealm.accompanist.lyrics.core.parser.TTMLParser

/**
 * TTML 原文 → 插件行表。
 *
 * 解析交给 accompanist-lyrics-core 的 [TTMLParser]（逐字音节、翻译、音译、和声 x-bg、
 * 对唱 agent → 左右分侧都在库内完成），本映射做五件事：
 * 1. **对唱开关**：`duet` 关闭时忽略 alignment，全部行不分侧（对齐上游内置版的
 *    「启用对唱表演」设置项语义）；
 * 2. **和声开关**：`background` 关闭时丢弃和声行；开启时和声行作为**独立行**输出，
 *    角色写进 metadata 的 "role"（LEAD/BG）——与 Spicy 文档桥、lyricfetch 同一约定；
 * 3. **翻译开关**：`translation` 关闭时不挂翻译；
 * 4. **原文回扫**：库只认「内层带时轴」的 x-bg——规范 §5/§7.3 允许的行级 x-bg（纯文本）
 *    会被它整条丢弃，x-bg 内嵌的 x-roman 它也从不读取（和声行的 phonetic 恒为 null）。
 *    这两类都是规范内的合法写法，丢了就是内容损失，由 [scanRawLines] 在原文上找回；
 *    只在库没给出对应内容时兜底，库给出的部分一律不动。
 * 5. **演唱者身份**：`ttm:agent`（规范 §7.2：「在 `<p>` 标签上使用 `ttm:agent` 属性，并通过
 *    在 `<head>` 中定义的 `xml:id` (如 `v1`) 来指明演唱者。」）随行带进 metadata 的
 *    "agent"/"agentType"——库只用它算 alignment，算完即丢，宿主拿不到身份就分不清
 *    「真对唱（两位演唱者重叠）」与「单纯重叠」。x-bg 和声行没有自己的 agent，身份继承
 *    它所属的 `<p>`；`<p>` 没写 agent 就不挂键（不发明源里没有的值）。
 *
 * 行时间轴按 begin 排序、钳制到非负，空文本行丢弃（宁缺毋滥）。
 */
internal object TtmlMapper {

    const val META_ROLE = "role"
    const val ROLE_LEAD = "LEAD"
    const val ROLE_BG = "BG"

    /** 演唱者身份键：宿主按它推导对唱左右分侧（见 docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md）。 */
    const val META_AGENT = "agent"

    /** 演唱者类型键：`<p>` 上只有 id，类型来自 `<head>` 的声明（person/group/other…）。 */
    const val META_AGENT_TYPE = "agentType"

    fun map(
        ttml: String,
        duet: Boolean,
        translation: Boolean,
        background: Boolean,
    ): List<PluginLyricLine> {
        val synced = runCatching { TTMLParser().parse(ttml) }.getOrNull() ?: return emptyList()
        if (synced.lines.isEmpty()) return emptyList()

        // 回扫不再只服务于和声行：演唱者身份挂在每一行上，background 关掉也得带。
        // 扫描是纯读，畸形 XML 由 runCatching 兜住——扫不出来就退化成改动前的输出（宁缺毋滥）。
        val rawLines = runCatching { RawLineCursor(scanRawLines(ttml)) }.getOrNull()

        val rows = ArrayList<PluginLyricLine>(synced.lines.size + 8)
        for (line in synced.lines) {
            // 每个解析行取一次身份队列，取用节奏与解析行一一对应（同窗口多 <p> 时按文档序）。
            val raw = rawLines?.takeAgent(line.start.toLong(), line.end.toLong())
            when (line) {
                is KaraokeLine.MainKaraokeLine -> {
                    rows += mainLine(line, duet, translation, raw)
                    if (background) {
                        val bg = rawLines?.takeSpans(line.start.toLong(), line.end.toLong())
                        val parsed = line.accompanimentLines
                        if (parsed.isNullOrEmpty()) {
                            // 库没解析出和声行：本行若含行级 x-bg，其内容已被库丢掉，按原文补行。
                            if (bg != null) {
                                for (span in bg.spans) rawBgRow(span, bg)?.let(rows::add)
                            }
                        } else {
                            // 库解析出的和声行照旧输出，只补它从不填写的音译（见 RawBgSpan.roman）。
                            val romas = bg?.spans?.filter { it.syllables.isNotEmpty() }?.map { it.roman }
                            parsed.forEachIndexed { index, accompaniment ->
                                rows += accompanimentLine(
                                    accompaniment,
                                    translation,
                                    romas?.getOrNull(index),
                                    raw,
                                )
                            }
                        }
                    }
                }

                is KaraokeLine.AccompanimentKaraokeLine ->
                    if (background) rows += accompanimentLine(line, translation, raw = raw)

                is SyncedLine -> {
                    rows += plainLine(line, translation, raw)
                    if (background) {
                        // 逐行主唱同样可能有行级 x-bg（库对 SyncedLine 也没有和声概念）。
                        val bg = rawLines?.takeSpans(line.start.toLong(), line.end.toLong())
                        if (bg != null) {
                            for (span in bg.spans) rawBgRow(span, bg)?.let(rows::add)
                        }
                    }
                }

                else -> Unit // 未知行型：跳过而不是崩溃（插件链里任何异常都会被宿主吞掉）
            }
        }
        return sanitize(rows)
    }

    /** 库中版本是否有逐字覆盖（「仅升级为逐字歌词」的接受条件）。 */
    fun hasWordLevel(rows: List<PluginLyricLine>): Boolean {
        if (rows.isEmpty()) return false
        val withWords = rows.count { (it.words?.size ?: 0) >= 2 }
        return withWords * 2 >= rows.size
    }

    /**
     * 行元数据：角色恒有（LEAD/BG），演唱者身份只在该行所属的 `<p>` 真的写了 `ttm:agent` 时挂上。
     *
     * 规范 §4.1：agent 的 `type` 支持 `person` / `character` / `organization` / `group` /
     * `other`，id 惯例 `v1,v2,v3…`（`group→v1000`、`other→v2000`）；`<p>` 上只有 id，
     * `type` 由 [scanRawLines] 从 `<head>` 的 `<ttm:agent xml:id="…" type="…"/>` 声明解析。
     * 没声明（或声明里没写 type）就只挂 `agent`——不发明源里没有的值，宿主对缺省有自己的语义。
     */
    private fun rowMetadata(role: String, raw: RawLine?): PluginMetadata {
        val values = LinkedHashMap<String, String?>()
        values[META_ROLE] = role
        raw?.agent?.let { values[META_AGENT] = it }
        raw?.agentType?.let { values[META_AGENT_TYPE] = it }
        return PluginMetadata(values = values)
    }

    private fun mainLine(
        line: KaraokeLine.MainKaraokeLine,
        duet: Boolean,
        translation: Boolean,
        raw: RawLine?,
    ): PluginLyricLine = PluginLyricLine(
        begin = line.start.toLong(),
        end = line.end.toLong(),
        duration = (line.end - line.start).toLong().coerceAtLeast(0L),
        isAlignedRight = duet && line.alignment == KaraokeAlignment.End,
        metadata = rowMetadata(ROLE_LEAD, raw),
        text = line.syllables.joinToString("") { it.content },
        words = line.syllables.toWords(),
        translation = line.translation?.takeIf { translation && it.isNotBlank() },
        roma = line.phonetic?.takeIf { it.isNotBlank() },
    )

    private fun accompanimentLine(
        line: KaraokeLine.AccompanimentKaraokeLine,
        translation: Boolean,
        fallbackRoma: String? = null,
        raw: RawLine? = null,
    ): PluginLyricLine = PluginLyricLine(
        begin = line.start.toLong(),
        end = line.end.toLong(),
        duration = (line.end - line.start).toLong().coerceAtLeast(0L),
        // 和声行不参与对唱左右分侧（它挂在主行之下，分侧由主行表达）。
        isAlignedRight = false,
        // 身份继承父 `<p>`（x-bg span 自己没有 agent，规范 §7.2 只把 agent 定义在行上）。
        metadata = rowMetadata(ROLE_BG, raw),
        text = line.syllables.joinToString("") { it.content },
        words = line.syllables.toWords(),
        translation = line.translation?.takeIf { translation && it.isNotBlank() },
        // 库从不为和声行填 phonetic（x-bg 内嵌的 x-roman 它根本不读），缺的音译由原文回扫兜底；
        // 库哪天自己填了，一律以库的为准。
        roma = line.phonetic?.takeIf { it.isNotBlank() }
            ?: fallbackRoma?.takeIf { it.isNotBlank() },
    )

    private fun plainLine(line: SyncedLine, translation: Boolean, raw: RawLine?): PluginLyricLine =
        PluginLyricLine(
            begin = line.start.toLong(),
            end = line.end.toLong(),
            duration = (line.end - line.start).toLong().coerceAtLeast(0L),
            isAlignedRight = false,
            metadata = rowMetadata(ROLE_LEAD, raw),
            text = line.content,
            translation = line.translation?.takeIf { translation && it.isNotBlank() },
        )

    /**
     * 把库丢掉的行级 x-bg 补成独立 BG 行（形状与 [accompanimentLine] 一致）。
     *
     * 空白文本不产出：宿主与审核细则都禁止空白行，补出来只会是噪声。
     * 窗口优先取 span 自身的 begin/end（规范 §7.3 允许带），缺省回退父 `<p>` 的窗口。
     * 翻译不在此补：库的和声翻译走内嵌 x-translation / iTunes 元数据两条路径，本次只修
     * 「行级 x-bg 被整条丢弃」与「x-bg 内嵌 x-roman 被丢弃」两处内容损失，不顺手扩大范围。
     */
    private fun rawBgRow(span: RawBgSpan, parent: RawLine): PluginLyricLine? {
        if (span.text.isBlank()) return null
        val begin = span.begin ?: parent.begin
        val end = span.end ?: parent.end
        return PluginLyricLine(
            begin = begin,
            end = end,
            duration = (end - begin).coerceAtLeast(0L),
            // 和声行不参与对唱左右分侧（它挂在主行之下，分侧由主行表达）。
            isAlignedRight = false,
            metadata = rowMetadata(ROLE_BG, parent),
            text = span.text,
            // 内层带时轴音节时才有逐字词表；行级 x-bg（本兜底的主路径）留给宿主走行级渲染。
            words = span.syllables.takeIf { it.isNotEmpty() },
            roma = span.roman,
        )
    }

    private fun List<KaraokeSyllable>.toWords(): List<PluginWord>? =
        takeIf { it.isNotEmpty() }?.map { syllable ->
            PluginWord(
                begin = syllable.start.toLong(),
                end = syllable.end.toLong(),
                duration = (syllable.end - syllable.start).toLong().coerceAtLeast(0L),
                text = syllable.content,
            )
        }

    private fun sanitize(rows: List<PluginLyricLine>): List<PluginLyricLine> =
        rows.asSequence()
            .filter { !it.text.isNullOrBlank() }
            .map { row ->
                val begin = row.begin.coerceAtLeast(0L)
                val end = row.end.coerceAtLeast(begin)
                row.copy(begin = begin, end = end, duration = (end - begin).coerceAtLeast(0L))
            }
            .sortedBy { it.begin }
            .toList()
}

// ---- 原文回扫（补库丢弃的 x-bg 内容，并取回库丢弃的演唱者身份）-------------

/**
 * 原文扫描结果：一个带窗口的 `<p>`。
 *
 * 对齐键是 (begin, end)：解析行按 start 升序、同 start 保持文档序，扫描结果按文档序入队，
 * 同一个键下两侧的顺序一致，因此按序取用即可对上（见 [RawLineCursor]）。
 *
 * `agent` / `agentType` 是该 `<p>` 的演唱者身份（没写 / 没声明为 null）；`spans` 是它的直接子
 * x-bg 内容——没有 x-bg 的行也在扫描结果里（身份挂在每一行上，与和声开关无关），`spans` 为空。
 */
internal data class RawLine(
    val begin: Long,
    val end: Long,
    val agent: String?,
    val agentType: String?,
    val spans: List<RawBgSpan>,
)

/**
 * 原文扫描结果：一个直接子 x-bg span。
 *
 * - `begin` / `end`：span 自身的窗口（规范 §7.3：可选，缺省由调用方回退 `<p>` 的窗口）；
 * - `text`：去掉辅助 span 后的文本，按库口径（实体解码 + 空白折叠 + trim）归一化；
 * - `roman`：内嵌 x-roman 的文本——库从不读它（和声行的 phonetic 恒为 null），这是 x-roman 兜底的来源；
 * - `syllables`：库谓词下的内层时轴音节；**非空即说明库会自行解析成和声行**，兜底只处理为空的情况。
 */
internal data class RawBgSpan(
    val begin: Long?,
    val end: Long?,
    val text: String,
    val roman: String?,
    val syllables: List<PluginWord>,
)

/**
 * 回扫原文，按文档序取出每个带窗口的 `<p>`（窗口 + 演唱者身份 + 直接子 x-bg 内容）。
 *
 * 为什么要有这一步：库的 [TTMLParser] 只把「内层带时轴 `<span>`」的 x-bg 解析成和声行，
 * 行级写法（`<span ttm:role="x-bg">(伴唱)</span>`，规范 §5/§7.3 明确允许）会被整条丢弃；
 * x-bg 内嵌的 x-roman 它也从不读取。这两类都是规范内的合法写法，丢了就是内容损失。
 *
 * 演唱者身份同理是库外信息：库读 `ttm:agent` 只为算 alignment，算完即丢（规范 §7.2：
 * 「在 `<p>` 标签上使用 `ttm:agent` 属性，并通过在 `<head>` 中定义的 `xml:id` (如 `v1`) 来
 * 指明演唱者。」「即使是单人演唱的歌曲，也应为 `<p>` 标签添加 `ttm:agent="v1"`，并定义 "v1"
 * agent。」），宿主拿不到 id/type 就分不清真对唱与单纯重叠。id 按库的取法原样带出（属性名恰为
 * `ttm:agent`）；type 由 `<head>` 的 `<ttm:agent xml:id="…" type="…"/>` 声明解析。
 *
 * 为什么自己写扫描器而不是复用库的：库的 `SimpleXmlParser` / `XmlElement` / `parseAsTime`
 * 在 0.4.7 里都是 internal，插件无法引用；这里按库的同一口径复刻最小实现（含实体解码、
 * 空白折叠、时间戳解析），且只服务于兜底路径——形状异常时最坏也只是「不补」，不影响既有输出。
 */
internal fun scanRawLines(ttml: String): List<RawLine> {
    val root = parseRawXml(ttml) ?: return emptyList()
    val agentTypes = scanRawAgentTypes(root)
    val result = ArrayList<RawLine>()
    fun visit(node: ScanNode) {
        if (node.name == "p") {
            val begin = node.attr("begin")?.let(::parseRawTime)
            val end = node.attr("end")?.let(::parseRawTime)
            if (begin != null && end != null) {
                // 属性名恰为 ttm:agent（同库内 computeLineAlignments 的取法）；空值视为没写。
                val agent = node.attr("ttm:agent")?.takeIf { it.isNotBlank() }
                result += RawLine(
                    begin = begin,
                    end = end,
                    agent = agent,
                    agentType = agent?.let(agentTypes::get),
                    spans = node.children
                        .filter { it.name == "span" && it.hasRole("x-bg") }
                        .map { span ->
                            RawBgSpan(
                                begin = span.attr("begin")?.let(::parseRawTime),
                                end = span.attr("end")?.let(::parseRawTime),
                                text = normalizeRawText(span.plainText()),
                                roman = span.children
                                    .firstOrNull { it.name == "span" && it.hasRole("x-roman") }
                                    ?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() },
                                syllables = span.timedSyllables(),
                            )
                        },
                )
            }
        }
        for (child in node.children) visit(child)
    }
    visit(root)
    return result
}

/**
 * 原文扫描：`<ttm:agent xml:id="…" type="…"/>` 声明表（id → type）。
 *
 * 口径同库内 `parseAgentTypes`（元素名以 ":agent" 结尾或恰为 "agent"，`xml:id`（或裸 `id`）
 * 为键、`type` 为值，同一 id 重复声明取最后一个）；差别只在扫描范围：库只看 `<metadata>` 的
 * 直接子元素，这里扫整棵树——规范 §7.2 只要求声明在 `<head>` 里，扫全树对两种摆放都成立，
 * 且结果只用于把 `<p>` 上已有的 id 补成 type，不会改变任何行的产出。
 * 没有 type 的声明不入表：不发明源里没有的值（宿主对缺失 type 有自己的缺省语义）。
 */
private fun scanRawAgentTypes(root: ScanNode): Map<String, String> {
    val result = HashMap<String, String>()
    fun visit(node: ScanNode) {
        if (node.name.endsWith(":agent") || node.name == "agent") {
            val id = node.attr("xml:id") ?: node.attr("id")
            val type = node.attr("type")?.takeIf { it.isNotBlank() }
            if (id != null && type != null) result[id] = type
        }
        for (child in node.children) visit(child)
    }
    visit(root)
    return result
}

/**
 * 原文扫描 ↔ 解析行的对齐游标：按 (begin, end) 分桶、桶内按文档序取用。
 *
 * 身份与和声内容分成两个队列、各自消费：
 * - [takeAgent] 收所有带窗口的 `<p>`——身份挂在每一行上，background 关掉也要取；
 * - [takeSpans] 只收含直接子 x-bg 的 `<p>`——与 1.0.2 的兜底逐字节同源：同一窗口出现多个
 *   `<p>`（病态输入）时兜底行的归属保持改动前的行为，本次只加不减。此时身份与内容可能落到
 *   不同的 `<p>` 上：内容随内容所属的 `<p>`，身份按文档序——两害相权取其轻（宁缺毋滥）。
 *
 * 取不到就返回 null——补不上只是保持改动前的行为，绝不会因为对不齐而错补（宁缺毋滥）。
 */
private class RawLineCursor(lines: List<RawLine>) {
    private val agents = HashMap<Pair<Long, Long>, ArrayDeque<RawLine>>(lines.size)
    private val backgrounds = HashMap<Pair<Long, Long>, ArrayDeque<RawLine>>(lines.size)

    init {
        for (line in lines) {
            agents.getOrPut(line.begin to line.end) { ArrayDeque() }.addLast(line)
            if (line.spans.isNotEmpty()) {
                backgrounds.getOrPut(line.begin to line.end) { ArrayDeque() }.addLast(line)
            }
        }
    }

    fun takeAgent(begin: Long, end: Long): RawLine? = agents[begin to end]?.removeFirstOrNull()

    fun takeSpans(begin: Long, end: Long): RawLine? = backgrounds[begin to end]?.removeFirstOrNull()
}

// ---- 最小 XML 扫描器（口径复刻库内 SimpleXmlParser / parseAsTime）----------
//
// 库把 TTML 解析成 XmlElement 树时，文本按「非空段归节点自身、不带换行的空白段留作
// #text 子节点、带换行的空白段（排版缩进）丢弃」归档，时间戳按 SS / MM:SS / HH:MM:SS
// 三种形态解析。回扫必须与这套口径一致，否则补出来的文本/时间与库解析的行对不上。

/** 扫描出的原文节点；`text` 与 `children` 的归档口径同库内 XmlElement。 */
private class ScanNode(
    val name: String,
    val attrs: List<Pair<String, String>>,
) {
    val children = ArrayList<ScanNode>()
    val text = StringBuilder()

    fun attr(name: String): String? = attrs.firstOrNull { it.first == name }?.second

    /** 同库内 hasRole：属性名以 ":role" 结尾（带命名空间前缀的 role）且值相等；裸 `role` 不认。 */
    fun hasRole(role: String): Boolean = attrs.any { it.first.endsWith(":role") && it.second == role }
}

/** 同库内 extractAllText：本节点文本 + 全部后代文本，跳过辅助 span（翻译/音译/嵌套和声）。 */
private fun ScanNode.plainText(): String = buildString {
    append(text)
    for (child in children) {
        if (child.name == "span" &&
            (child.hasRole("x-translation") || child.hasRole("x-bg") || child.hasRole("x-roman"))
        ) {
            continue
        }
        append(child.plainText())
    }
}

/**
 * 同库内 parseSyllablesFromChildren：直接子 `<span>`、非 x-translation/x-bg、带 begin 与 end、
 * 自身文本非空，才是一个时轴音节（末尾音节的尾随空白同样裁掉）。
 *
 * 只用于判定「库会不会自行解析出和声行」；本兜底路径下它必为空，非空时说明内容归库处理。
 */
private fun ScanNode.timedSyllables(): List<PluginWord> {
    val result = ArrayList<PluginWord>()
    for ((index, child) in children.withIndex()) {
        if (child.name != "span") continue
        if (child.hasRole("x-translation") || child.hasRole("x-bg")) continue
        val begin = child.attr("begin") ?: continue
        val end = child.attr("end") ?: continue
        if (child.text.isEmpty()) continue
        var content = decodeRawEntities(child.text.toString())
        children.getOrNull(index + 1)?.takeIf { it.name == "#text" }?.let {
            content += decodeRawEntities(it.text.toString())
        }
        val start = parseRawTime(begin)
        val endMs = parseRawTime(end)
        result += PluginWord(
            begin = start,
            end = endMs,
            duration = (endMs - start).coerceAtLeast(0L),
            text = content,
        )
    }
    if (result.isNotEmpty()) {
        val last = result.last()
        result[result.lastIndex] = last.copy(text = last.text.orEmpty().trimEnd())
    }
    return result
}

private val RAW_WHITESPACE = Regex("\\s+")

/** 同库内 normalizeXmlTextContent：实体解码 → 空白折叠 → trim。 */
private fun normalizeRawText(text: String): String =
    decodeRawEntities(text).replace(RAW_WHITESPACE, " ").trim()

/** 同库内 decodeXmlEntities（只处理 XML 预定义实体，与库一致）。 */
private fun decodeRawEntities(text: String): String {
    if (!text.contains('&')) return text
    return text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
}

/** 同库内 SimpleXmlParser：处理注释 / 处理指令 / 自闭合标签，遇畸形输入就截断（不抛异常）。 */
private fun parseRawXml(xml: String): ScanNode? {
    val stack = ArrayDeque<ScanNode>()
    var i = 0
    while (i < xml.length) {
        if (xml[i] != '<') {
            val next = xml.indexOf('<', i)
            val segment = if (next < 0) xml.substring(i) else xml.substring(i, next)
            if (segment.isNotEmpty()) stack.lastOrNull()?.let { appendRawText(it, segment) }
            i = if (next < 0) xml.length else next
            continue
        }
        when {
            xml.startsWith("</", i) -> {
                val close = xml.indexOf('>', i + 2)
                if (close < 0) break
                if (stack.size > 1) stack.removeLast().let { stack.last().children += it }
                i = close + 1
            }

            xml.startsWith("<!--", i) -> {
                val end = xml.indexOf("-->", i + 4)
                i = if (end < 0) xml.length else end + 3
            }

            xml.startsWith("<?", i) -> {
                val end = xml.indexOf("?>", i + 2)
                i = if (end < 0) xml.length else end + 2
            }

            else -> {
                val close = xml.indexOf('>', i + 1)
                if (close < 0) break
                var tag = xml.substring(i + 1, close)
                val selfClosing = tag.endsWith('/')
                if (selfClosing) tag = tag.dropLast(1).trim()
                val node = parseRawTag(tag)
                if (selfClosing) {
                    if (stack.isEmpty()) return node
                    stack.last().children += node
                } else {
                    stack.addLast(node)
                }
                i = close + 1
            }
        }
    }
    return stack.firstOrNull()
}

/** 同库内 appendText：非空段归节点自身；纯空白段不带换行的留作 #text，带换行的按排版空白丢弃。 */
private fun appendRawText(node: ScanNode, segment: String) {
    when {
        !segment.isBlank() -> node.text.append(segment)
        segment.contains('\n') || segment.contains('\r') -> Unit
        else -> {
            val textNode = ScanNode("#text", emptyList())
            textNode.text.append(segment)
            node.children += textNode
        }
    }
}

/** 同库内 parseTagAndAttributes：名字取到首个空白，属性值支持引号包裹与裸值。 */
private fun parseRawTag(tag: String): ScanNode {
    val firstSpace = tag.indexOfFirst { it.isWhitespace() }
    if (firstSpace < 0) return ScanNode(tag, emptyList())
    val attrs = ArrayList<Pair<String, String>>()
    var i = firstSpace + 1
    while (i < tag.length) {
        while (i < tag.length && tag[i].isWhitespace()) i++
        if (i >= tag.length) break
        val eq = tag.indexOf('=', i)
        if (eq < 0) break
        val name = tag.substring(i, eq).trim()
        i = eq + 1
        while (i < tag.length && tag[i].isWhitespace()) i++
        if (i >= tag.length) break
        val quote = tag[i]
        if (quote == '"' || quote == '\'') {
            val close = tag.indexOf(quote, i + 1)
            if (close < 0) break
            attrs += name to tag.substring(i + 1, close)
            i = close + 1
        } else {
            var end = i
            while (end < tag.length && !tag[end].isWhitespace()) end++
            attrs += name to tag.substring(i, end)
            i = end
        }
    }
    return ScanNode(tag.substring(0, firstSpace), attrs)
}

/**
 * 同库内 `String.parseAsTime()`（毫秒）：支持 `SS` / `SS.ms` / `MM:SS(.ms)` / `HH:MM:SS(.ms)`，
 * 非数字段按 0 计、小数位按 1/2/3 位分别缩放。
 */
private fun parseRawTime(value: String): Long {
    if (value.isEmpty()) return 0L
    val firstColon = value.indexOf(':')
    if (firstColon < 0) return secondsAndMillis(value, 0, value.length)
    val lastColon = value.lastIndexOf(':')
    return if (firstColon == lastColon) {
        digitsOf(value, 0, firstColon) * 60_000L +
            secondsAndMillis(value, firstColon + 1, value.length)
    } else {
        digitsOf(value, 0, firstColon) * 3_600_000L +
            digitsOf(value, firstColon + 1, lastColon) * 60_000L +
            secondsAndMillis(value, lastColon + 1, value.length)
    }
}

private fun secondsAndMillis(value: String, start: Int, end: Int): Long {
    var dot = -1
    for (i in start until end) {
        if (value[i] == '.') {
            dot = i
            break
        }
    }
    if (dot < 0) return digitsOf(value, start, end) * 1000L
    val seconds = digitsOf(value, start, dot) * 1000L
    val millisStart = dot + 1
    val millisLength = end - millisStart
    if (millisLength <= 0) return seconds
    val take = if (millisLength >= 3) 3 else millisLength
    var millis = digitsOf(value, millisStart, millisStart + take)
    if (millisLength == 1) millis *= 100 else if (millisLength == 2) millis *= 10
    return seconds + millis
}

/** 区间内的十进制数字；出现非数字或空区间按 0（同库内 digitsToIntOr0 的语义）。 */
private fun digitsOf(value: String, start: Int, end: Int): Long {
    if (start >= end) return 0L
    var result = 0L
    for (i in start until end) {
        val c = value[i]
        if (c < '0' || c > '9') return 0L
        result = result * 10 + (c - '0')
    }
    return result
}
