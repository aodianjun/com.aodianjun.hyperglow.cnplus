package com.eza.hyperglow.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.theme.defaultTextStyles
import top.yukonga.miuix.kmp.theme.lightColorScheme

class AppUiAppearanceTest {

    @Test
    fun emptyValuesFallBackToDefaults() {
        val appearance = normalizeAppUiAppearance(emptyMap())
        assertEquals(AppThemeMode.SYSTEM, appearance.themeMode)
        assertEquals(AppThemeColorMode.DEFAULT, appearance.themeColorMode)
        assertEquals(DEFAULT_THEME_COLOR_ARGB, appearance.themeColorArgb)
        assertFalse(appearance.hasBackgroundImage)
        assertEquals(0L, appearance.backgroundImageMtime)
        assertEquals(DEFAULT_BACKGROUND_DIM_PERCENT, appearance.backgroundDimPercent)
        assertEquals(DEFAULT_BACKGROUND_BLUR_PERCENT, appearance.backgroundBlurPercent)
        assertEquals(null, appearance.controlColorArgb)
        assertEquals(DEFAULT_CONTROL_OPACITY_PERCENT, appearance.controlOpacityPercent)
        assertEquals(null, appearance.textColorArgb)
        assertEquals(FONT_FAMILY_SYSTEM, appearance.fontFamily)
        assertEquals(AppSystemBarIcons.AUTO, appearance.systemBarIcons)
    }

    @Test
    fun unknownValuesFallBackToDefaults() {
        val appearance = normalizeAppUiAppearance(
            mapOf(
                "theme_mode" to "ALIEN",
                "theme_color_mode" to "ALIEN",
                "theme_color_argb" to "not-an-int",
                "has_background_image" to "yes",
                "background_image_mtime" to "yesterday",
                "background_dim_percent" to "not-an-int",
                "background_blur_percent" to "not-an-int",
                "control_color_argb" to "not-an-int",
                "control_opacity_percent" to "far",
                "text_color_argb" to "red",
                "font_family" to "ALIEN",
                "system_bar_icons" to "ALIEN"
            )
        )
        assertEquals(AppThemeMode.SYSTEM, appearance.themeMode)
        assertEquals(AppThemeColorMode.DEFAULT, appearance.themeColorMode)
        assertEquals(DEFAULT_THEME_COLOR_ARGB, appearance.themeColorArgb)
        assertFalse(appearance.hasBackgroundImage)
        assertEquals(DEFAULT_BACKGROUND_DIM_PERCENT, appearance.backgroundDimPercent)
        assertEquals(DEFAULT_BACKGROUND_BLUR_PERCENT, appearance.backgroundBlurPercent)
        assertEquals(null, appearance.controlColorArgb)
        assertEquals(DEFAULT_CONTROL_OPACITY_PERCENT, appearance.controlOpacityPercent)
        assertEquals(null, appearance.textColorArgb)
        assertEquals(FONT_FAMILY_SYSTEM, appearance.fontFamily)
        assertEquals(AppSystemBarIcons.AUTO, appearance.systemBarIcons)
    }

    @Test
    fun knownValuesAreKeptAndPercentFieldsAreClamped() {
        val appearance = normalizeAppUiAppearance(
            mapOf(
                "theme_mode" to "DARK",
                "theme_color_mode" to "CUSTOM",
                "theme_color_argb" to 0xFF112233.toInt(),
                "has_background_image" to true,
                "background_image_mtime" to 42L,
                "background_dim_percent" to 250,
                "background_blur_percent" to -5,
                "control_color_argb" to 0xFF00FFAA.toInt(),
                "control_opacity_percent" to 250,
                "text_color_argb" to 0xFFFF8800.toInt(),
                "font_family" to "custom:myfont",
                "system_bar_icons" to "LIGHT"
            )
        )
        assertEquals(AppThemeMode.DARK, appearance.themeMode)
        assertEquals(AppThemeColorMode.CUSTOM, appearance.themeColorMode)
        assertEquals(0xFF112233.toInt(), appearance.themeColorArgb)
        assertTrue(appearance.hasBackgroundImage)
        assertEquals(42L, appearance.backgroundImageMtime)
        assertEquals(100, appearance.backgroundDimPercent)
        assertEquals(0, appearance.backgroundBlurPercent)
        assertEquals(0xFF00FFAA.toInt(), appearance.controlColorArgb)
        assertEquals(100, appearance.controlOpacityPercent)
        assertEquals(0xFFFF8800.toInt(), appearance.textColorArgb)
        assertEquals("custom:myfont", appearance.fontFamily)
        assertEquals(AppSystemBarIcons.LIGHT, appearance.systemBarIcons)
    }

