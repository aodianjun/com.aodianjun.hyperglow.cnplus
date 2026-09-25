package com.eza.hyperglow.aod

import android.content.Context
import android.content.SharedPreferences
import com.eza.hyperglow.producer.LyricSource

data class AodRenderConfig(
    val aodEnabled: Boolean = true,
    val lockscreenEnabled: Boolean = false,
    val alignment: String = "auto",
    val secondaryMode: String = "Main only",
    val overflowMode: String = "Wrap",
    val metadataVisible: String = "hide",
    val metadataAnchor: String = "top",
    val metadataSizePercent: Int = 100,
    val weight: String = "Medium",
    val textSize: String = "normal",
    val textSizeCustom: Int = 100,
    val fontFamily: String = "spotify",
    val animation: String = "Gradient",
    val glow: String = "Off",
    val adaptiveSectioning: Boolean = true,
    val keepAwake: Boolean = true,
    /** 时钟跟随模式:true=实时跟随实际时钟位置定位AOD歌词;false=锚定防抖(默认)。 */
    val aodClockFollow: Boolean = false,
    /**
     * 自定义系统时钟钉住位置的垂直偏移(px,负值上移、正值下移)。
     * 仅在关闭「实时跟随系统时钟」且 AOD 正在渲染时,叠加到被钉住的时钟 Y 上。
     */
    val aodClockYOffset: Int = 0,
    val keepAwakeUnsynced: Boolean = false,
    val keepAwakeDurationMs: Long = -1L,
    val experimentalPositionFollowing: Boolean = false,
    val burnInPattern: String = "static_bottom",
    val burnInIntervalMs: Long = 60_000L,
    val pauseLingerMs: Long = 5_000L,
    /**
     * 暂停时显示歌曲信息、歌词:全局开关,同时作用于息屏(AOD)与锁屏。
     * 开启后,音乐暂停时按 [pauseLingerMs] 显示冻结的歌曲信息与歌词;
     * 关闭(默认)时,暂停立即清除两侧内容。
     */
    val pauseShowContent: Boolean = false,
    val lockscreenKeepAwake: Boolean = false,
    val raiseToAod: Boolean = false,
    val suppressLockscreenEditorLongPress: Boolean = false,
    /**
     * 用户手动开启的实验模式。当 SystemUI 版本不在 verified 白名单(profileState=
     * EXPERIMENTAL_ELIGIBLE)但符号探测显示 surface 可用时,开启此项让 app 端本地把
     * supportState 视为 EXPERIMENTAL_ACTIVE,从而放开 AOD/锁屏 surface 配置。
     *
     * 仅影响 app 端 UI 是否允许配置;实际渲染依赖 hook 端已 try/catch 安装的 surface
     * hook(符号在则装上,符号不在则跳过)。风险:未验证版本上符号签名可能不一致,
     * hook 装上但行为异常 —— 由用户自行承担。
     */
    val experimentalMode: Boolean = false,
    /**
     * 是否在状态栏常驻前台服务通知(true 默认显示,false 拉起前台服务后立刻隐藏通知,
     * 以保持进程前台不被冻结,同时让通知栏更干净)。
     */
    val persistentNotification: Boolean = true,
    /** 是否从最近任务列表(后台卡片)中隐藏本应用。 */
    val hideBackgroundCard: Boolean = false,
    /** 是否从桌面启动器中隐藏应用图标。 */
    val hideLauncherIcon: Boolean = false,
    /**
     * AOD 亮度增强:歌词 guard 激活且处于 DOZE_AOD 时,把小米压低的 doze 亮度
     * 钳制到可读级别(见 AodBrightnessHook)。默认开启,保持既有行为。
     */
    val aodBrightnessBoost: Boolean = true,
    /**
     * 插件处理总开关:开启后 HyperLyric 兼容插件链参与歌词富化(翻译/罗马音/逐字等)。
     * 默认关闭;关闭或无插件结果时投影链保持透传,行为与未装插件完全一致。
     */
    val pluginProcessingEnabled: Boolean = false,
    /**
     * 抑制小米系统 AOD 内容:绘制全屏自绘歌词画布时,通过 View.setVisibility 强制接缝把
     * 受抑制容器上任何非 GONE 请求改回 GONE,隐藏时钟/天气等系统元素(可独立开关)。
     */
    val suppressStockAodContent: Boolean = false,
    /** AOD 画布随设备旋转(竖屏/横屏/横屏反向/自动)。可独立开关。 */
    val aodRotateWithDevice: Boolean = false,
    val aodRotationMode: String = AOD_ROTATION_MODE_PORTRAIT,
    val aodRotationSettleMs: Long = DEFAULT_ROTATION_SETTLE_MS,
    val aodCanvasAnchorLandscape: Float = DEFAULT_CANVAS_ANCHOR,
    val aodLandscapeTextScale: Float = DEFAULT_LANDSCAPE_TEXT_SCALE,
    /**
     * 横屏且歌词随设备旋转时,自动隐藏系统息屏内容(时钟/天气等);回竖屏从旋转回落时
     * 自动恢复系统息屏内容。可独立开关(默认关闭,保持横屏也显示系统内容)。
     */
    val aodLandscapeHideStock: Boolean = false,
    /** 横屏全屏化:歌词在横屏时居中并自动放缩铺满(不越界),默认关闭。 */
    val aodLandscapeFullscreen: Boolean = false,
    val aodCanvasPaddingPortraitXPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    val aodCanvasPaddingPortraitYPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    val aodCanvasPaddingLandscapeXPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    val aodCanvasPaddingLandscapeYPercent: Float = DEFAULT_CANVAS_PADDING_PERCENT,
    /**
     * AOD 亮度增强模式:仅当 [aodBrightnessBoost] 为 true 时生效。
     *  - false = 按场景自动化(把小米压低的 doze 亮度钳制到可读级别);
     *  - true  = 用 [aodBrightnessLevel] 固定的自定义亮度覆盖。
     */
    val aodBrightnessOverride: Boolean = false,
    /** 自定义 AOD 亮度(10-255),仅当 [aodBrightnessOverride] 为 true 时生效。 */
    val aodBrightnessLevel: Int = DEFAULT_AOD_BRIGHTNESS_LEVEL,
    /** 调试开关:在 AOD 画布上描出画布边界(逻辑帧 ow×oh)与内容裁剪区,便于核对布局。 */
    val aodDebugShowCanvasFrame: Boolean = false,
    /**
     * 渲染刷新率上限档(issue #68 #12):0=跟随现有行为(16ms≈60fps);
     * 60/90/120=用户可选上限,随配置下发到 SystemUI 侧帧调度。
     */
    val aodRefreshRateCap: Int = 0
) {
    companion object {
        /** 出厂默认配置;备份解码时用于逐字段回退缺失/类型错误的值。 */
        val DEFAULTS: AodRenderConfig = AodRenderConfig()
    }
}

