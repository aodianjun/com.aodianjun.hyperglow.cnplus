-keep class io.github.libxposed.api.** { *; }
-keep interface io.github.libxposed.api.** { *; }
-keep class com.eza.hyperglow.root.HookEntry { *; }
-keep class com.eza.hyperglow.** extends android.app.Service { *; }
-keep class com.eza.hyperglow.** extends android.content.ContentProvider { *; }
# SuperLyricApi — keep the Binder receiver types unobfuscated (required by the API docs).
-keep class com.hchen.superlyricapi.* { *; }
# SuperLyricApi references android.os.ServiceManager, a hidden API not present in the
# public SDK; suppress the R8 "missing class" error during release minification.
-dontwarn android.os.ServiceManager
