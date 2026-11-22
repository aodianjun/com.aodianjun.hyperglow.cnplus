package com.eza.hyperglow.ui

import android.content.Context
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import java.util.Locale
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
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
    const val ABOUT_HOME = "about_home"
    const val ABOUT_LINKS = "about_links"
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

    /**
     * 歌词外观编辑器内部的锚点。这些索引登记在外观编辑器的 `appearanceListState` 上,
     * 其余锚点登记在对应页面的 LazyColumn 上。
     */
    val APPEARANCE_ANCHORS: Set<String> = setOf(
        APPEARANCE_HEADER,
        APPEARANCE_PLACEMENT,
        APPEARANCE_TEXT_LANGUAGE,
        APPEARANCE_EFFECTS,
        APPEARANCE_SPECTRUM,
        APPEARANCE_SPRITES,
        APPEARANCE_COLORS,
        APPEARANCE_LOCKSCREEN_CARD,
        APPEARANCE_BOTH_SURFACES
    )

    fun isAppearanceAnchor(anchor: String?): Boolean =
        anchor != null && anchor in APPEARANCE_ANCHORS
}

/**
 * 外观编辑器里开关/滑杆的行级 key。UI 与搜索索引共用同一常量,避免手写字符串漂移。
 * 下拉项(位置/字号/字体等)由 [GlobalSearchRowAnchors.appearanceChoiceKey] 自动生成。
 */
internal object SearchRowKeys {
    const val STATUS_CONFIG_PRESET = "status_config_preset"
    const val SETTINGS_CONFIG_PRESET = "settings_config_preset"
    const val APPEARANCE_SHOW_AOD = "appearance_show_aod"
    const val APPEARANCE_SHOW_LOCKSCREEN = "appearance_show_lockscreen"
    const val APPEARANCE_CUSTOM_POSITION = "appearance_custom_position"
    const val APPEARANCE_DUET_ALIGNMENT = "appearance_duet_alignment"
    const val APPEARANCE_DUET_CONCURRENT = "appearance_duet_concurrent"
    const val APPEARANCE_INTERLUDE_COUNTDOWN = "appearance_interlude_countdown"
    const val APPEARANCE_PURE_MUSIC_DISPLAY = "appearance_pure_music_display"
    const val APPEARANCE_PURE_MUSIC_TEXT = "appearance_pure_music_text"
    const val APPEARANCE_TAIL_TIMEOUT_DELAY = "appearance_tail_timeout_delay"
    const val APPEARANCE_DUET_MARKERS = "appearance_duet_markers"
    const val APPEARANCE_LYRIC_TIME_OFFSET = "appearance_lyric_time_offset"
    const val APPEARANCE_SECONDARY_AUTO_SIZE = "appearance_secondary_auto_size"
    const val APPEARANCE_SECONDARY_TEXT_BRIGHT = "appearance_secondary_text_bright"
    const val APPEARANCE_SECONDARY_WORD_KARAOKE = "appearance_secondary_word_karaoke"
    const val APPEARANCE_SECONDARY_NEXT_LINE = "appearance_secondary_next_line"
    const val APPEARANCE_NEXT_LINE_AUX = "appearance_next_line_aux"
    const val APPEARANCE_RUBY_VISIBLE = "appearance_ruby_visible"
    const val APPEARANCE_ADAPTIVE_SECTIONING = "appearance_adaptive_sectioning"
    const val APPEARANCE_SHOW_NEXT_LINE = "appearance_show_next_line"
    const val APPEARANCE_METADATA_VISIBLE = "appearance_metadata_visible"
    const val APPEARANCE_HIDE_ALBUM_SAME_AS_TITLE = "appearance_hide_album_same_as_title"
    const val APPEARANCE_ARTWORK_VISIBLE = "appearance_artwork_visible"
    const val APPEARANCE_ARTWORK_ADAPTIVE_SCALE = "appearance_artwork_adaptive_scale"
    const val APPEARANCE_ARTWORK_SIZE = "appearance_artwork_size"
    const val APPEARANCE_ARTWORK_SPIN = "appearance_artwork_spin"
    const val APPEARANCE_ARTWORK_SPIN_WHEN_PAUSED = "appearance_artwork_spin_when_paused"
    const val APPEARANCE_SPECTRUM_ENABLED = "appearance_spectrum_enabled"
    const val APPEARANCE_SPECTRUM_BACKGROUND = "appearance_spectrum_background"
    const val APPEARANCE_SPECTRUM_OPACITY = "appearance_spectrum_opacity"
    const val APPEARANCE_SPECTRUM_Y_OFFSET = "appearance_spectrum_y_offset"
    const val APPEARANCE_SPECTRUM_HEIGHT = "appearance_spectrum_height"
    const val APPEARANCE_SPECTRUM_SENSITIVITY = "appearance_spectrum_sensitivity"
    const val APPEARANCE_LYRIC_CARD = "appearance_lyric_card"
    const val APPEARANCE_PLAYBACK_PROGRESS = "appearance_playback_progress"
    const val APPEARANCE_CARD_TRANSPARENCY = "appearance_card_transparency"
    const val APPEARANCE_SPECTRUM_COLOR = "appearance_spectrum_color"
    const val APPEARANCE_CUSTOM_FONT = "appearance_custom_font"
    const val APPEARANCE_LYRIC_SIZE = "appearance_lyric_size"
    const val APPEARANCE_IMPORT_WORD_EFFECT = "appearance_import_word_effect"
    const val APPEARANCE_WORD_EFFECT_STUDIO = "appearance_word_effect_studio"
    const val APPEARANCE_IMPORT_SPRITE = "appearance_import_sprite"
    const val APPEARANCE_IMPORT_APPEARANCE = "appearance_import_appearance"
    const val APPEARANCE_EXPORT_APPEARANCE = "appearance_export_appearance"
    const val APPEARANCE_RESET_SURFACES = "appearance_reset_surfaces"
    const val APPEARANCE_AOD_BEHAVIOR = "appearance_aod_behavior"
    const val APPEARANCE_KEEP_LOCKSCREEN_AWAKE = "appearance_keep_lockscreen_awake"
    const val APPEARANCE_BLOCK_LOCKSCREEN_CUSTOMIZATION =
        "appearance_block_lockscreen_customization"
    const val APPEARANCE_RAISE_TO_AOD = "appearance_raise_to_aod"
    const val ABOUT_ONBOARDING = "about_onboarding"
    const val ABOUT_HELP = "about_help"
    const val ABOUT_CHANGELOG = "about_changelog"
    const val ABOUT_CONTRIBUTORS = "about_contributors"
    const val ABOUT_LICENSES = "about_licenses"
}

