package com.eza.hyperglow.ui

import android.app.Activity
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.R
import com.eza.hyperglow.DiagnosticLoggingPreferences
import com.eza.hyperglow.DIAGNOSTIC_LOG_LEVELS
import com.eza.hyperglow.LOG_RETENTION_DAYS
import com.eza.hyperglow.root.utils.ShellUtils
import kotlinx.coroutines.launch
import com.eza.hyperglow.aod.AodLyricBridgeService
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.XiaomiCapabilityStore
import com.eza.hyperglow.aod.XiaomiRuntimeSupportState
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.producer.LyricProducers
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.capability.XiaomiProfileState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 底部导航项:tab 枚举 + 图标 + 标签资源;悬浮/贴底两种底栏共用同一份清单。 */
private data class HomeTab(
    val tab: SettingsTab,
    val icon: ImageVector,
    val labelRes: Int
)

@Composable
internal fun HomeScreen(
    showRestartResult: (Boolean) -> Unit,
    selectedTabName: String,
    onSelectTab: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenLyricLayout: (String) -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenAodBehavior: () -> Unit,
    onOpenAppAppearance: () -> Unit,
    floatingNavBar: Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showRestartDialog by rememberSaveable { mutableStateOf(false) }
    var restartSystemUiTarget by rememberSaveable { mutableStateOf(true) }
    var restartAodTarget by rememberSaveable { mutableStateOf(true) }
    var restartHyperglowTarget by rememberSaveable { mutableStateOf(false) }
    var restartLyricSource by rememberSaveable { mutableStateOf(false) }
    var showPauseLingerDialog by rememberSaveable { mutableStateOf(false) }
    var showLogRetentionDialog by rememberSaveable { mutableStateOf(false) }
    var showLogLevelDialog by rememberSaveable { mutableStateOf(false) }
    var showClearLogsDialog by rememberSaveable { mutableStateOf(false) }
    var showLanguageDialog by rememberSaveable { mutableStateOf(false) }
    var showSourceDialog by rememberSaveable { mutableStateOf(false) }
    var showResetDefaultsDialog by rememberSaveable { mutableStateOf(false) }
    val selectedTab = SettingsTab.entries.firstOrNull { it.name == selectedTabName }
        ?: SettingsTab.STATUS
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
    val exportLogsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val written = runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                it.write(readDiagnosticLogsForExport(context))
            } ?: error("Log export unavailable")
        }.isSuccess
        Toast.makeText(
            context,
            context.getString(
                if (written) R.string.toast_logs_exported
                else R.string.toast_logs_export_failed
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
    var lockscreenKeepAwake by remember {
        mutableStateOf(initialConfig.lockscreenKeepAwake)
    }
    var raiseToAod by remember { mutableStateOf(initialConfig.raiseToAod) }
    var suppressLockscreenEditorLongPress by remember {
        mutableStateOf(initialConfig.suppressLockscreenEditorLongPress)
    }
    var pauseLingerMs by remember { mutableStateOf(initialConfig.pauseLingerMs) }
    var pauseShowContent by remember { mutableStateOf(initialConfig.pauseShowContent) }
    var diagnosticLogging by remember {
        mutableStateOf(DiagnosticLoggingPreferences.read(context))
    }
    var logRetentionDays by remember {
        mutableStateOf(DiagnosticLoggingPreferences.readRetentionDays(context))
    }
    var logLevel by remember {
        mutableStateOf(DiagnosticLoggingPreferences.readLevel(context))
    }
    // 画布边框调试开关(自 AodBehaviorScreen 迁入「开发者选项」组)。
    var aodDebugShowCanvasFrame by remember {
        mutableStateOf(initialConfig.aodDebugShowCanvasFrame)
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
        containerColor = appSurfaceColor(),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.app_name),
                actions = {
                    IconButton(onClick = { showRestartDialog = true }) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Refresh,
                            contentDescription =
                                stringResource(R.string.action_restart_systemui),
                            tint = appTopBarTitleColor()
                        )
                    }
                }
            )
        },
        bottomBar = {
            // 悬浮底栏开关(App 外观设置):开=miuix FloatingNavigationBar,关=贴底 NavigationBar。
            val tabs = listOf(
                HomeTab(SettingsTab.STATUS, MiuixIcons.Regular.Home, R.string.nav_status),
                HomeTab(SettingsTab.SETTINGS, MiuixIcons.Regular.Settings, R.string.nav_settings),
                HomeTab(SettingsTab.APP, MiuixIcons.Tune, R.string.nav_app_settings),
                HomeTab(SettingsTab.ABOUT, MiuixIcons.Info, R.string.nav_about)
            )
            val itemColors = NavigationBarDefaults.navigationBarItemColors(
                unselectedContentColor = appControlContentColor(MiuixTheme.colorScheme.onSurfaceContainer),
                selectedContentColor = appControlContentColor(MiuixTheme.colorScheme.onSurfaceContainer)
            )
            if (floatingNavBar) {
                FloatingNavigationBar(color = appNavBarColor()) {
                    tabs.forEach { entry ->
                        FloatingNavigationBarItem(
                            selected = pagerState.currentPage == entry.tab.ordinal,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(entry.tab.ordinal) }
                            },
                            icon = entry.icon,
                            label = stringResource(entry.labelRes),
                            colors = itemColors
                        )
                    }
                }
            } else {
                NavigationBar(color = appNavBarColor()) {
                    tabs.forEach { entry ->
                        NavigationBarItem(
                            selected = pagerState.currentPage == entry.tab.ordinal,
                            onClick = {
                                scope.launch { pagerState.animateScrollToPage(entry.tab.ordinal) }
                            },
                            icon = entry.icon,
                            label = stringResource(entry.labelRes),
                            colors = itemColors
                        )
                    }
                }
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
                SettingsTab.STATUS -> {
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
                            aodVersion = capabilityReport.aodVersion,
                            onOpenSurface = onOpenLyricLayout
                        )
                    }
                    item { SmallTitle(text = stringResource(R.string.section_live_status)) }
                    item {
                        // 读取上面的 customizationDocument State:配置一变化该 item 即重绘,
                        // 保证预览始终跟随当前外观设置(在"外观"编辑器里改完即生效)。
                        // 两张预览卡合并为一张 + 锁屏/息屏切换,状态页不再被拉长。
                        val compiled = SceneCompiler.compile(customizationDocument)
                        val lockscreenProfile = compiled.profiles.getValue(
                            SceneCompiler.SURFACE_LOCKSCREEN
                        )
                        val aodProfile = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD)
                        // 每面各自组装歌曲信息、按本面「识别对唱标记」隐去标记:切换面时
                        // 使用该面自己的快照,改一面的内容项不影响另一面。
                        val lockscreenLive = collectLiveSnapshot(
                            lockscreenProfile.metadataParts,
                            lockscreenProfile.metadataSeparators,
                            lockscreenProfile.duetMarkers,
                            lockscreenProfile.hideAlbumWhenSameAsTitle
                        )
                        val aodLive = collectLiveSnapshot(
                            aodProfile.metadataParts,
                            aodProfile.metadataSeparators,
                            aodProfile.duetMarkers,
                            aodProfile.hideAlbumWhenSameAsTitle
                        )
                        var previewAod by rememberSaveable { mutableStateOf(false) }
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            LiveStatusSection()
                            LyricPreviewCardWithSwitch(
                                lockscreenProfile = lockscreenProfile,
                                aodProfile = aodProfile,
                                lockscreenLive = lockscreenLive,
                                aodLive = aodLive,
                                selectedAod = previewAod,
                                onSelectSurface = { previewAod = it },
                                modifier = Modifier.padding(horizontal = 12.dp)
                            )
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
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_permission_status)) }
                    item {
                        SettingsCard {
                            PermissionStatusSection()
                        }
                    }
                }

                SettingsTab.SETTINGS -> {
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
                            ArrowPreference(
                                title = stringResource(R.string.title_aod_behavior_settings),
                                summary = stringResource(R.string.summary_aod_behavior_entry),
                                onClick = onOpenAodBehavior,
                                enabled = aodSupported
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_lockscreen_wake)) }
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
                            ArrowPreference(
                                title = stringResource(R.string.setting_reset_defaults),
                                summary = stringResource(R.string.summary_reset_defaults),
                                onClick = { showResetDefaultsDialog = true }
                            )
                        }
                    }
                }

                SettingsTab.APP -> {
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
                    item { SmallTitle(text = stringResource(R.string.section_app_appearance)) }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.section_app_appearance),
                                onClick = onOpenAppAppearance
                            )
                        }
                    }
                    item { SmallTitle(text = stringResource(R.string.section_developer_options)) }
                    if (experimentalEligible) {
                        item {
                            SettingsCard {
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
                    item {
                        SettingsCard {
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
                                summary = stringResource(R.string.summary_aod_debug_show_canvas_frame)
                            )
                        }
                    }
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
                                title = stringResource(R.string.setting_log_retention),
                                summary = logRetentionLabel(context, logRetentionDays),
                                onClick = { showLogRetentionDialog = true }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.setting_log_level),
                                summary = logLevelLabel(context, logLevel),
                                onClick = { showLogLevelDialog = true }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.action_export_logs),
                                summary = stringResource(R.string.summary_export_logs),
                                onClick = { exportLogsLauncher.launch("hyperglow-logs.txt") }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.action_clear_logs),
                                onClick = { showClearLogsDialog = true }
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
                }

                SettingsTab.ABOUT -> {
                    item { SmallTitle(text = stringResource(R.string.section_about)) }
                    item {
                        AboutHeroCard(
                            systemUiVersion = capabilityReport.systemUiVersion,
                            aodVersion = capabilityReport.aodVersion
                        )
                    }
                    item { AboutAuthorCard() }
                    item {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.about_support),
                                summary = stringResource(R.string.summary_about_support),
                                onClick = { openExternalUrl(context, PROJECT_SITE_URL) }
                            )
                        }
                    }
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
            Column(Modifier.dialogScrollable()) {
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

    if (showResetDefaultsDialog) {
        WindowDialog(
            title = stringResource(R.string.dialog_reset_defaults_title),
            summary = stringResource(R.string.dialog_reset_defaults_summary),
            show = true,
            onDismissRequest = { showResetDefaultsDialog = false }
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { showResetDefaultsDialog = false }
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.action_restore),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        showResetDefaultsDialog = false
                        val success = resetToDefaults(context)
                        Toast.makeText(
                            context,
                            if (success) R.string.toast_settings_restored else R.string.toast_settings_restore_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                        if (success) (context as? Activity)?.recreate()
                    }
                )
            }
        }
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
                SwitchPreference(
                    restartHyperglowTarget,
                    { enabled -> restartHyperglowTarget = enabled },
                    stringResource(R.string.dialog_restart_target_hyperglow)
                )
                SwitchPreference(
                    restartLyricSource,
                    { enabled -> restartLyricSource = enabled },
                    stringResource(R.string.dialog_restart_target_lyric_source)
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
                            val hookTargetSelected = restartSystemUiTarget ||
                                restartAodTarget || restartHyperglowTarget
                            if (!hookTargetSelected && !restartLyricSource) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_restart_no_target),
                                    Toast.LENGTH_SHORT
                                ).show()
                                return@TextButton
                            }
                            showRestartDialog = false
                            // 歌词源重启在应用进程内即时生效(无 root);与挂钩进程重启相互独立。
                            if (restartLyricSource) {
                                LyricProducers.arbiterOrNull()?.restartSelected()
                            }
                            if (hookTargetSelected) {
                                scope.launch {
                                    showRestartResult(
                                        ShellUtils.restartHookedProcesses(
                                            systemUi = restartSystemUiTarget,
                                            miuiAod = restartAodTarget,
                                            hyperglowApp = restartHyperglowTarget
                                        )
                                    )
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.toast_lyric_source_restarted),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
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
            Column(Modifier.dialogScrollable()) {
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

    if (showLogRetentionDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_log_retention),
            show = true,
            onDismissRequest = { showLogRetentionDialog = false }
        ) {
            Column(Modifier.dialogScrollable()) {
                LOG_RETENTION_DAYS.forEach { value ->
                    RadioButtonPreference(
                        logRetentionLabel(context, value),
                        logRetentionDays == value,
                        {
                            if (updateLogRetentionDays(context, value)) logRetentionDays = value
                            showLogRetentionDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showLogLevelDialog) {
        WindowDialog(
            title = stringResource(R.string.setting_log_level),
            summary = stringResource(R.string.dialog_log_level_summary),
            show = true,
            onDismissRequest = { showLogLevelDialog = false }
        ) {
            Column(Modifier.dialogScrollable()) {
                DIAGNOSTIC_LOG_LEVELS.forEach { value ->
                    RadioButtonPreference(
                        logLevelLabel(context, value),
                        logLevel == value,
                        {
                            if (updateLogLevel(context, value)) logLevel = value
                            showLogLevelDialog = false
                        }
                    )
                }
            }
        }
    }

    if (showClearLogsDialog) {
        WindowDialog(
            title = stringResource(R.string.dialog_clear_logs_title),
            summary = stringResource(R.string.dialog_clear_logs_summary),
            show = true,
            onDismissRequest = { showClearLogsDialog = false }
        ) {
            Column {
                androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
                    TextButton(
                        text = stringResource(R.string.action_cancel),
                        modifier = Modifier.weight(1f),
                        onClick = { showClearLogsDialog = false }
                    )
                    Spacer(Modifier.width(20.dp))
                    TextButton(
                        text = stringResource(R.string.action_clear_logs),
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        onClick = {
                            showClearLogsDialog = false
                            val cleared = clearDiagnosticLogs(context)
                            Toast.makeText(
                                context,
                                context.getString(
                                    if (cleared) {
                                        R.string.toast_logs_cleared
                                    } else {
                                        R.string.toast_logs_clear_failed
                                    }
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    )
                }
            }
        }
    }

}
