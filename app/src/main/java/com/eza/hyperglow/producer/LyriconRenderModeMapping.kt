package com.eza.hyperglow.producer

import com.eza.hyperglow.AppLog
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import io.github.proify.lyricon.lyric.model.RichLyricLine

internal fun CompiledSurfaceProfile.toProducerRenderModes() = ProducerRenderModes(
    weight = weight,
    textSize = textSize,
    textSizeCustom = textSizeCustom,
    secondary = secondaryMode,
    animation = animation,
    glow = glow,
    lineSyncFill = lineSyncFillMode,
    overflow = overflow,
    transition = transition.id,
    font = fontFamily
)

internal fun RichLyricLine.toLyricWords(): List<LyricWord>? = words?.map { w ->
    // io.github.proify.lyricon.lyric.model.LyricWord has begin/end/text.
    // boundaryAfter is a Spicy-specific concept; default false (the engine treats word
    // boundaries from begin/end timing). roma is line-level (RichLyricLine.ruma), not per-word.
    LyricWord(
        text = w.text.orEmpty(),
        romanized = "",
        startMs = w.begin,
        endMs = w.end,
        boundaryAfter = false
    )
}

/**
 * Refresh [renderModesSnapshot] from the AOD [CompiledSurfaceProfile]. Called on start and
 * song change — NOT at 60 Hz (compile is non-trivial). Per spec clause 5, the lyricon `Song`
 * carries no render modes, so they are sourced from HyperGlow's own customization.
 */
@Synchronized
internal fun LyriconLyricProducer.refreshRenderModes() {
    val ctx = contextRef ?: run {
        renderModesSnapshot = LyriconLyricProducer.defaultRenderModes()
        return
    }
    renderModesSnapshot = runCatching {
        val compiled = CustomizationRepository.loadCompiled(ctx)
        val profile = compiled.profiles[SceneCompiler.SURFACE_AOD]
        profile?.toProducerRenderModes() ?: LyriconLyricProducer.defaultRenderModes()
    }.onFailure {
        AppLog.w("LyriconLyricProducer", "refreshRenderModes failed, using defaults", it)
    }.getOrDefault(LyriconLyricProducer.defaultRenderModes())
}
