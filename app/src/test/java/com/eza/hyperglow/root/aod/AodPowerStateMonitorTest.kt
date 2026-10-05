package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [isAodPowerSaverActive] — the pure AOD power-saver predicate:
 * battery low while discharging, or device thermally throttled (≥ MODERATE) — and for
 * [isAodPowerSaverEffective], the user-switch gate in front of it.
 */
class AodPowerStateMonitorTest {

    @Test
    fun lowBatteryWhileDischargingActivatesSaver() {
        assertTrue(isAodPowerSaverActive(batteryPercent = 15, charging = false, thermalStatus = null))
        assertTrue(isAodPowerSaverActive(batteryPercent = 5, charging = false, thermalStatus = null))
        assertTrue(isAodPowerSaverActive(batteryPercent = 0, charging = false, thermalStatus = null))
    }

    @Test
    fun chargingExemptsLowBattery() {
        // 插电时电量低不降帧:功耗不是瓶颈,视觉流畅度优先。
        assertFalse(isAodPowerSaverActive(batteryPercent = 5, charging = true, thermalStatus = null))
        assertFalse(isAodPowerSaverActive(batteryPercent = 15, charging = true, thermalStatus = null))
    }

    @Test
    fun batteryAboveThresholdKeepsFullCadence() {
        assertFalse(isAodPowerSaverActive(batteryPercent = 16, charging = false, thermalStatus = null))
        assertFalse(isAodPowerSaverActive(batteryPercent = 100, charging = false, thermalStatus = null))
    }

    @Test
    fun thermalThrottlingActivatesSaverRegardlessOfBattery() {
        // THERMAL_STATUS_MODERATE(2) 及以上降帧;LIGHT(1) 不降。
        assertTrue(isAodPowerSaverActive(batteryPercent = 100, charging = true, thermalStatus = 2))
        assertTrue(isAodPowerSaverActive(batteryPercent = 100, charging = true, thermalStatus = 4))
        assertFalse(isAodPowerSaverActive(batteryPercent = 100, charging = true, thermalStatus = 1))
        assertEquals(
            AOD_POWER_SAVER_THERMAL_STATUS,
            2
        )
    }

    @Test
    fun unknownReadingsKeepFullCadence() {
        // 粘性广播/热状态读取失败(null)时不得误降帧。
        assertFalse(isAodPowerSaverActive(batteryPercent = null, charging = null, thermalStatus = null))
        assertFalse(isAodPowerSaverActive(batteryPercent = null, charging = false, thermalStatus = null))
        assertFalse(isAodPowerSaverActive(batteryPercent = 10, charging = null, thermalStatus = null))
    }

    @Test
    fun eitherConditionAloneIsSufficient() {
        assertTrue(
            isAodPowerSaverActive(batteryPercent = 10, charging = false, thermalStatus = 1)
        )
        assertTrue(
            isAodPowerSaverActive(batteryPercent = 90, charging = false, thermalStatus = 3)
        )
    }

    /**
     * 用户开关总闸:关闭时任何电量/温度状态都不降帧(画布恒按帧上限渲染);
     * 开关缺省(null,旧配置/旧生产者不带该字段)按开启处理,保持历史降帧行为。
     */
    @Test
    fun userSwitchGatesTheSaver() {
        assertFalse(isAodPowerSaverEffective(switchEnabled = false, saverActive = true))
        assertFalse(isAodPowerSaverEffective(switchEnabled = false, saverActive = false))
        assertTrue(isAodPowerSaverEffective(switchEnabled = true, saverActive = true))
        assertFalse(isAodPowerSaverEffective(switchEnabled = true, saverActive = false))
        assertTrue(isAodPowerSaverEffective(switchEnabled = null, saverActive = true))
        assertFalse(isAodPowerSaverEffective(switchEnabled = null, saverActive = false))
    }
}
