package com.eza.hyperglow.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.customization.CustomFontContract
import com.eza.hyperglow.R
import java.io.File
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.Colors
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.defaultTextStyles

/** 应用自身界面(设置 UI)的外观状态:主题、背景图片(变暗/模糊)、控件颜色/不透明度、文字颜色/字体、系统栏图标。不参与 hook 端渲染配置。 */
internal data class AppUiAppearance(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val themeColorMode: AppThemeColorMode = AppThemeColorMode.DEFAULT,
    val themeColorArgb: Int = DEFAULT_THEME_COLOR_ARGB,
    val hasBackgroundImage: Boolean = false,
    val backgroundImageMtime: Long = 0L,
    val backgroundDimPercent: Int = DEFAULT_BACKGROUND_DIM_PERCENT,
    val backgroundBlurPercent: Int = DEFAULT_BACKGROUND_BLUR_PERCENT,
    val controlColorArgb: Int? = null,
    val controlOpacityPercent: Int = DEFAULT_CONTROL_OPACITY_PERCENT,
    val textColorArgb: Int? = null,
    val fontFamily: String = FONT_FAMILY_SYSTEM,
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
// 可空键:未设置时不落盘(恢复默认=移除),备份/读取缺键即回落主题默认色。
internal const val KEY_CONTROL_COLOR_ARGB = "control_color_argb"
internal const val KEY_CONTROL_OPACITY_PERCENT = "control_opacity_percent"
// 文字颜色同为可空键;字体令牌恒有值(默认跟随系统)。
internal const val KEY_TEXT_COLOR_ARGB = "text_color_argb"
internal const val KEY_FONT_FAMILY = "font_family"
internal const val KEY_SYSTEM_BAR_ICONS = "system_bar_icons"

/** 控件玻璃化的默认不透明度:100% 即三档原生透明度(顶栏 0.7/卡片 0.82/导航 0.93)。 */
internal const val DEFAULT_CONTROL_OPACITY_PERCENT = 100

/** 应用界面字体令牌:跟随系统(默认)/衬线/等宽;已导入字体走 `custom:<id>`(CustomFontContract 契约)。 */
internal const val FONT_FAMILY_SYSTEM = "system"
internal const val FONT_FAMILY_SERIF = "serif"
internal const val FONT_FAMILY_MONO = "monospace"

/** 内置通用字族;未列出的令牌交由 CustomFontContract 解析为已导入字体。 */
internal val BUILTIN_APP_FONT_FAMILIES = mapOf(
    FONT_FAMILY_SERIF to FontFamily.Serif,
    FONT_FAMILY_MONO to FontFamily.Monospace
)

internal const val APP_BACKGROUND_IMAGE_FILE = "app_background.jpg"
private const val MAX_BACKGROUND_IMAGE_SIDE = 2160

/** 背景图片是否生效:各屏 Scaffold 据此改透明,让图片透出。 */
internal val LocalAppBackgroundActive = staticCompositionLocalOf { false }

/** 自定义控件颜色(null=跟随主题);MainActivity 从外观状态提供,卡片/顶栏/悬浮导航玻璃化时取用。 */
internal val LocalAppControlColor = staticCompositionLocalOf { null as Color? }

/** 控件玻璃化的不透明度系数(0..1,100%=三档原生透明度);MainActivity 从外观状态提供。 */
internal val LocalAppControlOpacity = staticCompositionLocalOf { 1f }

/** 背景图层的 backdrop 句柄:顶栏渐变模糊据此采样壁纸;未启用背景或未记录时为 null。 */
internal val LocalAppLayerBackdrop = staticCompositionLocalOf { null as top.yukonga.miuix.kmp.blur.LayerBackdrop? }

// 背景图片生效时的玻璃化透明度:顶栏最透、卡片轻透、底部悬浮导航最实——
// 三档让壁纸透出的同时拉开层次(导航与卡片明度/实度差异即区分度来源)。
internal const val APP_GLASS_TOP_BAR_ALPHA = 0.7f
internal const val APP_GLASS_CARD_ALPHA = 0.82f
internal const val APP_GLASS_NAV_ALPHA = 0.93f

/**
 * 表面玻璃化:背景图片生效、或用户设置了自定义控件颜色时,按档位透明度×不透明度系数着色
 * (背景透出/页面底色透出);两者皆无时原样返回,维持 miuix 默认实色(默认用户零回归)。
 */
@Composable
internal fun appGlassSurface(color: Color, alpha: Float = APP_GLASS_CARD_ALPHA): Color {
    val effective = (alpha * LocalAppControlOpacity.current).coerceIn(0f, 1f)
    return when {
        LocalAppBackgroundActive.current || LocalAppControlColor.current != null ->
            color.copy(alpha = effective)

        else -> color
    }
}

/** 设置卡片容器色:自定义控件颜色优先,否则主题 token;未启用背景时与 miuix CardDefaults 默认一致。 */
@Composable
internal fun appCardContainerColor(): Color =
    appGlassSurface(LocalAppControlColor.current ?: MiuixTheme.colorScheme.surfaceContainer)

/** 玻璃化表面的内容色:自定义控件颜色按亮度反转保证可读,未自定义时回落主题默认。 */
@Composable
internal fun appControlContentColor(fallback: Color): Color {
    val custom = LocalAppControlColor.current ?: return fallback
    return if (custom.luminance() > 0.5f) Color.Black else Color.White
}

/** 顶栏容器色(miuix TopAppBar 默认实色 surface,背景生效时轻透,不再是一整块黑)。 */
@Composable
internal fun appTopBarColor(): Color =
    appGlassSurface(LocalAppControlColor.current ?: MiuixTheme.colorScheme.surface, APP_GLASS_TOP_BAR_ALPHA)

/** 底部悬浮导航容器色:比卡片更实一档并抬高明度档位,保证壁纸上的区分度;未启用背景时用 miuix 默认。 */
@Composable
internal fun appNavBarColor(): Color {
    val custom = LocalAppControlColor.current
    if (custom == null && !LocalAppBackgroundActive.current) {
        return MiuixTheme.colorScheme.surfaceContainer
    }
    return appGlassSurface(custom ?: MiuixTheme.colorScheme.surfaceContainerHighest, APP_GLASS_NAV_ALPHA)
}

/** 顶栏标题/返回图标色:自定义控件颜色按亮度反转,未自定义时回落 onSurface。 */
@Composable
internal fun appTopBarTitleColor(): Color =
    appControlContentColor(MiuixTheme.colorScheme.onSurface)

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
    val controlColorArgb = values[KEY_CONTROL_COLOR_ARGB] as? Int
    val controlOpacityPercent = ((values[KEY_CONTROL_OPACITY_PERCENT] as? Int)
        ?: DEFAULT_CONTROL_OPACITY_PERCENT).coerceIn(0, 100)
    val textColorArgb = values[KEY_TEXT_COLOR_ARGB] as? Int
    val fontFamily = normalizeAppFontFamily(values[KEY_FONT_FAMILY])
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
        controlColorArgb = controlColorArgb,
        controlOpacityPercent = controlOpacityPercent,
        textColorArgb = textColorArgb,
        fontFamily = fontFamily,
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
            KEY_CONTROL_COLOR_ARGB to appearance.controlColorArgb,
            KEY_CONTROL_OPACITY_PERCENT to appearance.controlOpacityPercent,
            KEY_TEXT_COLOR_ARGB to appearance.textColorArgb,
            KEY_FONT_FAMILY to appearance.fontFamily,
            KEY_SYSTEM_BAR_ICONS to appearance.systemBarIcons.name
        )
    )
    val editor = context.getSharedPreferences(APP_UI_PREFS, android.content.Context.MODE_PRIVATE).edit()
    editor.putString(KEY_THEME_MODE, normalized.themeMode.name)
    editor.putString(KEY_THEME_COLOR_MODE, normalized.themeColorMode.name)
    editor.putInt(KEY_THEME_COLOR_ARGB, normalized.themeColorArgb)
    editor.putBoolean(KEY_HAS_BACKGROUND_IMAGE, normalized.hasBackgroundImage)
    editor.putLong(KEY_BACKGROUND_IMAGE_MTIME, normalized.backgroundImageMtime)
    editor.putInt(KEY_BACKGROUND_DIM_PERCENT, normalized.backgroundDimPercent)
    editor.putInt(KEY_BACKGROUND_BLUR_PERCENT, normalized.backgroundBlurPercent)
    // 可空键:未设置即移除,让"恢复默认"真正回到主题默认色而不是残留旧值。
    if (normalized.controlColorArgb != null) {
        editor.putInt(KEY_CONTROL_COLOR_ARGB, normalized.controlColorArgb)
    } else {
        editor.remove(KEY_CONTROL_COLOR_ARGB)
    }
    editor.putInt(KEY_CONTROL_OPACITY_PERCENT, normalized.controlOpacityPercent)
    // 可空键:未设置即移除,让"恢复默认"真正回到主题文字色而不是残留旧值。
    if (normalized.textColorArgb != null) {
        editor.putInt(KEY_TEXT_COLOR_ARGB, normalized.textColorArgb)
    } else {
        editor.remove(KEY_TEXT_COLOR_ARGB)
    }
    editor.putString(KEY_FONT_FAMILY, normalized.fontFamily)
    editor.putString(KEY_SYSTEM_BAR_ICONS, normalized.systemBarIcons.name)
    return editor.commit()
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
    applyMiuiStatusBarDarkMode(activity.window, darkIcons)
}