internal fun normalizeAodAlignment(value: String?): String = when (value) {
    "auto" -> "auto"
    "start" -> "start"
    "center" -> "center"
    "end" -> "end"
    else -> "auto"
}

internal fun normalizeAodSecondary(value: String?): String = when (value) {
    "Transliteration" -> "Transliteration"
    "Translation" -> "Translation"
    "Both" -> "Both"
    else -> "Main only"
}

internal fun normalizeAodOverflow(value: String?): String = when (value) {
    "Clip" -> "Clip"
    else -> "Wrap"
}

internal fun normalizeAodMetadataVisible(value: String?): String =
    if (value == "show") "show" else "hide"

internal fun normalizeAodMetadataAnchor(value: String?): String =
    if (value == "bottom") "bottom" else "top"

internal fun normalizeAodWeight(value: String?): String = when (value) {
    "Regular" -> "Regular"
    "Bold" -> "Bold"
    else -> "Medium"
}

internal fun normalizeAodTextSize(value: String?): String = when (value) {
    "small" -> "small"
    "large" -> "large"
    "xlarge" -> "xlarge"
    "custom" -> "custom"
    else -> "normal"
}

internal fun normalizeAodFontFamily(value: String?): String = when (value) {
    "noto" -> "noto"
    "spotify" -> "spotify"
    "apple" -> "apple"
    else -> "spotify"
}

internal fun normalizeAodAnimation(value: String?): String =
    if (value == "Minimal") "Minimal" else "Gradient"

