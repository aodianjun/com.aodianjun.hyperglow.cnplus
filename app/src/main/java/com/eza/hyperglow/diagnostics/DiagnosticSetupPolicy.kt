package com.eza.hyperglow.diagnostics

internal data class HyperGlowSetupInput(
    val rootAccessStatus: String,
    val capabilityReportPresent: Boolean,
    val systemUiCallbackPresent: Boolean,
    val profileState: String,
    /** 任一歌词生产者（Spicy EX / Lyricon / SuperLyric / LyricInfo）当前有活跃状态。 */
    val anyProducerBridgePresent: Boolean,
    /** 任一非 Spicy 歌词源已连接；此时歌词走中文音乐 App 路径，Spotify 不是必需宿主。 */
    val alternateLyricSourceConnected: Boolean,
    val systemUiPackagePresent: Boolean,
    val xiaomiAodPackagePresent: Boolean,
    val spotifyPackagePresent: Boolean
)

/**
 * States whose build runs what its symbols support. Only a build with no usable surface is
 * unsupported. `available` is the current state; the rest were produced by the retired version
 * comparison and stay here so a report from an older build still classifies (上游 6216fdc).
 * 与 MainActivity 的运行时门控(runtimeProfileAvailable/resolveModuleWorking)保持一致。
 */
private val RUNNABLE_PROFILE_STATES = setOf(
    "available",
    "verified_profile",
    "verified_profile_missing_symbols",
    "experimental_active"
)

internal fun resolveHyperGlowSetupChecks(input: HyperGlowSetupInput): HyperGlowSetupChecks {
    val failures = mutableListOf<String>()
    var hardFailure = false

    when (input.rootAccessStatus) {
        "granted" -> Unit
        "not_checked" -> failures += "root_access"
        else -> {
            failures += "root_access"
            hardFailure = true
        }
    }
    if (!input.capabilityReportPresent) {
        failures += "capability_report"
        hardFailure = true
    }
    if (!input.systemUiCallbackPresent) {
        failures += "systemui_hook"
        hardFailure = true
    }
    val profileSupported = input.profileState in RUNNABLE_PROFILE_STATES
    if (input.capabilityReportPresent && !profileSupported) {
        failures += "unsupported_profile"
        hardFailure = true
    }
    if (!input.systemUiPackagePresent) {
        failures += "systemui_package"
        hardFailure = true
    }
    if (!input.xiaomiAodPackagePresent) {
        failures += "xiaomi_aod_package"
        hardFailure = true
    }
    // Spotify 包是 Spicy EX 路径的宿主；Lyricon / SuperLyric / LyricInfo 运行在各自的
    // 音乐 App 中。任一非 Spicy 源已连接时，Spotify 缺席不再视为 setup 失败。
    if (!input.spotifyPackagePresent && !input.alternateLyricSourceConnected) {
        failures += "spotify_package"
        hardFailure = true
    }
    if (!input.anyProducerBridgePresent) failures += "producer_bridge"

    return HyperGlowSetupChecks(
        setupState = when {
            hardFailure -> "failed"
            failures.isNotEmpty() -> "warning"
            else -> "ready"
        },
        setupFailures = failures,
        rootAccessStatus = input.rootAccessStatus,
        capabilityReportPresent = input.capabilityReportPresent,
        systemUiHookActive = input.systemUiCallbackPresent,
        profileSupported = profileSupported,
        // wire 字段名 spotifyProducerBridgePresent 保留（intake allowlist 已映射），
        // 语义为"任一歌词生产者桥接存在"。
        spotifyProducerBridgePresent = input.anyProducerBridgePresent,
        requiredPackagesPresent = input.systemUiPackagePresent &&
            input.xiaomiAodPackagePresent &&
            (input.spotifyPackagePresent || input.alternateLyricSourceConnected)
    )
}
