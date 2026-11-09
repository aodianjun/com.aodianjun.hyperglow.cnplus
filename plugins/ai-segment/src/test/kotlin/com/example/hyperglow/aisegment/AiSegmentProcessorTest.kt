package com.example.hyperglow.aisegment

import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricsUpdateMode
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginLogger
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 内存版 PluginConfig(manifest 默认值由宿主合并;测试里直接给映射)。 */
private class FakeConfig(private val values: Map<String, Any?>) : PluginConfig {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        values[key] as? Boolean ?: defaultValue

    override fun getString(key: String, defaultValue: String?): String? =
        (values[key] as? String) ?: defaultValue

    override fun getLong(key: String, defaultValue: Long): Long =
        (values[key] as? Number)?.toLong() ?: defaultValue

    override fun getFloat(key: String, defaultValue: Float): Float =
        (values[key] as? Number)?.toFloat() ?: defaultValue

    override fun getStringSet(key: String, defaultValue: Set<String>): Set<String> =
        (values[key] as? Set<String>) ?: defaultValue
}

private class FakeLogger : PluginLogger {
    override fun debug(message: String) {}
    override fun info(message: String) {}
    override fun warn(message: String, throwable: Throwable?) {}
    override fun error(message: String, throwable: Throwable?) {}
}

class AiSegmentProcessorTest {

    private class FakeContext(override val config: PluginConfig) : PluginContext {
        override val pluginId: String = "com.example.hyperglow.aisegment"
        override val hostApiVersion: Int = 1
        override val logger: PluginLogger = FakeLogger()
        override val cache: com.lidesheng.hyperlyric.plugin.api.PluginCache =
            object : com.lidesheng.hyperlyric.plugin.api.PluginCache {
                private val map = mutableMapOf<String, String>()
                override fun getString(key: String): String? = map[key]
                override fun putString(key: String, value: String) { map[key] = value }
                override fun getBytes(key: String): ByteArray? = map[key]?.toByteArray()
                override fun putBytes(key: String, value: ByteArray) {
                    map[key] = value.toString(Charsets.UTF_8)
                }
                override fun contains(key: String): Boolean = map.containsKey(key)
                override fun remove(key: String) { map.remove(key) }
                override fun clear() { map.clear() }
            }
        override val storage: com.lidesheng.hyperlyric.plugin.api.PluginStorage =
            object : com.lidesheng.hyperlyric.plugin.api.PluginStorage {
                private val map = mutableMapOf<String, String>()
                override fun getString(key: String, defaultValue: String?): String? = map[key] ?: defaultValue
                override fun putString(key: String, value: String) { map[key] = value }
                override fun remove(key: String) { map.remove(key) }
                override fun clear() { map.clear() }
            }
        override fun registerExtension(extension: com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension) {}
    }

    private class FakeClient(
        private val respond: (List<RowToSegment>) -> SegmentationParsed?,
    ) : SegmentClient("https://fake.invalid/v1") {
        var calls = 0
        override fun segment(
            config: SegmentConfig,
            meta: TrackMeta,
            rows: List<RowToSegment>,
            budgetMs: Int,
        ): SegmentationParsed? {
            calls++
            return respond(rows)
        }
    }

    private fun song(vararg lines: String, withWords: Boolean = false): PluginSong {
        val step = 3_000L
        val rows = lines.mapIndexed { index, text ->
            PluginLyricLine(
                begin = 1_000L + index * step,
                end = 1_000L + (index + 1) * step,
                duration = step,
                text = text,
                words = if (withWords) {
                    syntheticFor(text, 1_000L + index * step, 1_000L + (index + 1) * step)
                } else {
                    null
                },
            )
        }
        return PluginSong(id = "song1", name = "测试曲目", lyrics = rows)
    }

    /** 与 LineWindows 合成口径一致的最小词表(逐字)。 */
    private fun syntheticFor(text: String, begin: Long, end: Long): List<PluginWord> {
        val chars = text.toCharArray()
        val window = end - begin
        return chars.mapIndexed { index, ch ->
            val b = begin + window * index / chars.size
            val e = begin + window * (index + 1) / chars.size
            PluginWord(b, e, e - b, ch.toString())
        }
    }

    private fun processor(
        context: PluginContext,
        client: SegmentClient,
        clock: () -> Long,
    ): AiSegmentProcessor = AiSegmentProcessor(
        context = context,
        cache = SegmentationCache(object : com.lidesheng.hyperlyric.plugin.api.PluginCache {
            private val map = mutableMapOf<String, String>()
            override fun getString(key: String): String? = map[key]
            override fun putString(key: String, value: String) { map[key] = value }
            override fun getBytes(key: String): ByteArray? = map[key]?.toByteArray()
            override fun putBytes(key: String, value: ByteArray) {
                map[key] = value.toString(Charsets.UTF_8)
            }
            override fun contains(key: String): Boolean = map.containsKey(key)
            override fun remove(key: String) { map.remove(key) }
            override fun clear() { map.clear() }
        }),
        clientFactory = { client },
        nowMs = clock,
    )

