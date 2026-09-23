package com.eza.hyperglow.root.aod

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横屏相关行为的纯函数测试:
 *  - 「横屏自动隐藏系统息屏内容、回落竖屏恢复」的判定 [shouldHideStockAodContent];
 *  - 横屏全屏化「自动铺满不越界」的放缩比 [fullscreenAutoScale];
 *  - 横屏刚性变换(平移分量/缩放枢轴)与缩放上限 [landscapeRotationTransform];#57 现场数据回归见测试内注释。
 */
class AodLandscapeBehaviorTest {

    // ---- shouldHideStockAodContent ----

    @Test
    fun globalSuppressAlwaysHides() {
        assertTrue(
            shouldHideStockAodContent(
                suppressBase = true,
                landscapeStep = false,
                rotateWithDevice = false,
                landscapeHideStock = false
            )
        )
    }

    @Test
    fun landscapeHideRequiresBothLandscapeAndRotationEnabled() {
        // 开关开启、横屏、随设备旋转 → 隐藏。
        assertTrue(
            shouldHideStockAodContent(
                suppressBase = false,
                landscapeStep = true,
                rotateWithDevice = true,
                landscapeHideStock = true
            )
        )
        // 竖屏回落 → 恢复(不隐藏)。
        assertTrue(
            !shouldHideStockAodContent(
                suppressBase = false,
                landscapeStep = false,
                rotateWithDevice = true,
                landscapeHideStock = true
            )
        )
        // 虽横屏但未开启随设备旋转 → 不隐藏。
        assertTrue(
            !shouldHideStockAodContent(
                suppressBase = false,
                landscapeStep = true,
                rotateWithDevice = false,
                landscapeHideStock = true
            )
        )
        // 开关未开启 → 不隐藏。
        assertTrue(
            !shouldHideStockAodContent(
                suppressBase = false,
                landscapeStep = true,
                rotateWithDevice = true,
                landscapeHideStock = false
            )
        )
    }

    @Test
    fun fullyOffYieldsNoSuppression() {
        assertTrue(
            !shouldHideStockAodContent(
                suppressBase = false,
                landscapeStep = false,
                rotateWithDevice = false,
                landscapeHideStock = false
            )
        )
    }

    // ---- fullscreenAutoScale ----

    @Test
    fun fillsAvailableHeightWhenRoomAllows() {
        // 可用高 1080,内容高 540,fill 0.85 → 1.7,命中上限 1.7。
        assertEquals(
            1.7f,
            fullscreenAutoScale(540f, 1080f, 0.85f, 1.0f, 1.7f),
            0.001f
        )
        // 相同输入、更宽上限 2.0 → 1080/540*0.85 = 1.7。
        assertEquals(
            1.7f,
            fullscreenAutoScale(540f, 1080f, 0.85f, 1.0f, 2.0f),
            0.001f
        )
    }

    @Test
    fun neverBelowMinimumFromShrinking() {
        // 内容比可用高更高 → 原始倍数 <1,钳制到最小 1.0(不缩小)。
        assertEquals(
            1.0f,
            fullscreenAutoScale(2000f, 1080f, 0.85f, 1.0f, 1.7f),
            0.001f
        )
    }

    @Test
    fun proportionalInterpolationRespected() {
        // 可用 1080 / 内容 800 * 0.85 = 1.1475,落于 [1.0, 1.7] 内。
        assertEquals(
            1.1475f,
            fullscreenAutoScale(800f, 1080f, 0.85f, 1.0f, 1.7f),
            0.001f
        )
    }

    @Test
    fun degenerateInputFallsBackToMinimum() {
        assertEquals(1.0f, fullscreenAutoScale(0f, 1080f, 0.85f, 1.0f, 1.7f), 0.001f)
        assertEquals(1.0f, fullscreenAutoScale(540f, 0f, 0.85f, 1.0f, 1.7f), 0.001f)
        assertEquals(1.0f, fullscreenAutoScale(540f, 1080f, -0.2f, 1.0f, 1.7f), 0.001f)
        assertEquals(1.0f, fullscreenAutoScale(540f, 1080f, Float.NaN, 1.0f, 1.7f), 0.001f)
    }

