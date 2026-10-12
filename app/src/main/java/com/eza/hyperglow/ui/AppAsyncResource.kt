package com.eza.hyperglow.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 「异步加载 + latest wins」调度器(P3 主线程 IO 搬离)。
 *
 * 背景:应用界面的背景图解码与自定义字体解析原先都在 Compose 合成期同步执行。
 * 本地测量报告的量级估算(非真机数字,ANR 未测):背景 JPEG 最长边 2160px,
 * 单次 [android.graphics.BitmapFactory.decodeFile] 约 50–300ms;字体文件上限
 * 30MB([com.eza.hyperglow.customization.CustomFontContract.MAX_FONT_BYTES]),
 * 单次 [android.graphics.Typeface.createFromFile] 约 10–50ms。两笔都直接记在
 * 主线程的启动/换图/换字体路径上,这里把「读文件 + 解码/解析」整体搬到
 * [dispatcher](默认 IO 通道),合成期只读最近一次结果。
 *
 * 顺序保证(latest wins):每次 [load] 领取一个递增代次;结果回到发起线程后,
 * 若期间已发起更新的代次,旧结果被丢弃——连续换图/换字体时最终生效的必然是最后一次,
 * 旧结果不会覆盖新选择。[load] 自身不取消旧任务(取消不保证打断文件解码),
 * 过期结果只丢弃不发布。
 */
internal class AppAsyncResourceLoader(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val generation = AtomicLong(0L)

    /**
     * 在 [scope] 中发起一次加载:[produce] 在 [dispatcher] 上执行,结果经代次校验后
     * 交给 [publish]。加载失败(异常)或为空时发布 null,由调用方沿用既有回落表现
     * (不绘制背景/跟随系统字体),与搬线程前「失败即回落默认」一致。
     * 返回本次加载的 [Job],便于调用方/测试等待完成。
     */
    fun <V> load(scope: CoroutineScope, produce: suspend () -> V?, publish: (V?) -> Unit): Job {
        val ticket = generation.incrementAndGet()
        return scope.launch {
            val loaded = try {
                withContext(dispatcher) { produce() }
            } catch (cancelled: CancellationException) {
                // 作用域取消(退出组合)继续传播,不吞掉结构化并发语义。
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (generation.get() != ticket) return@launch
            publish(loaded)
        }
    }
}

/**
 * [AppAsyncResourceLoader] 的组合入口:[keys] 变化时在 IO 通道重新加载,返回最近一次结果。
 * 未完成/失败/被更新代次取代时为 null(调用方沿用默认回落表现);加载中保留上一次的值,
 * 换图/换字体期间不闪回默认(最终生效的仍是最后一次加载的结果)。
 */
@Composable
internal fun <V> rememberAppAsyncResource(vararg keys: Any?, load: suspend () -> V?): V? {
    val scope = rememberCoroutineScope()
    val loader = remember { AppAsyncResourceLoader() }
    var value by remember { mutableStateOf<V?>(null) }
    LaunchedEffect(*keys) { loader.load(scope, load) { value = it } }
    return value
}
