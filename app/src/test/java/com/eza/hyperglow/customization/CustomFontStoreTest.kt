package com.eza.hyperglow.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 多字体保留:导入只新增、历史单槽自动登记、令牌白名单防止 `custom:<id>` 变成路径片段。
 */
class CustomFontStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun fontBytes(vararg magic: Int): ByteArray = ByteArray(64) { index ->
        if (index < magic.size) magic[index].toByte() else 0
    }

    private val ttf = fontBytes(0x00, 0x01, 0x00, 0x00)
    private val otf = fontBytes(0x4F, 0x54, 0x54, 0x4F)

    @Test
    fun importKeepsEveryFontInsteadOfOverwritingTheSingleSlot() {
        val root = temp.newFolder("files")
        val first = CustomFontStore.import(root, ttf, "字体A.ttf", 100L)
        val second = CustomFontStore.import(root, otf, "Second Font.otf", 200L)

        assertEquals(2, CustomFontStore.list(root).size)
        assertTrue(CustomFontContract.fontFile(root, first!!.id).isFile)
        assertTrue(CustomFontContract.fontFile(root, second!!.id).isFile)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun duplicateDisplayNamesGetDistinctIds() {
        val root = temp.newFolder("files")
        val first = CustomFontStore.import(root, ttf, "Same Name.ttf", 100L)!!
        val second = CustomFontStore.import(root, ttf, "Same Name.ttf", 200L)!!

        assertNotEquals(first.id, second.id)
        assertEquals(2, CustomFontStore.list(root).size)
    }

    @Test
    fun legacySingleSlotJoinsTheListWithoutMigration() {
        val root = temp.newFolder("files")
        val legacy = CustomFontContract.fontFile(root, CustomFontContract.FAMILY_CUSTOM)
        legacy.parentFile!!.mkdirs()
        legacy.writeBytes(ttf)

        val fonts = CustomFontStore.list(root)
        assertEquals(1, fonts.size)
        assertEquals(CustomFontContract.FAMILY_CUSTOM, fonts[0].id)
        // 旧配置 fontFamily="custom" 仍命中同一文件。
        assertEquals(legacy, CustomFontContract.fontFile(root, "custom"))
    }

    @Test
    fun invalidOrOversizeFilesAreRejected() {
        val root = temp.newFolder("files")
        assertNull(CustomFontStore.import(root, ByteArray(16), "broken.ttf", 1L))
        assertNull(
            CustomFontStore.import(
                root,
                ByteArray(CustomFontContract.MAX_FONT_BYTES + 1),
                "huge.ttf",
                1L
            )
        )
        assertTrue(CustomFontStore.list(root).isEmpty())
    }

    @Test
    fun deleteRemovesFontAndEntry() {
        val root = temp.newFolder("files")
        val entry = CustomFontStore.import(root, ttf, "Doomed.ttf", 100L)!!

        assertTrue(CustomFontStore.delete(root, entry.id))
        assertTrue(CustomFontStore.list(root).isEmpty())
        assertFalse(CustomFontContract.fontFile(root, entry.id).exists())
    }

    @Test
    fun customFamilyTokensAreWhitelisted() {
        assertTrue(CustomFontContract.isCustomFontFamily("custom"))
        assertTrue(CustomFontContract.isCustomFontFamily("custom:abc-1_2"))
        assertFalse(CustomFontContract.isCustomFontFamily("noto"))
        assertFalse(CustomFontContract.isCustomFontFamily("custom:"))
        assertFalse(CustomFontContract.isCustomFontFamily("custom:../x"))
        assertFalse(CustomFontContract.isCustomFontFamily("custom:" + "a".repeat(33)))

        assertEquals("custom:abc", CustomFontContract.customFontFamily("abc"))
        assertEquals("custom", CustomFontContract.customFontFamily("custom"))
        assertEquals("abc", CustomFontContract.fontIdOf("custom:abc"))
        assertNull(CustomFontContract.fontIdOf("noto"))
    }

    @Test
    fun displayNameFallsBackToIdWhenBlank() {
        val root = temp.newFolder("files")
        val entry = CustomFontStore.import(root, ttf, ".ttf", 100L)!!

        assertEquals(entry.id, entry.name)
    }

    /** name 表记录:平台/编码/语言/nameID/文本(统一按 UTF-16BE 存储)。 */
    private class NameRec(
        val platform: Int,
        val encoding: Int,
        val language: Int,
        val nameId: Int,
        val text: String
    )

    /** 构造只含 name 表的最小 sfnt,供 [CustomFontNameReader] 解析。 */
    private fun nameTableFont(vararg names: NameRec): ByteArray {
        val strings = names.map { it.text.toByteArray(Charsets.UTF_16BE) }
        val stringOffset = 6 + names.size * 12
        val tableSize = stringOffset + strings.sumOf { it.size }
        val out = ByteArray(28 + tableSize)

        fun be16(at: Int, v: Int) {
            out[at] = (v shr 8).toByte()
            out[at + 1] = v.toByte()
        }

        fun be32(at: Int, v: Int) {
            out[at] = (v shr 24).toByte()
            out[at + 1] = (v shr 16).toByte()
            out[at + 2] = (v shr 8).toByte()
            out[at + 3] = v.toByte()
        }

        be32(0, 0x00010000) // sfnt version
        be16(4, 1) // numTables
        be32(12, 0x6E616D65) // 'name'
        be32(20, 28) // name 表偏移
        be32(24, tableSize)
        be16(28 + 2, names.size) // count
        be16(28 + 4, stringOffset)
        var stringCursor = 0
        names.forEachIndexed { index, rec ->
            val at = 28 + 6 + index * 12
            be16(at, rec.platform)
            be16(at + 2, rec.encoding)
            be16(at + 4, rec.language)
            be16(at + 6, rec.nameId)
            be16(at + 8, strings[index].size)
            be16(at + 10, stringCursor)
            stringCursor += strings[index].size
        }
        var write = 28 + stringOffset
        strings.forEach { bytes ->
            bytes.copyInto(out, write)
            write += bytes.size
        }
        return out
    }

    @Test
    fun fontNameReaderPrefersFamilyNameLikeBuiltInLabels() {
        val font = nameTableFont(
            NameRec(3, 1, 0x409, 4, "MyFont Family Bold"),
            NameRec(3, 1, 0x409, 1, "MyFont Family")
        )

        assertEquals("MyFont Family", CustomFontNameReader.readDisplayName(font))
    }

    @Test
    fun fontNameReaderPrefersSimplifiedChineseThenEnglish() {
        val font = nameTableFont(
            NameRec(3, 1, 0x409, 1, "English Name"),
            NameRec(3, 1, 0x804, 1, "中文名字")
        )

        assertEquals("中文名字", CustomFontNameReader.readDisplayName(font))
    }

    @Test
    fun fontNameReaderFallsBackToFullNameAndRejectsGarbage() {
        val fullNameOnly = nameTableFont(NameRec(3, 1, 0x409, 4, "Only Full Name"))

        assertEquals("Only Full Name", CustomFontNameReader.readDisplayName(fullNameOnly))
        assertNull(CustomFontNameReader.readDisplayName(ByteArray(64)))
        assertNull(CustomFontNameReader.readDisplayName(ByteArray(2)))
    }

    @Test
    fun labelPrefersFontInternalNameThenImportedFileName() {
        val root = temp.newFolder("files")
        val named = CustomFontStore.import(
            root,
            nameTableFont(NameRec(3, 1, 0x804, 1, "字体真名")),
            "filename.ttf",
            100L
        )!!
        val plain = CustomFontStore.import(root, ttf, "fallback-name.ttf", 200L)!!

        assertEquals("字体真名", CustomFontStore.label(root, named))
        assertEquals("fallback-name", CustomFontStore.label(root, plain))
    }

    @Test
    fun labelServesLegacySingleSlotWithoutStoredName() {
        val root = temp.newFolder("files")
        val legacy = CustomFontContract.fontFile(root, CustomFontContract.FAMILY_CUSTOM)
        legacy.parentFile!!.mkdirs()
        legacy.writeBytes(nameTableFont(NameRec(3, 1, 0x804, 1, "旧槽字体名")))

        val entry = CustomFontStore.list(root).single()
        assertEquals(CustomFontContract.FAMILY_CUSTOM, entry.id)
        assertEquals("旧槽字体名", CustomFontStore.label(root, entry))
    }
}
