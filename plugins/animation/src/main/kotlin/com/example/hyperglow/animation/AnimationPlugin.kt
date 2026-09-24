package com.example.hyperglow.animation

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
 * HyperGlow 官方动画插件入口。
 *
 * 插件 API 只有一次 [LyricProcessorExtension.processResult] 快照回调（无逐帧通道），
 * 因此"动画"在此被收敛为**宿主可见的静态呈现**——通过 PluginLyricField.TEXT /
 * SECONDARY / WORDS 让宿主的 KTV 渲染管线产出动态观感，符合 ARCHITECTURE.md
 * 里"逐元素 transform/alpha/glow/sprite loop"这条能力上限。
 *
 * 处理协议：返回 null 时宿主保留原快照继续后续处理器；本插件在动画关闭或没有任何
 * 可变更行时返回 null，绝不污染宿主快照。
 */
class AnimationPlugin : HyperLyricPlugin {
    override fun onLoad(context: PluginContext) {
        context.logger.info("animation plugin loaded (host api ${context.hostApiVersion})")
        context.registerExtension(AnimationProcessor(context))
    }
}

private class AnimationProcessor(private val context: PluginContext) : LyricProcessorExtension {

    override val id: String = "animation.effects"

    override val stage: PluginProcessorStage = PluginProcessorStage.TRANSLATION_ENHANCEMENT

    override fun processResult(song: PluginSong): PluginSongResult? {
        if (!context.config.getBoolean(AnimationConfig.KEY_ENABLED, false)) return null

        val rows = song.lyrics ?: return null
        if (rows.isEmpty()) return null

        val cfg = context.config
        val mode = cfg.getString(AnimationConfig.KEY_MODE, AnimationConfig.MODE_BREATHING)
            ?.trim().orEmpty()
        if (mode.isEmpty()) return null

        val intensity = cfg.getFloat(AnimationConfig.KEY_INTENSITY, AnimationConfig.DEFAULT_INTENSITY)
            .coerceIn(0f, AnimationConfig.MAX_INTENSITY)
        val stepMs = cfg.getFloat(AnimationConfig.KEY_STEP_MS, AnimationConfig.DEFAULT_STEP_MS_FLOAT)
            .coerceIn(AnimationConfig.MIN_STEP_MS, AnimationConfig.MAX_STEP_MS)
            .toLong()
        val glyphStyle = cfg.getString(AnimationConfig.KEY_GLYPH_STYLE, AnimationConfig.STYLE_SYMBOL)
            ?.trim().orEmpty()

        val rng = HashRng(song.seed())
        val renderer = AnimationRenderer(mode, intensity, stepMs, glyphStyle)

        var touchedText = 0
        var touchedSecondary = 0
        var touchedTranslation = 0
        var touchedWords = 0

        val newRows = rows.mapIndexed { index, row ->
            val updated = renderer.render(row, index, rng)
            if (updated === row) return@mapIndexed updated
            if (updated.text != row.text) touchedText++
            if (updated.secondary != row.secondary) touchedSecondary++
            if (updated.translation != row.translation) touchedTranslation++
            if (updated.words != row.words) touchedWords++
            updated
        }

        if (touchedText == 0 && touchedSecondary == 0 && touchedTranslation == 0 && touchedWords == 0) {
            return null
        }

        val changedFields = buildSet {
            if (touchedText > 0) add(PluginLyricField.TEXT)
            if (touchedSecondary > 0) add(PluginLyricField.SECONDARY)
            if (touchedTranslation > 0) add(PluginLyricField.TRANSLATION)
            if (touchedWords > 0) add(PluginLyricField.WORDS)
        }

        context.logger.debug(
            "animation '$mode' lines=${newRows.size} " +
                "text=$touchedText secondary=$touchedSecondary " +
                "translation=$touchedTranslation words=$touchedWords"
        )

        return PluginSongResult(
            song = song.copy(lyrics = newRows),
            changedFields = setOf(PluginSongField.LYRICS),
            lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
            changedLyricFields = changedFields
        )
    }

    private fun PluginSong.seed(): String =
        (id ?: "no-id") + "|" + (name ?: "") + "|" + (artist ?: "") +
            "|" + (album ?: "") + "|" + duration
}
