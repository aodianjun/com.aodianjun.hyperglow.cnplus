package com.eza.hyperglow.customization

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 歌曲信息切片组装单测：[composeSongMetadata] 与部分/逐槽分隔符归一化。
 *
 * 组装是纯函数,实机投影层([com.eza.hyperglow.aod.AodStateProjector])与 app 预览
 * (ProducerCollectors/collectDemoSnapshot)共用同一实现,保证所见即所得。
 * 显示顺序由 parts 顺序决定;相邻两项之间的分隔符逐槽独立(separators 第 i 项对应第 i 个槽位)。
 * 默认(parts=title,artist + separators=newline)与历史
 * `listOf(title, artist).filter{isNotBlank}.joinToString("\n").replace('·','\n')` 逐字等价。
 */
class SongMetadataComposeTest {

    @Test
    fun defaultAssemblyMatchesLegacyTitleArtistNewline() {
        assertEquals(
            "Song\nArtist",
            composeSongMetadata("Song", "Artist", "", METADATA_PARTS_DEFAULT, METADATA_SEPARATOR_NEWLINE)
        )
        assertEquals(
            "Song",
            composeSongMetadata("Song", "", "Album", METADATA_PARTS_DEFAULT, METADATA_SEPARATOR_NEWLINE)
        )
    }

    @Test
    fun middleDotInsidePartTextStillSplitsSlices() {
        assertEquals(
            "Song\nArtist",
            composeSongMetadata(
                "Song·Artist",
                "",
                "",
                METADATA_PARTS_DEFAULT,
                METADATA_SEPARATOR_NEWLINE
            )
        )
    }

    @Test
    fun slicesAreTrimmedAndBlanksDropped() {
        assertEquals(
            "Song",
            composeSongMetadata(" Song ", "  ", "·", METADATA_PARTS_DEFAULT, METADATA_SEPARATOR_NEWLINE)
        )
    }

    @Test
    fun partsRenderInUserChosenOrder() {
        assertEquals(
            "Song\nArtist\nAlbum",
            composeSongMetadata("Song", "Artist", "Album", "title,artist,album", METADATA_SEPARATOR_NEWLINE)
        )
        // 自定义排序:选择顺序即显示顺序。
        assertEquals(
            "Album\nArtist\nSong",
            composeSongMetadata("Song", "Artist", "Album", "album,artist,title", METADATA_SEPARATOR_NEWLINE)
        )
    }

    @Test
    fun perGapSeparatorsAreIndependent() {
        // 三个部分两个槽位:第一槽换行、第二槽行内 dot。
        assertEquals(
            "Song\nArtist · Album",
            composeSongMetadata("Song", "Artist", "Album", "title,artist,album", "newline,dot")
        )
        // 第一槽行内 dot、第二槽换行。
        assertEquals(
            "Song · Artist\nAlbum",
            composeSongMetadata("Song", "Artist", "Album", "title,artist,album", "dot,newline")
        )
        // 两槽都用行内分隔符,则全部内联到一行。
        assertEquals(
            "Song · Artist - Album",
            composeSongMetadata("Song", "Artist", "Album", "title,artist,album", "dot,hyphen")
        )
    }

    @Test
    fun metadataGapCountMatchesSelectedParts() {
        assertEquals(0, metadataGapCount("title"))
        assertEquals(1, metadataGapCount(METADATA_PARTS_DEFAULT))
        assertEquals(2, metadataGapCount("album,title,artist"))
    }

    @Test
    fun normalizeMetadataSeparatorsResizesToGapCount() {
        // 序列长度恒等于槽位数:过短补换行,过长截断,单部分无槽位返回空串,
        // 非法项只回落自己那一槽(逐槽独立,不影响相邻有效槽位)。
        assertEquals("dot,newline", normalizeMetadataSeparators("dot", "title,artist,album"))
        assertEquals("dot", normalizeMetadataSeparators("dot,dot,dot", "title,artist"))
        assertEquals("", normalizeMetadataSeparators("dot", "title"))
        assertEquals("newline,dot", normalizeMetadataSeparators("bogus,dot", "title,artist,album"))
    }

    @Test
    fun inlineSeparatorsJoinSlicesOnOneLine() {
        assertEquals(
            "Song · Artist",
            composeSongMetadata("Song", "Artist", "", METADATA_PARTS_DEFAULT, "dot")
        )
        assertEquals(
            "Song - Artist",
            composeSongMetadata("Song", "Artist", "", METADATA_PARTS_DEFAULT, "hyphen")
        )
        assertEquals(
            "Song | Artist",
            composeSongMetadata("Song", "Artist", "", METADATA_PARTS_DEFAULT, "pipe")
        )
        assertEquals(
            "Song、Artist",
            composeSongMetadata("Song", "Artist", "", METADATA_PARTS_DEFAULT, "dunhao")
        )
        assertEquals(
            "Song / Artist",
            composeSongMetadata("Song", "Artist", "", METADATA_PARTS_DEFAULT, "slash")
        )
    }

