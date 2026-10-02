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
        assertEquals(4, nextIslandRestoreValue(recorded = 0, requested = 4))
    }

    @Test
    fun hostGoneRequestNeverTouchesTheLedger() {
        assertNull(nextIslandRestoreValue(recorded = null, requested = ISLAND_VIEW_GONE))
        assertEquals(4, nextIslandRestoreValue(recorded = 4, requested = ISLAND_VIEW_GONE))
    }

    @Test
    fun goneConstantMatchesFrameworkViewGone() {
        // View.VISIBLE=0 / INVISIBLE=4 / GONE=8
        assertEquals(8, ISLAND_VIEW_GONE)
    }

    @Test
    fun visibleFlagRequestsAreRewrittenToGonePreservingOtherBits() {
        // setVisibility(VISIBLE) 到达 setFlags 的形态:flags=0, mask=可见位段
        assertEquals(ISLAND_VIEW_GONE, rewrittenIslandFlagsRequest(0, ISLAND_FLAG_VISIBILITY_MASK))
        // 其余 flag 位(低两位)保留,仅可见位段被改写为 GONE
        assertEquals(0x3 or ISLAND_VIEW_GONE, rewrittenIslandFlagsRequest(0x3, 0x3 or ISLAND_FLAG_VISIBILITY_MASK))
        // INVISIBLE(4)同样改写为 GONE(doze 期间只接受 GONE)
        assertEquals(ISLAND_VIEW_GONE, rewrittenIslandFlagsRequest(4, ISLAND_FLAG_VISIBILITY_MASK))
    }

    @Test
    fun goneAndNonVisibilityRequestsPassThrough() {
        // setVisibility(GONE) 到达 setFlags 的形态:flags 位值 8(= GONE),原样直通
        assertNull(rewrittenIslandFlagsRequest(8, ISLAND_FLAG_VISIBILITY_MASK))
        // 掩码不含可见位段(setFlags 的普通 flag 变更)直通
        assertNull(rewrittenIslandFlagsRequest(0, 0x3))
        // 请求位落在掩码外且掩码内的可见位已是 GONE:直通
        assertNull(rewrittenIslandFlagsRequest(8 or 0x3, 0x3 or ISLAND_FLAG_VISIBILITY_MASK))
    }

    @Test
    fun visibilityMaskMatchesFrameworkValue() {
        assertEquals(0x0000000C, ISLAND_FLAG_VISIBILITY_MASK)
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
