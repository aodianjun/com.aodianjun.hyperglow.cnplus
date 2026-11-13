package com.eza.hyperglow.ui

import android.content.Context
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.plugin.PluginInstaller
import com.eza.hyperglow.plugin.PluginManifest
import com.eza.hyperglow.plugin.PluginRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 插件应用内下载与自动更新中心。
 *
 * 发布通道即 `docs/PLUGIN_RELEASE_CONVENTIONS.md` 定义的唯一滚动发行
 * （GitHub tag `plugins`，资产名固定 `hyperglow-<name>-plugin.zip`，版本在 manifest 里）：
 * 检查 = 拉资产表 → 逐个下载 ZIP → [PluginInstaller.readManifest] 解出远端 manifest →
 * 与已装 manifest 按 id 比版本。自动模式（`plugin_auto_update_enabled`，默认开）在
 * App 启动后延迟执行：**更新**直接装、**新装**装（除非用户卸载过该插件——卸载即加入
 * 忽略表，避免「刚删又被自动装回来」）；失败静默记日志，不影响启动。
 *
 * 网络读取全部走 [queryPluginReleaseAssets] / [downloadPluginZip]（VersionCheck.kt，
 * ArchitectureGuardTest 的网络白名单文件）；本文件只做编排，不出现网络客户端类型。
 */
internal object PluginUpdateCenter {
    private const val TAG = "PluginUpdate"

    private const val PREFS = "plugin_auto_update"
    private const val KEY_LAST_AUTO_CHECK_MS = "last_auto_check_ms"
    private const val KEY_LAST_RESULT = "last_result_summary"
    private const val KEY_AUTO_IGNORED = "auto_install_ignored"

    /** 两次自动检查的最小间隔：插件是滚动发行，一天数查足矣，省流量也省 API 配额。 */
    private const val AUTO_CHECK_MIN_INTERVAL_MS = 6 * 60 * 60 * 1000L

    /** 启动后延迟再查：避开冷启动争抢（dex 加载/桥服务/首个投影帧）。 */
    private const val STARTUP_DELAY_MS = 8_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkMutex = Mutex()
    private var startupScheduled = false

    /** 远端插件相对本机的状态。 */
    internal enum class State {
        /** 未安装（自动模式：不在忽略表才自动安装）。 */
        NEW_INSTALL,
        /** 已安装且远端版本更新（自动模式：直接升级）。 */
        UPDATE,
        /** 已安装且已是远端版本。 */
        UP_TO_DATE,
        /** ZIP 损坏或 manifest 无效：绝不自动安装，手动检查页如实呈现。 */
        INVALID
    }

    /** 一条远端插件的检查结果。 */
    internal data class RemotePluginStatus(
        val assetName: String,
        val url: String,
        val remote: PluginManifest?,
        val installed: PluginManifest?,
        val state: State
    ) {
        val displayId: String get() = remote?.id ?: assetName
    }

    internal sealed interface CheckOutcome {
        data class Success(val statuses: List<RemotePluginStatus>) : CheckOutcome
        data class Failure(val reason: String) : CheckOutcome
    }

    // --- 偏好 ---

    internal fun autoUpdateEnabled(context: Context): Boolean =
        AodRenderPreferences.read(context).pluginAutoUpdateEnabled

    internal fun lastAutoCheckMs(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_AUTO_CHECK_MS, 0L)

    internal fun lastResult(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LAST_RESULT, null)

