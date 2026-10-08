package com.eza.hyperglow.root.aod

internal data class AodCanvasLayoutGroup(
    val start: Int,
    val end: Int,
    val kind: String,
    val keepTogether: Boolean,
    val confidence: Double
)

internal data class RubySpanGeometry(
    val spanX: Float,
    val spanWidth: Float,
    val baseX: Float,
    val baseWidth: Float,
    val extraWidth: Float,
    val rubyCenterX: Float
)

internal data class MetadataLayoutBounds(
    val metadataBaseline: Float,
    val lyricStart: Float,
    val lyricEnd: Float
)

internal enum class SpotlightWordState { SUNG, ACTIVE, UNSUNG }

internal data class AodCanvasContent(
    val trackGeneration: Long,
    val metadata: String,
    val original: String,
    val romanized: String,
    val translated: String,
    val nextLine: String = "",
    /** 下一行歌词的辅助文字(音标/翻译,按 secondaryMode 取用;「显示第二行辅助文字」开启时有内容才显示)。 */
    val nextLineRomanized: String = "",
    val nextLineTranslated: String = "",
    val alignedRight: Boolean,
    val lineLevelSync: Boolean,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val positionMs: Long,
    val sampledAtElapsedMs: Long,
    /**
     * 快照的投递时间基准(producer 侧 `updatedAtElapsedMs`):画布用它判「过期旧账」——
     * 年龄超过一次过渡总时长时跳过换行三段动画(见 [shouldSkipLineTransition])。
     * 0 = 未知(预览/直接构造),年龄判据不生效。
     */
    val updatedAtElapsedMs: Long = 0L,
    val speed: Float,
    val words: List<AodCanvasWord>,
    /**
     * 插件提供的逐字翻译词表(词级译文 + 时间窗,见 `PluginLyricField.TRANSLATION_WORDS`):
     * 「辅助文字逐字效果」开启时翻译辅助行按这些真实词窗点亮,片段文本直接相连构成整行译文
     * (分隔符由插件写在片段内)。空表 = 回落行窗口 + 行内几何合成(历史行为)。
     */
    val translationWords: List<AodCanvasWord> = emptyList(),
    val ruby: List<AodCanvasRuby>,
    val layoutGroups: List<AodCanvasLayoutGroup>,
    val weight: String,
    val textSizeMode: String,
    val textSizeCustom: Int,
    val secondaryMode: String,
    val animationMode: String,
    val glowMode: String,
    val motionMode: String,
    val lineSyncFillMode: String,
    val overflowMode: String,
    val transitionMode: String,
    /** 换行动画速率档(Slowest/Slow/Normal/Fast/Fastest),等比缩放退场/入场/晋级位移时长;默认 Normal。 */
    val lineTransitionSpeed: String = "Normal",
    val fontFamily: String,
    val alignmentMode: String,
    val metadataVisible: Boolean,
    val metadataAnchor: String,
    val metadataSizePercent: Int = 100,
    val adaptiveSectioning: Boolean,
    val palette: Map<String, String>,
    val secondaryTextBright: Boolean = true,
    /** 辅助文字逐字效果:见 SurfaceProfile.secondaryWordKaraoke。 */
    val secondaryWordKaraoke: Boolean = false,
    /** 辅助文字字号倍率(相对主行百分比),见 SurfaceProfile.secondaryTextSizePercent。 */
    val secondaryTextSizePercent: Int = com.eza.hyperglow.customization.SECONDARY_TEXT_SIZE_PERCENT_DEFAULT,
    /** 辅助文字自适应大小(装不下时缩到可读性下限),见 SurfaceProfile.secondaryAutoSize。 */
    val secondaryAutoSize: Boolean = true,
    val lyricLineLimit: Int = 3,
    val showNextLine: Boolean = false,
    /** 辅助文字显示第二行歌词:见 SurfaceProfile.secondaryNextLine。 */
    val secondaryNextLine: Boolean = false,
    /** 显示第二行辅助文字:以第二行歌词行实际显示为前提;见 SurfaceProfile.nextLineAux。 */
    val nextLineAux: Boolean = false,
    /** 歌曲信息对齐(auto/start/center/end),auto 跟随主对齐解析;见 SurfaceProfile.metadataAlignment。 */
    val metadataAlignment: String = "auto",
    /** 第二行歌词对齐(auto/start/center/end),auto 跟随主对齐解析;见 SurfaceProfile.nextLineAlignment。 */
    val nextLineAlignment: String = "auto",
    /** 歌曲图片帧(有界 JPEG,空数组=无封面);仅经包名/曲目校对的封面非空。 */
    val artworkJpeg: ByteArray = ByteArray(0),
    /** 封面稳定键;空串=无封面,画布按帧缓存解码位图。 */
    val artworkKey: String = "",
    /** 歌曲图片显示开关(per-surface profile 配置,见 ArtworkDisplayConfig)。 */
    val artworkVisible: Boolean = false,
    /** 歌曲图片形状 token(square/circle),见 [com.eza.hyperglow.customization.ARTWORK_SHAPES]。 */
    val artworkShape: String = "square",
    /** 圆形歌曲图片是否旋转;仅圆形生效(见 ArtworkDisplayConfig.spins)。 */
    val artworkSpin: Boolean = false,
    /** 暂停驻留期间是否继续旋转(见 SurfaceProfile.artworkSpinWhenPaused)。 */
    val artworkSpinWhenPaused: Boolean = false,
    /** 歌曲图片自适应缩放(见 ArtworkDisplayConfig.adaptiveScale):关闭时边长取固定 [artworkSizeDp]。 */
    val artworkAdaptiveScale: Boolean = true,
    /** 自定义歌曲图片边长(dp);仅 [artworkAdaptiveScale] 关闭时生效。 */
    val artworkSizeDp: Int = com.eza.hyperglow.customization.ARTWORK_SIZE_DEFAULT_DP,
    /** 当前快照是否为暂停驻留的冻结帧(pauseRetentionEligible):为真时旋转默认停。 */
    val playbackPaused: Boolean = false,
    /** 对唱并发行(仅息屏);null = 无并发行或「显示并发歌词(对唱)」已关。 */
    val duetLine: AodCanvasDuetLine? = null
)

