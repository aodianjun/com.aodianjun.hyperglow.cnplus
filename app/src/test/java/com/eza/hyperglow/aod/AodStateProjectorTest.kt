package com.eza.hyperglow.aod

import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.customization.CompiledSurfaceProfile
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.TransitionPreset
import com.eza.hyperglow.producer.LyricDuetLine
import com.eza.hyperglow.producer.LyricKind
import com.eza.hyperglow.producer.LyricLayoutGroup
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricRuby
import com.eza.hyperglow.producer.LyricWord
import com.eza.hyperglow.producer.ProducerRenderModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [projectToDisplay] 单测：Phase 1 纯函数映射层的测试基线。
 *
 * 覆盖原 `AodProjectionEngine.project()` 的全部映射分支，按 [LyricKind] 维度组织：
 * - NONE / UNSYNCED → "🎶"、空罗马音/翻译、transition="None"(NONE)
 * - LINE / SYLLABLE → 活动行原文、per-word、lineLevelSync 语义
 * - 位置投影、渲染模式透传、保活、trackGeneration/wakeSignal 同构性
 *
 * 不依赖 Android 框架（`prefs`/`compiled` 直接构造），可纯 JVM 运行。
 */
class AodStateProjectorTest {

    private val prefs = AodRenderConfig(
        aodEnabled = true,
        alignment = "center",
        secondaryMode = "Translation",
        weight = "Bold",
        textSize = "large",
        textSizeCustom = 140,
        fontFamily = "spotify",
        animation = "Minimal",
        glow = "On",
        keepAwake = true,
        keepAwakeUnsynced = false,
        keepAwakeDurationMs = -1L,
        experimentalPositionFollowing = true,
        burnInPattern = "four_corner",
        burnInIntervalMs = 60_000L,
        metadataVisible = "show",
        metadataAnchor = "bottom"
    )

    private val compiled: CompiledCustomization = CompiledCustomization(
        version = 1,
        revision = 1L,
        hash = "h",
        sourceId = "src",
        linkSurfaces = false,
        profiles = linkedMapOf(
            SceneCompiler.SURFACE_LOCKSCREEN to aodProfile(enabled = false, metadataVisible = false),
            SceneCompiler.SURFACE_AOD to aodProfile(enabled = true, metadataVisible = true)
        )
    )

    private fun aodProfile(
        enabled: Boolean = true,
        metadataVisible: Boolean = true
    ) = CompiledSurfaceProfile(
        surface = SceneCompiler.SURFACE_AOD,
        enabled = enabled,
        anchor = "below_stock_clock",
        widthFraction = 0.9f,
        maxHeightFraction = 0.5f,
        verticalBias = 0.5f,
        collisionPolicy = "avoid",
        widgets = emptyList(),
        transition = TransitionPreset(),
        alignment = "auto",
        secondaryMode = "Main only",
        metadataVisible = metadataVisible,
        metadataAnchor = "top",
        weight = "Medium",
        textSize = "normal",
        textSizeCustom = 100,
        fontFamily = "spotify",
        animation = "Gradient",
        glow = "Off",
        lineSyncFillMode = "Top to bottom",
        overflow = "Wrap",
        adaptiveSectioning = true,
        palette = emptyMap()
    )

    private fun renderModes() = ProducerRenderModes(
        weight = "Bold",
        textSize = "large",
        textSizeCustom = 140,
        secondary = "Translation",
        animation = "Minimal",
        glow = "On",
        lineSyncFill = "Top to bottom",
        overflow = "Wrap",
        transition = "Crossfade",
        font = "spotify"
    )

    private fun state(
        lyricKind: LyricKind = LyricKind.SYLLABLE,
        line: String = "",
        romanizedLine: String = "",
        translatedLine: String = "",
        lineIndex: Int = -1,
        words: List<LyricWord>? = null,
        translationWords: List<LyricWord> = emptyList(),
        alignedRight: Boolean = false,
        lineStartMs: Long = 0L,
        lineEndMs: Long = 0L,
        ruby: List<LyricRuby> = emptyList(),
        layoutGroups: List<LyricLayoutGroup> = emptyList(),
        hasTimedLyrics: Boolean = true,
        nextLineStartMs: Long? = null,
        language: String = "",
        positionMs: Long = 0L,
        durationMs: Long = 180_000L,
        sampledAtElapsedMs: Long = 0L,
        speed: Float = 1f,
        playing: Boolean = true,
        status: String = "ready",
        producerId: String = "spicy-prod",
        generation: Int = 1,
        trackUri: String = "spotify:track:abc",
        title: String = "Title",
        artist: String = "Artist",
        album: String = ""
    ) = LyricProducerState(
        producerId = producerId,
        generation = generation,
        sequence = 1L,
        status = status,
        trackUri = trackUri,
        title = title,
        artist = artist,
        album = album,
        imageId = "",
        line = line,
        romanizedLine = romanizedLine,
        translatedLine = translatedLine,
        lineIndex = lineIndex,
        positionMs = positionMs,
        durationMs = durationMs,
        sampledAtElapsedMs = sampledAtElapsedMs,
        speed = speed,
        playing = playing,
        receivedAtElapsedMs = sampledAtElapsedMs,
        words = words,
        translationWords = translationWords,
        renderModes = renderModes(),
        lyricKind = lyricKind,
        alignedRight = alignedRight,
        lineStartMs = lineStartMs,
        lineEndMs = lineEndMs,
        ruby = ruby,
        layoutGroups = layoutGroups,
        hasTimedLyrics = hasTimedLyrics,
        nextLineStartMs = nextLineStartMs,
        language = language
    )