    @Test
    fun explicitThemeModeOverridesSystemDarkState() {
        assertFalse(isDarkTheme(appearance(AppThemeMode.LIGHT), systemDark = true))
        assertTrue(isDarkTheme(appearance(AppThemeMode.DARK), systemDark = false))
        assertTrue(isDarkTheme(appearance(AppThemeMode.SYSTEM), systemDark = true))
        assertFalse(isDarkTheme(appearance(AppThemeMode.SYSTEM), systemDark = false))
    }

    @Test
    fun fontFamilyTokenAcceptsCustomContractTokens() {
        assertEquals("custom", normalizeAppUiAppearance(mapOf("font_family" to "custom")).fontFamily)
        assertEquals(
            "custom:abc_1",
            normalizeAppUiAppearance(mapOf("font_family" to "custom:abc_1")).fontFamily
        )
        // 契约侧把 id 归一为小写,接受时回吐规范令牌。
        assertEquals(
            "custom:upper",
            normalizeAppUiAppearance(mapOf("font_family" to "custom:UPPER")).fontFamily
        )
        assertEquals(FONT_FAMILY_SYSTEM, normalizeAppUiAppearance(mapOf("font_family" to 42)).fontFamily)
        assertEquals(FONT_FAMILY_SYSTEM, normalizeAppUiAppearance(mapOf("font_family" to null)).fontFamily)
    }

    @Test
    fun appTextStylesPropagateFontFamilyWithoutTouchingMetrics() {
        val defaults = appMiuixTextStyles(null)
        assertEquals(defaultTextStyles().main, defaults.main)
        val serif = appMiuixTextStyles(FontFamily.Serif)
        assertEquals(FontFamily.Serif, serif.main.fontFamily)
        assertEquals(FontFamily.Serif, serif.body2.fontFamily)
        assertEquals(FontFamily.Serif, serif.footnote1.fontFamily)
        assertEquals(FontFamily.Serif, serif.title4.fontFamily)
        assertEquals(defaults.main.fontSize, serif.main.fontSize)
        assertEquals(defaults.title2.fontSize, serif.title2.fontSize)
        assertEquals(defaults.subtitle.fontWeight, serif.subtitle.fontWeight)
    }

    @Test
    fun appTextColorSchemeOverridesOnlyPrimaryTextTokens() {
        val base = lightColorScheme()
        val custom = Color(0xFF123456)
        val adjusted = appTextColorScheme(base, custom)
        assertEquals(custom, adjusted.onBackground)
        assertEquals(custom, adjusted.onSurface)
        assertEquals(custom, adjusted.onSurfaceContainer)
        // 次级 token 保持主题层级:summary/行尾动作/小节标题/主色/容器色全部原样。
        assertEquals(base.onSurfaceVariantSummary, adjusted.onSurfaceVariantSummary)
        assertEquals(base.onSurfaceVariantActions, adjusted.onSurfaceVariantActions)
        assertEquals(base.onBackgroundVariant, adjusted.onBackgroundVariant)
        assertEquals(base.primary, adjusted.primary)
        assertEquals(base.surfaceContainer, adjusted.surfaceContainer)
    }

    private fun appearance(mode: AppThemeMode): AppUiAppearance =
        normalizeAppUiAppearance(mapOf("theme_mode" to mode.name))
}
