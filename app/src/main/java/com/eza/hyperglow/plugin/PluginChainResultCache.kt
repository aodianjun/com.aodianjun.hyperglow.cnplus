package com.eza.hyperglow.plugin

import android.content.Context
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.producer.LyricProducerState
import com.lidesheng.hyperlyric.plugin.api.PluginLyricField
import com.lidesheng.hyperlyric.plugin.api.PluginLyricLine
import com.lidesheng.hyperlyric.plugin.api.PluginMetadata
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongField
import com.lidesheng.hyperlyric.plugin.api.PluginWord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 插件链结果缓存（「歌词计算写入缓存」）：把 [PluginRuntime.processChain] 对一首歌的
 * 处理结果（翻译/罗马音/逐字等富化）按**歌曲身份**落盘，再次播放到同一首歌时直接
 * 复用，不再重跑插件链——链内可能是逐行 LLM 调用，重复播放重复计算既慢又贵。
 *
 * 正确性三道闸（任何一道不满足就当未命中，回退重跑链）：
 * 1. **歌曲身份** [songIdentity]：producerId + 归一化歌名/歌手 + 时长秒，跨会话稳定
 *   （刻意不含 generation/trackUri——两者随播放会话变化，会使同一首歌永远不命中）；
 * 2. **输入指纹** [inputFingerprint]：原始行表内容摘要。同一身份但歌词内容变了
 *   （源换了几行、纯 LRC 换成逐字 TTML）时旧结果不可复用；
 * 3. **链纪元** [chainEpoch]：已装插件的 id+版本+激活态+全部设置值摘要。插件安装/
 *   卸载/设置变更都会改变纪元，旧结果自动失效，不需要手动清缓存。
 *
 * 存储为宿主侧文件（`files/plugin_chain_cache/<sha256(identity)>.json`），LRU 上限
 * [MAX_ENTRIES] 条 / [MAX_TOTAL_BYTES] 字节，超出按 lastUsedAt 淘汰。全部 IO 失败均
 * 吞掉记日志：缓存永远是优化，绝不能让读写出错影响播放链。
 */
object PluginChainResultCache {
    private const val TAG = "PluginChainCache"

    /** LRU 条数上限：一首全量逐字+翻译的条目约几十到几百 KB，32 条足够日常回放。 */
    const val MAX_ENTRIES = 32

    /** 总字节预算：单条异常膨胀（超长歌词+全词表）也不至于吃掉几十 MiB 磁盘。 */
    const val MAX_TOTAL_BYTES = 6L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- 键与指纹 ---

    /**
     * 跨会话稳定的歌曲身份：producerId + 归一化(歌名/歌手) + 时长秒。归一化 = 去首尾
     * 空白、转小写、折叠连续空白——同一首歌各次会话的元数据常有大小写/空格抖动。
     * 歌名为空的会话（源还没给元数据）不缓存：身份不可信，命中会是别的歌。
     */
    fun songIdentity(state: LyricProducerState): String? {
        val title = normalizeTitleToken(state.title) ?: return null
        val artist = normalizeTitleToken(state.artist) ?: ""
        val durationSec = if (state.durationMs > 0L) state.durationMs / 1000L else 0L
        return "${state.producerId}|$title|$artist|$durationSec"
    }

    /** 歌名/歌手归一：空白折叠 + 小写；空白结果按缺失处理（歌名缺失返回 null）。 */
    private fun normalizeTitleToken(value: String): String? =
        value.trim().replace(Regex("\\s+"), " ").lowercase().ifEmpty { null }

    /**
     * 链纪元：已加载插件里每个可激活插件的 id+版本+激活态+设置值摘要的哈希。
     * 设置读取走 [PluginSettingsStore.readAll]（SharedPreferences 内存 map，廉价）；
     * 序列化按 key 排序保证同状态同哈希。插件安装/卸载/设置任何一项变化都会翻转纪元。
     */
    fun chainEpoch(context: Context): String {
        val descriptor = PluginRuntime.installed().joinToString(";") { plugin ->
            val activated = runCatching { plugin.isActivated(context) }.getOrDefault(true)
            val settings = if (activated && plugin.plugin != null) {
                PluginSettingsStore.readAll(context, plugin.manifest.id)
                    .entries
                    .sortedBy { it.key }
                    .joinToString(",") { (key, value) -> "$key=${describeValue(value)}" }
            } else {
                ""
            }
            "${plugin.manifest.id}@${plugin.manifest.version}:${plugin.plugin != null}:$activated:$settings"
        }
        return sha256(descriptor)
    }