/**
 * MIUI/HyperOS 的状态栏图标颜色由系统按状态栏背后的内容自动反色决定,会压过
 * WindowInsetsController 的外观请求(真机实测:标志位已下发到窗口管理器,同一标志在
 * 深色壁纸上仍渲染浅色图标,换成亮色背景后立刻变深色)。这里额外尝试 MIUI 自己的
 * extraWindowAttributes 标志争取优先权——类与常量在 HyperOS 3 上仍然存在,但入口方法
 * Window.setExtraFlags(int, int) 已被移除,实测该通道当前不可用(结论去重后留一条日志,
 * 便于将来在别的 MIUI 版本上复查);非 MIUI 或字段不存在时静默跳过,标准路径不受影响。
 */
private fun applyMiuiStatusBarDarkMode(window: android.view.Window, darkIcons: Boolean) {
    val failure = runCatching {
        val layoutParams = Class.forName("android.view.MiuiWindowManager\$LayoutParams")
        val darkModeFlag = layoutParams.getField("EXTRA_FLAG_STATUS_BAR_DARK_MODE").getInt(layoutParams)
        val intClass = Int::class.javaPrimitiveType ?: error("primitive int class unavailable")
        window.javaClass.getMethod("setExtraFlags", intClass, intClass)
            .invoke(window, if (darkIcons) darkModeFlag else 0, darkModeFlag)
    }.exceptionOrNull()
    val line = if (failure == null) {
        "dark=$darkIcons applied"
    } else {
        "dark=$darkIcons unavailable: ${failure.javaClass.simpleName}: ${failure.message}"
    }
    if (line != lastMiuiFlagOutcome) {
        lastMiuiFlagOutcome = line
        if (failure == null) AppLog.i("AppUiAppearance", "miui status-bar flag $line")
        else AppLog.w("AppUiAppearance", "miui status-bar flag $line")
    }
}

