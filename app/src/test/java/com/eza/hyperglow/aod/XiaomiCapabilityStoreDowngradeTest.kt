package com.eza.hyperglow.aod

import com.eza.hyperglow.root.capability.XiaomiProfileState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the app-side guard against the startup race: SystemUI reports 4/16 before the AOD dex
 * loads, which would otherwise overwrite a recent available report with unsupported_profile.
 */
class XiaomiCapabilityStoreDowngradeTest {

    @Test
    fun partialAodSnapshotDoesNotDowngradeRecentAvailableReport() {
        assertTrue(
            shouldIgnorePartialAodDowngrade(
                incomingProfileState = XiaomiProfileState.UNSUPPORTED_PROFILE,
                incomingAodSurfaceProbe = false,
                existingProfileState = XiaomiProfileState.AVAILABLE,
                existingAodSurfaceProbe = true,
                existingAgeMs = 30_000L
            )
        )
    }

    @Test
    fun incomingReportWithAodProbeIsAccepted() {
        assertFalse(
            shouldIgnorePartialAodDowngrade(
                incomingProfileState = XiaomiProfileState.UNSUPPORTED_PROFILE,
                incomingAodSurfaceProbe = true,
                existingProfileState = XiaomiProfileState.AVAILABLE,
                existingAodSurfaceProbe = true,
                existingAgeMs = 30_000L
            )
        )
    }

    @Test
    fun staleExistingReportIsAccepted() {
        assertFalse(
            shouldIgnorePartialAodDowngrade(
                incomingProfileState = XiaomiProfileState.UNSUPPORTED_PROFILE,
                incomingAodSurfaceProbe = false,
                existingProfileState = XiaomiProfileState.AVAILABLE,
                existingAodSurfaceProbe = true,
                existingAgeMs = PARTIAL_AOD_DOWNGRADE_GRACE_MS + 1L
            )
        )
    }

    @Test
    fun existingUnsupportedReportIsAccepted() {
        assertFalse(
            shouldIgnorePartialAodDowngrade(
                incomingProfileState = XiaomiProfileState.UNSUPPORTED_PROFILE,
                incomingAodSurfaceProbe = false,
                existingProfileState = XiaomiProfileState.UNSUPPORTED_PROFILE,
                existingAodSurfaceProbe = true,
                existingAgeMs = 30_000L
            )
        )
    }

    @Test
    fun incomingAvailableReportIsAccepted() {
        assertFalse(
            shouldIgnorePartialAodDowngrade(
                incomingProfileState = XiaomiProfileState.AVAILABLE,
                incomingAodSurfaceProbe = false,
                existingProfileState = XiaomiProfileState.AVAILABLE,
                existingAodSurfaceProbe = true,
                existingAgeMs = 30_000L
            )
        )
    }
}
