// HyperLyric 兼容插件：AMLL TTML 逐字歌词（从 AMLL TTML DataBase 取词）。
//
// 与 plugins/lyricfetch 同一打包管线（d8 → manifest.json + classes.dex），两处差异：
// 1. 依赖 accompanist-lyrics-core（Apache-2.0，纯 Kotlin/JVM），用它的 TTMLParser
//    解析 TTML（逐字/翻译/音译/和声/对唱 agent）；类随插件打进 dex（宿主契约允许）；
// 2. d8 输入因此不止插件自身 jar，还要带上 runtime 依赖（kotlin-stdlib 仍由宿主提供，
//    只作为 --classpath 参与解析）。
//
// 产物：`./gradlew :plugins:amll-ttml:pluginZip`
//   → build/distributions/hyperglow-amll-ttml-plugin.zip（manifest.json + classes.dex）
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        // 对齐 JVM 21：accompanist-lyrics-core 是 Java 21 字节码，而它的 inline API 会把
        // 库字节码内联进本模块——目标不一致时 Kotlin 直接报错（与 app 模块对齐 miuix 同理）。
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    compileOnly(project(":plugins:api"))
    // 测试源集要访问 API 类型(TtmlMapper 返回 PluginLyricLine 等):compileOnly 不传递给
    // test 源集,须显式声明;它只影响 :test 编译/运行,不进 dex(main 的 dex 输入仍是
    // runtimeClasspath,api 依旧只是 compileOnly)。
    testImplementation(project(":plugins:api"))
    // TTML 解析内核(与 lyricfetch 同款依赖;AMLL TTML 用其中的 TTMLParser)。
    implementation("com.mocharealm.accompanist:lyrics-core:0.4.7")
    // org.json 由 Android 平台提供（插件进程内解析为平台副本），因此 compileOnly：
    // 编译期需要、绝不打进 dex。JVM 单测没有平台副本，故测试侧显式引入同一实现。
    compileOnly("org.json:json:20240303")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20240303")
}

// --- SDK 定位（local.properties 的 sdk.dir → ANDROID_HOME 环境变量）---

val amllTtmlSdkDir: String = run {
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
val amllTtmlPreferredBuildTools = "36.0.0"
val amllTtmlPreferredCompileSdk = 37

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

val amllTtmlD8: File? = File(amllTtmlSdkDir, "build-tools").let { parent ->
    d8In(File(parent, amllTtmlPreferredBuildTools))
        ?: newestVersionDir(parent) { dir -> d8In(dir) != null }?.let { dir -> d8In(dir) }
}

val amllTtmlAndroidJar: File? = File(amllTtmlSdkDir, "platforms").let { parent ->
    File(parent, "android-$amllTtmlPreferredCompileSdk/android.jar").takeIf { it.isFile }
        ?: newestVersionDir(parent) { dir -> File(dir, "android.jar").isFile }
            ?.let { File(it, "android.jar") }
}

val amllTtmlJar = tasks.named<Jar>("jar")
val amllTtmlDexOutput = layout.buildDirectory.dir("pluginDex")

val amllTtmlDex = tasks.register<Exec>("dexPlugin") {
    group = "build"
    description = "Converts the AMLL TTML plugin jar (plus bundled deps) to DEX via d8."
    dependsOn(amllTtmlJar)
    inputs.files(amllTtmlJar.map { it.archiveFile })
    inputs.property("d8", amllTtmlD8?.absolutePath ?: "unresolved")
    outputs.dir(amllTtmlDexOutput)
    doFirst {
        val d8 = amllTtmlD8 ?: throw GradleException(
            "d8 not found under ${amllTtmlSdkDir}/build-tools. " +
                "Set sdk.dir in local.properties or ANDROID_HOME."
        )
        val androidJar = amllTtmlAndroidJar ?: throw GradleException(
            "android.jar not found under ${amllTtmlSdkDir}/platforms."
        )
        val runtimeJars = configurations.runtimeClasspath.get()
        // kotlin-stdlib 由宿主 App 进程提供：只作 classpath，不进 dex。
        val stdlibJars = runtimeJars.filter { it.name.startsWith("kotlin-stdlib") }
        // 其余运行时依赖（accompanist-lyrics-core）随插件一起进 dex。
        val bundledJars = runtimeJars.filterNot { it.name.startsWith("kotlin-stdlib") }
        val outputDir = amllTtmlDexOutput.get().asFile
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
                add(amllTtmlJar.get().archiveFile.get().asFile.absolutePath)
                bundledJars.forEach { jar -> add(jar.absolutePath) }
            }
        )
    }
}

tasks.register<Zip>("pluginZip") {
    group = "build"
    description = "Packages the installable HyperLyric-compatible AMLL TTML plugin ZIP."
    dependsOn(amllTtmlDex)
    archiveFileName.set("hyperglow-amll-ttml-plugin.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src/main/plugin") {
        include("manifest.json")
    }
    from(amllTtmlDexOutput) {
        include("classes.dex")
    }
}
