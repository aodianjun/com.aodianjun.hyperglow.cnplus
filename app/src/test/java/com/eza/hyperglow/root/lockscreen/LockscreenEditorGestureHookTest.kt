package com.eza.hyperglow.root.lockscreen

import com.eza.hyperglow.root.HookInstallGuard
import com.eza.hyperglow.root.symbols.SymbolRequest
import com.eza.hyperglow.root.symbols.SymbolResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LockscreenEditorGestureHookTest {
    @Test
    fun suppressionRequiresSettingAndVerifiedCapability() {
        assertTrue(shouldSuppressLockscreenEditorGesture(enabled = true, supported = true))
        assertFalse(shouldSuppressLockscreenEditorGesture(enabled = false, supported = true))
        assertFalse(shouldSuppressLockscreenEditorGesture(enabled = true, supported = false))
    }

    @Test
    fun installGuardRunsSuccessfulInstallOnlyOnce() {
        val guard = HookInstallGuard()
        var attempts = 0

        assertTrue(guard.runOnce { attempts += 1; true })
        assertFalse(guard.runOnce { attempts += 1; true })

        assertEquals(1, attempts)
        assertTrue(guard.isInstalled())
    }

    @Test
    fun missingEditorSymbolsResolveToNullSoInstallDegradesSilently() {
        SymbolResolver.clearCaches()
        val withoutSystemUi = object : ClassLoader(javaClass.classLoader) {
            override fun loadClass(name: String): Class<*> = throw ClassNotFoundException(name)
        }

        assertNull(
            SymbolResolver.resolveMethod(
                withoutSystemUi,
                "editor-gesture",
                SymbolRequest.method(
                    "com.android.keyguard.editor.KeyguardEditorHelper",
                    "onTouchEvent",
                    "android.view.MotionEvent"
                )
            )
        )
    }
}
