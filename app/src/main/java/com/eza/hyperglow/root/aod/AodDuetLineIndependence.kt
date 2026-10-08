package com.eza.hyperglow.root.aod

/**
 * 并发行相对主行**独立**的画布侧纯函数(owner 2026-10-07):并发行有自己独立的内容键与
 * 槽位——主行换行只换主行块,不动并发行;并发行自己到点才换,且换行时只播自己的过渡。
 * 与 [AodLyricCanvasView] 的绘制接线同源,单测钉住「主行换行不改变并发行内容与槽位」。
 */

/**
 * 并发行自己的内容键(文本 + 行窗起点):输入只有并发行自己的行内容,主行换行(活动行
 * 变化)不改变它——画布据此判「并发行自己换行」并播它自己的加入淡入(见
 * [AodLyricCanvasView] 的 duetJoinAlpha),与主行的三段式换行过渡互不牵连。
 * 空文本 = 无并发行(键为 null)。
 */
internal fun aodDuetContentKey(text: String?, lineStartMs: Long): String? =
    text?.takeIf { it.isNotBlank() }?.let { "$it@$lineStartMs" }

/**
 * 主行过渡期间并发行行的静止槽位:槽位一律取过渡起点快照([snapshotBaselines]),新布局
 * ([currentBaselines])只在起点没有对应行(并发行自己刚换行、多出辅助行)时兜底——
 * 主行换行后的新布局不参与,「主行换行不改变并发行槽位」由此结构性成立。
 */
internal fun frozenDuetBaselines(
    snapshotBaselines: List<Float>,
    currentBaselines: List<Float>
): List<Float> = currentBaselines.mapIndexed { index, fallback ->
    snapshotBaselines.getOrNull(index) ?: fallback
}
