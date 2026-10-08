package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.ArtworkDisplayConfig
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.METADATA_PARTS_DEFAULT
import com.eza.hyperglow.customization.METADATA_SEPARATORS_DEFAULT
import com.eza.hyperglow.customization.SECONDARY_TEXT_SIZE_PERCENT_DEFAULT
import com.eza.hyperglow.customization.artworkDisplayConfig
import com.eza.hyperglow.customization.composeSongMetadata
import com.eza.hyperglow.customization.resolveLineTransition
import com.eza.hyperglow.producer.stripDuetMarker
import com.eza.hyperglow.producer.stripDuetMarkerRun
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricWord

/**
 * 映射到画布内容;[artwork] 默认取自 [profile](per-surface 歌曲图片配置),可显式覆盖。
 * [duet] 曲面参与对唱渲染(息屏与锁屏调用方都传 true),再与该曲面自己的
 * `duetConcurrent` 开关相与——锁屏与息屏各自独立(上游为 AOD-only,CN+ 扩展到锁屏卡片);
 * 并发行的 alignedRight 经同一「对唱分侧」门控。
 *
 * **按面独立(per-surface)**:「歌曲信息内容」「识别对唱标记」由本面 [profile] 决定——
 * 快照只携带原始歌名/歌手/专辑与原始行文本/两版分侧取值,本函数按面组装/剥离/选侧,
 * 因此息屏与锁屏各改各的、互不联动。
 */
internal fun LyricSnapshot.toAodCanvasContent(
    profile: CompiledSurfaceProfile? = null,
    artwork: ArtworkDisplayConfig = artworkDisplayConfig(profile),
    duet: Boolean = false
): AodCanvasContent {
    // 本面「歌曲信息内容」:按本面 parts/separators 重新组装(原始 title/artist/album 随快照下发)。
    // 快照未携带任何原始切片时(旧消费方/演示态只给文档级组装值)回落 [metadata],
    // 避免按面重组把这份兜底值清空;携带原始切片时按本面重组(即使是空结果也尊重本面选择)。
    val surfaceMetadata = if (title.isNotBlank() || artist.isNotBlank() || album.isNotBlank()) {
        composeSongMetadata(
            title = title,
            artist = artist,
            album = album,
            parts = profile?.metadataParts ?: METADATA_PARTS_DEFAULT,
            separators = profile?.metadataSeparators ?: METADATA_SEPARATORS_DEFAULT,
            hideAlbumWhenSameAsTitle = profile?.hideAlbumWhenSameAsTitle ?: false
        )
    } else {
        metadata
    }
    // 本面「识别对唱标记」:开启时隐去行首标记,并取标记识别版分侧;关闭时原样显示、取身份版。
    val duetMarkers = profile?.duetMarkers != false
    // 大元数据引导:主行位置显示本面组装后的歌曲信息(快照下发的是占位符,这里替换)。
    val surfaceOriginal = when {
        largeMetadata -> surfaceMetadata
        duetMarkers -> stripDuetMarker(original)
        else -> original
    }
    val surfaceAlignedRight = if (duetMarkers) alignedRightMarkers else alignedRight
    return AodCanvasContent(
    trackGeneration = trackGeneration,
    metadata = surfaceMetadata,
    original = surfaceOriginal,
    romanized = romanized,
    translated = translated,
    nextLine = if (duetMarkers) stripDuetMarker(nextLine) else nextLine,
    nextLineRomanized = nextLineRomanized,
    nextLineTranslated = nextLineTranslated,
    // 对唱分侧门控:关闭时忽略行级 alignedRight(源显式与身份推导一并忽略),全部按主对齐解析。
    alignedRight = duetAlignedRight(surfaceAlignedRight, profile?.duetAlignment ?: true),
    lineLevelSync = lineLevelSync,
    lineStartMs = lineStartMs,
    lineEndMs = lineEndMs,
    positionMs = positionMs,
    sampledAtElapsedMs = sampledAtElapsedMs,
    // 快照的投递时间基准随内容进画布:换行过渡按它判「过期旧账」并跳过三段动画。
    updatedAtElapsedMs = updatedAtElapsedMs,
    speed = speed,
    // 逐字卡拉OK按词绘制:与主行文本同源剥离行首标记,否则标记会残留/错位。
    words = (if (duetMarkers) stripSurfaceDuetMarkerWords(words) else words).map {
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
    // 插件逐字翻译词表:片段文本即译文片段,不参与行首标记剥离(它不来自原文);
    // 词级数据随快照下发,按面开关(「辅助文字逐字效果」)在渲染侧取用。
    translationWords = translationWords.map {
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
    secondaryWordKaraoke = profile?.secondaryWordKaraoke ?: false,
    secondaryTextSizePercent = profile?.secondaryTextSizePercent
        ?: SECONDARY_TEXT_SIZE_PERCENT_DEFAULT,
    secondaryAutoSize = profile?.secondaryAutoSize ?: true,
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
    nextLineAux = profile?.nextLineAux ?: false,
    metadataAlignment = profile?.metadataAlignment ?: "auto",
    nextLineAlignment = profile?.nextLineAlignment ?: "auto",
    artworkJpeg = artworkJpeg,
    artworkKey = artworkKey,
    artworkVisible = artwork.visible,
    artworkShape = artwork.shape,
    artworkSpin = artwork.spins,
    artworkSpinWhenPaused = profile?.artworkSpinWhenPaused ?: false,
    artworkAdaptiveScale = artwork.adaptiveScale,
    artworkSizeDp = artwork.sizeDp,
    playbackPaused = pauseRetentionEligible,
    duetLine = if (duet && (profile?.duetConcurrent ?: true)) {
        duetLine?.let { line ->
            // 本面「识别对唱标记」:同源剥离并发行文本与逐字词表;剥空(纯标记行)整条丢弃。
            val duetText = if (duetMarkers) stripDuetMarker(line.text) else line.text
            if (duetText.isBlank()) {
                null
            } else {
                AodCanvasDuetLine(
                    text = duetText,
                    romanized = line.romanized,
                    translated = line.translated,
                    // 分侧门控与主行同源:关闭「对唱分侧」时并发行同样不按右对齐。
                    alignedRight = duetAlignedRight(
                        if (duetMarkers) line.alignedRightMarkers else line.alignedRight,
                        profile?.duetAlignment ?: true
                    ),
                    // 和声身份原样过面:渲染侧据此走辅助行车道(不按「对唱分侧」门控——
                    // 它不是演唱者身份,只是回声行的呈现车道)。
                    harmony = line.harmony,
                    lineStartMs = line.lineStartMs,
                    lineEndMs = line.lineEndMs,
                    words = (if (duetMarkers) stripSurfaceDuetMarkerWords(line.words) else line.words).map {
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
        }
    } else {
        null
    }
    )
}

/**
 * 词表级剥离行首标记(投影层 [LyricWord] 专用,语义与 producer 侧 `stripDuetMarkerWords` 一致:
 * 首词以行首标记起头时剥掉标记前缀,剥空则移除该词;无变化时返回输入实例)。
 * 快照为息屏/锁屏共用,逐字卡拉OK按词绘制,标记剥离必须与主行文本同源做在按面渲染处,
 * 否则标记会残留/错位。
 */
private fun stripSurfaceDuetMarkerWords(words: List<LyricWord>): List<LyricWord> {
    val first = words.firstOrNull() ?: return words
    val stripped = stripDuetMarkerRun(first.text)
    if (stripped == first.text) return words
    val out = words.toMutableList()
    if (stripped.isBlank()) {
        out.removeAt(0)
    } else {
        out[0] = first.copy(text = stripped)
    }
    return out
}
