package com.eza.hyperglow.plugin

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginClassLoaderPolicyTest {

    @Test
    fun `platform and plugin-api classes stay parent-first`() {
        listOf(
            "java.lang.String",
            "javax.net.ssl.SSLSocket",
            "android.content.Context",
            "androidx.compose.runtime.Composer",
            "dalvik.system.PathClassLoader",
            "libcore.io.IoBridge",
            "org.json.JSONObject",
            "org.w3c.dom.Document",
            "org.xml.sax.InputSource",
            "org.xmlpull.v1.XmlPullParser",
            "com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin"
        ).forEach { name ->
            assertTrue("$name must be shared with host", PluginClassLoaderPolicy.isSharedWithHost(name))
        }
    }

    @Test
    fun `plugin-bundled kotlin and plugin own classes load child-first`() {
        listOf(
            "kotlin.collections.SetsKt__SetsKt",
            "kotlin.jvm.internal.Intrinsics",
            "kotlinx.coroutines.CoroutineScope",
            "com.amll.ttml.TtmlParser",
            "com.lidesheng.hyperlyric.plugin.ai.translation.AiTranslationPlugin"
        ).forEach { name ->
            assertFalse("$name must load child-first", PluginClassLoaderPolicy.isSharedWithHost(name))
        }
    }

    @Test
    fun `dex overlap counts bundled packages`() {
        val dex = buildDex(
            listOf(
                "Lkotlin/collections/SetsKt__SetsKt;",
                "Lkotlin/Unit;",
                "Lkotlinx/coroutines/CoroutineScope;",
                "Landroidx/compose/runtime/Composer;",
                "Lcom/amll/ttml/TtmlParser;"
            )
        )
        val overlap = PluginClassLoaderPolicy.summarizeDexOverlap(dex)
        assertNotNull(overlap)
        overlap!!
        assertEquals(5, overlap.totalClasses)
        assertEquals(2, overlap.kotlinClasses)
        assertEquals(1, overlap.kotlinxClasses)
        assertEquals(1, overlap.androidxClasses)
    }

    @Test
    fun `dex overlap returns null on non-dex input`() {
        assertNull(PluginClassLoaderPolicy.summarizeDexOverlap(byteArrayOf()))
        assertNull(PluginClassLoaderPolicy.summarizeDexOverlap(ByteArray(256) { 0x42 }))
        assertNull(PluginClassLoaderPolicy.summarizeDexOverlap(buildDex(emptyList()).copyOf(0x40)))
    }

    /**
     * 最小 DEX 构造：parser 只读 header 魔数与 string_id/type_id/class_def 三张表，
     * 这里按同样布局写出 descriptor 字符串，验证统计逻辑而非完整 DEX 语义。
     * 测试 descriptor 均短于 128 字符，uleb128 长度前缀按单字节写入。
     */
    private fun buildDex(descriptors: List<String>): ByteArray {
        val stringIdsOff = 0x70
        val typeIdsOff = stringIdsOff + descriptors.size * 4
        val classDefsOff = typeIdsOff + descriptors.size * 4
        val stringDataOff = classDefsOff + descriptors.size * 32

        val dataOffsets = IntArray(descriptors.size)
        var cursor = stringDataOff
        descriptors.forEachIndexed { i, descriptor ->
            dataOffsets[i] = cursor
            cursor += 1 + descriptor.length + 1
        }

        val out = ByteArray(cursor)
        val buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        out[0] = 'd'.code.toByte()
        out[1] = 'e'.code.toByte()
        out[2] = 'x'.code.toByte()
        out[3] = '\n'.code.toByte()
        buf.putInt(0x3C, stringIdsOff)
        buf.putInt(0x4C, typeIdsOff)
        buf.putInt(0x60, descriptors.size)
        buf.putInt(0x68, classDefsOff)
        descriptors.forEachIndexed { i, descriptor ->
            buf.putInt(stringIdsOff + i * 4, dataOffsets[i])
            buf.putInt(typeIdsOff + i * 4, i)
            buf.putInt(classDefsOff + i * 32, i)
            var p = dataOffsets[i]
            out[p++] = descriptor.length.toByte()
            descriptor.forEach { c -> out[p++] = c.code.toByte() }
            out[p] = 0
        }
        return out
    }
}
