# Plugin Release Conventions

# English / English

Status: canonical packaging and release contract for every module under `plugins/`

This document defines how a plugin is packaged, versioned, documented, verified, and published.
It complements — and does not replace — three neighbours:

- `docs/ARCHITECTURE.md` § *Plugin Runtime Boundary* — trust model, staged loading, class-loader
  policy, settings keying.
- `docs/CONTRIBUTING.md` — the `plugins/api` freeze rule and the CI overview.
- `docs/RELEASE_CONVENTIONS.md` — release-note format for the app itself (plugins have their own
  rolling release, see below).

## 1. What a plugin release is

- A plugin ships as a **ZIP containing exactly two entries**: `manifest.json` and `classes.dex`.
  No resources, no nested archives, no signature files, no extra directories.
- Plugins are published to a **single rolling pre-release tagged `plugins`**
  (`HyperGlow Plugins (latest)`): the `publish-plugins` job deletes and recreates it on every
  `main` push, so the assets always match the newest `main`. It is deliberately marked
  pre-release so it can never be mistaken for an app release.
- **Asset names are fixed and version-free** — `hyperglow-<name>-plugin.zip` — because the
  release is rolling; the version lives in the manifest, not in the file name. A stable URL is
  the point: users can bookmark one link.
- On pull requests the same job builds both plugins and uploads them as workflow artifacts but
  **does not publish**: unreviewed code never reaches a release.

## 2. Versioning

- `manifest.version` is semver and is bumped whenever the plugin's observable behaviour changes.
- `manifest.apiVersion` is the host-API generation the plugin is written against; it must be
  `≤` the host's `HYPERLYRIC_PLUGIN_API_VERSION`. It is not a feature switch — never bump it to
  "unlock" something.
- `plugins/api` is a frozen byte-identical copy of HyperLyric's contract (`CONTRIBUTING.md`).
  A plugin may not require any declaration change there; if a capability needs a new API
  declaration, that is an upstream conversation first.

## 3. Packaging rules

Inside `classes.dex`:

- **Must** contain the plugin's own classes plus every third-party *runtime* dependency it needs
  (plugin-bundled classes load child-first, so bundling is how a plugin stays self-contained).
- **Must not** contain the plugin API package (`com.lidesheng.hyperlyric.plugin.api.**`) — the
  host resolves it parent-first and a bundled copy is dead weight.
- **Must not** contain `kotlin-stdlib` — the host provides it.
- **Must not** contain platform libraries such as `org.json` — they resolve to the platform copy.
  Declare them `compileOnly` (plus `testImplementation` for JVM unit tests).
- The d8 step must therefore take *all* runtime jars except `kotlin-stdlib` as inputs, with
  `kotlin-stdlib` supplied only as `--classpath`. A hand-built dex that skips transitive
  dependencies is not equivalent to the CI artifact — **the CI-built ZIP is the released one**.

Inside `manifest.json`:

- `id` matches `[a-z0-9_]+(\.[a-z0-9_]+)+` and is globally unique.
- `entry` names a class with a **public no-argument constructor** implementing `HyperLyricPlugin`
  (the host does `Class.forName(entry).newInstance()`).
- `activationSettingKey`, when present, points at a declared setting.
- Every `select`/`multiSelect` setting declares options; every option value is handled by code.
- Settings the code reads are declared; declared settings are read (no dead keys either way).
- Localized text: the plain `name`/`title`/`summary`/`dialogSummary`/`emptyValueSummary` fields are
  the plugin's own language (Simplified Chinese for the plugins in this repository), and each
  `*Locales` map carries the overrides. **Declare `zh-CN` explicitly and put it first** — the host
  resolves *exact tag* → *same language and compatible Chinese script* → *script-neutral* →
  *default*, so a Simplified device (`zh-CN` / `zh-Hans*`) never takes a `zh-TW` override, and a
  map without a `zh-CN` key renders the default for Simplified devices (2026-10-07: a settings page
  that declared only `zh-TW`/`en` showed Traditional Chinese on Simplified devices).

