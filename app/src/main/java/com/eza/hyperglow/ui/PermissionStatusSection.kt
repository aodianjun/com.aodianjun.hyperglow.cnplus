package com.eza.hyperglow.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.eza.hyperglow.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 检查并显示 HyperGlow 运行所需的关键权限与运行状态:
 *
 * 1. 通知权限 —— 前台服务必须有可见通知才能保持前台状态(Android 13+ 需用户授权)
 * 2. 歌词前台服务运行状态 —— AodLyricBridgeService 是否已激活为前台服务
 * 3. 电池优化 —— 若系统对 HyperGlow 启用电池优化,MIUI GreezeManager 在息屏时会反复冻结
 *    进程,导致 AOD/锁屏歌词不更新
 *
 * 任一项异常都会破坏息屏歌词更新链路。
 */
@Composable
internal fun PermissionStatusSection() {
    val context = LocalContext.current

    var notificationGranted by remember {
        mutableStateOf(checkNotificationPermission(context))
    }
    var foregroundServiceRunning by remember {
        mutableStateOf(checkForegroundServiceRunning(context))
    }
    var batteryWhitelisted by remember {
        mutableStateOf(checkBatteryOptimizationWhitelisted(context))
    }
    var rootGranted by remember { mutableStateOf(false) }
    var queryAllPackages by remember {
        mutableStateOf(checkQueryAllPackages(context))
    }
    val scope = rememberCoroutineScope()

    // 从系统设置返回时刷新权限状态
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        notificationGranted = checkNotificationPermission(context)
        foregroundServiceRunning = checkForegroundServiceRunning(context)
        batteryWhitelisted = checkBatteryOptimizationWhitelisted(context)
        queryAllPackages = checkQueryAllPackages(context)
    }

    LaunchedEffect(Unit) {
        // 进入页面时刷新一次(系统设置变更不会自动通知)
        notificationGranted = checkNotificationPermission(context)
        foregroundServiceRunning = checkForegroundServiceRunning(context)
        batteryWhitelisted = checkBatteryOptimizationWhitelisted(context)
        queryAllPackages = checkQueryAllPackages(context)
        rootGranted = checkRootGranted()
    }

    // 1. 通知权限
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationGranted) {
        ArrowPreference(
            title = stringResource(R.string.label_notification_permission),
            summary = stringResource(R.string.summary_notification_permission_denied),
            onClick = {
                permissionLauncher.launch(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                )
            }
        )
    } else {
        BasicComponent(
            title = stringResource(R.string.label_notification_permission),
            summary = stringResource(R.string.summary_notification_permission_granted)
        )
    }

    // 2. 前台服务运行状态(只读展示)
    BasicComponent(
        title = stringResource(R.string.label_foreground_service),
        summary = if (foregroundServiceRunning) {
            stringResource(R.string.summary_foreground_service_running)
        } else {
            stringResource(R.string.summary_foreground_service_not_running)
        }
    )

    // 3. 电池优化白名单
    if (!batteryWhitelisted) {
        ArrowPreference(
            title = stringResource(R.string.label_battery_optimization),
            summary = stringResource(R.string.summary_battery_optimization_optimized),
            onClick = {
                runCatching {
                    permissionLauncher.launch(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                    )
                }.onFailure {
                    // 部分设备不支持直接弹窗,回退到电池优化列表页
                    runCatching {
                        permissionLauncher.launch(
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    }
                }
            }
        )
    } else {
        BasicComponent(
            title = stringResource(R.string.label_battery_optimization),
            summary = stringResource(R.string.summary_battery_optimization_whitelisted)
        )
    }

    // 4. Root 权限(重启 SystemUI 等系统级操作需要)
    if (rootGranted) {
        BasicComponent(
            title = stringResource(R.string.label_root_permission),
            summary = stringResource(R.string.summary_root_permission_granted)
        )
    } else {
        ArrowPreference(
            title = stringResource(R.string.label_root_permission),
            summary = stringResource(R.string.summary_root_permission_denied),
            onClick = {
                // 触发一次 su 授权请求,弹出 Root 授权对话框
                scope.launch {
                    requestRootAccess()
                    rootGranted = checkRootGranted()
                }
            }
        )
    }

    // 5. 获取应用列表(枚举已安装应用,用于检测可用的音乐应用)
    if (queryAllPackages) {
        BasicComponent(
            title = stringResource(R.string.label_query_all_packages),
            summary = stringResource(R.string.summary_query_all_packages_granted)
        )
    } else {
        ArrowPreference(
            title = stringResource(R.string.label_query_all_packages),
            summary = stringResource(R.string.summary_query_all_packages_denied),
            onClick = {
                permissionLauncher.launch(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            }
        )
    }
}

private fun checkQueryAllPackages(context: Context): Boolean {
    // Android 11+ 枚举全部已安装应用需要 QUERY_ALL_PACKAGES 特殊权限
    // (MIUI 上表现为「获取应用列表」开关);低于 11 无此限制,视为已授予。
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.checkSelfPermission(Manifest.permission.QUERY_ALL_PACKAGES) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

private suspend fun checkRootGranted(): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        output.contains("uid=0")
    }.getOrDefault(false)
}

private suspend fun requestRootAccess() {
    withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
        }
    }
}

private fun checkNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    return manager.areNotificationsEnabled()
}

/**
 * 检查 AodLyricBridgeService 是否作为前台服务运行。前台服务会在 [NotificationManager] 中
 * 保留一个 ongoing 通知,所以检查通知是否启用 + 渠道未关闭即代表前台服务能正常显示通知。
 *
 * getRunningServices 在 Android 8+ 仅返回本应用自己的服务,无法跨进程检查 SystemUI 侧。
 * 这里只验证 HyperGlow 主进程是否能正常维持前台服务状态 —— 因为 AodLyricBridgeService
 * 默认运行在 HyperGlow 主进程。
 */
private fun checkForegroundServiceRunning(context: Context): Boolean {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (!manager.areNotificationsEnabled()) return false
    val channel = manager.getNotificationChannel("lyric_bridge") ?: return false
    return channel.importance != NotificationManager.IMPORTANCE_NONE
}

private fun checkBatteryOptimizationWhitelisted(context: Context): Boolean {
    val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}
