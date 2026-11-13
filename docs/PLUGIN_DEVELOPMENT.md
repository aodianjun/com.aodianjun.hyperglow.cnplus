# Plugin Development Guide

# English / English

Status: authoring standard for third-party plugins — the interfaces, the runtime contract, the
packaging rules and the verification gates. This is the document to hand to someone who wants to
write a plugin.

Neighbours (read them too, this guide does not repeat them in full):

- `docs/PLUGIN_RELEASE_CONVENTIONS.md` — the canonical packaging / release / documentation
  contract for modules under `plugins/`.
- `docs/ARCHITECTURE.md` § *Plugin Runtime Boundary* — the trust model.
- `plugins/api/src/main/kotlin/com/lidesheng/hyperlyric/plugin/api/PluginApi.kt` — the frozen,
  byte-fingerprinted interface (the source of truth for every signature quoted here).
- `plugins/template/` — a compilable pass-through skeleton to copy. `plugins/demo/` — the full
  API surface (settings, cache, the change protocol) in working code.

## 1. What a plugin is

A plugin is a **ZIP with exactly two entries**: `manifest.json` and `classes.dex`. The host
validates the manifest structurally, writes the dex to a read-only location and loads it through a
dedicated class loader in the **app process** (never SystemUI).

- **HyperLyric-compatible**: the API package is `com.lidesheng.hyperlyric.plugin.api`, a
  FQCN-identical copy of the upstream contract. A plugin ZIP built for HyperLyric loads in
  HyperGlow unchanged, and vice versa. Do not rename, reorder or "improve" anything in that
  package; CI fingerprints it (`plugins/api/check-api-fingerprint.py`).
- **Position in the pipeline**: producer → **plugin chain (enrich)** → projection →
  SystemUI / lockscreen / AOD. Plugins see one immutable song snapshot per run and return an
  explicitly declared update; they never touch rendering.
- **Trust model**: a plugin is user-installed input of untrusted origin. Manifest validation is
  structural only — there is no publisher signature, no runtime API allowlist, no sandbox.
  Installing a plugin grants it the app process's own capabilities (no SystemUI access, no root,
  no Xposed hooks). Do not request or imply higher privileges.

## 2. Quick start

1. Copy `plugins/template` to `plugins/<yourname>`.
2. Rename consistently: the source package, `manifest.json` `id` + `entry`, the entry class name.
3. Add `include(":plugins:<yourname>")` to the root `settings.gradle.kts`.
4. Build: `./gradlew :plugins:<yourname>:pluginZip` →
   `plugins/<yourname>/build/distributions/hyperglow-<yourname>-plugin.zip`.
5. Install: HyperGlow settings → Plugin management → install from local ZIP.

Unit tests: `./gradlew :plugins:<yourname>:test` (a plain Kotlin/JVM test source set; the platform
`org.json` is not on the JVM test classpath, declare `testImplementation("org.json:json:…")` like
the other plugins do). The d8 packaging step needs Android SDK build-tools; it is located via
`local.properties` `sdk.dir` → `ANDROID_HOME`.

## 3. manifest.json reference

```json
{
  "id": "com.yourname.hyperglow.example",
  "name": "示例插件",
  "nameLocales": { "zh-CN": "示例插件", "zh-TW": "範例外掛", "en": "Example Plugin" },
  "author": "yourname",
  "version": "1.0.0",
  "apiVersion": 1,
  "entry": "com.yourname.hyperglow.example.ExamplePlugin",
  "activationSettingKey": "example_enabled",
  "cacheScopes": [
    { "id": "example_songs", "title": "Example cache",
      "titleLocales": { "zh-CN": "示例缓存", "en": "Example cache" },
      "summary": "Per-song results; delete an entry to look the song up again.",
      "summaryLocales": { "zh-CN": "按曲目保留的结果；删除条目即可重新取词。" } }
  ],
  "settings": [
    { "type": "switch", "key": "example_enabled", "title": "Enable",
      "titleLocales": { "zh-CN": "启用示例插件" }, "default": false },
    { "type": "text", "key": "example_prefix", "title": "Prefix",
      "summaryLocales": { "zh-CN": "每行歌词的前缀" }, "default": "♪ " },
    { "type": "select", "key": "example_mode", "title": "Mode", "default": "prefix",
      "options": [ { "value": "prefix", "label": "Prefix" },
                   { "value": "uppercase", "label": "UPPERCASE",
                     "labelLocales": { "zh-CN": "全大写" } } ] }
  ]
}
```

Top-level fields:

| Field | Type | Required | Notes |
| --- | --- | --- | --- |
| `id` | string | yes | `[a-z0-9_]+(\.[a-z0-9_]+)+`, globally unique; also the plugin directory name and settings-file key. |
| `name` | string | yes | Plugin's own language (Simplified Chinese for the plugins in this repository). |
| `nameLocales` | map | no | Locale overrides for `name`. |
| `author` | string | no | |
| `version` | string | yes | semver; bump whenever observable behaviour changes. |
| `apiVersion` | int | yes | Must be ≤ the host's `HYPERLYRIC_PLUGIN_API_VERSION` (currently `1`). Not a feature switch. |
| `entry` | string | yes | FQCN of the entry class (public no-arg constructor, implements `HyperLyricPlugin`). |
| `activationSettingKey` | string | no | Key of the switch that enables the plugin. Absent = always active. **Declared but defaulting to `false` = plugin silently inactive** — the usual cause of "loaded but does nothing". |
| `settingGroups` | array | no | `{ id, title, titleLocales }` — groups settings into one surface. |
| `cacheScopes` | array | no | `{ id, title, summary?, titleLocales?, summaryLocales? }` — see §9. |
| `settings` | array | no | See below. |

Setting item fields (all localized fields take the same `*Locales` companion):

