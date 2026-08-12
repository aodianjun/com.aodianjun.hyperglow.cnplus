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