/**
 * 行级锚点登记表:每条可搜索的开关/选项在组合时注册一个 [BringIntoViewRequester],
 * 命中结果先滚到所属分组,再调用 [reveal] 把具体那一行带进视口。
 * 没有登记(条件未渲染/页面未组合)时由调用方回落到分组锚点。
 */
internal object GlobalSearchRowAnchors {
    private val requesters = mutableMapOf<String, BringIntoViewRequester>()

    /** 外观下拉项(位置/字号/字体等)统一按枚举名生成 key,UI 与索引共用。 */
    fun appearanceChoiceKey(kindName: String): String = "appearance_choice_$kindName"

    /** 语义色行(已唱/未唱/辉光等)按枚举名生成 key。 */
    fun appearancePaletteKey(paletteName: String): String = "appearance_palette_$paletteName"

    fun register(key: String, requester: BringIntoViewRequester) {
        requesters[key] = requester
    }

    fun unregister(key: String, requester: BringIntoViewRequester) {
        if (requesters[key] === requester) requesters.remove(key)
    }

    fun isRegistered(key: String): Boolean = requesters.containsKey(key)

    suspend fun reveal(key: String): Boolean {
        val requester = requesters[key] ?: return false
        return runCatching { requester.bringIntoView() }.isSuccess
    }
}

/**
 * 给可搜索的单行开关挂行级锚点。必须作用于 LazyColumn item 内部的具体行:
 * 分组 item 被组合后行即登记,搜索选中时可精确滚到该行。
 */
@Composable
internal fun Modifier.searchRowAnchor(key: String): Modifier {
    val requester = remember(key) { BringIntoViewRequester() }
    DisposableEffect(key, requester) {
        GlobalSearchRowAnchors.register(key, requester)
        onDispose { GlobalSearchRowAnchors.unregister(key, requester) }
    }
    return bringIntoViewRequester(requester)
}

/** 搜索命中后要打开的子页面;非空时不再按 [GlobalSearchEntry.tab] 切主页分页。 */
internal enum class GlobalSearchDestination {
    APP_APPEARANCE,
    AOD_BEHAVIOR
}

/**
 * 全局搜索条目:只做「找到并带路」,不复制设置本身的交互。
 * [appearanceSection] 对应设置页顶部分段(0=息屏外观 1=锁屏外观 2=其他设置);
 * 外观通用项留空表示「保持当前曲面」,锁屏专属项填 1。
 * [anchor] 非空时先切页再滚到该分组或外观编辑器内的具体分组。
 * [rowAnchor] 非空时在分组滚动后继续把具体开关带进视口。
 * [destination] 非空时直接打开对应子页面(应用外观 / 息屏行为)。
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
    val rowAnchor: String? = null,
    val destination: GlobalSearchDestination? = null,
    val requiresExperimental: Boolean = false
)

private fun SettingsTab.navLabelRes(): Int = when (this) {
    SettingsTab.STATUS -> R.string.nav_status
    SettingsTab.SETTINGS -> R.string.nav_settings
    SettingsTab.APP -> R.string.nav_app_settings
    SettingsTab.ABOUT -> R.string.nav_about
}

/** 关于页不参与「关于页内搜索」(关于页没有搜索入口),但其它页搜索时可以定位过去。 */
private val BASE_SEARCH_ENTRIES: List<GlobalSearchEntry> = listOf(
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
        titleRes = R.string.config_preset_title,
        sectionLabelRes = R.string.section_live_status,
        tab = SettingsTab.STATUS,
        summaryRes = R.string.config_preset_entry_summary,
        keywords = "配置 预设 云端 保存 切换 preset config cloud",
        anchor = GlobalSearchAnchor.STATUS_LIVE,
        rowAnchor = SearchRowKeys.STATUS_CONFIG_PRESET
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
        titleRes = R.string.config_preset_title,
        sectionLabelRes = R.string.section_config_backup,
        tab = SettingsTab.SETTINGS,
        summaryRes = R.string.config_preset_summary,
        keywords = "配置 预设 云端 分节 快照 preset config cloud",
        appearanceSection = 2,
        anchor = GlobalSearchAnchor.SETTINGS_BACKUP,
        rowAnchor = SearchRowKeys.SETTINGS_CONFIG_PRESET
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
        anchor = GlobalSearchAnchor.APP_DEVELOPER
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
        anchor = GlobalSearchAnchor.APPEARANCE_HEADER,
        rowAnchor = SearchRowKeys.APPEARANCE_SHOW_AOD
    ),
    GlobalSearchEntry(
        titleRes = R.string.setting_show_lockscreen,
        sectionLabelRes = R.string.section_appearance,
        tab = SettingsTab.SETTINGS,
        keywords = "锁屏 显示 歌词 开关 lockscreen show",
        appearanceSection = 1,
        anchor = GlobalSearchAnchor.APPEARANCE_HEADER,
        rowAnchor = SearchRowKeys.APPEARANCE_SHOW_LOCKSCREEN
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_placement,
        sectionLabelRes = R.string.section_placement,
        tab = SettingsTab.SETTINGS,
        keywords = "位置 布局 对齐 偏移 placement layout offset",
        anchor = GlobalSearchAnchor.APPEARANCE_PLACEMENT
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_text_language,
        sectionLabelRes = R.string.section_text_language,
        tab = SettingsTab.SETTINGS,
        keywords = "文字 字体 字号 语言 时间 歌曲名 text font size language",
        anchor = GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_effects,
        sectionLabelRes = R.string.section_effects,
        tab = SettingsTab.SETTINGS,
        keywords = "效果 动效 动画 过渡 effects animation",
        anchor = GlobalSearchAnchor.APPEARANCE_EFFECTS
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_spectrum,
        sectionLabelRes = R.string.section_spectrum,
        tab = SettingsTab.SETTINGS,
        keywords = "频谱 波形 节奏 spectrum",
        anchor = GlobalSearchAnchor.APPEARANCE_SPECTRUM
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_custom_sprites,
        sectionLabelRes = R.string.section_custom_sprites,
        tab = SettingsTab.SETTINGS,
        keywords = "贴纸 素材 自定义 sprites stickers",
        anchor = GlobalSearchAnchor.APPEARANCE_SPRITES
    ),
    GlobalSearchEntry(
        titleRes = R.string.section_colors,
        sectionLabelRes = R.string.section_colors,
        tab = SettingsTab.SETTINGS,
        keywords = "颜色 配色 色调 渐变 colors palette",
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
        anchor = GlobalSearchAnchor.APPEARANCE_BOTH_SURFACES
    )
)

