package com.eza.hyperglow.root.aod

import android.os.Handler
import android.os.Looper
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.readHierarchyField
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap

object AodLifetimeHook {
    private const val CONTROLLER_CLASS = "com.miui.aod.doze.MiuiShowStyleController"
    private val hookedClassLoaders = Collections.synchronizedSet(
        Collections.newSetFromMap(WeakHashMap<ClassLoader, Boolean>())
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        val controllerClass = runCatching { classLoader.loadClass(CONTROLLER_CLASS) }.getOrNull()
            ?: return
        if (!hookedClassLoaders.add(classLoader)) return
        // 观察点 B-1:一次性 dump 方法/字段表。show 侧入口未知,HyperOS 升级后方法名会漂移,
        // 这份表让下一次抓取日志时能直接对照真实签名,不必反编译 systemui。
        dumpControllerSurface(controllerClass)
        for (constructor in controllerClass.declaredConstructors) {
            constructor.isAccessible = true
            module.hook(constructor).intercept(ControllerConstructorHooker)
        }
        for (methodName in POLICY_HIDE_METHODS) {
            val method = controllerClass.getDeclaredMethod(methodName)
            method.isAccessible = true
            module.deoptimize(method)
            module.hook(method).intercept(PolicyHideHooker(method))
        }
        installWindowActionProbes(module, controllerClass)
        HookLogger.i(
            TAG,
            "AOD lifetime hooks installed constructors=${controllerClass.declaredConstructors.size} " +
                "policyMethods=${POLICY_HIDE_METHODS.size}"
        )
    }

    private object ControllerConstructorHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            chain.thisObject?.let(AodLifetimeController::registerController)
            return result
        }
    }

    /**
     * 观察点 A:policy hide 的放行/抑制全记录。抑制路径已有 AodLifetimeController 日志,
     * 但放行的 hide 此前完全静默——而放行正是窗口真正关闭的时刻。09:53 故障链里
     * 09:53:02.8 后 surface 被 detach,却没有任何日志能回答"是谁、以什么参数关掉的"。
     * 放行时补记参数与调用栈前四帧,直接指认调用方(音乐状态变化?超时?传感器?)。
     */
    private class PolicyHideHooker(private val method: Method) : Hooker {
        override fun intercept(chain: Chain): Any? {
            val suppressed = AodLifetimeController.suppressPolicyHide(chain.thisObject, method)
            if (suppressed) return null
            val args = chain.args.joinToString(", ") { it.toString() }
            val caller = Thread.currentThread().stackTrace
                .drop(3).take(4)
                .joinToString(" <- ") { it.methodName }
            HookLogger.i(
                TAG,
                "policyHide ${method.name} allowed args=[$args] caller=$caller"
            )
            return chain.proceed()
        }
    }

    /**
     * 观察点 B-2:show/hide 类无参 void 方法的纯观察探针,不改行为。回答"前奏期间系统有
     * 没有尝试重新拉起窗口"——若 show 侧从未 enter,则复活机制需要主动触发而非等待。
     * POLICY_HIDE_METHODS 已由 [PolicyHideHooker] 覆盖(带 caller 栈),此处排除避免重复 hook。
     */
    private fun installWindowActionProbes(module: XposedModule, controllerClass: Class<*>) {
        var installed = 0
        for (method in controllerClass.declaredMethods) {
            if (method.returnType != Void.TYPE || method.parameterTypes.isNotEmpty()) continue
            if (method.name !in WINDOW_ACTION_METHODS) continue
            runCatching {
                method.isAccessible = true
                module.deoptimize(method)
                module.hook(method).intercept(WindowActionProbeHooker(method))
                installed++
            }
        }
        HookLogger.i(TAG, "window action probes installed=$installed candidates=${WINDOW_ACTION_METHODS.size}")
    }

    private class WindowActionProbeHooker(private val method: Method) : Hooker {
        override fun intercept(chain: Chain): Any? {
            HookLogger.i(TAG, "ctlAction ${method.name} enter")
            return try {
                chain.proceed().also { HookLogger.i(TAG, "ctlAction ${method.name} exit") }
            } catch (error: Throwable) {
                HookLogger.i(TAG, "ctlAction ${method.name} threw=${error.javaClass.simpleName}")
                throw error
            }
        }
    }

    private fun dumpControllerSurface(controllerClass: Class<*>) {
        runCatching {
            controllerClass.declaredMethods.sortedBy { it.name }.forEach { method ->
                HookLogger.i(
                    TAG,
                    "ctl method ${method.name}(" +
                        method.parameterTypes.joinToString(", ") { it.simpleName } +
                        "): ${method.returnType.simpleName}"
                )
            }
            controllerClass.declaredFields.sortedBy { it.name }.forEach { field ->
                HookLogger.i(TAG, "ctl field ${field.name}: ${field.type.simpleName}")
            }
        }.onFailure { HookLogger.w(TAG, "controller surface dump failed", it) }
    }

    private val POLICY_HIDE_METHODS = listOf("smartHide", "hideDoze")
    private val WINDOW_ACTION_METHODS = setOf(
        "showDoze", "show", "hide", "updateState", "update",
        "setVisible", "setShowing", "dismiss", "refresh"
    )
    private const val TAG = "AodLifetimeHook"
}

