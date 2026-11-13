package com.eza.hyperglow.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** LazyColumn 分组锚点:搜索结果据此滚动到对应分组。 */
internal object GlobalSearchAnchor {
    const val STATUS_HOME = "status_home"
    const val STATUS_LIVE = "status_live"
    const val STATUS_SOURCE = "status_source"
    const val STATUS_RUNTIME = "status_runtime"
    const val STATUS_PERMISSION = "status_permission"
    const val SETTINGS_ENHANCEMENT = "settings_enhancement"
    const val SETTINGS_PLAYBACK = "settings_playback"
    const val SETTINGS_BACKUP = "settings_backup"
    const val APP_LANGUAGE = "app_language"
    const val APP_NAVIGATION = "app_navigation"
    const val APP_APPEARANCE = "app_appearance"
    const val APP_DEVELOPER = "app_developer"
    const val APP_SYSTEM = "app_system"
    // 歌词外观编辑器内部的分组锚点(由 LyricAppearanceSection 的 LazyColumn 登记)。
    const val APPEARANCE_HEADER = "appearance_header"
    const val APPEARANCE_PLACEMENT = "appearance_placement"
    const val APPEARANCE_TEXT_LANGUAGE = "appearance_text_language"
    const val APPEARANCE_EFFECTS = "appearance_effects"
    const val APPEARANCE_SPECTRUM = "appearance_spectrum"
    const val APPEARANCE_SPRITES = "appearance_sprites"
    const val APPEARANCE_COLORS = "appearance_colors"
    const val APPEARANCE_LOCKSCREEN_CARD = "appearance_lockscreen_card"
    const val APPEARANCE_BOTH_SURFACES = "appearance_both_surfaces"
}

/**
 * 全局搜索条目:只做「找到并带路」,不复制设置本身的交互。
 * [appearanceSection] 对应设置页顶部分段(0=息屏外观 1=锁屏外观 2=其他设置),
 * [anchor] 非空时先切页再滚到该分组或外观编辑器内的具体分组。
 * [requiresExperimental] 标记只在「实验模式可用」时才渲染的条目,避免搜到却找不到按钮。
 */
internal data class GlobalSearchEntry(
    val titleRes: Int,
    val sectionLabelRes: Int,
    val tab: SettingsTab,
    val summaryRes: Int? = null,
    val keywords: String = "",
    val appearanceSection: Int? = null,
    val anchor: String? = null,
    val requiresExperimental: Boolean = false
)

private fun SettingsTab.navLabelRes(): Int = when (this) {
    SettingsTab.STATUS -> R.string.nav_status
    SettingsTab.SETTINGS -> R.string.nav_settings
    SettingsTab.APP -> R.string.nav_app_settings
    SettingsTab.ABOUT -> R.string.nav_about
}

