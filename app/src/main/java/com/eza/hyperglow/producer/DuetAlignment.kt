package com.eza.hyperglow.producer

/**
 * 对唱左右分侧（移植自 limczhh/HyperLyric 的 `LyricPresentationResolver.alignAgents` /
 * `alignTypedAgents` 语义；两个项目同为 GPL-3.0，此处移植行为契约与元数据键名，非逐行照抄）。
 *
 * 背景：对唱歌里两位歌手交替/重叠演唱，HyperLyric 按「演唱者身份」把行分配到左右两侧，
 * CN+ 的 `LyricProducerState.alignedRight` 一直是这条语义的载体（画布 `resolveAlignmentMode`
 * 在主对齐为 "auto" 时按它决定左/右），只是此前生产者恒填 false、没有身份来源。
 *
 * 与 HyperLyric 的对应关系：
 * - `AGENT_KEYS`/`AGENT_TYPE_KEYS` 与 HyperLyric 的 `AGENT_METADATA_KEYS`/`AGENT_TYPE_METADATA_KEYS` 同名同序；
 * - [resolveDuetAlignment] 的无类型分支镜像 `alignAgents`（首个歌手居左、其余居右），
 *   有类型分支镜像 `alignTypedAgents`（`group`=单人合唱团居左，`other`=首位交替歌手起右并随歌手切换翻转）；
 * - 源已显式标记 `alignedRight` 的行一律保留（HyperLyric 的 `if (line.isAlignedRight) return@map line`）。
 *
 * 有意不移植：HyperLyric 的 `METADATA_KEY_ALIGNMENT_RESOLVED` 幂等标记（CN+ 每次按整首重算，
 * 不需要跨次幂等标记）与 `LyricPresentationResolver` 的并发第二行选择（CN+ 画布无并发行模型）。
 */
internal object DuetAlignment {
    /** 演唱者身份键，按优先级取第一个非空值（与 HyperLyric AGENT_METADATA_KEYS 一致）。 */
    internal val AGENT_KEYS = listOf("agent", "amll:agent", "vocal", "amll:vocal")

    /** 演唱者类型键，按优先级取第一个非空值（与 HyperLyric AGENT_TYPE_METADATA_KEYS 一致）。 */
    internal val AGENT_TYPE_KEYS = listOf(
        "amll:agent-type",
        "agent:type",
        "agentType",
        "vocal:type"
    )

    /** 单人合唱团/伴唱组：HyperLyric 语义为不参与交替，保持居左。 */
    internal const val AGENT_TYPE_GROUP = "group"

    /** 首位交替歌手：语义为从右侧起唱。 */
    internal const val AGENT_TYPE_OTHER = "other"
}

/**
 * 一行的对唱信息。[sourceAlignedRight] 是歌词源自己给出的对齐（Spicy 文档 `alignedRight`、
 * Lyricon `RichLyricLine.isAlignedRight`、插件 `PluginLyricLine.isAlignedRight`），优先级最高。
 */
internal data class DuetLine(
    val agentId: String? = null,
    val agentType: String? = null,
    val sourceAlignedRight: Boolean = false
)

/** 从行级元数据读取演唱者身份（空白视为未标注）。 */
internal fun duetAgentId(metadata: Map<String, String?>?): String? =
    DuetAlignment.AGENT_KEYS.firstNotNullOfOrNull { key ->
        metadata?.get(key)?.trim()?.takeIf { it.isNotEmpty() }
    }

/** 从行级元数据读取演唱者类型，统一小写（HyperLyric 对 type 做 lowercase 归一）。 */
internal fun duetAgentType(metadata: Map<String, String?>?): String? =
    DuetAlignment.AGENT_TYPE_KEYS.firstNotNullOfOrNull { key ->
        metadata?.get(key)?.trim()?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() }
    }

/**
 * 按演唱者身份决定每行左/右分侧，返回与 [lines] 等长的 `alignedRight` 列表。
 *
 * 规则（HyperLyric 语义）：
 * 1. 源已显式标右对齐的行恒为 true，不再改写；
 * 2. 任一行带显式演唱者类型 → 交替算法：`group` 行保持源值；首个非 group 歌手
 *    「类型为 other 则起右、否则起左」，同一歌手保持，换歌手即翻转；
 * 3. 无显式类型但整首出现 ≥2 个不同身份 → 首个身份居左、其余居右；
 * 4. 其余情况（无身份/单一身份）一律保持源值。
 *
 * 入参须是**整首歌**的行（而非活动行）：身份排序与「首个歌手」依赖全局出现顺序。
 */
