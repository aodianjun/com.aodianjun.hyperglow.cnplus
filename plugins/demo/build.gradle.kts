// HyperLyric 兼容参考插件（demo）。
//
// 纯 Kotlin/JVM 模块：compileOnly 引用 :plugins:api（API 类由宿主 App 经 parent
// ClassLoader 在运行时提供，绝不打进插件 ZIP——HyperLyric 打包约定）。
//
// 产物：`./gradlew :plugins:demo:pluginZip`
//   → build/distributions/hyperglow-demo-plugin.zip（manifest.json + classes.dex）
// 管线：jar → build-tools d8（--lib android.jar；kotlin-stdlib 与其余 runtime 依赖一起进 dex）→ zip。
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly(project(":plugins:api"))
}

// --- SDK 定位（local.properties 的 sdk.dir → ANDROID_HOME 环境变量）---

val demoSdkDir: String = run {
    val localProperties = rootProject.file("local.properties")
    val fromLocal = if (localProperties.isFile) {
        Properties().apply { localProperties.inputStream().use(::load) }
            .getProperty("sdk.dir")
    } else {
        null
    }
    providers.environmentVariable("ANDROID_HOME").orElse(fromLocal ?: "").get()
}

val demoBuildToolsVersion = "36.0.0"
val demoCompileSdk = 37
val demoD8 = File(demoSdkDir, "build-tools/$demoBuildToolsVersion/d8")
val demoAndroidJar = File(demoSdkDir, "platforms/android-$demoCompileSdk/android.jar")

val demoJar = tasks.named<Jar>("jar")
val demoDexOutput = layout.buildDirectory.dir("pluginDex")

val demoDex = tasks.register<Exec>("dexPlugin") {
    group = "build"
    description = "Converts the demo plugin jar to DEX via Android build-tools d8."
    dependsOn(demoJar)
    inputs.files(demoJar.map { it.archiveFile })
    inputs.property("d8", demoD8.absolutePath)
    outputs.dir(demoDexOutput)
    doFirst {
        if (!demoD8.canExecute()) {
            throw GradleException(
                "d8 not found at ${demoD8.absolutePath}. " +
                    "Set sdk.dir in local.properties or ANDROID_HOME."
            )
        }
        if (!demoAndroidJar.isFile) {
            throw GradleException("android.jar not found at ${demoAndroidJar.absolutePath}.")
        }
        // 插件自包含：kotlin-stdlib 打进 dex（宿主 release 包 R8 改名后无法按原名提供）。
        val bundledJars = configurations.runtimeClasspath.get()
            .filterNot { it.name.startsWith("kotlin-stdlib-jdk") }
        val outputDir = demoDexOutput.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        commandLine(
            buildList {
                add(demoD8.absolutePath)
                add("--release")
                add("--min-api")
                add("33")
                add("--lib")
                add(demoAndroidJar.absolutePath)
                add("--output")
                add(outputDir.absolutePath)
                add(demoJar.get().archiveFile.get().asFile.absolutePath)
                bundledJars.forEach { jar -> add(jar.absolutePath) }
            }
        )
    }
}

tasks.register<Zip>("pluginZip") {
    group = "build"
    description = "Packages the installable HyperLyric-compatible demo plugin ZIP."
    dependsOn(demoDex)
    archiveFileName.set("hyperglow-demo-plugin.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src/main/plugin") {
        include("manifest.json")
    }
    from(demoDexOutput) {
        include("classes.dex")
    }
}
