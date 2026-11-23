package com.eza.hyperglow.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 云端失败类型 → 用户文案；网络客户端只抛 [PresetHubFailure]。 */
internal fun hubFailureText(context: Context, error: Throwable): String {
    val hub = error as? PresetHubException
    val failure = hub?.failure ?: PresetHubFailure.NETWORK
    return when (failure) {
        PresetHubFailure.INVALID_CONFIG -> context.getString(R.string.preset_hub_error_invalid_config)
        PresetHubFailure.NETWORK -> context.getString(R.string.preset_hub_error_network)
        PresetHubFailure.UNAUTHORIZED -> context.getString(R.string.preset_hub_error_auth)
        PresetHubFailure.RATE_LIMITED -> context.getString(R.string.preset_hub_error_rate)
        PresetHubFailure.NOT_FOUND -> context.getString(R.string.preset_hub_error_repo)
        PresetHubFailure.CONFLICT -> context.getString(R.string.preset_hub_error_conflict)
        PresetHubFailure.MALFORMED -> context.getString(R.string.preset_hub_error_malformed)
        PresetHubFailure.TOO_LARGE -> context.getString(R.string.preset_hub_error_too_large)
        PresetHubFailure.SERVER -> context.getString(
            R.string.preset_hub_error_server,
            hub?.status ?: 0
        )
    }
}

/**
 * 配置预设卡片：名称/徽标 + 摘要 + **大预览**。
 *
 * 预览走预设里外观文档编译出的锁屏 profile，`useLiveSnapshot=false` 且不轮播演示行：
 * 列表滚动时每张可见卡片只画一帧，不订阅正在播放的歌词、也不互相抢动画。
 * 点整张卡进入详情页，那里才是实时预览与分节勾选/应用。
 */
