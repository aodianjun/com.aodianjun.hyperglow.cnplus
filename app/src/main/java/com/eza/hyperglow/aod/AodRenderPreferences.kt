package com.eza.hyperglow.aod

import android.content.Context
import android.content.SharedPreferences
import com.eza.hyperglow.producer.LyricSource

data class AodRenderConfig(
    val aodEnabled: Boolean = true,
    val lockscreenEnabled: Boolean = false,
    val seamlessTransitionEnabled: Boolean = true,
    val alignment: String = "auto",
    val secondaryMode: String = "Main only",
    val overflowMode: String = "Wrap",
    val metadataVisible: String = "hide",
    val metadataAnchor: String = "top",
    val weight: String = "Medium",
    val textSize: String = "normal",
    val textSizeCustom: Int = 100,
    val fontFamily: String = "spotify",
    val animation: String = "Gradient",
    val glow: String = "Off",
    val adaptiveSectioning: Boolean = true,
    val keepAwake: Boolean = true,
    val keepAwakeUnsynced: Boolean = false,
    val keepAwakeDurationMs: Long = -1L,
    val experimentalPositionFollowing: Boolean = false,
    val burnInPattern: String = "static_bottom",
    val burnInIntervalMs: Long = 60_000L,
    val pauseLingerMs: Long = 5_000L,
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
    val hideLauncherIcon: Boolean = false
)

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

internal fun normalizeKeepAwakeDurationMs(value: Long): Long = when (value) {
    300_000L, 600_000L, 1_800_000L, 3_600_000L, 7_200_000L -> value
    else -> -1L
}

internal fun normalizePauseLingerMs(value: Long): Long = when (value) {
    -1L, 0L, 5_000L, 10_000L, 30_000L -> value
    else -> 5_000L
}

object AodRenderPreferences {
    const val PREFS = "aod_render"
    const val SCHEMA_VERSION_KEY = "schema_version"
    /**
     * SharedPreferences 结构版本号。当 key 重命名、默认值变更或格式不兼容时递增,
     * 并在 [migrateIfNeeded] 中添加从旧版本到新版本的迁移逻辑。
     * v0 = 无版本戳(旧安装); v1 = 首次引入版本号。
     */
    const val SCHEMA_VERSION = 1
    const val AOD_ENABLED = "aod_enabled"
    const val LOCKSCREEN_ENABLED = "lockscreen_enabled"
    const val SEAMLESS_TRANSITION_ENABLED = "seamless_transition_enabled"
    const val ALIGNMENT = "alignment"
    const val SECONDARY = "secondary"
    const val OVERFLOW = "overflow"
    const val METADATA_VISIBLE = "metadata_visible"
    const val METADATA_ANCHOR = "metadata_anchor"
    const val WEIGHT = "weight"
    const val TEXT_SIZE = "text_size"
    const val TEXT_SIZE_CUSTOM = "text_size_custom"
    const val FONT_FAMILY = "font_family"
    const val ANIMATION = "animation"
    const val GLOW = "glow"
    const val ADAPTIVE_SECTIONING = "adaptive_sectioning"
    const val KEEP_AWAKE = "keep_awake"
    const val KEEP_AWAKE_UNSYNCED = "keep_awake_unsynced"
    const val KEEP_AWAKE_DURATION_MS = "keep_awake_duration_ms"
    const val EXPERIMENTAL_POSITION_FOLLOWING = "experimental_position_following"
    const val BURN_IN_PATTERN = "burn_in_pattern"
    const val BURN_IN_INTERVAL_MS = "burn_in_interval_ms"
    const val PAUSE_LINGER_MS = "pause_linger_ms"
    const val LOCKSCREEN_KEEP_AWAKE = "lockscreen_keep_awake"
    const val RAISE_TO_AOD = "raise_to_aod"
    const val SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS = "suppress_lockscreen_editor_long_press"
    const val LYRIC_SOURCE = "lyric_source"
    const val EXPERIMENTAL_MODE = "experimental_mode"
    const val PERSISTENT_NOTIFICATION = "persistent_notification"
    const val HIDE_BACKGROUND_CARD = "hide_background_card"
    const val HIDE_LAUNCHER_ICON = "hide_launcher_icon"

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
            migrateIfNeeded(it)
        }
        return cachedConfig ?: AodRenderConfig(
            prefs.getBoolean(AOD_ENABLED, true),
            prefs.getBoolean(LOCKSCREEN_ENABLED, false),
            true,
            normalizeAodAlignment(prefs.getString(ALIGNMENT, "auto")),
            normalizeAodSecondary(prefs.getString(SECONDARY, "Main only")),
            normalizeAodOverflow(prefs.getString(OVERFLOW, "Wrap")),
            normalizeAodMetadataVisible(prefs.getString(METADATA_VISIBLE, "hide")),
            normalizeAodMetadataAnchor(prefs.getString(METADATA_ANCHOR, "top")),
            normalizeAodWeight(prefs.getString(WEIGHT, "Medium")),
            normalizeAodTextSize(prefs.getString(TEXT_SIZE, "normal")),
            prefs.getInt(TEXT_SIZE_CUSTOM, 100).coerceIn(50, 200),
            normalizeAodFontFamily(prefs.getString(FONT_FAMILY, "spotify")),
            normalizeAodAnimation(prefs.getString(ANIMATION, "Gradient")),
            normalizeAodGlow(prefs.getString(GLOW, "Off")),
            prefs.getBoolean(ADAPTIVE_SECTIONING, true),
            prefs.getBoolean(KEEP_AWAKE, true),
            prefs.getBoolean(KEEP_AWAKE_UNSYNCED, false),
            normalizeKeepAwakeDurationMs(prefs.getLong(KEEP_AWAKE_DURATION_MS, -1L)),
            prefs.getBoolean(EXPERIMENTAL_POSITION_FOLLOWING, false),
            normalizeAodBurnInPattern(prefs.getString(BURN_IN_PATTERN, "static_bottom")),
            normalizeAodBurnInInterval(prefs.getLong(BURN_IN_INTERVAL_MS, 60_000L)),
            normalizePauseLingerMs(prefs.getLong(PAUSE_LINGER_MS, 5_000L)),
            prefs.getBoolean(LOCKSCREEN_KEEP_AWAKE, false),
            prefs.getBoolean(RAISE_TO_AOD, false),
            prefs.getBoolean(SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS, false),
            prefs.getBoolean(EXPERIMENTAL_MODE, false),
            prefs.getBoolean(PERSISTENT_NOTIFICATION, true),
            prefs.getBoolean(HIDE_BACKGROUND_CARD, false),
            prefs.getBoolean(HIDE_LAUNCHER_ICON, false)
        ).also { cachedConfig = it }
    }

    /**
     * 将旧版本的 SharedPreferences 迁移到当前 [SCHEMA_VERSION]。
     * v0→v1: 无结构变更,各 normalize* 函数已内联处理旧值;仅写入版本戳。
     * 后续版本在此追加 `if (from < 2) { ... }` 分支。
     */
    private fun migrateIfNeeded(prefs: SharedPreferences) {
        val current = prefs.getInt(SCHEMA_VERSION_KEY, 0)
        if (current >= SCHEMA_VERSION) return
        prefs.edit().putInt(SCHEMA_VERSION_KEY, SCHEMA_VERSION).apply()
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
