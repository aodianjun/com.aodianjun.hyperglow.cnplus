package com.eza.hyperglow.ui

import com.eza.hyperglow.DiagnosticLogLevel
import com.eza.hyperglow.DiagnosticTraceFile
import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.AodRenderConfig.Companion.DEFAULTS
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.MAX_AOD_BRIGHTNESS
import com.eza.hyperglow.aod.MIN_AOD_BRIGHTNESS
import com.eza.hyperglow.aod.normalizeAodCanvasAnchor
import com.eza.hyperglow.aod.normalizeAodCanvasPaddingPercent
import com.eza.hyperglow.aod.normalizeAodClockYOffset
import com.eza.hyperglow.aod.normalizeAodLandscapeTextScale
import com.eza.hyperglow.aod.normalizeAodRefreshRateCap
import com.eza.hyperglow.aod.normalizeAodRotationMode
import com.eza.hyperglow.aod.normalizeAodRotationSettleMs
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_AUTO
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.normalizeDiagnosticLogLevel
import com.eza.hyperglow.normalizeLogRetentionDays
import com.eza.hyperglow.plugin.isValidPluginId
import com.eza.hyperglow.producer.LyricSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * 类型安全的配置备份编解码。
 *
 * 旧实现(见 [com.eza.hyperglow.ui.MainActivity] 的 exportAllConfig/importAllConfig)在导入时猜测
 * 每个值的类型,且先试 intOrNull 再试 longOrNull,于是任何数值恰好落在 Int 范围内的 Long 偏好
 * (如 keepAwakeDurationMs)会被还原成 Int,下一次 [AodRenderPreferences.read] 在启动路径抛出
 * ClassCastException。这里每个 key 声明且只声明一种类型;任何其它形状的值都被丢弃回该 key 的
 * 默认值,未知 key 一律忽略、绝不写入。
 *
 * 在 v1 信封([PREFERENCES_KEY] + [CUSTOMIZATION_KEY])之上,格式按纯加法约定补齐了全部
 * 用户设置:[LYRIC_SOURCE_KEY](歌词源)、[APP_UI_KEY](应用外观)、[UI_LANGUAGE_KEY](界面语言)、
 * [DIAGNOSTICS_KEY](诊断开关与日志保留)、[PLUGIN_SETTINGS_KEY](插件设置,按 manifest 的
 * `backup` 标记过滤)。加法演进不抬版本号:老版本按"未知 key 忽略"照常导入它认识的部分,
 * 新版本对未含新键的旧备份一律"缺省即保持现状",不会把新设置面清成默认值。资源类文件
 * (自定义字体、背景图片、插件安装包)不进 JSON,配置备份只承载设置。
 */
internal sealed interface ConfigBackupDecodeResult {
    data class Success(
        val preferences: AodRenderConfig,
        val customizationDocument: CustomizationDocument?,
        /** 补充设置面;各字段为 null 表示载荷未含该键,导入端保持现状。 */
        val sideSettings: ConfigBackupSideSettings
    ) : ConfigBackupDecodeResult

    /** 载荷整体拒绝;被拒的导入绝不会部分应用状态。 */
    data class Rejected(val reason: ConfigBackupRejection) : ConfigBackupDecodeResult
}

internal enum class ConfigBackupRejection {
    OVERSIZE,
    BAD_FORMAT,
    BAD_VERSION,
    MALFORMED
}

/**
 * 补齐后的设置面。null = 载荷未含该键,导入端保持现状;非 null(含默认值)则整体应用。
 */
internal data class ConfigBackupSideSettings(
    val lyricSource: LyricSource? = null,
    val appUiAppearance: AppUiAppearance? = null,
    val uiLanguage: UiLanguage? = null,
    val diagnosticLogging: Boolean? = null,
    val logRetentionDays: Int? = null,
    val logLevel: DiagnosticLogLevel? = null,
    /** 插件 id → (设置键 → SharedPreferences 原生值);写入按覆盖语义,未列出的键保持现状。 */
    val pluginSettings: Map<String, Map<String, Any?>>? = null
)

internal data class BackupBooleanField(val key: String, val read: (AodRenderConfig) -> Boolean)

