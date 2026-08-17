package com.eza.hyperglow.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class UiLanguagePolicyTest {
    @Test
    fun mapsStoredApplicationLocalesToSupportedOptions() {
        assertEquals(UiLanguage.SYSTEM, resolveUiLanguage(""))
        assertEquals(UiLanguage.ENGLISH, resolveUiLanguage("en-US"))
        assertEquals(UiLanguage.SIMPLIFIED_CHINESE, resolveUiLanguage("zh-CN"))
        assertEquals(UiLanguage.TRADITIONAL_CHINESE, resolveUiLanguage("zh-TW"))
        assertEquals(UiLanguage.TRADITIONAL_CHINESE, resolveUiLanguage("zh-HK"))
        assertEquals(UiLanguage.TRADITIONAL_CHINESE, resolveUiLanguage("zh-Hant"))
        assertEquals(UiLanguage.SYSTEM, resolveUiLanguage("vi-VN"))
    }
}
