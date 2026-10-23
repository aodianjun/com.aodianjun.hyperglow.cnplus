package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.root.projection.LyricDuetLine
import com.eza.hyperglow.root.projection.LyricSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 并发行的 per-surface 门控:曲面参与对唱渲染([toAodCanvasContent] 的 duet 参数)且
 * 该曲面自己的 `duetConcurrent` 开启时,快照的并发行才进入画布内容(锁屏与息屏各自独立)。
 */
class LyricCanvasMapperTest {

    private val duetOff = SceneCompiler.compile(
        CustomizationDocument(
            profiles = mapOf(
                SceneCompiler.SURFACE_AOD to SurfaceProfile(duetConcurrent = false)
            )
        )
    ).profiles.getValue(SceneCompiler.SURFACE_AOD)

    private val duetOn = SceneCompiler.compile(SceneCompiler.safeDefaultDocument())
        .profiles.getValue(SceneCompiler.SURFACE_AOD)

    private fun snapshot() = LyricSnapshot(
        visible = true,
        original = "main",
        durationMs = 10_000L
    ).copy(
        duetLine = LyricDuetLine(text = "second", lineStartMs = 0L, lineEndMs = 1_000L)
    )

    @Test
    fun mapperCarriesDuetWhenSurfaceWantsIt() {
        val content = snapshot().toAodCanvasContent(duetOn, duet = true)
        assertNotNull(content.duetLine)
        assertEquals("second", content.duetLine!!.text)
    }

    @Test
    fun mapperDropsDuetWhenSurfaceSwitchOff() {
        assertNull(snapshot().toAodCanvasContent(duetOff, duet = true).duetLine)
    }

    @Test
    fun mapperCarriesHarmonyAsAuxContentEvenWhenDuetSwitchOff() {
        // 和声行与「显示并发歌词(对唱)」解耦:辅助文字内容档选「和声」
        // (SECONDARY_MODE_BACKGROUND_VOCAL)时,role=BG 的 x-bg 回声即使本面关掉对唱开关
        // 也进入画布内容(走辅助行车道);同尺寸并发行(非和声)仍只认对唱开关。
        val harmonyProfile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        duetConcurrent = false,
                        secondaryMode = "BackgroundVocal"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)

        val harmonySnapshot = snapshot().copy(
            duetLine = LyricDuetLine(
                text = "echo",
                harmony = true,
                lineStartMs = 0L,
                lineEndMs = 1_000L
            )
        )
        val harmony = harmonySnapshot.toAodCanvasContent(harmonyProfile, duet = true).duetLine
        assertNotNull(harmony)
        assertTrue(harmony!!.harmony)
        assertEquals("echo", harmony.text)
        // 非和声并发行不受该档影响:对唱开关关闭时照旧丢弃。
        assertNull(snapshot().toAodCanvasContent(harmonyProfile, duet = true).duetLine)

        // 多选里取消勾选「和声」(显式关闭位)时整条和声不进画布内容——即使对唱开关开着。
        val harmonyOffProfile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        duetConcurrent = true,
                        secondaryMode = "Translation,NoHarmony"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertNull(harmonySnapshot.toAodCanvasContent(harmonyOffProfile, duet = true).duetLine)

