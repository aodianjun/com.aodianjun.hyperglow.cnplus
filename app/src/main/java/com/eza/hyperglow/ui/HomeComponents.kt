package com.eza.hyperglow.ui

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.BuildConfig
import com.eza.hyperglow.R
import com.eza.hyperglow.customization.SceneCompiler
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * Home hero section (HyperHome 风格):顶部的模块运行状态卡 + 两个 surface 统计卡 + 系统信息卡。
 * 参照 HyperHome 的主页布局:状态卡用彩色背景 + 大号半透明图标,AOD/锁屏两个统计卡并排,
 * 下方为系统信息列表。统计卡可点击,直达对应曲面的外观编辑器。
 */
@Composable
internal fun HomeOverviewHero(
    working: Boolean,
    supportLabel: String,
    aodEnabled: Boolean,
    lockscreenEnabled: Boolean,
    systemUiVersion: String,
    aodVersion: String,
    onOpenSurface: (String) -> Unit
) {
    val context = LocalContext.current
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
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenSurface(SceneCompiler.SURFACE_AOD) }
                )
                HomeStatCard(
                    title = stringResource(R.string.label_lockscreen_lyrics),
                    value = homeSurfaceState(context, working, lockscreenEnabled),
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenSurface(SceneCompiler.SURFACE_LOCKSCREEN) }
                )
            }
        }
        Card(
            colors = CardDefaults.defaultColors(
                color = appCardContainerColor(),
                contentColor = appControlContentColor(MiuixTheme.colorScheme.onSurfaceContainer)
            )
        ) {
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
                HomeInfoRow(
                    stringResource(R.string.label_android_version),
                    Build.VERSION.RELEASE
                )
                HomeInfoRow(stringResource(R.string.label_device_model), Build.MODEL, last = true)
            }
        }
    }
}

/**
 * 关于页主卡(样式参照 HyperCeiler 关于页):居中 App 图标 + 名称 + 版本号,下接检查更新;
 * 另附设备信息卡(设备型号 / Android 版本 / 系统界面 · 息屏)。所有卡片走 [SettingsCard],
 * 与其余页面保持同一边距(修复此前卡片满宽无左右边距的显示问题)。
 */
@Composable
internal fun AboutHeroCard(systemUiVersion: String, aodVersion: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checkingUpdate by remember { mutableStateOf(false) }
    var updateDialogVersion by remember { mutableStateOf<String?>(null) }

    fun startCheckUpdate() {
        if (checkingUpdate) return
        checkingUpdate = true
        scope.launch(Dispatchers.IO) {
            val tag = queryLatestReleaseTag()
            val latest = tag?.substringAfterLast('-')
            withContext(Dispatchers.Main) {
                checkingUpdate = false
                when {
                    latest == null ->
                        Toast.makeText(context, R.string.update_check_failed, Toast.LENGTH_SHORT).show()
                    compareVersions(latest, BuildConfig.VERSION_NAME) > 0 ->
                        updateDialogVersion = latest
                    else ->
                        Toast.makeText(context, R.string.update_check_up_to_date, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val appIcon = rememberAppIconBitmap()
    SettingsCard {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (appIcon != null) {
                Image(
                    bitmap = appIcon,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp).clip(RoundedCornerShape(18.dp))
                )
            } else {
                Image(
                    painter = painterResource(R.drawable.ic_launcher),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp)
                )
            }
            Text(
                stringResource(R.string.app_name),
                fontSize = MiuixTheme.textStyles.title3.fontSize,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp)
            )
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp)
            )
            Spacer(Modifier.height(12.dp))
            HomeUpdateRow(
                checking = checkingUpdate,
                onClick = { startCheckUpdate() }
            )
        }
    }

    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            HomeInfoRow(stringResource(R.string.label_device_model), Build.MODEL)
            HomeInfoRow(stringResource(R.string.label_android_version), Build.VERSION.RELEASE)
            HomeInfoRow(
                stringResource(R.string.label_systemui_aod),
                "$systemUiVersion / $aodVersion",
                last = true
            )
        }
    }

    updateDialogVersion?.let { version ->
        WindowDialog(
            title = stringResource(R.string.update_dialog_title),
            summary = stringResource(R.string.update_dialog_summary, version),
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

/**
 * 关于页应用图标:直接取系统解析的应用图标(自适应图标 background+foreground 由系统合成,
 * 与桌面/系统设置里看到的始终一致)——换图标后无需改这里,不再硬编码 drawable。
 * 渲染失败(理论不会)时由调用方回落 [R.drawable.ic_launcher]。
 */
@Composable
private fun rememberAppIconBitmap(sizePx: Int = 216): ImageBitmap? {
    val context = LocalContext.current
    return remember(sizePx) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val bitmap = android.graphics.Bitmap.createBitmap(
                sizePx,
                sizePx,
                android.graphics.Bitmap.Config.ARGB_8888
            )
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, sizePx, sizePx)
            drawable.draw(canvas)
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}

/** 作者信息与头像;头像直连 avatars CDN(github.com 域国内常不可达,重定向会超时)。 */
private const val AUTHOR_GITHUB_URL = "https://github.com/aodianjun"
private const val AUTHOR_AVATAR_URL = "https://avatars.githubusercontent.com/u/130821781"
private const val AUTHOR_NAME = "凹点菌"
private const val AUTHOR_HANDLE = "@aodianjun"

/** 作者头像异步拉取(失败时为 null,由调用方回落 APK 内置头像)。 */
@Composable
private fun rememberAuthorAvatar(url: String): ImageBitmap? {
    var avatar by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        avatar = withContext(Dispatchers.IO) {
            fetchImageBitmap(url)?.asImageBitmap()
        }
    }
    return avatar
}

/**
 * 关于页作者卡(参照 HyperCeiler 关于页作者条目):GitHub 头像 + 昵称 + @handle,
 * 整行可点击打开作者主页;头像未取到时显示纯色圆占位。
 */
@Composable
internal fun AboutAuthorCard() {
    val context = LocalContext.current
    val avatar = rememberAuthorAvatar(AUTHOR_AVATAR_URL)
    SettingsCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { openExternalUrl(context, AUTHOR_GITHUB_URL) }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center
            ) {
                if (avatar != null) {
                    Image(
                        bitmap = avatar,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 网络拉取失败时的兜底:APK 内置作者头像,保证条目永远有图。
                    Image(
                        painter = painterResource(R.drawable.author_avatar),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    AUTHOR_NAME,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    stringResource(R.string.about_author_role, AUTHOR_HANDLE),
                    fontSize = MiuixTheme.textStyles.body2.fontSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp)
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
    // 语义色只做点缀:背景在主题 surface 上低透明度混合,深浅模式下都不出现整块刺眼浅色;
    // 文字走主题 onSurface,深色模式自动反相。背景图片生效时随卡片玻璃化,让壁纸透出。
    val statusBackground = appGlassSurface(lerp(MiuixTheme.colorScheme.surface, statusColor, 0.12f))
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
                    color = MiuixTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    supportLabel,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 2.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HomeStatCard(
    title: String,
    value: String,
    modifier: Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() },
        colors = CardDefaults.defaultColors(
            color = appCardContainerColor(),
            contentColor = appControlContentColor(MiuixTheme.colorScheme.onSurfaceContainer)
        )
    ) {
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
