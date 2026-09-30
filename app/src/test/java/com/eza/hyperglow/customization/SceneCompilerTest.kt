package com.eza.hyperglow.customization

import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.root.customization.SystemUiCustomizationValidator
import com.eza.hyperglow.root.customization.WidgetRendererRegistry
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.PlacementRect
import com.eza.hyperglow.root.surface.WidgetMeasurement
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SceneCompilerTest {
    @Test
    fun safeDefaultsUseLyricsOnlySpotifyMainLineSweep() {
        val compiled = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val lockscreen = compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val aod = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertFalse(lockscreen.enabled)
        assertTrue(aod.enabled)
        listOf(lockscreen, aod).forEach { profile ->
            assertEquals(listOf("lyrics"), profile.widgets.map { it.type })
            assertFalse(profile.metadataVisible)
            assertEquals("spotify", profile.fontFamily)
            assertEquals("Left to right (main only)", profile.lineSyncFillMode)
        }
    }

    @Test
    fun legacyPreferencesMigrateWithoutEnablingLockscreen() {
        val document = CustomizationRepository.documentFromLegacy(
            AodRenderConfig(
                lockscreenEnabled = false,
                alignment = "end",
                secondaryMode = "Both",
                metadataVisible = "hide",
                metadataSizePercent = 135,
                weight = "Bold",
                fontFamily = "spotify"
            )
        )
        val lockscreen = document.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val aod = document.profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertFalse(lockscreen.enabled)
        assertTrue(aod.enabled)
        assertEquals("end", aod.alignment)
        assertEquals("Both", aod.secondaryMode)
        assertFalse(aod.metadataVisible)
        assertEquals(135, aod.metadataSizePercent)
        assertEquals("Bold", aod.weight)
        assertEquals("spotify", aod.fontFamily)
    }

    @Test
    fun unknownWidgetsDropAndMissingLyricsFallsBackSafely() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        widgets = listOf(WidgetSpec("unknown"), WidgetSpec("artwork_accent"))
                    )
                )
            )
        )

        assertEquals(
            listOf("lyrics"),
            compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).widgets.map { it.type }
        )
    }

    @Test
    fun aodPolicyClampsComponentsHeightAndTransition() {
        val widgets = listOf(
            WidgetSpec("metadata"),
            WidgetSpec("status_text"),
            WidgetSpec("spacer"),
            WidgetSpec("divider"),
            WidgetSpec("lyrics"),
            WidgetSpec("media_progress")
        )
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        maxHeightFraction = 0.9f,
                        widgets = widgets,
                        transition = TransitionPreset(durationMs = 5_000)
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(0.3f, compiled.maxHeightFraction)
        assertTrue(compiled.widgets.size <= SceneCompiler.MAX_AOD_WIDGETS)
        assertFalse(compiled.widgets.any { it.type == "media_progress" })
        assertEquals(600, compiled.transition.durationMs)
    }

    @Test
    fun aodHeightFixedToMinimumRegardlessOfStoredValue() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(maxHeightFraction = 0.5f),
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        enabled = true,
                        maxHeightFraction = 0.9f
                    )
                )
            )
        )

        assertEquals(
            SceneCompiler.AOD_FIXED_MAX_HEIGHT_FRACTION,
            compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).maxHeightFraction
        )
        // 锁屏卡片保留高度档位(仅收敛到上限)。
        assertEquals(
            0.8f,
            compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).maxHeightFraction
        )
    }

    @Test
    fun metadataSizeClampsAndFuriganaPreferenceSurvivesValidation() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        metadataSizePercent = 900,
                        rubyVisible = false
                    )
                )
            )
        )
        val validated = SystemUiCustomizationValidator.validate(compiled)!!
            .profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(200, validated.metadataSizePercent)
        assertFalse(validated.rubyVisible)
    }

    @Test
    fun lyricLineLimitAndSecondaryBrightnessCompileWithSafeFallback() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        lyricLineLimit = 0,
                        secondaryTextBright = false
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(0, compiled.lyricLineLimit)
        assertFalse(compiled.secondaryTextBright)
        assertEquals(
            DEFAULT_LYRIC_LINE_LIMIT,
            SceneCompiler.compile(
                CustomizationDocument(
                    profiles = mapOf(
                        SceneCompiler.SURFACE_AOD to SurfaceProfile(lyricLineLimit = 99)
                    )
                )
            ).profiles.getValue(SceneCompiler.SURFACE_AOD).lyricLineLimit
        )
    }

    @Test
    fun lineLevelSweepDirectionIsCompiledAndValidated() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        lineSyncFillMode = "Left to right (main only)"
                    )
                )
            )
        )
        val validated = SystemUiCustomizationValidator.validate(compiled)!!

        assertEquals(
            "Left to right (main only)",
            validated.profiles.getValue(SceneCompiler.SURFACE_AOD).lineSyncFillMode
        )
        assertEquals(
            "Left to right (whole block)",
            SystemUiCustomizationValidator.validate(
                compiled.copy(
                    profiles = compiled.profiles + (
                        SceneCompiler.SURFACE_AOD to compiled.profiles
                            .getValue(SceneCompiler.SURFACE_AOD)
                            .copy(lineSyncFillMode = "Left to right (whole block)")
                        )
                )
            )!!.profiles.getValue(SceneCompiler.SURFACE_AOD).lineSyncFillMode
        )
        assertEquals(
            "Left to right (main only)",
            SystemUiCustomizationValidator.validate(
                compiled.copy(
                    profiles = compiled.profiles + (
                        SceneCompiler.SURFACE_AOD to compiled.profiles
                            .getValue(SceneCompiler.SURFACE_AOD)
                            .copy(lineSyncFillMode = "Left to right")
                        )
                )
            )!!.profiles.getValue(SceneCompiler.SURFACE_AOD).lineSyncFillMode
        )
        assertEquals(
            "None",
            SystemUiCustomizationValidator.validate(
                compiled.copy(
                    profiles = compiled.profiles + (
                        SceneCompiler.SURFACE_AOD to compiled.profiles
                            .getValue(SceneCompiler.SURFACE_AOD)
                            .copy(lineSyncFillMode = "None")
                        )
                )
            )!!.profiles.getValue(SceneCompiler.SURFACE_AOD).lineSyncFillMode
        )
        assertEquals(
            "Left to right (main only)",
            SystemUiCustomizationValidator.validate(
                compiled.copy(
                    profiles = compiled.profiles + (
                        SceneCompiler.SURFACE_AOD to compiled.profiles
                            .getValue(SceneCompiler.SURFACE_AOD)
                            .copy(lineSyncFillMode = "Diagonal")
                        )
                )
            )!!.profiles.getValue(SceneCompiler.SURFACE_AOD).lineSyncFillMode
        )
    }

    @Test
    fun lineTransitionCompilesAndValidatesWithAutoFallback() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(lineTransition = "Slide left")
                )
            )
        )
        val validated = SystemUiCustomizationValidator.validate(compiled)!!

        assertEquals(
            "Slide left",
            validated.profiles.getValue(SceneCompiler.SURFACE_AOD).lineTransition
        )
        // "Auto"(跟随源)自身保留
        assertEquals(
            LINE_TRANSITION_AUTO,
            SystemUiCustomizationValidator.validate(
                compiled.copy(
                    profiles = compiled.profiles + (
                        SceneCompiler.SURFACE_AOD to compiled.profiles
                            .getValue(SceneCompiler.SURFACE_AOD)
                            .copy(lineTransition = LINE_TRANSITION_AUTO)
                        )
                )
            )!!.profiles.getValue(SceneCompiler.SURFACE_AOD).lineTransition
        )
        // 未知值兜底 "Auto",与 normalizeLineTransition 一致(不引入非法词进 SystemUI)
        assertEquals(
            LINE_TRANSITION_AUTO,
            SystemUiCustomizationValidator.validate(
                compiled.copy(
                    profiles = compiled.profiles + (
                        SceneCompiler.SURFACE_AOD to compiled.profiles
                            .getValue(SceneCompiler.SURFACE_AOD)
                            .copy(lineTransition = "Diagonal")
                        )
                )
            )!!.profiles.getValue(SceneCompiler.SURFACE_AOD).lineTransition
        )
        // 词表内可选项逐一原样通过(历史档 + HyperLyric 预设 id;"Slide left" 上面已验)
        for (mode in LINE_TRANSITION_MODES.filter { it != LINE_TRANSITION_AUTO && it != "Slide left" }) {
            assertEquals(
                mode,
                SystemUiCustomizationValidator.validate(
                    compiled.copy(
                        profiles = compiled.profiles + (
                            SceneCompiler.SURFACE_AOD to compiled.profiles
                                .getValue(SceneCompiler.SURFACE_AOD)
                                .copy(lineTransition = mode)
                            )
                    )
                )!!.profiles.getValue(SceneCompiler.SURFACE_AOD).lineTransition
            )
        }
        // #95 短名档归一到同配方预设 id(compile 与 validate 同规则,wire 不触发重写拒收)
        for ((alias, canonicalId) in mapOf(
            "Fade left" to "fade_out_left_fade_in_right",
            "Landing" to "fade_out_left_landing",
            "Slide swap" to "slide_out_left_slide_in_right"
        )) {
            assertEquals(
                canonicalId,
                SystemUiCustomizationValidator.validate(
                    compiled.copy(
                        profiles = compiled.profiles + (
                            SceneCompiler.SURFACE_AOD to compiled.profiles
                                .getValue(SceneCompiler.SURFACE_AOD)
                                .copy(lineTransition = alias)
                            )
                    )
                )!!.profiles.getValue(SceneCompiler.SURFACE_AOD).lineTransition
            )
        }
        // canonicalize 往返同样必须保住字段(compile -> toSurfaceProfile 逐字段重建)。
        val canonical = CustomizationRepository.canonicalizeDocument(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(lineTransition = "Slide left")
                )
            )
        )!!
        assertEquals(
            "Slide left",
            canonical.profiles.getValue(SceneCompiler.SURFACE_AOD).lineTransition
        )
    }

    @Test
    fun lineTransitionSpeedCompilesValidatesAndSurvivesCanonicalizeRoundTrip() {
        // 换行动画速率必须穿过 compile、SystemUI 二次校验与仓库 canonicalize 往返
        // (compile -> toSurfaceProfile 逐字段重建):任一环节漏字段都会让设置保存后
        // 弹回 Normal(逐字段重建映射的经典回归面)。
        val document = CustomizationDocument(
            profiles = mapOf(
                SceneCompiler.SURFACE_AOD to SurfaceProfile(lineTransitionSpeed = "Fast")
            )
        )
        val compiled = SceneCompiler.compile(document).profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertEquals("Fast", compiled.lineTransitionSpeed)

        val validated = SystemUiCustomizationValidator.validate(SceneCompiler.compile(document))!!
            .profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertEquals("Fast", validated.lineTransitionSpeed)

        val canonical = CustomizationRepository.canonicalizeDocument(document)!!
        assertEquals("Fast", canonical.profiles.getValue(SceneCompiler.SURFACE_AOD).lineTransitionSpeed)
        // 默认文档保持 Normal:不改变既有用户的速率。
        assertEquals(
            LINE_TRANSITION_SPEED_NORMAL,
            SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
                .profiles.getValue(SceneCompiler.SURFACE_AOD).lineTransitionSpeed
        )
        // 非法值兜底 Normal,与 normalizeLineTransitionSpeed 一致(不引入非法词进 SystemUI)。
        val dirty = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(lineTransitionSpeed = "warp")
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertEquals(LINE_TRANSITION_SPEED_NORMAL, dirty.lineTransitionSpeed)
    }

    @Test
    fun schemaRejectsOversizeAndExecutableReferencesButIgnoresUnknownFields() {
        assertNull(SceneCompiler.decodeDocument("x".repeat(SceneCompiler.MAX_CONFIG_BYTES + 1)))
        assertNull(SceneCompiler.decodeDocument("""{"version":1,"name":"file:///tmp/x"}"""))
        assertNull(
            SceneCompiler.decodeDocument(
                """{"version":1,"name":"https\u003a//example.invalid/profile"}"""
            )
        )
        assertNull(SceneCompiler.decodeDocument("""{"version":1,"className":"Injected"}"""))
        assertNotNull(
            SceneCompiler.decodeDocument(
                """{"version":1,"id":"safe","unknown":{"nested":true}}"""
            )
        )
    }

    @Test
    fun revisionHashIsStableAndChangesWithProfile() {
        val first = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val same = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val changed = SceneCompiler.compile(
            SceneCompiler.safeDefaultDocument().copy(linkSurfaces = true)
        )

        assertEquals(first.hash, same.hash)
        assertEquals(first.revision, same.revision)
        assertNotEquals(first.hash, changed.hash)
    }

    @Test
    fun linkedCompilerDerivesOneStyleButPreservesEnableFlags() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                linkSurfaces = true,
                profiles = mapOf(
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        enabled = false,
                        alignment = "start"
                    ),
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        enabled = true,
                        alignment = "end"
                    )
                )
            )
        )

        assertEquals(
            "end",
            compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).alignment
        )
        assertFalse(compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).enabled)
        assertTrue(compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).enabled)
        assertEquals(
            "card",
            compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).backgroundStyle
        )
        assertEquals("none", compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).backgroundStyle)
    }

    @Test
    fun linkedStylingStillPreservesSurfaceSpecificMetadataVisibility() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                linkSurfaces = true,
                profiles = mapOf(
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        metadataVisible = false,
                        widgets = listOf(WidgetSpec("lyrics"))
                    ),
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        metadataVisible = true,
                        widgets = listOf(WidgetSpec("lyrics"), WidgetSpec("metadata", optional = true))
                    )
                )
            )
        )

        assertFalse(compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).metadataVisible)
        assertTrue(compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).metadataVisible)
    }

    @Test
    fun systemUiValidationPreservesLockscreenOnlyCardAndSafeCollisionPolicy() {
        val compiled = SceneCompiler.compile(
            SceneCompiler.safeDefaultDocument().copy(
                linkSurfaces = true,
                profiles = SceneCompiler.safeDefaultDocument().profiles +
                    (SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        collisionPolicy = "avoid",
                        backgroundStyle = "card"
                    ))
            )
        )
        val validated = SystemUiCustomizationValidator.validate(compiled)!!
        val lockscreen = validated.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val aod = validated.profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals("avoid", lockscreen.collisionPolicy)
        assertEquals("card", lockscreen.backgroundStyle)
        assertEquals("none", aod.backgroundStyle)
    }

    @Test
    fun systemUiValidatorReappliesRegistryAndAodLimits() {
        val compiled = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val aod = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).copy(
            widgets = listOf(WidgetSpec("unknown"), WidgetSpec("artwork_accent")),
            maxHeightFraction = 1f
        )
        val validated = SystemUiCustomizationValidator.validate(
            compiled.copy(profiles = compiled.profiles + (SceneCompiler.SURFACE_AOD to aod))
        )!!.profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(listOf("lyrics"), validated.widgets.map { it.type })
        assertEquals(0.9f, validated.maxHeightFraction)
        assertNotNull(WidgetRendererRegistry.renderer("lyrics"))
        assertNull(WidgetRendererRegistry.renderer("arbitrary_class"))
    }

    @Test
    fun linkedLockScreenCardAppearanceIsNotOverriddenByAod() {
        // linkSurfaces=true 时锁屏卡片背景应保留锁屏自己的颜色/透明度,
        // 而不是被 AOD 的 cardAlpha/cardColor 覆盖(回归:调整锁屏卡片无效)。
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                linkSurfaces = true,
                profiles = mapOf(
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        backgroundStyle = "card",
                        cardAlpha = 30,
                        cardColor = "accent"
                    ),
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        backgroundStyle = "none",
                        cardAlpha = 90,
                        cardColor = "black"
                    )
                )
            )
        )
        val lockscreen = compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        assertEquals("card", lockscreen.backgroundStyle)
        assertEquals(30, lockscreen.cardAlpha)
        assertEquals("accent", lockscreen.cardColor)

        // 运行时验证器在 linkSurfaces=true 时同样必须保留锁屏卡片的颜色/透明度,
        // 而不是回退到 AOD 取值(回归:锁屏卡片颜色/透明度调整无效)。
        val validated = SystemUiCustomizationValidator.validate(compiled)!!
            .profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        assertEquals("card", validated.backgroundStyle)
        assertEquals(30, validated.cardAlpha)
        assertEquals("accent", validated.cardColor)
    }

    @Test
    fun cardAlphaAndColorAreClampedAndFallenBackOnCompile() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        backgroundStyle = "card",
                        cardAlpha = 999,
                        cardColor = "neon_pink"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)

        assertEquals(100, compiled.cardAlpha)
        assertEquals(DEFAULT_CARD_COLOR, compiled.cardColor)
        assertEquals(
            0,
            SceneCompiler.compile(
                CustomizationDocument(
                    profiles = mapOf(
                        SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                            backgroundStyle = "card",
                            cardAlpha = -20
                        )
                    )
                )
            ).profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).cardAlpha
        )
    }

    @Test
    fun cardAlphaAndColorSurviveSystemUiValidationForEachPresetToken() {
        CARD_COLOR_VALUES.forEach { token ->
            val compiled = SceneCompiler.compile(
                CustomizationDocument(
                    profiles = mapOf(
                        SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                            backgroundStyle = "card",
                            cardAlpha = 42,
                            cardColor = token
                        )
                    )
                )
            )
            val validated = SystemUiCustomizationValidator.validate(compiled)!!
                .profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)

            assertEquals(token, validated.cardColor)
            assertEquals(42, validated.cardAlpha)
            assertEquals("card", validated.backgroundStyle)
        }
    }

    @Test
    fun cardAlphaAndColorSurviveCanonicalizeRoundTrip() {
        // 仓库保存/加载会对文档做 canonicalize 往返(compile -> toSurfaceProfile)。
        // 回归:锁屏卡片颜色/透明度经往返后必须保留,否则滑条拖动会立刻弹回默认。
        val document = CustomizationDocument(
            linkSurfaces = true,
            profiles = mapOf(
                SceneCompiler.SURFACE_AOD to SurfaceProfile(
                    backgroundStyle = "none",
                    cardAlpha = 90,
                    cardColor = "black"
                ),
                SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                    backgroundStyle = "card",
                    cardAlpha = 30,
                    cardColor = "accent"
                )
            )
        )
        val canonical = CustomizationRepository.canonicalizeDocument(document)!!
        val lockscreen = canonical.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        assertEquals("card", lockscreen.backgroundStyle)
        assertEquals(30, lockscreen.cardAlpha)
        assertEquals("accent", lockscreen.cardColor)
    }

    @Test
    fun secondaryNextLineCompilesValidatesAndSurvivesCanonicalizeRoundTrip() {
        // 辅助文字显示第二行歌词开关必须穿过 compile、SystemUI 二次校验与仓库
        // canonicalize 往返(compile -> toSurfaceProfile):任一环节漏字段都会让开关
        // 保存后弹回关闭(逐字段重建映射的经典回归面)。
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(secondaryNextLine = true)
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertTrue(compiled.secondaryNextLine)

        val validated = SystemUiCustomizationValidator.validate(
            SceneCompiler.compile(
                CustomizationDocument(
                    profiles = mapOf(
                        SceneCompiler.SURFACE_AOD to SurfaceProfile(secondaryNextLine = true)
                    )
                )
            )
        )!!.profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertTrue(validated.secondaryNextLine)

        val canonical = CustomizationRepository.canonicalizeDocument(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(secondaryNextLine = true)
                )
            )
        )!!
        assertTrue(canonical.profiles.getValue(SceneCompiler.SURFACE_AOD).secondaryNextLine)
        // 默认文档保持关闭:不改变既有用户的呈现。
        assertFalse(
            SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
                .profiles.getValue(SceneCompiler.SURFACE_AOD).secondaryNextLine
        )
    }

    @Test
    fun rowAlignmentsCompileValidateAndSurviveCanonicalizeRoundTrip() {
        // 歌曲信息/第二行歌词独立对齐必须穿过 compile、SystemUI 二次校验与仓库
        // canonicalize 往返(compile -> toSurfaceProfile):任一环节漏字段都会让设置
        // 保存后弹回 auto(逐字段重建映射的经典回归面)。
        val document = CustomizationDocument(
            profiles = mapOf(
                SceneCompiler.SURFACE_AOD to SurfaceProfile(
                    metadataAlignment = "center",
                    nextLineAlignment = "end"
                )
            )
        )
        val compiled = SceneCompiler.compile(document).profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertEquals("center", compiled.metadataAlignment)
        assertEquals("end", compiled.nextLineAlignment)

        val validated = SystemUiCustomizationValidator.validate(SceneCompiler.compile(document))!!
            .profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertEquals("center", validated.metadataAlignment)
        assertEquals("end", validated.nextLineAlignment)

        val canonical = CustomizationRepository.canonicalizeDocument(document)!!
        assertEquals("center", canonical.profiles.getValue(SceneCompiler.SURFACE_AOD).metadataAlignment)
        assertEquals("end", canonical.profiles.getValue(SceneCompiler.SURFACE_AOD).nextLineAlignment)
        // 默认文档保持 auto:不改变既有用户的呈现。
        val safe = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
            .profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertEquals("auto", safe.metadataAlignment)
        assertEquals("auto", safe.nextLineAlignment)
        // 非法值回落 auto(与主对齐 ALIGNMENTS 白名单同规则)。
        val dirty = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        metadataAlignment = "bogus",
                        nextLineAlignment = "diagonal"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertEquals("auto", dirty.metadataAlignment)
        assertEquals("auto", dirty.nextLineAlignment)
    }

    @Test
    fun duetAlignmentCompilesValidatesAndSurvivesCanonicalizeRoundTrip() {
        // 对唱分侧开关必须穿过 compile、SystemUI 二次校验与仓库 canonicalize 往返
        // (compile -> toSurfaceProfile):关闭状态是「与默认不同」的值,漏字段会被
        // 逐字段重建映射静默弹回默认(开启),这正是 secondaryNextLine 的经典回归面。
        val off = CustomizationDocument(
            profiles = mapOf(
                SceneCompiler.SURFACE_AOD to SurfaceProfile(duetAlignment = false)
            )
        )
        assertFalse(SceneCompiler.compile(off).profiles.getValue(SceneCompiler.SURFACE_AOD).duetAlignment)
        assertFalse(
            SystemUiCustomizationValidator.validate(SceneCompiler.compile(off))!!
                .profiles.getValue(SceneCompiler.SURFACE_AOD).duetAlignment
        )
        assertFalse(
            CustomizationRepository.canonicalizeDocument(off)!!
                .profiles.getValue(SceneCompiler.SURFACE_AOD).duetAlignment
        )
        // 默认文档保持开启:对唱分侧是既有 alignedRight 渲染语义的默认延续。
        assertTrue(
            SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
                .profiles.getValue(SceneCompiler.SURFACE_AOD).duetAlignment
        )
    }

    @Test
    fun duetMarkersCompilesValidatesAndSurvivesCanonicalizeRoundTrip() {
        // 「识别对唱标记」是文档级全局开关:关闭状态是「与默认不同」的值,必须穿过
        // compile、SystemUI 二次校验与仓库 canonicalize 逐字段重建往返,漏字段会被
        // 静默弹回默认(开启)。
        val off = CustomizationDocument(duetMarkers = false)
        assertFalse(SceneCompiler.compile(off).duetMarkers)
        assertFalse(SystemUiCustomizationValidator.validate(SceneCompiler.compile(off))!!.duetMarkers)
        assertFalse(CustomizationRepository.canonicalizeDocument(off)!!.duetMarkers)
        // 默认文档保持开启(标记识别即对唱特性在真实内容上的输入形态)。
        assertTrue(SceneCompiler.compile(SceneCompiler.safeDefaultDocument()).duetMarkers)
    }

    @Test
    fun systemUiValidatorResetsInvalidCardColorToDefault() {
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        backgroundStyle = "card",
                        cardColor = "black"
                    )
                )
            )
        )
        val tampered = compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
            .copy(cardColor = "injected_color")
        val validated = SystemUiCustomizationValidator.validate(
            compiled.copy(
                profiles = compiled.profiles + (SceneCompiler.SURFACE_LOCKSCREEN to tampered)
            )
        )!!.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)

        assertEquals(DEFAULT_CARD_COLOR, validated.cardColor)
    }

    @Test
    fun systemUiValidatorRejectsVersionAndChangesCanonicalDigestAfterTampering() {
        val compiled = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        assertNull(SystemUiCustomizationValidator.validate(compiled.copy(version = 99)))

        val tamperedAod = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD).copy(
            anchor = "screen_center",
            palette = mapOf("primaryText" to "dimmed")
        )
        val validated = SystemUiCustomizationValidator.validate(
            compiled.copy(profiles = compiled.profiles + (SceneCompiler.SURFACE_AOD to tamperedAod))
        )!!

        assertEquals(
            "screen_center",
            validated.profiles.getValue(SceneCompiler.SURFACE_AOD).anchor
        )
        assertEquals(
            "dimmed",
            validated.profiles.getValue(SceneCompiler.SURFACE_AOD).palette["primaryText"]
        )
        assertNotEquals(compiled.hash, validated.hash)
    }

    @Test
    fun repositoryRejectsFutureVersionAndRecoversPreviousDocument() {
        assertNull(
            CustomizationRepository.canonicalizeDocument(
                SceneCompiler.safeDefaultDocument().copy(version = CURRENT_CUSTOMIZATION_VERSION + 1)
            )
        )
        val previous = SceneCompiler.safeDefaultDocument().copy(name = "Previous")
        val previousRaw = SceneCompiler.json.encodeToString(previous)
        val recovered = CustomizationRepository.recoverDocument(
            currentRaw = "{broken",
            previousRaw = previousRaw,
            legacy = AodRenderConfig()
        )

        assertEquals("Previous", recovered.name)
    }

    @Test
    fun placementHidesOptionalWidgetsBeforePrimaryLyric() {
        val profile = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
            .profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
            .copy(enabled = true, maxHeightFraction = 1f)
        val lyric = WidgetSpec("lyrics")
        val metadata = WidgetSpec("metadata", optional = true)
        val resolved = PlacementEngine.resolve(
            profile,
            PlacementEnvironment(
                safeCanvas = PlacementRect(0f, 0f, 1000f, 500f),
                stockClockBottom = 100f,
                bottomReserveTop = 300f
            ),
            listOf(WidgetMeasurement(lyric, 160f), WidgetMeasurement(metadata, 80f)),
            minimumLyricHeight = 100f
        )

        assertNotNull(resolved.contentRect)
        assertEquals(listOf("lyrics"), resolved.visibleWidgets.map { it.type })
        assertEquals(listOf("metadata"), resolved.hiddenWidgets.map { it.type })
    }

    @Test
    fun placementShrinksPrimaryLyricToMinimumAfterOptionalWidgetsHide() {
        val profile = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
            .profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
            .copy(enabled = true, maxHeightFraction = 1f)
        val resolved = PlacementEngine.resolve(
            profile,
            PlacementEnvironment(
                safeCanvas = PlacementRect(0f, 0f, 1_000f, 500f),
                stockClockBottom = 100f,
                bottomReserveTop = 250f
            ),
            listOf(
                WidgetMeasurement(WidgetSpec("lyrics"), 240f),
                WidgetMeasurement(WidgetSpec("metadata", optional = true), 60f)
            ),
            minimumLyricHeight = 100f
        )

        assertEquals(150f, resolved.contentRect?.height)
        assertEquals(listOf("lyrics"), resolved.visibleWidgets.map { it.type })
    }

    @Test
    fun metadataPartsAndSeparatorCompileThroughAndNormalize() {
        val document = SceneCompiler.safeDefaultDocument().copy(
            metadataParts = "album,title,bogus",
            metadataSeparator = "dot"
        )
        val compiled = SceneCompiler.compile(document)
        assertEquals("title,album", compiled.metadataParts)
        assertEquals("dot", compiled.metadataSeparator)

        val invalid = SceneCompiler.compile(
            document.copy(metadataParts = "bogus", metadataSeparator = "unknown")
        )
        assertEquals(METADATA_PARTS_DEFAULT, invalid.metadataParts)
        assertEquals(METADATA_SEPARATOR_NEWLINE, invalid.metadataSeparator)

        // SystemUI 侧校验同样收敛新字段,防止越界配置经 wire 落地。
        val validated = SystemUiCustomizationValidator.validate(compiled)
        assertNotNull(validated)
        assertEquals("title,album", validated?.metadataParts)
        assertEquals("dot", validated?.metadataSeparator)
    }

    @Test
    fun artworkSettingsArePerSurfaceAcrossCompileValidateAndCanonicalize() {
        val document = SceneCompiler.safeDefaultDocument().copy(
            profiles = linkedMapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                    artworkVisible = true,
                    artworkShape = ARTWORK_SHAPE_CIRCLE,
                    artworkSpin = true,
                    artworkSpinWhenPaused = true
                ),
                SceneCompiler.SURFACE_AOD to SurfaceProfile(artworkVisible = false)
            )
        )
        val compiled = SceneCompiler.compile(document)
        val lockscreen = compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val aod = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD)

        // per-surface:锁屏开(圆形旋转)、息屏关,互不联动。
        assertEquals(true, lockscreen.artworkVisible)
        assertEquals(ARTWORK_SHAPE_CIRCLE, lockscreen.artworkShape)
        assertEquals(true, lockscreen.artworkSpin)
        assertEquals(true, lockscreen.artworkSpinWhenPaused)
        assertEquals(false, aod.artworkVisible)
        assertEquals(false, aod.artworkSpinWhenPaused)
        assertEquals(ARTWORK_SHAPE_SQUARE, aod.artworkShape)
        assertEquals(false, aod.artworkSpin)

        // 校验器与编译同源归一(不改写),否则 wire 的 validate_rewrote_fields 会拒收。
        assertEquals(compiled, SystemUiCustomizationValidator.validate(compiled))

        // 回写文档(defaults→canonical)后两面仍各自独立,文档级迁移载体保持清空。
        val canonical = CustomizationRepository.canonicalizeDocument(document)!!
        assertEquals(
            true,
            canonical.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).artworkVisible
        )
        assertEquals(
            true,
            canonical.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).artworkSpinWhenPaused
        )
        assertEquals(
            false,
            canonical.profiles.getValue(SceneCompiler.SURFACE_AOD).artworkVisible
        )
        assertNull(canonical.artworkVisible)
        assertNull(canonical.artworkShape)
        assertNull(canonical.artworkSpin)
    }

    @Test
    fun legacyDocumentLevelArtworkSeedsBothSurfacesOnce() {
        // 旧版(文档级)三项全局设置:首次读取播种到两个曲面,载体清空后不再播种。
        val legacy = SceneCompiler.safeDefaultDocument().copy(
            artworkVisible = true,
            artworkShape = ARTWORK_SHAPE_CIRCLE,
            artworkSpin = true
        )
        val migrated = CustomizationRepository.migrateDocument(legacy)!!
        listOf(SceneCompiler.SURFACE_LOCKSCREEN, SceneCompiler.SURFACE_AOD).forEach { surface ->
            val profile = migrated.profiles.getValue(surface)
            assertEquals(true, profile.artworkVisible)
            assertEquals(ARTWORK_SHAPE_CIRCLE, profile.artworkShape)
            assertEquals(true, profile.artworkSpin)
        }
        assertNull(migrated.artworkVisible)
        assertNull(migrated.artworkShape)
        assertNull(migrated.artworkSpin)

        // 已播种文档(载体为空)二次迁移原样通过,不覆盖用户后续的 per-surface 选择。
        val diverged = migrated.copy(
            profiles = migrated.profiles + (
                SceneCompiler.SURFACE_AOD to migrated.profiles
                    .getValue(SceneCompiler.SURFACE_AOD)
                    .copy(artworkVisible = false)
                )
        )
        val reloaded = CustomizationRepository.migrateDocument(diverged)!!
        assertEquals(
            false,
            reloaded.profiles.getValue(SceneCompiler.SURFACE_AOD).artworkVisible
        )
        assertEquals(
            true,
            reloaded.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN).artworkVisible
        )
    }

    @Test
    fun duetConcurrentCompilesValidatesAndSurvivesCanonicalizeRoundTrip() {
        // 对唱并发行开关必须穿过 compile、SystemUI 二次校验与仓库 canonicalize 往返:
        // 关闭状态是「与默认不同」的值,漏字段会被逐字段重建映射静默弹回默认(开启),
        // 这正是 secondaryNextLine/duetAlignment 的经典回归面。
        val off = CustomizationDocument(
            profiles = mapOf(
                SceneCompiler.SURFACE_AOD to SurfaceProfile(duetConcurrent = false)
            )
        )
        assertFalse(
            SceneCompiler.compile(off).profiles.getValue(SceneCompiler.SURFACE_AOD).duetConcurrent
        )
        assertFalse(
            SystemUiCustomizationValidator.validate(SceneCompiler.compile(off))!!
                .profiles.getValue(SceneCompiler.SURFACE_AOD).duetConcurrent
        )
        assertFalse(
            CustomizationRepository.canonicalizeDocument(off)!!
                .profiles.getValue(SceneCompiler.SURFACE_AOD).duetConcurrent
        )
        // 默认文档保持开启(上游 duetEnabled 同值)。
        assertTrue(
            SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
                .profiles.getValue(SceneCompiler.SURFACE_AOD).duetConcurrent
        )
    }

}