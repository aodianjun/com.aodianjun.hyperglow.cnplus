package com.eza.hyperglow.root.aod

import android.graphics.Color
import kotlin.math.roundToInt

internal data class AodResolvedPalette(
    val primaryText: Int,
    val secondaryText: Int,
    val metadataText: Int,
    val nextLineText: Int,
    val sungText: Int,
    val unsungText: Int,
    val glow: Int,
    val accent: Int
)

internal fun resolveAodPalette(tokens: Map<String, String>): AodResolvedPalette =
    AodResolvedPalette(
        primaryText = resolvePaletteColor(tokens["primaryText"], Color.WHITE),
        secondaryText = resolvePaletteColor(tokens["secondaryText"], Color.WHITE),
        metadataText = resolvePaletteColor(tokens["metadataText"], 0xFFB3B3B3.toInt()),
        nextLineText = resolvePaletteColor(tokens["nextLineText"], Color.WHITE),
        sungText = resolvePaletteColor(tokens["sungText"], Color.WHITE),
        unsungText = resolvePaletteColor(tokens["unsungText"], Color.WHITE),
        glow = resolvePaletteColor(tokens["glow"], Color.WHITE),
        accent = resolvePaletteColor(tokens["accent"], Color.WHITE)
    )

private fun resolvePaletteColor(token: String?, fallback: Int): Int = when {
    token == "dimmed" -> opaqueRgb(
        (((fallback ushr 16) and 0xFF) * 0.72f).roundToInt(),
        (((fallback ushr 8) and 0xFF) * 0.72f).roundToInt(),
        ((fallback and 0xFF) * 0.72f).roundToInt()
    )
    // 自定义字体颜色:"#RRGGBB" 形式的 token(解析失败时回退默认色)
    else -> parseOpaqueColorOrNull(token) ?: fallback
}

/** 解析 "#RGB"/"#RRGGBB"/"#AARRGGBB" 为不透明 ARGB;非色值 token 返回 null(纯函数,可单测)。 */
internal fun parseOpaqueColorOrNull(token: String?): Int? {
    if (token == null || token.length !in intArrayOf(4, 7, 9) || token[0] != '#') return null
    val hex = token.substring(1)
    for (c in hex) {
        if (Character.digit(c, 16) < 0) return null
    }
    return when (hex.length) {
        3 -> {
            val r = Character.digit(hex[0], 16)
            val g = Character.digit(hex[1], 16)
            val b = Character.digit(hex[2], 16)
            opaqueRgb(r * 17, g * 17, b * 17)
        }
        6 -> opaqueRgb(
            hex.substring(0, 2).toInt(16),
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16)
        )
        else -> opaqueRgb(
            hex.substring(2, 4).toInt(16),
            hex.substring(4, 6).toInt(16),
            hex.substring(6, 8).toInt(16)
        )
    }
}

private fun opaqueRgb(red: Int, green: Int, blue: Int): Int =
    (0xFF shl 24) or
        (red.coerceIn(0, 255) shl 16) or
        (green.coerceIn(0, 255) shl 8) or
        blue.coerceIn(0, 255)
