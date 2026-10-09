package com.eza.hyperglow.root.aod

import com.eza.hyperglow.root.projection.LyricWord

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
 * 并发行自己换行的时间线(纯函数):**退场 → 入场两段,无晋级位移段** —— 并发行槽位冻结
 * (见 [frozenDuetBaselines]),它不移动,所以不插位移段;两段的配方/时长/速率档与主行
 * 共用同一份 [lineTransitionTimeline](退场半段 [exitTransitionMs] + 入场半段
 * [enterTransitionMs],各档再乘速率倍率 [lineTransitionDurationScale]),不另立一套动画系统。
 *
 * [mode] 取画布已解析的档位(`content.transitionMode`):与主行**同一个字段**——"Auto" 在
 * 映射层已按歌词源偏好解析(见 LyricCanvasMapper 的 resolveLineTransition),两行由此必然
 * 用同一档位;不在这里二次解析(两处判据就是「预览/实机档位分叉」那类故障的来源)。
 */
internal fun duetRowTransitionTimeline(mode: String, speed: String): LineTransitionTimeline =
    lineTransitionTimeline(mode, speed, promoting = false)

/**
 * 并发行内容键变化时走哪条过渡(纯函数):上一版并发行还在(旧键非空、与当前键不同)、
 * 档位非 None、且旧布局确有并发行行([previousRowAvailable])时,播预设的退场→入场
 * (时间线见 [duetRowTransitionTimeline]);否则回落既有 180 ms 加入淡入(见
 * AodLyricCanvasView.duetJoinAlpha)——首次出现没有旧内容可退场、None 档不播动画,
 * 行为与今天逐帧一致。
 *
 * [previousKey]/[nextKey] 为 [aodDuetContentKey] 的产出。当前键为 null(并发行消失)不播
 * 过渡:屏上没有新内容,只剩退场层会停在半路。
 */
internal fun shouldStartDuetRowTransition(
    previousKey: String?,
    nextKey: String?,
    transitionMode: String,
    previousRowAvailable: Boolean
): Boolean = previousKey != null && nextKey != null && previousKey != nextKey &&
    transitionMode != "None" && previousRowAvailable

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
 * [timed] 是**事实**不是决策:并发行是否带真实词窗,由调用方按本侧词表类型用同一判据取值
 * ——实机画布侧 [hasTimedWordWindows]\(`AodCanvasWord`),App 内预览侧
 * [projectedWordsHaveTimedWindows]\(`root.projection.LyricWord`)。两套判据必须同值(见单测)。
 */
internal fun planDuetRow(
    animationMode: String,
    timed: Boolean,
    lineLevelSync: Boolean,
    lineSyncFillMode: String,
    lineStartMs: Long,
    lineEndMs: Long
): OriginalLinePlan = planOriginalLine(
    animationMode = animationMode,
    timed = timed,
    lineLevelSync = lineLevelSync,
    lineSyncFillMode = lineSyncFillMode,
    lineStartMs = lineStartMs,
    lineEndMs = lineEndMs
)

/**
 * 真实词窗判据的投影侧版本(`root.projection.LyricWord`,App 内预览用):与画布侧
 * [hasTimedWordWindows](`AodCanvasWord`)同义——文本非空且 `endMs > startMs`。
 * 两套词表类型(`AodCanvasWord` / `root.projection.LyricWord`)形状相同、判据也必须同值:
 * 判据分叉就是「预览有逐字、实机没有」那类故障(本次修复前恰好相反:预览对、实机错)。
 */
internal fun projectedWordsHaveTimedWindows(words: List<LyricWord>): Boolean =
    words.any { it.text.isNotBlank() && it.endMs > it.startMs }

/**
 * 和声行(辅助行车道的 x-bg 回声)的逐字段 —— 纯函数,实机
 * `AodLyricCanvasView.harmonyTimedLines` 与 App 内预览 `PreviewComponents` 共用(预览即实机)。
 *
 * 和声行此前只按自己的行窗**均匀合成**逐字窗口(整行平摊到每个字),而插件逐音节下发的
 * 真实词窗就在 [AodCanvasWord] 词表里(AMLL TTML 规范 6.2:逐字歌词每个音节自带
 * begin/end)——实测《乐鸣东方》L33 的 x-bg 和声「(人间百相 总让我神往)」末字「往)」
 * 独占 1.98s(lmdf.ttml 02:53.757–02:55.737),均匀合成把整行 5.2s 平摊给 9 个音节,
 * 整行抢在音频前面点亮、又提前唱完。本函数把真实词窗转成辅助行车道消费的
 * [SecondaryTimedSegment],由 `drawAuxKaraokeRow` 的逐字段路径按真实词窗点亮。
 *
 * 取舍与翻译行 [translatedTimedSegments] 同源(委托同一实现,不另写一份判据):片段文本
 * 直接相连必须重建出整行文本 [expectedText],重建不一致/空词表/全零窗一律返回 null,
 * 调用方回落行窗均匀合成——和声的显示文本恒取行文本,若按片段画就会在逐字开关开/关之间
 * 显示两份不同文本,宁可不要逐字效果。
 */
internal fun harmonyTimedSegments(
    words: List<AodCanvasWord>,
    expectedText: String,
    measureText: (String) -> Float
): List<SecondaryTimedSegment>? = translatedTimedSegments(words, expectedText, measureText)
