package com.eza.hyperglow.root.utils

import android.os.Build

/**
 * 平台代际门控。
 *
 * 现场教训：OS4.0.2.0 的 SystemUI 进程里 `Build.VERSION.SDK_INT` 并不是 37，按 API 判断会把
 * 整条 OS4 分支静默跳过（0.3.173 现场日志里一条 `OS4 anchor probe` 都没有）。因此以小米
 * 系统属性 `ro.mi.os.version.name`（形如 `OS4.0.2.0.VNBCNXM`）为准；属性缺失时才回退到
 * API 37。OS3 的取值路径不受影响。
 */
internal object PlatformVersion {
    /** SystemUI 加载后按 OS4 专属接缝确认过的结果；优先级高于属性/API 推断。 */
    @Volatile
    private var confirmedOs4 = false

    val isHyperOs4OrNewer: Boolean
        get() = confirmedOs4 || inferredHyperOs4OrNewer

    /** 现场诊断用：把读到的系统版本串原样带进日志，判定失败时能直接看出属性形态。 */
    val rawVersionName: String? by lazy { systemProperty(HYPER_OS_VERSION_PROPERTY) }

    /**
     * 属性名在部分构建上不可读。KeyguardPanelViewController 的
     * `access$updateNotificationState` 是 OS4.0.2.0 独有的访问器（OS3 是实例方法
     * `onUpdateNotificationState`），拿它做一次类加载探针即可确认代际。
     */
    fun confirmFromSystemUiClassLoader(loader: ClassLoader) {
        if (confirmedOs4) return
        val os4Accessor = runCatching {
            Class.forName(KEYGUARD_PANEL_CONTROLLER, false, loader)
                .declaredMethods
                .any { it.name.startsWith("access$") && it.name.contains("updateNotificationState") }
        }.getOrDefault(false)
        if (os4Accessor) confirmedOs4 = true
    }

    private val inferredHyperOs4OrNewer: Boolean by lazy { detectHyperOs4OrNewer() }

    private fun detectHyperOs4OrNewer(): Boolean {
        val name = systemProperty(HYPER_OS_VERSION_PROPERTY)
        if (!name.isNullOrBlank()) {
            val major = OS_MAJOR.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: BARE_MAJOR.find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (major != null) return major >= 4
        }
        return Build.VERSION.SDK_INT >= HYPER_OS_4_API
    }

    private fun systemProperty(key: String): String? = runCatching {
        (Class.forName("android.os.SystemProperties")
            .getDeclaredMethod("get", String::class.java)
            .invoke(null, key) as? String)?.trim()
    }.getOrNull()

    private const val HYPER_OS_VERSION_PROPERTY = "ro.mi.os.version.name"
    private const val HYPER_OS_4_API = 37
    private const val KEYGUARD_PANEL_CONTROLLER =
        "com.android.keyguard.panel.KeyguardPanelViewController"

    /** `OS4.0.2.0...` / `HyperOS 4...` 都取第一个数字。 */
    private val OS_MAJOR = Regex("""(?:OS|HyperOS)\s*(\d+)""", RegexOption.IGNORE_CASE)

    /** 少数构建只给裸版本号 `4.0.2.0`。 */
    private val BARE_MAJOR = Regex("""^\s*(\d+)\.\d+""")
}
