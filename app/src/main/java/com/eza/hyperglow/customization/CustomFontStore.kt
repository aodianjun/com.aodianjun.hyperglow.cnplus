package com.eza.hyperglow.customization

import android.content.Context
import android.net.Uri
import com.eza.hyperglow.BuildConfig
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 自定义字体契约(预览与实机共用):令牌格式、落盘布局与跨进程读取入口。
 *
 * - 令牌:`custom`(历史单槽)或 `custom:<id>`;`id` 仅允许 `[a-z0-9_-]`、长度 1..32。
 *   令牌会经配置包/状态快照跨进程下发并参与文件寻址,因此 id 必须过白名单,
 *   否则 `custom:../../x` 这类值会变成路径穿越。
 * - 布局:`filesDir/custom_fonts/<id>.ttf`;历史单槽为 `custom_fonts/custom.ttf`。
 * - SystemUI 进程读不到应用私有目录(0700),经 [com.eza.hyperglow.bridge.CustomFontProvider]
 *   按 id 取流。
 */
object CustomFontContract {
    const val AUTHORITY_SUFFIX = ".customfont"
    const val PATH_FONT = "font"
    const val METHOD_VERSION = "font_version"
    const val EXTRA_VERSION = "version"

    /** 字体目录(相对 `filesDir`)。 */
    const val FONT_DIR = "custom_fonts"

    /** 历史单槽文件名;其 id 固定为 [FAMILY_CUSTOM]。 */
    const val LEGACY_FONT_FILE = "custom.ttf"

    /** 多字体清单文件名(位于 [FONT_DIR] 内)。 */
    const val INDEX_FILE = "index.json"

    /** 历史单槽令牌:id 即 `custom`,旧配置无需迁移即可继续命中。 */
    const val FAMILY_CUSTOM = "custom"
    const val FAMILY_PREFIX = "custom:"

    const val MAX_FONT_ID_CHARS = 32
    const val MAX_FONT_BYTES = 30 * 1024 * 1024

    private val ID_PATTERN = Regex("[a-z0-9_-]{1,$MAX_FONT_ID_CHARS}")

    /** id 规范化:非法字符/超长一律拒绝(不做静默改写,避免同名字体互相覆盖)。 */
    fun sanitizeFontId(value: String?): String? =
        value?.lowercase()?.takeIf { ID_PATTERN.matches(it) }

    fun isCustomFontFamily(family: String?): Boolean = fontIdOf(family) != null

    /** 令牌 → 字体 id;`custom` 表示历史单槽。非自定义令牌返回 null。 */
    fun fontIdOf(family: String?): String? = when {
        family == null -> null
        family == FAMILY_CUSTOM -> FAMILY_CUSTOM
        family.startsWith(FAMILY_PREFIX) -> sanitizeFontId(family.removePrefix(FAMILY_PREFIX))
        else -> null
    }

    fun customFontFamily(id: String): String =
        if (id == FAMILY_CUSTOM) FAMILY_CUSTOM else "$FAMILY_PREFIX$id"

    fun fontDir(fontRoot: File): File = File(fontRoot, FONT_DIR)

    fun fontFile(fontRoot: File, id: String): File = if (id == FAMILY_CUSTOM) {
        File(fontDir(fontRoot), LEGACY_FONT_FILE)
    } else {
        File(fontDir(fontRoot), "$id.ttf")
    }

    fun indexFile(fontRoot: File): File = File(fontDir(fontRoot), INDEX_FILE)

    /** SystemUI 侧取字体的 content URI;非法令牌回落到历史单槽。 */
    fun customFontUri(family: String?): Uri {
        val id = fontIdOf(family) ?: FAMILY_CUSTOM
        return Uri.parse(
            "content://${BuildConfig.APPLICATION_ID}$AUTHORITY_SUFFIX/$PATH_FONT/$id"
        )
    }
}

@Serializable
internal data class CustomFontEntry(
    val id: String,
    /** 展示名(导入时的文件名去掉扩展名);历史单槽为空,由界面回落通用文案。 */
    val name: String = "",
    val addedAtMs: Long = 0L,
    val sizeBytes: Long = 0L
)

