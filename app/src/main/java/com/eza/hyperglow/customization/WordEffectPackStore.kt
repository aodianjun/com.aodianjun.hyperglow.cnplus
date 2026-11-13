package com.eza.hyperglow.customization

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 单词效果包（`.hgwordanim.json` / 任意 JSON）：
 *
 * ```json
 * {
 *   "format": "hyperglow-word-effect-pack",
 *   "version": 1,
 *   "name": "我的效果包",
 *   "author": "someone",
 *   "effects": [ { "id": "bounce", "name": "弹跳", "tracks": [...] } ]
 * }
 * ```
 *
 * 导入后效果进入 App 私有目录的选择器清单；选中时把**整份规格**写进
 * `SurfaceProfile.wordEffect`，因此删包/换设备后已选效果仍能正确渲染。
 * 纯数据、无代码执行；[normalizeWordEffectSpec] 负责范围钳制与结构校验。
 */
@Serializable
data class WordEffectPack(
    val format: String = "",
    val version: Int = 0,
    val name: String = "",
    val author: String = "",
    val effects: List<WordEffectSpec> = emptyList()
)

/** 选择器条目：规格 + 来源包名（仅展示用）。 */
@Serializable
data class WordEffectEntry(
    val spec: WordEffectSpec,
    val source: String = ""
)

/** 导入结果：成功条目 + 被拒绝条目的简短说明（用于 Toast/诊断）。 */
data class WordEffectImportResult(
    val imported: List<WordEffectSpec>,
    val rejected: List<String>
) {
    val ok: Boolean get() = imported.isNotEmpty()
}

@Serializable
private data class WordEffectStoreFile(
    val version: Int = 1,
    val effects: List<WordEffectEntry> = emptyList()
)

object WordEffectPackStore {
    const val PACK_FORMAT = "hyperglow-word-effect-pack"
    const val PACK_VERSION = 1
    const val MAX_PACK_BYTES = 64 * 1024
    const val MAX_EFFECTS = 32

    /** 私有清单上限:32 个程序效果也不至于把文件撑爆;读侧超限按空清单处理。 */
    private const val MAX_STORE_BYTES = 128 * 1024
    private const val DIR = "word_effects"
    private const val FILE = "effects.json"
    private const val MAX_SOURCE_CHARS = 48

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** 已导入的效果（按导入顺序；同 id 重导入 = 原位替换）。 */
    fun list(context: Context): List<WordEffectSpec> =
        read(context).effects.map { it.spec }

    /**
     * 解析并落盘一个效果包。结构非法的条目逐条拒绝，合法条目照常导入；
     * 整包格式/版本/大小不对时返回空导入 + 原因。
     */
    fun import(
        context: Context,
        bytes: ByteArray,
        sourceName: String = ""
    ): WordEffectImportResult {
        if (bytes.isEmpty() || bytes.size > MAX_PACK_BYTES) {
            return WordEffectImportResult(emptyList(), listOf("pack size"))
        }
        val pack = runCatching {
            json.decodeFromString<WordEffectPack>(bytes.decodeToString())
        }.getOrNull() ?: return WordEffectImportResult(emptyList(), listOf("invalid json"))
        if (pack.format != PACK_FORMAT) {
            return WordEffectImportResult(emptyList(), listOf("format"))
        }
        if (pack.version != PACK_VERSION) {
            return WordEffectImportResult(emptyList(), listOf("version"))
        }
        if (pack.effects.isEmpty() || pack.effects.size > MAX_EFFECTS) {
            return WordEffectImportResult(emptyList(), listOf("effect count"))
        }
        val source = pack.name.trim().ifBlank { sourceName }.take(MAX_SOURCE_CHARS)
        val imported = ArrayList<WordEffectSpec>(pack.effects.size)
        val rejected = ArrayList<String>()
        for ((index, raw) in pack.effects.withIndex()) {
            val normalized = normalizeWordEffectSpec(raw)
            if (normalized == null) {
                rejected += "#${index + 1}:${raw.id.ifBlank { "?" }}"
            } else {
                imported += normalized
            }
        }
        if (imported.isNotEmpty()) {
            val merged = read(context).effects.toMutableList()
            for (spec in imported) {
                val entry = WordEffectEntry(spec = spec, source = source)
                val existing = merged.indexOfFirst { it.spec.id == spec.id }
                if (existing >= 0) merged[existing] = entry else merged += entry
            }
            // 上限保护：超出时丢最早导入的条目，保证选择器有界。
            while (merged.size > MAX_EFFECTS) merged.removeAt(0)
            if (!write(context, merged)) {
                return WordEffectImportResult(emptyList(), rejected + "write failed")
            }
        }
        return WordEffectImportResult(imported, rejected)
    }

    /** 从选择器清单移除（已选进 profile 的规格不受影响，仍可渲染）。 */
    fun delete(context: Context, id: String): Boolean {
        val existing = read(context).effects
        val remaining = existing.filterNot { it.spec.id == id }
        if (remaining.size == existing.size) return false
        return write(context, remaining)
    }

    private fun read(context: Context): WordEffectStoreFile {
        val file = storeFile(context)
        if (!file.isFile || file.length() > MAX_STORE_BYTES) return WordEffectStoreFile()
        val raw = runCatching { file.readText() }.getOrNull() ?: return WordEffectStoreFile()
        val decoded = runCatching { json.decodeFromString<WordEffectStoreFile>(raw) }
            .getOrDefault(WordEffectStoreFile())
        // 读侧再规范化一次:被手工改过/旧版本写入的规格不能把非法 program 喂给渲染器。
        val sanitized = decoded.effects.mapNotNull { entry ->
            normalizeWordEffectSpec(entry.spec)?.let { WordEffectEntry(it, entry.source) }
        }.take(MAX_EFFECTS)
        return WordEffectStoreFile(effects = sanitized)
    }

    private fun write(context: Context, effects: List<WordEffectEntry>): Boolean {
        val dir = File(context.filesDir, DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val encoded = runCatching {
            json.encodeToString(WordEffectStoreFile(effects = effects))
        }.getOrNull() ?: return false
        if (encoded.toByteArray(Charsets.UTF_8).size > MAX_STORE_BYTES) return false
        val target = File(dir, FILE)
        val temp = File(dir, "$FILE.tmp")
        if (!runCatching { temp.writeText(encoded) }.isSuccess) return false
        // 先尝试原子替换(避免「删旧文件后崩溃」丢清单),不支持的文件系统再退普通替换。
        val moved = runCatching {
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        }.isSuccess || runCatching {
            java.nio.file.Files.move(
                temp.toPath(),
                target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        }.isSuccess
        if (!moved) {
            temp.delete()
            return false
        }
        return true
    }

    private fun storeFile(context: Context): File = File(File(context.filesDir, DIR), FILE)
}
