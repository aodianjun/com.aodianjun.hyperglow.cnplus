package com.eza.hyperglow.ui

import com.eza.hyperglow.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索匹配是纯逻辑:这里直接构造 [GlobalSearchDocument],不加载 Android 资源,
 * 因此不需要 Robolectric 的 `isIncludeAndroidResources`(打开它会拖慢并影响插件运行时测试)。
 */
class GlobalSearchTest {

    private fun doc(
        title: String,
        summary: String? = null,
        section: String = "状态",
        tab: String = "状态",
        keywords: String = "",
        requiresExperimental: Boolean = false,
        anchor: String? = null
    ): GlobalSearchDocument = GlobalSearchDocument(
        entry = GlobalSearchEntry(
            titleRes = 0,
            sectionLabelRes = 0,
            tab = SettingsTab.STATUS,
            keywords = keywords,
            anchor = anchor,
            requiresExperimental = requiresExperimental
        ),
        title = title,
        summary = summary,
        sectionLabel = section,
        tabLabel = tab
    )

    private val sourceDoc = doc(
        title = "歌词来源",
        summary = "选择歌词来源",
        section = "歌词源",
        keywords = "歌词 来源 选源 lyric source",
        anchor = GlobalSearchAnchor.STATUS_SOURCE
    )

    @Test
    fun exactTitleStillMatches() {
        val results = filterGlobalSearchDocuments(listOf(sourceDoc), "歌词来源")
        assertTrue(results.any { it.anchor == GlobalSearchAnchor.STATUS_SOURCE })
    }

    @Test
    fun subsequenceFuzzyMatchFindsSkippedCharacters() {
        // 「歌源」不是连续子串,但按顺序命中「歌词来源」。
        val results = filterGlobalSearchDocuments(listOf(sourceDoc), "歌源")
        assertTrue(results.any { it.anchor == GlobalSearchAnchor.STATUS_SOURCE })
    }

    @Test
    fun twoLetterAsciiQueryDoesNotSpraySubsequenceHits() {
        // 「sg」是 settings 的跳字子序列;旧实现会把所有带 settings 的条目都捞回来。
        // 两字母英文只做连续子串匹配,这里没有任何文案含 "sg",应为空。
        val settingsDoc = doc(title = "设置", keywords = "settings")
        assertTrue(filterGlobalSearchDocuments(listOf(settingsDoc), "sg").isEmpty())
    }

    @Test
    fun singleChineseCharacterStillMatchesContiguousText() {
        assertTrue(filterGlobalSearchDocuments(listOf(sourceDoc), "歌").isNotEmpty())
    }

    @Test
    fun multiTokenQueryRequiresEveryToken() {
        val results = filterGlobalSearchDocuments(listOf(sourceDoc), "歌词 来源")
        assertTrue(results.any { it.anchor == GlobalSearchAnchor.STATUS_SOURCE })
        assertTrue(filterGlobalSearchDocuments(listOf(sourceDoc), "歌词 zzzzz").isEmpty())
    }

    @Test
    fun typoInEnglishKeywordStillMatches() {
        // spotifx → spotify:替换一个字符,不是跳字子序列,只能靠编辑距离命中。
        val spotifyDoc = doc(title = "Spotify", keywords = "spotify")
        assertTrue(filterGlobalSearchDocuments(listOf(spotifyDoc), "spotifx").isNotEmpty())
    }

    @Test
    fun completionsReturnTitlesContainingInput() {
        val titles = listOf("歌词来源", "歌词增强", "实时状态")
        val completions = globalSearchCompletionsFromTitles(titles, "歌词")
        assertTrue(completions.isNotEmpty())
        assertTrue(completions.all { it.contains("歌词") })
    }

