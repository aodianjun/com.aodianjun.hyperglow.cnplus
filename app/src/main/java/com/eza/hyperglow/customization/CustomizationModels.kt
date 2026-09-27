package com.eza.hyperglow.customization

import kotlinx.serialization.Serializable

@Serializable
data class CustomizationDocument(
    val version: Int = CURRENT_CUSTOMIZATION_VERSION,
    val id: String = "default_continuity",
    val name: String = "Seamless Default",
    val linkSurfaces: Boolean = false,
    /** 歌曲信息显示部分(歌名/歌手/专辑),见 [METADATA_PARTS] 与 [normalizeMetadataParts];全局生效,同时作用于息屏与锁屏。 */
    val metadataParts: String = METADATA_PARTS_DEFAULT,
    /** 歌曲信息分隔符 token,见 [METADATA_SEPARATORS];全局生效,同时作用于息屏与锁屏。 */
    val metadataSeparator: String = METADATA_SEPARATOR_NEWLINE,
    val profiles: Map<String, SurfaceProfile> = emptyMap()
)

@Serializable
data class SurfaceProfile(
    val enabled: Boolean = true,
    val anchor: String = "below_stock_clock",
    /** 时钟跟随模式:true=实时跟随实际时钟位置定位歌词;false=锚定防抖(默认,避免时钟振荡导致歌词跳动)。仅作旧版迁移载体,运行时以 aod_render prefs 的 aod_clock_follow 为准。 */
    val aodClockFollow: Boolean = false,
    val widthFraction: Float = 0.88f,
    val maxHeightFraction: Float = 0.46f,
    val verticalBias: Float = 0.5f,
    val collisionPolicy: String = "avoid",
    val widgets: List<WidgetSpec> = listOf(WidgetSpec("lyrics")),
    val transition: TransitionPreset = TransitionPreset(),
    val alignment: String = "auto",
    val secondaryMode: String = "Main only",
    val secondaryTextBright: Boolean = true,
    val lyricLineLimit: Int = DEFAULT_LYRIC_LINE_LIMIT,
    /** Show the upcoming next lyric line dimmed below the active line. */
    val showNextLine: Boolean = false,
    /**
     * 辅助文字显示第二行歌词:开启后下一行歌词以辅助文字样式(辅助行颜色+高亮辅助文字+
     * 音标行字号公式)绘制在主行下方,并取代「显示下一行歌词」的独立行(同一行不重复出现)。
     */
    val secondaryNextLine: Boolean = false,
    val metadataVisible: Boolean = false,
    val metadataAnchor: String = "top",
    val metadataSizePercent: Int = 100,
    /**
     * 歌曲信息(歌名/歌手)对齐:auto/start/center/end。"auto" 跟随主歌词对齐的解析结果
     * (见 resolveRowAlignmentMode),显式值独立于主对齐生效。
     */
    val metadataAlignment: String = "auto",
    /**
     * 第二行歌词对齐:auto/start/center/end。"auto" 跟随主歌词对齐的解析结果
     * (见 resolveRowAlignmentMode),显式值独立于主对齐生效,两种呈现形态(辅助文字/
     * 独立下一行行)共用。
     */
    val nextLineAlignment: String = "auto",
    val rubyVisible: Boolean = true,
    val weight: String = "Medium",
    val textSize: String = "normal",
    val textSizeCustom: Int = 100,
    val fontFamily: String = "spotify",
    val animation: String = "Gradient",
    val glow: String = "Off",
    val lineSyncFillMode: String = "Left to right (main only)",
    val overflow: String = "Wrap",
    /** 换行动画,见 [LINE_TRANSITION_MODES]。"Auto"=跟随歌词源自身的偏好。 */
    val lineTransition: String = LINE_TRANSITION_AUTO,
    val adaptiveSectioning: Boolean = true,
    val palette: Map<String, String> = emptyMap(),
    val backgroundStyle: String = "auto",
    /** 卡片背景不透明度,0-100。0 完全透明,100 完全不透明。 */
    val cardAlpha: Int = 85,
    /** 卡片背景色 token,见 [CARD_COLOR_VALUES]。 */
    val cardColor: String = "black"
)

@Serializable
data class WidgetSpec(
    val type: String,
    val style: String = "primary",
    val optional: Boolean = false,
    val visible: Boolean = true
)

@Serializable
data class TransitionPreset(
    val id: String = "continuity",
    val durationMs: Int = 320,
    val easing: String = "fast_out_slow_in"
)

