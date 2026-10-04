package com.eza.hyperglow.ui

import android.os.SystemClock
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey
import top.yukonga.miuix.kmp.nav.transition.NavGesture
import top.yukonga.miuix.kmp.nav.transition.NavTransition
import top.yukonga.miuix.kmp.nav.transition.navGraphicsTransition

/**
 * 应用内界面路由:由 miuix-nav 的返回栈驱动,系统返回手势(预测性返回)在栈深 > 1 时
 * 1:1 跟手驱动转场,松手按速度判定提交/取消。
 *
 * 路由必须是 `@Serializable` 的 data object / data class:返回栈经 rememberSaveable 持久化,
 * 序列化器在编译期生成(不依赖反射),配置变更与进程死亡后可恢复。contentKey 默认取路由值本身,
 * 全栈必须唯一 —— 同一曲面重复入栈见 [pushRoute] 的幂等处理。
 */
@Serializable
internal sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object Diagnostics : AppRoute

    @Serializable
    data object Plugins : AppRoute

    @Serializable
    data object AodBehavior : AppRoute

    @Serializable
    data object AppAppearance : AppRoute

    @Serializable
    data object Help : AppRoute

    @Serializable
    data object Changelog : AppRoute

    @Serializable
    data object Contributors : AppRoute

    @Serializable
    data object Licenses : AppRoute

    /** 歌词增强:列出已安装的歌词处理插件。 */
    @Serializable
    data object LyricEnhancement : AppRoute

    /** 单个歌词增强插件的设置页。 */
    @Serializable
    data class PluginSettings(val pluginId: String) : AppRoute

    /** 单个插件的缓存管理页。 */
    @Serializable
    data class PluginCache(val pluginId: String) : AppRoute
}

/**
 * 幂等入栈:同一路由值连点两次会得到重复 contentKey,被 miuix-nav 在 reconcile 期以
 * IllegalArgumentException 拒绝(全栈 contentKey 必须唯一),这里跳过已在栈上的路由。
 */
internal fun NavBackStack.pushRoute(route: AppRoute) {
    if (route !in this) add(route)
}

/** 出栈:根路由不出栈(NavDisplay 拒绝空栈;根上的系统返回由平台处理为退出应用)。 */
internal fun NavBackStack.popRoute() {
    if (size > 1) removeLastOrNull()
}

/** 页面被拖离静止位的最小幅度:再小就不算一次真正的拖动(避免把 1% 以下的抖动当手势)。 */
private const val BACK_GESTURE_REST_EPSILON = 0.01f

/**
 * 记录返回手势的实时样本(拖动比例 + 估计速度),供提交回调做阈值判定(见 [backTriggerReached])。
 *
 * 只认「手指正在驱动」的帧:手势上下文存在、没有自驱动 settle、且页面确实被拖离静止位。其余帧
 * (静止、程序化动画、以及被否决后仍冻结的手势上下文)一律丢弃样本 —— 这样返回键 / 顶栏返回按钮 /
 * 系统离散返回永远不会被阈值拦住:它们提交时没有实时手势。
 * 写入发生在绘制期(转场的 graphicsLayer 块内),读取发生在主线程的提交回调,同一线程,无需同步。
 */
internal class AppBackGestureTracker {
    private var progress = Float.NaN
    private var frameTimeMillis = 0L
    private var previousProgress = Float.NaN
    private var previousTimeMillis = 0L

    /**
     * 每帧采样一次(仅顶层条目调用)。
     *
     * @param gesture 当前手势上下文(可为空 = 没有手势)。
     * @param settleActive 共享驱动是否正在自驱动 settle(松手后的收敛 / 程序化出入栈)。
     * @param depth 顶层条目的相对深度(手指驱动时等于负的拖动比例)。
     */
    fun onFrame(gesture: NavGesture?, settleActive: Boolean, depth: Float, nowMillis: Long) {
        val fingerDriven = gesture != null && !settleActive && -depth > BACK_GESTURE_REST_EPSILON
        if (!fingerDriven) {
            clear()
            return
        }
        // 同一毫秒内的重复回调(同帧多次绘制)不参与速度估计,避免 dt=0 把快甩读成 0 速度。
        if (!progress.isNaN() && nowMillis > frameTimeMillis) {
            previousProgress = progress
            previousTimeMillis = frameTimeMillis
        }
        progress = gesture.progress.coerceIn(0f, 1f)
        frameTimeMillis = nowMillis
    }

