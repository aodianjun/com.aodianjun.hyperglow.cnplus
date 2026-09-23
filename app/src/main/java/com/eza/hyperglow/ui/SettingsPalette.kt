package com.eza.hyperglow.ui



private val SEMANTIC_PALETTE_KEYS = setOf(
    "primaryText",
    "secondaryText",
    "metadataText",
    "sungText",
    "unsungText",
    "glow",
    "accent",
    "surfaceScrim"
)

internal fun palettePreset(name: String): Map<String, String> =
    if (name == "dimmed") SEMANTIC_PALETTE_KEYS.associateWith { "dimmed" } else emptyMap()

internal fun palettePresetName(palette: Map<String, String>): String =
    if (palette.isNotEmpty() && palette.values.all { it == "dimmed" }) "dimmed" else "default"

/** 字体颜色选项的可选值:"default"(白) + 一组 AOD 场景友好的亮色 token。 */
internal val FONT_COLOR_CHOICES = listOf(
    "default",
    "#FFD9A0",
    "#A9D9FF",
    "#B8F0C9",
    "#FFC9DE",
    "#FFF3A8",
    "#D9C9FF"
)

/** 字体颜色作用于主文字/已唱/未唱/光晕四个语义键;选 default 时清除这些键恢复白。 */
private val FONT_COLOR_KEYS = listOf("primaryText", "sungText", "unsungText", "glow")

internal fun applyFontColor(palette: Map<String, String>, value: String): Map<String, String> =
    applyPaletteColor(palette, FONT_COLOR_KEYS, value)

internal fun fontColorPresetName(palette: Map<String, String>): String =
    paletteColorPresetName(palette, "primaryText")

/** 歌曲信息颜色只作用于 metadataText 键。 */
internal fun applyMetadataColor(palette: Map<String, String>, value: String): Map<String, String> =
    applyPaletteColor(palette, listOf("metadataText"), value)

internal fun metadataColorPresetName(palette: Map<String, String>): String =
    paletteColorPresetName(palette, "metadataText")

/** 下一行歌词颜色只作用于 nextLineText 键。 */
internal fun applyNextLineColor(palette: Map<String, String>, value: String): Map<String, String> =
    applyPaletteColor(palette, listOf("nextLineText"), value)

internal fun nextLineColorPresetName(palette: Map<String, String>): String =
    paletteColorPresetName(palette, "nextLineText")

private fun applyPaletteColor(
    palette: Map<String, String>,
    keys: List<String>,
    value: String
): Map<String, String> = if (value == "default") {
    palette.filterKeys { it !in keys }
} else {
    palette + keys.associateWith { value }
}

private fun paletteColorPresetName(palette: Map<String, String>, key: String): String =
    palette[key]?.takeIf { it.startsWith("#") } ?: "default"
