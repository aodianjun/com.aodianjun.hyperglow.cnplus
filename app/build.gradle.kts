import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val traceLoggingOverride = providers.gradleProperty("traceLogging").orNull?.let { value ->
    require(value == "true" || value == "false") {
        "traceLogging must be true or false"
    }
    value.toBoolean()
}
val diagnosticIntakeUrl = providers.gradleProperty("diagnosticIntakeUrl")
    .orElse("https://reports.eza.dpdns.org/v1/reports")
val diagnosticIntakeUri = URI(diagnosticIntakeUrl.get())
require(
    diagnosticIntakeUri.scheme == "https" &&
        !diagnosticIntakeUri.host.isNullOrBlank() &&
        diagnosticIntakeUri.userInfo == null &&
        diagnosticIntakeUri.query == null &&
        diagnosticIntakeUri.fragment == null
) {
    "diagnosticIntakeUrl must be an HTTPS URL without embedded credentials"
}

val signingKeystoreFile = providers.environmentVariable("SIGNING_KEYSTORE_FILE").orNull
val signingStorePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    signingKeystoreFile,
    signingStorePassword,
    signingKeyAlias,
    signingKeyPassword
).all { !it.isNullOrBlank() }

// 签名 guard(Bridge 同款,issue #68 #9):请求 release 产物任务而正式签名未配置时,
// 配置期直接失败——绝不走 buildTypes 的 debug 签名回退,产出"伪正式版"。
// 本地构建 release 请先配置 SIGNING_KEYSTORE_FILE / SIGNING_STORE_PASSWORD /
// SIGNING_KEY_ALIAS / SIGNING_KEY_PASSWORD;仅跑测试或 debug 构建不受影响。
val releaseArtifactRequested = gradle.startParameter.taskNames.any {
    Regex("(?i)(^|:)(assemble|bundle|lint)release$").containsMatchIn(it)
}
if (releaseArtifactRequested && !releaseSigningConfigured) {
    throw GradleException(
        "release artifact task requested but SIGNING_* env is not configured; " +
            "refusing to produce a debug-signed release. Configure signing or use assembleDebug."
    )
}

// 关于页「使用帮助」的文案来源:仓库根 FAQ.md(受私有生成源管理,不在仓库内另存副本),
// 构建期拷贝进 assets,保证 APK 内内容与 docs 侧单一来源一致。
// 注意:AGP 9 的 SourceSet API 拒绝 Provider(任务输出)作为源目录,因此这里注册普通
// 目录并显式声明「assets 合并任务依赖拷贝任务」,顺序由下方 tasks.matching 保证。
val faqAssetOutputDir = layout.buildDirectory.dir("generated/faqAssets")

val copyFaqAsset = tasks.register<Copy>("copyFaqAsset") {
    from(rootProject.file("FAQ.md"))
    into(faqAssetOutputDir)
}