        // 多选里勾了和声(内容集合)时同样与对唱开关解耦。
        val harmonyOnProfile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        duetConcurrent = false,
                        secondaryMode = "Translation,BackgroundVocal"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        assertNotNull(harmonySnapshot.toAodCanvasContent(harmonyOnProfile, duet = true).duetLine)
    }

    @Test
    fun mapperDropsDuetWhenSurfaceNotParticipating() {
        assertNull(snapshot().toAodCanvasContent(duetOn).duetLine)
    }

    @Test
    fun mapperResolvesInterludeDotsWindowPerSurface() {
        // 投影层只下发**原始空隙**两端,per-surface 的开关与「显示下一行」延迟映射
        // 在映射层解析:同一份快照投到两面,各自拿到自己的窗口。
        val snapshot = LyricSnapshot(
            visible = true,
            original = "main",
            durationMs = 60_000L,
            interludeStartMs = 12_000L,
            interludeEndMs = 24_000L
        )
        // 息屏:开关开、未开「显示下一行」→ 保留 1s 延迟(参考实现默认档)。
        val aod = snapshot.toAodCanvasContent(duetOn)
        assertEquals(13_000L, aod.interludeDotsStartMs)
        assertEquals(24_000L, aod.interludeDotsEndMs)

        // 开了「显示下一行歌词」:圆点从间奏起点即开始(无需为上一行保留停留)。
        val withNext = snapshot.toAodCanvasContent(
            SceneCompiler.compile(
                CustomizationDocument(
                    profiles = mapOf(
                        SceneCompiler.SURFACE_AOD to SurfaceProfile(showNextLine = true)
                    )
                )
            ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        )
        assertEquals(12_000L, withNext.interludeDotsStartMs)
        assertEquals(24_000L, withNext.interludeDotsEndMs)

        // 「辅助文字显示第二行歌词」同样把延迟映射为 0(该面也在屏上展示下一行)。
        val withSecondaryNext = snapshot.toAodCanvasContent(
            SceneCompiler.compile(
                CustomizationDocument(
                    profiles = mapOf(
                        SceneCompiler.SURFACE_AOD to SurfaceProfile(secondaryNextLine = true)
                    )
                )
            ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        )
        assertEquals(12_000L, withSecondaryNext.interludeDotsStartMs)

        // 本面开关关闭:窗口整体清空,呈现与改动前逐字一致。
        val off = snapshot.toAodCanvasContent(
            SceneCompiler.compile(
                CustomizationDocument(
                    profiles = mapOf(
                        SceneCompiler.SURFACE_AOD to SurfaceProfile(interludeCountdown = false)
                    )
                )
            ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        )
        assertEquals(0L, off.interludeDotsStartMs)
        assertEquals(0L, off.interludeDotsEndMs)
    }

    @Test
    fun mapperLeavesInterludeDotsEmptyWhenSnapshotCarriesNoWindow() {
        // 无长间奏的快照(0/0):两面都不会拿到窗口。
        val content = LyricSnapshot(
            visible = true,
            original = "main",
            durationMs = 60_000L
        ).toAodCanvasContent(duetOn)
        assertEquals(0L, content.interludeDotsStartMs)
        assertEquals(0L, content.interludeDotsEndMs)
    }

    @Test
    fun mapperCarriesSecondarySizeSettingsAndFallsBackToDefaults() {
        // 辅助字号倍率/自适应大小按面透传(per-surface);profile=null(演示/无配置)回落
        // 默认 100/true —— 与 SurfaceProfile 默认值同源,画布行装配据此拟合字号。
        val custom = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        secondaryTextSizePercent = 130,
                        secondaryAutoSize = false
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val mapped = snapshot().toAodCanvasContent(custom)
        assertEquals(130, mapped.secondaryTextSizePercent)
        assertFalse(mapped.secondaryAutoSize)

        val fallback = snapshot().toAodCanvasContent(null)
        assertEquals(
            com.eza.hyperglow.customization.SECONDARY_TEXT_SIZE_PERCENT_DEFAULT,
            fallback.secondaryTextSizePercent
        )
        assertTrue(fallback.secondaryAutoSize)
    }

    // --- 无歌词/纯音乐歌词行为(per-surface 三态,见 SurfaceProfile.noLyricsBehavior)---

    private fun pureMusicSnapshot() = LyricSnapshot(
        visible = true,
        original = "纯音乐请欣赏",
        metadata = "Song\nArtist",
        title = "Song",
        artist = "Artist",
        durationMs = 60_000L,
        pureMusic = true
    )

    private fun profileWith(behavior: String) = SceneCompiler.compile(
        CustomizationDocument(
            profiles = mapOf(
                SceneCompiler.SURFACE_AOD to SurfaceProfile(noLyricsBehavior = behavior)
            )
        )
    ).profiles.getValue(SceneCompiler.SURFACE_AOD)

    @Test
    fun mapperKeepsPureMusicMarkerWhenBehaviorIsKeep() {
        // KEEP(默认):历史行为零变化——标记行照常当歌词显示。
        val content = pureMusicSnapshot().toAodCanvasContent(profileWith("keep"))
        assertEquals("纯音乐请欣赏", content.original)
        // 歌曲信息区块保持快照原值(KEEP 不动元数据);是否显示仍由本面 metadataVisible 决定。
        assertEquals("Song\nArtist", content.metadata)
    }

    @Test
    fun mapperReplacesPureMusicMarkerWithSongInfo() {
        // MUSIC_INFO:主行槽位改显示本面组装后的歌曲信息,并清掉标记行的词级数据。
        val content = pureMusicSnapshot().toAodCanvasContent(profileWith("music_info"))
        assertEquals(content.metadata, content.original)
        assertTrue(content.original.isNotBlank())
        assertTrue(content.words.isEmpty())
        assertTrue(content.lineLevelSync.not())
    }

    @Test
    fun mapperBlanksPureMusicContentForRestoreDefault() {
        // RESTORE_DEFAULT:不显示任何歌词/占位与歌曲信息,交还系统默认(系统内容抑制另行解除)。
        val content = pureMusicSnapshot().toAodCanvasContent(profileWith("restore_default"))
        assertEquals("", content.original)
        assertEquals("", content.metadata)
        assertFalse(content.metadataVisible)
        assertTrue(content.words.isEmpty())
    }

    @Test
    fun mapperLeavesOrdinarySongUntouchedByNoLyricsBehavior() {
        // 非纯音乐曲目:任何三态设置都不改动内容(判定在投影层已定,只有纯音乐才参与)。
        val ordinary = LyricSnapshot(
            visible = true,
            original = "main",
            metadata = "Song\nArtist",
            title = "Song",
            artist = "Artist",
            durationMs = 60_000L,
            pureMusic = false
        )
        val content = ordinary.toAodCanvasContent(profileWith("restore_default"))
        assertEquals("main", content.original)
        assertEquals("Song\nArtist", content.metadata)
    }

    // --- 真无歌词(producer lyricKind == NONE,快照 noLyrics=true)三态 ---

    private fun noLyricsSnapshot(
        original: String = "🎶",
        metadata: String = "Song\nArtist",
        title: String = "Song",
        artist: String = "Artist"
    ) = LyricSnapshot(
        visible = true,
        original = original,
        metadata = metadata,
        title = title,
        artist = artist,
        durationMs = 60_000L,
        noLyrics = true
    )

    @Test
    fun mapperKeepsNoLyricsPlaceholderWhenBehaviorIsKeep() {
        // KEEP(默认):占位行照常显示,行为与接线前一致。
        val content = noLyricsSnapshot().toAodCanvasContent(profileWith("keep"))
        assertEquals("🎶", content.original)
        assertEquals("Song\nArtist", content.metadata)
    }

    @Test
    fun mapperReplacesNoLyricsPlaceholderWithSongInfo() {
        // MUSIC_INFO:主行槽位改显示本面组装后的歌曲信息,并清掉占位行的词级数据。
        val content = noLyricsSnapshot().toAodCanvasContent(profileWith("music_info"))
        assertEquals("Song\nArtist", content.original)
        assertEquals(content.metadata, content.original)
        assertTrue(content.words.isEmpty())
        assertTrue(content.lineLevelSync.not())
    }

    @Test
    fun mapperFallsBackToOriginalWhenNoLyricsMetadataIsBlank() {
        // MUSIC_INFO 但元数据为空:不能「隐藏了占位行却画不出任何东西」,回落原占位行。
        val content = noLyricsSnapshot(metadata = "", title = "", artist = "")
            .toAodCanvasContent(profileWith("music_info"))
        assertEquals("🎶", content.original)
        assertTrue(content.original.isNotBlank())
        assertEquals("", content.metadata)
    }

    @Test
    fun mapperBlanksNoLyricsContentForRestoreDefault() {
        // RESTORE_DEFAULT:不显示占位/歌词与歌曲信息,交还系统默认(系统内容抑制另行解除)。
        val content = noLyricsSnapshot().toAodCanvasContent(profileWith("restore_default"))
        assertEquals("", content.original)
        assertEquals("", content.metadata)
        assertFalse(content.metadataVisible)
        assertTrue(content.words.isEmpty())
    }

    @Test
    fun stockReleaseGateCoversPureMusicAndTrueNoLyrics() {
        // 控制器解除系统内容抑制的同一判据:纯音乐标记行与真无歌词都要在
        // RESTORE_DEFAULT 时交还系统内容;其它行为组合保持抑制。
        assertTrue(shouldReleaseStockForNoContent(true, false, "restore_default"))
        assertTrue(shouldReleaseStockForNoContent(false, true, "restore_default"))
        assertTrue(shouldReleaseStockForNoContent(true, true, "restore_default"))
        assertFalse(shouldReleaseStockForNoContent(false, true, "music_info"))
        assertFalse(shouldReleaseStockForNoContent(false, true, "keep"))
        assertFalse(shouldReleaseStockForNoContent(false, false, "restore_default"))
    }

    // --- 尾奏超时三态(per-surface,见 SurfaceProfile.tailTimeoutBehavior)---

    private fun tailSnapshot(
        original: String = "last line",
        metadata: String = "Song\nArtist",
        title: String = "Song",
        artist: String = "Artist",
        positionMs: Long = 60_000L
    ) = LyricSnapshot(
        visible = true,
        original = original,
        metadata = metadata,
        title = title,
        artist = artist,
        durationMs = 60_000L,
        lineStartMs = 50_000L,
        lineEndMs = 55_000L,
        positionMs = positionMs,
        sampledAtElapsedMs = positionMs,
        lineLevelSync = true,
        words = listOf(
            com.eza.hyperglow.root.projection.LyricWord("last", "", 50_000L, 51_000L, true, 0, 4)
        ),
        tailEligible = true
    )

    private fun tailProfileWith(
        behavior: String,
        delaySeconds: Int = 3
    ) = SceneCompiler.compile(
        CustomizationDocument(
            profiles = mapOf(
                SceneCompiler.SURFACE_AOD to SurfaceProfile(
                    tailTimeoutBehavior = behavior,
                    tailTimeoutDelaySeconds = delaySeconds
                )
            )
        )
    ).profiles.getValue(SceneCompiler.SURFACE_AOD)

    @Test
    fun mapperKeepsLastLineForTailKeep() {
        // KEEP(默认):到点后原样保留最后一行(历史行为)。
        val content = tailSnapshot().toAodCanvasContent(
            tailProfileWith("keep"),
            nowElapsedMs = 60_000L
        )
        assertEquals("last line", content.original)
        assertEquals("Song\nArtist", content.metadata)
        assertTrue(content.words.isNotEmpty())
    }

    @Test
    fun mapperReplacesLastLineWithSongInfoForTailMusicInfo() {
        val content = tailSnapshot().toAodCanvasContent(
            tailProfileWith("music_info"),
            nowElapsedMs = 60_000L
        )
        assertEquals("Song\nArtist", content.original)
        assertEquals(content.metadata, content.original)
        assertTrue(content.words.isEmpty())
        assertTrue(content.lineLevelSync.not())
    }

    @Test
    fun mapperFallsBackToLastLineWhenTailMusicInfoMetadataIsBlank() {
        val content = tailSnapshot(metadata = "", title = "", artist = "")
            .toAodCanvasContent(tailProfileWith("music_info"), nowElapsedMs = 60_000L)
        assertEquals("last line", content.original)
    }

    @Test
    fun mapperBlanksTailContentForRestoreDefault() {
        val content = tailSnapshot().toAodCanvasContent(
            tailProfileWith("restore_default"),
            nowElapsedMs = 60_000L
        )
        assertEquals("", content.original)
        assertEquals("", content.metadata)
        assertFalse(content.metadataVisible)
        assertTrue(content.words.isEmpty())
        assertEquals("", content.artworkKey)
    }

    @Test
    fun mapperDoesNotApplyTailBehaviorBeforeThisSurfacesDelay() {
        // 同一快照在 57_000(position)时:delay=3 已过 deadline(58_000)? 未到,保持最后一行;
        // 60_000 时才替换。这里验证 mapper 用本面 delay 而非无条件应用。
        val snapshot = tailSnapshot(positionMs = 57_000L)
        val before = snapshot.toAodCanvasContent(
            tailProfileWith("music_info", delaySeconds = 3),
            nowElapsedMs = 57_000L
        )
        assertEquals("last line", before.original)
        val after = snapshot.toAodCanvasContent(
            tailProfileWith("music_info", delaySeconds = 3),
            nowElapsedMs = 58_000L
        )
        assertEquals("Song\nArtist", after.original)
    }

    @Test
    fun tailTimeoutIsPerSurface() {
        // 同一快照(lineEnd=10_000、position=13_000):AOD delay=3 已到点,锁屏 delay=10 未到点。
        val snapshot = LyricSnapshot(
            visible = true,
            original = "last line",
            metadata = "Song\nArtist",
            title = "Song",
            artist = "Artist",
            durationMs = 60_000L,
            lineStartMs = 5_000L,
            lineEndMs = 10_000L,
            positionMs = 13_000L,
            sampledAtElapsedMs = 13_000L,
            tailEligible = true
        )
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        tailTimeoutBehavior = "music_info",
                        tailTimeoutDelaySeconds = 3
                    ),
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        tailTimeoutBehavior = "music_info",
                        tailTimeoutDelaySeconds = 10
                    )
                )
            )
        )
        val aod = snapshot.toAodCanvasContent(
            compiled.profiles.getValue(SceneCompiler.SURFACE_AOD),
            nowElapsedMs = 13_000L
        )
        val lockscreen = snapshot.toAodCanvasContent(
            compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN),
            nowElapsedMs = 13_000L
        )
        assertEquals("Song\nArtist", aod.original)
        assertEquals("last line", lockscreen.original)

        // 20_000 时锁屏也到点。
        val lockscreenLater = snapshot.toAodCanvasContent(
            compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN),
            nowElapsedMs = 20_000L
        )
        assertEquals("Song\nArtist", lockscreenLater.original)
    }

    @Test
    fun pureMusicAndNoLyricsTakePriorityOverTailBehavior() {
        // 纯音乐/真无歌词标记优先:tailEligible 即使为真也不叠加尾奏三态。
        val pure = tailSnapshot(original = "纯音乐请欣赏").copy(pureMusic = true)
        val pureContent = pure.toAodCanvasContent(
            tailProfileWith("restore_default"),
            nowElapsedMs = 60_000L
        )
        assertEquals("纯音乐请欣赏", pureContent.original)

        val none = tailSnapshot(original = "🎶").copy(noLyrics = true)
        val noneContent = none.toAodCanvasContent(
            tailProfileWith("restore_default"),
            nowElapsedMs = 60_000L
        )
        assertEquals("🎶", noneContent.original)
    }

    @Test
    fun stockReleaseGateCoversTailRestoreDefault() {
        assertTrue(shouldReleaseStockForTailTimeout(true, "restore_default"))
        assertFalse(shouldReleaseStockForTailTimeout(true, "music_info"))
        assertFalse(shouldReleaseStockForTailTimeout(true, "keep"))
        assertFalse(shouldReleaseStockForTailTimeout(false, "restore_default"))
    }
}
