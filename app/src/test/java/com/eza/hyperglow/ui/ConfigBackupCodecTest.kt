package com.eza.hyperglow.ui

import com.eza.hyperglow.DiagnosticLogLevel
import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.producer.LyricSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigBackupCodecTest {
    /** 一个远离默认值的配置,覆盖每个 Long 都取恰好能落在 Int 范围内的值。 */
    private fun nonDefaultPreferences() = AodRenderConfig(
        aodEnabled = false,
        lockscreenEnabled = true,
        alignment = "end",
        secondaryMode = "Both",
        overflowMode = "Clip",
        metadataVisible = "show",
        metadataAnchor = "bottom",
        metadataSizePercent = 160,
        weight = "Bold",
        textSize = "large",
        textSizeCustom = 137,
        fontFamily = "noto",
        animation = "Minimal",
        glow = "On",
        adaptiveSectioning = false,
        keepAwake = false,
        aodClockFollow = true,
        aodClockYOffset = 120,
        keepAwakeUnsynced = true,
        keepAwakeDurationMs = 300_000L,
        experimentalPositionFollowing = true,
        burnInPattern = "four_corner",
        burnInIntervalMs = 60_000L,
        pauseLingerMs = 5_000L,
        pauseShowContent = true,
        lockscreenKeepAwake = true,
        raiseToAod = true,
        suppressLockscreenEditorLongPress = true,
        experimentalMode = true,
        persistentNotification = false,
        hideBackgroundCard = true,
        hideLauncherIcon = true,
        aodBrightnessBoost = false,
        pluginProcessingEnabled = true,
        suppressStockAodContent = true,
        aodRotateWithDevice = true,
        aodRotationMode = "landscape_reverse",
        aodRotationSettleMs = 2_000L,
        aodCanvasAnchorLandscape = 0.75f,
        aodLandscapeTextScale = 1.5f,
        aodLandscapeHideStock = true,
        aodLandscapeFullscreen = true,
        aodLandscapeFullscreenSafeMarginPercent = 12.5f,
        aodCanvasPaddingPortraitXPercent = 4.5f,
        aodCanvasPaddingPortraitYPercent = 6f,
        aodCanvasPaddingLandscapeXPercent = 8.25f,
        aodCanvasPaddingLandscapeYPercent = 10f,
        aodBrightnessOverride = true,
        aodBrightnessLevel = 73,
        aodDebugShowCanvasFrame = true,
        aodRefreshRateCap = 90,
        filterNonMusicSources = false,
        hideCreditLines = true
    )

    /** 每个补充设置面字段都取非默认值;插件值覆盖 SharedPreferences 全部六种原生类型。 */
    private fun fullSideSettings() = ConfigBackupSideSettings(
        lyricSource = LyricSource.LYRICON,
        appUiAppearance = AppUiAppearance(
            themeMode = AppThemeMode.DARK,
            themeColorMode = AppThemeColorMode.CUSTOM,
            themeColorArgb = 0xFF112233.toInt(),
            hasBackgroundImage = true,
            backgroundImageMtime = 42L,
            backgroundDimPercent = 25,
            backgroundBlurPercent = 66,
            controlColorArgb = 0xFF00FFAA.toInt(),
            controlOpacityPercent = 33,
            textColorArgb = 0xFFFF8800.toInt(),
            fontFamily = "custom:myfont",
            systemBarIcons = AppSystemBarIcons.LIGHT,
            floatingNavBar = false
        ),
        uiLanguage = UiLanguage.SIMPLIFIED_CHINESE,
        predictiveBack = false,
        backTriggerPercent = 35,
        diagnosticLogging = true,
        logRetentionDays = 15,
        logLevel = DiagnosticLogLevel.VERBOSE,
        pluginSettings = mapOf(
            "com.example.plugin" to mapOf(
                "enabled" to true,
                "title" to "hello",
                "count" to 300_000_000,
                "duration" to 300_000L,
                "ratio" to 1.5f,
                "tags" to setOf("a", "b")
            )
        )
    )

    @Test
    fun nonDefaultProfileRoundTripsFieldForField() {
        val encoded = ConfigBackupCodec.encode(
            nonDefaultPreferences(),
            null,
            ConfigBackupSideSettings()
        )

        val result = ConfigBackupCodec.decode(encoded.toByteArray())

        val decoded = (result as ConfigBackupDecodeResult.Success).preferences
        // 三个 Long 偏好正是旧实现的损坏场景:值都落在 Int 范围内,猜测阶梯把它们还原成 Int,
        // 下一次 getLong 读取就抛 ClassCastException。
        assertEquals(300_000L, decoded.keepAwakeDurationMs)
        assertEquals(60_000L, decoded.burnInIntervalMs)
        assertEquals(5_000L, decoded.pauseLingerMs)
        assertEquals(2_000L, decoded.aodRotationSettleMs)
        assertEquals(nonDefaultPreferences(), decoded)
    }

    @Test
    fun customizationDocumentRoundTrips() {
        val document = SceneCompiler.safeDefaultDocument()

        val result = ConfigBackupCodec.decode(
            ConfigBackupCodec.encode(AodRenderConfig(), document, ConfigBackupSideSettings())
                .toByteArray()
        )

        assertEquals(document, (result as ConfigBackupDecodeResult.Success).customizationDocument)
    }

    @Test
    fun sideSettingsRoundTripFieldForField() {
        val encoded = ConfigBackupCodec.encode(AodRenderConfig(), null, fullSideSettings())

        val result = ConfigBackupCodec.decode(encoded.toByteArray())

        assertEquals(fullSideSettings(), (result as ConfigBackupDecodeResult.Success).sideSettings)
    }

    @Test
    fun pluginNumericValuesKeepTheirDeclaredTypes() {
        // Int/Long/Float 在 JSON 里同形,还原时猜类型会复现 keepAwakeDurationMs 的
        // ClassCastException;类型标签保证三种数字各回各位。
        val result = ConfigBackupCodec.decode(
            ConfigBackupCodec.encode(AodRenderConfig(), null, fullSideSettings()).toByteArray()
        )

        val values = (result as ConfigBackupDecodeResult.Success)
            .sideSettings.pluginSettings!!.getValue("com.example.plugin")
        assertTrue(values["count"] is Int)
        assertTrue(values["duration"] is Long)
        assertTrue(values["ratio"] is Float)
        assertEquals(300_000_000, values["count"])
        assertEquals(300_000L, values["duration"])
        assertEquals(1.5f, values["ratio"])
        assertTrue(values["tags"] is Set<*>)
    }

    @Test
    fun oldBackupsWithoutSideKeysLeaveSurfacesUntouched() {
        // 旧备份从未捕获过补充设置面:导入它不得把歌词源/外观/插件设置清成默认值,
        // 这些键缺省时必须保持 null 由导入端维持现状。
        val v1 = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": 1,
              "renderPreferences": {
                "${AodRenderPreferences.KEEP_AWAKE}": false
              }
            }
        """.trimIndent()

        val result = ConfigBackupCodec.decode(v1.toByteArray())

        val side = (result as ConfigBackupDecodeResult.Success).sideSettings
        assertNull(side.lyricSource)
        assertNull(side.appUiAppearance)
        assertNull(side.uiLanguage)
        assertNull(side.predictiveBack)
        assertNull(side.backTriggerPercent)
        assertNull(side.diagnosticLogging)
        assertNull(side.logRetentionDays)
        assertNull(side.pluginSettings)
        assertEquals(false, result.preferences.keepAwake)
    }

    @Test
    fun absentSideKeysAreNull() {
        val payload = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "lyricSource": "SPICY"
            }
        """.trimIndent()

        val side = (ConfigBackupCodec.decode(payload.toByteArray())
            as ConfigBackupDecodeResult.Success).sideSettings

        assertEquals(LyricSource.SPICY, side.lyricSource)
        assertNull(side.appUiAppearance)
        assertNull(side.uiLanguage)
        assertNull(side.predictiveBack)
        assertNull(side.backTriggerPercent)
        assertNull(side.diagnosticLogging)
        assertNull(side.pluginSettings)
    }

    @Test
    fun navigationSettingsRoundTripAndNormalize() {
        val payload = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "appNavigation": {
                "predictiveBack": false,
                "backTriggerPercent": 37
              }
            }
        """.trimIndent()

        val side = (ConfigBackupCodec.decode(payload.toByteArray())
            as ConfigBackupDecodeResult.Success).sideSettings

        assertEquals(false, side.predictiveBack)
        // 阈值与设置页写入同口径归一(37 → 35,步进 5)。
        assertEquals(35, side.backTriggerPercent)

        // 对象在但缺键 → 该键回落默认值;超界阈值收敛到上限。
        val clamped = (ConfigBackupCodec.decode("""
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "appNavigation": {"backTriggerPercent": 999}
            }
        """.trimIndent().toByteArray()) as ConfigBackupDecodeResult.Success).sideSettings

        assertEquals(true, clamped.predictiveBack)
        assertEquals(AppNavigationPreferences.MAX_BACK_TRIGGER_PERCENT, clamped.backTriggerPercent)
    }

    @Test
    fun malformedNavigationValuesFallBackToDefaults() {
        val payload = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "appNavigation": {
                "predictiveBack": "yes",
                "backTriggerPercent": "far"
              }
            }
        """.trimIndent()

        val side = (ConfigBackupCodec.decode(payload.toByteArray())
            as ConfigBackupDecodeResult.Success).sideSettings

        assertEquals(AppNavigationPreferences.DEFAULT_PREDICTIVE_BACK, side.predictiveBack)
        assertEquals(
            AppNavigationPreferences.DEFAULT_BACK_TRIGGER_PERCENT,
            side.backTriggerPercent
        )
    }

    @Test
    fun absentRenderKeysKeepBaseInsteadOfResettingToDefaults() {
        // 旧备份里不存在的新键在导入时保持现状:以 base(当前生效配置)为底,只有载荷
        // 实际包含的键被覆盖;键在但形状不符才回落默认值——两种情形必须可区分。
        val base = nonDefaultPreferences()
        val missing = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "renderPreferences": {
                "${AodRenderPreferences.KEEP_AWAKE}": true
              }
            }
        """.trimIndent()

        val decoded = (ConfigBackupCodec.decode(missing.toByteArray(), base)
            as ConfigBackupDecodeResult.Success).preferences

        assertEquals(true, decoded.keepAwake)
        assertEquals(base.copy(keepAwake = true), decoded)

        val malformed = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "renderPreferences": {
                "${AodRenderPreferences.KEEP_AWAKE_DURATION_MS}": 300000.5
              }
            }
        """.trimIndent()

        val malformedDecoded = (ConfigBackupCodec.decode(malformed.toByteArray(), base)
            as ConfigBackupDecodeResult.Success).preferences

        assertEquals(-1L, malformedDecoded.keepAwakeDurationMs)
        assertEquals(base.copy(keepAwakeDurationMs = -1L), malformedDecoded)
    }

    @Test
    fun unknownEnumValuesFallBackToDefaultsNotRawStrings() {
        val payload = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "lyricSource": "ALIEN",
              "uiLanguage": "ALIEN"
            }
        """.trimIndent()

        val side = (ConfigBackupCodec.decode(payload.toByteArray())
            as ConfigBackupDecodeResult.Success).sideSettings

        assertEquals(LyricSource.SPICY, side.lyricSource)
        assertEquals(UiLanguage.SYSTEM, side.uiLanguage)
    }

    @Test
    fun malformedSideValuesFallBackToSectionDefaults() {
        val payload = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "appUiAppearance": {
                "theme_mode": "ALIEN",
                "theme_color_argb": "not-an-int",
                "background_dim_percent": 250,
                "background_blur_percent": "far",
                "control_color_argb": "far",
                "control_opacity_percent": 250
              },
              "diagnostics": {
                "diagnostic_logging": "yes",
                "log_retention_days": "far"
              }
            }
        """.trimIndent()

        val side = (ConfigBackupCodec.decode(payload.toByteArray())
            as ConfigBackupDecodeResult.Success).sideSettings

        val appearance = side.appUiAppearance!!
        assertEquals(AppThemeMode.SYSTEM, appearance.themeMode)
        assertEquals(DEFAULT_THEME_COLOR_ARGB, appearance.themeColorArgb)
        assertEquals(100, appearance.backgroundDimPercent)
        assertEquals(DEFAULT_BACKGROUND_BLUR_PERCENT, appearance.backgroundBlurPercent)
        assertEquals(null, appearance.controlColorArgb)
        assertEquals(100, appearance.controlOpacityPercent)
        assertEquals(false, side.diagnosticLogging)
        assertEquals(7, side.logRetentionDays)
    }

    @Test
    fun malformedPluginValuesAreDroppedKeyByKey() {
        val payload = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "pluginSettings": {
                "com.example.plugin": {
                  "good": true,
                  "bad_float": {"f": "abc"},
                  "bad_tag": {"x": 1},
                  "bad_pair": {"i": 1, "l": 2},
                  "bad_array": [1, 2],
                  "": 5
                },
                "../evil": {"k": true}
              }
            }
        """.trimIndent()

        val side = (ConfigBackupCodec.decode(payload.toByteArray())
            as ConfigBackupDecodeResult.Success).sideSettings

        // 形状不符的键逐个丢弃;id 越界(参与 prefs 文件名)的插件整体不进结果。
        assertEquals(
            mapOf("com.example.plugin" to mapOf<String, Any?>("good" to true)),
            side.pluginSettings
        )
    }

    @Test
    fun numericStringValueSurvivesAsAStringNotANumber() {
        val preferences = nonDefaultPreferences().copy(fontFamily = "100")

        val result = ConfigBackupCodec.decode(
            ConfigBackupCodec.encode(preferences, null, ConfigBackupSideSettings()).toByteArray()
        )

        assertEquals("100", (result as ConfigBackupDecodeResult.Success).preferences.fontFamily)
    }

    @Test
    fun unknownKeysAreDroppedNeverDecoded() {
        val withExtras = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "injectedTopLevel": {"deep": [1, 2, 3]},
              "renderPreferences": {
                "${AodRenderPreferences.KEEP_AWAKE}": false,
                "arbitrary_key": 42,
                "${AodRenderPreferences.PAUSE_LINGER_MS}": "5000"
              }
            }
        """.trimIndent()
        val withoutExtras = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "renderPreferences": {
                "${AodRenderPreferences.KEEP_AWAKE}": false
              }
            }
        """.trimIndent()

        val fromExtras = ConfigBackupCodec.decode(withExtras.toByteArray())
        val fromClean = ConfigBackupCodec.decode(withoutExtras.toByteArray())

        assertEquals(fromClean, fromExtras)
    }

    @Test
    fun wrongTypedValueFallsBackToDefaultInsteadOfGuessing() {
        val payload = """
            {
              "format": "${ConfigBackupCodec.FORMAT}",
              "version": ${ConfigBackupCodec.VERSION},
              "renderPreferences": {
                "${AodRenderPreferences.KEEP_AWAKE_DURATION_MS}": 300000.5,
                "${AodRenderPreferences.WEIGHT}": 100
              }
            }
        """.trimIndent()

        val result = ConfigBackupCodec.decode(payload.toByteArray())

        val decoded = (result as ConfigBackupDecodeResult.Success).preferences
        assertEquals(-1L, decoded.keepAwakeDurationMs)
        assertEquals("Medium", decoded.weight)
    }

    @Test
    fun wrongFormatTagIsRejected() {
        val payload = """
            {"format": "something-else", "version": ${ConfigBackupCodec.VERSION}}
        """.trimIndent()

        val result = ConfigBackupCodec.decode(payload.toByteArray())

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.BAD_FORMAT),
            result
        )
    }

    @Test
    fun unsupportedVersionsAreRejected() {
        // 格式只做加法、版本号不抬;仍拒绝范围之外的版本,不猜未来格式。
        for (version in listOf(0, 2, 999)) {
            val payload = """
                {"format": "${ConfigBackupCodec.FORMAT}", "version": $version}
            """.trimIndent()

            val result = ConfigBackupCodec.decode(payload.toByteArray())

            assertEquals(
                ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.BAD_VERSION),
                result
            )
        }
    }

    @Test
    fun missingVersionIsRejected() {
        val payload = """{"format": "${ConfigBackupCodec.FORMAT}"}"""

        val result = ConfigBackupCodec.decode(payload.toByteArray())

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.BAD_VERSION),
            result
        )
    }

    @Test
    fun malformedJsonIsRejected() {
        val result = ConfigBackupCodec.decode("{not json".toByteArray())

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.MALFORMED),
            result
        )
    }

    @Test
    fun oversizePayloadIsRejectedBeforeParsing() {
        val oversize = ByteArray(ConfigBackupCodec.MAX_BYTES + 1) { 'z'.code.toByte() }

        val result = ConfigBackupCodec.decode(oversize)

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.OVERSIZE),
            result
        )
    }

    @Test
    fun payloadAtExactlyTheLimitReachesParsing() {
        // 边界本身不因尺寸被拒:恰好 MAX_BYTES 的垃圾以 malformed 失败而非 oversized,
        // 因此上限只拒绝严格更大的输入。
        val atLimit = ByteArray(ConfigBackupCodec.MAX_BYTES) { 'z'.code.toByte() }

        val result = ConfigBackupCodec.decode(atLimit)

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.MALFORMED),
            result
        )
    }
}
