package com.eza.hyperglow.ui

import androidx.compose.ui.unit.LayoutDirection
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.nav.core.NavBackStack
import top.yukonga.miuix.kmp.nav.core.NavKey
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

    /** 外观编辑页:曲面(aod/lockscreen)是路由值的一部分,两个曲面可各自在栈上留一份。 */
    @Serializable
    data class LyricLayout(val surface: String) : AppRoute
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

/**
 * 应用导航转场:几何沿用 miuix 默认档(顶层自后缘整宽滑入/滑出、被覆盖层 1/4 宽视差、RTL 镜像),
 * 但被覆盖层随覆盖进度完全淡出,而不是停在 alpha = 0.9。
 *
 * 不能直接用 NavTransitions.MiuixDefault 的原因:CN+ 的页面在「背景图片」模式下容器色为透明
 * (appSurfaceColor() 返回 Color.Transparent,壁纸由 NavDisplay 背后的 AppBackgroundLayer 提供),
 * 被覆盖层停在 0.9 会让它的卡片/文字透过上层页面显形。旧实现(AnimatedContent)在转场结束后
 * 把下层移出组合,不存在叠加,这里以「覆盖即淡出」保持观感一致。
 */
internal val AppNavTransition: NavTransition = navGraphicsTransition(opaqueDepth = 1f) { scope ->
    val width = scope.layoutSize.width.toFloat()
    val depth = scope.relativeDepth
    val rtl = scope.layoutDirection == LayoutDirection.Rtl
    if (depth <= 0f) {
        // 顶层进出:自后缘整宽滑入/滑出。进入偏移取整设备像素,避免带圆角裁剪的页面边缘
        // 在分数偏移下闪出细线(与 MiuixDefault 同处理)。
        translationX = ((if (rtl) -1f else 1f) * (-depth).coerceIn(0f, 1f) * width).roundToInt().toFloat()
    } else {
        val covered = depth.coerceIn(0f, 1f)
        translationX = (if (rtl) 1f else -1f) * covered * width * 0.25f
        alpha = 1f - covered
    }
}