internal fun normalizeAodGlow(value: String?): String = when (value) {
    "On", "Word only", "Subtle line" -> "On"
    else -> "Off"
}

internal fun normalizeAodBurnInPattern(value: String?): String = when (value) {
    "static_top" -> "static_top"
    "static_bottom" -> "static_bottom"
    "vertical_swap" -> "vertical_swap"
    "four_corner" -> "four_corner"
    "six_zone" -> "six_zone"
    else -> "static_bottom"
}

internal fun normalizeAodBurnInInterval(value: Long): Long = when {
    value < 45_000L -> 30_000L
    value < 90_000L -> 60_000L
    value < 210_000L -> 120_000L
    else -> 300_000L
}

internal fun normalizeAodCanvasAnchor(value: Float): Float =
    if (value.isFinite() && value in 0f..1f) value else DEFAULT_CANVAS_ANCHOR

internal fun normalizeAodRotationSettleMs(value: Long): Long = when (value) {
    0L, 500L, 1_000L, 2_000L, 5_000L, 10_000L -> value
    else -> DEFAULT_ROTATION_SETTLE_MS
}

internal fun normalizeAodRotationMode(value: String?): String = when (value) {
    AOD_ROTATION_MODE_LANDSCAPE -> AOD_ROTATION_MODE_LANDSCAPE
    AOD_ROTATION_MODE_LANDSCAPE_REVERSE -> AOD_ROTATION_MODE_LANDSCAPE_REVERSE
    AOD_ROTATION_MODE_AUTO -> AOD_ROTATION_MODE_AUTO
    else -> AOD_ROTATION_MODE_PORTRAIT
}

/**
 * issue #29:设置页只暴露「随设备旋转」总开关,从不写入 AOD_ROTATION_MODE,导致该偏好
 * 一直停留在 [AOD_ROTATION_MODE_PORTRAIT],最终 [resolveAodRotationStep] 对 portrait 恒返回
 * null、永不旋转。此处做读取联动:开关已开启但模式仍为 portrait(即从未设置有效模式)时
 * 视作 [AOD_ROTATION_MODE_AUTO];开关关闭时保持 portrait(不影响任何旋转行为)。
 */
internal fun effectiveAodRotationMode(rotateWithDevice: Boolean, normalizedMode: String): String =
    if (rotateWithDevice && normalizedMode == AOD_ROTATION_MODE_PORTRAIT) {
        AOD_ROTATION_MODE_AUTO
    } else {
        normalizedMode
    }

internal fun normalizeAodLandscapeTextScale(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0.5f, 2f) else DEFAULT_LANDSCAPE_TEXT_SCALE

/**
 * Per-axis canvas padding in percent of the logical frame (0-20%). Percent
 * keeps insets proportional on the long and short axes, where an absolute dp
 * value would eat ~2.3x more of the short axis than the long one.
 */
internal fun normalizeAodCanvasPaddingPercent(value: Float): Float =
    if (value.isFinite()) value.coerceIn(0f, MAX_CANVAS_PADDING_PERCENT)
    else DEFAULT_CANVAS_PADDING_PERCENT

internal const val MAX_CANVAS_PADDING_PERCENT = 20f
internal const val DEFAULT_CANVAS_PADDING_PERCENT = 2f
/** 自定义 AOD 亮度档位的安全范围。 */
internal const val MIN_AOD_BRIGHTNESS = 10
internal const val MAX_AOD_BRIGHTNESS = 255
internal const val DEFAULT_AOD_BRIGHTNESS_LEVEL = 255
internal const val MIN_AOD_CLOCK_Y_OFFSET = -480
internal const val MAX_AOD_CLOCK_Y_OFFSET = 480
private const val DEFAULT_AOD_CLOCK_Y_OFFSET = 0
private const val DEFAULT_CANVAS_ANCHOR = 0.5f
private const val DEFAULT_ROTATION_SETTLE_MS = 1_000L
private const val DEFAULT_LANDSCAPE_TEXT_SCALE = 1f
internal const val AOD_ROTATION_MODE_PORTRAIT = "portrait"
internal const val AOD_ROTATION_MODE_LANDSCAPE = "landscape"
internal const val AOD_ROTATION_MODE_LANDSCAPE_REVERSE = "landscape_reverse"
internal const val AOD_ROTATION_MODE_AUTO = "auto"

