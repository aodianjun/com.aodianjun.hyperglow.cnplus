package com.eza.hyperglow.customization

import kotlinx.serialization.Serializable

@Serializable
data class CustomizationDocument(
    val version: Int = CURRENT_CUSTOMIZATION_VERSION,
    val id: String = "default_continuity",
    val name: String = "Seamless Default",
    val linkSurfaces: Boolean = false,
    /**
     * 歌曲信息显示部分(歌名/歌手/专辑),见 [METADATA_PARTS] 与 [normalizeMetadataParts];
     * 顺序即显示顺序(可自定义排序),全局生效,同时作用于息屏与锁屏。
     */
    val metadataParts: String = METADATA_PARTS_DEFAULT,
    /**
     * 相邻两个显示部分之间的分隔符 token 列表(逗号分隔,第 i 项为第 i 与第 i+1 部分之间的
     * 分隔符,逐槽独立选择),见 [METADATA_SEPARATORS];全局生效,同时作用于息屏与锁屏。
     */
    val metadataSeparators: String = METADATA_SEPARATORS_DEFAULT,
    /**
     * 旧版单一分隔符 token;仅作旧文档迁移载体:读取时按槽位展开进 [metadataSeparators] 并清空,
     * 回写文档时不再携带(保证只播种一次)。
     */
    val metadataSeparator: String? = null,
    /**
     * 专辑名与歌名一致时隐藏专辑切片:开启后,当专辑名(裁剪两端空白后)与歌名完全相同,
     * 组装歌曲信息时丢弃专辑部分(等同该部分为空,分隔符随之折叠),避免重复显示。
     * 全局生效(内容级解释,同时作用于息屏与锁屏)。
     */
    val hideAlbumWhenSameAsTitle: Boolean = false,
    /**
     * 识别对唱标记:行首「（男）/（女）/（合）」演唱者标记被识别为演唱者身份——显示时
     * 隐去标记文本,并作为对唱左右分侧的身份输入(元数据身份恒优先,
     * 见 [com.eza.hyperglow.producer.resolveDuetAlignment]);「（副歌）/（间奏）」等段落标记
     * 同样隐去但不作身份、不参与分侧;关闭则原样显示、标记不参与分侧。
     * 全局生效(内容级解释,同时作用于息屏与锁屏)。
     */
    val duetMarkers: Boolean = true,
    /**
     * 旧版歌曲图片显示开关(文档级全局)。歌曲图片自本版起为 per-surface 设置,由
     * [SurfaceProfile.artworkVisible]/[SurfaceProfile.artworkShape]/[SurfaceProfile.artworkSpin]
     * 分别承载(锁屏与息屏各自独立);本字段仅作旧文档迁移载体:读取时一次性播种到两个曲面,
     * 回写文档时清空(见 CustomizationRepository.migrateDocument)。
     */
    val artworkVisible: Boolean? = null,
    /** 旧版歌曲图片形状 token,见 [ARTWORK_SHAPES];仅作旧文档迁移载体,见 [artworkVisible]。 */
    val artworkShape: String? = null,
    /** 旧版圆形歌曲图片旋转开关;仅作旧文档迁移载体,见 [artworkVisible]。 */
    val artworkSpin: Boolean? = null,
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
    /**
     * 显示第二行辅助文字:在第二行歌词行之后,再按 [secondaryMode] 追加该行自己的辅助
     * 文字行(音标/翻译,有内容才显示)——四行呈现:第一行歌词、第一行辅助文字、
     * 第二行歌词、第二行辅助文字。开启时第二行歌词同样按辅助文字形态呈现
     * (即使 [secondaryNextLine] 关闭;该开关以「显示第二行」为前提)。
     */
    val nextLineAux: Boolean = false,
    /** 歌曲图片显示开关:歌曲信息左侧显示系统播放窗口的专辑图(经包名/曲目校对,见 SongArtworkRepository);每个 surface 独立设置,锁屏与息屏互不联动。 */
    val artworkVisible: Boolean = false,
    /** 歌曲图片形状 token,见 [ARTWORK_SHAPES];每个 surface 独立设置。 */
    val artworkShape: String = ARTWORK_SHAPE_SQUARE,
    /** 圆形歌曲图片是否旋转;仅 [artworkShape] 为 [ARTWORK_SHAPE_CIRCLE] 时生效(设置界面同样只在圆形下露出),每个 surface 独立设置。 */
    val artworkSpin: Boolean = false,
    /** 音乐暂停驻留期间圆形封面是否继续旋转;仅 [artworkSpin] 开启时有意义。默认关(暂停即停转,驻留期无逐帧开销)。 */
    val artworkSpinWhenPaused: Boolean = false,
    /**
     * 歌曲图片自适应缩放:开启(默认)时边长随歌曲信息字号等比缩放(字号 × 1.6,历史行为);
     * 关闭时用固定自定义边长 [artworkSizeDp],不随字号变化。每个 surface 独立设置。
     */
    val artworkAdaptiveScale: Boolean = true,
    /** 自定义歌曲图片边长(dp);仅 [artworkAdaptiveScale] 关闭时生效,见 [normalizeArtworkSizeDp]。 */
    val artworkSizeDp: Int = ARTWORK_SIZE_DEFAULT_DP,
    /**
     * 对唱分侧:开启时按行级 `alignedRight`(歌词源显式值,或由演唱者身份元数据推导,
     * 见 [com.eza.hyperglow.producer.resolveDuetAlignment])把该行画到左/右一侧;
     * 关闭时忽略分侧、全部行按 [alignment] 解析。主对齐为显式 start/center/end 时本开关无效果。
     */
    val duetAlignment: Boolean = true,
    /**
     * 显示并发歌词(对唱):主行播放窗口与另一唱词行重叠 ≥1s 时,该行作为并发行在主行
     * 下方同时显示(纯时间轴重叠判定,见 [com.eza.hyperglow.producer.selectDuetLineIndex];
     * 移植上游 amarinne/hyperglow 99ba119d4 的 duet/secondLine 特性)。仅息屏面生效,
     * 锁屏恒 solo;关闭后快照不携带并发行,呈现与关闭前 solo 行为逐字一致。默认开(上游同值)。
     */
    val duetConcurrent: Boolean = true,
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
    /** 换行动画速率,见 [LINE_TRANSITION_SPEEDS];等比缩放退场/入场时长,不改帧配方。 */
    val lineTransitionSpeed: String = LINE_TRANSITION_SPEED_NORMAL,
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
    /** 歌曲信息显示部分(歌名/歌手/专辑,顺序即显示顺序);全局生效,由 [CustomizationDocument.metadataParts] 编译而来。 */
    val metadataParts: String = METADATA_PARTS_DEFAULT,
    /** 歌曲信息逐槽分隔符 token 列表;全局生效,由 [CustomizationDocument.metadataSeparators] 编译而来。 */
    val metadataSeparators: String = METADATA_SEPARATORS_DEFAULT,
    /** 专辑名与歌名一致时隐藏专辑切片;全局生效,由 [CustomizationDocument.hideAlbumWhenSameAsTitle] 编译而来。 */
    val hideAlbumWhenSameAsTitle: Boolean = false,
    /** 识别对唱标记;全局生效,由 [CustomizationDocument.duetMarkers] 编译而来。 */
    val duetMarkers: Boolean = true,
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
    /** 换行动画速率,见 [LINE_TRANSITION_SPEEDS];由 [SurfaceProfile.lineTransitionSpeed] 编译而来。 */
    val lineTransitionSpeed: String = LINE_TRANSITION_SPEED_NORMAL,
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
    val nextLineAlignment: String = "auto",
    /** 显示第二行辅助文字,见 [SurfaceProfile.nextLineAux]。 */
    val nextLineAux: Boolean = false,
    /** 歌曲图片显示开关,见 [SurfaceProfile.artworkVisible];每个 surface 独立承载。 */
    val artworkVisible: Boolean = false,
    /** 歌曲图片形状 token,见 [ARTWORK_SHAPES];由 [SurfaceProfile.artworkShape] 编译而来。 */
    val artworkShape: String = ARTWORK_SHAPE_SQUARE,
    /** 圆形歌曲图片旋转开关;仅圆形生效,由 [SurfaceProfile.artworkSpin] 编译而来。 */
    val artworkSpin: Boolean = false,
    /** 暂停驻留期间是否继续旋转,由 [SurfaceProfile.artworkSpinWhenPaused] 编译而来。 */
    val artworkSpinWhenPaused: Boolean = false,
    /** 歌曲图片自适应缩放,由 [SurfaceProfile.artworkAdaptiveScale] 编译而来。 */
    val artworkAdaptiveScale: Boolean = true,
    /** 自定义歌曲图片边长(dp),由 [SurfaceProfile.artworkSizeDp] 编译而来。 */
    val artworkSizeDp: Int = ARTWORK_SIZE_DEFAULT_DP,
    /** 对唱分侧,见 [SurfaceProfile.duetAlignment]。 */
    val duetAlignment: Boolean = true,
    /** 对唱并发行开关,见 [SurfaceProfile.duetConcurrent];仅息屏面消费,锁屏编译进档但不渲染。 */
    val duetConcurrent: Boolean = true
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

/**
 * 画布换行动画词表(profile 可选集);"Auto" 仅存在于 profile 层。
 * AodStateWire.transitionMode 只会产出历史档,未知值 fail-safe `Fade up`,不受新增档影响。
 * 小写 snake_case 档为 HyperLyric(limczhh/HyperLyric)「歌词切换动画」预设 id 原名复刻
 * (25 个,退场→换字→进场序列,配方见 root.aod LINE_TRANSITION_PRESETS);历史档保持不变。
 */
val LINE_TRANSITION_MODES = listOf(
    LINE_TRANSITION_AUTO,
    "Fade up",
    "Crossfade",
    "Slide up",
    "Slide left",
    "Zoom",
    "fade_out_fade_in",
    "fade_out_up_fade_in_up",
    "fade_out_down_fade_in_down",
    "fade_out_left_fade_in_right",
    "fade_out_left_fade_in_up",
    "fade_out_left_zoom_in",
    "fade_out_left_landing",
    "fade_out_right_fade_in_left",
    "fade_out_right_fade_in_up",
    "fade_out_right_zoom_in",
    "fade_out_right_landing",
    "fade_out_left_zoom_in_right",
    "fade_out_right_zoom_in_left",
    "slide_out_left_slide_in_right",
    "slide_out_left_fade_in_up",
    "slide_out_left_zoom_in",
    "slide_out_left_landing",
    "slide_out_right_slide_in_left",
    "slide_out_right_fade_in_up",
    "slide_out_right_zoom_in",
    "slide_out_right_landing",
    "flip_out_x_flip_in_x",
    "flip_out_y_flip_in_y",
    "rotate_out_rotate_in",
    "zoom_out_zoom_in",
    "None"
)

/**
 * #95 短名档的规范别名:与对应 HyperLyric 预设同配方,归一到预设 id 避免词表重复;
 * 只在 profile 层归一,wire 词表不受影响。
 */
private val LINE_TRANSITION_ALIASES = mapOf(
    "Fade left" to "fade_out_left_fade_in_right",
    "Landing" to "fade_out_left_landing",
    "Slide swap" to "slide_out_left_slide_in_right"
)

internal fun normalizeLineTransition(value: String): String {
    val canonical = LINE_TRANSITION_ALIASES[value] ?: value
    return canonical.takeIf { it in LINE_TRANSITION_MODES } ?: LINE_TRANSITION_AUTO
}

/** 换行动画速率档默认值:保持基准时长(退场 130ms / 入场 210ms)。 */
const val LINE_TRANSITION_SPEED_NORMAL = "Normal"

/**
 * 换行动画速率词表:Slow / Normal / Fast。只等比缩放退场/入场时长
 * (见 root.aod lineTransitionDurationScale),不动帧配方与缓动曲线;
 * "None" 换行动画下无动画,速率无从生效。无"跟随源"语义——速率是纯视觉偏好。
 */
val LINE_TRANSITION_SPEEDS = listOf(
    LINE_TRANSITION_SPEED_NORMAL,
    "Slow",
    "Fast"
)

internal fun normalizeLineTransitionSpeed(value: String): String =
    value.takeIf { it in LINE_TRANSITION_SPEEDS } ?: LINE_TRANSITION_SPEED_NORMAL

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

/** 部分 token 的规范词表(用于白名单校验与补全;显示顺序由用户选择决定,见 [normalizeMetadataParts])。 */
val METADATA_PARTS = listOf(METADATA_PART_TITLE, METADATA_PART_ARTIST, METADATA_PART_ALBUM)

const val METADATA_PARTS_DEFAULT = "title,artist"

/** 每个切片独立成行(历史默认行为)。 */
const val METADATA_SEPARATOR_NEWLINE = "newline"

/** 默认逐槽分隔符序列(默认两部分之间一个换行)。 */
const val METADATA_SEPARATORS_DEFAULT = METADATA_SEPARATOR_NEWLINE

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

/** 归一化:按用户给定顺序保留、去重、丢弃未知 token;全空回落默认。 */
internal fun normalizeMetadataParts(value: String?): String {
    val requested = value?.split(',')
        ?.map { it.trim() }
        ?.filter { it in METADATA_PARTS }
        ?.distinct()
        .orEmpty()
    return requested.takeIf { it.isNotEmpty() }?.joinToString(",") ?: METADATA_PARTS_DEFAULT
}

internal fun normalizeMetadataSeparator(value: String?): String =
    value?.takeIf { it in METADATA_SEPARATORS } ?: METADATA_SEPARATOR_NEWLINE

/** 已选部分数量 - 1,即部分之间可独立选择的分隔符槽位数。 */
internal fun metadataGapCount(parts: String): Int =
    (normalizeMetadataParts(parts).split(',').size - 1).coerceAtLeast(0)

/**
 * 分隔符序列归一化:槽位数由 [parts] 推出,逐槽取对应 token,缺项/非法项回落换行;
 * 序列过长截断、过短补换行,保证长度恒等于槽位数。单部分(0 槽)返回空串。
 */
internal fun normalizeMetadataSeparators(value: String?, parts: String): String {
    val gaps = metadataGapCount(parts)
    if (gaps <= 0) return ""
    val requested = value?.split(',')?.map { it.trim() }.orEmpty()
    return (0 until gaps).joinToString(",") { index ->
        normalizeMetadataSeparator(requested.getOrNull(index))
    }
}

/**
 * 歌曲信息组装:按 [parts](歌名/歌手/专辑,顺序即显示顺序)取切片,相邻部分之间用
 * [separators] 对应槽位的分隔符连接,逐槽独立(槽位不足回落换行)。每个部分文本内的 `·`
 * 仍视作切片边界(历史行为:部分音源把「歌名·歌手」塞进单字段),同一切片内部与部分之间
 * 共用该部分之后的槽位分隔符;最后一个部分之后无槽位,回落换行。切片两端空白裁剪,空切片丢弃。
 *
 * [hideAlbumWhenSameAsTitle] 开启且专辑名(裁剪两端空白后)与歌名完全相同时,专辑部分按
 * 「无切片」处理(整体丢弃、分隔符随之折叠),用于避免专辑与歌名重复显示。
 */
internal fun composeSongMetadata(
    title: String,
    artist: String,
    album: String,
    parts: String,
    separators: String,
    hideAlbumWhenSameAsTitle: Boolean = false
): String {
    val normalizedAlbum = album.trim()
    val suppressAlbum = hideAlbumWhenSameAsTitle &&
        normalizedAlbum.isNotEmpty() &&
        normalizedAlbum == title.trim()
    val source = mapOf(
        METADATA_PART_TITLE to title,
        METADATA_PART_ARTIST to artist,
        METADATA_PART_ALBUM to album
    )
    val gapTokens = normalizeMetadataSeparators(separators, parts)
        .split(',')
        .filter { it.isNotEmpty() }
    val builder = StringBuilder()
    var pendingSeparator = ""
    var firstPiece = true
    normalizeMetadataParts(parts).split(',').forEachIndexed { partIndex, part ->
        val slices = if (part == METADATA_PART_ALBUM && suppressAlbum) {
            emptyList()
        } else {
            source.getValue(part).split('·')
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
        slices.forEachIndexed { sliceIndex, slice ->
            if (firstPiece) {
                builder.append(slice)
                firstPiece = false
            } else {
                val separator = if (sliceIndex == 0) {
                    pendingSeparator
                } else {
                    metadataSeparatorText(gapTokens.getOrNull(partIndex) ?: METADATA_SEPARATOR_NEWLINE)
                }
                builder.append(separator).append(slice)
            }
        }
        if (slices.isNotEmpty()) {
            pendingSeparator =
                metadataSeparatorText(gapTokens.getOrNull(partIndex) ?: METADATA_SEPARATOR_NEWLINE)
        }
    }
    return builder.toString()
}

/**
 * 组装后歌曲信息相对「两行」静态预算的额外行数:换行槽位超过 1 个时才超出两行预算;
 * 行内分隔符按单行预算(超宽自动折行属于渲染期行为,不计入静态预算)。
 */
internal fun metadataExpectedExtraLines(parts: String, separators: String): Int {
    val newlineGaps = normalizeMetadataSeparators(separators, parts)
        .split(',')
        .count { it == METADATA_SEPARATOR_NEWLINE }
    return (newlineGaps - 1).coerceAtLeast(0)
}

// --- 歌曲图片:形状与旋转 ---

/** 方形歌曲图片(默认):直角矩形裁切,不旋转。 */
const val ARTWORK_SHAPE_SQUARE = "square"

/** 圆形歌曲图片:圆形裁切,可选旋转(见 [SurfaceProfile.artworkSpin])。 */
const val ARTWORK_SHAPE_CIRCLE = "circle"

/** 歌曲图片形状 token 词表。 */
val ARTWORK_SHAPES = listOf(ARTWORK_SHAPE_SQUARE, ARTWORK_SHAPE_CIRCLE)

/** 自定义歌曲图片边长下限(dp)。 */
const val ARTWORK_SIZE_MIN_DP = 12

/** 自定义歌曲图片边长上限(dp)。 */
const val ARTWORK_SIZE_MAX_DP = 96

/** 自定义歌曲图片默认边长(dp):与 100% 歌曲信息字号下的自适应边长一致。 */
const val ARTWORK_SIZE_DEFAULT_DP = 22

internal fun normalizeArtworkSizeDp(value: Int): Int =
    value.coerceIn(ARTWORK_SIZE_MIN_DP, ARTWORK_SIZE_MAX_DP)

internal fun normalizeArtworkShape(value: String?): String =
    value?.takeIf { it in ARTWORK_SHAPES } ?: ARTWORK_SHAPE_SQUARE

/**
 * 旋转开关的生效值:仅圆形可旋转(设置界面也只在圆形下露出旋转开关)。
 * 方形下即使文档里残留 spin=true 也不生效,渲染/预览统一读本函数的返回值。
 */
internal fun effectiveArtworkSpin(shape: String, spin: Boolean): Boolean =
    spin && normalizeArtworkShape(shape) == ARTWORK_SHAPE_CIRCLE

/**
 * 歌曲图片显示配置(渲染/预览的统一入口):由 surface profile 派生(per-surface,
 * 锁屏与息屏各读自己的 profile),渲染侧与 Compose 预览共用,保证所见即所得。
 * [spins] 为旋转生效值(仅圆形可转)。
 */
internal data class ArtworkDisplayConfig(
    val visible: Boolean = false,
    val shape: String = ARTWORK_SHAPE_SQUARE,
    val spin: Boolean = false,
    /** 自适应缩放:true 时边长随歌曲信息字号缩放,false 时取固定 [sizeDp]。 */
    val adaptiveScale: Boolean = true,
    /** 自定义边长(dp);仅 [adaptiveScale] 关闭时生效。 */
    val sizeDp: Int = ARTWORK_SIZE_DEFAULT_DP
) {
    val spins: Boolean
        get() = effectiveArtworkSpin(shape, spin)
}

internal fun artworkDisplayConfig(profile: CompiledSurfaceProfile?): ArtworkDisplayConfig =
    ArtworkDisplayConfig(
        visible = profile?.artworkVisible == true,
        shape = normalizeArtworkShape(profile?.artworkShape),
        spin = profile?.artworkSpin == true,
        adaptiveScale = profile?.artworkAdaptiveScale != false,
        sizeDp = normalizeArtworkSizeDp(profile?.artworkSizeDp ?: ARTWORK_SIZE_DEFAULT_DP)
    )