| Field | Notes |
| --- | --- |
| `type` | `switch` \| `text` \| `password` \| `select` \| `multiSelect` \| `number` \| `slider` \| `action` |
| `key` | Unique; read back via `PluginConfig`. |
| `title`, `summary` | Row title / secondary text. |
| `dialogSummary` | Extra explanation shown when the setting is opened. |
| `emptyValueSummary` | Placeholder text when the value is empty. |
| `default` | Raw JSON default (bool / number / string; a comma/semicolon-separated string seeds a `multiSelect` default set). |
| `options` | For `select` / `multiSelect`: `[{ value, label, labelLocales? }]`; values must be non-blank and handled by your code. |
| `min` / `max` / `step` | For `slider` / `number`; `slider` requires `min ≤ max`. |
| `valuePresentation` | `default` \| `endAction` \| `summary` \| `summaryPreview` — where the current value appears in the row. |
| `inputType` | `default` \| `uri` \| `number` — keyboard intent for text settings. |
| `previewLineCount` | Preview lines for text settings (default 2). |
| `conflictsWith` | Keys this setting is mutually exclusive with (host greys them out); must reference declared keys. |
| `backup` | `false` excludes the value from full host config backup (use for secrets). |
| `group` | `settingGroups` id to place this row in a group. |

Structural validation (install-time rejection): id pattern; `apiVersion ≤ host`; non-blank `entry`;
unique non-blank setting keys; `select`/`multiSelect` must have non-blank options; `slider` needs
`min ≤ max`; `conflictsWith`/`group` must resolve; unknown `type`/`valuePresentation`/`inputType`
wire names are rejected. Unknown top-level fields are ignored (forward compatibility).

**Localization rule**: the plain `name`/`title`/`summary`/… fields are the plugin's own language
and each `*Locales` map carries overrides. Declare `zh-CN` explicitly and put it first. The host
resolves *exact tag* → *same language with a compatible Chinese script* → *script-neutral* →
*default*, so a Simplified device never takes a `zh-TW` override and a map without a `zh-CN` key
renders the default for Simplified devices.

## 4. The API (v1)

`HYPERLYRIC_PLUGIN_API_VERSION = 1`. Everything below lives in
`com.lidesheng.hyperlyric.plugin.api`.

| Interface | Purpose |
| --- | --- |
| `HyperLyricPlugin` | Entry point. `onLoad(context)` registers extensions; `onEnable()` fires when the activation switch turns on; `onConfigChanged(config)` fires after settings are saved; `onUnload()` fires on reload/uninstall/load-failure (best-effort, always wrapped). |
| `HyperLyricExtension` | Anything you register. Has an `id`. |
| `LyricProcessorExtension` | The lyric processor. `stage` (default `TRANSLATION_ENHANCEMENT`); implement `processResult(song, processingContext)` — the 1-arg overload is bridged automatically. Return `null` = "no result this run". |
| `PluginContext` | The **only** host object you get: `pluginId`, `hostApiVersion`, `config`, `logger`, `cache`, `storage`, `registerExtension()`. |
| `PluginConfig` | Read-only settings: `getBoolean/getString/getLong/getFloat/getStringSet`. |
| `PluginLogger` | `debug/info/warn/error` (+ `withTag`). Forwards to the host log under `Plugin:<id>`. |
| `PluginStorage` | Namespaced key/value storage (per-plugin SharedPreferences): `getString/putString/remove/clear`. |
| `PluginCache` | Host-owned persistent cache: `getString/putString/getBytes/putBytes/contains/remove/clear`. No-op when the manifest declares no `cacheScopes`. |
| `PluginCacheExtension` | Cache-management surface shown in the host UI: `listEntries()`, `clearEntry(id)`, `clearAll()`. |

Stages: `PluginProcessorStage.LYRIC_REPLACEMENT` → `TRANSLATION_ENHANCEMENT` (lower runs first).
Stages order processors **within one plugin**; across plugins the host runs whole plugins in
plugin-id order (§6).

Minimal processor (this is `plugins/template`, verbatim shape):

```kotlin
class ExamplePlugin : HyperLyricPlugin {
    override fun onLoad(context: PluginContext) {
        context.registerExtension(ExampleProcessor(context))
    }
}

private class ExampleProcessor(private val context: PluginContext) : LyricProcessorExtension {
    override val id: String = "example.processor"

    override fun processResult(song: PluginSong): PluginSongResult? {
        val rows = song.lyrics ?: return null
        if (rows.isEmpty()) return null
        if (!context.config.getBoolean("example_enabled", false)) return null
        val prefix = context.config.getString("example_prefix", "♪ ") ?: "♪ "
        val decorated = rows.map { row ->
            row.text?.let { row.copy(text = prefix + it) } ?: row
        }
        if (decorated == rows) return null
        return PluginSongResult(
            song = song.copy(lyrics = decorated),
            changedFields = setOf(PluginSongField.LYRICS),
            lyricsUpdateMode = PluginLyricsUpdateMode.PATCH,
            changedLyricFields = setOf(PluginLyricField.TEXT)
        )
    }
}
```

Data model (all times are milliseconds, `Long`):

| Type | Fields |
| --- | --- |
| `PluginSong` | `id`, `name`, `artist`, `album`, `duration`, `metadata`, `lyrics: List<PluginLyricLine>?` |
| `PluginLyricLine` | `begin`, `end`, `duration`, `isAlignedRight`, `metadata`, `text`, `words`, `secondary`, `secondaryWords`, `translation`, `translationWords`, `roma` |
| `PluginWord` | `begin`, `end`, `duration`, `text`, `metadata` |
| `PluginMetadata` | `values: Map<String, String?>` — row metadata conventions: `role` = `LEAD` / `BG`, `agent` = singer id (§5) |
| `PluginMediaInfo` | `title`, `artist`, `album`, `duration`, `sourcePackageName` (only when the lyric source supplied it — never infer it) |
| `PluginProcessingContext` | `mediaInfo` — per-invocation, read-only; the way to get song metadata for network lookups |

The API deliberately exposes no Android, MediaSession or Xposed types — you cannot get a `Context`.
Everything you need must come through `PluginContext`.

## 5. The result protocol

