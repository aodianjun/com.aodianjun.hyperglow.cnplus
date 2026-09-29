package com.eza.hyperglow.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.eza.hyperglow.R
import java.io.File
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 应用自身界面(设置 UI)的外观状态:主题、背景图片(变暗/模糊)、系统栏图标。不参与 hook 端渲染配置。 */
internal data class AppUiAppearance(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val themeColorMode: AppThemeColorMode = AppThemeColorMode.DEFAULT,
    val themeColorArgb: Int = DEFAULT_THEME_COLOR_ARGB,
    val hasBackgroundImage: Boolean = false,
    val backgroundImageMtime: Long = 0L,
    val backgroundDimPercent: Int = DEFAULT_BACKGROUND_DIM_PERCENT,
    val backgroundBlurPercent: Int = DEFAULT_BACKGROUND_BLUR_PERCENT,
    val systemBarIcons: AppSystemBarIcons = AppSystemBarIcons.AUTO
)

internal enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

internal enum class AppThemeColorMode {
    /** 使用 miuix 默认配色。 */
    DEFAULT,

    /** 取系统/壁纸动态配色。 */
    DYNAMIC,

    /** 以自定义种子色生成整套配色。 */
    CUSTOM
}

internal enum class AppSystemBarIcons {
    AUTO,
    LIGHT,
    DARK
}

internal const val DEFAULT_THEME_COLOR_ARGB = 0xFF3482FF.toInt()
internal const val DEFAULT_BACKGROUND_DIM_PERCENT = 45
internal const val DEFAULT_BACKGROUND_BLUR_PERCENT = 0

/** 背景模糊 100% 对应的渲染半径;百分比线性折算,设置页与背景层共用同一映射。 */
internal const val MAX_BACKGROUND_BLUR_DP = 25f

private const val APP_UI_PREFS = "app_ui_appearance"

// 键名同时是配置备份的 appUiAppearance 段 schema(ConfigBackupCodec 读写),故为 internal。
internal const val KEY_THEME_MODE = "theme_mode"
internal const val KEY_THEME_COLOR_MODE = "theme_color_mode"
internal const val KEY_THEME_COLOR_ARGB = "theme_color_argb"
internal const val KEY_HAS_BACKGROUND_IMAGE = "has_background_image"
internal const val KEY_BACKGROUND_IMAGE_MTIME = "background_image_mtime"
internal const val KEY_BACKGROUND_DIM_PERCENT = "background_dim_percent"
internal const val KEY_BACKGROUND_BLUR_PERCENT = "background_blur_percent"
internal const val KEY_SYSTEM_BAR_ICONS = "system_bar_icons"

internal const val APP_BACKGROUND_IMAGE_FILE = "app_background.jpg"
private const val MAX_BACKGROUND_IMAGE_SIDE = 2160

/** 背景图片是否生效:各屏 Scaffold 据此改透明,让图片透出。 */
internal val LocalAppBackgroundActive = staticCompositionLocalOf { false }

// 背景图片生效时的玻璃化透明度:顶栏最透、卡片轻透、底部悬浮导航最实——
// 三档让壁纸透出的同时拉开层次(导航与卡片明度/实度差异即区分度来源)。
internal const val APP_GLASS_TOP_BAR_ALPHA = 0.7f
internal const val APP_GLASS_CARD_ALPHA = 0.82f
internal const val APP_GLASS_NAV_ALPHA = 0.93f

/** 背景图片生效时把派生表面色玻璃化(叠加指定档透明度),否则原样返回(与 miuix 默认一致)。 */
@Composable
internal fun appGlassSurface(color: Color, alpha: Float = APP_GLASS_CARD_ALPHA): Color =
    if (LocalAppBackgroundActive.current) {
        color.copy(alpha = alpha)
    } else {
        color
    }

/** 设置卡片容器色;未启用背景时与 miuix CardDefaults 默认(surfaceContainer 实色)一致。 */
@Composable
internal fun appCardContainerColor(): Color =
    appGlassSurface(MiuixTheme.colorScheme.surfaceContainer)

/** 顶栏容器色(miuix TopAppBar 默认实色 surface,背景生效时轻透,不再是一整块黑)。 */
@Composable
internal fun appTopBarColor(): Color =
    appGlassSurface(MiuixTheme.colorScheme.surface, APP_GLASS_TOP_BAR_ALPHA)

/** 底部悬浮导航容器色:比卡片更实一档并抬高明度档位,保证壁纸上的区分度;未启用背景时用 miuix 默认。 */
@Composable
internal fun appNavBarColor(): Color =
    if (LocalAppBackgroundActive.current) {
        MiuixTheme.colorScheme.surfaceContainerHighest.copy(alpha = APP_GLASS_NAV_ALPHA)
    } else {
        MiuixTheme.colorScheme.surfaceContainer
    }

