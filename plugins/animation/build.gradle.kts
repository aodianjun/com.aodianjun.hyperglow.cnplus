// HyperGlow 官方动画插件（HyperLyric 打包格式）。
//
// 纯 Kotlin/JVM 模块：compileOnly 引用 :plugins:api（API 类由宿主 App 经 parent
// ClassLoader 在运行时提供，绝不打包进插件 ZIP——HyperLyric 打包约定）。
//
// 产物：`./gradlew :plugins:animation:pluginZip`
//   → build/distributions/hyperglow-animation-plugin.zip（manifest.json + classes.dex）
// 管线：jar → build-tools d8（--lib android.jar，kotlin-stdlib 随插件打进 dex）→ zip。
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

val animationSdkDir: String = run {
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
// CI 镜像与本地 SDK 的 build-tools / platform 版本差异不该让打包任务失效。
val animationPreferredBuildTools = "36.0.0"
val animationPreferredCompileSdk = 37

fun animationVersionSegments(name: String): List<Int> =
    name.split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }

fun compareAnimationVersionNames(a: String, b: String): Int {
    val left = animationVersionSegments(a)
    val right = animationVersionSegments(b)
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = (left.getOrNull(i) ?: 0) - (right.getOrNull(i) ?: 0)
        if (diff != 0) return diff
    }
    return 0
}

fun newestAnimationVersionDir(parent: File, usable: (File) -> Boolean): File? =
    parent.listFiles()
        ?.filter { it.isDirectory && usable(it) }
        ?.maxWithOrNull { a, b -> compareAnimationVersionNames(a.name, b.name) }

fun animationD8In(dir: File): File? =
    File(dir, "d8").takeIf { it.canExecute() } ?: File(dir, "d8.bat").takeIf { it.isFile }

val animationD8: File? = File(animationSdkDir, "build-tools").let { parent ->
    animationD8In(File(parent, animationPreferredBuildTools))
        ?: newestAnimationVersionDir(parent) { dir -> animationD8In(dir) != null }
            ?.let { dir -> animationD8In(dir) }
}

val animationAndroidJar: File? = File(animationSdkDir, "platforms").let { parent ->
    File(parent, "android-$animationPreferredCompileSdk/android.jar").takeIf { it.isFile }
        ?: newestAnimationVersionDir(parent) { dir -> File(dir, "android.jar").isFile }
            ?.let { File(it, "android.jar") }
}

val animationJar = tasks.named<Jar>("jar")
val animationDexOutput = layout.buildDirectory.dir("pluginDex")

val animationDex = tasks.register<Exec>("dexPlugin") {
    group = "build"
    description = "Converts the animation plugin jar (plus bundled deps) to DEX via d8."
    dependsOn(animationJar)
    inputs.files(animationJar.map { it.archiveFile })
    inputs.property("d8", animationD8?.absolutePath ?: "unresolved")
    outputs.dir(animationDexOutput)
    doFirst {
        val d8 = animationD8 ?: throw GradleException(
            "d8 not found under ${animationSdkDir}/build-tools. " +
                "Set sdk.dir in local.properties or ANDROID_HOME."
        )
        val androidJar = animationAndroidJar ?: throw GradleException(
            "android.jar not found under ${animationSdkDir}/platforms."
        )
        // 插件自包含：kotlin-stdlib 随插件打进 dex，宿主 release 包 R8 改名后无法按原名提供。
        // 排除 kotlin-stdlib-jdk7/jdk8 兼容壳（Kotlin 2.x 下为空 jar），防 d8 重复类。
        val bundledJars = configurations.runtimeClasspath.get()
            .filterNot { it.name.startsWith("kotlin-stdlib-jdk") }
        val outputDir = animationDexOutput.get().asFile
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
                add(animationJar.get().archiveFile.get().asFile.absolutePath)
                bundledJars.forEach { jar -> add(jar.absolutePath) }
            }
        )
    }
}

tasks.register<Zip>("pluginZip") {
    group = "build"
    description = "Packages the installable HyperGlow animation plugin ZIP."
    dependsOn(animationDex)
    archiveFileName.set("hyperglow-animation-plugin.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src/main/plugin") {
        include("manifest.json")
    }
    from(animationDexOutput) {
        include("classes.dex")
    }
}