`PluginSongResult(song, changedFields, lyricsUpdateMode = REPLACE, changedLyricFields = emptySet())`

- **`changedFields` / `changedLyricFields` are authoritative.** The host never infers changed
  fields by comparing DTOs: a value you changed but did not declare is discarded; a nullable value
  you declared (with `null`) clears the field.
- **`PATCH`** keeps the row count and row indices — candidate rows are merged field-by-field over
  the current rows, only for the declared `changedLyricFields`. A row-count mismatch rejects the
  whole result.
- **`REPLACE`** supplies a complete new row list (must be non-empty; each row needs a valid
  timeline: `begin >= 0`, `end >= begin`).
- Any structural violation (declared `LYRICS` with `lyrics == null`, PATCH mismatch, empty
  REPLACE, invalid timeline) → the result is **dropped with a logged reason**, the current snapshot
  is kept and the chain continues. Returning `null` is the clean "no result".

What actually reaches the screen (host bridge consumption map — code-verified):

| Declared change | Effect |
| --- | --- |
| `TEXT` | Main line text. |
| `TRANSLATION`, `TRANSLATION_WORDS` | Translated line — text first, word-list fallback (either declaration refreshes it). |
| `ROMA` | Romanized line. |
| `WORDS` | Word-level timeline for karaoke. An empty list does **not** overwrite existing words. |
| `TRANSLATION_WORDS` | Word-level translation; empty list does not overwrite. |
| `IS_ALIGNED_RIGHT` | Duet left/right alignment. |
| `TEXT` / `TRANSLATION` / `ROMA` | Also refreshes the **next-line group** (`nextLine` / `nextLineTranslated` / `nextLineRomanized`) from your row list. |
| `BEGIN`, `END`, `DURATION` | Used when re-selecting the active row after a row-table rewrite; **sampling keeps producer authority** — you do not change playback position semantics. |
| `SECONDARY`, `SECONDARY_WORDS` | Merged into the snapshot but **not consumed by the display bridge** (dead keys). Put auxiliary content in `ROMA` / `TRANSLATION`. |
| `metadata.role` | `LEAD` / `BG` (harmony). `BG` rows go to the harmony/duet lane, never the main line. |
| `metadata.agent` | Singer identity for duet voice slots: first-seen agent → slot 0 (main line), further agents → slot 1 (concurrent lane). Without agents the host auto-assigns by overlap (≥1 s shared window = two singers present). |
| `NAME`, `ARTIST`, `ALBUM` | Song-level fields. `ID`, `DURATION`, `METADATA` are merged but not consumed by display. |

Two more host rules worth knowing:

- **Enhancement, not replacement**: a blank/empty plugin value never overwrites a non-empty
  producer value (`keepUnlessBlank`), and an empty word list never wipes an existing timeline.
  Emitting a half-filled snapshot cannot blank the screen.
- **Declared `WORDS` wins**: when any accepted result declares `WORDS`, the host normalizes the
  merged row windows against your word windows (the word window is the final authority for that
  row). When you do not declare `WORDS`, the producer's row windows are kept as-is.

## 6. Runtime contract

**When the chain runs.** Once per song/session, asynchronously, on song change. Three input paths
converge on the same chain: a full Spicy document; a full in-memory song snapshot (read once per
session); and incremental line-stream sources (rows aggregated as playback advances — the first
line runs the chain immediately, later rows re-run it throttled to ≥15 s intervals, and a re-run
requested while the chain is running is evaluated once it finishes). **Design for incremental
input**: your processor may be called several times per song with a growing row list, and it may
see a partial song. Cache by song identity, and never assume the last row is the last lyric.

**Ordering.** Plugins run **serially in plugin-id lexicographic order**; within one plugin,
processors run in stage order. Each processor receives the merged snapshot of everything before
it. (Stages do not reorder plugins against each other.)

**Timeouts.** Each processor has a **40 s** host limit. On timeout your result is discarded and the
chain continues; three consecutive timeouts for the same processor **open a circuit** that skips
it without waiting for the rest of the session. A normal return or a thrown error resets the
streak; a reload resets all circuits.

**Cancellation.** Switching songs cancels the running chain. Respond to interruption
(`Thread.interrupt`): do not swallow `InterruptedException`, and check `Thread.interrupted()` in
long loops. Timeout handling is cooperative — a call that ignores interrupts keeps occupying a
worker thread until it returns.

**Threading.** Processors run on a dedicated bounded pool (2 threads) — not the app's shared IO
pool. Do not block indefinitely; there is no guarantee about which thread you run on, so make
your processor thread-safe with respect to itself (or document that it is single-flight).

**Error isolation.** Any throw, timeout or illegal result only drops that one result. The original
lyrics and every other plugin are unaffected — fail loudly in your logs, not by corrupting output.

**Lifecycle.** The plugin instance lives across songs (no per-song reset — keep per-song state in
your cache or keyed by `PluginSong.id`/name). `onUnload` is delivered on reload/uninstall/load
failure; the class loader itself cannot be unloaded, so replaced code lingers until the process
restarts. Installing/uninstalling/upgrading triggers an immediate rescan + reload (no app restart
needed). Enable/disable and settings changes are live: the activation switch is re-read on every
chain run, `onEnable` fires on off→on, and `onConfigChanged` is delivered when settings are saved
— config changes take effect from the next song, the current song is not re-run.

## 7. Host services

- `logger` — host log, source area `Plugin:<id>`; throttled callers should do their own throttling.
- `storage` — per-plugin key/value (SharedPreferences `plugin_storage_<id>`); for small state only.
- `cache` — files under the host's plugin cache directory, keyed by SHA-256 of your key. Quota:
  **16 MiB per plugin**, shared by all of that plugin's entries. `putBytes` over quota keeps the old
  value and only logs — if the write matters, read back with `contains()`/`getBytes()`. All
  cache/storage IO failures are swallowed host-side; they can never break the lyric pipeline.
- No cache scope declared in the manifest → the host hands you a **no-op cache** (nothing is
  stored, the settings page shows no cache section).

