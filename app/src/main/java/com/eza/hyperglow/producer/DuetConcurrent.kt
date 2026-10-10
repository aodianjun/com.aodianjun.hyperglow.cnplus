package com.eza.hyperglow.producer

/**
 * 对唱并发行(第二行)选取——移植上游 amarinne/hyperglow 99ba119d4
 * `SpicyBridgeDocumentStore.concurrentRowsAt` 的判定语义,抽成与具体歌词源无关的纯函数,
 * 由持有整首行表的生产者(Spicy/Lyricon/LyricInfo)在发射前预计算并发行候选;
 * 投影层只做策略开关与格式转换,不从原始行表选行(契约:projection MUST NOT re-select)。
 *
 * 判定以纯时间轴重叠为底:另一唱词行与主行的播放窗口重叠且共享窗口达到
 * [MIN_CONCURRENT_OVERLAP_MS],该行即「并发行」。行角色只作一个例外输入(上游 fefe5c54):
 * **显式伴唱行**(Spicy 文档 role=BACKGROUND、插件行 role=BG)只要有正重叠即加入——作者
 * 编排的短应答(实测上游 Right Now (Na Na Na) 三处 0.97s)不再被一秒门整段吞掉;非伴唱行
 * 之间(lead 行偶然重叠)仍守一秒门。与「对唱分侧」([resolveDuetAlignment],行级 alignedRight)
 * 和「识别对唱标记」(duetMarkers)正交——角色只决定「加入门槛」,不参与身份推导。
 */

/** 屏幕上同时保留的唱词行数:主行 + 一行并发行(上游 MAX_CONCURRENT_LYRIC_LINES=2)。 */
internal const val MAX_CONCURRENT_DUET_LINES = 2

/**
 * 并发行与主行的最小共享窗口(毫秒)。重叠短于该值的行不加入,避免将死的行尾在一瞬间
 * 闪现出双行段(上游同值)。**只约束非伴唱行**:显式伴唱行([DuetLineWindow.isBackground])
 * 按作者编排的共享窗口加入,不受此门限制(上游 fefe5c54 同语义)。
 */
internal const val MIN_CONCURRENT_OVERLAP_MS = 1_000L

/**
 * 行表的 Minimal 时间窗投影:各歌词源把自己的行模型映射成它交给 [selectDuetLineIndex]。
 * [isInterlude] 为间奏行(不参与);[isBackground] 为**显式伴唱行**(Spicy 文档
 * role=BACKGROUND、插件行 role=BG 的 x-bg 回声)——只有携带行角色的源能置位,无角色的源
 * (Lyricon/LyricInfo)保持默认 false,全部行按非伴唱行守一秒门。
 */
data class DuetLineWindow(
    val startMs: Long,
    val endMs: Long,
    val isInterlude: Boolean,
    val isBackground: Boolean = false
)

private fun duetOverlap(first: DuetLineWindow, second: DuetLineWindow): Long =
    minOf(first.endMs, second.endMs) - maxOf(first.startMs, second.startMs)

/**
 * 加入并发场景判据(上游 fefe5c54 的 `joinsConcurrentScene` 同语义):共享窗口为正,且
 * 任一侧是显式伴唱行、或共享窗口达到 [MIN_CONCURRENT_OVERLAP_MS]。伴唱行是作者显式编排的
 * 应答/回声,一秒门会把它们整段吞掉(短应答实测 0.97s);非伴唱行之间的偶然重叠仍守一秒门,
 * 将死的行尾不闪现双行段。两侧对称判定——主行是伴唱行、候选是 lead 行时同样放宽。
 */
private fun joinsConcurrentScene(primary: DuetLineWindow, candidate: DuetLineWindow): Boolean {
    val overlap = duetOverlap(primary, candidate)
    return overlap > 0L && (primary.isBackground || candidate.isBackground ||
        overlap >= MIN_CONCURRENT_OVERLAP_MS)
}

