package com.eza.hyperglow.producer

import com.eza.hyperglow.customization.CompiledSurfaceProfile
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
