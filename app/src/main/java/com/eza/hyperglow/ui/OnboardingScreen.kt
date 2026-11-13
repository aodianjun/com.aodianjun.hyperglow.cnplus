package com.eza.hyperglow.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eza.hyperglow.R
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.XiaomiCapabilityStore
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首次启动引导的完成标记。独立于其余设置且不参与配置导入/导出:
 * 升级安装时若已有任意配置,视为老用户,不再弹一次新手引导。
 */
internal object OnboardingPreferences {
    private const val PREFS = "hyperglow_onboarding"
    private const val KEY_COMPLETED = "completed"
    private const val APP_UI_PREFS = "app_ui_appearance"
    private const val APP_NAVIGATION_PREFS = "app_navigation"

    fun shouldShow(context: Context): Boolean =
        !isCompleted(context) && !hasExistingConfiguration(context)

    fun markCompleted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_COMPLETED, true)
            .apply()
    }

    private fun isCompleted(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETED, false)

    /**
     * 只看用户在界面上改过设置才会写入的偏好文件。surface_customization 在应用启动时
     * 由文档迁移自动落盘、xiaomi_capabilities 由 SystemUI 上报写入,都不能用来区分新老用户。
     */
    private fun hasExistingConfiguration(context: Context): Boolean = listOf(
        AodRenderPreferences.PREFS,
        APP_UI_PREFS,
        APP_NAVIGATION_PREFS
    ).any { name ->
        runCatching {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isNotEmpty()
        }.getOrDefault(false)
    }
}

private const val ONBOARDING_PAGE_COUNT = 4

/** 状态色按当前主题明度取深浅两档,保证小字号文字在深浅模式下都有足够对比度。 */
@Composable
private fun onboardingSuccessColor(): Color =
    if (MiuixTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFF1E8E3E)
    } else {
        Color(0xFF4CD964)
    }

@Composable
private fun onboardingWarningColor(): Color =
    if (MiuixTheme.colorScheme.surface.luminance() > 0.5f) {
        Color(0xFF9A6700)
    } else {
        Color(0xFFF5A524)
    }

/**
 * 首次启动引导:欢迎、启用模块、选择歌词源、检查权限四页。
 * 作为 MainActivity 的全屏覆盖层显示在主页之上,完成或跳过后写入 [OnboardingPreferences]。
 *
 * [onBack] 非空时顶栏显示返回按钮(从设置里重新打开引导时使用);首次启动不传,只能完成或跳过。
 */
@Composable
internal fun OnboardingScreen(onFinish: () -> Unit, onBack: (() -> Unit)? = null) {
    val pagerState = rememberPagerState { ONBOARDING_PAGE_COUNT }
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == ONBOARDING_PAGE_COUNT - 1

    Scaffold(
        containerColor = appOverlaySurfaceColor(),
        topBar = {
            AppTopBar(
                title = stringResource(R.string.app_name),
                onBack = onBack,
                overlay = true,
                actions = {
                    if (!isLastPage) {
                        TextButton(
                            text = stringResource(R.string.onboarding_skip),
                            onClick = onFinish
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.Top
            ) { page ->
                when (page) {
                    0 -> WelcomePage()
                    1 -> ModulePage()
                    2 -> LyricSourcePage()
                    else -> PermissionPage()
                }
            }
            OnboardingFooter(
                pageCount = ONBOARDING_PAGE_COUNT,
                currentPage = pagerState.currentPage,
                primaryLabel = stringResource(
                    if (isLastPage) R.string.onboarding_start else R.string.onboarding_next
                ),
                onPrimary = {
                    if (isLastPage) {
                        onFinish()
                    } else {
                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    }
                }
            )
        }
    }
}

@Composable
private fun OnboardingPageColumn(content: @Composable ColumnScope.() -> Unit) {
    // 内容不满一屏时垂直居中,超出时(小屏/大字号)整页可滚动。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content
        )
    }
}

@Composable
private fun StepHero(step: Int) {
    Box(
        modifier = Modifier
            .size(104.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = step.toString(),
            fontSize = 44.sp,
            fontWeight = FontWeight.SemiBold,
            color = MiuixTheme.colorScheme.primary
        )
    }
}

@Composable
private fun OnboardingTitle(text: String) {
    Text(
        text = text,
        fontSize = 26.sp,
        fontWeight = FontWeight.SemiBold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 24.dp)
    )
}

@Composable
private fun OnboardingSummary(text: String) {
    Text(
        text = text,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 12.dp)
    )
}

@Composable
private fun OnboardingCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        colors = CardDefaults.defaultColors(
            color = appCardContainerColor(),
            contentColor = appControlContentColor(MiuixTheme.colorScheme.onSurfaceContainer)
        )
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) { content() }
    }
}

@Composable
private fun WelcomePage() {
    OnboardingPageColumn {
        Image(
            painter = painterResource(R.drawable.ic_launcher),
            contentDescription = null,
            modifier = Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(28.dp))
        )
        OnboardingTitle(stringResource(R.string.onboarding_welcome_title))
        OnboardingSummary(stringResource(R.string.onboarding_welcome_summary))
        OnboardingCard {
            Text(
                text = stringResource(R.string.onboarding_requirements_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            Spacer(Modifier.height(8.dp))
            RequirementRow(stringResource(R.string.onboarding_requirement_root))
            RequirementRow(stringResource(R.string.onboarding_requirement_lsposed))
            RequirementRow(stringResource(R.string.onboarding_requirement_source))
        }
    }
}

@Composable
private fun RequirementRow(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
        )
    }
}