internal data class BackupFloatField(val key: String, val read: (AodRenderConfig) -> Float)

internal data class BackupIntField(val key: String, val read: (AodRenderConfig) -> Int)

internal data class BackupLongField(val key: String, val read: (AodRenderConfig) -> Long)

internal data class BackupStringField(val key: String, val read: (AodRenderConfig) -> String)

internal object ConfigBackupCodec {
    const val FORMAT = "hyperglow-config-backup"
    const val VERSION = 1

    /** 载荷上限:渲染设置本体很小,余量留给插件设置里的长文本值。 */
    const val MAX_BYTES = 2 * 1024 * 1024

    internal val booleanFields = listOf(
        BackupBooleanField(AodRenderPreferences.AOD_ENABLED) { it.aodEnabled },
        BackupBooleanField(AodRenderPreferences.LOCKSCREEN_ENABLED) { it.lockscreenEnabled },
        BackupBooleanField(AodRenderPreferences.ADAPTIVE_SECTIONING) { it.adaptiveSectioning },
        BackupBooleanField(AodRenderPreferences.KEEP_AWAKE) { it.keepAwake },
        BackupBooleanField(AodRenderPreferences.AOD_CLOCK_FOLLOW) { it.aodClockFollow },
        BackupBooleanField(AodRenderPreferences.KEEP_AWAKE_UNSYNCED) { it.keepAwakeUnsynced },
        BackupBooleanField(AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING) {
            it.experimentalPositionFollowing
        },
        BackupBooleanField(AodRenderPreferences.PAUSE_SHOW_CONTENT) { it.pauseShowContent },
        BackupBooleanField(AodRenderPreferences.LOCKSCREEN_KEEP_AWAKE) { it.lockscreenKeepAwake },
        BackupBooleanField(AodRenderPreferences.RAISE_TO_AOD) { it.raiseToAod },
        BackupBooleanField(AodRenderPreferences.SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS) {
            it.suppressLockscreenEditorLongPress
        },
        BackupBooleanField(AodRenderPreferences.EXPERIMENTAL_MODE) { it.experimentalMode },
        BackupBooleanField(AodRenderPreferences.PERSISTENT_NOTIFICATION) {
            it.persistentNotification
        },
        BackupBooleanField(AodRenderPreferences.HIDE_BACKGROUND_CARD) { it.hideBackgroundCard },
        BackupBooleanField(AodRenderPreferences.HIDE_LAUNCHER_ICON) { it.hideLauncherIcon },
        BackupBooleanField(AodRenderPreferences.AOD_BRIGHTNESS_BOOST) { it.aodBrightnessBoost },
        BackupBooleanField(AodRenderPreferences.PLUGIN_PROCESSING_ENABLED) {
            it.pluginProcessingEnabled
        },
        BackupBooleanField(AodRenderPreferences.SUPPRESS_STOCK_AOD_CONTENT) {
            it.suppressStockAodContent
        },
        BackupBooleanField(AodRenderPreferences.AOD_LANDSCAPE_HIDE_STOCK) {
            it.aodLandscapeHideStock
        },
        BackupBooleanField(AodRenderPreferences.AOD_LANDSCAPE_FULLSCREEN) {
            it.aodLandscapeFullscreen
        },
        BackupBooleanField(AodRenderPreferences.AOD_ROTATE_WITH_DEVICE) {
            it.aodRotateWithDevice
        },
        BackupBooleanField(AodRenderPreferences.AOD_BRIGHTNESS_OVERRIDE) {
            it.aodBrightnessOverride
        },
        BackupBooleanField(AodRenderPreferences.AOD_DEBUG_SHOW_CANVAS_FRAME) {
            it.aodDebugShowCanvasFrame
        }
    )

    internal val intFields = listOf(
        BackupIntField(AodRenderPreferences.TEXT_SIZE_CUSTOM) { it.textSizeCustom },
        BackupIntField(AodRenderPreferences.METADATA_SIZE) { it.metadataSizePercent },
        BackupIntField(AodRenderPreferences.AOD_BRIGHTNESS_LEVEL) { it.aodBrightnessLevel },
        BackupIntField(AodRenderPreferences.AOD_CLOCK_Y_OFFSET) { it.aodClockYOffset },
        BackupIntField(AodRenderPreferences.AOD_REFRESH_RATE_CAP) { it.aodRefreshRateCap }
    )

