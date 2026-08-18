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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.R
import com.eza.hyperglow.aod.AodLyricBridgeService
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 模块入口 Activity:承载 MiuixTheme 主题容器,在首页/外观编辑页/诊断页之间切换。 */

private const val DIAGNOSTICS_DESTINATION = "__diagnostics__"

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
            val controller = remember { ThemeController(colorSchemeMode = ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
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
                            onOpenLyricLayout = { target -> editingSurface = target }
                        )
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
