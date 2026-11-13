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
    private var headerOffsetPx = 0
    @Volatile
    private var childOffsetPx = 0
    private var stackRef: WeakReference<ViewGroup>? = null
    private var headerRef: WeakReference<View>? = null
    private val hookedMethods = Collections.synchronizedSet(mutableSetOf<String>())

    fun bindModule(module: XposedModule) {
        this.module = module
    }

    /**
     * 媒体卡与歌词的占位位移。header 与 header 之后的子视图分开给量：
     * - 上方模式：两者同量上移（媒体卡 + 通知整体让位）；
     * - 下方模式：媒体卡上移让出空间，通知按歌词实际高度单独下移；
     * - 旧布局：两者都 0，直接 clear。
     */
    fun update(
        stack: ViewGroup?,
        header: View?,
        headerOffsetPx: Int,
        childOffsetPx: Int
    ) {
        if (stack == null || header == null || (headerOffsetPx == 0 && childOffsetPx == 0)) {
            clear()
            return
        }
        val changed = this.headerOffsetPx != headerOffsetPx ||
            this.childOffsetPx != childOffsetPx ||
            this.stackRef?.get() !== stack ||
            this.headerRef?.get() !== header
        this.headerOffsetPx = headerOffsetPx
        this.childOffsetPx = childOffsetPx
        this.stackRef = WeakReference(stack)
        this.headerRef = WeakReference(header)
        install(stack.javaClass)
        if (changed) stack.requestLayout()
    }

    fun clear() {
        val stack = stackRef?.get()
        val changed = headerOffsetPx != 0 || childOffsetPx != 0
        headerOffsetPx = 0
        childOffsetPx = 0
        appliedHeaderOffsetPx = 0
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

    private fun apply(stack: ViewGroup): Boolean {
        val headerOffset = headerOffsetPx
        val childOffset = childOffsetPx
        if (headerOffset == 0 && childOffset == 0) return true
        val header = headerRef?.get() ?: return false
        if (header.parent !== stack) return false
        val headerIndex = stack.indexOfChild(header)
        if (headerIndex < 0) return false
        if (headerOffset != 0) header.offsetTopAndBottom(headerOffset)
        for (index in headerIndex + 1 until stack.childCount) {
            val child = stack.getChildAt(index)
            if (child.visibility == View.GONE) continue
            if (childOffset != 0) child.offsetTopAndBottom(childOffset)
        }
        return true
    }

    /**
     * 实际已经落在媒体卡上的位移。读取锁屏几何时必须扣掉它，否则把「上次偏移后的位置」
     * 当成原厂位置会在下一帧重复叠加。onLayout 期间先清零(子视图会被重新摆回原厂位置),
     * apply 成功后再写成当前值。
     */
    @Volatile
    private var appliedHeaderOffsetPx = 0

    fun currentHeaderOffsetPx(): Int = appliedHeaderOffsetPx

    private object LayoutHooker : Hooker {
        override fun intercept(chain: Chain): Any? {
            appliedHeaderOffsetPx = 0
            val result = chain.proceed()
            val stack = chain.thisObject as? ViewGroup
            if (stack != null) {
                val applied = apply(stack)
                appliedHeaderOffsetPx = if (applied) headerOffsetPx else 0
            }
            return result
        }
    }
}
