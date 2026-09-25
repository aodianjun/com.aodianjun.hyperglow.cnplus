package com.eza.hyperglow.ui

import android.content.Intent
import android.app.LocaleManager
import android.content.res.Configuration
import android.net.Uri
import android.os.LocaleList
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.R
import com.eza.hyperglow.aod.XiaomiRuntimeSupportState
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card

@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .padding(bottom = 8.dp)
            .fillMaxWidth()
    ) {
        Column { content() }
    }
}

internal fun String.isStaticClockPlacement(): Boolean =
    this == "static_top" || this == "static_bottom"

internal enum class SettingsTab {
    OVERVIEW,
    CONFIG
}

internal const val DIAGNOSTICS_DESTINATION = "__diagnostics__"
internal const val PLUGIN_DESTINATION = "__plugins__"
internal const val AOD_BEHAVIOR_DESTINATION = "__aod_behavior__"
internal const val GITHUB_URL = "https://github.com/amarinne/hyperglow"
internal const val GITHUB_CNPLUS_URL = "https://github.com/aodianjun/hyperglow_CNplus"
internal const val SPICY_EX_GITHUB_URL = "https://github.com/amarinne/spicy-ex/releases"

internal fun currentUiLanguage(context: android.content.Context): UiLanguage {
    val tags = context.getSystemService(LocaleManager::class.java)
        ?.applicationLocales
        ?.toLanguageTags()
        .orEmpty()
    return resolveUiLanguage(tags)
}

internal fun setUiLanguage(context: android.content.Context, language: UiLanguage) {
    context.getSystemService(LocaleManager::class.java)?.applicationLocales = when (language) {
        UiLanguage.SYSTEM -> LocaleList.getEmptyLocaleList()
        UiLanguage.ENGLISH -> LocaleList.forLanguageTags("en")
        UiLanguage.SIMPLIFIED_CHINESE -> LocaleList.forLanguageTags("zh-CN")
    }
}

internal fun uiLanguageLabel(context: android.content.Context, language: UiLanguage): String =
    context.getString(
        when (language) {
            UiLanguage.SYSTEM -> R.string.language_system_default
            UiLanguage.ENGLISH -> R.string.language_english
            UiLanguage.SIMPLIFIED_CHINESE -> R.string.language_simplified_chinese
        }
    )

internal fun englishInterfaceLanguageLabel(context: android.content.Context): String = runCatching {
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocales(LocaleList(Locale.ENGLISH))
    context.createConfigurationContext(configuration)
        .getString(R.string.setting_interface_language)
}.getOrElse {
    context.getString(R.string.setting_interface_language)
}

internal fun openExternalUrl(context: android.content.Context, url: String) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
    if (!opened) {
        Toast.makeText(context, context.getString(R.string.toast_no_link_handler), Toast.LENGTH_LONG).show()
    }
}

internal fun queryLatestReleaseTag(): String? {
    var connection: HttpURLConnection? = null
    return try {
        val url = URL("https://api.github.com/repos/aodianjun/com.aodianjun.hyperglow.cnplus/releases/latest")
        connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        if (connection.responseCode == HttpURLConnection.HTTP_OK) {
            val response = connection.inputStream.bufferedReader().readText()
            val json = Json.parseToJsonElement(response)
            json.jsonObject["tag_name"]?.jsonPrimitive?.content
        } else null
    } catch (e: Exception) {
        AppLog.e("MainActivity", "queryLatestReleaseTag failed", e)
        null
    } finally {
        connection?.disconnect()
    }
}

internal fun supportStateLabel(
    context: android.content.Context,
    state: XiaomiRuntimeSupportState,
    availableCapabilities: Int,
    totalCapabilities: Int
): String = when (state) {
    // 一个能跑的构建按「解析出多少能力」描述,而不是一个信心词(上游 6216fdc)。
    XiaomiRuntimeSupportState.AVAILABLE ->
        context.getString(R.string.status_available, availableCapabilities, totalCapabilities)
    else -> context.getString(
        when (state) {
            XiaomiRuntimeSupportState.NO_SYSTEM_UI_REPORT -> R.string.status_no_systemui_report
            XiaomiRuntimeSupportState.VERIFIED_PROFILE -> R.string.status_verified_profile
            XiaomiRuntimeSupportState.VERIFIED_PROFILE_MISSING_SYMBOLS ->
                R.string.status_verified_profile_missing_symbols
            XiaomiRuntimeSupportState.UNSUPPORTED_PROFILE -> R.string.status_unsupported_profile
            XiaomiRuntimeSupportState.EXPERIMENTAL_ELIGIBLE -> R.string.status_experimental_eligible
            else -> R.string.status_experimental_active
        }
    )
}

