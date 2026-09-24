package com.eza.hyperglow.ui

import android.content.Context
import androidx.annotation.StringRes
import com.eza.hyperglow.R
import com.eza.hyperglow.root.aod.parseOpaqueColorOrNull
import java.util.Locale
import kotlin.math.roundToInt

private const val DEFAULT_TEXT_ARGB = 0xFFFFFFFF.toInt()
private const val DEFAULT_METADATA_ARGB = 0xFFB3B3B3.toInt()
private const val DIMMED_FACTOR = 0.72f

internal const val PALETTE_DEFAULT = "default"
internal const val PALETTE_DIMMED = "dimmed"

/**
 * 歌词渲染层支持的全部语义色键(与 `SceneCompiler` / `SystemUiCustomization` 的
 * `SEMANTIC_COLORS` 白名单一一对应)。每个键都能通过设置页的取色器设为任意颜色,
 * token 缺省(未写入 palette)时回退到 [defaultArgb]。
 */
internal enum class PaletteColor(
    val token: String,
    @param:StringRes val titleRes: Int,
    val defaultArgb: Int
) {
    PRIMARY_TEXT("primaryText", R.string.palette_primary_text, DEFAULT_TEXT_ARGB),
    SECONDARY_TEXT("secondaryText", R.string.palette_secondary_text, DEFAULT_TEXT_ARGB),
    SUNG_TEXT("sungText", R.string.palette_sung_text, DEFAULT_TEXT_ARGB),
    UNSUNG_TEXT("unsungText", R.string.palette_unsung_text, DEFAULT_TEXT_ARGB),
    GLOW("glow", R.string.palette_glow, DEFAULT_TEXT_ARGB),
    METADATA_TEXT("metadataText", R.string.palette_metadata_text, DEFAULT_METADATA_ARGB),
    NEXT_LINE_TEXT("nextLineText", R.string.palette_next_line_text, DEFAULT_TEXT_ARGB),
    ACCENT("accent", R.string.palette_accent, DEFAULT_TEXT_ARGB),
    SURFACE_SCRIM("surfaceScrim", R.string.palette_surface_scrim, DEFAULT_TEXT_ARGB)
}

/** dimmed 预设把全部语义色键统一压暗;其余名称返回空(表示 default)。 */
internal fun palettePreset(name: String): Map<String, String> =
    if (name == PALETTE_DIMMED) {
        PaletteColor.entries.associate { it.token to PALETTE_DIMMED }
    } else {
        emptyMap()
    }

internal fun palettePresetName(palette: Map<String, String>): String =
    if (palette.isNotEmpty() && palette.values.all { it == PALETTE_DIMMED }) {
        PALETTE_DIMMED
    } else {
        PALETTE_DEFAULT
    }

/** 读取某语义色键当前 token;缺省为 [PALETTE_DEFAULT]。 */
internal fun paletteValue(palette: Map<String, String>, key: PaletteColor): String =
    palette[key.token] ?: PALETTE_DEFAULT

/**
 * 写入/清除某语义色键:值为 [PALETTE_DEFAULT] 时删除该键恢复默认,
 * 否则写入任意 token(预设名或 `#RRGGBB` hex)。
 */
internal fun applyPaletteColor(
    palette: Map<String, String>,
    key: PaletteColor,
    value: String
): Map<String, String> =
    if (value == PALETTE_DEFAULT) palette - key.token else palette + (key.token to value)

/** 某键当前生效的不透明 ARGB,供设置页色块预览;dimmed 按 72% 亮度折算。 */
internal fun paletteEffectiveArgb(palette: Map<String, String>, key: PaletteColor): Int {
    val token = palette[key.token]
    if (token == PALETTE_DIMMED) return scaleRgb(key.defaultArgb, DIMMED_FACTOR)
    return parseOpaqueColorOrNull(token) ?: key.defaultArgb
}

private fun scaleRgb(argb: Int, factor: Float): Int {
    val red = (((argb ushr 16) and 0xFF) * factor).roundToInt()
    val green = (((argb ushr 8) and 0xFF) * factor).roundToInt()
    val blue = ((argb and 0xFF) * factor).roundToInt()
    return (0xFF shl 24) or
        (red.coerceIn(0, 255) shl 16) or
        (green.coerceIn(0, 255) shl 8) or
        blue.coerceIn(0, 255)
}

/** 把不透明 ARGB 转成 `#RRGGBB` token,作为 palette 存储值。 */
internal fun argbToColorToken(argb: Int): String =
    String.format(Locale.US, "#%06X", argb and 0xFFFFFF)

private val COLOR_TOKEN_LABELS = mapOf(
    "#FFD9A0" to R.string.option_color_warm_gold,
    "#A9D9FF" to R.string.option_color_ice_blue,
    "#B8F0C9" to R.string.option_color_mint_green,
    "#FFC9DE" to R.string.option_color_sakura_pink,
    "#FFF3A8" to R.string.option_color_butter_yellow,
    "#D9C9FF" to R.string.option_color_lavender
)

/** 颜色 token 的展示文案:default/dimmed/已知预设名,其余直接显示 hex 色值。 */
internal fun colorTokenLabel(context: Context, value: String): String = when (value) {
    PALETTE_DEFAULT -> context.getString(R.string.option_default)
    PALETTE_DIMMED -> context.getString(R.string.option_dimmed)
    else -> COLOR_TOKEN_LABELS[value]?.let(context::getString) ?: value
}