## 4. Cache management is part of the contract

If a plugin caches anything the user could want to inspect or invalidate (network lookups,
generated artifacts), then **both** are required:

1. a `cacheScopes` entry in the manifest — without it the host hands the plugin a no-op cache and
   nothing is actually stored, and the settings page shows no cache section at all;
2. a registered `PluginCacheExtension` whose `id` equals the declared scope id, implementing
   `listEntries` / `clearEntry` / `clearAll`.

A cache the user cannot see or clear is a defect, not a missing nicety: deleting one entry must
be enough to make the plugin redo that work. Deleting must also invalidate the plugin's own
in-memory copy. The host owns the quota (16 MiB per plugin) and the storage location; the plugin
owns keys, serialization and expiry.

## 5. Required documentation (per plugin)

`plugins/<name>/README.md` must carry, in this order:

1. what the plugin does and **where it sits in the pipeline** (stage, update mode);
2. the fields it writes and the fields it deliberately does **not** write, with the reason;
3. its online sources / data sources and their verified status;
4. settings table;
5. cache management notes (what is cached, for how long, how the user clears it);
6. **verification status** — what was verified, where (host tests, real API, device) and what was
   not; use the STYLE_GUIDE §12 evidence labels honestly;
7. known limitations;
8. **credits and licenses** (§6 below).

## 6. Attribution and licensing

Every plugin README must credit, and every new dependency must be reflected here:

- **Bundled libraries** — project name, author, license, and the fact that they are bundled into
  the dex.
- **Ported behaviour** — the source project, and an explicit statement that only behaviour and
  parameters were ported (no code copied), when that is the case.
- **Data sets** — upstream project, license, and the pinned revision/hash the generator verifies.
- **Online lyric sources** — that the data belongs to the platforms and rights holders, that only
  public endpoints are read, and that no authentication or paywall is bypassed.

Licenses must be compatible with the repository license (GPL-3.0). Apache-2.0 dependencies and
data are fine; the obligation is attribution, not permission.

The rolling `plugins` release notes carry the same credits block, generated by the
`publish-plugins` job — so credits added to the README and to the workflow stay in sync.

## 7. Verification gates

**Automated (CI, every PR and every `main` push):**

- `:plugins:<name>:pluginZip` builds for every plugin in `settings.gradle.kts` — this is the only
  automated check of the packaging pipeline (`pluginZip` is not part of the app build).
- Artifacts are uploaded on PRs; the release is published only from `main`.

**Not automated — the author/reviewer must do it before publishing:**

- plugin unit tests: `./gradlew :plugins:<name>:test` (the app CI job never runs them);
- artifact inspection: ZIP entries are exactly `[classes.dex, manifest.json]`; the manifest passes
  the host's structural rules; the dex defines the expected classes and none of the forbidden ones
  (§3); the entry class satisfies the constructor contract;
- behaviour that a user can see (the host renders it) needs device verification — unit tests are
  necessary, not sufficient, and an unverified feature must be listed as such in the README.

## 8. Release-notes rule

The `plugins` release body is regenerated on every `main` push, so it must stay
version-independent: what the plugins are, how to install them, the credits block, and the
commit it was built from. Anything that changes per version (behaviour notes, migration advice)
belongs in the plugin README or the app's release notes — not in the rolling body.

## 9. Checklist before a plugin can ship

```
[ ] manifest: id / entry / version / apiVersion / settings / cacheScopes match the code
[ ] activationSettingKey points at a declared switch; no unread settings, no undeclared reads
[ ] cacheScopes id == PluginCacheExtension SCOPE_ID; list/clear implemented and wired
[ ] dex: no plugin-API / kotlin-stdlib / platform classes bundled
[ ] zip: exactly manifest.json + classes.dex
[ ] plugin unit tests green locally
[ ] README complete (pipeline position, fields written/not written, sources, settings, cache,
    verification status, limitations, credits & licenses)
[ ] credits updated for every new dependency, data set or ported behaviour
[ ] device check recorded, or explicitly listed as not verified
```

