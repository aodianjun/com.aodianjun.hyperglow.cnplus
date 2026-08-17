package com.eza.hyperglow.ui

import java.util.Locale

internal enum class UiLanguage {
    SYSTEM,
    ENGLISH,
    SIMPLIFIED_CHINESE,
    TRADITIONAL_CHINESE
}

internal fun resolveUiLanguage(tags: String): UiLanguage {
    val first = tags.split(',').firstOrNull().orEmpty().trim()
    if (first.isEmpty()) return UiLanguage.SYSTEM
    val locale = Locale.forLanguageTag(first)
    return when (locale.language.lowercase(Locale.ROOT)) {
        "zh" -> {
            // 繁体：台湾、香港、澳门，或显式指定 Hant 脚本
            val region = locale.country.uppercase(Locale.ROOT)
            val script = locale.script
            if (region in setOf("TW", "HK", "MO") || script.equals("Hant", ignoreCase = true)) {
                UiLanguage.TRADITIONAL_CHINESE
            } else {
                UiLanguage.SIMPLIFIED_CHINESE
            }
        }
        "en" -> UiLanguage.ENGLISH
        else -> UiLanguage.SYSTEM
    }
}
