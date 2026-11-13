package com.example.hyperglow.aitranslationwords

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LanguageDetectTest {

    @Test
    fun detectsChineseWithoutKana() {
        assertEquals(Lang.ZH, LanguageDetect.detect("你说你来到这世界的那天"))
        assertEquals(Lang.ZH, LanguageDetect.detect("蝴蝶 (Cocoon Broken)"))
    }

    @Test
    fun detectsJapaneseWhenKanaPresent() {
        assertEquals(Lang.JA, LanguageDetect.detect("君の名前は"))
        assertEquals(Lang.JA, LanguageDetect.detect("今日はいい天気ですね"))
    }

    @Test
    fun detectsKorean() {
        assertEquals(Lang.KO, LanguageDetect.detect("안녕하세요"))
    }

    @Test
    fun detectsEnglish() {
        assertEquals(Lang.EN, LanguageDetect.detect("Take me hand"))
    }

    @Test
    fun detectsSpanishBySpecialMarks() {
        assertEquals(Lang.ES, LanguageDetect.detect("¿Cómo estás, corazón?"))
    }

    @Test
    fun symbolsAndDigitsAreOther() {
        assertEquals(Lang.OTHER, LanguageDetect.detect("123 !!! …---"))
        assertEquals(Lang.OTHER, LanguageDetect.detect(""))
    }

    @Test
    fun normalizeTargetVariants() {
        assertEquals(Lang.ZH, LanguageDetect.normalizeTarget("中文"))
        assertEquals(Lang.ZH, LanguageDetect.normalizeTarget("Chinese"))
        assertEquals(Lang.ZH, LanguageDetect.normalizeTarget("zh-CN"))
        assertEquals(Lang.EN, LanguageDetect.normalizeTarget("英文"))
        assertEquals(Lang.EN, LanguageDetect.normalizeTarget("English"))
        assertEquals(Lang.JA, LanguageDetect.normalizeTarget("日本語"))
        assertEquals(Lang.KO, LanguageDetect.normalizeTarget("한국어"))
        assertEquals(Lang.ES, LanguageDetect.normalizeTarget("Español"))
        // 识别不出（如法语）返回 null：仍会原样进 prompt，只是行级跳过判断不启用
        assertNull(LanguageDetect.normalizeTarget("法语"))
        assertNull(LanguageDetect.normalizeTarget("  "))
    }

    @Test
    fun skippableRules() {
        // 空白与纯数字/符号
        assertTrue(LanguageDetect.isSkippable("", Lang.ZH))
        assertTrue(LanguageDetect.isSkippable("   ", Lang.ZH))
        assertTrue(LanguageDetect.isSkippable("123", null))
        assertTrue(LanguageDetect.isSkippable("♪ ♪ ♪", null))
        assertTrue(LanguageDetect.isSkippable("—— · ——", null))
        // 已是目标语言
        assertTrue(LanguageDetect.isSkippable("你好世界", Lang.ZH))
        assertFalse(LanguageDetect.isSkippable("hello world", Lang.ZH))
        // 目标语言识别不出时不按语言跳过（只按空白/符号）
        assertFalse(LanguageDetect.isSkippable("hello world", null))
        assertFalse(LanguageDetect.isSkippable("你好世界", null))
    }
}
