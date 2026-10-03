package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 画布内部 normalizeAodAnimation 与 aod/AodRenderPreferences 同名函数的漂移守卫。
 *
 * 画布(root.aod 包)按同包解析调用本包副本,不会走 aod 包版本;两处曾因不同步
 * (副本只认 Minimal)导致 BetterLyrics 档在画布被静默压回 Gradient——表现即
 * 「设置只在预览生效、实机不变」。新增动画档时本测试会先红。
 */
class AodCanvasTextMetricsTest {
    @Test
    fun canvasNormalizeAnimationMatchesAodPreferences() {
        val values = listOf("Minimal", "Gradient", "BetterLyrics", "Karaoke fill", "bogus", "")
        for (value in values) {
            assertEquals(
                "root.aod copy must match aod/AodRenderPreferences for '$value'",
                com.eza.hyperglow.aod.normalizeAodAnimation(value),
                normalizeAodAnimation(value)
            )
        }
    }

    @Test
    fun effectAllowanceCoversGlowRadiusAndBetterLyricsSink() {
        // 发光开启:余量 = 光晕半径(与 haloClipRect 同一取值 textSize × 0.36)。
        assertEquals(
            36f,
            canvasEffectAllowancePx(
                textSizePx = 100f,
                lineHeightPx = 140f,
                glowEnabled = true,
                floatSinkActive = false
            ),
            0.0001f
        )
        // 发光关闭但 BetterLyrics 档:未唱字下沉量(行高 × 0.10)仍需余量。
        assertEquals(
            14f,
            canvasEffectAllowancePx(
                textSizePx = 100f,
                lineHeightPx = 140f,
                glowEnabled = false,
                floatSinkActive = true
            ),
            0.0001f
        )
        // 两项同时开启取较大者(大字号 + 扁行高时辉光主导,小字号 + 高行高时下沉主导)。
        assertEquals(
            36f,
            canvasEffectAllowancePx(100f, 140f, glowEnabled = true, floatSinkActive = true),
            0.0001f
        )
        assertEquals(
            30f,
            canvasEffectAllowancePx(10f, 300f, glowEnabled = true, floatSinkActive = true),
            0.0001f
        )
        // 两项都关闭:无外扩,既有布局逐像素不变。
        assertEquals(
            0f,
            canvasEffectAllowancePx(100f, 140f, glowEnabled = false, floatSinkActive = false),
            0.0001f
        )
        // 非法输入(未测量/零字号)不产生余量。
        assertEquals(
            0f,
            canvasEffectAllowancePx(Float.NaN, Float.NaN, glowEnabled = true, floatSinkActive = true),
            0.0001f
        )
        assertEquals(
            0f,
            canvasEffectAllowancePx(0f, 0f, glowEnabled = true, floatSinkActive = true),
            0.0001f
        )
    }

    @Test
    fun effectEdgeNeedsCreditTheLeadingGapAndChargeTheTrailingEdgeInFull() {
        // 顶部:首行行前距(24px)已覆盖 20px 外扩 → 零余量,内容位置不变。
        val covered = canvasEffectEdgeNeeds(
            topRowOverdrawPx = 20f,
            topRowGapBeforePx = 24f,
            bottomRowOverdrawPx = 20f
        )
        assertEquals(0f, covered.topPx, 0.0001f)
        // 底部块尾与裁剪沿之间没有留白:外扩量全额计入。
        assertEquals(20f, covered.bottomPx, 0.0001f)
        // 大字号下外扩超过行前距:顶部只补差额(不重复计入行前距)。
        val deficit = canvasEffectEdgeNeeds(
            topRowOverdrawPx = 30.6f,
            topRowGapBeforePx = 24f,
            bottomRowOverdrawPx = 30.6f
        )
        assertEquals(6.6f, deficit.topPx, 0.0001f)
        assertEquals(30.6f, deficit.bottomPx, 0.0001f)
        // 反向堆叠分支(歌曲信息贴底)首行行前距不落位:按 0 抵扣,顶部全额计入。
        val reverse = canvasEffectEdgeNeeds(
            topRowOverdrawPx = 30.6f,
            topRowGapBeforePx = 0f,
            bottomRowOverdrawPx = 0f
        )
        assertEquals(30.6f, reverse.topPx, 0.0001f)
        assertEquals(0f, reverse.bottomPx, 0.0001f)
        // 无外扩(发光关闭且非 BetterLyrics 档):两端都为 0,既有布局逐像素不变。
        val none = canvasEffectEdgeNeeds(0f, 24f, 0f)
        assertEquals(0f, none.topPx, 0.0001f)
        assertEquals(0f, none.bottomPx, 0.0001f)
        // 非法输入不产生余量。
        val invalid = canvasEffectEdgeNeeds(Float.NaN, Float.NaN, Float.NaN)
        assertEquals(0f, invalid.topPx, 0.0001f)
        assertEquals(0f, invalid.bottomPx, 0.0001f)
    }

