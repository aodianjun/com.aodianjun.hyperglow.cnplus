package com.eza.hyperglow.ui

import android.content.Context
import com.eza.hyperglow.aod.AodRenderPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OnboardingPreferencesTest {
    private val prefsNames = listOf(
        "hyperglow_onboarding",
        AodRenderPreferences.PREFS,
        "app_ui_appearance",
        "app_navigation"
    )

    @Before
    fun clearPreferences() {
        prefsNames.forEach { name ->
            RuntimeEnvironment.getApplication()
                .getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
    }

    @Test
    fun freshInstallShowsOnboarding() {
        assertTrue(OnboardingPreferences.shouldShow(RuntimeEnvironment.getApplication()))
    }

    @Test
    fun completedFlagHidesOnboarding() {
        val context = RuntimeEnvironment.getApplication()

        OnboardingPreferences.markCompleted(context)

        assertFalse(OnboardingPreferences.shouldShow(context))
    }

    @Test
    fun existingUserConfigurationHidesOnboarding() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(AodRenderPreferences.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(AodRenderPreferences.AOD_ENABLED, true)
            .commit()

        assertFalse(OnboardingPreferences.shouldShow(context))
    }
}
