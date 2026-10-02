package com.eza.hyperglow

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 本地日志等级档:只决定写入日志镜像(`diagnostic-trace.log`)的等级下限。
 * 「诊断日志」开关仍是总闸——关闭时镜像目录为空,一行都不写;等级只在总闸开启后生效。
 *
 * [VERBOSE] 额外把 SystemUI 进程里的模块日志(logcat,经 root 拉取)一并落盘,
 * 即「关于本模块的日志全部保留」。
 */
internal enum class DiagnosticLogLevel(val wire: String, val minSeverity: Int) {
    /** 仅错误(E):只留故障行,噪声最低。 */
    ERRORS("errors", DIAGNOSTIC_SEVERITY_ERROR),

    /** 警告及以上(W/E)。 */
    WARNING("warning", DIAGNOSTIC_SEVERITY_WARN),

    /** 正常(I/W/E):App 侧决策日志与故障日志,默认档。 */
    NORMAL("normal", DIAGNOSTIC_SEVERITY_INFO),

    /** 详细:全等级,并额外镜像 SystemUI 侧模块日志(logcat)。 */
    VERBOSE("verbose", DIAGNOSTIC_SEVERITY_INFO);

    /** 是否额外镜像 SystemUI hook 侧模块日志(需要 root,且比其余档更耗电)。 */
    val mirrorsSystemUiLogs: Boolean
        get() = this == VERBOSE
}

/** 镜像行等级字母对应的严重度:越大越严重。 */
internal const val DIAGNOSTIC_SEVERITY_ERROR = 3
internal const val DIAGNOSTIC_SEVERITY_WARN = 2
internal const val DIAGNOSTIC_SEVERITY_INFO = 1

internal val DIAGNOSTIC_LOG_LEVELS = DiagnosticLogLevel.entries.toList()

/** 等级词表归一化:未知值回落「正常」(默认档),不放大也不静默降到只剩错误。 */
internal fun normalizeDiagnosticLogLevel(value: String?): DiagnosticLogLevel =
    DiagnosticLogLevel.entries.firstOrNull { it.wire == value } ?: DiagnosticLogLevel.NORMAL

/**
 * 镜像行等级字母 → 严重度。无法识别(含 logcat 的 V/D)返回 null,由 [shouldWriteDiagnosticLine]
 * 直接丢弃:低于 info 的行没有诊断价值,不该挤占有界镜像。
 */
internal fun diagnosticLineSeverity(level: String): Int? = when (level.uppercase(Locale.US)) {
    "E" -> DIAGNOSTIC_SEVERITY_ERROR
    "W" -> DIAGNOSTIC_SEVERITY_WARN
    "I" -> DIAGNOSTIC_SEVERITY_INFO
    else -> null
}

/** 该等级的行是否达到镜像下限。 */
internal fun shouldWriteDiagnosticLine(level: String, minSeverity: Int): Boolean {
    val severity = diagnosticLineSeverity(level) ?: return false
    return severity >= minSeverity
}

// --- SystemUI 侧模块日志(logcat 拉取)---

/** 与 hook 侧 [com.eza.hyperglow.root.HookLogger] 一致的 logcat tag。 */
internal const val SYSTEM_UI_LOG_TAG = "HyperGlow"

internal const val SYSTEM_UI_LOG_AREA = "SystemUI"

/** SystemUI 侧日志被镜像时的等级与原消息。 */
internal data class MirroredSystemUiLine(val level: String, val message: String)

/** 与 `logcat -v threadtime` 对齐,口径同诊断采集的 -T 时间戳。 */
private val SYSTEM_UI_LOGCAT_TIME_FORMATTER = DateTimeFormatter.ofPattern(
    "MM-dd HH:mm:ss.SSS",
    Locale.US
)

/**
 * 只拉取本模块 tag 的 logcat 行。`-d` 单次转储后退出,`-b main -b system` 覆盖 hook 输出所在
 * 缓冲区;`-T` 从上次拉取时刻起切,避免重复转储整段历史把有界镜像冲掉。
 */
internal fun systemUiLogcatCommand(sinceWallClockMs: Long): String {
    val since = SYSTEM_UI_LOGCAT_TIME_FORMATTER.format(
        Instant.ofEpochMilli(sinceWallClockMs).atZone(ZoneId.systemDefault())
    )
    return "logcat -d -b main -b system -v threadtime -T '$since' " +
        "-s $SYSTEM_UI_LOG_TAG:V '*:S'"
}

/** `MM-dd HH:mm:ss.SSS  PID  TID L TAG: message`。 */
private val SYSTEM_UI_THREADTIME_LINE =
    Regex("^\\S+\\s+\\S+\\s+\\S+\\s+\\S+\\s+([VDIWEF])\\s+([^:]+):\\s?(.*)$")

/**
 * logcat 输出 → 可镜像的行。只保留本模块 tag;等级映射到镜像用的 I/W/E(详见
 * [diagnosticLineSeverity],V/D 会被下限过滤),消息取 tag 之后的部分。
 */
internal fun mirrorSystemUiLogcatLines(output: String): List<MirroredSystemUiLine> =
    output.lineSequence().mapNotNull { raw ->
        val match = SYSTEM_UI_THREADTIME_LINE.find(raw.trim()) ?: return@mapNotNull null
        if (match.groupValues[2].trim() != SYSTEM_UI_LOG_TAG) return@mapNotNull null
        val level = when (match.groupValues[1]) {
            "E", "F" -> "E"
            "W" -> "W"
            "I" -> "I"
            else -> return@mapNotNull null
        }
        MirroredSystemUiLine(level, match.groupValues[3])
    }.toList()