/**
 * 选取 [primaryIndex] 主行在 [positionMs] 处的并发行下标;无候选返回 -1。三段式(上游同序):
 * 1. 覆盖行:非主行、非间奏、position ∈ [start,end) 且加入判据成立([joinsConcurrentScene],
 *    显式伴唱行正重叠即加入、其余 ≥ [MIN_CONCURRENT_OVERLAP_MS]),
 *    取开始时间最晚的一行(并发行至多 [MAX_CONCURRENT_DUET_LINES]-1 即 1 行);
 * 2. 退出缓冲:已唱完(end ≤ position)但加入判据成立的行,取结束最晚的——对唱中途
 *    已结束的一行不消失,与主行一起淡出,而不是双行段中途塌成单行;
 * 3. 预加入:主行独唱时,取主行窗口内最早开始的未来加入行作为候选(显示侧在窗口开始前
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
                joinsConcurrentScene(primary, window)
        }
        .maxByOrNull { it.value.startMs }
    if (covering != null) return covering.index
    val ended = windows.asSequence()
        .withIndex()
        .filter { (index, window) ->
            index != primaryIndex && !window.isInterlude &&
                window.endMs <= positionMs &&
                joinsConcurrentScene(primary, window)
        }
        .maxByOrNull { it.value.endMs }
    if (ended != null) return ended.index
    val preJoin = windows.asSequence()
        .withIndex()
        .filter { (index, window) ->
            index != primaryIndex && !window.isInterlude &&
                window.startMs > positionMs && window.startMs < primary.endMs &&
                joinsConcurrentScene(primary, window)
        }
        .minByOrNull { it.value.startMs }
    return preJoin?.index ?: -1
}

/**
 * 并发行候选的**独立时间轴**判定(owner 2026-10-07):屏上已锁定的并发行([locked],null =
 * 尚未上屏)是否让位给本次到达的候选([incoming],null = 本次无候选)。
 *
 * 候选是按当前主行选的,主行一换候选就换——不锁的话屏上并发行会随主行换行被替换/卷走
 * (「第二行还没唱完就换到第一行」)。本判定把已上屏的并发行锁到它自己的窗口结束:
 * 1. 未锁定:候选直接采用(有则上屏、无则保持无);
 * 2. 已锁定且位置仍在它自己的窗口内([positionMs] < locked.endMs):保持不动——主行换行
 *    只换候选来源,不换屏上内容;仅当出现真正重叠的新候选(此刻已开唱、与锁定行共享窗口
 *    ≥ [MIN_CONCURRENT_OVERLAP_MS])时才提前切换。**加入门槛的伴唱放宽不改变本门槛**:
 *    显式伴唱行([DuetLineWindow.isBackground],上游 fefe5c54)在选行处正重叠即加入,但
 *    已上屏的行仍只在真正重叠 ≥1s 时让位——短应答不打断正在唱的行(owner 2026-10-07
 *    「主行换行时和声还没唱完就不换」;屏上没有锁定行时,短应答照常按加入判据上屏);
 * 3. 已锁定且它自己的窗口已结束:采用本次候选,交还按当前主行的常规重选(并发行自己到点
 *    换行,由 [selectDuetLineIndex] 重新选出的候选接管)。
 */
internal fun shouldAdoptDuetLineCandidate(
    locked: DuetLineWindow?,
    incoming: DuetLineWindow?,
    positionMs: Long
): Boolean {
    if (locked == null) return true
    if (incoming != null && sameDuetWindow(locked, incoming)) return false
    if (positionMs >= locked.endMs) return true
    return incoming != null &&
        positionMs >= incoming.startMs && positionMs < incoming.endMs &&
        duetOverlap(locked, incoming) >= MIN_CONCURRENT_OVERLAP_MS
}

/** 行窗身份判据:起止相同即同一行窗(并发行锁据此判「候选没换」,不切换也就无过渡)。 */
private fun sameDuetWindow(first: DuetLineWindow, second: DuetLineWindow): Boolean =
    first.startMs == second.startMs && first.endMs == second.endMs