## 8. Settings contract

Settings are rendered by the host settings screen from the manifest, and the **host side** applies
the manifest defaults (settings-row rendering, the activation check, config backup — `getStringSet`
defaults split the manifest `default` string on `,` / `;`). **Plugin-side reads do not**: through
`PluginConfig` you read the stored value with the default *you* pass to `getX()` — pass the same
default as the manifest so the two can never disagree. The switch pointed to by
`activationSettingKey` is the plugin's enable switch. `conflictsWith` greys out mutually exclusive
options; `backup: false` keeps a value out of config backup (use it for API keys). Declared
settings must be read by your code and settings your code reads must be declared — dead keys in
either direction are a defect.

## 9. Cache management contract

If you cache anything the user could want to inspect or invalidate (network lookups, generated
artifacts), **both** are required:

1. a `cacheScopes` entry in the manifest (id, title, optional summary; localized);
2. a registered `PluginCacheExtension` whose `id` equals the declared scope id, implementing
   `listEntries` / `clearEntry` / `clearAll` with safe-to-display metadata only (never cache
   bodies, API keys or full translations).

Deleting one entry must be enough to make the plugin redo that work — deleting must also
invalidate your own in-memory copy. The host owns the quota and the storage location; you own
keys, serialization, schema versions and expiry.

## 10. Packaging rules

Inside `classes.dex`:

- **Must** contain your own classes plus **every third-party runtime dependency, including
  `kotlin-stdlib`**. A plugin is self-contained: plugin-bundled classes load child-first (§11), so
  your copy always wins. The host cannot provide `kotlin.*`: the release app is R8-minified with no
  `kotlin.**` keep rule, so every stdlib class is renamed and parent-first fallback resolves
  nothing. (Plugin builds made before 2026-10 assumed "the host provides kotlin-stdlib" — they fail
  at `onLoad` on release hosts with `NoClassDefFoundError: kotlin.jvm.internal.Intrinsics`. Do not
  copy that pattern; CI now rejects it, see below.)
- **Must not** contain the plugin API package (`com.lidesheng.hyperlyric.plugin.api.**`) — the host
  resolves it parent-first; a bundled copy is dead weight and breaks type identity.
- **Must not** contain platform libraries such as `org.json` — declare them `compileOnly` (plus
  `testImplementation` for JVM unit tests).

The d8 step (see any `plugins/*/build.gradle.kts`):

```
d8 --release --min-api 33 --lib <android.jar> --output <dir> <plugin.jar> <all runtime jars>
```

- Take **all** runtime jars as inputs (including `kotlin-stdlib`); exclude the empty
  `kotlin-stdlib-jdk7/jdk8` shims if your graph ever carries them (duplicate-class guard).
- `--min-api 33`. Align your Kotlin `jvmTarget` with your dependencies: libraries compiled for
  Java 21 (e.g. `accompanist-lyrics-core`) force `jvmTarget = 21` because inline APIs copy library
  bytecode into your module.
- The ZIP must be exactly `manifest.json` + `classes.dex` — no resources, no nested archives.

CI gate (`plugins/check-plugin-dex.py`, run on every PR and every `main` push): every built ZIP's
dex must define `kotlin.jvm.internal.Intrinsics` whenever it references it (i.e. the stdlib is
really bundled), must not bundle plugin-API classes, and the ZIP shape is checked. A plugin that
fails the gate is not publishable.

## 11. Class loading

Two resolution paths, by prefix:

- **Parent-first (host-owned)**: `java.`, `javax.`, `android.`, `androidx.`, `dalvik.`,
  `libcore.`, `org.json.`, `org.w3c.`, `org.xml.`, `org.xmlpull.`, and the plugin API contract
  package `com.lidesheng.hyperlyric.plugin.api.`.
- **Child-first (everything else)**: if your dex contains the class, your copy is used; otherwise
  the host is the fallback.

Consequences: you may bundle any library you like — it can never be shadowed by the host's
R8-narrowed copies (cross-class-loader access to those used to throw `IllegalAccessError`). You
cannot shadow platform or API classes — that is by design, the API needs one shared type identity
between you and the host. No Kotlin runtime type crosses the API boundary (collection types erase
to `java.util` at runtime), which is exactly why child-first isolation is safe.

## 12. Verification and release

- **Unit tests are necessary, not sufficient**: `./gradlew :plugins:<name>:test`. User-visible
  behaviour (the host renders it) needs device verification — record it in the plugin README, and
  list what was *not* verified honestly.
- **README** per `PLUGIN_RELEASE_CONVENTIONS.md` §5: pipeline position, fields written / not
  written, sources, settings table, cache notes, verification status, limitations, credits &
  licenses.
- **CI**: `pluginZip` builds for every plugin in `settings.gradle.kts` on every PR and every `main`
  push; the dex gate (§10) runs with it; artifacts are uploaded on PRs and the rolling `plugins`
  pre-release is rebuilt only from `main` (fixed asset names, version lives in the manifest).
- **Versioning**: bump `manifest.version` on any observable change; never bump `apiVersion` to
  "unlock" something — it is the host-API generation you are written against.

## 13. Pitfalls checklist

- [ ] `kotlin-stdlib` bundled in the dex (self-contained) — the #1 cause of `onLoad` failures on
      release hosts.
- [ ] Nothing from `com.lidesheng.hyperlyric.plugin.api.**` or platform libraries in the dex.
- [ ] Every changed field declared in `changedFields` / `changedLyricFields`; PATCH row counts
      match; REPLACE timelines are monotonic.
- [ ] `activationSettingKey` declared and its default is what you mean (absent = always on,
      `false` default = off until the user enables it).
- [ ] Incremental sources: handle growing/partial row lists; cache by song identity.
- [ ] Stay under 40 s per call; respond to interruption; no indefinite blocking.
- [ ] Empty outputs don't blank the screen (that is enforced host-side, but don't rely on it to
      hide bugs).
