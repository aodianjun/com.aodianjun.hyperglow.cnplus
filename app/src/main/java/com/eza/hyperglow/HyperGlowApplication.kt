package com.eza.hyperglow

import android.app.Application
import android.content.Intent
import com.eza.hyperglow.aod.AodLyricBridgeService
import com.eza.hyperglow.aod.AodProjectionEngine
import com.eza.hyperglow.diagnostics.DiagnosticCaptureManager
import com.eza.hyperglow.diagnostics.DiagnosticDraftStore
import com.eza.hyperglow.producer.LyricProducers

class HyperGlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticCaptureManager.expireIfNeeded(this)
        DiagnosticDraftStore.load(this)
        DiagnosticLoggingRuntime.setEnabled(DiagnosticLoggingPreferences.read(this))
        LyricProducers.start(this)
        AodProjectionEngine.start(this)
        // 把 AodLyricBridgeService 提升为前台服务,避免 MIUI GreezeManager 在息屏时
        // 反复冻结进程导致 AOD/锁屏歌词不更新。SystemUI 通过 bindService 绑定时,
        // service 不会自动进入前台,必须显式 startForegroundService 激活。
        startForegroundService(Intent(this, AodLyricBridgeService::class.java))
    }
}

