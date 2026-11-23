package com.eza.hyperglow.root.lockscreen

import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.RoundedRectBlurRegion
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.utils.PlatformVersion

/**
 * 锁屏卡片的系统级背景模糊层。
 *
 * 用一个放在窗口内容之后的 SurfaceView,通过 API 37 的
 * `SurfaceView.setBlurRegions` + `RoundedRectBlurRegion` 让 SurfaceFlinger 直接模糊
 * 这个 surface 后面的图层(包括动态壁纸)。这才是系统媒体卡那种"背后是什么就模糊什么"
 * 的实现,不依赖 WallpaperManager 的静态壁纸。
 *
 * surface 本身保持透明,卡片的中性 tint/高光由上层 View 绘制;圆角与模糊半径都交给
 * `RoundedRectBlurRegion`。
 *
 * 只有 HyperOS 4 才启用这条路径;非 OS4(OS3/Android 15/16、AOSP 17 等)由
 * [supported] 直接关掉,[update] 返回 false,调用方回落到旧实现:静态壁纸预模糊,
 * 动态壁纸取不到静态图时再用中性半透明玻璃。
 */
internal class NativeBlurSurface(private val host: View) {

    private var surfaceView: SurfaceView? = null
    private var surfaceReady = false

    /**
     * OS4 鉴别,不用 `SDK_INT >= 37` 单独判断:
     * - HyperOS 4.0.2 的 SystemUI 进程里 SDK_INT 不一定报 37,只看 API 会把 OS4
     *   误判成旧系统,整条实时模糊分支被静默跳过;
     * - 以 [PlatformVersion.isHyperOs4OrNewer] 为准(读 `ro.mi.os.version.name`,
     *   缺失时才回退 API;SystemUI 侧还有 OS4 类探针确认);
     * - 非 OS4 一律不允许使用实时模糊,退回旧版静态壁纸模糊/半透明逻辑。
     */
    private var supported = PlatformVersion.isHyperOs4OrNewer

    private var lastSpec: Spec? = null
    private var cornerRadiiValues = 8

    init {
        // 每个进程只记一次门控结果:现场看到底是 OS4 判定失败,还是 API/调用失败。
        if (!gateLogged) {
            gateLogged = true
            HookLogger.w(
                "LyricCardNativeBlur",
                "gate os4=${PlatformVersion.isHyperOs4OrNewer} " +
                    "raw=${PlatformVersion.rawVersionName} sdk=${Build.VERSION.SDK_INT} " +
                    "supported=$supported"
            )
        }
    }

    private data class Spec(
        val width: Int,
        val height: Int,
        val cornerRadiusPx: Float,
        val blurRadiusPx: Float,
        val alpha: Float
    )

    fun update(
        bounds: RectF,
        cornerRadiusPx: Float,
        blurRadiusPx: Float,
        alpha: Float = 1f
    ): Boolean {
        if (!supported || bounds.width() <= 0f || bounds.height() <= 0f) return false
        val parent = host.parent as? ViewGroup ?: return false
        val view = ensureView(parent) ?: return false
        val width = bounds.width().toInt().coerceAtLeast(1)
        val height = bounds.height().toInt().coerceAtLeast(1)
        val currentParams = view.layoutParams
        if (currentParams?.width != width || currentParams?.height != height) {
            // FrameLayout 测量子 View 时会把 LayoutParams 强转成 MarginLayoutParams,
            // 必须用 FrameLayout.LayoutParams,否则锁屏打开时 SystemUI 直接崩。
            view.layoutParams = FrameLayout.LayoutParams(width, height)
        }
        view.x = bounds.left
        view.y = bounds.top
        view.visibility = View.VISIBLE
        lastSpec = Spec(width, height, cornerRadiusPx, blurRadiusPx, alpha)
        if (surfaceReady) applyRegions()
        return true
    }

    fun hide() {
        surfaceView?.visibility = View.GONE
    }

    fun release() {
        val view = surfaceView ?: return
        surfaceView = null
        surfaceReady = false
        lastSpec = null
        runCatching { view.holder.removeCallback(holderCallback) }
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun ensureView(parent: ViewGroup): SurfaceView? {
        surfaceView?.let { return it }
        if (!supported) return null
        return runCatching {
            val view = SurfaceView(host.context).apply {
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setWillNotDraw(true)
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = View.VISIBLE
                holder.addCallback(holderCallback)
            }
            // 放在宿主 View 之前;surface 本身在窗口内容之后,模糊的是后面的壁纸。
            // 必须用 FrameLayout.LayoutParams(见 update 里的说明)。
            parent.addView(view, 0, FrameLayout.LayoutParams(1, 1))
            surfaceView = view
            view
        }.onFailure {
            HookLogger.w("LyricCardNativeBlur", "create failed: ${it.message}")
            supported = false
        }.getOrNull()
    }

    private val holderCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            surfaceReady = true
            applyRegions()
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            surfaceReady = true
            applyRegions()
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            surfaceReady = false
        }
    }

    private fun applyRegions() {
        val view = surfaceView ?: return
        val spec = lastSpec ?: return
        val radius = spec.cornerRadiusPx.coerceAtLeast(0f)
        val radii = when (cornerRadiiValues) {
            8 -> floatArrayOf(radius, radius, radius, radius, radius, radius, radius, radius)
            else -> floatArrayOf(radius, radius, radius, radius)
        }
        runCatching {
            // 构造参数顺序是 (bounds, cornerRadii, alpha, blurRadius) —— 取自 android.jar
            // 的 LocalVariableTable。之前把 blurRadius 传进 alpha 槽、把 alpha(1f) 当成
            // 半径,SurfaceFlinger 里就成了 1px 模糊:图层有 blurRegions 但肉眼看不到模糊。
            val region = RoundedRectBlurRegion(
                RectF(0f, 0f, spec.width.toFloat(), spec.height.toFloat()),
                radii,
                spec.alpha,
                spec.blurRadiusPx
            )
            view.setBlurRegions(listOf(region))
        }.onFailure { error ->
            if (cornerRadiiValues == 8) {
                // 有些实现只接受四个圆角值,换 4 个再试一次。
                cornerRadiiValues = 4
                applyRegions()
            } else {
                HookLogger.w("LyricCardNativeBlur", "setBlurRegions failed: ${error.message}")
                supported = false
                view.visibility = View.GONE
            }
        }
    }

    private companion object {
        /** 门控日志只打一次,避免每次锁屏重建卡片都刷一条。 */
        @Volatile
        private var gateLogged = false
    }
}
