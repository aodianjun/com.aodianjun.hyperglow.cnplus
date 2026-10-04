// HyperLyric 兼容插件：在线歌词增强（取词 + 多格式解析）。
//
// 与 plugins/template、plugins/demo 同一打包管线，两处差异：
// 1. 依赖 accompanist-lyrics-core（Apache-2.0，纯 Kotlin/JVM，无传递依赖），
//    它的类**打进插件 dex**（宿主契约允许插件自带类，child-first 加载）；
// 2. d8 输入因此不止插件自身 jar，还要带上 runtime 依赖（kotlin-stdlib 仍由宿主提供，
//    只作为 --classpath 参与解析）。
//
// 产物：`./gradlew :plugins:lyricfetch:pluginZip`
//   → build/distributions/hyperglow-lyricfetch-plugin.zip（manifest.json + classes.dex）
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
    // 解析能力来源之一：多格式歌词解析（LRC / Enhanced LRC / YRC / KRC / TTML /
    // Lyricify Syllable 自动识别）。打进插件 dex，宿主不需要知道它。
    implementation("com.mocharealm.accompanist:lyrics-core:0.4.7")
    // org.json 由 Android 平台提供（插件进程内解析为平台副本），因此 compileOnly：
    // 编译期需要、绝不打进 dex。JVM 单测没有平台副本，故测试侧显式引入同一实现。
    compileOnly("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}

// --- SDK 定位（local.properties 的 sdk.dir → ANDROID_HOME 环境变量）---

val lyricFetchSdkDir: String = run {
    val localProperties = rootProject.file("local.properties")
    val fromLocal = if (localProperties.isFile) {
        Properties().apply { localProperties.inputStream().use(::load) }
            .getProperty("sdk.dir")
    } else {
        null
    }
    providers.environmentVariable("ANDROID_HOME").orElse(fromLocal ?: "").get()
}

val lyricFetchBuildToolsVersion = "36.0.0"
val lyricFetchCompileSdk = 37
val lyricFetchD8 = File(lyricFetchSdkDir, "build-tools/$lyricFetchBuildToolsVersion/d8")
val lyricFetchAndroidJar = File(lyricFetchSdkDir, "platforms/android-$lyricFetchCompileSdk/android.jar")

val lyricFetchJar = tasks.named<Jar>("jar")
val lyricFetchDexOutput = layout.buildDirectory.dir("pluginDex")

val lyricFetchDex = tasks.register<Exec>("dexPlugin") {
    group = "build"
    description = "Converts the lyric fetch plugin jar (plus bundled deps) to DEX via d8."
    dependsOn(lyricFetchJar)
    inputs.files(lyricFetchJar.map { it.archiveFile })
    inputs.property("d8", lyricFetchD8.absolutePath)
    outputs.dir(lyricFetchDexOutput)
    doFirst {
        if (!lyricFetchD8.canExecute()) {
            throw GradleException(
                "d8 not found at ${lyricFetchD8.absolutePath}. " +
                    "Set sdk.dir in local.properties or ANDROID_HOME."
            )
        }
        if (!lyricFetchAndroidJar.isFile) {
            throw GradleException("android.jar not found at ${lyricFetchAndroidJar.absolutePath}.")
        }
        val runtimeJars = configurations.runtimeClasspath.get()
        // kotlin-stdlib 由宿主 App 进程提供：只作 classpath，不进 dex。
        val stdlibJars = runtimeJars.filter { it.name.startsWith("kotlin-stdlib") }
        // 其余运行时依赖（accompanist-lyrics-core）随插件一起进 dex。
        val bundledJars = runtimeJars.filterNot { it.name.startsWith("kotlin-stdlib") }
        val outputDir = lyricFetchDexOutput.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        commandLine(
            buildList {
                add(lyricFetchD8.absolutePath)
                add("--release")
                add("--min-api")
                add("33")
                add("--lib")
                add(lyricFetchAndroidJar.absolutePath)
                stdlibJars.forEach { jar ->
                    add("--classpath")
                    add(jar.absolutePath)
                }
                add("--output")
                add(outputDir.absolutePath)
                add(lyricFetchJar.get().archiveFile.get().asFile.absolutePath)
                bundledJars.forEach { jar -> add(jar.absolutePath) }
            }
        )
    }
}

tasks.register<Zip>("pluginZip") {
    group = "build"
    description = "Packages the installable HyperLyric-compatible lyric fetch plugin ZIP."
    dependsOn(lyricFetchDex)
    archiveFileName.set("hyperglow-lyricfetch-plugin.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src/main/plugin") {
        include("manifest.json")
    }
    from(lyricFetchDexOutput) {
        include("classes.dex")
    }
}