/** 最近一次 MIUI 标志下发结论;去重,避免每次重组都写日志。 */
private var lastMiuiFlagOutcome: String? = null

/** MIUI/HyperOS 家族:状态栏图标颜色由系统按状态栏背后的内容自动决定,应用的深浅设置会被压过。 */
internal fun isMiuiFamilySystem(): Boolean =
    Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
        Build.BRAND.equals("Redmi", ignoreCase = true) ||
        Build.BRAND.equals("POCO", ignoreCase = true)

/** 应用界面字体令牌规范化:白名单外一律回落跟随系统(fail-closed)。 */
internal fun normalizeAppFontFamily(value: Any?): String {
    if (value is String) {
        if (value == FONT_FAMILY_SYSTEM || value == FONT_FAMILY_SERIF || value == FONT_FAMILY_MONO) {
            return value
        }
        // 契约侧 id 会归一为小写,接受时回吐规范令牌,避免 raw 大写残留进 prefs/备份。
        val id = CustomFontContract.fontIdOf(value)
        if (id != null) return CustomFontContract.customFontFamily(id)
    }
    return FONT_FAMILY_SYSTEM
}

/**
 * 应用界面文本样式:fontFamily 为 null(跟随系统)时保持 miuix 默认;
 * 非 null 时把字族铺满全部 14 档样式,字号/字重/行高等其余属性逐档不变。
 */
