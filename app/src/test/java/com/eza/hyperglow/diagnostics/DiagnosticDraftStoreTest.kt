package com.eza.hyperglow.diagnostics

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 2.12「DraftStore encode 在 try 外」修复的契约测试：
 * - 非法报告（[DiagnosticReportCodec.encode] 的 require 抛异常）必须收敛为 false，
 *   不逃逸 [DiagnosticDraftStore.save] 的 Boolean 契约；
 * - 失败不落半截文件、不覆盖既有草稿；
 * - 合法报告 save→load 往返仍成功。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DiagnosticDraftStoreTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    @Test
    fun saveReturnsFalseInsteadOfThrowingForInvalidReport() {
        val ctx = context()
        // description 空白 → isValidReport=false → encode 的 require 抛 IllegalArgumentException。
        val invalid = validReport().copy(description = "   ")

        assertFalse(DiagnosticDraftStore.save(ctx, invalid))
        assertFalse(File(ctx.cacheDir, "diagnostic-report-pending.json").exists())
    }

    @Test
    fun saveLoadRoundTripKeepsValidReport() {
        val ctx = context()
        val report = validReport()

        assertTrue(DiagnosticDraftStore.save(ctx, report))
        val loaded = DiagnosticDraftStore.load(ctx)

        assertEquals(report.reportId, loaded?.reportId)
        assertEquals(report.description, loaded?.description)
    }

    @Test
    fun invalidReportDoesNotClobberExistingDraft() {
        val ctx = context()
        assertTrue(DiagnosticDraftStore.save(ctx, validReport()))

        assertFalse(DiagnosticDraftStore.save(ctx, validReport().copy(reportId = "not-an-id")))

        assertEquals(validReport().reportId, DiagnosticDraftStore.load(ctx)?.reportId)
    }

    private fun validReport() = DiagnosticReportEnvelope(
        reportId = "R1-00000000000000000000000000",
        createdAtUtc = "2026-08-01T00:00:00Z",
        category = HyperGlowReportCategory.COMPATIBILITY.wireValue,
        description = "Compatibility problem",
        commonMetadata = DiagnosticCommonMetadata(
            appVersionName = "0.4.0",
            appVersionCode = 45,
            buildType = "debug",
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            model = "Xiaomi 14",
            device = "houji",
            product = "houji_global",
            androidRelease = "16",
            androidApi = 36,
            androidSecurityPatch = "2026-07-01",
            androidDisplay = "BP2A.test",
            androidIncremental = "test",
            buildFingerprint = "xiaomi/houji/test:user/release-keys",
            xiaomiOsProperties = mapOf("ro.mi.os.version.name" to "OS3"),
            locales = listOf("en-US"),
            packageVersions = mapOf(
                "systemui" to DiagnosticPackageVersion(true, "16.0", 1L),
                "xiaomi_aod" to DiagnosticPackageVersion(true, "3.0", 2L)
            )
        ),
        productMetadata = HyperGlowProductMetadata(
            capabilityReportProtocol = 2,
            capabilityReportAgeMs = 100L,
            profileState = "unsupported_profile",
            rawSymbolProbes = mapOf("AOD_SURFACE_LIFECYCLE" to true),
            resolvedCapabilities = emptyList(),
            configuredSurfaces = mapOf("aod" to true, "lockscreen" to false),
            systemUiCallbackPresent = true,
            spotifyProducerStatePresent = true,
            spotifyProducerSafeStatus = "ready",
            spotifyProducerPlaying = true,
            spotifyProducerStateAgeMs = 50L,
            diagnosticLoggingAvailable = true,
            diagnosticLoggingEnabled = false,
            rootAccessStatus = "not_checked",
            currentMediaEvidence = DiagnosticMediaEvidence(
                present = true,
                trackUri = "spotify:track:test",
                title = "Test song",
                artist = "Test artist",
                album = "Test album",
                source = "hyperglow_bridge",
                provider = "Spicy Lyrics",
                language = "ja",
                timingType = "Syllable",
                lineIndex = 3,
                originalLine = "Current lyric",
                romanizedLine = "Current reading",
                translatedLine = "Current translation",
                stateAgeMs = 50L
            )
        ),
        capture = DiagnosticCaptureMetadata(
            outcome = "not_requested",
            startedAtUtc = null,
            finishedAtUtc = "2026-08-01T00:00:01Z",
            previousDiagnosticLoggingEnabled = null,
            rootAccessStatus = "not_checked",
            commandFailures = emptyList(),
            truncationFlags = emptyMap()
        ),
        rawDiagnostics = DiagnosticRawData(
            diagnosticEventsAndLogs = "filtered module log",
            crashExcerpt = "",
            lsposedModuleLines = "",
            runtimeSettings = mapOf("keepAodActive" to "true")
        )
    )
}
