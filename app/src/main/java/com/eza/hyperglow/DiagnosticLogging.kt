package com.eza.hyperglow

import android.content.Context
import com.eza.hyperglow.aod.AodRenderPreferences
import com.eza.hyperglow.aod.AodStateBridge
import com.eza.hyperglow.customization.CompiledCustomization
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.root.projection.currentProcessUserId

internal fun diagnosticLoggingEnabled(available: Boolean, requested: Boolean): Boolean =
    available && requested

internal object DiagnosticLoggingRuntime {
    @Volatile
    private var requested = false

    val enabled: Boolean
        get() = diagnosticLoggingEnabled(BuildConfig.TRACE_LOGGING_AVAILABLE, requested)

    fun setEnabled(enabled: Boolean) {
        requested = enabled
    }
}

internal object DiagnosticLoggingPreferences {
    private const val PREFS = "diagnostics"
    private const val KEY_DIAGNOSTIC_LOGGING = "diagnostic_logging"
    private const val KEY_LOG_RETENTION_DAYS = "log_retention_days"
    private const val KEY_LOG_LEVEL = "log_level"

    fun read(context: Context): Boolean = diagnosticLoggingEnabled(
        available = BuildConfig.TRACE_LOGGING_AVAILABLE,
        requested = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_DIAGNOSTIC_LOGGING, false)
    )

    fun write(context: Context, enabled: Boolean): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(
                KEY_DIAGNOSTIC_LOGGING,
                diagnosticLoggingEnabled(BuildConfig.TRACE_LOGGING_AVAILABLE, enabled)
            )
            .commit()

    fun readRetentionDays(context: Context): Int = normalizeLogRetentionDays(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_LOG_RETENTION_DAYS, DiagnosticTraceFile.DEFAULT_RETENTION_DAYS)
    )

    fun writeRetentionDays(context: Context, days: Int): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LOG_RETENTION_DAYS, normalizeLogRetentionDays(days))
            .commit()

    fun readLevel(context: Context): DiagnosticLogLevel = normalizeDiagnosticLogLevel(
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOG_LEVEL, null)
    )

    fun writeLevel(context: Context, level: DiagnosticLogLevel): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LOG_LEVEL, level.wire)
            .commit()
}

internal fun setDiagnosticLogging(context: Context, enabled: Boolean): Boolean {
    if (!DiagnosticLoggingPreferences.write(context, enabled)) return false
    syncDiagnosticLoggingRuntime(context)
    AodStateBridge.publishConfiguration(
        RuntimeCustomization.loadCompiled(context),
        currentProcessUserId(),
        experimentalMode = AodRenderPreferences.read(context).experimentalMode
    )
    return true
}

/**
 * 日志等级写入:只过滤镜像的写入阈值,不动总闸开关,也不改变镜像目录是否启用。
 * 详细档额外拉起 SystemUI 侧 logcat 镜像(见 [DiagnosticSystemUiMirror])。
 */
internal fun setDiagnosticLogLevel(context: Context, level: DiagnosticLogLevel): Boolean {
    if (!DiagnosticLoggingPreferences.writeLevel(context, level)) return false
    syncDiagnosticLoggingRuntime(context)
    return true
}

/**
 * 把持久化偏好落到各运行时持有者:总闸开关 → 镜像目录,日志等级 → 写入下限,
 * 详细档 → SystemUI 侧 logcat 镜像的启停。启动与每次设置变更都走这一条路径。
 */
internal fun syncDiagnosticLoggingRuntime(context: Context) {
    val effective = DiagnosticLoggingPreferences.read(context)
    DiagnosticLoggingRuntime.setEnabled(effective)
    DiagnosticTraceFile.setDirectory(context.applicationContext.filesDir.takeIf { effective })
    val level = DiagnosticLoggingPreferences.readLevel(context)
    DiagnosticTraceFile.setMinSeverity(level.minSeverity)
    DiagnosticSystemUiMirror.sync(effective && level.mirrorsSystemUiLogs)
}