---

# 中文 / Chinese

状态：`plugins/` 下所有可安装插件的打包与发布契约

本文档定义插件的打包、版本、文档、验证与发布方式。它与以下三份文档互补而不替代：

- `docs/ARCHITECTURE.md` §「插件运行时边界」—— 信任模型、分阶段加载、类加载策略、设置键。
- `docs/CONTRIBUTING.md` —— `plugins/api` 冻结规则与 CI 概览。
- `docs/RELEASE_CONVENTIONS.md` —— **主程序**的发布日志格式（插件有自己的滚动发布，见下）。

## 1. 什么是"一次插件发布"

- 插件以 **恰好含两个条目的 ZIP** 交付：`manifest.json` 与 `classes.dex`。不含资源、嵌套包、
  签名文件或多余目录。
- 插件发布到**唯一一条滚动预发行**，tag 为 `plugins`（标题 `HyperGlow Plugins (latest)`）：
  `publish-plugins` job 在每次 `main` 推送时删除重建，资产始终对应最新 main。标为预发行是刻意的——
  它绝不能被误当成主程序的正式版。
- **资产名固定、不带版本号**（`hyperglow-<name>-plugin.zip`），因为发布是滚动的；版本号活在
  manifest 里而不是文件名里。稳定 URL 就是目的：用户可以只收藏一个链接。
- 在 PR 上，同一个 job 会构建两个插件并作为 workflow artifact 上传，但**不发布**：未经评审的
  代码绝不进 release。

## 2. 版本规则

- `manifest.version` 为 semver，插件的可观察行为变化时递增。
- `manifest.apiVersion` 是插件编写所针对的宿主 API 代次，必须 `≤` 宿主的
  `HYPERLYRIC_PLUGIN_API_VERSION`。它不是功能开关——绝不为了"解锁"什么而改它。
- `plugins/api` 是 HyperLyric 契约的冻结逐字节副本（`CONTRIBUTING.md`）。插件不得要求在那里
  改动任何声明；若某能力需要新的 API 声明，先走上游契约变更的流程。

## 3. 打包规则

`classes.dex` 内：

- **必须**包含插件自身类，以及它需要的全部第三方**运行时**依赖（插件自带类按子优先加载，
  自带依赖正是插件保持自包含的方式）。
- **不得**包含插件 API 包（`com.lidesheng.hyperlyric.plugin.api.**`）——宿主按双亲优先解析，
  捆进去是死重。
- **不得**包含 `kotlin-stdlib` —— 宿主提供。
- **不得**包含平台库（如 `org.json`）——它们解析到平台副本。用 `compileOnly` 声明
  （JVM 单测另加 `testImplementation`）。
- 因此 d8 步骤必须把**除 `kotlin-stdlib` 外的所有运行时 jar** 作为输入，`kotlin-stdlib` 只作
  `--classpath`。手工搭的 dex 若漏掉传递依赖，与 CI 产物**不等价**——**以 CI 构建的 ZIP 为准**。

`manifest.json` 内：

- `id` 匹配 `[a-z0-9_]+(\.[a-z0-9_]+)+` 且全局唯一。
- `entry` 指向一个**有公开无参构造**、实现 `HyperLyricPlugin` 的类（宿主用
  `Class.forName(entry).newInstance()` 加载）。
- `activationSettingKey`（若声明）必须指向一个已声明的设置。
- 每个 `select`/`multiSelect` 都要有 options，且每个选项值在代码里被处理。
- 代码读取的设置必须已声明；已声明的设置必须被读取（两个方向都不许有死键）。

## 4. 缓存管理属于契约的一部分

只要插件缓存了任何用户可能想查看或失效的东西（联网结果、生成物），就**必须**同时满足：

1. manifest 里声明 `cacheScopes` —— 不声明时宿主交给插件的是 no-op 缓存，实际什么都没存下，
   设置页也不会出现缓存区；
