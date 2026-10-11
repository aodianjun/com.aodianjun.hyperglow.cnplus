package com.eza.hyperglow.diagnostics

import android.content.Context
import java.io.File

internal object DiagnosticDraftStore {
    private const val PREFS = "diagnostic_draft"
    private const val FILE_NAME = "diagnostic-report-pending.json"

    /**
     * 把待发送报告落盘为草稿。
     *
     * 契约:任何失败都返回 false,绝不抛异常。失败含两类:
     * 1. [DiagnosticReportCodec.encode] 的 `require` 校验不通过(非法报告/超客户端上限)——
     *    2026-10 修复:此前 encode 在 try 之外,校验异常会逃逸 Boolean 契约;
     * 2. 磁盘写入或偏好提交抛出的 IO 异常。
     * 返回 false 时草稿文件与偏好均保持原状(encode 失败不会留下半截文件)。
     */
    fun save(context: Context, report: DiagnosticReportEnvelope): Boolean {
        return try {
            val encoded = DiagnosticReportCodec.encode(report)
            File(context.cacheDir, FILE_NAME).writeText(encoded, Charsets.UTF_8)
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong(KEY_CREATED_AT, System.currentTimeMillis())
                .putString(KEY_REPORT_ID, report.reportId)
                .commit()
        } catch (_: Exception) {
            false
        }
    }

    fun load(context: Context): DiagnosticReportEnvelope? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val createdAt = prefs.getLong(KEY_CREATED_AT, -1L)
        val now = System.currentTimeMillis()
        if (createdAt < 0L || now < createdAt ||
            now - createdAt >= DiagnosticLimits.CAPTURE_TTL_MS
        ) {
            clear(context)
            return null
        }
        val report = try {
            val file = File(context.cacheDir, FILE_NAME)
            if (file.length() !in 1..DiagnosticLimits.CLIENT_BODY_BYTES.toLong()) {
                clear(context)
                return null
            }
            DiagnosticReportCodec.decodeOrNull(
                file.readText(Charsets.UTF_8)
            )
        } catch (_: Exception) {
            null
        }
        if (report == null || report.reportId != prefs.getString(KEY_REPORT_ID, "")) {
            clear(context)
            return null
        }
        return report
    }

    fun clear(context: Context) {
        runCatching { File(context.cacheDir, FILE_NAME).delete() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private const val KEY_CREATED_AT = "created_at"
    private const val KEY_REPORT_ID = "report_id"
}
