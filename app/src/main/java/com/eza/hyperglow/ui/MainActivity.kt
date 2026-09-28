package com.eza.hyperglow.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.R
import com.eza.hyperglow.aod.AodLyricBridgeService
import top.yukonga.miuix.kmp.theme.MiuixTheme

class MainActivity : ComponentActivity() {
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
            val darkTheme = isDarkTheme(appAppearance, isSystemInDarkTheme())
            SideEffect {
                applySystemBarIcons(this@MainActivity, appAppearance, darkTheme)
            }
            MiuixTheme(controller = controller) {
                CompositionLocalProvider(
                    LocalAppBackgroundActive provides appAppearance.hasBackgroundImage
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AppBackgroundLayer(
                            appearance = appAppearance,
                            darkTheme = darkTheme
                        )
                        var editingSurface by rememberSaveable { mutableStateOf<String?>(null) }
                        var selectedTabName by rememberSaveable {
                            mutableStateOf(SettingsTab.OVERVIEW.name)
                        }
                        AnimatedContent(
                            targetState = editingSurface,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                if (targetState != null) {
                                    (slideInHorizontally(
                                        animationSpec = tween(320, easing = FastOutSlowInEasing),
                                        initialOffsetX = { it }
                                    ) + fadeIn(tween(220))) togetherWith
                                        (slideOutHorizontally(
                                            animationSpec = tween(320, easing = FastOutSlowInEasing),
                                            targetOffsetX = { -it }
                                        ) + fadeOut(tween(180)))
                                } else {
                                    (slideInHorizontally(
                                        animationSpec = tween(320, easing = FastOutSlowInEasing),
                                        initialOffsetX = { -it }
                                    ) + fadeIn(tween(220))) togetherWith
                                        (slideOutHorizontally(
                                            animationSpec = tween(320, easing = FastOutSlowInEasing),
                                            targetOffsetX = { it }
                                        ) + fadeOut(tween(180)))
                                }
                            },
                            label = "settingsDestination"
                        ) { surface ->
                            if (surface == DIAGNOSTICS_DESTINATION) {
                                DiagnosticsScreen(onBack = { editingSurface = null })
                            } else if (surface == PLUGIN_DESTINATION) {
                                PluginManagementScreen(onBack = { editingSurface = null })
                            } else if (surface == AOD_BEHAVIOR_DESTINATION) {
                                AodBehaviorScreen(onBack = { editingSurface = null })
                            } else if (surface == APP_APPEARANCE_DESTINATION) {
                                AppAppearanceScreen(
                                    onBack = { editingSurface = null },
                                    onAppearanceChanged = {
                                        appAppearance = loadAppUiAppearance(this@MainActivity)
                                    }
                                )
                            } else if (surface != null) {
                                LyricLayoutScreen(
                                    initialSurface = surface,
                                    onBack = { editingSurface = null }
                                )
                            } else {
                                HomeScreen(
                                    showRestartResult = ::showRestartResult,
                                    selectedTabName = selectedTabName,
                                    onSelectTab = { selectedTabName = it },
                                    onOpenDiagnostics = { editingSurface = DIAGNOSTICS_DESTINATION },
                                    onOpenLyricLayout = { target -> editingSurface = target },
                                    onOpenPlugins = { editingSurface = PLUGIN_DESTINATION },
                                    onOpenAodBehavior = { editingSurface = AOD_BEHAVIOR_DESTINATION },
                                    onOpenAppAppearance = { editingSurface = APP_APPEARANCE_DESTINATION }
                                )
                            }
                        }
                    }
                }
            }
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

/** 背景图片层:图片铺满后叠遮罩保证可读性;无图片或解码失败时不绘制任何内容。 */
@Composable
private fun AppBackgroundLayer(
    appearance: AppUiAppearance,
    darkTheme: Boolean
) {
    if (!appearance.hasBackgroundImage) return
    val context = LocalContext.current
    val bitmap = remember(appearance.backgroundImageMtime) {
        loadAppBackgroundBitmap(context)?.asImageBitmap()
    } ?: return
    Box(modifier = Modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
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
