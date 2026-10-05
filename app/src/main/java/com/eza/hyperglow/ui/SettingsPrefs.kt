package com.eza.hyperglow.ui

import androidx.compose.foundation.layout.offset
import com.eza.hyperglow.DiagnosticLoggingPreferences
import com.eza.hyperglow.DiagnosticTraceFile
import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.setDiagnosticLogging
import com.eza.hyperglow.setDiagnosticLogLevel
import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.AodStateBridge
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.plugin.PluginInstaller
import com.eza.hyperglow.plugin.PluginRuntime
import com.eza.hyperglow.plugin.PluginSettingsStore
import com.eza.hyperglow.plugin.isValidPluginId
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

/** 与 [ConfigBackupCodec.MAX_BYTES] 同源,避免导入预检与编解码上限漂移。 */
internal const val MAX_CONFIG_FILE_BYTES = ConfigBackupCodec.MAX_BYTES

internal fun exportAllConfig(context: android.content.Context): String =
    ConfigBackupCodec.encode(
        AodRenderPreferences.read(context),
        CustomizationRepository.loadDocument(context),
        collectSideSettings(context)
    )

internal fun importAllConfig(context: android.content.Context, raw: String): Boolean {
    // 以当前生效配置为基底:载荷缺省的键保持现状(旧备份没有的新键不被清成默认值)。
    val result = ConfigBackupCodec.decode(raw.toByteArray(), AodRenderPreferences.read(context))
    if (result !is ConfigBackupDecodeResult.Success) return false
    if (!configBackupWritePreferences(context, result.preferences)) return false
    val document = result.customizationDocument
    if (document != null && !CustomizationRepository.saveDocument(context, document)) return false
    applySideSettings(context, result.sideSettings)
    return true
}

/** 补充设置面的导出采集;每项都取当前生效值,导出文件自成完整状态。 */
private fun collectSideSettings(context: android.content.Context): ConfigBackupSideSettings =
    ConfigBackupSideSettings(
        lyricSource = AodRenderPreferences.readLyricSource(context),
        appUiAppearance = loadAppUiAppearance(context),
        uiLanguage = currentUiLanguage(context),
        predictiveBack = AppNavigationPreferences.readPredictiveBack(context),
        backTriggerPercent = AppNavigationPreferences.readBackTriggerPercent(context),
        diagnosticLogging = DiagnosticLoggingPreferences.read(context),
        logRetentionDays = DiagnosticLoggingPreferences.readRetentionDays(context),
        logLevel = DiagnosticLoggingPreferences.readLevel(context),
        pluginSettings = collectPluginSettingsBackup(context)
    )

/**
 * 应用补充设置面。各字段为 null 表示旧备份未含该键,保持现状;
 * 非 null 则整体应用。资源文件(背景图)不随备份流动,见内联注释。
 */
private fun applySideSettings(context: android.content.Context, side: ConfigBackupSideSettings) {
    side.lyricSource?.let { AodRenderPreferences.writeLyricSource(context, it) }
    side.appUiAppearance?.let { appearance ->
        // 背景图片是资源文件、不进备份 JSON:目标设备没有该文件时不能留下
        // "有背景"的悬空标记,否则各屏透明容器透出空底。
        val restored =
            if (appearance.hasBackgroundImage && !appBackgroundImageFile(context).isFile) {
                appearance.copy(hasBackgroundImage = false, backgroundImageMtime = 0L)
            } else {
                appearance
            }
        updateAppUiAppearance(context, restored)
    }
    side.uiLanguage?.let { setUiLanguage(context, it) }
    // 导航设置即时写盘;界面侧在 Activity 重建后读取新值(与导入提示「部分设置需重启生效」一致)。
    side.predictiveBack?.let { AppNavigationPreferences.writePredictiveBack(context, it) }
    side.backTriggerPercent?.let {
        AppNavigationPreferences.writeBackTriggerPercent(context, it)
    }
    side.diagnosticLogging?.let { updateDiagnosticLogging(context, it) }
    side.logRetentionDays?.let { updateLogRetentionDays(context, it) }
    side.logLevel?.let { updateLogLevel(context, it) }
    side.pluginSettings?.let { applyPluginSettingsBackup(context, it) }
}