    // ---- fullscreenWidthCapScale / resolveFullscreenLandscapeScale (issue #51) ----

    @Test
    fun widthCapKeepsWidestLineInsideCanvas() {
        // 最长行 2000px、可用逻辑宽 2400 → 上限 1.2;短轴填充给出 1.7 时被截到 1.2,
        // 行两端不被裁出画布。
        assertEquals(1.2f, fullscreenWidthCapScale(2000f, 2400f, 1.0f, 1.7f), 0.001f)
    }

    @Test
    fun widthCapDoesNotBindNarrowContent() {
        // 短行内容:2400/1000=2.4 超过 maxScale,不设限;仍由短轴填充与 maxScale 决定。
        assertEquals(1.7f, fullscreenWidthCapScale(1000f, 2400f, 1.0f, 1.7f), 0.001f)
    }

    @Test
    fun widthCapNeverShrinksContent() {
        // 行已占满/超出可用宽度 → 比例 <=1,钳到 minScale(保持原大,不缩小)。
        assertEquals(1.0f, fullscreenWidthCapScale(2400f, 2400f, 1.0f, 1.7f), 0.001f)
        assertEquals(1.0f, fullscreenWidthCapScale(3000f, 2400f, 1.0f, 1.7f), 0.001f)
    }

    @Test
    fun widthCapDegenerateInputDoesNotBind() {
        assertEquals(1.7f, fullscreenWidthCapScale(0f, 2400f, 1.0f, 1.7f), 0.001f)
        assertEquals(1.7f, fullscreenWidthCapScale(1200f, 0f, 1.0f, 1.7f), 0.001f)
    }

    @Test
    fun resolvedScalePicksTighterOfHeightFillAndWidthCap() {
        // issue #51 现场形态:内容高 301、可用高 906(短轴公式给 1.7),最长行 1800、
        // 可用宽 2400(长轴上限 1.33) → 取更严的 1.33,且两轴均无溢出(of=none)。
        val decision = resolveFullscreenLandscapeScale(
            contentHeight = 301f,
            availableHeight = 906f,
            maxLineWidth = 1800f,
            availableWidth = 2400f,
            fillRatio = 0.85f,
            minScale = 1.0f,
            maxScale = 1.7f
        )
        assertEquals(1.3333f, decision.scale, 0.001f)
        assertEquals("none", decision.overflowAxes)
    }

    @Test
    fun resolvedScaleReportsOverflowAxisForOversizedContent() {
        // 内容比可用区还大(缩放下限 1.0 也放不下)→ of 必须如实报出溢出轴。
        val decision = resolveFullscreenLandscapeScale(
            contentHeight = 1200f,
            availableHeight = 906f,
            maxLineWidth = 3000f,
            availableWidth = 2400f,
            fillRatio = 0.85f,
            minScale = 1.0f,
            maxScale = 1.7f
        )
        assertEquals(1.0f, decision.scale, 0.001f)
        assertEquals("hw", decision.overflowAxes)
    }

    // ---- issue #51 根因回归:横屏对齐基准必须是逻辑帧宽 ow,而非视口宽 ----

    @Test
    fun landscapeCenteredLineMustAlignAgainstLogicalFrameWidth() {
        // 视口 906x2400(竖屏画布),横屏逻辑帧 2400x906。一条 1400px 行居中:
        // 正确基准 ow=2400 → startX=500;若误用视口宽 906 → startX=-247(负坐标)。
        val correct = edgeSafeAlignedStart(2400f, 0f, 0f, 0f, 1400f, "center")
        assertEquals(500f, correct, 0.001f)
        val wrong = edgeSafeAlignedStart(906f, 0f, 0f, 0f, 1400f, "center")
        assertTrue(wrong < 0f)
    }

    // ---- issue #55/#57 横屏刚性变换:平移分量与缩放枢轴 ----