object AodLifetimeController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lyricActive = false
    private var activeController = WeakReference<Any>(null)
    private var controllerGeneration = 0L
    private var pendingController = WeakReference<Any>(null)
    private var pendingControllerGeneration = -1L
    private var pendingMethod: Method? = null
    private var pendingReplay: Runnable? = null
    private var guardCause = "init"

    /** Records what the coordinator last acted on, so a guard transition can name its own cause. */
    @Synchronized
    fun noteGuardCause(cause: String) {
        guardCause = cause
    }

    @Synchronized
    fun setLyricActive(active: Boolean) {
        if (lyricActive == active) return
        lyricActive = active
        HookLogger.i(TAG, "Lyric lifetime guard active=$active cause=$guardCause")
        if (active) {
            clearPendingHideLocked()
            activeController.get()?.let(::cancelPolicyTimeouts)
        } else {
            replayPendingHide()
        }
    }

    @Synchronized
    fun registerController(controller: Any) {
        controllerGeneration++
        activeController = WeakReference(controller)
        clearPendingHideLocked()
        HookLogger.i(TAG, "AOD policy controller captured")
        if (lyricActive) cancelPolicyTimeouts(controller)
    }

    @Synchronized
    fun suppressPolicyHide(controller: Any, method: Method): Boolean {
        if (!lyricActive) return false
        if (activeController.get() !== controller) {
            controllerGeneration++
            activeController = WeakReference(controller)
            clearPendingHideLocked()
        }
        pendingController = WeakReference(controller)
        pendingControllerGeneration = controllerGeneration
        pendingMethod = method
        HookLogger.i(TAG, "Suppressed ${method.name} during active lyrics")
        return true
    }

    @Synchronized
    private fun replayPendingHide() {
        val controller = pendingController.get()
        val method = pendingMethod
        val generation = pendingControllerGeneration
        pendingController.clear()
        pendingControllerGeneration = -1L
        pendingMethod = null
        if (controller == null || method == null) return
        val controllerRef = WeakReference(controller)
        val replay = object : Runnable {
            override fun run() {
                try {
                    val currentController = controllerRef.get()
                    val allowed = synchronized(this@AodLifetimeController) {
                        pendingReplay === this && shouldReplaySuppressedPolicyHide(
                            lyricActive = lyricActive,
                            capturedGeneration = generation,
                            currentGeneration = controllerGeneration,
                            sameController = currentController != null &&
                                activeController.get() === currentController
                        )
                    }
                    if (!allowed || currentController == null) return
                    try {
                        method.invoke(currentController)
                        HookLogger.i(TAG, "Replayed ${method.name} after lyric session")
                    } catch (error: Exception) {
                        (error as? java.lang.reflect.InvocationTargetException)
                            ?.cause
                            ?.let { if (it is Error) throw it }
                        HookLogger.w(TAG, "Pending AOD hide replay failed", error)
                    }
                } finally {
                    synchronized(this@AodLifetimeController) {
                        if (pendingReplay === this) pendingReplay = null
                    }
                }
            }
        }
        pendingReplay = replay
        mainHandler.postDelayed(replay, REPLAY_DELAY_MS)
    }

    private fun clearPendingHideLocked() {
        pendingReplay?.let(mainHandler::removeCallbacks)
        pendingReplay = null
        pendingController.clear()
        pendingControllerGeneration = -1L
        pendingMethod = null
    }

    private fun cancelPolicyTimeouts(controller: Any) {
        var cancelled = 0
        for (fieldName in POLICY_TIMEOUT_FIELDS) {
            val timeout = readHierarchyField(controller, fieldName) ?: continue
            if (runCatching {
                    timeout.javaClass.getMethod("cancel").invoke(timeout)
                }.isSuccess) {
                cancelled++
            }
        }
        HookLogger.i(TAG, "Cancelled active AOD policy timeouts=$cancelled")
    }

    private val POLICY_TIMEOUT_FIELDS = listOf("mSmartHideDozeTimeout", "mHideDozeTimeout")
    private const val TAG = "AodLifetimeController"
    private const val REPLAY_DELAY_MS = 500L
}

internal fun shouldReplaySuppressedPolicyHide(
    lyricActive: Boolean,
    capturedGeneration: Long,
    currentGeneration: Long,
    sameController: Boolean
): Boolean = !lyricActive && capturedGeneration >= 0L &&
    capturedGeneration == currentGeneration && sameController
