package com.eza.hyperglow.ui

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置预设的纯数据契约：分节识别与按勾选裁剪信封。
 * 快照/解码依赖 Android Context，由真机冒烟覆盖；这里锁住最容易被改坏的两件事——
 * 分节与备份信封键的映射、以及「未勾选分节绝不写进 payload」的裁剪语义。
 */
class ConfigPresetStoreTest {

    private fun preset(payload: JsonObject) = ConfigPreset(
        id = "preset-1",
        name = "Test",
        note = "",
        createdAt = 1L,
        payload = payload
    )

    private fun envelope(): JsonObject = buildJsonObject {
        put(ConfigBackupCodec.FORMAT_KEY, ConfigBackupCodec.FORMAT)
        put(ConfigBackupCodec.VERSION_KEY, ConfigBackupCodec.VERSION)
        put(
            ConfigBackupCodec.CUSTOMIZATION_KEY,
            buildJsonObject { put("id", "doc") }
        )
        put(
            ConfigBackupCodec.PREFERENCES_KEY,
            buildJsonObject { put("aod_enabled", true) }
        )
        put(ConfigBackupCodec.LYRIC_SOURCE_KEY, JsonPrimitive("SPICY"))
        put(ConfigBackupCodec.UI_LANGUAGE_KEY, JsonPrimitive("SYSTEM"))
        put(
            ConfigBackupCodec.APP_NAVIGATION_KEY,
            buildJsonObject { put(ConfigBackupCodec.PREDICTIVE_BACK_KEY, true) }
        )
    }

    @Test
    fun `sections follow the envelope keys present in the payload`() {
        val sections = preset(envelope()).sections

        assertTrue(ConfigPresetSection.APPEARANCE in sections)
        assertTrue(ConfigPresetSection.BEHAVIOR in sections)
        assertTrue(ConfigPresetSection.LYRIC_SOURCE in sections)
        assertTrue(ConfigPresetSection.INTERFACE in sections)
        assertFalse(ConfigPresetSection.APP_UI in sections)
        assertFalse(ConfigPresetSection.DIAGNOSTICS in sections)
        assertFalse(ConfigPresetSection.PLUGINS in sections)
    }

    @Test
    fun `filtered envelope keeps format version and only selected sections`() {
        val filtered = ConfigPresetStore.filteredEnvelope(
            preset = preset(envelope()),
            selected = setOf(ConfigPresetSection.APPEARANCE)
        )

        assertEquals(ConfigBackupCodec.FORMAT, (filtered[ConfigBackupCodec.FORMAT_KEY] as JsonPrimitive).content)
        assertTrue(filtered.containsKey(ConfigBackupCodec.CUSTOMIZATION_KEY))
        assertFalse(filtered.containsKey(ConfigBackupCodec.PREFERENCES_KEY))
        assertFalse(filtered.containsKey(ConfigBackupCodec.LYRIC_SOURCE_KEY))
        assertFalse(filtered.containsKey(ConfigBackupCodec.UI_LANGUAGE_KEY))
        assertFalse(filtered.containsKey(ConfigBackupCodec.APP_NAVIGATION_KEY))
    }

    @Test
    fun `filtered envelope keeps every key of a multi-key section`() {
        val filtered = ConfigPresetStore.filteredEnvelope(
            preset = preset(envelope()),
            selected = setOf(ConfigPresetSection.INTERFACE)
        )

        assertTrue(filtered.containsKey(ConfigBackupCodec.UI_LANGUAGE_KEY))
        assertTrue(filtered.containsKey(ConfigBackupCodec.APP_NAVIGATION_KEY))
        assertFalse(filtered.containsKey(ConfigBackupCodec.CUSTOMIZATION_KEY))
        assertFalse(filtered.containsKey(ConfigBackupCodec.PREFERENCES_KEY))
    }

    @Test
    fun `filtered envelope with no selection contains only the envelope header`() {
        val filtered = ConfigPresetStore.filteredEnvelope(
            preset = preset(envelope()),
            selected = emptySet()
        )

        assertEquals(2, filtered.size)
        assertTrue(filtered.containsKey(ConfigBackupCodec.FORMAT_KEY))
        assertTrue(filtered.containsKey(ConfigBackupCodec.VERSION_KEY))
    }

    @Test
    fun `unknown top-level keys become their own sections after the known ones`() {
        val payload = JsonObject(
            envelope().toMutableMap().apply {
                put("futureSettings", buildJsonObject { put("flag", true) })
                put("anotherNewSection", JsonPrimitive("x"))
            }
        )
        val sections = preset(payload).sections.toList()

        val future = sections.first { it.envelopeKeys == listOf("futureSettings") }
        assertFalse(future.isKnown)
        assertEquals("key:futureSettings", future.id)
        // 已知分节保持在前，未知键按字母序追加。
        assertEquals(
            listOf("key:anotherNewSection", "key:futureSettings"),
            sections.takeLast(2).map { it.id }
        )
        assertTrue(sections.indexOf(future) > sections.indexOf(ConfigPresetSection.LYRIC_SOURCE))
    }

    @Test
    fun `filtered envelope keeps unknown sections when selected`() {
        val payload = JsonObject(
            envelope().toMutableMap().apply {
                put("futureSettings", buildJsonObject { put("flag", true) })
            }
        )
        val source = preset(payload)
        val future = source.sections.first { it.envelopeKeys == listOf("futureSettings") }

        val filtered = ConfigPresetStore.filteredEnvelope(
            preset = source,
            selected = setOf(future)
        )

        assertTrue(filtered.containsKey("futureSettings"))
        assertFalse(filtered.containsKey(ConfigBackupCodec.CUSTOMIZATION_KEY))
        assertFalse(filtered.containsKey(ConfigBackupCodec.PREFERENCES_KEY))
        assertEquals(3, filtered.size)
    }

    @Test
    fun `empty object sections are ignored`() {
        val payload = JsonObject(
            envelope().toMutableMap().apply {
                put(ConfigBackupCodec.PLUGIN_SETTINGS_KEY, JsonObject(emptyMap()))
            }
        )

        assertFalse(ConfigPresetSection.PLUGINS in preset(payload).sections)
    }
}
