package com.eza.hyperglow.root.spectrum

import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Binder
import com.eza.hyperglow.root.HookInstallGuard
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.root.HookRegistry
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field

/**
 * 移植自 leset0ng/hyperwave 的 FullRateCaptureHooks（Apache-2.0），改为 HyperGlow
 * 的 libxposed 运行时。
 *
 * 只做一件事：让 SystemUI/miui.aod 构建的「USAGE_MEDIA + 48k float mono +
 * routeFlags=3」AudioMix 通过 framework 的格式/策略注册校验；调用方 UID 限定为
 * SystemUI 或 miui.aod。无对应类/方法时 fail-closed，回退系统行为。
 */
internal object FullRateCaptureHooks {
    private const val FEATURE = "spectrum-loopback"
    private const val AUDIO_MIX = "android.media.audiopolicy.AudioMix"
    private const val AUDIO_MIX_BUILDER = "android.media.audiopolicy.AudioMix\$Builder"
    private const val AUDIO_POLICY_CONFIG = "android.media.audiopolicy.AudioPolicyConfig"
    private const val AUDIO_SERVICE = "com.android.server.audio.AudioService"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val MIUI_AOD = "com.miui.aod"

    private val scope = CaptureFormatScope()
    private val installGuard = HookInstallGuard()
    @Volatile
    private var systemUiUid = -1
    @Volatile
    private var miuiAodUid = -1

    fun install(module: XposedModule, classLoader: ClassLoader, systemServer: Boolean) {
        val completed = installGuard.runOnce {
            val mixInstalled = runCatching {
                installAudioMixHooks(module, classLoader)
            }.getOrElse { error ->
                HookLogger.w("LoopbackHooks", "AudioMix hooks failed", error)
                false
            }
            val policyInstalled = if (systemServer) {
                runCatching {
                    installAudioServiceHook(module, classLoader)
                }.getOrElse { error ->
                    HookLogger.w("LoopbackHooks", "AudioService hook failed", error)
                    false
                }
            } else {
                false
            }
            mixInstalled || policyInstalled
        }
        if (completed) {
            HookLogger.bootstrap(
                "LoopbackHooks",
                "installed systemServer=$systemServer"
            )
        }
    }

    /** Hot reload retires HookRegistry handles; let the new generation install again. */
    fun resetForReload() {
        installGuard.reset()
    }

