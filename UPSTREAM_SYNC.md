# 上游同步状态（amarinne/hyperglow → CN+）

# English / 英文

> Purpose: whenever the user asks to "sync upstream updates", read this file first to learn the
> current sync baseline, then compare it against the list of new upstream commits to decide what
> needs to be ported. The two repositories have completely independent git histories (CN+ is a
> repackaged standalone fork), so a direct merge is impossible — changes can only be selected
> and ported manually by content.

## Current Status

- **CN+ version**: 0.3.91 (118), upstream baseline as of `8422d78` (v0.3.97). Evaluated increments since then: `748912e` (2026-09-14) and `2885511` (2026-09-15) — see the evaluation sections below.
- **Upstream latest**: 2026-09-15 `2885511`, version 0.3.177 (203) — **evaluated 2026-09-15** (DexKit symbol resolution + metadata multi-line; no mandatory port), see the `2885511` Evaluation section below.
- **Upstream repository**: https://github.com/amarinne/hyperglow (default branch: main)
- Baseline verification marks (2026-09-05): AodLyricBridgeService already includes dynamic uid matching,
  HierarchyFields.kt and its use across all hooks, missingProbeNames, and miuix via the public Maven Central repository

## `748912e` Evaluation (v0.3.173 · 2026-09-14)

One large feature commit (61 files, +7735/−1115) on top of the `8422d78` baseline.
Decomposed (granular, every changed file accounted for):

