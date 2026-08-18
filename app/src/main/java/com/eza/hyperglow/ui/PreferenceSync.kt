package com.eza.hyperglow.ui

import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.setDiagnosticLogging
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.AodStateBridge
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.root.projection.currentProcessUserId

/** 偏好设置写入与运行时同步:开关落盘、发布配置到 SystemUI、调色板预设等。 */

internal fun updateCustomizationSurfaceEnabled(
    context: android.content.Context,
    surface: String,
    enabled: Boolean
): Boolean {
    val document = CustomizationRepository.loadDocument(context)
    val profiles = document.profiles.toMutableMap()
    profiles[surface] = (profiles[surface] ?: SurfaceProfile()).copy(enabled = enabled)
    if (!CustomizationRepository.saveDocument(context, document.copy(profiles = profiles))) {
        return false
    }
    syncCustomizationRuntime(context, CustomizationRepository.loadDocument(context))
    return true
}

internal fun withMetadataVisible(profile: SurfaceProfile, visible: Boolean): SurfaceProfile {
    val widgets = profile.widgets.filterNot { it.type == "metadata" }.toMutableList()
    if (visible) {
        widgets += com.eza.hyperglow.customization.WidgetSpec(
            "metadata",
            optional = true
        )
    }
    return profile.copy(metadataVisible = visible, widgets = widgets)
}

private val SEMANTIC_PALETTE_KEYS = setOf(
    "primaryText",
    "secondaryText",
    "metadataText",
    "sungText",
    "unsungText",
    "glow",
    "accent",
    "surfaceScrim"
)

internal fun palettePreset(name: String): Map<String, String> =
    if (name == "dimmed") SEMANTIC_PALETTE_KEYS.associateWith { "dimmed" } else emptyMap()

internal fun palettePresetName(palette: Map<String, String>): String =
    if (palette.isNotEmpty() && palette.values.all { it == "dimmed" }) "dimmed" else "default"

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

internal fun updateDiagnosticLogging(
    context: android.content.Context,
    enabled: Boolean
): Boolean = setDiagnosticLogging(context, enabled)
