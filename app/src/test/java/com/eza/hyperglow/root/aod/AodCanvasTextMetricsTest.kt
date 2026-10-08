package com.eza.hyperglow.root.aod

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
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
    /**
     * 旧字号公式(2026-10-01 修复版)原样内联,供 m=100 逐值对拍:新公式只是把 0.48 项
     * 乘上倍率、再硬顶 0.62×主行,默认档必须与旧值逐点相等(既有修复不回退)。
     */
    private fun legacyReadingTextSizeSp(baseSp: Float): Float =
        max(min(14f, baseSp * 0.62f), round(baseSp * 0.48f))

    private fun legacyTranslationTextSizeSp(baseSp: Float): Float =
        max(min(13f, baseSp * 0.62f - 1f), round(baseSp * 0.48f) - 1f)

    @Test
    fun secondarySizePercentHundredMatchesLegacyFormulaPointwise() {
        // m=100(默认)与旧公式逐点相等:19.04/38/15.64 等现实 baseSp 全覆盖,
        // 另钉 CustomizationPreviewTest 的既有三组真机值(默认参数路径)。
        val bases = listOf(14.076f, 15.64f, 19.04f, 23f, 26f, 38f, 57f, 100f)
        for (base in bases) {
            assertEquals(
                "reading m=100 must equal legacy at base=$base",
                legacyReadingTextSizeSp(base),
                secondaryReadingTextSizeSp(base),
                0.0001f
            )
            assertEquals(
                "reading m=100 explicit must equal legacy at base=$base",
                legacyReadingTextSizeSp(base),
                secondaryReadingTextSizeSp(base, 100),
                0.0001f
            )
            assertEquals(
                "translation m=100 must equal legacy at base=$base",
                legacyTranslationTextSizeSp(base),
                secondaryTranslationTextSizeSp(base),
                0.0001f
            )
            assertEquals(
                "translation m=100 explicit must equal legacy at base=$base",
                legacyTranslationTextSizeSp(base),
                secondaryTranslationTextSizeSp(base, 100),
                0.0001f
            )
        }
        assertEquals(11.8f, secondaryReadingTextSizeSp(19.04f), 0.01f)
        assertEquals(18f, secondaryReadingTextSizeSp(38f), 0.001f)
        assertEquals(10.8f, secondaryTranslationTextSizeSp(19.04f), 0.01f)
        assertEquals(17f, secondaryTranslationTextSizeSp(38f), 0.001f)
        assertEquals(9.7f, secondaryReadingTextSizeSp(15.64f), 0.01f)
        assertEquals(8.7f, secondaryTranslationTextSizeSp(15.64f), 0.01f)
    }

    @Test
    fun secondarySizePercentIsMonotonicAndClampsOutOfRange() {
        // 50→150 单调不减;越界 percent(0/999/负值)钳到边界且与边界档逐值相等。
        val bases = listOf(15.64f, 19.04f, 38f, 57f)
        for (base in bases) {
            var previousReading = 0f
            var previousTranslation = 0f
            var percent = 50
            while (percent <= 150) {
                val reading = secondaryReadingTextSizeSp(base, percent)
                val translation = secondaryTranslationTextSizeSp(base, percent)
                assertTrue(
                    "reading must not decrease (base=$base percent=$percent)",
                    reading >= previousReading - 0.0001f
                )
                assertTrue(
                    "translation must not decrease (base=$base percent=$percent)",
                    translation >= previousTranslation - 0.0001f
                )
                previousReading = reading
                previousTranslation = translation
                percent += 5
            }
            assertEquals(secondaryReadingTextSizeSp(base, 50), secondaryReadingTextSizeSp(base, 0), 0f)
            assertEquals(secondaryReadingTextSizeSp(base, 50), secondaryReadingTextSizeSp(base, -100), 0f)
            assertEquals(secondaryReadingTextSizeSp(base, 150), secondaryReadingTextSizeSp(base, 999), 0f)
            assertEquals(
                secondaryTranslationTextSizeSp(base, 150),
                secondaryTranslationTextSizeSp(base, 999),
                0f
            )
        }
        assertEquals(0.5f, secondarySizeMultiplier(0), 0f)
        assertEquals(0.5f, secondarySizeMultiplier(-100), 0f)
        assertEquals(1f, secondarySizeMultiplier(100), 0f)
        assertEquals(1.5f, secondarySizeMultiplier(999), 0f)
    }

    @Test
    fun secondarySizeNeverApproachesMainLineAndSaturatesAtCap() {
        // 硬顶:任意 percent 下 reading ≤ 0.62×base、translation ≤ 0.62×base−1
        // (「辅助行不得逼近主行」的 2026-10-01 性格线不回退)。
        val bases = listOf(15.64f, 19.04f, 38f, 57f, 100f)
        for (base in bases) {
            for (percent in intArrayOf(50, 75, 100, 125, 150)) {
                assertTrue(
                    secondaryReadingTextSizeSp(base, percent) <= base * 0.62f + 0.0001f
                )
                assertTrue(
                    secondaryTranslationTextSizeSp(base, percent) <= base * 0.62f - 1f + 0.0001f
                )
            }
        }
        // percent=150 因 0.48×1.5 = 0.72 > 0.62 在常规 baseSp 下饱和到硬顶。
        assertEquals(0.62f * 38f, secondaryReadingTextSizeSp(38f, 150), 0.0001f)
        assertEquals(0.62f * 38f - 1f, secondaryTranslationTextSizeSp(38f, 150), 0.0001f)
    }

    @Test
    fun secondarySizeFloorDoesNotScaleWithPercent() {
        // 下限不随倍率缩放:50% 档在最小现实 baseSp 下由下限接管,不会缩到不可读。
        val base = 15.64f
        assertEquals(min(14f, base * 0.62f), secondaryReadingTextSizeSp(base, 50), 0.0001f)
        assertEquals(min(13f, base * 0.62f - 1f), secondaryTranslationTextSizeSp(base, 50), 0.0001f)
        // secondarySizeFloorSp 与字号公式的下限项同源;configured ≥ floor(minScale ≤ 1 的前提)。
        for (b in listOf(15.64f, 19.04f, 38f, 100f)) {
            assertEquals(min(14f, b * 0.62f), secondarySizeFloorSp(b, translation = false), 0.0001f)
            assertEquals(min(13f, b * 0.62f - 1f), secondarySizeFloorSp(b, translation = true), 0.0001f)
            for (percent in intArrayOf(50, 100, 150)) {
                assertTrue(
                    secondaryReadingTextSizeSp(b, percent) >=
                        secondarySizeFloorSp(b, false) - 0.0001f
                )
                assertTrue(
                    secondaryTranslationTextSizeSp(b, percent) >=
                        secondarySizeFloorSp(b, true) - 0.0001f
                )
            }
        }
    }

    @Test
    fun adaptiveFitKeepsExistingPresentationWhenItFits() {
        // 装得下 → 恒 1(既有呈现逐像素不变),且只探一次(早返回,长文不付二分成本)。
        var calls = 0
        val scale = adaptiveSecondaryFitScale(minScale = 0.5f, allowedLines = 2) { calls++; 1 }
        assertEquals(1f, scale, 0f)
        assertEquals(1, calls)
    }

    @Test
    fun adaptiveFitBisectsToTheLargestFittingScale() {
        // 单调阶跃假测量:scale ≥ 0.8 折 3 行,否则 1 行。结果必须装得下,
        // 且再大一点点就装不下(收敛到阈值下侧);并落在 [minScale, 1] 内。
        val linesAt = { scale: Float -> if (scale >= 0.8f) 3 else 1 }
        val scale = adaptiveSecondaryFitScale(minScale = 0.2f, allowedLines = 1, linesAt = linesAt)
        assertTrue("result must fit: $scale", linesAt(scale) <= 1)
        assertTrue("result+epsilon must not fit", linesAt(scale + 0.001f) > 1)
        assertTrue("result must stay within [minScale, 1]", scale >= 0.2f && scale <= 1f)
    }

    @Test
    fun adaptiveFitFallsToFloorWhenEvenTheFloorOverflows() {
        // 到下限仍装不下 → 取下限(不返回 1、不返回 0;接受溢出/裁切,与 Clip 口径一致)。
        assertEquals(
            0.3f,
            adaptiveSecondaryFitScale(minScale = 0.3f, allowedLines = 1) { 5 },
            0f
        )
    }

    @Test
    fun adaptiveFitIgnoresInvalidInputs() {
        // 非法下限(0/负/NaN/>1)不介入:返回 1 且不测量(防「自适应把字号放大」)。
        for (invalid in listOf(0f, -1f, Float.NaN, 1.5f)) {
            var calls = 0
            val scale = adaptiveSecondaryFitScale(invalid, allowedLines = 1) { calls++; 9 }
            assertEquals("minScale=$invalid", 1f, scale, 0f)
            assertEquals("minScale=$invalid must not probe", 0, calls)
        }
        // allowedLines < 1 同样不介入。
        assertEquals(1f, adaptiveSecondaryFitScale(0.5f, allowedLines = 0) { 9 }, 0f)
    }

    @Test
    fun adaptiveFitHonorsAllowedLinesOneAndTwo() {
        // allowedLines=1/2 两档边界:同一阶跃在 2 行档本来就装得下(恒 1);
        // 1 行档必须缩到阈值以下、且不低于下限。
        val linesAt = { scale: Float -> if (scale >= 0.6f) 2 else 1 }
        assertEquals(1f, adaptiveSecondaryFitScale(0.4f, allowedLines = 2, linesAt = linesAt), 0f)
        val oneLine = adaptiveSecondaryFitScale(0.4f, allowedLines = 1, linesAt = linesAt)
        assertTrue(oneLine < 0.6f)
        assertTrue(oneLine >= 0.4f)
    }

    @Test
    fun adaptiveFitResultStaysWithinMinScaleAndOne() {
        // 不变量(规格 §7.6):结果恒 ∈ [minScale, 1],绝不放大、绝不越下限;
        // 除「到下限仍放不下」外必须装得下。
        for (threshold in listOf(0.95f, 0.8f, 0.5f, 0.31f)) {
            for (allowed in 1..2) {
                for (minScale in listOf(0.1f, 0.2f, 0.3f, 0.5f)) {
                    val linesAt = { scale: Float ->
                        if (scale >= threshold) allowed + 1 else allowed
                    }
                    val scale = adaptiveSecondaryFitScale(minScale, allowed, linesAt)
                    assertTrue("scale=$scale minScale=$minScale", scale >= minScale - 0.0001f)
                    assertTrue("scale=$scale", scale <= 1f)
                    assertTrue(
                        "must fit or reach the floor (scale=$scale)",
                        linesAt(scale) <= allowed || scale == minScale
                    )
                }
            }
        }
    }

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
