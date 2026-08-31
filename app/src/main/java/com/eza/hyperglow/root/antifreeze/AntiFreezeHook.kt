package com.eza.hyperglow.root.antifreeze

import com.eza.hyperglow.root.HookLogger
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * MIUI 省电防冻结。
 *
 * 在 system_server 里 hook 冻结链的三层入口，当冻结目标属于"当前正在播放媒体"
 * 的 app 时跳过冻结调用（音乐位置源不断流）。
 *
 * 三层防线：
 *  1. com.miui.server.greeze.GreezeManagerStub —— MIUI 私有 Greezer 主入口（boot classpath）
 *  2. com.android.server.am.ProcessList.freezeBinderAndPackageCgroup —— MIUI 包级 cgroup 冻结
 *  3. com.android.server.am.Freezer.freezeBinder —— AOSP binder freeze 底层
 *
 * 只拦截"冻结方向"：Boolean 参数为 false（解冻）的调用永远原样放行，
 * 避免把已冻结的进程卡死在冻结态。
 *
 * 安全性：所有反射与 hook 全部 try-catch；类不存在（MIUI 版本差异）时静默降级，
 * 只影响本功能，不影响模块其他部分。
 */
object AntiFreezeHook {
    private const val TAG = "AntiFreeze"

    private val hookedKeys = mutableSetOf<String>()

    private val classTargets = listOf(
        "com.miui.server.greeze.GreezeManagerStub",
        "com.android.server.am.ProcessList",
        "com.android.server.am.Freezer",
    )

    private val installedClasses = mutableSetOf<String>()

    /**
     * system_server 专用入口：不依赖桥提供的 classLoader。
     *
     * zygote fork 后 ClassLoader.getSystemClassLoader() 仍是 zygote 的静态 loader
     * （不含 services.jar），所以依次尝试多个候选 loader，并后台重试直到
     * ActivityThread 就绪后 system_server 主 loader 可用。
     */
    fun installInSystemServer(module: XposedModule) {
        if (installInternal(module, null)) return
        HookLogger.bootstrap(TAG, "deferred_retry_started_in_system_server")
        Thread({
            var attempt = 0
            while (attempt < 60) {
                Thread.sleep(1000)
                attempt++
                if (installInternal(module, null)) break
            }
        }, "anti-freeze-retry").apply { isDaemon = true }.start()
    }

    fun install(module: XposedModule, classLoader: ClassLoader) {
        installInternal(module, classLoader)
    }

    /** @return true 当所有目标类都成功（或已安装） */
    private fun installInternal(module: XposedModule, primary: ClassLoader?): Boolean {
        var missing = 0
        for (name in classTargets) {
            if (name in installedClasses) continue
            val clazz = resolveClass(name, primary)
            if (clazz == null) {
                missing++
                HookLogger.bootstrap(TAG, "resolve_miss class=$name")
                continue
            }
            try {
                var count = 0
                for (method in clazz.declaredMethods) {
                    if (!isFreezeMethod(method)) continue
                    val key = clazz.name + "#" + method.toGenericString()
                    if (!hookedKeys.add(key)) continue
                    module.hook(method).intercept(FreezeInterceptor(method))
                    count++
                }
                installedClasses.add(name)
                HookLogger.bootstrap(TAG, "installed class=$name methods=$count via=${clazz.classLoader}")
            } catch (t: Throwable) {
                HookLogger.w(TAG, "hook failed class=$name", t)
                missing++
            }
        }
        return missing == 0 && installedClasses.size == classTargets.size
    }

    private fun resolveClass(name: String, primary: ClassLoader?): Class<*>? {
        val candidates = buildList {
            primary?.let(::add)
            runCatching { Thread.currentThread().contextClassLoader }.getOrNull()?.let(::add)
            runCatching { javaClass.classLoader }.getOrNull()?.let(::add)
            runCatching { ClassLoader.getSystemClassLoader() }.getOrNull()?.let(::add)
            runCatching {
                val at = Class.forName("android.app.ActivityThread", false, javaClass.classLoader)
                val inst = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
                val ctx = at.getMethod("getSystemContext").invoke(inst) as? android.content.Context
                ctx?.classLoader
            }.getOrNull()?.let(::add)
        }.filterNotNull().distinct()
        for (loader in candidates) {
            runCatching { Class.forName(name, false, loader) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun isFreezeMethod(method: Method): Boolean {
        val name = method.name
        return when {
            name == "freezeBinder" -> true
            name == "freezeBinderAndPackageCgroup" -> true
            method.declaringClass.name.contains("greeze") && name.contains("freeze") -> true
            else -> false
        }
    }

    private class FreezeInterceptor(private val method: Method) : Hooker {
        override fun intercept(chain: Chain): Any? {
            try {
                val args = chain.args
                // 解冻方向永远放行
                for (arg in args) {
                    if (arg is Boolean && !arg) return chain.proceed()
                }
                if (matchesPlayingApp(args)) {
                    HookLogger.i(
                        TAG,
                        "freeze skipped playing media " +
                            "method=${method.declaringClass.simpleName}#${method.name} " +
                            "args=[${args.joinToString { it?.toString() ?: "null" }}]"
                    )
                    return defaultReturn()
                }
            } catch (t: Throwable) {
                HookLogger.bootstrap(
                    TAG,
                    "intercept_failed error=${t.javaClass.simpleName} msg=${t.message}"
                )
                HookLogger.w(TAG, "intercept failed", t)
            }
            return chain.proceed()
        }

        private fun matchesPlayingApp(args: List<Any?>): Boolean {
            for (arg in args) {
                val value = arg as? Int ?: continue
                val uidByPid = uidForPid(value)
                if (uidByPid > 0 && PlayingMediaResolver.isActiveMediaUid(uidByPid)) return true
                if (PlayingMediaResolver.isActiveMediaUid(value)) return true
            }
            return false
        }

        private fun defaultReturn(): Any? = when {
            method.returnType == Void.TYPE -> null
            method.returnType == java.lang.Boolean.TYPE -> false
            method.returnType.isPrimitive -> 0
            else -> null
        }
    }

    @Volatile
    private var uidForPidMethod: Method? = null

    private fun uidForPid(pid: Int): Int {
        if (pid <= 0) return -1
        return runCatching {
            var m = uidForPidMethod
            if (m == null) {
                m = Class.forName("android.os.Process").getMethod("getUidForPid", Integer.TYPE)
                uidForPidMethod = m
            }
            (m.invoke(null, pid) as? Int) ?: -1
        }.getOrDefault(-1)
    }
}
