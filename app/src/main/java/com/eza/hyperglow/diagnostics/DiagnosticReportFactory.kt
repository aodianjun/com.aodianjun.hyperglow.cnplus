package com.eza.hyperglow.diagnostics

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.DiagnosticLoggingPreferences
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.AodStateBridge
import com.eza.hyperglow.aod.XiaomiCapabilityStore
import com.eza.hyperglow.bridge.SpicyBridgeStore
import com.eza.hyperglow.bridge.SpicyBridgeDocumentStore
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.producer.LyricProducers
import com.eza.hyperglow.producer.LyricSource
import com.eza.hyperglow.producer.ProducerConnection
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object DiagnosticReportFactory {
    fun requiresCompatibilityGuidedCapture(
        capabilityReportPresent: Boolean,
        systemUiCallbackPresent: Boolean
    ): Boolean = !capabilityReportPresent || !systemUiCallbackPresent

    suspend fun createCompatibilityReport(
        context: Context,
        category: HyperGlowReportCategory,
        description: String,
        reportId: String = DiagnosticReportId.generate()
    ): DiagnosticReportEnvelope = withContext(Dispatchers.IO) {
        create(
            context = context,
            reportId = reportId,
            category = category,
            description = description,
            startedAtUtcMillis = null,
            finishedAtUtcMillis = System.currentTimeMillis(),
            previousDiagnosticLogging = null,
            captured = CapturedDiagnosticData(
                outcome = "not_requested",
                rootAccessStatus = checkDiagnosticRootAccess(DiagnosticRootProcessRunner),
                logs = "",
                crashExcerpt = "",
                lsposedLines = "",
                commandFailures = emptyList(),
                truncationFlags = emptyMap()
            )
        )
    }

    suspend fun createCapturedReport(
        context: Context,
        capture: FinishedDiagnosticCapture
    ): DiagnosticReportEnvelope = withContext(Dispatchers.IO) {
        create(
            context = context,
            reportId = capture.session.reportId,
            category = capture.session.category,
            description = capture.session.description,
            startedAtUtcMillis = capture.session.startedAtUtcMillis,
            finishedAtUtcMillis = capture.finishedAtUtcMillis,
            previousDiagnosticLogging = capture.session.previousDiagnosticLogging,
            captured = capture.data
        )
    }

    private fun create(
        context: Context,
        reportId: String,
        category: HyperGlowReportCategory,
        description: String,
        startedAtUtcMillis: Long?,
        finishedAtUtcMillis: Long,
        previousDiagnosticLogging: Boolean?,
        captured: CapturedDiagnosticData
    ): DiagnosticReportEnvelope {
        require(description.isNotBlank() && description.utf8Size() <= DiagnosticLimits.DESCRIPTION_BYTES)
        val capability = XiaomiCapabilityStore.read(context)
        val document = CustomizationRepository.loadDocument(context)
        val renderPreferences = AodRenderPreferences.read(context)
        val aodConfigured = document.profiles[SceneCompiler.SURFACE_AOD]?.enabled
            ?: renderPreferences.aodEnabled
        val lockscreenConfigured = document.profiles[SceneCompiler.SURFACE_LOCKSCREEN]?.enabled
            ?: renderPreferences.lockscreenEnabled
        // 媒体证据取自仲裁者的活跃生产者：CN+ 的歌词可能来自 Spicy EX（Spotify）、
        // Lyricon、SuperLyric 或 LyricInfo，Spicy 桥只是其中一路。仅读 SpicyBridgeStore
        // 会让其它源用户的报告里媒体证据永远为空，并把 setup 误判为缺 Spotify。
        val arbiter = LyricProducers.arbiterOrNull()
        val producerSource = arbiter?.activeSource?.value
        val producer = arbiter?.active?.value
        val producerAge = producer?.let {
            (SystemClock.elapsedRealtime() - it.receivedAtElapsedMs).coerceAtLeast(0L)
        }
        // provider/timingType 只对 Spicy 源存在独立歌词文档；其余源以源名与
        // lyricKind（unsynced/line/syllable）表达同样的信息。
        val spicyState = if (producerSource == LyricSource.SPICY) {
            SpicyBridgeStore.state.value?.takeIf { SpicyBridgeStore.isCurrentActive(it) }
        } else {
            null
        }
        val lyricDocument = spicyState?.let { spicy ->
            SpicyBridgeDocumentStore.state.value?.takeIf { it.matches(spicy) }
        }
        val alternateLyricSourceConnected = LyricSource.entries
            .filterNot { it == LyricSource.SPICY }
            .any { source ->
                val conn = arbiter?.connection(source)?.value
                conn == ProducerConnection.CONNECTED || conn == ProducerConnection.RECONNECTED
            }
        val capabilityAge = capability.reportedAtUtcMillis.takeIf { capability.hasReport && it > 0L }
            ?.let { (System.currentTimeMillis() - it).coerceAtLeast(0L) }
        val profileState = if (capability.hasReport) {
            capability.profileState.wireValue
        } else {
            "no_systemui_report"
        }
        val commonMetadata = collectCommonMetadata(context)
        val systemUiCallbackPresent = AodStateBridge.hasSystemUiCallback()
        val setupChecks = resolveHyperGlowSetupChecks(
            HyperGlowSetupInput(
                rootAccessStatus = captured.rootAccessStatus,
                capabilityReportPresent = capability.hasReport,
                systemUiCallbackPresent = systemUiCallbackPresent,
                profileState = profileState,
                anyProducerBridgePresent = producer != null,
                alternateLyricSourceConnected = alternateLyricSourceConnected,
                systemUiPackagePresent = commonMetadata.packageVersions["systemui"]?.present == true,
                xiaomiAodPackagePresent =
                    commonMetadata.packageVersions["xiaomi_aod"]?.present == true,
                spotifyPackagePresent = commonMetadata.packageVersions["spotify"]?.present == true
            )
        )
        return DiagnosticReportEnvelope(
            reportId = reportId,
            createdAtUtc = Instant.ofEpochMilli(finishedAtUtcMillis).toString(),
            category = category.wireValue,
            description = description,
            commonMetadata = commonMetadata,
            productMetadata = HyperGlowProductMetadata(
                capabilityReportProtocol = capability.protocolVersion,
                capabilityReportAgeMs = capabilityAge,
                profileState = profileState,
                rawSymbolProbes = capability.rawProbes.toSortedMap(),
                // wire 字段名 spotifyProducer* 是历史遗留（intake allowlist 已映射），
                // 值的语义是"当前活跃生产者"，不限于 Spicy；真实源名在
                // currentMediaEvidence.source 中给出。
                resolvedCapabilities = if (capability.hasReport) {
                    capability.capabilities.sorted()
                } else {
                    emptyList()
                },
                configuredSurfaces = linkedMapOf(
                    "aod" to aodConfigured,
                    "lockscreen" to lockscreenConfigured
                ),
                systemUiCallbackPresent = systemUiCallbackPresent,
                spotifyProducerStatePresent = producer != null,
                spotifyProducerSafeStatus = producer?.status ?: "absent",
                spotifyProducerPlaying = producer?.playing,
                spotifyProducerStateAgeMs = producerAge,
                diagnosticLoggingAvailable = BuildConfig.TRACE_LOGGING_AVAILABLE,
                diagnosticLoggingEnabled = DiagnosticLoggingPreferences.read(context),
                rootAccessStatus = captured.rootAccessStatus,
                currentMediaEvidence = producer?.takeIf { it.title.isNotBlank() }?.let {
                    val sourceName = producerSource?.name?.lowercase().orEmpty()
                    DiagnosticMediaEvidence(
                        present = true,
                        trackUri = it.trackUri.utf8Prefix(DiagnosticLimits.MEDIA_METADATA_BYTES),
                        title = it.title.utf8Prefix(DiagnosticLimits.MEDIA_METADATA_BYTES),
                        artist = it.artist.utf8Prefix(DiagnosticLimits.MEDIA_METADATA_BYTES),
                        album = it.album.utf8Prefix(DiagnosticLimits.MEDIA_METADATA_BYTES),
                        source = sourceName,
                        provider = when (producerSource) {
                            LyricSource.SPICY -> lyricDocument?.provider.orEmpty()
                            null -> ""
                            else -> sourceName
                        }.utf8Prefix(DiagnosticLimits.MEDIA_METADATA_BYTES),
                        language = it.language
                            .utf8Prefix(DiagnosticLimits.MEDIA_METADATA_BYTES),
                        timingType = when (producerSource) {
                            LyricSource.SPICY -> lyricDocument?.type.orEmpty()
                            null -> ""
                            // lyricKind 即时间轴类型：unsynced / line / syllable / none。
                            else -> it.lyricKind.name.lowercase()
                        }.utf8Prefix(DiagnosticLimits.MEDIA_METADATA_BYTES),
                        lineIndex = it.lineIndex.coerceIn(-1, 5_000),
                        originalLine = it.line.utf8Prefix(DiagnosticLimits.LYRIC_LINE_BYTES),
                        romanizedLine = it.romanizedLine
                            .utf8Prefix(DiagnosticLimits.LYRIC_LINE_BYTES),
                        translatedLine = it.translatedLine
                            .utf8Prefix(DiagnosticLimits.LYRIC_LINE_BYTES),
                        stateAgeMs = producerAge
                    )
                } ?: emptyMediaEvidence(),
                setupChecks = setupChecks
            ),
            capture = DiagnosticCaptureMetadata(
                outcome = captured.outcome,
                startedAtUtc = startedAtUtcMillis?.let { Instant.ofEpochMilli(it).toString() },
                finishedAtUtc = Instant.ofEpochMilli(finishedAtUtcMillis).toString(),
                previousDiagnosticLoggingEnabled = previousDiagnosticLogging,
                rootAccessStatus = captured.rootAccessStatus,
                commandFailures = captured.commandFailures,
                truncationFlags = captured.truncationFlags
            ),
            rawDiagnostics = DiagnosticRawData(
                diagnosticEventsAndLogs = captured.logs,
                crashExcerpt = captured.crashExcerpt,
                lsposedModuleLines = captured.lsposedLines,
                runtimeSettings = linkedMapOf(
                    "aodConfigured" to aodConfigured.toString(),
                    "lockscreenConfigured" to lockscreenConfigured.toString(),
                    "keepAodActive" to renderPreferences.keepAwake.toString(),
                    "keepAodActiveWithoutTimedLyrics" to
                        renderPreferences.keepAwakeUnsynced.toString(),
                    "keepAodActiveDurationMs" to renderPreferences.keepAwakeDurationMs.toString(),
                    "lockscreenKeepAwake" to renderPreferences.lockscreenKeepAwake.toString(),
                    "raiseToAod" to renderPreferences.raiseToAod.toString(),
                    "positionFollowing" to
                        renderPreferences.experimentalPositionFollowing.toString(),
                    "diagnosticLogging" to
                        DiagnosticLoggingPreferences.read(context).toString(),
                    "diagnosticLoggingDuringCapture" to
                        (startedAtUtcMillis != null && BuildConfig.TRACE_LOGGING_AVAILABLE).toString()
                )
            )
        ).also { DiagnosticReportCodec.encode(it) }
    }

    private fun collectCommonMetadata(context: Context): DiagnosticCommonMetadata =
        DiagnosticCommonMetadata(
            appVersionName = BuildConfig.VERSION_NAME.utf8Prefix(256),
            appVersionCode = BuildConfig.VERSION_CODE,
            buildType = BuildConfig.BUILD_TYPE.utf8Prefix(64),
            manufacturer = Build.MANUFACTURER.orEmpty().utf8Prefix(256),
            brand = Build.BRAND.orEmpty().utf8Prefix(256),
            model = Build.MODEL.orEmpty().utf8Prefix(256),
            device = Build.DEVICE.orEmpty().utf8Prefix(256),
            product = Build.PRODUCT.orEmpty().utf8Prefix(256),
            androidRelease = Build.VERSION.RELEASE.orEmpty().utf8Prefix(64),
            androidApi = Build.VERSION.SDK_INT,
            androidSecurityPatch = Build.VERSION.SECURITY_PATCH.orEmpty().utf8Prefix(64),
            androidDisplay = Build.DISPLAY.orEmpty().utf8Prefix(256),
            androidIncremental = Build.VERSION.INCREMENTAL.orEmpty().utf8Prefix(256),
            buildFingerprint = Build.FINGERPRINT.orEmpty().utf8Prefix(1_024),
            xiaomiOsProperties = XIAOMI_PROPERTY_KEYS.associateWith(::readProperty)
                .filterValues { it.isNotBlank() },
            locales = context.resources.configuration.locales.toLanguageTags()
                .split(',')
                .filter(String::isNotBlank)
                .take(8)
                .map { it.utf8Prefix(64) },
            packageVersions = linkedMapOf(
                "hyperglow" to packageVersion(context, context.packageName),
                "systemui" to packageVersion(context, "com.android.systemui"),
                "xiaomi_aod" to packageVersion(context, "com.miui.aod"),
                "spotify" to packageVersion(context, "com.spotify.music")
            )
        )

    @Suppress("DEPRECATION")
    private fun packageVersion(context: Context, packageName: String): DiagnosticPackageVersion =
        try {
            val info = context.packageManager.getPackageInfo(packageName, 0)
            DiagnosticPackageVersion(
                present = true,
                versionName = info.versionName.orEmpty().utf8Prefix(256),
                versionCode = info.longVersionCode
            )
        } catch (_: PackageManager.NameNotFoundException) {
            DiagnosticPackageVersion(false, "missing", 0L)
        } catch (_: Exception) {
            DiagnosticPackageVersion(false, "unavailable", 0L)
        }

    private fun readProperty(key: String): String = try {
        val process = ProcessBuilder("/system/bin/getprop", key).redirectErrorStream(true).start()
        if (!process.waitFor(PROPERTY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            ""
        } else {
            process.inputStream.bufferedReader().use { it.readLine().orEmpty() }.utf8Prefix(256)
        }
    } catch (_: Exception) {
        ""
    }

    private val XIAOMI_PROPERTY_KEYS = listOf(
        "ro.mi.os.version.name",
        "ro.mi.os.version.incremental",
        "ro.miui.ui.version.name",
        "ro.miui.ui.version.code",
        "ro.product.mod_device",
        "ro.miui.build.region"
    )
    private const val PROPERTY_TIMEOUT_MS = 500L

    private fun emptyMediaEvidence() = DiagnosticMediaEvidence(
        present = false,
        trackUri = "",
        title = "",
        artist = "",
        album = "",
        source = "",
        provider = "",
        language = "",
        timingType = "",
        lineIndex = -1,
        originalLine = "",
        romanizedLine = "",
        translatedLine = "",
        stateAgeMs = null
    )
}
