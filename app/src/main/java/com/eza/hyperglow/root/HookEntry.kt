package com.eza.hyperglow.root

import android.app.Application
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.root.aod.AodBrightnessHook
import com.eza.hyperglow.root.aod.AodSurfaceHook
import com.eza.hyperglow.root.aod.AodLifetimeHook
import com.eza.hyperglow.root.aod.AodPositionHook
import com.eza.hyperglow.root.aod.AodDisplayStateHook
import com.eza.hyperglow.root.aod.AodWakeBroker
import com.eza.hyperglow.root.antifreeze.AntiFreezeHook
import com.eza.hyperglow.root.capability.XiaomiCapabilityResolver
import com.eza.hyperglow.root.capability.missingProbeNames
import com.eza.hyperglow.root.lockscreen.LockscreenSurfaceHook
import com.eza.hyperglow.root.lockscreen.LockscreenEditorGestureHook
import com.eza.hyperglow.root.lockscreen.RaiseToAodHook
import com.eza.hyperglow.root.projection.SystemUiLyricProjectionRuntime
import com.eza.hyperglow.root.transition.LinkageTransitionHook
import com.eza.hyperglow.root.transition.SystemUiClockMorphHook
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * Safely installs a single hook, logging failures without aborting subsequent installs.
 * Extracted to eliminate the repeated try-catch-install pattern across [onPackageLoaded],
 * [installMiuiAodHooks], and [ClassLoaderHooker].
 */
private inline fun safeInstall(tag: String, name: String, block: () -> Unit) {
    try {
        block()
    } catch (error: Exception) {
        HookLogger.w(tag, "$name hook unavailable", error)
    }
}