internal fun resolveDuetAlignment(lines: List<DuetLine>, enabled: Boolean): List<Boolean> {
    val source = lines.map { it.sourceAlignedRight }
    if (!enabled || lines.isEmpty()) return source

    if (lines.any { it.agentType != null }) {
        return alignTypedAgents(lines)
    }

    val agents = buildList {
        lines.forEach { line ->
            val agent = line.agentId ?: return@forEach
            if (agent !in this) add(agent)
        }
    }
    if (agents.size < 2) return source

    // 首个身份居左、其余居右：与 HyperLyric `rightByAgent[index > 0]` 同式。
    val rightByAgent = agents.withIndex().associate { indexed -> indexed.value to (indexed.index > 0) }
    return lines.map { line ->
        when {
            line.sourceAlignedRight -> true
            else -> line.agentId?.let { rightByAgent[it] } ?: line.sourceAlignedRight
        }
    }
}

/**
 * 有显式演唱者类型时的交替分配（镜像 HyperLyric `alignTypedAgents`）。
 * `group` 行不参与交替（保持源值）；其余歌手按出现顺序交替左右，同一歌手保持同一侧。
 * 交替状态对「源已显式居右」的行也照常推进——源值只决定该行输出，不影响后续行的翻转换算
 * （HyperLyric 先算 `right` 再判 `isAlignedRight`）。
 */
private fun alignTypedAgents(lines: List<DuetLine>): List<Boolean> {
    var lastAgent: String? = null
    var lastRight = false
    return lines.map { line ->
        val agent = line.agentId
        val type = line.agentType
        if (agent == null || type == DuetAlignment.AGENT_TYPE_GROUP) {
            return@map line.sourceAlignedRight
        }
        val right = when {
            lastAgent == null -> {
                lastAgent = agent
                lastRight = type == DuetAlignment.AGENT_TYPE_OTHER
                lastRight
            }
            lastAgent == agent -> lastRight
            else -> {
                lastAgent = agent
                lastRight = !lastRight
                lastRight
            }
        }
        line.sourceAlignedRight || right
    }
}

// --- 对唱文本标记(（男）/（女）/（合）)识别 ---
//
// 网易云等源的对唱信息以行首文本标记承载(实测《讲男讲女》《男左女右》),不是
// agent/amll:agent 元数据键;没有标记识别时 resolveDuetAlignment 拿不到身份输入,
// 分侧不生效。标记识别(文档级开关 duetMarkers)把标记翻译成演唱者身份喂给分侧推导,
// 并在显示侧剥离标记文本。与元数据身份并存:元数据恒优先,标记只作无元数据时的兜底。

/** 行首对唱标记的匹配(全/半角括号 + 男/女/合 + 其后空白,空白含在剥离范围内)。 */
internal val DUET_MARKER_PREFIX = Regex("^[(（]\\s*(男|女|合)\\s*[)）]\\s*")

/** 合唱标记词:不作为演唱者身份参与交替(与 HyperLyric `group` 恒左同视)。 */
internal const val DUET_MARKER_TOKEN_CHORUS = "合"

/** 识别出的行首对唱标记。[text] 为剥离标记(含其后空白)后的行文本。 */
internal data class DuetMarker(
    val token: String,
    val text: String
)

/**
 * 解析行首对唱标记:命中返回标记与剥离后的行文本;未命中返回 null。
 * 剥离后文本为空(纯标记行)也返回 null——保留原样显示,不产出空行。
 */
internal fun parseDuetMarker(raw: String): DuetMarker? {
    val match = DUET_MARKER_PREFIX.find(raw) ?: return null
    val stripped = raw.substring(match.value.length)
    if (stripped.isBlank()) return null
    return DuetMarker(token = match.groupValues[1], text = stripped)
}

/** 剥离行首对唱标记(幂等:未带标记的文本原样返回)。 */
internal fun stripDuetMarker(raw: String): String =
    parseDuetMarker(raw)?.text ?: raw

/**
 * 剥离词表中作为行首标记出现的部分:首词以标记起头时剥掉标记前缀,剥空则移除该词。
 * 词表与行文本必须同源处理,否则逐字卡拉OK路径(按词绘制)会残留/错位标记。
 * 无变化时返回输入实例。
 */
internal fun stripDuetMarkerWords(words: List<LyricWord>): List<LyricWord> {
    val first = words.firstOrNull() ?: return words
    val match = DUET_MARKER_PREFIX.find(first.text) ?: return words
    val stripped = first.text.substring(match.value.length)
    if (stripped == first.text) return words
    val out = words.toMutableList()
    if (stripped.isBlank()) {
        out.removeAt(0)
    } else {
        out[0] = first.copy(text = stripped)
    }
    return out
}

/**
 * 标记 → 演唱者身份(分侧推导的兜底输入,元数据身份恒优先):男/女 各为一个身份,
 * 「合」不参与交替故不产出身份(该行保持源值)。
 */
internal fun duetMarkerAgentId(marker: DuetMarker): String? =
    marker.token.takeIf { it != DUET_MARKER_TOKEN_CHORUS }