@Composable
private fun ModulePage() {
    val context = LocalContext.current
    var capabilityReport by remember { mutableStateOf(XiaomiCapabilityStore.read(context)) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(XiaomiCapabilityStore.PREFS, Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            capabilityReport = XiaomiCapabilityStore.read(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val detected = capabilityReport.hasReport
    val statusColor = if (detected) onboardingSuccessColor() else onboardingWarningColor()

    OnboardingPageColumn {
        StepHero(2)
        OnboardingTitle(stringResource(R.string.onboarding_module_title))
        OnboardingSummary(stringResource(R.string.onboarding_module_summary))
        OnboardingCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.onboarding_module_status),
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f)
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(
                        if (detected) R.string.onboarding_module_detected
                        else R.string.onboarding_module_pending
                    ),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = statusColor
                )
            }
            if (!detected) {
                Text(
                    text = stringResource(R.string.onboarding_module_restart_hint),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            TextButton(
                text = stringResource(R.string.onboarding_action_open_lsposed),
                onClick = { openLsposedManager(context) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

@Composable
private fun LyricSourcePage() {
    val sources = listOf(
        R.string.lyric_source_lyricon to R.string.summary_lyric_source_lyricon,
        R.string.lyric_source_superlyric to R.string.summary_lyric_source_superlyric,
        R.string.lyric_source_lyricinfo to R.string.summary_lyric_source_lyricinfo,
        R.string.lyric_source_spicy to R.string.summary_lyric_source_spicy
    )
    OnboardingPageColumn {
        StepHero(3)
        OnboardingTitle(stringResource(R.string.onboarding_source_title))
        OnboardingSummary(stringResource(R.string.onboarding_source_summary))
        OnboardingCard {
            sources.forEachIndexed { index, (nameRes, summaryRes) ->
                if (index > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp)
                            .height(0.5.dp)
                            .background(
                                MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                    )
                }
                Text(
                    text = stringResource(nameRes),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = stringResource(summaryRes),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionPage() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var notificationGranted by remember { mutableStateOf(checkNotificationPermission(context)) }
    var batteryWhitelisted by remember {
        mutableStateOf(checkBatteryOptimizationWhitelisted(context))
    }
    var rootGranted by remember { mutableStateOf(false) }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        notificationGranted = checkNotificationPermission(context)
        batteryWhitelisted = checkBatteryOptimizationWhitelisted(context)
    }

    LaunchedEffect(Unit) {
        notificationGranted = checkNotificationPermission(context)
        batteryWhitelisted = checkBatteryOptimizationWhitelisted(context)
        rootGranted = checkRootGranted()
    }

    OnboardingPageColumn {
        StepHero(4)
        OnboardingTitle(stringResource(R.string.onboarding_permission_title))
        OnboardingSummary(stringResource(R.string.onboarding_permission_summary))
        OnboardingCard {
            PermissionStatusRow(
                title = stringResource(R.string.label_notification_permission),
                status = stringResource(
                    if (notificationGranted) R.string.summary_notification_permission_granted
                    else R.string.summary_notification_permission_denied
                ),
                granted = notificationGranted,
                actionLabel = stringResource(R.string.action_grant_notification_permission),
                onAction = {
                    settingsLauncher.launch(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        }
                    )
                }
            )
            PermissionStatusRow(
                title = stringResource(R.string.label_battery_optimization),
                status = stringResource(
                    if (batteryWhitelisted) R.string.summary_battery_optimization_whitelisted
                    else R.string.summary_battery_optimization_optimized
                ),
                granted = batteryWhitelisted,
                actionLabel = stringResource(R.string.action_open_battery_settings),
                onAction = {
                    val requested = runCatching {
                        settingsLauncher.launch(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                        )
                    }.isSuccess
                    if (!requested) {
                        runCatching {
                            settingsLauncher.launch(
                                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            )
                        }
                    }
                }
            )
            PermissionStatusRow(
                title = stringResource(R.string.label_root_permission),
                status = stringResource(
                    if (rootGranted) R.string.summary_root_permission_granted
                    else R.string.summary_root_permission_denied
                ),
                granted = rootGranted,
                actionLabel = stringResource(R.string.onboarding_action_grant_root),
                onAction = {
                    scope.launch {
                        requestRootAccess()
                        rootGranted = checkRootGranted()
                    }
                }
            )
        }
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    status: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                text = status,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = if (granted) onboardingSuccessColor()
                else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (!granted) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = actionLabel,
                onClick = onAction,
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
        }
    }
}

@Composable
private fun OnboardingFooter(
    pageCount: Int,
    currentPage: Int,
    primaryLabel: String,
    onPrimary: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            repeat(pageCount) { index ->
                val selected = index == currentPage
                val width by animateDpAsState(
                    targetValue = if (selected) 20.dp else 7.dp,
                    label = "onboardingDotWidth"
                )
                Box(
                    modifier = Modifier
                        .height(7.dp)
                        .width(width)
                        .clip(CircleShape)
                        .background(
                            if (selected) MiuixTheme.colorScheme.primary
                            else MiuixTheme.colorScheme.onSurface.copy(alpha = 0.16f)
                        )
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onPrimary,
            modifier = Modifier.fillMaxWidth(),
            minHeight = 50.dp,
            colors = ButtonDefaults.buttonColorsPrimary()
        ) {
            Text(
                text = primaryLabel,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onPrimary
            )
        }
    }
}