    private fun configMap(maxChars: Int = 8) = mapOf<String, Any?>(
        "enabled" to true,
        "base_url" to "https://fake.invalid/v1",
        "model" to "test-model",
        "api_key" to "sk-test",
        "max_line_chars" to maxChars,
    )

    @Test
    fun shortLinesAreLeftUntouched() {
        var calls = 0
        val client = FakeClient({ calls++; null })
        val proc = processor(FakeContext(FakeConfig(configMap(maxChars = 20))), client, clock = { 0L })
        val result = proc.processResult(song("短行一", "短行二"), PluginProcessingContext())
        assertNull(result)
        assertEquals(0, calls)
    }

    @Test
    fun longLinesAreReplacedBySegmentedRows() {
        val client = FakeClient({ rows ->
            SegmentationParsed(
                rows.associate { row ->
                    val half = row.text.length / 2
                    row.number to listOf(row.text.take(half), row.text.drop(half))
                }
            )
        })
        val proc = processor(FakeContext(FakeConfig(configMap(maxChars = 8))), client, clock = { 0L })
        val result = proc.processResult(
            song("一二三四五六七八九", "短行"),
            PluginProcessingContext(),
        )
        assertNotNull(result)
        assertEquals(PluginLyricsUpdateMode.REPLACE, result.lyricsUpdateMode)
        assertEquals(setOf(PluginSongField.LYRICS), result.changedFields)
        // 长行拆 2 子行 + 短行原样 → 3 行
        assertEquals(3, result.song.lyrics?.size)
        assertEquals("一二三四", result.song.lyrics?.get(0)?.text)
        assertEquals("五六七八九", result.song.lyrics?.get(1)?.text)
        assertTrue(result.changedLyricFields.contains(PluginLyricField.TEXT))
        assertTrue(result.changedLyricFields.contains(PluginLyricField.WORDS))
    }

    @Test
    fun cachedRowsSkipTheNetworkCall() {
        var calls = 0
        val client = FakeClient({ rows ->
            calls++
            SegmentationParsed(rows.associate { row ->
                row.number to listOf(row.text.take(4), row.text.drop(4))
            })
        })
        val proc = processor(FakeContext(FakeConfig(configMap(maxChars = 8))), client, clock = { 0L })
        proc.processResult(song("一二三四五六七八九"), PluginProcessingContext())
        assertEquals(1, calls)
        // 第二次跑同一首歌:行缓存命中 → 不再请求
        proc.processResult(song("一二三四五六七八九"), PluginProcessingContext())
        assertEquals(1, calls)
    }

    @Test
    fun failedRequestEntersCooldown() {
        val client = FakeClient({ null })
        var now = 0L
        val proc = processor(FakeContext(FakeConfig(configMap(maxChars = 8))), client, clock = { now })
        assertNull(proc.processResult(song("一二三四五六七八九"), PluginProcessingContext()))
        assertEquals(1, client.calls)
        // 冷却期内不再发请求
        now += 60_000L
        assertNull(proc.processResult(song("一二三四五六七八九"), PluginProcessingContext()))
        assertEquals(1, client.calls)
    }

    @Test
    fun mismatchedSegmentationFallsBackToPassthrough() {
        // 行 1 AI 输出拼回不一致 → 回落原样;行 2 正常拆分 → 仍然 REPLACE
        val client = FakeClient({ rows ->
            SegmentationParsed(
                rows.associate { row ->
                    if (row.number == 1) {
                        row.number to listOf("被 AI 改坏的文本")
                    } else {
                        row.number to listOf(row.text.take(4), row.text.drop(4))
                    }
                }
            )
        })
        val proc = processor(FakeContext(FakeConfig(configMap(maxChars = 8))), client, clock = { 0L })
        val result: PluginSongResult? = proc.processResult(
            song("一二三四五六七八九", "九八七六五四三二一"),
            PluginProcessingContext(),
        )
        assertNotNull(result)
        assertEquals(3, result.song.lyrics?.size)
        assertEquals("一二三四五六七八九", result.song.lyrics?.get(0)?.text)
    }

    @Test
    fun missingConfigIsANoOp() {
        val client = FakeClient({ SegmentationParsed(emptyMap()) })
        val proc = processor(FakeContext(FakeConfig(mapOf("enabled" to true))), client, clock = { 0L })
        assertNull(proc.processResult(song("一二三四五六七八九"), PluginProcessingContext()))
    }
}
