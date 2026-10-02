# 上游同步状态（amarinne/hyperglow → CN+）

# English / 英文

> Purpose: whenever the user asks to "sync upstream updates", read this file first to learn the
> current sync baseline, then compare it against the list of new upstream commits to decide what
> needs to be ported. The two repositories have completely independent git histories (CN+ is a
> repackaged standalone fork), so a direct merge is impossible — changes can only be selected
> and ported manually by content.

## Current Status

- **CN+ version**: 0.3.125 (152) as of 2026-09-28, upstream baseline as of `1537c58` (v0.3.178). Evaluated increments since `8422d78` (v0.3.97): `748912e` (2026-09-11), `2885511` (2026-09-14), `1537c58` (2026-09-17) — all handled; every evaluated item is accounted for in the tables below.
- **Upstream latest**: 2026-09-28 `99ba119`, version 0.3.191 (216) — **detected 2026-10-02, not yet ported**; a single squashed `update` commit (~1457 insertions / 47 files, `1537c58..99ba119`) whose items are all registered in the "Pending Evaluation" table below.
- **Newest synced item**: 2026-09-17 `1537c58`, version 0.3.178 (204) — **fully ported 2026-09-25** (managed-position exhaustion fallback + `burnInPattern` diagnostic field), see its row in the synced table below. The recorded baseline stays `1537c58` on purpose, so the scan keeps reporting the pending delta until it is ported and recorded.
- **Upstream repository**: https://github.com/amarinne/hyperglow (default branch: main)
- Baseline verification marks (2026-09-05): AodLyricBridgeService already includes dynamic uid matching,
  HierarchyFields.kt and its use across all hooks, missingProbeNames, and miuix via the public Maven Central repository

## Synced / Included

> Date column = the upstream commit date; the date a change landed in CN+ is in the status column.

