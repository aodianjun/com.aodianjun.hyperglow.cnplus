package com.eza.hyperglow.customization

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.encodeToString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 2.1 反向锁序回归:三个写入入口(saveDocument / importDocument / reset)必须保证
 * [CustomizationRepository.onChange] 在**不持有** repository 监视器时执行。
 *
 * 正向链:写入入口(持 repository 锁)→ onChange → LyricProducers.onCustomizationChanged →
 * Lyricon 对唱快照(`LyriconDuetAlignmentKt` 的 static synchronized,持文件 facade 类锁)→
 * loadCompiled(要 repository 锁)。反向链:LyriconSdkCallbacks(持 facade 类锁)→
 * loadCompiled(要 repository 锁)。回调若在最外层 repository 锁内执行,UI 线程与 SDK
 * 回调线程即构成 ABBA 死锁(UI 挂死/ANR),见 CNPLUS-OPTIMIZATION-REVIEW 2.1。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CustomizationRepositoryLockTest {

    private fun context() = RuntimeEnvironment.getApplication()

    /** CustomizationRepository 是进程级 object:回调必须清掉,避免污染其它用例。 */
    @After
    fun clearOnChange() {
        CustomizationRepository.onChange = null
    }

    /**
     * 回调体内抛异常会被 `runCatching` 吞掉,所以「回调是否持锁」记录到锁外再断言:
     * 修复前该值为 true(必失败),修复后为 false(必通过)。
     */
    private fun assertWriteRunsCallbackOutsideRepositoryMonitor(
        entry: String,
        write: () -> Boolean
    ) {
        val calls = AtomicInteger(0)
        val heldRepositoryLock = AtomicBoolean(false)
        CustomizationRepository.onChange = {
            calls.incrementAndGet()
            heldRepositoryLock.set(Thread.holdsLock(CustomizationRepository))
        }

        assertTrue("$entry 应保存成功", write())
        assertEquals("$entry 成功保存应恰好触发一次 onChange", 1, calls.get())
        assertFalse("$entry 的 onChange 不得在 repository 监视器内执行", heldRepositoryLock.get())
    }

    @Test
    fun saveDocumentRunsCallbackOutsideRepositoryMonitor() {
        assertWriteRunsCallbackOutsideRepositoryMonitor("saveDocument") {
            CustomizationRepository.saveDocument(context(), SceneCompiler.safeDefaultDocument())
        }
    }

    @Test
    fun importDocumentRunsCallbackOutsideRepositoryMonitor() {
        val raw = SceneCompiler.json.encodeToString(SceneCompiler.safeDefaultDocument())
        assertWriteRunsCallbackOutsideRepositoryMonitor("importDocument") {
            CustomizationRepository.importDocument(context(), raw)
        }
    }

    @Test
    fun resetRunsCallbackOutsideRepositoryMonitor() {
        assertWriteRunsCallbackOutsideRepositoryMonitor("reset") {
            CustomizationRepository.reset(context())
        }
    }

    @Test
    fun failedWritesDoNotInvokeCallback() {
        val calls = AtomicInteger(0)
        CustomizationRepository.onChange = { calls.incrementAndGet() }

        // canonicalize 失败(migrate 拒绝未知版本)与 decode 失败都不得触发回调。
        val unknownVersion = SceneCompiler.safeDefaultDocument().copy(version = 999)
        assertFalse(CustomizationRepository.saveDocument(context(), unknownVersion))
        assertFalse(CustomizationRepository.importDocument(context(), "{ not json"))

        assertEquals("失败写入不得触发 onChange", 0, calls.get())
    }

    /**
     * 并发锤:线程 A 循环 saveDocument(其回调复刻真实正向链:facade 类锁 → loadCompiled),
     * 线程 B 复刻 SDK 回调线程的反向锁序(facade 类锁 → loadCompiled)。修复前为 ABBA 死锁
     * (A 持 repository 锁等 facade 类锁、B 持 facade 类锁等 repository 锁),超时断言必失败;
     * 修复后回调在 repository 锁外执行,全链锁序单向(facade → repository),必须跑完不挂死。
     */
    @Test
    fun saveDocumentAndReverseLockOrderSdkThreadCompleteWithoutDeadlock() {
        val context = context()
        // `LyriconDuetAlignmentKt` 的 @Synchronized 扩展函数编译为 static synchronized,
        // 监视器即文件 facade 类的 Class 对象(javap 已核验,见 2.1 证据链)。
        val duetAlignmentFacade = Class.forName("com.eza.hyperglow.producer.LyriconDuetAlignmentKt")
        val document = SceneCompiler.safeDefaultDocument()
        CustomizationRepository.onChange = {
            // 复刻 LyricProducers.onCustomizationChanged → LyriconLyricProducer →
            // refreshDuetAlignment(持 facade 类锁)→ CustomizationRepository.loadCompiled。
            synchronized(duetAlignmentFacade) {
                CustomizationRepository.loadCompiled(context)
            }
        }

        val completed = CountDownLatch(2)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val saver = Thread({
            try {
                repeat(SAVE_ITERATIONS) {
                    check(CustomizationRepository.saveDocument(context, document))
                }
            } catch (t: Throwable) {
                failures.add(t)
            } finally {
                completed.countDown()
            }
        }, "lockorder-saver").apply { isDaemon = true }
        val sdkThread = Thread({
            try {
                repeat(SDK_ITERATIONS) {
                    // 复刻 LyriconSdkCallbacks.onSongChanged → refreshDuetAlignment → loadCompiled。
                    synchronized(duetAlignmentFacade) {
                        CustomizationRepository.loadCompiled(context)
                    }
                }
            } catch (t: Throwable) {
                failures.add(t)
            } finally {
                completed.countDown()
            }
        }, "lockorder-sdk").apply { isDaemon = true }

        saver.start()
        sdkThread.start()
        // 死锁时两线程互等,倒计时归不了零 → 超时断言失败(修复前必失败)。
        assertTrue(
            "并发锤未在 ${HAMMER_TIMEOUT_SECONDS}s 内完成(反向锁序死锁?);线程异常=$failures",
            completed.await(HAMMER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        )
        assertTrue("并发锤线程异常: $failures", failures.isEmpty())
    }

    private companion object {
        /** 保存迭代:每轮持 repository 锁落盘、锁外回调,与 SDK 侧反向锁序反复碰撞。 */
        const val SAVE_ITERATIONS = 300

        /** SDK 侧迭代:loadCompiled 命中编译缓存时开销很小,可以多跑。 */
        const val SDK_ITERATIONS = 2_000

        const val HAMMER_TIMEOUT_SECONDS = 10L
    }
}
