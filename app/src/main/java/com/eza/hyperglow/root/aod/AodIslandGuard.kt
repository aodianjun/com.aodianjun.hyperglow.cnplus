package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.View
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import com.eza.hyperglow.root.symbols.SymbolResolver
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.util.Collections
import java.util.WeakHashMap

/**
 * 息屏期间超级岛(DynamicIslandWindow)残留抑制。
 *
 * 真机取证(2026-10-02,24122RKC7C / HyperOS 3):岛的隐藏链完全由**状态迁移事件**驱动
 * (`IslandTempHiddenEventCoordinator` 消费 screenLocked false→true 等 collect 结果置
 * tempHidden → hideAllElementSurface + relayoutToMini)。当息屏后数秒内发生一次系统策略
 * 自发唤醒+蓝牙信任解锁(本机实测 WAKE_REASON_UNKNOWN,息屏后 3.0s),未落地的锁定迁移
 * 会被该次唤醒/解锁事件流吞掉——实测窗口内显示器已进入 DOZE 而岛控制器仍保持"展开可见"
 * 状态(02:40:19.5→02:40:21.6)。若该次唤醒不发生或被防误触立即压回,岛就以可见态整段
 * 留在息屏画面上,表现为「AOD 有时把息屏前的超级岛保留进去」。
 *
 * 本守卫恢复的是系统自身的意图(岛在息屏期间本来就应当不可见,7/7 干净息屏轮次均隐藏):
 * 设备非交互(!isInteractive)期间,把岛窗口根视图(miui.systemui.dynamicisland.window.
 * DynamicIslandWindowView,由 MIUISystemUIPlugin 动态 classloader 加载)强制 GONE,
 * 唤醒后按台账恢复。拉(周期复断言)推(可见性请求改写)双通道,与 AodSurfaceHook 的
 * landscape 抑制同一套台账不变量。符号缺失时整体不安装(fail-closed),零 hook 面。
 */
object AodIslandGuard {
    private const val ISLAND_WINDOW_VIEW_CLASS =
        "miui.systemui.dynamicisland.window.DynamicIslandWindowView"
    private const val FEATURE_ID = "aod-island-guard"
    private const val TAG = "AodIslandGuard"

    /** 息屏复断言周期。岛隐藏后无事件不会重现;周期只兜底竞态窗口内的迟到重显。 */
    private const val POLL_INTERVAL_MS = 1_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    @Volatile private var suppressionActive = false
    @Volatile private var powerManager: PowerManager? = null
    private var pollerStarted = false
    private var firstRewriteLogged = false

