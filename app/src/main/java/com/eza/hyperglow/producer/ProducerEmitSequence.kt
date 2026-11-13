package com.eza.hyperglow.producer

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 生产者的会话代次(generation)与发射序号(sequence)。
 *
 * 为什么要原子:会话回调(主线程)、位置轮询(`Dispatchers.Default`)与 restart 可并发进入
 * emit/换歌路径;`@Volatile var` 上的 `++` 是「读-改-写」三步,交错会丢号或重号,下游仲裁器
 * (producerId:generation:sequence)据此去重/判过期,重号会让相邻两帧被当成同一帧。
 *
 * 注意(评审结论):原子化只解决编号本身。标题/行/位置等多字段快照的一致性仍需收敛到单一
 * 发射上下文,不在本类职责内。
 */
internal class ProducerEmitSequence {
    private val generationCounter = AtomicInteger(0)
    private val sequenceCounter = AtomicLong(0L)

    /** 当前会话代次(换歌时递增);读取不改变状态。 */
    val generation: Int get() = generationCounter.get()

    /** 进入新会话代次(换歌),返回递增后的代次。 */
    fun nextGeneration(): Int = generationCounter.incrementAndGet()

    /** 下一次发射序号(全局单调递增,不重号),返回递增后的序号。 */
    fun nextSequence(): Long = sequenceCounter.incrementAndGet()
}