    internal val floatFields = listOf(
        BackupFloatField(AodRenderPreferences.AOD_CANVAS_ANCHOR_LANDSCAPE) {
            it.aodCanvasAnchorLandscape
        },
        BackupFloatField(AodRenderPreferences.AOD_LANDSCAPE_TEXT_SCALE) {
            it.aodLandscapeTextScale
        },
        BackupFloatField(AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_X_PERCENT) {
            it.aodCanvasPaddingPortraitXPercent
        },
        BackupFloatField(AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_Y_PERCENT) {
            it.aodCanvasPaddingPortraitYPercent
        },
        BackupFloatField(AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_X_PERCENT) {
            it.aodCanvasPaddingLandscapeXPercent
        },
        BackupFloatField(AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_Y_PERCENT) {
            it.aodCanvasPaddingLandscapeYPercent
        }
    )

    internal val longFields = listOf(
        BackupLongField(AodRenderPreferences.KEEP_AWAKE_DURATION_MS) { it.keepAwakeDurationMs },
        BackupLongField(AodRenderPreferences.BURN_IN_INTERVAL_MS) { it.burnInIntervalMs },
        BackupLongField(AodRenderPreferences.PAUSE_LINGER_MS) { it.pauseLingerMs },
        BackupLongField(AodRenderPreferences.AOD_ROTATION_SETTLE_MS) { it.aodRotationSettleMs }
    )

    internal val stringFields = listOf(
        BackupStringField(AodRenderPreferences.ALIGNMENT) { it.alignment },
        BackupStringField(AodRenderPreferences.SECONDARY) { it.secondaryMode },
        BackupStringField(AodRenderPreferences.OVERFLOW) { it.overflowMode },
        BackupStringField(AodRenderPreferences.METADATA_VISIBLE) { it.metadataVisible },
        BackupStringField(AodRenderPreferences.METADATA_ANCHOR) { it.metadataAnchor },
        BackupStringField(AodRenderPreferences.WEIGHT) { it.weight },
        BackupStringField(AodRenderPreferences.TEXT_SIZE) { it.textSize },
        BackupStringField(AodRenderPreferences.FONT_FAMILY) { it.fontFamily },
        BackupStringField(AodRenderPreferences.ANIMATION) { it.animation },
        BackupStringField(AodRenderPreferences.GLOW) { it.glow },
        BackupStringField(AodRenderPreferences.BURN_IN_PATTERN) { it.burnInPattern },
        BackupStringField(AodRenderPreferences.AOD_ROTATION_MODE) { it.aodRotationMode }
    )

    fun encode(
        preferences: AodRenderConfig,
        document: CustomizationDocument?,
        side: ConfigBackupSideSettings
    ): String {
        val root = buildJsonObject {
            put(FORMAT_KEY, FORMAT)
            put(VERSION_KEY, VERSION)
            put(PREFERENCES_KEY, buildJsonObject {
                booleanFields.forEach { put(it.key, it.read(preferences)) }
                intFields.forEach { put(it.key, it.read(preferences)) }
                floatFields.forEach { put(it.key, it.read(preferences)) }
                longFields.forEach { put(it.key, it.read(preferences)) }
                stringFields.forEach { put(it.key, it.read(preferences)) }
            })
            document?.let { put(CUSTOMIZATION_KEY, SceneCompiler.json.encodeToJsonElement(it)) }
            encodeSideSettings(side).forEach { (key, value) -> put(key, value) }
        }
        return root.toString()
    }

