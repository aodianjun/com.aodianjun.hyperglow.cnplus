package com.eza.hyperglow.root.aod

import com.eza.hyperglow.customization.ARTWORK_SHAPE_CIRCLE
import com.eza.hyperglow.customization.ARTWORK_SHAPE_SQUARE
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile
import com.eza.hyperglow.customization.WidgetSpec
import com.eza.hyperglow.root.projection.LyricSnapshot
import com.eza.hyperglow.root.projection.LyricRuby
import com.eza.hyperglow.root.projection.LyricWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AodCanvasLayoutTest {
    @Test
    fun semanticPaletteResolvesOnceToBoundedColors() {
        val default = resolveAodPalette(emptyMap())
        val dimmed = resolveAodPalette(
            mapOf(
                "sungText" to "dimmed",
                "metadataText" to "dimmed",
                "glow" to "external"
            )
        )

        assertNotEquals(default.sungText, dimmed.sungText)
        assertNotEquals(default.metadataText, dimmed.metadataText)
        assertEquals(default.glow, dimmed.glow)
    }

    @Test
    fun nextLineColorResolvesIndependentlyFromSecondaryText() {
        // 下一行歌词颜色是独立 token:不能回退/混用 secondaryText,
        // 否则"下一行歌词颜色"设置在行级同步路径完全无效。
        val palette = resolveAodPalette(
            mapOf(
                "nextLineText" to "#FFD9A0",
                "secondaryText" to "dimmed"
            )
        )
        assertEquals(0xFFFFD9A0.toInt(), palette.nextLineText)
        assertNotEquals(palette.nextLineText, palette.secondaryText)
        assertEquals(0.45f, staticNextLineTextFactor(), 0.0001f)
    }

    @Test
    fun secondLineColorIgnoresPresentationForm() {
        // 取色与呈现形态解耦:「辅助文字显示第二行歌词」只借辅助文字的字号/亮度样式,
        // 颜色必须恒走「下一行颜色」——否则开启该开关后"下一行颜色"设置完全失效。
        val palette = resolveAodPalette(
            mapOf(
                "nextLineText" to "#FFD9A0",
                "secondaryText" to "#88CCFF"
            )
        )
        assertEquals(
            palette.nextLineText,
            secondLineColorArgb(SecondLinePresentation.AS_SECONDARY, palette)
        )
        assertEquals(
            palette.nextLineText,
            secondLineColorArgb(SecondLinePresentation.STANDALONE, palette)
        )
        assertNotEquals(
            palette.secondaryText,
            secondLineColorArgb(SecondLinePresentation.AS_SECONDARY, palette)
        )
    }

    @Test
    fun sentenceFillSpansWrappedLinesContinuously() {
        assertEquals(listOf(1f, 1f / 3f), splitContinuousFill(0.5f, listOf(100f, 300f)))
    }

    @Test
    fun mainOnlyHorizontalSweepDistributesProgressLineByLine() {
        // 「逐行扫光」回归守卫:Left to right (main only) 必须按累计宽度逐行推进,
        // 各行依次从左到右扫光——不能退化成所有行同时扫描。
        assertEquals(
            listOf(1f, 1f / 3f),
            horizontalRowProgress("Left to right (main only)", 0.5f, listOf(100f, 300f))
        )
        // 剩余取值(旧词表/未知值)与上游归一一致,落入行级逐行推进。
        assertEquals(
            listOf(1f, 1f / 3f),
            horizontalRowProgress("Left to right (sentence)", 0.5f, listOf(100f, 300f))
        )
        assertEquals(
            listOf(1f, 1f / 3f),
            horizontalRowProgress("bogus", 0.5f, listOf(100f, 300f))
        )
    }

    @Test
    fun wholeBlockHorizontalSweepDrivesAllRowsSimultaneously() {
        // 整块兼容模式:所有可见行以同一 X 进度同时扫描,不按行分摊。
        val widths = listOf(100f, 300f, 200f)
        assertEquals(
            listOf(0.25f, 0.25f, 0.25f),
            horizontalRowProgress("Left to right (whole block)", 0.25f, widths)
        )
        assertEquals(
            listOf(1f, 1f, 1f),
            horizontalRowProgress("Left to right (whole block)", 1f, widths)
        )
    }

    @Test
    fun paletteHexTokensResolveToOpaqueColors() {
        // "#RRGGBB" 自定义字体颜色 → 不透明 ARGB
        val warm = resolveAodPalette(mapOf("sungText" to "#FFD9A0", "glow" to "#FFD9A0"))
        assertEquals(0xFFFFD9A0.toInt(), warm.sungText)
        assertEquals(0xFFFFD9A0.toInt(), warm.glow)
        // "#AARRGGBB" 丢弃 alpha;短格式 "#RGB" 展开;非法 token 回退默认白
        assertEquals(0xFF00AABB.toInt(), parseOpaqueColorOrNull("#CC00AABB"))
        assertEquals(0xFF112233.toInt(), parseOpaqueColorOrNull("#123"))
        assertNull(parseOpaqueColorOrNull("dimmed"))
        assertNull(parseOpaqueColorOrNull("#GGHHII"))
        assertNull(parseOpaqueColorOrNull("#12345"))
        assertEquals(android.graphics.Color.WHITE, resolveAodPalette(mapOf("sungText" to "#12345")).sungText)
    }

    @Test
    fun wrappedTimedTransliterationKeepsOneOrderedWordSequence() {
        val segments = listOf(
            SecondaryTimedSegment("ming yun", 80f, 10f, 0L, 100L),
            SecondaryTimedSegment("que yao", 80f, 10f, 100L, 200L),
            SecondaryTimedSegment("wo men", 80f, 10f, 200L, 300L),
            SecondaryTimedSegment("wei nan", 80f, 0f, 300L, 400L)
        )

        assertEquals(
            listOf(0 until 2, 2 until 4),
            secondaryTimedLineRanges(segments, available = 190f, maxLines = 2)
        )
        assertEquals(1f, secondaryTimedProgress(250L, 100L, 200L), 0.0001f)
        assertEquals(0.5f, secondaryTimedProgress(250L, 200L, 300L), 0.0001f)
        assertEquals(0f, secondaryTimedProgress(250L, 300L, 400L), 0.0001f)
        assertEquals(
            timedWordProgress(250L, 200L, 300L),
            secondaryTimedProgress(250L, 200L, 300L),
            0.0001f
        )
    }

    @Test
    fun adaptiveOffTimedTransliterationKeepsOneTimedVisualLine() {
        val segments = listOf(
            SecondaryTimedSegment("first", 80f, 10f, 0L, 100L),
            SecondaryTimedSegment("second", 80f, 10f, 100L, 200L),
            SecondaryTimedSegment("third", 80f, 0f, 200L, 300L)
        )

        assertEquals(
            listOf(segments.indices),
            secondaryTimedVisualRanges(
                segments,
                available = 100f,
                maxLines = 2,
                wrap = false
            )
        )
        assertEquals(0.5f, secondaryTimedProgress(150L, 100L, 200L), 0.0001f)
    }

    @Test
    fun blankWordReadingDoesNotDiscardOtherTimedReadings() {
        val words = listOf(
            AodCanvasWord("first", "first", 0L, 100L, true),
            AodCanvasWord("missing", "", 100L, 200L, true),
            AodCanvasWord("third", "third", 200L, 300L, false)
        )

        assertEquals(listOf(0, 2), timedRomanizedWordIndexes(words))
    }

    @Test
    fun repeatedTextStillChangesIdentityAcrossRowsAndTracks() {
        val first = AodCanvasLineIdentity(7L, 1_000L, 2_000L, "same")

        assertEquals(first, AodCanvasLineIdentity(7L, 1_000L, 2_000L, "same"))
        assertNotEquals(first, AodCanvasLineIdentity(7L, 3_000L, 4_000L, "same"))
        assertNotEquals(first, AodCanvasLineIdentity(8L, 1_000L, 2_000L, "same"))
    }

    @Test
    fun topToBottomFillUsesOneSharedBlockCoordinate() {
        assertEquals(100f, sharedBlockClipBottom(0f, 100f, 500f), 0.0001f)
        assertEquals(300f, sharedBlockClipBottom(0.5f, 100f, 500f), 0.0001f)
        assertEquals(500f, sharedBlockClipBottom(1f, 100f, 500f), 0.0001f)
    }

    @Test
    fun lineLevelSyncHonorsConfiguredSweepDirection() {
        assertEquals(
            "Left to right (main only)",
            resolvedLineSyncFillMode(true, "Left to right (sentence)")
        )
        assertEquals(
            "Left to right (main only)",
            resolvedLineSyncFillMode(true, "Left to right (main only)")
        )
        // 四种取值各自生效:None/Top to bottom 不再被吞掉(否则选项形同虚设)。
        assertEquals(
            "None",
            resolvedLineSyncFillMode(true, "None")
        )
        assertEquals(
            "Top to bottom",
            resolvedLineSyncFillMode(true, "Top to bottom")
        )
        assertEquals(
            "Left to right (whole block)",
            resolvedLineSyncFillMode(true, "Left to right (whole block)")
        )
    }

    @Test
    fun surfaceProfileOverridesProducerLineLevelSweepDirection() {
        val profile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        lineSyncFillMode = "Left to right (whole block)"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals(
            "Left to right (whole block)",
            LyricSnapshot(lineSyncFillMode = "Top to bottom")
                .toAodCanvasContent(profile)
                .lineSyncFillMode
        )
    }

    @Test
    fun surfaceProfileCanSuppressFuriganaWithoutChangingTransportSnapshot() {
        val profile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(rubyVisible = false)
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val snapshot = LyricSnapshot(
            original = "漢字",
            ruby = listOf(LyricRuby(0, 2, "かんじ"))
        )

        assertTrue(snapshot.ruby.isNotEmpty())
        assertTrue(snapshot.toAodCanvasContent(profile).ruby.isEmpty())
    }

    @Test
    fun metadataSizingUsesBoundedScaleAndHeightReservation() {
        assertEquals(0.5f, metadataTextSizeMultiplier(1), 0.0001f)
        assertEquals(1f, metadataTextSizeMultiplier(100), 0.0001f)
        assertEquals(2f, metadataTextSizeMultiplier(900), 0.0001f)
        assertEquals(36f, metadataWidgetHeightDp(100), 0.0001f)
        assertTrue(metadataWidgetHeightDp(200) > metadataWidgetHeightDp(50))
    }

    @Test
    fun canvasContentCarriesProfileMetadataSizePercent() {
        val profile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        metadataVisible = true,
                        metadataSizePercent = 160,
                        widgets = listOf(WidgetSpec("lyrics"), WidgetSpec("metadata"))
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val snapshot = LyricSnapshot(original = "line", metadata = "Song · Artist")

        assertEquals(160, snapshot.toAodCanvasContent(profile).metadataSizePercent)
        assertEquals(100, snapshot.toAodCanvasContent(null).metadataSizePercent)
    }

    @Test
    fun artworkGeometryScalesWithMetadataTextSize() {
        // 槽边长 = 歌曲信息字号 × 1.6;前置宽度 = 槽 + 6dp 间距(实机与预览同源)。
        assertEquals(16f, artworkSidePx(10f), 0.0001f)
        assertEquals(32f, artworkSidePx(20f), 0.0001f)
        assertEquals(16f + 6f * 2f, artworkLeadingPx(10f, 2f), 0.0001f)
    }

    @Test
    fun artworkCustomSizeIgnoresTextSizeAndReservesWidgetHeight() {
        // 关闭自适应:边长取固定 dp × density,与歌曲信息字号无关。
        assertEquals(30f, artworkSidePx(20f, 2f, adaptiveScale = false, customSizeDp = 15), 0.0001f)
        assertEquals(30f, artworkSidePx(80f, 2f, adaptiveScale = false, customSizeDp = 15), 0.0001f)
        assertEquals(30f + 6f * 2f, artworkLeadingPx(20f, 2f, false, 15), 0.0001f)
        // dp 估算与 px 公式同源:自适应 = 字号 sp × 1.6 × 字体缩放;自定义取固定值。
        assertEquals(22.4f, artworkSideDp(100, 1f, true, 22), 0.0001f)
        assertEquals(15f, artworkSideDp(100, 1f, false, 15), 0.0001f)
        // 静态高度预算:图片不超文本预算时不变,超出时按图片槽 + 上下余量抬高。
        assertEquals(36f, metadataWidgetHeightDp(100, artworkHeightDp = 15f), 0.0001f)
        assertEquals(
            60f + ARTWORK_WIDGET_VERTICAL_PADDING_DP,
            metadataWidgetHeightDp(100, artworkHeightDp = 60f),
            0.0001f
        )
    }

    @Test
    fun artworkSpinAdvancesContinuouslyOnlyWhenEnabled() {
        assertEquals(0f, artworkSpinDegrees(false, 12_345L), 0.0001f)
        assertEquals(0f, artworkSpinDegrees(true, 0L), 0.0001f)
        assertEquals(180f, artworkSpinDegrees(true, ARTWORK_SPIN_PERIOD_MS / 2), 0.0001f)
        val next = artworkSpinDegrees(true, ARTWORK_SPIN_PERIOD_MS / 2 + 16L)
        assertTrue(next > 180f && next < 360f)
    }

    @Test
    fun canvasContentCarriesPerSurfaceArtworkConfigAndFrame() {
        val snapshot = LyricSnapshot(
            original = "line",
            metadata = "Song · Artist",
            artworkJpeg = byteArrayOf(1, 2, 3),
            artworkKey = "com.music.player|song|artist"
        )
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(
                        artworkVisible = true,
                        artworkShape = ARTWORK_SHAPE_CIRCLE,
                        artworkSpin = true
                    )
                )
            )
        )

        // 歌曲图片为 per-surface:锁屏开圆形旋转、息屏默认关,两面互不影响。
        val lockscreen = compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val content = snapshot.toAodCanvasContent(lockscreen)
        assertEquals(true, content.artworkVisible)
        assertEquals(ARTWORK_SHAPE_CIRCLE, content.artworkShape)
        assertEquals(true, content.artworkSpin)
        // 默认自适应开启;关闭并设自定义尺寸后按配置透传到画布内容。
        assertEquals(true, content.artworkAdaptiveScale)
        val custom = snapshot.toAodCanvasContent(
            lockscreen.copy(artworkAdaptiveScale = false, artworkSizeDp = 48)
        )
        assertEquals(false, custom.artworkAdaptiveScale)
        assertEquals(48, custom.artworkSizeDp)
        assertEquals("com.music.player|song|artist", content.artworkKey)
        assertTrue(content.artworkJpeg.contentEquals(byteArrayOf(1, 2, 3)))

        val aod = snapshot.toAodCanvasContent(
            compiled.profiles.getValue(SceneCompiler.SURFACE_AOD)
        )
        assertEquals(false, aod.artworkVisible)
        assertTrue(aod.artworkJpeg.contentEquals(byteArrayOf(1, 2, 3)))

        // 方形下残留 spin=true 不生效(渲染/预览统一读生效值)。
        val square = snapshot.toAodCanvasContent(
            lockscreen.copy(artworkShape = ARTWORK_SHAPE_SQUARE)
        )
        assertEquals(false, square.artworkSpin)

        // 显示门槛 = profile 的 artworkVisible(渲染侧再叠帧非空);帧恒随快照透传,
        // 默认配置只关显示不丢帧(曲目帧与显示开关解耦)。
        val defaults = snapshot.toAodCanvasContent()
        assertEquals(false, defaults.artworkVisible)
        assertTrue(defaults.artworkJpeg.contentEquals(byteArrayOf(1, 2, 3)))

        // 无帧快照 + 默认配置:无封面可显(校对不过=无帧)。
        val bare = LyricSnapshot(original = "line", metadata = "Song · Artist").toAodCanvasContent()
        assertEquals(false, bare.artworkVisible)
        assertEquals(0, bare.artworkJpeg.size)
    }

    @Test
    fun loadingMetadataCanMorphOnlyIntoMatchingVisiblePersistentMetadata() {
        assertTrue(
            shouldMorphSongChangeMetadata(
                "Song · Artist",
                "Song · Artist",
                0L,
                0L,
                false,
                "Song · Artist",
                true
            )
        )
        assertFalse(
            shouldMorphSongChangeMetadata(
                "Song · Artist",
                "Song · Artist",
                0L,
                0L,
                false,
                "Other · Artist",
                true
            )
        )
        assertFalse(
            shouldMorphSongChangeMetadata(
                "Song · Artist",
                "Song · Artist",
                0L,
                0L,
                false,
                "Song · Artist",
                false
            )
        )
    }

    @Test
    fun legacyLeftToRightProfileMigratesToMainLyricSweep() {
        val profile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        lineSyncFillMode = "Left to right"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)

        assertEquals("Left to right (main only)", profile.lineSyncFillMode)
    }

    @Test
    fun lyricLineLimitSupportsOneThroughFiveAndUnboundedLayout() {
        assertEquals(1, resolvedLyricLayoutLineLimit(1, originalLength = 50, wordCount = 10))
        assertEquals(5, resolvedLyricLayoutLineLimit(5, originalLength = 50, wordCount = 10))
        assertEquals(50, resolvedLyricLayoutLineLimit(0, originalLength = 50, wordCount = 10))
    }

    @Test
    fun secondaryTextBrightnessIsStaticAndProfileControlled() {
        val profile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(secondaryTextBright = false)
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val content = LyricSnapshot(romanized = "reading")
            .toAodCanvasContent(profile)

        assertFalse(content.secondaryTextBright)
        assertEquals(1f, staticSecondaryTextFactor(true), 0.0001f)
        assertEquals(0.35f, staticSecondaryTextFactor(false), 0.0001f)
    }

    @Test
    fun secondaryNextLineMapsFromProfileAndDefaultsToOff() {
        val on = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(secondaryNextLine = true)
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val snapshot = LyricSnapshot(original = "current", nextLine = "second")

        assertTrue(snapshot.toAodCanvasContent(on).secondaryNextLine)
        // 无 profile 或未开启时默认关闭,既有呈现零变化。
        assertFalse(snapshot.toAodCanvasContent(null).secondaryNextLine)
        assertFalse(snapshot.toAodCanvasContent().secondaryNextLine)
    }

    @Test
    fun lineTransitionSpeedMapsFromProfileAndDefaultsToNormal() {
        val on = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(lineTransitionSpeed = "Slow")
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val snapshot = LyricSnapshot(original = "current")

        assertEquals("Slow", snapshot.toAodCanvasContent(on).lineTransitionSpeed)
        // 无 profile 时默认 Normal(基准时长),既有呈现零变化。
        assertEquals("Normal", snapshot.toAodCanvasContent(null).lineTransitionSpeed)
        assertEquals("Normal", snapshot.toAodCanvasContent().lineTransitionSpeed)
    }

    @Test
    fun rowAlignmentsMapFromProfileAndDefaultToAuto() {
        val profile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        metadataAlignment = "center",
                        nextLineAlignment = "end"
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val snapshot = LyricSnapshot(original = "current", nextLine = "second")

        assertEquals("center", snapshot.toAodCanvasContent(profile).metadataAlignment)
        assertEquals("end", snapshot.toAodCanvasContent(profile).nextLineAlignment)
        // 无 profile 时默认 auto(跟随主对齐),既有呈现零变化。
        assertEquals("auto", snapshot.toAodCanvasContent(null).metadataAlignment)
        assertEquals("auto", snapshot.toAodCanvasContent().nextLineAlignment)
    }

    @Test
    fun duetAlignmentGatesLineSideAndDefaultsToOn() {
        val off = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(duetAlignment = false)
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val on = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(duetAlignment = true)
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        // 快照按新契约同时携带两套分侧值(元数据身份版 + 标记识别版);本用例不区分两版,
        // 故同取 true 以表示「该行分侧」。标记识别开关的按面选用见
        // surfaceDuetMarkersStripAndSelectAlignmentIndependently。
        val split = LyricSnapshot(
            original = "current",
            alignedRight = true,
            alignedRightMarkers = true
        )

        // 开启(或无 profile 的默认)时保留行级分侧;关闭时整行回落主对齐解析。
        assertTrue(split.toAodCanvasContent(on).alignedRight)
        assertTrue(split.toAodCanvasContent(null).alignedRight)
        assertFalse(split.toAodCanvasContent(off).alignedRight)
        // 门控只做减法:未分侧的行在任何开关下都保持未分侧。
        assertFalse(
            LyricSnapshot(original = "current")
                .toAodCanvasContent(on).alignedRight
        )
    }

    @Test
    fun surfaceMetadataComposesFromOwnProfilePartsAndSeparators() {
        // 「歌曲信息内容」per-surface:息屏显式设置(专辑→歌名,换行),锁屏未显式设置时继承
        // 文档级默认值(歌名·歌手)。同一份快照下两面各自组装,互不联动。
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                metadataParts = "title,artist",
                metadataSeparators = "dot",
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        metadataParts = "album,title",
                        metadataSeparators = "newline"
                    ),
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile()
                )
            )
        )
        val aod = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD)
        val lockscreen = compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val snapshot = LyricSnapshot(
            original = "line",
            title = "Song",
            artist = "Artist",
            album = "Album"
        )

        assertEquals("Album\nSong", snapshot.toAodCanvasContent(aod).metadata)
        assertEquals("Song · Artist", snapshot.toAodCanvasContent(lockscreen).metadata)
    }

    @Test
    fun metadataOnlySnapshotKeepsLegacyComposedValueWhenNoRawSlices() {
        // 快照未携带原始歌名/歌手/专辑(旧消费方/演示态只给文档级组装值)时,按面重组不得把这份
        // 兜底值清空——回落快照 metadata;携带原始切片时才按本面重组(见上一则测试)。
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(metadataParts = "album")
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val snapshot = LyricSnapshot(original = "line", metadata = "Song · Artist")

        assertEquals("Song · Artist", snapshot.toAodCanvasContent(compiled).metadata)
    }

    @Test
    fun surfaceDuetMarkersStripAndSelectAlignmentIndependently() {
        // 「识别对唱标记」per-surface:息屏开启则隐去标记并取标记识别版分侧;锁屏关闭则原样显示
        // 标记并取元数据身份版分侧。同一份快照(两版分侧随状态下发)下两面各自决策。
        val compiled = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(duetMarkers = true),
                    SceneCompiler.SURFACE_LOCKSCREEN to SurfaceProfile(duetMarkers = false)
                )
            )
        )
        val aod = compiled.profiles.getValue(SceneCompiler.SURFACE_AOD)
        val lockscreen = compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        val snapshot = LyricSnapshot(
            original = "（女） 词",
            nextLine = "（男） 下一句",
            alignedRight = false,
            alignedRightMarkers = true
        )

        val aodContent = snapshot.toAodCanvasContent(aod, duet = true)
        assertEquals("词", aodContent.original)
        assertEquals("下一句", aodContent.nextLine)
        assertTrue(aodContent.alignedRight)

        val lockscreenContent = snapshot.toAodCanvasContent(lockscreen)
        assertEquals("（女） 词", lockscreenContent.original)
        assertEquals("（男） 下一句", lockscreenContent.nextLine)
        assertFalse(lockscreenContent.alignedRight)
    }

    @Test
    fun duetAlignedRightOnlyGatesTheLineSideBit() {
        // 真值表:开关关闭时忽略行级 alignedRight,开启时原样透传。
        assertTrue(duetAlignedRight(alignedRight = true, duetAlignment = true))
        assertFalse(duetAlignedRight(alignedRight = true, duetAlignment = false))
        assertFalse(duetAlignedRight(alignedRight = false, duetAlignment = true))
        assertFalse(duetAlignedRight(alignedRight = false, duetAlignment = false))
    }

    @Test
    fun resolveRowAlignmentModeExplicitOverridesAndAutoFollowsMain() {
        // 显式 start/center/end 直接生效,不受主对齐影响。
        assertEquals("start", resolveRowAlignmentMode("start", "end", false))
        assertEquals("center", resolveRowAlignmentMode("center", "start", true))
        assertEquals("end", resolveRowAlignmentMode("end", "center", false))
        // auto 跟随主对齐解析结果(主 auto 时按歌词方向右对齐,否则左对齐)。
        assertEquals("center", resolveRowAlignmentMode("auto", "center", false))
        assertEquals("start", resolveRowAlignmentMode("auto", "start", true))
        assertEquals("end", resolveRowAlignmentMode("auto", "auto", true))
        assertEquals("start", resolveRowAlignmentMode("auto", "auto", false))
        // 非法值按 auto 处理(与编译白名单的回落语义一致)。
        assertEquals("end", resolveRowAlignmentMode("bogus", "end", false))
        assertEquals("end", resolveRowAlignmentMode("diagonal", "auto", true))
    }

    @Test
    fun secondLinePresentationNeverStacksBothForms() {
        // 呈现决策(实机/预览同源):「辅助文字显示第二行歌词」仅在第一行辅助文字实际显示时
        // 生效,以辅助形态取代独立下一行行,同一行不重复出现;第一行无辅助文字时该开关不产生
        // 第二行呈现,独立下一行行按「显示下一行歌词」照常;「显示第二行辅助文字」开启时
        // 第二行歌词行本身也按辅助形态呈现(以「显示下一行歌词」为前提,即使「辅助文字显示
        // 第二行歌词」关闭);两个第二行开关都关闭或无下行文本时一律为空。
        assertEquals(
            SecondLinePresentation.AS_SECONDARY,
            secondLinePresentation(
                secondaryNextLine = true,
                showNextLine = true,
                hasLine = true,
                hasFirstLineAux = true,
                nextLineAux = false
            )
        )
        assertEquals(
            SecondLinePresentation.AS_SECONDARY,
            secondLinePresentation(
                secondaryNextLine = true,
                showNextLine = false,
                hasLine = true,
                hasFirstLineAux = true,
                nextLineAux = false
            )
        )
        // 第一行无辅助文字:该开关不产生辅助形态,回落到独立下一行行。
        assertEquals(
            SecondLinePresentation.STANDALONE,
            secondLinePresentation(
                secondaryNextLine = true,
                showNextLine = true,
                hasLine = true,
                hasFirstLineAux = false,
                nextLineAux = false
            )
        )
        assertEquals(
            SecondLinePresentation.NONE,
            secondLinePresentation(
                secondaryNextLine = true,
                showNextLine = false,
                hasLine = true,
                hasFirstLineAux = false,
                nextLineAux = false
            )
        )
        assertEquals(
            SecondLinePresentation.STANDALONE,
            secondLinePresentation(
                secondaryNextLine = false,
                showNextLine = true,
                hasLine = true,
                hasFirstLineAux = true,
                nextLineAux = false
            )
        )
        assertEquals(
            SecondLinePresentation.NONE,
            secondLinePresentation(
                secondaryNextLine = false,
                showNextLine = false,
                hasLine = true,
                hasFirstLineAux = false,
                nextLineAux = false
            )
        )
        assertEquals(
            SecondLinePresentation.NONE,
            secondLinePresentation(
                secondaryNextLine = true,
                showNextLine = true,
                hasLine = false,
                hasFirstLineAux = true,
                nextLineAux = false
            )
        )
        // 「显示第二行辅助文字」开启:第二行歌词行本身按辅助形态呈现(以「显示下一行歌词」
        // 为前提,即使「辅助文字显示第二行歌词」关闭;第一行无辅助文字同样成立)。
        assertEquals(
            SecondLinePresentation.AS_SECONDARY,
            secondLinePresentation(
                secondaryNextLine = false,
                showNextLine = true,
                hasLine = true,
                hasFirstLineAux = true,
                nextLineAux = true
            )
        )
        assertEquals(
            SecondLinePresentation.AS_SECONDARY,
            secondLinePresentation(
                secondaryNextLine = false,
                showNextLine = true,
                hasLine = true,
                hasFirstLineAux = false,
                nextLineAux = true
            )
        )
        // 两个第二行开关都关闭:没有第二行歌词行,本开关不产生呈现。
        assertEquals(
            SecondLinePresentation.NONE,
            secondLinePresentation(
                secondaryNextLine = false,
                showNextLine = false,
                hasLine = true,
                hasFirstLineAux = true,
                nextLineAux = true
            )
        )
    }

    @Test
    fun secondLineRendersAsSecondaryFollowsPresentation() {
        // 辅助形态判定(绘制期取色/亮度档用)与呈现决策同源:两处读同一判定,防止漂移。
        assertTrue(
            secondLineRendersAsSecondary(
                secondaryNextLine = true,
                showNextLine = false,
                hasLine = true,
                hasFirstLineAux = true,
                nextLineAux = false
            )
        )
        assertFalse(
            secondLineRendersAsSecondary(
                secondaryNextLine = true,
                showNextLine = false,
                hasLine = true,
                hasFirstLineAux = false,
                nextLineAux = false
            )
        )
        assertFalse(
            secondLineRendersAsSecondary(
                secondaryNextLine = false,
                showNextLine = false,
                hasLine = true,
                hasFirstLineAux = true,
                nextLineAux = false
            )
        )
        // 「显示第二行辅助文字」开启 + 「显示下一行歌词」开启:即使「辅助文字显示第二行歌词」
        // 关闭、第一行无辅助文字,第二行也按辅助形态取色/亮度。
        assertTrue(
            secondLineRendersAsSecondary(
                secondaryNextLine = false,
                showNextLine = true,
                hasLine = true,
                hasFirstLineAux = false,
                nextLineAux = true
            )
        )
        // 「显示下一行歌词」关闭:单独的「显示第二行辅助文字」不产生辅助形态。
        assertFalse(
            secondLineRendersAsSecondary(
                secondaryNextLine = false,
                showNextLine = false,
                hasLine = true,
                hasFirstLineAux = true,
                nextLineAux = true
            )
        )
        assertFalse(
            secondLineRendersAsSecondary(
                secondaryNextLine = false,
                showNextLine = true,
                hasLine = false,
                hasFirstLineAux = false,
                nextLineAux = true
            )
        )
    }

    @Test
    fun firstLineAuxTextFollowsModeAndContent() {
        // 第一行辅助文字是否实际显示(实机行装配/预览同源):按模式取音标/翻译,非空即成立。
        assertTrue(hasFirstLineAuxText("Transliteration", "roma", ""))
        assertTrue(hasFirstLineAuxText("Translation", "", "trans"))
        assertTrue(hasFirstLineAuxText("Both", "roma", "trans"))
        assertFalse(hasFirstLineAuxText("Main only", "roma", "trans"))
        assertFalse(hasFirstLineAuxText("Transliteration", "", "trans"))
        assertFalse(hasFirstLineAuxText("Translation", "roma", ""))
        assertFalse(hasFirstLineAuxText("Both", "", ""))
    }

    @Test
    fun secondLineAuxPreferredLinesFollowsTheSecondLine() {
        // 第二行辅助行的换行档跟随第二行自身呈现的行数(owner 2026-10-02 真机反馈:
        // 此前误用主行行数);空/非法布局回落 1(永远至少一行)。
        assertEquals(1, secondLineAuxPreferredLines(1))
        assertEquals(3, secondLineAuxPreferredLines(3))
        assertEquals(1, secondLineAuxPreferredLines(0))
        assertEquals(1, secondLineAuxPreferredLines(-2))
    }

    @Test
    fun secondLineAuxRowsFollowModeAndContent() {
        // 「显示第二行辅助文字」行清单(实机/预览同源):开关关闭恒空;按辅助文字模式取行,
        // 文本为空则跳过;Both 档音标在前翻译在后。
        assertTrue(secondLineAuxRows(false, "Both", "roma", "trans").isEmpty())
        assertEquals(
            listOf(SecondLineAuxRow.TRANSLATED),
            secondLineAuxRows(true, "Translation", "roma", "trans")
        )
        assertEquals(
            listOf(SecondLineAuxRow.ROMANIZED),
            secondLineAuxRows(true, "Transliteration", "roma", "trans")
        )
        assertEquals(
            listOf(SecondLineAuxRow.ROMANIZED, SecondLineAuxRow.TRANSLATED),
            secondLineAuxRows(true, "Both", "roma", "trans")
        )
        assertTrue(secondLineAuxRows(true, "Main only", "roma", "trans").isEmpty())
        assertEquals(
            listOf(SecondLineAuxRow.TRANSLATED),
            secondLineAuxRows(true, "Both", "", "trans")
        )
        assertTrue(secondLineAuxRows(true, "Both", "", "").isEmpty())
    }

    @Test
    fun gradientSweepUsesBroadZoneAndFinishesOutsideVisibleExtent() {
        assertEquals(GradientSweepZone(-40f, 0f), gradientSweepZone(0f, 100f))
        assertEquals(GradientSweepZone(30f, 70f), gradientSweepZone(0.5f, 100f))
        assertEquals(GradientSweepZone(100f, 140f), gradientSweepZone(1f, 100f))
    }

    @Test
    fun lineLevelRowsWithTransportWordsAlwaysUseSharedGlowPipeline() {
        val content = LyricSnapshot(
            original = "絡み合う迷宮",
            lineLevelSync = true,
            lineStartMs = 1_000L,
            lineEndMs = 3_000L,
            words = listOf(LyricWord("絡み合う", "karamiau", 1_000L, 2_000L, false))
        ).toAodCanvasContent()

        assertTrue(content.lineLevelSync)
        // 发光开启时必须走共享渲染核心(LyricGlowRenderer)的预览管线,
        // 否则行级同步源渲染旧渐变扫光,AOD 与预览不一致。
        assertTrue(
            usesPreviewGlowPipeline(
                animationMode = content.animationMode,
                timed = true,
                lineLevelSync = content.lineLevelSync,
                glowMode = "On"
            )
        )
        // 行级同步 + 关闭发光:同样走统一管线(dim 底 + 扫光,无光晕)
        assertTrue(
            usesPreviewGlowPipeline(
                animationMode = content.animationMode,
                timed = true,
                lineLevelSync = true,
                glowMode = "Off"
            )
        )
        // 逐字时间源 + 关闭发光 + 非行级同步:保留逐字卡拉OK路径
        assertFalse(
            usesPreviewGlowPipeline(
                animationMode = content.animationMode,
                timed = true,
                lineLevelSync = false,
                glowMode = "Off"
            )
        )
        // Minimal 模式永远不走扫光管线
        assertFalse(
            usesPreviewGlowPipeline(
                animationMode = "Minimal",
                timed = true,
                lineLevelSync = true,
                glowMode = "On"
            )
        )
    }

    @Test
    fun unifiedBlockProgressPrefersLineTimingThenWordSpans() {
        // 行级时间有效:完全对齐预览 previewProjectedProgress 的行区间归一
        assertEquals(
            0.5f,
            unifiedBlockProgress(1_500L, 1_000L, 2_000L, emptyList()),
            0.0001f
        )
        // 行级时间无效 + 词级时间有效:回退全局首词→末词范围 [1000,3000],
        // 1500 处即 (1500-1000)/(3000-1000) = 0.25
        assertEquals(
            0.25f,
            unifiedBlockProgress(
                positionMs = 1_500L,
                lineStartMs = 0L,
                lineEndMs = 0L,
                words = listOf(
                    AodCanvasWord("絡み", "karami", 1_000L, 2_000L, true),
                    AodCanvasWord("合う", "au", 2_000L, 3_000L, false)
                )
            ),
            0.0001f
        )
        // 两者皆无:退化区间语义,已到达即全亮(静态整行,与旧 lineProgress 行为一致)
        assertEquals(
            1f,
            unifiedBlockProgress(positionMs = 0L, lineStartMs = 0L, lineEndMs = 0L, words = emptyList()),
            0.0001f
        )
    }

    @Test
    fun lineLevelRowsWithTransportWordsStillUseOneSharedCanvasSweep() {
        val content = LyricSnapshot(
            original = "絡み合う迷宮",
            lineLevelSync = true,
            lineStartMs = 1_000L,
            lineEndMs = 3_000L,
            words = listOf(LyricWord("絡み合う", "karamiau", 1_000L, 2_000L, false))
        ).toAodCanvasContent()

        assertTrue(content.lineLevelSync)
        assertTrue(
            shouldUseSharedLineLevelSweep(
                lineLevelSync = content.lineLevelSync,
                hasOriginalLines = true,
                animationMode = content.animationMode,
                lineStartMs = content.lineStartMs,
                lineEndMs = content.lineEndMs
            )
        )
        assertFalse(
            shouldUseSharedLineLevelSweep(
                lineLevelSync = false,
                hasOriginalLines = true,
                animationMode = content.animationMode,
                lineStartMs = content.lineStartMs,
                lineEndMs = content.lineEndMs
            )
        )
    }

    @Test
    fun betterLyricsNeverTakesTheSharedLineLevelSweep() {
        // BetterLyrics 档必须落在词级卡拉OK路径:此前带行窗的源在 drawRows 就被共享扫光门
        // 拦下,实机永远走不到 drawWordKaraoke(表现即「预览有逐字效果、实机没有」)。
        assertFalse(
            shouldUseSharedLineLevelSweep(
                lineLevelSync = true,
                hasOriginalLines = true,
                animationMode = "BetterLyrics",
                lineStartMs = 1_000L,
                lineEndMs = 3_000L
            )
        )
        // 其余非 Minimal 档不受影响:带行窗的行级同步源仍走共享逐行扫光。
        assertTrue(
            shouldUseSharedLineLevelSweep(
                lineLevelSync = true,
                hasOriginalLines = true,
                animationMode = "Gradient",
                lineStartMs = 1_000L,
                lineEndMs = 3_000L
            )
        )
        assertFalse(
            shouldUseSharedLineLevelSweep(
                lineLevelSync = true,
                hasOriginalLines = true,
                animationMode = "Minimal",
                lineStartMs = 1_000L,
                lineEndMs = 3_000L
            )
        )
    }

    @Test
    fun originalLinePlanKeepsPreviewAndDeviceOnOneRouting() {
        // 逐字源 + 行级同步 + 带行窗(实机稳态最常见组合):取配置的逐行扫光,
        // 不再是预览整块、实机逐行。
        assertEquals(
            OriginalLinePlan(OriginalLinePath.BLOCK_SWEEP, "Left to right (main only)"),
            planOriginalLine(
                animationMode = "Gradient",
                timed = true,
                lineLevelSync = true,
                glowMode = "Off",
                lineSyncFillMode = "Left to right (main only)",
                lineStartMs = 1_000L,
                lineEndMs = 3_000L
            )
        )
        // 显式整块兼容档仍是整块(整块只保留给显式选择,不被逐行归一吞掉)。
        assertEquals(
            OriginalLinePlan(OriginalLinePath.BLOCK_SWEEP, "Left to right (whole block)"),
            planOriginalLine(
                animationMode = "Gradient",
                timed = true,
                lineLevelSync = true,
                glowMode = "On",
                lineSyncFillMode = "Left to right (whole block)",
                lineStartMs = 1_000L,
                lineEndMs = 3_000L
            )
        )
        // BetterLyrics 档:逐字源(真实词窗)与行级源(字符合成)都走词级卡拉OK。
        assertEquals(
            OriginalLinePlan(OriginalLinePath.WORD_KARAOKE, "Left to right (main only)"),
            planOriginalLine(
                animationMode = "BetterLyrics",
                timed = true,
                lineLevelSync = true,
                glowMode = "Off",
                lineSyncFillMode = "Left to right (main only)",
                lineStartMs = 1_000L,
                lineEndMs = 3_000L
            )
        )
        assertEquals(
            OriginalLinePlan(OriginalLinePath.WORD_KARAOKE, "Left to right (main only)"),
            planOriginalLine(
                animationMode = "BetterLyrics",
                timed = false,
                lineLevelSync = true,
                glowMode = "On",
                lineSyncFillMode = "Left to right (main only)",
                lineStartMs = 1_000L,
                lineEndMs = 3_000L
            )
        )
        // Minimal 档与「行进度效果=None」都是静态全亮(None 经共享管线解析为静态)。
        assertEquals(
            OriginalLinePlan(OriginalLinePath.STATIC, "None"),
            planOriginalLine(
                animationMode = "Minimal",
                timed = true,
                lineLevelSync = true,
                glowMode = "On",
                lineSyncFillMode = "Left to right (main only)",
                lineStartMs = 1_000L,
                lineEndMs = 3_000L
            )
        )
        assertEquals(
            OriginalLinePlan(OriginalLinePath.BLOCK_SWEEP, "None"),
            planOriginalLine(
                animationMode = "BetterLyrics",
                timed = true,
                lineLevelSync = true,
                glowMode = "On",
                lineSyncFillMode = "None",
                lineStartMs = 1_000L,
                lineEndMs = 3_000L
            )
        )
        // 无行窗的逐字源 + 发光开:走共享扫光块,且取配置效果而非硬编码整块
        // (此前 drawOriginal 的 4 参数重载默认整块,用户选了逐行也会被整块覆盖)。
        assertEquals(
            OriginalLinePlan(OriginalLinePath.BLOCK_SWEEP, "Left to right (main only)"),
            planOriginalLine(
                animationMode = "Gradient",
                timed = true,
                lineLevelSync = true,
                glowMode = "On",
                lineSyncFillMode = "Left to right (main only)",
                lineStartMs = 0L,
                lineEndMs = 0L
            )
        )
        // 大元数据引导态(非行级同步)+ 逐字源 + 关闭发光:保留基础词级卡拉OK路径。
        assertEquals(
            OriginalLinePlan(OriginalLinePath.WORD_KARAOKE, "Left to right (main only)"),
            planOriginalLine(
                animationMode = "Gradient",
                timed = true,
                lineLevelSync = false,
                glowMode = "Off",
                lineSyncFillMode = "Left to right (main only)",
                lineStartMs = 1_000L,
                lineEndMs = 3_000L
            )
        )
        // 行级(无逐字时间)源:同样取生效进度效果(Top to bottom 不被吞掉)。
        assertEquals(
            OriginalLinePlan(OriginalLinePath.BLOCK_SWEEP, "Top to bottom"),
            planOriginalLine(
                animationMode = "Gradient",
                timed = false,
                lineLevelSync = true,
                glowMode = "Off",
                lineSyncFillMode = "Top to bottom",
                lineStartMs = 1_000L,
                lineEndMs = 3_000L
            )
        )
    }

    @Test
    fun rubyStartSelectsContainingWrappedLine() {
        assertEquals(0, rubyLineIndex(4, listOf(0, 8), listOf(8, 16)))
        assertEquals(1, rubyLineIndex(8, listOf(0, 8), listOf(8, 16)))
        assertNull(rubyLineIndex(16, listOf(0, 8), listOf(8, 16)))
    }

    @Test
    fun rubySpanOverhangDoesNotMoveBaseRun() {
        val geometry = rubySpanGeometry(10f, 20f, 30f)
        assertEquals(5f, geometry.spanX, 0.0001f)
        assertEquals(30f, geometry.spanWidth, 0.0001f)
        assertEquals(10f, geometry.baseX, 0.0001f)
        assertEquals(20f, geometry.baseWidth, 0.0001f)
        assertEquals(20f, geometry.rubyCenterX, 0.0001f)
        assertEquals(0f, geometry.extraWidth, 0.0001f)
    }

    @Test
    fun centeredRubyDrawUsesBaseCenterWithoutSubtractingHalfWidthTwice() {
        assertEquals(30f, rubyDrawCenterX(10f, 20f), 0.0001f)
    }

    @Test
    fun narrowerRubyKeepsNeighborCoordinatesUnchanged() {
        val geometry = rubySpanGeometry(10f, 20f, 24f)
        assertEquals(8f, geometry.spanX, 0.0001f)
        assertEquals(24f, geometry.spanWidth, 0.0001f)
        assertEquals(10f, geometry.baseX, 0.0001f)
        assertEquals(0f, geometry.extraWidth, 0.0001f)
    }

    @Test
    fun rubyBaseTextRunsPrecomputePlainAndRubyCoordinates() {
        val measuredEnds = mutableListOf<Int>()

        assertEquals(
            listOf(
                OriginalTextRun(0, 2, 0f),
                OriginalTextRun(2, 4, 20f),
                OriginalTextRun(4, 6, 40f),
                OriginalTextRun(6, 8, 60f)
            ),
            originalTextRuns(
                textLength = 8,
                rubyBaseRuns = listOf(
                    OriginalTextRun(2, 4, 20f),
                    OriginalTextRun(4, 6, 40f)
                )
            ) { end ->
                measuredEnds += end
                end * 10f
            }
        )
        assertEquals(listOf(0, 6), measuredEnds)
    }

    @Test
    fun overlappingRubyBaseTextRunsNeverRedrawConsumedText() {
        assertEquals(
            listOf(
                OriginalTextRun(0, 2, 0f),
                OriginalTextRun(2, 5, 20f),
                OriginalTextRun(5, 7, 30f),
                OriginalTextRun(7, 8, 70f)
            ),
            originalTextRuns(
                textLength = 8,
                rubyBaseRuns = listOf(
                    OriginalTextRun(2, 5, 20f),
                    OriginalTextRun(3, 7, 30f)
                )
            ) { end -> end * 10f }
        )
    }

    @Test
    fun sizeLadderUsesLiveCardMultiplier() {
        assertEquals(28f * 0.68f, baseTextSizeSp("x"), 0.0001f)
        assertEquals(26f * 0.68f, baseTextSizeSp("x".repeat(14)), 0.0001f)
        assertEquals(24f * 0.68f, baseTextSizeSp("x".repeat(22)), 0.0001f)
        assertEquals(23f * 0.68f, baseTextSizeSp("x".repeat(30)), 0.0001f)
    }

    @Test
    fun sizeModeMultipliersMatchSpec() {
        assertEquals(0.9f, textSizeModeMultiplier("small", 100), 0.0001f)
        assertEquals(1f, textSizeModeMultiplier("normal", 100), 0.0001f)
        assertEquals(1.2f, textSizeModeMultiplier("large", 100), 0.0001f)
        assertEquals(1.5f, textSizeModeMultiplier("xlarge", 100), 0.0001f)
        assertEquals(0f, textSizeModeMultiplier("custom", -10), 0.0001f)
        assertEquals(5f, textSizeModeMultiplier("custom", 900), 0.0001f)
    }

    @Test
    fun legacyScrollModeMapsToWrap() {
        assertEquals("Wrap", normalizeAodOverflow("Scroll with lyric"))
        assertEquals("Wrap", normalizeAodOverflow("auto"))
        assertEquals("Clip", normalizeAodOverflow("Clip"))
    }

    @Test
    fun transportedRangesArePrimaryAndInvalidRangesAreIgnored() {
        val word = AodCanvasWord("重複", "", 0L, 1L, false, 3, 5)
        assertEquals(3 until 5, transportedWordOffset("重複 重複", word))
        assertNull(transportedWordOffset("重複", word))
    }

    @Test
    fun currentWordControlsFollowingGap() {
        assertEquals(0f, aodWordGapAfter(false, 8f), 0.0001f)
        assertEquals(8f, aodWordGapAfter(true, 8f), 0.0001f)
    }

    @Test
    fun adaptiveOffNeverWrapsBetweenAttachedWordFragments() {
        val words = listOf(
            AodCanvasWord("hello", "", 0L, 100L, true),
            AodCanvasWord("phra", "", 100L, 200L, false),
            AodCanvasWord("se", "", 200L, 300L, false)
        )

        assertEquals(listOf(0 until 1, 1 until 3), attachedWordRanges(words))
        assertEquals(
            listOf(0 until 1, 1 until 3),
            legacyAttachedWordLineRanges(
                words = words,
                wordWidths = listOf(50f, 40f, 40f),
                gapAfters = listOf(5f, 0f, 0f),
                available = 100f,
                maxLines = 3
            )
        )
    }

    @Test
    fun camouflageFragmentsUseTrailingEdgeBoundaries() {
        val words = listOf(
            AodCanvasWord("My", "My", 0L, 100L, true),
            AodCanvasWord("Camoufla", "Camoufla", 100L, 200L, false),
            AodCanvasWord("ge", "ge", 200L, 300L, false)
        )

        assertEquals(listOf(0 until 1, 1 until 3), attachedWordRanges(words))
        assertEquals("My Camouflage", joinedRomanizedWords(words.map { it.romanized to it.boundaryAfter }))
    }

    @Test
    fun serializedOffsetsPreserveAuthoredJapaneseAndSpaceSeparators() {
        val adjacent = authoredWordSeparator(
            "朝か昼か",
            AodCanvasWord("朝か", "", 0L, 1L, false, 0, 2),
            AodCanvasWord("昼か", "", 1L, 2L, false, 2, 4)
        )
        val spaced = authoredWordSeparator(
            "day night",
            AodCanvasWord("day", "", 0L, 1L, true, 0, 3),
            AodCanvasWord("night", "", 1L, 2L, false, 4, 9)
        )

        assertEquals("", adjacent)
        assertEquals(" ", spaced)
    }

    @Test
    fun rubyCrossingTwoRangesCoalescesWithoutSyntheticBoundaryGap() {
        val words = coalesceRubyWords(
            "甲乙",
            listOf(
                AodCanvasWord("甲", "ka", 0L, 100L, false, 0, 1),
                AodCanvasWord("乙", "otsu", 100L, 200L, false, 1, 2)
            ),
            listOf(AodCanvasRuby(0, 2, "かおつ"))
        )

        assertEquals(1, words.size)
        assertEquals("甲乙", words[0].text)
        assertEquals(0, words[0].sourceStart)
        assertEquals(2, words[0].sourceEnd)
    }

    @Test
    fun coalescedRubyWordKeepsFinalTokenBoundaryGap() {
        val words = coalesceRubyWords(
            "甲乙 丙",
            listOf(
                AodCanvasWord("甲", "ka", 0L, 100L, false, 0, 1),
                AodCanvasWord("乙", "otsu", 100L, 200L, true, 1, 2),
                AodCanvasWord("丙", "hei", 200L, 300L, false, 3, 4)
            ),
            listOf(AodCanvasRuby(0, 2, "かおつ"))
        )

        assertTrue(words[0].boundaryAfter)
        assertEquals(8f, aodWordGapAfter(words[0].boundaryAfter, 8f), 0.0001f)
    }

    @Test
    fun invalidTransportedRangesDowngradeRubyOwnershipSafely() {
        val words = coalesceRubyWords(
            "甲乙",
            listOf(
                AodCanvasWord("甲", "", 0L, 100L, false, -1, -1),
                AodCanvasWord("乙", "", 100L, 200L, false, 1, 3)
            ),
            listOf(AodCanvasRuby(0, 2, "かおつ"))
        )

        assertEquals(2, words.size)
    }

    @Test
    fun metadataBottomReservesSpaceAndStaysAtCanvasBottom() {
        val bounds = metadataLayoutBounds("bottom", 360f, 8f, 8f, -12f, 4f, 10f)
        assertEquals(348f, bounds.metadataBaseline, 0.0001f)
        assertEquals(326f, bounds.lyricEnd, 0.0001f)
    }

    @Test
    fun rubyReservationAndRowStackScaleWithNormalAndXlargeSizes() {
        val normalBase = baseTextSizeSp("x")
        val xlargeBase = normalBase * textSizeModeMultiplier("xlarge", 100)
        val normalRuby = rubyReservation(normalBase, -normalBase * 0.46f)
        val xlargeRuby = rubyReservation(xlargeBase, -xlargeBase * 0.46f)
        assertEquals(normalBase * 0.58f, normalRuby, 0.0001f)
        assertEquals(xlargeBase * 0.58f, xlargeRuby, 0.0001f)
        assertEquals(80f + normalRuby * 2f, originalRowHeight(40f, 2, normalRuby * 2f), 0.0001f)
        assertEquals(40f + xlargeRuby * 2f, originalLineBaseline(0f, 1, 40f, xlargeRuby, xlargeRuby), 0.0001f)
        assertEquals(
            40f + xlargeRuby * 2f + 4f,
            originalLineBaseline(0f, 1, 40f, xlargeRuby, xlargeRuby, 4f),
            0.0001f
        )
        assertEquals(84f + normalRuby * 2f, originalRowHeight(40f, 2, normalRuby * 2f, 4f), 0.0001f)
    }

    @Test
    fun endAlignmentReservesVisualOverhangAndAnimationSafety() {
        assertEquals(
            76f,
            edgeSafeAlignedStart(
                canvasWidth = 200f,
                paddingLeft = 10f,
                paddingRight = 10f,
                visualLeft = 0f,
                visualRight = 110f,
                alignment = "end",
                safetyInset = 4f
            ),
            0.0001f
        )
    }

    @Test
    fun secondaryLineHeightReservesTypefaceBottomOvershoot() {
        assertEquals(26f, safeSecondaryLineHeight(-16f, 6f, 10f), 0.0001f)
    }

    @Test
    fun spotlightBrightnessUsesSinOutSquaredRamp() {
        assertEquals(0.42f, spotlightBrightness(0f), 0.0001f)
        assertEquals(0.71f, spotlightBrightness(0.5f), 0.0001f)
        assertEquals(1f, spotlightBrightness(1f), 0.0001f)
    }

    @Test
    fun spotlightAlphaKeepsActiveWordAboveUnsungFloor() {
        assertEquals(0.56f, spotlightAlpha(0f, SpotlightWordState.ACTIVE), 0.0001f)
        assertEquals(0.71f, spotlightAlpha(0.5f, SpotlightWordState.ACTIVE), 0.0001f)
        assertEquals(1f, spotlightAlpha(1f, SpotlightWordState.ACTIVE), 0.0001f)
        assertEquals(1f, spotlightAlpha(0.25f, SpotlightWordState.SUNG), 0.0001f)
        assertEquals(0.56f, spotlightAlpha(0.75f, SpotlightWordState.UNSUNG), 0.0001f)
    }

    @Test
    fun rubyClipStartsAboveBaseGlyphByReservedBand() {
        assertEquals(40f, rubyClipTop(100f, -40f, 20f), 0.0001f)
    }

    @Test
    fun rubyTopShiftClampsEntireBlockToCanvasPadding() {
        assertEquals(8f, rubyTopShift(4f, 12f), 0.0001f)
        assertEquals(0f, rubyTopShift(12f, 12f), 0.0001f)
        assertEquals(0f, rubyTopShift(20f, 12f), 0.0001f)
    }

    @Test
    fun oldBatteryMotionNormalizesToFluid() {
        assertEquals("Fluid", normalizeAodMotion("Battery"))
        assertEquals("Fluid", normalizeAodMotion("Fluid"))
    }

    @Test
    fun timingLoopIsAlwaysSixteenMsWhileEffectivelyVisibleAuthoritativeAndTimed() {
        val active = EffectiveCadenceInputs(
            attached = true,
            sceneActive = true,
            ownVisible = true,
            windowVisible = true,
            aggregatedVisible = true,
            effectiveAlpha = 1f,
            timedOrTransitionActive = true
        )
        assertTrue(isEffectiveCadenceActive(active))
        assertEquals(16L, frameIntervalForTiming(isEffectiveCadenceActive(active), true))
        assertFalse(isEffectiveCadenceActive(active.copy(sceneActive = false)))
        assertFalse(isEffectiveCadenceActive(active.copy(windowVisible = false)))
        assertFalse(isEffectiveCadenceActive(active.copy(aggregatedVisible = false)))
        // 精确零语义:只有 alpha 乘积恰为 0 才算隐藏,doze 压暗(0<a<1)仍保持节奏。
        assertFalse(isEffectiveCadenceActive(active.copy(effectiveAlpha = 0f)))
        assertTrue(isEffectiveCadenceActive(active.copy(effectiveAlpha = 0.01f)))
        assertTrue(isEffectiveCadenceActive(active.copy(effectiveAlpha = 0.3f)))
        assertTrue(isEffectiveCadenceActive(active.copy(effectiveAlpha = 0f, handoffActive = true)))
        assertFalse(
            isEffectiveCadenceActive(
                active.copy(effectiveAlpha = 0f, handoffActive = true, sceneActive = false)
            )
        )
        assertFalse(isEffectiveCadenceActive(active.copy(attached = false)))
        assertEquals(0L, frameIntervalForTiming(false, true))
        assertEquals(0L, frameIntervalForTiming(true, false))
    }

    @Test
    fun verifiedDozeCadenceIgnoresXiaomiGenericVisibilityButKeepsDirectGates() {
        val doze = EffectiveCadenceInputs(
            attached = true,
            sceneActive = true,
            ownVisible = true,
            windowVisible = false,
            aggregatedVisible = false,
            effectiveAlpha = 0f,
            timedOrTransitionActive = true,
            verifiedDozeHost = true
        )

        assertTrue(isEffectiveCadenceActive(doze))
        assertFalse(isEffectiveCadenceActive(doze.copy(attached = false)))
        assertFalse(isEffectiveCadenceActive(doze.copy(sceneActive = false)))
        assertFalse(isEffectiveCadenceActive(doze.copy(ownVisible = false)))
        assertFalse(isEffectiveCadenceActive(doze.copy(timedOrTransitionActive = false)))
    }

    @Test
    fun wordTimingDrivesCadenceAndLineSyncForcesSweep() {
        val words = listOf(AodCanvasWord("word", "", 1_000L, 2_000L, false))

        assertTrue(hasActiveCanvasTiming(false, "Top to bottom", 0L, 0L, words))
        assertTrue(hasActiveCanvasTiming(true, "Top to bottom", 1_000L, 2_000L, emptyList()))
        // None 在行级同步下真正关闭进度时序(不再被归一到水平扫光而失效)。
        assertFalse(hasActiveCanvasTiming(true, "None", 1_000L, 2_000L, words))
        assertFalse(hasActiveCanvasTiming(false, "Top to bottom", 0L, 0L, emptyList()))
        assertFalse(hasActiveCanvasTiming(false, "Top to bottom", 0L, 0L, words, speed = 0f))
        // 辅助文字逐字效果独立于主行进度效果:None 下主行静态,辅助行仍要续帧;暂停恒停。
        assertTrue(
            hasActiveCanvasTiming(true, "None", 1_000L, 2_000L, words, auxKaraoke = true)
        )
        assertFalse(
            hasActiveCanvasTiming(
                true, "None", 1_000L, 2_000L, words, speed = 0f, auxKaraoke = true
            )
        )
    }

    @Test
    fun cadenceGateStopsWhenHiddenAndRestartsWhenVisibilityReturns() {
        val gate = EffectiveCadenceGate()

        assertEquals(CadenceChange.START, gate.update(true))
        assertEquals(CadenceChange.STOP, gate.update(false))
        assertEquals(CadenceChange.START, gate.update(true))
        assertEquals(CadenceChange.NONE, gate.update(true))
    }

    @Test
    fun drawWakePulseResultLogsOnlyOnOutcomeChange() {
        assertTrue(shouldLogDrawWakePulseResult(null, AodDrawWakePulseResult.SUCCESS))
        assertFalse(
            shouldLogDrawWakePulseResult(
                AodDrawWakePulseResult.SUCCESS,
                AodDrawWakePulseResult.SUCCESS
            )
        )
        assertTrue(
            shouldLogDrawWakePulseResult(
                AodDrawWakePulseResult.SUCCESS,
                AodDrawWakePulseResult.INVOCATION_FAILED
            )
        )
    }

    @Test
    fun exitTransitionDrivesFramesForUntimedIncomingUntilSettled() {
        assertEquals(16L, frameIntervalForTiming(true, false, true))
        assertEquals(0L, frameIntervalForTiming(true, false, false))
        assertFalse(isExitTransitionExpired(1_000L, 1_209L, 210L))
        assertTrue(isExitTransitionExpired(1_000L, 1_210L, 210L))
    }

    @Test
    fun handoffSuppressesDuplicateLineTransition() {
        assertEquals(true, shouldStartLineTransition(true, "Fade up", false))
        assertEquals(false, shouldStartLineTransition(true, "Fade up", true))
        assertEquals(false, shouldStartLineTransition(true, "Fade up", false, resuming = true))
        assertEquals(false, shouldStartLineTransition(true, "None", false))
    }

    @Test
    fun adaptiveCardBoundsCoverIncomingAndOutgoingRowsOnly() {
        assertEquals(
            AodCanvasVerticalBounds(80f, 260f),
            unionAodCanvasVerticalBounds(
                AodCanvasVerticalBounds(100f, 220f),
                AodCanvasVerticalBounds(80f, 260f)
            )
        )
        assertEquals(
            AodCanvasVerticalBounds(100f, 220f),
            unionAodCanvasVerticalBounds(AodCanvasVerticalBounds(100f, 220f), null)
        )
    }

    @Test
    fun lexicalRangeKeepsJapaneseParticleWithPreviousTimedWord() {
        val offsets = listOf(4 until 6, 6 until 7, 7 until 9)
        val groups = listOf(
            AodCanvasLayoutGroup(4, 7, "ja-lexeme", true, 0.95),
            AodCanvasLayoutGroup(7, 12, "ja-lexeme", true, 0.95)
        )

        assertEquals(listOf(0, 0, 1), lexicalGroupIds(offsets, groups))
    }

    @Test
    fun missingLayoutMetadataKeepsLegacyWordWrapping() {
        assertEquals(listOf(null, null), lexicalGroupIds(listOf(0 until 2, 3 until 5), emptyList()))
    }

    @Test
    fun sentenceLayoutKeepsUncoveredPunctuationAndSkipsWhitespaceRuns() {
        val groups = listOf(
            AodCanvasLayoutGroup(0, 2, "zh-icu-word", true, 0.9),
            AodCanvasLayoutGroup(4, 6, "zh-icu-word", true, 0.9)
        )

        assertEquals(
            listOf(0 until 2, 2 until 3, 4 until 6),
            coveredLayoutRanges("音乐， 响起", groups)
        )
    }

    @Test
    fun lexicalChunksBalanceAcrossRequiredLineCount() {
        assertEquals(
            listOf(0 until 2, 2 until 4),
            balancedChunkRanges(listOf(40f, 40f, 40f, 40f), 120f, 3)
        )
    }

    @Test
    fun oversizedLexicalChunkCanEmergencyWrapBeforeBalancing() {
        assertEquals(
            listOf(0 until 1, 1 until 2),
            balancedChunkRanges(listOf(140f, 40f), 120f, 3)
        )
    }

    @Test
    fun legacyWrappingUsesUpstreamGreedyBreaksInsteadOfBalancing() {
        assertEquals(
            listOf(0 until 3, 3 until 4),
            legacyWordLineRanges(
                wordWidths = listOf(40f, 40f, 40f, 40f),
                gapAfters = listOf(0f, 0f, 0f, 0f),
                available = 120f,
                maxLines = 3
            )
        )
    }

    @Test
    fun legacyWrappingLeavesOverflowInTheLastUpstreamCappedLine() {
        assertEquals(
            listOf(0 until 1, 1 until 3),
            legacyWordLineRanges(
                wordWidths = listOf(80f, 80f, 80f),
                gapAfters = listOf(0f, 0f, 0f),
                available = 100f,
                maxLines = 2
            )
        )
    }

    @Test
    fun overlayRectCentersSurfaceWithoutTouchingStockMeasurement() {
        assertEquals(
            AodSurfaceRect(60, 224, 940, 584),
            calculateAodSurfaceRect(1000, 700, 200, 24, 880, 360)
        )
    }

    @Test
    fun overlayRectShrinksWhenStockContentLeavesLimitedHeight() {
        assertEquals(
            AodSurfaceRect(60, 624, 940, 676),
            calculateAodSurfaceRect(1000, 700, 600, 24, 880, 360)
        )
    }

    @Test
    fun overlayRectNeverExtendsBeyondVisibleRoot() {
        assertEquals(
            AodSurfaceRect(0, 676, 1000, 676),
            calculateAodSurfaceRect(1000, 700, 900, 24, 1200, 360)
        )
    }

    @Test
    fun zeroSizedAodRootIsNeverUsableForRendering() {
        assertEquals(false, hasUsableAodRootSize(0, 700))
        assertEquals(false, hasUsableAodRootSize(1_000, 0))
        assertEquals(false, hasUsableAodRootSize(-1, 700))
        assertEquals(true, hasUsableAodRootSize(1_000, 700))
    }

    @Test
    fun pinyinTokensNeverSplitAtNormalBoundaries() {
        val source = secondaryTokens("tiān tiān bǎ tā guà zuǐ biān dào dǐ shén mó shì zhēn ài")
        val lines = balancedTokenLineTexts(
            source,
            listOf(20f, 20f, 14f, 12f, 20f, 22f, 24f, 20f, 18f, 22f, 20f, 18f, 24f, 12f),
            4f,
            190f,
            2
        )
        assertEquals(source, lines.flatMap(::secondaryTokens))
        assertEquals(true, lines.any { it.contains("zhēn ài") })
    }

    @Test
    fun romanizedWordsRespectAttachedMainRuns() {
        assertEquals(
            "watashi tachi no tsuzuki",
            joinedRomanizedWords(
                listOf(
                    "watashi" to true,
                    "tachi" to true,
                    "no" to true,
                    "tsuzuki" to false
                )
            )
        )
        assertEquals("deshou", joinedRomanizedWords(listOf("desho" to false, "u" to true)))
    }

    @Test
    fun russianSecondaryWrapKeepsWholeWords() {
        val tokens = secondaryTokens("Tut bez tebya, bez tebya vsyo ne tak, vsyo ne tak")
        val lines = balancedTokenLineTexts(tokens, tokens.map { it.length * 8f }, 4f, 190f, 2)
        assertEquals(tokens, lines.flatMap(::secondaryTokens))
    }

    @Test
    fun sweepBandWidthScalesWithRowWidthAndFloorsAtOnePixel() {
        assertEquals(100f * LyricGlowRenderer.SWEEP_BAND_FRACTION, sweepBandWidth(100f), 0.0001f)
        // 极窄行宽：width * 占比 < 1f 时下限 1px 生效
        assertEquals(1f, sweepBandWidth(2f), 0.0001f)
        assertEquals(1f, sweepBandWidth(0f), 0.0001f)
    }

    @Test
    fun sweepGradientStartCoversRowFromBandOffsetToLeftPlusWidth() {
        val band = 100f * LyricGlowRenderer.SWEEP_BAND_FRACTION

        // lp=0：光锋起点在 rowLeft-band（band 左外沿）
        assertEquals(10f - band, sweepGradientStart(10f, 100f, 0f), 0.0001f)
        // lp=1：起点推进到 rowLeft+rowWidth（行右缘）
        assertEquals(10f + 100f, sweepGradientStart(10f, 100f, 1f), 0.0001f)
        // lp=0.5：起点居中
        assertEquals(10f - band + (100f + band) * 0.5f, sweepGradientStart(10f, 100f, 0.5f), 0.0001f)
    }

    @Test
    fun haloClipRectTracksSweptPrefixAndHaloMargins() {
        val row = LyricGlowRow(left = 10f, width = 100f, baseline = 200f, drawText = { _, _ -> })

        // lp<=0 的跳过语义由调用方负责，函数本身不做特殊处理：lp=0 时右边界收缩到 rowLeft
        val band = 100f * LyricGlowRenderer.SWEEP_BAND_FRACTION
        val untouched = haloClipRect(row, 0f, ascent = -20f, descent = 6f, haloRadius = 8f)
        assertEquals(10f - band, untouched.left, 0.0001f)
        assertEquals(10f, untouched.right, 0.0001f)
        // lp=1：右边界到 rowLeft+rowWidth+band
        val full = haloClipRect(row, 1f, ascent = -20f, descent = 6f, haloRadius = 8f)
        assertEquals(10f + 100f + band, full.right, 0.0001f)
        // 上下边界 = baseline + ascent/descent ± haloRadius
        assertEquals(200f - 20f - 8f, full.top, 0.0001f)
        assertEquals(200f + 6f + 8f, full.bottom, 0.0001f)
    }

    @Test
    fun easeInOutCubicMatchesPreviewSweepCurve() {
        // 端点固定:0→0, 1→1(整块进度不受缓动影响)
        assertEquals(0f, easeInOutCubic(0f), 0.0001f)
        assertEquals(1f, easeInOutCubic(1f), 0.0001f)
        // 与预览旧实现完全相同的三次曲线:p<0.5 为 4p³,p>=0.5 为 1-(-2p+2)³/2
        assertEquals(4f * 0.25f * 0.25f * 0.25f, easeInOutCubic(0.25f), 0.0001f)
        val v = -2f * 0.75f + 2f
        assertEquals(1f - v * v * v / 2f, easeInOutCubic(0.75f), 0.0001f)
        // 中点连续且两侧对称
        assertEquals(0.5f, easeInOutCubic(0.5f), 0.0001f)
    }

    @Test
    fun sweepBandFractionIsPreviewValue() {
        // 预览与实机共享同一光带占比:此前实机 0.4/预览 0.28 的漂移就是不同步的根因之一
        assertEquals(0.28f, LyricGlowRenderer.SWEEP_BAND_FRACTION, 0.0001f)
        // dim 底不透明度 30%,与预览 color.copy(alpha = 0.30f) 一致
        assertEquals((255 * 0.30f).toInt(), LyricGlowRenderer.DIM_BASE_ALPHA)
    }

    @Test
    fun landscapeLogicalFrameSwapsViewportDimensions() {
        // 旋转 90° 后逻辑宽 = 视口高、逻辑高 = 视口宽:刚性变换恰好铺满竖屏视口。
        val frame = aodLandscapeLogicalFrame(1440, 3200)
        assertEquals(3200, frame.ow)
        assertEquals(1440, frame.oh)
    }

    @Test
    fun landscapeLogicalFrameIsInvolutory() {
        // 对逻辑框再做一次交换应回到视口原始尺寸(横屏↔竖屏互逆)。
        val frame = aodLandscapeLogicalFrame(1440, 3200)
        val back = aodLandscapeLogicalFrame(frame.ow, frame.oh)
        assertEquals(1440, back.ow)
        assertEquals(3200, back.oh)
    }

    @Test
    fun landscapeFramePaddingDividesPercentByOneHundred() {
        // issue #32 根因回归:横屏 padding 是逻辑帧的百分比(默认 2%),
        // 若未除以 100 会被当成 200% → 裁剪矩形为空 → 横屏歌词整屏空白。
        // 视口竖屏尺寸 950x2302,横屏逻辑宽 ow=2302、逻辑高 oh=950。
        val layout = aodLandscapeFrameLayout(
            viewWidth = 950,
            viewHeight = 2302,
            paddingXPercent = 2f,
            paddingYPercent = 2f
        )
        assertEquals(2302, layout.ow)
        assertEquals(950, layout.oh)
        // X 轴相对逻辑宽 ow(=视口高):round(2302 * 0.02) = 46
        assertEquals(46, layout.padLeft)
        assertEquals(46, layout.padRight)
        // Y 轴相对逻辑高 oh(=视口宽):round(950 * 0.02) = 19
        assertEquals(19, layout.padTop)
        assertEquals(19, layout.padBottom)
        // 裁剪矩形必须合法(left<right 且 top<bottom),否则整屏裁空。
        assertTrue(layout.clipRectValid)
        assertTrue(layout.clipLeft < layout.clipRight)
        assertTrue(layout.clipTop < layout.clipBottom)
    }

    @Test
    fun landscapeFramePaddingAtMaxPercentStillYieldsValidClip() {
        // 最大允许 20%:左右各 round(2302*0.2)=460、上下各 round(950*0.2)=190,矩形仍合法。
        val layout = aodLandscapeFrameLayout(950, 2302, paddingXPercent = 20f, paddingYPercent = 20f)
        assertEquals(460, layout.padLeft)
        assertEquals(190, layout.padTop)
        assertTrue(layout.clipRectValid)
    }

    @Test
    fun clipRectValidDetectsEmptyOrInvertedPadding() {
        // 防呆契约:旧 bug(百分比未除 100,2f 被当作 200%)会得到 left>=right 或 top>=bottom 的空矩形,
        // clipRectValid 必须能识别,绘制层据此退化为"不裁剪"而不是整屏空白。
        val broken = aodLandscapeFrameLayout(950, 2302, paddingXPercent = 200f, paddingYPercent = 200f)
        assertFalse(broken.clipRectValid)
        assertTrue(broken.clipLeft >= broken.clipRight)
        assertTrue(broken.clipTop >= broken.clipBottom)
    }

    @Test
    fun fullscreenBlockCenterOffsetCentersSmallerBlock() {
        // issue #41:横屏全屏时整块(元数据+歌词)应在可用高度内居中。
        // 视口 1080x2400,逻辑 oh=1080,pad=round(1080*0.02)=22,available=1036。
        // 内容块原本锚在顶部(blockTop=padTop=22),高 300 → 应下移 368 到居中位置。
        val offset = fullscreenBlockCenterOffset(
            blockTop = 22f,
            blockHeight = 300f,
            availableHeight = 1036f,
            regionTop = 22f
        )
        // (1036-300)/2 - (22-22) = 368
        assertEquals(368f, offset, 0.0001f)
    }

    @Test
    fun fullscreenBlockCenterOffsetKeepsTopWhenBlockTooTall() {
        // 内容块高于可用高度时不缩小也不上移:偏移恒为 regionTop - blockTop(此处内容本就在顶部 → 0)。
        val bigger = fullscreenBlockCenterOffset(
            blockTop = 100f,
            blockHeight = 1200f,
            availableHeight = 1036f,
            regionTop = 22f
        )
        // (1036-1200)/2 为负 → max(0,·)→ 0,再减去尚未在顶部的 (100-22)。
        assertEquals(-78f, bigger, 0.0001f)
        // 内容块本就贴顶(blockTop==padTop)时越界不产生任何偏移。
        assertEquals(
            0f,
            fullscreenBlockCenterOffset(22f, 1200f, 1036f, 22f),
            0.0001f
        )
    }

    @Test
    fun contentStackHeightAccumulatesRowsWithMetadataGapAndPadding() {
        // 自适应卡片高度的实测堆叠高:逐行(行高+行前距)累加,元数据-歌词间距只在有元数据
        // 行时计入,画布上下 padding 兜住上下沿(与 positionRows 顶锚排版同式)。
        assertEquals(
            130f,
            contentStackHeightPx(
                rowHeightsPx = listOf(40f, 30f, 20f),
                rowGapsBeforePx = listOf(0f, 8f, 4f),
                metadataGapPx = 10f,
                padTopPx = 8f,
                padBottomPx = 10f
            ),
            0.0001f
        )
        assertEquals(
            92f,
            contentStackHeightPx(
                rowHeightsPx = listOf(40f, 30f),
                rowGapsBeforePx = listOf(8f, 4f),
                metadataGapPx = 0f,
                padTopPx = 5f,
                padBottomPx = 5f
            ),
            0.0001f
        )
    }

    @Test
    fun metadataBandGrowsToArtworkSlotAndCentersTextBlock() {
        // 带高 = max(文本块高, 图片槽边长):文本块在带内垂直居中,歌词从带沿让出 gap,
        // 图片槽与文本块同心中线(实机不再把图片画低/被内容框裁切)。
        val ascent = -12f
        val descent = 4f
        val lineHeight = descent - ascent // 16f
        val block = metadataBlockHeightPx(2, lineHeight, ascent, descent)
        assertEquals(32f, block, 0.0001f)
        assertEquals(16f, metadataBlockHeightPx(1, lineHeight, ascent, descent), 0.0001f)
        val side = 40f

        // 顶部锚点:带 [8, 48] 高 40 > 块 32,块顶 = 8 + (40-32)/2 = 12,首行基线 24。
        val top = metadataLayoutBounds(
            "top", 360f, 8f, 8f, ascent, descent, 10f,
            blockHeight = block, bandHeight = side
        )
        assertEquals(24f, top.metadataBaseline, 0.0001f)
        assertEquals(58f, top.lyricStart, 0.0001f)
        // 图片中心 = 文本视觉中线 = 带中心(8 + 40/2 = 28)。
        assertEquals(28f, metadataTextCenterY(24f, 24f + lineHeight, ascent, descent), 0.0001f)
        assertEquals(8f + side / 2f, metadataTextCenterY(24f, 24f + lineHeight, ascent, descent), 0.0001f)

        // 底部锚点:带 [312, 352],末行基线 344,歌词终点 = 带顶 - gap = 302。
        val bottom = metadataLayoutBounds(
            "bottom", 360f, 8f, 8f, ascent, descent, 10f,
            blockHeight = block, bandHeight = side
        )
        assertEquals(344f, bottom.metadataBaseline, 0.0001f)
        assertEquals(302f, bottom.lyricEnd, 0.0001f)
        assertEquals(332f, metadataTextCenterY(328f, 344f, ascent, descent), 0.0001f)
        assertEquals(312f + side / 2f, metadataTextCenterY(328f, 344f, ascent, descent), 0.0001f)
    }

    @Test
    fun metadataBandFallsBackToLegacyGeometryWithoutTallerArtwork() {
        // 图片关闭 / 图片不高于文本块:带几何退化到历史公式(逐值等价)。
        val legacy = metadataLayoutBounds("top", 360f, 8f, 8f, -12f, 4f, 10f)
        val withBlock = metadataLayoutBounds(
            "top", 360f, 8f, 8f, -12f, 4f, 10f,
            blockHeight = metadataBlockHeightPx(1, 16f, -12f, 4f), bandHeight = 16f
        )
        assertEquals(legacy.metadataBaseline, withBlock.metadataBaseline, 0.0001f)
        assertEquals(legacy.lyricStart, withBlock.lyricStart, 0.0001f)
        assertEquals(legacy.lyricEnd, withBlock.lyricEnd, 0.0001f)

        val legacyBottom = metadataLayoutBounds("bottom", 360f, 8f, 8f, -12f, 4f, 10f)
        val bottomWithBlock = metadataLayoutBounds(
            "bottom", 360f, 8f, 8f, -12f, 4f, 10f,
            blockHeight = metadataBlockHeightPx(1, 16f, -12f, 4f), bandHeight = 16f
        )
        assertEquals(legacyBottom.metadataBaseline, bottomWithBlock.metadataBaseline, 0.0001f)
        assertEquals(legacyBottom.lyricEnd, bottomWithBlock.lyricEnd, 0.0001f)
    }

    @Test
    fun metadataTextCenterSitsAboveBaselineMidpoint() {
        // 文本视觉中线 = 基线中点 + (ascent + descent)/2;ascent 为负,故中线在基线中点之上。
        // (回归守卫:此前实机写成 `- (descent + ascent)/2`,图片被画低约 0.7×字号。)
        assertEquals(-4f, metadataTextCenterY(0f, 0f, -12f, 4f), 0.0001f)
        assertEquals(100f, metadataTextCenterY(100f, 116f, -16f, 0f), 0.0001f)
        // ascent + descent == 0 时中线与基线中点重合(与符号无关的退化点)。
        assertEquals(0f, metadataTextCenterY(0f, 0f, -8f, 8f), 0.0001f)
    }

    @Test
    fun canvasContentCarriesPauseSpinGate() {
        // 暂停驻留快照与 per-surface 开关透传:artworkSpinEffective 同时驱动节拍门与绘制角。
        val snapshot = LyricSnapshot(original = "line", pauseRetentionEligible = true)
        val profile = SceneCompiler.compile(
            CustomizationDocument(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        artworkVisible = true,
                        artworkShape = ARTWORK_SHAPE_CIRCLE,
                        artworkSpin = true,
                        artworkSpinWhenPaused = true
                    )
                )
            )
        ).profiles.getValue(SceneCompiler.SURFACE_AOD)
        val content = snapshot.toAodCanvasContent(profile)
        assertTrue(content.playbackPaused)
        assertTrue(content.artworkSpinWhenPaused)
        assertTrue(
            artworkSpinEffective(content.artworkSpin, content.artworkSpinWhenPaused, content.playbackPaused)
        )

        // 暂停 + 未开「音乐暂停时继续旋转」:停转(节拍门随之停,绘制角冻结)。
        assertFalse(artworkSpinEffective(true, false, true))
        // 播放中:旋转照常,与该开关无关。
        assertTrue(artworkSpinEffective(true, false, false))
        // 旋转总开关关:恒停。
        assertFalse(artworkSpinEffective(false, true, false))

        // 无 profile:开关缺省关;暂停标记随快照透传,与 profile 无关。
        val bare = snapshot.toAodCanvasContent(null)
        assertFalse(bare.artworkSpinWhenPaused)
        assertTrue(bare.playbackPaused)
        val live = LyricSnapshot(original = "line").toAodCanvasContent(profile)
        assertFalse(live.playbackPaused)
    }
}