/** 外观编辑器单行:标题/摘要/所属分组 + 分组锚点 + 行级锚点。 */
private fun appearanceRow(
    titleRes: Int,
    summaryRes: Int?,
    sectionLabelRes: Int,
    anchor: String,
    rowAnchor: String,
    keywords: String,
    appearanceSection: Int? = null
): GlobalSearchEntry = GlobalSearchEntry(
    titleRes = titleRes,
    sectionLabelRes = sectionLabelRes,
    tab = SettingsTab.SETTINGS,
    summaryRes = summaryRes,
    keywords = keywords,
    appearanceSection = appearanceSection,
    anchor = anchor,
    rowAnchor = rowAnchor
)

/** 外观下拉项:行级 key 由 [AodChoiceKind] 自动生成,和 AodChoiceRow 里的登记保持一致。 */
private fun appearanceChoiceRow(
    kind: AodChoiceKind,
    sectionLabelRes: Int,
    anchor: String,
    keywords: String,
    appearanceSection: Int? = null
): GlobalSearchEntry = appearanceRow(
    titleRes = kind.titleRes,
    summaryRes = null,
    sectionLabelRes = sectionLabelRes,
    anchor = anchor,
    rowAnchor = GlobalSearchRowAnchors.appearanceChoiceKey(kind.name),
    keywords = keywords,
    appearanceSection = appearanceSection
)

private fun appearancePaletteRow(color: PaletteColor): GlobalSearchEntry = appearanceRow(
    titleRes = color.titleRes,
    summaryRes = null,
    sectionLabelRes = R.string.section_colors,
    anchor = GlobalSearchAnchor.APPEARANCE_COLORS,
    rowAnchor = GlobalSearchRowAnchors.appearancePaletteKey(color.name),
    keywords = "颜色 配色 色值 已唱 未唱 下一行 歌曲信息 辉光 辅助文字 强调色 color palette"
)

/**
 * 歌词外观编辑器逐项索引:所有开关、下拉项、滑杆、颜色行与底部动作。
 * 通用项 appearanceSection 留空 = 保持用户当前编辑的曲面(息屏/锁屏);
 * 锁屏专属项固定为 1,避免切到息屏后找不到对应行。
 */