    /** 侧设置面只编码非 null 字段:null 字段不落键,解码端据此保持现状。 */
    private fun encodeSideSettings(side: ConfigBackupSideSettings): Map<String, JsonElement> {
        val out = mutableMapOf<String, JsonElement>()
        side.lyricSource?.let { out[LYRIC_SOURCE_KEY] = JsonPrimitive(it.name) }
        side.appUiAppearance?.let { out[APP_UI_KEY] = encodeAppUiAppearance(it) }
        side.uiLanguage?.let { out[UI_LANGUAGE_KEY] = JsonPrimitive(it.name) }
        if (side.diagnosticLogging != null || side.logRetentionDays != null ||
            side.logLevel != null
        ) {
            out[DIAGNOSTICS_KEY] = buildJsonObject {
                side.diagnosticLogging?.let { put(DIAGNOSTIC_LOGGING_KEY, it) }
                side.logRetentionDays?.let { put(LOG_RETENTION_DAYS_KEY, it) }
                side.logLevel?.let { put(LOG_LEVEL_KEY, it.wire) }
            }
        }
        side.pluginSettings?.let { plugins ->
            out[PLUGIN_SETTINGS_KEY] = buildJsonObject {
                plugins.forEach { (pluginId, values) ->
                    // 防御:插件 id 参与 shared_prefs 文件名,越界 id 一律不落盘。
                    if (!isValidPluginId(pluginId)) return@forEach
                    put(pluginId, buildJsonObject {
                        values.forEach { (key, value) ->
                            if (key.isEmpty()) return@forEach
                            encodePluginValue(value)?.let { put(key, it) }
                        }
                    })
                }
            }
        }
        return out
    }

    private fun encodeAppUiAppearance(appearance: AppUiAppearance): JsonElement =
        buildJsonObject {
            put(KEY_THEME_MODE, appearance.themeMode.name)
            put(KEY_THEME_COLOR_MODE, appearance.themeColorMode.name)
            put(KEY_THEME_COLOR_ARGB, appearance.themeColorArgb)
            put(KEY_HAS_BACKGROUND_IMAGE, appearance.hasBackgroundImage)
            put(KEY_BACKGROUND_IMAGE_MTIME, appearance.backgroundImageMtime)
            put(KEY_BACKGROUND_DIM_PERCENT, appearance.backgroundDimPercent)
            put(KEY_BACKGROUND_BLUR_PERCENT, appearance.backgroundBlurPercent)
            appearance.controlColorArgb?.let { put(KEY_CONTROL_COLOR_ARGB, it) }
            put(KEY_CONTROL_OPACITY_PERCENT, appearance.controlOpacityPercent)
            appearance.textColorArgb?.let { put(KEY_TEXT_COLOR_ARGB, it) }
            put(KEY_FONT_FAMILY, appearance.fontFamily)
            put(KEY_SYSTEM_BAR_ICONS, appearance.systemBarIcons.name)
        }

    /**
     * 插件设置值的备份编码。SharedPreferences 原生值只有六种,其中三种数字在 JSON 里
     * 同形(Int/Long/Float 都是 number),必须带类型标签,否则导入时猜类型会复现
     * keepAwakeDurationMs 那类 ClassCastException。Boolean/String 直接用 JSON 原生形,
     * Set<String> 用字符串数组;不支持的类型整体丢弃该键。
     */
    private fun encodePluginValue(value: Any?): JsonElement? = when {
        value is Boolean -> JsonPrimitive(value)
        value is String -> JsonPrimitive(value)
        value is Int -> buildJsonObject { put(PLUGIN_VALUE_TAG_INT, value) }
        value is Long -> buildJsonObject { put(PLUGIN_VALUE_TAG_LONG, value) }
        value is Float && value.isFinite() -> buildJsonObject {
            put(PLUGIN_VALUE_TAG_FLOAT, value)
        }
        value is Set<*> -> {
            val strings = value.filterIsInstance<String>()
            if (strings.size == value.size) JsonArray(strings.map { JsonPrimitive(it) }) else null
        }
        else -> null
    }

    /** [encodePluginValue] 的逆;形状不符返回 null,调用方丢弃该键、不写入。 */
    private fun decodePluginValue(element: JsonElement): Any? = when {
        element is JsonPrimitive && element.isString -> element.content
        element is JsonPrimitive && element.booleanOrNull != null -> element.booleanOrNull
        element is JsonArray -> {
            val strings = element.map { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            }
            strings.toSet()
        }
        element is JsonObject && element.size == 1 ->
            decodeTaggedNumber(element.entries.first())
        else -> null
    }