    private fun project(
        state: LyricProducerState,
        now: Long = 0L,
        compiled: CompiledCustomization? = this.compiled
    ): AodDisplayState =
        projectToDisplay(
            state = state,
            now = now,
            prefs = prefs,
            compiled = compiled,
            metadataIntroPolicy = SongMetadataIntroPolicy(),
            powerSessionPolicy = AodPowerSessionPolicy(),
            userId = 0
        )

    // --- 插件逐字翻译词表(translationWords,翻译辅助行按真实词窗点亮)---

    @Test
    fun pluginTranslationWordsAreForwardedForActiveLine() {
        val s = state(
            line = "I love you",
            translatedLine = "我爱你",
            lineIndex = 0,
            lineStartMs = 0L,
            lineEndMs = 2_000L,
            words = listOf(LyricWord("I love you", "", 0L, 2_000L, true)),
            translationWords = listOf(
                LyricWord("我", "", 0L, 600L, true),
                LyricWord(" 爱", "", 600L, 1_200L, true),
                LyricWord(" 你", "", 1_200L, 2_000L, true)
            )
        )
        val out = project(s)

        assertEquals(3, out.translationWords.size)
        assertEquals(listOf("我", " 爱", " 你"), out.translationWords.map { it.text })
        assertEquals(600L, out.translationWords[1].startMs)
        assertEquals(2_000L, out.translationWords[2].endMs)
    }

    /** 无活动行(空档占位符)时不携带逐字翻译词表——与 words 同一门控。 */
    @Test
    fun pluginTranslationWordsAreDroppedWithoutActiveLine() {
        val s = state(
            line = "",
            translatedLine = "我爱你",
            lineIndex = -1,
            translationWords = listOf(LyricWord("我", "", 0L, 600L, true))
        )
        val out = project(s)

        assertTrue(out.translationWords.isEmpty())
    }

    // --- 语言不一致的日语假名注音(上游 8422d78)---

    @Test
    fun chineseLineWithKanaRubyDropsRubyAndRomanization() {
        val s = state(
            line = "晴天",
            lineIndex = 0,
            words = listOf(LyricWord("晴天", "せいてん", 0L, 2_000L, false)),
            ruby = listOf(LyricRuby(0, 1, "せい")),
            language = "zh",
            romanizedLine = "seiten"
        )
        val out = project(s)

        assertEquals("晴天", out.original)
        assertEquals("", out.romanized)
        assertTrue(out.ruby.isEmpty())
        assertTrue(out.words.all { it.romanized.isEmpty() })
    }

    @Test
    fun japaneseLineKeepsKanaRuby() {
        val s = state(
            line = "晴天",
            lineIndex = 0,
            words = listOf(LyricWord("晴天", "せいてん", 0L, 2_000L, false)),
            ruby = listOf(LyricRuby(0, 1, "せい")),
            language = "ja",
            romanizedLine = "seiten"
        )
        val out = project(s)

        assertEquals("晴天", out.original)
        assertEquals("seiten", out.romanized)
        assertTrue(out.ruby.isNotEmpty())
        assertTrue(out.words.all { it.romanized.isNotEmpty() })
    }

    @Test
    fun chineseLineWithPinyinRubyKeepsRuby() {
        val s = state(
            line = "晴天",
            lineIndex = 0,
            ruby = listOf(LyricRuby(0, 1, "qíng")),
            language = "zh-Hant"
        )
        val out = project(s)

        assertTrue(out.ruby.isNotEmpty())
    }

    @Test
    fun languageInconsistentKanaRubyNormalizesLanguageTags() {
        assertTrue(hasLanguageInconsistentKanaRuby("zh", listOf("せい")))
        assertTrue(hasLanguageInconsistentKanaRuby("zh-Hant", listOf("カナ")))
        assertTrue(hasLanguageInconsistentKanaRuby("zh_CN", listOf("ﾃｽﾄ")))
        assertFalse(hasLanguageInconsistentKanaRuby("zh", listOf("qíng")))
        assertFalse(hasLanguageInconsistentKanaRuby("ja", listOf("せい")))
        assertFalse(hasLanguageInconsistentKanaRuby("", listOf("せい")))
        assertFalse(hasLanguageInconsistentKanaRuby("zh", emptyList()))
    }

    // --- lyricKind = NONE ---

    @Test
    fun noneKind_rendersMusicalNoteAndForcesNoneTransition() {
        val s = state(
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false,
            line = "",
            lineIndex = -1,
            title = "",
            artist = ""
        )
        val out = project(s)
        assertEquals("🎶", out.original)
        assertEquals("", out.romanized)
        assertEquals("", out.translated)
        assertEquals("None", out.transitionMode)
        assertFalse(out.lineLevelSync)
        assertTrue(out.words.isEmpty())
    }

    // --- lyricKind = UNSYNCED ---

    @Test
    fun unsyncedKind_rendersMusicalNoteButKeepsTransitionFromProfile() {
        val s = state(
            lyricKind = LyricKind.UNSYNCED,
            hasTimedLyrics = false,
            line = "untimed one-liner",
            lineIndex = -1,
            title = "",
            artist = ""
        )
        val out = project(s)
        assertEquals("🎶", out.original)
        // UNSYNCED 不像 NONE 那样强制 "None"：取 profile.lineTransition 的解析值
        // （默认 "Auto" → "Fade up"）。renderModes 的 "Crossfade" 只在 compiled 缺失时才是答案。
        assertEquals("Fade up", out.transitionMode)
        assertFalse(out.lineLevelSync)
        // compiled 缺失时回落到 renderModes 的原值，确认兜底路径仍通。
        assertEquals("Crossfade", project(s, compiled = null).transitionMode)
    }

