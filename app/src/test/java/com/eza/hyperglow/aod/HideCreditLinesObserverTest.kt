package com.eza.hyperglow.aod

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 2.12「hideCreditLines 组合期冻结」修复的读取/观察缝测试：
 * 观察者只对 HIDE_CREDIT_LINES 回调，且拿到本次变更后的值（直接读原始偏好，绕过
 * [AodRenderPreferences.read] 的组合期缓存，避免与缓存失效监听的通知顺序竞争）；
 * 坏类型回落默认值、不抛异常。Compose 侧接线（DisposableEffect 注册/注销）依赖
 * 组合环境，人工验证步骤见交付说明。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class HideCreditLinesObserverTest {

    private fun prefs(): SharedPreferences =
        RuntimeEnvironment.getApplication()
            .getSharedPreferences(AodRenderPreferences.PREFS, Context.MODE_PRIVATE)

    @Test
    fun observerReceivesOnlyHideCreditLinesChangesWithFreshValue() {
        val prefs = prefs()
        val seen = mutableListOf<Boolean>()
        val observer = AodRenderPreferences.HideCreditLinesObserver { seen += it }
        prefs.registerOnSharedPreferenceChangeListener(observer)
        try {
            prefs.edit().putBoolean(AodRenderPreferences.HIDE_CREDIT_LINES, true).commit()
            // 无关键不触发回调。
            prefs.edit().putBoolean(AodRenderPreferences.AOD_ENABLED, false).commit()
            prefs.edit().putBoolean(AodRenderPreferences.HIDE_CREDIT_LINES, false).commit()
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(observer)
        }

        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun malformedStoredValueFallsBackToDefaultWithoutThrowing() {
        val prefs = prefs()
        prefs.edit().putString(AodRenderPreferences.HIDE_CREDIT_LINES, "not-a-boolean").commit()

        assertEquals(false, AodRenderPreferences.readHideCreditLines(prefs))
    }
}