    @Test
    fun forceProviderIsNotMarkedExperimental() {
        // 「强制歌词提供者」在设置页始终渲染,索引不能把它当实验模式专属条目,
        // 否则会出现「按钮在、却搜不到」的反向不一致。
        val forceEntry = GLOBAL_SEARCH_ENTRIES.first {
            it.titleRes == R.string.setting_force_lyric_provider
        }
        assertFalse(forceEntry.requiresExperimental)

        val forceDoc = doc(
            title = "强制歌词提供者",
            keywords = "强制 歌词 提供者 force provider",
            requiresExperimental = false
        )
        assertTrue(
            filterGlobalSearchDocuments(
                listOf(forceDoc),
                "强制",
                experimentalEligible = false
            ).isNotEmpty()
        )
    }

    @Test
    fun experimentalEntriesAreHiddenWhenIneligible() {
        val experimentalDoc = doc(title = "实验模式", requiresExperimental = true)
        val eligible = filterGlobalSearchDocuments(
            listOf(experimentalDoc),
            "实验",
            experimentalEligible = true
        )
        assertTrue(eligible.any { it.requiresExperimental })
        val ineligible = filterGlobalSearchDocuments(
            listOf(experimentalDoc),
            "实验",
            experimentalEligible = false
        )
        assertTrue(ineligible.none { it.requiresExperimental })
    }

    @Test
    fun indexCoversAppearanceRowsAndAbout() {
        // 外观编辑器逐项:下拉项与开关都带行级锚点,选中后能滚到具体那一行。
        val fontEntry = GLOBAL_SEARCH_ENTRIES.first { it.titleRes == R.string.choice_font }
        assertEquals(
            GlobalSearchRowAnchors.appearanceChoiceKey(AodChoiceKind.FONT.name),
            fontEntry.rowAnchor
        )
        val spectrumEntry = GLOBAL_SEARCH_ENTRIES.first {
            it.titleRes == R.string.setting_spectrum_enabled
        }
        assertEquals(SearchRowKeys.APPEARANCE_SPECTRUM_ENABLED, spectrumEntry.rowAnchor)
        // 关于页静态入口也能被其它页搜到。
        assertTrue(GLOBAL_SEARCH_ENTRIES.count { it.tab == SettingsTab.ABOUT } >= 5)
        // 子页面选项直接带 destination。
        assertTrue(
            GLOBAL_SEARCH_ENTRIES.any {
                it.destination == GlobalSearchDestination.AOD_BEHAVIOR
            }
        )
        // 玻璃/卡片材质新增项:材质下拉带行锚点,App 外观子页项带 destination。
        val cardMaterial = GLOBAL_SEARCH_ENTRIES.first {
            it.titleRes == R.string.choice_card_material
        }
        assertEquals(
            GlobalSearchRowAnchors.appearanceChoiceKey(AodChoiceKind.CARD_MATERIAL.name),
            cardMaterial.rowAnchor
        )
        val glassMaterial = GLOBAL_SEARCH_ENTRIES.first {
            it.titleRes == R.string.setting_glass_material
        }
        assertEquals(GlobalSearchDestination.APP_APPEARANCE, glassMaterial.destination)
        // 全量索引应覆盖 160 条上下,避免后续新增开关时又退回只索引分组标题。
        assertTrue(GLOBAL_SEARCH_ENTRIES.size >= 150)
    }

    @Test
    fun appearanceAnchorsAreClassifiedExplicitly() {
        assertTrue(GlobalSearchAnchor.isAppearanceAnchor(GlobalSearchAnchor.APPEARANCE_COLORS))
        assertFalse(GlobalSearchAnchor.isAppearanceAnchor(GlobalSearchAnchor.STATUS_SOURCE))
        assertFalse(GlobalSearchAnchor.isAppearanceAnchor(null))
    }

    @Test
    fun blankQueryReturnsNothing() {
        assertTrue(filterGlobalSearchDocuments(listOf(sourceDoc), "  ").isEmpty())
        assertTrue(globalSearchCompletionsFromTitles(listOf("歌词来源"), "").isEmpty())
        assertFalse(globalSearchCompletionsFromTitles(listOf("歌词来源"), "歌词").isEmpty())
    }
}