- [ ] Cache: declare `cacheScopes` + implement `PluginCacheExtension`, or the cache is a no-op;
      check `contains()` after `putBytes` if the write matters (quota overflow keeps the old value).
- [ ] Auxiliary content goes in `ROMA` / `TRANSLATION`; `SECONDARY` / `SECONDARY_WORDS` are dead
      keys.
- [ ] Manifest and code agree (no unread settings, no undeclared reads).
- [ ] No Context/MediaSession/Xposed assumptions — the API gives you none.

## 14. See also

- `plugins/template/README.md`, `plugins/demo/` — code.
- `docs/PLUGIN_RELEASE_CONVENTIONS.md` — packaging / release / README contract.
- `docs/ARCHITECTURE.md` § *Plugin Runtime Boundary* — trust model and lifecycle.
- `plugins/api/.../PluginApi.kt` — the frozen interface with its own KDoc.

---

# 中文 / Chinese

状态：第三方插件开发标准 —— 接口、运行时契约、打包规则与验证门禁。本文档就是发给「要写插件的人」
的那一份。

相邻文档（本文不重复它们的全部内容）：

- `docs/PLUGIN_RELEASE_CONVENTIONS.md` —— `plugins/` 下模块的打包 / 发布 / 文档契约（正式标准）。
- `docs/ARCHITECTURE.md` §「插件运行时边界」—— 信任模型。
- `plugins/api/src/main/kotlin/com/lidesheng/hyperlyric/plugin/api/PluginApi.kt` —— 冻结的、
  带字节指纹的接口（本文引用的每个签名以它为准）。
- `plugins/template/` —— 可直接编译的直通骨架，复制即用。`plugins/demo/` —— 完整 API 用法
  （设置、缓存、变更协议）的可运行参考。

## 1. 插件是什么

插件是**恰好含两个条目的 ZIP**：`manifest.json` 与 `classes.dex`。宿主对 manifest 做结构校验，
把 dex 写到只读位置，并在 **App 进程**（绝不进 SystemUI）里用专用 ClassLoader 加载。

- **HyperLyric 兼容**：API 包名是 `com.lidesheng.hyperlyric.plugin.api`，与上游契约 FQCN 逐字节
  一致——为 HyperLyric 构建的插件 ZIP 不改一字节即可在 HyperGlow 加载，反之亦然。不要改名、
  重排或"改进"该包内任何声明；CI 有指纹门禁（`plugins/api/check-api-fingerprint.py`）。
- **管线位置**：生产者 → **插件链（enrich）** → 投影 → SystemUI / 锁屏 / AOD。插件每次运行拿到
  一份不可变的整首歌快照，返回**显式声明**的变更；插件永远不碰渲染。
- **信任模型**：插件是用户自装的不可信输入。manifest 只做结构校验——没有发布者签名、没有运行时
  API 允许名单、没有沙箱。安装插件即授予它 App 进程自身的能力（无 SystemUI 访问、无 root、
  无 Xposed 钩子）。请勿要求或暗示更高权限。

## 2. 快速开始

1. 把 `plugins/template` 复制为 `plugins/<你的名字>`。
2. 四处同步改名：源码包名、`manifest.json` 的 `id` 与 `entry`、入口类名。
3. 在根 `settings.gradle.kts` 加 `include(":plugins:<你的名字>")`。
4. 打包：`./gradlew :plugins:<你的名字>:pluginZip` →
   `plugins/<你的名字>/build/distributions/hyperglow-<你的名字>-plugin.zip`。
5. 安装：HyperGlow 设置 → 插件管理 → 从本地 ZIP 安装。

单测：`./gradlew :plugins:<你的名字>:test`（普通 Kotlin/JVM 测试源集；JVM 测试没有平台
`org.json`，需像其他插件一样声明 `testImplementation("org.json:json:…")`）。d8 打包步骤需要
Android SDK build-tools，定位规则：`local.properties` 的 `sdk.dir` → `ANDROID_HOME`。

## 3. manifest.json 参考

（示例见英文节的 JSON，字段含义相同。）

顶层字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `id` | string | 是 | `[a-z0-9_]+(\.[a-z0-9_]+)+`，全局唯一；同时是插件目录名与设置文件键。 |
| `name` | string | 是 | 插件母语文案（本仓库插件用简体中文）。 |
| `nameLocales` | map | 否 | `name` 的按语言覆盖。 |
| `author` | string | 否 | |
| `version` | string | 是 | semver；可观察行为变化时递增。 |
| `apiVersion` | int | 是 | 必须 ≤ 宿主 `HYPERLYRIC_PLUGIN_API_VERSION`（当前 `1`）。不是功能开关。 |
| `entry` | string | 是 | 入口类全名（公开无参构造，实现 `HyperLyricPlugin`）。 |
| `activationSettingKey` | string | 否 | 启用开关的设置键。缺省 = 始终激活。**声明了但默认 `false` = 插件静默不工作**——"已加载但没反应"的第一大原因。 |
| `settingGroups` | array | 否 | `{ id, title, titleLocales }`——把设置分组到一个界面。 |
| `cacheScopes` | array | 否 | `{ id, title, summary?, titleLocales?, summaryLocales? }`——见 §9。 |
| `settings` | array | 否 | 见下表。 |

设置项字段（所有 `title`/`summary` 类字段都有 `*Locales` 伴生）：

| 字段 | 说明 |
| --- | --- |
| `type` | `switch` \| `text` \| `password` \| `select` \| `multiSelect` \| `number` \| `slider` \| `action` |
| `key` | 唯一；经 `PluginConfig` 读回。 |
| `title` / `summary` | 行标题 / 副文本。 |
| `dialogSummary` | 打开设置项时展示的补充说明。 |
| `emptyValueSummary` | 值为空时的占位说明。 |
| `default` | 原始 JSON 默认值（bool / number / string；逗号/分号分隔的字符串给 `multiSelect` 播种默认集合）。 |
| `options` | `select` / `multiSelect` 必填：`[{ value, label, labelLocales? }]`；value 不得为空且代码必须处理。 |
| `min` / `max` / `step` | `slider` / `number` 用；`slider` 要求 `min ≤ max`。 |
| `valuePresentation` | `default` \| `endAction` \| `summary` \| `summaryPreview`——当前值在行内的呈现位置。 |
| `inputType` | `default` \| `uri` \| `number`——文本项的键盘意图。 |
| `previewLineCount` | 文本项预览行数（默认 2）。 |
| `conflictsWith` | 与之互斥的设置键（宿主置灰）；必须引用已声明的键。 |
| `backup` | `false` 时该值不进统一配置备份（放密钥用）。 |
| `group` | 所属 `settingGroups` 的 id。 |

