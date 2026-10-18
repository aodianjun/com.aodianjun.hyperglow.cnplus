package com.eza.hyperglow.customization

import android.content.Context
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 单词效果包导入契约:
 * - 合法包落盘后选择器可见,且存的是规范化后的规格(再规范化幂等);
 * - 同 id 重导入原位替换,不产生重复条目;
 * - 整包格式/版本/大小错误拒绝;单条结构错误逐条拒绝、不影响同包合法条目;
 * - 删除只影响选择器清单。
 *
 * Robolectric 的 filesDir 每个用例是独立临时目录;仍显式清空 `word_effects`,
 * 避免同一 JVM 内 object 级状态或复用目录导致非确定性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WordEffectPackStoreTest {
    private fun context(): Context = RuntimeEnvironment.getApplication()

    private fun storeDir(): File = File(context().filesDir, "word_effects")

    @Before
    fun cleanStoreBefore() {
        storeDir().deleteRecursively()
    }

    @After
    fun cleanStoreAfter() {
        storeDir().deleteRecursively()
    }

    private fun bytes(json: String): ByteArray = json.toByteArray(Charsets.UTF_8)

    private fun packJson(
        effectsJson: String,
        name: String = "Test Pack",
        format: String = WordEffectPackStore.PACK_FORMAT,
        version: Int = WordEffectPackStore.PACK_VERSION
    ): String = """
        {
          "format": "$format",
          "version": $version,
          "name": "$name",
          "author": "unit-test",
          "effects": [$effectsJson]
        }
    """.trimIndent()

    private fun effectJson(
        id: String = "bounce_soft",
        name: String = "Soft Bounce",
        property: String = "translateY",
        from: Float = 0.08f,
        to: Float = 0f
    ): String = """
        {
          "id": "$id",
          "name": "$name",
          "longSyllableMs": 700,
          "stagger": 0.1,
          "sweep": true,
          "sweepBand": 0.3,
          "unsungDim": 0.35,
          "glow": "long",
          "tracks": [
            {
              "property": "$property",
              "keyframes": [
                { "at": 0.0, "value": $from, "easing": "easeOut" },
                { "at": 0.35, "value": $to, "easing": "easeInOut" },
                { "at": 1.0, "value": $to, "easing": "linear" }
              ],
              "longOnly": false
            }
          ],
          "colors": [
            { "at": 0.0, "color": "#FFFFCC00", "easing": "linear" },
            { "at": 1.0, "color": "#FFFFFFFF", "easing": "linear" }
          ]
        }
    """.trimIndent()

    /** 结构非法:未知属性 → normalizeWordEffectSpec 返回 null。 */
    private fun invalidEffectJson(id: String): String = """
        {
          "id": "$id",
          "name": "Broken",
          "tracks": [
            {
              "property": "explode",
              "keyframes": [
                { "at": 0.0, "value": 1.0 }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun validPackImportsAndStoresNormalizedSpec() {
        val result = WordEffectPackStore.import(
            context(),
            bytes(packJson(effectJson())),
            "test"
        )

        assertTrue(result.ok)
        assertTrue(result.rejected.isEmpty())
        val stored = WordEffectPackStore.list(context()).single()
        assertEquals("bounce_soft", stored.id)
        // 落盘的是规范化结果:再次规范化必须逐字段相等(幂等)。
        assertEquals(normalizeWordEffectSpec(stored), stored)
        assertEquals(normalizeWordEffectSpec(result.imported.single()), stored)
    }

    @Test
    fun reimportingSameIdReplacesEntryWithoutDuplicate() {
        val ctx = context()
        assertTrue(WordEffectPackStore.import(ctx, bytes(packJson(effectJson())), "first").ok)

        val updatedEffect = effectJson(
            name = "Updated Bounce",
            property = "scale",
            from = 1.0f,
            to = 1.1f
        )
        val result = WordEffectPackStore.import(
            ctx,
            bytes(packJson(updatedEffect, name = "Second Pack")),
            "second"
        )

        assertTrue(result.ok)
        val stored = WordEffectPackStore.list(ctx)
        assertEquals(1, stored.size)
        assertEquals("bounce_soft", stored.single().id)
        assertEquals("Updated Bounce", stored.single().name)
        assertEquals("scale", stored.single().tracks.single().property)
        assertEquals(normalizeWordEffectSpec(result.imported.single()), stored.single())
    }

    @Test
    fun invalidFormatVersionAndOversizedPackAreRejected() {
        val ctx = context()
        val effects = effectJson()

        val badFormat = WordEffectPackStore.import(
            ctx,
            bytes(packJson(effects, format = "hyperglow-other-pack")),
            "test"
        )
        assertFalse(badFormat.ok)
        assertTrue(badFormat.imported.isEmpty())

        val badVersion = WordEffectPackStore.import(
            ctx,
            bytes(packJson(effects, version = WordEffectPackStore.PACK_VERSION + 1)),
            "test"
        )
        assertFalse(badVersion.ok)
        assertTrue(badVersion.imported.isEmpty())

        val oversized = WordEffectPackStore.import(
            ctx,
            ByteArray(WordEffectPackStore.MAX_PACK_BYTES + 1),
            "test"
        )
        assertFalse(oversized.ok)
        assertTrue(oversized.imported.isEmpty())
        assertTrue(WordEffectPackStore.list(ctx).isEmpty())
    }

    @Test
    fun invalidEffectIsRejectedWhileValidEffectStillImports() {
        val ctx = context()
        val result = WordEffectPackStore.import(
            ctx,
            bytes(
                packJson(
                    listOf(effectJson(), invalidEffectJson("broken_effect")).joinToString(",")
                )
            ),
            "test"
        )

        assertTrue(result.ok)
        assertEquals(listOf("bounce_soft"), result.imported.map { it.id })
        assertEquals(1, result.rejected.size)
        assertTrue(result.rejected.single().contains("broken_effect"))
        assertEquals(listOf("bounce_soft"), WordEffectPackStore.list(ctx).map { it.id })
    }

    @Test
    fun deleteRemovesImportedEffect() {
        val ctx = context()
        assertTrue(WordEffectPackStore.import(ctx, bytes(packJson(effectJson())), "test").ok)
        assertEquals(1, WordEffectPackStore.list(ctx).size)

        assertTrue(WordEffectPackStore.delete(ctx, "bounce_soft"))

        assertTrue(WordEffectPackStore.list(ctx).isEmpty())
        assertFalse(WordEffectPackStore.delete(ctx, "bounce_soft"))
    }
}