@Serializable
data class CompiledCustomization(
    val version: Int,
    val revision: Long,
    val hash: String,
    val sourceId: String,
    val linkSurfaces: Boolean,
    /** 歌曲信息显示部分(歌名/歌手/专辑);全局生效,同时作用于息屏与锁屏,由 [CustomizationDocument.metadataParts] 编译而来。 */
    val metadataParts: String = METADATA_PARTS_DEFAULT,
    /** 歌曲信息分隔符 token;全局生效,由 [CustomizationDocument.metadataSeparator] 编译而来。 */
    val metadataSeparator: String = METADATA_SEPARATOR_NEWLINE,
    val profiles: Map<String, CompiledSurfaceProfile>,
    val pauseLingerMs: Long = 5_000L,
    /** 暂停时显示歌曲信息、歌词:App 端运行时开关,随配置下发到 SystemUI,同时作用于息屏与锁屏驻留。 */
    val pauseShowContent: Boolean = false,
    val diagnosticLogging: Boolean = false,
    val lockscreenKeepAwake: Boolean = false,
    val raiseToAod: Boolean = false,
    val suppressLockscreenEditorLongPress: Boolean = false,
    /** AOD 亮度增强:App 端运行时开关,随配置下发给 AOD 进程的 AodBrightnessController。 */
    val aodBrightnessBoost: Boolean = true,
    /**
     * AOD 亮度增强模式:false=按场景自动化钳制到可读亮度;true=用 [aodBrightnessLevel]
     * 固定的自定义亮度覆盖。仅当 [aodBrightnessBoost] 为 true 时生效。
     */
    val aodBrightnessOverride: Boolean = false,
    /** 自定义 AOD 亮度(10-255),仅在 [aodBrightnessOverride] 为 true 时生效。 */
    val aodBrightnessLevel: Int = 255,
    /**
     * 自定义系统时钟钉住位置的垂直偏移(px,负值上移、正值下移)。
     * 仅关闭「实时跟随系统时钟」且 AOD 渲染时生效,叠加到被钉住的系统时钟 Y 上。
     */
    val aodClockYOffset: Int = 0,
    /**
     * 渲染刷新率上限档(issue #68 #12):0=跟随现有行为(16ms);60/90/120=用户可选
     * 上限。App 端运行时开关,随配置下发到 SystemUI 侧 AodLyricCanvasView 帧调度。
     */
    val aodRefreshRateCap: Int = 0
)

@Serializable
data class CompiledSurfaceProfile(
    val surface: String,
    val enabled: Boolean,
    val anchor: String,
    /** 时钟跟随模式:true=实时跟随实际时钟;false=锚定防抖。由 SurfaceProfile.aodClockFollow 编译而来,RuntimeCustomization.loadCompiled 会以 aod_render prefs 的 aod_clock_follow 覆盖。 */
    val aodClockFollow: Boolean = false,
    val widthFraction: Float,
    val maxHeightFraction: Float,
    val verticalBias: Float,
    val collisionPolicy: String,
    val widgets: List<WidgetSpec>,
    val transition: TransitionPreset,
    val alignment: String,
    val secondaryMode: String,
    val metadataVisible: Boolean,
    val metadataAnchor: String,
    val weight: String,
    val textSize: String,
    val textSizeCustom: Int,
    val fontFamily: String,
    val animation: String,
    val glow: String,
    val lineSyncFillMode: String,
    val overflow: String,
    /** 换行动画,见 [LINE_TRANSITION_MODES];由 [SurfaceProfile.lineTransition] 编译而来。 */
    val lineTransition: String = LINE_TRANSITION_AUTO,
    val adaptiveSectioning: Boolean,
    val palette: Map<String, String>,
    val backgroundStyle: String = "none",
    /** 卡片背景不透明度,0-100。仅当 backgroundStyle=="card" 时生效。 */
    val cardAlpha: Int = 85,
    /** 卡片背景色 token,见 [CARD_COLOR_VALUES]。仅当 backgroundStyle=="card" 时生效。 */
    val cardColor: String = "black",
    val metadataSizePercent: Int = 100,
    val rubyVisible: Boolean = true,
    val secondaryTextBright: Boolean = true,
    val lyricLineLimit: Int = DEFAULT_LYRIC_LINE_LIMIT,
    /** Show the upcoming next lyric line dimmed below the active line. */
    val showNextLine: Boolean = false,
    /** 辅助文字显示第二行歌词,见 [SurfaceProfile.secondaryNextLine]。 */
    val secondaryNextLine: Boolean = false,
    /** 歌曲信息对齐,见 [SurfaceProfile.metadataAlignment]。 */
    val metadataAlignment: String = "auto",
    /** 第二行歌词对齐,见 [SurfaceProfile.nextLineAlignment]。 */
    val nextLineAlignment: String = "auto"
)

const val CURRENT_CUSTOMIZATION_VERSION = 1
const val DEFAULT_LYRIC_LINE_LIMIT = 3
const val NO_LYRIC_LINE_LIMIT = 0

/** 锁屏歌词卡片背景色可选 token。 */
val CARD_COLOR_VALUES = setOf("black", "dark_gray", "white", "accent", "blur")
const val DEFAULT_CARD_ALPHA = 85
const val DEFAULT_CARD_COLOR = "black"

internal fun normalizeCardAlpha(value: Int): Int = value.coerceIn(0, 100)

