package com.eza.hyperglow.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticReportFactoryPropertyTest {
    @Test
    fun parsesBracketFormatAndKeepsOnlyRequestedKeys() {
        val values = DiagnosticReportFactory.selectProperties(
            lines = listOf(
                "[ro.mi.os.version.name]: [OS2.0]",
                "[ro.miui.ui.version.name]: [V816]",
                "[ro.product.model]: [ignored]"
            ),
            keys = listOf(
                "ro.mi.os.version.name",
                "ro.miui.ui.version.name",
                "ro.missing"
            )
        )

        assertEquals(
            mapOf(
                "ro.mi.os.version.name" to "OS2.0",
                "ro.miui.ui.version.name" to "V816"
            ),
            values
        )
    }

    @Test
    fun parsesKeyValueFormatAndDropsBlankValues() {
        val values = DiagnosticReportFactory.selectProperties(
            lines = listOf(
                "ro.miui.build.region=CN",
                "ro.product.mod_device=",
                "malformed",
                "ro.mi.os.version.incremental=OS2.0.1.0=extra"
            ),
            keys = listOf(
                "ro.miui.build.region",
                "ro.product.mod_device",
                "ro.mi.os.version.incremental"
            )
        )

        assertEquals(
            mapOf(
                "ro.miui.build.region" to "CN",
                "ro.mi.os.version.incremental" to "OS2.0.1.0=extra"
            ),
            values
        )
    }

    @Test
    fun truncatesLongPropertyValues() {
        val values = DiagnosticReportFactory.selectProperties(
            lines = listOf("[ro.miui.ui.version.code]: [${"9".repeat(300)}]"),
            keys = listOf("ro.miui.ui.version.code")
        )

        assertEquals("9".repeat(256), values.getValue("ro.miui.ui.version.code"))
    }
}
