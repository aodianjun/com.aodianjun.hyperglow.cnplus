package com.example.hyperglow.scriptconvert

import com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginLogger
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginStorage
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 处理器行为钉子：转换只动内容字段，时间轴/分侧/角色 metadata 原样保留；
 * 未声明翻译开关时不碰翻译；目标字形已一致时返回 null（宿主保持透传）。
 */
class ScriptConvertPluginTest {

    @Test
    fun convertsTextWordsAndTranslationKeepingTimeline() {
        val processor = processorOf(mapOf("scriptconvert_target" to "traditional"))
        val song = songOf(
            PluginLyricLine(
                begin = 1_000L,
                end = 4_000L,
                duration = 3_000L,
                isAlignedRight = true,
                metadata = PluginMetadata(values = mapOf("role" to "LEAD")),
                text = "我爱你",
                words = listOf(
                    PluginWord(begin = 1_000L, end = 2_000L, duration = 1_000L, text = "我"),
                    PluginWord(begin = 2_000L, end = 4_000L, duration = 2_000L, text = "爱你"),
                ),
                translation = "I love you",
                translationWords = listOf(
                    PluginWord(begin = 1_000L, end = 4_000L, duration = 3_000L, text = "我爱你"),
                ),
                roma = "wo ai ni",
            )
        )

        val result = assertNotNull(processor.processResult(song))
        assertEquals(setOf(PluginSongField.LYRICS), result.changedFields)
        assertEquals(PluginLyricsUpdateMode.PATCH, result.lyricsUpdateMode)
        assertEquals(
            setOf(
                PluginLyricField.TEXT,
                PluginLyricField.WORDS,
                PluginLyricField.TRANSLATION_WORDS,
            ),
            result.changedLyricFields,
        )

        val row = assertNotNull(result.song.lyrics).single()
        assertEquals("我愛你", row.text)
        assertEquals(listOf("我", "愛你"), row.words?.map { it.text })
        assertEquals("I love you", row.translation)
        assertEquals(listOf("我愛你"), row.translationWords?.map { it.text })

        // 时间轴、分侧、角色 metadata、roma 原样保留。
        assertEquals(1_000L, row.begin)
        assertEquals(4_000L, row.end)
        assertEquals(3_000L, row.duration)
        assertTrue(row.isAlignedRight)
        assertEquals("LEAD", row.metadata?.values?.get("role"))
        assertEquals("wo ai ni", row.roma)
        assertEquals(listOf(1_000L, 2_000L), row.words?.map { it.begin })
        assertEquals(listOf(2_000L, 4_000L), row.words?.map { it.end })
    }

    @Test
    fun translationLeftAloneWhenDisabled() {
        val processor = processorOf(
            mapOf(
                "scriptconvert_target" to "traditional",
                "scriptconvert_translation" to false,
            )
        )
        val song = songOf(
            PluginLyricLine(begin = 0L, end = 1_000L, text = "再见", translation = "再见")
        )

        val result = assertNotNull(processor.processResult(song))
        assertEquals(setOf(PluginLyricField.TEXT), result.changedLyricFields)
        val row = assertNotNull(result.song.lyrics).single()
        assertEquals("再見", row.text)
        assertEquals("再见", row.translation)
    }

    @Test
    fun returnsNullWhenAlreadyTargetScript() {
        val processor = processorOf(mapOf("scriptconvert_target" to "simplified"))
        val song = songOf(PluginLyricLine(begin = 0L, end = 1_000L, text = "我爱你"))
        assertNull(processor.processResult(song))
    }

    @Test
    fun returnsNullForEmptyOrAbsentLyrics() {
        val processor = processorOf(mapOf("scriptconvert_target" to "traditional"))
        assertNull(processor.processResult(PluginSong(name = "no lyrics")))
        assertNull(processor.processResult(PluginSong(name = "empty", lyrics = emptyList())))
    }

    @Test
    fun rowsWithoutWordsStillConvertText() {
        val processor = processorOf(mapOf("scriptconvert_target" to "traditional"))
        val song = songOf(
            PluginLyricLine(begin = 0L, end = 1_000L, text = "头发"),
            PluginLyricLine(begin = 1_000L, end = 2_000L, text = "干杯"),
        )
        val result = assertNotNull(processor.processResult(song))
        assertEquals(setOf(PluginLyricField.TEXT), result.changedLyricFields)
        assertEquals(listOf("頭髮", "乾杯"), assertNotNull(result.song.lyrics).map { it.text })
    }

    private fun songOf(vararg rows: PluginLyricLine): PluginSong =
        PluginSong(name = "test", artist = "artist", duration = 10_000L, lyrics = rows.toList())

    private fun processorOf(config: Map<String, Any>): LyricProcessorExtension {
        val context = FakePluginContext(config)
        ScriptConvertPlugin().onLoad(context)
        val extension = context.extensions.singleOrNull()
        return assertNotNull(extension as? LyricProcessorExtension, "plugin must register one processor")
    }

    private class FakePluginContext(private val values: Map<String, Any>) : PluginContext {
        val extensions = mutableListOf<HyperLyricExtension>()
        override val pluginId: String = "com.example.hyperglow.scriptconvert"
        override val hostApiVersion: Int = 1
        override val config: PluginConfig = FakePluginConfig(values)
        override val logger: PluginLogger = object : PluginLogger {
            override fun debug(message: String) = Unit
            override fun info(message: String) = Unit
            override fun warn(message: String, throwable: Throwable?) = Unit
            override fun error(message: String, throwable: Throwable?) = Unit
        }
        override val cache: PluginCache = object : PluginCache {
            private val map = HashMap<String, Any>()
            override fun getString(key: String): String? = map[key] as? String
            override fun putString(key: String, value: String) { map[key] = value }
            override fun getBytes(key: String): ByteArray? = map[key] as? ByteArray
            override fun putBytes(key: String, value: ByteArray) { map[key] = value }
            override fun contains(key: String): Boolean = map.containsKey(key)
            override fun remove(key: String) { map.remove(key) }
            override fun clear() = map.clear()
        }
        override val storage: PluginStorage = object : PluginStorage {
            private val map = HashMap<String, String>()
            override fun getString(key: String, defaultValue: String?): String? =
                map[key] ?: defaultValue
            override fun putString(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
            override fun clear() = map.clear()
        }
        override fun registerExtension(extension: HyperLyricExtension) {
            extensions += extension
        }
    }

    private class FakePluginConfig(private val values: Map<String, Any>) : PluginConfig {
        override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
            values[key] as? Boolean ?: defaultValue
        override fun getString(key: String, defaultValue: String?): String? =
            values[key] as? String ?: defaultValue
        override fun getLong(key: String, defaultValue: Long): Long =
            values[key] as? Long ?: defaultValue
        override fun getFloat(key: String, defaultValue: Float): Float =
            values[key] as? Float ?: defaultValue
        override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
            @Suppress("UNCHECKED_CAST")
            (values[key] as? Set<String>) ?: defaultValue
    }
}