    @Test
    fun legacySymmetricTranslateReproducesIssue57FieldBounds() {
        // 现场数据回归(issue #57):0.3.103 的自检日志
        //   Landscape bounds: clip=(0,0)-(2400,906) bounds=(1392,-10)-(2932,4070) view=906x2400
        // 用旧参数(translate 共用同一量 t=d/scale、scale 绕视口中心)复算:两个对角角点必须与
        // 日志逐点吻合,以证明本文件的映射模型与真机 Canvas 行为完全一致。
        val legacy = LandscapeRotationTransform(
            degrees = 90f,
            viewPivotX = 453f,
            viewPivotY = 1200f,
            translateX = -747f / 1.7f,
            translateY = -747f / 1.7f,
            scale = 1.7f,
            scalePivotX = 453f,
            scalePivotY = 1200f
        )
        val bounds = mapLandscapeLogicalRect(legacy, 0f, 0f, 2400f, 906f)

        assertEquals(1392, bounds.minX.roundToInt())
        assertEquals(4070, bounds.maxY.roundToInt())
        assertEquals(2932, bounds.maxX.roundToInt())
        assertEquals(-10, bounds.minY.roundToInt())
        // 旧参数下内容与画布完全不相交 —— 这正是「横屏什么都看不到」的直接证据。
        assertFalse(bounds.hits(906, 2400))
    }

    @Test
    fun logicalFrameMapsExactlyOntoViewportAtScaleOne() {
        // 修复后的参数在 scale=1 时把逻辑帧四角逐点映射到视口四角:
        // 两种画布尺寸(整屏 906x2400、旧中间态 906x720)× 两个旋转方向都必须成立。
        listOf(906 to 2400, 906 to 720).forEach { (w, h) ->
            listOf(AodOrientationStep.LANDSCAPE, AodOrientationStep.REVERSE_LANDSCAPE).forEach { step ->
                val transform = landscapeRotationTransform(w, h, step, 1f)!!
                val bounds = mapLandscapeLogicalRect(transform, 0f, 0f, h.toFloat(), w.toFloat())
                assertEquals("minX w=$w h=$h $step", 0f, bounds.minX, 0.001f)
                assertEquals("minY w=$w h=$h $step", 0f, bounds.minY, 0.001f)
                assertEquals("maxX w=$w h=$h $step", w.toFloat(), bounds.maxX, 0.001f)
                assertEquals("maxY w=$w h=$h $step", h.toFloat(), bounds.maxY, 0.001f)
            }
        }
    }

    @Test
    fun scaledContentStaysCenteredAndCoversViewport() {
        // issue #57 现场参数:视口 906x2400、scale=1.7。逻辑帧中心必须映射到视口中心(缩放
        // 对称),放大后的逻辑帧完整覆盖视口(内容有余量,而不是被整体推出画布)。
        val transform =
            landscapeRotationTransform(906, 2400, AodOrientationStep.LANDSCAPE, 1.7f)!!
        val center = mapLandscapeLogicalPoint(transform, 1200f, 453f)

        assertEquals(453f, center.first, 0.001f)
        assertEquals(1200f, center.second, 0.001f)
        val frame = mapLandscapeLogicalRect(transform, 0f, 0f, 2400f, 906f)
        assertTrue(frame.covers(906, 2400))
        assertTrue(frame.hits(906, 2400))
    }

    @Test
    fun rotationTransformUsesLogicalFrameCenterAsScalePivot() {
        // 缩放发生在逻辑坐标系内(pre-concat 的 S·T·R),枢轴必须取逻辑帧中心 (oh/2, ow/2),
        // 而不是视口中心 —— 记错坐标系会让放大后的内容整体偏移。#57 修复的另一半是平移分量:
        // 逻辑帧坐标系内取 (d, -d),x/y 必须不同号。
        val transform =
            landscapeRotationTransform(906, 2400, AodOrientationStep.LANDSCAPE, 1.7f)!!

        assertEquals(1200f, transform.scalePivotX, 0.001f) // oh/2 = 906/2
        assertEquals(453f, transform.scalePivotY, 0.001f)  // ow/2 = 2400/2
        assertEquals(-747f, transform.translateX, 0.001f)
        assertEquals(747f, transform.translateY, 0.001f)
    }