internal fun normalizeKeepAwakeDurationMs(value: Long): Long = when (value) {
    300_000L, 600_000L, 1_800_000L, 3_600_000L, 7_200_000L -> value
    else -> -1L
}

internal fun normalizePauseLingerMs(value: Long): Long = when (value) {
    -1L, 0L, 5_000L, 10_000L, 30_000L -> value
    else -> 5_000L
}

/** 自定义系统时钟 Y 偏移(px),钳制到安全范围。默认 0(不偏移)。 */
internal fun normalizeAodClockYOffset(value: Int): Int =
    value.coerceIn(MIN_AOD_CLOCK_Y_OFFSET, MAX_AOD_CLOCK_Y_OFFSET)

/** 渲染刷新率上限档:仅接受 0(跟随现有)/60/90/120,其余回落 0。 */
internal fun normalizeAodRefreshRateCap(value: Int): Int = when (value) {
    60, 90, 120 -> value
    else -> 0
}

object AodRenderPreferences {
    const val PREFS = "aod_render"
    const val AOD_ENABLED = "aod_enabled"
    const val LOCKSCREEN_ENABLED = "lockscreen_enabled"
    const val ALIGNMENT = "alignment"
    const val SECONDARY = "secondary"
    const val OVERFLOW = "overflow"
    const val METADATA_VISIBLE = "metadata_visible"
    const val METADATA_ANCHOR = "metadata_anchor"
    const val METADATA_SIZE = "metadata_size"
    const val WEIGHT = "weight"
    const val TEXT_SIZE = "text_size"
    const val TEXT_SIZE_CUSTOM = "text_size_custom"
    const val FONT_FAMILY = "font_family"
    const val ANIMATION = "animation"
    const val GLOW = "glow"
    const val ADAPTIVE_SECTIONING = "adaptive_sectioning"
    const val KEEP_AWAKE = "keep_awake"
    const val AOD_CLOCK_FOLLOW = "aod_clock_follow"
    const val AOD_CLOCK_Y_OFFSET = "aod_clock_y_offset"
    const val KEEP_AWAKE_UNSYNCED = "keep_awake_unsynced"
    const val KEEP_AWAKE_DURATION_MS = "keep_awake_duration_ms"
    const val EXPERIMENTAL_POSITION_FOLLOWING = "experimental_position_following"
    const val BURN_IN_PATTERN = "burn_in_pattern"
    const val BURN_IN_INTERVAL_MS = "burn_in_interval_ms"
    const val PAUSE_LINGER_MS = "pause_linger_ms"
    const val PAUSE_SHOW_CONTENT = "pause_show_content"
    const val LOCKSCREEN_KEEP_AWAKE = "lockscreen_keep_awake"
    const val RAISE_TO_AOD = "raise_to_aod"
    const val SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS = "suppress_lockscreen_editor_long_press"
    const val LYRIC_SOURCE = "lyric_source"
    const val EXPERIMENTAL_MODE = "experimental_mode"
    const val PERSISTENT_NOTIFICATION = "persistent_notification"
    const val HIDE_BACKGROUND_CARD = "hide_background_card"
    const val HIDE_LAUNCHER_ICON = "hide_launcher_icon"
    const val AOD_BRIGHTNESS_BOOST = "aod_brightness_boost"
    const val AOD_BRIGHTNESS_OVERRIDE = "aod_brightness_override"
    const val AOD_BRIGHTNESS_LEVEL = "aod_brightness_level"
    const val PLUGIN_PROCESSING_ENABLED = "plugin_processing_enabled"
    const val SUPPRESS_STOCK_AOD_CONTENT = "suppress_stock_aod_content"
    const val AOD_ROTATE_WITH_DEVICE = "aod_rotate_with_device"
    const val AOD_ROTATION_MODE = "aod_rotation_mode"
    const val AOD_ROTATION_SETTLE_MS = "aod_rotation_settle_ms"
    const val AOD_CANVAS_ANCHOR_LANDSCAPE = "aod_canvas_anchor_landscape"
    const val AOD_LANDSCAPE_TEXT_SCALE = "aod_landscape_text_scale"
    const val AOD_LANDSCAPE_HIDE_STOCK = "aod_landscape_hide_stock"
    const val AOD_LANDSCAPE_FULLSCREEN = "aod_landscape_fullscreen"
    const val AOD_CANVAS_PADDING_PORTRAIT_X_PERCENT = "aod_canvas_padding_portrait_x_percent"
    const val AOD_CANVAS_PADDING_PORTRAIT_Y_PERCENT = "aod_canvas_padding_portrait_y_percent"
    const val AOD_CANVAS_PADDING_LANDSCAPE_X_PERCENT = "aod_canvas_padding_landscape_x_percent"
    const val AOD_CANVAS_PADDING_LANDSCAPE_Y_PERCENT = "aod_canvas_padding_landscape_y_percent"
    const val AOD_DEBUG_SHOW_CANVAS_FRAME = "aod_debug_show_canvas_frame"
    const val AOD_REFRESH_RATE_CAP = "aod_refresh_rate_cap"

