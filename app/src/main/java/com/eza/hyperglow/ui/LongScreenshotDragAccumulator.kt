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
 */
internal class LongScreenshotDragAccumulator {
    private var lastY = Float.NaN
    private var total = 0

    /** 当前累计滚动位移(MIUI 读取的 scrollY)。 */
    val scrollY: Int get() = total

    /** 手势按下:记录起点。 */
    fun onDown(y: Float) {
        lastY = y
    }

    /** 手势移动:累计位移。没有起点(未收到 DOWN)时忽略,避免把孤立事件当拖拽。 */
    fun onMove(y: Float) {
        if (lastY.isNaN()) return
        total += (lastY - y).toInt()
        lastY = y
    }

    /** 手势结束(UP/CANCEL):重置起点;累计值保留(跨步进采集单调递增)。 */
    fun onEnd() {
        lastY = Float.NaN
    }
}