    @Test
    fun rotationTransformIsNullForPortraitOrDegenerateSize() {
        assertNull(landscapeRotationTransform(906, 2400, AodOrientationStep.PORTRAIT, 1.7f))
        assertNull(landscapeRotationTransform(0, 2400, AodOrientationStep.LANDSCAPE, 1.7f))
        assertNull(landscapeRotationTransform(906, 0, AodOrientationStep.LANDSCAPE, 1.7f))
        // 非有限 scale 回退为 1,不产生 NaN 坐标。
        val fallback =
            landscapeRotationTransform(906, 2400, AodOrientationStep.LANDSCAPE, Float.NaN)!!
        assertEquals(1f, fallback.scale, 0.001f)
    }

    @Test
    fun cappedLineEndsStayInsideViewport() {
        // 与 #51 长轴上限配合:最长行(≤ 可用宽)放大后,行两端仍必须落在视口内。
        val transform =
            landscapeRotationTransform(906, 2400, AodOrientationStep.LANDSCAPE, 1.7f)!!
        // 居中 1400px 行 → 逻辑 x∈[500,1900];映射到视口 y 应落在 [0,2400]。
        val start = mapLandscapeLogicalPoint(transform, 500f, 453f)
        val end = mapLandscapeLogicalPoint(transform, 1900f, 453f)

        assertTrue(start.second >= 0f)
        assertTrue(end.second <= 2400f)
    }

    // ---- issue #63 横屏内容块锚定 [landscapeBlockAnchorOffset] ----

    @Test
    fun anchorHalfMatchesLegacyFullscreenCentering() {
        // anchor=0.5 必须与旧「全屏强制居中」逐点等价,保证既有全屏行为不回归。
        listOf(
            floatArrayOf(100f, 300f, 906f, 22f),
            floatArrayOf(22f, 1200f, 1036f, 22f),
            floatArrayOf(500f, 100f, 906f, 30f)
        ).forEach { (blockTop, blockHeight, available, padTop) ->
            val legacy = fullscreenBlockCenterOffset(blockTop, blockHeight, available, padTop)
            val anchored = landscapeBlockAnchorOffset(blockTop, blockHeight, available, padTop, 0.5f)
            assertEquals("top=$blockTop h=$blockHeight", legacy, anchored, 0.001f)
        }
    }

    @Test
    fun anchorEndpointsPinBlockToEdges() {
        // 可用 906、块高 306、块原顶部 100、padTop 22:
        // anchor=0 → 块顶贴 padTop(offset=-78);anchor=1 → 块底贴 padTop+906(offset=522)。
        val top = landscapeBlockAnchorOffset(100f, 306f, 906f, 22f, 0f)
        val bottom = landscapeBlockAnchorOffset(100f, 306f, 906f, 22f, 1f)
        assertEquals(-78f, top, 0.001f)
        assertEquals(522f, bottom, 0.001f)
    }

    @Test
    fun anchorNeverLiftsBlockWhenContentTallerThanAvailable() {
        // 内容高于可用区间:不缩小、不上移(保持原顶部,避免裁切),三个锚点结果一致。
        val expected = -(100f - 22f)
        listOf(0f, 0.5f, 1f).forEach { anchor ->
            assertEquals(
                "anchor=$anchor",
                expected,
                landscapeBlockAnchorOffset(100f, 1200f, 906f, 22f, anchor),
                0.001f
            )
        }
    }

    @Test
    fun anchorOutOfRangeIsCoerced() {
        val atZero = landscapeBlockAnchorOffset(100f, 306f, 906f, 22f, 0f)
        val atOne = landscapeBlockAnchorOffset(100f, 306f, 906f, 22f, 1f)
        assertEquals(atZero, landscapeBlockAnchorOffset(100f, 306f, 906f, 22f, -0.5f), 0.001f)
        assertEquals(atOne, landscapeBlockAnchorOffset(100f, 306f, 906f, 22f, 1.8f), 0.001f)
    }

    // ---- 横屏画布 rect 宽高交换 [swapAodSurfaceRectForLandscape] ----

    @Test
    fun landscapeRectSwapExchangesDimsAroundCenter() {
        // issue #63 现场形态:竖屏放置给出 900x600(宽>高)的歌词 rect,中心 (550,500)。
        // 交换后 600x900,中心不变 —— 横持视角下画布由「高>宽」变为「宽>高」。
        val swapped = swapAodSurfaceRectForLandscape(
            AodSurfaceRect(100, 200, 1000, 800),
            rootWidth = 1080,
            rootHeight = 2400
        )
        assertEquals(AodSurfaceRect(250, 50, 850, 950), swapped)
    }