结构校验（安装时拒绝）：id 格式；`apiVersion ≤ 宿主`；`entry` 非空；设置键唯一非空；
`select`/`multiSelect` 必须有非空 options；`slider` 需 `min ≤ max`；`conflictsWith`/`group` 必须
可解析；未知的 `type`/`valuePresentation`/`inputType` wire 名直接拒绝。未知顶层字段被忽略
（向前兼容）。

**本地化规则**：裸 `name`/`title`/`summary` 等字段 = 插件母语文案，`*Locales` 只列覆盖。
**显式声明 `zh-CN` 并放在最前**。宿主按「精确标签 → 同主语言且中文文种相容 → 文种中立 →
默认值」解析：简体设备不会命中 `zh-TW` 覆盖；没有 `zh-CN` 键的 map 在简体设备上渲染默认值。

## 4. API（v1）

`HYPERLYRIC_PLUGIN_API_VERSION = 1`。以下全部位于 `com.lidesheng.hyperlyric.plugin.api`。

| 接口 | 用途 |
| --- | --- |
| `HyperLyricPlugin` | 入口。`onLoad(context)` 注册扩展；`onEnable()` 在激活开关由关到开时回调；`onConfigChanged(config)` 在设置保存后回调；`onUnload()` 在重载/卸载/加载失败时回调（best-effort，恒包异常隔离）。 |
| `HyperLyricExtension` | 你注册的任何扩展。有 `id`。 |
| `LyricProcessorExtension` | 歌词处理器。`stage`（默认 `TRANSLATION_ENHANCEMENT`）；实现 `processResult(song, processingContext)`——单参重载由宿主自动桥接。返回 `null` = 本次无结果。 |
| `PluginContext` | 你拿到的**唯一**宿主对象：`pluginId`、`hostApiVersion`、`config`、`logger`、`cache`、`storage`、`registerExtension()`。 |
| `PluginConfig` | 只读设置：`getBoolean/getString/getLong/getFloat/getStringSet`。 |
| `PluginLogger` | `debug/info/warn/error`（另有 `withTag`）。写入宿主日志 `Plugin:<id>`。 |
| `PluginStorage` | 命名空间键值存储（每插件一份 SharedPreferences）：`getString/putString/remove/clear`。 |
| `PluginCache` | 宿主持久缓存：`getString/putString/getBytes/putBytes/contains/remove/clear`。manifest 未声明 `cacheScopes` 时是 no-op。 |
| `PluginCacheExtension` | 宿主 UI 的缓存管理面：`listEntries()`、`clearEntry(id)`、`clearAll()`。 |

阶段：`LYRIC_REPLACEMENT` → `TRANSLATION_ENHANCEMENT`（小者先跑）。阶段只在**单个插件内部**给
处理器排序；跨插件按插件 id 顺序整体串行（§6）。

最小处理器示例见英文节代码（即 `plugins/template` 的形状）。数据模型（时间单位一律毫秒 `Long`）：

| 类型 | 字段 |
| --- | --- |
| `PluginSong` | `id`、`name`、`artist`、`album`、`duration`、`metadata`、`lyrics: List<PluginLyricLine>?` |
| `PluginLyricLine` | `begin`、`end`、`duration`、`isAlignedRight`、`metadata`、`text`、`words`、`secondary`、`secondaryWords`、`translation`、`translationWords`、`roma` |
| `PluginWord` | `begin`、`end`、`duration`、`text`、`metadata` |
| `PluginMetadata` | `values: Map<String, String?>`——行元数据约定：`role` = `LEAD`/`BG`，`agent` = 演唱者 id（§5） |
| `PluginMediaInfo` | `title`、`artist`、`album`、`duration`、`sourcePackageName`（仅当歌词源提供了它——绝不臆测） |
| `PluginProcessingContext` | `mediaInfo`——每次调用一份，只读；联网取词时从这里拿歌曲信息 |

API 刻意不暴露任何 Android / MediaSession / Xposed 类型——你拿不到 `Context`，需要的一切只能
经 `PluginContext` 获得。

## 5. 结果协议

`PluginSongResult(song, changedFields, lyricsUpdateMode = REPLACE, changedLyricFields = emptySet())`

- **`changedFields` / `changedLyricFields` 是权威声明。** 宿主绝不做 DTO 对比推断：改了但没声明
  的字段会被丢弃；声明了 `null` 的字段会被清空。
- **`PATCH`** 保持行数与行索引不变——候选行按声明的 `changedLyricFields` 逐字段覆盖当前行。
  行数不一致 → 整个结果被拒。
- **`REPLACE`** 提供完整新行表（不得为空；每行时间轴合法：`begin >= 0`、`end >= begin`）。
- 任何结构违规（声明了 `LYRICS` 但 `lyrics == null`、PATCH 行数不符、REPLACE 空表、时间轴非法）
  → 该结果**带日志原因被丢弃**，宿主保留当前快照继续后续处理器。返回 `null` 是干净的"无结果"。

真正上屏的映射（宿主桥逐行消费，代码实证）：

