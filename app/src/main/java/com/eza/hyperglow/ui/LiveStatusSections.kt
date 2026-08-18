package com.eza.hyperglow.ui

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.eza.hyperglow.R
import com.eza.hyperglow.aod.XiaomiRuntimeSupportState
import com.eza.hyperglow.producer.LyricProducerState
import com.eza.hyperglow.producer.LyricProducers
import com.eza.hyperglow.producer.LyricSource
import com.eza.hyperglow.producer.ProducerConnection
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.window.WindowDialog

/** 实时状态与歌词源区块:正在播放/投影状态卡、歌词源选择、连接引导与源选择对话框。 */

// --- Phase 3 UI: lyric source + live status + Lyricon setup hint ---

/**
 * Collects the arbiter's [active] state in a Compose-stable way. Returns null before the
 * arbiter is started (e.g. in previews) — `collectAsState` is still invoked unconditionally
 * so Compose's remember-slot invariants hold.
 */
@Composable
internal fun collectActiveState(): androidx.compose.runtime.State<LyricProducerState?> {
    val arbiter = LyricProducers.arbiterOrNull()
    val flow = remember(arbiter) {
        arbiter?.active
            ?: kotlinx.coroutines.flow.MutableStateFlow<LyricProducerState?>(null)
    }
    return flow.collectAsState()
}

@Composable
private fun collectPreference(): androidx.compose.runtime.State<LyricSource> {
    val arbiter = LyricProducers.arbiterOrNull()
    val flow = remember(arbiter) {
        arbiter?.preference
            ?: kotlinx.coroutines.flow.MutableStateFlow(LyricSource.SPICY)
    }
    return flow.collectAsState()
}

@Composable
private fun collectActiveSource(): androidx.compose.runtime.State<LyricSource?> {
    val arbiter = LyricProducers.arbiterOrNull()
    val flow = remember(arbiter) {
        arbiter?.activeSource
            ?: kotlinx.coroutines.flow.MutableStateFlow<LyricSource?>(null)
    }
    return flow.collectAsState()
}

@Composable
private fun collectConnection(source: LyricSource): androidx.compose.runtime.State<ProducerConnection> {
    val arbiter = LyricProducers.arbiterOrNull()
    val flow = remember(arbiter, source) {
        arbiter?.connection(source)
            ?: kotlinx.coroutines.flow.MutableStateFlow(ProducerConnection.DISCONNECTED)
    }
    return flow.collectAsState()
}

// 判断模块当前是否处于可用的运行状态(与 runtimeProfileAvailable 一致)。
internal fun resolveModuleWorking(state: XiaomiRuntimeSupportState): Boolean =
    state == XiaomiRuntimeSupportState.VERIFIED_PROFILE ||
        state == XiaomiRuntimeSupportState.VERIFIED_PROFILE_MISSING_SYMBOLS ||
        state == XiaomiRuntimeSupportState.EXPERIMENTAL_ACTIVE

/**
 * Live status card: surfaces the track the active source is currently reporting, plus the
 * projection state (showing / paused / idle), so the user can tell at a glance whether
 * HyperGlow is actively projecting lyrics.
 */
@Composable
internal fun LiveStatusSection() {
    val context = LocalContext.current
    val activeState by collectActiveState()
    val activeSource by collectActiveSource()
    val preference by collectPreference()
    val active = activeState
    // 显示"正在播放"时标注实际在用的歌词源:若回退到了其他源,则显示回退到的那个,
    // 而不是用户当前选中的源。activeSource 为 null 时回退到选中源(理论上不会发生)。
    val source = activeSource ?: preference
    SettingsCard {
        BasicComponent(
            title = stringResource(R.string.label_now_playing),
            summary = active?.let { nowPlayingSummary(context, it, source) }
                ?: context.getString(R.string.summary_no_track)
        )
        BasicComponent(
            title = stringResource(R.string.label_projection_state),
            summary = projectionStateSummary(context, active)
        )
    }
}

/**
 * Lyric source card: shows the selected source and its connection state, with a tap target
 * to switch between Spicy EX and Lyricon.
 */
@Composable
internal fun LyricSourceSection(onOpenSourceDialog: () -> Unit) {
    val context = LocalContext.current
    val preference by collectPreference()
    // 按实际选择的源读取其真实连接状态,与 SourceSetupHint 保持一致,避免"上面已连接、
    // 下面未连接"的矛盾提示。Spicy 也读取 producer 的真实连接(无 Spicy EX/数据过期时为
    // DISCONNECTED),不再硬编码 CONNECTED。
    val activeConnection = when (preference) {
        LyricSource.LYRICON -> collectConnection(LyricSource.LYRICON).value
        LyricSource.SUPERLYRIC -> collectConnection(LyricSource.SUPERLYRIC).value
        LyricSource.LYRICINFO -> collectConnection(LyricSource.LYRICINFO).value
        LyricSource.SPICY -> collectConnection(LyricSource.SPICY).value
    }
    SettingsCard {
        ArrowPreference(
            title = stringResource(R.string.label_active_source),
            summary = context.getString(
                R.string.summary_active_source,
                lyricSourceLabel(context, preference),
                connectionLabel(context, activeConnection, preference)
            ),
            onClick = onOpenSourceDialog
        )
    }
}