/** 背景图片生效时容器透明,否则维持 miuix 默认 surface 色。 */
@Composable
internal fun appSurfaceColor(): Color =
    if (LocalAppBackgroundActive.current) {
        Color.Transparent
    } else {
        top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.surface
    }

/** 从裸键值表解析外观状态;未知值一律回落默认(fail-closed)。 */
internal fun normalizeAppUiAppearance(values: Map<String, Any?>): AppUiAppearance {
    val themeMode = AppThemeMode.entries.firstOrNull { it.name == values[KEY_THEME_MODE] }
        ?: AppThemeMode.SYSTEM
    val themeColorMode = AppThemeColorMode.entries
        .firstOrNull { it.name == values[KEY_THEME_COLOR_MODE] } ?: AppThemeColorMode.DEFAULT
    val themeColorArgb = (values[KEY_THEME_COLOR_ARGB] as? Int) ?: DEFAULT_THEME_COLOR_ARGB
    val hasBackgroundImage = values[KEY_HAS_BACKGROUND_IMAGE] == true
    val backgroundImageMtime = (values[KEY_BACKGROUND_IMAGE_MTIME] as? Long) ?: 0L
    val backgroundDimPercent = ((values[KEY_BACKGROUND_DIM_PERCENT] as? Int)
        ?: DEFAULT_BACKGROUND_DIM_PERCENT).coerceIn(0, 100)
    val backgroundBlurPercent = ((values[KEY_BACKGROUND_BLUR_PERCENT] as? Int)
        ?: DEFAULT_BACKGROUND_BLUR_PERCENT).coerceIn(0, 100)
    val systemBarIcons = AppSystemBarIcons.entries.firstOrNull { it.name == values[KEY_SYSTEM_BAR_ICONS] }
        ?: AppSystemBarIcons.AUTO
    return AppUiAppearance(
        themeMode = themeMode,
        themeColorMode = themeColorMode,
        themeColorArgb = themeColorArgb,
        hasBackgroundImage = hasBackgroundImage,
        backgroundImageMtime = backgroundImageMtime,
        backgroundDimPercent = backgroundDimPercent,
        backgroundBlurPercent = backgroundBlurPercent,
        systemBarIcons = systemBarIcons
    )
}

internal fun loadAppUiAppearance(context: android.content.Context): AppUiAppearance =
    normalizeAppUiAppearance(
        context.getSharedPreferences(APP_UI_PREFS, android.content.Context.MODE_PRIVATE).all
    )

internal fun updateAppUiAppearance(
    context: android.content.Context,
    appearance: AppUiAppearance
): Boolean {
    val normalized = normalizeAppUiAppearance(
        mapOf(
            KEY_THEME_MODE to appearance.themeMode.name,
            KEY_THEME_COLOR_MODE to appearance.themeColorMode.name,
            KEY_THEME_COLOR_ARGB to appearance.themeColorArgb,
            KEY_HAS_BACKGROUND_IMAGE to appearance.hasBackgroundImage,
            KEY_BACKGROUND_IMAGE_MTIME to appearance.backgroundImageMtime,
            KEY_BACKGROUND_DIM_PERCENT to appearance.backgroundDimPercent,
            KEY_BACKGROUND_BLUR_PERCENT to appearance.backgroundBlurPercent,
            KEY_SYSTEM_BAR_ICONS to appearance.systemBarIcons.name
        )
    )
    return context.getSharedPreferences(APP_UI_PREFS, android.content.Context.MODE_PRIVATE).edit()
        .putString(KEY_THEME_MODE, normalized.themeMode.name)
        .putString(KEY_THEME_COLOR_MODE, normalized.themeColorMode.name)
        .putInt(KEY_THEME_COLOR_ARGB, normalized.themeColorArgb)
        .putBoolean(KEY_HAS_BACKGROUND_IMAGE, normalized.hasBackgroundImage)
        .putLong(KEY_BACKGROUND_IMAGE_MTIME, normalized.backgroundImageMtime)
        .putInt(KEY_BACKGROUND_DIM_PERCENT, normalized.backgroundDimPercent)
        .putInt(KEY_BACKGROUND_BLUR_PERCENT, normalized.backgroundBlurPercent)
        .putString(KEY_SYSTEM_BAR_ICONS, normalized.systemBarIcons.name)
        .commit()
}

