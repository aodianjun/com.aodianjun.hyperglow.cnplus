package com.eza.hyperglow.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** Home 概览卡片组件:运行状态卡、AOD/锁屏统计卡、系统信息列表与更新检查。 */

/**
 * Home hero section (HyperHome 风格):顶部的模块运行状态卡 + 两个 surface 统计卡 + 系统信息卡。
 * 参照 HyperHome 的主页布局:状态卡用彩色背景 + 大号半透明图标,AOD/锁屏两个统计卡并排,
 * 下方为系统信息列表。
 */
@Composable
internal fun HomeOverviewHero(
    working: Boolean,
    supportLabel: String,
    aodEnabled: Boolean,
    lockscreenEnabled: Boolean,
    systemUiVersion: String,
    aodVersion: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateDialogVersion by remember { mutableStateOf<String?>(null) }
    var updateMismatch by remember { mutableStateOf(false) }

    fun startCheckUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch(Dispatchers.IO) {
            val info = queryLatestReleaseInfo()
            val latest = info?.tag?.substringAfterLast('-')
            val localHash = localApkSha256(context)
            withContext(Dispatchers.Main) {
                checkingUpdate = false
                when {
                    info == null || latest == null ->
                        Toast.makeText(context, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                    // 哈希校验:release 能取到资产指纹,且本地安装 APK 指纹不在其中
                    // → 安装的不是官方发布,视为"不是最新版",引导前往发布页下载。
                    info.sha256.isNotEmpty() && localHash != null && localHash !in info.sha256 -> {
                        updateDialogVersion = latest
                        updateMismatch = true
                    }
                    compareVersions(latest, BuildConfig.VERSION_NAME) > 0 -> {
                        updateDialogVersion = latest
                        updateMismatch = false
                    }
                    else ->
                        Toast.makeText(context, R.string.update_check_up_to_date, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column(
        modifier = Modifier.padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HomeStatusCard(
                working = working,
                supportLabel = supportLabel,
                modifier = Modifier.weight(1f).aspectRatio(1f)
            )
            Column(
                Modifier.weight(1f).aspectRatio(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HomeStatCard(
                    title = stringResource(R.string.label_aod_lyrics),
                    value = homeSurfaceState(context, working, aodEnabled),
                    modifier = Modifier.weight(1f)
                )
                HomeStatCard(
                    title = stringResource(R.string.label_lockscreen_lyrics),
                    value = homeSurfaceState(context, working, lockscreenEnabled),
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Card {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                HomeInfoRow(stringResource(R.string.label_compatibility), supportLabel)
                HomeInfoRow(
                    stringResource(R.string.label_systemui_aod),
                    "$systemUiVersion / $aodVersion"
                )
                HomeInfoRow(
                    stringResource(R.string.label_app_version),
                    BuildConfig.VERSION_NAME
                )
                HomeUpdateRow(
                    checking = checkingUpdate,
                    onClick = { startCheckUpdate() }
                )
                HomeInfoRow(
                    stringResource(R.string.label_android_version),
                    Build.VERSION.RELEASE
                )
                HomeInfoRow(stringResource(R.string.label_device_model), Build.MODEL, last = true)
            }
        }
    }

    updateDialogVersion?.let { version ->
        WindowDialog(
            title = stringResource(
                if (updateMismatch) R.string.update_mismatch_dialog_title
                else R.string.update_dialog_title
            ),
            summary = if (updateMismatch) {
                stringResource(R.string.update_mismatch_dialog_summary)
            } else {
                stringResource(R.string.update_dialog_summary, version)
            },
            show = true,
            onDismissRequest = { updateDialogVersion = null }
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    text = stringResource(R.string.action_cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { updateDialogVersion = null }
                )
                Spacer(Modifier.width(20.dp))
                TextButton(
                    text = stringResource(R.string.action_download),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        updateDialogVersion = null
                        openExternalUrl(
                            context,
                            "https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus/releases/latest"
                        )
                    }
                )
            }
        }
    }
}

@Composable
private fun HomeUpdateRow(checking: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !checking, onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Text(
            stringResource(R.string.action_check_update),
            fontSize = MiuixTheme.textStyles.headline1.fontSize,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = stringResource(if (checking) R.string.update_checking else R.string.action_check_update_short),
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.align(Alignment.CenterVertically)
        )
    }
}

@Composable
private fun HomeStatusCard(working: Boolean, supportLabel: String, modifier: Modifier) {
    val statusColor = if (working) ComposeColor(0xFF36D167) else ComposeColor(0xFFFF5A52)
    val statusBackground = if (working) ComposeColor(0xFFDFFAE4) else ComposeColor(0xFFFFE5E3)
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = statusBackground)
    ) {
        Box(Modifier.fillMaxSize()) {
            // 背景大图标:固定在卡片右下角,并裁剪到卡片内,避免超出卡片宽度/高度
            Box(
                Modifier.fillMaxSize().clipToBounds(),
                contentAlignment = Alignment.BottomEnd
            ) {
                Icon(
                    imageVector = MiuixIcons.Copy,
                    contentDescription = null,
                    tint = statusColor.copy(alpha = 0.78f),
                    modifier = Modifier.size(120.dp).padding(end = 4.dp, bottom = 4.dp)
                )
            }
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    stringResource(if (working) R.string.home_working else R.string.home_not_working),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ComposeColor(0xFF101010),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    supportLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = ComposeColor(0xFF2F3A32).copy(alpha = 0.78f),
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HomeStatCard(title: String, value: String, modifier: Modifier) {
    Card(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Text(
                value,
                fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 2.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun HomeInfoRow(title: String, content: String, last: Boolean = false) {
    Text(
        title,
        fontSize = MiuixTheme.textStyles.headline1.fontSize,
        fontWeight = FontWeight.Medium
    )
    Text(
        content,
        fontSize = MiuixTheme.textStyles.body2.fontSize,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(top = 2.dp, bottom = if (last) 0.dp else 16.dp)
    )
}

private fun homeSurfaceState(
    context: android.content.Context,
    working: Boolean,
    enabled: Boolean
): String = if (!working) {
    context.getString(R.string.runtime_unavailable)
} else {
    context.getString(if (enabled) R.string.runtime_enabled else R.string.runtime_disabled)
}
