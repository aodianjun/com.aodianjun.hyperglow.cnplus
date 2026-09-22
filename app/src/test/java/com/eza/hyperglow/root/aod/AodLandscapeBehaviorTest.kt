package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 横屏相关行为的纯函数测试:
 *  - 「横屏自动隐藏系统息屏内容、回落竖屏恢复」的判定 [shouldHideStockAodContent];
 *  - 横屏全屏化「自动铺满不越界」的放缩比 [fullscreenAutoScale]。
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

    // ---- issue #55 横向平移量缩放补偿 ----

    @Test
    fun compensateRotationTranslateKeepsDeviceShiftEqualToD() {
        // issue #55:先 translate 再 scale 时平移量 d 会被放大成 d×scale,
        // 补偿后视觉平移 = scale * (d/scale) = d(恒等于原始平移量),内容不再被推出画布。
        val d = -747f // 横屏全屏:视口 906x2400,d=(906-2400)/2
        val scale = 1.7f
        val compensated = compensateRotationTranslate(d, scale)
        assertEquals(-747f / scale, compensated, 0.0001f)
        assertEquals(d, scale * compensated, 0.001f)
    }

    @Test
    fun compensateRotationTranslateScaleOneIsIdentity() {
        // scale≈1 时不补偿,行为与旧版完全一致(无回归)。
        assertEquals(-747f, compensateRotationTranslate(-747f, 1f), 0.001f)
        assertEquals(93f, compensateRotationTranslate(93f, 1f), 0.001f)
    }

    @Test
    fun compensateRotationTranslateNonFiniteScaleFallsBackToD() {
        // 非有限 scale(NaN/Inf)不得除出 NaN,回退为原始平移量。
        assertEquals(-747f, compensateRotationTranslate(-747f, Float.NaN), 0.001f)
        assertEquals(-747f, compensateRotationTranslate(-747f, Float.POSITIVE_INFINITY), 0.001f)
    }

    @Test
    fun landscapeLineStaysOnScreenAfterRotationTransform() {
        // 建模 beginRotationTransform(rotate 90° + translate(d,d) + scale(s, 视口中心)):
        // 逻辑点 (x,y) → (w - y, x) → 缩放后视口 y = cy + (x - cy) * s。
        // 取 issue #51 现场参数:视口 906x2400、s=1.7,验证逻辑 x(对齐结果)映射到视口 y。
        val cy = 2400f / 2f
        val s = 1.7f
        fun mapLogicalXToViewY(x: Float) = cy + (x - cy) * s

        // 正确基准(逻辑帧宽 2400)居中 1400px 行:x∈[500,1900] → 视口 y∈[10,2390] 全程可见。
        val startX = edgeSafeAlignedStart(2400f, 0f, 0f, 0f, 1400f, "center")
        assertEquals(500f, startX, 0.001f)
        assertTrue(mapLogicalXToViewY(startX) >= 0f)
        assertTrue(mapLogicalXToViewY(startX + 1400f) <= 2400f)

        // 回归对照(误用视口宽 906):startX=-247 → 行起点落在视口 y=-1260,
        // 行首约 1.2k px 被裁出屏幕(issue #51 现场:横屏歌词被推出可视区)。
        val brokenX = edgeSafeAlignedStart(906f, 0f, 0f, 0f, 1400f, "center")
        assertEquals(-247f, brokenX, 0.001f)
        assertTrue(mapLogicalXToViewY(brokenX) < 0f)
    }
}