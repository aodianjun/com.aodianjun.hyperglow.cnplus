package com.eza.hyperglow.root.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/** Root process restart path extracted from HyperLyric. */
object ShellUtils {
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val MIUI_AOD_PACKAGE = "com.miui.aod"

    /** killAppProcess 结果：至少杀掉一个进程。 */
    private const val KILL_KILLED = 0
    /** killAppProcess 结果：进程未在运行（无可杀 pid）。 */
    private const val KILL_NOT_RUNNING = 2

    /**
     * 重启模块作用域(scope.list)内可安全重启的进程，由调用方勾选：
     * - [systemUi]：SystemUI 进程（锁屏/锁屏编辑的 hook 也装在这里）；
     * - [miuiAod]：独立的 MiuiAOD 进程（AOD 亮度/生命周期/表面 hook）。
     *
     * 两者都选时先杀 MiuiAOD——未运行或杀失败都不算失败（SystemUI 重启后会重新拉起
     * AOD），SystemUI 的结果作为整体成功标志。仅选 MiuiAOD 时，进程未在运行也视为
     * 成功：hook 将在下次 AOD 启动时自然生效，无需报失败。
     * android(system_server)同样在作用域内但只能整机重启，不由此入口处理。
     */
    suspend fun restartHookedProcesses(
        systemUi: Boolean = true,
        miuiAod: Boolean = true
    ): Boolean {
        require(systemUi || miuiAod) { "no restart target selected" }
        val aodResult = if (miuiAod) killAppProcess(MIUI_AOD_PACKAGE) else KILL_NOT_RUNNING
        return if (systemUi) {
            killAppProcess(SYSTEM_UI_PACKAGE) == KILL_KILLED
        } else {
            aodResult == KILL_KILLED || aodResult == KILL_NOT_RUNNING
        }
    }

    /**
     * 返回 root 脚本退出码：0=至少杀掉一个进程，1=有 pid 但杀失败（或 root 拒绝），
     * 2=进程未在运行，-1=执行异常（无 root 等）。
     */
    private suspend fun killAppProcess(packageName: String, signal: Int = 15): Int {
        val script = $$"""
            pid=$(pgrep -f "$$packageName" | grep -v $$)
            if [ -z "$pid" ]; then
                pids=""
                pid=$(ps -A -o PID,ARGS=CMD | grep "$$packageName" | grep -v "grep")
                for i in $pid; do
                    case "$i" in
                        ''|*[!0-9]*) ;;
                        *) pids="$pids $i" ;;
                    esac
                done
                pid=$pids
            fi

            killed=0
            if [ -n "$pid" ]; then
                for i in $pid; do
                    kill -s $$signal "$i" >/dev/null 2>&1
                    kill -s 9 "$i" >/dev/null 2>&1
                    if [ $? -eq 0 ]; then
                        killed=1
                    fi
                done
            fi

            if [ $killed -eq 1 ]; then
                exit 0
            fi
            if [ -z "$pid" ]; then
                exit 2
            fi
            exit 1
        """.trimIndent()

        return execRootScriptExitCode("nsenter --mount=/proc/1/ns/mnt -- sh", script)
    }

    private suspend fun execRootScriptExitCode(cmd: String, inputScript: String): Int =
        withContext(Dispatchers.IO) {
            var process: Process? = null
            var output: DataOutputStream? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                output = DataOutputStream(process.outputStream)
                output.write(inputScript.toByteArray(Charsets.UTF_8))
                output.writeBytes("\nexit\n")
                output.flush()
                process.waitFor()
            } catch (_: Exception) {
                -1
            } finally {
                runCatching { output?.close() }
                process?.destroy()
            }
        }
}
