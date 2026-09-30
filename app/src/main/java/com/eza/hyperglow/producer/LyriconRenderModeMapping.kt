package com.eza.hyperglow.producer

import com.eza.hyperglow.AppLog
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.resolveLineTransition
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
    // 换行动画取 profile.lineTransition("Auto" 退默认 Fade up);不能再借用场景过渡
    // preset id(continuity/crossfade/none),那是 AOD↔锁屏联动的词表,语义不同。
    transition = resolveLineTransition(lineTransition, "Fade up"),
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
 * 翻译词表 → [LyricWord]（与 [toLyricWords] 同构映射）。翻译是冗余对：SDK 的
 * `RichLyricLine.normalize` 会用非空 translationWords 重新生成 translation，但订阅端
 * 拿到的 Song 不经逐行 normalize，只带词表的源必须靠 [effectiveTranslation] 兜底。
 */
internal fun RichLyricLine.toTranslationWords(): List<LyricWord>? = translationWords?.map { w ->
    LyricWord(
        text = w.text.orEmpty(),
        romanized = "",
        startMs = w.begin,
        endMs = w.end,
        boundaryAfter = false
    )
}

/**
 * 翻译取文兜底（SDK 冗余对）：translation 非空优先，否则由 translationWords 拼出。
 * 与 `RichLyricLine.normalize` 的词表再生语义一致，但只在文本缺失时兜底、不覆盖已有文本。
 */
internal fun RichLyricLine.effectiveTranslation(): String =
    translation?.takeIf { it.isNotBlank() }
        ?: translationWords?.takeIf { it.isNotEmpty() }?.joinToString("") { it.text.orEmpty() }
        ?: ""

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
