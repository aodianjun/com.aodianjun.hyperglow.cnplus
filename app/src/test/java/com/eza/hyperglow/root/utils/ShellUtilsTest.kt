package com.eza.hyperglow.root.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellUtilsTest {

    @Test
    fun killProcessScriptWithRelaunchStartsComponentOnlyAfterSuccessfulKill() {
        val script = ShellUtils.buildKillProcessScript(
            packageName = "com.aodianjun.hyperglow.cnplus",
            relaunchComponent = "com.aodianjun.hyperglow.cnplus/com.eza.hyperglow.ui.MainActivity"
        )

        val relaunch =
            "sleep 1; am start -n \"com.aodianjun.hyperglow.cnplus/com.eza.hyperglow.ui.MainActivity\" >/dev/null 2>&1"

        assertTrue("kill commands missing", script.lastIndexOf("kill -s 9") >= 0)
        assertTrue("relaunch line missing", script.contains(relaunch))
        assertTrue(
            "relaunch must come after the kills",
            script.indexOf(relaunch) > script.lastIndexOf("kill -s 9")
        )
        assertTrue(
            "relaunch must run inside the success branch",
            script.indexOf(relaunch) > script.indexOf("if [ \$killed -eq 1 ]; then") &&
                script.indexOf(relaunch) < script.indexOf("exit 0")
        )
    }

    @Test
    fun killProcessScriptWithoutRelaunchContainsNoStartCommand() {
        val script = ShellUtils.buildKillProcessScript(
            packageName = "com.aodianjun.hyperglow.cnplus",
            relaunchComponent = null
        )

        assertTrue("kill commands missing", script.contains("kill -s 9"))
        assertFalse("unexpected relaunch", script.contains("am start"))
    }
}