2. 注册 `PluginCacheExtension`，其 `id` 与声明的 scope id 一致，实现
   `listEntries` / `clearEntry` / `clearAll`。

**用户看不到、清不掉的缓存是缺陷，不是"少个锦上添花"**：删除单条就必须足以让插件重做那次工作。
删除还必须同时失效插件自己的内存副本。配额（每插件 16 MiB）与存储位置由宿主掌握；键、序列化与
过期策略由插件掌握。

## 5. 每个插件必含的文档

`plugins/<name>/README.md` 必须依次包含：

1. 插件做什么、**位于管线何处**（阶段、更新模式）；
2. 它写哪些字段、**刻意不写**哪些字段及原因；
3. 在线来源 / 数据来源及其实测状态；
4. 设置项表；
5. 缓存管理说明（缓存什么、保留多久、用户如何清除）；
6. **验证情况** —— 验证了什么、在哪种环境（宿主测试、真实接口、真机）、**以及什么没有验证**；
   诚实使用 STYLE_GUIDE §12 的证据标签；
7. 已知限制；
8. **致谢与许可**（见 §6）。

## 6. 致谢与许可

每个插件 README 都必须致谢，且每新增一项依赖都要在这里体现：

- **捆绑的库** —— 项目名、作者、许可，并说明它被打进 dex。
- **移植的行为** —— 来源项目，并在确实如此时明确写出"只移植了行为与参数，未复制代码"。
- **数据集** —— 上游项目、许可，以及生成器校验的固定 revision/hash。
- **在线歌词来源** —— 数据版权归各平台与权利人所有、只读取公开接口、不绕过鉴权或付费墙。

许可必须与仓库许可（GPL-3.0）兼容。Apache-2.0 的依赖与数据没问题；义务是署名，不是授权。

滚动 `plugins` release 的说明里带同一份致谢块，由 `publish-plugins` job 生成——所以 README 与
workflow 里的致谢要保持同步。

## 7. 验证门禁

**自动（CI，每个 PR 与每次 main 推送）：**

- `settings.gradle.kts` 里的每个插件都要能构建出 `:plugins:<name>:pluginZip` —— 这是打包管线
  **唯一**的自动化检查（`pluginZip` 不属于 app 构建）。
- PR 上上传 artifact；只有 `main` 才发布。

**非自动 —— 作者/评审在发布前必须做：**

- 插件单测：`./gradlew :plugins:<name>:test`（app 的 CI job 从不跑它们）；
- 产物检查：ZIP 条目恰为 `[classes.dex, manifest.json]`；manifest 过宿主结构校验；dex 里定义
  的类符合预期且不含 §3 的禁项；入口类满足构造契约；
- 用户能看见的行为（由宿主渲染）需要真机验证 —— 单测是必要非充分条件；未验证的功能必须在
  README 里如实列出。

## 8. 发布说明的规则

`plugins` release 的正文每次 main 推送都会重建，因此必须与版本无关：插件是什么、怎么安装、
致谢块、构建自哪个 commit。任何随版本变化的内容（行为变更说明、迁移建议）属于插件 README 或
主程序的发布日志，而不是这条滚动正文。

## 9. 发布前检查清单

```
[ ] manifest：id / entry / version / apiVersion / settings / cacheScopes 与代码一致
[ ] activationSettingKey 指向已声明的开关；没有未被读取的设置，也没有未声明的读取
[ ] cacheScopes 的 id == PluginCacheExtension 的 SCOPE_ID；list/clear 已实现并接上
[ ] dex：未捆绑插件 API / kotlin-stdlib / 平台类
[ ] zip：恰为 manifest.json + classes.dex
[ ] 插件单测本地全绿
[ ] README 完整（管线位置、写/不写字段、来源、设置、缓存、验证情况、限制、致谢与许可）
[ ] 每新增依赖/数据集/移植行为都已更新致谢
[ ] 真机验证已记录，或明确标注未验证
```