/**
 * 对唱并发行(画布模型,仅息屏消费):与主行播放窗口重叠的另一唱词行,在主行下方
 * 同尺寸堆叠渲染,各画各的词级扫光。v1 不携带 ruby/layoutGroups。
 * [harmony] 为真时是插件行 role=BG 的 x-bg 回声,改走辅助行车道(小字号辅助行),
 * 不再同尺寸堆叠——两条一样的大字行是错观感(真机 2026-10-07 反馈)。
 */
internal data class AodCanvasDuetLine(
    val text: String,
    val romanized: String = "",
    val translated: String = "",
    val alignedRight: Boolean = false,
    /** 和声行标记;渲染侧据此选辅助行车道,见 [AodCanvasContent.duetLine]。 */
    val harmony: Boolean = false,
    val lineStartMs: Long = 0L,
    val lineEndMs: Long = 0L,
    val words: List<AodCanvasWord> = emptyList()
)

/**
 * 对唱共享缩放:并发行加入后内容块超出画布内容区时,整块(主行+并发行)按同一比例缩小,
 * 0.3 为绝对下限(低于下限不再缩,超出部分被裁——下限不保证容纳,上游同语义);
 * 内容装得下时不缩放(nobody shrinks unless the combined content exceeds the canvas)。
 */
internal fun duetSharedFitScale(combinedHeightPx: Float, availableHeightPx: Float): Float =
    if (availableHeightPx <= 0f || combinedHeightPx <= availableHeightPx) {
        1f
    } else {
        (availableHeightPx / combinedHeightPx).coerceIn(0.3f, 1f)
    }

