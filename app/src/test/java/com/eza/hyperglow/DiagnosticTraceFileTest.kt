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

    @Test
    fun pruneDropsLinesOlderThanTheRetentionCutoff() {
        val dir = Files.createTempDirectory("hyperglow-trace").toFile()
        try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
            val oldMs = format.parse("2026-09-01T10:00:00.000")!!.time
            val recentMs = format.parse("2026-09-27T10:00:00.000")!!.time
            val nowMs = format.parse("2026-09-28T10:00:00.000")!!.time
            File(dir, DiagnosticTraceFile.ROTATED_FILE_NAME).writeText(
                "${format.format(oldMs)} I [Area] stale-rotated\n"
            )
            File(dir, DiagnosticTraceFile.FILE_NAME).writeText(
                "${format.format(oldMs)} I [Area] stale-current\n" +
                    "${format.format(recentMs)} I [Area] fresh\n" +
                    "not a trace line\n"
            )
            DiagnosticTraceFile.setRetentionDays(7)

            DiagnosticTraceFile.prune(dir, nowMs)

            assertFalse(File(dir, DiagnosticTraceFile.ROTATED_FILE_NAME).exists())
            val kept = File(dir, DiagnosticTraceFile.FILE_NAME).readText()
            assertTrue(kept.contains("fresh"))
            assertFalse(kept.contains("stale-current"))
            assertFalse(kept.contains("not a trace line"))
        } finally {
            DiagnosticTraceFile.setRetentionDays(DiagnosticTraceFile.DEFAULT_RETENTION_DAYS)
            dir.deleteRecursively()
        }
    }

    @Test
    fun appendDropsLinesBelowTheLevelFloor() {
        val dir = Files.createTempDirectory("hyperglow-trace").toFile()
        try {
            DiagnosticTraceFile.setDirectory(dir)
            DiagnosticTraceFile.setMinSeverity(DIAGNOSTIC_SEVERITY_WARN)

            DiagnosticTraceFile.append("I", "Area", "info-line")
            DiagnosticTraceFile.append("W", "Area", "warn-line")
            DiagnosticTraceFile.append("E", "Area", "error-line")

            val written = File(dir, DiagnosticTraceFile.FILE_NAME).readText()
            assertFalse(written.contains("info-line"))
            assertTrue(written.contains("warn-line"))
            assertTrue(written.contains("error-line"))
        } finally {
            DiagnosticTraceFile.setMinSeverity(DIAGNOSTIC_SEVERITY_INFO)
            DiagnosticTraceFile.setDirectory(null)
            dir.deleteRecursively()
        }
    }

    @Test
    fun appendDropsUnrecognizedLevelsRegardlessOfTheFloor() {
        val dir = Files.createTempDirectory("hyperglow-trace").toFile()
        try {
            DiagnosticTraceFile.setDirectory(dir)
            DiagnosticTraceFile.setMinSeverity(DIAGNOSTIC_SEVERITY_INFO)

            DiagnosticTraceFile.append("V", "Area", "verbose-line")

            assertFalse(File(dir, DiagnosticTraceFile.FILE_NAME).exists())
        } finally {
            DiagnosticTraceFile.setDirectory(null)
            dir.deleteRecursively()
        }
    }

    @Test
    fun readAllReturnsBothFilesOldestFirstWithoutTimeFilter() {
        val dir = Files.createTempDirectory("hyperglow-trace").toFile()
        try {
            File(dir, DiagnosticTraceFile.ROTATED_FILE_NAME).writeText(
                "2026-09-01T10:00:00.000 I [Area] rotated\n"
            )
            File(dir, DiagnosticTraceFile.FILE_NAME).writeText(
                "2026-09-02T10:00:00.000 E [Area] current\n"
            )

            val all = DiagnosticTraceFile.readAll(dir)

            assertEquals(
                "2026-09-01T10:00:00.000 I [Area] rotated\n" +
                    "2026-09-02T10:00:00.000 E [Area] current",
                all
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readAllWithoutAnyTraceFileYieldsEmpty() {
        val dir = Files.createTempDirectory("hyperglow-trace").toFile()
        try {
            assertEquals("", DiagnosticTraceFile.readAll(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun clearRemovesBothTraceFiles() {
        val dir = Files.createTempDirectory("hyperglow-trace").toFile()
        try {
            File(dir, DiagnosticTraceFile.FILE_NAME).writeText("x\n")
            File(dir, DiagnosticTraceFile.ROTATED_FILE_NAME).writeText("y\n")

            assertTrue(DiagnosticTraceFile.clear(dir))

            assertFalse(File(dir, DiagnosticTraceFile.FILE_NAME).exists())
            assertFalse(File(dir, DiagnosticTraceFile.ROTATED_FILE_NAME).exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun logRetentionNormalizationSnapsToAllowedDays() {
        assertEquals(1, normalizeLogRetentionDays(-3))
        assertEquals(1, normalizeLogRetentionDays(1))
        assertEquals(3, normalizeLogRetentionDays(5))
        assertEquals(7, normalizeLogRetentionDays(7))
        assertEquals(15, normalizeLogRetentionDays(20))
        assertEquals(30, normalizeLogRetentionDays(99))
    }

    @Test
    fun retentionCutoffMovesBackWholeDays() {
        val nowMs = 1_000_000_000_000L

        assertEquals(nowMs - 7L * 24 * 60 * 60 * 1000, logRetentionCutoffMs(nowMs, 7))
        assertEquals(nowMs - 30L * 24 * 60 * 60 * 1000, logRetentionCutoffMs(nowMs, 99))
    }

    @Test
    fun unparseableTimestampsAreNeverRetained() {
        assertFalse(shouldRetainTraceLine(null, 0L))
        assertFalse(shouldRetainTraceLine(10L, 20L))
        assertTrue(shouldRetainTraceLine(20L, 20L))
    }
}
