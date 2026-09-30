package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.ArtworkDisplayConfig
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.artworkDisplayConfig
import com.eza.hyperglow.customization.resolveLineTransition
import com.eza.hyperglow.root.projection.LyricSnapshot

/**
 * 映射到画布内容;[artwork] 默认取自 [profile](per-surface 歌曲图片配置),可显式覆盖。
 * [duet] 仅息屏调用方传 true(对唱并发行是息屏专属,锁屏卡片恒 solo,上游同语义);
 * 并发行的 alignedRight 经同一「对唱分侧」门控。
 */
internal fun LyricSnapshot.toAodCanvasContent(
    profile: CompiledSurfaceProfile? = null,
    artwork: ArtworkDisplayConfig = artworkDisplayConfig(profile),
    duet: Boolean = false
): AodCanvasContent = AodCanvasContent(
    trackGeneration = trackGeneration,
    metadata = metadata,
    original = original,
    romanized = romanized,
    translated = translated,
    nextLine = nextLine,
    // 对唱分侧门控:关闭时忽略行级 alignedRight(源显式与身份推导一并忽略),全部按主对齐解析。
    alignedRight = duetAlignedRight(alignedRight, profile?.duetAlignment ?: true),
    lineLevelSync = lineLevelSync,
    lineStartMs = lineStartMs,
    lineEndMs = lineEndMs,
    positionMs = positionMs,
    sampledAtElapsedMs = sampledAtElapsedMs,
    speed = speed,
    words = words.map {
        AodCanvasWord(
            it.text,
            it.romanized,
            it.startMs,
            it.endMs,
            it.boundaryAfter,
            it.sourceStart,
            it.sourceEnd
        )
    },
    ruby = if (profile?.rubyVisible == false) {
        emptyList()
    } else {
        ruby.map { AodCanvasRuby(it.start, it.end, it.reading) }
    },
    layoutGroups = layoutGroups.map {
        AodCanvasLayoutGroup(it.start, it.end, it.kind, it.keepTogether, it.confidence)
    },
    weight = profile?.weight ?: weight,
    textSizeMode = profile?.textSize ?: textSizeMode,
    textSizeCustom = profile?.textSizeCustom ?: textSizeCustom,
    secondaryMode = profile?.secondaryMode ?: secondaryMode,
    secondaryTextBright = profile?.secondaryTextBright ?: true,
    lyricLineLimit = profile?.lyricLineLimit ?: 3,
    animationMode = profile?.animation ?: animationMode,
    glowMode = profile?.glow ?: glowMode,
    motionMode = normalizeAodMotion(motionMode),
    lineSyncFillMode = when (profile?.lineSyncFillMode) {
        "None",
        "Top to bottom",
        "Left to right (main only)",
        "Left to right (whole block)" -> profile.lineSyncFillMode
        "Left to right" -> "Left to right (main only)"
        else -> lineSyncFillMode
    },
    overflowMode = profile?.overflow ?: overflowMode,
    // 换行动画:profile 显式选择优先("Auto" 时沿用歌词源偏好,见 resolveLineTransition)。
    transitionMode = resolveLineTransition(profile?.lineTransition, transitionMode),
    // 换行动画速率:纯视觉偏好,无源偏好语义,缺省 Normal(基准时长)。
    lineTransitionSpeed = profile?.lineTransitionSpeed ?: "Normal",
    fontFamily = profile?.fontFamily ?: fontFamily,
    alignmentMode = profile?.alignment ?: alignmentMode,
    metadataVisible = profile?.metadataVisible ?: metadataVisible,
    metadataAnchor = if ((profile?.metadataAnchor ?: metadataAnchor) == "bottom") "bottom" else "top",
    metadataSizePercent = profile?.metadataSizePercent ?: 100,
    adaptiveSectioning = profile?.adaptiveSectioning ?: adaptiveSectioning,
    palette = profile?.palette.orEmpty(),
    showNextLine = profile?.showNextLine ?: false,
    secondaryNextLine = profile?.secondaryNextLine ?: false,
    metadataAlignment = profile?.metadataAlignment ?: "auto",
    nextLineAlignment = profile?.nextLineAlignment ?: "auto",
    artworkJpeg = artworkJpeg,
    artworkKey = artworkKey,
    artworkVisible = artwork.visible,
    artworkShape = artwork.shape,
    artworkSpin = artwork.spins,
    artworkSpinWhenPaused = profile?.artworkSpinWhenPaused ?: false,
    playbackPaused = pauseRetentionEligible,
    duetLine = if (duet) {
        duetLine?.let { line ->
            AodCanvasDuetLine(
                text = line.text,
                romanized = line.romanized,
                translated = line.translated,
                // 分侧门控与主行同源:关闭「对唱分侧」时并发行同样不按右对齐。
                alignedRight = duetAlignedRight(line.alignedRight, profile?.duetAlignment ?: true),
                lineStartMs = line.lineStartMs,
                lineEndMs = line.lineEndMs,
                words = line.words.map {
                    AodCanvasWord(
                        it.text,
                        it.romanized,
                        it.startMs,
                        it.endMs,
                        it.boundaryAfter,
                        it.sourceStart,
                        it.sourceEnd
                    )
                }
            )
        }
    } else {
        null
    }
)