/** 关于页不参与搜索(关于页本身也没有搜索入口)。 */
internal val GLOBAL_SEARCH_ENTRIES: List<GlobalSearchEntry> = listOf(
    // ---- 状态 ----
    GlobalSearchEntry(
        titleRes = R.string.section_home_status,
        sectionLabelRes = R.string.section_home_status,
        tab = SettingsTab.STATUS,
        keywords = "首页 概览 状态 hero home overview status",
        anchor = GlobalSearchAnchor.STATUS_HOME
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_live_status,
        sectionLabelRes = R.string.section_live_status,
        tab = SettingsTab.STATUS,
        keywords = "实时 预览 歌词 状态 preview live lyrics",
        anchor = GlobalSearchAnchor.STATUS_LIVE
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_lyric_source,
        sectionLabelRes = R.string.section_lyric_source,
        tab = SettingsTab.STATUS,
        keywords = "歌词 来源 选源 spotify lyricon superlyric lyric source",
        anchor = GlobalSearchAnchor.STATUS_SOURCE
    ),
    GlobalSearchEntry(
        titleRes = R.string.label_active_source,
        sectionLabelRes = R.string.section_lyric_source,
        tab = SettingsTab.STATUS,
        keywords = "当前 歌词源 连接 状态 active source connection",
        anchor = GlobalSearchAnchor.STATUS_SOURCE
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_filter_non_music_sources,
        sectionLabelRes = R.string.section_lyric_source,
        tab = SettingsTab.STATUS,
        summaryRes = R.string.summary_filter_non_music_sources,
        keywords = "过滤 非音乐 来源 filter non music",
        anchor = GlobalSearchAnchor.STATUS_SOURCE
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_hide_credit_lines,
        sectionLabelRes = R.string.section_lyric_source,
        tab = SettingsTab.STATUS,
        summaryRes = R.string.summary_hide_credit_lines,
        keywords = "隐藏 制作 词曲 credit lines",
        anchor = GlobalSearchAnchor.STATUS_SOURCE
    ),
    GlobalSearchEntry(
        titleRes = R.string.plugin_management_title,
        sectionLabelRes = R.string.section_lyric_source,
        tab = SettingsTab.STATUS,
        summaryRes = R.string.plugin_management_summary,
        keywords = "插件 管理 安装 plugin",
        anchor = GlobalSearchAnchor.STATUS_SOURCE
    ),
    GlobalSearchEntry(
        titleRes = R.string.action_report_problem,
        sectionLabelRes = R.string.section_runtime_status,
        tab = SettingsTab.STATUS,
        keywords = "反馈 诊断 兼容性 报告 问题 report diagnostics",
        anchor = GlobalSearchAnchor.STATUS_RUNTIME
    ),
    GlobalSearchEntry(
        titleRes = R.string.label_notification_permission,
        sectionLabelRes = R.string.section_permission_status,
        tab = SettingsTab.STATUS,
        keywords = "通知 权限 notification permission",
        anchor = GlobalSearchAnchor.STATUS_PERMISSION
    ),
    GlobalSearchEntry(
        titleRes = R.string.label_battery_optimization,
        sectionLabelRes = R.string.section_permission_status,
        tab = SettingsTab.STATUS,
        keywords = "电池 优化 白名单 省电 battery optimization",
        anchor = GlobalSearchAnchor.STATUS_PERMISSION
    ),
    GlobalSearchEntry(
        titleRes = R.string.label_root_permission,
        sectionLabelRes = R.string.section_permission_status,
        tab = SettingsTab.STATUS,
        keywords = "root 权限 超级用户 su",
        anchor = GlobalSearchAnchor.STATUS_PERMISSION
    ),
    GlobalSearchEntry(
        titleRes = R.string.label_foreground_service,
        sectionLabelRes = R.string.section_permission_status,
        tab = SettingsTab.STATUS,
        keywords = "前台 服务 运行 foreground service",
        anchor = GlobalSearchAnchor.STATUS_PERMISSION
    ),
    GlobalSearchEntry(
        titleRes = R.string.action_open_root_manager,
        sectionLabelRes = R.string.section_permission_status,
        tab = SettingsTab.STATUS,
        keywords = "root 管理器 magisk kernelsu apatch manager",
        anchor = GlobalSearchAnchor.STATUS_PERMISSION
    ),

    // ---- 设置 · 其他设置 ----
    GlobalSearchEntry(
        titleRes = R.string.title_lyric_enhancement,
        sectionLabelRes = R.string.settings_section_other,
        tab = SettingsTab.SETTINGS,
        summaryRes = R.string.summary_lyric_enhancement,
        keywords = "歌词 增强 插件 翻译 lyric enhancement",
        appearanceSection = 2,
        anchor = GlobalSearchAnchor.SETTINGS_ENHANCEMENT
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_pause_show_content,
        sectionLabelRes = R.string.section_playback_behavior,
        tab = SettingsTab.SETTINGS,
        summaryRes = R.string.summary_pause_show_content,
        keywords = "暂停 显示 内容 pause",
        appearanceSection = 2,
        anchor = GlobalSearchAnchor.SETTINGS_PLAYBACK
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_after_spotify_pauses,
        sectionLabelRes = R.string.section_playback_behavior,
        tab = SettingsTab.SETTINGS,
        keywords = "暂停 后 停留 时长 spotify pause linger",
        appearanceSection = 2,
        anchor = GlobalSearchAnchor.SETTINGS_PLAYBACK
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_export_config,
        sectionLabelRes = R.string.section_config_backup,
        tab = SettingsTab.SETTINGS,
        summaryRes = R.string.summary_export_config,
        keywords = "导出 备份 配置 export backup",
        appearanceSection = 2,
        anchor = GlobalSearchAnchor.SETTINGS_BACKUP
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_import_config,
        sectionLabelRes = R.string.section_config_backup,
        tab = SettingsTab.SETTINGS,
        summaryRes = R.string.summary_import_config,
        keywords = "导入 恢复 配置 import restore",
        appearanceSection = 2,
        anchor = GlobalSearchAnchor.SETTINGS_BACKUP
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_reset_defaults,
        sectionLabelRes = R.string.section_config_backup,
        tab = SettingsTab.SETTINGS,
        summaryRes = R.string.summary_reset_defaults,
        keywords = "重置 默认 恢复出厂 reset defaults",
        appearanceSection = 2,
        anchor = GlobalSearchAnchor.SETTINGS_BACKUP
    ),

    // ---- 应用设置 ----
    GlobalSearchEntry(
        titleRes = R.string.section_language,
        sectionLabelRes = R.string.section_language,
        tab = SettingsTab.APP,
        keywords = "语言 中文 英文 界面 language locale",
        anchor = GlobalSearchAnchor.APP_LANGUAGE
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_interface_language,
        sectionLabelRes = R.string.section_language,
        tab = SettingsTab.APP,
        keywords = "界面 语言 中文 英文 interface language",
        anchor = GlobalSearchAnchor.APP_LANGUAGE
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_predictive_back,
        sectionLabelRes = R.string.section_app_navigation,
        tab = SettingsTab.APP,
        summaryRes = R.string.summary_predictive_back,
        keywords = "预测 返回 手势 navigation back gesture",
        anchor = GlobalSearchAnchor.APP_NAVIGATION
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_back_trigger_threshold,
        sectionLabelRes = R.string.section_app_navigation,
        tab = SettingsTab.APP,
        summaryRes = R.string.summary_back_trigger_threshold,
        keywords = "返回 阈值 灵敏度 back threshold",
        anchor = GlobalSearchAnchor.APP_NAVIGATION
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_app_appearance,
        sectionLabelRes = R.string.section_app_appearance,
        tab = SettingsTab.APP,
        keywords = "主题 背景 图片 字体 颜色 外观 theme background font",
        anchor = GlobalSearchAnchor.APP_APPEARANCE
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_experimental_mode,
        sectionLabelRes = R.string.section_developer_options,
        tab = SettingsTab.APP,
        summaryRes = R.string.summary_experimental_mode,
        keywords = "实验 模式 尝鲜 experimental",
        anchor = GlobalSearchAnchor.APP_DEVELOPER,
        requiresExperimental = true
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_force_lyric_provider,
        sectionLabelRes = R.string.section_developer_options,
        tab = SettingsTab.APP,
        summaryRes = R.string.summary_force_lyric_provider,
        keywords = "强制 歌词 提供者 force provider",
        anchor = GlobalSearchAnchor.APP_DEVELOPER,
        requiresExperimental = true
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_aod_debug_show_canvas_frame,
        sectionLabelRes = R.string.section_developer_options,
        tab = SettingsTab.APP,
        summaryRes = R.string.summary_aod_debug_show_canvas_frame,
        keywords = "息屏 调试 边框 canvas frame debug",
        anchor = GlobalSearchAnchor.APP_DEVELOPER
    ),
    GlobalSearchEntry(
        titleRes = R.string.label_diagnostic_logging,
        sectionLabelRes = R.string.section_developer_options,
        tab = SettingsTab.APP,
        keywords = "诊断 日志 logging",
        anchor = GlobalSearchAnchor.APP_DEVELOPER
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_log_retention,
        sectionLabelRes = R.string.section_developer_options,
        tab = SettingsTab.APP,
        keywords = "日志 保留 时长 retention",
        anchor = GlobalSearchAnchor.APP_DEVELOPER
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_log_level,
        sectionLabelRes = R.string.section_developer_options,
        tab = SettingsTab.APP,
        keywords = "日志 级别 level",
        anchor = GlobalSearchAnchor.APP_DEVELOPER
    ),
    GlobalSearchEntry(
        titleRes = R.string.action_export_logs,
        sectionLabelRes = R.string.section_developer_options,
        tab = SettingsTab.APP,
        summaryRes = R.string.summary_export_logs,
        keywords = "导出 日志 export logs",
        anchor = GlobalSearchAnchor.APP_DEVELOPER
    ),
    GlobalSearchEntry(
        titleRes = R.string.action_clear_logs,
        sectionLabelRes = R.string.section_developer_options,
        tab = SettingsTab.APP,
        keywords = "清空 清除 日志 clear logs",
        anchor = GlobalSearchAnchor.APP_DEVELOPER
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_foreground_notification,
        sectionLabelRes = R.string.section_system_integration,
        tab = SettingsTab.APP,
        summaryRes = R.string.summary_foreground_notification,
        keywords = "常驻 通知 前台 服务 foreground notification",
        anchor = GlobalSearchAnchor.APP_SYSTEM
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_hide_background_card,
        sectionLabelRes = R.string.section_system_integration,
        tab = SettingsTab.APP,
        summaryRes = R.string.summary_hide_background_card,
        keywords = "隐藏 后台 卡片 最近任务 recents",
        anchor = GlobalSearchAnchor.APP_SYSTEM
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_hide_launcher_icon,
        sectionLabelRes = R.string.section_system_integration,
        tab = SettingsTab.APP,
        summaryRes = R.string.summary_hide_launcher_icon,
        keywords = "隐藏 桌面 图标 launcher icon",
        anchor = GlobalSearchAnchor.APP_SYSTEM
    ),

    // ---- 歌词外观(切分段并滚到编辑器内的具体分组) ----
    GlobalSearchEntry(
        titleRes = R.string.setting_show_aod,
        sectionLabelRes = R.string.section_appearance,
        tab = SettingsTab.SETTINGS,
        keywords = "息屏 显示 歌词 开关 aod show",
        appearanceSection = 0,
        anchor = GlobalSearchAnchor.APPEARANCE_HEADER
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_show_lockscreen,
        sectionLabelRes = R.string.section_appearance,
        tab = SettingsTab.SETTINGS,
        keywords = "锁屏 显示 歌词 开关 lockscreen show",
        appearanceSection = 1,
        anchor = GlobalSearchAnchor.APPEARANCE_HEADER
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_placement,
        sectionLabelRes = R.string.section_placement,
        tab = SettingsTab.SETTINGS,
        keywords = "位置 布局 对齐 偏移 placement layout offset",
        appearanceSection = 0,
        anchor = GlobalSearchAnchor.APPEARANCE_PLACEMENT
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_text_language,
        sectionLabelRes = R.string.section_text_language,
        tab = SettingsTab.SETTINGS,
        keywords = "文字 字体 字号 语言 时间 歌曲名 text font size language",
        appearanceSection = 0,
        anchor = GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_effects,
        sectionLabelRes = R.string.section_effects,
        tab = SettingsTab.SETTINGS,
        keywords = "效果 动效 动画 过渡 effects animation",
        appearanceSection = 0,
        anchor = GlobalSearchAnchor.APPEARANCE_EFFECTS
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_spectrum,
        sectionLabelRes = R.string.section_spectrum,
        tab = SettingsTab.SETTINGS,
        keywords = "频谱 波形 节奏 spectrum",
        appearanceSection = 0,
        anchor = GlobalSearchAnchor.APPEARANCE_SPECTRUM
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_custom_sprites,
        sectionLabelRes = R.string.section_custom_sprites,
        tab = SettingsTab.SETTINGS,
        keywords = "贴纸 素材 自定义 sprites stickers",
        appearanceSection = 0,
        anchor = GlobalSearchAnchor.APPEARANCE_SPRITES
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_colors,
        sectionLabelRes = R.string.section_colors,
        tab = SettingsTab.SETTINGS,
        keywords = "颜色 配色 色调 渐变 colors palette",
        appearanceSection = 0,
        anchor = GlobalSearchAnchor.APPEARANCE_COLORS
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_lockscreen_card,
        sectionLabelRes = R.string.section_lockscreen_card,
        tab = SettingsTab.SETTINGS,
        keywords = "锁屏 媒体卡 卡片 lockscreen card",
        appearanceSection = 1,
        anchor = GlobalSearchAnchor.APPEARANCE_LOCKSCREEN_CARD
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_both_surfaces,
        sectionLabelRes = R.string.section_both_surfaces,
        tab = SettingsTab.SETTINGS,
        keywords = "通用 全局 两面 both surfaces",
        appearanceSection = 0,
        anchor = GlobalSearchAnchor.APPEARANCE_BOTH_SURFACES
    )
)

