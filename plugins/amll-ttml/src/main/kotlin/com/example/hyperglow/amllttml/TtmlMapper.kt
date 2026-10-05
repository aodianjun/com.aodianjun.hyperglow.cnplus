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
 * 对唱 agent → 左右分侧都在库内完成），本映射只做三件事：
 * 1. **对唱开关**：`duet` 关闭时忽略 alignment，全部行不分侧（对齐上游内置版的
 *    「启用对唱表演」设置项语义）；
 * 2. **和声开关**：`background` 关闭时丢弃和声行；开启时和声行作为**独立行**输出，
 *    角色写进 metadata 的 "role"（LEAD/BG）——与 Spicy 文档桥、lyricfetch 同一约定；
 * 3. **翻译开关**：`translation` 关闭时不挂翻译。
 *
 * 行时间轴按 begin 排序、钳制到非负，空文本行丢弃（宁缺毋滥）。
 */
internal object TtmlMapper {

    const val META_ROLE = "role"
    const val ROLE_LEAD = "LEAD"
    const val ROLE_BG = "BG"

    fun map(
        ttml: String,
        duet: Boolean,
        translation: Boolean,
        background: Boolean,
    ): List<PluginLyricLine> {
        val synced = runCatching { TTMLParser().parse(ttml) }.getOrNull() ?: return emptyList()
        if (synced.lines.isEmpty()) return emptyList()

        val rows = ArrayList<PluginLyricLine>(synced.lines.size + 8)
        for (line in synced.lines) {
            when (line) {
                is KaraokeLine.MainKaraokeLine -> {
                    rows += mainLine(line, duet, translation)
                    if (background) {
                        line.accompanimentLines?.forEach { accompaniment ->
                            rows += accompanimentLine(accompaniment, translation)
                        }
                    }
                }

                is KaraokeLine.AccompanimentKaraokeLine ->
                    if (background) rows += accompanimentLine(line, translation)

                is SyncedLine -> rows += plainLine(line, translation)

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

    private fun mainLine(
        line: KaraokeLine.MainKaraokeLine,
        duet: Boolean,
        translation: Boolean,
    ): PluginLyricLine = PluginLyricLine(
        begin = line.start.toLong(),
        end = line.end.toLong(),
        duration = (line.end - line.start).toLong().coerceAtLeast(0L),
        isAlignedRight = duet && line.alignment == KaraokeAlignment.End,
        metadata = PluginMetadata(values = mapOf(META_ROLE to ROLE_LEAD)),
        text = line.syllables.joinToString("") { it.content },
        words = line.syllables.toWords(),
        translation = line.translation?.takeIf { translation && it.isNotBlank() },
        roma = line.phonetic?.takeIf { it.isNotBlank() },
    )

    private fun accompanimentLine(
        line: KaraokeLine.AccompanimentKaraokeLine,
        translation: Boolean,
    ): PluginLyricLine = PluginLyricLine(
        begin = line.start.toLong(),
        end = line.end.toLong(),
        duration = (line.end - line.start).toLong().coerceAtLeast(0L),
        // 和声行不参与对唱左右分侧（它挂在主行之下，分侧由主行表达）。
        isAlignedRight = false,
        metadata = PluginMetadata(values = mapOf(META_ROLE to ROLE_BG)),
        text = line.syllables.joinToString("") { it.content },
        words = line.syllables.toWords(),
        translation = line.translation?.takeIf { translation && it.isNotBlank() },
        roma = line.phonetic?.takeIf { it.isNotBlank() },
    )

    private fun plainLine(line: SyncedLine, translation: Boolean): PluginLyricLine = PluginLyricLine(
        begin = line.start.toLong(),
        end = line.end.toLong(),
        duration = (line.end - line.start).toLong().coerceAtLeast(0L),
        isAlignedRight = false,
        metadata = PluginMetadata(values = mapOf(META_ROLE to ROLE_LEAD)),
        text = line.content,
        translation = line.translation?.takeIf { translation && it.isNotBlank() },
    )

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
