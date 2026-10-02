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
 * 长会话不会撑爆 data 分区。保留期限([setRetentionDays])另按时间修剪超期的行,
 * 「清除日志」入口可随时整体删除([clear])。
 */
internal object DiagnosticTraceFile {
    internal const val FILE_NAME = "diagnostic-trace.log"
    internal const val ROTATED_FILE_NAME = "diagnostic-trace.log.1"
    internal const val MAX_BYTES = 512L * 1024L
    internal const val DEFAULT_RETENTION_DAYS = 7

    /** "yyyy-MM-dd'T'HH:mm:ss.SSS".length,本对象写入的每一行都以此定长前缀开头。 */
    private const val TIMESTAMP_LENGTH = 23

    private val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var directory: File? = null

    @Volatile
    private var retentionDays: Int = DEFAULT_RETENTION_DAYS

    /**
     * 写入等级下限。总闸(「诊断日志」开关)打开后,只有严重度达到该下限的行才落盘;
     * 关闭(目录为 null)时一行都不写,等级不参与。
     */
    @Volatile
    private var minSeverity: Int = DIAGNOSTIC_SEVERITY_INFO

    fun setDirectory(directory: File?) {
        this.directory = directory
    }

    fun setRetentionDays(days: Int) {
        retentionDays = normalizeLogRetentionDays(days)
    }

    fun setMinSeverity(severity: Int) {
        minSeverity = severity
    }

    /**
     * 「清除日志」:删除两个镜像文件。清理目标由调用方显式传入——日志未开启时
     * [directory] 为空、镜像不写入,但上次会话的遗留文件仍要能清掉。
     * 返回两文件都已不存在。
     */
    @Synchronized
    fun clear(target: File): Boolean = runCatching {
        File(target, FILE_NAME).delete()
        File(target, ROTATED_FILE_NAME).delete()
        !File(target, FILE_NAME).exists() && !File(target, ROTATED_FILE_NAME).exists()
    }.getOrDefault(false)

    /**
     * 按保留期限修剪:丢弃早于截止点的行,整文件清空则删除。进程启动、保留期限变更与
     * 轮转时执行,无需每次追加都重写文件。
     */
    @Synchronized
    fun prune(target: File, nowMs: Long = System.currentTimeMillis()) {
        runCatching {
            val cutoff = logRetentionCutoffMs(nowMs, retentionDays)
            for (name in listOf(ROTATED_FILE_NAME, FILE_NAME)) {
                val file = File(target, name)
                if (!file.isFile) continue
                val lines = file.readLines()
                val kept = lines.filter { shouldRetainTraceLine(parse(it), cutoff) }
                if (kept.size == lines.size) continue
                if (kept.isEmpty()) {
                    file.delete()
                } else {
                    file.writeText(kept.joinToString("\n") + "\n")
                }
            }
        }
    }

    fun append(level: String, area: String, message: String) {
        if (!shouldWriteDiagnosticLine(level, minSeverity)) return
        val target = directory ?: return
        val line = "${format(System.currentTimeMillis())} $level [$area] $message"
        runCatching { write(target, line) }
    }

    /**
     * 「导出日志」:读取两个镜像文件的全部现存行(不做时间过滤——导出的是还没被清理的
     * 全部内容),轮转文件在前保持时间递增。目标由调用方显式传入,理由同 [clear]:
     * 日志当前关闭时 [directory] 为空,遗留文件仍要能导出。
     */
    fun readAll(target: File): String = runCatching {
        sequenceOf(ROTATED_FILE_NAME, FILE_NAME)
            .map { File(target, it) }
            .filter { it.isFile }
            .flatMap { file -> file.readLines().asSequence() }
            .joinToString("\n")
    }.getOrDefault("")

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
            prune(directory)
            File(directory, ROTATED_FILE_NAME).delete()
            file.renameTo(File(directory, ROTATED_FILE_NAME))
        }
        file.appendText("$line\n")
    }
}

internal fun shouldRotateTrace(sizeBytes: Long, maxBytes: Long): Boolean = sizeBytes >= maxBytes

private const val DAY_MS = 24L * 60L * 60L * 1000L

/** 日志保留期限档(天):镜像行超过该天数即清理。 */
internal val LOG_RETENTION_DAYS = listOf(1, 3, 7, 15, 30)

/** 保留期限天数规范化为最近的合法档(读档/写档/展示共用,取较小档破平)。 */
internal fun normalizeLogRetentionDays(days: Int): Int =
    LOG_RETENTION_DAYS.minByOrNull { kotlin.math.abs(it - days) } ?: DiagnosticTraceFile.DEFAULT_RETENTION_DAYS

/** 保留期限截止点:早于该时刻的镜像行可清理。 */
internal fun logRetentionCutoffMs(nowMs: Long, retentionDays: Int): Long =
    nowMs - normalizeLogRetentionDays(retentionDays) * DAY_MS

/** 时间戳不可解析的行不是镜像行,不保留(与 readForReport 同口径)。 */
internal fun shouldRetainTraceLine(lineTimestampMs: Long?, cutoffMs: Long): Boolean =
    lineTimestampMs != null && lineTimestampMs >= cutoffMs