private val APPEARANCE_ROW_ENTRIES: List<GlobalSearchEntry> = listOf(
    // ---- 位置 ----
    appearanceChoiceRow(
        AodChoiceKind.POSITION,
        R.string.section_placement,
        GlobalSearchAnchor.APPEARANCE_PLACEMENT,
        "位置 布局 对齐 锚点 position placement anchor"
    ),
    appearanceChoiceRow(
        AodChoiceKind.WIDTH,
        R.string.section_placement,
        GlobalSearchAnchor.APPEARANCE_PLACEMENT,
        "宽度 宽度比例 宽度占比 width"
    ),
    appearanceChoiceRow(
        AodChoiceKind.HEIGHT,
        R.string.section_placement,
        GlobalSearchAnchor.APPEARANCE_PLACEMENT,
        "高度 高度比例 height",
        appearanceSection = 1
    ),
    appearanceRow(
        R.string.setting_custom_position,
        R.string.summary_custom_position,
        R.string.section_placement,
        GlobalSearchAnchor.APPEARANCE_PLACEMENT,
        SearchRowKeys.APPEARANCE_CUSTOM_POSITION,
        "自定义 位置 垂直 偏移 百分比 custom position vertical"
    ),
    appearanceChoiceRow(
        AodChoiceKind.OVERLAP,
        R.string.section_placement,
        GlobalSearchAnchor.APPEARANCE_PLACEMENT,
        "重叠 遮挡 避让 碰撞 overlap collision"
    ),

    // ---- 文字和语言 ----
    appearanceChoiceRow(
        AodChoiceKind.ALIGNMENT,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "对齐 居中 左对齐 右对齐 alignment center"
    ),
    appearanceRow(
        R.string.setting_duet_alignment,
        R.string.summary_duet_alignment,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_DUET_ALIGNMENT,
        "对唱 分侧 对齐 duet alignment"
    ),
    appearanceRow(
        R.string.setting_duet_concurrent,
        R.string.summary_duet_concurrent,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_DUET_CONCURRENT,
        "对唱 并发 同时显示 duet concurrent"
    ),
    appearanceRow(
        R.string.setting_interlude_countdown,
        R.string.summary_interlude_countdown,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_INTERLUDE_COUNTDOWN,
        "间奏 倒计时 圆点 interlude countdown"
    ),
    appearanceChoiceRow(
        AodChoiceKind.NO_LYRICS_BEHAVIOR,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "无歌词 纯音乐 行为 no lyrics behavior"
    ),
    appearanceRow(
        R.string.setting_pure_music_display,
        R.string.summary_pure_music_display,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_PURE_MUSIC_DISPLAY,
        "纯音乐 识别 空行 pure music"
    ),
    appearanceRow(
        R.string.setting_pure_music_text,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_PURE_MUSIC_TEXT,
        "纯音乐 文案 文本 自定义 pure music text"
    ),
    appearanceChoiceRow(
        AodChoiceKind.TAIL_TIMEOUT_BEHAVIOR,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "尾奏 超时 结束 行为 tail timeout"
    ),
    appearanceRow(
        R.string.setting_tail_timeout_delay,
        R.string.summary_tail_timeout_delay,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_TAIL_TIMEOUT_DELAY,
        "尾奏 超时 延迟 秒 tail delay"
    ),
    appearanceRow(
        R.string.setting_duet_markers,
        R.string.summary_duet_markers,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_DUET_MARKERS,
        "对唱 标记 识别 duet markers"
    ),
    appearanceRow(
        R.string.setting_lyric_time_offset,
        R.string.summary_lyric_time_offset,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_LYRIC_TIME_OFFSET,
        "歌词 时间 偏移 提前 延后 sync offset"
    ),
    appearanceChoiceRow(
        AodChoiceKind.SECONDARY_TEXT,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "辅助 文字 音标 翻译 和声 secondary text"
    ),
    appearanceRow(
        R.string.setting_secondary_auto_size,
        R.string.summary_secondary_auto_size,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_SECONDARY_AUTO_SIZE,
        "辅助 文字 自动 字号 secondary auto size"
    ),
    appearanceRow(
        R.string.setting_bright_secondary_text,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_SECONDARY_TEXT_BRIGHT,
        "辅助 文字 亮度 bright secondary"
    ),
    appearanceRow(
        R.string.setting_secondary_word_karaoke,
        R.string.summary_secondary_word_karaoke,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_SECONDARY_WORD_KARAOKE,
        "辅助 文字 逐字 点亮 secondary karaoke"
    ),
    appearanceRow(
        R.string.setting_secondary_next_line,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_SECONDARY_NEXT_LINE,
        "辅助 文字 第二行 secondary next line"
    ),
    appearanceRow(
        R.string.setting_next_line_aux,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_NEXT_LINE_AUX,
        "下一行 辅助 文字 next line aux"
    ),
    appearanceRow(
        R.string.setting_show_furigana,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_RUBY_VISIBLE,
        "音标 注音 假名 furigana ruby"
    ),
    appearanceChoiceRow(
        AodChoiceKind.LONG_LINES,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "长句 换行 截断 wrap clip"
    ),
    appearanceChoiceRow(
        AodChoiceKind.LYRIC_LINES,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "歌词 行数 显示几行 lyric lines"
    ),
    appearanceRow(
        R.string.setting_keep_phrases_together,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_ADAPTIVE_SECTIONING,
        "短语 不断行 自适应 分句 phrases together"
    ),
    appearanceRow(
        R.string.setting_show_next_line,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_SHOW_NEXT_LINE,
        "下一行 歌词 预览 next line"
    ),
    appearanceChoiceRow(
        AodChoiceKind.SECOND_LINE_ALIGNMENT,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "第二行 对齐 next line alignment"
    ),
    appearanceRow(
        R.string.setting_show_song_info,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_METADATA_VISIBLE,
        "歌曲信息 歌名 歌手 song info metadata"
    ),
    appearanceChoiceRow(
        AodChoiceKind.SONG_INFO_POSITION,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "歌曲信息 位置 上 下 song info position"
    ),
    appearanceChoiceRow(
        AodChoiceKind.SONG_INFO_ALIGNMENT,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "歌曲信息 对齐 song info alignment"
    ),
    appearanceChoiceRow(
        AodChoiceKind.SONG_INFO_LAYOUT,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "歌曲信息 布局 单行 两行 song info layout"
    ),
    appearanceChoiceRow(
        AodChoiceKind.SONG_INFO_SEPARATOR,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "歌曲信息 分隔符 separator"
    ),
    appearanceRow(
        R.string.setting_hide_album_same_as_title,
        R.string.summary_hide_album_same_as_title,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_HIDE_ALBUM_SAME_AS_TITLE,
        "专辑 同名 隐藏 album same as title"
    ),
    appearanceRow(
        R.string.setting_show_song_artwork,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_ARTWORK_VISIBLE,
        "封面 专辑图 artwork cover"
    ),
    appearanceChoiceRow(
        AodChoiceKind.SONG_ARTWORK_SHAPE,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        "封面 形状 圆形 方形 artwork shape"
    ),
    appearanceRow(
        R.string.setting_song_artwork_adaptive,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_ARTWORK_ADAPTIVE_SCALE,
        "封面 自适应 缩放 artwork adaptive"
    ),
    appearanceRow(
        R.string.setting_song_artwork_size,
        R.string.summary_song_artwork_size,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_ARTWORK_SIZE,
        "封面 大小 尺寸 artwork size"
    ),
    appearanceRow(
        R.string.setting_song_artwork_spin,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_ARTWORK_SPIN,
        "封面 旋转 artwork spin"
    ),
    appearanceRow(
        R.string.setting_song_artwork_spin_paused,
        null,
        R.string.section_text_language,
        GlobalSearchAnchor.APPEARANCE_TEXT_LANGUAGE,
        SearchRowKeys.APPEARANCE_ARTWORK_SPIN_WHEN_PAUSED,
        "封面 暂停 旋转 artwork spin paused"
    ),

    // ---- 效果 ----
    appearanceChoiceRow(
        AodChoiceKind.TEXT_WEIGHT,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        "字重 粗细 加粗 text weight bold"
    ),
    appearanceChoiceRow(
        AodChoiceKind.TEXT_SIZE,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        "字号 大小 text size"
    ),
    appearanceRow(
        R.string.setting_lyric_size,
        null,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        SearchRowKeys.APPEARANCE_LYRIC_SIZE,
        "字号 大小 百分比 加减 歌词 size percent"
    ),
    appearanceChoiceRow(
        AodChoiceKind.FONT,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        "字体 字形 font"
    ),
    appearanceRow(
        R.string.action_import_custom_font,
        null,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        SearchRowKeys.APPEARANCE_CUSTOM_FONT,
        "导入 自定义 字体 custom font import"
    ),
    appearanceChoiceRow(
        AodChoiceKind.WORD_ANIMATION,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        "逐字 动画 特效 word animation"
    ),
    appearanceChoiceRow(
        AodChoiceKind.WORD_EFFECT,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        "逐字 效果 自定义 word effect"
    ),
    appearanceRow(
        R.string.action_import_word_effect_pack,
        null,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        SearchRowKeys.APPEARANCE_IMPORT_WORD_EFFECT,
        "导入 逐字 效果包 word effect import"
    ),
    appearanceRow(
        R.string.action_open_word_effect_studio,
        null,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        SearchRowKeys.APPEARANCE_WORD_EFFECT_STUDIO,
        "逐字 效果 工坊 编辑 studio"
    ),
    appearanceChoiceRow(
        AodChoiceKind.GLOW,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        "辉光 发光 glow"
    ),
    appearanceChoiceRow(
        AodChoiceKind.LINE_PROGRESS,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        "逐行 进度 填充 line progress"
    ),
    appearanceChoiceRow(
        AodChoiceKind.LINE_TRANSITION,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        "行 过渡 动画 line transition"
    ),
    appearanceChoiceRow(
        AodChoiceKind.LINE_TRANSITION_SPEED,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        "行 过渡 速度 line transition speed"
    ),
    appearanceChoiceRow(
        AodChoiceKind.TEXT_BRIGHTNESS,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        "文字 亮度 压暗 text brightness dimmed"
    ),
    appearanceChoiceRow(
        AodChoiceKind.TRANSITION_SPEED,
        R.string.section_effects,
        GlobalSearchAnchor.APPEARANCE_EFFECTS,
        "场景 过渡 速度 transition speed"
    ),

    // ---- 音乐频谱 ----
    appearanceRow(
        R.string.setting_spectrum_enabled,
        R.string.summary_spectrum_enabled,
        R.string.section_spectrum,
        GlobalSearchAnchor.APPEARANCE_SPECTRUM,
        SearchRowKeys.APPEARANCE_SPECTRUM_ENABLED,
        "频谱 开关 波形 spectrum enabled"
    ),
    appearanceChoiceRow(
        AodChoiceKind.SPECTRUM_SOURCE,
        R.string.section_spectrum,
        GlobalSearchAnchor.APPEARANCE_SPECTRUM,
        "频谱 来源 节奏 回环 采集 spectrum source"
    ),
    appearanceChoiceRow(
        AodChoiceKind.SPECTRUM_STYLE,
        R.string.section_spectrum,
        GlobalSearchAnchor.APPEARANCE_SPECTRUM,
        "频谱 样式 柱状 镜像 线条 spectrum style"
    ),
    appearanceChoiceRow(
        AodChoiceKind.SPECTRUM_COLOR,
        R.string.section_spectrum,
        GlobalSearchAnchor.APPEARANCE_SPECTRUM,
        "频谱 颜色 spectrum color"
    ),
    appearanceRow(
        R.string.setting_spectrum_background,
        R.string.summary_spectrum_background,
        R.string.section_spectrum,
        GlobalSearchAnchor.APPEARANCE_SPECTRUM,
        SearchRowKeys.APPEARANCE_SPECTRUM_BACKGROUND,
        "频谱 背景 spectrum background"
    ),
    appearanceRow(
        R.string.setting_spectrum_opacity,
        null,
        R.string.section_spectrum,
        GlobalSearchAnchor.APPEARANCE_SPECTRUM,
        SearchRowKeys.APPEARANCE_SPECTRUM_OPACITY,
        "频谱 透明度 spectrum opacity"
    ),
    appearanceRow(
        R.string.setting_spectrum_y_offset,
        null,
        R.string.section_spectrum,
        GlobalSearchAnchor.APPEARANCE_SPECTRUM,
        SearchRowKeys.APPEARANCE_SPECTRUM_Y_OFFSET,
        "频谱 垂直 偏移 spectrum y offset"
    ),
    appearanceRow(
        R.string.setting_spectrum_height,
        null,
        R.string.section_spectrum,
        GlobalSearchAnchor.APPEARANCE_SPECTRUM,
        SearchRowKeys.APPEARANCE_SPECTRUM_HEIGHT,
        "频谱 高度 spectrum height"
    ),
    appearanceRow(
        R.string.setting_spectrum_sensitivity,
        null,
        R.string.section_spectrum,
        GlobalSearchAnchor.APPEARANCE_SPECTRUM,
        SearchRowKeys.APPEARANCE_SPECTRUM_SENSITIVITY,
        "频谱 灵敏度 spectrum sensitivity"
    ),

    // ---- 自定义贴纸 ----
    appearanceRow(
        R.string.action_import_custom_sprite,
        R.string.summary_import_custom_sprite,
        R.string.section_custom_sprites,
        GlobalSearchAnchor.APPEARANCE_SPRITES,
        SearchRowKeys.APPEARANCE_IMPORT_SPRITE,
        "导入 贴纸 素材 sprite import"
    ),

    // ---- 颜色 ----
    *PaletteColor.entries.map { appearancePaletteRow(it) }.toTypedArray(),
    appearanceRow(
        R.string.palette_spectrum,
        null,
        R.string.section_colors,
        GlobalSearchAnchor.APPEARANCE_COLORS,
        SearchRowKeys.APPEARANCE_SPECTRUM_COLOR,
        "频谱 颜色 自定义 spectrum color"
    ),

    // ---- 锁屏歌词卡片 ----
    appearanceRow(
        R.string.setting_show_lyric_card,
        null,
        R.string.section_lockscreen_card,
        GlobalSearchAnchor.APPEARANCE_LOCKSCREEN_CARD,
        SearchRowKeys.APPEARANCE_LYRIC_CARD,
        "锁屏 歌词 卡片 背景 lyric card",
        appearanceSection = 1
    ),
    appearanceChoiceRow(
        AodChoiceKind.CARD_COLOR,
        R.string.section_lockscreen_card,
        GlobalSearchAnchor.APPEARANCE_LOCKSCREEN_CARD,
        "锁屏 卡片 颜色 card color",
        appearanceSection = 1
    ),
    appearanceRow(
        R.string.setting_card_transparency,
        R.string.summary_card_transparency,
        R.string.section_lockscreen_card,
        GlobalSearchAnchor.APPEARANCE_LOCKSCREEN_CARD,
        SearchRowKeys.APPEARANCE_CARD_TRANSPARENCY,
        "锁屏 卡片 透明度 transparency",
        appearanceSection = 1
    ),
    appearanceRow(
        R.string.setting_show_playback_progress,
        R.string.summary_show_playback_progress,
        R.string.section_lockscreen_card,
        GlobalSearchAnchor.APPEARANCE_LOCKSCREEN_CARD,
        SearchRowKeys.APPEARANCE_PLAYBACK_PROGRESS,
        "锁屏 播放 进度 条 playback progress",
        appearanceSection = 1
    ),

    // ---- 两个显示区域 ----
    appearanceRow(
        R.string.action_import_appearance,
        null,
        R.string.section_both_surfaces,
        GlobalSearchAnchor.APPEARANCE_BOTH_SURFACES,
        SearchRowKeys.APPEARANCE_IMPORT_APPEARANCE,
        "导入 外观 配置 appearance import"
    ),
    appearanceRow(
        R.string.action_export_appearance,
        null,
        R.string.section_both_surfaces,
        GlobalSearchAnchor.APPEARANCE_BOTH_SURFACES,
        SearchRowKeys.APPEARANCE_EXPORT_APPEARANCE,
        "导出 外观 配置 appearance export"
    ),
    appearanceRow(
        R.string.action_reset_surfaces,
        null,
        R.string.section_both_surfaces,
        GlobalSearchAnchor.APPEARANCE_BOTH_SURFACES,
        SearchRowKeys.APPEARANCE_RESET_SURFACES,
        "重置 外观 恢复默认 reset"
    ),

    // ---- 列表尾部:行为入口 / 锁屏唤醒 ----
    appearanceRow(
        R.string.title_aod_behavior_settings,
        R.string.summary_aod_behavior_entry,
        R.string.section_both_surfaces,
        GlobalSearchAnchor.APPEARANCE_BOTH_SURFACES,
        SearchRowKeys.APPEARANCE_AOD_BEHAVIOR,
        "息屏 行为 设置 aod behavior",
        appearanceSection = 0
    ),
    appearanceRow(
        R.string.setting_keep_lockscreen_awake,
        R.string.summary_keep_lockscreen_awake,
        R.string.section_lockscreen_wake,
        GlobalSearchAnchor.APPEARANCE_BOTH_SURFACES,
        SearchRowKeys.APPEARANCE_KEEP_LOCKSCREEN_AWAKE,
        "锁屏 保持 唤醒 常亮 keep awake",
        appearanceSection = 1
    ),
    appearanceRow(
        R.string.setting_block_lockscreen_customization,
        R.string.summary_block_lockscreen_customization,
        R.string.section_lockscreen_wake,
        GlobalSearchAnchor.APPEARANCE_BOTH_SURFACES,
        SearchRowKeys.APPEARANCE_BLOCK_LOCKSCREEN_CUSTOMIZATION,
        "锁屏 自定义 长按 屏蔽 禁用 customization",
        appearanceSection = 1
    ),
    appearanceRow(
        R.string.setting_raise_to_aod,
        R.string.summary_raise_to_aod,
        R.string.section_lockscreen_wake,
        GlobalSearchAnchor.APPEARANCE_BOTH_SURFACES,
        SearchRowKeys.APPEARANCE_RAISE_TO_AOD,
        "抬起 亮屏 息屏 raise to aod",
        appearanceSection = 1
    )
)