/** 单个查询词对一条索引的匹配得分;null 表示未命中。分档让「标题包含」排在模糊命中之前。 */
private fun tokenScore(token: String, title: String, haystack: String): Int? {
    val titleIndex = title.indexOf(token)
    if (titleIndex >= 0) return 1200 - titleIndex.coerceAtMost(100)
    val hayIndex = haystack.indexOf(token)
    if (hayIndex >= 0) return 900 - hayIndex.coerceAtMost(100)
    // 字符按顺序出现即算命中(如「歌源」命中「歌词来源」),覆盖漏字/缩写。
    if (isSubsequence(token, title)) return 560
    if (isSubsequence(token, haystack)) return 420
    // 英文拼写容错:任意单词编辑距离 ≤1(如 sorce → source)。
    if (token.length >= 3 &&
        haystack.split(Regex("[^a-z0-9]+")).any { word ->
            word.length >= 3 && editDistanceAtMostOne(token, word)
        }
    ) {
        return 300
    }
    return null
}

private fun isSubsequence(needle: String, haystack: String): Boolean {
    if (needle.isEmpty()) return false
    var index = 0
    for (char in haystack) {
        if (char == needle[index]) {
            index++
            if (index == needle.length) return true
        }
    }
    return false
}

/** 编辑距离是否 ≤1(允许一次插入/删除/替换);用于英文关键词的模糊容错。 */
private fun editDistanceAtMostOne(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) {
            i++
            j++
            continue
        }
        if (++edits > 1) return false
        when {
            a.length > b.length -> i++
            a.length < b.length -> j++
            else -> {
                i++
                j++
            }
        }
    }
    return edits + (a.length - i) + (b.length - j) <= 1
}