internal object RuntimeCustomization {
    fun loadCompiled(context: Context): CompiledCustomization {
        val preferences = AodRenderPreferences.read(context)
        val compiled = CustomizationRepository.loadCompiled(context)
        // 将"实时时钟跟随"开关(息屏行为设置里)合并进 AOD 渲染 profile,
        // 使 hook 端通过 CompiledSurfaceProfile.aodClockFollow 读取该开关并生效。
        val aodKey = com.eza.hyperglow.customization.SceneCompiler.SURFACE_AOD
        val aod = compiled.profiles[aodKey]
        val merged = if (aod != null && preferences.aodClockFollow != aod.aodClockFollow) {
            compiled.copy(
                profiles = compiled.profiles + (aodKey to aod.copy(aodClockFollow = preferences.aodClockFollow))
            )
        } else {
            compiled
        }
        return withDiagnosticLogging(
            merged,
            DiagnosticLoggingPreferences.read(context),
            pauseLingerMs = preferences.pauseLingerMs,
            pauseShowContent = preferences.pauseShowContent,
            lockscreenKeepAwake = preferences.lockscreenKeepAwake,
            raiseToAod = preferences.raiseToAod,
            suppressLockscreenEditorLongPress = preferences.suppressLockscreenEditorLongPress,
            aodBrightnessBoost = preferences.aodBrightnessBoost,
            aodBrightnessOverride = preferences.aodBrightnessOverride,
            aodBrightnessLevel = preferences.aodBrightnessLevel,
            aodClockYOffset = preferences.aodClockYOffset,
            aodRefreshRateCap = preferences.aodRefreshRateCap,
            aodPowerSaver = preferences.aodPowerSaver
        )
    }

    fun compile(
        document: CustomizationDocument,
        diagnosticLogging: Boolean,
        pauseLingerMs: Long = 5_000L,
        pauseShowContent: Boolean = false,
        lockscreenKeepAwake: Boolean = false,
        raiseToAod: Boolean = false,
        suppressLockscreenEditorLongPress: Boolean = false
    ): CompiledCustomization = withDiagnosticLogging(
        SceneCompiler.compile(document),
        diagnosticLogging,
        pauseLingerMs = pauseLingerMs,
        pauseShowContent = pauseShowContent,
        lockscreenKeepAwake = lockscreenKeepAwake,
        raiseToAod = raiseToAod,
        suppressLockscreenEditorLongPress = suppressLockscreenEditorLongPress
    )

    internal fun withDiagnosticLogging(
        configuration: CompiledCustomization,
        diagnosticLogging: Boolean,
        available: Boolean = BuildConfig.TRACE_LOGGING_AVAILABLE,
        pauseLingerMs: Long = configuration.pauseLingerMs,
        pauseShowContent: Boolean = configuration.pauseShowContent,
        lockscreenKeepAwake: Boolean = configuration.lockscreenKeepAwake,
        raiseToAod: Boolean = configuration.raiseToAod,
        suppressLockscreenEditorLongPress: Boolean =
            configuration.suppressLockscreenEditorLongPress,
        aodBrightnessBoost: Boolean = configuration.aodBrightnessBoost,
        aodBrightnessOverride: Boolean = configuration.aodBrightnessOverride,
        aodBrightnessLevel: Int = configuration.aodBrightnessLevel,
        aodClockYOffset: Int = configuration.aodClockYOffset,
        aodRefreshRateCap: Int = configuration.aodRefreshRateCap,
        aodPowerSaver: Boolean = configuration.aodPowerSaver
    ): CompiledCustomization = requireNotNull(
        SceneCompiler.finalizeCompiled(
            configuration.copy(
                revision = 0L,
                hash = "",
                diagnosticLogging = diagnosticLoggingEnabled(
                    available,
                    diagnosticLogging
                ),
                pauseLingerMs = com.eza.hyperglow.aod.normalizePauseLingerMs(pauseLingerMs),
                pauseShowContent = pauseShowContent,
                lockscreenKeepAwake = lockscreenKeepAwake,
                raiseToAod = raiseToAod,
                suppressLockscreenEditorLongPress = suppressLockscreenEditorLongPress,
                aodBrightnessBoost = aodBrightnessBoost,
                aodBrightnessOverride = aodBrightnessOverride,
                aodBrightnessLevel = aodBrightnessLevel.coerceIn(
                    com.eza.hyperglow.aod.MIN_AOD_BRIGHTNESS,
                    com.eza.hyperglow.aod.MAX_AOD_BRIGHTNESS
                ),
                aodClockYOffset = com.eza.hyperglow.aod.normalizeAodClockYOffset(aodClockYOffset),
                aodRefreshRateCap = com.eza.hyperglow.aod.normalizeAodRefreshRateCap(aodRefreshRateCap),
                aodPowerSaver = aodPowerSaver
            )
        )
    )
}