/** 关于页静态入口:关于页本身没有搜索图标,但从其它页可以搜到并切过去。 */
private val ABOUT_SEARCH_ENTRIES: List<GlobalSearchEntry> = listOf(
    GlobalSearchEntry(
        titleRes = R.string.title_onboarding,
        sectionLabelRes = R.string.section_about,
        tab = SettingsTab.ABOUT,
        summaryRes = R.string.summary_onboarding,
        keywords = "引导 教程 上手 onboarding guide",
        anchor = GlobalSearchAnchor.ABOUT_LINKS,
        rowAnchor = SearchRowKeys.ABOUT_ONBOARDING
    ),
    GlobalSearchEntry(
        titleRes = R.string.title_help,
        sectionLabelRes = R.string.section_about,
        tab = SettingsTab.ABOUT,
        keywords = "帮助 常见问题 faq help",
        anchor = GlobalSearchAnchor.ABOUT_LINKS,
        rowAnchor = SearchRowKeys.ABOUT_HELP
    ),
    GlobalSearchEntry(
        titleRes = R.string.title_changelog,
        sectionLabelRes = R.string.section_about,
        tab = SettingsTab.ABOUT,
        keywords = "更新 日志 版本 changelog",
        anchor = GlobalSearchAnchor.ABOUT_LINKS,
        rowAnchor = SearchRowKeys.ABOUT_CHANGELOG
    ),
    GlobalSearchEntry(
        titleRes = R.string.title_contributors,
        sectionLabelRes = R.string.section_about,
        tab = SettingsTab.ABOUT,
        keywords = "贡献者 致谢 contributors",
        anchor = GlobalSearchAnchor.ABOUT_LINKS,
        rowAnchor = SearchRowKeys.ABOUT_CONTRIBUTORS
    ),
    GlobalSearchEntry(
        titleRes = R.string.title_licenses,
        sectionLabelRes = R.string.section_about,
        tab = SettingsTab.ABOUT,
        keywords = "开源 许可 license",
        anchor = GlobalSearchAnchor.ABOUT_LINKS,
        rowAnchor = SearchRowKeys.ABOUT_LICENSES
    )
)