    /** 已捕获的岛窗口根视图(弱引用:窗口随 SystemUI 重建/用户切换自然更替)。 */
    private val islandRoots = Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    /**
     * 台账:岛根视图 → 宿主信念态。首见 forceGone 前 putIfAbsent 捕获;抑制期间宿主对
     * 同一视图的非 GONE 请求以其覆盖台账(最新意图)。关闸按台账恢复:闸门开启期间宿主
     * 发出的 VISIBLE 已被改写,宿主不会重发,不主动恢复则岛在唤醒后停在 GONE。
     */
    private val restoreTargets = WeakHashMap<View, Int>()

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val viewClass = SymbolResolver.resolveClass(
            classLoader, FEATURE_ID, ISLAND_WINDOW_VIEW_CLASS
        ) ?: return
        if (!hookedClassLoaders.add(classLoader)) return
        for (constructor in viewClass.declaredConstructors) {
            constructor.isAccessible = true
            HookRegistry.hook(module, FEATURE_ID, constructor, IslandRootCaptureHooker())
        }
        installVisibilitySeam(module)
        HookLogger.i(
            TAG,
            "Island doze guard installed viewClass=$ISLAND_WINDOW_VIEW_CLASS " +
                "constructors=${viewClass.declaredConstructors.size}"
        )
    }

    /**
     * 双接缝:framework `View.setVisibility` 与 `View.setFlags`。setVisibility 内部委托
     * setFlags,但宿主可能直调 setFlags 或在子类覆写 setVisibility——两条都拦才拦得全。
     * 真机实证(2026-10-02 16:53 复现):doze 期间 stock 以高于 1s/次的频率把岛根重新置回
     * VISIBLE,仅靠周期复断言会在两次压制之间留出可见间隙(闪烁);接缝级同步改写才能
     * 让根在 doze 期间持续保持 GONE。
     */
    private fun installVisibilitySeam(module: XposedModule) {
        val setVisibility = runCatching {
            View::class.java.getMethod("setVisibility", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (setVisibility != null) {
            HookRegistry.hook(module, FEATURE_ID, setVisibility, IslandVisibilityHooker)
        }
        val setFlags = runCatching {
            View::class.java.getMethod("setFlags", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
        if (setFlags != null) {
            HookRegistry.hook(module, FEATURE_ID, setFlags, IslandFlagsHooker)
        }
        HookLogger.i(TAG, "Island visibility enforcement seam installed visibility=${setVisibility != null} flags=${setFlags != null}")
    }

    private class IslandRootCaptureHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            (chain.thisObject as? View)?.let { registerIslandRoot(it) }
            return result
        }
    }

    @Synchronized
    private fun registerIslandRoot(view: View) {
        if (powerManager == null) {
            powerManager = runCatching {
                view.context?.getSystemService(PowerManager::class.java)
            }.getOrNull()
        }
        if (islandRoots.add(view)) {
            HookLogger.i(TAG, "Island window root captured view=${view.javaClass.name}")
            startPoller()
            if (suppressionActive) forceGone(view, "attach")
        }
    }

    private fun startPoller() {
        if (pollerStarted) return
        pollerStarted = true
        mainHandler.postDelayed(pollTick, POLL_INTERVAL_MS)
    }

    private val pollTick = object : Runnable {
        override fun run() {
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
            // isInteractive 读取是本地缓存值,无 binder 往返;1s 周期可忽略。
            val interactive = powerManager?.isInteractive ?: return
            if (!interactive && !suppressionActive) {
                setSuppressionGate(true, "doze-observed")
            } else if (interactive && suppressionActive) {
                setSuppressionGate(false, "interactive-observed")
            } else if (!interactive) {
                reEnforce()
            }
        }
    }

    @Synchronized
    fun setSuppressionGate(active: Boolean, cause: String) {
        if (suppressionActive == active) return
        suppressionActive = active
        if (active) firstRewriteLogged = false
        HookLogger.i(TAG, "Island doze suppression gate=$active cause=$cause")
        if (active) enforceSuppressed() else releaseSuppressedViews()
    }

    fun isSuppressionActive(): Boolean = suppressionActive

    @Synchronized
    private fun enforceSuppressed() {
        var suppressed = 0
        for (root in islandRoots) if (root != null) {
            if (forceGone(root, "gate-open")) suppressed++
        }
        HookLogger.i(TAG, "Island doze suppression enforced views=$suppressed")
    }

    /** 闸门保持开启期间的周期复断言:只对被宿主重新置为可见的根再次压制。 */
    private fun reEnforce() {
        for (root in islandRoots) if (root != null) forceGone(root, "reassert")
    }

    @Synchronized
    private fun forceGone(view: View, cause: String): Boolean {
        return try {
            if (view.visibility == ISLAND_VIEW_GONE) {
                false
            } else {
                restoreTargets.putIfAbsent(view, view.visibility)
                view.visibility = ISLAND_VIEW_GONE
                HookLogger.i(
                    TAG,
                    "Island root suppressed during doze cause=$cause " +
                        "restoredVisibility=${restoreTargets[view]}"
                )
                true
            }
        } catch (error: Exception) {
            HookLogger.w(TAG, "Island force-gone failed", error)
            false
        }
    }

    /** 关闸恢复:按台账放回宿主信念态。调用前闸门须已翻 false,恢复才可直通。 */
    @Synchronized
    private fun releaseSuppressedViews() {
        if (restoreTargets.isEmpty()) return
        var restored = 0
        for ((view, visibility) in restoreTargets) {
            runCatching {
                if (view.visibility != visibility) {
                    view.visibility = visibility
                    restored++
                }
            }
        }
        restoreTargets.clear()
        HookLogger.i(TAG, "Island roots released after wake restored=$restored")
    }

    private object IslandVisibilityHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!suppressionActive) return chain.proceed()
            val requested = (chain.args.getOrNull(0) as? Number)?.toInt() ?: return chain.proceed()
            if (requested == ISLAND_VIEW_GONE) return chain.proceed()
            val view = chain.thisObject as? View ?: return chain.proceed()
            val registered = synchronized(this@AodIslandGuard) { islandRoots.contains(view) }
            if (!registered) return chain.proceed()
            synchronized(this@AodIslandGuard) {
                restoreTargets[view] =
                    nextIslandRestoreValue(restoreTargets[view], requested)
            }
            logRewrite("visibility", requested, view)
            return chain.proceed(arrayOf<Any>(ISLAND_VIEW_GONE))
        }
    }

    /**
     * setFlags 接缝:setVisibility 的底层通道,也是宿主绕过 setVisibility 的直调路径。
     * 只改写「会让岛根可见」的请求:requested 视图位 ≠ GONE 时把该位强写为 GONE,
     * 其余 flag 位与掩码原样保留;非可见性掩码的请求直通。
     */
    private object IslandFlagsHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            if (!suppressionActive) return chain.proceed()
            val flags = (chain.args.getOrNull(0) as? Number)?.toInt() ?: return chain.proceed()
            val mask = (chain.args.getOrNull(1) as? Number)?.toInt() ?: return chain.proceed()
            if (mask and ISLAND_FLAG_VISIBILITY_MASK == 0) return chain.proceed()
            val view = chain.thisObject as? View ?: return chain.proceed()
            val registered = synchronized(this@AodIslandGuard) { islandRoots.contains(view) }
            if (!registered) return chain.proceed()
            val requested = flags and ISLAND_FLAG_VISIBILITY_MASK
            if (requested == ISLAND_VIEW_GONE) return chain.proceed()
            synchronized(this@AodIslandGuard) {
                restoreTargets[view] =
                    nextIslandRestoreValue(restoreTargets[view], requested)
            }
            logRewrite("flags", requested, view)
            return chain.proceed(arrayOf<Any>(rewrittenIslandFlagsRequest(flags, mask) ?: flags, mask))
        }
    }

    /** 首次改写取调用者栈前四帧(一次性,认领谁在 doze 期间重显岛根);其后节流。 */
    private fun logRewrite(seam: String, requested: Int, view: View) {
        val caller = synchronized(this@AodIslandGuard) {
            if (firstRewriteLogged) "" else {
                firstRewriteLogged = true
                " caller=" + Thread.currentThread().stackTrace
                    .drop(3).take(4).joinToString(" <- ") { it.methodName }
            }
        }
        HookLogger.iThrottled("aod-island-rewrite", 5_000L, TAG) {
            "Island visible request during doze rewritten seam=$seam requested=$requested$caller"
        }
    }
}