    /** 设置值的稳定字符串化（仅用于纪元哈希；backup=false 的键同样参与——改了就该失效）。 */
    private fun describeValue(value: Any?): String = when (value) {
        null -> "null"
        is Set<*> -> value.filterIsInstance<String>().sorted().joinToString(prefix = "[", postfix = "]")
        else -> value.toString()
    }

    /**
     * 链输入指纹：纪元 + 原始行表内容摘要。行摘要取会被插件读取/回写覆盖的全部内容
     * 字段（文本、窗口、角色、词表、翻译/罗马音），行数变化或任一行内容变化都翻转指纹。
     */
    fun inputFingerprint(song: PluginSong, epoch: String): String {
        val digest = StringBuilder(epoch).append('\n')
        val rows = song.lyrics.orEmpty()
        digest.append(rows.size).append('\n')
        rows.forEachIndexed { index, row ->
            digest.append(index).append('|')
                .append(row.begin).append('|').append(row.end).append('|')
                .append(row.isAlignedRight).append('|')
                .append(row.metadata?.values.toString()).append('|')
                .append(row.text.orEmpty()).append('|')
                .append(row.secondary.orEmpty()).append('|')
                .append(row.translation.orEmpty()).append('|')
                .append(row.roma.orEmpty()).append('|')
                .append(wordsDigest(row.words)).append('|')
                .append(wordsDigest(row.secondaryWords)).append('|')
                .append(wordsDigest(row.translationWords)).append('\n')
        }
        return sha256(digest.toString())
    }

    private fun wordsDigest(words: List<PluginWord>?): String =
        if (words.isNullOrEmpty()) {
            "-"
        } else {
            words.joinToString(",") { "${it.begin}-${it.end}:${it.text.orEmpty()}" }
        }

    // --- 读写 ---

    /**
     * 命中则返回按当前会话重新钉键的 [PatchedSong]（含 passthrough 空改动结果——
     * 复用「上次链跑完没有改动」同样免掉这次重跑）；任何形态的未命中/损坏返回 null。
     */
    fun load(
        context: Context,
        identity: String,
        fingerprint: String,
        sessionKey: String
    ): PatchedSong? {
        val file = entryFile(context, identity) ?: return null
        val entry = runCatching {
            json.decodeFromString(CachedChainResult.serializer(), file.readText())
        }.getOrElse { error ->
            AppLog.w(TAG, "cache decode failed (${file.name}): ${error.message}")
            return null
        }
        if (entry.fingerprint != fingerprint) {
            AppLog.i(TAG, "cache stale (fingerprint changed) for ${file.name}")
            return null
        }
        val song = entry.song.toPluginSong()
        // LRU 触碰：命中即续期（best-effort；失败只影响淘汰顺序，不影响正确性）。
        runCatching {
            file.writeText(
                json.encodeToString(
                    CachedChainResult.serializer(),
                    entry.copy(lastUsedAtMs = System.currentTimeMillis())
                )
            )
        }
        AppLog.i(
            TAG,
            "cache hit for ${file.name}: songFields=${entry.changedSongFields} " +
                "lyricFields=${entry.changedLyricFields} rows=${song.lyrics?.size ?: 0}"
        )
        return PatchedSong(
            sessionKey = sessionKey,
            song = song,
            changedSongFields = entry.changedSongFields.mapNotNull { runCatching { PluginSongField.valueOf(it) }.getOrNull() }.toSet(),
            changedLyricFields = entry.changedLyricFields.mapNotNull { runCatching { PluginLyricField.valueOf(it) }.getOrNull() }.toSet()
        )
    }

    /** 落盘一份链结果（含 passthrough）。identity/fingerprint 由调用方按同一首歌给出。 */
    fun store(context: Context, identity: String, fingerprint: String, patched: PatchedSong) {
        store(context, identity, fingerprint, patched, title = "", artist = "", producerId = "")
    }

