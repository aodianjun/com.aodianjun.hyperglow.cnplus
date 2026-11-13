package com.eza.hyperglow.plugin

import android.content.Context
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.ProducerRenderModes
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 插件链结果缓存（「歌词计算写入缓存」）的三道正确性闸：
 * - 身份归一：同曲跨会话命中（大小写/空白抖动不影响），无题不缓存；
 * - 指纹：输入行表变了不复用旧结果；
 * - 读写往返：字段集与行内容逐值还原，sessionKey 按当前会话重新钉键。
 * 另覆盖 LRU 上限与 passthrough（空改动）结果。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PluginChainResultCacheTest {

    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun renderModes() = ProducerRenderModes(
        weight = "Medium", textSize = "normal", textSizeCustom = 100,
        secondary = "Main only", animation = "Gradient", glow = "Off",
        lineSyncFill = "Left to right (main only)", overflow = "Wrap",
        transition = "Fade up", font = "spotify"
    )

    private fun state(
        title: String = "Test Song",
        artist: String = "Artist",
        durationMs: Long = 200_000L,
        producerId: String = "lyricon"
    ) = LyricProducerState(
        producerId = producerId,
        generation = 7,
        sequence = 1L,
        status = "ready",
        trackUri = "lyricon://track/1",
        title = title, artist = artist, album = "Album", imageId = "",
        line = "", romanizedLine = "", translatedLine = "",
        lineIndex = 0, positionMs = 0L, durationMs = durationMs,
        sampledAtElapsedMs = 0L, speed = 1f, playing = true,
        receivedAtElapsedMs = 0L, words = null, renderModes = renderModes()
    )

    private fun song(text: String = "hello world") = PluginSong(
        name = "Test Song",
        artist = "Artist",
        lyrics = listOf(
            PluginLyricLine(
                begin = 1_000L, end = 5_000L, duration = 4_000L,
                text = text,
                words = listOf(PluginWord(begin = 1_000L, end = 3_000L, text = "hello")),
                translation = "你好世界"
            )
        )
    )

    private fun patched(
        sessionKey: String,
        song: PluginSong
    ) = PatchedSong(
        sessionKey = sessionKey,
        song = song,
        changedSongFields = setOf(PluginSongField.LYRICS),
        changedLyricFields = setOf(PluginLyricField.TRANSLATION, PluginLyricField.WORDS)
    )

    @org.junit.Before
    fun cleanCache() {
        PluginChainResultCache.clear(context())
    }

    @Test
    fun storeThenLoadRoundTripsAndRekeysSession() {
        val context = context()
        val identity = PluginChainResultCache.songIdentity(state())!!
        val epoch = PluginChainResultCache.chainEpoch(context)
        val fingerprint = PluginChainResultCache.inputFingerprint(song(), epoch)
        PluginChainResultCache.store(context, identity, fingerprint, patched("old:1:uri", song()))

        val loaded = PluginChainResultCache.load(context, identity, fingerprint, "new:2:uri")
        assertNotNull(loaded)
        assertEquals("new:2:uri", loaded!!.sessionKey)
        assertEquals(setOf(PluginSongField.LYRICS), loaded.changedSongFields)
        assertEquals(
            setOf(PluginLyricField.TRANSLATION, PluginLyricField.WORDS),
            loaded.changedLyricFields
        )
        val row = loaded.song.lyrics!!.single()
        assertEquals("hello world", row.text)
        assertEquals("你好世界", row.translation)
        assertEquals(1_000L, row.words!!.single().begin)
    }

    @Test
    fun differentFingerprintMisses() {
        val context = context()
        val identity = PluginChainResultCache.songIdentity(state())!!
        val epoch = PluginChainResultCache.chainEpoch(context)
        PluginChainResultCache.store(context, identity, "fp-A", patched("s", song()))
        assertNull(PluginChainResultCache.load(context, identity, "fp-B", "s"))
    }

    @Test
    fun fingerprintTracksRowContentChanges() {
        val epoch = PluginChainResultCache.chainEpoch(context())
        val before = PluginChainResultCache.inputFingerprint(song(text = "hello world"), epoch)
        val after = PluginChainResultCache.inputFingerprint(song(text = "hello there"), epoch)
        assertTrue(before != after)
        assertEquals(before, PluginChainResultCache.inputFingerprint(song(), epoch))
    }

    @Test
    fun identityNormalizesCaseAndWhitespaceButKeepsTitleRequired() {
        val base = PluginChainResultCache.songIdentity(state(title = "Test  Song", artist = "ARTIST"))
        val variant = PluginChainResultCache.songIdentity(state(title = "test song", artist = "artist"))
        assertEquals(base, variant)
        // 歌名缺失(空白)的会话身份不可信,不缓存。
        assertNull(PluginChainResultCache.songIdentity(state(title = "   ")))
    }

    @Test
    fun passthroughEmptyChangeResultIsAlsoReused() {
        val context = context()
        val identity = PluginChainResultCache.songIdentity(state())!!
        val fingerprint =
            PluginChainResultCache.inputFingerprint(song(), PluginChainResultCache.chainEpoch(context))
        PluginChainResultCache.store(
            context, identity, fingerprint,
            PatchedSong("s", song(), emptySet(), emptySet())
        )
        val loaded = PluginChainResultCache.load(context, identity, fingerprint, "s")
        assertNotNull(loaded)
        assertTrue(!loaded!!.changedAnything())
    }

    @Test
    fun listEntriesCarriesDisplayInfoAndClearEntryRemovesOne() {
        val context = context()
        val epoch = PluginChainResultCache.chainEpoch(context)
        val fingerprint = PluginChainResultCache.inputFingerprint(song(), epoch)
        val firstIdentity = PluginChainResultCache.songIdentity(
            state(title = "Song One", artist = "Singer A", producerId = "lyricon")
        )!!
        val secondIdentity = PluginChainResultCache.songIdentity(
            state(title = "Song Two", artist = "Singer B", producerId = "spicy")
        )!!
        PluginChainResultCache.store(
            context, firstIdentity, fingerprint, patched("s", song()),
            title = "Song One", artist = "Singer A", producerId = "lyricon"
        )
        PluginChainResultCache.store(
            context, secondIdentity, fingerprint, patched("s", song()),
            title = "Song Two", artist = "Singer B", producerId = "spicy"
        )

        val listed = PluginChainResultCache.listEntries(context)
        assertEquals(2, listed.size)
        // 列表按缓存时间新→旧:后存的 Song Two 在前。
        assertEquals("Song Two", listed[0].title)
        assertEquals("Singer B", listed[0].artist)
        assertEquals("spicy", listed[0].producerId)
        assertEquals(1, listed[0].rows)
        assertTrue(listed[0].sizeBytes > 0L)
        assertTrue(listed[0].createdAtEpochMs > 0L)
        assertEquals(secondIdentity, listed[0].identity)

        // 单删命中:其余条目不受影响,且删掉的那首再 load 不命中。
        assertTrue(PluginChainResultCache.clearEntry(context, secondIdentity))
        assertEquals(1, PluginChainResultCache.listEntries(context).size)
        assertNull(
            PluginChainResultCache.load(context, secondIdentity, fingerprint, "s")
        )
        assertNotNull(
            PluginChainResultCache.load(context, firstIdentity, fingerprint, "s")
        )
        // 再删同一条:文件已不在,返回 false。
        assertTrue(!PluginChainResultCache.clearEntry(context, secondIdentity))

        // 全清:列表为空。
        PluginChainResultCache.clear(context)
        assertEquals(0, PluginChainResultCache.listEntries(context).size)
        assertEquals(0L, PluginChainResultCache.sizeBytes(context))
    }

    @Test
    fun lruEvictionKeepsEntryCountBounded() {
        val context = context()
        val epoch = PluginChainResultCache.chainEpoch(context)
        val fingerprint = PluginChainResultCache.inputFingerprint(song(), epoch)
        for (i in 0 until PluginChainResultCache.MAX_ENTRIES + 5) {
            val id = PluginChainResultCache.songIdentity(state(title = "Song $i"))!!
            PluginChainResultCache.store(context, id, fingerprint, patched("s", song()))
        }
        val files = PluginChainResultCache.run {
            context.filesDir.resolve("plugin_chain_cache").listFiles().orEmpty()
        }
        assertEquals(PluginChainResultCache.MAX_ENTRIES, files.size)
    }

    @Test
    fun installerReadManifestPeeksWithoutInstalling() {
        val manifestJson = """
            { "id": "com.example.peek", "name": "Peek", "entry": "com.example.Peek", "apiVersion": 1 }
        """.trimIndent()
        val zip = java.io.ByteArrayOutputStream().let { out ->
            java.util.zip.ZipOutputStream(out).use { stream ->
                stream.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
                stream.write(manifestJson.toByteArray())
                stream.closeEntry()
                stream.putNextEntry(java.util.zip.ZipEntry("classes.dex"))
                stream.write(byteArrayOf(1, 2, 3))
                stream.closeEntry()
            }
            out.toByteArray()
        }
        val manifest = PluginInstaller.readManifest(zip)
        assertNotNull(manifest)
        assertEquals("com.example.peek", manifest!!.id)

        // 损坏 ZIP / 缺 manifest → null,不抛异常。
        assertNull(PluginInstaller.readManifest(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 1, 2, 3)))
        assertNull(PluginInstaller.readManifest(byteArrayOf()))
    }
}
