// HyperLyric 兼容插件：OpenAI 兼容接口的 AI 歌词翻译 + 逐字时间对齐（用户自带地址/模型/Key）。
//
// 与 plugins/lyricfetch、plugins/amll-ttml 同一打包管线（d8 → manifest.json + classes.dex）。
// 依赖面比它们更小：**没有任何第三方运行时库**（HTTP 用 java.net，JSON 用平台 org.json），
// 因此 d8 输入为插件自身 jar + kotlin-stdlib（宿主 release 包 R8 改名，无法提供 kotlin.*）。
//
// 产物：`./gradlew :plugins:ai-translation-words:pluginZip`
//   → build/distributions/hyperglow-ai-translation-words-plugin.zip（manifest.json + classes.dex）
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

val aiTransWordsSdkDir: String = run {
    val localProperties = rootProject.file("local.properties")
    val fromLocal = if (localProperties.isFile) {
        Properties().apply { localProperties.inputStream().use(::load) }
            .getProperty("sdk.dir")
    } else {
        null
    }
    providers.environmentVariable("ANDROID_HOME").orElse(fromLocal ?: "").get()
}

val aiTransWordsPreferredBuildTools = "36.0.0"
val aiTransWordsPreferredCompileSdk = 37

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

val aiTransWordsD8: File? = File(aiTransWordsSdkDir, "build-tools").let { parent ->
    d8In(File(parent, aiTransWordsPreferredBuildTools))
        ?: newestVersionDir(parent) { dir -> d8In(dir) != null }?.let { dir -> d8In(dir) }
}

val aiTransWordsAndroidJar: File? = File(aiTransWordsSdkDir, "platforms").let { parent ->
    File(parent, "android-$aiTransWordsPreferredCompileSdk/android.jar").takeIf { it.isFile }
        ?: newestVersionDir(parent) { dir -> File(dir, "android.jar").isFile }
            ?.let { File(it, "android.jar") }
}

val aiTransWordsJar = tasks.named<Jar>("jar")
val aiTransWordsDexOutput = layout.buildDirectory.dir("pluginDex")

val aiTransWordsDex = tasks.register<Exec>("dexPlugin") {
    group = "build"
    description = "Converts the AI word-level translation plugin jar to DEX via d8."
    dependsOn(aiTransWordsJar)
    inputs.files(aiTransWordsJar.map { it.archiveFile })
    inputs.property("d8", aiTransWordsD8?.absolutePath ?: "unresolved")
    outputs.dir(aiTransWordsDexOutput)
    doFirst {
        val d8 = aiTransWordsD8 ?: throw GradleException(
            "d8 not found under ${aiTransWordsSdkDir}/build-tools. " +
                "Set sdk.dir in local.properties or ANDROID_HOME."
        )
        val androidJar = aiTransWordsAndroidJar ?: throw GradleException(
            "android.jar not found under ${aiTransWordsSdkDir}/platforms."
        )
        val runtimeJars = configurations.runtimeClasspath.get()
        // 插件必须自包含：宿主 release 包经 R8 混淆，kotlin-stdlib 全量改名（无 kotlin.** keep），
        // 双亲回退解析不到 kotlin.*；自带副本按 child-first 加载，才是唯一可靠来源。
        // 排除 kotlin-stdlib-jdk7/jdk8 兼容壳（Kotlin 2.x 下为空 jar），防 d8 重复类。
        val bundledJars = runtimeJars.filterNot { it.name.startsWith("kotlin-stdlib-jdk") }
        val outputDir = aiTransWordsDexOutput.get().asFile
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
                add("--output")
                add(outputDir.absolutePath)
                add(aiTransWordsJar.get().archiveFile.get().asFile.absolutePath)
                bundledJars.forEach { jar -> add(jar.absolutePath) }
            }
        )
    }
}

tasks.register<Zip>("pluginZip") {
    group = "build"
    description = "Packages the installable HyperLyric-compatible AI word-level translation plugin ZIP."
    dependsOn(aiTransWordsDex)
    archiveFileName.set("hyperglow-ai-translation-words-plugin.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src/main/plugin") {
        include("manifest.json")
    }
    from(aiTransWordsDexOutput) {
        include("classes.dex")
    }
}
