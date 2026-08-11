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
        versionCode = 87
        versionName = "0.3.69"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    val composeBom = platform("androidx.compose:compose-bom:2026.04.01")
    implementation(composeBom)
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-icons-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
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
}
