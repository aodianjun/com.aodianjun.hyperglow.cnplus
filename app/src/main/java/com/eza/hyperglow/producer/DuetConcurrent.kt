package com.eza.hyperglow.producer

/**
 * 对唱并发行(第二行)选取——移植上游 amarinne/hyperglow 99ba119d4
 * `SpicyBridgeDocumentStore.concurrentRowsAt` 的判定语义,抽成与具体歌词源无关的纯函数,
 * 由持有整首行表的生产者(Spicy/Lyricon/LyricInfo)在发射前预计算并发行候选;
 * 投影层只做策略开关与格式转换,不从原始行表选行(契约:projection MUST NOT re-select)。
 *
 * 判定是纯时间轴重叠:另一唱词行与主行的播放窗口重叠且共享窗口达到
 * [MIN_CONCURRENT_OVERLAP_MS],该行即「并发行」。不依赖任何歌手/声部标记,与
 * 「对唱分侧」([resolveDuetAlignment],行级 alignedRight)和「识别对唱标记」(duetMarkers)正交。
 */

/** 屏幕上同时保留的唱词行数:主行 + 一行并发行(上游 MAX_CONCURRENT_LYRIC_LINES=2)。 */
internal const val MAX_CONCURRENT_DUET_LINES = 2

/**
 * 并发行与主行的最小共享窗口(毫秒)。重叠短于该值的行不加入,避免将死的行尾在一瞬间
 * 闪现出双行段(上游同值)。
 */
internal const val MIN_CONCURRENT_OVERLAP_MS = 1_000L

/** 行表的 Minimal 时间窗投影:各歌词源把自己的行模型映射成它交给 [selectDuetLineIndex]。 */
data class DuetLineWindow(val startMs: Long, val endMs: Long, val isInterlude: Boolean)

private fun duetOverlap(first: DuetLineWindow, second: DuetLineWindow): Long =
    minOf(first.endMs, second.endMs) - maxOf(first.startMs, second.startMs)

/**
 * 选取 [primaryIndex] 主行在 [positionMs] 处的并发行下标;无候选返回 -1。三段式(上游同序):
 * 1. 覆盖行:非主行、非间奏、position ∈ [start,end) 且与主行重叠 ≥ [MIN_CONCURRENT_OVERLAP_MS],
 *    取开始时间最晚的一行(并发行至多 [MAX_CONCURRENT_DUET_LINES]-1 即 1 行);
 * 2. 退出缓冲:已唱完(end ≤ position)但与主行重叠达标的行,取结束最晚的——对唱中途
 *    已结束的一行不消失,与主行一起淡出,而不是双行段中途塌成单行;
 * 3. 预加入:主行独唱时,取主行窗口内最早开始的未来重叠行作为候选(显示侧在窗口开始前
 *    不绘制它),使加入瞬间的布局不移动不缩放。
 */
fun selectDuetLineIndex(
    windows: List<DuetLineWindow>,
    primaryIndex: Int,
    positionMs: Long
): Int {
    if (primaryIndex !in windows.indices) return -1
    val primary = windows[primaryIndex]
    if (primary.isInterlude) return -1
    val covering = windows.asSequence()
        .withIndex()
        .filter { (index, window) ->
            index != primaryIndex && !window.isInterlude &&
                positionMs >= window.startMs && positionMs < window.endMs &&
                duetOverlap(primary, window) >= MIN_CONCURRENT_OVERLAP_MS
        }
        .maxByOrNull { it.value.startMs }
    if (covering != null) return covering.index
    val ended = windows.asSequence()
        .withIndex()
        .filter { (index, window) ->
            index != primaryIndex && !window.isInterlude &&
                window.endMs <= positionMs &&
                duetOverlap(primary, window) >= MIN_CONCURRENT_OVERLAP_MS
        }
        .maxByOrNull { it.value.endMs }
    if (ended != null) return ended.index
    val preJoin = windows.asSequence()
        .withIndex()
        .filter { (index, window) ->
            index != primaryIndex && !window.isInterlude &&
                window.startMs > positionMs && window.startMs < primary.endMs &&
                duetOverlap(primary, window) >= MIN_CONCURRENT_OVERLAP_MS
        }
        .minByOrNull { it.value.startMs }
    return preJoin?.index ?: -1
}