internal fun appMiuixTextStyles(fontFamily: FontFamily?): TextStyles {
    val base = defaultTextStyles()
    if (fontFamily == null) return base
    return TextStyles(
        main = base.main.copy(fontFamily = fontFamily),
        paragraph = base.paragraph.copy(fontFamily = fontFamily),
        body1 = base.body1.copy(fontFamily = fontFamily),
        body2 = base.body2.copy(fontFamily = fontFamily),
        button = base.button.copy(fontFamily = fontFamily),
        footnote1 = base.footnote1.copy(fontFamily = fontFamily),
        footnote2 = base.footnote2.copy(fontFamily = fontFamily),
        headline1 = base.headline1.copy(fontFamily = fontFamily),
        headline2 = base.headline2.copy(fontFamily = fontFamily),
        subtitle = base.subtitle.copy(fontFamily = fontFamily),
        title1 = base.title1.copy(fontFamily = fontFamily),
        title2 = base.title2.copy(fontFamily = fontFamily),
        title3 = base.title3.copy(fontFamily = fontFamily),
        title4 = base.title4.copy(fontFamily = fontFamily)
    )
}

/**
 * 自定义文字色只覆盖主文字 token(根内容色 onBackground、Surface 内容色 onSurface、
 * 卡片内容色 onSurfaceContainer);summary/小节标题/行尾动作等次级 token 保持主题层级,
 * 避免整屏文字被同一个颜色抹平后失去主次。
 */
internal fun appTextColorScheme(base: Colors, custom: Color): Colors = base.copy(
    onBackground = custom,
    onSurface = custom,
    onSurfaceContainer = custom
)

/**
 * 字体令牌 → Compose 字族:内置字族取常量,已导入字体从应用私有目录直接加载
 * (与 SystemUI 不同,应用进程读自己的 filesDir 无需跨进程 Provider);
 * 文件缺失/损坏回落 null,由调用方走默认样式(跟随系统)。
 */
@Composable
internal fun appTextFontFamily(token: String): FontFamily? {
    BUILTIN_APP_FONT_FAMILIES[token]?.let { return it }
    val id = CustomFontContract.fontIdOf(token) ?: return null
    val context = LocalContext.current
    val file = CustomFontContract.fontFile(context.filesDir, id)
    return remember(file.absolutePath, file.lastModified()) {
        runCatching { FontFamily(Typeface.createFromFile(file)) }.getOrNull()
    }
}

/**
 * 自定义文字色的主题挂载点:未设置时原样透传;设置后用同源配色覆盖主文字 token
 * 并同步根内容色([LocalContentColor] 会被 Card 等容器按自身配色重设,这里只兜根层级)。
 */
@Composable
internal fun AppTextOverride(textColorArgb: Int?, content: @Composable () -> Unit) {
    val custom = textColorArgb?.let { Color(it) }
    if (custom == null) {
        content()
        return
    }
    val adjusted = appTextColorScheme(MiuixTheme.colorScheme, custom)
    MiuixTheme(colors = adjusted) {
        CompositionLocalProvider(LocalContentColor provides custom, content = content)
    }
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
