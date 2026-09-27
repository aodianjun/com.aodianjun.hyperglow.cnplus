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
