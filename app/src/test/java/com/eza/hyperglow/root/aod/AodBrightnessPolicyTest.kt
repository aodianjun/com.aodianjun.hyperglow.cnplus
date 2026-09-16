package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Test

class AodBrightnessPolicyTest {
    @Test
    fun activeLyricGuardClampsOnlyLowNonzeroBrightnessInExactAodState() {
        assertEquals(
            255,
            resolveAodBrightnessRequest(
                requestedBrightness = 1,
                readableBrightness = 255,
                lyricGuardActive = true,
                dozeStateName = "DOZE_AOD"
            )
        )
        assertEquals(
            255,
            resolveAodBrightnessRequest(
                requestedBrightness = 128,
                readableBrightness = 255,
                lyricGuardActive = true,
                dozeStateName = "DOZE_AOD"
            )
        )
    }

    @Test
    fun inactiveGuardAndNonAodStatesKeepXiaomiBrightnessAuthority() {
        val states = listOf(
            null,
            "DOZE",
            "DOZE_AOD_PAUSING",
            "DOZE_AOD_PAUSED",
            "DOZE_REQUEST_PULSE",
            "FINISH"
        )

        assertEquals(1, resolveAodBrightnessRequest(1, 255, false, "DOZE_AOD"))
        states.forEach { state ->
            assertEquals(1, resolveAodBrightnessRequest(1, 255, true, state))
        }
    }

    @Test
    fun offInvalidAndAlreadyReadableRequestsPassThrough() {
        listOf(-1, 0, 255, 300).forEach { requested ->
            assertEquals(
                requested,
                resolveAodBrightnessRequest(requested, 255, true, "DOZE_AOD")
            )
        }
        assertEquals(1, resolveAodBrightnessRequest(1, 0, true, "DOZE_AOD"))
        assertEquals(1, resolveAodBrightnessRequest(1, -1, true, "DOZE_AOD"))
    }

    @Test
    fun stateGateRequiresExactAodStateBeforeClamping() {
        assertEquals(1, resolveAodBrightnessRequest(1, 255, true, null))
        assertEquals(255, resolveAodBrightnessRequest(1, 255, true, "DOZE_AOD"))
    }

    @Test
    fun disabledBoostKeepsXiaomiBrightnessAuthority() {
        assertEquals(
            1,
            resolveAodBrightnessRequest(
                requestedBrightness = 1,
                readableBrightness = 255,
                lyricGuardActive = true,
                dozeStateName = "DOZE_AOD",
                boostEnabled = false
            )
        )
        assertEquals(
            128,
            resolveAodBrightnessRequest(128, 255, true, "DOZE_AOD", boostEnabled = false)
        )
    }

    @Test
    fun brightnessOverrideForcesFixedLevelInExactAodState() {
        assertEquals(
            120,
            resolveAodBrightnessRequest(
                requestedBrightness = 1,
                readableBrightness = 255,
                lyricGuardActive = true,
                dozeStateName = "DOZE_AOD",
                brightnessOverrideEnabled = true,
                brightnessOverrideLevel = 120
            )
        )
    }

    @Test
    fun brightnessOverrideIsClampedToSafeRange() {
        // 越界档位收窄到 [10, 255]。
        assertEquals(
            10,
            resolveAodBrightnessRequest(1, 255, true, "DOZE_AOD", true, 3)
        )
        assertEquals(
            255,
            resolveAodBrightnessRequest(1, 255, true, "DOZE_AOD", true, 999)
        )
    }

    @Test
    fun brightnessOverrideRespectsDozeStateAndGuardGates() {
        // 非 DOZE_AOD 状态:覆写不应生效。
        assertEquals(
            1,
            resolveAodBrightnessRequest(1, 255, true, "DOZE", true, 120)
        )
        // guard 未激活:覆写不生效。
        assertEquals(
            1,
            resolveAodBrightnessRequest(1, 255, false, "DOZE_AOD", true, 120)
        )
    }
}
