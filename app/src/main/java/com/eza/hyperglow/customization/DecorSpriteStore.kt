package com.eza.hyperglow.customization

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.eza.hyperglow.root.customization.SpriteWire
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** 用户导入的装饰贴图条目(id 64..255;编号与内置素材同池,渲染时先查自定义)。 */
data class CustomSprite(
    val id: Int,
    val name: String,
    val hash: String,
    val addedAt: Long
)

/**
 * 自定义贴图存储:导入校验 → 重编码 PNG(剥元数据、统一格式) → 存 app 私有目录
 * → 编号 64..255(与内置素材 0..63 分池) + sha256 注册表。
 *
 * 信任边界:贴图字节**不进配置文档**,文档里只出现编号;下发 SystemUI 走
 * `ParcelFileDescriptor`(见 SpriteWire),SystemUI 侧再按哈希/尺寸校验后解码。
 * 因此用户导入的图片既不能是路径/URL,也不能携带任何可执行内容。
 */
object DecorSpriteStore {

    /**
     * 配置发布链每秒调用 [attachments]；条目少时也会重新读偏好、解析 JSON 并逐文件 stat。
     * 注册表只在 import/delete 中变化，进程内缓存到下一次变更即可，发布热路径不再碰磁盘。
     */
    private val attachmentsLock = Any()
    private var cachedAttachmentsContext: Context? = null
    private var cachedAttachments: List<SpriteWire.Attachment>? = null

    const val PREFS = "decor_sprites"
    const val KEY_ENTRIES = "entries"

    /** 与内置素材分池:0..63 内置,64..255 用户导入。 */
    const val MIN_ID = 64
    const val MAX_ID = 255
    // 与 SpriteWire.MAX_SPRITES 同源:传输批上限即注册上限,避免超出的贴图静默不生效。
    const val MAX_ENTRIES = 32

    /** 源文件字节上限(4 MiB);重编码后的 PNG 通常远小于此。 */
    const val MAX_SOURCE_BYTES = 4 * 1024 * 1024
    const val MIN_IMAGE_DIM = 8
    const val MAX_IMAGE_DIM = 1024
    private const val DIR = "deco"

    fun list(context: Context): List<CustomSprite> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null) ?: return emptyList()
        return decodeEntries(raw).filter { file(context, it.id) != null }
    }

    fun file(context: Context, id: Int): File? {
        if (id < MIN_ID || id > MAX_ID) return null
        val file = File(decoDir(context), "custom_$id.png")
        return file.takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * 导入一张图片:解码校验尺寸 → 等比缩到 ≤MAX_IMAGE_DIM → 重编码 PNG → 落盘登记。
     * 失败返回 null;成功返回新条目(编号原样返回给调用方,可直接写进效果)。
     */
    fun import(context: Context, source: ByteArray, displayName: String): CustomSprite? {
        if (source.isEmpty() || source.size > MAX_SOURCE_BYTES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width < MIN_IMAGE_DIM || height < MIN_IMAGE_DIM ||
            width > MAX_IMAGE_DIM * 4 || height > MAX_IMAGE_DIM * 4
        ) {
            return null
        }
        val sample = maxOf(1, maxOf(width, height) / MAX_IMAGE_DIM)
        val decoded = runCatching {
            BitmapFactory.decodeByteArray(
                source, 0, source.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }.getOrNull() ?: return null
        val scaled = if (maxOf(decoded.width, decoded.height) > MAX_IMAGE_DIM) {
            val ratio = MAX_IMAGE_DIM.toFloat() / maxOf(decoded.width, decoded.height)
            val target = Bitmap.createScaledBitmap(
                decoded,
                (decoded.width * ratio).toInt().coerceAtLeast(MIN_IMAGE_DIM),
                (decoded.height * ratio).toInt().coerceAtLeast(MIN_IMAGE_DIM),
                true
            )
            if (target !== decoded) decoded.recycle()
            target
        } else {
            decoded
        }
        val encoded = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
        scaled.recycle()
        if (encoded.isEmpty()) return null

        val existing = list(context).toMutableList()
        if (existing.size >= MAX_ENTRIES) return null
        val id = allocateId(existing.map { it.id }.toSet()) ?: return null
        val dir = decoDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, "custom_$id.png")
        runCatching { target.writeBytes(encoded) }.getOrElse { return null }
        val entry = CustomSprite(
            id = id,
            name = displayName.trim().take(48).ifBlank { "sprite_$id" },
            hash = sha256(encoded),
            addedAt = System.currentTimeMillis()
        )
        existing += entry
        if (!writeEntries(context, existing)) {
            runCatching { target.delete() }
            return null
        }
        invalidateAttachments()
        return entry
    }

    /** 当前全部可下发的贴图附件(编号+哈希+文件);配置发布时由 [SpriteWire] 挂到 Bundle。 */
    internal fun attachments(context: Context): List<SpriteWire.Attachment> {
        val app = context.applicationContext ?: context
        cachedAttachments?.let {
            if (cachedAttachmentsContext === app) return it
        }
        return synchronized(attachmentsLock) {
            val cached = cachedAttachments
            if (cached != null && cachedAttachmentsContext === app) {
                cached
            } else {
                val built = list(context).mapNotNull { entry ->
                    val spriteFile = file(context, entry.id) ?: return@mapNotNull null
                    SpriteWire.Attachment(entry.id, entry.hash, spriteFile)
                }
                cachedAttachments = built
                cachedAttachmentsContext = app
                built
            }
        }
    }

    /** import/delete 成功后调用；下一次配置发布重建附件表。 */
    internal fun invalidateAttachments() {
        synchronized(attachmentsLock) {
            cachedAttachments = null
            cachedAttachmentsContext = null
        }
    }

    fun delete(context: Context, id: Int): Boolean {
        val entries = list(context).toMutableList()
        val removed = entries.removeAll { it.id == id }
        if (!removed) return false
        runCatching { file(context, id)?.delete() }
        val saved = writeEntries(context, entries)
        if (saved) invalidateAttachments()
        return saved
    }

    /** 供预览/侧载使用的解码缓存(按编号;文件变更由 import/delete 触发重建)。 */
    fun bitmap(context: Context, id: Int): Bitmap? {
        val file = file(context, id) ?: return null
        cache[id]?.let { if (it.first == file.lastModified()) return it.second }
        val decoded = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
            ?: return null
        cache[id] = file.lastModified() to decoded
        return decoded
    }

    private val cache = HashMap<Int, Pair<Long, Bitmap>>()

    // ---- 纯逻辑(可单测) ----

    internal fun allocateId(used: Set<Int>): Int? =
        (MIN_ID..MAX_ID).firstOrNull { it !in used }

    internal fun encodeEntries(entries: List<CustomSprite>): String {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("name", entry.name)
                    .put("hash", entry.hash)
                    .put("addedAt", entry.addedAt)
            )
        }
        return array.toString()
    }

    internal fun decodeEntries(raw: String): List<CustomSprite> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optInt("id", -1)
                val hash = item.optString("hash")
                if (id < MIN_ID || id > MAX_ID || hash.length != 64) continue
                add(
                    CustomSprite(
                        id = id,
                        name = item.optString("name").take(48).ifBlank { "sprite_$id" },
                        hash = hash,
                        addedAt = item.optLong("addedAt", 0L)
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun decoDir(context: Context) = File(context.filesDir, DIR)

    private fun writeEntries(context: Context, entries: List<CustomSprite>): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ENTRIES, encodeEntries(entries))
            .commit()
}
