package com.eza.hyperglow.ui

import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.AodRenderConfig.Companion.DEFAULTS
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.MAX_AOD_BRIGHTNESS
import com.eza.hyperglow.aod.MIN_AOD_BRIGHTNESS
import com.eza.hyperglow.aod.normalizeAodCanvasAnchor
import com.eza.hyperglow.aod.normalizeAodCanvasPaddingPercent
import com.eza.hyperglow.aod.normalizeAodClockYOffset
import com.eza.hyperglow.aod.normalizeAodLandscapeTextScale
import com.eza.hyperglow.aod.normalizeAodRotationMode
import com.eza.hyperglow.aod.normalizeAodRotationSettleMs
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_AUTO
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
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
 */
internal sealed interface ConfigBackupDecodeResult {
    data class Success(
        val preferences: AodRenderConfig,
        val customizationDocument: CustomizationDocument?
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

internal data class BackupBooleanField(val key: String, val read: (AodRenderConfig) -> Boolean)

internal data class BackupFloatField(val key: String, val read: (AodRenderConfig) -> Float)

internal data class BackupIntField(val key: String, val read: (AodRenderConfig) -> Int)

internal data class BackupLongField(val key: String, val read: (AodRenderConfig) -> Long)

internal data class BackupStringField(val key: String, val read: (AodRenderConfig) -> String)

internal object ConfigBackupCodec {
    const val FORMAT = "hyperglow-config-backup"
    const val VERSION = 1
    const val MAX_BYTES = 512 * 1024

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
        }
    )

    internal val intFields = listOf(
        BackupIntField(AodRenderPreferences.TEXT_SIZE_CUSTOM) { it.textSizeCustom },
        BackupIntField(AodRenderPreferences.METADATA_SIZE) { it.metadataSizePercent },
        BackupIntField(AodRenderPreferences.AOD_BRIGHTNESS_LEVEL) { it.aodBrightnessLevel },
        BackupIntField(AodRenderPreferences.AOD_CLOCK_Y_OFFSET) { it.aodClockYOffset }
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

    fun encode(preferences: AodRenderConfig, document: CustomizationDocument?): String {
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
        }
        return root.toString()
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
        if ((envelope[VERSION_KEY] as? JsonPrimitive)?.intOrNull != VERSION) {
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

        return ConfigBackupDecodeResult.Success(preferences, document)
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
            ?: DEFAULTS.aodBrightnessLevel).coerceIn(MIN_AOD_BRIGHTNESS, MAX_AOD_BRIGHTNESS)
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
}