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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.R
import com.eza.hyperglow.DiagnosticLoggingPreferences
import com.eza.hyperglow.DIAGNOSTIC_LOG_LEVELS
import com.eza.hyperglow.LOG_RETENTION_DAYS
import com.eza.hyperglow.root.utils.ShellUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.FloatingNavigationBar
import top.yukonga.miuix.kmp.basic.FloatingNavigationBarItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDefaults
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TabRowDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SliderPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 底部导航项:tab 枚举 + 图标 + 标签资源;悬浮/贴底两种底栏共用同一份清单。 */
private data class HomeTab(
    val tab: SettingsTab,
    val icon: ImageVector,
    val labelRes: Int
)

/** 搜索结果待滚动目标:先到 [anchor] 分组,再尝试把 [rowAnchor] 对应的具体开关带进视口。 */
private data class PendingSearchScroll(
    val tab: SettingsTab,
    val anchor: String,
    val rowAnchor: String?
)

@Composable
internal fun HomeScreen(
    showRestartResult: (Boolean) -> Unit,
    selectedTabName: String,
    onSelectTab: (String) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenAodBehavior: () -> Unit,
    onOpenAppAppearance: () -> Unit,
    onOpenOnboarding: () -> Unit,
    onOpenConfigPresets: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenChangelog: () -> Unit,
    onOpenContributors: () -> Unit,
    onOpenLicenses: () -> Unit,
    onOpenLyricEnhancement: () -> Unit,
    floatingNavBar: Boolean,
    predictiveBackEnabled: Boolean,
    onPredictiveBackChanged: (Boolean) -> Unit,
    backTriggerPercent: Int,
    onBackTriggerPercentChanged: (Int) -> Unit
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
    // 设置页顶部分段:0=息屏设置 1=锁屏设置 2=其他设置。
    var settingsSectionIndex by rememberSaveable { mutableStateOf(0) }
    var showResetDefaultsDialog by rememberSaveable { mutableStateOf(false) }
    // 全局搜索:入口出现在除关于页外的所有页;选中结果后切页并滚到对应分组。
    var showGlobalSearch by rememberSaveable { mutableStateOf(false) }
    val statusListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()
    val appListState = rememberLazyListState()
    val aboutListState = rememberLazyListState()
    // 歌词外观编辑器内部也有独立的 LazyColumn,搜索结果需要滚到它里面的分组。
    val appearanceListState = rememberLazyListState()
    // anchor → LazyColumn item index,由各页 LazyColumn 组合时登记。
    val searchAnchorIndices = remember { mutableStateMapOf<String, Int>() }
    var pendingSearchScroll by remember { mutableStateOf<PendingSearchScroll?>(null) }
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
    // 搜索结果:先等外层分页停到目标 Tab,再滚到分组;有行级锚点时继续把具体开关带进视口。
    LaunchedEffect(pendingSearchScroll) {
        val pending = pendingSearchScroll ?: return@LaunchedEffect
        // 分页动画期间目标页可能尚未组合,提前读锚点会拿到上一次的旧索引。
        withTimeoutOrNull(3_000) {
            snapshotFlow { pagerState.currentPage to pagerState.isScrollInProgress }
                .first { (page, scrolling) -> !scrolling && page == pending.tab.ordinal }
        }
        val index = withTimeoutOrNull(2_000) {
            snapshotFlow { searchAnchorIndices[pending.anchor] }.filterNotNull().first()
        }
        val listState: LazyListState = when {
            GlobalSearchAnchor.isAppearanceAnchor(pending.anchor) -> appearanceListState
            pending.tab == SettingsTab.STATUS -> statusListState
            pending.tab == SettingsTab.SETTINGS -> settingsListState
            pending.tab == SettingsTab.APP -> appListState
            else -> aboutListState
        }
        if (index != null) listState.scrollToItem(index)
        // 行级锚点:条件开关未渲染时不会登记,超时后停在分组标题即可。
        pending.rowAnchor?.let { rowKey ->
            withTimeoutOrNull(1_500) {
                while (!GlobalSearchRowAnchors.isRegistered(rowKey)) {
                    withFrameNanos { }
                }
            }
            GlobalSearchRowAnchors.reveal(rowKey)
        }
        pendingSearchScroll = null
    }
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
        scope.launch {
            // 导出内容：App 镜像 + 最近 10 分钟 SystemUI HyperGlow logcat(wakeProbe 在这里)。
            // root 不可用时只导出 App 镜像，不阻断导出。
            val payload = withContext(Dispatchers.IO) {
                readDiagnosticLogsForExportWithSystemUi(context)
            }
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                        it.write(payload)
                    } ?: error("Log export unavailable")
                }.isSuccess
            }
            if (written) {
                Toast.makeText(context, R.string.toast_logs_exported, Toast.LENGTH_LONG).show()
                return@launch
            }
            // SAF 写入失败时落到 App 外部文件目录，至少日志不会丢。
            val local = withContext(Dispatchers.IO) {
                writeDiagnosticLogsToLocalFile(context, payload)
            }
            if (local != null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_logs_saved_to, local.absolutePath),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(context, R.string.toast_logs_export_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
    // 导入串行化锁:导入搬 IO 通道后不再被主线程天然串行化,并发导入会互相交错写 prefs/文档。
    val importMutex = remember { Mutex() }
    val importConfigLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val imported = importMutex.withLock {
                // 读 ≤2MiB + UTF-8 解码 + 信封/子文档 JSON 解析搬 IO 通道(SAF I/O 与
                // JSON/文档解码不得在主线程,STYLE_GUIDE §4;本地估算 50–200ms/次,真机未测)。
                val decoded = withContext(Dispatchers.IO) {
                    val raw = runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            val bytes = input.readNBytes(MAX_CONFIG_FILE_BYTES + 1)
                            if (bytes.size > MAX_CONFIG_FILE_BYTES) error("Config file too large")
                            bytes.toString(Charsets.UTF_8)
                        } ?: error("Config file unavailable")
                    }.getOrNull()
                    raw?.let { decodeImportedConfig(context, it) }
                }
                // 落盘与侧设置应用留在原线程:插件 onConfigChanged / 生产者 onCustomizationChanged
                // 的调用线程与搬线程前一致(设置页同样在主线程触发)。
                val ok = decoded != null && applyImportedConfig(context, decoded)
                if (ok) {
                    // 写入 SharedPreferences 会自动触发 AodRenderPreferences 缓存失效,
                    // 这里同步把运行时配置推给 SystemUI 生效。
                    publishRuntimeConfiguration(context)
                }
                ok
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
    // supportState() 对 UNSUPPORTED_PROFILE + 实验模式已有解锁分支;界面门控若只认
    // EXPERIMENTAL_ELIGIBLE,该分支永远没有入口。两种状态都显示开关,是否真的解锁
    // 仍由 rawProbes 推导决定。
    val experimentalEligible = effectiveReport.profileState ==
        XiaomiProfileState.EXPERIMENTAL_ELIGIBLE ||
        effectiveReport.profileState == XiaomiProfileState.UNSUPPORTED_PROFILE
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
    // 「视频/非音乐音频不显示歌词」:识别当前音频源,视频/播客等来源播放期间不显示歌词。
    var filterNonMusicSources by remember {
        mutableStateOf(initialConfig.filterNonMusicSources)
    }
    // 「不显示非歌词内容(作词/作曲等)」:歌词里的制作名单行整行隐藏。
    var hideCreditLines by remember {
        mutableStateOf(initialConfig.hideCreditLines)
    }
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
    // 开发者选项:强制指定歌词提供器(关闭时保留「自动选更好的源」)。
    var forceLyricProvider by remember {
        mutableStateOf(initialConfig.forceLyricProvider)
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
                val document = CustomizationRepository.loadDocument(context)
                customizationDocument = document
                // 预设/导入是整份文档替换,可能同时改了曲面 enabled;同步主页两个总开关,
                // 避免「配置已开、界面还显示关」。
                document.profiles[SceneCompiler.SURFACE_AOD]?.enabled?.let { aodEnabled = it }
                document.profiles[SceneCompiler.SURFACE_LOCKSCREEN]?.enabled?.let {
                    lockscreenEnabled = it
                }
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    // 每次进入应用都按持久化配置重新应用"隐藏后台卡片",避免重启后失效。
    LaunchedEffect(Unit) {
        if (initialConfig.hideBackgroundCard) applyHideFromRecents(context, true)
    }

    // 外观设置已并入「设置」页的息屏/锁屏分段(不再有独立编辑器页与入口行):
    // 状态页的外观卡直接切到对应分段,与手动切分段等价。
    val openAppearanceSection: (String) -> Unit = { surface ->
        settingsSectionIndex = if (surface == SceneCompiler.SURFACE_AOD) 0 else 1
        onSelectTab(SettingsTab.SETTINGS.name)
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = appSurfaceColor(),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.app_name),
                actions = {
                    // 全局搜索:四个 Tab 都可用,索引覆盖设置项、外观逐项与关于页入口。
                    IconButton(onClick = { showGlobalSearch = true }) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Search,
                            contentDescription = stringResource(R.string.search_title),
                            tint = appTopBarTitleColor()
                        )
                    }
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
            // 玻璃材质开启时,底栏自身容器让位给 backdrop;形状跟 miuix 默认圆角一致
            // (悬浮底栏 50dp 胶囊,贴底底栏为矩形)。
            val glassActive = isAppGlassMaterialActive()
            val navColor = if (glassActive) Color.Transparent else appNavBarColor()
            if (floatingNavBar) {
                FloatingNavigationBar(
                    modifier = if (glassActive) {
                        Modifier.appGlassMaterial(
                            RoundedCornerShape(50.dp),
                            cornerRadius = 50.dp,
                            tiltReactive = true
                        )
                    } else {
                        Modifier
                    },
                    color = navColor
                ) {
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
                NavigationBar(
                    modifier = if (glassActive) {
                        // 0 圆角要用 CornerBasedShape;RectangleShape 会让高光 shader 退化成胶囊。
                        Modifier.appGlassMaterial(
                            RoundedCornerShape(0.dp),
                            cornerRadius = 0.dp
                        )
                    } else {
                        Modifier
                    },
                    color = navColor
                ) {
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
            val isSettingsPage = SettingsTab.entries[page] == SettingsTab.SETTINGS
            // 息屏设置/锁屏设置分段就是歌词外观设置本体(原独立外观编辑器整块并入,实时预览常驻
            // 在列表上方);它自带滚动,所以这两个分段不走下面的 LazyColumn,其余页面/分段照旧。
            val appearanceSurface = if (isSettingsPage) {
                when (settingsSectionIndex) {
                    0 -> SceneCompiler.SURFACE_AOD
                    1 -> SceneCompiler.SURFACE_LOCKSCREEN
                    else -> null
                }
            } else {
                null
            }
            Column(Modifier.fillMaxSize()) {
                if (isSettingsPage) {
                    // 设置页顶部分段选择器:息屏设置 / 锁屏设置 / 其他设置(固定在列表上方)。
                    TabRow(
                        tabs = listOf(
                            stringResource(R.string.settings_section_aod),
                            stringResource(R.string.settings_section_lockscreen),
                            stringResource(R.string.settings_section_other)
                        ),
                        selectedTabIndex = settingsSectionIndex,
                        onTabSelected = { settingsSectionIndex = it },
                        // 接入 App 控件外观体系:自定义控件色/背景图玻璃化/不透明度总控都生效。
                        // 轨道完全透明(HyperOS 分段控件的做法):只留选中的胶囊浮在页面上,
                        // 自定义控件色时选中态靠胶囊与页面的实度差呈现,不会出现一条突兀的底条。
                        colors = TabRowDefaults.tabRowColors(
                            backgroundColor = Color.Transparent,
                            contentColor = appControlContentColor(
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            ),
                            selectedBackgroundColor = appGlassSurface(
                                LocalAppControlColor.current
                                    ?: MiuixTheme.colorScheme.surfaceContainer,
                                APP_GLASS_NAV_ALPHA
                            ),
                            selectedContentColor = appControlContentColor(
                                MiuixTheme.colorScheme.onBackground
                            )
                        ),
                        modifier = Modifier.padding(
                            top = innerPadding.calculateTopPadding() + 8.dp,
                            start = 12.dp,
                            end = 12.dp,
                            bottom = 4.dp
                        )
                    )
                }
                if (appearanceSurface != null) {
                    LyricAppearanceSection(
                        surface = appearanceSurface,
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = innerPadding.calculateBottomPadding() + 20.dp
                        ),
                        modifier = Modifier.weight(1f),
                        listState = appearanceListState,
                        onRegisterAnchor = { anchor, index ->
                            searchAnchorIndices[anchor] = index
                        },
                        // 分段自己的条目:该曲面的歌词总开关排在列表首,行为入口/锁屏唤醒排在列表尾。
                        header = {
                            if (appearanceSurface == SceneCompiler.SURFACE_AOD) {
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
                                        modifier = Modifier.searchRowAnchor(
                                            SearchRowKeys.APPEARANCE_SHOW_AOD
                                        ),
                                        summary = if (aodSupported) {
                                            null
                                        } else {
                                            stringResource(R.string.summary_show_aod_unsupported)
                                        },
                                        enabled = aodSupported
                                    )
                                }
                            } else {
                                SettingsCard {
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
                                        modifier = Modifier.searchRowAnchor(
                                            SearchRowKeys.APPEARANCE_SHOW_LOCKSCREEN
                                        ),
                                        summary = if (lockscreenSupported) {
                                            null
                                        } else {
                                            stringResource(R.string.summary_unavailable_systemui_version)
                                        },
                                        enabled = lockscreenSupported
                                    )
                                }
                            }
                        },
                        footer = {
                            if (appearanceSurface == SceneCompiler.SURFACE_AOD) {
                                SettingsCard {
                                    ArrowPreference(
                                        title = stringResource(R.string.title_aod_behavior_settings),
                                        modifier = Modifier.searchRowAnchor(
                                            SearchRowKeys.APPEARANCE_AOD_BEHAVIOR
                                        ),
                                        summary = stringResource(R.string.summary_aod_behavior_entry),
                                        onClick = onOpenAodBehavior,
                                        enabled = aodSupported
                                    )
                                }
                            } else {
                                Column {
                                    SmallTitle(text = stringResource(R.string.section_lockscreen_wake))
                                    SettingsCard {
                                        SwitchPreference(
                                            lockscreenKeepAwake,
                                            { enabled ->
                                                if (updateLockscreenKeepAwake(context, enabled)) {
                                                    lockscreenKeepAwake = enabled
                                                }
                                            },
                                            stringResource(R.string.setting_keep_lockscreen_awake),
                                            modifier = Modifier.searchRowAnchor(
                                                SearchRowKeys.APPEARANCE_KEEP_LOCKSCREEN_AWAKE
                                            ),
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
                                            modifier = Modifier.searchRowAnchor(
                                                SearchRowKeys.APPEARANCE_BLOCK_LOCKSCREEN_CUSTOMIZATION
                                            ),
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
                                            modifier = Modifier.searchRowAnchor(
                                                SearchRowKeys.APPEARANCE_RAISE_TO_AOD
                                            ),
                                            summary = if (raiseToAodSupported) {
                                                stringResource(R.string.summary_raise_to_aod)
                                            } else {
                                                stringResource(R.string.summary_unavailable_systemui_version)
                                            },
                                            enabled = raiseToAodSupported
                                        )
                                    }
                                }
                            }
                        }
                    )
                } else {
                LazyColumn(
                    state = when (SettingsTab.entries[page]) {
                        SettingsTab.STATUS -> statusListState
                        SettingsTab.SETTINGS -> settingsListState
                        SettingsTab.APP -> appListState
                        SettingsTab.ABOUT -> aboutListState
                    },
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(
                        top = if (isSettingsPage) 8.dp else innerPadding.calculateTopPadding() + 12.dp,
                        bottom = innerPadding.calculateBottomPadding() + 20.dp
                    )
                ) {
                // 为全局搜索登记每个 item 的序号:每次组合从 0 重算,索引与 LazyColumn 一致。
                var searchItemIndex = 0
                fun LazyListScope.tracked(
                    anchor: String? = null,
                    content: @Composable LazyItemScope.() -> Unit
                ) {
                    anchor?.let { searchAnchorIndices[it] = searchItemIndex }
                    item { content() }
                    searchItemIndex++
                }
                when (SettingsTab.entries[page]) {
                SettingsTab.STATUS -> {
                    tracked(GlobalSearchAnchor.STATUS_HOME) {
                        SmallTitle(text = stringResource(R.string.section_home_status))
                    }
                    tracked {
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
                            onOpenSurface = openAppearanceSection
                        )
                    }
                    tracked(GlobalSearchAnchor.STATUS_LIVE) {
                        SmallTitle(text = stringResource(R.string.section_live_status))
                    }
                    tracked {
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
                        // 「不显示非歌词内容」是应用级偏好(非按面),两面预览同值传入,
                        // 保证预览与实机投影层用同一分类函数(预览即实机)。
                        val lockscreenLive = collectLiveSnapshot(
                            lockscreenProfile.metadataParts,
                            lockscreenProfile.metadataSeparators,
                            lockscreenProfile.duetMarkers,
                            lockscreenProfile.hideAlbumWhenSameAsTitle,
                            hideCreditLines
                        )
                        val aodLive = collectLiveSnapshot(
                            aodProfile.metadataParts,
                            aodProfile.metadataSeparators,
                            aodProfile.duetMarkers,
                            aodProfile.hideAlbumWhenSameAsTitle,
                            hideCreditLines
                        )
                        var previewAod by rememberSaveable { mutableStateOf(false) }
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ConfigPresetEntryCard(onClick = onOpenConfigPresets)
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
                    tracked(GlobalSearchAnchor.STATUS_SOURCE) {
                        SmallTitle(text = stringResource(R.string.section_lyric_source))
                    }
                    tracked { LyricSourceSection(onOpenSourceDialog = { showSourceDialog = true }) }
                    tracked {
                        SettingsCard {
                            SwitchPreference(
                                filterNonMusicSources,
                                { enabled ->
                                    if (updateFilterNonMusicSources(context, enabled)) {
                                        filterNonMusicSources = enabled
                                    }
                                },
                                stringResource(R.string.setting_filter_non_music_sources),
                                summary = stringResource(
                                    R.string.summary_filter_non_music_sources
                                )
                            )
                            SwitchPreference(
                                hideCreditLines,
                                { enabled ->
                                    if (updateHideCreditLines(context, enabled)) {
                                        hideCreditLines = enabled
                                    }
                                },
                                stringResource(R.string.setting_hide_credit_lines),
                                summary = stringResource(
                                    R.string.summary_hide_credit_lines
                                )
                            )
                        }
                    }
                    tracked { SourceSetupHint() }
                    tracked {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.plugin_management_title),
                                summary = stringResource(R.string.plugin_management_summary),
                                onClick = onOpenPlugins
                            )
                        }
                    }
                    tracked(GlobalSearchAnchor.STATUS_RUNTIME) {
                        SmallTitle(text = stringResource(R.string.section_runtime_status))
                    }
                    tracked {
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
                    tracked(GlobalSearchAnchor.STATUS_PERMISSION) {
                        SmallTitle(text = stringResource(R.string.section_permission_status))
                    }
                    tracked {
                        SettingsCard {
                            PermissionStatusSection()
                        }
                    }
                }

                // 息屏设置/锁屏设置两个分段在上面的外观分段分支里渲染(即歌词外观设置本体),
                // 走到这个 LazyColumn 的只可能是「其他设置」。
                SettingsTab.SETTINGS -> {
                        tracked(GlobalSearchAnchor.SETTINGS_ENHANCEMENT) {
                            SettingsCard {
                                ArrowPreference(
                                    title = stringResource(R.string.title_lyric_enhancement),
                                    summary = stringResource(R.string.summary_lyric_enhancement),
                                    onClick = onOpenLyricEnhancement
                                )
                            }
                        }
                        tracked(GlobalSearchAnchor.SETTINGS_PLAYBACK) {
                            SmallTitle(text = stringResource(R.string.section_playback_behavior))
                        }
                        tracked {
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
                                // 「音乐暂停后」不可设置(开关关/模块不可用)时不渲染,可设置再出现——不再灰显。
                                if (pauseShowContent &&
                                    runtimeProfileAvailable && (aodSupported || lockscreenSupported)
                                ) {
                                    ArrowPreference(
                                        title = stringResource(R.string.setting_after_spotify_pauses),
                                        summary = pauseLingerLabel(context, pauseLingerMs),
                                        onClick = { showPauseLingerDialog = true }
                                    )
                                }
                            }
                        }
                        tracked(GlobalSearchAnchor.SETTINGS_BACKUP) {
                            SmallTitle(text = stringResource(R.string.section_config_backup))
                        }
                        tracked {
                            SettingsCard {
                                ArrowPreference(
                                    title = stringResource(R.string.config_preset_title),
                                    modifier = Modifier.searchRowAnchor(
                                        SearchRowKeys.SETTINGS_CONFIG_PRESET
                                    ),
                                    summary = stringResource(R.string.config_preset_summary),
                                    onClick = onOpenConfigPresets
                                )
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
                    tracked(GlobalSearchAnchor.APP_LANGUAGE) {
                        SmallTitle(text = stringResource(R.string.section_language))
                    }
                    tracked {
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
                    tracked(GlobalSearchAnchor.APP_NAVIGATION) {
                        SmallTitle(text = stringResource(R.string.section_app_navigation))
                    }
                    tracked {
                        SettingsCard {
                            SwitchPreference(
                                predictiveBackEnabled,
                                onPredictiveBackChanged,
                                stringResource(R.string.setting_predictive_back),
                                summary = stringResource(
                                    if (predictiveBackEnabled) R.string.summary_predictive_back
                                    else R.string.summary_predictive_back_disabled
                                )
                            )
                            SliderPreference(
                                value = backTriggerPercent.toFloat(),
                                onValueChange = { onBackTriggerPercentChanged(it.toInt()) },
                                title = stringResource(R.string.setting_back_trigger_threshold),
                                summary = stringResource(R.string.summary_back_trigger_threshold),
                                valueText = if (backTriggerPercent == 0) {
                                    stringResource(R.string.option_back_trigger_system)
                                } else {
                                    "$backTriggerPercent%"
                                },
                                valueRange = 0f..AppNavigationPreferences.MAX_BACK_TRIGGER_PERCENT.toFloat(),
                                steps = AppNavigationPreferences.MAX_BACK_TRIGGER_PERCENT /
                                    AppNavigationPreferences.BACK_TRIGGER_STEP_PERCENT - 1,
                                enabled = predictiveBackEnabled
                            )
                        }
                    }
                    tracked(GlobalSearchAnchor.APP_APPEARANCE) {
                        SmallTitle(text = stringResource(R.string.section_app_appearance))
                    }
                    tracked {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.section_app_appearance),
                                onClick = onOpenAppAppearance
                            )
                        }
                    }
                    tracked(GlobalSearchAnchor.APP_DEVELOPER) {
                        SmallTitle(text = stringResource(R.string.section_developer_options))
                    }
                    if (experimentalEligible) {
                        tracked {
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
                    tracked {
                        SettingsCard {
                            SwitchPreference(
                                forceLyricProvider,
                                { enabled ->
                                    prefs.edit().putBoolean(
                                        AodRenderPreferences.FORCE_LYRIC_PROVIDER,
                                        enabled
                                    ).apply()
                                    LyricProducers.arbiterOrNull()
                                        ?.updateForcePreferredSource(enabled)
                                    forceLyricProvider = enabled
                                },
                                stringResource(R.string.setting_force_lyric_provider),
                                summary = stringResource(R.string.summary_force_lyric_provider)
                            )
                        }
                    }
                    tracked {
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
                    tracked {
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
                    tracked(GlobalSearchAnchor.APP_SYSTEM) {
                        SmallTitle(text = stringResource(R.string.section_system_integration))
                    }
                    tracked {
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
                    tracked(GlobalSearchAnchor.ABOUT_HOME) {
                        SmallTitle(text = stringResource(R.string.section_about))
                    }
                    tracked {
                        AboutHeroCard(
                            systemUiVersion = capabilityReport.systemUiVersion,
                            aodVersion = capabilityReport.aodVersion
                        )
                    }
                    tracked { AboutAuthorCard() }
                    tracked(GlobalSearchAnchor.ABOUT_LINKS) {
                        SettingsCard {
                            ArrowPreference(
                                title = stringResource(R.string.title_onboarding),
                                modifier = Modifier.searchRowAnchor(
                                    SearchRowKeys.ABOUT_ONBOARDING
                                ),
                                summary = stringResource(R.string.summary_onboarding),
                                onClick = onOpenOnboarding
                            )
                            ArrowPreference(
                                title = stringResource(R.string.title_help),
                                modifier = Modifier.searchRowAnchor(SearchRowKeys.ABOUT_HELP),
                                onClick = onOpenHelp
                            )
                            ArrowPreference(
                                title = stringResource(R.string.title_changelog),
                                modifier = Modifier.searchRowAnchor(SearchRowKeys.ABOUT_CHANGELOG),
                                onClick = onOpenChangelog
                            )
                            ArrowPreference(
                                title = stringResource(R.string.title_contributors),
                                modifier = Modifier.searchRowAnchor(
                                    SearchRowKeys.ABOUT_CONTRIBUTORS
                                ),
                                onClick = onOpenContributors
                            )
                            ArrowPreference(
                                title = stringResource(R.string.title_licenses),
                                modifier = Modifier.searchRowAnchor(SearchRowKeys.ABOUT_LICENSES),
                                summary = stringResource(R.string.summary_licenses),
                                onClick = onOpenLicenses
                            )
                        }
                    }
                    // 「支持」入口按需求暂时隐藏（保留字符串与 PROJECT_SITE_URL 以便恢复）。
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
                                    openExternalUrl(context, GITEE_CNPLUS_URL)
                                }
                            )
                            ArrowPreference(
                                title = stringResource(R.string.action_hyperglow_qq_group),
                                onClick = {
                                    openExternalUrl(context, QQ_GROUP_URL)
                                }
                            )
                        }
                    }
                }

                }
                }
                }
            }
        }
    }
    if (showGlobalSearch) {
        GlobalSearchOverlay(
            onDismiss = { showGlobalSearch = false },
            experimentalEligible = experimentalEligible,
            onSelect = { entry ->
                showGlobalSearch = false
                // 新的选择覆盖旧目标;子页条目不需要主页滚动。
                pendingSearchScroll = null
                when (entry.destination) {
                    GlobalSearchDestination.APP_APPEARANCE -> onOpenAppAppearance()
                    GlobalSearchDestination.AOD_BEHAVIOR -> onOpenAodBehavior()
                    null -> {
                        if (entry.tab == SettingsTab.SETTINGS) {
                            // 外观通用项保持当前曲面(在其他设置时回落到息屏);锁屏专属项固定 1;
                            // 其他设置固定 2。这样在锁屏外观里搜索不会莫名跳回息屏。
                            settingsSectionIndex = when {
                                entry.appearanceSection != null -> entry.appearanceSection
                                GlobalSearchAnchor.isAppearanceAnchor(entry.anchor) ->
                                    if (settingsSectionIndex == 2) 0 else settingsSectionIndex
                                else -> 2
                            }
                        }
                        entry.anchor?.let {
                            pendingSearchScroll = PendingSearchScroll(
                                entry.tab,
                                it,
                                entry.rowAnchor
                            )
                        }
                        scope.launch { pagerState.animateScrollToPage(entry.tab.ordinal) }
                    }
                }
            }
        )
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
                            // 两件事互相独立,顺序有意为之:先启动挂钩进程重启(root 杀进程),
                            // 再重建歌词源订阅。此前顺序相反——歌词源重建先占住主线程,它抛异常
                            // 或长时间不返回时,勾选歌词源会把其他进程的重启整段吞掉(真机反馈)。
                            // 反过来,杀进程已在 IO 线程上跑起来,歌词源侧再出什么问题都不影响它。
                            if (hookTargetSelected) {
                                scope.launch {
                                    val restarted = runCatching {
                                        ShellUtils.restartHookedProcesses(
                                            systemUi = restartSystemUiTarget,
                                            miuiAod = restartAodTarget,
                                            hyperglowApp = restartHyperglowTarget
                                        )
                                    }.getOrDefault(false)
                                    showRestartResult(restarted)
                                }
                            }
                            if (restartLyricSource) {
                                // 全部歌词源一起重建:歌词可能来自回退源,只重建选中源会在
                                // 默认 SPICY 首选下空转(见 LyricProducerArbiter.restartAll)。
                                runCatching { LyricProducers.arbiterOrNull()?.restartAll() }
                                    .onFailure {
                                        AppLog.w(
                                            "HomeScreen",
                                            "restart lyric sources failed",
                                            it
                                        )
                                    }
                                if (!hookTargetSelected) {
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.toast_lyric_source_restarted),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
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

/**
 * 第一页（状态页）预览上方的配置预设入口：图标 + 标题 + 一句话说明。
 * 走 [BasicComponent]，与设置页其它入口同一套卡片材质，但放在歌词预览正上方，进得更快。
 */
@Composable
private fun ConfigPresetEntryCard(onClick: () -> Unit) {
    SettingsCard {
        BasicComponent(
            title = stringResource(R.string.config_preset_title),
            modifier = Modifier.searchRowAnchor(SearchRowKeys.STATUS_CONFIG_PRESET),
            summary = stringResource(R.string.config_preset_entry_summary),
            startAction = {
                Icon(
                    imageVector = MiuixIcons.Tune,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
            },
            onClick = onClick
        )
    }
}