/**
 * Conditional setup hint for the source-selectable Xposed/music sources (Lyricon, SuperLyric,
 * LyricInfo): only rendered when the user picked that source but it isn't connected. Hidden once
 * connected/reconnecting or when Spicy is the active source.
 */
@Composable
internal fun SourceSetupHint() {
    val context = LocalContext.current
    val preference by collectPreference()
    // 仅当源实际未连接时才显示"未连接"引导,与上方 LyricSourceSection 的连接状态保持一致,
    // 避免出现"上面已连接、下面未连接"的矛盾提示。Spicy 也读取真实连接。
    val connection = when (preference) {
        LyricSource.LYRICON -> collectConnection(LyricSource.LYRICON).value
        LyricSource.SUPERLYRIC -> collectConnection(LyricSource.SUPERLYRIC).value
        LyricSource.LYRICINFO -> collectConnection(LyricSource.LYRICINFO).value
        LyricSource.SPICY -> collectConnection(LyricSource.SPICY).value
    }
    if (connection == ProducerConnection.CONNECTED ||
        connection == ProducerConnection.RECONNECTED
    ) {
        return
    }
    when (preference) {
        LyricSource.LYRICON -> XposedSourceHint(
            titleRes = R.string.title_lyricon_setup,
            summaryRes = R.string.summary_lyricon_not_connected,
            guideActionRes = R.string.action_lyricon_guide,
            guideUrl = LYRICON_GUIDE_URL
        )

        LyricSource.SUPERLYRIC -> XposedSourceHint(
            titleRes = R.string.title_superlyric_setup,
            summaryRes = R.string.summary_superlyric_not_connected,
            guideActionRes = R.string.action_superlyric_guide,
            guideUrl = SUPERLYRIC_GUIDE_URL
        )

        LyricSource.LYRICINFO -> LyricInfoHint(context)

        LyricSource.SPICY -> SpicyHint(context)
    }
}

/**
 * Setup hint for Spicy EX when it is selected but not publishing lyrics (Spicy EX not installed,
 * or no active Spotify playback). Points the user to install Spicy EX / open Spotify.
 */
