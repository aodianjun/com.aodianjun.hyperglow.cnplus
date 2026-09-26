package com.eza.hyperglow.root.customization

import com.eza.hyperglow.RuntimeCustomization
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompiledCustomizationWirePayloadTest {
    @Test
    fun validPayloadRoundTripsWithoutAndroidBundle() {
        val configuration = RuntimeCustomization.withDiagnosticLogging(
            SceneCompiler.compile(SceneCompiler.safeDefaultDocument()),
            diagnosticLogging = true,
            available = true,
            pauseLingerMs = 30_000L,
            raiseToAod = true,
            suppressLockscreenEditorLongPress = true
        )
        val payload = CompiledCustomizationBundleCodec.toWirePayload(configuration, userId = 10)

        assertTrue(CompiledCustomizationBundleCodec.isValidWirePayload(payload))
        assertEquals(
            configuration,
            CompiledCustomizationBundleCodec.fromWirePayload(payload, expectedUserId = 10)
        )
        assertTrue(
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload,
                expectedUserId = 10
            )?.diagnosticLogging == true
        )
        assertTrue(
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload,
                expectedUserId = 10
            )?.raiseToAod == true
        )
        assertEquals(
            30_000L,
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload,
                expectedUserId = 10
            )?.pauseLingerMs
        )
        assertTrue(
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload,
                expectedUserId = 10
            )?.suppressLockscreenEditorLongPress == true
        )
        assertNull(
            CompiledCustomizationBundleCodec.fromWirePayload(payload, expectedUserId = 0)
        )
    }

    @Test
    fun invalidEnvelopeFieldsFailClosedBeforeJsonDecode() {
        val configuration = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val payload = CompiledCustomizationBundleCodec.toWirePayload(configuration)

        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(payload.copy(protocol = 99))
        )
        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(payload.copy(userId = -1))
        )
        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(payload.copy(revision = -1L))
        )
        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(payload.copy(hash = "not-sha256"))
        )
        assertNull(
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload.copy(revision = payload.revision + 1L)
            )
        )
    }

    @Test
    fun oversizedAsciiAndUtf8PayloadsFailClosed() {
        val configuration = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val payload = CompiledCustomizationBundleCodec.toWirePayload(configuration)

        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(
                payload.copy(json = "x".repeat(SceneCompiler.MAX_CONFIG_BYTES + 1))
            )
        )
        assertFalse(
            CompiledCustomizationBundleCodec.isValidWirePayload(
                payload.copy(json = "é".repeat(SceneCompiler.MAX_CONFIG_BYTES / 2 + 1))
            )
        )
    }

    @Test
    fun experimentalModeFlagRoundTripsThroughWirePayload() {
        val configuration = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val payload = CompiledCustomizationBundleCodec.toWirePayload(
            configuration,
            userId = 0,
            experimentalMode = true
        )
        assertTrue(payload.experimentalMode)
        // flag 不影响 payload 合法性
        assertTrue(CompiledCustomizationBundleCodec.isValidWirePayload(payload))
        // 默认值为 false(老 app 端不带此字段时回退)
        val defaultPayload = CompiledCustomizationBundleCodec.toWirePayload(configuration)
        assertFalse(defaultPayload.experimentalMode)
    }

    @Test
    fun customPaletteColorsRoundTripWithoutValidateMutation() {
        val palette = mapOf(
            "primaryText" to "#A9D9FF",
            "secondaryText" to "dimmed",
            "metadataText" to "#80FFFFFF",
            "nextLineText" to "#FFF",
            "sungText" to "wallpaper",
            "unsungText" to "white",
            "glow" to "#FF8800",
            "accent" to "clock",
            "surfaceScrim" to "#20202020"
        )
        val document = SceneCompiler.safeDefaultDocument().copy(
            profiles = mapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                    palette = palette,
                    fontFamily = "noto",
                    weight = "Bold",
                    cardAlpha = 40,
                    cardColor = "accent",
                    lyricLineLimit = 2
                ),
                SceneCompiler.SURFACE_AOD to SurfaceProfile(
                    palette = palette,
                    fontFamily = "apple",
                    weight = "Regular"
                )
            )
        )
        val configuration = RuntimeCustomization.withDiagnosticLogging(
            SceneCompiler.compile(document),
            diagnosticLogging = false,
            available = true,
            pauseLingerMs = 10_000L
        )
        val payload = CompiledCustomizationBundleCodec.toWirePayload(configuration, userId = 10)

        val rejections = ArrayList<String>()
        val parsed = CompiledCustomizationBundleCodec.fromWirePayload(
            payload,
            expectedUserId = 10,
            onReject = { rejections += it }
        )

        assertTrue("payload rejected: $rejections", rejections.isEmpty())
        assertEquals(configuration, parsed)
        assertEquals(
            "#A9D9FF",
            parsed?.profiles?.get(SceneCompiler.SURFACE_LOCKSCREEN)?.palette?.get("primaryText")
        )
        assertEquals(
            "#20202020",
            parsed?.profiles?.get(SceneCompiler.SURFACE_AOD)?.palette?.get("surfaceScrim")
        )
    }

    @Test
    fun customFontFamilyRoundTripsWithoutValidateMutation() {
        // 自定义字体令牌若被 SystemUI 校验器改写,CompiledCustomizationBundleCodec 会以
        // validate_rewrote_fields 整包拒收 —— 表现即"字体只在预览生效、实机不变"。
        val document = SceneCompiler.safeDefaultDocument().copy(
            profiles = mapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(fontFamily = "custom:legacy"),
                SceneCompiler.SURFACE_AOD to SurfaceProfile(fontFamily = "custom:yzzqdbtb")
            )
        )
        val configuration = SceneCompiler.compile(document)

        assertEquals(
            "custom:yzzqdbtb",
            configuration.profiles.getValue(SceneCompiler.SURFACE_AOD).fontFamily
        )
        val payload = CompiledCustomizationBundleCodec.toWirePayload(configuration, userId = 10)
        val rejections = ArrayList<String>()
        val parsed = CompiledCustomizationBundleCodec.fromWirePayload(
            payload,
            expectedUserId = 10,
            onReject = { rejections += it }
        )

        assertTrue("payload rejected: $rejections", rejections.isEmpty())
        assertEquals(configuration, parsed)
        assertEquals(
            "custom:legacy",
            parsed?.profiles?.get(SceneCompiler.SURFACE_LOCKSCREEN)?.fontFamily
        )
    }

    @Test
    fun unsafeFontFamilyTokenIsNormalizedAtCompileTime() {
        val document = SceneCompiler.safeDefaultDocument().copy(
            profiles = mapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(fontFamily = "custom:../escape"),
                SceneCompiler.SURFACE_AOD to SurfaceProfile(fontFamily = "custom:../escape")
            )
        )
        val configuration = SceneCompiler.compile(document)

        assertEquals(
            "spotify",
            configuration.profiles.getValue(SceneCompiler.SURFACE_AOD).fontFamily
        )
    }

    @Test
    fun rejectionReasonsClassifyEachFailurePath() {
        val configuration = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        val payload = CompiledCustomizationBundleCodec.toWirePayload(configuration, userId = 10)

        val envelopeRejections = ArrayList<String>()
        assertNull(
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload.copy(protocol = 99),
                expectedUserId = 10,
                onReject = { envelopeRejections += it }
            )
        )
        assertTrue(envelopeRejections.single().startsWith("envelope_invalid"))

        val userRejections = ArrayList<String>()
        assertNull(
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload,
                expectedUserId = 0,
                onReject = { userRejections += it }
            )
        )
        assertTrue(userRejections.single().startsWith("user_mismatch"))

        val decodeRejections = ArrayList<String>()
        assertNull(
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload.copy(json = "{not-json"),
                expectedUserId = 10,
                onReject = { decodeRejections += it }
            )
        )
        assertTrue(decodeRejections.single().startsWith("json_decode_failed"))

        val mismatchRejections = ArrayList<String>()
        assertNull(
            CompiledCustomizationBundleCodec.fromWirePayload(
                payload.copy(revision = payload.revision + 1L),
                expectedUserId = 10,
                onReject = { mismatchRejections += it }
            )
        )
        assertTrue(mismatchRejections.single().startsWith("envelope_body_mismatch"))
    }
}
