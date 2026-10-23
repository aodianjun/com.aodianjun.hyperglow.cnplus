package com.eza.hyperglow.root.aod

import com.eza.hyperglow.aod.tailTimeoutActive
import com.eza.hyperglow.customization.ArtworkDisplayConfig
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.DEFAULT_SONG_INFO_ARTIST_SIZE_PERCENT
import com.eza.hyperglow.customization.METADATA_PARTS_DEFAULT
import com.eza.hyperglow.customization.METADATA_SEPARATORS_DEFAULT
import com.eza.hyperglow.customization.NO_LYRICS_BEHAVIOR_KEEP
import com.eza.hyperglow.customization.NO_LYRICS_BEHAVIOR_MUSIC_INFO
import com.eza.hyperglow.customization.NO_LYRICS_BEHAVIOR_RESTORE_DEFAULT
import com.eza.hyperglow.customization.SECONDARY_TEXT_SIZE_PERCENT_DEFAULT
import com.eza.hyperglow.customization.TAIL_TIMEOUT_BEHAVIOR_KEEP
import com.eza.hyperglow.customization.TAIL_TIMEOUT_BEHAVIOR_MUSIC_INFO
import com.eza.hyperglow.customization.TAIL_TIMEOUT_BEHAVIOR_RESTORE_DEFAULT
import com.eza.hyperglow.customization.TAIL_TIMEOUT_DELAY_SECONDS_DEFAULT
import com.eza.hyperglow.customization.artworkDisplayConfig
import com.eza.hyperglow.customization.composeSongMetadata
import com.eza.hyperglow.customization.normalizeMetadataLayout
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
    duet: Boolean = false,
    /**
     * 尾奏判定用的单调时钟;null 时按 [android.os.SystemClock.elapsedRealtime] 取当前值
     * (仅在 [tailEligible] 为真时读取,单测可显式传入保持可测)。
     */
    nowElapsedMs: Long? = null
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
    // 本面「无歌词/纯音乐歌词行为」(per-surface,与「识别对唱标记」同式):纯音乐曲目(整首只有
    // 「纯音乐请欣赏」标记,见 producer.PureMusicLyrics)与真无歌词曲目(producer lyricKind==NONE)
    // 的标记行语义只在本层解释一次;生产者侧照常下发原始标记行。KEEP(默认)不改动,MUSIC_INFO
    // 复用「大元数据引导」的主行槽位替换路径,RESTORE_DEFAULT 清空全部内容(系统内容抑制由
    // 控制器解除,见 AodSurfaceController)。
    val noLyricsBehavior = profile?.noLyricsBehavior ?: NO_LYRICS_BEHAVIOR_KEEP
    val noContent = pureMusic || noLyrics
    val noContentMusicInfo = noContent && noLyricsBehavior == NO_LYRICS_BEHAVIOR_MUSIC_INFO
    val noContentRestore = noContent && noLyricsBehavior == NO_LYRICS_BEHAVIOR_RESTORE_DEFAULT
    // 本面「尾奏超时行为」(per-surface,与「无歌词/纯音乐歌词行为」同式):快照只标记
    // tailEligible(处于尾奏上下文),延迟到点由本面自己的 tailTimeoutDelaySeconds 在此
    // 计算——息屏与锁屏延迟可以不同,互不等待。纯音乐/真无歌词优先,不叠加尾奏;
    // KEEP(默认)在到点后仍原样保留最后一行,即历史行为。
    val tailTimeoutBehavior = profile?.tailTimeoutBehavior ?: TAIL_TIMEOUT_BEHAVIOR_KEEP
    val tailActive = tailEligible && !noContent && tailTimeoutActive(
        tailEligible = true,
        lineStartMs = lineStartMs,
        lineEndMs = lineEndMs,
        positionMs = positionMs,
        sampledAtElapsedMs = sampledAtElapsedMs,
        speed = speed,
        nowElapsedMs = nowElapsedMs ?: android.os.SystemClock.elapsedRealtime(),
        delaySeconds = profile?.tailTimeoutDelaySeconds ?: TAIL_TIMEOUT_DELAY_SECONDS_DEFAULT
    )
    val tail = tailActive
    val tailMusicInfo = tail && tailTimeoutBehavior == TAIL_TIMEOUT_BEHAVIOR_MUSIC_INFO
    val tailRestore = tail && tailTimeoutBehavior == TAIL_TIMEOUT_BEHAVIOR_RESTORE_DEFAULT
    // 长间奏倒计时圆点(per-surface):投影层只携带原始空隙两端,本面开关与「显示下一行」
    // 延迟映射在此解析(见 interludeDotsWindow)。关闭时窗口为 null,呈现与改动前逐字一致。
    val interludeDots = interludeDotsWindow(
        interludeStartMs = interludeStartMs,
        interludeEndMs = interludeEndMs,
        enabled = profile?.interludeCountdown != false,
        showsNextLine = profile?.showNextLine == true || profile?.secondaryNextLine == true
    )
    // 大元数据引导:主行位置显示本面组装后的歌曲信息(快照下发的是占位符,这里替换)。
    // 无歌词内容 MUSIC_INFO 复用同一路径:标记行槽位改显示本面歌曲信息;元数据为空时回落
    // 原标记行(不能「隐藏了原内容却画不出任何东西」)。RESTORE_DEFAULT 直接置空。
    val surfaceOriginal = when {
        noContentRestore || tailRestore -> ""
        largeMetadata || noContentMusicInfo || tailMusicInfo -> surfaceMetadata.ifBlank { original }
        duetMarkers -> stripDuetMarker(original)
        else -> original
    }
    val surfaceAlignedRight = if (duetMarkers) alignedRightMarkers else alignedRight
    val content = AodCanvasContent(
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
    // 自定义效果随 per-surface 编译档进入画布内容;null = 内置档,行为不变。
    wordEffect = profile?.wordEffect,
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
    metadataLayout = normalizeMetadataLayout(profile?.metadataLayout),
    metadataArtistSizePercent = profile?.metadataArtistSizePercent
        ?: DEFAULT_SONG_INFO_ARTIST_SIZE_PERCENT,
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
    interludeDotsStartMs = interludeDots?.first ?: 0L,
    interludeDotsEndMs = interludeDots?.last ?: 0L,
    // 并发行门控(实机与预览同源,见 duetLineCarried):同尺寸并发行只认本面对唱开关;
    // 和声行两段门控——候选携带(对唱开关 ∨ 勾了和声档)且未被多选里的「和声」取消勾选。
    duetLine = if (duet && duetLineCarried(
            profile?.duetConcurrent ?: true,
            duetLine?.harmony == true,
            profile?.secondaryMode ?: "Main only"
        )
    ) {
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
    // 无歌词内容三态收尾(per-surface,纯音乐与真无歌词同门控):MUSIC_INFO 清掉标记行的
    // 词级/辅助/并发行/间奏数据(标记不是歌词,不参与卡拉OK/逐字);RESTORE_DEFAULT 再清空
    // 歌曲信息与封面,交还系统默认。
    return when {
        noContentRestore || tailRestore -> content.blankAllTailContent()
        noContentMusicInfo || tailMusicInfo -> content.blankTailEnhancements()
        else -> content
    }
}

/** 尾奏/无歌词 MUSIC_INFO:清掉词级/辅助/下一行/并发行/间奏数据(歌曲信息不参与卡拉OK)。 */
private fun AodCanvasContent.blankTailEnhancements(): AodCanvasContent = copy(
    romanized = "",
    translated = "",
    nextLine = "",
    nextLineRomanized = "",
    nextLineTranslated = "",
    words = emptyList(),
    translationWords = emptyList(),
    ruby = emptyList(),
    layoutGroups = emptyList(),
    alignedRight = false,
    lineLevelSync = false,
    duetLine = null,
    interludeDotsStartMs = 0L,
    interludeDotsEndMs = 0L
)

/** 尾奏/无歌词 RESTORE_DEFAULT:再清空歌曲信息与封面,交还系统默认。 */
private fun AodCanvasContent.blankAllTailContent(): AodCanvasContent = blankTailEnhancements().copy(
    metadata = "",
    metadataVisible = false,
    original = "",
    artworkJpeg = ByteArray(0),
    artworkKey = "",
    artworkVisible = false
)

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

/**
 * 无歌词内容(纯音乐标记行或真无歌词)且本面行为取「交还系统默认」时,控制器必须解除
 * 系统内容抑制:否则系统内容被藏而本面又无内容可显示,留下空白屏。息屏与锁屏共用同一判据。
 */
internal fun shouldReleaseStockForNoContent(
    pureMusic: Boolean,
    noLyrics: Boolean,
    noLyricsBehavior: String
): Boolean = (pureMusic || noLyrics) && noLyricsBehavior == NO_LYRICS_BEHAVIOR_RESTORE_DEFAULT

/**
 * 尾奏超时且本面行为取「交还系统默认」时,控制器同样必须解除系统内容抑制:否则最后一行
 * 被清空、系统内容又被藏,留下空白屏。纯音乐/真无歌词由 [shouldReleaseStockForNoContent]
 * 优先判定(调用方两者都查,任一命中即解除)。
 */
internal fun shouldReleaseStockForTailTimeout(
    tailActive: Boolean,
    tailTimeoutBehavior: String
): Boolean = tailActive && tailTimeoutBehavior == TAIL_TIMEOUT_BEHAVIOR_RESTORE_DEFAULT