@Composable
private fun SpicyHint(context: android.content.Context) {
    SettingsCard {
        BasicComponent(
            title = stringResource(R.string.title_spicy_setup),
            summary = stringResource(R.string.summary_spicy_not_connected)
        )
        ArrowPreference(
            title = stringResource(R.string.action_download_spicy_ex),
            onClick = { openExternalUrl(context, SPICY_EX_GITHUB_URL) }
        )
        ArrowPreference(
            title = stringResource(R.string.action_open_spotify_for_spicy),
            onClick = {
                val launchIntent = context.packageManager
                    .getLaunchIntentForPackage("com.spotify.music")
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_spotify_not_installed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}

/**
 * Setup hint for Xposed-module-based lyric sources (Lyricon, SuperLyric): shows the module must be
 * activated in SystemUI, with a direct shortcut to the LSPosed manager plus the setup guide.
 */
@Composable
private fun XposedSourceHint(
    titleRes: Int,
    summaryRes: Int,
    guideActionRes: Int,
    guideUrl: String
) {
    val context = LocalContext.current
    // 该引导是"如何配置该源"的说明，与连接状态无关，选中即显示。
    // 注意：SuperLyric/Lyricon 的 connection 在模块服务可达时即为 CONNECTED
    // （即使未真正配置好），不能据此隐藏引导。
    SettingsCard {
        BasicComponent(
            title = stringResource(titleRes),
            summary = stringResource(summaryRes)
        )
        ArrowPreference(
            title = stringResource(R.string.action_open_lsposed),
            onClick = { openLsposedManager(context) }
        )
        ArrowPreference(
            title = stringResource(guideActionRes),
            onClick = { openExternalUrl(context, guideUrl) }
        )
    }
}

private fun openLsposedManager(context: android.content.Context) {
    // 用 getLaunchIntentForPackage 获取带 LAUNCHER category 的完整启动 Intent。
    // 直接 Intent(ACTION_MAIN).setPackage(...) 不带任何 category,隐式解析仅匹配声明了
    // android.intent.category.DEFAULT 的 filter;而 LSPosed 管理器的 MainActivity 只声明了
    // LAUNCHER、没有 DEFAULT,导致 startActivity 抛 ActivityNotFoundException,被误报为
    // 「此设备未安装 LSPosed」。getLaunchIntentForPackage 返回带 LAUNCHER 的完整 Intent,
    // 可正确解析打开。
    val launchIntent = runCatching {
        context.packageManager.getLaunchIntentForPackage(LSPOSED_MANAGER_PACKAGE)
    }.getOrNull()
    val opened = launchIntent != null && runCatching {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
    }.isSuccess
    if (!opened) {
        Toast.makeText(
            context,
            context.getString(R.string.toast_lsposed_not_installed),
            Toast.LENGTH_LONG
        ).show()
    }
}

@Composable
private fun LyricInfoHint(context: android.content.Context) {
    val preference by collectPreference()
    val connection by collectConnection(preference)
    if (connection == ProducerConnection.CONNECTED ||
        connection == ProducerConnection.RECONNECTED
    ) return
    SettingsCard {
        BasicComponent(
            title = stringResource(R.string.title_lyricinfo_setup),
            summary = stringResource(R.string.summary_lyricinfo_not_connected)
        )
        ArrowPreference(
            title = stringResource(R.string.action_lyricinfo_notification_access),
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                )
            }
        )
        ArrowPreference(
            title = stringResource(R.string.action_lyricinfo_guide),
            onClick = { openExternalUrl(context, LYRICINFO_GUIDE_URL) }
        )
    }
}

@Composable
internal fun LyricSourcePickerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val arbiter = LyricProducers.arbiterOrNull()
    val current by collectPreference()
    WindowDialog(
        title = stringResource(R.string.section_lyric_source),
        summary = stringResource(R.string.dialog_lyric_source_summary),
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column {
            LyricSource.entries.forEach { source ->
                RadioButtonPreference(
                    lyricSourceLabel(context, source),
                    current == source,
                    {
                        arbiter?.setPreference(source, context)
                        onDismiss()
                    }
                )
            }
        }
    }
}

private fun lyricSourceLabel(context: android.content.Context, source: LyricSource): String =
    context.getString(
        when (source) {
            LyricSource.SPICY -> R.string.lyric_source_spicy
            LyricSource.LYRICON -> R.string.lyric_source_lyricon
            LyricSource.SUPERLYRIC -> R.string.lyric_source_superlyric
            LyricSource.LYRICINFO -> R.string.lyric_source_lyricinfo
        }
    )

private fun connectionLabel(
    context: android.content.Context,
    connection: ProducerConnection,
    source: LyricSource
): String {
    // Lyricon needs API >= 27 (O_MR1); below that the producer is a no-op.
    if (source == LyricSource.LYRICON && Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
        return context.getString(R.string.source_status_unavailable_api)
    }
    return context.getString(
        when (connection) {
            ProducerConnection.CONNECTED -> R.string.source_status_connected
            ProducerConnection.RECONNECTED -> R.string.source_status_reconnected
            ProducerConnection.CONNECT_TIMEOUT -> R.string.source_status_connect_timeout
            ProducerConnection.DISCONNECTED -> R.string.source_status_disconnected
        }
    )
}

private fun nowPlayingSummary(
    context: android.content.Context,
    state: LyricProducerState,
    source: LyricSource
): String {
    val title = state.title.ifBlank { context.getString(R.string.summary_no_track) }
    val artist = state.artist.trim()
    return when {
        artist.isBlank() -> "$title  ·  ${lyricSourceLabel(context, source)}"
        else -> "$title · $artist  ·  ${lyricSourceLabel(context, source)}"
    }
}

private fun projectionStateSummary(
    context: android.content.Context,
    active: LyricProducerState?
): String {
    if (active == null) return context.getString(R.string.projection_state_idle)
    return when {
        !active.playing -> context.getString(R.string.projection_state_paused)
        active.line.isNotBlank() || active.hasTimedLyrics ->
            context.getString(R.string.projection_state_active)
        else -> context.getString(R.string.projection_state_idle)
    }
}

private const val SPICY_EX_GITHUB_URL = "https://github.com/amarinne/spicy-ex/releases"
private const val LYRICON_GUIDE_URL = "https://github.com/amarinne/hyperglow#lyricon-setup"
private const val SUPERLYRIC_GUIDE_URL = "https://github.com/HChenX/SuperLyric#readme"
private const val LYRICINFO_GUIDE_URL = "https://github.com/limczhh/LyricInfo#readme"
private const val LSPOSED_MANAGER_PACKAGE = "org.lsposed.manager"
