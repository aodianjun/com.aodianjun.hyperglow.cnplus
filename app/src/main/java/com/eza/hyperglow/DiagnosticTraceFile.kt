package com.eza.hyperglow

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App 进程日志的 root 可读文件镜像。
 *
 * HyperOS 在机主设备上会丢弃本进程的 logcat 输出,诊断报告里只能看到 SystemUI 侧 hook
 * 日志,App 侧每次决策都得反推。镜像只在诊断日志开启期间写入,容量有界并保留一次轮转,
 * 长会话不会撑爆 data 分区。
 */
internal object DiagnosticTraceFile {
    internal const val FILE_NAME = "diagnostic-trace.log"
    internal const val ROTATED_FILE_NAME = "diagnostic-trace.log.1"
    internal const val MAX_BYTES = 512L * 1024L

    /** "yyyy-MM-dd'T'HH:mm:ss.SSS".length,本对象写入的每一行都以此定长前缀开头。 */
    private const val TIMESTAMP_LENGTH = 23

    private val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var directory: File? = null

    fun setDirectory(directory: File?) {
        this.directory = directory
    }

    fun append(level: String, area: String, message: String) {
        val target = directory ?: return
        val line = "${format(System.currentTimeMillis())} $level [$area] $message"
        runCatching { write(target, line) }
    }

    /**
     * 读取自 sinceWallClockMs 起的镜像内容用于报告。logcat 各段都用 -T 从捕获开始切,
     * 镜像段保持同一口径:早于捕获开始的行属于上次会话,不进报告。无法解析时间戳的行
     * 直接丢弃——本对象写入的行都是定长时间戳开头,解析失败说明该行不是镜像行。
     */
    fun readForReport(sinceWallClockMs: Long): String {
        val dir = directory ?: return ""
        return sequenceOf(ROTATED_FILE_NAME, FILE_NAME)
            .map { File(dir, it) }
            .filter { it.isFile }
            .flatMap { file -> file.readLines().asSequence() }
            .filter { line -> parse(line)?.let { it >= sinceWallClockMs } == true }
            .joinToString("\n")
    }

    @Synchronized
    private fun format(nowMs: Long): String = timestamp.format(Date(nowMs))

    @Synchronized
    private fun parse(line: String): Long? {
        if (line.length < TIMESTAMP_LENGTH) return null
        return runCatching { timestamp.parse(line.substring(0, TIMESTAMP_LENGTH)).time }.getOrNull()
    }

    @Synchronized
    private fun write(directory: File, line: String) {
        if (!directory.isDirectory) return
        val file = File(directory, FILE_NAME)
        if (shouldRotateTrace(file.length(), MAX_BYTES)) {
            File(directory, ROTATED_FILE_NAME).delete()
            file.renameTo(File(directory, ROTATED_FILE_NAME))
        }
        file.appendText("$line\n")
    }
}

internal fun shouldRotateTrace(sizeBytes: Long, maxBytes: Long): Boolean = sizeBytes >= maxBytes
