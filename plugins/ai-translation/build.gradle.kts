// HyperLyric 兼容插件：OpenAI 兼容接口的 AI 歌词翻译（用户自带地址/模型/Key）。
//
// 与 plugins/lyricfetch、plugins/amll-ttml 同一打包管线（d8 → manifest.json + classes.dex）。
// 依赖面比它们更小：**没有任何第三方运行时库**（HTTP 用 java.net，JSON 用平台 org.json），
// 因此 d8 输入只有插件自身 jar；kotlin-stdlib 由宿主提供（--classpath）。
//
// 产物：`./gradlew :plugins:ai-translation:pluginZip`
//   → build/distributions/hyperglow-ai-translation-plugin.zip（manifest.json + classes.dex）
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    compileOnly(project(":plugins:api"))
    // 测试源集要访问 API 类型（处理器/缓存的签名），compileOnly 不传递给 test。
    testImplementation(project(":plugins:api"))
    // org.json 由 Android 平台提供（插件进程内解析为平台副本），因此 compileOnly：
    // 编译期需要、绝不打进 dex。JVM 单测没有平台副本，故测试侧显式引入同一实现。
    compileOnly("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}

// --- SDK 定位（local.properties 的 sdk.dir → ANDROID_HOME 环境变量）---

val aiTransSdkDir: String = run {
    val localProperties = rootProject.file("local.properties")
    val fromLocal = if (localProperties.isFile) {
        Properties().apply { localProperties.inputStream().use(::load) }
            .getProperty("sdk.dir")
    } else {
        null
    }
    providers.environmentVariable("ANDROID_HOME").orElse(fromLocal ?: "").get()
}

val aiTransPreferredBuildTools = "36.0.0"
val aiTransPreferredCompileSdk = 37

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

val aiTransD8: File? = File(aiTransSdkDir, "build-tools").let { parent ->
    d8In(File(parent, aiTransPreferredBuildTools))
        ?: newestVersionDir(parent) { dir -> d8In(dir) != null }?.let { dir -> d8In(dir) }
}

val aiTransAndroidJar: File? = File(aiTransSdkDir, "platforms").let { parent ->
    File(parent, "android-$aiTransPreferredCompileSdk/android.jar").takeIf { it.isFile }
        ?: newestVersionDir(parent) { dir -> File(dir, "android.jar").isFile }
            ?.let { File(it, "android.jar") }
}

val aiTransJar = tasks.named<Jar>("jar")
val aiTransDexOutput = layout.buildDirectory.dir("pluginDex")

val aiTransDex = tasks.register<Exec>("dexPlugin") {
    group = "build"
    description = "Converts the AI translation plugin jar to DEX via d8."
    dependsOn(aiTransJar)
    inputs.files(aiTransJar.map { it.archiveFile })
    inputs.property("d8", aiTransD8?.absolutePath ?: "unresolved")
    outputs.dir(aiTransDexOutput)
    doFirst {
        val d8 = aiTransD8 ?: throw GradleException(
            "d8 not found under ${aiTransSdkDir}/build-tools. " +
                "Set sdk.dir in local.properties or ANDROID_HOME."
        )
        val androidJar = aiTransAndroidJar ?: throw GradleException(
            "android.jar not found under ${aiTransSdkDir}/platforms."
        )
        val runtimeJars = configurations.runtimeClasspath.get()
        // kotlin-stdlib 由宿主 App 进程提供：只作 classpath，不进 dex。
        val stdlibJars = runtimeJars.filter { it.name.startsWith("kotlin-stdlib") }
        // 本插件无其他运行时依赖；此过滤保持与兄弟插件同构（未来加依赖时自动进 dex）。
        val bundledJars = runtimeJars.filterNot { it.name.startsWith("kotlin-stdlib") }
        val outputDir = aiTransDexOutput.get().asFile
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
                add(aiTransJar.get().archiveFile.get().asFile.absolutePath)
                bundledJars.forEach { jar -> add(jar.absolutePath) }
            }
        )
    }
}

tasks.register<Zip>("pluginZip") {
    group = "build"
    description = "Packages the installable HyperLyric-compatible AI translation plugin ZIP."
    dependsOn(aiTransDex)
    archiveFileName.set("hyperglow-ai-translation-plugin.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src/main/plugin") {
        include("manifest.json")
    }
    from(aiTransDexOutput) {
        include("classes.dex")
    }
}