android {
    namespace = "com.eza.hyperglow"
    compileSdk = 37

    defaultConfig {
        // LSPosed 模块仓库按 applicationId(应用包名)索引。
        // 原包名 com.eza.hyperglow 已被原作者占用，CN+ 独立版改用此包名发布。
        // 代码包路径/namespace 保留 com.eza.hyperglow，组件相对名与 import 均无需改动。
        applicationId = "com.aodianjun.hyperglow.cnplus"
        minSdk = 33
        targetSdk = 37
        versionCode = 186
        versionName = "0.3.159"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "DIAGNOSTIC_INTAKE_URL",
            "\"$diagnosticIntakeUri\""
        )
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(signingKeystoreFile))
                storePassword = requireNotNull(signingStorePassword)
                keyAlias = requireNotNull(signingKeyAlias)
                keyPassword = requireNotNull(signingKeyPassword)
            }
        }
        // 固定调试签名密钥(已入库): 保证本地与 CI 每次构建签名一致,
        // 更新时可覆盖安装,无需卸载。仅用于调试/测试分发。
        // 注意: 不能命名为 "debug"(AGP 已自动创建同名配置)。
        val debugKeystore = file("keystore/hyperglow-dbg.jks")
        if (debugKeystore.exists()) {
            create("hyperglowDebug") {
                storeFile = debugKeystore
                storePassword = "hyperglow_debug_2026"
                keyAlias = "hyperglow"
                keyPassword = "hyperglow_debug_2026"
            }
        }
    }

    buildTypes {
        debug {
            // 优先使用正式签名:CI release job 中 release 与 debug 使用同一正式密钥,
            // 使 release 版可直接覆盖已安装的 debug 版(签名一致才能覆盖安装)。
            // 无正式签名密钥(本地/普通 CI)时回退到固定调试签名,保证每次构建签名一致。
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.findByName("hyperglowDebug")
            buildConfigField(
                "boolean",
                "TRACE_LOGGING_AVAILABLE",
                (traceLoggingOverride ?: true).toString()
            )
        }
        release {
            isMinifyEnabled = true
            signingConfigs.findByName("release")?.let { signingConfig = it }
            buildConfigField(
                "boolean",
                "TRACE_LOGGING_AVAILABLE",
                (traceLoggingOverride ?: true).toString()
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        // miuix 0.9.4 的产物编译在 JVM target 21:NavDisplay 的 entry/rememberNavBackStack 等
        // inline API 会把库字节码内联进调用方,目标不一致时 Kotlin 报
        // "Cannot inline bytecode built with JVM target 21 into bytecode that is being built
        // with JVM target 17"。应用因此与 miuix 对齐到 21(CI 用 JDK 21,D8/R8 支持 class file 65)。
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

    sourceSets {
        getByName("main") {
            assets.srcDir(faqAssetOutputDir.get().asFile)
        }
    }
}

// assets 合并任务(merge<Variant>Assets)与 lint 任务都必须先看到 copyFaqAsset 的产物:
// lint 的 generate<Variant>Lint*ReportModel 同样把 assets 源目录列为任务输入,漏声明会在
// release job 的 lintVital 阶段触发 Gradle 9 的隐式依赖校验失败(PR #164 合并 run 实证)。
tasks.matching { task ->
    (task.name.startsWith("merge") && task.name.endsWith("Assets")) ||
        task.name.contains("lint", ignoreCase = true)
}.configureEach {
    dependsOn(copyFaqAsset)
}

dependencies {
    // HyperLyric 插件 API(FQCN 兼容):App 直接实现宿主侧接口,同时插件 dex 经
    // parent ClassLoader 按同名类链接。必须 implementation(打进宿主 APK)。
    implementation(project(":plugins:api"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.4")
    // 背景生效时顶栏的渐变模糊(backdrop 渐进式纹理模糊,RuntimeShader 路径需 API 33+,与 minSdk 一致)。
    implementation("top.yukonga.miuix.kmp:miuix-blur-android:0.9.4")
    // miuix-nav:连续栈深度导航运行时,承载 App 内界面返回栈;系统预测性返回手势按 1:1
    // 跟手驱动转场(NavDisplay 内置 PredictiveBackHandler,经 androidx.navigationevent 接入)。
    implementation("top.yukonga.miuix.kmp:miuix-nav-android:0.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    // DexKit — dynamic symbol resolution for Xiaomi symbols that get renamed across ROM
    // versions. Brought in by the 2885511 sync; used only under the optional DexKit arm of
    // SymbolResolver (bundled reflection stays the fast path).
    implementation("org.luckypray:dexkit:2.2.0")
    // lyricon subscriber SDK — consumes lyrics from lyricon's central service
    // (Xposed-injected into com.android.systemui). Transitively pulls in
    // io.github.proify.lyricon.lyric:model (the Song/RichLyricLine model).
    implementation("io.github.proify.lyricon:subscriber:0.1.70")
    // SuperLyricApi — Binder-based receiver for lyrics published by the SuperLyric
    // Xposed module (com.hchen.superlyricapi: SuperLyricHelper / ISuperLyricReceiver).
    implementation("com.github.HChenX:SuperLyricApi:3.4")
    // LyricInfo needs no dependency: it injects lyrics into MediaSession metadata
    // (MediaMetadata.extras.lyricInfo, elrc format), consumed via MediaSessionManager.
    compileOnly("io.github.libxposed:api:102.0.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Robolectric: 用于依赖 Android 框架(Binder/PackageManager)的桥接层单测。
    testImplementation("org.robolectric:robolectric:4.16.1")
}
