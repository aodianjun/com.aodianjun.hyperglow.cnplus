package com.eza.hyperglow.producer

import android.content.Context
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.LyricTimeOffset

/**
 * 文档级「歌词时间偏移」的生产者侧装配:时间轴源生产者(Lyricon/LyricInfo/Spicy)各持一份
 * 缓存值,发射边界把原始媒体坐标换算到显示时间轴(见
 * [com.eza.hyperglow.customization.LyricTimeOffset])。装配通道与「识别对唱标记」同款:
 * 设置保存/导入/重置经 [LyricProducers.onCustomizationChanged] 即刻刷新,不必等下次切歌。
 */
internal fun loadLyricTimeOffsetMs(contextRef: Context?): Int = runCatching {
    contextRef?.let { CustomizationRepository.loadCompiled(it).lyricTimeOffsetMs } ?: 0
}.getOrDefault(0)

/**
 * 词级时间平移到显示时间轴(正偏移延后、负偏移提前);offsetMs=0 时原样返回同一实例,
 * 默认状态(未设偏移)的 60Hz 发射路径零分配。
 */
internal fun List<LyricWord>.shiftedByOffset(offsetMs: Int): List<LyricWord> {
    if (offsetMs == 0) return this
    return map {
        it.copy(
            startMs = LyricTimeOffset.displayMs(it.startMs, offsetMs),
            endMs = LyricTimeOffset.displayMs(it.endMs, offsetMs)
        )
    }
}