/**
 * 标题/说明/分组/页面/关键词全部参与匹配,支持空格分词与模糊命中
 * (连续子串 > 按顺序出现的字符 > 英文编辑距离 ≤1);按得分降序返回。
 */
internal fun filterGlobalSearchEntries(
    context: Context,
    query: String,
    experimentalEligible: Boolean = true
): List<GlobalSearchEntry> {
    val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    return GLOBAL_SEARCH_ENTRIES.mapNotNull { entry ->
        if (entry.requiresExperimental && !experimentalEligible) return@mapNotNull null
        val title = context.getString(entry.titleRes).lowercase()
        val haystack = buildString {
            append(title)
            entry.summaryRes?.let { append(' ').append(context.getString(it)) }
            append(' ').append(context.getString(entry.sectionLabelRes))
            append(' ').append(context.getString(entry.tab.navLabelRes()))
            append(' ').append(entry.keywords)
        }.lowercase()
        var score = 0
        for (token in tokens) {
            score += tokenScore(token, title, haystack) ?: return@mapNotNull null
        }
        entry to score
    }.sortedByDescending { it.second }.map { it.first }
}

/**
 * 输入补全:从条目标题里挑出包含当前输入的候选,前缀命中优先,去重后最多 [limit] 条。
 * 点选候选即把输入补成完整标题。
 */