/** 下一行歌词的呈现方式;同一行只会以其中一种形态出现,不叠加。 */
internal enum class SecondLinePresentation { NONE, AS_SECONDARY, STANDALONE }

/**
 * 第一行辅助文字是否实际显示(与实机行装配、预览 previewSecondaryLines 同源):
 * 按辅助文字模式取音标/翻译,该行内容非空即成立。
 */
internal fun hasFirstLineAuxText(
    secondaryMode: String,
    romanized: String,
    translated: String
): Boolean {
    val showReading = secondaryMode == "Transliteration" || secondaryMode == "Both"
    val showTranslation = secondaryMode == "Translation" || secondaryMode == "Both"
    return (showReading && romanized.isNotBlank()) || (showTranslation && translated.isNotBlank())
}

/**
 * 下一行歌词呈现决策(实机 AodLyricCanvasView 与预览 PreviewComponents 同源):
 * 「辅助文字显示第二行歌词」仅在第一行辅助文字实际显示时生效——此时第二行以辅助文字
 * 样式绘制并取代独立的「显示下一行歌词」行;第一行没有辅助文字时该开关不产生第二行
 * 呈现,独立下一行行按「显示下一行歌词」照常。「显示第二行辅助文字」开启时,第二行
 * 歌词行本身也按辅助文字形态呈现(即使 [secondaryNextLine] 关闭——此时以「显示下一行
 * 歌词」为前提;两个第二行开关都关闭时不产生第二行呈现),并在成立时追加其自身的
 * 辅助文字行(见 [secondLineAuxRows],独立下一行行形态同样追加)。
 * 样式与颜色解耦:辅助文字形态只借辅助文字的字号/亮度档,颜色恒走「下一行颜色」
 * (见 [secondLineColorArgb]),否则该颜色设置对辅助文字形态失效。
 */
internal fun secondLinePresentation(
    secondaryNextLine: Boolean,
    showNextLine: Boolean,
    hasLine: Boolean,
    hasFirstLineAux: Boolean,
    nextLineAux: Boolean
): SecondLinePresentation = when {
    !hasLine -> SecondLinePresentation.NONE
    secondLineRendersAsSecondary(
        secondaryNextLine,
        showNextLine,
        hasLine,
        hasFirstLineAux,
        nextLineAux
    ) ->
        SecondLinePresentation.AS_SECONDARY
    showNextLine -> SecondLinePresentation.STANDALONE
    else -> SecondLinePresentation.NONE
}

/**
 * 第二行是否按辅助文字形态呈现(绘制期取色/亮度档与行装配共用本判定,防止两处漂移):
 * 「辅助文字显示第二行歌词」开启且第一行辅助文字实际显示;或「显示第二行辅助文字」开启
 * 且第二行歌词行会显示(「显示下一行歌词」开启)——后者即使「辅助文字显示第二行歌词」
 * 关闭也成立(owner 2026-10-04:第四行以第二行歌词行为前提,第二行歌词行本身沿用辅助
 * 文字形态,不区分其以哪种形态呈现)。
 */
internal fun secondLineRendersAsSecondary(
    secondaryNextLine: Boolean,
    showNextLine: Boolean,
    hasLine: Boolean,
    hasFirstLineAux: Boolean,
    nextLineAux: Boolean
): Boolean = hasLine && (
    (secondaryNextLine && hasFirstLineAux) ||
        (nextLineAux && showNextLine)
    )

/** 第二行歌词的辅助文字行(按辅助文字模式取音标/翻译,有内容才出)。 */
internal enum class SecondLineAuxRow { ROMANIZED, TRANSLATED }

/**
 * 「显示第二行辅助文字」的行清单(实机 AodLyricCanvasView 与预览 PreviewComponents 同源):
 * 按 [secondaryMode] 取该行自己的音标/翻译行,文本为空则跳过;开关关闭恒空。
 * 行序与主行辅助文字一致(音标在前、翻译在后)。
 */
