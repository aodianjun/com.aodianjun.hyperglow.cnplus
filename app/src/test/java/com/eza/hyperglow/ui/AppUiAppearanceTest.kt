package com.eza.hyperglow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
                "system_bar_icons" to "ALIEN"
            )
        )
        assertEquals(AppThemeMode.SYSTEM, appearance.themeMode)
        assertEquals(AppThemeColorMode.DEFAULT, appearance.themeColorMode)
        assertEquals(DEFAULT_THEME_COLOR_ARGB, appearance.themeColorArgb)
        assertFalse(appearance.hasBackgroundImage)
        assertEquals(DEFAULT_BACKGROUND_DIM_PERCENT, appearance.backgroundDimPercent)
        assertEquals(AppSystemBarIcons.AUTO, appearance.systemBarIcons)
    }

    @Test
    fun knownValuesAreKeptAndDimIsClamped() {
        val appearance = normalizeAppUiAppearance(
            mapOf(
                "theme_mode" to "DARK",
                "theme_color_mode" to "CUSTOM",
                "theme_color_argb" to 0xFF112233.toInt(),
                "has_background_image" to true,
                "background_image_mtime" to 42L,
                "background_dim_percent" to 250,
                "system_bar_icons" to "LIGHT"
            )
        )
        assertEquals(AppThemeMode.DARK, appearance.themeMode)
        assertEquals(AppThemeColorMode.CUSTOM, appearance.themeColorMode)
        assertEquals(0xFF112233.toInt(), appearance.themeColorArgb)
        assertTrue(appearance.hasBackgroundImage)
        assertEquals(42L, appearance.backgroundImageMtime)
        assertEquals(100, appearance.backgroundDimPercent)
        assertEquals(AppSystemBarIcons.LIGHT, appearance.systemBarIcons)
    }

    @Test
    fun explicitThemeModeOverridesSystemDarkState() {
        assertFalse(isDarkTheme(appearance(AppThemeMode.LIGHT), systemDark = true))
        assertTrue(isDarkTheme(appearance(AppThemeMode.DARK), systemDark = false))
        assertTrue(isDarkTheme(appearance(AppThemeMode.SYSTEM), systemDark = true))
        assertFalse(isDarkTheme(appearance(AppThemeMode.SYSTEM), systemDark = false))
    }

    private fun appearance(mode: AppThemeMode): AppUiAppearance =
        normalizeAppUiAppearance(mapOf("theme_mode" to mode.name))
}
