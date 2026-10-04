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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color as ComposeColor
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
                        LocalAppLayerBackdrop provides layerBackdrop
                    ) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            AppBackgroundLayer(
                                appearance = appAppearance,
                                darkTheme = darkTheme,
                                layerBackdrop = layerBackdrop
                            )
                            val backStack = rememberNavBackStack<AppRoute>(AppRoute.Home)
                            var selectedTabName by rememberSaveable {
                                mutableStateOf(SettingsTab.STATUS.name)
                            }
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
                                        HomeScreen(
                                            showRestartResult = ::showRestartResult,
                                            selectedTabName = selectedTabName,
                                            onSelectTab = { selectedTabName = it },
                                            onOpenDiagnostics = { backStack.pushRoute(AppRoute.Diagnostics) },
                                            onOpenPlugins = { backStack.pushRoute(AppRoute.Plugins) },
                                            onOpenAodBehavior = { backStack.pushRoute(AppRoute.AodBehavior) },
                                            onOpenAppAppearance = { backStack.pushRoute(AppRoute.AppAppearance) },
                                            onOpenHelp = { backStack.pushRoute(AppRoute.Help) },
                                            onOpenChangelog = { backStack.pushRoute(AppRoute.Changelog) },
                                            onOpenContributors = { backStack.pushRoute(AppRoute.Contributors) },
                                            onOpenLicenses = { backStack.pushRoute(AppRoute.Licenses) },
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
                                    entry<AppRoute.Diagnostics> {
                                        DiagnosticsScreen(onBack = { backStack.popRoute() })
                                    }
                                    entry<AppRoute.Plugins> {
                                        PluginManagementScreen(onBack = { backStack.popRoute() })
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
                                    entry<AppRoute.Help> { HelpScreen(onBack = { backStack.popRoute() }) }
                                    entry<AppRoute.Changelog> { ChangelogScreen(onBack = { backStack.popRoute() }) }
                                    entry<AppRoute.Contributors> { ContributorsScreen(onBack = { backStack.popRoute() }) }
                                    entry<AppRoute.Licenses> { LicensesScreen(onBack = { backStack.popRoute() }) }
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
                        }
                    }
                }
            }
        }
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

/** 背景图片层:图片铺满并按设置模糊后叠遮罩保证可读性;无图片或解码失败时不绘制任何内容。
 *  该层同时作为 backdrop 源被记录,供各屏顶栏的渐变模糊采样。 */
@Composable
private fun AppBackgroundLayer(
    appearance: AppUiAppearance,
    darkTheme: Boolean,
    layerBackdrop: LayerBackdrop
) {
    if (!appearance.hasBackgroundImage) return
    val context = LocalContext.current
    val bitmap = remember(appearance.backgroundImageMtime) {
        loadAppBackgroundBitmap(context)?.asImageBitmap()
    } ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .layerBackdrop(layerBackdrop)
    ) {
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
