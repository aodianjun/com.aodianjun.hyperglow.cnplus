package com.eza.hyperglow.aod

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AodStateWireCodecTest {
    @Test
    fun validSnapshotAndKeepAliveRoundTripWithoutAndroidBundle() {
        val snapshot = snapshotMessage(
            value = snapshotValue(
                original = "line",
                romanized = "romanized",
                translated = "translated",
                metadata = "track · artist",
                words = listOf(AodStateWireWord("li", "ri", 10L, 20L, true, 0, 2)),
                ruby = listOf(AodStateWireRuby(0, 2, "reading")),
                layoutGroups = listOf(AodStateWireLayoutGroup(0, 4, "phrase", true, 0.75))
            )
        )
        val snapshotEnvelope = AodStateWireCodec.encode(snapshot)

        assertEquals(snapshot, snapshotEnvelope?.let(AodStateWireCodec::decode))

        val keepAlive = AodStateWireMessage.KeepAlive(
            revision = 7L,
            userId = 10,
            updatedAtElapsedMs = 900L,
            keepAlive = true,
            wakeSignal = 44L,
            playbackActive = true
        )
        val keepAliveEnvelope = AodStateWireCodec.encode(keepAlive)
            ?.copy(body = byteArrayOf(1, 2, 3))

        assertEquals(keepAlive, keepAliveEnvelope?.let(AodStateWireCodec::decode))

        val paused = AodStateWireMessage.Hidden(
            revision = 8L,
            userId = 10,
            updatedAtElapsedMs = 901L,
            keepAlive = false,
            wakeSignal = 0L,
            pauseRetentionEligible = true
        )
        assertEquals(paused, AodStateWireCodec.encode(paused)?.let(AodStateWireCodec::decode))
    }

    @Test
    fun exactCollectionLimitsRoundTripAndOverLimitsFailClosedBeforeMapping() {
        val exact = snapshotMessage(
            value = snapshotValue(
                original = "x".repeat(500),
                words = List(AodStateWireLimits.MAX_WORDS) { index ->
                    AodStateWireWord("w", "r", index.toLong(), index + 1L, false, index, index + 1)
                },
                ruby = List(AodStateWireLimits.MAX_RUBY) { index ->
                    AodStateWireRuby(index, index + 1, "r")
                },
                layoutGroups = List(AodStateWireLimits.MAX_LAYOUT_GROUPS) { index ->
                    val start = index % 499
                    AodStateWireLayoutGroup(start, start + 1, "word", true, 0.5)
                }
            )
        )
        val exactEnvelope = AodStateWireCodec.encode(exact)

        assertEquals(exact, exactEnvelope?.let(AodStateWireCodec::decode))
        assertNull(
            AodStateWireCodec.encode(
                exact.copy(value = exact.value.copy(words = exact.value.words + exact.value.words.first()))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                exact.copy(value = exact.value.copy(ruby = exact.value.ruby + exact.value.ruby.first()))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                exact.copy(
                    value = exact.value.copy(
                        layoutGroups = exact.value.layoutGroups + exact.value.layoutGroups.first()
                    )
                )
            )
        )

        val body = requireNotNull(exactEnvelope?.body)
        for ((offset, limit) in listOf(
            8 to AodStateWireLimits.MAX_WORDS,
            12 to AodStateWireLimits.MAX_RUBY,
            16 to AodStateWireLimits.MAX_LAYOUT_GROUPS
        )) {
            val malformed = body.copyOf()
            ByteBuffer.wrap(malformed).putInt(offset, limit + 1)
            assertNull(AodStateWireCodec.decode(exactEnvelope.copy(body = malformed)))
        }
    }

    @Test
    fun aggregateAsciiAndMultibyteOverflowFailClosed() {
        val asciiOverflow = snapshotMessage(
            value = snapshotValue(
                words = List(100) {
                    AodStateWireWord("x".repeat(500), "", 0L, 1L, false, -1, -1)
                }
            )
        )
        val multibyteOverflow = snapshotMessage(
            value = snapshotValue(
                words = List(34) {
                    AodStateWireWord("界".repeat(500), "", 0L, 1L, false, -1, -1)
                }
            )
        )

        assertNull(AodStateWireCodec.encode(asciiOverflow))
        assertNull(AodStateWireCodec.encode(multibyteOverflow))
    }

    @Test
    fun bodyCeilingTruncationUnknownVersionAndUnknownKindFailClosed() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage()))
        val body = requireNotNull(envelope.body)

        assertNull(
            AodStateWireCodec.decode(
                envelope.copy(body = ByteArray(AodStateWireLimits.MAX_ENCODED_BODY_BYTES + 1))
            )
        )
        assertNull(AodStateWireCodec.decode(envelope.copy(body = body.copyOf(body.size - 1))))
        val unknownBodyVersion = body.copyOf()
        ByteBuffer.wrap(unknownBodyVersion).putInt(4, 99)
        assertNull(AodStateWireCodec.decode(envelope.copy(body = unknownBodyVersion)))
        assertNull(
            AodStateWireCodec.decode(
                envelope.copy(protocol = AodStateWireContract.PROTOCOL_VERSION + 1)
            )
        )
        assertNull(AodStateWireCodec.decode(envelope.copy(kind = 99)))
    }

    @Test
    fun malformedStringsNonfiniteValuesAndInvalidRangesFailClosed() {
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue(speed = Float.NaN))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue(
                        original = "line",
                        ruby = listOf(AodStateWireRuby(0, 9, "reading"))
                    )
                )
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue(
                        weight = "x".repeat(AodStateWireLimits.MAX_STYLE_CHARS + 1)
                    )
                )
            )
        )

        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage()))
        val body = requireNotNull(envelope.body).copyOf()
        body[28] = 2
        assertNull(AodStateWireCodec.decode(envelope.copy(body = body)))
    }

    @Test
    fun unsupportedStylesAndUnboundedPlaybackValuesFailClosed() {
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue().copy(lineSyncFillMode = "Diagonal"))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue().copy(transitionMode = "Slide"))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue().copy(durationMs = 0L))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue().copy(
                        durationMs = AodStateWireLimits.MAX_MEDIA_DURATION_MS + 1L
                    )
                )
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue(speed = AodStateWireLimits.MAX_PLAYBACK_SPEED + 0.1f)
                )
            )
        )
    }

    @Test
    fun transitionVocabularyIsWhitelistedWithLegacyLowerCaseAliases() {
        for (mode in listOf("Fade up", "Crossfade", "Slide up", "Slide left", "Zoom", "None")) {
            assertEquals(mode, normalizeAodTransition(mode))
            // 词表内的值必须能通过 fail-closed 校验编码进 wire,并按原值回环
            val message = snapshotMessage(value = snapshotValue().copy(transitionMode = mode))
            assertNotNull(AodStateWireCodec.encode(message))
            assertEquals(message, AodStateWireCodec.encode(message)?.let(AodStateWireCodec::decode))
        }
        // 历史小写别名:Lyricon 曾以联动 preset id(continuity/crossfade/none)填 transition。
        // "none" 必须归一到真正关闭换行的 "None",不能再落到 "Fade up"(静默开启换行动画)。
        assertEquals("Fade up", normalizeAodTransition("continuity"))
        assertEquals("Crossfade", normalizeAodTransition("crossfade"))
        assertEquals("None", normalizeAodTransition("none"))
        // 未知值 fail-safe 兜底 "Fade up";与原值不等,编码侧仍 fail-closed 拒绝裸 "Slide"
        assertEquals("Fade up", normalizeAodTransition("Slide"))
        assertEquals("Fade up", normalizeAodTransition(""))
    }

    @Test
    fun malformedUtf16FailsClosedInsteadOfReplacingCharacters() {
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue(original = "line\uD83D"))
            )
        )
    }

    @Test
    fun senderAndReceiverUseSameLimitsContract() {
        val exactText = "x".repeat(AodStateWireLimits.MAX_LYRIC_CHARS)
        val exact = snapshotMessage(value = snapshotValue(original = exactText))
        val envelope = requireNotNull(AodStateWireCodec.encode(exact))

        assertEquals(exact, AodStateWireCodec.decode(envelope))
        assertTrue(requireNotNull(envelope.body).size <= AodStateWireLimits.MAX_ENCODED_BODY_BYTES)
        assertEquals(48 * 1024, AodStateWireLimits.MAX_AGGREGATE_TEXT_UTF8_BYTES)
        // 编码体上限 = 文本聚合预算 + 歌曲图片 JPEG 预算 + 结构开销(两账分计)。
        assertEquals(96 * 1024, AodStateWireLimits.MAX_ENCODED_BODY_BYTES)
        assertEquals(24 * 1024, AodStateWireLimits.MAX_ARTWORK_BYTES)
        assertEquals(
            com.eza.hyperglow.producer.MAX_ARTWORK_JPEG_BYTES,
            AodStateWireLimits.MAX_ARTWORK_BYTES
        )
    }

    @Test
    fun nextLineAuxTextRoundTripsAndUntrimmedFailsClosed() {
        // 下一行辅助文字(「显示第二行辅助文字」)随行文本过桥:内容相等是回环/去重判定的基石。
        val message = snapshotMessage(
            value = snapshotValue(
                nextLine = "nextline",
                nextLineRomanized = "next roma",
                nextLineTranslated = "next trans"
            )
        )
        assertEquals(message, AodStateWireCodec.encode(message)?.let(AodStateWireCodec::decode))
        // 首尾空白 fail-closed(与 nextLine 同口径,归一到投影侧)。
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue(nextLineTranslated = " padded "))
            )
        )
    }

    @Test
    fun artworkFrameRoundTripsWithContentEquality() {
        val jpegBytes = ByteArray(64) { it.toByte() }
        val message = snapshotMessage(
            value = snapshotValue(
                artworkJpeg = ArtworkJpeg(jpegBytes.copyOf()),
                artworkKey = "com.music.player|song|artist"
            )
        )
        val envelope = requireNotNull(AodStateWireCodec.encode(message))
        val decoded = AodStateWireCodec.decode(envelope)

        assertEquals(message, decoded)
        // 同帧不同实例必须相等(按内容比较):wire 快照整对象相等是回环/去重判定的基石。
        assertEquals(ArtworkJpeg(jpegBytes.copyOf()), ArtworkJpeg(jpegBytes.copyOf()))
        assertEquals(
            ArtworkJpeg(jpegBytes.copyOf()).hashCode(),
            ArtworkJpeg(jpegBytes.copyOf()).hashCode()
        )
        assertFalse(ArtworkJpeg(byteArrayOf(1, 2, 3)) == ArtworkJpeg(byteArrayOf(1, 2, 4)))
    }

    @Test
    fun oversizeHalfOrUntrimmedArtworkFailsClosed() {
        // 超限 JPEG:直接拒绝出包(有界渲染器契约)。
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue(
                        artworkJpeg = ArtworkJpeg(ByteArray(AodStateWireLimits.MAX_ARTWORK_BYTES + 1)),
                        artworkKey = "key"
                    )
                )
            )
        )
        // 半截帧:有图无键 / 有键无图,一律拒收。
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue(artworkJpeg = ArtworkJpeg(byteArrayOf(1))))
            )
        )
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(value = snapshotValue(artworkKey = "key"))
            )
        )
        // 规范键必须 trim 后原样:带首尾空白拒收。
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue(
                        artworkJpeg = ArtworkJpeg(byteArrayOf(1)),
                        artworkKey = " key "
                    )
                )
            )
        )
        // 键超长拒收。
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue(
                        artworkJpeg = ArtworkJpeg(byteArrayOf(1)),
                        artworkKey = "k".repeat(AodStateWireLimits.MAX_ARTWORK_KEY_CHARS + 1)
                    )
                )
            )
        )
    }

    @Test
    fun encodedEnvelopeOwnsBodyBytes() {
        val envelope = requireNotNull(AodStateWireCodec.encode(snapshotMessage()))
        val first = requireNotNull(envelope.body)
        val second = requireNotNull(requireNotNull(AodStateWireCodec.encode(snapshotMessage())).body)

        assertFalse(first === second)
        assertArrayEquals(first, second)
    }

    private fun snapshotMessage(
        value: AodStateWireSnapshot = snapshotValue()
    ) = AodStateWireMessage.Snapshot(
        revision = 7L,
        userId = 10,
        updatedAtElapsedMs = 800L,
        keepAlive = true,
        wakeSignal = 33L,
        playbackActive = true,
        value = value
    )

    private fun snapshotValue(
        original: String = "line",
        romanized: String = "",
        translated: String = "",
        nextLine: String = "nextline",
        nextLineRomanized: String = "",
        nextLineTranslated: String = "",
        metadata: String = "track",
        speed: Float = 1f,
        words: List<AodStateWireWord> = emptyList(),
        ruby: List<AodStateWireRuby> = emptyList(),
        layoutGroups: List<AodStateWireLayoutGroup> = emptyList(),
        weight: String = "Medium",
        artworkJpeg: ArtworkJpeg = ArtworkJpeg.EMPTY,
        artworkKey: String = ""
    ) = AodStateWireSnapshot(
        trackGeneration = 12L,
        aodEnabled = true,
        lockscreenEnabled = true,
        positionFollowingEnabled = true,
        burnInPattern = "static_bottom",
        burnInIntervalMs = 60_000L,
        suppressStockAodContent = true,
        aodRotateWithDevice = true,
        aodRotationMode = "landscape",
        aodRotationSettleMs = 2_000L,
        aodCanvasAnchorLandscape = 0.6f,
        aodLandscapeTextScale = 0.85f,
        aodLandscapeHideStock = false,
        aodLandscapeFullscreen = false,
        aodCanvasPaddingPortraitXPercent = 10f,
        aodCanvasPaddingPortraitYPercent = 12f,
        aodCanvasPaddingLandscapeXPercent = 14f,
        aodCanvasPaddingLandscapeYPercent = 16f,
        original = original,
        romanized = romanized,
        translated = translated,
        nextLine = nextLine,
        nextLineRomanized = nextLineRomanized,
        nextLineTranslated = nextLineTranslated,
        metadata = metadata,
        alignedRight = true,
        lineLevelSync = true,
        lineStartMs = 10L,
        lineEndMs = 20L,
        durationMs = 1_000L,
        positionMs = 100L,
        sampledAtElapsedMs = 700L,
        speed = speed,
        words = words,
        ruby = ruby,
        layoutGroups = layoutGroups,
        weight = weight,
        textSizeMode = "normal",
        textSizeCustom = 100,
        secondaryMode = "Main only",
        animationMode = "Gradient",
        glowMode = "Off",
        motionMode = "Fluid",
        lineSyncFillMode = "Top to bottom",
        overflowMode = "Wrap",
        transitionMode = "Fade up",
        fontFamily = "noto",
        alignmentMode = "auto",
        metadataVisible = true,
        metadataAnchor = "top",
        adaptiveSectioning = true,
        artworkJpeg = artworkJpeg,
        artworkKey = artworkKey
    )

    @Test
    fun duetLineRoundTripsThroughWireBody() {
        val message = snapshotMessage(
            value = snapshotValue().copy(
                duetLine = AodStateWireDuetLine(
                    text = "second line",
                    romanized = "roma",
                    translated = "trans",
                    alignedRight = true,
                    lineStartMs = 40L,
                    lineEndMs = 900L,
                    words = listOf(AodStateWireWord("sec", "", 40L, 900L, true, -1, -1))
                )
            )
        )
        assertEquals(message, AodStateWireCodec.encode(message)?.let(AodStateWireCodec::decode))
    }

    @Test
    fun duetLineFailsClosedOnBlankTextOrWindowBeyondDuration() {
        // 空白文本:isValidSnapshot 拒收 → 编码直接出包失败(fail-closed)。
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue().copy(
                        duetLine = AodStateWireDuetLine(
                            text = "   ",
                            lineStartMs = 0L,
                            lineEndMs = 10L,
                            words = emptyList()
                        )
                    )
                )
            )
        )
        // 行窗越过歌长(快照 duration=1000):拒收。
        assertNull(
            AodStateWireCodec.encode(
                snapshotMessage(
                    value = snapshotValue().copy(
                        duetLine = AodStateWireDuetLine(
                            text = "second",
                            lineStartMs = 0L,
                            lineEndMs = 2_000L,
                            words = emptyList()
                        )
                    )
                )
            )
        )
    }

}