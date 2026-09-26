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
}
