package com.eza.hyperglow.customization

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 歌曲信息切片组装单测：[composeSongMetadata] 与部分/分隔符归一化。
 *
 * 组装是纯函数,实机投影层([com.eza.hyperglow.aod.AodStateProjector])与 app 预览
 * (ProducerCollectors/collectDemoSnapshot)共用同一实现,保证所见即所得。
 * 默认(parts=title,artist + separator=newline)与历史
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
    fun albumPartAppendsInCanonicalOrder() {
        assertEquals(
            "Song\nArtist\nAlbum",
            composeSongMetadata("Song", "Artist", "Album", "title,artist,album", METADATA_SEPARATOR_NEWLINE)
        )
        // 选择顺序不影响显示顺序,恒为 歌名→歌手→专辑。
        assertEquals(
            "Song\nArtist\nAlbum",
            composeSongMetadata("Song", "Artist", "Album", "album,artist,title", METADATA_SEPARATOR_NEWLINE)
        )
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
    fun normalizeMetadataPartsFiltersUnknownDedupesAndFallsBackToDefault() {
        assertEquals("title,artist", normalizeMetadataParts(METADATA_PARTS_DEFAULT))
        assertEquals("title,artist,album", normalizeMetadataParts("title,artist,album"))
        assertEquals("title,artist", normalizeMetadataParts("title,foo,artist,title"))
        assertEquals("title,album", normalizeMetadataParts("album,title"))
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
}