/** 外观状态到 miuix 主题控制器的映射:自定义/动态取色走 Monet 系列,默认配色保持原生浅/深色。 */
internal fun appThemeController(appearance: AppUiAppearance): ThemeController {
    val colorSchemeMode = when (appearance.themeColorMode) {
        AppThemeColorMode.DEFAULT -> when (appearance.themeMode) {
            AppThemeMode.SYSTEM -> ColorSchemeMode.System
            AppThemeMode.LIGHT -> ColorSchemeMode.Light
            AppThemeMode.DARK -> ColorSchemeMode.Dark
        }

        AppThemeColorMode.DYNAMIC,
        AppThemeColorMode.CUSTOM -> when (appearance.themeMode) {
            AppThemeMode.SYSTEM -> ColorSchemeMode.MonetSystem
            AppThemeMode.LIGHT -> ColorSchemeMode.MonetLight
            AppThemeMode.DARK -> ColorSchemeMode.MonetDark
        }
    }
    val keyColor = if (appearance.themeColorMode == AppThemeColorMode.CUSTOM) {
        Color(appearance.themeColorArgb)
    } else {
        null
    }
    return ThemeController(colorSchemeMode = colorSchemeMode, keyColor = keyColor)
}

/** 当前外观下界面是否走深色:主题模式显式指定时覆盖系统深色状态。 */
internal fun isDarkTheme(appearance: AppUiAppearance, systemDark: Boolean): Boolean =
    when (appearance.themeMode) {
        AppThemeMode.SYSTEM -> systemDark
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
    }

/** 按外观设置写入状态栏/导航栏图标深浅;AUTO 跟随当前深色状态。 */
internal fun applySystemBarIcons(
    activity: Activity,
    appearance: AppUiAppearance,
    darkTheme: Boolean
) {
    val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
    val darkIcons = when (appearance.systemBarIcons) {
        AppSystemBarIcons.DARK -> true
        AppSystemBarIcons.LIGHT -> false
        AppSystemBarIcons.AUTO -> !darkTheme
    }
    controller.isAppearanceLightStatusBars = darkIcons
    controller.isAppearanceLightNavigationBars = darkIcons
}

internal fun appBackgroundImageFile(context: android.content.Context): File =
    File(context.filesDir, APP_BACKGROUND_IMAGE_FILE)

/** 背景模糊百分比(0-100)换算成渲染半径;设置页预览与背景层共用同一映射,保证预览即所得。 */
internal fun backgroundBlurRadius(percent: Int): Dp =
    (percent.coerceIn(0, 100) / 100f * MAX_BACKGROUND_BLUR_DP).dp

/**
 * 把所选图片解码后有界缩放(最长边不超过 [MAX_BACKGROUND_IMAGE_SIDE])转存为应用私有 JPEG。
 * 先写临时文件再原子替换,导入失败时保留原有背景不动。
 */
internal fun importAppBackgroundImage(context: android.content.Context, uri: Uri): Boolean {
    val out = appBackgroundImageFile(context)
    val tmp = File(out.parentFile, out.name + ".tmp")
    tmp.delete()
    val decoded = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_BACKGROUND_IMAGE_SIDE) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            context.contentResolver.openInputStream(uri)
                ?.use { BitmapFactory.decodeStream(it, null, options) }
        }
    }.getOrNull()
    if (decoded == null) {
        tmp.delete()
        return false
    }
    val written = runCatching {
        tmp.outputStream().use { stream ->
            decoded.compress(Bitmap.CompressFormat.JPEG, 88, stream)
        }
    }.getOrDefault(false) && tmp.length() > 0
    decoded.recycle()
    if (!written) {
        tmp.delete()
        return false
    }
    out.delete()
    return tmp.renameTo(out)
}

internal fun removeAppBackgroundImage(context: android.content.Context): Boolean {
    val file = appBackgroundImageFile(context)
    return !file.exists() || file.delete()
}

internal fun loadAppBackgroundBitmap(context: android.content.Context): Bitmap? {
    val file = appBackgroundImageFile(context)
    if (!file.isFile) return null
    return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
}

internal fun appThemeModeLabel(context: android.content.Context, mode: AppThemeMode): String =
    context.getString(
        when (mode) {
            AppThemeMode.SYSTEM -> R.string.option_theme_system
            AppThemeMode.LIGHT -> R.string.option_theme_light
            AppThemeMode.DARK -> R.string.option_theme_dark
        }
    )

internal fun appThemeColorModeLabel(
    context: android.content.Context,
    mode: AppThemeColorMode
): String = context.getString(
    when (mode) {
        AppThemeColorMode.DEFAULT -> R.string.option_default
        AppThemeColorMode.DYNAMIC -> R.string.option_theme_color_dynamic
        AppThemeColorMode.CUSTOM -> R.string.option_theme_color_custom
    }
)

internal fun appSystemBarIconsLabel(
    context: android.content.Context,
    icons: AppSystemBarIcons
): String = context.getString(
    when (icons) {
        AppSystemBarIcons.AUTO -> R.string.option_bar_icons_auto
        AppSystemBarIcons.LIGHT -> R.string.option_bar_icons_light
        AppSystemBarIcons.DARK -> R.string.option_bar_icons_dark
    }
)