@Serializable
internal data class CustomFontIndex(val fonts: List<CustomFontEntry> = emptyList())

/**
 * 从字体容器(TTF/OTF/TTC)的 `name` 表读取字体真名,让导入字体与内置字体一样按名字展示,
 * 而不是「自定义字体」泛称。纯 JVM 解析便于单测;解析失败返回 null,由调用方回落
 * 导入文件名/通用文案。
 *
 * 记录选择:nameID 1(家族名)优先于 4(全名)——与内置项「Noto Sans」「SF Pro Display」
 * 同为家族名风格;语言偏好 zh-CN → en-US → 其它 Windows → Unicode → Mac。
 */
internal object CustomFontNameReader {
    private const val TAG_NAME = 0x6E616D65L // 'name'
    private const val MAGIC_SFNT = 0x00010000L
    private const val MAGIC_TRUE = 0x74727565L // 'true'
    private const val MAGIC_OTTO = 0x4F54544FL // 'OTTO'
    private const val MAGIC_TTCF = 0x74746366L // 'ttcf'
    private const val NAME_ID_FAMILY = 1
    private const val NAME_ID_FULL = 4

    fun readDisplayName(bytes: ByteArray): String? {
        val base = tableBase(bytes) ?: return null
        val numTables = u16(bytes, base + 4) ?: return null
        var nameStart = -1
        for (i in 0 until numTables) {
            val record = base + 12 + i * 16
            if (u32(bytes, record) == TAG_NAME) {
                nameStart = u32(bytes, record + 8)?.toInt() ?: return null
                break
            }
        }
        if (nameStart < 0) return null
        val count = u16(bytes, nameStart + 2) ?: return null
        val stringOffset = u16(bytes, nameStart + 4) ?: return null
        var best: String? = null
        var bestScore = Int.MAX_VALUE
        for (i in 0 until count) {
            val record = nameStart + 6 + i * 12
            val platform = u16(bytes, record) ?: break
            val language = u16(bytes, record + 4) ?: break
            val nameId = u16(bytes, record + 6) ?: break
            if (nameId != NAME_ID_FAMILY && nameId != NAME_ID_FULL) continue
            val length = u16(bytes, record + 8) ?: break
            val offset = u16(bytes, record + 10) ?: break
            val start = nameStart + stringOffset + offset
            if (start < 0 || start + length > bytes.size) continue
            val text = decodeName(bytes, start, length, platform) ?: continue
            val entryScore = score(platform, language, nameId)
            if (entryScore < bestScore) {
                bestScore = entryScore
                best = text
            }
        }
        return best
    }

    /** TTC 容器取第一个字体的表目录偏移;单字体直接用 0;都不是则返回 null。 */
    private fun tableBase(bytes: ByteArray): Int? = when (u32(bytes, 0)) {
        MAGIC_SFNT, MAGIC_TRUE, MAGIC_OTTO -> 0
        MAGIC_TTCF -> u32(bytes, 12)?.toInt()
        else -> null
    }

    private fun score(platform: Int, language: Int, nameId: Int): Int =
        languageRank(platform, language) * 2 + if (nameId == NAME_ID_FAMILY) 0 else 1

    private fun languageRank(platform: Int, language: Int): Int = when {
        platform == 3 && language == 0x804 -> 0 // Windows 简体中文
        platform == 3 && language == 0x409 -> 1 // Windows en-US
        platform == 3 -> 2
        platform == 0 -> 3 // Unicode 平台
        else -> 4 // Mac 等
    }

    private fun decodeName(bytes: ByteArray, start: Int, length: Int, platform: Int): String? {
        val charset = if (platform == 0 || platform == 3) Charsets.UTF_16BE else Charsets.ISO_8859_1
        val text = runCatching { String(bytes, start, length, charset) }.getOrNull() ?: return null
        return text.filter { it.code >= 0x20 }
            .trim()
            .take(48)
            .takeIf { it.isNotBlank() }
    }

