package com.eza.hyperglow.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 自定义贴纸存储:编号分配、注册表编解码、文件存在性过滤、删除、sha256。
 * 导入解码依赖 BitmapFactory 的 shadow,这里只覆盖纯逻辑与注册表行为。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DecorSpriteStoreTest {

    private fun context() = RuntimeEnvironment.getApplication()

    private val hashA = "a".repeat(64)
    private val hashB = "b".repeat(64)

    @Test
    fun allocateIdStartsAt64AndSkipsUsed() {
        assertEquals(64, DecorSpriteStore.allocateId(emptySet()))
        assertEquals(66, DecorSpriteStore.allocateId(setOf(64, 65)))
        assertEquals(255, DecorSpriteStore.allocateId((64..254).toSet()))
    }

    @Test
    fun allocateIdReturnsNullWhenPoolExhausted() {
        assertNull(DecorSpriteStore.allocateId((64..255).toSet()))
    }

    @Test
    fun entriesRoundTrip() {
        val entries = listOf(
            CustomSprite(64, "星", hashA, 111L),
            CustomSprite(200, "框", hashB, 222L)
        )
        assertEquals(entries, DecorSpriteStore.decodeEntries(DecorSpriteStore.encodeEntries(entries)))
    }

    @Test
    fun decodeDropsOutOfRangeIdsAndBadHashes() {
        val raw = """
            [
              {"id":63,"name":"builtin","hash":"$hashA","addedAt":1},
              {"id":64,"name":"ok","hash":"$hashA","addedAt":1},
              {"id":300,"name":"high","hash":"$hashA","addedAt":1},
              {"id":65,"name":"short","hash":"abcd","addedAt":1}
            ]
        """.trimIndent()
        val decoded = DecorSpriteStore.decodeEntries(raw)
        assertEquals(listOf(64), decoded.map { it.id })
    }

    @Test
    fun decodeToleratesGarbage() {
        assertTrue(DecorSpriteStore.decodeEntries("not json").isEmpty())
    }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            DecorSpriteStore.sha256("abc".toByteArray())
        )
    }

    @Test
    fun listOnlyReturnsEntriesWithBackingFile() {
        val context = context()
        val dir = File(context.filesDir, "deco")
        assertTrue(dir.isDirectory || dir.mkdirs())
        File(dir, "custom_70.png").writeBytes(byteArrayOf(1, 2, 3))
        context.getSharedPreferences(DecorSpriteStore.PREFS, 0).edit()
            .putString(
                DecorSpriteStore.KEY_ENTRIES,
                DecorSpriteStore.encodeEntries(
                    listOf(
                        CustomSprite(70, "has-file", hashA, 1L),
                        CustomSprite(71, "missing", hashB, 2L)
                    )
                )
            )
            .commit()

        val listed = DecorSpriteStore.list(context)
        assertEquals(listOf(70), listed.map { it.id })
        assertNotNull(DecorSpriteStore.file(context, 70))
    }

    @Test
    fun deleteRemovesEntryAndFile() {
        val context = context()
        val dir = File(context.filesDir, "deco")
        assertTrue(dir.isDirectory || dir.mkdirs())
        val file = File(dir, "custom_72.png")
        file.writeBytes(byteArrayOf(9))
        context.getSharedPreferences(DecorSpriteStore.PREFS, 0).edit()
            .putString(
                DecorSpriteStore.KEY_ENTRIES,
                DecorSpriteStore.encodeEntries(listOf(CustomSprite(72, "gone", hashA, 1L)))
            )
            .commit()

        assertTrue(DecorSpriteStore.delete(context, 72))
        assertFalse(file.exists())
        assertTrue(DecorSpriteStore.list(context).isEmpty())
        assertFalse(DecorSpriteStore.delete(context, 72))
    }

    @Test
    fun fileRejectsBuiltInAndOutOfRangeIds() {
        val context = context()
        assertNull(DecorSpriteStore.file(context, 0))
        assertNull(DecorSpriteStore.file(context, 63))
        assertNull(DecorSpriteStore.file(context, 256))
    }
}