    /** 最近一次手势样本;没有手势(返回键/顶栏按钮/系统离散返回)时返回 null。 */
    fun release(): Release? =
        if (progress.isNaN()) null else Release(progress = progress, velocity = velocity())

    private fun velocity(): Float {
        if (previousProgress.isNaN()) return 0f
        val elapsed = frameTimeMillis - previousTimeMillis
        if (elapsed <= 0L) return 0f
        return (progress - previousProgress) * 1000f / elapsed
    }

    private fun clear() {
        progress = Float.NaN
        frameTimeMillis = 0L
        previousProgress = Float.NaN
        previousTimeMillis = 0L
    }

    /** 松手样本:[progress] 为拖动比例(0..1),[velocity] 为正表示朝返回方向(progress-units/s)。 */
    internal class Release(val progress: Float, val velocity: Float)
}

/**
 * 「未达阈值」提交的否决:把栈改回去,让 NavDisplay 的驱动 retarget 回静止位。
 *
 * NavDisplay 的提交判定来自系统(miuix 只按速度兜底),应用要在**保留跟手预览**的前提下加位置阈值,
 * 只能在提交回调里否决这次 pop —— 先 pop 让驱动转向静止位,等组合观察到这次栈变化(LaunchedEffect
 * 的键是栈深)再把同一路由压回:库文档的 revival 路径会把仍在下场动画中的条目原地折回,条目不重建、
 * rememberSaveable 状态与滚动位置都保留,视觉上就是一次弹回。
 */
internal class AppBackVetoState {
    private var pendingRoute: AppRoute? = null

    /** 否决一次提交:记下顶层路由并出栈。 */
    fun veto(backStack: NavBackStack) {
        val route = backStack.lastOrNull() as? AppRoute ?: return
        pendingRoute = route
        backStack.popRoute()
    }

    /** 由 `LaunchedEffect(backStack.size)` 调用:栈变化被组合观察到后恢复被否决的条目。 */
    fun restoreIfPending(backStack: NavBackStack) {
        val route = pendingRoute ?: return
        pendingRoute = null
        backStack.pushRoute(route)
    }
}

/**
 * 应用导航转场:几何沿用 miuix 默认档(顶层自后缘整宽滑入/滑出、被覆盖层 1/4 宽视差、RTL 镜像),
 * 但被覆盖层随覆盖进度完全淡出,而不是停在 alpha = 0.9。
 *
 * 不能直接用 NavTransitions.MiuixDefault 的原因:CN+ 的页面在「背景图片」模式下容器色为透明
 * (appSurfaceColor() 返回 Color.Transparent,壁纸由 NavDisplay 背后的 AppBackgroundLayer 提供),
 * 被覆盖层停在 0.9 会让它的卡片/文字透过上层页面显形。旧实现(AnimatedContent)在转场结束后
 * 把下层移出组合,不存在叠加,这里以「覆盖即淡出」保持观感一致。
 *
 * @param gestureTracker 手势样本记录器(返回触发阈值判定用);传 null 表示不记录。
 */
internal fun appNavTransition(gestureTracker: AppBackGestureTracker?): NavTransition =
    navGraphicsTransition(opaqueDepth = 1f) { scope ->
        val width = scope.layoutSize.width.toFloat()
        val depth = scope.relativeDepth
        val rtl = scope.layoutDirection == LayoutDirection.Rtl
        if (depth <= 0f) {
            // 顶层:顺带记录手势样本(提交回调在阈值判定时读取)。
            gestureTracker?.onFrame(
                gesture = scope.gesture,
                settleActive = scope.settle != null,
                depth = depth,
                nowMillis = SystemClock.uptimeMillis()
            )
            // 顶层进出:自后缘整宽滑入/滑出。进入偏移取整设备像素,避免带圆角裁剪的页面边缘
            // 在分数偏移下闪出细线(与 MiuixDefault 同处理)。
            translationX = ((if (rtl) -1f else 1f) * (-depth).coerceIn(0f, 1f) * width)
                .roundToInt()
                .toFloat()
        } else {
            val covered = depth.coerceIn(0f, 1f)
            translationX = (if (rtl) 1f else -1f) * covered * width * 0.25f
            alpha = 1f - covered
        }
    }