| 声明 | 效果 |
| --- | --- |
| `TEXT` | 主行文本。 |
| `TRANSLATION`、`TRANSLATION_WORDS` | 翻译行——文本优先，词表兜底（任一被声明都会回填）。 |
| `ROMA` | 罗马音行。 |
| `WORDS` | 逐字时间轴。空表**不**覆盖已有词表。 |
| `TRANSLATION_WORDS` | 逐字翻译；空表不覆盖。 |
| `IS_ALIGNED_RIGHT` | 对唱左右分侧。 |
| `TEXT` / `TRANSLATION` / `ROMA` | 同时从你的行表回填**下一行组**（`nextLine` / `nextLineTranslated` / `nextLineRomanized`）。 |
| `BEGIN`、`END`、`DURATION` | 行表被改写后用于重选活动行；**采样语义保留生产者权威值**——你不改变播放位置语义。 |
| `SECONDARY`、`SECONDARY_WORDS` | 会合并进快照但**显示桥不消费**（死键）。辅助内容请放 `ROMA` / `TRANSLATION`。 |
| `metadata.role` | `LEAD` / `BG`（和声）。`BG` 行进和声/并发行车道，不参与主行选择。 |
| `metadata.agent` | 对唱声部身份：首见 agent → 槽位 0（主行），后续 agent → 槽位 1（并发行车道）。无 agent 时宿主按重叠自动分配（共享窗口 ≥1s = 两位演唱者同时在场）。 |
| `NAME`、`ARTIST`、`ALBUM` | 歌曲级字段。`ID`、`DURATION`、`METADATA` 会合并但显示不消费。 |

另外两条宿主规则值得记住：

- **增强而非替换**：插件空值绝不覆盖生产者非空值（`keepUnlessBlank`），空词表绝不抹掉已有
  时间轴——输出半截快照不会把屏上歌词抹掉。
- **声明 `WORDS` 者为准**：任一被接受的结果声明了 `WORDS` 时，宿主会用你的词窗归一合并后的
  行窗（该行的词窗是最终权威）；不声明 `WORDS` 时保留生产者行窗。

## 6. 运行时契约

**链何时跑。** 每首歌/每个会话一次，随换歌异步执行。三条输入路径汇入同一条链：Spicy 整首文档；
内存整首快照（每会话读一次）；逐行增量源（随播放聚合——首行立即跑链，之后按 ≥15s 节流补跑，
链运行期间到来的新行在链结束后补评一次）。**按增量输入设计**：你的处理器可能在一首歌里被调用
多次、行表逐步增长，也可能看到半截歌。按歌曲身份缓存，且永远不要假设"最后一行就是最后一句"。

**顺序。** 插件之间**按插件 id 字典序整体串行**；单个插件内部按 stage 排序。每个处理器拿到的是
此前所有结果合并后的快照。（stage 不会让插件互相插队。）

**超时。** 每个处理器宿主上限 **40 秒**。超时 → 你的结果被丢弃、链继续；同一处理器连续 3 次
超时 → 本轮会话内熔断，之后不再等待直接跳过。正常返回或抛错都会清零连击；重载清零全部熔断。

**取消。** 切歌会取消运行中的链。请响应中断（`Thread.interrupt`）：不要吞 `InterruptedException`，
长循环里检查 `Thread.interrupted()`。超时是协作式的——忽略中断的调用会一直占住执行线程直到
自然返回。

**线程。** 处理器在专用有界线程池（2 条线程）上运行，不占用 App 共享 IO 池。不要无限阻塞；宿主
不保证你在哪条线程上跑——处理器要对自身并发安全（或在 README 里写明它是单飞的）。

**异常隔离。** 任何抛错、超时或非法结果只丢弃这一个结果；原始歌词与其他插件毫发无伤。出错请
大声写日志，而不是输出坏数据。

**生命周期。** 插件实例跨歌存活（没有每首歌的重置——每歌状态请放缓存或按 `PluginSong.id`/歌名
自行键控）。`onUnload` 在重载/卸载/加载失败时送达；ClassLoader 本身无法卸载，被替换的旧代码
要到进程重启才彻底释放。安装/卸载/升级后宿主立即重扫重载（无需重启 App）。启用/禁用与设置修改
实时生效：激活开关每次跑链都重读，`onEnable` 在由关到开时回调，`onConfigChanged` 在设置保存时
送达——配置从下一首歌开始生效，当前歌不自动重跑。

## 7. 宿主服务

- `logger` —— 宿主日志，来源区域 `Plugin:<id>`；高频日志请自行节流。
- `storage` —— 每插件键值（SharedPreferences `plugin_storage_<id>`）；只适合小状态。
- `cache` —— 宿主插件缓存目录下每键一文件（键名 SHA-256）。配额：**每插件 16 MiB**，该插件的所有
  缓存条目共享。`putBytes` 超配额时保留旧值、只留日志——写入重要时用 `contains()`/`getBytes()`
  回读确认。所有 cache/storage 的 IO 异常在宿主侧吞掉，绝不影响歌词管线。
- manifest 未声明 cache scope → 宿主交给插件的是 **no-op 缓存**（什么都不存，设置页也不出现
  缓存区）。

## 8. 设置契约

设置由宿主设置页按 manifest 渲染，manifest 默认值在**宿主侧**生效（设置行渲染、激活判定、
配置备份——`getStringSet` 的默认值由 manifest `default` 字符串按 `,` / `;` 切分得到）。
**插件侧读回不套用**：经 `PluginConfig` 读到的是存储值 + **你传给 `getX()` 的默认值**——请让
它与 manifest 默认值保持一致，两边永不打架。`activationSettingKey` 指向的开关就是插件的启用
开关。`conflictsWith` 让互斥选项置灰；`backup: false` 把值排除在配置备份之外（放 API Key）。
代码读取的设置必须已声明、已声明的设置必须被读取——两个方向的死键都是缺陷。

## 9. 缓存管理契约

只要插件缓存了任何用户可能想查看或失效的东西（联网结果、生成物），就**必须**同时满足：

1. manifest 里声明 `cacheScopes`（id、title、可选 summary；带本地化）；
2. 注册 `PluginCacheExtension`，其 `id` 与声明的 scope id 一致，实现
   `listEntries` / `clearEntry` / `clearAll`，只返回可安全展示的元数据（绝不外传缓存正文、
   API Key 或整篇翻译）。

