package com.eza.hyperglow.aod

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.R

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
        AppLog.bootstrap(TAG, "foreground_service_started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun isSystemUiCaller(): Boolean {
        val uid = Binder.getCallingUid()
        val packages = packageManager.getPackagesForUid(uid).orEmpty()
        val allowed = uid == Process.SYSTEM_UID && packages.contains(SYSTEM_UI_PACKAGE)
        if (!allowed) AppLog.w(TAG, "Rejected caller uid=$uid")
        return allowed
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
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyric_notification)
            .setContentTitle(getString(R.string.notification_lyric_bridge_title))
            .setContentText(getString(R.string.notification_lyric_bridge_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .build()
    }

    companion object {
        private const val TAG = "AodLyricBridgeService"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val CHANNEL_ID = "lyric_bridge"
        private const val NOTIFICATION_ID = 1
    }
}