    @Test
    fun normalizeMetadataPartsKeepsOrderFiltersUnknownAndDedupes() {
        assertEquals("title,artist", normalizeMetadataParts(METADATA_PARTS_DEFAULT))
        assertEquals("title,artist,album", normalizeMetadataParts("title,artist,album"))
        assertEquals("title,artist", normalizeMetadataParts("title,foo,artist,title"))
        // 顺序保留:album,title 不再被重排回规范顺序。
        assertEquals("album,title", normalizeMetadataParts("album,title"))
        assertEquals(METADATA_PARTS_DEFAULT, normalizeMetadataParts(""))
        assertEquals(METADATA_PARTS_DEFAULT, normalizeMetadataParts(null))
        assertEquals(METADATA_PARTS_DEFAULT, normalizeMetadataParts("foo,bar"))
    }

    @Test
    fun normalizeMetadataSeparatorRejectsUnknownTokens() {
        assertEquals(METADATA_SEPARATOR_NEWLINE, normalizeMetadataSeparator(METADATA_SEPARATOR_NEWLINE))
        assertEquals("dot", normalizeMetadataSeparator("dot"))
        assertEquals(METADATA_SEPARATOR_NEWLINE, normalizeMetadataSeparator("emoji"))
        assertEquals(METADATA_SEPARATOR_NEWLINE, normalizeMetadataSeparator(null))
    }

    @Test
    fun metadataExpectedExtraLinesCountsOnlyNewlineThirdSlice() {
        assertEquals(1, metadataExpectedExtraLines("title,artist,album", METADATA_SEPARATOR_NEWLINE))
        assertEquals(0, metadataExpectedExtraLines(METADATA_PARTS_DEFAULT, METADATA_SEPARATOR_NEWLINE))
        assertEquals(0, metadataExpectedExtraLines("title,artist,album", "dot"))
    }

    @Test
    fun composeNormalizesIncomingPartsAndSeparator() {
        // 未经归一的入参也按词表收敛,非法部分丢弃、非法分隔符回落换行。
        assertEquals(
            "Song\nArtist",
            composeSongMetadata("Song", "Artist", "Album", "title,unknown,artist", "bogus")
        )
    }

    @Test
    fun normalizeArtworkShapeRejectsUnknownTokens() {
        assertEquals(ARTWORK_SHAPE_SQUARE, normalizeArtworkShape(ARTWORK_SHAPE_SQUARE))
        assertEquals(ARTWORK_SHAPE_CIRCLE, normalizeArtworkShape(ARTWORK_SHAPE_CIRCLE))
        assertEquals(ARTWORK_SHAPE_SQUARE, normalizeArtworkShape("oval"))
        assertEquals(ARTWORK_SHAPE_SQUARE, normalizeArtworkShape(null))
    }

    @Test
    fun artworkSpinOnlyEvertsForCircleShape() {
        // 旋转仅圆形生效:方形下残留的 spin=true 不生效,渲染/预览统一读生效值。
        assertEquals(true, effectiveArtworkSpin(ARTWORK_SHAPE_CIRCLE, true))
        assertEquals(false, effectiveArtworkSpin(ARTWORK_SHAPE_CIRCLE, false))
        assertEquals(false, effectiveArtworkSpin(ARTWORK_SHAPE_SQUARE, true))
        assertEquals(false, effectiveArtworkSpin(ARTWORK_SHAPE_SQUARE, false))
    }

    @Test
    fun artworkDisplayConfigDerivesEffectiveSpin() {
        assertEquals(true, ArtworkDisplayConfig(true, ARTWORK_SHAPE_CIRCLE, true).spins)
        assertEquals(false, ArtworkDisplayConfig(true, ARTWORK_SHAPE_SQUARE, true).spins)
        assertEquals(false, ArtworkDisplayConfig(true, ARTWORK_SHAPE_CIRCLE, false).spins)
        assertEquals(false, ArtworkDisplayConfig().visible)
        assertEquals(ARTWORK_SHAPE_SQUARE, ArtworkDisplayConfig().shape)
    }

    @Test
    fun artworkDisplayConfigReadsPerSurfaceProfile() {
        val compiled = SceneCompiler.compile(
            SceneCompiler.safeDefaultDocument().copy(
                profiles = mapOf(
                    SceneCompiler.SURFACE_AOD to SurfaceProfile(
                        artworkVisible = true,
                        artworkShape = ARTWORK_SHAPE_CIRCLE,
                        artworkSpin = true
                    )
                )
            )
        )
        // 息屏开、锁屏关:显示配置各读自己的 profile,不再由文档级全局配置派生。
        val aod = artworkDisplayConfig(compiled.profiles.getValue(SceneCompiler.SURFACE_AOD))
        assertEquals(true, aod.visible)
        assertEquals(ARTWORK_SHAPE_CIRCLE, aod.shape)
        assertEquals(true, aod.spins)

        val lockscreen = artworkDisplayConfig(
            compiled.profiles.getValue(SceneCompiler.SURFACE_LOCKSCREEN)
        )
        assertEquals(false, lockscreen.visible)
        assertEquals(false, lockscreen.spins)
    }
}
