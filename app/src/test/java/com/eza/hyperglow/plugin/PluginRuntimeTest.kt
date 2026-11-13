package com.eza.hyperglow.plugin

import android.content.Context
import com.lidesheng.hyperlyric.plugin.api.HyperLyricPlugin
import com.lidesheng.hyperlyric.plugin.api.LyricProcessorExtension
import com.lidesheng.hyperlyric.plugin.api.PluginContext
import com.lidesheng.hyperlyric.plugin.api.PluginProcessingContext
import com.lidesheng.hyperlyric.plugin.api.PluginProcessorStage
import com.lidesheng.hyperlyric.plugin.api.PluginSong
import com.lidesheng.hyperlyric.plugin.api.PluginSongResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * 插件运行时的三条行为约束（2.6 加载/重载异步化、2.7 超时语义、2.12 onUnload）。
 *
 * 用 [PluginRuntime.entryLoaderOverride] 注入缝跳过真实 dex/ClassLoader：manifest 落盘、
 * 扫描、onLoad、整表替换、onUnload 全部走生产路径，只有「拿到入口实例」这一步被替换，
 * 因此这里断言的是真实 reload 行为而不是 mock 行为。超时/熔断阈值同样注入小值，
 * 生产默认（40s / 3 次）不变。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PluginRuntimeTest {

    /** 未运行哨兵：区分「执行器返回了结果」与「执行器根本没跑/被取消」。 */
    private val notRun = Any()

    @Before
    fun cleanPluginDirs() {
        PluginInstaller.pluginsRoot(context()).deleteRecursively()
    }

    @After
    fun tearDown() {
        PluginRuntime.entryLoaderOverride = null
        PluginRuntime.processorTimeoutMs = PluginRuntime.PROCESSOR_TIMEOUT_MS
        PluginRuntime.processorTimeoutCircuitThreshold = PluginRuntime.TIMEOUT_CIRCUIT_THRESHOLD
        PluginInstaller.pluginsRoot(context()).deleteRecursively()
    }

    private fun context(): Context = RuntimeEnvironment.getApplication()

    /** 只写 manifest.json：加载器走注入缝，不需要真 dex。 */
    private fun installManifest(context: Context, id: String) {
        val dir = PluginInstaller.pluginDir(context, id)
        dir.mkdirs()
        File(dir, "manifest.json").writeText(
            """{"id":"$id","name":"Fake $id","entry":"com.example.Fake","apiVersion":1}"""
        )
    }

    private fun song() = PluginSong(id = "song-1", name = "测试歌曲")

    /** 等某个插件实例真正进入已加载表（不提交新的 reload；按实例比对，避免跨用例残留误判）。 */
    private fun awaitLoaded(pluginId: String, expected: HyperLyricPlugin) {
        val deadline = System.currentTimeMillis() + 5_000
        while (PluginRuntime.installed(pluginId)?.plugin !== expected &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(10)
        }
        assertSame("插件 $pluginId 未在期限内加载完成", expected, PluginRuntime.installed(pluginId)?.plugin)
    }

    // --- 2.12 onUnload -----------------------------------------------------

    @Test
    fun reloadUnloadsThePreviousInstanceExactlyOnce() {
        val context = context()
        installManifest(context, "com.example.one")
        val created = CopyOnWriteArrayList<FakePlugin>()
        PluginRuntime.entryLoaderOverride = { _, _ -> FakePlugin().also { created += it } }

        PluginRuntime.bootstrap(context)
        runBlocking { PluginRuntime.reloadAllAndAwait() }

        // bootstrap 触发的首次加载 + 显式 reload = 两个实例，按提交顺序串行执行。
        assertEquals(2, created.size)
        assertEquals("旧实例必须被 onUnload 一次", 1, created[0].unloadCount.get())
        assertEquals("新实例仍在链上，不得被卸载", 0, created[1].unloadCount.get())
        assertEquals(1, PluginRuntime.installed().size)
        assertSame(created[1], PluginRuntime.installed("com.example.one")?.plugin)
    }

    @Test
    fun uninstallReloadUnloadsTheRemovedPluginOnly() {
        val context = context()
        installManifest(context, "com.example.one")
        installManifest(context, "com.example.two")
        val createdById = ConcurrentHashMap<String, CopyOnWriteArrayList<FakePlugin>>()
        PluginRuntime.entryLoaderOverride = { _, manifest ->
            val instance = FakePlugin()
            createdById.computeIfAbsent(manifest.id) { CopyOnWriteArrayList() }.add(instance)
            instance
        }

        PluginRuntime.bootstrap(context)
        runBlocking { PluginRuntime.reloadAllAndAwait() }
        assertEquals(2, PluginRuntime.installed().size)

        PluginInstaller.uninstall(context, "com.example.one")
        runBlocking { PluginRuntime.reloadAllAndAwait() }

        assertNull(PluginRuntime.installed("com.example.one"))
        assertEquals(1, PluginRuntime.installed().size)
        assertEquals(
            "被卸载插件的当前实例恰好卸载一次",
            1,
            createdById.getValue("com.example.one").last().unloadCount.get()
        )
        assertEquals(
            "仍在链上的插件不得被卸载",
            0,
            createdById.getValue("com.example.two").last().unloadCount.get()
        )
    }

    @Test
    fun onLoadFailureStillRunsCleanupExactlyOnce() {
        val context = context()
        installManifest(context, "com.example.broken")
        val created = CopyOnWriteArrayList<FakePlugin>()
        PluginRuntime.entryLoaderOverride = { _, _ ->
            FakePlugin(onLoadError = IllegalStateException("boom")).also { created += it }
        }

        PluginRuntime.bootstrap(context)
        runBlocking { PluginRuntime.reloadAllAndAwait() }

        // 每次加载都会新建实例：onLoad 抛错 → 立即 best-effort onUnload；
        // 下一次 reload 只对「仍持有实例」的旧表条目调 onUnload，失败条目不会二次清理。
        assertEquals(2, created.size)
        assertTrue("onLoad 失败的实例必须走清理", created.all { it.unloadCount.get() == 1 })
        val loaded = PluginRuntime.installed("com.example.broken")
        assertNotNull(loaded)
        assertNull(loaded?.plugin)
        assertTrue("loadError=${loaded?.loadError}", loaded?.loadError?.startsWith("onLoad:") == true)
    }

    @Test
    fun failingOnUnloadDoesNotBreakTheReload() {
        val context = context()
        installManifest(context, "com.example.one")
        val created = CopyOnWriteArrayList<FakePlugin>()
        PluginRuntime.entryLoaderOverride = { _, _ ->
            FakePlugin(onUnloadError = RuntimeException("cleanup failed")).also { created += it }
        }

        PluginRuntime.bootstrap(context)
        runBlocking { PluginRuntime.reloadAllAndAwait() }

        assertEquals(1, created[0].unloadCount.get())
        assertNotNull(
            "onUnload 抛错必须被吞掉，新实例照常上线",
            PluginRuntime.installed("com.example.one")?.plugin
        )
    }

    // --- 2.6 加载/重载异步化 ------------------------------------------------

    @Test
    fun reloadRunsOnTheBackgroundChannelWithoutBlockingTheCaller() {
        val context = context()
        installManifest(context, "com.example.one")
        val loaderThread = AtomicReference<String>()
        val loaderFinished = AtomicBoolean(false)
        val loaderEntered = CountDownLatch(1)
        val releaseLoader = CountDownLatch(1)
        PluginRuntime.entryLoaderOverride = { _, _ ->
            loaderThread.set(Thread.currentThread().name)
            loaderEntered.countDown()
            releaseLoader.await(10, TimeUnit.SECONDS)
            loaderFinished.set(true)
            FakePlugin()
        }

        PluginRuntime.bootstrap(context)

        assertTrue("加载必须已经开始", loaderEntered.await(5, TimeUnit.SECONDS))
        // 加载仍被卡住而 bootstrap 已返回 = 调用线程没有被插件加载阻塞。
        assertFalse("bootstrap 不得等待加载完成", loaderFinished.get())
        // 调试模式下协程会追加 " @coroutine#N"，按前缀断言线程通道。
        val threadName = loaderThread.get()
        assertTrue("加载必须走专用通道: $threadName", threadName.startsWith(PluginRuntime.RELOAD_THREAD_NAME))
        assertNotEquals(Thread.currentThread().name, threadName)

        releaseLoader.countDown()
        runBlocking { PluginRuntime.reloadAllAndAwait() }
        assertNotNull(PluginRuntime.installed("com.example.one")?.plugin)
    }

    @Test
    fun chainQueriesKeepSeeingTheOldSnapshotWhileANewLoadIsInFlight() {
        val context = context()
        installManifest(context, "com.example.one")
        val first = FakePlugin()
        val second = FakePlugin()
        val loads = AtomicInteger()
        val secondLoadEntered = CountDownLatch(1)
        val releaseSecondLoad = CountDownLatch(1)
        PluginRuntime.entryLoaderOverride = { _, _ ->
            val instance = if (loads.incrementAndGet() == 1) first else second
            if (loads.get() == 2) {
                secondLoadEntered.countDown()
                releaseSecondLoad.await(10, TimeUnit.SECONDS)
            }
            instance
        }

        // 首次加载（bootstrap 触发）完成后再制造一次「进行中的 reload」。
        PluginRuntime.bootstrap(context)
        awaitLoaded("com.example.one", first)
        assertSame(first, PluginRuntime.installed("com.example.one")?.plugin)

        val reload = PluginRuntime.reloadAll()
        assertTrue(secondLoadEntered.await(5, TimeUnit.SECONDS))
        // 加载进行中：链查询拿到的是上一份完整快照（旧实例），不是空表、也不是半截表。
        assertSame(first, PluginRuntime.installed("com.example.one")?.plugin)
        assertEquals(1, PluginRuntime.installed().size)

        releaseSecondLoad.countDown()
        runBlocking { reload.join() }
        assertSame(second, PluginRuntime.installed("com.example.one")?.plugin)
        assertEquals("旧实例恰好卸载一次", 1, first.unloadCount.get())
        assertEquals(0, second.unloadCount.get())
    }

    @Test
    fun concurrentReloadsStaySerializedAndNeverExposeAHalfTable() {
        val context = context()
        installManifest(context, "com.example.one")
        installManifest(context, "com.example.two")
        val inFlight = AtomicInteger()
        val maxInFlight = AtomicInteger()
        PluginRuntime.entryLoaderOverride = { _, _ ->
            val now = inFlight.incrementAndGet()
            maxInFlight.updateAndGet { maxOf(it, now) }
            Thread.sleep(30)
            inFlight.decrementAndGet()
            FakePlugin()
        }
        // 采样 installed()：只允许看到「完整旧表」或「完整新表」（都是 2 条），
        // 绝不允许看到 1 条（两个插件的半截表）。
        val observedSizes = ConcurrentLinkedQueue<Int>()
        val sampling = AtomicBoolean(true)

        PluginRuntime.bootstrap(context)
        runBlocking { PluginRuntime.reloadAllAndAwait() }
        assertEquals(2, PluginRuntime.installed().size)
        val sampler = Thread {
            while (sampling.get()) observedSizes += PluginRuntime.installed().size
        }.apply { isDaemon = true; start() }

        val first = PluginRuntime.reloadAll()
        val second = PluginRuntime.reloadAll()
        runBlocking {
            first.join()
            second.join()
        }
        sampling.set(false)
        sampler.join(2_000)

        assertEquals("两次并发 reload 不得交错", 1, maxInFlight.get())
        assertEquals(2, PluginRuntime.installed().size)
        assertTrue(
            "installed() 不得看到半截表: ${observedSizes.toSet()}",
            observedSizes.all { it == 2 }
        )
    }

    // --- 2.7 超时语义 / 取消传播 / 熔断 -------------------------------------

    @Test
    fun timeoutIsExplicitlyDistinguishedFromANullResult() {
        PluginRuntime.processorTimeoutMs = 50
        val obeying = FakeProcessor("obeying") {
            Thread.sleep(10_000)
            null
        }
        val timedOut = runBlocking {
            PluginRuntime.executeProcessor("com.example.one", obeying, song(), PluginProcessingContext())
        }
        assertEquals(PluginRuntime.ProcessorOutcome.TimedOut, timedOut)

        val nullResult = FakeProcessor("no-result") { null }
        val completed = runBlocking {
            PluginRuntime.executeProcessor(
                "com.example.one",
                nullResult,
                song(),
                PluginProcessingContext()
            )
        }
        // 旧实现（withTimeoutOrNull + 耗时猜上限）无法区分这两条路径。
        assertEquals(PluginRuntime.ProcessorOutcome.Completed(null), completed)
    }

    @Test
    fun ignoringInterruptStillEndsAsTimeoutOnlyAfterTheBlockReturns() {
        PluginRuntime.processorTimeoutMs = 30
        val blockMs = 200L
        val ignoring = FakeProcessor("ignoring") {
            val deadline = System.nanoTime() + blockMs * 1_000_000
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(5)
                } catch (ignored: InterruptedException) {
                    // 故意忽略中断：JVM 线程不能被强杀，只能等它自然返回。
                }
            }
            null
        }
        val startedAt = System.nanoTime()
        val outcome = runBlocking {
            PluginRuntime.executeProcessor("com.example.one", ignoring, song(), PluginProcessingContext())
        }
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(PluginRuntime.ProcessorOutcome.TimedOut, outcome)
        assertTrue(
            "超时不是强杀：必须等阻塞自然返回（elapsed=${elapsedMs}ms）",
            elapsedMs >= blockMs
        )
    }

    @Test
    fun outerCancellationPropagatesInsteadOfBeingSwallowed() {
        PluginRuntime.processorTimeoutMs = 60_000
        val entered = CountDownLatch(1)
        val blocking = FakeProcessor("blocking") {
            entered.countDown()
            Thread.sleep(60_000)
            null
        }
        val observed = AtomicReference<Any?>(notRun)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val job = scope.launch {
            try {
                observed.set(
                    PluginRuntime.executeProcessor(
                        "com.example.one",
                        blocking,
                        song(),
                        PluginProcessingContext()
                    )
                )
            } catch (cancelled: CancellationException) {
                observed.set(cancelled)
            }
        }

        assertTrue("处理器必须已进入执行", entered.await(5, TimeUnit.SECONDS))
        runBlocking { job.cancelAndJoin() }
        scope.cancel()

        val value = observed.get()
        assertTrue("外层取消必须继续传播，不得吞成 null/无结果: $value", value is CancellationException)
    }

    @Test
    fun consecutiveTimeoutsOpenTheCircuitAndSkipTheProcessor() {
        val context = context()
        installManifest(context, "com.example.one")
        PluginRuntime.processorTimeoutMs = 30
        PluginRuntime.processorTimeoutCircuitThreshold = 2
        val calls = AtomicInteger()
        val hanging = FakeProcessor("hanging") {
            calls.incrementAndGet()
            val deadline = System.nanoTime() + 120_000_000L
            while (System.nanoTime() < deadline) {
                try {
                    Thread.sleep(5)
                } catch (ignored: InterruptedException) {
                    // 忽略中断：模拟无法被超时终止的处理器。
                }
            }
            null
        }
        PluginRuntime.entryLoaderOverride = { _, _ -> FakePlugin(processor = hanging) }

        PluginRuntime.bootstrap(context)
        runBlocking { PluginRuntime.reloadAllAndAwait() }

        val input = song()
        repeat(3) {
            val output = runBlocking { PluginRuntime.processChain(input, null) }
            assertSame("无结果/熔断跳过时必须原样透传", input, output)
        }
        // 前两次各超时一次 → 达到阈值 2 熔断；第三次起直接跳过，不再调用插件。
        assertEquals("熔断后不得继续调用", 2, calls.get())

        // 新加载会话（reload）重置熔断状态。
        runBlocking { PluginRuntime.reloadAllAndAwait() }
        runBlocking { PluginRuntime.processChain(input, null) }
        assertEquals("reload 后必须重新尝试", 3, calls.get())
    }

    // --- 测试替身 -----------------------------------------------------------

    /** 假插件：记录 onLoad/onUnload 次数，可注入抛错与处理器注册。 */
    private class FakePlugin(
        private val processor: LyricProcessorExtension? = null,
        private val onLoadError: Throwable? = null,
        private val onUnloadError: Throwable? = null
    ) : HyperLyricPlugin {
        val loadCount = AtomicInteger()
        val unloadCount = AtomicInteger()

        override fun onLoad(context: PluginContext) {
            loadCount.incrementAndGet()
            processor?.let { context.registerExtension(it) }
            onLoadError?.let { throw it }
        }

        override fun onUnload() {
            unloadCount.incrementAndGet()
            onUnloadError?.let { throw it }
        }
    }

    /** 假处理器：宿主调的是 2 参 processResult，block 即其实现。 */
    private class FakeProcessor(
        override val id: String,
        override val stage: PluginProcessorStage = PluginProcessorStage.TRANSLATION_ENHANCEMENT,
        private val block: () -> PluginSongResult?
    ) : LyricProcessorExtension {
        override fun processResult(
            song: PluginSong,
            processingContext: PluginProcessingContext
        ): PluginSongResult? = block()
    }
}
