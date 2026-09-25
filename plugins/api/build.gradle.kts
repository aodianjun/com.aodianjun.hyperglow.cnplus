// HyperLyric 插件 API（FQCN 兼容副本）。
// 纯 Kotlin/JVM 模块：宿主 App 通过 parent ClassLoader 向插件 dex 提供 API 类，
// 因此本模块绝不能被塞进插件 ZIP（HyperLyric 打包约定：compileOnly 引用本模块）。
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