/** 导出采集:已安装插件的全部设置,manifest 标记 `backup=false` 的键两头都不流动。 */
private fun collectPluginSettingsBackup(
    context: android.content.Context
): Map<String, Map<String, Any?>> =
    PluginInstaller.installed(context).associate { manifest ->
        val excluded = manifest.settings.filterNot { it.backup }.mapTo(HashSet()) { it.key }
        manifest.id to PluginSettingsStore.readAll(context, manifest.id)
            .filterKeys { it !in excluded }
    }

/**
 * 导入写回:覆盖语义,只写载荷里列出的键;插件未安装也照写,后装插件即拾取。
 * 写入后按设置页同路径发 onConfigChanged,让已加载插件即时刷新快照。
 */
private fun applyPluginSettingsBackup(
    context: android.content.Context,
    settings: Map<String, Map<String, Any?>>
) {
    val manifests = PluginInstaller.installed(context).associateBy { it.id }
    settings.forEach { (pluginId, values) ->
        // 编解码器已按白名单过滤,这里再挡一道:插件 id 参与 prefs 文件名。
        if (!isValidPluginId(pluginId)) return@forEach
        var changed = false
        values.forEach { (key, value) ->
            if (value == null) return@forEach
            val declared = manifests[pluginId]?.settings?.firstOrNull { it.key == key }
            if (declared != null && !declared.backup) return@forEach
            PluginSettingsStore.writeValue(context, pluginId, key, value)
            changed = true
        }
        if (changed) PluginRuntime.notifyConfigChanged(pluginId)
    }
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

/**
 * 「视频/非音乐音频不显示歌词」开关(应用级偏好):识别当前音频源,视频/播客等非音乐
 * 来源播放期间不进入歌词显示链(见 producer/MediaSourcePolicy)。生产者懒读该偏好,
 * 切换立即生效;下发运行时配置保持与其余设置一致的广播行为。
 */
internal fun updateFilterNonMusicSources(
    context: android.content.Context,
    enabled: Boolean
): Boolean {
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putBoolean(AodRenderPreferences.FILTER_NON_MUSIC_SOURCES, enabled)
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

internal fun updateAodLandscapeFullscreenSafeMarginPercent(
    context: android.content.Context,
    value: Float
): Boolean {
    val normalized = com.eza.hyperglow.aod.normalizeAodFullscreenSafeMarginPercent(value)
    val saved = context.getSharedPreferences(AodRenderPreferences.PREFS, 0).edit()
        .putFloat(AodRenderPreferences.AOD_LANDSCAPE_FULLSCREEN_SAFE_MARGIN_PERCENT, normalized)
        .commit()
    if (!saved) return false
    publishRuntimeConfiguration(context)
    return true
}

internal fun updateDiagnosticLogging(
    context: android.content.Context,
    enabled: Boolean
): Boolean = setDiagnosticLogging(context, enabled)

internal fun updateLogRetentionDays(context: android.content.Context, days: Int): Boolean {
    if (!DiagnosticLoggingPreferences.writeRetentionDays(context, days)) return false
    DiagnosticTraceFile.setRetentionDays(DiagnosticLoggingPreferences.readRetentionDays(context))
    DiagnosticTraceFile.prune(context.applicationContext.filesDir)
    return true
}

internal fun updateLogLevel(
    context: android.content.Context,
    level: com.eza.hyperglow.DiagnosticLogLevel
): Boolean = setDiagnosticLogLevel(context, level)

/** 「导出日志」:两个镜像文件里全部尚未清理的行,轮转文件在前。 */
internal fun readDiagnosticLogsForExport(context: android.content.Context): String =
    DiagnosticTraceFile.readAll(context.applicationContext.filesDir)

internal fun clearDiagnosticLogs(context: android.content.Context): Boolean =
    DiagnosticTraceFile.clear(context.applicationContext.filesDir)