internal fun globalSearchCompletions(
    context: Context,
    query: String,
    limit: Int = 6,
    experimentalEligible: Boolean = true
): List<String> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return emptyList()
    return GLOBAL_SEARCH_ENTRIES
        .filter { experimentalEligible || !it.requiresExperimental }
        .map { context.getString(it.titleRes) }
        .distinct()
        .filter { title ->
            val lower = title.lowercase()
            lower != q && lower.contains(q)
        }
        .sortedBy { title -> title.lowercase().indexOf(q) }
        .take(limit)
}

/**
 * 全屏搜索覆盖层:搜索范围覆盖除关于页外的所有设置页;
 * 选中结果后由调用方负责切页并滚动到对应分组。
 */
@Composable
internal fun GlobalSearchOverlay(
    onDismiss: () -> Unit,
    onSelect: (GlobalSearchEntry) -> Unit,
    experimentalEligible: Boolean = true
) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val results = remember(query, experimentalEligible) {
        filterGlobalSearchEntries(context, query, experimentalEligible)
    }
    val completions = remember(query, experimentalEligible) {
        globalSearchCompletions(context, query, experimentalEligible = experimentalEligible)
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Scaffold(
        containerColor = appOverlaySurfaceColor(),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.search_title),
                onBack = onDismiss,
                overlay = true
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                singleLine = true,
                label = stringResource(R.string.search_placeholder),
                useLabelAsPlaceholder = true,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
            )
            if (query.isNotBlank() && completions.isNotEmpty()) {
                // 输入补全:点一下把输入补成完整标题,同时保留关键词搜索入口。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    completions.forEach { completion ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(appCardContainerColor())
                                .clickable { query = completion }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = completion,
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.onSurfaceContainerHighest
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            when {
                query.isBlank() -> Unit
                results.isEmpty() -> Text(
                    text = stringResource(R.string.search_empty),
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                ) {
                    item {
                        SettingsCard {
                            results.forEach { entry ->
                                ArrowPreference(
                                    title = stringResource(entry.titleRes),
                                    summary = stringResource(
                                        R.string.search_scope,
                                        stringResource(entry.tab.navLabelRes()),
                                        stringResource(entry.sectionLabelRes)
                                    ),
                                    onClick = { onSelect(entry) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
