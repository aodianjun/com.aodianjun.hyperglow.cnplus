// HyperLyric 兼容插件：歌词字形转换（简↔繁）。
//
// 与 plugins/template、plugins/demo 同一打包管线：纯 Kotlin/JVM 模块，compileOnly 引用
// :plugins:api（API 类由宿主 App 经 parent ClassLoader 在运行时提供，绝不打进插件 ZIP）。
//
// 产物：`./gradlew :plugins:scriptconvert:pluginZip`
//   → build/distributions/hyperglow-scriptconvert-plugin.zip（manifest.json + classes.dex）
// 管线：jar → build-tools d8（--lib android.jar，--classpath kotlin-stdlib）→ zip。
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
    testImplementation(kotlin("test"))
}

// --- SDK 定位（local.properties 的 sdk.dir → ANDROID_HOME 环境变量）---

val scriptConvertSdkDir: String = run {
    val localProperties = rootProject.file("local.properties")
    val fromLocal = if (localProperties.isFile) {
        Properties().apply { localProperties.inputStream().use(::load) }
            .getProperty("sdk.dir")
    } else {
        null
    }
    providers.environmentVariable("ANDROID_HOME").orElse(fromLocal ?: "").get()
}

val scriptConvertBuildToolsVersion = "36.0.0"
val scriptConvertCompileSdk = 37
val scriptConvertD8 = File(scriptConvertSdkDir, "build-tools/$scriptConvertBuildToolsVersion/d8")
val scriptConvertAndroidJar = File(scriptConvertSdkDir, "platforms/android-$scriptConvertCompileSdk/android.jar")

val scriptConvertJar = tasks.named<Jar>("jar")
val scriptConvertDexOutput = layout.buildDirectory.dir("pluginDex")

val scriptConvertDex = tasks.register<Exec>("dexPlugin") {
    group = "build"
    description = "Converts the script convert plugin jar to DEX via Android build-tools d8."
    dependsOn(scriptConvertJar)
    inputs.files(scriptConvertJar.map { it.archiveFile })
    inputs.property("d8", scriptConvertD8.absolutePath)
    outputs.dir(scriptConvertDexOutput)
    doFirst {
        if (!scriptConvertD8.canExecute()) {
            throw GradleException(
                "d8 not found at ${scriptConvertD8.absolutePath}. " +
                    "Set sdk.dir in local.properties or ANDROID_HOME."
            )
        }
        if (!scriptConvertAndroidJar.isFile) {
            throw GradleException("android.jar not found at ${scriptConvertAndroidJar.absolutePath}.")
        }
        // kotlin-stdlib 由宿主 App 进程提供：作为 classpath 参与解析，不进 dex。
        val stdlibJars = configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("kotlin-stdlib") }
        val outputDir = scriptConvertDexOutput.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        commandLine(
            buildList {
                add(scriptConvertD8.absolutePath)
                add("--release")
                add("--min-api")
                add("33")
                add("--lib")
                add(scriptConvertAndroidJar.absolutePath)
                stdlibJars.forEach { jar ->
                    add("--classpath")
                    add(jar.absolutePath)
                }
                add("--output")
                add(outputDir.absolutePath)
                add(scriptConvertJar.get().archiveFile.get().asFile.absolutePath)
            }
        )
    }
}

tasks.register<Zip>("pluginZip") {
    group = "build"
    description = "Packages the installable HyperLyric-compatible script convert plugin ZIP."
    dependsOn(scriptConvertDex)
    archiveFileName.set("hyperglow-scriptconvert-plugin.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src/main/plugin") {
        include("manifest.json")
    }
    from(scriptConvertDexOutput) {
        include("classes.dex")
    }
}
