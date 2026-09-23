package com.eza.hyperglow.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.R
import com.eza.hyperglow.DiagnosticLoggingPreferences
import com.eza.hyperglow.root.utils.ShellUtils
import kotlinx.coroutines.launch
import com.eza.hyperglow.aod.AodLyricBridgeService
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.MAX_CANVAS_PADDING_PERCENT
import com.eza.hyperglow.aod.XiaomiCapabilityStore
import com.eza.hyperglow.aod.XiaomiRuntimeSupportState
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiProfileState
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun HomeScreen(
    showRestartResult: (Boolean) -> Unit,
    selectedTabName: String,
    onSelectTab: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLyricLayout: (String) -> Unit,
    onOpenPlugins: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRestartDialog by remember { mutableStateOf(false) }
    var restartSystemUiTarget by remember { mutableStateOf(true) }
    var restartAodTarget by remember { mutableStateOf(true) }
    var showBurnInPatternDialog by remember { mutableStateOf(false) }
    var showBurnInIntervalDialog by remember { mutableStateOf(false) }
    var showRotationModeDialog by remember { mutableStateOf(false) }
    var showRotationSettleDialog by remember { mutableStateOf(false) }
    var showPauseLingerDialog by remember { mutableStateOf(false) }
    var showKeepAwakeDurationDialog by remember { mutableStateOf(false) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showSourceDialog by remember { mutableStateOf(false) }
    val selectedTab = SettingsTab.entries.firstOrNull { it.name == selectedTabName }
        ?: SettingsTab.OVERVIEW
    val selectedTabIndex = SettingsTab.entries.indexOf(selectedTab)
    val pagerState = rememberPagerState(initialPage = selectedTabIndex) {
        SettingsTab.entries.size
    }
    LaunchedEffect(pagerState.currentPage) {
        onSelectTab(SettingsTab.entries[pagerState.currentPage].name)
    }
    LaunchedEffect(selectedTabIndex) {
        if (!pagerState.isScrollInProgress && pagerState.currentPage != selectedTabIndex) {
            pagerState.animateScrollToPage(selectedTabIndex)
        }
    }
    val prefs = remember { context.getSharedPreferences(AodRenderPreferences.PREFS, 0) }
    val exportConfigLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(exportAllConfig(context))
            } ?: error("Config export unavailable")
        }.isSuccess
        Toast.makeText(
            context,
            context.getString(
                if (written) R.string.toast_config_exported
                else R.string.toast_config_export_failed
            ),
            Toast.LENGTH_LONG
        ).show()
    }
    val importConfigLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val raw = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val bytes = input.readNBytes(MAX_CONFIG_FILE_BYTES + 1)
                if (bytes.size > MAX_CONFIG_FILE_BYTES) error("Config file too large")
                bytes.toString(Charsets.UTF_8)
            } ?: error("Config file unavailable")
        }.getOrNull()
        val imported = raw != null && importAllConfig(context, raw)
        if (imported) {
            // 写入 SharedPreferences 会自动触发 AodRenderPreferences 缓存失效,
            // 这里同步把运行时配置推给 SystemUI 生效。
            publishRuntimeConfiguration(context)
        }
        Toast.makeText(
            context,
            context.getString(
                if (imported) R.string.toast_config_imported
                else R.string.toast_config_import_invalid
            ),
            Toast.LENGTH_LONG
        ).show()
    }
    var capabilityReport by remember { mutableStateOf(XiaomiCapabilityStore.read(context)) }
    DisposableEffect(context) {
        val capabilityPrefs = context.getSharedPreferences(XiaomiCapabilityStore.PREFS, 0)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            capabilityReport = XiaomiCapabilityStore.read(context)
        }
        capabilityPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { capabilityPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val initialConfig = remember { AodRenderPreferences.read(context) }
    val initialDocument = remember { CustomizationRepository.loadDocument(context) }
    var experimentalMode by remember { mutableStateOf(initialConfig.experimentalMode) }
    // App-side overlay: hook 端上报的 report 保持原样,UI 把用户开关叠加到
    // experimentalModeEnabled 上,据此推导 supportState / has()。开关切换即时生效,
    // 无需等 hook 重新上报。
    val effectiveReport = capabilityReport.copy(experimentalModeEnabled = experimentalMode)
    val supportState = effectiveReport.supportState()
    val aodSupported = effectiveReport.has(XiaomiCapability.AOD_SURFACE)
    val lockscreenSupported = effectiveReport.has(XiaomiCapability.LOCKSCREEN_HOST) &&
        effectiveReport.has(XiaomiCapability.LOCKSCREEN_GEOMETRY)
    val runtimeProfileAvailable = supportState == XiaomiRuntimeSupportState.AVAILABLE ||
        supportState == XiaomiRuntimeSupportState.VERIFIED_PROFILE ||
        supportState == XiaomiRuntimeSupportState.VERIFIED_PROFILE_MISSING_SYMBOLS ||
        supportState == XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE
    val positionFollowingSupported = effectiveReport.has(XiaomiCapability.AOD_POSITION_UPDATES)
    val raiseToAodSupported = effectiveReport.has(XiaomiCapability.RAISE_TO_AOD)
    val lockscreenEditorGestureSupported = effectiveReport.has(
        XiaomiCapability.LOCKSCREEN_EDITOR_GESTURE
    )
    val experimentalEligible = effectiveReport.profileState ==
        XiaomiProfileState.EXPERIMENTAL_ELIGIBLE
    var aodEnabled by remember {
        mutableStateOf(
            initialDocument.profiles[SceneCompiler.SURFACE_AOD]?.enabled
                ?: initialConfig.aodEnabled
        )
    }
    var lockscreenEnabled by remember {
        mutableStateOf(
            initialDocument.profiles[SceneCompiler.SURFACE_LOCKSCREEN]?.enabled
                ?: initialConfig.lockscreenEnabled
        )
    }
    var keepAwake by remember { mutableStateOf(initialConfig.keepAwake) }
    var aodClockFollow by remember { mutableStateOf(initialConfig.aodClockFollow) }
    var aodClockYOffset by remember { mutableStateOf(initialConfig.aodClockYOffset) }
    var keepAwakeUnsynced by remember { mutableStateOf(initialConfig.keepAwakeUnsynced) }
    var keepAwakeDurationMs by remember { mutableStateOf(initialConfig.keepAwakeDurationMs) }
    var lockscreenKeepAwake by remember {
        mutableStateOf(initialConfig.lockscreenKeepAwake)
    }
    var raiseToAod by remember { mutableStateOf(initialConfig.raiseToAod) }
    var suppressLockscreenEditorLongPress by remember {
        mutableStateOf(initialConfig.suppressLockscreenEditorLongPress)
    }
    var positionFollowing by remember {
        mutableStateOf(initialConfig.experimentalPositionFollowing)
    }
    var burnInPattern by remember { mutableStateOf(initialConfig.burnInPattern) }
    var burnInIntervalMs by remember { mutableStateOf(initialConfig.burnInIntervalMs) }
    var pauseLingerMs by remember { mutableStateOf(initialConfig.pauseLingerMs) }
    var pauseShowContent by remember { mutableStateOf(initialConfig.pauseShowContent) }
    var aodBrightnessBoost by remember { mutableStateOf(initialConfig.aodBrightnessBoost) }
    var aodBrightnessOverride by remember { mutableStateOf(initialConfig.aodBrightnessOverride) }
    var aodBrightnessLevel by remember { mutableStateOf(initialConfig.aodBrightnessLevel) }
    var aodRotateWithDevice by remember { mutableStateOf(initialConfig.aodRotateWithDevice) }
    var aodRotationMode by remember { mutableStateOf(initialConfig.aodRotationMode) }
    var aodRotationSettleMs by remember { mutableStateOf(initialConfig.aodRotationSettleMs) }
    var aodLandscapeTextScale by remember { mutableStateOf(initialConfig.aodLandscapeTextScale) }
    var aodCanvasAnchorLandscape by remember { mutableStateOf(initialConfig.aodCanvasAnchorLandscape) }
    var aodCanvasPaddingPortraitXPercent by remember {
        mutableStateOf(initialConfig.aodCanvasPaddingPortraitXPercent)
    }
    var aodCanvasPaddingPortraitYPercent by remember {
        mutableStateOf(initialConfig.aodCanvasPaddingPortraitYPercent)
    }
    var aodCanvasPaddingLandscapeXPercent by remember {
        mutableStateOf(initialConfig.aodCanvasPaddingLandscapeXPercent)
    }
    var aodCanvasPaddingLandscapeYPercent by remember {
        mutableStateOf(initialConfig.aodCanvasPaddingLandscapeYPercent)
    }
    var suppressStockAodContent by remember { mutableStateOf(initialConfig.suppressStockAodContent) }
    var aodLandscapeHideStock by remember { mutableStateOf(initialConfig.aodLandscapeHideStock) }
    var aodLandscapeFullscreen by remember { mutableStateOf(initialConfig.aodLandscapeFullscreen) }
    var aodDebugShowCanvasFrame by remember { mutableStateOf(initialConfig.aodDebugShowCanvasFrame) }
    var diagnosticLogging by remember {
        mutableStateOf(DiagnosticLoggingPreferences.read(context))
    }
    var persistentNotification by remember {
        mutableStateOf(initialConfig.persistentNotification)
    }
    var hideBackgroundCard by remember {
        mutableStateOf(initialConfig.hideBackgroundCard)
    }
    var hideLauncherIcon by remember {
        mutableStateOf(initialConfig.hideLauncherIcon)
    }
    // 监听外观配置变化:在"外观"编辑器里保存后,主页两个歌词预览立即反映最新设置。
    // 用 State 承载文档,预览 item 读取该 State,配置一变即触发重绘,无需依赖页面重建。
    var customizationDocument by remember {
        mutableStateOf(CustomizationRepository.loadDocument(context))
    }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(
            CustomizationRepository.PREFS,
            android.content.Context.MODE_PRIVATE
        )
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == CustomizationRepository.KEY_DOCUMENT) {
                customizationDocument = CustomizationRepository.loadDocument(context)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // 每次进入应用都按持久化配置重新应用"隐藏后台卡片",避免重启后失效。
    LaunchedEffect(Unit) {
        if (initialConfig.hideBackgroundCard) applyHideFromRecents(context, true)
    }

    Scaffold(
        topBar = { TopAppBar(title = stringResource(R.string.app_name)) },
        bottomBar = {
            FloatingNavigationBar {
                FloatingNavigationBarItem(
                    selected = pagerState.currentPage == SettingsTab.OVERVIEW.ordinal,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(SettingsTab.OVERVIEW.ordinal) }
                    },
                    icon = MiuixIcons.Regular.Home,
                    label = stringResource(R.string.nav_overview)
                )
                FloatingNavigationBarItem(
                    selected = pagerState.currentPage == SettingsTab.CONFIG.ordinal,
                    onClick = {
                        scope.launch { pagerState.animateScrollToPage(SettingsTab.CONFIG.ordinal) }
                    },
                    icon = MiuixIcons.Regular.Settings,
                    label = stringResource(R.string.nav_settings)
                )
            }
        }
    ) { innerPadding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top
        ) { page ->
            LazyColumn(
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 12.dp,
                    bottom = innerPadding.calculateBottomPadding() + 20.dp
                )
            ) {
                when (SettingsTab.entries[page]) {
                SettingsTab.OVERVIEW -> {
                    item { SmallTitle(text = stringResource(R.string.section_home_status)) }
                    item {
                        HomeOverviewHero(
                            working = resolveModuleWorking(supportState),
                            supportLabel = supportStateLabel(
                                context,
                                supportState,
                                effectiveReport.availableCapabilityCount,
                                effectiveReport.totalCapabilityCount
                            ),
                            aodEnabled = aodEnabled,
                            lockscreenEnabled = lockscreenEnabled,
                            systemUiVersion = capabilityReport.systemUiVersion,
                            aodVersion = capabilityReport.aodVersion
                        )
                    }
                    item { SmallTitle(text = stringResource(R.string.section_live_status)) }
                    item {
                        // 读取上面的 customizationDocument State:配置一变化该 item 即重绘,
                        // 保证两个预览始终跟随当前外观设置(在"外观"编辑器里改完即生效)。
                        val compiled = SceneCompiler.compile(customizationDocument)
                        val previewLive = collectLiveSnapshot()
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            LiveStatusSection()
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                LyricPreviewCard(
                                    title = stringResource(R.string.label_lockscreen_preview),
                                    profile = compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN),
                                    scenario = "Lockscreen · notifications",
                                    live = previewLive,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                LyricPreviewCard(
                                    title = stringResource(R.string.label_aod_preview),
                                    profile = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD),
                                    scenario = "Full AOD",
                                    live = previewLive,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_lyric_source)) }
                    item { LyricSourceSection(onOpenSourceDialog = { showSourceDialog = true }) }
                    item { SourceSetupHint() }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.plugin_management_title),
                                summary = stringResource(R.string.plugin_management_summary),
                                onClick = onOpenPlugins
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_runtime_status)) }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                diagnosticLogging,
                                { enabled ->
                                    if (updateDiagnosticLogging(context, enabled)) {
                                        diagnosticLogging = enabled
                                    }
                                },
                                stringResource(R.string.label_diagnostic_logging),
                                summary = if (BuildConfig.TRACE_LOGGING_AVAILABLE) {
                                    stringResource(R.string.summary_diagnostic_logging_available)
                                } else {
                                    stringResource(R.string.summary_diagnostic_logging_unavailable)
                                },
                                enabled = BuildConfig.TRACE_LOGGING_AVAILABLE
                            )
                            ArrowPreference(
                                title = if (supportState == XiaomiRuntimeSupportState.NO_SYSTEM_UI_REPORT ||
                                    supportState == XiaomiRuntimeSupportState.UNSUPPORTED_PROFILE ||
                                    supportState == XiaomiRuntimeSupportState.EXPERIMENTAL_ELIGIBLE ||
                                    effectiveReport.availableCapabilityCount <
                                    effectiveReport.totalCapabilityCount
                                ) {
                                    stringResource(R.string.action_send_compatibility_report)
                                } else {
                                    stringResource(R.string.action_report_problem)
                                },
                                onClick = onOpenDiagnostics
                            )
                            ArrowPreference(
                                title = stringResource(R.string.action_restart_systemui),
                                onClick = { showRestartDialog = true }
                            )
                            if (experimentalEligible) {
                                SwitchPreference(
                                    experimentalMode,
                                    { enabled ->
                                        if (updateExperimentalMode(context, enabled)) {
                                            experimentalMode = enabled
                                        }
                                    },
                                    stringResource(R.string.setting_experimental_mode),
                                    summary = if (experimentalMode) {
                                        stringResource(R.string.summary_experimental_mode_on)
                                    } else {
                                        stringResource(R.string.summary_experimental_mode)
                                    }
                                )
                            }
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_permission_status)) }
                    item {
                        SettingsCard {
                            PermissionStatusSection()
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_project)) }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.action_hyperglow_github),
                                onClick = {
                                    openExternalUrl(context, GITHUB_URL)
                                }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.action_hyperglow_cnplus_github),
                                onClick = {
                                    openExternalUrl(context, GITHUB_CNPLUS_URL)
                                }
                            )
                        }
                    }
                }

                SettingsTab.CONFIG -> {
                    item { SmallTitle(text = stringResource(R.string.section_language)) }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = englishInterfaceLanguageLabel(context),
                                summary = uiLanguageLabel(
                                    context,
                                    currentUiLanguage(context)
                                ),
                                onClick = { showLanguageDialog = true }
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_surfaces)) }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                aodEnabled,
                                { enabled ->
                                    if (!aodSupported) return@SwitchPreference
                                    if (updateCustomizationSurfaceEnabled(
                                            context,
                                            SceneCompiler.SURFACE_AOD,
                                            enabled
                                        )
                                    ) {
                                        aodEnabled = enabled
                                    }
                                },
                                stringResource(R.string.setting_show_aod),
                                summary = if (aodSupported) {
                                    null
                                } else {
                                    stringResource(R.string.summary_show_aod_unsupported)
                                },
                                enabled = aodSupported
                            )
                            SwitchPreference(
                                lockscreenEnabled,
                                { enabled ->
                                    if (!lockscreenSupported) {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.toast_lockscreen_unsupported),
                                            Toast.LENGTH_LONG
                                        ).show()
                                        return@SwitchPreference
                                    }
                                    if (updateCustomizationSurfaceEnabled(
                                            context,
                                            SceneCompiler.SURFACE_LOCKSCREEN,
                                            enabled
                                        )
                                    ) {
                                        lockscreenEnabled = enabled
                                    }
                                },
                                stringResource(R.string.setting_show_lockscreen),
                                summary = if (lockscreenSupported) {
                                    null
                                } else {
                                    stringResource(R.string.summary_unavailable_systemui_version)
                                },
                                enabled = lockscreenSupported
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_appearance)) }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.title_aod_appearance),
                                onClick = { onOpenLyricLayout(SceneCompiler.SURFACE_AOD) }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.title_lockscreen_appearance),
                                onClick = { onOpenLyricLayout(SceneCompiler.SURFACE_LOCKSCREEN) }
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_playback_behavior)) }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                pauseShowContent,
                                { enabled ->
                                    if (updatePauseShowContent(context, enabled)) {
                                        pauseShowContent = enabled
                                    }
                                },
                                stringResource(R.string.setting_pause_show_content),
                                summary = stringResource(
                                    R.string.summary_pause_show_content,
                                    stringResource(R.string.setting_after_spotify_pauses)
                                ),
                                enabled = runtimeProfileAvailable && (aodSupported || lockscreenSupported)
                            )
                            ArrowPreference(
                                title = stringResource(R.string.setting_after_spotify_pauses),
                                summary = pauseLingerLabel(context, pauseLingerMs),
                                onClick = { showPauseLingerDialog = true },
                                enabled = pauseShowContent &&
                                    runtimeProfileAvailable && (aodSupported || lockscreenSupported)
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_aod_behavior)) }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                keepAwake,
                                { enabled ->
                                    prefs.edit().putBoolean(AodRenderPreferences.KEEP_AWAKE, enabled).apply()
                                    keepAwake = enabled
                                },
                                stringResource(R.string.setting_keep_aod_active),
                                summary =
                                    if (aodSupported) {
                                        stringResource(R.string.summary_keep_aod_active)
                                    } else {
                                        stringResource(R.string.summary_unavailable_systemui_profile)
                                    },
                                enabled = aodSupported
                            )
                            ArrowPreference(
                                title = stringResource(R.string.setting_keep_aod_active_for),
                                summary = keepAwakeDurationLabel(context, keepAwakeDurationMs),
                                onClick = { showKeepAwakeDurationDialog = true },
                                enabled = aodSupported && keepAwake
                            )
                            SwitchPreference(
                                keepAwakeUnsynced,
                                { enabled ->
                                    prefs.edit().putBoolean(
                                        AodRenderPreferences.KEEP_AWAKE_UNSYNCED,
                                        enabled
                                    ).apply()
                                    keepAwakeUnsynced = enabled
                                },
                                stringResource(R.string.setting_keep_aod_unsynced),
                                enabled = aodSupported && keepAwake
                            )
                            SwitchPreference(
                                aodClockFollow,
                                { enabled ->
                                    if (updateAodClockFollow(context, enabled)) {
                                        aodClockFollow = enabled
                                    }
                                },
                                stringResource(R.string.setting_aod_clock_follow),
                                summary = stringResource(R.string.summary_aod_clock_follow),
                                enabled = aodSupported
                            )
                            if (!aodClockFollow) {
                                SliderPreference(
                                    value = aodClockYOffset.toFloat(),
                                    onValueChange = { px ->
                                        if (updateAodClockYOffset(context, px.toInt())) {
                                            aodClockYOffset = px.toInt()
                                        }
                                    },
                                    title = stringResource(R.string.setting_aod_clock_y_offset),
                                    summary = stringResource(R.string.summary_aod_clock_y_offset),
                                    valueText = aodClockYOffset.toString(),
                                    valueRange = com.eza.hyperglow.aod.MIN_AOD_CLOCK_Y_OFFSET.toFloat()..
                                        com.eza.hyperglow.aod.MAX_AOD_CLOCK_Y_OFFSET.toFloat(),
                                    steps =
                                        com.eza.hyperglow.aod.MAX_AOD_CLOCK_Y_OFFSET -
                                        com.eza.hyperglow.aod.MIN_AOD_CLOCK_Y_OFFSET
                                )
                            }
                            ArrowPreference(
                                title = stringResource(R.string.setting_aod_clock_image),
                                summary = if (positionFollowingSupported) {
                                    aodMovementLabel(context, positionFollowing, burnInPattern)
                                } else {
                                    stringResource(R.string.summary_aod_placement_unsupported)
                                },
                                onClick = {
                                    if (positionFollowingSupported) {
                                        showBurnInPatternDialog = true
                                    } else {
                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.summary_aod_placement_unsupported),
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                },
                                enabled = aodSupported && positionFollowingSupported
                            )
                            if (aodSupported && positionFollowingSupported && positionFollowing &&
                                !burnInPattern.isStaticClockPlacement()
                            ) {
                                ArrowPreference(
                                    title = stringResource(R.string.setting_movement_interval),
                                    summary = burnInIntervalLabel(context, burnInIntervalMs),
                                    onClick = { showBurnInIntervalDialog = true }
                                )
                            }
                            SwitchPreference(
                                aodBrightnessBoost,
                                { enabled ->
                                    if (updateAodBrightnessBoost(context, enabled)) {
                                        aodBrightnessBoost = enabled
                                    }
                                },
                                stringResource(R.string.setting_aod_brightness_boost),
                                summary = stringResource(R.string.summary_aod_brightness_boost),
                                enabled = aodSupported
                            )
                            if (aodBrightnessBoost) {
                                SwitchPreference(
                                    aodBrightnessOverride,
                                    { enabled ->
                                        if (updateAodBrightnessOverride(context, enabled)) {
                                            aodBrightnessOverride = enabled
                                        }
                                    },
                                    stringResource(R.string.setting_aod_brightness_custom),
                                    summary = stringResource(
                                        if (aodBrightnessOverride) {
                                            R.string.summary_aod_brightness_custom_on
                                        } else {
                                            R.string.summary_aod_brightness_custom_off
                                        }
                                    ),
                                    enabled = aodSupported
                                )
                                if (aodBrightnessOverride) {
                                    SliderPreference(
                                        value = aodBrightnessLevel.toFloat(),
                                        onValueChange = { pct ->
                                            if (updateAodBrightnessLevel(context, pct.toInt())) {
                                                aodBrightnessLevel = pct.toInt()
                                            }
                                        },
                                        title = stringResource(R.string.setting_aod_brightness_level),
                                        summary = stringResource(R.string.summary_aod_brightness_level),
                                        valueText = aodBrightnessLevel.toString(),
                                        valueRange = 10f..255f,
                                        steps = 244
                                    )
                                }
                            }
                            SwitchPreference(
                                suppressStockAodContent,
                                { enabled ->
                                    prefs.edit().putBoolean(
                                        AodRenderPreferences.SUPPRESS_STOCK_AOD_CONTENT,
                                        enabled
                                    ).apply()
                                    suppressStockAodContent = enabled
                                },
                                stringResource(R.string.setting_suppress_stock_aod),
                                summary = stringResource(R.string.summary_suppress_stock_aod),
                                enabled = aodSupported
                            )
                            SwitchPreference(
                                aodRotateWithDevice,
                                { enabled ->
                                    prefs.edit().putBoolean(
                                        AodRenderPreferences.AOD_ROTATE_WITH_DEVICE,
                                        enabled
                                    ).apply()
                                    if (enabled) {
                                        // issue #29:开启开关时若从未存过有效旋转模式,写入默认
                                        // auto,避免偏好长期停留在 portrait 导致永不旋转。
                                        val storedMode =
                                            prefs.getString(
                                                AodRenderPreferences.AOD_ROTATION_MODE,
                                                null
                                            ).orEmpty()
                                        if (storedMode.isBlank()) {
                                            prefs.edit().putString(
                                                AodRenderPreferences.AOD_ROTATION_MODE,
                                                com.eza.hyperglow.aod.AOD_ROTATION_MODE_AUTO
                                            ).apply()
                                            aodRotationMode =
                                                com.eza.hyperglow.aod.AOD_ROTATION_MODE_AUTO
                                        }
                                    }
                                    aodRotateWithDevice = enabled
                                },
                                stringResource(R.string.setting_aod_rotate_with_device),
                                summary = stringResource(R.string.summary_aod_rotate_with_device),
                                enabled = aodSupported
                            )
                            if (aodRotateWithDevice) {
                                ArrowPreference(
                                    title = stringResource(R.string.setting_aod_rotation_mode),
                                    summary = aodRotationModeLabel(context, aodRotationMode),
                                    onClick = { showRotationModeDialog = true },
                                    enabled = aodSupported
                                )
                                ArrowPreference(
                                    title = stringResource(R.string.setting_aod_rotation_settle),
                                    summary = aodRotationSettleLabel(context, aodRotationSettleMs),
                                    onClick = { showRotationSettleDialog = true },
                                    enabled = aodSupported
                                )
                                SliderPreference(
                                    value = aodLandscapeTextScale,
                                    onValueChange = { v ->
                                        if (updateAodLandscapeTextScale(context, v)) {
                                            aodLandscapeTextScale = v
                                        }
                                    },
                                    title = stringResource(R.string.setting_aod_landscape_scale),
                                    summary = stringResource(R.string.summary_aod_landscape_scale),
                                    valueText = (aodLandscapeTextScale * 100).toInt().toString() + "%",
                                    valueRange = 0.5f..2f,
                                    steps = 14
                                )
                                SliderPreference(
                                    value = aodCanvasAnchorLandscape,
                                    onValueChange = { v ->
                                        if (updateAodCanvasAnchorLandscape(context, v)) {
                                            aodCanvasAnchorLandscape = v
                                        }
                                    },
                                    title = stringResource(R.string.setting_aod_landscape_anchor),
                                    summary = stringResource(R.string.summary_aod_landscape_anchor),
                                    valueText =
                                        (aodCanvasAnchorLandscape * 100).roundToInt().toString() + "%",
                                    valueRange = 0f..1f,
                                    steps = 10
                                )
                                SliderPreference(
                                    value = aodCanvasPaddingPortraitXPercent,
                                    onValueChange = { v ->
                                        if (updateAodCanvasPaddingPercent(
                                                context,
                                                AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_X_PERCENT,
                                                v
                                            )
                                        ) {
                                            aodCanvasPaddingPortraitXPercent = v
                                        }
                                    },
                                    title = stringResource(R.string.setting_aod_padding_portrait_x),
                                    summary = stringResource(R.string.summary_aod_padding),
                                    valueText = aodCanvasPaddingPortraitXPercent.toInt().toString() + "%",
                                    valueRange = 0f..MAX_CANVAS_PADDING_PERCENT,
                                    steps = MAX_CANVAS_PADDING_PERCENT.toInt()
                                )
                                SliderPreference(
                                    value = aodCanvasPaddingPortraitYPercent,
                                    onValueChange = { v ->
                                        if (updateAodCanvasPaddingPercent(
                                                context,
                                                AodRenderPreferences.AOD_CANVAS_PADDING_PORTRAIT_Y_PERCENT,
                                                v
                                            )
                                        ) {
                                            aodCanvasPaddingPortraitYPercent = v
                                        }
                                    },
                                    title = stringResource(R.string.setting_aod_padding_portrait_y),
                                    summary = stringResource(R.string.summary_aod_padding),
                                    valueText = aodCanvasPaddingPortraitYPercent.toInt().toString() + "%",
                                    valueRange = 0f..MAX_CANVAS_PADDING_PERCENT,
                                    steps = MAX_CANVAS_PADDING_PERCENT.toInt()
                                )
                                SliderPreference(
                                    value = aodCanvasPaddingLandscapeXPercent,
                                    onValueChange = { v ->
                                        if (updateAodCanvasPaddingPercent(
                                                context,
                                                AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_X_PERCENT,
                                                v
                                            )
                                        ) {
                                            aodCanvasPaddingLandscapeXPercent = v
                                        }
                                    },
                                    title = stringResource(R.string.setting_aod_padding_landscape_x),
                                    summary = stringResource(R.string.summary_aod_padding),
                                    valueText =
                                        aodCanvasPaddingLandscapeXPercent.toInt().toString() + "%",
                                    valueRange = 0f..MAX_CANVAS_PADDING_PERCENT,
                                    steps = MAX_CANVAS_PADDING_PERCENT.toInt()
                                )
                                SliderPreference(
                                    value = aodCanvasPaddingLandscapeYPercent,
                                    onValueChange = { v ->
                                        if (updateAodCanvasPaddingPercent(
                                                context,
                                                AodRenderPreferences.AOD_CANVAS_PADDING_LANDSCAPE_Y_PERCENT,
                                                v
                                            )
                                        ) {
                                            aodCanvasPaddingLandscapeYPercent = v
                                        }
                                    },
                                    title = stringResource(R.string.setting_aod_padding_landscape_y),
                                    summary = stringResource(R.string.summary_aod_padding),
                                    valueText =
                                        aodCanvasPaddingLandscapeYPercent.toInt().toString() + "%",
                                    valueRange = 0f..MAX_CANVAS_PADDING_PERCENT,
                                    steps = MAX_CANVAS_PADDING_PERCENT.toInt()
                                )
                                SwitchPreference(
                                    aodLandscapeHideStock,
                                    { enabled ->
                                        prefs.edit().putBoolean(
                                            AodRenderPreferences.AOD_LANDSCAPE_HIDE_STOCK,
                                            enabled
                                        ).apply()
                                        aodLandscapeHideStock = enabled
                                    },
                                    stringResource(R.string.setting_aod_landscape_hide_stock),
                                    summary = stringResource(
                                        R.string.summary_aod_landscape_hide_stock
                                    )
                                )
                                SwitchPreference(
                                    aodLandscapeFullscreen,
                                    { enabled ->
                                        prefs.edit().putBoolean(
                                            AodRenderPreferences.AOD_LANDSCAPE_FULLSCREEN,
                                            enabled
                                        ).apply()
                                        aodLandscapeFullscreen = enabled
                                    },
                                    stringResource(R.string.setting_aod_landscape_fullscreen),
                                    summary = stringResource(
                                        R.string.summary_aod_landscape_fullscreen
                                    )
                                )
                                SwitchPreference(
                                    aodDebugShowCanvasFrame,
                                    { enabled ->
                                        prefs.edit().putBoolean(
                                            AodRenderPreferences.AOD_DEBUG_SHOW_CANVAS_FRAME,
                                            enabled
                                        ).apply()
                                        aodDebugShowCanvasFrame = enabled
                                    },
                                    stringResource(R.string.setting_aod_debug_show_canvas_frame),
                                    summary = stringResource(
                                        R.string.summary_aod_debug_show_canvas_frame
                                    )
                                )
                            }
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_lockscreen_behavior)) }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                lockscreenKeepAwake,
                                { enabled ->
                                    if (updateLockscreenKeepAwake(context, enabled)) {
                                        lockscreenKeepAwake = enabled
                                    }
                                },
                                stringResource(R.string.setting_keep_lockscreen_awake),
                                summary =
                                    stringResource(R.string.summary_keep_lockscreen_awake),
                                enabled = lockscreenSupported && lockscreenEnabled
                            )
                            SwitchPreference(
                                suppressLockscreenEditorLongPress,
                                { enabled ->
                                    if (updateLockscreenEditorLongPress(context, enabled)) {
                                        suppressLockscreenEditorLongPress = enabled
                                    }
                                },
                                stringResource(R.string.setting_block_lockscreen_customization),
                                summary = if (lockscreenEditorGestureSupported) {
                                    stringResource(R.string.summary_block_lockscreen_customization)
                                } else {
                                    stringResource(R.string.summary_unavailable_systemui_version)
                                },
                                enabled = lockscreenEditorGestureSupported
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_wake_gestures)) }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                raiseToAod,
                                { enabled ->
                                    if (updateRaiseToAod(context, enabled)) {
                                        raiseToAod = enabled
                                    }
                                },
                                stringResource(R.string.setting_raise_to_aod),
                                summary = if (raiseToAodSupported) {
                                    stringResource(R.string.summary_raise_to_aod)
                                } else {
                                    stringResource(R.string.summary_unavailable_systemui_version)
                                },
                                enabled = raiseToAodSupported
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_system_integration)) }
                    item {
                        SettingsCard {
                            SwitchPreference(
                                persistentNotification,
                                { enabled ->
                                    prefs.edit().putBoolean(
                                        AodRenderPreferences.PERSISTENT_NOTIFICATION,
                                        enabled
                                    ).apply()
                                    persistentNotification = enabled
                                    // 重新触发前台服务,让常驻通知按新开关显示/隐藏
                                    runCatching {
                                        context.startService(
                                            Intent(context, AodLyricBridgeService::class.java)
                                        )
                                    }
                                },
                                stringResource(R.string.setting_foreground_notification),
                                summary = stringResource(R.string.summary_foreground_notification)
                            )
                            SwitchPreference(
                                hideBackgroundCard,
                                { enabled ->
                                    prefs.edit().putBoolean(
                                        AodRenderPreferences.HIDE_BACKGROUND_CARD,
                                        enabled
                                    ).apply()
                                    hideBackgroundCard = enabled
                                    applyHideFromRecents(context, enabled)
                                },
                                stringResource(R.string.setting_hide_background_card),
                                summary = stringResource(R.string.summary_hide_background_card)
                            )
                            SwitchPreference(
                                hideLauncherIcon,
                                { enabled ->
                                    prefs.edit().putBoolean(
                                        AodRenderPreferences.HIDE_LAUNCHER_ICON,
                                        enabled
                                    ).apply()
                                    hideLauncherIcon = enabled
                                    applyHideLauncherIcon(context, enabled)
                                },
                                stringResource(R.string.setting_hide_launcher_icon),
                                summary = stringResource(R.string.summary_hide_launcher_icon)
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_config_backup)) }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.setting_export_config),
                                summary = stringResource(R.string.summary_export_config),
                                onClick = { exportConfigLauncher.launch("hyperglow-config.json") }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.setting_import_config),
                                summary = stringResource(R.string.summary_import_config),
                                onClick = {
                                    importConfigLauncher.launch(
                                        arrayOf("application/json", "text/plain", "application/octet-stream")
                                    )
                                }
                            )
                        }
                    }
                }

                }
            }
        }
    }

    if (showLanguageDialog) {
        val currentLanguage = currentUiLanguage(context)
        WindowDialog(
            title = englishInterfaceLanguageLabel(context),
            summary = stringResource(R.string.dialog_language_summary),
            show = true,
            onDismissRequest = { showLanguageDialog = false }
        ) {
            Column {
                UiLanguage.entries.forEach { language ->
                    RadioButtonPreference(
                        uiLanguageLabel(context, language),
                        currentLanguage == language,
                        {
                            showLanguageDialog = false
                            setUiLanguage(context, language)
                        }
                    )
                }
            }
        }
    }

    if (showSourceDialog) {
        LyricSourcePickerDialog(onDismiss = { showSourceDialog = false })
    }

    if (showRestartDialog) {
        WindowDialog(
            title = stringResource(R.string.dialog_restart_systemui_title),
            summary =
                stringResource(R.string.dialog_restart_systemui_summary),
            show = true,
            onDismissRequest = { showRestartDialog = false }
        ) {
            Column {
                SwitchPreference(
                    restartSystemUiTarget,
                    { enabled -> restartSystemUiTarget = enabled },
                    stringResource(R.string.dialog_restart_target_systemui)
                )
                SwitchPreference(
                    restartAodTarget,
                    { enabled -> restartAodTarget = enabled },
                    stringResource(R.string.dialog_restart_target_aod)
                )
                androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = stringResource(R.string.action_cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { showRestartDialog = false }
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.action_restart),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            if (!restartSystemUiTarget && !restartAodTarget) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_restart_no_target),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@TextButton
                            }
                            showRestartDialog = false
                            scope.launch {
                                showRestartResult(
                                    ShellUtils.restartHookedProcesses(
                                        systemUi = restartSystemUiTarget,
                                        miuiAod = restartAodTarget
                                    )
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    if (showBurnInPatternDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_aod_clock_image),
            show = true,
            onDismissRequest = { showBurnInPatternDialog = false }
        ) {
            Column {
                RadioButtonPreference(
                    stringResource(R.string.option_follow_xiaomi),
                    !positionFollowing,
                    {
                        prefs.edit().putBoolean(
                            AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING,
                            false
                        ).apply()
                        positionFollowing = false
                        showBurnInPatternDialog = false
                    }
                )
                BURN_IN_PATTERNS.forEach { value ->
                    RadioButtonPreference(
                        burnInPatternLabel(context, value),
                        positionFollowing && burnInPattern == value,
                        {
                            prefs.edit()
                                .putBoolean(
                                    AodRenderPreferences.EXPERIMENTAL_POSITION_FOLLOWING,
                                    true
                                )
                                .putString(AodRenderPreferences.BURN_IN_PATTERN, value)
                                .apply()
                            positionFollowing = true
                            burnInPattern = value
                            showBurnInPatternDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showRotationModeDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_aod_rotation_mode),
            show = true,
            onDismissRequest = { showRotationModeDialog = false }
        ) {
            Column {
                AOD_ROTATION_MODES.forEach { mode ->
                    RadioButtonPreference(
                        aodRotationModeOptionLabel(context, mode),
                        aodRotationMode == mode,
                        {
                            if (updateAodRotationMode(context, mode)) aodRotationMode = mode
                            showRotationModeDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showRotationSettleDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_aod_rotation_settle),
            show = true,
            onDismissRequest = { showRotationSettleDialog = false }
        ) {
            Column {
                AOD_ROTATION_SETTLES.forEach { ms ->
                    RadioButtonPreference(
                        aodRotationSettleLabel(context, ms),
                        aodRotationSettleMs == ms,
                        {
                            if (updateAodRotationSettleMs(context, ms)) aodRotationSettleMs = ms
                            showRotationSettleDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showPauseLingerDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_after_spotify_pauses),
            summary = stringResource(R.string.dialog_pause_summary),
            show = true,
            onDismissRequest = { showPauseLingerDialog = false }
        ) {
            Column {
                PAUSE_LINGER_OPTIONS.forEach { value ->
                    RadioButtonPreference(
                        pauseLingerLabel(context, value),
                        pauseLingerMs == value,
                        {
                            if (updatePauseLinger(context, value)) pauseLingerMs = value
                            showPauseLingerDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showKeepAwakeDurationDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_keep_aod_active_for),
            summary = stringResource(R.string.dialog_keep_aod_duration_summary),
            show = true,
            onDismissRequest = { showKeepAwakeDurationDialog = false }
        ) {
            Column {
                KEEP_AWAKE_DURATIONS.forEach { value ->
                    RadioButtonPreference(
                        keepAwakeDurationLabel(context, value),
                        keepAwakeDurationMs == value,
                        {
                            if (updateKeepAwakeDuration(context, value)) keepAwakeDurationMs = value
                            showKeepAwakeDurationDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showBurnInIntervalDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_movement_interval),
            show = true,
            onDismissRequest = { showBurnInIntervalDialog = false }
        ) {
            Column {
                BURN_IN_INTERVALS.forEach { value ->
                    RadioButtonPreference(
                        burnInIntervalLabel(context, value),
                        burnInIntervalMs == value,
                        {
                            prefs.edit().putLong(AodRenderPreferences.BURN_IN_INTERVAL_MS, value).apply()
                            burnInIntervalMs = value
                            showBurnInIntervalDialog = false
                        }
                    )
                }
            }
        }
    }
}
