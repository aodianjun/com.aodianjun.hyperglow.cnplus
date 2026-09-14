# 上游同步状态（amarinne/hyperglow → CN+）

# English / 英文

> Purpose: whenever the user asks to "sync upstream updates", read this file first to learn the
> current sync baseline, then compare it against the list of new upstream commits to decide what
> needs to be ported. The two repositories have completely independent git histories (CN+ is a
> repackaged standalone fork), so a direct merge is impossible — changes can only be selected
> and ported manually by content.

## Current Status

- **CN+ version**: 0.3.83 (110), upstream baseline as of `8422d78` (v0.3.97; full evaluation completed on 2026-09-11: code either ported or exempted with rationale, see the tables below)
- **Upstream latest**: 2026-09-11 `748912e`, version 0.3.173 (185) — **evaluated 2026-09-14**, see the `748912e` Evaluation section below. No mandatory port; highest-value item is ConfigBackupCodec/SettingsSession, and `suppressStockAodContent` warrants a verify-first check.
- **Upstream repository**: https://github.com/amarinne/hyperglow (default branch: main)
- Baseline verification marks (2026-09-05): AodLyricBridgeService already includes dynamic uid matching,
  HierarchyFields.kt and its use across all hooks, missingProbeNames, and miuix via the public Maven Central repository

## `748912e` Evaluation (v0.3.173 · 2026-09-14)

One large feature commit (61 files, +7735/−1115) on top of the `8422d78` baseline.
Decomposed into independently-assessable features:

| # | Feature | Key files / size | CN+ relevance | Recommendation |
|---|---|---|---|---|
| 1 | AOD canvas rotates with the device (`AodOrientationMonitor` new — accelerometer lifecycle / debounce / framework-rotation rebase; wire v4/v6/v8 adds `aodRotateWithDevice`/`aodRotationMode`/per-orientation anchor·scale·padding; ~+2300 in `AodSurfaceController`+`AodLyricCanvasView`) | AodOrientationMonitor, AodStateWire, AodRenderPreferences, AodSurfaceController, AodLyricCanvasView, HookEntry + AodOrientationMonitorTest / AodCanvasBiasTest / AodCanvasLayoutTest | Medium — only landscape / tilted-AOD users | **Defer (large, risk-heavy)**. CN+ AOD is portrait-first; upstream itself documents a sensor-callback-loss bug needing a fixed workaround. Port only if landscape full-screen AOD is a confirmed need |
| 2 | `suppressStockAodContent` — hide Xiaomi's stock AOD content while HyperGlow draws its own full-screen canvas | wire field + canvas + preferences | High (core to rendering a replacement AOD) | **Verify-first**: CN+ already has its own stock-content suppression stack; if it already hides stock lyrics, mark covered (note in LOCKSCREEN_AOD_BEHAVIOR_SPEC.md); otherwise extract this one field + render gate |
| 3 | `HookRegistry` (new) + `HookEntry` hot-reload (`onHotReloading`/`onHotReloaded`, generation retire, `PROTECTIVE` mode, refuse reload while a lyric session is active, hook install split into `installDefaultLoaderHooks`/`installAodHooks`) | HookRegistry, HookEntry + HookRegistryTest | Low–Medium (dev-loop ergonomics, not user-facing) | **Defer**: CN+ hooks are statically installed and work; adopting this is a large entry-point refactor with no end-user gain |
| 4 | Config backup/restore (`ConfigBackupCodec` +200, `SettingsSession` +91) + Settings UI tab rework (`MainActivity` +1559) | ConfigBackupCodec, SettingsSession, MainActivity, PreferenceSettingsStore + ConfigBackupCodecTest / SettingsSessionTest | High user value | **Port codec + session only** (self-contained, testable, high value — this is the previously-deferred `0424ae9` item now shipped upstream). **Skip** the MainActivity UI tab restructure: CN+ has its own Settings layout |
| 5 | Misc hardening feeding 1/2 (`AodBrightnessHook` +58, `AodLifetimeHook` +19, `AodStateProjector` +62, `LyricCanvasMapper` +40, `LockscreenSurfaceController` +51, `LinkageTransitionCoordinator` +42, `SpicyBridgeDocumentStore` +68, `DiagnosticLogging` +20, CustomizationModels/Repository, SystemUiCustomization, SceneCompiler) + many test updates | many | Varies per file | **Selective**: most are coupled to features 1–2 and not worth extracting alone; scan `AodStateBridge`/`AodStateProjector`/`DiagnosticLogging`/`SpicyBridgeDocumentStore` for standalone fixes |

