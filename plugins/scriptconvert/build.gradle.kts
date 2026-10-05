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

// d8 / android.jar 定位：优先用首选版本，缺失时回落到 SDK 里版本号最大的已安装版本——
// CI 镜像预装的 build-tools / platform 版本变化不该让打包任务失效。
val scriptConvertPreferredBuildTools = "36.0.0"
val scriptConvertPreferredCompileSdk = 37

/** 只取名字里的数字段：`36.0.0` → [36,0,0]，`android-37` → [37]。 */
fun versionSegments(name: String): List<Int> =
    name.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }

/** 版本号比较：逐段比数值（"36.0.0" > "35.0.1"，"android-37" > "android-36"）。 */
fun compareVersionNames(a: String, b: String): Int {
    val left = versionSegments(a)
    val right = versionSegments(b)
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}

fun newestVersionDir(parent: File, usable: (File) -> Boolean): File? =
    parent.listFiles()
        ?.filter { it.isDirectory && usable(it) }
        ?.maxWithOrNull { a, b -> compareVersionNames(a.name, b.name) }

fun d8In(dir: File): File? =
    File(dir, "d8").takeIf { it.canExecute() } ?: File(dir, "d8.bat").takeIf { it.isFile }

val scriptConvertD8: File? = File(scriptConvertSdkDir, "build-tools").let { parent ->
    d8In(File(parent, scriptConvertPreferredBuildTools))
        ?: newestVersionDir(parent) { dir -> d8In(dir) != null }?.let { dir -> d8In(dir) }
}

val scriptConvertAndroidJar: File? = File(scriptConvertSdkDir, "platforms").let { parent ->
    File(parent, "android-$scriptConvertPreferredCompileSdk/android.jar").takeIf { it.isFile }
        ?: newestVersionDir(parent) { dir -> File(dir, "android.jar").isFile }
            ?.let { File(it, "android.jar") }
}

val scriptConvertJar = tasks.named<Jar>("jar")
val scriptConvertDexOutput = layout.buildDirectory.dir("pluginDex")

val scriptConvertDex = tasks.register<Exec>("dexPlugin") {
    group = "build"
    description = "Converts the script convert plugin jar to DEX via Android build-tools d8."
    dependsOn(scriptConvertJar)
    inputs.files(scriptConvertJar.map { it.archiveFile })
    inputs.property("d8", scriptConvertD8?.absolutePath ?: "unresolved")
    outputs.dir(scriptConvertDexOutput)
    doFirst {
        val d8 = scriptConvertD8 ?: throw GradleException(
            "d8 not found under ${scriptConvertSdkDir}/build-tools. " +
                "Set sdk.dir in local.properties or ANDROID_HOME."
        )
        val androidJar = scriptConvertAndroidJar ?: throw GradleException(
            "android.jar not found under ${scriptConvertSdkDir}/platforms."
        )
        // kotlin-stdlib 由宿主 App 进程提供：作为 classpath 参与解析，不进 dex。
        val stdlibJars = configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("kotlin-stdlib") }
        val outputDir = scriptConvertDexOutput.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        commandLine(
            buildList {
                add(d8.absolutePath)
                add("--release")
                add("--min-api")
                add("33")
                add("--lib")
                add(androidJar.absolutePath)
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