internal fun burnInPatternLabel(context: android.content.Context, value: String): String =
    context.getString(
        when (value) {
            "static_top" -> R.string.pattern_keep_top
            "six_zone" -> R.string.pattern_six_positions
            "four_corner" -> R.string.pattern_four_corners
            "vertical_swap" -> R.string.pattern_top_bottom
            else -> R.string.pattern_keep_bottom
        }
    )

internal fun aodMovementLabel(
    context: android.content.Context,
    positionFollowing: Boolean,
    pattern: String
): String = if (positionFollowing) {
    burnInPatternLabel(context, pattern)
} else {
    context.getString(R.string.option_follow_xiaomi)
}

internal fun burnInIntervalLabel(context: android.content.Context, value: Long): String =
    context.getString(
        when (value) {
            30_000L -> R.string.duration_30_seconds
            120_000L -> R.string.duration_2_minutes
            300_000L -> R.string.duration_5_minutes
            else -> R.string.duration_1_minute
        }
    )

internal fun pauseLingerLabel(context: android.content.Context, value: Long): String =
    context.getString(
        when (value) {
            0L -> R.string.duration_clear_immediately
            10_000L -> R.string.duration_10_seconds
            30_000L -> R.string.duration_30_seconds
            -1L -> R.string.duration_keep_indefinitely
            else -> R.string.duration_5_seconds
        }
    )

internal val AOD_ROTATION_MODES = listOf(
    com.eza.hyperglow.aod.AOD_ROTATION_MODE_AUTO,
    com.eza.hyperglow.aod.AOD_ROTATION_MODE_LANDSCAPE,
    com.eza.hyperglow.aod.AOD_ROTATION_MODE_LANDSCAPE_REVERSE
)

internal val AOD_ROTATION_SETTLES = listOf(0L, 500L, 1_000L, 2_000L, 5_000L, 10_000L)

internal fun aodRotationModeLabel(context: android.content.Context, mode: String): String =
    aodRotationModeOptionLabel(context, mode)

internal fun aodRotationModeOptionLabel(
    context: android.content.Context,
    mode: String
): String = context.getString(
    when (mode) {
        com.eza.hyperglow.aod.AOD_ROTATION_MODE_LANDSCAPE ->
            R.string.option_aod_rotation_landscape
        com.eza.hyperglow.aod.AOD_ROTATION_MODE_LANDSCAPE_REVERSE ->
            R.string.option_aod_rotation_landscape_reverse
        else -> R.string.option_aod_rotation_auto
    }
)

internal fun aodRotationSettleLabel(context: android.content.Context, value: Long): String =
    context.getString(R.string.rotation_settle_ms, value)

internal val BURN_IN_PATTERNS = listOf(
    "static_top",
    "static_bottom",
    "six_zone",
    "four_corner",
    "vertical_swap"
)

internal fun keepAwakeDurationLabel(context: android.content.Context, value: Long): String =
    context.getString(
        when (value) {
            300_000L -> R.string.duration_5_minutes
            600_000L -> R.string.duration_10_minutes
            1_800_000L -> R.string.duration_30_minutes
            3_600_000L -> R.string.duration_1_hour
            7_200_000L -> R.string.duration_2_hours
            else -> R.string.duration_indefinitely
        }
    )

internal val KEEP_AWAKE_DURATIONS = listOf(
    300_000L,
    600_000L,
    1_800_000L,
    3_600_000L,
    7_200_000L,
    -1L
)

internal val PAUSE_LINGER_OPTIONS = listOf(0L, 5_000L, 10_000L, 30_000L, -1L)

internal val BURN_IN_INTERVALS = listOf(30_000L, 60_000L, 120_000L, 300_000L)

/** 渲染刷新率上限档(issue #68 #12):0=跟随默认;60/90/120=可选上限。 */
internal val AOD_REFRESH_RATE_CAPS = listOf(0, 60, 90, 120)

internal fun aodRefreshRateCapLabel(context: android.content.Context, value: Int): String =
    if (value <= 0) {
        context.getString(R.string.refresh_rate_cap_follow)
    } else {
        context.getString(R.string.refresh_rate_cap_hz, value)
    }
