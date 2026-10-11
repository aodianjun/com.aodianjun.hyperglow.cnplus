package com.eza.hyperglow.producer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2.12「sequence 非原子」修复的编号原子性测试：
 * 多线程并发递增不丢号、不重号（旧实现 `@Volatile var` 上的 `++` 读-改-写会交错）。
 * 只覆盖编号本身；多字段快照一致性不在本类职责内（见 [ProducerEmitSequence] 注释）。
 */
class ProducerEmitSequenceTest {

    private val threads = 8
    private val perThread = 2_000

    private fun hammer(block: () -> Unit) {
        val ready = CountDownLatch(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)
        repeat(threads) {
            Thread {
                ready.countDown()
                start.await()
                try {
                    block()
                } finally {
                    done.countDown()
                }
            }.start()
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS))
    }

    @Test
    fun concurrentSequenceIncrementsNeverDropOrDuplicateNumbers() {
        val emit = ProducerEmitSequence()
        val seen = ConcurrentHashMap.newKeySet<Long>()

        hammer { repeat(perThread) { seen += emit.nextSequence() } }

        val total = (threads * perThread).toLong()
        // 不重号：去重后数量等于调用次数；不丢号：最大值等于调用次数
        // （两条件同时成立 ⇔ 取值恰为 1..total）。
        assertEquals(total, seen.size.toLong())
        assertEquals(total, seen.maxOrNull())
    }

    @Test
    fun concurrentGenerationIncrementsAreAlsoAtomic() {
        val emit = ProducerEmitSequence()
        val seen = ConcurrentHashMap.newKeySet<Int>()

        hammer { repeat(perThread) { seen += emit.nextGeneration() } }

        val total = threads * perThread
        assertEquals(total, seen.size)
        assertEquals(total, seen.maxOrNull())
        assertEquals(total, emit.generation)
    }
}
