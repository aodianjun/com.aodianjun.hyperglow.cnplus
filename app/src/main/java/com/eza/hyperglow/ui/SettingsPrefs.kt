package com.eza.hyperglow.ui

import androidx.compose.foundation.layout.offset
import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.setDiagnosticLogging
import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.AodStateBridge
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.root.projection.currentProcessUserId

private fun applyDocumentToLegacyPreferences(
    context: android.content.Context,
    document: com.eza.hyperglow.customization.CustomizationDocument
) {
    val aod = document.profiles[SceneCompiler.SURFACE_AOD] ?: SceneCompiler.safeAodProfile()
    val lockscreen = document.profiles[SceneCompiler.SURFACE_LOCKSCREEN]
        ?: SceneCompiler.safeLockscreenProfile()
    context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.AOD_ENABLED, aod.enabled)
        .putBoolean(AodRenderPreferences.LOCKSCREEN_ENABLED, lockscreen.enabled)
        .putString(AodRenderPreferences.ALIGNMENT, aod.alignment)
        .putString(AodRenderPreferences.SECONDARY, aod.secondaryMode)
        .putString(AodRenderPreferences.OVERFLOW, aod.overflow)
        .putString(
            AodRenderPreferences.METADATA_VISIBLE,
            if (aod.metadataVisible) "show" else "hide"
        )
        .putString(AodRenderPreferences.METADATA_ANCHOR, aod.metadataAnchor)
        .putInt(AodRenderPreferences.METADATA_SIZE, aod.metadataSizePercent.coerceIn(50, 200))
        .putString(AodRenderPreferences.WEIGHT, aod.weight)
        .putString(AodRenderPreferences.TEXT_SIZE, aod.textSize)
        .putInt(AodRenderPreferences.TEXT_SIZE_CUSTOM, aod.textSizeCustom)
        .putString(AodRenderPreferences.FONT_FAMILY, aod.fontFamily)
        .putString(AodRenderPreferences.ANIMATION, aod.animation)
        .putString(AodRenderPreferences.GLOW, aod.glow)
        .putBoolean(AodRenderPreferences.ADAPTIVE_SECTIONING, aod.adaptiveSectioning)
        .commit()
}

internal fun syncCustomizationRuntime(
    context: android.content.Context,
    document: com.eza.hyperglow.customization.CustomizationDocument
) {
    applyDocumentToLegacyPreferences(context, document)
    publishRuntimeConfiguration(context)
}

internal fun updateLockscreenKeepAwake(
    context: android.content.Context,
    enabled: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.LOCKSCREEN_KEEP_AWAKE, enabled)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateRaiseToAod(
    context: android.content.Context,
    enabled: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.RAISE_TO_AOD, enabled)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateAodClockFollow(
    context: android.content.Context,
    enabled: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.AOD_CLOCK_FOLLOW, enabled)
        .commit()
    if (!saved) return false
    // 重新编译并分发配置,让 hook 端立即采用新的时钟跟随模式。
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateLockscreenEditorLongPress(
    context: android.content.Context,
    enabled: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.SUPPRESS_LOCKSCREEN_EDITOR_LONG_PRESS, enabled)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateExperimentalMode(
    context: android.content.Context,
    enabled: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.EXPERIMENTAL_MODE, enabled)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun applyHideFromRecents(context: android.content.Context, exclude: Boolean) {
    // 运行时把本应用的任务从最近任务列表隐藏/恢复,无需重启 Activity。
    runCatching {
        context.getSystemService(android.app.ActivityManager::class.java)
            ?.appTasks
            ?.forEach { it.setExcludeFromRecents(exclude) }
    }
}

internal fun applyHideLauncherIcon(context: android.content.Context, hide: Boolean) {
    // 只禁用/启用 LAUNCHER alias 组件来隐藏/恢复桌面图标。
    // 关键:不能禁用 MainActivity 本身,否则 LSPosed 管理器将无法再启动本应用
    // (LSPosed 通过 MainActivity 声明的 MODULE_SETTINGS category 作为模块入口)。
    runCatching {
        val component = android.content.ComponentName(
            context,
            "com.eza.hyperglow.ui.MainActivityAlias"
        )
        context.packageManager.setComponentEnabledSetting(
            component,
            if (hide) {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            },
            android.content.pm.PackageManager.DONT_KILL_APP
        )
    }
}

internal fun publishRuntimeConfiguration(context: android.content.Context) {
    AodStateBridge.publishConfiguration(
        RuntimeCustomization.loadCompiled(context),
        currentProcessUserId(),
        experimentalMode = AodRenderPreferences.read(context).experimentalMode
    )
}

internal const val MAX_CONFIG_FILE_BYTES = 512 * 1024