    private fun u16(bytes: ByteArray, at: Int): Int? {
        if (at < 0 || at + 2 > bytes.size) return null
        return ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
    }

    private fun u32(bytes: ByteArray, at: Int): Long? {
        if (at < 0 || at + 4 > bytes.size) return null
        return ((bytes[at].toLong() and 0xFF) shl 24) or
            ((bytes[at + 1].toLong() and 0xFF) shl 16) or
            ((bytes[at + 2].toLong() and 0xFF) shl 8) or
            (bytes[at + 3].toLong() and 0xFF)
    }
}

/**
 * 已导入自定义字体的清单(多字体保留)。
 *
 * 历史行为是固定单槽 `custom_fonts/custom.ttf`:再导入一次就覆盖上一个,用户侧表现为
 * 「自定义字体只能保留一个」。这里改为每字体一文件 + [CustomFontIndex] 清单,导入只新增、
 * 不覆盖;旧单槽在首次读取时自动登记为 id `custom`,既有 `fontFamily = "custom"` 的配置
 * 无需迁移即可继续生效。
 *
 * 仅负责落盘与清单;字体可读性校验(需要 android.graphics)留在界面层。
 *
 * 可见性为 internal:公开成员签名携带 [CustomFontEntry](internal 数据类)。
 */
internal object CustomFontStore {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun list(context: Context): List<CustomFontEntry> = list(context.filesDir)

    fun list(fontRoot: File): List<CustomFontEntry> {
        val indexed = readIndex(fontRoot).fonts
            .filter { CustomFontContract.sanitizeFontId(it.id) != null }
            .filter { CustomFontContract.fontFile(fontRoot, it.id).isFile }
        val legacy = CustomFontContract.fontFile(fontRoot, CustomFontContract.FAMILY_CUSTOM)
        if (!legacy.isFile || indexed.any { it.id == CustomFontContract.FAMILY_CUSTOM }) {
            return indexed
        }
        return listOf(
            CustomFontEntry(
                id = CustomFontContract.FAMILY_CUSTOM,
                name = "",
                addedAtMs = legacy.lastModified(),
                sizeBytes = legacy.length()
            )
        ) + indexed
    }

    /**
     * 落盘一个字体并登记清单。返回登记项;文件不可写或超限时返回 null。
     * 同名不同内容不做去重:重复导入同一个字体只会多一条清单项,由用户自行选择删除。
     */
    fun import(
        fontRoot: File,
        bytes: ByteArray,
        displayName: String?,
        nowMs: Long
    ): CustomFontEntry? {
        if (bytes.size > CustomFontContract.MAX_FONT_BYTES) return null
        if (!hasFontMagic(bytes)) return null
        val existing = list(fontRoot)
        val id = allocateId(fontRoot, existing, displayName)
        val dir = CustomFontContract.fontDir(fontRoot)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = CustomFontContract.fontFile(fontRoot, id)
        val written = runCatching { target.writeBytes(bytes) }.isSuccess
        if (!written) return null
        val entry = CustomFontEntry(
            id = id,
            name = displayNameOf(displayName, id),
            addedAtMs = nowMs,
            sizeBytes = bytes.size.toLong()
        )
        val saved = writeIndex(fontRoot, existing + entry)
        if (!saved) {
            target.delete()
            return null
        }
        return entry
    }

    /** 删除字体本体与清单项。历史单槽同样可删(删除后旧选择回落到内置字体)。 */
    fun delete(fontRoot: File, id: String): Boolean {
        val safeId = CustomFontContract.sanitizeFontId(id) ?: return false
        val file = CustomFontContract.fontFile(fontRoot, safeId)
        val remaining = list(fontRoot).filterNot { it.id == safeId }
        if (!writeIndex(fontRoot, remaining)) return false
        file.delete()
        return true
    }

    /** 字体 id → 展示名;空 id/空名回落到通用文案由界面层处理。 */
    fun displayName(fontRoot: File, family: String?): String? {
        val id = CustomFontContract.fontIdOf(family) ?: return null
        return list(fontRoot).firstOrNull { it.id == id }?.let { label(fontRoot, it) }
    }