    // --- lyricKind = LINE (活动行无 words) ---

    @Test
    fun lineKind_withActiveLine_showsMetadataInsteadOfLineText() {
        val s = state(
            lyricKind = LyricKind.LINE,
            line = "hello world",
            romanizedLine = "roma",
            translatedLine = "tr",
            lineIndex = 0,
            words = null,
            lineStartMs = 1_000L,
            lineEndMs = 3_000L,
            alignedRight = true
        )
        val out = project(s)
        // 有活动歌词行时优先显示歌词,而非歌名
        assertEquals("hello world", out.original)
        assertEquals("roma", out.romanized)
        assertEquals("tr", out.translated)
        // LINE 有活动行 → 保持行级同步(整行扫光)；无真实词级数据时不合成，words 为空
        assertTrue(out.lineLevelSync)
        assertEquals(1_000L, out.lineStartMs)
        assertEquals(3_000L, out.lineEndMs)
        assertTrue(out.alignedRight)
        assertTrue(out.words.isEmpty())
    }

    // --- lyricKind = SYLLABLE (活动行有 words) ---

    @Test
    fun syllableKind_withActiveLine_showsMetadataInsteadOfSyllables() {
        val words = listOf(
            LyricWord("he", "", 1_000L, 1_500L, false),
            LyricWord("llo", "", 1_500L, 2_000L, true)
        )
        val s = state(
            lyricKind = LyricKind.SYLLABLE,
            line = "hello",
            lineIndex = 0,
            words = words,
            lineStartMs = 1_000L,
            lineEndMs = 2_000L
        )
        val out = project(s)
        // 有活动歌词行时显示歌词;words 透传供"当前词微光"叠加,行级同步保持水平扫光
        assertEquals("hello", out.original)
        assertTrue(out.lineLevelSync) // 行级同步(整行水平扫光)
        assertEquals(2, out.words.size)
    }

    @Test
    fun syllableKind_withActiveLineButNoWords_lineLevelSyncEnabled() {
        // SYLLABLE 且无 words → 行级同步开启,但 words 为空
        val s = state(
            lyricKind = LyricKind.SYLLABLE,
            line = "hello",
            lineIndex = 0,
            words = null,
            lineStartMs = 1_000L,
            lineEndMs = 2_000L
        )
        val out = project(s)
        assertTrue(out.lineLevelSync)
        assertTrue(out.words.isEmpty())
    }

    // --- 位置投影 ---

    @Test
    fun positionIsForwardProjectedWhenPlaying() {
        val s = state(
            positionMs = 10_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 2f,
            playing = true,
            lineIndex = -1,
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false
        )
        val out = project(s, now = 1_500L) // 0.5s * 2x = 1000ms 外推
        assertEquals(11_000L, out.positionMs)
        assertEquals(1_500L, out.sampledAtElapsedMs)
    }

    @Test
    fun positionIsClampedToDuration() {
        val s = state(
            positionMs = 179_000L,
            sampledAtElapsedMs = 0L,
            speed = 1f,
            playing = true,
            durationMs = 180_000L,
            lineIndex = -1,
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false
        )
        val out = project(s, now = 5_000L) // 179000 + 5000 = 184000 → clamp 180000
        assertEquals(180_000L, out.positionMs)
    }

    @Test
    fun extrapolationCrossingSongEnd_clearsActiveLineInsteadOfShowingStaleLastLine() {
        // 息屏时网易云停写位置；外推越过歌曲结尾 → 歌曲已结束/重播。活动行应被清空（显示 🎶），
        // 而不是继续显示被钳制在末行的旧歌词。
        val s = state(
            positionMs = 179_000L,
            sampledAtElapsedMs = 0L,
            speed = 1f,
            playing = true,
            durationMs = 180_000L,
            lineIndex = 3,
            line = "stale last line",
            hasTimedLyrics = true,
            title = "",
            artist = ""
        )
        val out = project(s, now = 5_000L) // 179000 + 5000 = 184000 → 越界

        assertEquals("🎶", out.original)
        assertEquals(0L, out.lineStartMs)
        assertEquals(0L, out.lineEndMs)
        assertTrue(out.words.isEmpty())
    }

    @Test
    fun extrapolationWithinBounds_keepsActiveLine() {
        // 正常息屏匀速播放：外推仍在歌曲内 → 活动行保留。
        val s = state(
            positionMs = 10_000L,
            sampledAtElapsedMs = 1_000L,
            speed = 1f,
            playing = true,
            durationMs = 180_000L,
            lineIndex = 2,
            line = "current line",
            lineStartMs = 9_000L,
            lineEndMs = 12_000L,
            hasTimedLyrics = true
        )
        val out = project(s, now = 2_000L)

        assertEquals("current line", out.original)
        assertEquals(9_000L, out.lineStartMs)
        assertEquals(12_000L, out.lineEndMs)
    }

    @Test
    fun positionHoldsWhenNotPlaying() {
        val s = state(
            positionMs = 42_000L,
            sampledAtElapsedMs = 0L,
            speed = 1f,
            playing = false,
            lineIndex = -1,
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false
        )
        val out = project(s, now = 10_000L)
        assertEquals(42_000L, out.positionMs)
    }

