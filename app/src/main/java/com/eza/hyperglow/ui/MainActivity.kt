package com.eza.hyperglow.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.R
import com.eza.hyperglow.aod.AodLyricBridgeService
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.nav.core.NavDisplay
import top.yukonga.miuix.kmp.nav.core.NavDisplayEffects
import top.yukonga.miuix.kmp.nav.core.rememberNavBackStack
import top.yukonga.miuix.kmp.nav.core.rememberNavSystemCornerRadius
import top.yukonga.miuix.kmp.nav.gesture.PredictiveBackHandler
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.WindowNavigationEventScope

class MainActivity : ComponentActivity() {
    /** 最近一次外观设置与解析后的深色状态:窗口重新获焦时据此再下发一次系统栏图标(见 [onWindowFocusChanged])。 */
    private var lastAppearance: AppUiAppearance? = null
    private var lastDarkTheme: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        // 从前台上下文启动 AodLyricBridgeService。MIUI 禁止从后台启动前台服务,
        // HyperGlowApplication.onCreate 的 best-effort 尝试在息屏/后台时会失败;
        // Activity 处于前台时补启,确保服务进入前台状态以对抗 GreezeManager 冻结。
        runCatching {
            startForegroundService(Intent(this, AodLyricBridgeService::class.java))
        }.onFailure { error ->
            AppLog.w("MainActivity", "startForegroundService denied: ${error.message}")
        }
        // 插件应用内自动下载/自动更新:启动后延迟检查 plugins 滚动发行(幂等,失败静默)。
        PluginUpdateCenter.scheduleStartupCheck(applicationContext)
        setContent {
            var appAppearance by remember { mutableStateOf(loadAppUiAppearance(this@MainActivity)) }
            val controller = remember(appAppearance) { appThemeController(appAppearance) }
            val appFontFamily = appTextFontFamily(appAppearance.fontFamily)
            val appTextStyles = remember(appFontFamily) { appMiuixTextStyles(appFontFamily) }
            val darkTheme = isDarkTheme(appAppearance, isSystemInDarkTheme())
            SideEffect {
                lastAppearance = appAppearance
                lastDarkTheme = darkTheme
                applySystemBarIcons(this@MainActivity, appAppearance, darkTheme)
            }
            val layerBackdrop = rememberLayerBackdrop()
            MiuixTheme(controller = controller, textStyles = appTextStyles) {
                AppTextOverride(textColorArgb = appAppearance.textColorArgb) {
                    CompositionLocalProvider(
                        LocalAppBackgroundActive provides appAppearance.hasBackgroundImage,
                        LocalAppControlColor provides appAppearance.controlColorArgb?.let { ComposeColor(it) },
                        LocalAppControlOpacity provides appAppearance.controlOpacityPercent / 100f,
                        LocalAppGlassMaterial provides appAppearance.glassMaterial,
                        LocalAppGlassHdrHighlight provides appAppearance.hdrHighlight,
                        LocalAppGlassTuning provides appAppearance.glassTuning,
                        LocalAppGlassBlurPercent provides appAppearance.glassBlurPercent,
                        LocalAppLayerBackdrop provides layerBackdrop
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AppBackgroundLayer(
                                appearance = appAppearance,
                                darkTheme = darkTheme,
                                layerBackdrop = layerBackdrop
                            )
                            val backStack = rememberNavBackStack<AppRoute>(AppRoute.Home)
                            // 首次使用引导:盖在主页之上,完成后写入标记并从组合中移除。
                            // manual 区分「首次启动」与「关于页手动重看」:前者不允许返回退出,
                            // 否则未看完就退出、下次启动还会再弹一遍。
                            var showOnboarding by rememberSaveable {
                                mutableStateOf(OnboardingPreferences.shouldShow(this@MainActivity))
                            }
                            var onboardingManual by rememberSaveable { mutableStateOf(false) }
                            var selectedTabName by rememberSaveable {
                                mutableStateOf(SettingsTab.STATUS.name)
                            }
                            // 配置预设应用后会整体改写设置面；自增一次让 HomeScreen 以 key 重建,
                            // 避免保留在 remember 里的旧开关值/旧文档造成界面与落盘状态不一致。
                            var configRevision by remember { mutableStateOf(0) }
                            // 预设变更（删除/上传/存本地）后自增，通知仍在返回栈里的列表页重读存储与云端缓存。
                            var presetRevision by remember { mutableStateOf(0) }
                            var predictiveBackEnabled by remember {
                                mutableStateOf(
                                    AppNavigationPreferences.readPredictiveBack(this@MainActivity)
                                )
                            }
                            var backTriggerPercent by remember {
                                mutableStateOf(
                                    AppNavigationPreferences.readBackTriggerPercent(this@MainActivity)
                                )
                            }
                            val backGestureTracker = remember { AppBackGestureTracker() }
                            val backVeto = remember { AppBackVetoState() }
                            val navTransition = remember { appNavTransition(backGestureTracker) }
                            // 否决恢复:pop 被组合观察到之后再压回同一路由(见 AppBackVetoState)。
                            LaunchedEffect(backStack.size) { backVeto.restoreIfPending(backStack) }
                            val navCornerRadius = rememberNavSystemCornerRadius()
                            val navEffects = remember(navCornerRadius) {
                                NavDisplayEffects(
                                    cornerClipRadius = navCornerRadius,
                                    // 调暗遮罩关闭:背景图片模式下页面容器色为透明(appSurfaceColor()),
                                    // 静止态的下层遮罩会整体压暗壁纸;被覆盖层的观感由 appNavTransition 负责。
                                    dimAmount = 0f
                                )
                            }
                            // 显式提供本窗口的返回事件派发器(与 miuix 窗口组件同一机制):
                            // NavDisplay 的预测性返回处理器读 LocalNavigationEventDispatcherOwner,
                            // 取不到时会静默失活(返回手势完全无响应),这里从视图树解析后下发。
                            WindowNavigationEventScope {
                                NavDisplay(
                                    backStack = backStack,
                                    modifier = Modifier.fillMaxSize(),
                                    onBack = {
                                        val release = backGestureTracker.release()
                                        val belowThreshold = predictiveBackEnabled &&
                                            backTriggerPercent > 0 &&
                                            release != null &&
                                            !backTriggerReached(
                                                progress = release.progress,
                                                velocity = release.velocity,
                                                thresholdPercent = backTriggerPercent
                                            )
                                        if (belowThreshold) {
                                            // 拖动不足阈值:否决这次提交,页面弹回(跟手预览保持原生)。
                                            backVeto.veto(backStack)
                                        } else {
                                            backStack.popRoute()
                                        }
                                    },
                                    transition = navTransition,
                                    effects = navEffects
                                ) {
                                    entry<AppRoute.Home> {
                                        key(configRevision) {
                                            HomeScreen(
                                                showRestartResult = ::showRestartResult,
                                                selectedTabName = selectedTabName,
                                                onSelectTab = { selectedTabName = it },
                                                onOpenDiagnostics = { backStack.pushRoute(AppRoute.Diagnostics) },
                                                onOpenPlugins = { backStack.pushRoute(AppRoute.Plugins) },
                                                onOpenAodBehavior = { backStack.pushRoute(AppRoute.AodBehavior) },
                                                onOpenAppAppearance = { backStack.pushRoute(AppRoute.AppAppearance) },
                                                onOpenOnboarding = {
                                                    onboardingManual = true
                                                    showOnboarding = true
                                                },
                                                onOpenConfigPresets = {
                                                    backStack.pushRoute(AppRoute.ConfigPresets)
                                                },
                                                onOpenHelp = { backStack.pushRoute(AppRoute.Help) },
                                                onOpenChangelog = { backStack.pushRoute(AppRoute.Changelog) },
                                                onOpenContributors = { backStack.pushRoute(AppRoute.Contributors) },
                                                onOpenLicenses = { backStack.pushRoute(AppRoute.Licenses) },
                                                onOpenLyricEnhancement = {
                                                    backStack.pushRoute(AppRoute.LyricEnhancement)
                                                },
                                                floatingNavBar = appAppearance.floatingNavBar,
                                                predictiveBackEnabled = predictiveBackEnabled,
                                                onPredictiveBackChanged = { enabled ->
                                                    if (AppNavigationPreferences.writePredictiveBack(
                                                            this@MainActivity,
                                                            enabled
                                                        )
                                                    ) {
                                                        predictiveBackEnabled = enabled
                                                    }
                                                },
                                                backTriggerPercent = backTriggerPercent,
                                                onBackTriggerPercentChanged = { percent ->
                                                    val normalized = normalizeBackTriggerPercent(percent)
                                                    if (normalized != backTriggerPercent) {
                                                        backTriggerPercent = normalized
                                                        AppNavigationPreferences.writeBackTriggerPercent(
                                                            this@MainActivity,
                                                            normalized
                                                        )
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    entry<AppRoute.Diagnostics> {
                                        DiagnosticsScreen(onBack = { backStack.popRoute() })
                                    }
                                    entry<AppRoute.Plugins> {
                                        PluginManagementScreen(
                                            onBack = { backStack.popRoute() },
                                            onOpenSettings = { pluginId ->
                                                backStack.pushRoute(
                                                    AppRoute.PluginSettings(pluginId)
                                                )
                                            },
                                            onOpenCache = { pluginId ->
                                                backStack.pushRoute(AppRoute.PluginCache(pluginId))
                                            },
                                            onOpenChainCache = {
                                                backStack.pushRoute(AppRoute.PluginChainCache)
                                            }
                                        )
                                    }
                                    entry<AppRoute.AodBehavior> {
                                        AodBehaviorScreen(onBack = { backStack.popRoute() })
                                    }
                                    entry<AppRoute.AppAppearance> {
                                        AppAppearanceScreen(
                                            onBack = { backStack.popRoute() },
                                            onAppearanceChanged = {
                                                appAppearance = loadAppUiAppearance(this@MainActivity)
                                            }
                                        )
                                    }
                                    entry<AppRoute.ConfigPresets> {
                                        ConfigPresetScreen(
                                            refreshKey = presetRevision,
                                            onBack = { backStack.popRoute() },
                                            onOpenPreset = { presetId ->
                                                backStack.pushRoute(
                                                    AppRoute.ConfigPresetDetail(presetId)
                                                )
                                            }
                                        )
                                    }
                                    entry<AppRoute.ConfigPresetDetail> { route ->
                                        ConfigPresetDetailScreen(
                                            presetId = route.presetId,
                                            onBack = { backStack.popRoute() },
                                            onApplied = {
                                                // 整套设置已被预设改写:重建 Home 的 remembered 状态,
                                                // 并刷新应用外观(主题/背景/玻璃控件即时生效)。
                                                configRevision++
                                                appAppearance =
                                                    loadAppUiAppearance(this@MainActivity)
                                            },
                                            onPresetChanged = { presetRevision++ }
                                        )
                                    }
                                    entry<AppRoute.Help> { HelpScreen(onBack = { backStack.popRoute() }) }
                                    entry<AppRoute.Changelog> { ChangelogScreen(onBack = { backStack.popRoute() }) }
                                    entry<AppRoute.Contributors> { ContributorsScreen(onBack = { backStack.popRoute() }) }
                                    entry<AppRoute.Licenses> { LicensesScreen(onBack = { backStack.popRoute() }) }
                                    entry<AppRoute.LyricEnhancement> {
                                        LyricEnhancementScreen(
                                            onBack = { backStack.popRoute() },
                                            onOpenPluginSettings = { pluginId ->
                                                backStack.pushRoute(
                                                    AppRoute.PluginSettings(pluginId)
                                                )
                                            }
                                        )
                                    }
                                    entry<AppRoute.PluginSettings> { route ->
                                        PluginSettingsScreen(
                                            pluginId = route.pluginId,
                                            onBack = { backStack.popRoute() },
                                            onOpenCache = { pluginId ->
                                                backStack.pushRoute(AppRoute.PluginCache(pluginId))
                                            }
                                        )
                                    }
                                    entry<AppRoute.PluginCache> { route ->
                                        PluginCacheScreen(
                                            pluginId = route.pluginId,
                                            onBack = { backStack.popRoute() }
                                        )
                                    }
                                    entry<AppRoute.PluginChainCache> {
                                        PluginChainCacheScreen(
                                            onBack = { backStack.popRoute() }
                                        )
                                    }
                                }
                                if (!predictiveBackEnabled) {
                                    // 关闭预测性返回:自己消费返回手势(不做跟手预览),松手直接返回上一页。
                                    // 组合在 NavDisplay 之后 → 注册更晚 → 仲裁优先;弹窗比本处理器更晚组合,仍先消费。
                                    PredictiveBackHandler(
                                        enabled = backStack.size > 1,
                                        onProgress = { _ -> },
                                        onCommit = { backStack.popRoute() },
                                        onCancel = {}
                                    )
                                }
                            }
                            if (showOnboarding) {
                                OnboardingScreen(
                                    onFinish = {
                                        OnboardingPreferences.markCompleted(this@MainActivity)
                                        showOnboarding = false
                                        onboardingManual = false
                                    },
                                    // 首次启动不传 onBack:顶栏不显示返回按钮,返回手势也被引导层消费,
                                    // 只能「跳过」或「开始使用」。手动重看时允许返回且不写完成标记。
                                    onBack = if (onboardingManual) {
                                        {
                                            showOnboarding = false
                                            onboardingManual = false
                                        }
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        // MIUI 长截屏(截长屏)兼容:代理 View 必须在 setContent 之后挂载(见 LongScreenshotCompat.kt)。
        installLongScreenshotProxy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // MIUI 在窗口重新获焦时会按自己的规则重算状态栏图标颜色,这里按当前设置再下发一次(幂等)。
        val appearance = lastAppearance ?: return
        if (hasFocus) {
            applySystemBarIcons(this, appearance, lastDarkTheme)
        }
    }

    private fun showRestartResult(succeeded: Boolean) {
        Toast.makeText(
            this,
            getString(
                if (succeeded) R.string.toast_systemui_restarted
                else R.string.toast_systemui_restart_failed
            ),
            Toast.LENGTH_LONG
        ).show()
    }
}

/** 背景层:图片铺满并按设置模糊后叠遮罩保证可读性;启用了玻璃材质时即使没有图片也绘制
 *  主题底色,为顶栏/卡片/底栏的 backdrop 采样提供不透明内容(否则模糊会采到透明黑)。
 *  该层同时作为 backdrop 源被记录,供各屏顶栏与玻璃表面采样。
 *  解码(本地估算 50–300ms/次,真机未测)搬 IO 通道:以 mtime 为代次键,换图时
 *  latest wins(旧结果不覆盖新选择);首次加载完成前保留主题底色,换图期间保留上一张,
 *  避免闪回空底。 */
@Composable
private fun AppBackgroundLayer(
    appearance: AppUiAppearance,
    darkTheme: Boolean,
    layerBackdrop: LayerBackdrop
) {
    val materialActive = appearance.glassMaterial != AppGlassMaterial.NONE
    if (!appearance.hasBackgroundImage && !materialActive) return
    val context = LocalContext.current
    val bitmap = if (appearance.hasBackgroundImage) {
        rememberAppAsyncResource<ImageBitmap>(appearance.backgroundImageMtime) {
            loadAppBackgroundBitmap(context)?.asImageBitmap()
        }
    } else {
        null
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layerBackdrop(layerBackdrop)
            .background(MiuixTheme.colorScheme.surface)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (appearance.backgroundBlurPercent > 0) {
                            Modifier.blur(
                                radius = backgroundBlurRadius(appearance.backgroundBlurPercent),
                                edgeTreatment = BlurredEdgeTreatment.Unbounded
                            )
                        } else {
                            Modifier
                        }
                    )
            )
            val dim = appearance.backgroundDimPercent / 100f
            if (dim > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            (if (darkTheme) ComposeColor(0xFF000000) else ComposeColor(0xFFFFFFFF))
                                .copy(alpha = dim)
                        )
                )
            }
        }
    }
}