| # | Feature | Key files / size | CN+ relevance | Recommendation |
|---|---|---|---|---|
| 1 | **AOD canvas rotates with the device** — `AodOrientationMonitor` (new: accelerometer lifecycle / debounce / framework-rotation rebase + `AodOrientationMonitorTest`); projection/canvas carry `aodRotateWithDevice`/`aodRotationMode`/`aodRotationSettleMs`/`aodCanvasAnchor`/landscape anchor·text-scale·per-orientation padding; `aodLandscapeCanvasSize` logical landscape frame + rigid draw transform | AodOrientationMonitor, AodSurfaceController (~400), AodLyricCanvasView (orientation part), AodRenderPreferences, AodStateWire, LyricSnapshot, AodStateBridge | Medium — only landscape / tilted-AOD users | **Already present in CN+ (initial import, not ported this session)** — `AodOrientationMonitor` (+ `AodOrientationMonitorTest`) and `aodRotateWithDevice`/`aodRotationMode`/`aodCanvasAnchor`/`aodCanvasAnchorLandscape` are fully threaded through LyricSnapshot / AodStateProjector / AodRenderPreferences / AodStateWire. No port needed; re-align only if upstream's per-orientation anchor/text-scale defaults are to be matched exactly |
| 2 | **`suppressStockAodContent`** (hide Xiaomi's stock AOD while the full-screen canvas draws) — includes a new **enforcement seam**: framework `View.setVisibility` hook forces any non-GONE request on the suppressed container back to GONE (`StockVisibilityHooker` in AodSurfaceHook), plus AodPositionHook `suppressActive` pass-through + `holdStockPosition` (freeze the stock widget bundle during a lyric episode) + AodSurfaceController stock clock reserve | AodSurfaceHook (+43), AodPositionHook (+64), AodSurfaceController, AodStateBridge, wire, canvas | High (core to rendering a replacement AOD) | **Already present in CN+ (initial import)** — the call-boundary GONE seam exists (`AodSurfaceHook.StockVisibilityHooker` + `module.hook(setVisibility).intercept(...)`), `suppressStockAodContent` is threaded (Snapshot/Projector/Prefs/Wire), and AodPositionHook `suppressActive` pass-through + stock-bundle freeze/hold are in place (~L339). No port needed; reconcile hold-mode details only as desired |
| 3 | **`HookRegistry` (new) + hot-reload refit** — every hook now installs via `HookRegistry.hook(module, FEATURE_ID, …)` instead of inline `deoptimize`/`hook`; `onHotReloading`/`onHotReloaded`, generation retire, `PROTECTIVE` mode, refuse reload while a lyric session is active, install split into `installDefaultLoaderHooks`/`installAodHooks`; `SystemUiLifecycleHook.bootstrap()` re-runs for a re-derived host | HookRegistry (+80), HookRegistryTest, HookEntry (+251), ~12 hook files (each +FEATURE_ID/HookRegistry.hook), SystemUiLifecycleHook (+22) | Low–Medium (dev-loop ergonomics, not user-facing) | **Shipped (2026-09-16)**: `HookRegistry`(+`hookIdFor`) centralizes install/retire; `onHotReloading` rejects while a lyric session is active + retires generation (`AodLifetimeController.cancelPendingForReload`/`AodBrightnessController.cancelPendingForReload`/`AodOrientationMonitor.stop`); `onHotReloaded` unhooks previous handles + reinstalls from the re-derived host app and re-runs `SystemUiLifecycleHook.bootstrap`; install split into `installDefaultLoaderHooks`/`installAodHooks`; all SystemUI/AOD hooks install via `HookRegistry.hook` with per-module FEATURE_ID; `HookRegistryTest` covers id stability. AntiFreezeHook (system_server) intentionally stays direct. CI green |
| 4 | **Config backup/restore** (`ConfigBackupCodec` +200, `SettingsSession` +91) + Settings UI tab rework (`MainActivity` +1559) | ConfigBackupCodec, SettingsSession, MainActivity, PreferenceSettingsStore + ConfigBackupCodecTest / SettingsSessionTest | High user value | **Codec shipped (2026-09-16, in 0.3.88; adapted to CN+ field set; replaces MainActivity's type-guessing export/import); `SettingsSession`/`PreferenceSettingsStore` deferred** — the session is coupled to the unported MainActivity tab restructure (skipped: CN+ has its own Settings layout) |
| 5 | **Duet / concurrent second sung line** — `secondLine` (`AodStateWireSecondLine`/`AodDisplaySecondLine`/`LyricSecondLine`) threaded through wire→bridge→projector→canvas; sourced from `document.concurrentRowsAt(position, presentedRow)`; kana-japanese-rejection also applied to the concurrent line; new `duetEnabled` pref + `activeRowHasConcurrentRow`-style canvas rendering | AodStateProjector, AodStateBridge (+239), AodStateWire v9, LyricSnapshot, AodRenderPreferences, AodProjectionEngine, AodLyricCanvasView (duet part) | High value IF CN+ songs need duet/layered lines | **Defer / not directly portable**: CN+'s document/row model has **no concurrent rows / no `role` field**, so this needs document-model support first, then wire v9 + canvas. Re-evaluate if CN's lyric provider can emit overlapping concurrent lines |
| 6 | **`hasActualLyricTiming` interlude fix** — instrumental-dot rows no longer count as "actual timing", so an interlude-only document can't hold the AOD scene/keepalive | AodProjectionEngine (+11, `it.role != ROW_ROLE_INTERLUDE`) | Medium (scene liveness edge case) | **Equivalent already present** — `hasActualLyricTiming` (`AodProjectionEngine.kt:495` / `SpicyLyricProducer.kt:258`) already keeps instrumental/interlude dots out of "actual timing", via CN+'s own row judgement rather than upstream `ROW_ROLE_INTERLUDE`. Same semantics; no port needed |
| 7 | **CustomizationRepository compiled-cache + invalidation** — cache the compiled scene in memory; only re-decode/compile when the persisted inputs (current/previous doc, legacy config) change, cutting CPU/allocation at the 10 Hz tick; replaced the migration-version flag with always-canonicalize | CustomizationRepository (+129) | Medium (perf) | **Ported (2026-09-16)**: compiled-cache/open-path invalidation implemented in `CustomizationRepository.loadCompiled`; uncommitted, CI pending |
| 8 | **AOD max-height default 0.42→0.9** — `SurfacePolicyResolver` AOD `maximumHeightFraction 0.5→0.9`, `canonicalizeDocument` v2 migration (legacy AOD 0.42 → new default), `fromLegacyConfigFile` AOD maps to the new default | SurfacePolicyResolver, CustomizationRepository, SceneCompiler | Medium | **Code written (2026-09-16), compile/CI pending** — `SurfacePolicyResolver` AOD hard ceiling raised `0.5→0.9`. **`canonicalizeDocument`/`fromLegacyConfigFile` migration not applicable to CN+**: CN+'s `SceneCompiler.compileProfile` forces the AOD interior `maxHeightFraction` to `AOD_FIXED_MAX_HEIGHT_FRACTION=0.3` (content-fit) regardless of the nominal value, so the nominal 0.42 is dead for AOD and migrating it to 0.9 has no runtime effect. Interior height stays content-fit by design; only the runtime hard ceiling loosened |
| 9 | **Per-scene AOD brightness override** — `AodBrightnessController.setBrightnessOverride(enabled, level)` driven from `onCustomization` (new `aodBrightnessOverride`/`aodBrightnessLevel` fields) | AodPowerCoordinator (+10), AodBrightnessHook, CustomizationModels | Medium | **Code written (2026-09-16), compile/CI pending** — `AodBrightnessController.setBrightnessOverride(enabled, level)` + `resolveAodBrightnessRequest` two-mode logic (auto per-scene clamp-to-readable vs fixed custom level, clamped 10–255); `AodSurfaceController.onCustomization` drives it; Settings UI adds a master **AOD brightness boost** switch, then a **custom-brightness** toggle + 10–255 slider (auto scene clamp remains the default when the custom toggle is off); config fields `aodBrightnessOverride`/`aodBrightnessLevel` threaded through `AodRenderPreferences`/`CustomizationModels`/`DiagnosticLogging`; 3 new policy tests + zh/en strings |
| 10 | **New per-scene customization fields** — `duetEnabled`, `cardColor`, `cardAlpha` (+ brightness override of #9, + rotate/suppress of #1/#2) | CustomizationModels (+67), CustomizationRepository, SceneCompiler | Varies | **`cardColor`/`cardAlpha` already present (initial import)** — fully wired (CustomizationModels/SceneCompiler/SystemUiCustomization/LockscreenSurfaceController/MainActivity UI). `duetEnabled` only meaningful with #5 (absent in CN+) |
| 11 | **Wire body protocol v1→v9** — backward-compatible encode/decode across BODY_VERSION 1–9 (suppress/rotate→anchor→settle→landscape→mode→per-axis→per-orientation padding→secondLine), with per-version default derivation and normalization | AodStateWire (+342), AodStateWireCodecTest | High surface area | **Defer as its own item**: needed only to carry features 1/2/5/9; CN+ has its own wire codec — reconcile versions if/when adopting those features |
| 12 | **Misc hardening sweep** — remaining hooks (AodBrightness +58, AodLifetime +19, AodDisplayState +5, AodWakeBroker +7, AodSurfaceController +400 total, LockscreenSurfaceController +51, LinkageTransitionCoordinator +42, RaiseToAod +14, LinkageTransitionHook +8, SystemUiClockMorphHook +8, LockscreenSurfaceHook +11, LockscreenEditorGestureHook +11, LyricCanvasMapper +40, string/template updates, many test updates) | many | Varies per file | **Selective**: nearly all are either the HookRegistry refit (#3) or suppress/orientation wiring (#1/#2); they carry no independent user value on their own |

**Bottom line**: shipped in the 748912e batch (2026-09-16, pre-release 0.3.88) — **#8 (AOD max-height)**, **#9 (brightness override)**, **#4 (ConfigBackupCodec only; SettingsSession deferred)**, **#7 (customization compiled-cache)**, and **#3 (HookRegistry + hot-reload refit)**. Code-verified to **already exist in CN+ from the initial import** (no port needed): **#1 (orientation)**, **#2 (suppressStockAodContent)**, **#6 (interlude == equivalent)**, **#10 (cardColor/cardAlpha)**. Only **#5 (duet)** is not directly portable (CN+ document model has no concurrent rows / `role` field); **#11 (wire v9) is deferred** (wiring rework to carry features 1/2/5/9), and **#12** is largely the refit/wiring carried by #1/#2/#3. After any port, re-run the API-contract fingerprint and full CI (554+ tests), then update this file.

## Local enhancement (2026-09-16): AOD clock pin behavior + vertical offset

Not from upstream — a CN+ user-requested change on top of the anchored-clock feature:
- **Don't pin the anchored system-clock position while playback is paused or there is no playback** — `AodSurfaceController.applySuppressionAndRotation` now computes `pinClock = renderable && playbackActive && !currentAodProfile().aodClockFollow`, so the clock only gets pinned during active playback in anchor mode; on pause / no playback it returns to its system position and follows burn-in movement normally.
- **Vertical offset slider when "Follow system clock in real time" is off** — new `aodClockYOffset` pref (px, −480..480, negative up / positive down) threaded through `AodRenderPreferences` / `CompiledCustomization` (`CustomizationModels`) / `RuntimeCustomization` (`DiagnosticLogging`) / `ConfigBackupCodec`; `AodPositionHook.setClockYOffset(px)` adds the offset to the pinned clock Y (via `lastStockTranslationY` anchor, no per-frame accumulation); shown only when the follow toggle is off.

## `2885511` Evaluation (v0.3.177 · 2026-09-15)

One large commit (27 files, +1392/−195) on top of the `748912e` baseline. Decomposed:

| # | Feature | Key files / size | CN+ relevance | Recommendation |
|---|---|---|---|---|
| 1 | **DexKit dynamic symbol resolution** — new `root/symbols` package: `DexKitRuntime` (+195, `org.luckypray:dexkit:2.2.0` native lib, one bridge per classpath APK, bounded extraction fallback), `SymbolCache` (weak per-loader cache), `SymbolResolver` (+602, central symbol gate: bundled reflection first, DexKit on miss; policy via `debug.hyperglow.symbols`; provenance ledger). ~13 hooks + `XiaomiCapabilityResolver` probes refit to route through it; `frameworkOwned()` keeps boot-classpath reflection-only | DexKitRuntime/SymbolCache/SymbolResolver + ~12 hook files refit; Gradle dep | High — resilience to Xiaomi symbol renames across ROM builds | **Defer (large, risk-heavy)**. Self-contained but refits every install path and adds a native library (ABI packaging / proguard / SystemUI native loading). CN+ hooks work statically today; adopt only if a concrete renamed-symbol hook failure appears. If adopted, reconcile CN+'s `hierarchyField`/probe plumbing and keep the framework fast-path |
| 2 | **Multi-line metadata** — `AodStateProjector.projectToDisplay` joins title/artist with `\n` (+ `·`→newline) instead of `" · "`; `metadataLineTexts`/`metadataLayoutBounds` extra-line-height in canvas; metadata rows drawn per line | AodStateProjector +3, AodLyricCanvasView ~40, AodStateProjectorTest/AodCanvasLayoutTest | Medium-High, user-visible | **Port candidate (low risk, self-contained)** — CN+ has the same `" · "` join at `AodStateProjector.kt:82` and metadata RowKind; worth putting title/artist on separate lines |
| 3 | **Canonical punctuation attachment** (`aodPunctuationAttachToPrevious`/`Next`, `attachAodPunctuationGroups`) + chunk packing includes rendered separators | AodLyricCanvasView ~50, AodCanvasLayoutTest | Medium (wrap polish) | **Selective** — low risk; port if standalone CJK/Latin punctuation wrapping looks poor |
| 4 | **Shared logical clip for all lyric draw paths** (enforces horizontal padding even on indivisible-word/animation overhang; replaces per-`drawText` clip) | AodLyricCanvasView ~10 | Medium | **Port candidate (low risk)** — CN+ should keep everything inside the padded frame |
| 5 | **Duet-section end → recenter remaining solo** (`shouldRecenterAfterDuet`/`duetEnded`/`wasDuet`) | AodLyricCanvasView ~25 | None today | **Not applicable** — CN+ has no duet section feature (`no DuetSectionId`/duet handling in CN+ canvas). Skip |
| 6 | Version bump 0.3.173 → 0.3.177 | build.gradle.kts | n/a | ➖ Not applicable (CN+ independent numbering) |
| 7 | Docs: ARCHITECTURE hook-symbol-resolution, LOCKSCREEN_AOD_BEHAVIOR_SPEC canvas padding + symbol resolution | docs | Low | **Optional** — sync only if #1/#4 are ported |

Also: `strings.xml` got only a comment block (no user-facing text change).

**Bottom line**: no mandatory port. Lowest-risk, high-value selective ports are **#2 (multi-line metadata)** and **#4 (logical clip)**; **#3** is optional wrap polish; **#1 (DexKit)** is the marquee robustness feature but a large entry-point refactor — keep on watchlist and adopt only if a real renamed-symbol failure shows up; **#5 not applicable** (no duet in CN+). After any port, re-run the API-contract fingerprint and full CI, then update this file.

## Synced / Included

| Upstream commit | Date | Content | Status |
|---|---|---|---|
| `748912e` items #8+#9 | 2026-09-16 | Per-scene AOD brightness override (`AodBrightnessController.setBrightnessOverride` + two-mode UI: master boost switch → auto scene clamp vs custom 10–255 slider) and AOD max-height ceiling `0.5→0.9` (SurfacePolicyResolver only; CN+ AOD interior stays content-fit) | ✅ Shipped (2026-09-16, in pre-release 0.3.88 / `115-0.3.88`; CI green) |
| `748912e` items #4(codec)+#7 | 2026-09-16 | Type-safe config backup/restore codec (`ConfigBackupCodec` adapted to CN+'s field set via `AodRenderConfig.DEFAULTS`; replaces MainActivity's type-guessing `exportAllConfig`/`importAllConfig`) + `CustomizationRepository` compiled-cache/invalidation | ✅ Shipped (2026-09-16, in pre-release 0.3.88 / `115-0.3.88`; CI green). `SettingsSession`/`PreferenceSettingsStore` **deferred** (coupled to the unported tab UI restructure) |
| `748912e` item #3 | 2026-09-16 | `HookRegistry` central registry + hot-reload refit: every hook installs via `HookRegistry.hook(module, FEATURE_ID, …)` with `PROTECTIVE` mode; `onHotReloading` (reject while a lyric session is active + `retireGeneration`) / `onHotReloaded` (unhook previous handles + reinstall from re-derived host app + `SystemUiLifecycleHook.bootstrap`); install split into `installDefaultLoaderHooks`/`installAodHooks`; reload-cleanup `cancelPendingForReload`/`stop` on AOD controllers/monitor; `HookRegistryTest` | ✅ Shipped (2026-09-16; code + test written). AntiFreezeHook (system_server) stays direct. CI green |
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
| `0424ae9` (remaining part) | 2026-08-22 | ConfigBackupCodec (config backup/restore), SettingsSession, LucideIcons, RTL lyrics rendering (AodTextDirection / physical alignment conversion / drawDirectionalText), hideFromRecents | Medium | Optional: **config-backup codec ported (2026-09-16, as part of `748912e` #4)**; `SettingsSession` still implies a MainActivity refactor and is deferred with the tab rework; RTL is of low value for CN users |
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

- **CN+ 版本**：0.3.91 (118)，上游基线截至 `8422d78`（v0.3.97）。此后的评估增量：`748912e`（2026-09-14）与 `2885511`（2026-09-15）——见下方对应评估小节。
- **上游最新**：2026-09-15 `2885511`，版本 0.3.177 (203) —— **已于 2026-09-15 评估**（DexKit 符号解析 + 元数据多行化；无必须移植项），见下方「`2885511` 评估」小节。
- **上游仓库**：https://github.com/amarinne/hyperglow（default branch: main）
- 基线核实标记（2026-09-05）：AodLyricBridgeService 已含 uid 动态匹配、
  HierarchyFields.kt 及全 hook 使用、missingProbeNames、miuix 走 Maven Central 公共仓库

## `748912e` 评估（v0.3.173 · 2026-09-14）

`8422d78` 基线之上的单个大特性提交（61 文件，+7735/−1115）。已逐文件拆分为完整的独立评估项（共 12 项）：

| # | 特性 | 关键文件/体量 | CN+ 相关性 | 建议 |
|---|---|---|---|---|
| 1 | **AOD 画布随设备旋转**——`AodOrientationMonitor`（新文件：加速度计生命周期/防抖/框架旋转 rebase + `AodOrientationMonitorTest`）；投影/画布携带 `aodRotateWithDevice`/`aodRotationMode`/`aodRotationSettleMs`/`aodCanvasAnchor`/横屏锚点·字号·分朝向 padding；`aodLandscapeCanvasSize` 逻辑横屏框 + 刚性绘制变换 | AodOrientationMonitor、AodSurfaceController（约 400）、AodLyricCanvasView（旋转部分）、AodRenderPreferences、AodStateWire、LyricSnapshot、AodStateBridge | 中——仅横屏/侧放 AOD 用户 | **CN+ 初始导入已含（非本次移植）**——`AodOrientationMonitor`（+ `AodOrientationMonitorTest`）及 `aodRotateWithDevice`/`aodRotationMode`/`aodCanvasAnchor`/`aodCanvasAnchorLandscape` 已完整贯通 LyricSnapshot/AodStateProjector/AodRenderPreferences/AodStateWire。无需移植；仅当需精确对齐上游分朝向锚点/字号默认值时再对齐 |
| 2 | **`suppressStockAodContent`**（自绘全屏画布时隐藏小米系统 AOD 内容）——含新增**强制接缝**：框架 `View.setVisibility` hook 把受抑制容器上任何非 GONE 请求强制改回 GONE（AodSurfaceHook 的 `StockVisibilityHooker`），并有 AodPositionHook `suppressActive` 直通 + `holdStockPosition`（歌词时段冻结系统组件束）+ AodSurfaceController 系统时钟保留区 | AodSurfaceHook（+43）、AodPositionHook（+64）、AodSurfaceController、AodStateBridge、wire、画布 | 高（替身 AOD 渲染的核心） | **CN+ 初始导入已含**——调用边界 GONE 接缝存在（`AodSurfaceHook.StockVisibilityHooker` + `module.hook(setVisibility).intercept(...)`），`suppressStockAodContent` 已贯通（Snapshot/Projector/Prefs/Wire），AodPositionHook `suppressActive` 直通 + 系统组件束冻结/hold 也在位（约 L339）。无需移植；仅按需对齐 hold 模式细节 |
| 3 | **`HookRegistry`（新文件）+ 热重载改造**——所有 hook 改为经 `HookRegistry.hook(module, FEATURE_ID, …)` 安装（替代内联 `deoptimize`/`hook`）；`onHotReloading`/`onHotReloaded`、generation 退役、`PROTECTIVE` 模式、lyric 会话活跃时拒绝重载、hook 拆为 `installDefaultLoaderHooks`/`installAodHooks`；`SystemUiLifecycleHook.bootstrap()` 可对重派生宿主重跑 | HookRegistry（+80）、HookRegistryTest、HookEntry（+251）、约 12 个 hook 文件（各 +FEATURE_ID/HookRegistry.hook）、SystemUiLifecycleHook（+22） | 低-中（开发迭代便利，非用户功能） | **已发布（2026-09-16）**：`HookRegistry`（+`hookIdFor`）统一安装/退役；`onHotReloading` 在 lyric 会话活跃时拒绝 + 退役本代 hook（`AodLifetimeController.cancelPendingForReload`/`AodBrightnessController.cancelPendingForReload`/`AodOrientationMonitor.stop`）；`onHotReloaded` unhook 旧句柄 + 从重派生宿主应用重装并重跑 `SystemUiLifecycleHook.bootstrap`；安装拆为 `installDefaultLoaderHooks`/`installAodHooks`；全部 SystemUI/AOD hook 经 `HookRegistry.hook` 按模块 FEATURE_ID 注册；`HookRegistryTest` 覆盖 ID 稳定性。AntiFreezeHook（system_server）按设计保持直装。CI 跑绿 |
| 4 | **配置备份/恢复**（`ConfigBackupCodec` +200、`SettingsSession` +91）+ 设置页 tab 化重构（`MainActivity` +1559） | ConfigBackupCodec、SettingsSession、MainActivity、PreferenceSettingsStore + ConfigBackupCodecTest / SettingsSessionTest | 用户价值高 | **Codec 已发布（2026-09-16，随预发行 0.3.88，适配 CN+ 字段集，替换 MainActivity 猜测类型的 export/import）；`SettingsSession`/`PreferenceSettingsStore` 暂缓**——session 耦合未移植的 MainActivity tab 化重构（已跳过：CN+ 有自有设置布局） |
| 5 | **对唱/并发第二歌行**——`secondLine`（`AodStateWireSecondLine`/`AodDisplaySecondLine`/`LyricSecondLine`）贯通 wire→bridge→投影→画布；来源为 `document.concurrentRowsAt(position, presentedRow)`；对并发行同样做假名中文冲突拒绝；新增 `duetEnabled` 偏好 + 画布并发行渲染 | AodStateProjector、AodStateBridge（+239）、AodStateWire v9、LyricSnapshot、AodRenderPreferences、AodProjectionEngine、AodLyricCanvasView（对唱部分） | 若 CN+ 歌曲需对唱/叠唱则价值高 | **暂缓/不可直接移植**：CN+ 文档/行模型**无并发行、无 `role` 字段**，需先补文档模型，再做 wire v9 + 画布。若 CN+ 歌词源能提供重叠并发行再评估 |
| 6 | **`hasActualLyricTiming` interlude 修复**——乐器点(u0022点)行不再计入"实际计时"，避免纯过门文档把 AOD 场景/keepalive 拉住 | AodProjectionEngine（+11，`it.role != ROW_ROLE_INTERLUDE`） | 中（场景存活边缘场景） | **等效已实现**——`hasActualLyricTiming`（`AodProjectionEngine.kt:495` / `SpicyLyricProducer.kt:258`）已通过 CN+ 自身的行判定把乐器/过门点排除在“实际计时”之外，而非上游 `ROW_ROLE_INTERLUDE`。语义相同；无需移植 |
| 7 | **CustomizationRepository 编译缓存 + 失效**——内存缓存编译结果，仅当持久输入（当前/上一文档、legacy 配置）变化才重新解码/编译，降低 10 Hz tick 的 CPU/分配；用"始终规范化"取代版本号迁移标志 | CustomizationRepository（+129） | 中（性能） | **已发布（2026-09-16，随预发行 0.3.88）**：`CustomizationRepository.loadCompiled` 已实现编译缓存/换路径失效；CI 跑绿 |
| 8 | **AOD 最大高度默认 0.42→0.9**——`SurfacePolicyResolver` AOD `maximumHeightFraction 0.5→0.9`、`canonicalizeDocument` v2 迁移（legacy AOD 0.42→新默认）、`fromLegacyConfigFile` AOD 映射到新默认 | SurfacePolicyResolver、CustomizationRepository、SceneCompiler | 中 | **已发布（2026-09-16，随预发行 0.3.88）**——`SurfacePolicyResolver` AOD 硬上限 `0.5→0.9` 已放开。**`canonicalizeDocument`/`fromLegacyConfigFile` 迁移对 CN+ 不适用**：CN+ 的 `SceneCompiler.compileProfile` 在编译时把 AOD 内部 `maxHeightFraction` 强制为 `AOD_FIXED_MAX_HEIGHT_FRACTION=0.3`（内容贴合），与名义值无关，因此名义上的 0.42 对 AOD 是死值，迁移成 0.9 无运行时效果。内部高度按设计保持内容贴合，仅放开运行时硬上限 |
| 9 | **按场景 AOD 亮度覆写**——`AodBrightnessController.setBrightnessOverride(enabled, level)` 由 `onCustomization` 驱动（新增 `aodBrightnessOverride`/`aodBrightnessLevel` 字段） | AodPowerCoordinator（+10）、AodBrightnessHook、CustomizationModels | 中 | **已发布（2026-09-16，随预发行 0.3.88）**——`AodBrightnessController.setBrightnessOverride(enabled, level)` + `resolveAodBrightnessRequest` 两模式逻辑（按场景自动化钳制到可读亮度 vs 固定自定义档位，钳制 10–255）；`AodSurfaceController.onCustomization` 驱动；设置页新增总开关 **AOD 亮度增强**，其下再分**自定义亮度**开关 + 10–255 滑块（总开关开启、自定义关闭时即按场景自动化）；配置字段 `aodBrightnessOverride`/`aodBrightnessLevel` 贯通 `AodRenderPreferences`/`CustomizationModels`/`DiagnosticLogging`；新增 3 条策略测试与中英文案 |
| 10 | **新按场景定制字段**——`duetEnabled`、`cardColor`、`cardAlpha`（另含 #9 亮度覆写、#1/#2 旋转/抑制） | CustomizationModels（+67）、CustomizationRepository、SceneCompiler | 因项而异 | **`cardColor`/`cardAlpha` 初始导入已含**——已完整接线（CustomizationModels/SceneCompiler/SystemUiCustomization/LockscreenSurfaceController/MainActivity UI）。`duetEnabled` 仅 #5 存在时才有意义（CN+ 无 #5） |
| 11 | **wire 主体协议 v1→v9**——跨 BODY_VERSION 1–9 向后兼容编解码（抑制/旋转→锚点→沉淀→横屏→模式→分轴→分朝向 padding→secondLine），含按版本默认推导与规范化 | AodStateWire（+342）、AodStateWireCodecTest | 覆盖面大 | **单列暂缓**：仅为承载特性 1/2/5/9；CN+ 有自己的 wire 编解码——采用这些特性时再对齐版本 |
| 12 | **杂项加固扫尾**——其余 hook（AodBrightness +58、AodLifetime +19、AodDisplayState +5、AodWakeBroker +7、AodSurfaceController 合计 +400、LockscreenSurfaceController +51、LinkageTransitionCoordinator +42、RaiseToAod +14、LinkageTransitionHook +8、SystemUiClockMorphHook +8、LockscreenSurfaceHook +11、LockscreenEditorGestureHook +11、LyricCanvasMapper +40、文案/模板更新、大量测试更新） | 多文件 | 因文件而异 | **选择性**：几乎全部是 HookRegistry 改造（#3）或抑制/旋转接线（#1/#2），单独看无独立用户价值 |

**结论**：748912e 批次内已发布（2026-09-16，随预发行 0.3.88）——**#8（AOD 最大高度）、#9（亮度覆写）、#4（仅 ConfigBackupCodec，SettingsSession 暂缓）、#7（定制编译缓存）、#3（HookRegistry + 热重载改造）**。代码实测确认 **CN+ 初始导入已含（无需移植）**：**#1（旋转）、#2（suppressStockAodContent）、#6（interlude 等效）、#10（cardColor/cardAlpha）**。仅 **#5（对唱）** 不可直接移植（CN+ 文档模型无并发行/`role` 字段）；**#11（wire v9）暂缓**（为承载特性 1/2/5/9 的接线大改），**#12** 基本为 #1/#2/#3 的改造接线附随。任何移植后都需重跑插件契约指纹与全量 CI（554+ 测试），并更新本文件。

## 本地增强（2026-09-16）：AOD 时钟钉住行为 + 垂直偏移

非上游提交——CN+ 用户需求，叠加在“锚定时钟”功能上：
- **暂停/没有播放时不再钉住锚定的系统时钟位置**——`AodSurfaceController.applySuppressionAndRotation` 现在计算 `pinClock = renderable && playbackActive && !currentAodProfile().aodClockFollow`，仅在锚定模式下且播放活跃时钉住时钟；暂停/无播放时时钟回到系统位置、随防烧屏正常移动。
- **关闭「实时跟随系统时钟」时显示时钟垂直偏移滑块**——新增 `aodClockYOffset` 偏好（px，−480..480，负值上移/正值下移），贯通 `AodRenderPreferences` / `CompiledCustomization`（`CustomizationModels`）/ `RuntimeCustomization`（`DiagnosticLogging`）/ `ConfigBackupCodec`；`AodPositionHook.setClockYOffset(px)` 把偏移叠加到被钉住的时钟 Y（经 `lastStockTranslationY` 锚点，避免逐帧累积）；仅当跟随开关关闭时显示。

## `2885511` 评估（v0.3.177 · 2026-09-15）

`748912e` 基线之上的单个大提交（27 文件，+1392/−195）。拆分为独立评估项：

| # | 特性 | 关键文件/体量 | CN+ 相关性 | 建议 |
|---|---|---|---|---|
| 1 | **DexKit 动态符号解析**——新增 `root/symbols` 包：`DexKitRuntime`（+195，引入 `org.luckypray:dexkit:2.2.0` 原生库、按 classpath APK 建桥、受限抽取兜底）、`SymbolCache`（按 loader 弱缓存）、`SymbolResolver`（+602，符号总闸：先用内置反射、miss 才查 DexKit；`debug.hyperglow.symbols` 系统属性切策略；来源台账）；约 13 个 hook + `XiaomiCapabilityResolver` 探针改走此闸；`frameworkOwned()` 保证引导类路径仍纯反射 | DexKitRuntime/SymbolCache/SymbolResolver + 约 12 个 hook 文件改造；Gradle 依赖 | 高——应对不同 ROM 版本的小米符号改名 | **暂缓（体量大、风险高）**。自包含但改写全部安装路径并引入原生库（ABI 打包/proguard/SystemUI 原生加载）。CN+ 目前静态安装即工作；仅当出现确切的改名符号 hook 失效再移植。若移植需对齐 CN+ 的 `hierarchyField`/探针管线并保留框架快速路径 |
| 2 | **元数据多行化**——`AodStateProjector.projectToDisplay` 将歌名/歌手用 `\n` 拼接（`·`→换行）替代 `" · "`；画布 `metadataLineTexts`/`metadataLayoutBounds` 增加行高、元数据逐行绘制 | AodStateProjector +3，AodLyricCanvasView ~40，AodStateProjectorTest/AodCanvasLayoutTest | 中-高、用户可见 | **可选移植（低风险、自包含）**——CN+ 在 `AodStateProjector.kt:82` 有相同 `" · "` 拼接、已有元数据 RowKind；值得让歌名/歌手分行 |
| 3 | **书写体系标点归附**（`aodPunctuationAttachToPrevious`/`Next`、`attachAodPunctuationGroups`）+ 词块打包计入渲染分隔符 | AodLyricCanvasView ~50，AodCanvasLayoutTest | 中（换行润色） | **选择性**——低风险；若独立中日文字标点换行观感不佳再移植 |
| 4 | **所有歌词绘制路径共享逻辑裁剪**（强制水平 padding，即使整词不可分/动画越界也裁；取代逐 `drawText` clip） | AodLyricCanvasView ~10 | 中 | **可选移植（低风险）**——CN+ 应保证内容不越出四周 padding 框 |
| 5 | **对唱（duet）区块结束 → 剩余独唱回落居中**（`shouldRecenterAfterDuet`/`duetEnded`/`wasDuet`） | AodLyricCanvasView ~25 | 当前无 | **不适用**——CN+ 画布无对唱区块功能（无 `DuetSectionId`/对唱处理）。跳过 |
| 6 | 版本号 0.3.173 → 0.3.177 | build.gradle.kts | n/a | ➖ 不适用（CN+ 独立版本号体系） |
| 7 | 文档：ARCHITECTURE hook 符号解析、LOCKSCREEN_AOD_BEHAVIOR_SPEC 画布 padding + 符号解析 | docs | 低 | **可选**——仅当移植 #1/#4 时同步 |

另注：`strings.xml` 只加了一段注释（无用户可见文案变更）。

**结论**：无必须移植项。最低风险、高价值的可选移植是 **#2（元数据多行）+ #4（逻辑裁剪）**；**#3** 为可选换行润色；**#1（DexKit）** 是招牌健壮性特性但属大型入口重构——列入观察，仅当真实出现改名符号失效时再移植；**#5 不适用**（CN+ 无对唱）。任何移植后都需重跑插件契约指纹与全量 CI，并更新本文件。

## 已同步 / 已包含

| 上游提交 | 日期 | 内容 | 状态 |
|---|---|---|---|
| `748912e` 项 #8+#9 | 2026-09-16 | 按场景 AOD 亮度覆写（`AodBrightnessController.setBrightnessOverride` + 两模式设置 UI：总开关开启后 → 按场景自动化 vs 自定义 10–255 滑块）与 AOD 最大高度硬上限 `0.5→0.9`（仅 SurfacePolicyResolver；CN+ AOD 内部保持内容贴合） | ✅ 已发布（2026-09-16，随预发行 0.3.88 / `115-0.3.88`；CI 跑绿） |
| `748912e` 项 #4(codec)+#7 | 2026-09-16 | 类型安全配置备份/恢复编解码（`ConfigBackupCodec` 经 `AodRenderConfig.DEFAULTS` 适配 CN+ 字段集，替换 MainActivity 中猜测类型的 `exportAllConfig`/`importAllConfig`）+ `CustomizationRepository` 编译缓存/失效 | ✅ 已发布（2026-09-16，随预发行 0.3.88 / `115-0.3.88`；CI 跑绿）。`SettingsSession`/`PreferenceSettingsStore` **暂缓**（耦合未移植的 tab 化 UI 重构） |
| `748912e` 项 #3 | 2026-09-16 | `HookRegistry` 中心注册表 + 热重载改造：所有 hook 经 `HookRegistry.hook(module, FEATURE_ID, …)` 安装且带 `PROTECTIVE` 模式；`onHotReloading`（lyric 会话活跃时拒绝 + `retireGeneration`）/`onHotReloaded`（unhook 旧句柄 + 从重派生宿主应用重装 + `SystemUiLifecycleHook.bootstrap`）；安装拆为 `installDefaultLoaderHooks`/`installAodHooks`；AOD 控制器/监测器重载清理 `cancelPendingForReload`/`stop`；`HookRegistryTest` | ✅ 已发布（2026-09-16；代码+测试已写）。AntiFreezeHook（system_server）按设计保持直装。CI 跑绿 |
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
| `0424ae9`（剩余部分） | 2026-08-22 | ConfigBackupCodec（配置备份/恢复）、SettingsSession、LucideIcons、RTL 歌词渲染（AodTextDirection/物理对齐换算/drawDirectionalText）、hideFromRecents | 中 | 可选：**配置备份 codec 已移植（2026-09-16，随 `748912e` 项 #4）**；`SettingsSession` 仍牵扯 MainActivity 重构，随 tab 重构一并暂缓；RTL 对 CN 用户价值低 |
| `ced2769`（剩余部分） | 2026-08-13 | DiagnosticsScreen 简化、AodKeepaliveRegressionTest | 低 | 暂缓：UI 简化不适用（CN+ DiagnosticsScreen 结构不同）；postHandoff 诊断已移植 |
| `cc1f62f`（剩余部分） | 2026-08-15 | 文档传输宽限（scheduleDocumentClear/DOCUMENT_TRANSPORT_GRACE_MS）、spicyBridgeDocumentMismatch（引擎侧持留文档清除路径）、logKeepAliveEdge/logAodEnabledEdge | 中 | 暂缓：传输宽限结构性不适用（CN+ 文档由生产者主动 clear，无被动清除路径）；mismatch 调用路径 CN+ 不存在；建议出现对应 issue 再评估 logKeepAliveEdge |

## 同步时的操作流程

1. `git fetch upstream main`（remote `upstream` = https://github.com/amarinne/hyperglow ，已配置）
2. 对照上方“待同步”表，逐个 `git show <sha>` 审查改动
3. 按内容手工移植到 CN+（注意 CN+ 已深度分叉：Lyricon 生产器栈、版本号、CN 音乐应用适配）
4. 版本号不跟随上游（CN+ 独立体系）；UpdateChecker/VersionCheck 功能二选一
5. 移植后跑 CI（554+ 测试），全绿后推送
6. **更新本文件**：把已移植提交移入“已同步”表并更新基线日期
