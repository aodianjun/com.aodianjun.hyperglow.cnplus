// HyperLyric 兼容插件模板。
//
// 使用方法：把整个 template 目录复制并重命名（目录名、包名、id、entry 同步改），
// 然后在根 settings.gradle.kts 增加 include(":plugins:template")。
// 纯 Kotlin/JVM 模块：compileOnly 引用 :plugins:api（API 类由宿主 App 经 parent
// ClassLoader 在运行时提供，绝不打进插件 ZIP——HyperLyric 打包约定）。
//
// 产物：`./gradlew :plugins:template:pluginZip`
//   → build/distributions/hyperglow-template-plugin.zip（manifest.json + classes.dex）
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
}

// --- SDK 定位（local.properties 的 sdk.dir → ANDROID_HOME 环境变量）---

val templateSdkDir: String = run {
    val localProperties = rootProject.file("local.properties")
    val fromLocal = if (localProperties.isFile) {
        Properties().apply { localProperties.inputStream().use(::load) }
            .getProperty("sdk.dir")
    } else {
        null
    }
    providers.environmentVariable("ANDROID_HOME").orElse(fromLocal ?: "").get()
}

val templateBuildToolsVersion = "36.0.0"
val templateCompileSdk = 37
val templateD8 = File(templateSdkDir, "build-tools/$templateBuildToolsVersion/d8")
val templateAndroidJar = File(templateSdkDir, "platforms/android-$templateCompileSdk/android.jar")

val templateJar = tasks.named<Jar>("jar")
val templateDexOutput = layout.buildDirectory.dir("pluginDex")

val templateDex = tasks.register<Exec>("dexPlugin") {
    group = "build"
    description = "Converts the template plugin jar to DEX via Android build-tools d8."
    dependsOn(templateJar)
    inputs.files(templateJar.map { it.archiveFile })
    inputs.property("d8", templateD8.absolutePath)
    outputs.dir(templateDexOutput)
    doFirst {
        if (!templateD8.canExecute()) {
            throw GradleException(
                "d8 not found at ${templateD8.absolutePath}. " +
                    "Set sdk.dir in local.properties or ANDROID_HOME."
            )
        }
        if (!templateAndroidJar.isFile) {
            throw GradleException("android.jar not found at ${templateAndroidJar.absolutePath}.")
        }
        // kotlin-stdlib 由宿主 App 进程提供：作为 classpath 参与解析，不进 dex。
        val stdlibJars = configurations.runtimeClasspath.get()
            .filter { it.name.startsWith("kotlin-stdlib") }
        val outputDir = templateDexOutput.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        commandLine(
            buildList {
                add(templateD8.absolutePath)
                add("--release")
                add("--min-api")
                add("33")
                add("--lib")
                add(templateAndroidJar.absolutePath)
                stdlibJars.forEach { jar ->
                    add("--classpath")
                    add(jar.absolutePath)
                }
                add("--output")
                add(outputDir.absolutePath)
                add(templateJar.get().archiveFile.get().asFile.absolutePath)
            }
        )
    }
}

tasks.register<Zip>("pluginZip") {
    group = "build"
    description = "Packages the installable HyperLyric-compatible template plugin ZIP."
    dependsOn(templateDex)
    archiveFileName.set("hyperglow-template-plugin.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src/main/plugin") {
        include("manifest.json")
    }
    from(templateDexOutput) {
        include("classes.dex")
    }
}
