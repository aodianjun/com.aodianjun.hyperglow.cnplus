package com.eza.hyperglow.aod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.R

/** 未能解析出 SystemUI 的 uid。绝不会匹配任何真实调用者。 */
internal const val UNRESOLVED_SYSTEM_UI_UID = -1

/**
 * SystemUI 并不总是 uid 1000。HyperOS 3 的 Xiaomi 17 系列把 com.android.systemui
 * 跑在普通应用 uid 下,硬编码 system-uid 检查会拒绝一切正常工作的 Hook 调用:模块明明
 * 绑定、注册并上报了 capability,而 app 什么都没存,显示 no_systemui_report。改用包名
 * 解析出的 uid 匹配,在 SystemUI 共享 android.uid.system 的设备上仍得到 1000,语义一致;
 * 在新设备上则正确放行。
 */
internal fun isSystemUiBridgeCaller(callingUid: Int, systemUiUid: Int): Boolean =
    systemUiUid != UNRESOLVED_SYSTEM_UI_UID && callingUid == systemUiUid

class AodLyricBridgeService : Service() {
    private val binder = object : IAodLyricBridge.Stub() {
        override fun registerCallback(callback: IAodLyricCallback?) {
            if (callback != null && isSystemUiCaller()) {
                AodStateBridge.register(callback)
                AppLog.bootstrap(TAG, "systemui_callback_accepted")
            }
        }

        override fun unregisterCallback(callback: IAodLyricCallback?) {
            if (callback != null && isSystemUiCaller()) AodStateBridge.unregister(callback)
        }

        override fun reportCapabilities(report: Bundle?) {
            if (report != null && isSystemUiCaller()) {
                XiaomiCapabilityStore.save(this@AodLyricBridgeService, report)
                AppLog.bootstrap(TAG, "systemui_capability_report_accepted")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 提升为前台服务,避免 MIUI GreezeManager 在息屏时反复冻结 HyperGlow 进程,
        // 导致 AOD/锁屏歌词无法更新。bindService 不会触发 onStartCommand,所以
        // HyperGlowApplication.onCreate 主动 startForegroundService 来激活前台状态。
        startForeground(
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        // "常驻通知"关闭时:保持前台服务(进程不被冻结)但隐藏通知栏,让通知栏更干净。
        if (!AodRenderPreferences.read(this).persistentNotification) {
            getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
        AppLog.bootstrap(TAG, "foreground_service_started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun isSystemUiCaller(): Boolean {
        val uid = Binder.getCallingUid()
        val systemUiUid = resolveSystemUiUid()
        val allowed = isSystemUiBridgeCaller(uid, systemUiUid)
        if (!allowed) {
            // 打印两个 uid,单独一个 "rejected" 无法区分伪造调用者与本门禁与平台不匹配。
            AppLog.bootstrap(TAG, "systemui_caller_rejected uid=$uid systemui_uid=$systemUiUid")
            AppLog.w(TAG, "Rejected caller uid=$uid systemui_uid=$systemUiUid")
        }
        return allowed
    }

    private fun resolveSystemUiUid(): Int = try {
        packageManager.getPackageUid(SYSTEM_UI_PACKAGE, 0)
    } catch (error: PackageManager.NameNotFoundException) {
        AppLog.w(TAG, "SystemUI package not installed", error)
        UNRESOLVED_SYSTEM_UI_UID
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_lyric_bridge),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_lyric_bridge_text)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // 用显式组件启动 MainActivity:桌面图标被隐藏时(LAUNCHER alias 被禁用),
        // getLaunchIntentForPackage 会返回 null,导致点击通知无法打开应用。
        // MainActivity 始终启用,因此显式 Intent 在隐藏图标后依然有效。
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.eza.hyperglow.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyric_notification)
            .setContentTitle(getString(R.string.notification_lyric_bridge_title))
            .setContentText(getString(R.string.notification_lyric_bridge_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .setContentIntent(pendingIntent)
            .build()
    }

    companion object {
        private const val TAG = "AodLyricBridgeService"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val CHANNEL_ID = "lyric_bridge"
        private const val NOTIFICATION_ID = 1
    }
}
