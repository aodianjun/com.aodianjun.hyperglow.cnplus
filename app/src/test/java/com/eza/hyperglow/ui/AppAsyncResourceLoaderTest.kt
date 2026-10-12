package com.eza.hyperglow.ui

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AppAsyncResourceLoader] 的行为契约(P3 主线程 IO 搬离的可测缝):
 * 成功路径发布结果、失败/为空回落 null、并发加载 latest wins(旧结果被丢弃)。
 */
class AppAsyncResourceLoaderTest {

    @Test
    fun loadRunsOffCallingThreadAndPublishesResult() = runBlocking {
        val callerThread = Thread.currentThread().name
        val producedOn = CopyOnWriteArrayList<String>()
        val published = CopyOnWriteArrayList<String?>()
        val loader = AppAsyncResourceLoader(Dispatchers.IO)

        loader.load(
            scope = this,
            produce = {
                producedOn += Thread.currentThread().name
                "decoded"
            },
            publish = { published += it }
        ).join()

        // 生产块不在调用线程执行(这就是「搬离主线程」本身)。
        assertEquals(1, producedOn.size)
        assertNotEquals(callerThread, producedOn.single())
        assertEquals(listOf<String?>("decoded"), published.toList())
    }

    @Test
    fun failureAndEmptyResultPublishNull() = runBlocking {
        val published = CopyOnWriteArrayList<String?>()
        val loader = AppAsyncResourceLoader(Dispatchers.IO)

        loader.load(this, produce = { "previous" }, publish = { published += it }).join()
        // 异常:与搬线程前一致,回落默认(null = 不绘制/跟随系统)。
        loader.load(
            this,
            produce = { throw IllegalStateException("decode failed") },
            publish = { published += it }
        ).join()
        // 空结果(文件缺失/解码失败返回 null)同样回落 null,并清掉上一次的值。
        loader.load(this, produce = { null }, publish = { published += it }).join()

        assertEquals(listOf<String?>("previous", null, null), published.toList())
    }

    @Test
    fun staleLoadDoesNotOverwriteNewerResult() = runBlocking {
        val published = CopyOnWriteArrayList<String?>()
        val loader = AppAsyncResourceLoader(Dispatchers.IO)
        val staleStarted = CountDownLatch(1)
        val releaseStale = CountDownLatch(1)

        // 第一次加载:慢(卡在 releaseStale),模拟用户换图前的旧解码。
        val stale = loader.load(
            scope = this,
            produce = {
                staleStarted.countDown()
                releaseStale.await(10, TimeUnit.SECONDS)
                "stale"
            },
            publish = { published += it }
        )
        assertTrue(awaitOffEventLoop { staleStarted.await(10, TimeUnit.SECONDS) })

        // 第二次加载:快,先完成并发布(新选择)。
        loader.load(this, produce = { "fresh" }, publish = { published += it }).join()
        assertEquals(listOf<String?>("fresh"), published.toList())

        // 旧加载随后完成:代次已过期,结果被丢弃,不覆盖新值。
        releaseStale.countDown()
        stale.join()
        assertEquals(listOf<String?>("fresh"), published.toList())
    }

    @Test
    fun newerStaleLoadDiscardsEarlierPendingResult() = runBlocking {
        val published = CopyOnWriteArrayList<String?>()
        val loader = AppAsyncResourceLoader(Dispatchers.IO)
        val firstStarted = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)

        val first = loader.load(
            scope = this,
            produce = {
                firstStarted.countDown()
                releaseFirst.await(10, TimeUnit.SECONDS)
                "first"
            },
            publish = { published += it }
        )
        assertTrue(awaitOffEventLoop { firstStarted.await(10, TimeUnit.SECONDS) })
        // 后发起的加载也慢于更后发起的一次;只有最后一次的结果允许发布。
        val second = loader.load(
            scope = this,
            produce = {
                releaseFirst.await(10, TimeUnit.SECONDS)
                "second"
            },
            publish = { published += it }
        )
        val third = loader.load(this, produce = { "third" }, publish = { published += it })
        third.join()
        releaseFirst.countDown()
        first.join()
        second.join()

        assertEquals(listOf<String?>("third"), published.toList())
    }

    /**
     * 在 IO 通道上等待闩锁:runBlocking 的事件循环与测试体共用线程,直接在测试体里
     * 阻塞 `await` 会让已排队但尚未启动的子协程永远拿不到线程(测试自身死锁)。
     */
    private suspend fun awaitOffEventLoop(await: () -> Boolean): Boolean =
        withContext(Dispatchers.IO) { await() }
}
