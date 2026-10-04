pluginManagement {
    repositories {
        maven("https://api.xposed.info/")
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // miuix publishes to Maven Central, so the build needs no credentials. Anyone who clones
        // the public mirror can build; only signing a release requires the owner's private keystore.
        google()
        mavenCentral()
        // SuperLyricApi publishes to JitPack; only needed for the SuperLyric lyric source.
        maven("https://jitpack.io")
    }
}

rootProject.name = "hyperglow"
include(":app")
// HyperLyric 兼容插件体系:api 为 FQCN 兼容的插件 API 契约(宿主经 parent
// ClassLoader 提供,绝不打进插件 ZIP);demo 为参考实现插件模块。
include(":plugins:api")
include(":plugins:demo")
// 补充插件(可安装,不参与宿主 APK 构建):
// - lyricfetch: 在线取词 + 多格式解析(accompanist-lyrics-core 随插件打进 dex)
// - scriptconvert: 歌词简繁字形转换(OpenCC 词典内嵌)
include(":plugins:lyricfetch")
include(":plugins:scriptconvert")
