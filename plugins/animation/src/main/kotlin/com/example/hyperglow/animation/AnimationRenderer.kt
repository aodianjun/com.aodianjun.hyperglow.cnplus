package com.example.hyperglow.animation

import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine

/**
 * 按 (mode, intensity, stepMs, glyphStyle) 对每行歌词做纯函数式变换。
 *
 * 所有模式都是**静态呈现**：装饰符一次性写入 [PluginLyricLine.text] / .secondary，
 * wave 模式改写 [PluginLyricLine.words] 的时值让宿主 KTV 渲染管线自然产生
 * "逐词波浪"。插件端不做逐帧动画——那属于宿主 SystemUI/AOD 侧的能力，不在插件 API 内。
 */
internal class AnimationRenderer(
    private val mode: String,
    private val intensity: Float,
    private val stepMs: Long,
    private val glyphStyle: String,
) {
    private val density: Float = intensity / AnimationConfig.MAX_INTENSITY
    private val ascii: Boolean = glyphStyle == AnimationConfig.STYLE_ASCII

    fun render(row: PluginLyricLine, index: Int, rng: HashRng): PluginLyricLine = when (mode) {
        AnimationConfig.MODE_BREATHING -> renderBreathing(row, rng)
        AnimationConfig.MODE_PARTICLES -> renderParticles(row, rng)
        AnimationConfig.MODE_RIPPLE -> renderRipple(row, index, rng)
        AnimationConfig.MODE_SPECTRUM -> renderSpectrum(row, rng)
        AnimationConfig.MODE_NOTES -> renderNotes(row, rng)
        AnimationConfig.MODE_WAVE -> renderWave(row)
        else -> row
    }

    private fun glyphs(name: String): List<String> = when (name) {
        "breathing" ->
            if (ascii) BREATHING_ASCII else BREATHING_SYMBOL
        "particles" ->
            if (ascii) PARTICLES_ASCII else PARTICLES_SYMBOL
        "ripple" ->
            if (ascii) RIPPLE_ASCII else RIPPLE_SYMBOL
        "spectrum" ->
            if (ascii) SPECTRUM_ASCII else SPECTRUM_SYMBOL
        "notes" ->
            if (ascii) NOTES_ASCII else NOTES_SYMBOL
        else -> emptyList()
    }

    private fun renderBreathing(row: PluginLyricLine, rng: HashRng): PluginLyricLine {
        val text = row.text
        if (text.isNullOrBlank()) return row
        val glyphs = glyphs("breathing")
        if (glyphs.isEmpty()) return row
        val size = when {
            density > 0.6f -> 2
            density > 0.2f -> 1
            else -> 0
        }
        if (size == 0) return row
        val left = rng.pick(glyphs)
        val newText = if (size == 1) "$left  $text"
        else "$left  $text  ${rng.pick(glyphs)}"
        return row.copy(text = newText)
    }

    private fun renderParticles(row: PluginLyricLine, rng: HashRng): PluginLyricLine {
        val text = row.text
        if (text.isNullOrBlank()) return row
        val glyphs = glyphs("particles")
        if (glyphs.isEmpty()) return row
        val step = when {
            density > 0.65f -> 2
            density > 0.3f -> 3
            density > 0.1f -> 5
            else -> 0
        }
        if (step == 0) return row
        val tokens = text.split(" ").filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return row
        val sb = StringBuilder(tokens.size + tokens.size / step)
        tokens.forEachIndexed { i, token ->
            if (i > 0) sb.append(' ')
            sb.append(token)
            if (i < tokens.size - 1 && (i + 1) % step == 0) {
                sb.append(' ').append(rng.pick(glyphs))
            }
        }
        return row.copy(text = sb.toString())
    }

    private fun renderRipple(row: PluginLyricLine, index: Int, rng: HashRng): PluginLyricLine {
        val text = row.text
        if (text.isNullOrBlank()) return row
        val glyphs = glyphs("ripple")
        if (glyphs.isEmpty()) return row
        val baseDepth = when {
            density > 0.5f -> 3
            density > 0.2f -> 2
            else -> 1
        }
        if (baseDepth <= 0) return row
        val depth = (index % 3 + baseDepth).coerceAtMost(4)
        val prefix = buildString(depth) {
            repeat(depth) { append(rng.pick(glyphs)) }
        }
        return row.copy(text = "$prefix  $text")
    }

    private fun renderSpectrum(row: PluginLyricLine, rng: HashRng): PluginLyricLine {
        val text = row.text
        if (text.isNullOrBlank()) return row
        val glyphs = glyphs("spectrum")
        if (glyphs.isEmpty()) return row
        val barCount = (3 + density * 6).toInt().coerceIn(3, 9)
        val prefix = buildString(barCount) {
            repeat(barCount) { append(rng.pick(glyphs)) }
        }
        return row.copy(text = "  $prefix  $text")
    }

    private fun renderNotes(row: PluginLyricLine, rng: HashRng): PluginLyricLine {
        if (density < 0.15f) return row
        val glyphs = glyphs("notes")
        if (glyphs.isEmpty()) return row
        val note = rng.pick(glyphs)
        return when {
            !row.secondary.isNullOrBlank() -> row.copy(secondary = " $note ${row.secondary}")
            !row.translation.isNullOrBlank() -> row.copy(translation = " $note ${row.translation}")
            !row.text.isNullOrBlank() -> row.copy(text = "$note  ${row.text}")
            else -> row
        }
    }

    private fun renderWave(row: PluginLyricLine): PluginLyricLine {
        val words = row.words
        if (words == null || words.isEmpty()) return row
        if (stepMs <= 0L) return row

        val budget = if (row.end > 0L && row.begin > 0L) row.end - row.begin else null
        val count = words.size
        val safeStep = budget?.let { maxBudget ->
            ((maxBudget / count).toInt().coerceAtMost(stepMs.toInt()).toLong())
                .coerceAtLeast(1L)
        } ?: stepMs

        val updated = words.mapIndexed { i, w ->
            if (i == 0 || w.begin <= 0L) w else w.copy(begin = w.begin + i * safeStep)
        }
        return if (updated == words) row else row.copy(words = updated)
    }

    private companion object {
        val BREATHING_SYMBOL =
            listOf("·", "∙", "○", "◌", "●", "◐", "◑", "◒", "◓", "◔")
        val BREATHING_ASCII = listOf(".", "·", "o", "O", "o", "O")

        val PARTICLES_SYMBOL =
            listOf("✦", "✧", "✩", "✪", "✫", "✬", "✭", "✮", "❋", "❀", "✺", "❂")
        val PARTICLES_ASCII =
            listOf("*", "+", "~", "!", "@", "#", "$", "%", "&", "^")

        val RIPPLE_SYMBOL =
            listOf("〰", "≈", "〰", "≈", "～", "═", "━", "≋")
        val RIPPLE_ASCII = listOf("~", "=", "-", "^", "~", "=")

        val SPECTRUM_SYMBOL =
            listOf("▁", "▂", "▃", "▄", "▅", "▆", "▇", "█")
        val SPECTRUM_ASCII = listOf("_", "·", "o", "O", "#", "##")

        val NOTES_SYMBOL =
            listOf("♪", "♬", "♩", "♫", "♭", "♮", "♯")
        val NOTES_ASCII = listOf("^", "v", "(", ")", "[", "]")
    }
}
