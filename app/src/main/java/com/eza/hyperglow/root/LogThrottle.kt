package com.eza.hyperglow.root

/**
 * 按键时间窗节流 + 抑制计数(Bridge DiagnosticThrottler 同型,issue #68 #10)。
 *
 * [shouldLog] 决定同一 [key] 在窗口内是否放行;窗口内被抑制的次数由 [drainSuppressed]
 * 取走,调用方在放行输出时以 "[suppressed=N]" 后缀补报——降噪不吞掉"发生了多少次"。
 * 时钟经构造注入(纯函数可单测);线程安全。
 */
internal class LogThrottle(private val nowMillis: () -> Long = System::currentTimeMillis) {

    private val lastLoggedMillis = HashMap<String, Long>()
    private val suppressedCounts = HashMap<String, Int>()

    @Synchronized
    fun shouldLog(key: String, windowMillis: Long): Boolean {
        val now = nowMillis()
        val last = lastLoggedMillis[key]
        if (last == null || now - last >= windowMillis) {
            lastLoggedMillis[key] = now
            return true
        }
        suppressedCounts[key] = (suppressedCounts[key] ?: 0) + 1
        return false
    }

    /** 取走并清除 [key] 的抑制计数(放行输出时调用一次)。 */
    @Synchronized
    fun drainSuppressed(key: String): Int {
        val count = suppressedCounts.remove(key) ?: 0
        return count
    }
}
