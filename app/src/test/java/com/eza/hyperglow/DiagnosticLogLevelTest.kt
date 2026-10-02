package com.eza.hyperglow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogLevelTest {
    @Test
    fun levelNormalizationFallsBackToNormalForUnknownValues() {
        assertEquals(DiagnosticLogLevel.ERRORS, normalizeDiagnosticLogLevel("errors"))
        assertEquals(DiagnosticLogLevel.WARNING, normalizeDiagnosticLogLevel("warning"))
        assertEquals(DiagnosticLogLevel.NORMAL, normalizeDiagnosticLogLevel("normal"))
        assertEquals(DiagnosticLogLevel.VERBOSE, normalizeDiagnosticLogLevel("verbose"))
        assertEquals(DiagnosticLogLevel.NORMAL, normalizeDiagnosticLogLevel(null))
        assertEquals(DiagnosticLogLevel.NORMAL, normalizeDiagnosticLogLevel("bogus"))
    }

    @Test
    fun onlyVerboseMirrorsSystemUiLogs() {
        assertTrue(DiagnosticLogLevel.VERBOSE.mirrorsSystemUiLogs)
        assertFalse(DiagnosticLogLevel.ERRORS.mirrorsSystemUiLogs)
        assertFalse(DiagnosticLogLevel.WARNING.mirrorsSystemUiLogs)
        assertFalse(DiagnosticLogLevel.NORMAL.mirrorsSystemUiLogs)
    }

    @Test
    fun lineFloorKeepsOnlyAtOrAboveTheSelectedLevel() {
        val error = DiagnosticLogLevel.ERRORS.minSeverity
        assertFalse(shouldWriteDiagnosticLine("I", error))
        assertFalse(shouldWriteDiagnosticLine("W", error))
        assertTrue(shouldWriteDiagnosticLine("E", error))

        val warning = DiagnosticLogLevel.WARNING.minSeverity
        assertFalse(shouldWriteDiagnosticLine("I", warning))
        assertTrue(shouldWriteDiagnosticLine("W", warning))
        assertTrue(shouldWriteDiagnosticLine("E", warning))

        val normal = DiagnosticLogLevel.NORMAL.minSeverity
        assertTrue(shouldWriteDiagnosticLine("I", normal))
        assertTrue(shouldWriteDiagnosticLine("W", normal))
        assertTrue(shouldWriteDiagnosticLine("E", normal))
    }

    @Test
    fun unrecognizedLineLevelsAreAlwaysDropped() {
        // logcat 的 V/D 低于 info,没有诊断价值,任何档位都不落盘。
        assertFalse(shouldWriteDiagnosticLine("V", DIAGNOSTIC_SEVERITY_INFO))
        assertFalse(shouldWriteDiagnosticLine("D", DIAGNOSTIC_SEVERITY_INFO))
        assertFalse(shouldWriteDiagnosticLine("?", DIAGNOSTIC_SEVERITY_INFO))
    }

    @Test
    fun logcatCommandPullsOnlyTheModuleTagAfterTheGivenMoment() {
        val command = systemUiLogcatCommand(0L)

        assertTrue(command.startsWith("logcat -d -b main -b system -v threadtime -T '"))
        assertTrue(command.endsWith("-s $SYSTEM_UI_LOG_TAG:V '*:S'"))
    }

    @Test
    fun mirrorKeepsModuleTagLinesAndNormalizesLevels() {
        val output = """
            09-08 10:05:00.123  1234  1234 I HyperGlow: [AodSurface] hello
            09-08 10:05:00.124  1234  1234 E HyperGlow: [AodSurface] boom
            09-08 10:05:00.125  1234  1234 W HyperGlow: [AodSurface] warn
            09-08 10:05:00.126  1234  1234 F HyperGlow: [AodSurface] fatal
            09-08 10:05:00.127  1234  1234 V HyperGlow: [AodSurface] noisy
            09-08 10:05:00.128  1234  1234 I OtherTag: ignored
        """.trimIndent()

        val mirrored = mirrorSystemUiLogcatLines(output)

        assertEquals(
            listOf(
                MirroredSystemUiLine("I", "[AodSurface] hello"),
                MirroredSystemUiLine("E", "[AodSurface] boom"),
                MirroredSystemUiLine("W", "[AodSurface] warn"),
                MirroredSystemUiLine("E", "[AodSurface] fatal")
            ),
            mirrored
        )
    }
}
