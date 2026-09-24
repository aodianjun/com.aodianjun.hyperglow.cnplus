package com.eza.hyperglow.plugin

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 插件类加载策略（issue #65 建议 ①③）：
 *
 * 宿主 release 包经 R8 处理，kotlin.* 等类的运行期可见性被收紧；插件 dex 若自带
 * 同名类（AMLL TTML 插件自带 23 个 kotlin.* 类，含 SetsKt__SetsKt），双亲委派会
 * 优先解析到宿主副本，跨 ClassLoader 访问收紧类直接抛 IllegalAccessError。
 *
 * 因此插件类按「是否必须与宿主保持类型一致」分两类：
 *  - [isSharedWithHost] 为真：平台类与插件 API 契约类，必须双亲优先，否则
 *    宿主无法把插件实现 cast 成 API 接口；
 *  - 其余类（含插件自带的 kotlin./kotlinx. 副本）：插件 dex 中存在的优先用
 *    插件自己的（子优先），不存在再回退双亲。API 边界上无 kotlin 类型穿越
 *    （见 PluginApi.kt 打包契约），子优先不会破坏类型一致。
 */
internal object PluginClassLoaderPolicy {

    private val sharedPrefixes = listOf(
        "java.",
        "javax.",
        "android.",
        "androidx.",
        "dalvik.",
        "libcore.",
        "org.json.",
        "org.w3c.",
        "org.xml.",
        "org.xmlpull.",
        "com.lidesheng.hyperlyric.plugin.api."
    )

    fun isSharedWithHost(className: String): Boolean =
        sharedPrefixes.any { className.startsWith(it) }

    /** 插件 dex 中与宿主打包可能重叠的类统计（加载期诊断，issue #65 建议 ③）。 */
    data class DexOverlap(
        val totalClasses: Int,
        val kotlinClasses: Int,
        val kotlinxClasses: Int,
        val androidxClasses: Int
    )

    /**
     * 最小 DEX 解析：只读 header + class_def/type_id/string_id 三张表，
     * 抽出每个类的 descriptor（Lkotlin/collections/SetsKt__SetsKt; 形式）统计包前缀。
     * 解析失败返回 null，不影响加载主流程。
     */
    fun summarizeDexOverlap(dex: ByteArray): DexOverlap? = runCatching {
        require(dex.size >= DEX_HEADER_SIZE)
        val buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        require(
            dex[0] == 'd'.code.toByte() && dex[1] == 'e'.code.toByte() &&
                dex[2] == 'x'.code.toByte() && dex[3] == '\n'.code.toByte()
        )
        val stringIdsOff = buf.getInt(STRING_IDS_OFF)
        val typeIdsOff = buf.getInt(TYPE_IDS_OFF)
        val classDefsSize = buf.getInt(CLASS_DEFS_SIZE)
        val classDefsOff = buf.getInt(CLASS_DEFS_OFF)

        var kotlin = 0
        var kotlinx = 0
        var androidx = 0
        for (i in 0 until classDefsSize) {
            val classIdx = buf.getInt(classDefsOff + i * CLASS_DEF_ITEM_SIZE)
            val descriptorIdx = buf.getInt(typeIdsOff + classIdx * TYPE_ID_ITEM_SIZE)
            val descriptor = readString(buf, stringIdsOff, descriptorIdx)
            when {
                descriptor.startsWith("Lkotlinx/") -> kotlinx++
                descriptor.startsWith("Lkotlin/") -> kotlin++
                descriptor.startsWith("Landroidx/") -> androidx++
            }
        }
        DexOverlap(classDefsSize, kotlin, kotlinx, androidx)
    }.getOrNull()

    private fun readString(buf: ByteBuffer, stringIdsOff: Int, index: Int): String {
        var offset = buf.getInt(stringIdsOff + index * STRING_ID_ITEM_SIZE)
        while ((buf.get(offset).toInt() and ULEB128_CONTINUATION) != 0) offset++
        offset++
        val start = offset
        while (buf.get(offset) != 0.toByte()) offset++
        return String(buf.array(), start, offset - start, Charsets.US_ASCII)
    }

    private const val DEX_HEADER_SIZE = 0x70
    private const val STRING_IDS_OFF = 0x3C
    private const val TYPE_IDS_OFF = 0x4C
    private const val CLASS_DEFS_SIZE = 0x60
    private const val CLASS_DEFS_OFF = 0x68
    private const val STRING_ID_ITEM_SIZE = 4
    private const val TYPE_ID_ITEM_SIZE = 4
    private const val CLASS_DEF_ITEM_SIZE = 32
    private const val ULEB128_CONTINUATION = 0x80
}
