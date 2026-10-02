package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AodIslandGuardTest {
    @Test
    fun gateOpensOnDozeEdgeAndClosesOnInteractiveEdge() {
        assertEquals(IslandGateAction.OPEN, islandGateAction(isInteractive = false, gateActive = false))
        assertEquals(IslandGateAction.CLOSE, islandGateAction(isInteractive = true, gateActive = true))
    }

    @Test
    fun gateStaysWithinTheSameInteractiveState() {
        assertEquals(IslandGateAction.STAY, islandGateAction(isInteractive = false, gateActive = true))
        assertEquals(IslandGateAction.STAY, islandGateAction(isInteractive = true, gateActive = false))
    }

    @Test
    fun hostVisibleRequestOverridesRecordedBeliefState() {
        assertEquals(0, nextIslandRestoreValue(recorded = null, requested = 0))
        assertEquals(0, nextIslandRestoreValue(recorded = 4, requested = 0))
        assertEquals(8, nextIslandRestoreValue(recorded = 0, requested = 8))
    }

    @Test
    fun hostGoneRequestNeverTouchesTheLedger() {
        assertNull(nextIslandRestoreValue(recorded = null, requested = ISLAND_VIEW_GONE))
        assertEquals(4, nextIslandRestoreValue(recorded = 4, requested = ISLAND_VIEW_GONE))
    }

    @Test
    fun goneConstantMatchesFrameworkViewGone() {
        assertEquals(2, ISLAND_VIEW_GONE)
    }

    @Test
    fun gateActionIsTotalOverAllStates() {
        for (interactive in booleanArrayOf(true, false)) {
            for (active in booleanArrayOf(true, false)) {
                when {
                    !interactive && !active -> assertEquals(
                        IslandGateAction.OPEN, islandGateAction(interactive, active)
                    )
                    interactive && active -> assertEquals(
                        IslandGateAction.CLOSE, islandGateAction(interactive, active)
                    )
                    else -> assertEquals(
                        IslandGateAction.STAY, islandGateAction(interactive, active)
                    )
                }
            }
        }
    }
}
