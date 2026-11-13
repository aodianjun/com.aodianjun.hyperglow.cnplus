package com.eza.hyperglow.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 辅助文字多选内容档的纯函数契约:历史四档原样保留(行为不变)、多选集合规范落地、
 * 和声状态位恒有且只有一个(见 [normalizeAuxMode] / [toggleAuxContent])。
 */
class AuxContentModeTest {

    @Test
    fun legacyModesPassThroughUnchanged() {
        listOf("Main only", "Transliteration", "Translation", "Both", "BackgroundVocal").forEach {
            assertEquals(it, normalizeAuxMode(it))
        }
        // 历史档语义:和声照常显示(不受多选的「和声」勾选影响),音标/翻译按档取用。
        assertTrue(auxHarmonyShown("Main only"))
        assertTrue(auxHarmonyShown("Translation"))
        assertTrue(auxHarmonyShown("BackgroundVocal"))
        assertFalse(auxShowsReading("Main only"))
        assertTrue(auxShowsReading("Both"))
        assertTrue(auxShowsTranslation("Both"))
    }

    @Test
    fun canonicalSetsNormalizeWithExactlyOneHarmonyState() {
        assertEquals(
            "Transliteration,BackgroundVocal",
            normalizeAuxMode("BackgroundVocal,Transliteration")
        )
        assertEquals(
            "Transliteration,Translation,BackgroundVocal",
            normalizeAuxMode("Translation,Transliteration,BackgroundVocal")
        )
        assertEquals("Translation,NoHarmony", normalizeAuxMode("NoHarmony,Translation"))
        // 和声关掉时不再显示;内容照常按集合取用。
        assertFalse(auxHarmonyShown("Translation,NoHarmony"))
        assertTrue(auxShowsTranslation("Translation,NoHarmony"))
        assertFalse(auxShowsReading("Translation,NoHarmony"))
        // 只有和声状态位也算规范值(此时没有内容可显示)。
        assertEquals("NoHarmony", normalizeAuxMode("NoHarmony"))
    }

    @Test
    fun unknownValuesFallBackToLegacyDefault() {
        assertEquals("Main only", normalizeAuxMode("auto"))
        assertEquals("Main only", normalizeAuxMode(null))
        assertEquals("Main only", normalizeAuxMode("  "))
        assertEquals("Main only", normalizeAuxMode("Romanization"))
    }

    @Test
    fun toggleExpandsLegacyThenFlipsOneContent() {
        // 历史档先展开成显式集合:取消「和声」= 显式关闭位,内容保留。
        assertEquals("Translation,NoHarmony", toggleAuxContent("Translation", "BackgroundVocal"))
        // 历史「两者」取消翻译 = 只留音标 + 和声。
        assertEquals(
            "Transliteration,BackgroundVocal",
            toggleAuxContent("Both", "Translation")
        )
        // 勾回和声 = 去掉关闭位。
        assertEquals(
            "Translation,BackgroundVocal",
            toggleAuxContent("Translation,NoHarmony", "BackgroundVocal")
        )
        // 词表外条目原样返回(防误写)。
        assertEquals("Both", toggleAuxContent("Both", "Roma"))
    }

    @Test
    fun harmonyAsAuxDecouplesFromDuetSwitchOnlyWhenChecked() {
        assertTrue(auxHarmonyAsAux("BackgroundVocal"))
        assertTrue(auxHarmonyAsAux("Translation,BackgroundVocal"))
        assertFalse(auxHarmonyAsAux("Translation"))
        assertFalse(auxHarmonyAsAux("Translation,NoHarmony"))
        // 勾选态判据与设置页多选/摘要共用。
        assertTrue(auxContentChecked("Translation,BackgroundVocal", "BackgroundVocal"))
        assertFalse(auxContentChecked("Translation,NoHarmony", "BackgroundVocal"))
        assertTrue(auxContentChecked("Both", "Transliteration"))
    }
}
