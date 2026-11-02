package com.eza.hyperglow.root.lockscreen

import android.view.View
import android.view.ViewGroup
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.ref.WeakReference
import java.util.Collections

/**
 * 锁屏歌词占位状态。控制器只发布“媒体卡之后的子视图需要下移多少像素”;
 * 真正的位移由 [NotificationStackScrollLayout.onLayout] hook 在系统布局完成后做,
 * 不改子视图类型、不写 translationY,避免 MIUI 在下一帧把它重置掉。
 */
internal object LyricReserveState {
    private const val TAG = "LyricReserve"
    private const val FEATURE_ID = "lockscreen-lyric-reserve"

    @Volatile
    private var module: XposedModule? = null
    @Volatile
    private var reservePx = 0
    private var stackRef: WeakReference<ViewGroup>? = null
    private var headerRef: WeakReference<View>? = null
    private val hookedMethods = Collections.synchronizedSet(mutableSetOf<String>())

    fun bindModule(module: XposedModule) {
        this.module = module
    }

    fun update(stack: ViewGroup?, header: View?, reservePx: Int) {
        if (stack == null || header == null || reservePx <= 0) {
            clear()
            return
        }
        val changed = this.reservePx != reservePx ||
            this.stackRef?.get() !== stack ||
            this.headerRef?.get() !== header
        this.reservePx = reservePx
        this.stackRef = WeakReference(stack)
        this.headerRef = WeakReference(header)
        install(stack.javaClass)
        if (changed) stack.requestLayout()
    }

    fun clear() {
        val stack = stackRef?.get()
        val changed = reservePx != 0
        reservePx = 0
        stackRef = null
        headerRef = null
        if (changed && stack != null) stack.requestLayout()
    }

    private fun install(stackClass: Class<*>) {
        val module = module ?: return
        var type: Class<*>? = stackClass
        while (type != null && type != ViewGroup::class.java) {
            for (method in type.declaredMethods) {
                if (method.name != "onLayout" || method.parameterTypes.size != 5) continue
                val key = "${type.name}#onLayout"
                if (!hookedMethods.add(key)) continue
                runCatching {
                    HookRegistry.hook(module, FEATURE_ID, method, LayoutHooker)
                    HookLogger.i(TAG, "onLayout hook installed ${type.name}")
                }.onFailure {
                    HookLogger.w(TAG, "onLayout hook failed ${type.name}", it)
                }
            }
            type = type.superclass
        }
    }

    private fun apply(stack: ViewGroup) {
        val reserve = reservePx
        if (reserve <= 0) return
        val header = headerRef?.get() ?: return
        if (header.parent !== stack) return
        val headerIndex = stack.indexOfChild(header)
        if (headerIndex < 0) return
        for (index in headerIndex + 1 until stack.childCount) {
            val child = stack.getChildAt(index)
            if (child.visibility == View.GONE) continue
            child.offsetTopAndBottom(reserve)
        }
    }

    private object LayoutHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            val result = chain.proceed()
            (chain.thisObject as? ViewGroup)?.let(::apply)
            return result
        }
    }
}
