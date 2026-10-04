package com.eza.hyperglow.ui

/**
 * MIUI 长截屏假触摸的位移累计(纯逻辑,无 Android 依赖,便于 JVM 单测)。
 *
 * MIUI 的假触摸是「DOWN 于屏幕下方 → 每次 MOVE 上移约半屏」的连续拖拽(见
 * `miui.util.LongScreenshotUtils$ContentPort.dispatchFakeTouchEvent`),
 * 向上拖动(dy < 0)对应内容向下滚动,故累计 `lastY - y` 为正。
 *
 * 累计值充当代理 View 的 `scrollY`:MIUI 以它的增量判断「是否还有可滚内容」,
 * 增量为 0 即判定到底并在一帧后结束采集(真机日志 `scrolledY == 0 isEnd:true` 实证)。
 *
 * 安全上限([DEFAULT_MAX_SCROLL_PX]):代理拿不到「内容已到底」的真信号,若不封顶,
 * 短页面或已滚到底之后 MIUI 仍会持续注入假触摸、累计值永远递增(增量永不为 0),
 * 可能演变成超长拼接或无法结束的采集。到顶后累计值冻结,给 MIUI 一个明确的「到底」信号;
 * 下一次长截屏(与上次事件间隔超过 [SESSION_GAP_MS] 的新手势)重新计数,避免第二次采集
 * 一开始就报 0 增量。
 */
internal class LongScreenshotDragAccumulator(
    private val maxScrollPx: Int = DEFAULT_MAX_SCROLL_PX
) {
    private var lastY = Float.NaN
    private var lastEventMs = 0L
    private var total = 0

    /** 当前累计滚动位移(MIUI 读取的 scrollY)。 */
    val scrollY: Int get() = total

    /** 手势按下:记录起点;上一轮采集已触顶且间隔够久(用户重新发起长截屏)时重新计数。 */
    fun onDown(y: Float, nowMs: Long) {
        if (total >= maxScrollPx && nowMs - lastEventMs >= SESSION_GAP_MS) total = 0
        lastY = y
        lastEventMs = nowMs
    }

    /** 手势移动:累计位移(封顶);没有起点(未收到 DOWN)时忽略,避免把孤立事件当拖拽。 */
    fun onMove(y: Float, nowMs: Long) {
        if (lastY.isNaN()) return
        total = minOf(total + (lastY - y).toInt(), maxScrollPx)
        lastY = y
        lastEventMs = nowMs
    }

    /** 手势结束(UP/CANCEL):重置起点;累计值保留(跨步进采集单调递增)。 */
    fun onEnd(nowMs: Long) {
        lastY = Float.NaN
        lastEventMs = nowMs
    }

    companion object {
        /** 约 25 屏(2400px 屏)内容:正常长截屏远达不到,仅作失控兜底。 */
        const val DEFAULT_MAX_SCROLL_PX = 60_000

        /** 两次长截屏之间的最小间隔;小于它视为同一次采集的连续步进,不重置计数。 */
        private const val SESSION_GAP_MS = 1_500L
    }
}
