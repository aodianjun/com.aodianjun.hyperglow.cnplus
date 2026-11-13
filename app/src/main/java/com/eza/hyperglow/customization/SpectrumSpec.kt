package com.eza.hyperglow.customization

/** 频谱样式白名单；未知值编译期回退到 bars。 */
val SPECTRUM_STYLES = setOf("bars", "mirror", "line")

/** 频谱数据源：默认合成律动（不监听）；loopback=SystemUI 特权回环；capture=App 侧 MediaProjection。 */
val SPECTRUM_SOURCES = setOf("rhythm", "loopback", "capture")

/** 频谱配色 token 白名单。 */
val SPECTRUM_COLORS = setOf("accent", "primary", "gradient")

/**
 * AOD/锁屏画布底部的音乐频谱条配置。
 *
 * heightPercent 以 34dp 为基准；sensitivity 是渲染端增益（100 = 1.0x）。这些参数随
 * CompiledCustomization 下发给 SystemUI，运行时只消费不可变编译结果。
 */
@kotlinx.serialization.Serializable
data class SpectrumSpec(
    val enabled: Boolean = false,
    val style: String = "bars",
    val barCount: Int = 24,
    val heightPercent: Int = 100,
    val sensitivity: Int = 100,
    val color: String = "accent",
    /** rhythm=只看播放态的合成律动（默认，不监听）；loopback=SystemUI AudioPolicy 回环；capture=App 侧 MediaProjection。 */
    val source: String = "rhythm",
    /** true=歌词框内透明背景层；false=歌词块下方独立条带。 */
    val background: Boolean = true,
    /** 背景层/频谱条不透明度，10..100。 */
    val opacity: Int = 40,
    /** 频谱垂直位置偏移（相对歌词框高度，-50..50；正数向下）。 */
    val yOffsetPercent: Int = 0,
    /** 独立频谱颜色（#RGB/#RRGGBB/#AARRGGBB）；空=跟随上面的配色预设。 */
    val customColor: String = ""
)

fun normalizeSpectrumCustomColor(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.takeIf {
        Regex("^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$").matches(it)
    } ?: ""
}

fun normalizeSpectrum(value: SpectrumSpec): SpectrumSpec {
    // 关闭时收敛到默认值：编译配置因此省略 spectrum 字段，旧 SystemUI（无此字段）的
    // JSON 往返哈希仍能对上；否则每次配置更新都会被旧 hook 整份拒绝，自定义样式回默认。
    if (!value.enabled) return SpectrumSpec()
    return SpectrumSpec(
        enabled = true,
        style = value.style.takeIf { it in SPECTRUM_STYLES } ?: "bars",
        barCount = value.barCount.coerceIn(MIN_SPECTRUM_BARS, MAX_SPECTRUM_BARS),
        heightPercent = value.heightPercent.coerceIn(50, 200),
        sensitivity = value.sensitivity.coerceIn(50, 200),
        color = value.color.takeIf { it in SPECTRUM_COLORS } ?: "accent",
        source = value.source.takeIf { it in SPECTRUM_SOURCES } ?: "rhythm",
        background = value.background,
        opacity = value.opacity.coerceIn(10, 100),
        yOffsetPercent = value.yOffsetPercent.coerceIn(-50, 50),
        customColor = normalizeSpectrumCustomColor(value.customColor)
    )
}

/** 频谱条占位高度（dp），与画布内预留高度、placement 测量共享同一公式。 */
fun spectrumWidgetHeightDp(heightPercent: Int): Float =
    SPECTRUM_BASE_HEIGHT_DP * heightPercent.coerceIn(50, 200) / 100f

const val SPECTRUM_BASE_HEIGHT_DP = 34f
const val MIN_SPECTRUM_BARS = 12
const val MAX_SPECTRUM_BARS = 32