**Bottom line**: no mandatory port. Highest-value, lowest-risk item is **#4 ConfigBackupCodec/SettingsSession**; **#2** is worth a verify-first check against CN+'s existing suppression; **#1 and #3** are deferred. After any port, re-run the API-contract fingerprint and full CI (554+ tests), then update this file.

## Synced / Included

| Upstream commit | Date | Content | Status |
|---|---|---|---|
| `b0254d5` (v0.3.96) | 2026-09-01 | AOD brightness clamping: AodBrightnessHook (new file) + three-path installation in HookEntry + AodLifetimeHook visibility telemetry / setLyricGuardActive linkage; AodLyricClient keepalive same-revision merge (mergePendingKeepAlive); AodPowerCoordinator wake identity consumption moved earlier; AodSurfaceController alpha chain detection (effectiveSurfaceAlpha/surfaceAlphaChain); DiagnosticCaptureCollector logcat `-t 4000`→`-T <timestamp>`; diagnostic guidance copy (4 languages + template); ARCHITECTURE/DIAGNOSTIC_REPORTING/LOCKSCREEN_AOD_BEHAVIOR spec sync | ✅ Ported (2026-09-08; PAUSE_CONFIRM_MS=5s, notification geometry dead zone, and projection stale were already in the CN+ baseline — this pass only completed docs and tests) |
| `cc1f62f`+`ced2769`+`0424ae9` (diagnostics part) | 2026-08-13~22 | Three diagnostic capabilities: ① DiagnosticTraceFile — in-app log file mirror (HyperOS drops app-side logcat; AppLog i/w/e written to disk with 512KB rotation); ② SystemUiLyricProjection named rejection logging (the 5 silent rejections in accept() changed to deduplicated rejected(reason) records); ③ AodDrawWakePulseResult — four-way outcome classification of draw wake lock pulses + deduplicated recording | ✅ Ported (2026-09-11; CN+ enhancements: traces filtered by capture start and folded into the report's logs section as `app_trace=`, also carried on the root rejection path, with a dedicated APP_TRACE_BYTES=96KB budget — upstream only persists to disk without including it in reports) |
| `cc1f62f`+`ced2769` (diagnostics part 2) | 2026-08-13~15 | Two items: ④ named reasons for document rejections — SpicyBridgeDocumentStore.accept/commit return String? (all 14 rejection paths report a literal: no-state/state-mismatch/payload-identity/oversized/malformed/commit-timing/commit-order), spicyBridgeDocumentTimingFault reports the divergent field (duration/row/word/fillEnd), Service-side logDocumentRejection deduplicated logging; ⑤ postHandoff diagnostics — when handoff goes active→inactive while the scenario is still active, two full surface snapshots at +1s/+7s (visibility / alpha chain / rect / scale / render / wake in a single line), canceled by detachCurrent | ✅ Ported (2026-09-11; timingFault keeps the CN+ relaxed fillEndMs semantics (may cross the current line end, must not exceed the song length); the upstream spicyBridgeDocumentMismatch engine-side document clearing path does not exist in CN+ (the producer clears proactively), so that function was not ported) |
| `8422d78` (v0.3.97) | 2026-09-01 | Reject display of Japanese kana ruby annotations on Chinese songs (hasLanguageInconsistentKanaRuby/isKana, language field threaded through, fillEndMs crossing the line end legalized + lineEndMs render clamping) | ✅ Ported (2026-09-05, rewritten for the CN+ projection layer structure; 2026-09-11 re-reviewed the b0254d5..8422d78 increment and disposed of all of it: ① scalar auxiliary lines come only from documents — evaluated and **not ported**; CN+ keeps the fallback scalar romaji/translation line when no document exists: the trigger surface is limited to untimed / document-not-yet-arrived cases, the kana guard already covers the document main path, the scalar path has no observed harm in practice, and porting would be pure subtraction (cutting AI-translation display for untimed songs); the divergence is recorded in LOCKSCREEN_AOD_BEHAVIOR_SPEC.md; if wrong scalar annotations are ever reported, reverting only needs two lines in SpicyLyricProducer; ② three spec sections (auxiliary line source / kana rejection / fillEnd clamping) completed; ③ the timingFault message window=→duration= is a pure copy difference, skipped; ④ versionCode 0.3.97 not applicable) |
| `6216fdc` | 2026-08-08 | Version pinning retired: XiaomiProfileState adds AVAILABLE, capability count display (availableCapabilityCount/totalCapabilityCount), removal of the verifiedRuntimeProfile version pin, summary changed to available=n/total, DiagnosticSetupPolicy runnable state set | ✅ Ported (2026-09-05; the CN+ experiment-mode local override logic is kept) |
| `c5b1ffa` | 2026-08-11 | DiagnosticContract validation adds the "available" status | ✅ Ported (2026-09-05) |
| `f9dfa01` | 2026-08-09 | SystemUI dynamic uid matching + HierarchyFields field-chain traversal + probe-missing logging | ✅ Synced (except the UpdateChecker part; CN+ uses its own VersionCheck.kt) |
| `8d89b10` | 2026-08-06 | Major refactor introducing AodStateProjector | ✅ Synced |
| `2608031` | 2026-08-05 | Remove the credential-based miuix repository (switch to Maven Central) | ✅ Synced |
| `bf988be` | 2026-08-03 | bump 0.3.50 | ➖ Version number not applicable (CN+ has an independent version numbering scheme) |
| Earlier commits | ≤2026-08-03 | FAQ / diagnostics policy / history | ➖ Not individually verified (the baseline as a whole already includes them) |

## Evaluated, Deferred Items (within the baseline; not ported for stated reasons)

| Upstream commit | Date | Content | CN+ relevance | Recommendation |
|---|---|---|---|---|
| `0424ae9` (remaining part) | 2026-08-22 | ConfigBackupCodec (config backup/restore), SettingsSession, LucideIcons, RTL lyrics rendering (AodTextDirection / physical alignment conversion / drawDirectionalText), hideFromRecents | Medium | Optional: config backup has high user value but entails a MainActivity refactor; RTL is of low value for CN users |
| `ced2769` (remaining part) | 2026-08-13 | DiagnosticsScreen simplification, AodKeepaliveRegressionTest | Low | Defer: the UI simplification does not apply (CN+ DiagnosticsScreen has a different structure); postHandoff diagnostics already ported |
| `cc1f62f` (remaining part) | 2026-08-15 | Document transport grace (scheduleDocumentClear/DOCUMENT_TRANSPORT_GRACE_MS), spicyBridgeDocumentMismatch (engine-side held-document clearing path), logKeepAliveEdge/logAodEnabledEdge | Medium | Defer: the transport grace is structurally inapplicable (CN+ documents are cleared proactively by the producer, with no passive clearing path); the mismatch call path does not exist in CN+; recommend re-evaluating logKeepAliveEdge when a corresponding issue appears |

## Operating Procedure When Syncing

1. `git fetch upstream main` (remote `upstream` = https://github.com/amarinne/hyperglow , already configured)
2. Cross-check the tables above and review each change with `git show <sha>`
3. Port to CN+ manually by content (note that CN+ has diverged deeply: Lyricon producer stack, version numbering, CN music app adaptation)
4. Version numbers do not follow upstream (CN+ has an independent scheme); keep only one of UpdateChecker/VersionCheck
5. After porting, run CI (554+ tests) and push once everything is green
6. **Update this file**: move ported commits into the Synced table and update the baseline date

---

# 中文 / Chinese

> 用途：每次用户要求“同步上游更新”时，先读本文件了解已同步基线，
> 再对照上游新提交清单，判断哪些需要移植。
> 两仓库 git 历史完全独立（CN+ 为重打包独立版），无法直接 merge，
> 只能按内容手工挑选移植。

## 当前状态

- **CN+ 版本**：0.3.83 (110)，上游基线截至 `8422d78`（v0.3.97，2026-09-11 完成全量评估：代码已移植或按理由豁免，见下表）
- **上游最新**：2026-09-11 `748912e`，版本 0.3.173 (185) —— **已于 2026-09-14 评估**，见下方「`748912e` 评估」小节。无必须移植项；最高价值是 ConfigBackupCodec/SettingsSession，`suppressStockAodContent` 需先核实再做决定。
- **上游仓库**：https://github.com/amarinne/hyperglow（default branch: main）
- 基线核实标记（2026-09-05）：AodLyricBridgeService 已含 uid 动态匹配、
  HierarchyFields.kt 及全 hook 使用、missingProbeNames、miuix 走 Maven Central 公共仓库

## `748912e` 评估（v0.3.173 · 2026-09-14）

`8422d78` 基线之上的单个大特性提交（61 文件，+7735/−1115）。拆分为可独立评估的特性：

| # | 特性 | 关键文件/体量 | CN+ 相关性 | 建议 |
|---|---|---|---|---|
| 1 | AOD 画布随设备旋转（`AodOrientationMonitor` 新文件——加速度计生命周期/防抖/框架旋转 rebase；wire v4/v6/v8 新增 `aodRotateWithDevice`/`aodRotationMode`/各朝向 锚点·缩放·四周距；`AodSurfaceController`+`AodLyricCanvasView` 约 +2300） | AodOrientationMonitor、AodStateWire、AodRenderPreferences、AodSurfaceController、AodLyricCanvasView、HookEntry + AodOrientationMonitorTest / AodCanvasBiasTest / AodCanvasLayoutTest | 中——仅横屏/侧放 AOD 用户 | **暂缓（体量大、风险高）**。CN+ AOD 以竖屏为主；上游自带传感器回调丢失 bug 并给出固化规避。仅当横屏全屏 AOD 为真实需求时再移植 |
| 2 | `suppressStockAodContent`——HyperGlow 自绘全屏画布时隐藏小米系统 AOD 内容 | wire 字段 + 画布 + 偏好 | 高（替身 AOD 渲染的核心） | **先核实**：CN+ 已有自己的系统内容抑制栈；若已隐藏系统歌词则标注已覆盖（记入 LOCKSCREEN_AOD_BEHAVIOR_SPEC.md）；否则单独抽取该字段 + 渲染开关 |
| 3 | `HookRegistry`（新文件）+ `HookEntry` 热重载（`onHotReloading`/`onHotReloaded`、generation 退役、`PROTECTIVE` 模式、lyric 会话活跃时拒绝重载、hook 拆分为 `installDefaultLoaderHooks`/`installAodHooks`） | HookRegistry、HookEntry + HookRegistryTest | 低-中（开发迭代便利，非用户功能） | **暂缓**：CN+ hook 静态安装、运行正常；采用此结构是大型入口重构、无终端用户收益 |
| 4 | 配置备份/恢复（`ConfigBackupCodec` +200、`SettingsSession` +91）+ 设置页 tab 化重构（`MainActivity` +1559） | ConfigBackupCodec、SettingsSession、MainActivity、PreferenceSettingsStore + ConfigBackupCodecTest / SettingsSessionTest | 用户价值高 | **仅移植 codec + session**（自包含、可测、价值高——即此前已暂缓的 `0424ae9` 项现已在上游落地）。**跳过** MainActivity UI tab 重构：CN+ 有自有设置布局 |
| 5 | 支撑 1/2 的杂项加固（`AodBrightnessHook` +58、`AodLifetimeHook` +19、`AodStateProjector` +62、`LyricCanvasMapper` +40、`LockscreenSurfaceController` +51、`LinkageTransitionCoordinator` +42、`SpicyBridgeDocumentStore` +68、`DiagnosticLogging` +20、CustomizationModels/Repository、SystemUiCustomization、SceneCompiler）+ 大量测试更新 | 多文件 | 因文件而异 | **选择性**：多数与特性 1–2 耦合，不值得单独抽取；用 `AodStateBridge`/`AodStateProjector`/`DiagnosticLogging`/`SpicyBridgeDocumentStore` 排查独立修复 |

**结论**：无必须移植项。最高价值、最低风险是 **#4 ConfigBackupCodec/SettingsSession**；**#2** 值得先对照 CN+ 现有抑制栈核实战后再定；**#1、#3 暂缓**。任何移植后都需重跑插件契约指纹与全量 CI（554+ 测试），并更新本文件。

## 已同步 / 已包含

| 上游提交 | 日期 | 内容 | 状态 |
|---|---|---|---|
| `b0254d5` (v0.3.96) | 2026-09-01 | AOD 亮度钳制：AodBrightnessHook（新文件）+ HookEntry 三路径安装 + AodLifetimeHook visibility 遥测/setLyricGuardActive 联动；AodLyricClient keepalive 同 revision 合并（mergePendingKeepAlive）；AodPowerCoordinator wake identity 前移消费；AodSurfaceController alpha 链检测（effectiveSurfaceAlpha/surfaceAlphaChain）；DiagnosticCaptureCollector logcat `-t 4000`→`-T <timestamp>`；诊断引导文案（4 语言+模板）；ARCHITECTURE/DIAGNOSTIC_REPORTING/LOCKSCREEN_AOD_BEHAVIOR 规范同步 | ✅ 已移植（2026-09-08；PAUSE_CONFIRM_MS=5s、通知几何死区、projection stale 保留此前已在 CN+ 基线，仅补齐 docs 与测试） |
| `cc1f62f`+`ced2769`+`0424ae9`（诊断部分） | 2026-08-13~22 | 三项诊断能力：① DiagnosticTraceFile——App 进程日志文件镜像（HyperOS 丢弃 App 侧 logcat，AppLog i/w/e 落盘、512KB 轮转）；② SystemUiLyricProjection 命名拒绝日志（accept() 5 处静默拒绝改为 rejected(reason) 去重记录）；③ AodDrawWakePulseResult——draw wake 锁脉冲四类结局分类 + 去重记录 | ✅ 已移植（2026-09-11；CN+ 增强：trace 按捕获起点过滤并折叠进报告 logs 段 `app_trace=`，root 拒绝路径也携带，APP_TRACE_BYTES=96KB 专属预算，上游仅落盘不进报告） |
| `cc1f62f`+`ced2769`（诊断部分·二） | 2026-08-13~15 | 两项：④ 文档拒绝命名原因——SpicyBridgeDocumentStore.accept/commit 返回 String?（14 处拒绝路径全部报字面：no-state/state-mismatch/payload-identity/oversized/malformed/commit-timing/commit-order），spicyBridgeDocumentTimingFault 报出分叉字段（duration/row/word/fillEnd），Service 侧 logDocumentRejection 去重记录；⑤ postHandoff 诊断——handoff active→inactive 且场景仍活跃时 +1s/+7s 两次表面全景快照（visibility/alpha 链/rect/scale/render/wake 一行打齐），detachCurrent 取消 | ✅ 已移植（2026-09-11；timingFault 保留 CN+ fillEndMs 放宽语义（可越本行尾、不得越歌长）；上游 spicyBridgeDocumentMismatch 引擎侧清文档路径 CN+ 不存在（生产者主动 clear），未移植该函数） |
| `8422d78` (v0.3.97) | 2026-09-01 | 中文歌出现日语假名注音 ruby 时拒绝显示（hasLanguageInconsistentKanaRuby/isKana、language 字段贯通、fillEndMs 越行尾合法化 + lineEndMs 渲染钳制） | ✅ 已移植（2026-09-05，按 CN+ 投影层结构改写；2026-09-11 复核 b0254d5..8422d78 增量并全部处置：① 标量辅助行只来自文档——评估后**不移植**，CN+ 保留无文档时兜底行的标量罗马音/翻译：触发面仅限 untimed/文档未达，假名守卫已覆盖文档主路径，标量路径无实测危害，移植为纯减法（砍掉 untimed 歌的 AI 翻译显示），分歧已写入 LOCKSCREEN_AOD_BEHAVIOR_SPEC.md；若未来出现错误标量注音反馈，改回只需 SpicyLyricProducer 两行；② spec 三段（辅助行来源/假名拒绝/fillEnd 钳制）补齐；③ timingFault 消息 window=→duration= 纯文案差异，跳过；④ versionCode 0.3.97 不适用） |
| `6216fdc` | 2026-08-08 | 版本锁定退役：XiaomiProfileState 增加 AVAILABLE、capability 计数展示（availableCapabilityCount/totalCapabilityCount）、移除 verifiedRuntimeProfile 版本 pin、summary 改为 available=n/total、DiagnosticSetupPolicy 可运行状态集 | ✅ 已移植（2026-09-05；保留 CN+ 实验模式本地覆写逻辑） |
| `c5b1ffa` | 2026-08-11 | DiagnosticContract 校验增加 "available" 状态 | ✅ 已移植（2026-09-05） |
| `f9dfa01` | 2026-08-09 | SystemUI uid 动态匹配 + HierarchyFields 字段链遍历 + 探针缺失日志 | ✅ 已同步（UpdateChecker 部分除外，CN+ 用自己的 VersionCheck.kt） |
| `8d89b10` | 2026-08-06 | AodStateProjector 引入等大重构 | ✅ 已同步 |
| `2608031` | 2026-08-05 | 移除凭据 miuix 仓库（改 Maven Central） | ✅ 已同步 |
| `bf988be` | 2026-08-03 | bump 0.3.50 | ➖ 版本号不适用（CN+ 独立版本号体系） |
| 更早提交 | ≤2026-08-03 | FAQ / 诊断政策 / 历史 | ➖ 未逐个核对（基线整体已含） |

## 已评估暂缓项（基线之内、按理由未移植的部分）

| 上游提交 | 日期 | 内容 | CN+ 相关性 | 建议 |
|---|---|---|---|---|
| `0424ae9`（剩余部分） | 2026-08-22 | ConfigBackupCodec（配置备份/恢复）、SettingsSession、LucideIcons、RTL 歌词渲染（AodTextDirection/物理对齐换算/drawDirectionalText）、hideFromRecents | 中 | 可选：配置备份用户价值高但牵扯 MainActivity 重构；RTL 对 CN 用户价值低 |
| `ced2769`（剩余部分） | 2026-08-13 | DiagnosticsScreen 简化、AodKeepaliveRegressionTest | 低 | 暂缓：UI 简化不适用（CN+ DiagnosticsScreen 结构不同）；postHandoff 诊断已移植 |
| `cc1f62f`（剩余部分） | 2026-08-15 | 文档传输宽限（scheduleDocumentClear/DOCUMENT_TRANSPORT_GRACE_MS）、spicyBridgeDocumentMismatch（引擎侧持留文档清除路径）、logKeepAliveEdge/logAodEnabledEdge | 中 | 暂缓：传输宽限结构性不适用（CN+ 文档由生产者主动 clear，无被动清除路径）；mismatch 调用路径 CN+ 不存在；建议出现对应 issue 再评估 logKeepAliveEdge |

## 同步时的操作流程

1. `git fetch upstream main`（remote `upstream` = https://github.com/amarinne/hyperglow ，已配置）
2. 对照上方“待同步”表，逐个 `git show <sha>` 审查改动
3. 按内容手工移植到 CN+（注意 CN+ 已深度分叉：Lyricon 生产器栈、版本号、CN 音乐应用适配）
4. 版本号不跟随上游（CN+ 独立体系）；UpdateChecker/VersionCheck 功能二选一
5. 移植后跑 CI（554+ 测试），全绿后推送
6. **更新本文件**：把已移植提交移入“已同步”表并更新基线日期