    @Test
    fun landscapeRectSwapClampsIntoRoot() {
        // rect 靠左边缘:交换后左界会被推到 0 并保持完整宽度(不裁剪、不越界)。
        val swapped = swapAodSurfaceRectForLandscape(
            AodSurfaceRect(0, 0, 500, 900),
            rootWidth = 1080,
            rootHeight = 2400
        )
        assertEquals(AodSurfaceRect(0, 200, 900, 700), swapped)
    }

    @Test
    fun landscapeRectSwapShrinksOversizedWidthToRoot() {
        // 极端:交换后的宽超过 root 宽 → 钳到 root 宽;高仍完整交换,整体在 root 内。
        val swapped = swapAodSurfaceRectForLandscape(
            AodSurfaceRect(0, 0, 900, 1500),
            rootWidth = 1080,
            rootHeight = 2400
        )
        assertEquals(AodSurfaceRect(0, 300, 1080, 1200), swapped)
    }

    @Test
    fun landscapeRectSwapKeepsSquareRectUnchanged() {
        // 已是正方形的 rect 交换后不变(幂等,避免无谓的重新布局)。
        val rect = AodSurfaceRect(100, 200, 400, 500)
        assertEquals(rect, swapAodSurfaceRectForLandscape(rect, 1080, 2400))
    }

    // ---- issue #61 非全屏横屏缩放上限 [landscapeFrameFitScale] ----

    @Test
    fun landscapeFrameFitNeverMagnifiesBeyondFrame() {
        // issue #61 现场:u=1.5 fit=1.44 —— 逻辑帧 == 旋转后的画布,scale>1 必溢出被裁。
        // 上限恒为 1.0:scale=1 已精确铺满,再放大必然把帧内内容推出画布框。
        assertEquals(1.0f, landscapeFrameFitScale(userScale = 1.5f, fitScale = 1.44f), 0.001f)
        assertEquals(1.0f, landscapeFrameFitScale(userScale = 1.7f, fitScale = 3.01f), 0.001f)
        assertEquals(1.0f, landscapeFrameFitScale(userScale = 1.2f, fitScale = 1.0f), 0.001f)
    }

    @Test
    fun landscapeFrameFitDoesNotShrinkBelowOne() {
        // 下限沿用 FULLSCREEN_MIN_SCALE:内容超帧(fit<1)或用户倍数<1 时也不缩小,
        // 与修复前行为一致(缩小语义不属于本条 issue)。
        assertEquals(1.0f, landscapeFrameFitScale(userScale = 1.5f, fitScale = 0.7f), 0.001f)
        assertEquals(1.0f, landscapeFrameFitScale(userScale = 0.8f, fitScale = 1.44f), 0.001f)
    }

    @Test
    fun landscapeFrameFitCapIsExactlyOne() {
        // 回归锚点:非全屏横屏的放大上限必须恒为 1.0(逻辑帧 == 画布)。
        assertEquals(1.0f, LANDSCAPE_FRAME_MAX_SCALE, 0.001f)
    }

    // ---- issue #61 内容包围盒完整可见判定 [LandscapeMappedBounds.fitsWithin] ----

    @Test
    fun fitsWithinRejectsClippedContentThatCoversCanvas() {
        // issue #61 现场:bounds=(-199,-158)-(1105,878) vs view=906x720 ——
        // cover=true、hit=true,但内容四向被裁,fits 必须为 false。
        val clipped = LandscapeMappedBounds(-199f, -158f, 1105f, 878f)
        assertTrue(clipped.covers(906, 720))
        assertTrue(clipped.hits(906, 720))
        assertFalse(clipped.fitsWithin(906, 720))
    }

    @Test
    fun fitsWithinAcceptsContentInsideCanvas() {
        val inside = LandscapeMappedBounds(10f, 20f, 700f, 700f)
        assertTrue(inside.fitsWithin(906, 720))
        // 贴边(恰好等于画布)也算完整可见。
        assertTrue(LandscapeMappedBounds(0f, 0f, 906f, 720f).fitsWithin(906, 720))
    }
}