/** 子页面单行:命中后直接打开对应子页(页面较短,进入后即可看到目标分组)。 */
private fun subpageRow(
    titleRes: Int,
    summaryRes: Int?,
    sectionLabelRes: Int,
    destination: GlobalSearchDestination,
    keywords: String
): GlobalSearchEntry = GlobalSearchEntry(
    titleRes = titleRes,
    sectionLabelRes = sectionLabelRes,
    tab = if (destination == GlobalSearchDestination.APP_APPEARANCE) {
        SettingsTab.APP
    } else {
        SettingsTab.SETTINGS
    },
    summaryRes = summaryRes,
    keywords = keywords,
    destination = destination
)

private val SUBPAGE_SEARCH_ENTRIES: List<GlobalSearchEntry> = listOf(
    // ---- App 设置 → 应用外观 ----
    subpageRow(
        R.string.setting_theme_mode,
        null,
        R.string.section_app_appearance,
        GlobalSearchDestination.APP_APPEARANCE,
        "主题 模式 深色 浅色 跟随系统 theme mode dark light"
    ),
    subpageRow(
        R.string.setting_theme_color,
        null,
        R.string.section_app_appearance,
        GlobalSearchDestination.APP_APPEARANCE,
        "主题 颜色 主色 theme color"
    ),
    subpageRow(
        R.string.setting_control_color,
        null,
        R.string.section_app_appearance,
        GlobalSearchDestination.APP_APPEARANCE,
        "控件 颜色 卡片 control color"
    ),
    subpageRow(
        R.string.setting_control_opacity,
        null,
        R.string.section_app_appearance,
        GlobalSearchDestination.APP_APPEARANCE,
        "控件 不透明度 透明度 control opacity"
    ),
    subpageRow(
        R.string.setting_text_color,
        null,
        R.string.section_app_appearance,
        GlobalSearchDestination.APP_APPEARANCE,
        "文字 颜色 text color"
    ),
    subpageRow(
        R.string.setting_app_font,
        null,
        R.string.section_app_appearance,
        GlobalSearchDestination.APP_APPEARANCE,
        "字体 字形 app font"
    ),
    subpageRow(
        R.string.setting_floating_nav_bar,
        R.string.summary_floating_nav_bar,
        R.string.section_app_appearance,
        GlobalSearchDestination.APP_APPEARANCE,
        "底部 导航 悬浮 导航栏 floating nav bar"
    ),
    subpageRow(
        R.string.setting_background_image,
        null,
        R.string.section_app_appearance,
        GlobalSearchDestination.APP_APPEARANCE,
        "背景 图片 壁纸 background image wallpaper"
    ),
    subpageRow(
        R.string.setting_background_dim,
        null,
        R.string.section_app_appearance,
        GlobalSearchDestination.APP_APPEARANCE,
        "背景 变暗 暗度 background dim"
    ),
    subpageRow(
        R.string.setting_background_blur,
        null,
        R.string.section_app_appearance,
        GlobalSearchDestination.APP_APPEARANCE,
        "背景 模糊 background blur"
    ),
    subpageRow(
        R.string.setting_system_bar_icons,
        null,
        R.string.section_app_appearance,
        GlobalSearchDestination.APP_APPEARANCE,
        "系统栏 图标 状态栏 system bar icons"
    ),

    // ---- 设置 → 息屏行为 ----
    subpageRow(
        R.string.setting_keep_aod_active,
        R.string.summary_keep_aod_active,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "息屏 保持 常亮 aod keep active"
    ),
    subpageRow(
        R.string.setting_keep_aod_active_for,
        null,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "息屏 保持 时长 keep active duration"
    ),
    subpageRow(
        R.string.setting_keep_aod_unsynced,
        null,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "息屏 未同步 保持 unsynced"
    ),
    subpageRow(
        R.string.setting_aod_clock_follow,
        R.string.summary_aod_clock_follow,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "时钟 跟随 歌词 clock follow"
    ),
    subpageRow(
        R.string.setting_aod_clock_y_offset,
        R.string.summary_aod_clock_y_offset,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "时钟 垂直 偏移 clock y offset"
    ),
    subpageRow(
        R.string.setting_aod_clock_image,
        null,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "时钟 样式 图片 clock image style"
    ),
    subpageRow(
        R.string.setting_movement_interval,
        null,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "防烧屏 移动 间隔 burn in movement"
    ),
    subpageRow(
        R.string.setting_aod_brightness_boost,
        R.string.summary_aod_brightness_boost,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "息屏 亮度 增强 brightness boost"
    ),
    subpageRow(
        R.string.setting_aod_brightness_custom,
        null,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "息屏 自定义 亮度 brightness custom"
    ),
    subpageRow(
        R.string.setting_aod_brightness_level,
        R.string.summary_aod_brightness_level,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "息屏 亮度 等级 brightness level"
    ),
    subpageRow(
        R.string.setting_suppress_stock_aod,
        R.string.summary_suppress_stock_aod,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "隐藏 原生 息屏 系统 suppress stock aod"
    ),
    subpageRow(
        R.string.setting_aod_power_saver,
        R.string.summary_aod_power_saver,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "息屏 省电 power saver"
    ),
    subpageRow(
        R.string.setting_aod_refresh_rate_cap,
        null,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "息屏 刷新率 上限 refresh rate"
    ),
    subpageRow(
        R.string.setting_aod_rotate_with_device,
        R.string.summary_aod_rotate_with_device,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "息屏 旋转 跟随 设备 rotate"
    ),
    subpageRow(
        R.string.setting_aod_rotation_mode,
        null,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "旋转 模式 rotation mode"
    ),
    subpageRow(
        R.string.setting_aod_rotation_settle,
        null,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "旋转 稳定 延迟 rotation settle"
    ),
    subpageRow(
        R.string.setting_aod_landscape_scale,
        R.string.summary_aod_landscape_scale,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "横屏 缩放 landscape scale"
    ),
    subpageRow(
        R.string.setting_aod_landscape_anchor,
        R.string.summary_aod_landscape_anchor,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "横屏 锚点 landscape anchor"
    ),
    subpageRow(
        R.string.setting_aod_padding_portrait_x,
        R.string.summary_aod_padding,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "息屏 边距 水平 padding x"
    ),
    subpageRow(
        R.string.setting_aod_padding_portrait_y,
        R.string.summary_aod_padding,
        R.string.section_aod_behavior,
        GlobalSearchDestination.AOD_BEHAVIOR,
        "息屏 边距 垂直 padding y"
    )
)

