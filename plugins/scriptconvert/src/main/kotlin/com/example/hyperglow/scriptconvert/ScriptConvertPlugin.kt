package com.example.hyperglow.scriptconvert

import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginProcessorStage
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult

/**
 * 歌词字形转换插件：按用户设置把歌词在简体/繁体之间整体转换。
 *
 * 定位（与 HyperGlow 插件体系的关系）：
 * - 宿主与生产者都不做字形转换（简体源给简体、繁体源给繁体），本插件补这一层；
 * - 走 TRANSLATION_ENHANCEMENT 阶段：在歌词替换/翻译类插件之后运行，转换的是
 *   **最终要显示的内容**——这样 AI 翻译等后置插件产出的文本也能被一起转换。
 *
 * 只改内容字段，不碰时间轴：行/词的 begin/end/duration、对唱分侧、行角色 metadata
 * 一律原样保留。`secondary`/`secondaryWords` 有意不转换——宿主回向
 * （PluginSongBridge.enrichState）不消费这两个字段，声明它们只会制造"看起来改了、
 * 实际不生效"的死键。
 */
class ScriptConvertPlugin : HyperLyricPlugin {

    override fun onLoad(context: PluginContext) {
        context.logger.info("script convert plugin loaded (host api ${context.hostApiVersion})")
        context.registerExtension(ScriptConvertProcessor(context))
    }
}

private class ScriptConvertProcessor(private val context: PluginContext) : LyricProcessorExtension {

    override val id: String = "scriptconvert.processor"

    override val stage: PluginProcessorStage = PluginProcessorStage.TRANSLATION_ENHANCEMENT

    override fun processResult(song: PluginSong): PluginSongResult? {
        val rows = song.lyrics ?: return null
        if (rows.isEmpty()) return null

        val target = when (context.config.getString(KEY_TARGET, VALUE_SIMPLIFIED)?.trim()?.lowercase()) {
            VALUE_TRADITIONAL -> ScriptConverter.Target.TRADITIONAL
            else -> ScriptConverter.Target.SIMPLIFIED
        }
        val convertTranslation = context.config.getBoolean(KEY_TRANSLATION, true)

        var changedText = false
        var changedWords = false
        var changedTranslation = false
        var changedTranslationWords = false

        val converted = rows.map { row ->
            var next = row

            row.text?.let { original ->
                val result = ScriptConverter.convert(original, target)
                if (result != original) {
                    changedText = true
                    next = next.copy(text = result)
                }
            }

            row.words?.let { words ->
                var changed = false
                val convertedWords = words.map { word ->
                    val original = word.text
                    if (original.isNullOrEmpty()) {
                        word
                    } else {
                        val result = ScriptConverter.convert(original, target)
                        if (result != original) {
                            changed = true
                            word.copy(text = result)
                        } else {
                            word
                        }
                    }
                }
                if (changed) {
                    changedWords = true
                    next = next.copy(words = convertedWords)
                }
            }

            if (convertTranslation) {
                row.translation?.let { original ->
                    val result = ScriptConverter.convert(original, target)
                    if (result != original) {
                        changedTranslation = true
                        next = next.copy(translation = result)
                    }
                }
                row.translationWords?.let { words ->
                    var changed = false
                    val convertedWords = words.map { word ->
                        val original = word.text
                        if (original.isNullOrEmpty()) {
                            word
                        } else {
                            val result = ScriptConverter.convert(original, target)
                            if (result != original) {
                                changed = true
                                word.copy(text = result)
                            } else {
                                word
                            }
                        }
                    }
                    if (changed) {
                        changedTranslationWords = true
                        next = next.copy(translationWords = convertedWords)
                    }
                }
            }

            next
        }

        // 幂等：目标字形与源一致（或整首无可转换字符）时不产生结果，宿主保持透传。
        if (!changedText && !changedWords && !changedTranslation && !changedTranslationWords) {
            return null
        }

        val changedFields = buildSet {
            if (changedText) add(PluginLyricField.TEXT)
            if (changedWords) add(PluginLyricField.WORDS)
            if (changedTranslation) add(PluginLyricField.TRANSLATION)
            if (changedTranslationWords) add(PluginLyricField.TRANSLATION_WORDS)
        }
        context.logger.debug(
            "converted ${converted.size} rows to $target, changedFields=$changedFields"
        )
        return PluginSongResult(
            song = song.copy(lyrics = converted),
            changedFields = setOf(PluginSongField.LYRICS),
            lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
            changedLyricFields = changedFields
        )
    }

    private companion object {
        const val KEY_TARGET = "scriptconvert_target"
        const val KEY_TRANSLATION = "scriptconvert_translation"
        const val VALUE_SIMPLIFIED = "simplified"
        const val VALUE_TRADITIONAL = "traditional"
    }
}
