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

/**
 * 并发行(主行同款并排那一行)自己的渲染路径决策 —— 与主行**同一决策函数**
 * ([planOriginalLine]),并发行带真实词窗时走词级卡拉OK(真实词时间戳),行级源才落共享
 * 扫光块。
 *
 * 此前并发行恒走共享扫光块:整块进度按行窗线性铺满(`unifiedBlockProgress` 行级时间优先,
 * 有行窗就不看词表),而插件行窗可能是一个**拖长音**——实测 v1《乐鸣东方》147.444–154.300
 * 里末字「方」独占 5.65s,于是并发行在音频早已唱到别的句子之后还在慢慢铺光,与主行/音频
 * 都不同步(owner 2026-10-08:「并发时间戳没对上,明显第二句走的不是真实时间戳」)。
 *
 * [words] 传并发行自己的词表(不是主行的):判据与主行同式([hasTimedWordWindows])。
 */
internal fun planDuetRow(
    animationMode: String,
    lineLevelSync: Boolean,
    lineSyncFillMode: String,
    lineStartMs: Long,
    lineEndMs: Long,
    words: List<AodCanvasWord>
): OriginalLinePlan = planOriginalLine(
    animationMode = animationMode,
    timed = hasTimedWordWindows(words),
    lineLevelSync = lineLevelSync,
    lineSyncFillMode = lineSyncFillMode,
    lineStartMs = lineStartMs,
    lineEndMs = lineEndMs
)