/** 全量索引:主页三 Tab 的现有条目 + 歌词外观逐项 + 关于页静态入口。 */
internal val GLOBAL_SEARCH_ENTRIES: List<GlobalSearchEntry> =
    BASE_SEARCH_ENTRIES + APPEARANCE_ROW_ENTRIES + SUBPAGE_SEARCH_ENTRIES +
        ABOUT_SEARCH_ENTRIES

/** 单个查询词对一条索引的匹配得分;null 表示未命中。分档让「标题包含」排在模糊命中之前。 */
private fun tokenScore(token: String, title: String, haystack: String): Int? {
    val titleIndex = title.indexOf(token)
    if (titleIndex >= 0) return 1200 - titleIndex.coerceAtMost(100)
    val hayIndex = haystack.indexOf(token)
    if (hayIndex >= 0) return 900 - hayIndex.coerceAtMost(100)
    // 字符按顺序出现即算命中(如「歌源」命中「歌词来源」),覆盖漏字/缩写。
    // 单字与两字母英文不做跳字匹配,否则「a」「sd」这类输入会把整张索引都捞回来。
    if (allowsSubsequence(token) && isSubsequence(token, title)) return 560
    if (allowsSubsequence(token) && isSubsequence(token, haystack)) return 420
    // 英文拼写容错:任意单词编辑距离 ≤1(如 sorce → source)。
    if (token.length >= 3 &&
        token.all { it.code < 128 && it.isLetterOrDigit() } &&
        haystack.split(Regex("[^a-z0-9]+")).any { word ->
            word.length >= 3 && editDistanceAtMostOne(token, word)
        }
    ) {
        return 300
    }
    return null
}

/**
 * 跳字匹配的最小长度:三个字符以上,或两个汉字。
 * 两字母英文(如 "sd")跳字命中率过高,只保留连续子串匹配。
 */
