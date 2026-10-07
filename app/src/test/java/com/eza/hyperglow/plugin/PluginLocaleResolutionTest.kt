package com.eza.hyperglow.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 插件清单文案的语言解析（[localizedValue] / [chineseScriptOf]）。
 *
 * 修复的现场：插件清单按约定「默认值 = 插件母语文案，*Locales 只列需要覆盖的语言」
 * （仓库内插件只声明 zh-TW/en，简体靠默认值），而旧解析在主语言回退时直接取第一个 zh 条目
 * ——简体设备（zh-CN / zh-Hans*）因此整片显示成繁体（设置项标题、分组标题、对话框说明）。
 * 本组用例钉住新的优先级：完整匹配 → 同文种 → 文种中立 → 默认值。
 */
class PluginLocaleResolutionTest {

    private val overrides = linkedMapOf(
        "zh-TW" to "繁體",
        "en" to "English"
    )

    @Test
    fun exactTagWins() {
        assertEquals("繁體", localizedValue("zh-TW", overrides, "简体默认"))
        assertEquals("English", localizedValue("en", overrides, "简体默认"))
        assertEquals("English", localizedValue("en-US", overrides, "简体默认"))
    }

    /** 现场缺陷：简体设备不得被喂 zh-TW 覆盖，应回落默认值（插件母语文案）。 */
    @Test
    fun simplifiedDeviceDoesNotTakeTraditionalOverride() {
        assertEquals("简体默认", localizedValue("zh-CN", overrides, "简体默认"))
        assertEquals("简体默认", localizedValue("zh-Hans", overrides, "简体默认"))
        assertEquals("简体默认", localizedValue("zh-Hans-CN", overrides, "简体默认"))
    }

    @Test
    fun simplifiedDevicePicksSimplifiedEntryWhenDeclared() {
        val locales = linkedMapOf("zh-CN" to "简体", "zh-TW" to "繁體", "en" to "English")
        assertEquals("简体", localizedValue("zh-CN", locales, "fallback"))
        assertEquals("简体", localizedValue("zh-Hans-CN", locales, "fallback"))
        assertEquals("繁體", localizedValue("zh-TW", locales, "fallback"))
        assertEquals("繁體", localizedValue("zh-Hant-TW", locales, "fallback"))
    }

    /** 繁体设备 + 只声明了简体覆盖 → 默认值（而不是把简体当繁体设备的选择）。 */
    @Test
    fun traditionalDeviceFallsBackWhenOnlySimplifiedDeclared() {
        val locales = linkedMapOf("zh-CN" to "简体")
        assertEquals("默认（繁体）", localizedValue("zh-TW", locales, "默认（繁体）"))
    }

    /** 文种中立的条目（"zh"）优于显式相反文种的条目。 */
    @Test
    fun scriptNeutralEntryBeatsOppositeScript() {
        val locales = linkedMapOf("zh-TW" to "繁體", "zh" to "中文")
        assertEquals("中文", localizedValue("zh-CN", locales, "fallback"))
        assertEquals("繁體", localizedValue("zh-TW", locales, "fallback"))
    }

    /** 无从判断文种的标签（裸 "zh"）保持旧语义：取第一个同主语言条目（清单顺序即优先级）。 */
    @Test
    fun scriptlessDeviceKeepsInsertionOrderPriority() {
        val locales = linkedMapOf("zh-CN" to "简体", "zh-TW" to "繁體")
        assertEquals("简体", localizedValue("zh", locales, "fallback"))
    }

    @Test
    fun emptyLocalesUseFallback() {
        assertEquals("默认", localizedValue("zh-CN", emptyMap(), "默认"))
        assertEquals("默认", localizedValue("zh-CN", mapOf("ja" to "日本語"), "默认"))
    }

    @Test
    fun chineseScriptIsDerivedFromScriptOrRegion() {
        assertEquals("Hans", chineseScriptOf("zh-CN"))
        assertEquals("Hans", chineseScriptOf("zh-Hans"))
        assertEquals("Hans", chineseScriptOf("zh-Hans-CN"))
        assertEquals("Hans", chineseScriptOf("zh-SG"))
        assertEquals("Hans", chineseScriptOf("zh-MY"))
        assertEquals("Hant", chineseScriptOf("zh-TW"))
        assertEquals("Hant", chineseScriptOf("zh-Hant-TW"))
        assertEquals("Hant", chineseScriptOf("zh-HK"))
        assertEquals("Hant", chineseScriptOf("zh-MO"))
        assertNull(chineseScriptOf("zh"))
        assertNull(chineseScriptOf("zh-XX"))
        assertNull(chineseScriptOf("en-US"))
        assertNull(chineseScriptOf(""))
    }
}