    // SharedPreferences throws ClassCastException when an older/imported value has the wrong
    // primitive type. Treat malformed entries as missing so a bad setting cannot crash startup.
    private fun SharedPreferences.safeBoolean(key: String, default: Boolean): Boolean =
        runCatching { getBoolean(key, default) }.getOrDefault(default)

    private fun SharedPreferences.safeInt(key: String, default: Int): Int =
        runCatching { getInt(key, default) }.getOrDefault(default)

    private fun SharedPreferences.safeLong(key: String, default: Long): Long =
        runCatching { getLong(key, default) }.getOrDefault(default)

    private fun SharedPreferences.safeFloat(key: String, default: Float): Float =
        runCatching { getFloat(key, default) }.getOrDefault(default)

    private fun SharedPreferences.safeString(key: String, default: String?): String? =
        runCatching { getString(key, default) }.getOrDefault(default)

    private var preferences: SharedPreferences? = null
    private var cachedConfig: AodRenderConfig? = null
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        synchronized(this) { cachedConfig = null }
    }

    @Synchronized
    fun read(context: Context): AodRenderConfig {
        val prefs = preferences ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).also {
            preferences = it
            it.registerOnSharedPreferenceChangeListener(preferenceListener)
        }
        cachedConfig?.let { return it }
        val rotateWithDevice = prefs.safeBoolean(AOD_ROTATE_WITH_DEVICE, false)
        return AodRenderConfig(
            prefs.safeBoolean(AOD_ENABLED, true),
            prefs.safeBoolean(LOCKSCREEN_ENABLED, false),
            normalizeAodAlignment(prefs.safeString(ALIGNMENT, "auto")),
            normalizeAodSecondary(prefs.safeString(SECONDARY, "Main only")),
            normalizeAodOverflow(prefs.safeString(OVERFLOW, "Wrap")),
            normalizeAodMetadataVisible(prefs.safeString(METADATA_VISIBLE, "hide")),
            normalizeAodMetadataAnchor(prefs.safeString(METADATA_ANCHOR, "top")),
            prefs.safeInt(METADATA_SIZE, 100).coerceIn(50, 200),
            normalizeAodWeight(prefs.safeString(WEIGHT, "Medium")),
            normalizeAodTextSize(prefs.safeString(TEXT_SIZE, "normal")),
            prefs.safeInt(TEXT_SIZE_CUSTOM, 100).coerceIn(50, 200),
            normalizeAodFontFamily(prefs.safeString(FONT_FAMILY, "spotify")),
            normalizeAodAnimation(prefs.safeString(ANIMATION, "Gradient")),
            normalizeAodGlow(prefs.safeString(GLOW, "Off")),
            prefs.safeBoolean(ADAPTIVE_SECTIONING, true),
            prefs.safeBoolean(KEEP_AWAKE, true),
            prefs.safeBoolean(AOD_CLOCK_FOLLOW, false),
            normalizeAodClockYOffset(prefs.safeInt(AOD_CLOCK_Y_OFFSET, DEFAULT_AOD_CLOCK_Y_OFFSET)),
            prefs.safeBoolean(KEEP_AWAKE_UNSYNCED, false),
            normalizeKeepAwakeDurationMs(prefs.safeLong(KEEP_AWAKE_DURATION_MS, -1L)),
            prefs.safeBoolean(EXPERIMENTAL_POSITION_FOLLOWING, false),
            normalizeAodBurnInPattern(prefs.safeString(BURN_IN_PATTERN, "static_bottom")),
            normalizeAodBurnInInterval(prefs.safeLong(BURN_IN_INTERVAL_MS, 60_000L)),
            normalizePauseLingerMs(prefs.safeLong(PAUSE_LINGER_MS, 5_000L)),
            prefs.safeBoolean(PAUSE_SHOW_CONTENT, false),
            prefs.safeBoolean(LOCKSCREEN_KEEP_AWAKE, false),
            prefs.safeBoolean(RAISE_TO_AOD, false),
            prefs.safeBoolean(SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS, false),
            prefs.safeBoolean(EXPERIMENTAL_MODE, false),
            prefs.safeBoolean(PERSISTENT_NOTIFICATION, true),
            prefs.safeBoolean(HIDE_BACKGROUND_CARD, false),
            prefs.safeBoolean(HIDE_LAUNCHER_ICON, false),
            prefs.safeBoolean(AOD_BRIGHTNESS_BOOST, true),
            prefs.safeBoolean(PLUGIN_PROCESSING_ENABLED, false),
            prefs.safeBoolean(SUPPRESS_STOCK_AOD_CONTENT, false),
            rotateWithDevice,
            effectiveAodRotationMode(
                rotateWithDevice,
                normalizeAodRotationMode(
                    prefs.safeString(AOD_ROTATION_MODE, AOD_ROTATION_MODE_PORTRAIT)
                )
            ),
            normalizeAodRotationSettleMs(prefs.safeLong(AOD_ROTATION_SETTLE_MS, 1_000L)),
            normalizeAodCanvasAnchor(prefs.safeFloat(AOD_CANVAS_ANCHOR_LANDSCAPE, 0.5f)),
            normalizeAodLandscapeTextScale(prefs.safeFloat(AOD_LANDSCAPE_TEXT_SCALE, 1f)),
            prefs.safeBoolean(AOD_LANDSCAPE_HIDE_STOCK, false),
            prefs.safeBoolean(AOD_LANDSCAPE_FULLSCREEN, false),
            normalizeAodCanvasPaddingPercent(
                prefs.safeFloat(AOD_CANVAS_PADDING_PORTRAIT_X_PERCENT, DEFAULT_CANVAS_PADDING_PERCENT)
            ),
            normalizeAodCanvasPaddingPercent(
                prefs.safeFloat(AOD_CANVAS_PADDING_PORTRAIT_Y_PERCENT, DEFAULT_CANVAS_PADDING_PERCENT)
            ),
            normalizeAodCanvasPaddingPercent(
                prefs.safeFloat(AOD_CANVAS_PADDING_LANDSCAPE_X_PERCENT, DEFAULT_CANVAS_PADDING_PERCENT)
            ),
            normalizeAodCanvasPaddingPercent(
                prefs.safeFloat(AOD_CANVAS_PADDING_LANDSCAPE_Y_PERCENT, DEFAULT_CANVAS_PADDING_PERCENT)
            ),
            prefs.safeBoolean(AOD_BRIGHTNESS_OVERRIDE, false),
            prefs.safeInt(AOD_BRIGHTNESS_LEVEL, DEFAULT_AOD_BRIGHTNESS_LEVEL)
                .coerceIn(MIN_AOD_BRIGHTNESS, MAX_AOD_BRIGHTNESS),
            prefs.safeBoolean(AOD_DEBUG_SHOW_CANVAS_FRAME, false),
            normalizeAodRefreshRateCap(prefs.safeInt(AOD_REFRESH_RATE_CAP, 0))
        ).also { cachedConfig = it }
    }

    /**
     * The user's preferred lyrics source (Spicy EX vs Lyricon). Persisted so the choice
     * survives process restarts; read once at arbiter startup, written on every switch.
     * Defaults to [LyricSource.SPICY] (the historical behavior) when unset.
     */
    fun readLyricSource(context: Context): LyricSource {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(LYRIC_SOURCE, LyricSource.SPICY.name)
        return LyricSource.entries.firstOrNull { it.name == stored } ?: LyricSource.SPICY
    }

    fun writeLyricSource(context: Context, source: LyricSource) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LYRIC_SOURCE, source.name)
            .apply()
    }
}
