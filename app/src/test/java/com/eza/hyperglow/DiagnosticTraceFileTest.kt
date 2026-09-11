package com.eza.hyperglow

import java.io.File
import java.nio.file.Files
import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticTraceFileTest {
    @Test
    fun traceRotatesOnlyAtTheBound() {
        assertFalse(shouldRotateTrace(0L, DiagnosticTraceFile.MAX_BYTES))
        assertFalse(shouldRotateTrace(DiagnosticTraceFile.MAX_BYTES - 1L, DiagnosticTraceFile.MAX_BYTES))
        assertTrue(shouldRotateTrace(DiagnosticTraceFile.MAX_BYTES, DiagnosticTraceFile.MAX_BYTES))
        assertTrue(shouldRotateTrace(DiagnosticTraceFile.MAX_BYTES * 2, DiagnosticTraceFile.MAX_BYTES))
    }

    @Test
    fun readForReportKeepsOnlyLinesAtOrAfterTheCaptureStart() {
        val dir = Files.createTempDirectory("hyperglow-trace").toFile()
        try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
            val beforeMs = format.parse("2026-09-08T10:00:00.000")!!.time
            val sinceMs = format.parse("2026-09-08T10:02:00.000")!!.time
            val duringMs = format.parse("2026-09-08T10:05:00.000")!!.time
            // 轮转文件在前、当前文件在后,读取顺序保持时间递增。
            File(dir, DiagnosticTraceFile.ROTATED_FILE_NAME).writeText(
                "${format.format(beforeMs)} I [Area] stale-rotated\n" +
                    "${format.format(duringMs)} I [Area] fresh-rotated\n"
            )
            File(dir, DiagnosticTraceFile.FILE_NAME).writeText(
                "not a trace line\n" +
                    "${format.format(duringMs)} W [Area] fresh-current\n"
            )
            DiagnosticTraceFile.setDirectory(dir)

            val report = DiagnosticTraceFile.readForReport(sinceMs)

            assertTrue(report.contains("fresh-rotated"))
            assertTrue(report.contains("fresh-current"))
            assertFalse(report.contains("stale-rotated"))
            assertFalse(report.contains("not a trace line"))
        } finally {
            DiagnosticTraceFile.setDirectory(null)
            dir.deleteRecursively()
        }
    }

    @Test
    fun readForReportWithoutDirectoryYieldsNothing() {
        DiagnosticTraceFile.setDirectory(null)
        assertEquals("", DiagnosticTraceFile.readForReport(0L))
    }
}
