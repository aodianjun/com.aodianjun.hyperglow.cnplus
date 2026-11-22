package com.eza.hyperglow.ui

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 云端预设的纯数据契约：清单解析、合并、云端→本地映射。
 * 网络与 GitHub 资产替换由真机冒烟覆盖；这里锁住最容易在重构中漂移的形状与覆盖语义。
 */
class PresetHubTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun payload(marker: String = "doc"): JsonObject = buildJsonObject {
        put("format", "hyperglow-config-backup")
        put("version", 1)
        put("customization", buildJsonObject { put("id", marker) })
    }

    private fun cloudPreset(
        id: String = "cloud-1",
        name: String = "Night",
        author: String = "alice",
        createdAt: Long = 100L,
        updatedAt: Long = 100L
    ) = CloudPreset(
        id = id,
        name = name,
        note = "note",
        author = author,
        createdAt = createdAt,
        updatedAt = updatedAt,
        payload = payload()
    )

    @Test
    fun `manifest round-trips with nested payload`() {
        val manifest = CloudPresetManifest(updatedAt = 42L, presets = listOf(cloudPreset()))
        val decoded = parseCloudManifest(json.encodeToString(manifest))

        assertNotNull(decoded)
        assertEquals(42L, decoded!!.updatedAt)
        assertEquals(1, decoded.presets.size)
        assertEquals("alice", decoded.presets.first().author)
        assertEquals("doc", decoded.presets.first().payload["customization"]
            ?.let { (it as JsonObject)["id"]?.toString()?.trim('"') })
    }

    @Test
    fun `manifest rejects wrong format or version`() {
        val wrongFormat = """{"format":"other","version":1,"presets":[]}"""
        val wrongVersion = """{"format":"$PRESET_HUB_FORMAT","version":99,"presets":[]}"""
        val notJson = "not json"

        assertNull(parseCloudManifest(wrongFormat))
        assertNull(parseCloudManifest(wrongVersion))
        assertNull(parseCloudManifest(notJson))
    }

    @Test
    fun `manifest drops entries without id name or payload`() {
        val raw = json.encodeToString(
            CloudPresetManifest(
                presets = listOf(
                    cloudPreset(),
                    cloudPreset(id = ""),
                    cloudPreset(name = " "),
                    cloudPreset(id = "cloud-2").copy(payload = JsonObject(emptyMap()))
                )
            )
        )

        val decoded = parseCloudManifest(raw)
        assertNotNull(decoded)
        assertEquals(listOf("cloud-1"), decoded!!.presets.map { it.id })
    }

    @Test
    fun `merge replaces same id but keeps original author and createdAt`() {
        val manifest = CloudPresetManifest(
            updatedAt = 10L,
            presets = listOf(cloudPreset(updatedAt = 10L))
        )
        val local = ConfigPreset(
            id = "cloud-1",
            name = "Night v2",
            note = "new",
            createdAt = 999L,
            payload = payload("doc-v2")
        )

        val merged = mergeCloudPreset(manifest, local, author = "bob", now = 500L)
        val entry = merged.presets.single()

        assertEquals("cloud-1", entry.id)
        assertEquals("Night v2", entry.name)
        assertEquals("alice", entry.author)
        assertEquals(100L, entry.createdAt)
        assertEquals(500L, entry.updatedAt)
        assertEquals(500L, merged.updatedAt)
        assertEquals("doc-v2", (entry.payload["customization"] as JsonObject)["id"]
            ?.toString()?.trim('"'))
    }

    @Test
    fun `merge adds a new preset and sorts newest first`() {
        val manifest = CloudPresetManifest(
            presets = listOf(cloudPreset(id = "old", updatedAt = 10L))
        )
        val local = ConfigPreset(
            id = "new",
            name = "New",
            note = "",
            createdAt = 1L,
            payload = payload("new")
        )

        val merged = mergeCloudPreset(manifest, local, author = "bob", now = 500L)

        assertEquals(listOf("new", "old"), merged.presets.map { it.id })
        assertEquals("bob", merged.presets.first().author)
        assertEquals(1L, merged.presets.first().createdAt)
    }

    @Test
    fun `merge strips cloud prefix from an uploaded cloud-derived id`() {
        val local = ConfigPreset(
            id = CLOUD_PRESET_PREFIX + "abc",
            name = "Copy",
            note = "",
            createdAt = 1L,
            payload = payload()
        )

        val merged = mergeCloudPreset(CloudPresetManifest(), local, author = "bob", now = 5L)

        assertEquals("abc", merged.presets.single().id)
    }

    @Test
    fun `cloud preset maps to a routable config preset and back`() {
        val preset = cloudPreset().toConfigPreset()

        assertEquals("cloud:cloud-1", preset.id)
        assertEquals("alice", preset.author)
        assertTrue(isCloudPresetId(preset.id))
        assertEquals("cloud-1", preset.id.removePrefix(CLOUD_PRESET_PREFIX))
    }

    @Test
    fun `merge preserves the anonymous hub owner hash`() {
        val manifest = CloudPresetManifest(
            presets = listOf(cloudPreset().copy(owner = "owner-hash"))
        )
        val local = ConfigPreset(
            id = "cloud-1",
            name = "Updated",
            note = "",
            createdAt = 1L,
            payload = payload("updated")
        )

        val merged = mergeCloudPreset(manifest, local, author = "bob", now = 500L)

        assertEquals("owner-hash", merged.presets.single().owner)
    }

    @Test
    fun `anonymous upload url enables upload without a github token`() {
        val config = PresetHubConfig(anonymousUrl = "https://hub.example.workers.dev")

        assertTrue(config.canUpload)
        assertTrue(config.anonymousUrlValid)
        assertFalse(PresetHubConfig(anonymousUrl = "http://hub.example").canUpload)
        assertFalse(PresetHubConfig(anonymousUrl = "").canUpload)
        assertTrue(PresetHubConfig(token = "ghp_test").canUpload)
    }

    @Test
    fun `local preset json without author still decodes`() {
        val raw = """{"id":"x","name":"n","note":"","createdAt":1,"payload":{}}"""
        val decoded = json.decodeFromString<ConfigPreset>(raw)

        assertEquals("", decoded.author)
    }

    @Test
    fun `repository validation accepts owner slash repo only`() {
        assertTrue(isValidPresetHubRepo("aodianjun/com.aodianjun.hyperglow.cnplus"))
        assertTrue(isValidPresetHubRepo("a_b.c/d-e.f"))
        assertFalse(isValidPresetHubRepo("aodianjun"))
        assertFalse(isValidPresetHubRepo("aodianjun/"))
        assertFalse(isValidPresetHubRepo("/repo"))
        assertFalse(isValidPresetHubRepo("aodianjun/re po"))
        assertFalse(isValidPresetHubRepo("owner/repo\nextra"))
    }
}