@Composable
internal fun PresetPreviewCard(
    preset: ConfigPreset,
    subtitle: String,
    badge: String? = null,
    onClick: () -> Unit
) {
    val glass = isAppGlassMaterialActive()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(188.dp)
            .then(
                if (glass) {
                    Modifier.appGlassMaterial(
                        RoundedCornerShape(CardDefaults.CornerRadius),
                        cornerRadius = CardDefaults.CornerRadius
                    )
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.defaultColors(
            color = if (glass) Color.Transparent else appCardContainerColor(),
            contentColor = appControlContentColor(MiuixTheme.colorScheme.onSurfaceContainer)
        )
    ) {
        Column(Modifier.fillMaxSize()) {
            PresetPaletteStrip(preset)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preset.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    badge?.let {
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = it,
                            fontSize = 9.sp,
                            color = MiuixTheme.colorScheme.primary,
                            modifier = Modifier
                                .background(
                                    color = MiuixTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(
                            R.string.config_preset_section_count,
                            preset.sections.size
                        ),
                        fontSize = 10.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "›",
                        fontSize = 15.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}

/**
 * 卡片顶部的轻量外观缩略：只读预设外观文档里的配色 token，画渐变 + 两行色条。
 * 不编译场景、不订阅歌词，列表滚动时零额外渲染负担；真正的实时预览在详情页。
 */
@Composable
private fun PresetPaletteStrip(preset: ConfigPreset) {
    val colors = remember(preset) { presetPreviewColors(preset) }
    val sung = colors?.sung ?: Color(0xFFE6E6EE)
    val glow = colors?.glow ?: Color(0xFF9A9AAC)
    val unsung = colors?.unsung ?: Color(0xFF6E6E7A)
    val metadata = colors?.metadata ?: Color(0xFF9A9AA8)
    val accent = colors?.accent ?: Color(0xFF7FB8E8)
    // 深色文字/深色主色的预设也要在深色缩略条上看得见：低于亮度阈值就换可读的近似色。
    val stripAccent = if (accent.luminance() < 0.25f) Color(0xFF7FB8E8) else accent
    val sampleText = if (sung.luminance() < 0.3f) Color(0xFFF2F2F6) else sung
    val sampleGlow = if (glow.luminance() < 0.25f) Color(0xFFB8B8C8) else glow
    val barSung = if (sung.luminance() < 0.25f) sampleText else sung
    val barUnsung = if (unsung.luminance() < 0.2f) stripAccent.copy(alpha = 0.85f) else unsung
    val barMeta = if (metadata.luminance() < 0.2f) Color(0xFF8A8A96) else metadata
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .background(
                Brush.linearGradient(
                    listOf(
                        stripAccent.copy(alpha = 0.62f),
                        Color(0xFF24242C),
                        Color(0xFF111116)
                    )
                )
            )
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(96.dp)
                .background(
                    Brush.radialGradient(
                        listOf(stripAccent.copy(alpha = 0.34f), Color.Transparent)
                    ),
                    CircleShape
                )
        )
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 12.dp)
        ) {
            Text(
                text = "Aa",
                fontSize = 21.sp,
                fontWeight = FontWeight.Medium,
                style = TextStyle(
                    color = sampleText,
                    shadow = Shadow(color = sampleGlow, blurRadius = 12f)
                )
            )
            Spacer(Modifier.height(5.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    Modifier
                        .width(26.dp)
                        .height(3.dp)
                        .background(barSung.copy(alpha = 0.95f), RoundedCornerShape(2.dp))
                )
                Box(
                    Modifier
                        .width(16.dp)
                        .height(3.dp)
                        .background(barUnsung.copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                )
                Box(
                    Modifier
                        .width(10.dp)
                        .height(3.dp)
                        .background(barMeta.copy(alpha = 0.7f), RoundedCornerShape(2.dp))
                )
            }
        }
        if (colors == null) {
            Text(
                text = stringResource(R.string.preset_hub_no_appearance),
                fontSize = 9.sp,
                color = Color.White.copy(alpha = 0.55f),
                maxLines = 2,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .width(64.dp)
            )
        }
    }
}

private data class PresetPreviewColors(
    val sung: Color,
    val glow: Color,
    val unsung: Color,
    val metadata: Color,
    val accent: Color
)

private fun presetPreviewColors(preset: ConfigPreset): PresetPreviewColors? {
    val document = preset.payload[ConfigBackupCodec.CUSTOMIZATION_KEY] as? JsonObject
        ?: return null
    val profiles = document["profiles"] as? JsonObject ?: return null
    val profile = (profiles["lockscreen"] ?: profiles["aod"]) as? JsonObject ?: return null
    val palette = profile["palette"] as? JsonObject ?: JsonObject(emptyMap())
    fun color(key: String, fallback: Long): Color {
        val hex = (palette[key] as? JsonPrimitive)?.contentOrNull
        if (hex.isNullOrBlank()) return Color(fallback)
        return runCatching { Color(android.graphics.Color.parseColor(hex)) }
            .getOrDefault(Color(fallback))
    }
    return PresetPreviewColors(
        sung = color("sungText", 0xFFE6E6EE),
        glow = color("glow", 0xFF9A9AAC),
        unsung = color("unsungText", 0xFF6E6E7A),
        metadata = color("metadataText", 0xFF9A9AA8),
        accent = color("accent", 0xFF7FB8E8)
    )
}

/** 网格顶部搜索框：与卡片同宽，不再套 SettingsCard 的额外缩进。 */
@Composable
internal fun PresetGridSearchField(
    value: String,
    onValueChange: (String) -> Unit
) {
    val glass = isAppGlassMaterialActive()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (glass) {
                    Modifier.appGlassMaterial(
                        RoundedCornerShape(CardDefaults.CornerRadius),
                        cornerRadius = CardDefaults.CornerRadius
                    )
                } else {
                    Modifier
                }
            ),
        colors = CardDefaults.defaultColors(
            color = if (glass) Color.Transparent else appCardContainerColor(),
            contentColor = appControlContentColor(MiuixTheme.colorScheme.onSurfaceContainer)
        )
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = stringResource(R.string.preset_search_hint),
            useLabelAsPlaceholder = true,
            textStyle = TextStyle(
                color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                fontSize = 16.sp
            ),
            cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search)
        )
    }
}