删除单条必须足以让插件重做那次工作——删除还必须同时失效插件自己的内存副本。配额与存储位置由
宿主掌握；键、序列化、schema 版本与过期策略由插件掌握。

## 10. 打包规则

`classes.dex` 内：

- **必须**包含插件自身类，以及**全部第三方运行时依赖，含 `kotlin-stdlib`**。插件是自包含的：
  自带类按子优先加载（§11），你的副本恒胜。宿主**无法**提供 `kotlin.*`：release 包经 R8 混淆、
  没有 `kotlin.**` keep，stdlib 全量改名，双亲回退解析不到任何东西。（2026-10 之前的插件构建
  假定"宿主提供 kotlin-stdlib"——它们在 release 宿主上 `onLoad` 即抛
  `NoClassDefFoundError: kotlin.jvm.internal.Intrinsics`。不要复制那个模式；CI 现在会拦下它。）
- **不得**包含插件 API 包（`com.lidesheng.hyperlyric.plugin.api.**`）——宿主按双亲优先解析；
  捆进去是死重且破坏类型同一性。
- **不得**包含平台库（如 `org.json`）——用 `compileOnly` 声明（JVM 单测另加
  `testImplementation`）。

d8 步骤（见任一 `plugins/*/build.gradle.kts`）：

```
d8 --release --min-api 33 --lib <android.jar> --output <dir> <plugin.jar> <全部 runtime jar>
```

- 输入取**全部** runtime jar（含 `kotlin-stdlib`）；依赖图里若出现空的 `kotlin-stdlib-jdk7/jdk8`
  兼容壳，排除它们（防重复类）。
- `--min-api 33`。Kotlin `jvmTarget` 与依赖对齐：Java 21 字节码的库（如
  `accompanist-lyrics-core`）要求 `jvmTarget = 21`——inline API 会把库字节码拷进你的模块。
- ZIP 必须恰为 `manifest.json` + `classes.dex`——无资源、无嵌套包。

CI 门禁（`plugins/check-plugin-dex.py`，每个 PR 与每次 `main` 推送都跑）：构建出的每个 ZIP，其
dex 引用 `kotlin.jvm.internal.Intrinsics` 时必须定义它（= stdlib 确实打进去了）、不得捆绑插件
API 类、并校验 ZIP 形状。过不了门禁的插件不可发布。

## 11. 类加载

按前缀两条解析路径：

- **双亲优先（宿主拥有）**：`java.`、`javax.`、`android.`、`androidx.`、`dalvik.`、`libcore.`、
  `org.json.`、`org.w3c.`、`org.xml.`、`org.xmlpull.`，以及插件 API 契约包
  `com.lidesheng.hyperlyric.plugin.api.`。
- **子优先（其余一切）**：你的 dex 里有该类就用你的；没有才回退宿主。

推论：你可以随意捆绑任何库——它永远不会被宿主 R8 收紧后的副本顶掉（跨 ClassLoader 访问那些
副本曾直接抛 `IllegalAccessError`）。你无法遮蔽平台类或 API 类——这是设计：API 需要你与宿主
共享同一份类型身份。API 边界上没有 Kotlin 运行时类型穿越（集合类型在运行期擦除为 `java.util`），
这正是子优先隔离安全的原因。

## 12. 验证与发布

- **单测必要但不充分**：`./gradlew :plugins:<名字>:test`。用户能看见的行为（由宿主渲染）需要
  真机验证——在插件 README 里记录结论，并如实列出**未验证**的部分。
- **README** 按 `PLUGIN_RELEASE_CONVENTIONS.md` §5：管线位置、写/不写字段、来源、设置表、
  缓存说明、验证情况、限制、致谢与许可。
- **CI**：每个 PR 与每次 `main` 推送都会为 `settings.gradle.kts` 里的每个插件构建 `pluginZip`；
  dex 门禁（§10）随之运行；PR 上上传 artifact，滚动 `plugins` 预发行只从 `main` 重建（资产名
  固定，版本号活在 manifest 里）。
- **版本**：任何可观察变化都递增 `manifest.version`；绝不为了"解锁"什么而改 `apiVersion`——
  它是你所针对的宿主 API 代次。

## 13. 避坑清单

- [ ] `kotlin-stdlib` 已打进 dex（自包含）——release 宿主上 `onLoad` 失败的第一大原因。
- [ ] dex 里没有 `com.lidesheng.hyperlyric.plugin.api.**`，也没有平台库。
- [ ] 改过的字段都在 `changedFields` / `changedLyricFields` 里声明；PATCH 行数一致；REPLACE
      时间轴单调。
- [ ] `activationSettingKey` 已声明且默认值符合预期（缺省 = 恒开；默认 `false` = 用户没开就不工作）。
- [ ] 增量源：处理逐步增长/半截的行表；按歌曲身份缓存。
- [ ] 单次调用不超过 40s；响应中断；不无限阻塞。
- [ ] 空输出不会抹屏（宿主侧有兜底，但别靠它掩盖 bug）。
- [ ] 缓存：声明 `cacheScopes` + 实现 `PluginCacheExtension`，否则缓存是 no-op；写入重要时
      `putBytes` 后回读 `contains()`（超配额保留旧值）。
- [ ] 辅助内容放 `ROMA` / `TRANSLATION`；`SECONDARY` / `SECONDARY_WORDS` 是死键。
- [ ] manifest 与代码一致（没有未被读取的设置，也没有未声明的读取）。
- [ ] 不假设 Context / MediaSession / Xposed——API 一个都不给。

## 14. 参见

- `plugins/template/README.md`、`plugins/demo/` —— 代码。
- `docs/PLUGIN_RELEASE_CONVENTIONS.md` —— 打包 / 发布 / README 契约。
- `docs/ARCHITECTURE.md` §「插件运行时边界」—— 信任模型与生命周期。
- `plugins/api/.../PluginApi.kt` —— 冻结接口及其自带 KDoc。