private fun allowsSubsequence(token: String): Boolean =
    token.length >= 3 || (token.length == 2 && token.any { it.code > 0x7F })

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
 * 一条索引解析后的文案快照。把资源解析与匹配拆开,匹配逻辑可做纯 JVM 单测,
 * 不必为了 `getString` 打开 Robolectric 的 Android 资源加载(那会拖慢并影响其它测试)。
 */
internal data class GlobalSearchDocument(
    val entry: GlobalSearchEntry,
    val title: String,
    val summary: String?,
    val sectionLabel: String,
    val tabLabel: String
)

/** 按当前语言解析整张索引;每次打开搜索/改语言后调用,条目数量很小。 */
internal fun resolveGlobalSearchDocuments(context: Context): List<GlobalSearchDocument> =
    GLOBAL_SEARCH_ENTRIES.map { entry ->
        GlobalSearchDocument(
            entry = entry,
            title = context.getString(entry.titleRes),
            summary = entry.summaryRes?.let { context.getString(it) },
            sectionLabel = context.getString(entry.sectionLabelRes),
            tabLabel = context.getString(entry.tab.navLabelRes())
        )
    }

/**
 * 标题/说明/分组/页面/关键词全部参与匹配,支持空格分词与模糊命中
 * (连续子串 > 按顺序出现的字符 > 英文编辑距离 ≤1);按得分降序返回。
 */
internal fun filterGlobalSearchDocuments(
    documents: List<GlobalSearchDocument>,
    query: String,
    experimentalEligible: Boolean = true
): List<GlobalSearchEntry> {
    val tokens = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    return documents.mapNotNull { document ->
        val entry = document.entry
        if (entry.requiresExperimental && !experimentalEligible) return@mapNotNull null
        val title = document.title.lowercase(Locale.ROOT)
        val haystack = buildString {
            append(title)
            document.summary?.let { append(' ').append(it) }
            append(' ').append(document.sectionLabel)
            append(' ').append(document.tabLabel)
            append(' ').append(entry.keywords)
        }.lowercase(Locale.ROOT)
        var score = 0
        for (token in tokens) {
            score += tokenScore(token, title, haystack) ?: return@mapNotNull null
        }
        entry to score
    }.sortedByDescending { it.second }.map { it.first }
}

/** 输入补全的纯逻辑:排除与输入相同的标题,按首次出现位置排序,去重后最多 [limit] 条。 */
internal fun globalSearchCompletionsFromTitles(
    titles: List<String>,
    query: String,
    limit: Int = 6
): List<String> {
    val q = query.trim().lowercase(Locale.ROOT)
    if (q.isEmpty()) return emptyList()
    return titles
        .distinct()
        .filter { title ->
            val lower = title.lowercase(Locale.ROOT)
            lower != q && lower.contains(q)
        }
        .sortedBy { title -> title.lowercase(Locale.ROOT).indexOf(q) }
        .take(limit)
}

/** 搜索结果按「页面 · 分组」聚合;组间顺序沿用首个命中的得分顺序。 */
private data class GlobalSearchResultGroup(
    val tab: SettingsTab,
    val sectionLabelRes: Int,
    val entries: List<GlobalSearchEntry>
)

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
    var fieldExpanded by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    // 文案只解析一次,输入时走纯匹配,避免每次按键都重新查资源。
    val documents = remember { resolveGlobalSearchDocuments(context) }
    val results = remember(documents, query, experimentalEligible) {
        filterGlobalSearchDocuments(documents, query, experimentalEligible)
    }
    val completions = remember(documents, query, experimentalEligible) {
        globalSearchCompletionsFromTitles(
            titles = documents
                .filter { experimentalEligible || !it.entry.requiresExperimental }
                .map { it.title },
            query = query
        )
    }
    // 结果按分组聚合,组内保持分数顺序;LazyColumn 以组为 item,长结果不再整块组合。
    val groups = remember(results) {
        val grouped = LinkedHashMap<Pair<SettingsTab, Int>, MutableList<GlobalSearchEntry>>()
        results.forEach { entry ->
            grouped.getOrPut(entry.tab to entry.sectionLabelRes) { mutableListOf() }
                .add(entry)
        }
        grouped.map { (key, entries) ->
            GlobalSearchResultGroup(key.first, key.second, entries)
        }
    }
    // InputField 在 expanded=true 时自行请求焦点并拉起输入法;首帧后再展开,避免被系统忽略。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        fieldExpanded = true
        withFrameNanos { }
        keyboardController?.show()
    }
    BackHandler(onBack = onDismiss)

    Scaffold(
        modifier = Modifier.blockOverlayPointerInput(),
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
            InputField(
                query = query,
                onQueryChange = { query = it },
                onSearch = { keyboardController?.hide() },
                expanded = fieldExpanded,
                onExpandedChange = { fieldExpanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                label = stringResource(R.string.search_placeholder)
            )
            if (query.isNotBlank() && completions.isNotEmpty()) {
                // 输入补全:点一下把输入补成完整标题,同时保留关键词搜索入口。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
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
                query.isBlank() -> Text(
                    text = stringResource(R.string.search_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp
                )

                results.isEmpty() -> Text(
                    text = stringResource(R.string.search_empty),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 14.sp
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp)
                ) {
                    items(
                        items = groups,
                        key = { group -> "${group.tab.name}:${group.sectionLabelRes}" }
                    ) { group ->
                        SmallTitle(
                            text = stringResource(
                                R.string.search_scope,
                                stringResource(group.tab.navLabelRes()),
                                stringResource(group.sectionLabelRes)
                            ),
                            modifier = Modifier.padding(
                                start = 12.dp,
                                end = 12.dp,
                                top = 8.dp,
                                bottom = 4.dp
                            )
                        )
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .padding(bottom = 12.dp),
                            colors = CardDefaults.defaultColors(
                                color = appCardContainerColor(),
                                contentColor = appControlContentColor(
                                    MiuixTheme.colorScheme.onSurfaceContainer
                                )
                            )
                        ) {
                            group.entries.forEach { entry ->
                                ArrowPreference(
                                    title = stringResource(entry.titleRes),
                                    summary = entry.summaryRes?.let { stringResource(it) },
                                    onClick = {
                                        // 选中即离开覆盖层,先把输入法收起来,避免键盘盖住新页面。
                                        keyboardController?.hide()
                                        focusManager.clearFocus()
                                        onSelect(entry)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