/**
 * 第二行辅助行(音标/翻译)的换行档:跟随第二行歌词自身呈现的行数(至少 1 行),而不是主行行数
 * ——第二行短于/长于主行时,辅助行的折行跟随它所属的那一行(owner 2026-10-02 真机反馈:
 * 此前误用主行行数,短第二行的辅助文字被折成主行那么多行)。
 */
internal fun secondLineAuxPreferredLines(nextLineRenderedLineCount: Int): Int =
    nextLineRenderedLineCount.coerceAtLeast(1)

internal fun secondLineAuxRows(
    nextLineAux: Boolean,
    secondaryMode: String,
    nextLineRomanized: String,
    nextLineTranslated: String
): List<SecondLineAuxRow> {
    if (!nextLineAux) return emptyList()
    val rows = ArrayList<SecondLineAuxRow>(2)
    if ((secondaryMode == "Transliteration" || secondaryMode == "Both") &&
        nextLineRomanized.isNotBlank()
    ) {
        rows += SecondLineAuxRow.ROMANIZED
    }
    if ((secondaryMode == "Translation" || secondaryMode == "Both") &&
        nextLineTranslated.isNotBlank()
    ) {
        rows += SecondLineAuxRow.TRANSLATED
    }
    return rows
}

/**
 * 主对齐解析(实机 AodLyricCanvasView.setContent 与预览 PreviewComponents 同源):
 * 显式 start/center/end 直接生效;"auto" 按 alignedRight(歌词方向)右对齐,否则左对齐。
 */
internal fun resolveAlignmentMode(mode: String, alignedRight: Boolean): String = when (mode) {
    "start", "center", "end" -> mode
    else -> if (alignedRight) "end" else "start"
}

/**
 * 行级对齐解析(歌曲信息/第二行歌词独立对齐,实机 alignmentFor 与预览同源):
 * 显式 start/center/end 直接生效;"auto" 跟随主对齐的解析结果(见 [resolveAlignmentMode]),
 * 即默认与主歌词对齐保持一致;非法值按 "auto" 处理。
 */
internal fun resolveRowAlignmentMode(
    rowAlignment: String,
    mainAlignment: String,
    alignedRight: Boolean
): String = when (rowAlignment) {
    "start", "center", "end" -> rowAlignment
    else -> resolveAlignmentMode(mainAlignment, alignedRight)
}

/**
 * 对唱分侧门控(实机 [LyricSnapshot.toAodCanvasContent] 与预览同源):
 * 关闭时忽略行级 alignedRight,全部行按主对齐解析;开启时保留源显式值与演唱者身份推导结果
 * (见 [com.eza.hyperglow.producer.resolveDuetAlignment])。
 */
internal fun duetAlignedRight(alignedRight: Boolean, duetAlignment: Boolean): Boolean =
    alignedRight && duetAlignment

internal data class AodCanvasLineIdentity(
    val trackGeneration: Long,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val original: String
)

internal fun aodCanvasLineIdentity(content: AodCanvasContent): AodCanvasLineIdentity =
    AodCanvasLineIdentity(
        content.trackGeneration,
        content.lineStartMs,
        content.lineEndMs,
        content.original
    )

/**
 * 「同曲同文」判据(纯函数):主行文本未变,只是行时间窗更新——空档预览行在真正开始时
 * 正是这一形态(预览退化窗 [nextLineStartMs, nextLineStartMs] → 真实行窗
 * [lineStartMs, lineEndMs])。屏上文本未变的更新不是换行,不应触发换行动画,否则同一句
 * 会播两次入场动画(空档开始一次、开唱一次);文本相同但曲目不同(trackGeneration 变化)
 * 不算,换歌/重播仍照常播换行动画。见 [AodLyricCanvasView.setContent] 的行变更判定。
 */
internal fun isSameLineTextUpdate(
    previous: AodCanvasLineIdentity,
    next: AodCanvasLineIdentity
): Boolean = previous.trackGeneration == next.trackGeneration &&
    previous.original.isNotBlank() &&
    previous.original == next.original