internal fun normalizeCardColor(value: String): String =
    value.takeIf { it in CARD_COLOR_VALUES } ?: DEFAULT_CARD_COLOR

internal fun normalizeLyricLineLimit(value: Int): Int = when (value) {
    NO_LYRIC_LINE_LIMIT,
    in 1..5 -> value
    else -> DEFAULT_LYRIC_LINE_LIMIT
}

/** 换行动画的「跟随音源」哨兵值:不覆盖歌词源自带的过渡偏好。 */
const val LINE_TRANSITION_AUTO = "Auto"

/** 画布换行动画词表(与 AodStateWire.transitionMode 同词表);"Auto" 仅存在于 profile 层。 */
val LINE_TRANSITION_MODES = listOf(
    LINE_TRANSITION_AUTO,
    "Fade up",
    "Crossfade",
    "Slide up",
    "Slide left",
    "Zoom",
    "None"
)

internal fun normalizeLineTransition(value: String): String =
    value.takeIf { it in LINE_TRANSITION_MODES } ?: LINE_TRANSITION_AUTO

/**
 * 换行动画解析:profile 显式选择优先于歌词源偏好(设置即所得),`"Auto"` 沿用源值。
 * 源值来自投影层归一化后的画布词表(wire 出口 [com.eza.hyperglow.aod.normalizeAodTransition]
 * 已保证规范形),此处只做选择,不再二次归一。
 */
internal fun resolveLineTransition(profileValue: String?, sourceValue: String): String = when {
    profileValue == null || profileValue == LINE_TRANSITION_AUTO -> sourceValue
    else -> profileValue
}

// --- 歌曲信息切片:显示部分与分隔符 ---

/** 歌曲信息切片部分 token。 */
const val METADATA_PART_TITLE = "title"
const val METADATA_PART_ARTIST = "artist"
const val METADATA_PART_ALBUM = "album"

/** 部分的规范顺序(显示顺序恒为该顺序,与选择顺序无关)。 */
val METADATA_PARTS = listOf(METADATA_PART_TITLE, METADATA_PART_ARTIST, METADATA_PART_ALBUM)

const val METADATA_PARTS_DEFAULT = "title,artist"

/** 每个切片独立成行(历史默认行为)。 */
const val METADATA_SEPARATOR_NEWLINE = "newline"

/** 歌曲信息分隔符 token 词表;"newline" 为换行,其余为行内分隔符(见 [metadataSeparatorText])。 */
val METADATA_SEPARATORS = listOf(
    METADATA_SEPARATOR_NEWLINE,
    "dot",
    "hyphen",
    "pipe",
    "dunhao",
    "slash"
)

/** 分隔符 token 对应的连接文本;"newline" 产出换行符,由画布按行切片。 */
internal fun metadataSeparatorText(value: String): String = when (value) {
    "dot" -> " · "
    "hyphen" -> " - "
    "pipe" -> " | "
    "dunhao" -> "、"
    "slash" -> " / "
    else -> "\n"
}

internal fun normalizeMetadataParts(value: String?): String {
    val requested = value?.split(',')?.map { it.trim() }?.toSet().orEmpty()
    val parts = METADATA_PARTS.filter { it in requested }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(",") ?: METADATA_PARTS_DEFAULT
}

internal fun normalizeMetadataSeparator(value: String?): String =
    value?.takeIf { it in METADATA_SEPARATORS } ?: METADATA_SEPARATOR_NEWLINE

/**
 * 歌曲信息组装:按 [parts](歌名/歌手/专辑,恒按 [METADATA_PARTS] 规范顺序)取切片,
 * 以 [separator] 连接。每个部分文本内的 `·` 仍视作切片边界(历史行为:部分音源把
 * 「歌名·歌手」塞进单字段);切片两端空白裁剪,空切片丢弃。
 * separator 为 [METADATA_SEPARATOR_NEWLINE] 时切片各占一行,否则全部内联到一行。
 */
internal fun composeSongMetadata(
    title: String,
    artist: String,
    album: String,
    parts: String,
    separator: String
): String {
    val source = mapOf(
        METADATA_PART_TITLE to title,
        METADATA_PART_ARTIST to artist,
        METADATA_PART_ALBUM to album
    )
    val slices = normalizeMetadataParts(parts).split(',')
        .flatMap { source.getValue(it).split('·') }
        .map { it.trim() }
        .filter { it.isNotBlank() }
    return slices.joinToString(metadataSeparatorText(normalizeMetadataSeparator(separator)))
}

/**
 * 组装后歌曲信息相对「两行」静态预算的额外行数:换行分隔符下选满 3 部分需要 3 行;
 * 行内分隔符按单行预算(超宽自动折行属于渲染期行为,不计入静态预算)。
 */
internal fun metadataExpectedExtraLines(parts: String, separator: String): Int {
    if (normalizeMetadataSeparator(separator) != METADATA_SEPARATOR_NEWLINE) return 0
    return (normalizeMetadataParts(parts).split(',').size - 2).coerceAtLeast(0)
}
