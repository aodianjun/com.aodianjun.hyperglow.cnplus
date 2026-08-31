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
}

internal fun setDiagnosticLogging(context: Context, enabled: Boolean): Boolean {
    if (!DiagnosticLoggingPreferences.write(context, enabled)) return false
    val effective = DiagnosticLoggingPreferences.read(context)
    DiagnosticLoggingRuntime.setEnabled(effective)
    AodStateBridge.publishConfiguration(
        RuntimeCustomization.loadCompiled(context),
        currentProcessUserId(),
        experimentalMode = AodRenderPreferences.read(context).experimentalMode
    )
    return true
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
            suppressLockscreenEditorLongPress = preferences.suppressLockscreenEditorLongPress
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
            configuration.suppressLockscreenEditorLongPress
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
                suppressLockscreenEditorLongPress = suppressLockscreenEditorLongPress
            )
        )
    )
}
