package com.eza.hyperglow.producer

/**
 * 生产者侧的「整首歌歌词快照」：插件处理链对非 Spicy 源的输入。
 *
 * v1 数据边界只有 Spicy 路径有整首文档；v2 起内存里本就持有整首行数组的生产者
 * （Lyricon/LyricInfo）经 [LyricProducer.fullSongSnapshot] 直接提供快照，纯逐行源
 * （SuperLyric）由宿主侧聚合器（LineStreamAggregator）按同一形状合成。快照必须携带
 * 与当前 [LyricProducerState] 一致的 producerId/generation/trackUri 三元组，管线据此
 * 校验会话匹配——不匹配视为过渡态丢弃，等下一次发射。
 *
 * 行窗口语义与 SpicyBridgeDocument 一致（[startMs, endMs)）：enrichState 按 positionMs
 * 选活动行，所以行必须有真实起止时间——拿不出真实时间轴的源不产出快照。
 */
data class LyricSongSnapshot(
    val producerId: String,
    val generation: Int,
    val trackUri: String,
    val durationMs: Long,
    val rows: List<LyricSongRow>
) {
    /** 与当前状态的会话三元组是否一致（管线采用快照的前提）。 */
    fun matches(state: LyricProducerState): Boolean =
        producerId == state.producerId && generation == state.generation &&
            trackUri == state.trackUri
}

/**
 * 快照中的一行。[role] 沿用 Spicy 的行角色词汇（LEAD/伴奏等），桥接时进
 * PluginLyricLine.metadata——插件链回向选活动行复刻同一语义。
 *
 * [alignedRight] 是对唱左右分侧的载体（源显式值或演唱者身份推导结果，见
 * [resolveDuetAlignment]）；桥接时进 PluginLyricLine.isAlignedRight，使插件链能读到并对齐回写。
 *
 * 翻译是冗余对（[translation] 文本 + [translationWords] 词表，语义同 SDK 的
 * `RichLyricLine`/`PluginLyricLine`）：只带词表的源必须经 [effectiveTranslation] 兜底取文，
 * 文本与词表都要随行过桥，否则词级翻译在快照/插件链/显示三处任一环节被静默丢弃。
 */
data class LyricSongRow(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val translation: String = "",
    val roma: String = "",
    val words: List<LyricWord>? = null,
    val role: String = "LEAD",
    val alignedRight: Boolean = false,
    val translationWords: List<LyricWord>? = null
) {
    /**
     * 翻译取文兜底：[translation] 非空优先，否则由 [translationWords] 拼出。镜像
     * `RichLyricLine.normalize` 的冗余对语义（词表非空时以词表重新生成文本），但只在
     * 文本缺失时兜底、不覆盖已有文本——回向是增强显示而非替换显示（同
     * `PluginSongBridge.keepUnlessBlank` 原则）。
     */
    fun effectiveTranslation(): String = translation.takeIf { it.isNotBlank() }
        ?: translationWords?.takeIf { it.isNotEmpty() }?.joinToString("") { it.text.orEmpty() }
        ?: ""
}
