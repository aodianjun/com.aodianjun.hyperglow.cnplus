package com.eza.hyperglow.ui

import com.eza.hyperglow.plugin.PluginSettingData
import com.eza.hyperglow.plugin.PluginSettingGroupData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页分组拆分(纯函数):生态 manifest(hyperlyric.ai.translation)声明「基础设置(default)/
 * 高级设置(generation)」两组,基础项不写 group 字段——这些项必须归入第一个组而不是被丢弃,
 * 否则分组标题与基础项在设置页上消失。
 */
class PluginSettingsGroupingTest {

    private fun setting(key: String, group: String? = null) =
        PluginSettingData(key = key, title = key, group = group)

    private fun group(id: String, title: String) =
        PluginSettingGroupData(id = id, title = title)

    @Test
    fun noDeclaredGroupsFlattensEverything() {
        val settings = listOf(setting("a"), setting("b", "x"))
        val (ungrouped, grouped) = splitSettingsByGroup(settings, emptyList())
        assertEquals(listOf("a", "b"), ungrouped.map { it.key })
        assertTrue(grouped.isEmpty())
    }

    @Test
    fun unlabelledSettingsJoinTheFirstDeclaredGroup() {
        // 生态 manifest 形状:基础项无 group,高级项显式标注。
        val settings = listOf(
            setting("enabled"),
            setting("api_key"),
            setting("temperature", "generation"),
            setting("top_p", "generation"),
        )
        val groups = listOf(group("default", "基础设置"), group("generation", "高级设置"))
        val (ungrouped, grouped) = splitSettingsByGroup(settings, groups)
        assertTrue(ungrouped.isEmpty())
        assertEquals(2, grouped.size)
        assertEquals("default", grouped[0].first.id)
        assertEquals(listOf("enabled", "api_key"), grouped[0].second.map { it.key })
        assertEquals("generation", grouped[1].first.id)
        assertEquals(listOf("temperature", "top_p"), grouped[1].second.map { it.key })
    }

    @Test
    fun unknownGroupReferenceFallsBackToFirstGroupInsteadOfDropping() {
        val settings = listOf(setting("a", "missing_group"), setting("b"))
        val groups = listOf(group("default", "基础设置"))
        val (ungrouped, grouped) = splitSettingsByGroup(settings, groups)
        assertTrue(ungrouped.isEmpty())
        assertEquals(listOf("a", "b"), grouped.single().second.map { it.key })
    }

    @Test
    fun explicitlyLabelledFirstGroupKeepsOrderWithUnlabelled() {
        val settings = listOf(setting("x", "default"), setting("y"), setting("z", "generation"))
        val groups = listOf(group("default", "基础设置"), group("generation", "高级设置"))
        val (_, grouped) = splitSettingsByGroup(settings, groups)
        assertEquals(listOf("x", "y"), grouped[0].second.map { it.key })
        assertEquals(listOf("z"), grouped[1].second.map { it.key })
    }

    @Test
    fun emptyGroupIsSkipped() {
        val settings = listOf(setting("a"), setting("b", "generation"))
        val groups = listOf(group("default", "基础设置"), group("empty", "空组"), group("generation", "高级设置"))
        val (_, grouped) = splitSettingsByGroup(settings, groups)
        assertEquals(2, grouped.size)
        assertEquals(listOf("default", "generation"), grouped.map { it.first.id })
    }
}