    /** 同 [store]，附展示信息（歌曲名/歌手/来源）供缓存管理页列出。 */
    fun store(
        context: Context,
        identity: String,
        fingerprint: String,
        patched: PatchedSong,
        title: String,
        artist: String,
        producerId: String
    ) {
        val dir = cacheDir(context) ?: return
        val entry = CachedChainResult(
            version = FORMAT_VERSION,
            identity = identity,
            fingerprint = fingerprint,
            createdAtEpochMs = System.currentTimeMillis(),
            lastUsedAtMs = System.currentTimeMillis(),
            changedSongFields = patched.changedSongFields.map { it.name },
            changedLyricFields = patched.changedLyricFields.map { it.name },
            songTitle = title,
            songArtist = artist,
            producerId = producerId,
            song = CachedSong.from(patched.song)
        )
        val bytes = runCatching { json.encodeToString(CachedChainResult.serializer(), entry) }
            .getOrElse { error ->
                AppLog.w(TAG, "cache encode failed: ${error.message}")
                return
            }
        val file = entryFile(context, identity) ?: return
        val ok = runCatching {
            dir.mkdirs()
            file.writeText(bytes)
            evictOverflow(context)
        }.isSuccess
        AppLog.i(TAG, "cache store ${if (ok) "ok" else "failed"}: ${file.name} bytes=${bytes.length}")
    }

    /** 全清（测试与诊断用；日常失效靠纪元/指纹自动判定，不需要用户动手）。 */
    fun clear(context: Context) {
        runCatching { cacheDir(context)?.deleteRecursively() }
        AppLog.i(TAG, "cache cleared")
    }

    /** 当前缓存占用量（管理页展示用）；目录不可读返回 0。 */
    fun sizeBytes(context: Context): Long =
        cacheDir(context)?.listFiles()?.sumOf { it.length() } ?: 0L

    /** 缓存管理页的一条列表项：歌曲展示信息 + 大小/时间。 */
    data class ChainCacheEntry(
        val identity: String,
        val title: String,
        val artist: String,
        val producerId: String,
        val rows: Int,
        val sizeBytes: Long,
        val createdAtEpochMs: Long
    ) {
        /** 列表主行标题：歌名优先，缺失回落身份串尾段（不该出现，防呆）。 */
        val displayTitle: String
            get() = title.ifBlank { identity.substringAfterLast('|', identity) }
    }

    /** 列出全部缓存条目（按缓存时间新→旧）；IO 失败返回空表。 */
    fun listEntries(context: Context): List<ChainCacheEntry> {
        val dir = cacheDir(context) ?: return emptyList()
        return dir.listFiles()
            ?.mapNotNull { file ->
                runCatching {
                    val entry = json.decodeFromString(
                        CachedChainResult.serializer(), file.readText()
                    )
                    ChainCacheEntry(
                        identity = entry.identity,
                        title = entry.songTitle,
                        artist = entry.songArtist,
                        producerId = entry.producerId,
                        rows = entry.song.lyrics.size,
                        sizeBytes = file.length(),
                        createdAtEpochMs = entry.createdAtEpochMs
                    )
                }.getOrNull()
            }
            .orEmpty()
            .sortedByDescending { it.createdAtEpochMs }
    }

    /** 删除单条缓存（按歌曲身份）；返回是否真的删掉了文件。 */
    fun clearEntry(context: Context, identity: String): Boolean {
        val file = entryFile(context, identity) ?: return false
        val removed = runCatching { file.delete() }.getOrDefault(false)
        AppLog.i(TAG, "cache entry ${if (removed) "removed" else "remove failed"}: ${file.name}")
        return removed
    }

    // --- 存储与淘汰 ---

    private const val FORMAT_VERSION = 1

    private fun cacheDir(context: Context): File? = runCatching {
        File(context.applicationContext.filesDir, "plugin_chain_cache")
    }.getOrNull()

    private fun entryFile(context: Context, identity: String): File? {
        val dir = cacheDir(context) ?: return null
        return File(dir, sha256(identity) + ".json")
    }

