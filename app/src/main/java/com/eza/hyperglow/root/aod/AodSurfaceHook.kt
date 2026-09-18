package com.eza.hyperglow.root.aod

import android.view.View
import android.view.ViewGroup
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

object AodSurfaceHook {
    private const val AOD_VIEW_CLASS = "com.miui.aod.AODView"
    private const val FEATURE_ID = "aod-surface"
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    /**
     * suppressStockAodContent 的运行时闸门:关闭时为极速直通(不触碰任何视图)。
     * 由 AodSurfaceController 依据快照的 suppressStockAodContent 翻转。
     */
    @Volatile
    private var suppressionGateActive = false

    /** 受抑制的容器子树根(如 mTableModeContainer / burnInContainer)。 */
    private val suppressedRoots = Collections.newSetFromMap(WeakHashMap<View, Boolean>())
    /** 显式登记的受抑制目标(时钟/天气等系统组件束)。 */
    private val suppressedTargets = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val aodViewClass = SymbolResolver.resolveClass(
            classLoader, FEATURE_ID, AOD_VIEW_CLASS
        ) ?: return
        if (!hookedClassLoaders.add(classLoader)) return
        val attached = SymbolResolver.resolveMethod(
            classLoader, FEATURE_ID, SymbolRequest.method(AOD_VIEW_CLASS, "onAttachedToWindow")
        ) ?: return
        val detached = SymbolResolver.resolveMethod(
            classLoader, FEATURE_ID, SymbolRequest.method(AOD_VIEW_CLASS, "onDetachedFromWindow")
        ) ?: return
        HookRegistry.hook(module, FEATURE_ID, attached, AttachedHooker())
        HookRegistry.hook(module, FEATURE_ID, detached, DetachedHooker())
        installStockVisibilitySeam(module)
        HookLogger.i(TAG, "Direct AOD hooks installed")
    }

    /**
     * 安装闸门:framework View.setVisibility 的强制 GONE 接缝。这里的“闸门”有两层——
     * ① View 无法安置(无 setVisibility)则整段不安装;② 真正的开关由
     * [setSuppressionGate] 在运行时控制,关闭时 Hooker 走直通路径,几乎零开销。
     */
    private fun installStockVisibilitySeam(module: XposedModule) {
        val setVisibility = runCatching {
            View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType)
        }.getOrNull() ?: return
        HookRegistry.hook(module, FEATURE_ID, setVisibility, StockVisibilityHooker)
        HookLogger.i(TAG, "Stock visibility enforcement seam installed")
    }

    class AttachedHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            try {
                (chain.thisObject as? ViewGroup)?.let(AodSurfaceController::attach)
            } catch (error: Exception) {
                HookLogger.e(TAG, "Attach failed", error)
            }
            return result
        }
    }

    class DetachedHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            try {
                (chain.thisObject as? ViewGroup)?.let(AodSurfaceController::detach)
            } catch (error: Exception) {
                HookLogger.e(TAG, "Detach failed", error)
            }
            return result
        }
    }

    fun setSuppressionGate(active: Boolean) {
        if (suppressionGateActive == active) return
        suppressionGateActive = active
        HookLogger.i(TAG, "Stock suppression gate=$active targets=${suppressedTargets.size}")
        if (active) enforceSuppressed()
    }

    @Synchronized
    fun registerSuppressedRoot(view: View) {
        suppressedRoots.add(view)
        if (suppressionGateActive) forceGone(view)
    }

    @Synchronized
    fun registerSuppressedTarget(view: View) {
        suppressedTargets.add(view)
        if (suppressionGateActive) forceGone(view)
    }

    @Synchronized
    fun clearSuppressedState() {
        suppressedRoots.clear()
        suppressedTargets.clear()
    }

    @Synchronized
    private fun enforceSuppressed() {
        for (root in suppressedRoots) if (root != null) forceGone(root)
        for (target in suppressedTargets) if (target != null) forceGone(target)
    }

    @Synchronized
    private fun forceGone(view: View) {
        runCatching {
            if (view.visibility != View.GONE) view.visibility = View.GONE
        }
    }

    fun isSuppressionActive(): Boolean = suppressionGateActive

    private object StockVisibilityHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!suppressionGateActive) return chain.proceed()
            val requested = (chain.args.getOrNull(0) as? Number)?.toInt() ?: return chain.proceed()
            if (requested == View.GONE) return chain.proceed()
            val view = chain.thisObject as? View ?: return chain.proceed()
            if (!isSuppressed(view)) return chain.proceed()
            // 强制接缝:受抑制容器/目标上任何非 GONE 请求一律改回 GONE。
            return chain.proceed(arrayOf<Any>(View.GONE))
        }
    }

    private fun isSuppressed(view: View): Boolean = synchronized(this) {
        if (suppressedTargets.contains(view)) return@synchronized true
        if (suppressedRoots.contains(view)) return@synchronized true
        // 受抑制子树根之下任意后代同样被抑制。
        var parent = view.parent as? View
        while (parent != null) {
            if (suppressedRoots.contains(parent)) return@synchronized true
            parent = parent.parent as? View
        }
        false
    }

    private const val TAG = "AodSurfaceHook"
}