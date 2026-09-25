package com.eza.hyperglow.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LogThrottle] — 按键时间窗节流 + 抑制计数(Bridge DiagnosticThrottler
 * 同型)。时钟注入,行为完全确定。
 */
class LogThrottleTest {

    @Test
    fun firstCallLogsThenSuppressesWithinWindow() {
        var now = 1_000L
        val throttle = LogThrottle { now }
        assertTrue(throttle.shouldLog("k", 5_000L))
        now = 3_000L
        assertFalse(throttle.shouldLog("k", 5_000L))
        now = 5_999L
        assertFalse(throttle.shouldLog("k", 5_000L))
        now = 6_000L
        assertTrue(throttle.shouldLog("k", 5_000L))
    }

    @Test
    fun suppressedCountAccumulatesAndDrains() {
        var now = 0L
        val throttle = LogThrottle { now }
        assertTrue(throttle.shouldLog("k", 1_000L))
        now = 200L
        assertFalse(throttle.shouldLog("k", 1_000L))
        now = 500L
        assertFalse(throttle.shouldLog("k", 1_000L))
        now = 700L
        assertFalse(throttle.shouldLog("k", 1_000L))
        assertEquals(3, throttle.drainSuppressed("k"))
        // 取走后清零;再抑制重新累计。
        now = 800L
        assertFalse(throttle.shouldLog("k", 1_000L))
        assertEquals(1, throttle.drainSuppressed("k"))
        assertEquals(0, throttle.drainSuppressed("k"))
    }

    @Test
    fun keysAreThrottledIndependently() {
        var now = 0L
        val throttle = LogThrottle { now }
        assertTrue(throttle.shouldLog("a", 1_000L))
        assertTrue(throttle.shouldLog("b", 1_000L))
        now = 100L
        assertFalse(throttle.shouldLog("a", 1_000L))
        assertTrue(throttle.shouldLog("b", 1_000L))
        assertEquals(1, throttle.drainSuppressed("a"))
        assertEquals(0, throttle.drainSuppressed("b"))
    }

    @Test
    fun clockGoingBackwardsDoesNotExtendWindowForever() {
        // elapsedRealtime 不会回退,但注入时钟回归时按"已过窗口"放行,不卡死。
        var now = 10_000L
        val throttle = LogThrottle { now }
        assertTrue(throttle.shouldLog("k", 1_000L))
        now = 0L
        assertTrue(throttle.shouldLog("k", 1_000L))
    }
}
