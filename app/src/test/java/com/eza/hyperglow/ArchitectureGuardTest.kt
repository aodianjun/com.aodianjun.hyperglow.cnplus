package com.eza.hyperglow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 架构守卫(Bridge BridgeArchitectureGuardTest 同型,issue #68 #3):把 STYLE_GUIDE/
 * ARCHITECTURE 里靠人肉遵守的边界变成 CI 会红的断言。规则均在 0.3.113 (140) 现状上
 * 验证为零违规后才写入;扩展代码时若触发本测试,要么改正设计,要么在变更评审中
 * 显式更新本文件的允许清单。
 */
class ArchitectureGuardTest {

    @Test
    fun loggingGoesThroughAppLogOrHookLoggerOnly() {
        // 日志契约:所有 android.util.Log 调用只允许出现在两个日志门面里,
        // 其余代码经 AppLog/HookLogger 输出(否则诊断开关/镜像/脱敏全部旁路)。
        assertOnlyInFiles(
            marker = Regex("""(^|[^.\w])Log\.[iedvw]\(|import android\.util\.Log"""),
            allowedRelativePaths = setOf("Log.kt", "root/HookLogger.kt"),
            failureHint = "direct android.util.Log usage outside the logging facades"
        )
    }

    @Test
    fun networkClientsStayInAllowlistedFiles() {
        // SystemUI 运行时零网络(ARCHITECTURE 排除条款);app 侧仅允许列出的
        // 诊断上传(历史保留)与版本检查使用网络客户端。
        assertOnlyInFiles(
            marker = Regex("""java\.net\.URL|HttpURLConnection|OkHttpClient|openConnection"""),
            allowedRelativePaths = setOf(
                "diagnostics/DiagnosticUploader.kt",
                "ui/SettingsSupport.kt",
                "ui/VersionCheck.kt"
            ),
            failureHint = "network client outside the allowlisted files"
        )
    }

    @Test
    fun systemUiRuntimeCodeNeverImportsAppUi() {
        // root/** 会被加载进 SystemUI:禁止依赖 app 侧 Compose 设置层。
        assertScopesDoNotImport(scope = "root", forbidden = "com.eza.hyperglow.ui.")
    }

    @Test
    fun producersStayOutOfUiAndRoot() {
        // producer/** 是纯 app 侧来源层:不得触碰 UI,也不得反向依赖 hook 侧。
        assertScopesDoNotImport(scope = "producer", forbidden = "com.eza.hyperglow.ui.")
        assertScopesDoNotImport(scope = "producer", forbidden = "com.eza.hyperglow.root.")
    }

    @Test
    fun aodProjectionNeverImportsAppUi() {
        assertScopesDoNotImport(scope = "aod", forbidden = "com.eza.hyperglow.ui.")
    }

    // --- helpers ---

    private fun mainSourceDir(): File? {
        val direct = File("app/src/main/java/com/eza/hyperglow")
        if (direct.isDirectory) return direct
        val fromParent = File("../app/src/main/java/com/eza/hyperglow")
        assumeTrue("app sources are visible to unit tests", fromParent.isDirectory)
        return fromParent
    }

    private fun kotlinSources(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    private fun assertOnlyInFiles(marker: Regex, allowedRelativePaths: Set<String>, failureHint: String) {
        val base = mainSourceDir() ?: return
        val violations = mutableListOf<String>()
        for (file in kotlinSources(base)) {
            val relative = file.relativeTo(base).invariantSeparatorsPath
            if (relative in allowedRelativePaths) continue
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (marker.containsMatchIn(text)) violations += relative
        }
        assertTrue("$failureHint in: $violations", violations.isEmpty())
    }

    private fun assertScopesDoNotImport(scope: String, forbidden: String) {
        val base = mainSourceDir() ?: return
        val scopeDir = File(base, scope)
        assumeTrue("scope $scope exists", scopeDir.isDirectory)
        val violations = mutableListOf<String>()
        for (file in kotlinSources(scopeDir)) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (text.lines().any { it.trimStart().startsWith("import $forbidden") }) {
                violations += file.relativeTo(base).invariantSeparatorsPath
            }
        }
        assertTrue("$scope must not import $forbidden, found in: $violations", violations.isEmpty())
    }
}
