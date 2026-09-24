package com.example.hyperglow.animation

/**
 * 由字符串种子驱动的确定性 64-bit xorshift PRNG。
 *
 * 插件 API 只有一次 [com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension.processResult]
 * 回调（无逐帧通道），因此装饰必须**稳定可复现**：同一首歌无论装多少次、切多少轮，
 * 装饰位置/字符都一致；不同歌曲各自独立。用 song 元数据字符串做种子是最自然的
 * 做法，且不需要跨进程状态。
 */
internal class HashRng(seed: String) {
    // SplitMix64 混叠，把 hashCode 抖动均匀化，避免 0 种子的退化分支。
    private var state: Long = seed.fold(0L) { acc, ch ->
        acc * 1000003uL + ch.code.toULong()
    }.let { mix ->
        mix xor (mix ushr 30) xor (mix ushr 27) xor (mix ushr 31)
    }.let { if (it == 0L) -1L else it }

    private fun nextRaw(): Long {
        state += 0x9E3779B97F4A7C15L
        var z = state
        z = (z xor (z ushr 30)) * 0xBF58476D1CE4E5B9L
        z = (z xor (z ushr 27)) * 0x94D049BB133111EBL
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int {
        if (bound <= 0) return 0
        val v = nextRaw() and Long.MAX_VALUE
        return (v % bound).toInt()
    }

    fun pick(list: List<String>): String = list[nextInt(list.size)]
}