class HookEntry : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        super.onModuleLoaded(param)
        HookLogger.module = this
        HookLogger.bootstrap(
            TAG,
            "module_loaded version=${BuildConfig.VERSION_CODE} " +
                "minApi=$LIBXPOSED_MIN_API targetApi=$LIBXPOSED_TARGET_API " +
                "process=${processClass(param.processName)} name=${param.processName}"
        )
        val systemServer = try {
            param.isSystemServer
        } catch (_: Throwable) {
            // LSPosed < 2.1.2 的桥未实现 isSystemServer()（libxposed API 101+），
            // invokeinterface 会抛 AbstractMethodError；回退进程名判断。
            param.processName == "system"
        }
        if (systemServer) {
            try {
                AntiFreezeHook.installInSystemServer(this)
                HookLogger.bootstrap(TAG, "antifreeze_entry_invoked_in_system_server")
            } catch (error: Exception) {
                HookLogger.w(TAG, "AntiFreeze entry failed in system_server", error)
            }
        }
        HookLogger.bootstrap(TAG, "module_loaded_complete")
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        try {
            AntiFreezeHook.install(this, param.classLoader)
            HookLogger.bootstrap(TAG, "antifreeze_installed_in_system_server")
        } catch (error: Exception) {
            HookLogger.w(TAG, "AntiFreeze install failed", error)
        }
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName == SYSTEM_SERVER_PACKAGE) {
            installAntiFreeze(param)
            return
        }
        if (param.packageName == MIUI_AOD_PACKAGE) {
            // 部分机型的 AOD 逻辑运行在独立 com.miui.aod 进程而非 SystemUI 进程内。
            // 与 SystemUI 动态 loader 路径共用同一套 AOD hook,全部带符号守卫,
            // 进程内不存在对应类时各自安全跳过,不会与 SystemUI 侧重复生效。
            installMiuiAodHooks(param)
            return
        }
        if (param.packageName != SYSTEM_UI_PACKAGE) return
        val processName = runCatching { Application.getProcessName() }.getOrDefault("")
        HookLogger.bootstrap(TAG, "systemui_package_loaded process=${processClass(processName)}")
        if (processName.contains(':')) {
            HookLogger.bootstrap(TAG, "systemui_secondary_process_skipped")
            return
        }

        XiaomiCapabilityResolver.observeDefaultLoader(param.defaultClassLoader)
        XiaomiCapabilityResolver.observeAodLoader(param.defaultClassLoader)
        val capabilityReport = XiaomiCapabilityResolver.snapshot()
        val presentProbes = capabilityReport.rawProbes.values.count { it }
        // Default-loader probes only. The AOD dex is not loaded yet, so this is an early lower
        // bound, not the effective profile; `capability_report_sent` carries the settled counts.
        HookLogger.bootstrap(
            TAG,
            "systemui_capability_probes_default_loader probes=$presentProbes/" +
                "${capabilityReport.rawProbes.size} profile=${capabilityReport.profileState.wireValue} " +
                "missing=${missingProbeNames(capabilityReport.rawProbes)}"
        )
        val loader = param.defaultClassLoader
        safeInstall(TAG, "SystemUI lifecycle") { SystemUiLifecycleHook.install(this, loader) }
        safeInstall(TAG, "Lockscreen") { LockscreenSurfaceHook.install(this, loader) }
        safeInstall(TAG, "Linkage") { LinkageTransitionHook.install(this, loader) }
        safeInstall(TAG, "SystemUI clock morph geometry") {
            SystemUiClockMorphHook.install(this, loader)
        }
        safeInstall(TAG, "Raise-to-AOD") { RaiseToAodHook.install(this, loader) }
        safeInstall(TAG, "Lockscreen editor gesture") {
            LockscreenEditorGestureHook.install(this, loader)
        }
        installAodHooks(loader)
        try {
            val loaderClass = Class.forName("dalvik.system.BaseDexClassLoader")
            for (constructor in loaderClass.declaredConstructors) {
                deoptimize(constructor)
                hook(constructor).intercept(ClassLoaderHooker(this))
            }
            HookLogger.i(TAG, "Dynamic class-loader hooks installed")
        } catch (error: Exception) {
            HookLogger.e(TAG, "Class-loader hook failed", error)
        }
    }

    private fun installAntiFreeze(param: PackageLoadedParam) {
        try {
            AntiFreezeHook.install(this, param.defaultClassLoader)
        } catch (error: Exception) {
            HookLogger.w(TAG, "AntiFreeze install failed", error)
        }
    }

    private fun installMiuiAodHooks(param: PackageLoadedParam) {
        HookLogger.bootstrap(TAG, "miui_aod_package_loaded")
        val loader = param.defaultClassLoader
        XiaomiCapabilityResolver.observeAodLoader(loader)
        installAodHooks(loader)
    }

    /** Shared AOD hook installation used by SystemUI, MIUI AOD, and dynamic ClassLoader paths. */
    private fun installAodHooks(loader: ClassLoader) {
        safeInstall(TAG, "AOD surface") { AodSurfaceHook.install(this, loader) }
        safeInstall(TAG, "AOD lifetime") { AodLifetimeHook.install(this, loader) }
        safeInstall(TAG, "AOD brightness") { AodBrightnessHook.install(this, loader) }
        safeInstall(TAG, "AOD position") { AodPositionHook.install(this, loader) }
        safeInstall(TAG, "AOD display-state") { AodDisplayStateHook.install(this, loader) }
        safeInstall(TAG, "AOD wake broker") { AodWakeBroker.install(this, loader) }
    }

    private class ClassLoaderHooker(private val module: HookEntry) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            val loader = chain.thisObject as? ClassLoader ?: return result
            XiaomiCapabilityResolver.observeAodLoader(loader)
            SystemUiLyricProjectionRuntime.projection.reportCapabilities()
            module.installAodHooks(loader)
            return result
        }
    }

    companion object {
        private const val TAG = "HookEntry"
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val SYSTEM_SERVER_PACKAGE = "android"
        private const val MIUI_AOD_PACKAGE = "com.miui.aod"
        private const val LIBXPOSED_MIN_API = 101
        private const val LIBXPOSED_TARGET_API = 102

        private fun processClass(processName: String): String = when {
            processName.isBlank() -> "unknown"
            processName.contains(':') -> "secondary"
            processName == SYSTEM_UI_PACKAGE -> "primary"
            else -> "unexpected"
        }
    }
}
