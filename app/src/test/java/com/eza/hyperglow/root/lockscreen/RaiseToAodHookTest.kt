package com.eza.hyperglow.root.lockscreen

import com.eza.hyperglow.root.HookInstallGuard
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RaiseToAodHookTest {
    @Test
    fun onlyVerifiedEnabledPickupWakeIsSuppressed() {
        assertTrue(
            shouldSuppressPickupWake(
                enabled = true,
                wakeHookSupported = true,
                details = "com.android.systemui:PICK_UP"
            )
        )
        assertFalse(shouldSuppressPickupWake(false, true, "com.android.systemui:PICK_UP"))
        assertFalse(shouldSuppressPickupWake(true, false, "com.android.systemui:PICK_UP"))
        assertFalse(shouldSuppressPickupWake(true, true, "android.policy:POWER"))
        assertFalse(shouldSuppressPickupWake(true, true, null))
    }

    @Test
    fun abortedInstallStaysRetryableAfterMissingSymbol() {
        val guard = HookInstallGuard()
        var attempts = 0

        assertFalse(guard.runOnce { attempts += 1; false })
        assertFalse(guard.isInstalled())

        assertTrue(guard.runOnce { attempts += 1; true })
        assertEquals(2, attempts)
        assertTrue(guard.isInstalled())

        assertFalse(guard.runOnce { attempts += 1; true })
        assertEquals(2, attempts)
    }

    @Test
    fun hiddenWakeUpSignatureMissResolvesToNull() {
        SymbolResolver.clearCaches()

        assertNull(
            SymbolResolver.resolveMethod(
                javaClass.classLoader!!,
                "raise-to-aod",
                SymbolRequest.method(
                    "android.os.PowerManager",
                    "wakeUp",
                    "long",
                    "java.lang.String"
                )
            )
        )
    }
}
