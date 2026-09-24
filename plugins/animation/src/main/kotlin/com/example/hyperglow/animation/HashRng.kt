package com.example.hyperglow.animation

/**
 * 由字符串种子驱动的确定性 64-bit SplitMix64 PRNG。
 *
 * 插件 API 只有一次 [com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension.processResult]
 * 回调（无逐帧通道），因此装饰必须**稳定可复现**：同一首歌无论装多少次、切多少轮，
 * 装饰位置/字符都一致；不同歌曲各自独立。用 song 元数据字符串做种子是最自然的
 * 做法，且不需要跨进程状态。
 *
 * SplitMix64 的常量和 `Long.MAX_VALUE` 都超过有符号 64-bit 上限，用它们的**负数形式**
 * 表达（如 GOLDEN_RATIO_DELTA 是 -7046029254386353131，等价于 0x9E3779B97F4A7C15L 按
 * 位解释）。Kotlin 的 `*` / `xor` / `ushr` 在 Long 域都是自然回绕，符合原始 C 语义。
 */
internal class HashRng(seed: String) {
    // 混合阶段：多项式散列 + SplitMix64 混叠，把字符串 hash 均匀化，
    // 避免 0 种子退化分支（SplitMix64 输入 0 时会立即回退为 0，无熵）。
    private var state: Long = run {
        val mix = seed.fold(0L) { acc, ch ->
            acc * POLY_HASH_MULTIPLIER + ch.code.toLong()
        }
        val finalMix = (mix xor (mix ushr 30)) xor (mix ushr 27) xor (mix ushr 31)
        if (finalMix == 0L) -1L else finalMix
    }

    private fun nextRaw(): Long {
        state += GOLDEN_RATIO_DELTA
        var z = state
        z = (z xor (z ushr 30)) * MIX_CONST_A
        z = (z xor (z ushr 27)) * MIX_CONST_B
        return z xor (z ushr 31)
    }

    fun nextInt(bound: Int): Int {
        if (bound <= 0) return 0
        val v = nextRaw() and Long.MAX_VALUE
        return (v % bound).toInt()
    }

    fun pick(list: List<String>): String = list[nextInt(list.size)]

    private companion object {
        // 多项式散列乘子（Knuth 32-bit 变体常用值），Long 承载避免溢出歧义。
        const val POLY_HASH_MULTIPLIER: Long = 1_000_003L
        // SplitMix64 golden-ratio delta（0x9E3779B97F4A7C15L 的负数表达）
        const val GOLDEN_RATIO_DELTA: Long = -7_046_029_254_386_353_131L
        // SplitMix64 mix constants（0xBF58476D1CE4E5B9L / 0x94D049BB133111EBL 的负数表达）
        const val MIX_CONST_A: Long = -4_658_895_682_640_981_031L
        const val MIX_CONST_B: Long = -4_357_678_929_576_625_239L
    }
}