/** 网格里的分节标题（本地/云端通用样式）。 */
@Composable
internal fun PresetGridSectionTitle(title: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = count.toString(),
            fontSize = 12.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f)
        )
    }
}

/**
 * 上传前选择要公开的分节。
 *
 * 云端清单是公开 Release 资产，**插件设置默认不勾选**：插件设置里可能有 AI API Key、
 * 账号令牌等，误上传等于公开泄露。用户明确勾选后才随预设发出去。
 */
@Composable
internal fun PresetUploadDialog(
    preset: ConfigPreset,
    onDismiss: () -> Unit,
    onUpload: (ConfigPreset, String) -> Unit
) {
    val context = LocalContext.current
    val hub = remember { PresetHubPreferences.read(context) }
    // 匿名 Hub 在服务端直接拒绝 pluginSettings；这里不展示该分节，避免用户选了才报错。
    val anonymous = hub.hasAnonymousUpload
    val uploadable = preset.sections.filterNot {
        anonymous && it == ConfigPresetSection.PLUGINS
    }
    var selected by remember { mutableStateOf(uploadable.toSet()) }
    var invalid by remember { mutableStateOf(false) }
    var author by remember { mutableStateOf(hub.login) }

    WindowDialog(
        title = stringResource(R.string.preset_hub_upload),
        summary = stringResource(R.string.preset_hub_upload_summary),
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.dialogScrollable()) {
            Text(
                text = "${hub.repo} · ${hub.tag}",
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
            )
            TextField(
                value = author,
                onValueChange = { author = it.take(20) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = stringResource(R.string.preset_hub_author_hint),
                useLabelAsPlaceholder = true,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(8.dp))
            uploadable.forEach { section ->
                SwitchPreference(
                    checked = section in selected,
                    onCheckedChange = { checked ->
                        selected = if (checked) selected + section else selected - section
                        invalid = false
                    },
                    title = stringResource(section.titleRes),
                    summary = stringResource(section.summaryRes)
                )
            }
            if (anonymous) {
                if (ConfigPresetSection.PLUGINS in preset.sections) {
                    Text(
                        text = stringResource(R.string.preset_hub_anonymous_plugins_blocked),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                    )
                }
            } else if (ConfigPresetSection.PLUGINS in preset.sections) {
                Text(
                    text = stringResource(R.string.preset_hub_upload_secret_warning),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }
            if (invalid) {
                Text(
                    text = stringResource(R.string.preset_hub_upload_empty),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.preset_hub_upload),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        if (selected.isEmpty()) {
                            invalid = true
                        } else {
                            onUpload(
                                preset.copy(
                                    payload = ConfigPresetStore.filteredEnvelope(preset, selected)
                                ),
                                author.trim().ifBlank { "anonymous" }
                            )
                        }
                    }
                )
            }
        }
    }
}

