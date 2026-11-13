package com.eza.hyperglow.ui

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "zh-rCN")
class GlobalSearchTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test
    fun exactTitleStillMatches() {
        val results = filterGlobalSearchEntries(context, "歌词来源")
        assertTrue(results.any { it.anchor == GlobalSearchAnchor.STATUS_SOURCE })
    }

    @Test
    fun subsequenceFuzzyMatchFindsSkippedCharacters() {
        // 「歌源」不是连续子串,但按顺序命中「歌词来源」。
        val results = filterGlobalSearchEntries(context, "歌源")
        assertTrue(results.any { it.anchor == GlobalSearchAnchor.STATUS_SOURCE })
    }

    @Test
    fun multiTokenQueryRequiresEveryToken() {
        val results = filterGlobalSearchEntries(context, "歌词 来源")
        assertTrue(results.any { it.anchor == GlobalSearchAnchor.STATUS_SOURCE })
        assertTrue(filterGlobalSearchEntries(context, "歌词 zzzzz").isEmpty())
    }

    @Test
    fun typoInEnglishKeywordStillMatches() {
        assertTrue(filterGlobalSearchEntries(context, "sorce").isNotEmpty())
    }

    @Test
    fun completionsReturnTitlesContainingInput() {
        val completions = globalSearchCompletions(context, "歌词")
        assertTrue(completions.isNotEmpty())
        assertTrue(completions.all { it.contains("歌词") })
    }

    @Test
    fun experimentalEntriesAreHiddenWhenIneligible() {
        val eligible = filterGlobalSearchEntries(context, "实验", experimentalEligible = true)
        assertTrue(eligible.any { it.requiresExperimental })
        val ineligible = filterGlobalSearchEntries(context, "实验", experimentalEligible = false)
        assertTrue(ineligible.none { it.requiresExperimental })
    }

    @Test
    fun blankQueryReturnsNothing() {
        assertTrue(filterGlobalSearchEntries(context, "  ").isEmpty())
        assertTrue(globalSearchCompletions(context, "").isEmpty())
        assertFalse(globalSearchCompletions(context, "歌词").isEmpty())
    }
}
