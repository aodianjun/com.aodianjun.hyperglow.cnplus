package com.eza.hyperglow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 演示歌词跟随界面语言的钉子(LyricAppearanceSection.kt 的 demoLines / demoTrack):
 * English 走《Take My Hand》,其余(跟随系统/简体中文)走中文演示曲。判据必须与「界面语言」
 * 设置同一来源([UiLanguage]),系统语言为英文但用户显式选了「简体中文」时以用户选择为准
 * —— 过去演示歌词是编译期常量,语言切换对它无影响,这里防止再退回那一形态。
 */
class PreviewDemoLinesTest {

    @Test
    fun englishInterfaceUsesEnglishDemoTrack() {
        val lines = demoLines(UiLanguage.ENGLISH)
        assertTrue(
            "English demo must carry the English track lines, got: ${lines.map { it.original }}",
            lines.any { it.original.contains("Take my hand now", ignoreCase = true) }
        )
        val track = demoTrack(UiLanguage.ENGLISH)
        assertEquals("Take My Hand", track.title)
        assertEquals("DAISHI DANCE, Cécile Corbel", track.artist)
    }

    @Test
    fun chineseAndSystemKeepTheChineseDemoTrack() {
        // 跟随系统(SYSTEM)与显式简体中文都保留中文演示曲——只有显式 English 才切换。
        assertEquals(demoLines(UiLanguage.SIMPLIFIED_CHINESE), demoLines(UiLanguage.SYSTEM))
        assertEquals("蝴蝶", demoTrack(UiLanguage.SIMPLIFIED_CHINESE).title)
        assertEquals("蝴蝶", demoTrack(UiLanguage.SYSTEM).title)
        assertTrue(
            demoLines(UiLanguage.SIMPLIFIED_CHINESE).any { it.original.contains("蝴蝶") }
        )
    }

    @Test
    fun englishDemoLinesCarryNoFakeAuxiliaryText() {
        // 英文曲没有拼音/中译:辅助文字字段留空,避免「辅助文字」开关的预览被假内容撑出
        // 并不存在的第二/第四行。
        demoLines(UiLanguage.ENGLISH).forEach { line ->
            assertTrue("romanized must stay empty: ${line.original}", line.romanized.isEmpty())
            assertTrue("translated must stay empty: ${line.original}", line.translated.isEmpty())
            assertTrue("ruby must stay empty: ${line.original}", line.ruby.isEmpty())
        }
    }

    @Test
    fun bothDemoLineSetsAreNonEmptyAndSizedForCycling() {
        // 演示快照按 index 循环取下一行,空表会让预览整块空白。
        assertTrue(demoLines(UiLanguage.ENGLISH).size >= 2)
        assertTrue(demoLines(UiLanguage.SIMPLIFIED_CHINESE).size >= 2)
    }
}
