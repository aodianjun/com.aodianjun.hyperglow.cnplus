package com.eza.hyperglow.root.utils

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream

/** Root process restart path extracted from HyperLyric. */
object ShellUtils {
    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val MIUI_AOD_PACKAGE = "com.miui.aod"

    /**
     * 重启模块作用域(scope.list)内可安全重启的进程:先杀 MiuiAOD——亮度 hook 装在
     * 该进程,未运行或杀失败都不算失败;再杀 SystemUI,其结果作为整体成功标志,
     * 重启后由它重新拉起 AOD。android(system_server)同样在作用域内但只能整机
     * 重启,不由此入口处理。
     */
    suspend fun restartHookedProcesses(): Boolean {
        killAppProcess(MIUI_AOD_PACKAGE)
        return killAppProcess(SYSTEM_UI_PACKAGE)
    }

    private suspend fun killAppProcess(packageName: String, signal: Int = 15): Boolean {
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
            else
                exit 1
            fi
        """.trimIndent()

        return execRootScriptSilent("nsenter --mount=/proc/1/ns/mnt -- sh", script)
    }

    private suspend fun execRootScriptSilent(cmd: String, inputScript: String): Boolean =
        withContext(Dispatchers.IO) {
            var process: Process? = null
            var output: DataOutputStream? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
                output = DataOutputStream(process.outputStream)
                output.write(inputScript.toByteArray(Charsets.UTF_8))
                output.writeBytes("\nexit\n")
                output.flush()
                process.waitFor() == 0
            } catch (_: Exception) {
                false
            } finally {
                runCatching { output?.close() }
                process?.destroy()
            }
        }
}
