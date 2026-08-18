package com.eza.hyperglow.ui

import android.app.LocaleManager
import android.content.Intent
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
import com.eza.hyperglow.R
import com.eza.hyperglow.root.aod.metadataWidgetHeightDp
import com.eza.hyperglow.root.surface.PlacementEngine
import com.eza.hyperglow.root.surface.PlacementEnvironment
import com.eza.hyperglow.root.surface.PlacementRect
import com.eza.hyperglow.root.surface.ResolvedPlacement
import com.eza.hyperglow.root.surface.WidgetMeasurement
import top.yukonga.miuix.kmp.basic.Card
import java.util.Locale

/** 通用 UI 组件与工具:设置卡片、界面语言切换、外链跳转与歌词预览布局解析。 */

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
        UiLanguage.TRADITIONAL_CHINESE -> LocaleList.forLanguageTags("zh-TW")
    }
}

internal fun uiLanguageLabel(context: android.content.Context, language: UiLanguage): String =
    context.getString(
        when (language) {
            UiLanguage.SYSTEM -> R.string.language_system_default
            UiLanguage.ENGLISH -> R.string.language_english
            UiLanguage.SIMPLIFIED_CHINESE -> R.string.language_simplified_chinese
            UiLanguage.TRADITIONAL_CHINESE -> R.string.language_traditional_chinese
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

internal fun resolvePreviewPlacement(
    profile: com.eza.hyperglow.customization.CompiledSurfaceProfile,
    scenario: String,
    width: Float,
    height: Float
): ResolvedPlacement {
    val environment = previewEnvironment(scenario, width, height)
    val metadataHeight = if (profile.metadataVisible &&
        profile.widgets.any { it.type == "metadata" }
    ) {
        height * 0.10f *
            (metadataWidgetHeightDp(profile.metadataSizePercent) / metadataWidgetHeightDp(100))
    } else 0f
    val progressHeight = if (profile.widgets.any { it.type == "media_progress" }) {
        height * 0.05f
    } else {
        0f
    }
    val desiredHeight = height * profile.maxHeightFraction
    val minimumLyricHeight = height * 0.22f
    val measurements = profile.widgets.mapNotNull { widget ->
        when (widget.type) {
            "lyrics" -> WidgetMeasurement(
                widget,
                (desiredHeight - metadataHeight - progressHeight)
                    .coerceAtLeast(minimumLyricHeight)
            )
            "metadata" -> WidgetMeasurement(widget, metadataHeight)
            "media_progress" -> WidgetMeasurement(widget, progressHeight)
            else -> null
        }
    }
    return PlacementEngine.resolve(profile, environment, measurements, minimumLyricHeight)
}

internal fun previewEnvironment(
    scenario: String,
    width: Float,
    height: Float
): PlacementEnvironment = PlacementEnvironment(
    safeCanvas = PlacementRect(0f, 0f, width, height),
    stockClockBottom = when (scenario) {
        "Full AOD" -> height * 0.18f
        "Normal AOD", "FOD safe region" -> height * 0.34f
        else -> height * 0.26f
    },
    bottomReserveTop = when (scenario) {
        "FOD safe region" -> height * 0.70f
        else -> height * 0.90f
    },
    notificationTop = if (scenario == "Lockscreen · notifications") height * 0.62f else null
)