/** 云端设置：仓库 / Release 标签 / 上传 Token。 */@Composable
internal fun PresetHubSettingsDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val context = LocalContext.current
    val initial = remember { PresetHubPreferences.read(context) }
    var repo by remember { mutableStateOf(initial.repo) }
    var tag by remember { mutableStateOf(initial.tag) }
    var token by remember { mutableStateOf(initial.token) }
    var anonymousUrl by remember { mutableStateOf(initial.anonymousUrl) }
    var anonymousBackupUrl by remember { mutableStateOf(initial.anonymousBackupUrl) }
    var invalid by remember { mutableStateOf(false) }

    fun save() {
        val candidate = PresetHubConfig(
            repo = repo.trim(),
            tag = tag.trim(),
            token = token.trim(),
            login = initial.login,
            anonymousUrl = anonymousUrl.trim(),
            anonymousBackupUrl = anonymousBackupUrl.trim()
        )
        if (!candidate.hasRepo ||
            !candidate.anonymousUrlValid ||
            !candidate.anonymousBackupUrlValid
        ) {
            invalid = true
            return
        }
        if (PresetHubPreferences.write(context, candidate)) {
            onSaved()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.preset_hub_save_failed),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    WindowDialog(
        title = stringResource(R.string.preset_hub_settings),
        summary = stringResource(R.string.preset_hub_settings_note),
        show = true,
        onDismissRequest = onDismiss
    ) {
        Column(Modifier.dialogScrollable()) {
            TextField(
                value = repo,
                onValueChange = {
                    repo = it.take(120)
                    invalid = false
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = stringResource(R.string.preset_hub_repo_hint),
                useLabelAsPlaceholder = true,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = tag,
                onValueChange = {
                    tag = it.take(60)
                    invalid = false
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = stringResource(R.string.preset_hub_tag_hint),
                useLabelAsPlaceholder = true,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next)
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = token,
                onValueChange = { token = it.take(255) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = stringResource(R.string.preset_hub_token_hint),
                useLabelAsPlaceholder = true,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                )
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = anonymousUrl,
                onValueChange = {
                    anonymousUrl = it.take(300)
                    invalid = false
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = stringResource(R.string.preset_hub_anonymous_url_hint),
                useLabelAsPlaceholder = true,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                )
            )
            Spacer(Modifier.height(8.dp))
            TextField(
                value = anonymousBackupUrl,
                onValueChange = {
                    anonymousBackupUrl = it.take(300)
                    invalid = false
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = stringResource(R.string.preset_hub_backup_url_hint),
                useLabelAsPlaceholder = true,
                textStyle = TextStyle(
                    color = MiuixTheme.colorScheme.onSurfaceContainerHighest,
                    fontSize = 16.sp
                ),
                cursorBrush = SolidColor(MiuixTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                )
            )
            Text(
                text = stringResource(R.string.preset_hub_anonymous_note),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )
            Text(
                text = stringResource(R.string.preset_hub_backup_note),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            Text(
                text = stringResource(R.string.preset_hub_token_note),
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
            )
            if (invalid) {
                Text(
                    text = stringResource(
                        if ((anonymousUrl.isNotBlank() &&
                                !anonymousUrl.trim().startsWith("https://")) ||
                            (anonymousBackupUrl.isNotBlank() &&
                                !anonymousBackupUrl.trim().startsWith("https://"))
                        ) {
                            R.string.preset_hub_settings_invalid_url
                        } else {
                            R.string.preset_hub_settings_invalid
                        }
                    ),
                    fontSize = 12.sp,
                    color = MiuixTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            Row(Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = onDismiss
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.action_save),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { save() }
                )
            }
        }
    }
}

/** 云端分区标题 + 同步状态 + 刷新/设置入口（长列表里也比滚到底再操作顺手）。 */
@Composable
internal fun PresetHubSectionHeader(
    subtitle: String,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.preset_hub_cloud_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (refreshing) {
            TextButton(
                text = stringResource(R.string.preset_hub_refresh_action),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onRefresh
            )
        } else {
            TextButton(
                text = stringResource(R.string.preset_hub_refresh_action),
                onClick = onRefresh
            )
        }
        TextButton(
            text = stringResource(R.string.preset_hub_settings_action),
            onClick = onSettings
        )
    }
}

/** 云端空态/加载/错误共用的提示卡片。 */
@Composable
internal fun PresetHubStatusCard(
    title: String,
    summary: String,
    onClick: (() -> Unit)? = null
) {
    SettingsCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceContainerHighest
            )
            Text(
                text = summary,
                fontSize = 12.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** 详情页加载中的占位。 */
@Composable
internal fun ConfigPresetLoadingScreen(onBack: () -> Unit) {
    Scaffold(
        containerColor = appSurfaceColor(),
        topBar = { AppTopBar(title = stringResource(R.string.config_preset_title), onBack = onBack) }
    ) { padding ->
        Text(
            text = stringResource(R.string.config_preset_loading),
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(
                start = 20.dp,
                top = padding.calculateTopPadding() + 20.dp
            )
        )
    }
}
