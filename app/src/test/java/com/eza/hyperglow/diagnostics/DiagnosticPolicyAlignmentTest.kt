package com.eza.hyperglow.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Policy alignment gate: the serialized diagnostic report fields must stay aligned with
 * `DIAGNOSTIC_DATA_POLICY.md`（「Included data / 包含的数据」清单）。
 *
 * 两个方向都钉住（任一侧漂移都会让 CI 变红）：
 * 1. 报告新增字段而 policy 未审 → 各层级键集 ⊆ 允许清单的断言失败；
 * 2. policy 承诺的数据类别从报告中消失 → 关键键必须存在的断言失败；
 *    同时 policy 文档仍须逐类描述已采集数据（锚点断言）。
 *
 * 允许清单镜像 `DiagnosticContract.kt` 中各 @Serializable 数据类；键是 wire 契约，
 * 改名/新增都必须同时更新本文件与 policy 文档（内嵌历史遗留键 spotifyProducer* 的
 * 语义见 DiagnosticReportFactory 内注释：值为「当前活跃生产者」，不限 Spotify）。
 *
 * sampleReport 构造与 DiagnosticContractTest 保持同构（故意复制而非共享：
 * 两个契约测试各自钉住自己的门，互不依赖对方的 fixture）。
 */
class DiagnosticPolicyAlignmentTest {

    @Test
    fun encodedReportKeysStayWithinPolicyAllowlist() {
        val root = encodedSampleReport()

        assertKeysWithin("top level", root.keys, TOP_LEVEL_KEYS)
        objectAt(root, "commonMetadata")?.let {
            assertKeysWithin("commonMetadata", it.keys, COMMON_METADATA_KEYS)
            objectAt(it, "packageVersions")?.values?.forEach { v ->
                assertKeysWithin(
                    "commonMetadata.packageVersions entry",
                    (v as? JsonObject)?.keys ?: emptySet(),
                    PACKAGE_VERSION_KEYS
                )
            }
        }
        objectAt(root, "productMetadata")?.let {
            assertKeysWithin("productMetadata", it.keys, PRODUCT_METADATA_KEYS)
            objectAt(it, "setupChecks")?.let { s ->
                assertKeysWithin("productMetadata.setupChecks", s.keys, SETUP_CHECKS_KEYS)
            }
            objectAt(it, "currentMediaEvidence")?.let { m ->
                assertKeysWithin("productMetadata.currentMediaEvidence", m.keys, MEDIA_EVIDENCE_KEYS)
            }
        }
        objectAt(root, "capture")?.let {
            assertKeysWithin("capture", it.keys, CAPTURE_KEYS)
        }
        objectAt(root, "rawDiagnostics")?.let {
            assertKeysWithin("rawDiagnostics", it.keys, RAW_DIAGNOSTICS_KEYS)
        }
    }

    @Test
    fun policyPromisedDataCategoriesRemainPresent() {
        val root = encodedSampleReport()
        val topLevel = root.keys
        val product = (root["productMetadata"] as? JsonObject)?.keys ?: emptySet()
        val raw = (root["rawDiagnostics"] as? JsonObject)?.keys ?: emptySet()

        // policy「Included data」逐类对应：描述/类别、元数据、能力/符号、运行时设置、
        // 媒体证据（歌名/艺术家/专辑/track URI/提供方/语言/时间轴）、引导采集日志/崩溃/LSPosed。
        val requiredTop = listOf("reportId", "category", "description", "commonMetadata", "productMetadata")
        val requiredProduct = listOf(
            "profileState", "rawSymbolProbes", "resolvedCapabilities", "currentMediaEvidence",
            "setupChecks"
        )
        val requiredRaw = listOf(
            "diagnosticEventsAndLogs", "crashExcerpt", "lsposedModuleLines", "runtimeSettings"
        )
        val missing = requiredTop.filterNot { it in topLevel } +
            requiredProduct.filterNot { it in product } +
            requiredRaw.filterNot { it in raw }
        assertTrue("policy-promised data categories disappeared from the report: $missing", missing.isEmpty())
    }

