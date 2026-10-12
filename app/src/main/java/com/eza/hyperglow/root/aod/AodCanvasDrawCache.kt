package com.eza.hyperglow.root.aod

import android.graphics.Typeface

/**
 * 有界小容量键值缓存(绘制热路径复用):线性扫描,命中返回缓存实例,未命中现算并入表;
 * 容量到达上限即整表清空(保持有界,不做 LRU 记账)。仅绘制线程(主线程)访问,
 * 不做跨线程同步 —— 预览与实机画布都在主线程绘制。
 */
internal class BoundedDrawCache<K : Any, V : Any>(private val capacity: Int) {
    private val keys = ArrayList<K>(capacity)
    private val values = ArrayList<V>(capacity)

    fun getOrCompute(key: K, compute: () -> V): V {
        val index = keys.indexOf(key)
        if (index >= 0) return values[index]
        val value = compute()
        if (keys.size >= capacity) clear()
        keys.add(key)
        values.add(value)
        return value
    }

    fun clear() {
        keys.clear()
        values.clear()
    }

    val size: Int
        get() = keys.size
}

/**
 * 行级源逐字合成几何的缓存键:覆盖合成分支的全部输入 ——
 * 文本、Paint 测量态(字号/字型;全仓无 letterSpacing/textScaleX/skewX/fontFeatureSettings
 * 等其它测量属性)、行块窗口、行块总宽、行前累计宽、行起始 x。
 * 任一输入变化即 miss 重算几何,不做近似、不改测量口径。
 */
internal data class SyntheticKaraokeKey(
    val text: String,
    val textSize: Float,
    val typeface: Typeface?,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val totalWidth: Float,
    val precedingWidth: Float,
    val lineStartX: Float
)

/**
 * 行级源逐字合成的单个几何单元(与播放位置无关的部分):字符切片、行内绝对 x、测量宽、
 * 长音节标记与时间窗。逐帧只把 [windowStartMs]..[windowEndMs](及长音节的块级高亮窗
 * [highlightStartMs]..[highlightEndMs])经 timedWordProgress(播放位置)换算成进度,
 * 字符切片/测宽/窗口本身跨帧复用。
 */
internal class SyntheticKaraokeUnit(
    val text: String,
    val x: Float,
    val width: Float,
    val longSyllable: Boolean,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val highlightStartMs: Long,
    val highlightEndMs: Long
)