    @Test
    fun effectAllowanceKeepsBlockExtentsInsideTheClipBox() {
        // 「安全栅栏」的算术门:复刻 positionRows(顶锚)+ measureContentStack 的记账,
        // 断言效果外扩恒落在内容裁剪框内(相切或有余量,绝不越界)。三组覆盖:
        // 余量被行前距完全覆盖(默认字号)、顶部补差额 + 底部全额(xlarge)、超大字号。
        val cases = listOf(
            Triple(55f, 24f, 90f),
            Triple(85f, 24f, 131.8f),
            Triple(130f, 24f, 200f)
        )
        for ((textSizePx, gapBeforePx, rowHeightPx) in cases) {
            val overdraw = canvasEffectAllowancePx(
                textSizePx = textSizePx,
                lineHeightPx = rowHeightPx,
                glowEnabled = true,
                floatSinkActive = true
            )
            val needs = canvasEffectEdgeNeeds(
                topRowOverdrawPx = overdraw,
                topRowGapBeforePx = gapBeforePx,
                bottomRowOverdrawPx = overdraw
            )
            // 锁屏:画布高 = 实测堆叠高(含两端余量),padTop/padBottom 均为 0。
            val canvasHeight = contentStackHeightPx(
                rowHeightsPx = listOf(rowHeightPx),
                rowGapsBeforePx = listOf(gapBeforePx),
                metadataGapPx = 0f,
                padTopPx = needs.topPx,
                padBottomPx = needs.bottomPx
            )
            // 放置(顶锚):块起点 = padTop + 顶部余量,首行盒顶 = 起点 + 行前距,末行盒底 = 起点 + 行高 + 行前距。
            val blockTop = needs.topPx + gapBeforePx
            val blockBottom = needs.topPx + gapBeforePx + rowHeightPx
            assertTrue(
                "top overdraw must stay inside the clip box (text=$textSizePx): $blockTop - $overdraw",
                blockTop - overdraw >= -0.001f
            )
            assertTrue(
                "bottom overdraw must stay inside the clip box (text=$textSizePx): $blockBottom + $overdraw vs $canvasHeight",
                blockBottom + overdraw <= canvasHeight + 0.001f
            )
            // 同一次摆放喂给「安全栅栏」自检:已知形状必须恒为 clean。
            assertTrue(
                "self-check must be clean for known shapes (text=$textSizePx)",
                effectClipCheckPx(
                    blockTopPx = blockTop,
                    blockBottomPx = blockBottom,
                    topOverdrawPx = overdraw,
                    bottomOverdrawPx = overdraw,
                    clipTopPx = 0f,
                    clipBottomPx = canvasHeight
                ).clean
            )
        }
    }

    @Test
    fun effectClipCheckSeparatesRowOverflowFromMissingAllowance() {
        // 行盒在框内、只有外扩越界 = 余量算漏(新效果/新行种类),单独报出来;
        // 行盒自己越界是「内容放不下」,不再重复计效果越界;非有限输入视为 clean。
        val clean = effectClipCheckPx(100f, 200f, 30f, 30f, 0f, 260f)
        assertTrue("extents inside the clip box are clean", clean.clean)

        val effectOnly = effectClipCheckPx(100f, 200f, 30f, 30f, 0f, 220f)
        assertFalse("row boxes fit but the overdraw is cut", effectOnly.clean)
        assertEquals(0f, effectOnly.rowOverflowPx, 0.0001f)
        assertEquals(10f, effectOnly.effectOverflowPx, 0.0001f)

        val rowOverflow = effectClipCheckPx(100f, 300f, 30f, 30f, 0f, 260f)
        assertEquals(40f, rowOverflow.rowOverflowPx, 0.0001f)
        assertEquals(0f, rowOverflow.effectOverflowPx, 0.0001f)

        // 容差:恰好相切的浮点残差(亚像素)不算越界,否则运行期自检会被噪声刷屏。
        val subPixel = effectClipCheckPx(100f, 200f, 30f, 30f, 0f, 229.99f)
        assertTrue("sub-pixel residue must not count as a violation", subPixel.clean)

        val invalid = effectClipCheckPx(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
        assertTrue("non-finite inputs must not produce noise", invalid.clean)
    }
}