    // --- 渲染模式：编译后的 AOD profile 优先，producer renderModes 兜底 ---

    @Test
    fun compiledAodProfileOverridesProducerRenderModes() {
        // 渲染模式以编译后的 AOD profile 为准（预览读的就是这份 profile，两侧同源才谈得上
        // 所见即所得）；state.renderModes 降级为兜底。修复前这里只读 renderModes。
        val s = state(lyricKind = LyricKind.LINE, line = "x", lineIndex = 0, lineStartMs = 1, lineEndMs = 2)
        val out = project(s)
        val profile = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertEquals(profile.weight, out.weight)
        assertEquals(profile.textSize, out.textSizeMode)
        assertEquals(profile.textSizeCustom, out.textSizeCustom)
        assertEquals(profile.secondaryMode, out.secondaryMode)
        assertEquals(profile.animation, out.animationMode)
        assertEquals(profile.glow, out.glowMode)
        assertEquals(profile.lineSyncFillMode, out.lineSyncFillMode)
        assertEquals(profile.overflow, out.overflowMode)
        assertEquals(profile.fontFamily, out.fontFamily)
        // 换行动画：profile.lineTransition 为 "Auto" 时退默认 "Fade up"，
        // 与 producer 侧 LyriconRenderModeMapping.toProducerRenderModes 同一归一。
        assertEquals("Fade up", out.transitionMode)
    }