    @Test
    fun policyDocumentStillDescribesEveryCollectedCategory() {
        val doc = locatePolicyDocument() ?: return
        val text = doc.readText()
        val anchors = listOf(
            "description and chosen category",
            "capability/symbol results",
            "allowlisted runtime settings",
            "track URI",
            "provider/language/timing",
            "transliterated/translated",
            "guided capture",
            "crash excerpts",
            "LSPosed lines",
            "process snapshot"
        )
        val missing = anchors.filterNot { text.contains(it) }
        assertTrue(
            "DIAGNOSTIC_DATA_POLICY.md no longer describes collected data categories: $missing",
            missing.isEmpty()
        )
    }

    private fun locatePolicyDocument(): File? {
        val direct = File("DIAGNOSTIC_DATA_POLICY.md")
        if (direct.isFile) return direct
        val parent = File("../DIAGNOSTIC_DATA_POLICY.md")
        assumeTrue("DIAGNOSTIC_DATA_POLICY.md is visible to unit tests", parent.isFile)
        return parent
    }

    private fun encodedSampleReport(): JsonObject =
        Json.parseToJsonElement(DiagnosticReportCodec.encode(sampleReport())).jsonObject

    private fun objectAt(root: JsonObject, vararg path: String): JsonObject? {
        var current: JsonElement = root
        for (segment in path) {
            val obj = current as? JsonObject ?: return null
            current = obj[segment] ?: return null
        }
        return current as? JsonObject
    }

    private fun assertKeysWithin(area: String, observed: Set<String>, allowed: Set<String>) {
        val unexpected = observed.filterNot { it in allowed }
        assertTrue(
            "new diagnostic report fields at $area need a DIAGNOSTIC_DATA_POLICY.md review first: $unexpected",
            unexpected.isEmpty()
        )
    }

    private fun sampleReport() = DiagnosticReportEnvelope(
        reportId = "R1-00000000000000000000000000",
        createdAtUtc = "2026-08-01T00:00:00Z",
        category = HyperGlowReportCategory.COMPATIBILITY.wireValue,
        description = "Policy alignment fixture",
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

    private val TOP_LEVEL_KEYS = setOf(
        "envelopeVersion", "reportId", "product", "productReportVersion", "createdAtUtc",
        "category", "description", "commonMetadata", "productMetadata", "capture", "rawDiagnostics"
    )

    private val COMMON_METADATA_KEYS = setOf(
        "appVersionName", "appVersionCode", "buildType", "manufacturer", "brand", "model",
        "device", "product", "androidRelease", "androidApi", "androidSecurityPatch",
        "androidDisplay", "androidIncremental", "buildFingerprint", "xiaomiOsProperties",
        "locales", "packageVersions"
    )

    private val PACKAGE_VERSION_KEYS = setOf("present", "versionName", "versionCode")

    private val PRODUCT_METADATA_KEYS = setOf(
        "capabilityReportProtocol", "capabilityReportAgeMs", "profileState", "rawSymbolProbes",
        "resolvedCapabilities", "configuredSurfaces", "systemUiCallbackPresent",
        "spotifyProducerStatePresent", "spotifyProducerSafeStatus", "spotifyProducerPlaying",
        "spotifyProducerStateAgeMs", "diagnosticLoggingAvailable", "diagnosticLoggingEnabled",
        "rootAccessStatus", "currentMediaEvidence", "setupChecks"
    )

    private val SETUP_CHECKS_KEYS = setOf(
        "setupState", "setupFailures", "rootAccessStatus", "capabilityReportPresent",
        "systemUiHookActive", "profileSupported", "spotifyProducerBridgePresent",
        "requiredPackagesPresent"
    )

    private val MEDIA_EVIDENCE_KEYS = setOf(
        "present", "trackUri", "title", "artist", "album", "source", "provider", "language",
        "timingType", "lineIndex", "originalLine", "romanizedLine", "translatedLine", "stateAgeMs"
    )

    private val CAPTURE_KEYS = setOf(
        "outcome", "startedAtUtc", "finishedAtUtc", "previousDiagnosticLoggingEnabled",
        "rootAccessStatus", "commandFailures", "truncationFlags"
    )

    private val RAW_DIAGNOSTICS_KEYS = setOf(
        "diagnosticEventsAndLogs", "crashExcerpt", "lsposedModuleLines", "runtimeSettings"
    )
}
