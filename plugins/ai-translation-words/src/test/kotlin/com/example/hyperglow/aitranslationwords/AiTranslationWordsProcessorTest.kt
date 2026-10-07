package com.example.hyperglow.aitranslationwords

import com.lidesheng.hyperlyric.plugin.api.HyperLyricExtension
import com.lidesheng.hyperlyric.plugin.api.PluginCache
import com.lidesheng.hyperlyric.plugin.api.PluginConfig
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 处理器的分批 / 渐进 / 退避行为（真机实测教训：整首一次发时几十行的歌会让端到端超过单次预算，
 * 整首失败并进长冷却，观感就是「插件没在用」）。
 *
 * 全部走注入的 [BatchSender] 假实现，不触网；时钟注入以便验证总预算。
 */
class AiTranslationWordsProcessorTest {

    // ---- 假实现 -----------------------------------------------------------

    private class FakeConfig(private val values: Map<String, Any>) : PluginConfig {
        override fun getBoolean(key: String, defaultValue: Boolean) = values[key] as? Boolean ?: defaultValue
        override fun getString(key: String, defaultValue: String?) = values[key] as? String ?: defaultValue
        override fun getLong(key: String, defaultValue: Long) = values[key] as? Long ?: defaultValue
        override fun getFloat(key: String, defaultValue: Float) = values[key] as? Float ?: defaultValue
        override fun getStringSet(key: String, defaultValue: Set<String>) =
            (values[key] as? Set<String>) ?: defaultValue
    }

    private class FakeLogger : com.lidesheng.hyperlyric.plugin.api.PluginLogger {
        val warnings = mutableListOf<String>()
        val infos = mutableListOf<String>()
        override fun debug(message: String) {}
        override fun info(message: String) { infos += message }
        override fun warn(message: String, throwable: Throwable?) { warnings += message }
        override fun error(message: String, throwable: Throwable?) { warnings += message }
    }

