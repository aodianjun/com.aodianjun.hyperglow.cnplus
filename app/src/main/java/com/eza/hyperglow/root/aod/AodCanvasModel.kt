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
    val alignedRight: Boolean,
    val lineLevelSync: Boolean,
    val lineStartMs: Long,
    val lineEndMs: Long,
    val positionMs: Long,
    val sampledAtElapsedMs: Long,
    val speed: Float,
    val words: List<AodCanvasWord>,
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
    val fontFamily: String,
    val alignmentMode: String,
    val metadataVisible: Boolean,
    val metadataAnchor: String,
    val metadataSizePercent: Int = 100,
    val adaptiveSectioning: Boolean,
    val palette: Map<String, String>,
    val secondaryTextBright: Boolean = true,
    val lyricLineLimit: Int = 3,
    val showNextLine: Boolean = false,
    /** 辅助文字显示第二行歌词:见 SurfaceProfile.secondaryNextLine。 */
    val secondaryNextLine: Boolean = false,
    /** 歌曲信息对齐(auto/start/center/end),auto 跟随主对齐解析;见 SurfaceProfile.metadataAlignment。 */
    val metadataAlignment: String = "auto",
    /** 第二行歌词对齐(auto/start/center/end),auto 跟随主对齐解析;见 SurfaceProfile.nextLineAlignment。 */
    val nextLineAlignment: String = "auto"
)

/** 下一行歌词的呈现方式;同一行只会以其中一种形态出现,不叠加。 */
internal enum class SecondLinePresentation { NONE, AS_SECONDARY, STANDALONE }

/**
 * 下一行歌词呈现决策(实机 AodLyricCanvasView 与预览 PreviewComponents 同源):
 * 「辅助文字显示第二行歌词」开启时以辅助文字样式绘制并取代独立的「显示下一行歌词」行;
 * 关闭时维持独立下一行行的既有呈现。
 */
internal fun secondLinePresentation(
    secondaryNextLine: Boolean,
    showNextLine: Boolean,
    hasLine: Boolean
): SecondLinePresentation = when {
    !hasLine -> SecondLinePresentation.NONE
    secondaryNextLine -> SecondLinePresentation.AS_SECONDARY
    showNextLine -> SecondLinePresentation.STANDALONE
    else -> SecondLinePresentation.NONE
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
