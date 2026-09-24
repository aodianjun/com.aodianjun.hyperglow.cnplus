package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AodPositionUpdateTest {
    @Test
    fun brightLinkageReservesTopClockMorphRegion() {
        assertEquals(AodRenderedClockBounds(0, 935), brightLinkageClockBounds(2670))
    }

    @Test
    fun physicalBrightClockMorphDoesNotDependOnLyricLinkageAcceptance() {
        assertTrue(shouldUseBrightClockMorphGeometry(true, true, false, 2))
        assertTrue(shouldUseBrightClockMorphGeometry(true, false, true, 2))
        assertFalse(shouldUseBrightClockMorphGeometry(true, true, false, 3))
        assertFalse(shouldUseBrightClockMorphGeometry(false, true, true, 2))
    }

    @Test
    fun managedClockBoundsRemainSingleStableGeometryAuthority() {
        assertEquals(
            AodRenderedClockBounds(1463, 2037),
            resolvedAodClockBounds(
                renderedBounds = AodRenderedClockBounds(1851, 2425),
                controlledTop = 1463,
                controlledBottom = 2037,
                measuredTop = 1907,
                measuredBottom = 2350
            )
        )
    }

    @Test
    fun exactPhysicalClockBoundsOverrideDivergedManagedTarget() {
        assertEquals(
            AodRenderedClockBounds(263, 837),
            resolvedAodClockBounds(
                renderedBounds = null,
                controlledTop = 1851,
                controlledBottom = 2425,
                measuredTop = 1851,
                measuredBottom = 2425,
                exactPhysicalBounds = AodRenderedClockBounds(263, 837)
            )
        )
    }

    @Test
    fun exactClockCollisionAuthorityPrefersVisibleSystemUiThenAodTarget() {
        val systemUi = AodRenderedClockBounds(263, 837)
        val aodTarget = AodRenderedClockBounds(1851, 2425)

        assertEquals(systemUi, selectPhysicalAodClockBounds(systemUi, aodTarget))
        assertEquals(aodTarget, selectPhysicalAodClockBounds(null, aodTarget))
        assertEquals(
            aodTarget,
            resolvedAodClockBounds(
                renderedBounds = AodRenderedClockBounds(426, 837),
                controlledTop = aodTarget.top,
                controlledBottom = aodTarget.bottom,
                measuredTop = 426,
                measuredBottom = 837,
                exactPhysicalBounds = selectPhysicalAodClockBounds(null, null)
            )
        )
    }

    @Test
    fun opposingClockCrossfadeUsesManagedTargetBounds() {
        assertEquals(
            AodRenderedClockBounds(1851, 2425),
            resolvedAodClockBounds(
                renderedBounds = AodRenderedClockBounds(426, 837),
                controlledTop = 1851,
                controlledBottom = 2425,
                measuredTop = 426,
                measuredBottom = 2425
            )
        )
    }

    @Test
    fun aodRevealProgressUsesSmoothElapsedTimeCurve() {
        assertEquals(0f, smoothAodRevealProgress(0f), 0.0001f)
        assertEquals(0.5f, smoothAodRevealProgress(0.5f), 0.0001f)
        assertEquals(1f, smoothAodRevealProgress(1f), 0.0001f)
    }

    @Test
    fun settledSnapshotAndLayoutRefreshCannotBypassAodAuthorityOrEligibility() {
        assertTrue(shouldRenderAodSnapshot(true, true, true, true, false))
        assertFalse(shouldRenderAodSnapshot(false, true, true, true, false))
        assertFalse(shouldRenderAodSnapshot(true, true, false, true, false))
        assertFalse(shouldRenderAodSnapshot(true, true, true, false, false))
        assertFalse(shouldRenderAodSnapshot(true, true, true, true, true))
        assertFalse(
            shouldRenderAodSnapshot(
                true,
                true,
                true,
                true,
                false,
                spotifyAuthorized = false
            )
        )
    }

    @Test
    fun positionUpdatesCoalesceToLatestValue() {
        val coalescer = AodPositionUpdateCoalescer()

        assertTrue(coalescer.offer(AodPositionUpdate(3, 2f, 4f, 600)))
        assertFalse(coalescer.offer(AodPositionUpdate(3, 8f, 12f, 580)))
        assertEquals(AodPositionUpdate(3, 8f, 12f, 580), coalescer.drain(3))
        assertNull(coalescer.drain(3))
    }

    @Test
    fun detachedGenerationCancelsPendingUpdate() {
        val coalescer = AodPositionUpdateCoalescer()
        coalescer.offer(AodPositionUpdate(3, 8f, 12f))

        assertNull(coalescer.drain(4))
    }

    @Test
    fun horizontalBurnInTranslationMovesAndClampsOverlaySlot() {
        assertEquals(
            AodSurfaceRect(80, 224, 960, 676),
            calculateAodSurfaceRect(1000, 700, 200, 24, 880, 700, 20)
        )
        assertEquals(
            AodSurfaceRect(120, 224, 1000, 676),
            calculateAodSurfaceRect(1000, 700, 200, 24, 880, 700, 500)
        )
    }

    @Test
    fun translatedStockBottomUsesWindowCoordinates() {
        assertEquals(240, stockBottomInRoot(100, 260, 80))
    }

    @Test
    fun laterStockBottomRecomputesVerticalSlot() {
        val before = calculateAodSurfaceRect(1000, 700, 200, 24, 880, 700)
        val after = calculateAodSurfaceRect(1000, 700, 260, 24, 880, 700)

        assertEquals(224, before.top)
        assertEquals(284, after.top)
    }

    @Test
    fun fingerprintBoundaryReservesBottomSafeRegion() {
        assertEquals(
            AodSurfaceRect(60, 224, 940, 526),
            calculateAodSurfaceRect(1000, 700, 200, 24, 880, 700, safeBottom = 550)
        )
    }

    @Test
    fun liveLinkageGeometryExposesFullTopAndBottomClockZones() {
        assertEquals(
            AodClockZoneBounds(100f, 900f, 120, 420, 920, 1220),
            resolveAodClockZoneBounds(
                AodClockGeometry(
                    mode = 3,
                    baseTranslationY = 120f,
                    translationYStep = 100f,
                    viewTop = 20,
                    viewHeight = 300
                )
            )
        )
    }

    @Test
    fun moduleManagedSixZonePatternMovesClockAndLyricsAcrossFullCanvas() {
        val geometry = AodClockGeometry(3, 120f, 100f, 20, 300, 18)
        val first = managedAodClockDecision("six_zone", 0, 0, 200f, geometry)!!
        val second = managedAodClockDecision("six_zone", 1, 0, 200f, geometry)!!

        assertEquals(AodSceneZone.CLOCK_BOTTOM, first.zone)
        assertEquals(18, first.appliedTranslationX)
        assertEquals(900f, first.appliedTranslationY, 0.0001f)
        assertEquals(AodSceneZone.CLOCK_TOP, second.zone)
        assertEquals(-18, second.appliedTranslationX)
        assertEquals(100f, second.appliedTranslationY, 0.0001f)
        assertEquals(
            com.eza.hyperglow.root.surface.PlacementRect(0f, 120f, 1080f, 896f),
            aodSceneSafeCanvas(1080, 2400, first.clockTop, first.lyricTopSafe, 24, first.zone)
        )
    }

    @Test
    fun configuredPatternsHaveBoundedDeterministicCycles() {
        assertEquals(1, aodBurnInPatternSlots("static_top").size)
        assertEquals(1, aodBurnInPatternSlots("static_bottom").size)
        assertEquals(6, aodBurnInPatternSlots("six_zone").size)
        assertEquals(4, aodBurnInPatternSlots("four_corner").size)
        assertEquals(2, aodBurnInPatternSlots("vertical_swap").size)
        val geometry = AodClockGeometry(3, 120f, 100f, 20, 300, 18)
        assertEquals(
            managedAodClockDecision("vertical_swap", 0, 0, 200f, geometry),
            managedAodClockDecision("vertical_swap", 2, 0, 200f, geometry)
        )
        val static = managedAodClockDecision("static_bottom", 0, 0, 200f, geometry)
        val staticTop = managedAodClockDecision("static_top", 0, 0, 200f, geometry)
        assertEquals(static, managedAodClockDecision("static_bottom", 100, 0, 200f, geometry))
        assertEquals(staticTop, managedAodClockDecision("static_top", 100, 0, 200f, geometry))
        assertEquals(AodSceneZone.CLOCK_BOTTOM, static?.zone)
        assertEquals(AodSceneZone.CLOCK_TOP, staticTop?.zone)
        assertEquals(0, static?.appliedTranslationX)
        assertFalse(managedAodPatternRepeats("static_top"))
        assertFalse(managedAodPatternRepeats("static_bottom"))
        assertTrue(managedAodPatternRepeats("six_zone"))
    }

    @Test
    fun stockLinkageInitialPositionIsDeterministicBeforeFirstTranslationCallback() {
        assertEquals(
            AodNaturalTranslation(0, 500f),
            naturalAodTranslation(
                AodClockGeometry(
                    mode = 3,
                    baseTranslationY = 120f,
                    translationYStep = 100f,
                    viewTop = 20,
                    viewHeight = 300,
                    translationXStep = 18
                ),
                moveCurrent = 8
            )
        )
    }

    @Test
    fun standardAodInitialPositionUsesXiaomiGridIndex() {
        assertEquals(
            AodNaturalTranslation(18, 300f),
            naturalAodTranslation(
                AodClockGeometry(
                    mode = 0,
                    baseTranslationY = 120f,
                    translationYStep = 100f,
                    viewTop = 20,
                    viewHeight = 300,
                    translationXStep = 18
                ),
                moveCurrent = 16
            )
        )
    }

    @Test
    fun managedAnchorReappliesWhenXiaomiGeometryChangesAcrossScreenState() {
        val before = managedAodClockDecision(
            "static_bottom",
            0,
            0,
            200f,
            AodClockGeometry(3, 120f, 100f, 20, 300)
        )!!
        val after = managedAodClockDecision(
            "static_bottom",
            0,
            0,
            200f,
            AodClockGeometry(3, 160f, 90f, 40, 360)
        )!!

        assertTrue(managedAodPlacementChanged(before, after))
        assertFalse(managedAodPlacementChanged(after, after.copy(requestedTranslationY = 999f)))
    }

    @Test
    fun failedManagedAdvanceRollsBackOnlyUnchangedAttempt() {
        val attempted = managedAodClockDecision(
            "six_zone",
            2,
            0,
            200f,
            AodClockGeometry(3, 120f, 100f, 20, 300)
        )!!

        assertTrue(shouldRollbackFailedManagedAdvance(2, attempted, 2, attempted))
        assertFalse(shouldRollbackFailedManagedAdvance(3, attempted, 2, attempted))
        assertFalse(
            shouldRollbackFailedManagedAdvance(
                2,
                attempted.copy(appliedTranslationX = attempted.appliedTranslationX + 1),
                2,
                attempted
            )
        )
    }

    @Test
    fun failedStockRestoreClearsOnlyCapturedManagedPlacement() {
        val captured = managedAodClockDecision(
            "static_bottom",
            0,
            0,
            200f,
            AodClockGeometry(3, 120f, 100f, 20, 300)
        )!!

        assertTrue(shouldClearFailedManagedRestore(0, captured, 0, captured))
        assertFalse(
            shouldClearFailedManagedRestore(
                0,
                captured.copy(appliedTranslationY = captured.appliedTranslationY + 1f),
                0,
                captured
            )
        )
        assertFalse(shouldClearFailedManagedRestore(1, captured, 0, captured))
        assertFalse(shouldClearFailedManagedRestore(0, null, 0, captured))
    }

    @Test
    fun clockBottomZoneUsesEntireAlreadyBoundedLyricRegion() {
        assertEquals(1f, aodPlacementMaxHeightFraction(0.42f, AodSceneZone.CLOCK_BOTTOM))
        assertEquals(0.42f, aodPlacementMaxHeightFraction(0.42f, AodSceneZone.CLOCK_TOP))
    }

    @Test
    fun renderedClockPositionOwnsTheFreeSideDuringManagedMotion() {
        assertEquals(
            AodSceneZone.CLOCK_TOP,
            resolveRenderedAodSceneZone(
                AodSceneZone.CLOCK_BOTTOM,
                AodRenderedClockBounds(120, 420),
                rootHeight = 2400,
                margin = 24
            )
        )
        assertEquals(
            AodSceneZone.CLOCK_BOTTOM,
            resolveRenderedAodSceneZone(
                AodSceneZone.CLOCK_TOP,
                AodRenderedClockBounds(1740, 2040),
                rootHeight = 2400,
                margin = 24
            )
        )
        assertEquals(
            AodSceneZone.STOCK,
            resolveRenderedAodSceneZone(
                AodSceneZone.STOCK,
                AodRenderedClockBounds(1740, 2040),
                rootHeight = 2400,
                margin = 24
            )
        )
    }

    @Test
    fun zoneUsesHysteresisSoAClockMarginMoveDoesNotFlipTheLayout() {
        // root=2400, margin=24, hysteresis=240。先用无滞回调用确认严格比较下这些边界判定不变。
        // 滞回吸收临界点附近的微移,避免 CLOCK_TOP↔CLOCK_BOTTOM 反复翻转、画布在全高与一条
        // 之间骤变(issue #46)。

        // CLOCK_TOP(时钟偏上、下方空间大):当时钟略微下移使空间差落在滞回带内时,维持不翻转。
        // 无滞回时该空间差(freeBelow-freeAbove=172)已可触发 CLOCK_TOP;有滞回(需>240)则保持 managed。
        assertEquals(
            AodSceneZone.CLOCK_TOP,
            resolveRenderedAodSceneZone(
                AodSceneZone.CLOCK_TOP,
                AodRenderedClockBounds(1024, 1204),
                rootHeight = 2400,
                margin = 24,
                hysteresis = 240
            )
        )
        // 时钟继续显著下移,下方空间被边缘化(freeAbove-freeBelow=424 > 240)=> 翻转到 CLOCK_BOTTOM。
        assertEquals(
            AodSceneZone.CLOCK_BOTTOM,
            resolveRenderedAodSceneZone(
                AodSceneZone.CLOCK_TOP,
                AodRenderedClockBounds(1280, 1544),
                rootHeight = 2400,
                margin = 24,
                hysteresis = 240
            )
        )
        // 边界例:freeAbove-freeBelow=24(接近 0)。有滞回(240)时不翻转,保持 managed=CLOCK_TOP;
        // 默认 hysteresis=0 时则按旧严格比较翻转到 CLOCK_BOTTOM。
        assertEquals(
            AodSceneZone.CLOCK_TOP,
            resolveRenderedAodSceneZone(
                AodSceneZone.CLOCK_TOP,
                AodRenderedClockBounds(1200, 1224),
                rootHeight = 2400,
                margin = 24,
                hysteresis = 240
            )
        )
        assertEquals(
            AodSceneZone.CLOCK_BOTTOM,
            resolveRenderedAodSceneZone(
                AodSceneZone.CLOCK_TOP,
                AodRenderedClockBounds(1200, 1224),
                rootHeight = 2400,
                margin = 24,
                hysteresis = 0
            )
        )
    }

    @Test
    fun renderedClockNeverOverridesManagedTarget() {
        assertEquals(
            AodRenderedClockBounds(120, 420),
            resolvedAodClockBounds(
                renderedBounds = AodRenderedClockBounds(700, 1100),
                controlledTop = 120,
                controlledBottom = 420,
                measuredTop = 80,
                measuredBottom = 380
            )
        )
        assertEquals(
            AodRenderedClockBounds(120, 420),
            resolvedAodClockBounds(
                renderedBounds = null,
                controlledTop = 120,
                controlledBottom = 420,
                measuredTop = 80,
                measuredBottom = 380
            )
        )
        assertEquals(
            AodRenderedClockBounds(80, 380),
            resolvedAodClockBounds(
                renderedBounds = null,
                controlledTop = 420,
                controlledBottom = 120,
                measuredTop = 80,
                measuredBottom = 380
            )
        )
    }

    @Test
    fun unsupportedWallpaperModePassesThroughWithoutClockMutation() {
        val decision = managedAodClockDecision(
            "six_zone",
            0,
            10,
            50f,
            AodClockGeometry(2, 120f, 100f, 20, 300)
        )

        assertNull(decision)
    }

    @Test
    fun rememberedPhysicalBoundsOutrankTheManagedRequestWhenTheClockCannotBeMeasured() {
        val resolved = resolvedAodClockBounds(
            renderedBounds = null,
            controlledTop = 263,
            controlledBottom = 1266,
            measuredTop = 0,
            measuredBottom = 0,
            exactPhysicalBounds = null,
            rememberedPhysicalBounds = AodRenderedClockBounds(1015, 2019)
        )

        assertEquals(1015, resolved.top)
        assertEquals(2019, resolved.bottom)
    }

    @Test
    fun aLivePhysicalMeasurementStillOutranksTheRememberedOne() {
        val resolved = resolvedAodClockBounds(
            renderedBounds = null,
            controlledTop = 263,
            controlledBottom = 1266,
            measuredTop = 0,
            measuredBottom = 0,
            exactPhysicalBounds = AodRenderedClockBounds(300, 1300),
            rememberedPhysicalBounds = AodRenderedClockBounds(1015, 2019)
        )

        assertEquals(300, resolved.top)
        assertEquals(1300, resolved.bottom)
    }

    @Test
    fun anchorSeedsFromFirstRawBounds() {
        val anchor = stabilizeAodClockAnchor(
            previous = null,
            raw = AodRenderedClockBounds(546, 1626),
            nowElapsedMs = 0L
        )
        assertEquals(546, anchor.top)
        assertEquals(1626, anchor.bottom)
    }

    @Test
    fun anchorHoldsOuterExtentThroughMediaHeaderOscillation() {
        // Media header present squeezes the clock up; absent releases it. Oscillation must not move
        // the anchor because the held position keeps getting reconfirmed.
        val deep = AodRenderedClockBounds(546, 1626)
        val squeezed = AodRenderedClockBounds(336, 1416)
        var t = 0L
        val holdMs = 40_000L
        var anchor = stabilizeAodClockAnchor(null, deep, t, holdMs)
        // Oscillate between the two states every ~10s for well over the hold window.
        repeat(20) {
            t += 10_000L
            anchor = stabilizeAodClockAnchor(anchor, squeezed, t, holdMs)
            assertEquals("squeezed state must not pull the anchor", 1626, anchor.bottom)
            t += 10_000L
            anchor = stabilizeAodClockAnchor(anchor, deep, t, holdMs)
            assertEquals(1626, anchor.bottom)
        }
    }

    @Test
    fun anchorRelocatesOnlyAfterAGenuinePersistentMove() {
        val holdMs = 40_000L
        val deep = AodRenderedClockBounds(546, 1626)
        val movedUp = AodRenderedClockBounds(336, 1416)
        var t = 0L
        var anchor = stabilizeAodClockAnchor(null, deep, t, holdMs)
        // Within the hold window the anchor holds.
        t = 30_000L
        anchor = stabilizeAodClockAnchor(anchor, movedUp, t, holdMs)
        assertEquals(1626, anchor.bottom)
        // Past the hold window (40s) the anchor follows the persistent move.
        t = 60_000L
        anchor = stabilizeAodClockAnchor(anchor, movedUp, t, holdMs)
        assertEquals(1416, anchor.bottom)
        assertEquals(336, anchor.top)
    }

    @Test
    fun anchorFollowsSmallBurnInStepDownwardImmediately() {
        // issue #23 补充:下行立即硬同步、不做防抖——烧屏把时钟下拖 50px,anchor 应立刻吸附,
        // 否则歌词停留旧位、时钟移走后长期错位。
        var t = 0L
        var anchor = stabilizeAodClockAnchor(null, AodRenderedClockBounds(546, 1626), t)
        t = 1_000L
        anchor = stabilizeAodClockAnchor(anchor, AodRenderedClockBounds(546, 1700), t)
        assertEquals(1700, anchor.bottom)
        assertEquals(546, anchor.top)
    }

    @Test
    fun anchorFollowsDownwardRelocationImmediately() {
        // 大步下行同样立即跟随(真实版式迁移)。
        var t = 0L
        var anchor = stabilizeAodClockAnchor(null, AodRenderedClockBounds(546, 1626), t)
        t = 1_000L
        anchor = stabilizeAodClockAnchor(anchor, AodRenderedClockBounds(546, 1900), t)
        assertEquals(1900, anchor.bottom)
        assertEquals(546, anchor.top)
    }

    @Test
    fun anchorKeepsTrackingEachDownwardBurnInStep() {
        // 连续多个小步下行:每一步都应立即吸附,而不是被 holdMs 困住。
        var t = 0L
        var anchor = stabilizeAodClockAnchor(null, AodRenderedClockBounds(546, 1626), t)
        t = 1_000L
        anchor = stabilizeAodClockAnchor(anchor, AodRenderedClockBounds(546, 1700), t)
        assertEquals(1700, anchor.bottom)
        t = 2_000L
        anchor = stabilizeAodClockAnchor(anchor, AodRenderedClockBounds(546, 1774), t)
        assertEquals(1774, anchor.bottom)
        t = 3_000L
        anchor = stabilizeAodClockAnchor(anchor, AodRenderedClockBounds(546, 1848), t)
        assertEquals(1848, anchor.bottom)
    }

    @Test
    fun seedSinceElapsedMsIsInheritedWhenSeedingAfterADrop() {
        val anchor = stabilizeAodClockAnchor(
            previous = null,
            raw = AodRenderedClockBounds(546, 1626),
            nowElapsedMs = 90_000L,
            seedSinceElapsedMs = 30_000L
        )
        assertEquals(30_000L, anchor.sinceElapsedMs)
    }

    @Test
    fun physicalReadGapKeepsTheStableAnchorInsteadOfEatingAStaleRememberedDownMove() {
        // issue #38:物理读数缺失是瞬时缺口,不是时钟真的下移。若把一条 stale 的低位 remembered 值
        // (bottom 更大)喂进锚定器,会被当成"下行"立即硬同步,把歌词压到低位;此后真实位置上行恢复
        // 又被 40s 防抖压住,歌词锁在低位。修复:无新鲜读数且有稳定锚时,直接沿用锚。
        val stable = AodRenderedClockBounds(493, 1574)   // 实测稳定的系统时钟
        var t = 0L
        val anchor = resolveAnchoredAodClockBounds(
            hasFreshPhysical = false,
            previousAnchor = AodClockAnchor(stable.top, stable.bottom, t),
            rawClockBounds = AodRenderedClockBounds(1160, 2240), // stale remembered,不是新读数
            nowElapsedMs = t
        )
        assertEquals(1574, anchor.bottom)
        assertEquals(493, anchor.top)
    }

    @Test
    fun freshPhysicalDownMoveStillRelocatesTheAnchorImmediately() {
        // 真实物理读数到位(hasFreshPhysical=true)时仍走原有锚定逻辑:下行立即硬同步。
        var t = 0L
        val previous = AodClockAnchor(493, 1574, t)
        t = 1_000L
        val anchor = resolveAnchoredAodClockBounds(
            hasFreshPhysical = true,
            previousAnchor = previous,
            rawClockBounds = AodRenderedClockBounds(525, 1606),
            nowElapsedMs = t
        )
        assertEquals(1606, anchor.bottom)
    }

    @Test
    fun physicalReadGapWithoutEstablishedAnchorStillStabilizesFromAvailableRaw() {
        // 尚无稳定锚时(首次布局/面板暗),读数缺失也要从可得来源建立锚,沿用原 stabilize 行为。
        var t = 0L
        val anchor = resolveAnchoredAodClockBounds(
            hasFreshPhysical = false,
            previousAnchor = null,
            rawClockBounds = AodRenderedClockBounds(493, 1574),
            nowElapsedMs = t
        )
        assertEquals(1574, anchor.bottom)
    }

    @Test
    fun routingPutsPinAboveManagedAndSuppressAboveAll() {
        // issue #33 建议二:pin 与 managed 互斥,锚定优先;suppress 仍最高。
        assertTrue(
            routesToManagedPath(
                suppressActive = false,
                stockWidgetControlActive = true,
                pinClockVisible = false
            )
        )
        assertFalse(
            routesToManagedPath(
                suppressActive = false,
                stockWidgetControlActive = true,
                pinClockVisible = true
            )
        )
        assertFalse(
            routesToManagedPath(
                suppressActive = true,
                stockWidgetControlActive = true,
                pinClockVisible = false
            )
        )
        assertFalse(
            routesToManagedPath(
                suppressActive = false,
                stockWidgetControlActive = false,
                pinClockVisible = false
            )
        )
    }

    @Test
    fun seededControllerStateInheritsAnchorAcrossControllerReplacement() {
        // issue #33 建议一:controller 更替重建时继承锚定,而不是回到 null
        // (null 会让 stockResolution 以已下移的请求值就地重锚)。
        val seeded = seedControllerState(inheritedX = 390, inheritedY = 1471f)
        assertEquals(390, seeded.lastStockTranslationX)
        assertEquals(1471f, seeded.lastStockTranslationY)
        // 无继承值(跨会话首次):锚定字段留空,由 stockResolution 经
        // resolveStockAnchorSeed 按几何就绪情况播种(issue #66:绝不用请求值兜底)。
        val fresh = seedControllerState(inheritedX = null, inheritedY = null)
        assertNull(fresh.lastStockTranslationX)
        assertNull(fresh.lastStockTranslationY)
    }

    @Test
    fun pinnedClockAppliedYAddsOffsetOnceWithoutAccumulating() {
        // 冻结:应用 Y = 锚定值 + 一次性偏移;锚定基准本身不随帧累积
        // (state.lastStockTranslationY 始终保留原始锚定值)。
        assertEquals(1501f, pinnedClockAppliedY(1471f, 30, freeze = true, requestedY = 1942f))
        // 连续多帧:同一锚定 + 同一偏移 = 同一应用值(不逐帧叠加)。
        assertEquals(1501f, pinnedClockAppliedY(1471f, 30, freeze = true, requestedY = 2000f))
        // 未冻结:原样透传请求值。
        assertEquals(1942f, pinnedClockAppliedY(1471f, 30, freeze = false, requestedY = 1942f))
    }

    @Test
    fun needsClockYWritebackOnlyForPinnedStockClock() {
        // issue #39:仅 STOCK 时钟被钉住(应用 Y != 请求 Y)时才做渲染层写回。
        assertTrue(
            needsClockYWriteback(
                AodClockPlacementDecision(
                    requestedTranslationX = 390,
                    requestedTranslationY = -174f,
                    appliedTranslationX = 390,
                    appliedTranslationY = 260f,
                    clockTop = 771,
                    clockBottom = 1574,
                    lyricTopSafe = 771,
                    zone = AodSceneZone.STOCK,
                    zoneChanged = false,
                    overridden = false
                )
            )
        )
        // 普通透传(applied == requested)不触碰渲染视图。
        assertFalse(
            needsClockYWriteback(
                AodClockPlacementDecision(
                    requestedTranslationX = 390,
                    requestedTranslationY = 260f,
                    appliedTranslationX = 390,
                    appliedTranslationY = 260f,
                    clockTop = 771,
                    clockBottom = 1574,
                    lyricTopSafe = 771,
                    zone = AodSceneZone.STOCK,
                    zoneChanged = false,
                    overridden = false
                )
            )
        )
        // managed 位移(CLOCK_TOP/BOTTOM 区域)不做渲染层写回。
        assertFalse(
            needsClockYWriteback(
                AodClockPlacementDecision(
                    requestedTranslationX = 0,
                    requestedTranslationY = 120f,
                    appliedTranslationX = 0,
                    appliedTranslationY = 520f,
                    clockTop = 631,
                    clockBottom = 1434,
                    lyricTopSafe = 631,
                    zone = AodSceneZone.CLOCK_TOP,
                    zoneChanged = true,
                    overridden = true
                )
            )
        )
    }

    @Test
    fun stockClockDecisionKeepsRequestedSeparateFromPinnedAppliedY() {
        // issue #39:冻结时 appliedY(锚定+偏移)必须与系统请求的 requestedY 分离落到决策,
        // 否则 needsClockYWriteback 恒为 false,渲染层写回永不触发。
        val geometry = AodClockGeometry(
            mode = 2,
            baseTranslationY = 0f,
            translationYStep = 0f,
            viewTop = 511,
            viewHeight = 803
        )
        val pinned = stockClockDecision(
            requestedX = 390,
            requestedY = -174f,
            appliedY = 260f,
            geometry = geometry,
            zoneChanged = false
        )
        assertEquals(-174f, pinned.requestedTranslationY)
        assertEquals(260f, pinned.appliedTranslationY)
        // clockTop/Bottom 由实际应用值推导,供布局安全区使用。
        assertEquals(771, pinned.clockTop)
        assertEquals(1574, pinned.clockBottom)
        assertEquals(AodSceneZone.STOCK, pinned.zone)
        assertFalse(pinned.overridden)
        assertTrue(needsClockYWriteback(pinned))
    }

    @Test
    fun stockClockDecisionPassThroughKeepsAppliedEqualToRequested() {
        // 未冻结:appliedY == requestedY,不触发渲染层写回(普通透传不受影响)。
        val geometry = AodClockGeometry(
            mode = 2,
            baseTranslationY = 0f,
            translationYStep = 0f,
            viewTop = 511,
            viewHeight = 803
        )
        val passThrough = stockClockDecision(
            requestedX = 390,
            requestedY = 260f,
            appliedY = 260f,
            geometry = geometry,
            zoneChanged = false
        )
        assertEquals(260f, passThrough.requestedTranslationY)
        assertEquals(260f, passThrough.appliedTranslationY)
        assertFalse(needsClockYWriteback(passThrough))
    }

    @Test
    fun burnInVerticalStepMatchesNaturalTranslationGridFormula() {
        // 与 naturalAodTranslation 内部垂直步进公式严格一致(issue #66:反解
        // mTranslationY 基准值依赖同一垂直步进,二者必须同根,否则锁定会偏一格)。
        val geometry = AodClockGeometry(
            mode = 2,
            baseTranslationY = 231f,
            translationYStep = 18.5f,
            viewTop = 510,
            viewHeight = 803
        )
        for (moveCurrent in listOf(0, 2, 4, 10, 36)) {
            val natural = naturalAodTranslation(geometry, moveCurrent)!!
            val verticalStep = burnInVerticalStep(geometry.mode, moveCurrent)
            // natural.y = baseTranslationY + translationYStep*verticalStep - viewTop
            val recomposed = geometry.baseTranslationY +
                geometry.translationYStep * verticalStep - geometry.viewTop
            assertEquals(recomposed, natural.y, 0.001f)
        }
    }

    @Test
    fun burnInVerticalStepRespectsModeGridLayout() {
        // mode0 三列网格:verticalStep=halfStep/3;mode2/3 纯垂直:verticalStep=halfStep;
        // 其余 mode 不参与垂直位移返回 0。
        assertEquals(0, burnInVerticalStep(mode = 0, moveCurrent = 0))
        assertEquals(0, burnInVerticalStep(mode = 0, moveCurrent = 5))
        assertEquals(1, burnInVerticalStep(mode = 0, moveCurrent = 6))
        assertEquals(0, burnInVerticalStep(mode = 2, moveCurrent = 1))
        assertEquals(1, burnInVerticalStep(mode = 2, moveCurrent = 2))
        assertEquals(5, burnInVerticalStep(mode = 3, moveCurrent = 10))
        assertEquals(0, burnInVerticalStep(mode = 1, moveCurrent = 10))
        assertEquals(0, burnInVerticalStep(mode = 7, moveCurrent = 10))
    }

    @Test
    fun resolveStockAnchorSeedInheritsExistingAnchor() {
        // issue #66:已有锚定值 → 原样继承(同一 AOD 会话内重建不重置,issue #36),
        // 即使当前几何未就绪也不许重锚。
        val broken = AodClockGeometry(
            mode = 0,
            baseTranslationY = Float.NaN,
            translationYStep = 0f,
            viewTop = 0,
            viewHeight = 0
        )
        val seed = resolveStockAnchorSeed(1471f, broken)
        assertEquals(1471f, seed.anchorY)
        assertEquals(StockAnchorSeedSource.INHERITED, seed.source)
    }

    @Test
    fun resolveStockAnchorSeedUsesNaturalBaselineWhenGeometryReady() {
        // issue #66 根因二:首次播种取未位移基准 baseTranslationY - viewTop(即系统
        // 步进=0 时的 f),不用已随防烧屏位移的 requestedY。步进项为 0 时结果与 mode
        // 无关,故不走 naturalAodTranslation 的 mode 白名单(mode=1 也可播种)。
        val geometry = AodClockGeometry(
            mode = 1,
            baseTranslationY = 390f,
            translationYStep = 52.5f,
            viewTop = 36,
            viewHeight = 1080
        )
        val seed = resolveStockAnchorSeed(null, geometry)
        assertEquals(354f, seed.anchorY)
        assertEquals(StockAnchorSeedSource.NATURAL_BASELINE, seed.source)
    }

    @Test
    fun resolveStockAnchorSeedDefersWhenGeometryNotReady() {
        // issue #66 实机印证:AOD 刚进入时 step<=0 / viewHeight<=0 / 非有限,
        // 此时必须返回 null(调用方透传不播种),而不是回落到已位移的 requestedY
        // ——兜底值一旦写入 lastStockTranslationY 即被永久继承,钉住即固化偏移。
        val zeroStep = AodClockGeometry(
            mode = 0,
            baseTranslationY = 390f,
            translationYStep = 0f,
            viewTop = 36,
            viewHeight = 1080
        )
        assertNull(resolveStockAnchorSeed(null, zeroStep).anchorY)
        assertNull(resolveStockAnchorSeed(null, zeroStep).source)
        val zeroHeight = zeroStep.copy(translationYStep = 52.5f, viewHeight = 0)
        assertNull(resolveStockAnchorSeed(null, zeroHeight).anchorY)
        val nanBase = zeroStep.copy(baseTranslationY = Float.NaN, translationYStep = 52.5f)
        assertNull(resolveStockAnchorSeed(null, nanBase).anchorY)
    }
}
