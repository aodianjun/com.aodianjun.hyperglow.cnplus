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

    @Test
    fun previewRenderingDelegatesToSharedRenderCore() {
        // 预览同源契约(PARITY 契约的机器门):预览渲染必须委托 root 共享渲染核心,
        // 禁止在 ui 侧重建换算/渲染配方 —— 修复 PR #76 的显示漂移后,由本规则防止再漂。
        val base = mainSourceDir() ?: return
        val preview = File(base, "ui/PreviewComponents.kt")
        assertTrue("ui/PreviewComponents.kt exists", preview.isFile)
        val text = preview.readText()
        val required = listOf(
            "baseTextSizeSp",
            "textSizeModeMultiplier",
            "metadataTextSizeSp",
            "secondaryReadingTextSizeSp",
            "secondaryTranslationTextSizeSp",
            "nextLineTextSizeSp",
            "steadyTextAlpha",
            "staticSecondaryTextFactor",
            "staticNextLineTextFactor",
            "cardColorRgb",
            "resolveAodPalette",
            "secondLineColorArgb",
            "LyricGlowRenderer",
            "layoutOriginalLines",
            "layoutSecondaryLines",
            "layoutMetadataLines",
            "lineStartX"
        )
        val missing = required.filterNot { text.contains(it) }
        assertTrue("PreviewComponents must delegate to shared render core, missing: $missing", missing.isEmpty())
    }

    @Test
    fun previewRenderingHidesNoPrivateFormulaLiterals() {
        // 预览禁止出现渲染换算的字面量指纹(字号比例/透明度/卡片色表):
        // 这些公式只允许存在于 root 共享纯函数,ui 侧重写即为漂移回归。
        val base = mainSourceDir() ?: return
        val text = File(base, "ui/PreviewComponents.kt").readText()
        val marker = Regex(
            """0\.48f|0\.72f|0\.46f|0\.56f|0\.6f|-> 118|-> 140|1\.18f""" +
                """|0x3A6EA5|0x2A2A2A|0xFF1A1A1E|ComposeColor\(0xFF000000\)|ComposeColor\(0xFFFFFFFF\)"""
        )
        val hit = marker.findAll(text).map { it.value }.toList()
        assertTrue("render-math literals in PreviewComponents: $hit", hit.isEmpty())
    }

    @Test
    fun systemUiHostContextApplicationAlwaysFallsBack() {
        // SystemUI 侧 root/** 拿到的是宿主包 context,宿主 Application 对象可能不存在,
        // applicationContext 会返回 null(真机 NPE:AodPowerStateMonitor.attach 炸掉
        // buildSurface,息屏歌词 surface 整段空白):凡取 applicationContext 必须 ?: 回退。
        val base = mainSourceDir() ?: return
        val rootDir = File(base, "root")
        assumeTrue("scope root exists", rootDir.isDirectory)
        val marker = Regex("""\.applicationContext(?!\s*\?:)""")
        val violations = mutableListOf<String>()
        for (file in kotlinSources(rootDir)) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (marker.containsMatchIn(text)) {
                violations += file.relativeTo(base).invariantSeparatorsPath
            }
        }
        assertTrue("root applicationContext without ?: fallback in: $violations", violations.isEmpty())
    }

    @Test
    fun powerMonitorAttachNeverAbortsSurfaceBuild() {
        // 省电降帧是纯优化:AodPowerStateMonitor.attach 失败不得中断 AOD surface 构建
        // (真机 NPE 曾致息屏整段空白),必须 runCatching 隔离、失败仅降级 saver。
        val base = mainSourceDir() ?: return
        val controller = File(base, "root/aod/AodSurfaceController.kt")
        assertTrue("root/aod/AodSurfaceController.kt exists", controller.isFile)
        val marker = Regex("""runCatching\s*\{\s*AodPowerStateMonitor\.attach\(""")
        assertTrue(
            "AodPowerStateMonitor.attach must be wrapped in runCatching",
            marker.containsMatchIn(controller.readText())
        )
    }

    // --- 息屏渲染模式的来源契约 ---

    @Test
    fun aodRenderModesPreferCompiledProfileOverProducerRenderModes() {
        // 「BetterLyrics 预览看得到、实机没有」的机器门：息屏渲染模式必须以编译后的
        // AOD profile 为准，state.renderModes 只作兜底。应用内预览读的就是这份 profile
        // （PreviewComponents: betterLyrics = profile.animation == "BetterLyrics"），
        // 两端同源才谈得上所见即所得。
        //
        // 回归形状：投影层只读 state.renderModes，而全仓只有 Lyricon 会从 profile 回填它
        //（LyriconRenderModeMapping.toProducerRenderModes 是唯一调用点），LyricInfo /
        // SuperLyric 发硬编码默认值、Spicy 桥白名单又不含 BetterLyrics —— 这些源下
        // 「逐字动画」等设置永远到不了息屏，表现即「设置只在预览生效、实机不变」。
        val base = mainSourceDir() ?: return
        val projector = File(base, "aod/AodStateProjector.kt")
        assertTrue("aod/AodStateProjector.kt exists", projector.isFile)
        val text = projector.readText()
        val required = listOf(
            "aodProfile?.weight ?: modes.weight",
            "aodProfile?.textSize ?: modes.textSize",
            "aodProfile?.textSizeCustom ?: modes.textSizeCustom",
            "aodProfile?.secondaryMode ?: modes.secondary",
            "aodProfile?.animation ?: modes.animation",
            "aodProfile?.glow ?: modes.glow",
            "aodProfile?.lineSyncFillMode ?: modes.lineSyncFill",
            "aodProfile?.overflow ?: modes.overflow",
            "aodProfile?.fontFamily ?: modes.font",
            "aodProfile?.alignment ?: prefs.alignment",
            "aodProfile?.metadataAnchor ?: prefs.metadataAnchor",
            "aodProfile?.adaptiveSectioning ?: prefs.adaptiveSectioning",
            "resolveLineTransition(it.lineTransition"
        )
        val missing = required.filterNot { text.contains(it) }
        assertTrue(
            "AodStateProjector must read AOD render modes from the compiled profile first, " +
                "missing: $missing",
            missing.isEmpty()
        )
    }

    @Test
    fun producersDoNotShipUnnormalizableAnimationDefaults() {
        // aod/AodRenderPreferences.normalizeAodAnimation 只放行 Minimal / BetterLyrics，
        // 其余（含历史遗留名）一律回落 Gradient。生产者兜底默认值若写了过不了归一化的值，
        // 就是纯误导：既让兜底路径静默降级，也让「设置只在预览生效」这类反馈更难定位。
        val base = mainSourceDir() ?: return
        val producerDir = File(base, "producer")
        assumeTrue("scope producer exists", producerDir.isDirectory)
        // 普通字符串而非 raw string：raw string 遇到结尾的 `"` 会被 `"""` 提前截断。
        val marker = Regex("animation\\s*=\\s*\"Karaoke fill\"")
        val violations = mutableListOf<String>()
        for (file in kotlinSources(producerDir)) {
            val text = runCatching { file.readText() }.getOrNull() ?: continue
            if (marker.containsMatchIn(text)) {
                violations += file.relativeTo(base).invariantSeparatorsPath
            }
        }
        assertTrue(
            "producer renderModes defaults must survive normalizeAodAnimation, found \"Karaoke fill\" in: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun canvasKeepsRenderEffectsOffTheContentClipEdge() {
        // 「歌词刚好叠到画布边缘被裁切」的机器门(2026-10-03):歌词行的辉光光晕(字号 × 36%)
        // 与「BetterLyrics」档未唱字下沉会越出行盒,而竖屏画布上下内边距为 0、锁屏卡片又按
        // 实测内容定高 —— 块沿与内容裁剪沿必然重合,首/末行的外扩被切平成一条直线。
        //
        // 回归形状:余量只接一侧(只让卡片长高、放置没内缩,或反之),或画布退回自己私算
        // 0.36/0.10 字面量 —— 两者都会让块沿重新贴住裁剪沿。余量必须来自共享纯函数
        // (canvasEffectAllowancePx / canvasEffectEdgeNeeds),且同时接进「放置」(positionRows)
        // 与「自适应卡片高度测量」(measureContentStack)。
        val base = mainSourceDir() ?: return
        val canvas = File(base, "root/aod/AodLyricCanvasView.kt")
        assertTrue("root/aod/AodLyricCanvasView.kt exists", canvas.isFile)
        val text = canvas.readText()
        val required = listOf("canvasEffectAllowancePx(", "canvasEffectEdgeNeeds(")
        val missing = required.filterNot { text.contains(it) }
        assertTrue(
            "canvas effect allowance must delegate to the shared pure functions, missing: $missing",
            missing.isEmpty()
        )
        // 顶部/底部余量各须出现在放置与测量两处(每处一处,合计 ≥2)。
        for (symbol in listOf("effectNeeds.topPx", "effectNeeds.bottomPx")) {
            val count = text.split(symbol).size - 1
            assertTrue(
                "$symbol must be wired into both placement and adaptive-height measurement, " +
                    "found $count occurrence(s)",
                count >= 2
            )
        }
        // 光晕半径/下沉比例只允许来自共享核心(LyricGlowRenderer / LyricWordKaraokeRenderer)。
        val literals = Regex("""0\.36f|0\.10f""").findAll(text).map { it.value }.toList()
        assertTrue(
            "halo radius / sink fraction must come from the shared core, found literals: $literals",
            literals.isEmpty()
        )
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