    private fun decodeTaggedNumber(entry: Map.Entry<String, JsonElement>): Any? {
        val primitive = entry.value as? JsonPrimitive ?: return null
        if (primitive.isString) return null
        return when (entry.key) {
            PLUGIN_VALUE_TAG_INT -> primitive.intOrNull
            PLUGIN_VALUE_TAG_LONG -> primitive.longOrNull
            PLUGIN_VALUE_TAG_FLOAT -> primitive.floatOrNull?.takeIf { it.isFinite() }
            else -> null
        }
    }

    fun decode(payload: ByteArray): ConfigBackupDecodeResult {
        // 在任何解析花费之前先拒绝超大载荷。
        if (payload.size > MAX_BYTES) return ConfigBackupDecodeResult.Rejected(
            ConfigBackupRejection.OVERSIZE
        )
        val envelope = runCatching {
            SceneCompiler.json.parseToJsonElement(payload.decodeToString())
        }.getOrNull() as? JsonObject ?: return ConfigBackupDecodeResult.Rejected(
            ConfigBackupRejection.MALFORMED
        )

        val format = (envelope[FORMAT_KEY] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (format != FORMAT) {
            return ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.BAD_FORMAT)
        }
        val version = (envelope[VERSION_KEY] as? JsonPrimitive)?.intOrNull
        if (version == null || version < 1 || version > VERSION) {
            return ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.BAD_VERSION)
        }

        val stored = envelope[PREFERENCES_KEY] as? JsonObject ?: JsonObject(emptyMap())
        val preferences = decodePreferences(stored)

