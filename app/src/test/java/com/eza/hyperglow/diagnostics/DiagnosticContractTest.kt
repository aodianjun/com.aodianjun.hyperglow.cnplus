package com.eza.hyperglow.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticContractTest {
    @Test
    fun compatibilityCategoryUsesUnambiguousLabel() {
        assertEquals("Compatibility", HyperGlowReportCategory.COMPATIBILITY.displayName)
    }

    @Test
    fun reportIdUsesFixedCrockfordEncoding() {
        val id = DiagnosticReportId.fromBytes(ByteArray(16))

        assertEquals("R1-00000000000000000000000000", id)
        assertTrue(isValidDiagnosticReportId(id))
        assertTrue(isValidDiagnosticReportId(DiagnosticReportId.fromBytes(ByteArray(16) { -1 })))
        assertFalse(isValidDiagnosticReportId("R1-OOOOOOOOOOOOOOOOOOOOOOOOOO"))
    }

    @Test
    fun utf8TruncationKeepsFirstQuarterAndNewestThreeQuarters() {
        val source = "start-" + "中".repeat(80) + "-newest"

        val truncated = truncateDiagnosticText(source, 100)

        assertTrue(truncated.truncated)
        assertTrue(truncated.text.startsWith("start-"))
        assertTrue(truncated.text.endsWith("-newest"))
        assertTrue(truncated.text.contains("TRUNCATED"))
        assertTrue(truncated.includedBytes <= 100)
        assertFalse(truncated.text.contains('\uFFFD'))
    }

    @Test
    fun lineTruncationDoesNotKeepPartialNewestLogLine() {
        val source = (1..40).joinToString("\n") { index ->
            "HyperGlow line-$index " + "x".repeat(24)
        }

        val truncated = truncateDiagnosticLines(source, 240)
        val retained = truncated.text.lineSequence()
            .filter { it.isNotBlank() && !it.contains("TRUNCATED") }
            .toList()

        assertTrue(truncated.truncated)
        assertTrue(retained.isNotEmpty())
        assertTrue(retained.all { it.startsWith("HyperGlow line-") })
        assertTrue(truncated.text.endsWith("HyperGlow line-40 " + "x".repeat(24)))
        assertTrue(truncated.includedBytes <= 240)
    }

    @Test
    fun reportCodecRejectsOversizedDescription() {
        val report = sampleReport(description = "界".repeat(1_334))

        assertFalse(DiagnosticReportCodec.isValidReport(report))
    }

    @Test
    fun encodedReportContainsMediaEvidenceButNoCredentialFields() {
        val encoded = DiagnosticReportCodec.encode(sampleReport())

        assertTrue(encoded.contains("spotify:track:test"))
        assertTrue(encoded.contains("Current lyric"))
        listOf(
            "artwork",
            "imageId",
            "androidId",
            "serial",
            "imei",
            "wifiSsid",
            "authToken",
            "cookie",
            "screenshot"
        ).forEach { forbidden -> assertFalse(forbidden, encoded.contains(forbidden)) }
    }

    @Test
    fun githubIssueExcludesPrivateDiagnostics() {
        val report = sampleReport(
            description = "AOD disappears after a song change.",
            privateLog = "PRIVATE_LOG_SENTINEL"
        )

        val issue = buildHyperGlowGitHubIssue(report)

        assertTrue(issue.body.contains(report.reportId))
        assertTrue(issue.body.contains(report.description))
        assertTrue(issue.body.contains("## Description"))
        assertTrue(issue.body.contains("## Report details"))
        assertTrue(issue.body.contains("## Diagnostic data"))
        assertTrue(issue.body.contains(HYPERGLOW_DIAGNOSTIC_DATA_POLICY_URL))
        assertFalse(issue.body.contains("PRIVATE_LOG_SENTINEL"))
        assertFalse(issue.body.contains("keepAodActive"))
        assertTrue(issue.body.contains("Test song"))
        assertTrue(issue.body.contains("spotify:track:test"))
        assertFalse(issue.body.contains("Current lyric"))
    }

    @Test
    fun githubIssueWithDataEmbedsTruncatedDiagnosticsWithinBudget() {
        val report = sampleReport(
            description = "AOD disappears after a song change.",
            privateLog = "log-line-1\nlog-line-2\n" + ("padding ".repeat(40))
        )

        val issue = buildHyperGlowGitHubIssueWithData(report)

        // 摘要元数据仍在
        assertTrue(issue.body.contains(report.reportId))
        assertTrue(issue.body.contains("## Description"))
        assertTrue(issue.body.contains("## Report details"))
        // 日志与运行时设置已内嵌(折叠块)
        assertTrue(issue.body.contains("log-line-1"))
        assertTrue(issue.body.contains("keepAodActive = true"))
        assertTrue(issue.body.contains("<details>"))
        assertTrue(issue.body.contains("HyperGlow logs"))
        assertTrue(issue.body.contains("Runtime settings"))
        // 完整负载字节在预算内(摘要 ANCHOR:预算限制来自 URL 预填的承受力)
        assertTrue(issue.body.utf8Size() <= GITHUB_ISSUE_BODY_BYTES_LIMIT)
        // 附带完整的描述文本不被截断,说明日志被截断但描述保留
        assertTrue(issue.body.contains("AOD disappears after a song change."))
    }

    @Test
    fun githubIssueWithDataHonorsCustomByteBudget() {
        val report = sampleReport(
            description = "Short description.",
            privateLog = "start-event\n" + "x".repeat(2_000)
        )

        val tiny = buildHyperGlowGitHubIssueWithData(report, bodyBytesLimit = 1_200)
        val default = buildHyperGlowGitHubIssueWithData(report)

        assertTrue(tiny.body.utf8Size() <= 1_200)
        assertTrue(default.body.utf8Size() > tiny.body.utf8Size())
        assertTrue(default.body.contains("start-event"))
    }

    @Test
    fun githubIssueWithDataClippedToSummaryWhenBudgetTooSmall() {
        val report = sampleReport(description = "Keep the summary.")
        val issue = buildHyperGlowGitHubIssueWithData(report, bodyBytesLimit = 64)

        // 预算小于基础摘要时退回纯摘要(不内嵌日志)
        assertTrue(issue.body.contains(report.reportId))
        assertFalse(issue.body.contains("<details>"))
        assertTrue(issue.body.utf8Size() <= GITHUB_ISSUE_BODY_BYTES_LIMIT)
    }

    @Test
    fun jsonPreviewIsPrettyPrintedButRoundTrips() {
        val report = sampleReport()
        val preview = DiagnosticReportCodec.encodePretty(report)

        assertTrue(preview.contains('\n'))
        assertTrue(preview.contains("    \"reportId\""))
        assertEquals(report, DiagnosticReportCodec.decodeOrNull(preview))
    }

    @Test
    fun readableJsonPreviewExpandsMultilineDiagnosticsWithoutChangingPayload() {
        val report = sampleReport(privateLog = "first event\nsecond event")
        val payload = DiagnosticReportCodec.encode(report)

        val preview = requireNotNull(DiagnosticJsonPreviewFormatter.format(payload))

        assertEquals("first event\nsecond event", preview.diagnosticEventsAndLogs)
        assertFalse(preview.reportJson.contains("rawDiagnostics"))
        assertTrue(preview.reportJson.contains("\n"))
        assertEquals(report, DiagnosticReportCodec.decodeOrNull(payload))
    }

    @Test
    fun readableJsonPreviewRejectsMalformedJson() {
        assertNull(DiagnosticJsonPreviewFormatter.format("not-json"))
    }

    @Test
    fun invalidPendingJsonIsRejected() {
        assertNull(DiagnosticReportCodec.decodeOrNull("{\"envelopeVersion\":99}"))
    }

    private fun sampleReport(
        description: String = "Compatibility problem",
        privateLog: String = "filtered module log"
    ) = DiagnosticReportEnvelope(
        reportId = "R1-00000000000000000000000000",
        createdAtUtc = "2026-08-01T00:00:00Z",
        category = HyperGlowReportCategory.COMPATIBILITY.wireValue,
        description = description,
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
            diagnosticEventsAndLogs = privateLog,
            crashExcerpt = "",
            lsposedModuleLines = "",
            runtimeSettings = mapOf("keepAodActive" to "true")
        )
    )
}