| Upstream commit | Date | Content | Status |
|---|---|---|---|
| `1537c58` (v0.3.178) | 2026-09-17 | **Managed-position exhaustion falls back to stock geometry**: new pure function `shouldAttemptManagedPosition(latchedUnavailable, scheduleChanged)` (`AodSnapshotPolicy.kt`); `AodSurfaceController` gains a `managedPositionUnavailable` latch — on retry exhaustion it latches and `setStockWidgetControlActive(false)` releases managed control so the scene truly follows Xiaomi's stock clock geometry (instead of staying at the initial top fallback with control nominally still on); the latch clears when the burn-in pattern/interval changes (one retry); latch and retry counter reset on detach. Also: the diagnostic report's `renderPreferences` section gains a `burnInPattern` field (`DiagnosticReportFactory` + `DiagnosticContract` key set) | ✅ Ported (2026-09-25; CN+ adaptation: the guard hangs off CN+'s own `setStockWidgetControlActive` call site, without upstream's `!suppressStockAodContent` prefix — CN+ suppression runs independently in `applySuppressionAndRotation`; tests `exhaustedManagedRetriesFallBackToStockGeometry` + `latchedManagedFailureStaysOnStockUntilScheduleChanges` landed with `AodPositionUpdateTest`, verified offline). **CN+ revision (2026-09-25, #67 re-review)**: the exhaustion fallback is additionally gated on `aodClockFollow` (`shouldReleaseManagedControlOnExhaustion`) — only when "follow system clock in real time" is enabled does it latch and release managed control; in anchored (fixed) mode the integral pin owns the clock (issue #33 anchored-first; `advanceManagedPosition` is always false while the pin is active), so exhaustion only logs and control/latch stay untouched — fixed-mode behavior unchanged. Test `exhaustionReleaseOnlyWhenClockFollowEnabled` landed |
| `2885511` item #2 | 2026-09-14 | Multi-line metadata: `AodStateProjector.projectToDisplay` joins title/artist with `\n` (and `·`→newline) instead of `" · "`; canvas splits metadata rows into per-line lines with reserved extra line height | ✅ Ported (2026-09-18). Projector changed to `joinToString("\n").replace('·','\n')`; CN+ canvas `wrapMetadataText` now splits on `\n`/`·` as hard line breaks (wrapping only overflow segments). Multi-line render + extra-height reservation were already handled in CN+'s `drawText`/`positionRows`/intro-large/morph paths. AodCanvasLayoutTest/AodStateProjectorTest green |
| `2885511` item #4 | 2026-09-14 | Shared logical clip for all lyric draw paths: a single top-level `clipRect(padLeft, padTop, ow-padRight, oh-padBottom)` in `AodLyricCanvasView.drawRows` keeps even indivisible-word/animation overhang inside the padded frame; removes the per-`drawText` clip | ✅ Already present in the CN+ baseline (test-covered): the top-level shared clip enforces the horizontal padding; the remaining per-line clips are kept for extra vertical convergence |
| `2885511` item #1 (DexKit) | 2026-09-14 | DexKit dynamic symbol resolution: `DexKitRuntime`/`SymbolCache`/`SymbolResolver` (bundled-first / DexKit-fallback, `debug.hyperglow.symbols` policy, provenance ledger), Gradle dep + proguard keep, and refit of ~12 hooks + `XiaomiCapabilityResolver` probes to route through `SymbolResolver`; `HookEntry` installs/observes/clears it | ✅ Ported (2026-09-18). Unit tests for the symbols package pass; `compileDebugKotlin` green. `AodWakeBroker` + `HierarchyFields` intentionally kept on direct reflection (CN+ diverged candidate/fallback logic) |
| `748912e` items #8+#9 | 2026-09-11 | Per-scene AOD brightness override (`AodBrightnessController.setBrightnessOverride` + two-mode UI: master boost switch → auto scene clamp vs custom 10–255 slider) and AOD max-height ceiling `0.5→0.9` (SurfacePolicyResolver only; CN+ AOD interior stays content-fit) | ✅ Ported (2026-09-16, in pre-release 0.3.88 / `115-0.3.88`; CI green) |
| `748912e` items #4(codec)+#7 | 2026-09-11 | Type-safe config backup/restore codec (`ConfigBackupCodec` adapted to CN+'s field set via `AodRenderConfig.DEFAULTS`; replaces MainActivity's type-guessing `exportAllConfig`/`importAllConfig`) + `CustomizationRepository` compiled-cache/invalidation | ✅ Ported (2026-09-16, in pre-release 0.3.88 / `115-0.3.88`; CI green). `SettingsSession`/`PreferenceSettingsStore` **deferred** (coupled to the unported tab UI restructure) |
| `748912e` item #3 | 2026-09-11 | `HookRegistry` central registry + hot-reload refit: every hook installs via `HookRegistry.hook(module, FEATURE_ID, …)` with `PROTECTIVE` mode; `onHotReloading` (reject while a lyric session is active + `retireGeneration`) / `onHotReloaded` (unhook previous handles + reinstall from re-derived host app + `SystemUiLifecycleHook.bootstrap`); install split into `installDefaultLoaderHooks`/`installAodHooks`; reload-cleanup `cancelPendingForReload`/`stop` on AOD controllers/monitor; `HookRegistryTest` | ✅ Ported (2026-09-16; code + test written). AntiFreezeHook (system_server) stays direct. CI green |
| `748912e` item #1 | 2026-09-11 | AOD canvas rotates with the device: `AodOrientationMonitor` (accelerometer lifecycle / debounce / framework-rotation rebase) + per-orientation anchor / text-scale / padding wiring; `aodLandscapeCanvasSize` logical landscape frame | ✅ Already present in CN+ (initial import, not a separate port), threaded through LyricSnapshot / AodStateProjector / AodRenderPreferences / AodStateWire; re-align only if upstream's per-orientation defaults need exact matching |
| `748912e` item #2 | 2026-09-11 | `suppressStockAodContent`: hide Xiaomi's stock AOD while the full-screen canvas draws, incl. the `View.setVisibility` GONE enforcement seam (`StockVisibilityHooker`) and `holdStockPosition` | ✅ Already present in CN+ (initial import); reconcile hold-mode details only as desired |
| `748912e` item #6 | 2026-09-11 | `hasActualLyricTiming` interlude fix: instrumental-dot rows no longer count as actual timing | ✅ Equivalent already present — CN+'s own row judgement (`AodProjectionEngine` / `SpicyLyricProducer`) instead of upstream `ROW_ROLE_INTERLUDE`; same semantics |
| `748912e` item #5 (+ `duetEnabled` from #10) | 2026-09-11 | Duet / concurrent second sung line: primary + overlapping companion (≥1 s shared window, pure time-overlap) stacked on AOD, each with its own karaoke sweep | ✅ Ported (2026-09-30, PR #118): selection precomputed producer-side per CN+'s no-row-selection-at-projection contract (`selectDuetLineIndex` three-stage semantics; Spicy/Lyricon/LyricInfo; SuperLyric excluded), per-surface `duetConcurrent` switch, CN+ wire body v4 carries `duetLine`; upstream slot-inheritance / per-section transitions and the v1→v9 wire codec intentionally simplified — see SPEC |
| `748912e` item #10 (cardColor/cardAlpha) | 2026-09-11 | Per-scene `cardColor`/`cardAlpha` customization fields | ✅ Already present in CN+ (initial import), fully wired; `duetEnabled` only matters with item #5, ported via PR #118 (2026-09-30), see Synced |
| `748912e` item #12 | 2026-09-11 | Misc hardening sweep across remaining hooks (~+1k lines total) | ✅ Covered: nearly all of it is the #3 HookRegistry refit or the #1/#2 suppress/orientation wiring, which were ported; no separate diff beyond those items |
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

## Pending Evaluation (baseline not advanced)

> Detected 2026-10-02: upstream advanced from the recorded baseline `1537c58` (v0.3.178) to
> `99ba119` (2026-09-28, v0.3.191 / versionCode 216) — one squashed `update` commit, ~1457
> insertions over 47 files. Nothing below is ported yet; `.github/upstream-baseline.txt` is
> deliberately left at `1537c58` so the scan keeps reporting this delta until the port is done and
> recorded. Per-row "CN+ note" flags where CN+ has diverged enough to shape the port.

| Upstream commit | Date | Content | Status |
|---|---|---|---|
| `99ba119` item #1 | 2026-09-28 | **Song-info layout `stacked`/`single`** (`metadataLayout`): new pref threaded config → wire → projector → canvas → UI; `single` joins title/artist with ` · ` on one line, `stacked` keeps title over artist; a piece too wide for the frame wraps onto further lines at lyric size (persistent metadata **and** the song-change intro placeholder) instead of shrinking the whole block. Touches AodRenderPreferences / AodStateBridge / AodStateWire / AodStateProjector / LyricSnapshot / LyricCanvasMapper / AodLyricCanvasView / CustomizationModels / CustomizationRepository / SceneCompiler / SystemUiCustomization / SettingsSession / PreferenceSettingsStore / ConfigBackupCodec / MainActivity + strings + tests | ⏳ Not ported. Wire: upstream body v10 appends the layout; **CN+ owns its own body scheme (currently v5: v3 artwork / v4 duet / v5 next-line aux)**, so this would land as CN+ v6 |
| `99ba119` item #2 | 2026-09-28 | **Configurable song-intro length** (`songIntroDurationMs`, default 5000 ms): 2–30 s, or `-1` = no time cap (intro lasts the whole interlude and ends only when a lyric takes the row). `SongMetadataIntroPolicy.durationMs` becomes mutable + `setDurationMs`, re-applied on every projection so a settings change needs no restart; slider with a "keep indefinitely" stop in the song-change section; ConfigBackupCodec; strings | ⏳ Not ported |
| `99ba119` item #3 | 2026-09-28 | **Named wire-decode rejection reasons**: `AodStateWireDecodeOutcome` (Decoded/Rejected) + `decodeRejectReason`, naming `protocol_mismatch` / `invalid_scalars` / `missing_body` / `undecodable_body` / `unknown_kind`; `AodStateWireBundleCodec.envelopeFromBundle` split out; `AodLyricClient` logs the reason, so a self-healing app/hook protocol skew reads differently from real corruption | ⏳ Not ported. CN+ codec is separate → port the outcome type + gate names into CN+'s own decode gates |
| `99ba119` item #4 | 2026-09-28 | **Stock clock-or-image band reserved on the side it actually occupies**: new pure `aodStockClockReserve` / `AodStockReserve` used by `AodSurfaceController`; a top-band clock now reserves the top (previously always reserved the bottom, which pinned lyrics under the camera cutout on devices whose stock clock is not at the bottom) | ⏳ Not ported |
| `99ba119` item #5 | 2026-09-28 | **AOD wake-broker hardening**: `AodWakeAvailability` (ready/interactive/no_host/no_method/no_power_manager) + resolve/adopt/report/detail helpers; power manager seeded from a context at SystemUI `onCreate`; `adoptHost` seam fed by the AOD visibility hook (a plugin instance created before the hook never runs the constructor); wake method resolved at install; per-distinct-reason latch, with the installer-skip summary appended to `no_host` | ⏳ Not ported. **CN+ broker is heavily diverged** (WeakReference host, Lyricon watchdog, install retries, candidate class/field/method names) → port by concept only |
| `99ba119` item #6 | 2026-09-28 | **`Word` documents count as timed**: `AodProjectionEngine.isTimedDocumentType` / `isEffectiveLineLevelSync` accept `Word`; keepalive spec widened to `Line`/`Word`/`Syllable` | ⏳ Not ported (CN+ still only Line/Syllable) |
| `99ba119` item #7 | 2026-09-28 | **`readNumericField` in `AodPositionHook`**: read the boxed `Number` so an int/float field-width mismatch (`mTranslationY` int vs `mTranslationYStep` float on surveyed builds) no longer throws inside `runCatching` — the throw had silently disabled managed position **and** the stock-widget hold | ⏳ Not ported (real bug fix) |
| `99ba119` item #8 | 2026-09-28 | **Diagnostics root-probe hardening**: `ROOT_PROBE_TIMEOUT_MS = 15 s` for `id -u` (a root-manager grant prompt needs the longer window) + `runFirstRootBinary` tries `su` on `PATH` then absolute locations (`/system/bin/su`, `/system/xbin/su`, `/sbin/su`, `/debug_ramdisk/su`, `/data/adb/ksu/bin/su`, `/data/adb/ap/bin/su`); only an un-spawnable binary falls through | ⏳ Not ported |
| `99ba119` item #9 | 2026-09-28 | **Clause-punctuation wrap bonus**: `balancedChunkRanges(..., breakAfter)` + `PUNCTUATION_BREAK_BONUS_FRACTION = 0.2` + `endsWithClausePunctuation`, applied to secondary timed lines, token lines and word chunks (a break after clause punctuation is preferred when it needs no extra line) | ⏳ Not ported (supersedes the deferred `2885511` item #3 row) |
| `99ba119` item #10 | 2026-09-28 | **Duet slot memory removed**: `episodeDuetGenerations` / `duetEnded` deleted, `freshSolo = orderedIds.size == 1`; every later solo uses the configured free anchor | ➖ N/A for code: CN+ never had the slot memory (duet via PR #118 kept the primary slot); only the spec text differs |
| `99ba119` item #11 | 2026-09-28 | **Upstream doc sync**: ARCHITECTURE (punctuation phrase breaks; correction that Android does **not** auto-reconnect a bound-service client — rebinding is the producer client's responsibility, provider transport excepted); LOCKSCREEN_AOD_BEHAVIOR_SPEC (free-anchor solos / stock band reserve / intro-length slider / `Line`,`Word`,`Syllable` keepalive / wake-broker seams + named faults / stacked-single layout / capability ≠ reachability / named wire rejection); DIAGNOSTIC_REPORTING_SPEC (root probe) | ⏳ Not synced — CN+ `docs/` still carry the pre-`99ba119` text (e.g. ARCHITECTURE.md still claims the automatic-reconnect behavior) |
| `99ba119` item #12 | 2026-09-28 | Version 204/0.3.178 → 216/0.3.191 | ➖ Not applicable (CN+ independent version numbering) |
| `99ba119` item #13 | 2026-09-28 | Tests: new `AodWakeBrokerRecoveryTest` + additions to AodCanvasLayoutTest / AodPositionUpdateTest / AodStateWireCodecTest / AodStateProjectorTest / SongMetadataIntroPolicyTest / DiagnosticCaptureCollectorTest / AodRenderPreferencesTest / SceneCompilerTest / ConfigBackupCodecTest / AodProjectionLifecycleTest / SpicyBridgeDocumentTest | ⏳ Land with the port |

**Usefulness assessment (2026-10-02, cross-checked against the current CN+ tree)**

- **High value — port first:**
  - **item #7 `readNumericField`**: CN+ [readClockGeometry](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodPositionHook.kt#L698-L707) still reads `mTranslationY` with `getFloat`, yet CN+ itself pinned `mTranslationY` as **int** on surveyed firmware (issue #66 — the write-side fix). The read throws inside `runCatching`, so the geometry is always null and managed position **and** the stock-widget hold silently stop working. Tiny, zero-risk fix that restores function.
  - **item #5 wake-broker hardening (port by concept)**: CN+ captures the host only in the [DozeTriggers constructor hooker](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodWakeBroker.kt#L320-L343), so an instance created before hook install is never captured (the retries only re-install the hook). [VisibilityTelemetryHooker](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodLifetimeHook.kt#L134-L147) already receives the `DozeHost` (`chain.thisObject`) — exactly the host the broker needs — so it can serve as the `adoptHost` seam. Seeding the power manager from the SystemUI `onCreate` [Application](file:///workspace/app/src/main/java/com/eza/hyperglow/root/SystemUiLifecycleHook.kt#L50-L57) lets the Lyricon watchdog run without a `DozeTriggers` instance. Named faults + install-skip summary make the single current "AOD wake host unavailable" warning diagnosable.
  - **item #8 root-probe hardening**: CN+ runs only `ProcessBuilder("su", ...)` with the 5 s `COMMAND_TIMEOUT_MS`; KernelSU/APatch `su` often sits outside `PATH` and a root-manager grant prompt exceeds 5 s, so the probe fails and diagnostics misreport. Absolute-path fallbacks + the 15 s probe window are cheap.
- **Medium value — as needed:**
  - **item #1 metadata layout + wrap-instead-of-shrink**: CN+ [layoutMetadataLines](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/LyricLayoutEngine.kt#L176-L203) caps at 3 lines and drops overflow, and `AodCanvasModel` scales the block when content exceeds the canvas; upstream wraps onto further lines instead. `stacked`/`single` is a new choice for CN+ (CN+ already defaults to a ` · ` single line with per-slot separators — close to upstream `single`). User-visible, but must thread CN+'s own wire/customization/preview layer.
  - **item #2 configurable intro length**: CN+ fixes 3000 ms and has diverged structurally (no `openingResolved`/`provisional`); porting must reconcile the `-1` (no cap) semantics with CN+'s `availableInterludeMs >= durationMs` gate. Preference feature.
  - **item #3 named wire rejections**: CN+ logs a generic "Rejected invalid/malformed state payload"; folding the gate names into CN+'s own decoder cheaply separates self-healing app/hook protocol skew from real corruption.
  - **item #9 clause-punctuation wrap bonus**: CN+ has the same-shape `balancedChunkRanges` ([AodCanvasLineLayout](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodCanvasLineLayout.kt#L47)) to add `breakAfter`; pure typography polish.
  - **item #6 `Word` documents as timed**: CN+ document `type` comes from the bridge payload, so a source can emit `Word`; today `isTimedDocumentType` accepts only Line/Syllable, so a `Word` document renders as UNSYNCED. Cheap robustness.
- **Low value / defer:**
  - **item #4 stock-clock band reserve**: CN+ does not reserve a bottom band; it uses [avoidStockClockOverlap](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodSurfaceController.kt#L1678-L1697) (moves the lyric rect below the clock on overlap), so the upstream defect may not exist here — confirm on a top-clock device first.
  - **item #11 upstream spec sync**: value lies in doc accuracy (notably the "Android does not auto-reconnect" correction) but it should follow the code conclusions; do it alongside a port.
  - **item #13 tests**: land with their features.
- **Not applicable:** item #10 (CN+ never had duet slot memory); item #12 (CN+ version numbering).

## Not Synced / Excluded

> Evaluated and deliberately not ported, or not applicable — with reasons; re-evaluate on demand.
> Date column = the upstream commit date.

| Upstream commit | Date | Content | Reason |
|---|---|---|---|
| `748912e` item #4 remainder | 2026-09-11 | `SettingsSession` / `PreferenceSettingsStore` settings-session refactor | ⏸ Deferred: coupled to the unported MainActivity tab rework (CN+ has its own settings layout); same deferral as the `0424ae9` row below |
| `748912e` item #11 | 2026-09-11 | Wire body protocol v1→v9 backward-compatible codec | ⏸ Deferred: the upstream v1→v9 codec is not adopted; feature #5 (duet) is carried by CN+'s own wire body v4 (2026-09-30, PR #118) — reconcile versions if/when adopting the remaining features |
| `2885511` item #3 | 2026-09-14 | Canonical punctuation attachment (`aodPunctuationAttachToPrevious`/`Next`, `attachAodPunctuationGroups`) + chunk packing counting rendered separators | ⏸ Optional: low-risk wrap polish; port if standalone CJK/Latin punctuation wrapping looks poor |
| `2885511` item #5 | 2026-09-14 | Duet-section end → recenter remaining solo (`shouldRecenterAfterDuet`/`duetEnded`/`wasDuet`) | ⏸ Still not ported: PR #118 (2026-09-30) brought the concurrent second line with v1 simplifications (top-anchored layouts keep the primary slot; no slot-inheritance machinery), so recenter-after-duet has no counterpart yet — port alongside the slot system if duet layouts drift |
| `2885511` item #6 | 2026-09-14 | Version bump 0.3.173 → 0.3.177 | ➖ Not applicable (CN+ has independent version numbering) |
| `2885511` item #7 | 2026-09-14 | Docs: ARCHITECTURE hook symbol resolution; LOCKSCREEN_AOD_BEHAVIOR_SPEC shared clip + padding | ⏸ Not synced (checked 2026-09-28: neither doc covers it); sync when next touching those areas |
| `0424ae9` remainder | 2026-08-22 | `SettingsSession`; LucideIcons; RTL lyrics rendering (AodTextDirection / physical-alignment conversion / drawDirectionalText); hideFromRecents | ⏸ Deferred: SettingsSession implies the unported MainActivity tab rework (see `748912e` item #4); RTL is of low value for CN users |
| `ced2769` remainder | 2026-08-13 | DiagnosticsScreen simplification; AodKeepaliveRegressionTest | ⏸ Deferred: the UI simplification does not apply (CN+'s DiagnosticsScreen has a different structure); postHandoff diagnostics were ported |
| `cc1f62f` remainder | 2026-08-15 | Document transport grace (`scheduleDocumentClear`/`DOCUMENT_TRANSPORT_GRACE_MS`); `spicyBridgeDocumentMismatch` engine-side clearing; `logKeepAliveEdge`/`logAodEnabledEdge` | ⏸ Deferred: transport grace is structurally inapplicable (CN+ documents are cleared proactively by the producer); the mismatch call path does not exist in CN+; re-evaluate `logKeepAliveEdge` if a matching issue appears |

## Local enhancement (2026-09-16): AOD clock pin behavior + vertical offset

Not from upstream — a CN+ user-requested change on top of the anchored-clock feature:
- **Don't pin the anchored system-clock position while playback is paused or there is no playback** — `AodSurfaceController.applySuppressionAndRotation` now computes `pinClock = renderable && playbackActive && !currentAodProfile().aodClockFollow`, so the clock only gets pinned during active playback in anchor mode; on pause / no playback it returns to its system position and follows burn-in movement normally.
- **Vertical offset slider when "Follow system clock in real time" is off** — new `aodClockYOffset` pref (px, −480..480, negative up / positive down) threaded through `AodRenderPreferences` / `CompiledCustomization` (`CustomizationModels`) / `RuntimeCustomization` (`DiagnosticLogging`) / `ConfigBackupCodec`; `AodPositionHook.setClockYOffset(px)` adds the offset to the pinned clock Y (via `lastStockTranslationY` anchor, no per-frame accumulation); shown only when the follow toggle is off.

## Operating Procedure When Syncing

1. `git fetch upstream main` (remote `upstream` = https://github.com/amarinne/hyperglow , already configured)
2. Cross-check the Synced / Included and Not Synced tables above and review each change with `git show <sha>`
3. Port to CN+ manually by content (note that CN+ has diverged deeply: Lyricon producer stack, version numbering, CN music app adaptation)
4. Version numbers do not follow upstream (CN+ has an independent scheme); keep only one of UpdateChecker/VersionCheck
5. After porting, run CI (554+ tests) and push once everything is green
6. **Update this file**: move the item from the Not Synced table (or add a new row) into Synced / Included and update the baseline date

---

# 中文 / Chinese

> 用途：每次用户要求「同步上游更新」时，先读本文件了解已同步基线，
> 再对照上游新提交清单，判断哪些需要移植。
> 两仓库 git 历史完全独立（CN+ 为重打包独立版），无法直接 merge，
> 只能按内容手工挑选移植。

## 当前状态

- **CN+ 版本**：0.3.125 (152)（截至 2026-09-28），上游基线截至 `1537c58`（v0.3.178）。此后的评估增量：`748912e`（2026-09-11）、`2885511`（2026-09-14）与 `1537c58`（2026-09-17）——均已在下方各表中处置。
- **上游最新**：2026-09-28 `99ba119`，版本 0.3.191 (216) —— **2026-10-02 侦测到，尚未移植**；单个压平的 `update` 提交（`1537c58..99ba119`，约 1457 行 / 47 文件），各项已登记于下方新增的「待评估（尚未移植）」表。
- **最新已同步项**：2026-09-17 `1537c58`，版本 0.3.178 (204) —— **已于 2026-09-25 全部移植**（托管位移重试耗尽回落 + `burnInPattern` 诊断字段，#67），见「已同步 / 已包含」表。基线有意保持 `1537c58` 不动，使扫描持续报告该待处理增量，直至移植并登记。
- **上游仓库**：https://github.com/amarinne/hyperglow（default branch: main）
- 基线核实标记（2026-09-05）：AodLyricBridgeService 已含 uid 动态匹配、
  HierarchyFields.kt 及全 hook 使用、missingProbeNames、miuix 走 Maven Central 公共仓库

## 已同步 / 已包含

> 日期列为上游提交日期；落入 CN+ 的日期见各状态列。

| 上游提交 | 日期 | 内容 | 状态 |
|---|---|---|---|
| `1537c58` (v0.3.178) | 2026-09-17 | **managed 位置重试耗尽后回落原厂几何**：新增纯函数 `shouldAttemptManagedPosition(latchedUnavailable, scheduleChanged)`（`AodSnapshotPolicy.kt`）；`AodSurfaceController` 增加 `managedPositionUnavailable` latch —— 重试耗尽时置位并 `setStockWidgetControlActive(false)` 释放托管控制，使场景真正跟随小米原厂时钟几何（而非停在初始顶部兜底但控制名义上仍开）；burn-in 图案/间隔变化时清除 latch 重新尝试一次；detach 时复位 latch 与重试计数。另：诊断报告 `renderPreferences` 段新增 `burnInPattern` 字段（`DiagnosticReportFactory` + `DiagnosticContract` 键集） | ✅ 已移植（2026-09-25；CN+ 适配：守卫挂在 CN+ 自己的 `setStockWidgetControlActive` 调用点，未带上游的 `!suppressStockAodContent` 前缀——CN+ 抑制逻辑独立走 `applySuppressionAndRotation`；测试 `exhaustedManagedRetriesFallBackToStockGeometry` + `latchedManagedFailureStaysOnStockUntilScheduleChanges` 已随 `AodPositionUpdateTest` 落地并离线验证）。**CN+ 修正（2026-09-25，#67 复审）**：耗尽回落再加 `aodClockFollow` 门（`shouldReleaseManagedControlOnExhaustion`）——仅「实时跟随系统时钟」开启时才置 latch 并释放托管控制；锚定（固定）模式下时钟由 integral pin 接管（issue #33 锚定优先，`advanceManagedPosition` 在 pin 激活时恒 false），耗尽只记日志、控制与 latch 均不动，固定模式行为与移植前一致。测试 `exhaustionReleaseOnlyWhenClockFollowEnabled` 落地 |
| `2885511` 项 #2 | 2026-09-14 | **元数据多行**：`AodStateProjector.projectToDisplay` 将歌名/歌手用 `\n` 拼接（且 `·`→换行）替代 `" · "`；画布把元数据按行拆成多行并预留额外行高 | ✅ 已移植（2026-09-18）。投影层改为 `joinToString("\n").replace('·','\n')`；CN+ 画布 `wrapMetadataText` 现按 `\n`/`·` 作为硬换行拆行（仅对超宽段做 token 换行）。多行渲染与额外行高预留 CN+ 早已由 `drawText`/`positionRows`/intro-large/morph 处理。AodCanvasLayoutTest/AodStateProjectorTest 跑绿 |
| `2885511` 项 #1（DexKit） | 2026-09-14 | **DexKit 动态符号解析**：`DexKitRuntime`/`SymbolCache`/`SymbolResolver`（内置反射优先、DexKit 兜底、`debug.hyperglow.symbols` 策略、来源台账），Gradle 依赖 + proguard keep，约 12 个 hook 与 `XiaomiCapabilityResolver` 探针改走 `SymbolResolver`；`HookEntry` 安装/观察/清除 | ✅ 已移植（2026-09-18）。symbols 包单测通过；`AodWakeBroker` 与 `HierarchyFields` 按设计保留直接反射（CN+ 分叉的候选/兜底逻辑） |
| `2885511` 项 #4 | 2026-09-14 | **所有歌词绘制路径共享逻辑裁剪**：`AodLyricCanvasView.drawRows` 顶层统一 `clipRect(padLeft, padTop, ow-padRight, oh-padBottom)`，即使整词不可分/动画越界也强制限制在 padding 框内；删除 `drawText` 逐行重复 clip | ✅ 基线已含（测试覆盖；顶层共享 clip 统一施加水平 padding 边界，其余 per-line clip 保留作额外垂直收敛） |
| `748912e` 项 #8+#9 | 2026-09-11 | 按场景 AOD 亮度覆写（`AodBrightnessController.setBrightnessOverride` + 两模式设置 UI：总开关开启后 → 按场景自动化 vs 自定义 10–255 滑块）与 AOD 最大高度硬上限 `0.5→0.9`（仅 SurfacePolicyResolver；CN+ AOD 内部保持内容贴合） | ✅ 已移植（2026-09-16，随预发行 0.3.88 / `115-0.3.88`；CI 跑绿） |
| `748912e` 项 #4(codec)+#7 | 2026-09-11 | 类型安全配置备份/恢复编解码（`ConfigBackupCodec` 经 `AodRenderConfig.DEFAULTS` 适配 CN+ 字段集，替换 MainActivity 中猜测类型的 `exportAllConfig`/`importAllConfig`）+ `CustomizationRepository` 编译缓存/失效 | ✅ 已移植（2026-09-16，随预发行 0.3.88 / `115-0.3.88`；CI 跑绿）。`SettingsSession`/`PreferenceSettingsStore` **暂缓**（耦合未移植的 tab 化 UI 重构） |
| `748912e` 项 #3 | 2026-09-11 | `HookRegistry` 中心注册表 + 热重载改造：所有 hook 经 `HookRegistry.hook(module, FEATURE_ID, …)` 安装且带 `PROTECTIVE` 模式；`onHotReloading`（lyric 会话活跃时拒绝 + `retireGeneration`）/`onHotReloaded`（unhook 旧句柄 + 从重派生宿主应用重装 + `SystemUiLifecycleHook.bootstrap`）；安装拆为 `installDefaultLoaderHooks`/`installAodHooks`；AOD 控制器/监测器重载清理 `cancelPendingForReload`/`stop`；`HookRegistryTest` | ✅ 已移植（2026-09-16；代码+测试已写）。AntiFreezeHook（system_server）按设计保持直装。CI 跑绿 |
| `748912e` 项 #1 | 2026-09-11 | AOD 画布随设备旋转：`AodOrientationMonitor`（加速度计生命周期/防抖/框架旋转 rebase）+ 分朝向锚点/字号/padding 接线；`aodLandscapeCanvasSize` 逻辑横屏框 | ✅ CN+ 初始导入已含（非单独移植），已贯通 LyricSnapshot/AodStateProjector/AodRenderPreferences/AodStateWire；仅当需精确对齐上游分朝向默认值时再对齐 |
| `748912e` 项 #2 | 2026-09-11 | `suppressStockAodContent`：自绘全屏画布时隐藏小米系统 AOD 内容，含 `View.setVisibility` GONE 强制接缝（`StockVisibilityHooker`）与 `holdStockPosition` | ✅ CN+ 初始导入已含；仅按需对齐 hold 模式细节 |
| `748912e` 项 #6 | 2026-09-11 | `hasActualLyricTiming` interlude 修复：乐器点行不再计入「实际计时」 | ✅ 等效已实现——CN+ 自身行判定（`AodProjectionEngine`/`SpicyLyricProducer`）而非上游 `ROW_ROLE_INTERLUDE`；语义相同 |
| `748912e` 项 #5（含 #10 的 `duetEnabled`） | 2026-09-11 | 对唱/并发第二歌行：主行 + 重叠并发行（共享窗口 ≥1s，纯时间轴判定）在息屏双行同显、各画各的逐字扫光 | ✅ 已移植（2026-09-30，PR #118）：依 CN+「投影不选行」契约改为生产者侧预计算（`selectDuetLineIndex` 三段式；Spicy/Lyricon/LyricInfo 三源，SuperLyric 不产出），per-surface `duetConcurrent` 开关，CN+ wire body v4 携带 `duetLine`；上游槽位继承/双段独立过渡与 v1→v9 编解码有意简化——见 SPEC |
| `748912e` 项 #10（cardColor/cardAlpha） | 2026-09-11 | 按场景 `cardColor`/`cardAlpha` 定制字段 | ✅ CN+ 初始导入已含，端到端接线完整；`duetEnabled` 仅项 #5 存在时才有意义，已随 PR #118（2026-09-30）移植，见已同步 |
| `748912e` 项 #12 | 2026-09-11 | 其余 hook 杂项加固扫尾（合计约 +1k 行） | ✅ 已覆盖：几乎全部为 #3 HookRegistry 改造或 #1/#2 抑制/旋转接线（均已移植）；除此之外未单独应用差异 |
| `b0254d5` (v0.3.96) | 2026-09-01 | AOD 亮度钳制：AodBrightnessHook（新文件）+ HookEntry 三路径安装 + AodLifetimeHook visibility 遥测/setLyricGuardActive 联动；AodLyricClient keepalive 同 revision 合并（mergePendingKeepAlive）；AodPowerCoordinator wake identity 前移消费；AodSurfaceController alpha 链检测（effectiveSurfaceAlpha/surfaceAlphaChain）；DiagnosticCaptureCollector logcat `-t 4000`→`-T <timestamp>`；诊断引导文案（4 语言+模板）；ARCHITECTURE/DIAGNOSTIC_REPORTING/LOCKSCREEN_AOD_BEHAVIOR 规范同步 | ✅ 已移植（2026-09-08；PAUSE_CONFIRM_MS=5s、通知几何死区、projection stale 保留此前已在 CN+ 基线，仅补齐 docs 与测试） |
| `cc1f62f`+`ced2769`+`0424ae9`（诊断部分） | 2026-08-13~22 | 三项诊断能力：① DiagnosticTraceFile——App 进程日志文件镜像（HyperOS 丢弃 App 侧 logcat，AppLog i/w/e 落盘、512KB 轮转）；② SystemUiLyricProjection 命名拒绝日志（accept() 5 处静默拒绝改为 rejected(reason) 去重记录）；③ AodDrawWakePulseResult——draw wake 锁脉冲四类结局分类 + 去重记录 | ✅ 已移植（2026-09-11；CN+ 增强：trace 按捕获起点过滤并折叠进报告 logs 段 `app_trace=`，root 拒绝路径也携带，APP_TRACE_BYTES=96KB 专属预算，上游仅落盘不进报告） |
| `cc1f62f`+`ced2769`（诊断部分·二） | 2026-08-13~15 | 两项：④ 文档拒绝命名原因——SpicyBridgeDocumentStore.accept/commit 返回 String?（14 处拒绝路径全部报字面：no-state/state-mismatch/payload-identity/oversized/malformed/commit-timing/commit-order），spicyBridgeDocumentTimingFault 报出分叉字段（duration/row/word/fillEnd），Service 侧 logDocumentRejection 去重记录；⑤ postHandoff 诊断——handoff active→inactive 且场景仍活跃时 +1s/+7s 两次表面全景快照（visibility/alpha 链/rect/scale/render/wake 一行打齐），detachCurrent 取消 | ✅ 已移植（2026-09-11；timingFault 保留 CN+ fillEndMs 放宽语义（可越本行尾、不得越歌长）；上游 spicyBridgeDocumentMismatch 引擎侧清文档路径 CN+ 不存在（生产者主动 clear），未移植该函数） |
| `8422d78` (v0.3.97) | 2026-09-01 | 中文歌出现日语假名注音 ruby 时拒绝显示（hasLanguageInconsistentKanaRuby/isKana、language 字段贯通、fillEndMs 越行尾合法化 + lineEndMs 渲染钳制） | ✅ 已移植（2026-09-05，按 CN+ 投影层结构改写；2026-09-11 复核 b0254d5..8422d78 增量并全部处置：① 标量辅助行只来自文档——评估后**不移植**，CN+ 保留无文档时兜底行的标量罗马音/翻译：触发面仅限 untimed/文档未达，假名守卫已覆盖文档主路径，标量路径无实测危害，移植为纯减法（砍掉 untimed 歌的 AI 翻译显示），分歧已写入 LOCKSCREEN_AOD_BEHAVIOR_SPEC.md；若未来出现错误标量注音反馈，改回只需 SpicyLyricProducer 两行；② spec 三段（辅助行来源/假名拒绝/fillEnd 钳制）补齐；③ timingFault 消息 window=→duration= 纯文案差异，跳过；④ versionCode 0.3.97 不适用） |
| `6216fdc` | 2026-08-08 | 版本锁定退役：XiaomiProfileState 增加 AVAILABLE、capability 计数展示（availableCapabilityCount/totalCapabilityCount）、移除 verifiedRuntimeProfile 版本 pin、summary 改为 available=n/total、DiagnosticSetupPolicy 可运行状态集 | ✅ 已移植（2026-09-05；保留 CN+ 实验模式本地覆写逻辑） |
| `c5b1ffa` | 2026-08-11 | DiagnosticContract 校验增加「available」状态 | ✅ 已移植（2026-09-05） |
| `f9dfa01` | 2026-08-09 | SystemUI uid 动态匹配 + HierarchyFields 字段链遍历 + 探针缺失日志 | ✅ 已同步（UpdateChecker 部分除外，CN+ 用自己的 VersionCheck.kt） |
| `8d89b10` | 2026-08-06 | AodStateProjector 引入等大重构 | ✅ 已同步 |
| `2608031` | 2026-08-05 | 移除凭据 miuix 仓库（改 Maven Central） | ✅ 已同步 |
| `bf988be` | 2026-08-03 | bump 0.3.50 | ➖ 版本号不适用（CN+ 独立版本号体系） |
| 更早提交 | ≤2026-08-03 | FAQ / 诊断政策 / 历史 | ➖ 未逐个核对（基线整体已含） |

## 待评估（尚未移植，基线未推进）

> 2026-10-02 侦测：上游从基线 `1537c58`（v0.3.178）推进到 `99ba119`（2026-09-28，v0.3.191 / versionCode 216）
> ——单个压平的 `update` 提交，约 1457 行 / 47 文件。以下均**尚未移植**；`.github/upstream-baseline.txt`
> 有意保持 `1537c58`，使扫描持续报告该增量，直至移植并登记。各行的「CN+ 备注」标出 CN+ 分叉大到会影响移植方式之处。

| 上游提交 | 日期 | 内容 | 状态 |
|---|---|---|---|
| `99ba119` 项 #1 | 2026-09-28 | **歌曲信息布局 `stacked`/`single`**（`metadataLayout`）：新偏好贯通 配置 → wire → 投影 → 画布 → UI；`single` 用 ` · ` 把歌名/歌手并成一行，`stacked` 保持歌名在上歌手在下；某段过宽时在歌词字号下换行到后续行（**持久歌曲信息与切歌开场占位**皆然），而非整体缩小。涉及 AodRenderPreferences / AodStateBridge / AodStateWire / AodStateProjector / LyricSnapshot / LyricCanvasMapper / AodLyricCanvasView / CustomizationModels / CustomizationRepository / SceneCompiler / SystemUiCustomization / SettingsSession / PreferenceSettingsStore / ConfigBackupCodec / MainActivity + strings + 测试 | ⏳ 未移植。wire：上游 body v10 追加布局字段；**CN+ 自有 body 体系（现为 v5：v3 歌曲图片 / v4 对唱 / v5 下一行辅助文字）**，移植应落到 CN+ v6 |
| `99ba119` 项 #2 | 2026-09-28 | **可配置开场时长**（`songIntroDurationMs`，默认 5000 ms）：2–30 秒，或 `-1` = 不限时（开场持续整个间奏，直到歌词占行才结束）。`SongMetadataIntroPolicy.durationMs` 改为可变 + `setDurationMs`，每次投影重新下发（改设置无需重启）；切歌区内新增滑块（含「保持不限时」档）；ConfigBackupCodec；strings | ⏳ 未移植 |
| `99ba119` 项 #3 | 2026-09-28 | **wire 解码拒绝原因具名**：`AodStateWireDecodeOutcome`（Decoded/Rejected）+ `decodeRejectReason`，命名 `protocol_mismatch` / `invalid_scalars` / `missing_body` / `undecodable_body` / `unknown_kind`；拆出 `AodStateWireBundleCodec.envelopeFromBundle`；`AodLyricClient` 记录原因，使「app/hook 协议错位（会自愈）」与真实损坏可区分 | ⏳ 未移植。CN+ 编解码自成一套 → 把 outcome 类型 + 闸门名并入 CN+ 自己的解码闸门 |
| `99ba119` 项 #4 | 2026-09-28 | **原厂时钟/图片带按实际所在侧预留**：新增纯函数 `aodStockClockReserve` / `AodStockReserve` 供 `AodSurfaceController` 使用；顶部带时钟现在预留顶部（此前恒预留底部，导致原厂时钟不在底部的机型把歌词钉在挖孔下方） | ⏳ 未移植 |
| `99ba119` 项 #5 | 2026-09-28 | **AOD 唤醒 broker 加固**：`AodWakeAvailability`（ready/interactive/no_host/no_method/no_power_manager）+ resolve/adopt/report/detail 辅助函数；电源管理器在 SystemUI `onCreate` 由 context 预置；`adoptHost` 接缝由 AOD 可见性 hook 供给（早于 hook 创建的插件实例不会走构造器）；唤醒方法在安装时解析；按「不同原因」各报一次的去重闩锁，`no_host` 附带安装器跳过原因汇总 | ⏳ 未移植。**CN+ broker 已深度分叉**（WeakReference 宿主、Lyricon 看门狗、安装重试、候选类/字段/方法名）→ 仅按概念移植 |
| `99ba119` 项 #6 | 2026-09-28 | **`Word` 文档计入计时**：`AodProjectionEngine.isTimedDocumentType` / `isEffectiveLineLevelSync` 接受 `Word`；keepalive 规范放宽为 `Line`/`Word`/`Syllable` | ⏳ 未移植（CN+ 目前仅 Line/Syllable） |
| `99ba119` 项 #7 | 2026-09-28 | **`AodPositionHook.readNumericField`**：按装箱 `Number` 读取，避免 int/float 字段宽度不一致（已普查机型上 `mTranslationY` 为 int、`mTranslationYStep` 为 float）在 `runCatching` 内抛异常——该异常此前静默禁用了托管位移**与**原厂控件保持 | ⏳ 未移植（真实缺陷修复） |
| `99ba119` 项 #8 | 2026-09-28 | **诊断 root 探测加固**：`id -u` 用 `ROOT_PROBE_TIMEOUT_MS = 15 s`（root 管理器授权弹窗需要更长窗口）+ `runFirstRootBinary` 依次尝试 `PATH` 的 `su` 与绝对路径（`/system/bin/su`、`/system/xbin/su`、`/sbin/su`、`/debug_ramdisk/su`、`/data/adb/ksu/bin/su`、`/data/adb/ap/bin/su`）；仅「无法 spawn」才继续下一个 | ⏳ 未移植 |
| `99ba119` 项 #9 | 2026-09-28 | **子句标点断行加权**：`balancedChunkRanges(..., breakAfter)` + `PUNCTUATION_BREAK_BONUS_FRACTION = 0.2` + `endsWithClausePunctuation`，应用于次要计时行、token 行与词块（在不增加行数的前提下，优先在子句标点后断行） | ⏳ 未移植（取代此前暂缓的 `2885511` 项 #3 行） |
| `99ba119` 项 #10 | 2026-09-28 | **移除对唱槽位记忆**：删除 `episodeDuetGenerations` / `duetEnded`，`freshSolo = orderedIds.size == 1`；同一首歌之后每次独唱都使用配置的自由锚点 | ➖ 代码不适用：CN+ 从无该槽位记忆（对唱随 PR #118 保持主行原位）；仅规范文字有别 |
| `99ba119` 项 #11 | 2026-09-28 | **上游文档同步**：ARCHITECTURE（标点子句断行；更正——Android **不会**自动重连 bound-service 客户端，重连是生产者客户端的职责，provider 传输除外）；LOCKSCREEN_AOD_BEHAVIOR_SPEC（自由锚点独唱 / 原厂带预留 / 开场时长滑块 / `Line`,`Word`,`Syllable` keepalive / 唤醒 broker 接缝 + 具名故障 / stacked-single 布局 / 能力 ≠ 可达 / 具名 wire 拒绝）；DIAGNOSTIC_REPORTING_SPEC（root 探测） | ⏳ 未同步——CN+ `docs/` 仍是 `99ba119` 之前的文本（如 ARCHITECTURE.md 仍写着自动重连行为） |
| `99ba119` 项 #12 | 2026-09-28 | 版本号 204/0.3.178 → 216/0.3.191 | ➖ 不适用（CN+ 独立版本号体系） |
| `99ba119` 项 #13 | 2026-09-28 | 测试：新增 `AodWakeBrokerRecoveryTest`，并在 AodCanvasLayoutTest / AodPositionUpdateTest / AodStateWireCodecTest / AodStateProjectorTest / SongMetadataIntroPolicyTest / DiagnosticCaptureCollectorTest / AodRenderPreferencesTest / SceneCompilerTest / ConfigBackupCodecTest / AodProjectionLifecycleTest / SpicyBridgeDocumentTest 增补 | ⏳ 随移植落地 |

**有用性评估（2026-10-02，逐项对照当前 CN+ 代码）**

- **高价值 —— 建议优先移植：**
  - **项 #7 `readNumericField`**：CN+ [readClockGeometry](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodPositionHook.kt#L698-L707) 仍用 `getFloat` 读 `mTranslationY`，而 CN+ 自己在已普查固件上已确认该字段是 **int**（issue #66，写入侧修复）。读取会在 `runCatching` 内抛异常，导致 geometry 恒为 null，托管位移**与原厂控件保持**静默失效。改动极小、零风险，直接恢复功能。
  - **项 #5 唤醒 broker 加固（按概念移植）**：CN+ 只在 [DozeTriggers 构造器 hooker](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodWakeBroker.kt#L320-L343) 捕获宿主，早于 hook 创建实例则永不捕获（重试只是重挂 hook）。[VisibilityTelemetryHooker](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodLifetimeHook.kt#L134-L147) 已拿到 `DozeHost`（`chain.thisObject`），正是 broker 需要的宿主，可作 `adoptHost` 接缝。电源管理器改由 SystemUI `onCreate` 的 [Application](file:///workspace/app/src/main/java/com/eza/hyperglow/root/SystemUiLifecycleHook.kt#L50-L57) 预置，可让 Lyricon 看门狗不依赖 `DozeTriggers` 实例。具名故障 + 安装跳过汇总让目前唯一的 "AOD wake host unavailable" 警告可诊断。
  - **项 #8 诊断 root 探测加固**：CN+ 仅 `ProcessBuilder("su", ...)` + 5s（`COMMAND_TIMEOUT_MS`）；KernelSU/APatch 的 `su` 常在 `PATH` 外，且授权弹窗 >5s，探测失败 → 诊断误报。加绝对路径回退 + 15s 探测窗口，成本低。
- **中等价值 —— 视需要：**
  - **项 #1 歌曲信息布局 + 换行而非缩小**：CN+ [layoutMetadataLines](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/LyricLayoutEngine.kt#L176-L203) 上限 3 行、溢出丢弃，`AodCanvasModel` 在内容超出画布时整体缩放；上游改为继续换行到更多行。`stacked`/`single` 对 CN+ 是新增选项（CN+ 默认已是 ` · ` 单行 + 逐槽分隔符，接近上游 `single`）。用户可见，但需贯通 CN+ 自有 wire/自定义/预览层。
  - **项 #2 开场时长可配置**：CN+ 固定 3000ms，且结构已分叉（无 `openingResolved`/`provisional`）；移植需调和 `-1`（不限时）语义与 CN+ 的 `availableInterludeMs >= durationMs` 门槛。属偏好功能。
  - **项 #3 wire 解码具名拒绝**：CN+ 现只记笼统 "Rejected invalid/malformed state payload"；把闸门名并入 CN+ 自有解码，可低成本区分「app/hook 协议错位（自愈）」与真损坏。
  - **项 #9 子句标点断行加权**：CN+ 有同构的 `balancedChunkRanges`（[AodCanvasLineLayout](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodCanvasLineLayout.kt#L47)）可加 `breakAfter`；纯排版观感优化。
  - **项 #6 `Word` 文档计入计时**：CN+ 文档 `type` 来自桥接载荷，来源可能标 `Word`；目前 `isTimedDocumentType` 只认 Line/Syllable，`Word` 会被当作 UNSYNCED。低成本健壮性。
- **低价值 / 暂缓：**
  - **项 #4 原厂时钟带按侧预留**：CN+ 并非「恒预留底部」，而是用 [avoidStockClockOverlap](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodSurfaceController.kt#L1678-L1697)（重叠时把歌词下移），上游所指缺陷未必存在于 CN+；建议先在顶部时钟机型上复现再定。
  - **项 #11 上游规范文档同步**：价值在文档准确性（尤其「Android 不会自动重连」更正），但应跟随代码结论；可随移植附带。
  - **项 #13 测试**：随对应功能落地。
- **不适用：** 项 #10（CN+ 从无对唱槽位机制）；项 #12（CN+ 独立版本号）。

## 未同步 / 未纳入

> 已评估、有意不移植（或不适用），附原因；按需再评估。日期列为上游提交日期。

| 上游提交 | 日期 | 内容 | 原因 |
|---|---|---|---|
| `748912e` 项 #4 剩余 | 2026-09-11 | `SettingsSession`/`PreferenceSettingsStore` 设置会话重构 | ⏸ 暂缓：耦合未移植的 MainActivity tab 化重构（CN+ 有自有设置布局）；与下方 `0424ae9` 行同一暂缓 |
| `748912e` 项 #11 | 2026-09-11 | wire 主体协议 v1→v9 跨版本编解码 | ⏸ 暂缓：上游 v1→v9 编解码未采用；特性 #5（对唱）已由 CN+ 自有 wire body v4 承载（2026-09-30，PR #118）——采用其余特性时再对齐版本 |
| `2885511` 项 #3 | 2026-09-14 | 书写体系标点归附（`aodPunctuationAttachToPrevious`/`Next`、`attachAodPunctuationGroups`）+ 词块打包计入渲染分隔符 | ⏸ 可选：低风险换行润色；独立中日文/拉丁标点换行观感不佳时再移植 |
| `2885511` 项 #5 | 2026-09-14 | 对唱区块结束 → 剩余独唱回落居中（`shouldRecenterAfterDuet`/`duetEnded`/`wasDuet`） | ⏸ 仍未移植：PR #118（2026-09-30）以 v1 简化带来并发行（锚顶布局主行保持原位，无槽位继承机制），对唱结束回落居中尚无对应——若双行版式漂移再随槽位机制移植 |
| `2885511` 项 #6 | 2026-09-14 | 版本号 0.3.173 → 0.3.177 | ➖ 不适用（CN+ 独立版本号体系） |
| `2885511` 项 #7 | 2026-09-14 | 文档：ARCHITECTURE hook 符号解析；LOCKSCREEN_AOD_BEHAVIOR_SPEC 共享裁剪 + padding | ⏸ 未同步（2026-09-28 核查：两份文档均未涉及）；下次触碰相关区域时补齐 |
| `0424ae9` 剩余 | 2026-08-22 | `SettingsSession`；LucideIcons；RTL 歌词渲染（AodTextDirection/物理对齐换算/drawDirectionalText）；hideFromRecents | ⏸ 暂缓：SettingsSession 牵扯未移植的 MainActivity 重构（见 `748912e` 项 #4）；RTL 对 CN 用户价值低 |
| `ced2769` 剩余 | 2026-08-13 | DiagnosticsScreen 简化；AodKeepaliveRegressionTest | ⏸ 暂缓：UI 简化不适用（CN+ DiagnosticsScreen 结构不同）；postHandoff 诊断已移植 |
| `cc1f62f` 剩余 | 2026-08-15 | 文档传输宽限（`scheduleDocumentClear`/`DOCUMENT_TRANSPORT_GRACE_MS`）；`spicyBridgeDocumentMismatch` 引擎侧清除；`logKeepAliveEdge`/`logAodEnabledEdge` | ⏸ 暂缓：传输宽限结构性不适用（CN+ 文档由生产者主动 clear）；mismatch 调用路径 CN+ 不存在；出现对应 issue 再评估 `logKeepAliveEdge` |

## 本地增强（2026-09-16）：AOD 时钟钉住行为 + 垂直偏移

非上游提交——CN+ 用户需求，叠加在「锚定时钟」功能上：
- **暂停/没有播放时不再钉住锚定的系统时钟位置**——`AodSurfaceController.applySuppressionAndRotation` 现在计算 `pinClock = renderable && playbackActive && !currentAodProfile().aodClockFollow`，仅在锚定模式下且播放活跃时钉住时钟；暂停/无播放时时钟回到系统位置、随防烧屏正常移动。
- **关闭「实时跟随系统时钟」时显示时钟垂直偏移滑块**——新增 `aodClockYOffset` 偏好（px，−480..480，负值上移/正值下移），贯通 `AodRenderPreferences` / `CompiledCustomization`（`CustomizationModels`）/ `RuntimeCustomization`（`DiagnosticLogging`）/ `ConfigBackupCodec`；`AodPositionHook.setClockYOffset(px)` 把偏移叠加到被钉住的时钟 Y（经 `lastStockTranslationY` 锚点，避免逐帧累积）；仅当跟随开关关闭时显示。

## 同步时的操作流程

1. `git fetch upstream main`（remote `upstream` = https://github.com/amarinne/hyperglow ，已配置）
2. 对照上方「已同步 / 已包含」与「未同步 / 未纳入」两表，逐个 `git show <sha>` 审查改动
3. 按内容手工移植到 CN+（注意 CN+ 已深度分叉：Lyricon 生产器栈、版本号、CN 音乐应用适配）
4. 版本号不跟随上游（CN+ 独立体系）；UpdateChecker/VersionCheck 功能二选一
5. 移植后跑 CI（554+ 测试），全绿后推送
6. **更新本文件**：把该项从「未同步」表移入（或新增到）「已同步」表，并更新基线日期