        val document = when (val rawCustomization = envelope[CUSTOMIZATION_KEY]) {
            null -> null
            is JsonObject -> SceneCompiler.decodeDocument(rawCustomization.toString())
                ?: return ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.MALFORMED)
            else -> return ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.MALFORMED)
        }

        return ConfigBackupDecodeResult.Success(
            preferences,
            document,
            decodeSideSettings(envelope)
        )
    }

    /**
     * 侧设置面解码。键缺省 → null(保持现状);键在但取值无法识别 → 回落该键默认值;
     * 插件设置为覆盖语义,只写入载荷里列出的键。
     */
    private fun decodeSideSettings(envelope: JsonObject): ConfigBackupSideSettings {
        val lyricName = (envelope[LYRIC_SOURCE_KEY] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        val languageName = (envelope[UI_LANGUAGE_KEY] as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        val diagnostics = envelope[DIAGNOSTICS_KEY] as? JsonObject
        return ConfigBackupSideSettings(
            lyricSource = lyricName?.let { name ->
                LyricSource.entries.firstOrNull { it.name == name } ?: LyricSource.SPICY
            },
            appUiAppearance = (envelope[APP_UI_KEY] as? JsonObject)
                ?.let { decodeAppUiAppearance(it) },
            uiLanguage = languageName?.let { name ->
                UiLanguage.entries.firstOrNull { it.name == name } ?: UiLanguage.SYSTEM
            },
            diagnosticLogging = diagnostics?.let { it.boolean(DIAGNOSTIC_LOGGING_KEY) ?: false },
            logRetentionDays = diagnostics?.let {
                normalizeLogRetentionDays(
                    it.int(LOG_RETENTION_DAYS_KEY) ?: DiagnosticTraceFile.DEFAULT_RETENTION_DAYS
                )
            },
            logLevel = diagnostics?.string(LOG_LEVEL_KEY)?.let(::normalizeDiagnosticLogLevel),
            pluginSettings = decodePluginSettings(envelope[PLUGIN_SETTINGS_KEY])
        )
    }

    private fun decodeAppUiAppearance(stored: JsonObject): AppUiAppearance {
        // 只搬运形状正确的键;错形键不进 map,由 normalizeAppUiAppearance 回落默认。
        val values = mutableMapOf<String, Any?>()
        stored.string(KEY_THEME_MODE)?.let { values[KEY_THEME_MODE] = it }
        stored.string(KEY_THEME_COLOR_MODE)?.let { values[KEY_THEME_COLOR_MODE] = it }
        stored.int(KEY_THEME_COLOR_ARGB)?.let { values[KEY_THEME_COLOR_ARGB] = it }
        stored.boolean(KEY_HAS_BACKGROUND_IMAGE)?.let { values[KEY_HAS_BACKGROUND_IMAGE] = it }
        stored.long(KEY_BACKGROUND_IMAGE_MTIME)?.let { values[KEY_BACKGROUND_IMAGE_MTIME] = it }
        stored.int(KEY_BACKGROUND_DIM_PERCENT)?.let { values[KEY_BACKGROUND_DIM_PERCENT] = it }
        stored.int(KEY_BACKGROUND_BLUR_PERCENT)?.let { values[KEY_BACKGROUND_BLUR_PERCENT] = it }
        stored.int(KEY_CONTROL_COLOR_ARGB)?.let { values[KEY_CONTROL_COLOR_ARGB] = it }
        stored.int(KEY_CONTROL_OPACITY_PERCENT)?.let { values[KEY_CONTROL_OPACITY_PERCENT] = it }
        stored.int(KEY_TEXT_COLOR_ARGB)?.let { values[KEY_TEXT_COLOR_ARGB] = it }
        stored.string(KEY_FONT_FAMILY)?.let { values[KEY_FONT_FAMILY] = it }
        stored.string(KEY_SYSTEM_BAR_ICONS)?.let { values[KEY_SYSTEM_BAR_ICONS] = it }
        return normalizeAppUiAppearance(values)
    }

    private fun decodePluginSettings(raw: JsonElement?): Map<String, Map<String, Any?>>? {
        val plugins = raw as? JsonObject ?: return null
        val out = mutableMapOf<String, Map<String, Any?>>()
        plugins.forEach { (pluginId, element) ->
            if (!isValidPluginId(pluginId)) return@forEach
            val settings = element as? JsonObject ?: return@forEach
            val values = mutableMapOf<String, Any?>()
            settings.forEach { (key, value) ->
                if (key.isEmpty()) return@forEach
                decodePluginValue(value)?.let { values[key] = it }
            }
            out[pluginId] = values
        }
        return out
    }

    private fun decodePreferences(stored: JsonObject): AodRenderConfig = AodRenderConfig(
        aodEnabled = stored.boolean(AodRenderPreferences.AOD_ENABLED) ?: DEFAULTS.aodEnabled,
        lockscreenEnabled = stored.boolean(AodRenderPreferences.LOCKSCREEN_ENABLED)
            ?: DEFAULTS.lockscreenEnabled,
        alignment = stored.string(AodRenderPreferences.ALIGNMENT) ?: DEFAULTS.alignment,
        secondaryMode = stored.string(AodRenderPreferences.SECONDARY) ?: DEFAULTS.secondaryMode,
        overflowMode = stored.string(AodRenderPreferences.OVERFLOW) ?: DEFAULTS.overflowMode,
        metadataVisible = stored.string(AodRenderPreferences.METADATA_VISIBLE)
            ?: DEFAULTS.metadataVisible,
        metadataAnchor = stored.string(AodRenderPreferences.METADATA_ANCHOR)
            ?: DEFAULTS.metadataAnchor,
        metadataSizePercent = (stored.int(AodRenderPreferences.METADATA_SIZE)
            ?: DEFAULTS.metadataSizePercent).coerceIn(50, 200),
        weight = stored.string(AodRenderPreferences.WEIGHT) ?: DEFAULTS.weight,
        textSize = stored.string(AodRenderPreferences.TEXT_SIZE) ?: DEFAULTS.textSize,
        textSizeCustom = (stored.int(AodRenderPreferences.TEXT_SIZE_CUSTOM)
            ?: DEFAULTS.textSizeCustom).coerceIn(50, 200),
        fontFamily = stored.string(AodRenderPreferences.FONT_FAMILY) ?: DEFAULTS.fontFamily,
        animation = stored.string(AodRenderPreferences.ANIMATION) ?: DEFAULTS.animation,
        glow = stored.string(AodRenderPreferences.GLOW) ?: DEFAULTS.glow,
        adaptiveSectioning = stored.boolean(AodRenderPreferences.ADAPTIVE_SECTIONING)
            ?: DEFAULTS.adaptiveSectioning,
        keepAwake = stored.boolean(AodRenderPreferences.KEEP_AWAKE) ?: DEFAULTS.keepAwake,
        aodClockFollow = stored.boolean(AodRenderPreferences.AOD_CLOCK_FOLLOW)
            ?: DEFAULTS.aodClockFollow,
        aodClockYOffset = stored.int(AodRenderPreferences.AOD_CLOCK_Y_OFFSET)
            ?.let(::normalizeAodClockYOffset) ?: DEFAULTS.aodClockYOffset,
        keepAwakeUnsynced = stored.boolean(AodRenderPreferences.KEEP_AWAKE_UNSYNCED)
            ?: DEFAULTS.keepAwakeUnsynced,
        keepAwakeDurationMs = stored.long(AodRenderPreferences.KEEP_AWAKE_DURATION_MS)
            ?: DEFAULTS.keepAwakeDurationMs,
        experimentalPositionFollowing = stored.boolean(
            AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING
        ) ?: DEFAULTS.experimentalPositionFollowing,
        burnInPattern = stored.string(AodRenderPreferences.BURN_IN_PATTERN)
            ?: DEFAULTS.burnInPattern,
        burnInIntervalMs = stored.long(AodRenderPreferences.BURN_IN_INTERVAL_MS)
            ?: DEFAULTS.burnInIntervalMs,
        pauseLingerMs = stored.long(AodRenderPreferences.PAUSE_LINGER_MS)
            ?: DEFAULTS.pauseLingerMs,
        pauseShowContent = stored.boolean(AodRenderPreferences.PAUSE_SHOW_CONTENT)
            ?: DEFAULTS.pauseShowContent,
        lockscreenKeepAwake = stored.boolean(AodRenderPreferences.LOCKSCREEN_KEEP_AWAKE)
            ?: DEFAULTS.lockscreenKeepAwake,
        raiseToAod = stored.boolean(AodRenderPreferences.RAISE_TO_AOD) ?: DEFAULTS.raiseToAod,
        suppressLockscreenEditorLongPress = stored.boolean(
            AodRenderPreferences.SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS
        ) ?: DEFAULTS.suppressLockscreenEditorLongPress,
        experimentalMode = stored.boolean(AodRenderPreferences.EXPERIMENTAL_MODE)
            ?: DEFAULTS.experimentalMode,
        persistentNotification = stored.boolean(AodRenderPreferences.PERSISTENT_NOTIFICATION)
            ?: DEFAULTS.persistentNotification,
        hideBackgroundCard = stored.boolean(AodRenderPreferences.HIDE_BACKGROUND_CARD)
            ?: DEFAULTS.hideBackgroundCard,
        hideLauncherIcon = stored.boolean(AodRenderPreferences.HIDE_LAUNCHER_ICON)
            ?: DEFAULTS.hideLauncherIcon,
        aodBrightnessBoost = stored.boolean(AodRenderPreferences.AOD_BRIGHTNESS_BOOST)
            ?: DEFAULTS.aodBrightnessBoost,
        pluginProcessingEnabled = stored.boolean(AodRenderPreferences.PLUGIN_PROCESSING_ENABLED)
            ?: DEFAULTS.pluginProcessingEnabled,
        suppressStockAodContent = stored.boolean(
            AodRenderPreferences.SUPPRESS_STOCK_AOD_CONTENT
        ) ?: DEFAULTS.suppressStockAodContent,
        aodRotateWithDevice = stored.boolean(AodRenderPreferences.AOD_ROTATE_WITH_DEVICE)
            ?: DEFAULTS.aodRotateWithDevice,
        aodRotationMode = stored.string(AodRenderPreferences.AOD_ROTATION_MODE)
            ?.let(::normalizeAodRotationMode)
            ?: if (stored.boolean(AodRenderPreferences.AOD_ROTATE_WITH_DEVICE) == true) {
                AOD_ROTATION_MODE_AUTO
            } else {
                DEFAULTS.aodRotationMode
            },
        aodRotationSettleMs = stored.long(AodRenderPreferences.AOD_ROTATION_SETTLE_MS)
            ?.let(::normalizeAodRotationSettleMs) ?: DEFAULTS.aodRotationSettleMs,
        aodCanvasAnchorLandscape = stored.float(
            AodRenderPreferences.AOD_CANVAS_ANCHOR_LANDSCAPE
        )?.let(::normalizeAodCanvasAnchor) ?: DEFAULTS.aodCanvasAnchorLandscape,
        aodLandscapeTextScale = stored.float(AodRenderPreferences.AOD_LANDSCAPE_TEXT_SCALE)
            ?.let(::normalizeAodLandscapeTextScale) ?: DEFAULTS.aodLandscapeTextScale,
        aodLandscapeHideStock = stored.boolean(AodRenderPreferences.AOD_LANDSCAPE_HIDE_STOCK)
            ?: DEFAULTS.aodLandscapeHideStock,
        aodLandscapeFullscreen = stored.boolean(AodRenderPreferences.AOD_LANDSCAPE_FULLSCREEN)
            ?: DEFAULTS.aodLandscapeFullscreen,
        aodCanvasPaddingPortraitXPercent = stored.float(
            AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_X_PERCENT
        )?.let(::normalizeAodCanvasPaddingPercent) ?: DEFAULTS.aodCanvasPaddingPortraitXPercent,
        aodCanvasPaddingPortraitYPercent = stored.float(
            AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_Y_PERCENT
        )?.let(::normalizeAodCanvasPaddingPercent) ?: DEFAULTS.aodCanvasPaddingPortraitYPercent,
        aodCanvasPaddingLandscapeXPercent = stored.float(
            AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_X_PERCENT
        )?.let(::normalizeAodCanvasPaddingPercent) ?: DEFAULTS.aodCanvasPaddingLandscapeXPercent,
        aodCanvasPaddingLandscapeYPercent = stored.float(
            AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_Y_PERCENT
        )?.let(::normalizeAodCanvasPaddingPercent) ?: DEFAULTS.aodCanvasPaddingLandscapeYPercent,
        aodBrightnessOverride = stored.boolean(AodRenderPreferences.AOD_BRIGHTNESS_OVERRIDE)
            ?: DEFAULTS.aodBrightnessOverride,
        aodBrightnessLevel = (stored.int(AodRenderPreferences.AOD_BRIGHTNESS_LEVEL)
            ?: DEFAULTS.aodBrightnessLevel).coerceIn(MIN_AOD_BRIGHTNESS, MAX_AOD_BRIGHTNESS),
        aodDebugShowCanvasFrame = stored.boolean(AodRenderPreferences.AOD_DEBUG_SHOW_CANVAS_FRAME)
            ?: DEFAULTS.aodDebugShowCanvasFrame,
        aodRefreshRateCap = normalizeAodRefreshRateCap(
            stored.int(AodRenderPreferences.AOD_REFRESH_RATE_CAP) ?: DEFAULTS.aodRefreshRateCap
        )
    )

    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.float(key: String): Float? =
        (this[key] as? JsonPrimitive)?.floatOrNull

    /** 字符串 key 只接受 JSON 字符串;旧实现把数字字符串折叠成数字。 */
    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private const val FORMAT_KEY = "format"
    private const val VERSION_KEY = "version"
    private const val PREFERENCES_KEY = "renderPreferences"
    private const val CUSTOMIZATION_KEY = "customization"
    private const val LYRIC_SOURCE_KEY = "lyricSource"
    private const val APP_UI_KEY = "appUiAppearance"
    private const val UI_LANGUAGE_KEY = "uiLanguage"
    private const val DIAGNOSTICS_KEY = "diagnostics"
    private const val PLUGIN_SETTINGS_KEY = "pluginSettings"
    private const val DIAGNOSTIC_LOGGING_KEY = "diagnostic_logging"
    private const val LOG_RETENTION_DAYS_KEY = "log_retention_days"
    private const val LOG_LEVEL_KEY = "log_level"

    /** 插件设置数字值的类型标签(见 [encodePluginValue])。 */
    private const val PLUGIN_VALUE_TAG_INT = "i"
    private const val PLUGIN_VALUE_TAG_LONG = "l"
    private const val PLUGIN_VALUE_TAG_FLOAT = "f"
}