    private fun recordResult(context: Context, summary: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_AUTO_CHECK_MS, System.currentTimeMillis())
            .putString(KEY_LAST_RESULT, summary)
            .apply()
    }

    /** 用户主动卸载的插件加入忽略表：自动模式不再把它装回来；手动安装会解除忽略。 */
    internal fun setIgnored(context: Context, pluginId: String, ignored: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = prefs.getStringSet(KEY_AUTO_IGNORED, emptySet()).orEmpty().toMutableSet()
        val changed = if (ignored) next.add(pluginId) else next.remove(pluginId)
        if (changed) prefs.edit().putStringSet(KEY_AUTO_IGNORED, next).apply()
    }

    private fun ignoredIds(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_AUTO_IGNORED, emptySet()).orEmpty()

    // --- 检查 ---

    /**
     * 拉取插件发行并逐资产比对本地。只读，不安装；安装走 [install]。
     * 网络与磁盘读取都在 IO 通道；并发检查互斥（手动 + 自动撞车时后来者等待）。
     */
    internal suspend fun check(context: Context): CheckOutcome = checkMutex.withLock {
        val app = context.applicationContext
        val assets = withContext(Dispatchers.IO) { queryPluginReleaseAssets() }
            ?: return@withLock CheckOutcome.Failure("release query failed")
        // 资产名约定：hyperglow-<name>-plugin.zip；其余资产（示意包等）不进检查。
        val pluginAssets = assets.filter { asset ->
            asset.name.matches(Regex("hyperglow-.+-plugin\\.zip")) &&
                asset.sizeBytes <= PluginInstaller.MAX_ZIP_BYTES
        }
        if (pluginAssets.isEmpty()) return@withLock CheckOutcome.Failure("no plugin assets")
        val installedById = PluginRuntime.installed().associateBy { it.manifest.id }
        val statuses = pluginAssets.map { asset ->
            val bytes = withContext(Dispatchers.IO) {
                downloadPluginZip(asset.url, PluginInstaller.MAX_ZIP_BYTES)
            }
            val remote = bytes?.let { PluginInstaller.readManifest(it) }
            val installed = remote?.let { installedById[it.id]?.manifest }
            val state = when {
                remote == null -> State.INVALID
                installed == null -> State.NEW_INSTALL
                compareVersions(remote.version, installed.version) > 0 -> State.UPDATE
                else -> State.UP_TO_DATE
            }
            RemotePluginStatus(asset.name, asset.url, remote, installed, state)
        }
        CheckOutcome.Success(statuses)
    }

    /** 安装/升级一个远端插件：下载 → [PluginInstaller.install] → 重载运行时。 */
    internal suspend fun install(context: Context, url: String): Pair<PluginManifest?, String> {
        val app = context.applicationContext
        val bytes = withContext(Dispatchers.IO) {
            downloadPluginZip(url, PluginInstaller.MAX_ZIP_BYTES)
        } ?: return null to "download failed"
        val (manifest, reason) = withContext(Dispatchers.IO) {
            PluginInstaller.install(app, bytes)
        }
        if (manifest != null) {
            // 手动安装 = 用户明确要它：从自动忽略表解除。
            setIgnored(app, manifest.id, false)
            PluginRuntime.reloadAllAndAwait()
        }
        return manifest to reason
    }

    /**
     * 自动更新一轮：检查 → 更新直装、新装（不在忽略表）直装 → 返回结果摘要。
     * 全程静默（失败只记日志），摘要在插件页展示。
     */
    internal suspend fun runAutoUpdateOnce(context: Context): String {
        val app = context.applicationContext
        val outcome = check(app)
        val summary = when (outcome) {
            is CheckOutcome.Failure -> "检查失败（${outcome.reason}）"
            is CheckOutcome.Success -> {
                val ignored = ignoredIds(app)
                val actions = mutableListOf<String>()
                for (status in outcome.statuses) {
                    val shouldAutoInstall = when (status.state) {
                        State.UPDATE -> true
                        State.NEW_INSTALL -> status.displayId !in ignored
                        else -> false
                    }
                    if (!shouldAutoInstall) continue
                    val (manifest, reason) = install(app, status.url)
                    if (manifest != null) {
                        actions += when (status.state) {
                            State.UPDATE ->
                                "${manifest.id} v${manifest.version}（已升级）"
                            else ->
                                "${manifest.id} v${manifest.version}（新装）"
                        }
                        AppLog.i(TAG, "auto install ${status.displayId} -> v${manifest.version}")
                    } else {
                        actions += "${status.displayId}（安装失败: $reason）"
                        AppLog.w(TAG, "auto install failed for ${status.displayId}: $reason")
                    }
                }
                if (actions.isEmpty()) {
                    "已是最新（${outcome.statuses.size} 个插件）"
                } else {
                    actions.joinToString("；")
                }
            }
        }
        recordResult(app, summary)
        AppLog.i(TAG, "auto update done: $summary")
        return summary
    }

    /**
     * App 启动后的自动检查入口（幂等；MainActivity.onCreate 调用）。
     * 开关关闭或距上次检查不足 [AUTO_CHECK_MIN_INTERVAL_MS] 时静默跳过。
     */
    internal fun scheduleStartupCheck(context: Context) {
        synchronized(this) {
            if (startupScheduled) return
            startupScheduled = true
        }
        val app = context.applicationContext
        scope.launch {
            delay(STARTUP_DELAY_MS)
            if (!autoUpdateEnabled(app)) {
                AppLog.i(TAG, "startup check skipped: auto update disabled")
                return@launch
            }
            val last = lastAutoCheckMs(app)
            val elapsed = System.currentTimeMillis() - last
            if (last != 0L && elapsed < AUTO_CHECK_MIN_INTERVAL_MS) {
                AppLog.i(TAG, "startup check skipped: last check ${elapsed / 60000}min ago")
                return@launch
            }
            runCatching { runAutoUpdateOnce(app) }
                .onFailure { error -> AppLog.w(TAG, "startup auto update failed", error) }
        }
    }
}

/** 插件页更新检查的会话状态（对话框不能挂 LazyColumn item 里,状态提升到屏函数）。 */
internal sealed interface PluginUpdateUiState {
    data object Idle : PluginUpdateUiState

    /** 检查进行中。 */
    data object Checking : PluginUpdateUiState

    /** 检查完成,出结果列表。 */
    data class Done(val outcome: PluginUpdateCenter.CheckOutcome) : PluginUpdateUiState

    /** 单个插件安装进行中。 */
    data class Installing(val status: PluginUpdateCenter.RemotePluginStatus) : PluginUpdateUiState
}