    @Test
    fun betterLyricsFromProfileSurvivesProducerThatDoesNotRefillRenderModes() {
        // 「BetterLyrics 预览看得到、实机没有」的回归门：非 Lyricon 源的 renderModes 是硬编码
        // 默认值（此处模拟 "Karaoke fill"——normalizeAodAnimation 会把它退回 Gradient），
        // 而用户把逐字动画设成了 BetterLyrics。修复前 animationMode 恒为 Gradient。
        val betterLyrics = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD)
            .copy(animation = "BetterLyrics", glow = "On")
        val compiledWithBetterLyrics = compiled.copy(
            profiles = compiled.profiles + (SceneCompiler.SURFACE_AOD to betterLyrics)
        )
        val s = state(
            lyricKind = LyricKind.SYLLABLE,
            line = "晴天",
            lineIndex = 0,
            words = listOf(LyricWord("晴天", "せいてん", 0L, 2_000L, false)),
            lineStartMs = 1,
            lineEndMs = 2
        ).copy(renderModes = renderModes().copy(animation = "Karaoke fill"))
        val out = project(s, compiled = compiledWithBetterLyrics)
        assertEquals("BetterLyrics", out.animationMode)
        assertEquals("On", out.glowMode)
    }

    @Test
    fun producerRenderModesRemainTheFallbackWhenCompiledProfileMissing() {
        // compiled 缺失（降级 / 旧文档）时仍回落 renderModes，行为与改前一致。
        val s = state(lyricKind = LyricKind.LINE, line = "x", lineIndex = 0, lineStartMs = 1, lineEndMs = 2)
        val out = project(s, compiled = null)
        val modes = renderModes()
        assertEquals(modes.weight, out.weight)
        assertEquals(modes.textSize, out.textSizeMode)
        assertEquals(modes.animation, out.animationMode)
        assertEquals(modes.glow, out.glowMode)
        assertEquals(modes.transition, out.transitionMode)
    }

    @Test
    fun aodEnabledFromCompiledProfileOverridesPrefs() {
        val s = state(lyricKind = LyricKind.LINE, line = "x", lineIndex = 0, lineStartMs = 1, lineEndMs = 2)
        val out = project(s)
        assertTrue(out.aodEnabled) // compiled AOD profile enabled=true 覆盖
        assertFalse(out.lockscreenEnabled) // compiled lockscreen enabled=false
    }

    @Test
    fun metadataVisibleFromCompiledProfileOverridesPrefs() {
        val s = state(lyricKind = LyricKind.NONE, hasTimedLyrics = false, line = "", lineIndex = -1)
        val out = project(s)
        assertTrue(out.metadataVisible) // compiled AOD metadataVisible=true
    }

    // --- trackGeneration / wakeSignal 同构性 ---

    @Test
    fun trackGenerationMatchesEngineSpicyVersionSemantics() {
        val s = state(producerId = "p", generation = 7, trackUri = "spotify:track:z")
        val expected = ("p\u00007\u0000spotify:track:z").hashCode().toLong() and Long.MAX_VALUE
        assertEquals(expected, trackGeneration(s))
    }

    @Test
    fun wakeSignalDistinguishesTimedAndSongPhase() {
        val base = state(producerId = "p", generation = 1, trackUri = "spotify:track:x")
        val timed = sessionWakeSignal(base, hasTimedLyrics = true)
        val song = sessionWakeSignal(base, hasTimedLyrics = false)
        assertTrue(timed != song)
        assertTrue(timed != 0L)
        assertTrue(song != 0L)
    }

    // --- ruby / layoutGroups 透传 ---

    @Test
    fun rubyAndLayoutGroupsPreservedDuringActiveLyric() {
        // 有活动歌词行时 ruby 和 layoutGroups 保留(不再被歌名清空)
        val ruby = listOf(LyricRuby(0, 2, "ha"))
        val groups = listOf(LyricLayoutGroup(0, 5, "word", true, 0.9))
        val s = state(
            lyricKind = LyricKind.SYLLABLE,
            line = "hello",
            lineIndex = 0,
            words = listOf(LyricWord("hello", "", 1, 2, false)),
            lineStartMs = 1,
            lineEndMs = 2,
            ruby = ruby,
            layoutGroups = groups
        )
        val out = project(s)
        assertEquals(ruby.size, out.ruby.size)
        assertEquals(groups.size, out.layoutGroups.size)
    }

    @Test
    fun rubyAndLayoutGroupsClearedWhenNoActiveLine() {
        val s = state(
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false,
            line = "",
            lineIndex = -1,
            ruby = listOf(LyricRuby(0, 2, "ha")),
            layoutGroups = listOf(LyricLayoutGroup(0, 5, "word", true, 0.9))
        )
        val out = project(s)
        assertTrue(out.ruby.isEmpty())
        assertTrue(out.layoutGroups.isEmpty())
    }

    // --- nextLineStartMs 透传到 metadataIntroPolicy ---

    @Test
    fun nextLineStartMsIsUsedByMetadataIntroPolicyForInterlude() {
        // 在间奏期（hasTimedLyrics=true，无活动行），nextLineStartMs 决定能否展示大元数据。
        // 这里只验证 nextLineStartMs 透传到了输出链路（policy 实际行为由其自身单测覆盖）。
        val s = state(
            lyricKind = LyricKind.LINE, // song-level，但 lineIndex=-1 → 间奏
            hasTimedLyrics = true,
            line = "",
            lineIndex = -1,
            nextLineStartMs = 5_000L,
            positionMs = 1_000L,
            title = "",
            artist = ""
        )
        val out = project(s)
        assertNotNull(out)
        // original 在间奏期且无大元数据时应为 "🎶"
        assertEquals("🎶", out.original)
    }

    // --- visible 标志 ---

    @Test
    fun visibleFollowsOriginalNonBlank() {
        val active = state(lyricKind = LyricKind.LINE, line = "hi", lineIndex = 0, lineStartMs = 1, lineEndMs = 2)
        assertTrue(project(active).visible)

        val none = state(lyricKind = LyricKind.NONE, hasTimedLyrics = false, line = "", lineIndex = -1)
        // "🎶" 非空 → visible=true
        assertTrue(project(none).visible)
    }

    // --- 集成：LINE 无 words 时仅整行扫光（不合成逐字）；有真实 words 时透传叠加 ---

    @Test
    fun lineKind_withoutWords_pureScanLight_noWordOverlay() {
        // 普通 LRC（lyricinfo 源）只有行级时间、无词级数据 → words 为空，纯整行扫光，
        // 不合成逐字；lineLevelSync 保持 true 以保留扫光发光
        val s = state(
            lyricKind = LyricKind.LINE,
            line = "hello world",
            lineIndex = 0,
            words = null,
            lineStartMs = 1_000L,
            lineEndMs = 3_400L
        )
        val out = project(s)
        assertEquals("hello world", out.original)
        assertTrue(out.lineLevelSync) // LINE 保持行级同步(整行扫光)
        assertTrue(out.words.isEmpty()) // 无真实词级数据 → 不合成
    }

    @Test
    fun lineKind_existingWords_passedThroughWithLineLevelSync() {
        // 真实词级数据透传供"当前词微光"叠加，同时保持行级同步(整行水平扫光)
        val words = listOf(LyricWord("hello", "", 1_000L, 1_500L, false))
        val s = state(
            lyricKind = LyricKind.LINE,
            line = "hello world",
            lineIndex = 0,
            words = words,
            lineStartMs = 1_000L,
            lineEndMs = 3_400L
        )
        val out = project(s)
        assertEquals(1, out.words.size) // words 透传
        assertEquals("hello", out.words[0].text)
        assertTrue(out.lineLevelSync) // 行级同步(整行水平扫光)
    }

    @Test
    fun lineKind_noActiveLine_wordsEmpty() {
        // 无活动行（lineIndex=-1）→ 无歌词，words 保持为空
        val s = state(
            lyricKind = LyricKind.LINE,
            line = "hello world",
            lineIndex = -1,
            words = null,
            lineStartMs = 1_000L,
            lineEndMs = 3_400L
        )
        val out = project(s)
        assertTrue(out.words.isEmpty())
    }

    // --- shouldKeepAodAliveFor 顶层函数 ---

    @Test
    fun keepAliveRequiresPlayingAndAodAndKeepAwakeAndTimedOrUnsynced() {
        assertTrue(shouldKeepAodAliveFor(true, true, true, false, true))
        assertFalse(shouldKeepAodAliveFor(true, true, true, false, false)) // 无 timed、无 unsynced
        assertTrue(shouldKeepAodAliveFor(true, true, true, true, false)) // unsynced 兜底
        assertFalse(shouldKeepAodAliveFor(false, true, true, true, true))
        assertFalse(shouldKeepAodAliveFor(true, false, true, true, true))
        assertFalse(shouldKeepAodAliveFor(true, true, false, true, true))
    }

    // --- 歌曲信息切片:显示部分与分隔符(文档级全局配置)---

    @Test
    fun metadataComposesTitleAndArtistWithNewlineByDefault() {
        val s = state(
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false,
            title = "蝴蝶",
            artist = "洛天依"
        )
        assertEquals("蝴蝶\n洛天依", project(s).metadata)
    }

    @Test
    fun metadataHonorsConfiguredPartsAndSeparator() {
        val s = state(
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false,
            title = "Song",
            artist = "Artist",
            album = "Album"
        )
        val out = project(
            s,
            compiled = compiled.copy(
                metadataParts = "title,artist,album",
                metadataSeparators = "dot,dot"
            )
        )
        assertEquals("Song · Artist · Album", out.metadata)
    }

    @Test
    fun metadataHonorsCustomOrderAndPerGapSeparators() {
        val s = state(
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false,
            title = "Song",
            artist = "Artist",
            album = "Album"
        )
        // 自定义排序(专辑→歌名→歌手)与逐槽分隔符(第一槽换行、第二槽行内)。
        val out = project(
            s,
            compiled = compiled.copy(
                metadataParts = "album,title,artist",
                metadataSeparators = "newline,dot"
            )
        )
        assertEquals("Album\nSong · Artist", out.metadata)
    }

    @Test
    fun metadataPartSelectionDropsUnselectedAndBlankParts() {
        val s = state(
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false,
            title = "Song",
            artist = "Artist",
            album = "Album"
        )
        assertEquals(
            "Album",
            project(s, compiled = compiled.copy(metadataParts = "album")).metadata
        )
        assertEquals(
            "Song\nAlbum",
            project(s, compiled = compiled.copy(metadataParts = "title,album")).metadata
        )
        // 未选部分即使有值也不参与组装;历史 jammed「歌名·歌手」仍按切片边界拆开。
        val jammed = state(
            lyricKind = LyricKind.NONE,
            hasTimedLyrics = false,
            title = "Song·Artist",
            artist = ""
        )
        assertEquals(
            "Song\nArtist",
            project(jammed, compiled = compiled.copy(metadataParts = "title")).metadata
        )
    }

    // --- 对唱标记(（男）/（女）/（合）)不再在投影层剥离(per-surface)---

    @Test
    fun duetMarkerTextPassesThroughRawForSurfaceRendering() {
        // 快照为息屏/锁屏共用,行首标记剥离推迟到按面渲染(各面按自己「识别对唱标记」决策),
        // 投影层只下发原始文本与词表。渲染侧行为见
        // AodCanvasLayoutTest.surfaceDuetMarkersStripAndSelectAlignmentIndependently。
        val s = state(
            line = "（女） 男共女的事总有人偏私",
            lineIndex = 0,
            words = listOf(
                LyricWord("（女）男共女", "", 0L, 1_000L, false),
                LyricWord("的事", "", 1_000L, 2_000L, false)
            )
        ).copy(nextLine = "（男） 男共女的事深究无意义")
        val out = project(s)

        assertEquals("（女） 男共女的事总有人偏私", out.original)
        assertEquals("（男） 男共女的事深究无意义", out.nextLine)
        assertEquals(listOf("（女）男共女", "的事"), out.words.map { it.text })
    }

    // --- 下一行辅助文字(「显示第二行辅助文字」)与下一行同门控 ---

    @Test
    fun nextLineAuxTextPassesWithNextLineAndClearsOnUnsynced() {
        // 正常活动行:下一行与其音标/翻译一并透传(不剥离标记,与 translatedLine 同口径)。
        val active = project(
            state(line = "current", lineIndex = 0).copy(
                nextLine = "next",
                nextLineRomanized = "next roma",
                nextLineTranslated = "next trans"
            )
        )
        assertEquals("next", active.nextLine)
        assertEquals("next roma", active.nextLineRomanized)
        assertEquals("next trans", active.nextLineTranslated)

        // 未同步歌词(UNSYNCED):下一行与其辅助文字一并清空(同一门控)。
        val unsynced = project(
            state(lyricKind = LyricKind.UNSYNCED, line = "flat", lineIndex = -1).copy(
                nextLine = "next",
                nextLineRomanized = "next roma",
                nextLineTranslated = "next trans"
            )
        )
        assertEquals("", unsynced.nextLine)
        assertEquals("", unsynced.nextLineRomanized)
        assertEquals("", unsynced.nextLineTranslated)
    }

    @Test
    fun duetMarkerRawTextIsKeptRegardlessOfDocumentFlag() {
        // 文档级开关不再是投影层的决策输入(剥离已推迟到按面渲染),两种取值下原文一致。
        val s = state(line = "（女） 男共女的事总有人偏私", lineIndex = 0)
            .copy(nextLine = "（男） 下一句")
        val on = project(s, compiled = compiled.copy(duetMarkers = true))
        val off = project(s, compiled = compiled.copy(duetMarkers = false))

        assertEquals("（女） 男共女的事总有人偏私", on.original)
        assertEquals("（女） 男共女的事总有人偏私", off.original)
        assertEquals("（男） 下一句", on.nextLine)
        assertEquals("（男） 下一句", off.nextLine)
    }

    @Test
    fun sectionMarkerTextPassesThroughRaw() {
        // 段落标记(（副歌）/（间奏）等)与对唱标记同源:投影层原样下发,词表同步保留标记文本。
        val s = state(
            line = "（副歌） 爱你一万年",
            lineIndex = 0,
            words = listOf(
                LyricWord("（副歌）爱你", "", 0L, 1_000L, false),
                LyricWord("一万年", "", 1_000L, 2_000L, false)
            )
        ).copy(nextLine = "（间奏） 轻快地弹奏")
        val out = project(s)

        assertEquals("（副歌） 爱你一万年", out.original)
        assertEquals("（间奏） 轻快地弹奏", out.nextLine)
        assertEquals(listOf("（副歌）爱你", "一万年"), out.words.map { it.text })
    }

    @Test
    fun duetConcurrentOffDropsCandidateAtSource() {
        val s = state(
            line = "main",
            lineIndex = 0,
            words = listOf(LyricWord("main", "", 0L, 2_000L, false))
        ).copy(
            duetLine = LyricDuetLine(text = "second", lineStartMs = 500L, lineEndMs = 3_000L)
        )
        // 两面都关闭 → 源头撤行(任一曲面开启即携带,见下一条用例)。
        val gated = compiled.copy(
            profiles = linkedMapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to
                    compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
                        .copy(duetConcurrent = false),
                SceneCompiler.SURFACE_AOD to
                    aodProfile(enabled = true, metadataVisible = true).copy(duetConcurrent = false)
            )
        )
        val out = project(s, compiled = gated)
        assertNull(out.duetLine)
    }

    @Test
    fun duetConcurrentCarriesCandidateWhenOnlyLockscreenWantsIt() {
        // 锁屏开、息屏关:数据面仍携带候选(锁屏 mapper 会渲染,息屏 mapper 按自己的档位撤下)。
        val s = state(
            line = "main",
            lineIndex = 0,
            words = listOf(LyricWord("main", "", 0L, 2_000L, false))
        ).copy(
            duetLine = LyricDuetLine(text = "second", lineStartMs = 500L, lineEndMs = 3_000L)
        )
        val lockscreenOnly = compiled.copy(
            profiles = linkedMapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to
                    compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN),
                SceneCompiler.SURFACE_AOD to
                    aodProfile(enabled = true, metadataVisible = true).copy(duetConcurrent = false)
            )
        )
        val out = project(s, compiled = lockscreenOnly)
        assertNotNull(out.duetLine)
        assertEquals("second", out.duetLine!!.text)
    }

    @Test
    fun duetConcurrentCarriesCandidateRawForSurfaceRendering() {
        val s = state(
            line = "main",
            lineIndex = 0,
            words = listOf(LyricWord("main", "", 0L, 2_000L, false))
        ).copy(
            duetLine = LyricDuetLine(
                text = "（男）second",
                lineStartMs = 500L,
                lineEndMs = 3_000L,
                words = listOf(LyricWord("（男）second", "", 500L, 3_000L, false))
            )
        )
        val out = project(s)
        assertNotNull(out.duetLine)
        // 并发行文本原样下发(含行首标记),剥离由渲染面按本面「识别对唱标记」处理。
        assertEquals("（男）second", out.duetLine!!.text)
        assertTrue(out.duetLine!!.words.isNotEmpty())
    }

    // --- 空档预览:无活动行时主行提前显示下一行,替代 🎶 占位符 ---

    @Test
    fun interludeWithNextLine_previewsNextLineInMainRow() {
        // 间奏/前奏态(LINE/SYLLABLE 源,无活动行)且确有下一行 → 主行提前显示下一行;
        // 行窗取下一行起点(退化窗,进度恒 0 = 未唱);下一行槽位清空防重复;辅助行换成
        // 下一行的那份;上一行的派生数据(words/ruby/layoutGroups)不与预览文本错配。
        val s = state(
            lyricKind = LyricKind.LINE,
            hasTimedLyrics = true,
            line = "",
            lineIndex = -1,
            lineStartMs = 0L,
            lineEndMs = 0L,
            positionMs = 3_000L,
            nextLineStartMs = 8_000L,
            words = listOf(LyricWord("上一句", "", 0L, 2_000L, false)),
            ruby = listOf(LyricRuby(0, 1, "shang")),
            layoutGroups = listOf(LyricLayoutGroup(0, 2, "word", true, 0.9)),
            romanizedLine = "shang yi ju",
            translatedLine = "previous line",
            title = "",
            artist = ""
        ).copy(
            nextLine = "下一句",
            nextLineRomanized = "xia yi ju",
            nextLineTranslated = "next line"
        )
        val out = project(s)

        assertEquals("下一句", out.original)
        assertTrue(out.visible)
        // 下一行槽位及其辅助文字清空(同一句不再同时出现在主行与下一行两处)
        assertEquals("", out.nextLine)
        assertEquals("", out.nextLineRomanized)
        assertEquals("", out.nextLineTranslated)
        // 辅助行跟着主行换到下一行的那份
        assertEquals("xia yi ju", out.romanized)
        assertEquals("next line", out.translated)
        // 行窗 = 下一行起点(退化窗 → timedWordProgress 恒 0,见 AodCanvasLayoutTest)
        assertEquals(8_000L, out.lineStartMs)
        assertEquals(8_000L, out.lineEndMs)
        // 上一行的派生数据清空
        assertTrue(out.words.isEmpty())
        assertTrue(out.ruby.isEmpty())
        assertTrue(out.layoutGroups.isEmpty())
        assertFalse(out.lineLevelSync)
    }

    @Test
    fun interludeWithoutNextLine_keepsPlaceholderAndZeroWindow() {
        // 回归保护:无下一行文本时保持原行为(🎶 + 0/0 窗口)。
        val s = state(
            lyricKind = LyricKind.LINE,
            hasTimedLyrics = true,
            line = "",
            lineIndex = -1,
            nextLineStartMs = 8_000L,
            positionMs = 3_000L,
            title = "",
            artist = ""
        )
        val out = project(s)

        assertEquals("🎶", out.original)
        assertEquals(0L, out.lineStartMs)
        assertEquals(0L, out.lineEndMs)
    }

    @Test
    fun interludeWhileLoading_keepsPlaceholder() {
        // status == "loading":下一行尚未就绪,即使槽位里有文本也保持占位符(与简报语义表一致)。
        val s = state(
            lyricKind = LyricKind.LINE,
            hasTimedLyrics = true,
            line = "",
            lineIndex = -1,
            status = "loading",
            nextLineStartMs = 8_000L,
            positionMs = 3_000L,
            title = "",
            artist = ""
        ).copy(nextLine = "下一句", nextLineRomanized = "x", nextLineTranslated = "y")
        val out = project(s)

        assertEquals("🎶", out.original)
        assertEquals(0L, out.lineStartMs)
        assertEquals(0L, out.lineEndMs)
        // 预览未生效 → 下一行槽位保持原门控行为(照常透传)
        assertEquals("下一句", out.nextLine)
    }

    @Test
    fun activeLineIgnoresPreviewEvenWithNextLinePresent() {
        // 有活动行 → 完全不变:主行/辅助行/下一行槽位/窗口都保持原行为。
        val s = state(
            lyricKind = LyricKind.LINE,
            line = "正在唱",
            lineIndex = 0,
            lineStartMs = 5_000L,
            lineEndMs = 7_000L,
            nextLineStartMs = 7_000L,
            positionMs = 6_000L,
            romanizedLine = "roma",
            translatedLine = "trans"
        ).copy(nextLine = "下一句", nextLineRomanized = "x", nextLineTranslated = "y")
        val out = project(s)

        assertEquals("正在唱", out.original)
        assertEquals("roma", out.romanized)
        assertEquals("trans", out.translated)
        assertEquals("下一句", out.nextLine)
        assertEquals("x", out.nextLineRomanized)
        assertEquals("y", out.nextLineTranslated)
        assertEquals(5_000L, out.lineStartMs)
        assertEquals(7_000L, out.lineEndMs)
        assertTrue(out.lineLevelSync)
    }

    @Test
    fun unsyncedAndNoneKindsKeepPlaceholderDespiteNextLine() {
        // UNSYNCED(整首无计时)与 NONE 都保持占位符:预览只在「有计时歌词且无活动行」的
        // 间奏态生效。NONE 覆盖 LyricInfo 前奏/尾奏态(该源无活动行时 kind=NONE),
        // 与简报优先级 1(unsynced || noLyrics → 占位符,不变)一致。
        val unsynced = project(
            state(
                lyricKind = LyricKind.UNSYNCED,
                hasTimedLyrics = false,
                line = "flat line",
                lineIndex = -1
            ).copy(nextLine = "下一句")
        )
        assertEquals("🎶", unsynced.original)

        val none = project(
            state(
                lyricKind = LyricKind.NONE,
                hasTimedLyrics = true,
                line = "",
                lineIndex = -1,
                nextLineStartMs = 8_000L,
                positionMs = 3_000L
            ).copy(nextLine = "下一句")
        )
        assertEquals("🎶", none.original)
    }

    @Test
    fun previewDoesNotOverrideWindowDuringLargeMetadata() {
        // 大元数据引导(歌曲信息)靠「窗口在过去 → 全亮」呈现亮色文本;预览的退化窗
        // (未唱 = 暗色)不能覆盖它 —— 窗口保持状态自带值(0/0),大元数据标记仍为真。
        val s = state(
            lyricKind = LyricKind.LINE,
            hasTimedLyrics = true,
            line = "",
            lineIndex = -1,
            nextLineStartMs = 10_000L,
            positionMs = 0L,
            title = "蝴蝶",
            artist = "洛天依"
        ).copy(nextLine = "下一句")
        val out = project(s)

        assertTrue(out.largeMetadata)
        assertEquals(0L, out.lineStartMs)
        assertEquals(0L, out.lineEndMs)
    }

    @Test
    fun previewWithoutKnownNextLineStart_keepsZeroWindow() {
        // nextLineStartMs == null(窗口未知):仍提前显示下一行文本,但行窗保持 0/0。
        val s = state(
            lyricKind = LyricKind.LINE,
            hasTimedLyrics = true,
            line = "",
            lineIndex = -1,
            nextLineStartMs = null,
            title = "",
            artist = ""
        ).copy(nextLine = "下一句")
        val out = project(s)

        assertEquals("下一句", out.original)
        assertEquals(0L, out.lineStartMs)
        assertEquals(0L, out.lineEndMs)
    }

    @Test
    fun untrustworthyExtrapolationKeepsPlaceholderInsteadOfStaleNextLine() {
        // 数据源停写位置、外推越过歌曲结尾:活动行与「下一行」都来自过期快照,预览一并
        // 让位给占位符(外推防护的既有语义),不让空档把旧内容重新拉上台。
        val s = state(
            lyricKind = LyricKind.LINE,
            hasTimedLyrics = true,
            line = "",
            lineIndex = -1,
            positionMs = 179_000L,
            durationMs = 180_000L,
            sampledAtElapsedMs = 0L,
            speed = 1f,
            playing = true,
            nextLineStartMs = 190_000L
        ).copy(nextLine = "下一句")
        val out = project(s, now = 5_000L) // 179000 + 5000 = 184000 → 越界

        assertEquals("🎶", out.original)
        assertEquals(0L, out.lineStartMs)
        assertEquals(0L, out.lineEndMs)
    }

}