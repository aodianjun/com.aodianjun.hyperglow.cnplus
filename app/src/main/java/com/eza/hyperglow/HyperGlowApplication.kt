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
        DiagnosticTraceFile.setDirectory(filesDir.takeIf { DiagnosticLoggingRuntime.enabled })
        // 空镜像和从未打开的镜像无法区分;空闲进程在播放开始前一行都不会写。
        AppLog.i(
            "Diagnostics",
            "trace ready versionCode=${BuildConfig.VERSION_CODE} pid=${android.os.Process.myPid()}"
        )
        LyricProducers.start(this)
        AodProjectionEngine.start(this)
        // 插件运行时随 App 启动恢复已安装插件并接入投影链(总开关默认关闭)。
        com.eza.hyperglow.plugin.PluginPipeline.start(this)
        // 把 AodLyricBridgeService 提升为前台服务,避免 MIUI GreezeManager 在息屏时
        // 反复冻结进程导致 AOD/锁屏歌词不更新。SystemUI 通过 bindService 绑定时,
        // service 不会自动进入前台,必须显式 startForegroundService 激活。
        //
        // 但从后台调用 startForegroundService 会被 MIUI 拒绝并抛
        // ForegroundServiceStartNotAllowedException 导致崩溃。这里 best-effort 尝试,
        // 失败则等 MainActivity 在前台时再启动。
        runCatching {
            startForegroundService(Intent(this, AodLyricBridgeService::class.java))
        }.onFailure { error ->
            AppLog.w("HyperGlowApplication", "startForegroundService from background denied: ${error.message}")
        }
    }
}