    /**
     * 展示名:字体文件 name 表真名优先(与内置字体同风格),导入文件名兜底;
     * 都解析不出时返回 null,由界面回落「自定义字体」通用文案。
     */
    fun label(fontRoot: File, entry: CustomFontEntry): String? =
        runCatching {
            CustomFontNameReader.readDisplayName(
                CustomFontContract.fontFile(fontRoot, entry.id).readBytes()
            )
        }.getOrNull()
            ?: entry.name.takeIf { it.isNotBlank() }

    /** 导入文件是否为可识别的字体容器(TTF/OTF/TTC/旧 Mac trueType)。 */
    fun hasFontMagic(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val ttf = bytes[0] == 0x00.toByte() && bytes[1] == 0x01.toByte() &&
            bytes[2] == 0x00.toByte() && bytes[3] == 0x00.toByte()
        val otf = bytes[0] == 0x4F.toByte() && bytes[1] == 0x54.toByte() &&
            bytes[2] == 0x54.toByte() && bytes[3] == 0x4F.toByte()
        val ttc = bytes[0] == 0x74.toByte() && bytes[1] == 0x74.toByte() &&
            bytes[2] == 0x63.toByte() && bytes[3] == 0x66.toByte()
        val macTrueType = bytes[0] == 0x74.toByte() && bytes[1] == 0x72.toByte() &&
            bytes[2] == 0x75.toByte() && bytes[3] == 0x65.toByte()
        return ttf || otf || ttc || macTrueType
    }

    private fun readIndex(fontRoot: File): CustomFontIndex {
        val file = CustomFontContract.indexFile(fontRoot)
        if (!file.isFile) return CustomFontIndex()
        val raw = runCatching { file.readText() }.getOrNull() ?: return CustomFontIndex()
        return runCatching { json.decodeFromString<CustomFontIndex>(raw) }.getOrDefault(CustomFontIndex())
    }

    private fun writeIndex(fontRoot: File, fonts: List<CustomFontEntry>): Boolean {
        val dir = CustomFontContract.fontDir(fontRoot)
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val encoded = runCatching {
            json.encodeToString(CustomFontIndex(fonts.sortedBy { it.addedAtMs }))
        }.getOrNull() ?: return false
        val file = CustomFontContract.indexFile(fontRoot)
        val temp = File(dir, "${CustomFontContract.INDEX_FILE}.tmp")
        if (!runCatching { temp.writeText(encoded) }.isSuccess) return false
        if (file.exists() && !file.delete()) {
            temp.delete()
            return false
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            return false
        }
        return true
    }

    /** 从展示名派生稳定 id;冲突时追加 `-2`、`-3`… 直到不撞已有文件/清单。 */
    private fun allocateId(
        fontRoot: File,
        existing: List<CustomFontEntry>,
        displayName: String?
    ): String {
        val base = displayName
            ?.substringBeforeLast('.')
            ?.lowercase()
            ?.replace(Regex("[^a-z0-9_-]+"), "-")
            ?.trim('-')
            ?.take(CustomFontContract.MAX_FONT_ID_CHARS)
            ?.takeIf { it.isNotBlank() }
            ?: "font"
        val taken = existing.mapTo(HashSet()) { it.id }
        var candidate = CustomFontContract.sanitizeFontId(base) ?: "font"
        var suffix = 2
        while (candidate in taken || CustomFontContract.fontFile(fontRoot, candidate).isFile) {
            val tail = "-$suffix"
            candidate = (base.take(CustomFontContract.MAX_FONT_ID_CHARS - tail.length) + tail)
                .let { CustomFontContract.sanitizeFontId(it) } ?: "font-$suffix"
            suffix++
        }
        return candidate
    }

    private fun displayNameOf(displayName: String?, id: String): String =
        displayName
            ?.substringBeforeLast('.')
            ?.trim()
            ?.take(48)
            ?.takeIf { it.isNotBlank() }
            ?: id
}