internal fun exportAllConfig(context: android.content.Context): String =
    ConfigBackupCodec.encode(
        AodRenderPreferences.read(context),
        CustomizationRepository.loadDocument(context)
    )

internal fun importAllConfig(context: android.content.Context, raw: String): Boolean {
    val result = ConfigBackupCodec.decode(raw.toByteArray())
    if (result !is ConfigBackupDecodeResult.Success) return false
    if (!configBackupWritePreferences(context, result.preferences)) return false
    val document = result.customizationDocument
    return document == null || CustomizationRepository.saveDocument(context, document)
}

private fun configBackupWritePreferences(
    context: android.content.Context,
    config: AodRenderConfig
): Boolean {
    val editor = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
    ConfigBackupCodec.booleanFields.forEach { editor.putBoolean(it.key, it.read(config)) }
    ConfigBackupCodec.intFields.forEach { editor.putInt(it.key, it.read(config)) }
    ConfigBackupCodec.floatFields.forEach { editor.putFloat(it.key, it.read(config)) }
    ConfigBackupCodec.longFields.forEach { editor.putLong(it.key, it.read(config)) }
    ConfigBackupCodec.stringFields.forEach { editor.putString(it.key, it.read(config)) }
    return editor.commit()
}

internal fun resetToDefaults(context: android.content.Context): Boolean {
    val success = configBackupWritePreferences(context, AodRenderConfig.DEFAULTS)
    if (success) publishRuntimeConfiguration(context)
    return success
}

internal fun updateKeepAwakeDuration(context: android.content.Context, value: Long): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putLong(AodRenderPreferences.KEEP_AWAKE_DURATION_MS, value)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updatePauseLinger(context: android.content.Context, value: Long): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putLong(AodRenderPreferences.PAUSE_LINGER_MS, value)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updatePauseShowContent(context: android.content.Context, enabled: Boolean): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.PAUSE_SHOW_CONTENT, enabled)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateAodBrightnessBoost(context: android.content.Context, enabled: Boolean): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.AOD_BRIGHTNESS_BOOST, enabled)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateAodBrightnessOverride(
    context: android.content.Context,
    enabled: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.AOD_BRIGHTNESS_OVERRIDE, enabled)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateAodClockYOffset(
    context: android.content.Context,
    offset: Int
): Boolean {
    val clamped = com.eza.hyperglow.aod.normalizeAodClockYOffset(offset)
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putInt(AodRenderPreferences.AOD_CLOCK_Y_OFFSET, clamped)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateAodBrightnessLevel(
    context: android.content.Context,
    level: Int
): Boolean {
    val clamped = level.coerceIn(
        com.eza.hyperglow.aod.MIN_AOD_BRIGHTNESS,
        com.eza.hyperglow.aod.MAX_AOD_BRIGHTNESS
    )
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putInt(AodRenderPreferences.AOD_BRIGHTNESS_LEVEL, clamped)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateAodRefreshRateCap(
    context: android.content.Context,
    capHz: Int
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putInt(
            AodRenderPreferences.AOD_REFRESH_RATE_CAP,
            com.eza.hyperglow.aod.normalizeAodRefreshRateCap(capHz)
        )
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateAodRotationMode(
    context: android.content.Context,
    mode: String
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putString(AodRenderPreferences.AOD_ROTATION_MODE, mode)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateAodRotationSettleMs(
    context: android.content.Context,
    ms: Long
): Boolean {
    val normalized = com.eza.hyperglow.aod.normalizeAodRotationSettleMs(ms)
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putLong(AodRenderPreferences.AOD_ROTATION_SETTLE_MS, normalized)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateAodLandscapeTextScale(
    context: android.content.Context,
    value: Float
): Boolean {
    val normalized = com.eza.hyperglow.aod.normalizeAodLandscapeTextScale(value)
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putFloat(AodRenderPreferences.AOD_LANDSCAPE_TEXT_SCALE, normalized)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateAodCanvasAnchorLandscape(
    context: android.content.Context,
    value: Float
): Boolean {
    val normalized = com.eza.hyperglow.aod.normalizeAodCanvasAnchor(value)
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putFloat(AodRenderPreferences.AOD_CANVAS_ANCHOR_LANDSCAPE, normalized)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateAodCanvasPaddingPercent(
    context: android.content.Context,
    key: String,
    value: Float
): Boolean {
    val normalized = com.eza.hyperglow.aod.normalizeAodCanvasPaddingPercent(value)
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putFloat(key, normalized)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateDiagnosticLogging(
    context: android.content.Context,
    enabled: Boolean
): Boolean = setDiagnosticLogging(context, enabled)
