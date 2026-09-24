// HyperGlow 官方动画插件（HyperLyric 打包格式）。
//
// 纯 Kotlin/JVM 模块：compileOnly 引用 :plugins:api（API 类由宿主 App 经 parent
// ClassLoader 在运行时提供，绝不打包进插件 ZIP——HyperLyric 打包约定）。
//
// 产物：`./gradlew :plugins:animation:pluginZip`
//   → build/distributions/hyperglow-animation-plugin.zip（manifest.json + classes.dex）
// 管线：jar → build-tools d8（--lib android.jar，--classpath kotlin-stdlib）→ zip。
import java.util.Properties

plugins {
    id("org.jetbrains.kotlin.jvm")
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

val animationBuildToolsVersion = "36.0.0"
val animationCompileSdk = 37
val animationD8 = File(animationSdkDir, "build-tools/$animationBuildToolsVersion/d8")
val animationAndroidJar = File(animationSdkDir, "platforms/android-$animationCompileSdk/android.jar")

val animationJar = tasks.named<Jar>("jar")
val animationDexOutput = layout.buildDirectory.dir("pluginDex")

val animationDex = tasks.register<Exec>("dexPlugin") {
    group = "build"
    description = "Converts the animation plugin jar to DEX via Android build-tools d8."
    dependsOn(animationJar)
    inputs.files(animationJar.map { it.archiveFile })
    inputs.property("d8", animationD8.absolutePath)
    outputs.dir(animationDexOutput)
    doFirst {
        if (!animationD8.canExecute()) {
            throw GradleException(
                "d8 not found at ${animationD8.absolutePath}. " +
                    "Set sdk.dir in local.properties or ANDROID_HOME."
            )
        }
        if (!animationAndroidJar.isFile) {
            throw GradleException("android.jar not found at ${animationAndroidJar.absolutePath}.")
        }
        val stdlibJars = configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("kotlin-stdlib") }
        val outputDir = animationDexOutput.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        commandLine(
            buildList {
                add(animationD8.absolutePath)
                add("--release")
                add("--min-api")
                add("33")
                add("--lib")
                add(animationAndroidJar.absolutePath)
                stdlibJars.forEach { jar ->
                    add("--classpath")
                    add(jar.absolutePath)
                }
                add("--output")
                add(outputDir.absolutePath)
                add(animationJar.get().archiveFile.get().asFile.absolutePath)
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