    /** LRU 淘汰：条数或总字节超限时按 lastUsedAt 从旧到新删，直到回到限额内。 */
    private fun evictOverflow(context: Context) {
        val dir = cacheDir(context) ?: return
        val files = dir.listFiles()?.filter { it.isFile }?.ifEmpty { return } ?: return
        var total = files.sumOf { it.length() }
        val ordered = files.sortedBy { file ->
            runCatching {
                json.decodeFromString(CachedChainResult.serializer(), file.readText()).lastUsedAtMs
            }.getOrElse { Long.MIN_VALUE }
        }
        val toDelete = mutableSetOf<File>()
        var count = ordered.size
        for (file in ordered) {
            if (count <= MAX_ENTRIES && total <= MAX_TOTAL_BYTES) break
            toDelete += file
            total -= file.length()
            count--
        }
        if (toDelete.isNotEmpty()) {
            toDelete.forEach { runCatching { it.delete() } }
            AppLog.i(TAG, "cache evicted ${toDelete.size} entr(ies), remaining≈$total bytes")
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    // --- 序列化模型（PluginSong 的可持久化投影） ---

    @Serializable
    private data class CachedChainResult(
        val version: Int,
        /** 完整歌曲身份串（管理页展示与单条删除都按它定位）。 */
        val identity: String = "",
        val fingerprint: String,
        val createdAtEpochMs: Long,
        val lastUsedAtMs: Long,
        val songTitle: String = "",
        val songArtist: String = "",
        val producerId: String = "",
        val changedSongFields: List<String>,
        val changedLyricFields: List<String>,
        val song: CachedSong
    )

    @Serializable
    data class CachedSong(
        val id: String? = null,
        val name: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val duration: Long = 0L,
        val metadata: Map<String, String?> = emptyMap(),
        val lyrics: List<CachedLine> = emptyList()
    ) {
        fun toPluginSong(): PluginSong = PluginSong(
            id = id,
            name = name,
            artist = artist,
            album = album,
            duration = duration,
            metadata = PluginMetadata(values = metadata),
            lyrics = lyrics.map { it.toPluginLine() }
        )

        companion object {
            fun from(song: PluginSong): CachedSong = CachedSong(
                id = song.id,
                name = song.name,
                artist = song.artist,
                album = song.album,
                duration = song.duration,
                metadata = song.metadata?.values.orEmpty(),
                lyrics = song.lyrics.orEmpty().map { CachedLine.from(it) }
            )
        }
    }

    @Serializable
    data class CachedLine(
        val begin: Long = 0L,
        val end: Long = 0L,
        val duration: Long = 0L,
        val isAlignedRight: Boolean = false,
        val metadata: Map<String, String?> = emptyMap(),
        val text: String? = null,
        val words: List<CachedWord> = emptyList(),
        val secondary: String? = null,
        val secondaryWords: List<CachedWord> = emptyList(),
        val translation: String? = null,
        val translationWords: List<CachedWord> = emptyList(),
        val roma: String? = null
    ) {
        fun toPluginLine(): PluginLyricLine = PluginLyricLine(
            begin = begin,
            end = end,
            duration = duration,
            isAlignedRight = isAlignedRight,
            metadata = PluginMetadata(values = metadata),
            text = text,
            words = words.takeIf { it.isNotEmpty() }?.map { it.toPluginWord() },
            secondary = secondary,
            secondaryWords = secondaryWords.takeIf { it.isNotEmpty() }?.map { it.toPluginWord() },
            translation = translation,
            translationWords = translationWords.takeIf { it.isNotEmpty() }?.map { it.toPluginWord() },
            roma = roma
        )

        companion object {
            fun from(line: PluginLyricLine): CachedLine = CachedLine(
                begin = line.begin,
                end = line.end,
                duration = line.duration,
                isAlignedRight = line.isAlignedRight,
                metadata = line.metadata?.values.orEmpty(),
                text = line.text,
                words = line.words.orEmpty().map(CachedWord::from),
                secondary = line.secondary,
                secondaryWords = line.secondaryWords.orEmpty().map(CachedWord::from),
                translation = line.translation,
                translationWords = line.translationWords.orEmpty().map(CachedWord::from),
                roma = line.roma
            )
        }
    }

    @Serializable
    data class CachedWord(
        val begin: Long = 0L,
        val end: Long = 0L,
        val duration: Long = 0L,
        val text: String? = null
    ) {
        fun toPluginWord(): PluginWord = PluginWord(
            begin = begin,
            end = end,
            duration = duration,
            text = text
        )

        companion object {
            fun from(word: PluginWord): CachedWord = CachedWord(
                begin = word.begin,
                end = word.end,
                duration = word.duration,
                text = word.text
            )
        }
    }
}
