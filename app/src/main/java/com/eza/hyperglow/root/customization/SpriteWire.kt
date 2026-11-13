package com.eza.hyperglow.root.customization

import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * 自定义贴图的跨进程通道:配置 Bundle 里只放**编号 + sha256 + fd 列表**,不放路径、URL
 * 或图片字节本体。App 侧把 app 私有目录的 PNG 以只读 [ParcelFileDescriptor] 挂上,
 * SystemUI 侧在回调内读满、校验哈希、关闭 fd,再解码进内存注册表。
 *
 * 约定:
 * - 编号与内置素材分池:0..63 内置(assets/deco),64..255 用户导入;
 * - 单张上限 [MAX_BYTES_PER_SPRITE],整批上限 [MAX_TOTAL_BYTES],张数上限 [MAX_SPRITES];
 * - 任何一张读失败/哈希不符只丢弃该张,不拖垮整批配置;
 * - fd 生命周期:`attach` 返回的列表由调用方在广播结束后统一 close——Binder 在事务
 *   发送时已把 fd dup 进接收进程,发送侧关闭不影响已入队的接收侧副本。
 */
internal object SpriteWire {

    const val KEY_IDS = "decorSpriteIds"
    const val KEY_HASHES = "decorSpriteHashes"
    const val KEY_FDS = "decorSpriteFds"

    const val MIN_SPRITE_ID = 64
    const val MAX_SPRITE_ID = 255
    const val MAX_SPRITES = 32
    const val MAX_BYTES_PER_SPRITE = 4 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
    private const val HASH_LENGTH = 64

    /** App 侧待发送项:编号 + 内容哈希 + 文件(编号池与 [com.eza.hyperglow.customization.DecorSpriteStore] 对齐)。 */
    data class Attachment(val id: Int, val hash: String, val file: File) {
        val isValid: Boolean
            get() = id in MIN_SPRITE_ID..MAX_SPRITE_ID &&
                hash.length == HASH_LENGTH &&
                file.isFile && file.length() > 0
    }

    /** SystemUI 侧读取结果:编号 + 已校验的原始字节。 */
    data class Received(val id: Int, val hash: String, val bytes: ByteArray)

    fun signature(attachments: List<Attachment>): String =
        attachments.filter { it.isValid }
            .joinToString(",") { "${it.id}:${it.hash}" }

    /**
     * 把附件挂到 [bundle]:打开若干只读 fd 写进 fd 列表。返回已打开的 fd,调用方在
     * 发送完成后负责 close(接收进程拿的是内核 dup 后的副本)。
     */
    fun attach(
        bundle: Bundle,
        attachments: List<Attachment>,
        onReject: ((String) -> Unit)? = null
    ): List<ParcelFileDescriptor> {
        val usable = attachments.filter { it.isValid }.take(MAX_SPRITES)
        if (usable.isEmpty()) return emptyList()
        val ids = ArrayList<Int>(usable.size)
        val hashes = ArrayList<String>(usable.size)
        val fds = ArrayList<ParcelFileDescriptor>(usable.size)
        for (attachment in usable) {
            val descriptor = runCatching {
                ParcelFileDescriptor.open(attachment.file, ParcelFileDescriptor.MODE_READ_ONLY)
            }.getOrElse {
                onReject?.invoke("sprite_open_failed(${attachment.id}:${it.javaClass.simpleName})")
                continue
            }
            ids += attachment.id
            hashes += attachment.hash
            fds += descriptor
        }
        if (fds.isEmpty()) return emptyList()
        bundle.putIntArray(KEY_IDS, ids.toIntArray())
        bundle.putStringArray(KEY_HASHES, hashes.toTypedArray())
        bundle.putParcelableArrayList(KEY_FDS, fds)
        return fds
    }

    /**
     * 在回调内读取整批贴图(**必须在 Bundle 仍有效时调用**):逐张限长读取、sha256 校验、
     * 关闭接收侧 fd。返回通过校验的条目;任何异常都收敛为空列表而不是抛出。
     */
    fun read(bundle: Bundle, onReject: ((String) -> Unit)? = null): List<Received> {
        val ids = bundle.getIntArray(KEY_IDS) ?: return emptyList()
        val hashes = bundle.getStringArray(KEY_HASHES) ?: return emptyList()
        val descriptors = bundle.getParcelableArrayList<ParcelFileDescriptor>(KEY_FDS)
            ?: return emptyList()
        if (ids.isEmpty() || ids.size != hashes.size) return emptyList()
        val received = ArrayList<Received>(minOf(ids.size, descriptors.size))
        var total = 0L
        for (index in 0 until minOf(ids.size, hashes.size, descriptors.size)) {
            val descriptor = descriptors[index] ?: continue
            val id = ids[index]
            val hash = hashes[index]
            if (id !in MIN_SPRITE_ID..MAX_SPRITE_ID || hash.length != HASH_LENGTH) {
                closeQuietly(descriptor)
                continue
            }
            val bytes = try {
                readAll(descriptor, MAX_BYTES_PER_SPRITE)
            } catch (error: Exception) {
                onReject?.invoke("sprite_read_failed($id:${error.javaClass.simpleName})")
                null
            } finally {
                closeQuietly(descriptor)
            } ?: continue
            if (bytes.isEmpty()) continue
            if (total + bytes.size > MAX_TOTAL_BYTES) {
                onReject?.invoke("sprite_budget_exhausted(total=$total)")
                break
            }
            total += bytes.size
            if (!sha256(bytes).equals(hash, ignoreCase = true)) {
                onReject?.invoke("sprite_hash_mismatch($id)")
                continue
            }
            received += Received(id, hash, bytes)
        }
        return received
    }

    private fun readAll(descriptor: ParcelFileDescriptor, limit: Int): ByteArray {
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) throw IllegalStateException("sprite_too_large($total)")
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }

    private fun closeQuietly(descriptor: ParcelFileDescriptor) {
        runCatching { descriptor.close() }
    }

    internal fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