/** android.view.View.GONE 钉值(JVM 单测无框架依赖;可见性常量 VISIBLE=0/INVISIBLE=4/GONE=8)。 */
internal const val ISLAND_VIEW_GONE = 8

/** android.view.View.VISIBILITY_MASK 钉值(可见性位段,API 冻结常量)。 */
internal const val ISLAND_FLAG_VISIBILITY_MASK = 0x0000000C

/**
 * setFlags 请求改写判定(纯函数):掩码覆盖可见性位段且请求位 ≠ GONE 时,返回把可见位
 * 强写为 GONE 的新 flags(其余位保留);其余情况返回 null(直通不改写)。
 */
internal fun rewrittenIslandFlagsRequest(flags: Int, mask: Int): Int? {
    if (mask and ISLAND_FLAG_VISIBILITY_MASK == 0) return null
    val requested = flags and ISLAND_FLAG_VISIBILITY_MASK
    if (requested == ISLAND_VIEW_GONE) return null
    return (flags and ISLAND_FLAG_VISIBILITY_MASK.inv()) or ISLAND_VIEW_GONE
}

/** 岛闸门迁移判定(纯函数):非交互开闸、交互关闸、同态保持。 */
internal fun islandGateAction(isInteractive: Boolean, gateActive: Boolean): IslandGateAction = when {
    !isInteractive && !gateActive -> IslandGateAction.OPEN
    isInteractive && gateActive -> IslandGateAction.CLOSE
    else -> IslandGateAction.STAY
}

internal enum class IslandGateAction { OPEN, CLOSE, STAY }

/**
 * 台账更新规则(纯函数):宿主对岛根的非 GONE 请求以其值覆盖信念态(最新意图优先);
 * GONE 请求不改写台账。首见捕获由调用方 putIfAbsent 完成。
 */
internal fun nextIslandRestoreValue(recorded: Int?, requested: Int): Int? =
    if (requested == ISLAND_VIEW_GONE) recorded else requested
