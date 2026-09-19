package com.eza.hyperglow.ui

import com.eza.hyperglow.aod.AodRenderConfig
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.customization.SceneCompiler
import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigBackupCodecTest {
    /** 一个远离默认值的配置,覆盖每个 Long 都取恰好能落在 Int 范围内的值。 */
    private fun nonDefaultPreferences() = AodRenderConfig(
        aodEnabled = false,
        lockscreenEnabled = true,
        seamlessTransitionEnabled = false,
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
        aodCanvasAnchor = 0.25f,
        aodRotationSettleMs = 2_000L,
        aodCanvasAnchorLandscape = 0.75f,
        aodLandscapeTextScale = 1.5f,
        aodLandscapeHideStock = true,
        aodLandscapeFullscreen = true,
        aodCanvasPaddingPortraitXPercent = 4.5f,
        aodCanvasPaddingPortraitYPercent = 6f,
        aodCanvasPaddingLandscapeXPercent = 8.25f,
        aodCanvasPaddingLandscapeYPercent = 10f,
        aodBrightnessOverride = true,
        aodBrightnessLevel = 73
    )

    @Test
    fun nonDefaultProfileRoundTripsFieldForField() {
        val encoded = ConfigBackupCodec.encode(nonDefaultPreferences(), null)

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
            ConfigBackupCodec.encode(AodRenderConfig(), document).toByteArray()
        )

        assertEquals(document, (result as ConfigBackupDecodeResult.Success).customizationDocument)
    }

    @Test
    fun numericStringValueSurvivesAsAStringNotANumber() {
        val preferences = nonDefaultPreferences().copy(fontFamily = "100")

        val result = ConfigBackupCodec.decode(
            ConfigBackupCodec.encode(preferences, null).toByteArray()
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
    fun unsupportedVersionIsRejected() {
        val payload = """
            {"format": "${ConfigBackupCodec.FORMAT}", "version": 999}
        """.trimIndent()

        val result = ConfigBackupCodec.decode(payload.toByteArray())

        assertEquals(
            ConfigBackupDecodeResult.Rejected(ConfigBackupRejection.BAD_VERSION),
            result
        )
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