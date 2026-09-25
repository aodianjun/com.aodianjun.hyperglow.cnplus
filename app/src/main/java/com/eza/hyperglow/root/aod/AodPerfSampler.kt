package com.eza.hyperglow.root.aod

/**
 * 低开销热路径计时聚合(Bridge BridgePerformanceSampler 同型,issue #68 #13)。
 *
 * [enabled] 关闭时 [begin] 只做一次布尔判断并返回 [NO_SAMPLE],[end] 首参即返回——
 * 零集合分配、零逐帧日志;启用时 count/avg/max 进固定数组,每报告窗口经 [sink]
 * 输出一条汇总(如 `draw={count=300,avgUs=2100,maxUs=9400}`),回答"掉帧的花销在哪"。
 * record 走 synchronized:绘制线程是主线程单线程,但测试会并发压。
 */
internal class AodPerfSampler(
    private val enabled: () -> Boolean,
    private val clockNanos: () -> Long = System::nanoTime,
    private val sink: (String) -> Unit,
    private val reportIntervalNanos: Long = DEFAULT_REPORT_INTERVAL_NANOS
) {
    enum class Metric(val key: String) { DRAW("draw") }

    private val counts = LongArray(Metric.entries.size)
    private val totalNanos = LongArray(Metric.entries.size)
    private val maxNanos = LongArray(Metric.entries.size)
    private var windowStartedAtNanos = Long.MIN_VALUE
    private val lock = Any()

    fun begin(): Long = if (enabled()) clockNanos() else NO_SAMPLE

    fun end(metric: Metric, startedAtNanos: Long) {
        if (startedAtNanos == NO_SAMPLE) return
        record(metric, (clockNanos() - startedAtNanos).coerceAtLeast(0L))
    }

    private fun record(metric: Metric, elapsedNanos: Long) {
        val report = synchronized(lock) {
            if (windowStartedAtNanos == Long.MIN_VALUE) windowStartedAtNanos = clockNanos()
            val index = metric.ordinal
            counts[index]++
            totalNanos[index] += elapsedNanos
            if (elapsedNanos > maxNanos[index]) maxNanos[index] = elapsedNanos
            val nowNanos = clockNanos()
            if (nowNanos - windowStartedAtNanos < reportIntervalNanos) return
            val windowNanos = nowNanos - windowStartedAtNanos
            val text = buildReport(windowNanos)
            clearWindow(nowNanos)
            text
        } ?: return
        sink(report)
    }

    private fun clearWindow(nextWindowStartedAtNanos: Long) {
        counts.fill(0L)
        totalNanos.fill(0L)
        maxNanos.fill(0L)
        windowStartedAtNanos = nextWindowStartedAtNanos
    }

    private fun buildReport(windowNanos: Long): String {
        val report = StringBuilder(96)
        report.append("AOD perf aggregate, windowMs=").append(windowNanos / 1_000_000L)
        for (metric in Metric.entries) {
            val count = counts[metric.ordinal]
            if (count <= 0L) continue
            report.append(", ").append(metric.key)
                .append("={count=").append(count)
                .append(",avgUs=").append(totalNanos[metric.ordinal] / count / 1_000L)
                .append(",maxUs=").append(maxNanos[metric.ordinal] / 1_000L)
                .append('}')
        }
        return report.toString()
    }

    companion object {
        const val NO_SAMPLE = Long.MIN_VALUE
        const val DEFAULT_REPORT_INTERVAL_NANOS = 5_000_000_000L
    }
}
