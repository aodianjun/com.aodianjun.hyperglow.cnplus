package com.eza.hyperglow.ui

import com.eza.hyperglow.customization.SceneCompiler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.yukonga.miuix.kmp.nav.core.navBackStackOf
import top.yukonga.miuix.kmp.nav.transition.NavGesture
import top.yukonga.miuix.kmp.nav.transition.NavSwipeEdge

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

    // --- 返回触发阈值 ---

    @Test
    fun backTriggerReachedCommitsQuickFlickBelowThreshold() {
        // 阈值 50% 时轻快一甩(拖动只有 20%)仍应提交:velocity-first。
        assertTrue(backTriggerReached(progress = 0.2f, velocity = 1.4f, thresholdPercent = 50))
    }

    @Test
    fun backTriggerReachedCancelsSlowDragBelowThreshold() {
        assertFalse(backTriggerReached(progress = 0.2f, velocity = 0.1f, thresholdPercent = 50))
    }

    @Test
    fun backTriggerReachedCommitsAtOrAboveThreshold() {
        assertTrue(backTriggerReached(progress = 0.5f, velocity = 0f, thresholdPercent = 50))
        assertTrue(backTriggerReached(progress = 0.9f, velocity = 0f, thresholdPercent = 50))
    }

    @Test
    fun backTriggerReachedWithZeroThresholdAlwaysCommits() {
        // 0% = 判定交还系统:应用侧不再否决任何提交。
        assertTrue(backTriggerReached(progress = 0f, velocity = 0f, thresholdPercent = 0))
    }

    @Test
    fun normalizeBackTriggerPercentSnapsToStepAndClamps() {
        assertEquals(0, normalizeBackTriggerPercent(-10))
        assertEquals(0, normalizeBackTriggerPercent(3))
        assertEquals(25, normalizeBackTriggerPercent(27))
        assertEquals(60, normalizeBackTriggerPercent(75))
    }

    // --- 手势样本与否决 ---

    @Test
    fun gestureTrackerReportsReleaseProgressAndVelocity() {
        val tracker = AppBackGestureTracker()

        tracker.onFrame(gesture(0.2f), settleActive = false, depth = -0.2f, nowMillis = 1_000L)
        tracker.onFrame(gesture(0.4f), settleActive = false, depth = -0.4f, nowMillis = 1_100L)

        val release = requireNotNull(tracker.release())
        assertEquals(0.4f, release.progress, 0.0001f)
        assertEquals(2f, release.velocity, 0.0001f)
    }

    @Test
    fun gestureTrackerDropsSampleWhenNoGestureIsInFlight() {
        // 没有手势(返回键/顶栏按钮/系统离散返回)时不能留下样本,否则它们会被阈值拦住。
        val tracker = AppBackGestureTracker()
        tracker.onFrame(gesture(0.6f), settleActive = false, depth = -0.6f, nowMillis = 1_000L)

        tracker.onFrame(gesture = null, settleActive = false, depth = 0f, nowMillis = 1_016L)

        assertNull(tracker.release())
    }

    @Test
    fun gestureTrackerDropsSampleWhileSettleOwnsTheDriver() {
        // 松手收敛期间手势上下文仍冻结保留,但驱动已交给 settle:这段不能当实时手势。
        val tracker = AppBackGestureTracker()
        tracker.onFrame(gesture(0.6f), settleActive = false, depth = -0.6f, nowMillis = 1_000L)

        tracker.onFrame(gesture(0.6f), settleActive = true, depth = -0.6f, nowMillis = 1_016L)

        assertNull(tracker.release())
    }

    @Test
    fun gestureTrackerDropsSampleAfterVetoReturnsPageToRest() {
        // 否决后条目不卸载、手势上下文保持冻结:页面已回到静止位,不能再留样本(否则下一次
        // 返回键会被误判成手势提交而被阈值拦住)。
        val tracker = AppBackGestureTracker()
        tracker.onFrame(gesture(0.2f), settleActive = false, depth = -0.2f, nowMillis = 1_000L)

        tracker.onFrame(gesture(0.2f), settleActive = false, depth = 0f, nowMillis = 1_100L)

        assertNull(tracker.release())
    }

    @Test
    fun vetoPopsThenRestoresTheSameRoute() {
        val stack = navBackStackOf(AppRoute.Home)
        stack.pushRoute(AppRoute.Diagnostics)
        val veto = AppBackVetoState()

        veto.veto(stack)
        assertEquals(listOf(AppRoute.Home), stack.toList())

        veto.restoreIfPending(stack)
        assertEquals(listOf(AppRoute.Home, AppRoute.Diagnostics), stack.toList())
    }

    private fun gesture(progress: Float) =
        NavGesture(progress = progress, swipeEdge = NavSwipeEdge.None, touchY = 0f)
}
