-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
-keep class com.eza.hyperglow.root.HookEntry { *; }
-keep class com.eza.hyperglow.** extends android.app.Service { *; }
-keep class com.eza.hyperglow.** extends android.content.ContentProvider { *; }
# SuperLyricApi — keep the Binder receiver types unobfuscated (required by the API docs).
-keep class com.hchen.superlyricapi.* { *; }
# HyperLyric plugin API — plugin dexes link these classes by FQCN through the parent
# ClassLoader at runtime; any rename/obfuscation breaks plugin loading.
-keep class com.lidesheng.hyperlyric.plugin.api.** { *; }
# SuperLyricApi references android.os.ServiceManager, a hidden API not present in the
# public SDK; suppress the R8 "missing class" error during release minification.
-dontwarn android.os.ServiceManager
# DexKit — the bridge, query DSL, and matchers are invoked through the module's own code;
# keep their public surface unobfuscated so signatures match the upstream 2.2.0 artifact.
-keep class org.luckypray.dexkit.** { **; }
