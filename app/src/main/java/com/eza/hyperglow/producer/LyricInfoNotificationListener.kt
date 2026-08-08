package com.eza.hyperglow.producer

import android.service.notification.NotificationListenerService
import com.eza.hyperglow.AppLog

/**
 * Notification listener that grants HyperGlow access to other apps' [android.media.session]
 * sessions, which is required for the [LyricInfoLyricProducer] to read the `lyricInfo` metadata
 * extra that the LyricInfo Xposed module injects.
 *
 * The listener itself is passive: it only notifies the producer once the OS binds it (i.e. the
 * user granted notification access), at which point cross-app sessions become enumerable via
 * [android.media.session.MediaSessionManager.getActiveSessions].
 */
class LyricInfoNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        AppLog.i("LyricInfoNotificationListener", "connected")
        LyricProducers.onLyricInfoListenerConnected()
    }
}