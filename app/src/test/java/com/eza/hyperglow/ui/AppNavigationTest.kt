package com.eza.hyperglow.ui

import com.eza.hyperglow.customization.SceneCompiler
import org.junit.Assert.assertEquals
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.navBackStackOf

/**
 * 返回栈操作契约:重复入栈必须被幂等跳过(miuix-nav 在 reconcile 期拒绝重复 contentKey 并抛
 * IllegalArgumentException),根路由不出栈(NavDisplay 拒绝空栈,根上的系统返回由平台处理为退出应用)。
 */
class AppNavigationTest {

    @Test
    fun pushRouteSkipsRouteAlreadyOnStack() {
        val stack = navBackStackOf(AppRoute.Home)

        stack.pushRoute(AppRoute.Diagnostics)
        stack.pushRoute(AppRoute.Diagnostics)

        assertEquals(listOf(AppRoute.Home, AppRoute.Diagnostics), stack.toList())
    }

    @Test
    fun pushRouteKeepsSameDestinationOnDistinctSurfaces() {
        val stack = navBackStackOf(AppRoute.Home)

        stack.pushRoute(AppRoute.LyricLayout(SceneCompiler.SURFACE_AOD))
        stack.pushRoute(AppRoute.LyricLayout(SceneCompiler.SURFACE_LOCKSCREEN))

        assertEquals(
            listOf(
                AppRoute.Home,
                AppRoute.LyricLayout(SceneCompiler.SURFACE_AOD),
                AppRoute.LyricLayout(SceneCompiler.SURFACE_LOCKSCREEN)
            ),
            stack.toList()
        )
    }

    @Test
    fun popRouteNeverPopsTheRootEntry() {
        val stack = navBackStackOf(AppRoute.Home)

        stack.popRoute()

        assertEquals(listOf(AppRoute.Home), stack.toList())
    }

    @Test
    fun popRouteRemovesTopEntryAboveRoot() {
        val stack = navBackStackOf(AppRoute.Home)
        stack.pushRoute(AppRoute.Diagnostics)

        stack.popRoute()

        assertEquals(listOf(AppRoute.Home), stack.toList())
    }
}