    private class FakePluginCache : PluginCache {
        val map = LinkedHashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String) { map[key] = value }
        override fun getBytes(key: String): ByteArray? = null
        override fun putBytes(key: String, value: ByteArray) {}
        override fun contains(key: String) = map.containsKey(key)
        override fun remove(key: String) { map.remove(key) }
        override fun clear() = map.clear()
    }

    private class FakeContext(override val config: PluginConfig, override val cache: PluginCache) : PluginContext {
        override val pluginId = "com.example.hyperglow.aitranslationwords"
        override val hostApiVersion = 1
        override val logger = FakeLogger()
        override val storage = object : com.lidesheng.hyperlyric.plugin.api.PluginStorage {
            private val map = mutableMapOf<String, String>()
            override fun getString(key: String, defaultValue: String?) = map[key] ?: defaultValue
            override fun putString(key: String, value: String) { map[key] = value }
            override fun remove(key: String) { map.remove(key) }
            override fun clear() = map.clear()
        }
        override fun registerExtension(extension: HyperLyricExtension) {}
    }

    /** 记录每批的行数与 tokens，按脚本决定成功/失败。 */
    private class RecordingSender(
        private val failBatches: Set<Int> = emptySet(),
        private val clock: () -> Unit = {},
    ) : BatchSender {
        val batchSizes = mutableListOf<Int>()
        val batchTokens = mutableListOf<List<String>?>()
        override fun send(
            config: TranslationWordsConfig,
            meta: TrackMeta,
            lines: List<LineToTranslate>,
            expectedTokenCounts: Map<Int, Int>,
            budgetMs: Int,
        ): Map<Int, ParsedLine>? {
            val index = batchSizes.size
            batchSizes += lines.size
            batchTokens += lines.firstOrNull()?.tokens
            clock()
            if (index in failBatches) return null
            return lines.associate { line ->
                val fragments = expectedTokenCounts[line.index]?.let { count ->
                    List(count) { i -> if (i == 0) "译" else "文" }
                }
                line.index to ParsedLine(fragments?.joinToString("") ?: "译文", fragments)
            }
        }
    }

    // ---- 夹具 -------------------------------------------------------------

    private val baseConfig = mapOf(
        "enabled" to true,
        "api_key" to "test-key",
        "model" to "test-model",
        "target_language" to "中文",
        "force_override" to true,
        "word_timing" to true,
    )

    private fun song(lineCount: Int, wordsPerLine: Int = 0): PluginSong = PluginSong(
        name = "Test Song",
        artist = "Test Artist",
        lyrics = List(lineCount) { index ->
            PluginLyricLine(
                begin = index * 1_000L,
                end = index * 1_000L + 900L,
                duration = 900L,
                text = "line $index",
                words = if (wordsPerLine > 0) {
                    List(wordsPerLine) { w ->
                        PluginWord(
                            begin = index * 1_000L + w * 300L,
                            end = index * 1_000L + w * 300L + 300L,
                            duration = 300L,
                            text = "w$w"
                        )
                    }
                } else {
                    null
                }
            )
        }
    )

    private fun processor(
        sender: BatchSender,
        cache: FakePluginCache = FakePluginCache(),
        clock: () -> Long = { 0L },
        config: Map<String, Any> = baseConfig,
    ): Pair<AiTranslationWordsProcessor, FakePluginCache> {
        val context = FakeContext(FakeConfig(config), cache)
        return AiTranslationWordsProcessor(context, TranslationWordsCache(cache, clock), sender, clock) to cache
    }

    // ---- 用例 -------------------------------------------------------------

    @Test
    fun longSongIsTranslatedInBatches() {
        val sender = RecordingSender()
        val (processor, _) = processor(sender)

        val result = processor.processResult(song(30), PluginProcessingContext())

        assertNotNull(result)
        assertEquals(listOf(12, 12, 6), sender.batchSizes)
        assertEquals(30, result!!.song.lyrics!!.count { it.translation == "译文" })
        assertEquals(setOf(PluginLyricField.TRANSLATION), result.changedLyricFields)
    }

    @Test
    fun failedBatchDoesNotStopTheRemainingBatches() {
        val sender = RecordingSender(failBatches = setOf(1))
        val (processor, cache) = processor(sender)

        val result = processor.processResult(song(30), PluginProcessingContext())

        assertNotNull(result)
        assertEquals(listOf(12, 12, 6), sender.batchSizes)
        // 第 2 批失败不连累第 3 批：18 行已就绪并落缓存，缺的 12 行留待下一轮。
        assertEquals(18, result!!.song.lyrics!!.count { it.translation == "译文" })
        val payload = cache.map.values.single { it.contains("\"pairs\"") }
        assertEquals(18, org.json.JSONObject(payload).getJSONArray("pairs").length())
    }

    @Test
    fun cachedLinesAreReusedSoOnlyTheMissingBatchIsRequested() {
        val firstSender = RecordingSender(failBatches = setOf(1))
        val (processor, cache) = processor(firstSender)
        processor.processResult(song(30), PluginProcessingContext())

        // 第二轮：只有缺的 12 行发请求（命中行不再重复请求）。
        val secondSender = RecordingSender()
        val (second, _) = processor(secondSender, cache)
        val result = second.processResult(song(30), PluginProcessingContext())

        assertNotNull(result)
        assertEquals(listOf(12), secondSender.batchSizes)
        assertEquals(30, result!!.song.lyrics!!.count { it.translation == "译文" })
    }

    @Test
    fun totalBudgetStopsFurtherBatches() {
        // 每批发一次时钟前进 12s：30s 总预算只够 3 批（第 4 批开始前剩余 < 4s）。
        var elapsed = 0L
        val sender = RecordingSender(clock = { elapsed += 12_000L })
        val (processor, _) = processor(sender, clock = { elapsed })

        val result = processor.processResult(song(48), PluginProcessingContext())

        assertNotNull(result)
        assertEquals(3, sender.batchSizes.size)
        assertEquals(36, result!!.song.lyrics!!.count { it.translation == "译文" })
    }

    @Test
    fun allBatchesFailingSetsShortCooldownAndSkipsNextRun() {
        val sender = RecordingSender(failBatches = setOf(0, 1, 2, 3, 4))
        val (processor, _) = processor(sender)

        assertNull(processor.processResult(song(30), PluginProcessingContext()))
        // 连续 2 批失败即收手，不把预算耗在不可用的端点上。
        assertEquals(2, sender.batchSizes.size)

        val before = sender.batchSizes.size
        assertNull(processor.processResult(song(30), PluginProcessingContext()))
        assertEquals(before, sender.batchSizes.size)
    }

    @Test
    fun wordTimedLinesCarryTokensAndFragments() {
        val sender = RecordingSender()
        val (processor, _) = processor(sender)

        val result = processor.processResult(song(lineCount = 1, wordsPerLine = 2), PluginProcessingContext())

        assertNotNull(result)
        // 逐字行把源词送进请求，并按片段数校验后回写 translationWords（窗口取源词窗口）。
        assertEquals(listOf(listOf("w0", "w1")), sender.batchTokens)
        val row = result!!.song.lyrics!!.single()
        assertEquals("译文", row.translation)
        assertEquals(listOf("译", "文"), row.translationWords!!.map { it.text })
        assertEquals(0L, row.translationWords!![0].begin)
        assertEquals(300L, row.translationWords!![0].end)
        assertEquals(600L, row.translationWords!![1].end)
        assertTrue(PluginLyricField.TRANSLATION_WORDS in result.changedLyricFields)
    }

    @Test
    fun skipLanguagesSkipsWholeSongWithoutSending() {
        val sender = RecordingSender()
        val (processor, _) = processor(sender, config = baseConfig + ("skip_languages" to setOf("en")))

        assertNull(processor.processResult(song(3), PluginProcessingContext()))
        assertTrue(sender.batchSizes.isEmpty())
    }
}
