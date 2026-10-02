package com.eza.hyperglow

import com.eza.hyperglow.diagnostics.DiagnosticLimits
import com.eza.hyperglow.diagnostics.DiagnosticRootCommandRunner
import com.eza.hyperglow.diagnostics.DiagnosticRootProcessRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「详细」档专属:SystemUI hook 侧模块日志镜像。
 *
 * 总闸开启且等级为「详细」时,后台按固定周期以 root 拉取本模块 tag 的 logcat
 * (命令见 [systemUiLogcatCommand]),命中行写入同一个 App 镜像文件。等级切走或
 * 总闸关闭即停止,不再产生任何 root 调用。
 */
internal object DiagnosticSystemUiMirror {
    /** 拉取周期。logcat 用 -T 按上次拉取时刻增量切,周期不必太小。 */
    internal const val PULL_INTERVAL_MS = 20_000L

    /** 首次拉取的回看窗口:进程刚起时不搬整段历史,只给一小段重叠。 */
    private const val INITIAL_LOOKBACK_MS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var job: Job? = null

    /** 单测缝隙:替换 root 命令执行器。 */
    @Volatile
    internal var runner: DiagnosticRootCommandRunner = DiagnosticRootProcessRunner

    /** 幂等启停:需要则拉起拉取循环,不需要则取消;重复调用不叠加协程。 */
    @Synchronized
    fun sync(shouldRun: Boolean) {
        if (!shouldRun) {
            job?.cancel()
            job = null
            return
        }
        if (job?.isActive == true) return
        job = scope.launch { runLoop() }
    }

    private suspend fun runLoop() {
        var sinceWallClockMs = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        while (true) {
            delay(PULL_INTERVAL_MS)
            val pulledAtMs = System.currentTimeMillis()
            val result = runner.run(
                systemUiLogcatCommand(sinceWallClockMs),
                DiagnosticLimits.COMMAND_TIMEOUT_MS
            )
            // 无论本次是否拿到输出都推进游标,避免一次空转后重复整段历史。
            sinceWallClockMs = pulledAtMs
            if (result.output.isEmpty()) continue
            for (line in mirrorSystemUiLogcatLines(result.output)) {
                DiagnosticTraceFile.append(line.level, SYSTEM_UI_LOG_AREA, line.message)
            }
        }
    }
}