    private fun installAudioMixHooks(module: XposedModule, classLoader: ClassLoader): Boolean {
        val mixClass = loadClass(AUDIO_MIX, classLoader) ?: return false
        val canUse = runCatching {
            mixClass.getDeclaredMethod("canBeUsedForPrivilegedMediaCapture", AudioFormat::class.java)
        }.getOrNull()
        if (canUse != null) {
            HookRegistry.hook(module, "$FEATURE.validate", canUse, object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    val format = chain.args.getOrNull(0) as? AudioFormat
                    return if (format != null && scope.allows(format)) null else chain.proceed()
                }
            })
        }
        val builderClass = loadClass(AUDIO_MIX_BUILDER, classLoader) ?: return canUse != null
        val build = runCatching { builderClass.getDeclaredMethod("build") }.getOrNull()
            ?: return canUse != null
        HookRegistry.hook(module, "$FEATURE.build", build, object : Hooker {
            override fun intercept(chain: Chain): Any? {
                val builder = chain.thisObject ?: return chain.proceed()
                val shape = inspect(builder) ?: return chain.proceed()
                return if (eligible(shape)) {
                    scope.withFormat(shape.format) { chain.proceed() }
                } else {
                    chain.proceed()
                }
            }
        })
        return true
    }

    private fun installAudioServiceHook(module: XposedModule, classLoader: ClassLoader): Boolean {
        val serviceClass = loadClass(AUDIO_SERVICE, classLoader) ?: return false
        val methods = serviceClass.declaredMethods.filter { it.name == "isPolicyRegisterAllowed" }
        if (methods.isEmpty()) {
            HookLogger.w("LoopbackHooks", "AudioService.isPolicyRegisterAllowed not found")
            return false
        }
        methods.forEachIndexed { index, method ->
            HookRegistry.hook(module, "$FEATURE.policy$index", method, object : Hooker {
                override fun intercept(chain: Chain): Any? {
                    if (!trustedCaptureCaller()) return chain.proceed()
                    val config = chain.args.firstOrNull {
                        it?.javaClass?.name == AUDIO_POLICY_CONFIG
                    } ?: return chain.proceed()
                    val mix = singleMix(config) ?: return chain.proceed()
                    val shape = inspect(mix) ?: return chain.proceed()
                    // 已确认调用方是 SystemUI 且 mix 带我们的 48k 标记：直接放行注册。
                    // 原始校验会再次拒绝非标准 sink 格式；这里只跳过格式门，权限面仍由
                    // 调用方 UID 限定。
                    if (eligible(shape)) {
                        HookLogger.i("LoopbackHooks", "policy registration allowed")
                        return true
                    }
                    return chain.proceed()
                }
            })
        }
        return true
    }

    private fun trustedCaptureCaller(): Boolean {
        val caller = Binder.getCallingUid()
        return caller == resolveUid(SYSTEM_UI, systemUiUid) { systemUiUid = it } ||
            caller == resolveUid(MIUI_AOD, miuiAodUid) { miuiAodUid = it }
    }

    private fun resolveUid(packageName: String, cached: Int, update: (Int) -> Unit): Int {
        if (cached != -1) return cached
        val resolved = runCatching {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val activityThread = activityThreadClass
                .getDeclaredMethod("currentActivityThread")
                .invoke(null)
            val systemContext = activityThreadClass
                .getDeclaredMethod("getSystemContext")
                .invoke(activityThread)
            val packageManager = systemContext.javaClass
                .getMethod("getPackageManager")
                .invoke(systemContext) as android.content.pm.PackageManager
            packageManager.getApplicationInfo(packageName, 0).uid
        }.getOrElse {
            HookLogger.w("LoopbackHooks", "Cannot resolve uid for $packageName", it)
            -1
        }
        update(resolved)
        return resolved
    }

    private fun singleMix(config: Any): Any? {
        val mixes = runCatching {
            config.javaClass.getMethod("getMixes").invoke(config)
        }.getOrNull() ?: return null
        val list = when (mixes) {
            is List<*> -> mixes
            is Array<*> -> mixes.toList()
            else -> return null
        }
        return list.singleOrNull()
    }

    private fun inspect(owner: Any): LoopbackMixShape? {
        val rule = field(owner, "mRule")
        val routes = field(owner, "mRouteFlags") as? Int
        val format = field(owner, "mFormat") as? AudioFormat
        if (routes == null || format == null) return null
        return LoopbackMixShape(rule, routes, format)
    }

    private fun eligible(shape: LoopbackMixShape): Boolean {
        val format = shape.format
        if (format.sampleRate != LoopbackCaptureContract.SAMPLE_RATE) return false
        if (format.encoding != LoopbackCaptureContract.ENCODING) return false
        if (format.channelMask != LoopbackCaptureContract.CHANNEL_MASK) return false
        if (shape.routeFlags != LoopbackCaptureContract.ROUTE_FLAGS) return false
        val rule = shape.rule ?: return false
        val role = intMethod(rule, "getTargetMixRole")
        if (role != null && role != LoopbackCaptureContract.TARGET_MIX_ROLE) return false
        val privileged = booleanMethod(rule, "allowPrivilegedMediaPlaybackCapture")
        if (privileged == false) return false
        val voice = booleanMethod(rule, "voiceCommunicationCaptureAllowed")
        if (voice == true) return false
        // 规则内容能读就要求至少一个 USAGE_MEDIA 匹配项；读不到时退化为仅格式/路由校验，
        // 仍被线程局部 exact-format 作用域限制，不会放行其它 AudioMix。
        val criteria = runCatching {
            rule.javaClass.getMethod("getCriteria").invoke(rule) as? List<*>
        }.getOrNull() ?: return true
        var mediaCriterionSeen = false
        for (criterion in criteria) {
            if (criterion == null) continue
            val ruleId = intMethod(criterion, "getRule") ?: continue
            if (ruleId != 1) continue
            val attributes = runCatching {
                criterion.javaClass.getMethod("getAudioAttributes").invoke(criterion)
            }.getOrNull() as? AudioAttributes ?: continue
            if (attributes.usage != AudioAttributes.USAGE_MEDIA) continue
            mediaCriterionSeen = true
            val tags = runCatching {
                attributes.javaClass.getMethod("getTags").invoke(attributes) as? Set<*>
            }.getOrNull() ?: continue
            if (LoopbackCaptureContract.RULE_TAG in tags) break
        }
        // tag 读取失败时仍接受 USAGE_MEDIA（调用方已限定 SystemUI，格式也完全匹配），
        // 避免个别 ROM 的 AudioAttributes.getTags 差异让整条链路失效。
        return mediaCriterionSeen
    }

    private fun intMethod(target: Any, name: String): Int? = runCatching {
        target.javaClass.getMethod(name).invoke(target) as? Int
    }.getOrNull()

    private fun booleanMethod(target: Any, name: String): Boolean? = runCatching {
        target.javaClass.getMethod(name).invoke(target) as? Boolean
    }.getOrNull()

    private fun field(owner: Any, name: String): Any? {
        var current: Class<*>? = owner.javaClass
        while (current != null) {
            val field: Field? = runCatching { current.getDeclaredField(name) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(owner)
                }.getOrNull()
            }
            current = current.superclass
        }
        return null
    }

    private fun loadClass(name: String, classLoader: ClassLoader): Class<*>? {
        val candidates = buildList {
            add(classLoader)
            runCatching { Thread.currentThread().contextClassLoader }.getOrNull()?.let(::add)
            runCatching { javaClass.classLoader }.getOrNull()?.let(::add)
            runCatching { ClassLoader.getSystemClassLoader() }.getOrNull()?.let(::add)
            runCatching {
                val at = Class.forName("android.app.ActivityThread", false, classLoader)
                val inst = at.getMethod("currentActivityThread").invoke(null) ?: return@runCatching null
                (at.getMethod("getSystemContext").invoke(inst) as? android.content.Context)
                    ?.classLoader
            }.getOrNull()?.let(::add)
        }.filterNotNull().distinct()
        for (loader in candidates) {
            runCatching { Class.forName(name, false, loader) }.getOrNull()?.let { return it }
        }
        HookLogger.w("LoopbackHooks", "Class not found: $name")
        return null
    }
}
