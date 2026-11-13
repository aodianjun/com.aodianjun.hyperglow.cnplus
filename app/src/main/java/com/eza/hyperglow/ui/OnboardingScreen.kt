package com.eza.hyperglow.ui

import android.content.Context
import android.widget.Toast
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.root.capability.XiaomiCapability
import com.eza.hyperglow.root.utils.ShellUtils
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
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
    val scope = rememberCoroutineScope()
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

    // 真设置:这一页直接写歌词显示开关,而不是只告诉用户「有这两个功能」。
    val document = remember(context) { CustomizationRepository.loadDocument(context) }
    val config = remember(context) { AodRenderPreferences.read(context) }
    var aodEnabled by remember {
        mutableStateOf(
            document.profiles[SceneCompiler.SURFACE_AOD]?.enabled ?: config.aodEnabled
        )
    }
    var lockscreenEnabled by remember {
        mutableStateOf(
            document.profiles[SceneCompiler.SURFACE_LOCKSCREEN]?.enabled
                ?: config.lockscreenEnabled
        )
    }
    // 支持情况只用于说明文案;开关本身始终可操作,写入的配置在模块生效后自动应用。
    val effectiveReport = capabilityReport.copy(experimentalModeEnabled = config.experimentalMode)
    val aodSupported = effectiveReport.has(XiaomiCapability.AOD_SURFACE)
    val lockscreenSupported = effectiveReport.has(XiaomiCapability.LOCKSCREEN_HOST) &&
        effectiveReport.has(XiaomiCapability.LOCKSCREEN_GEOMETRY)

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
            Text(
                text = stringResource(R.string.onboarding_display_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
            SwitchPreference(
                aodEnabled,
                { enabled ->
                    if (updateCustomizationSurfaceEnabled(
                            context,
                            SceneCompiler.SURFACE_AOD,
                            enabled
                        )
                    ) {
                        aodEnabled = enabled
                    }
                },
                stringResource(R.string.setting_show_aod),
                summary = if (aodSupported) null
                else stringResource(R.string.summary_show_aod_unsupported)
            )
            SwitchPreference(
                lockscreenEnabled,
                { enabled ->
                    if (updateCustomizationSurfaceEnabled(
                            context,
                            SceneCompiler.SURFACE_LOCKSCREEN,
                            enabled
                        )
                    ) {
                        lockscreenEnabled = enabled
                    }
                },
                stringResource(R.string.setting_show_lockscreen),
                summary = if (lockscreenSupported) null
                else stringResource(R.string.summary_unavailable_systemui_version)
            )
            Spacer(Modifier.height(8.dp))
            TextButton(
                text = stringResource(R.string.onboarding_action_open_lsposed),
                onClick = { openLsposedManager(context) },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary()
            )
            TextButton(
                text = stringResource(R.string.onboarding_action_restart_systemui),
                onClick = {
                    scope.launch {
                        val restarted = runCatching {
                            ShellUtils.restartHookedProcesses(
                                systemUi = true,
                                miuiAod = false,
                                hyperglowApp = false
                            )
                        }.getOrDefault(false)
                        Toast.makeText(
                            context,
                            context.getString(
                                if (restarted) R.string.toast_systemui_restarted
                                else R.string.toast_systemui_restart_failed
                            ),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun LyricSourcePage() {
    // 与状态页完全同源:同一个歌词源卡片(含真实连接状态)、同一个未连接引导
    //(下载地址/通知使用权)、同一个选择对话框,避免引导页只是「展示有这玩意」。
    var showSourceDialog by rememberSaveable { mutableStateOf(false) }
    OnboardingPageColumn {
        StepHero(3)
        OnboardingTitle(stringResource(R.string.onboarding_source_title))
        OnboardingSummary(stringResource(R.string.onboarding_source_summary))
        LyricSourceSection(onOpenSourceDialog = { showSourceDialog = true })
        SourceSetupHint()
    }
    if (showSourceDialog) {
        LyricSourcePickerDialog(onDismiss = { showSourceDialog = false })
    }
}

@Composable
private fun PermissionPage() {
    // 与状态页同一套权限检查组件:通知、前台服务、电池优化、Root(含跳转 Root 管理器)。
    OnboardingPageColumn {
        StepHero(4)
        OnboardingTitle(stringResource(R.string.onboarding_permission_title))
        OnboardingSummary(stringResource(R.string.onboarding_permission_summary))
        SettingsCard {
            PermissionStatusSection()
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
