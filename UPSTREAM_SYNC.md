# 上游同步状态（amarinne/hyperglow → CN+）

# English / 英文

> Purpose: whenever the user asks to "sync upstream updates", read this file first to learn the
> current sync baseline, then compare it against the list of new upstream commits to decide what
> needs to be ported. The two repositories have completely independent git histories (CN+ is a
> repackaged standalone fork), so a direct merge is impossible — changes can only be selected
> and ported manually by content.

## Current Status

- **CN+ version**: 0.3.167 (194) as of 2026-10-07; the upstream baseline was advanced to `2eaa191` (v0.3.212) on 2026-10-18 — every item of the `fefe5c54 → 2eaa191` delta is disposed in the tables below and `.github/upstream-baseline.txt` now records `2eaa191`.
- **Baseline (last fully disposed increment)**: 2026-10-16 `2eaa191`, version 0.3.212 (237) — fully disposed in the 2026-10-18 pass (PR #273): all 5 commits ported, see the `Ported — fefe5c54 → 2eaa191` section below. The previous baseline 2026-10-05 `fefe5c54`, version 0.3.199 (224), was fully disposed in the 2026-10-10 pass: item #2 ported (PR #251), item #1 deferred (inert without the producer side), item #3 landed partially (the spec text for the join rule), item #4 not applicable. The same pass disposed of `84a0c9ce` (vC221 / 0.3.196): items #2/#3/#4 ported, item #1 evaluated as unaffected, #5 landed partially, #6 not applicable.
- **Upstream, previous increment**: 2026-09-28 `99ba119`, version 0.3.191 (216) — fully disposed in the 2026-10-10 pass: items #1/#9/#11 ported and #13 landed with the batch, item #2 deliberately not ported (replaced by the local long-interlude countdown-dots feature), item #4 deferred, items #10/#12 not applicable.
- **Newest synced item**: the 2026-10-18 batch — every item of `fefe5c54 → 2eaa191` (`1d45ade`, `2c8cf74`, `5ecb5df`, `f7a8287`, `2eaa191`) ported (PR #273); preceded by the 2026-10-10 batch — `99ba119` items #1/#9/#11, `84a0c9ce` items #2/#3/#4, `fefe5c54` item #2, plus the local countdown-dots enhancement. See their rows in the tables below.
- **Upstream status (2026-10-18)**: upstream `2eaa191` (2026-10-16, v0.3.212 / versionCode 237) is now the recorded baseline — the 5-commit delta (+1749/−212 over 46 files) from `fefe5c54` (v0.3.199) was ported in PR #273, so `.github/upstream-baseline.txt` now records `2eaa191` and every row of the delta is disposed in the section below.
- **Upstream repository**: https://github.com/amarinne/hyperglow (default branch: main)
- Baseline verification marks (2026-09-05): AodLyricBridgeService already includes dynamic uid matching,
  HierarchyFields.kt and its use across all hooks, missingProbeNames, and miuix via the public Maven Central repository

## Synced / Included

> Date column = the upstream commit date; the date a change landed in CN+ is in the status column.

| Upstream commit | Date | Content | Status |
|---|---|---|---|
| `fefe5c54` item #2 | 2026-10-05 | **Backing-vocal join rule**: `joinsConcurrentScene` replaces the bare `overlap ≥ MIN_CONCURRENT_OVERLAP_MS` test — an explicit backing-vocal row joins on any positive shared window (short authored responses are no longer swallowed by the one-second gate), while incidental overlaps between lead rows keep the gate | ✅ Ported (2026-10-10, PR #251). CN+ adaptation: the judgement lands in the source-agnostic pure functions (`producer/DuetConcurrent.kt`) rather than upstream's document store — CN+ had already refactored it there; `DuetLineWindow` gains `isBackground` (Spicy `role=BACKGROUND`, plugin `isHarmonyRow`; role-less sources keep every row on the one-second gate); the plugin `accompanimentRowIndex` (same-sentence harmony attached to the active line) moves to positive overlap with it. Intentional divergence: the adoption/lock gate stays at ≥1 s (owner 2026-10-07 — a short response must not interrupt a line that is still singing). Tests: `DuetConcurrentTest` +5, `PluginDuetConcurrentTest`/`PluginVoiceSlotTest` regressions green |
| `84a0c9ce` item #4 | 2026-10-05 | **`android:forceQueryable="true"`**: lets Spotify discover the module's bridge service and provider without changing Spotify's manifest | ✅ Ported (2026-10-10, PR #250). CN+ finding: per AOSP `AppsFilterImpl` the flag is only honored when the declaring package is system-installed, so it serves a priv-app/preinstalled install and changes nothing for a user-installed module — declared to match upstream and documented in the manifest comment; package visibility still grants no access (both endpoints validate caller UID) |
| `99ba119` item #9 | 2026-09-28 | **Clause-punctuation wrap bonus**: `balancedChunkRanges(..., breakAfter)` + `PUNCTUATION_BREAK_BONUS_FRACTION = 0.2` + `endsWithClausePunctuation` — within the minimal line count a break after clause punctuation costs less than an arbitrary split, so phrases break at commas and sentence ends when that needs no extra line | ✅ Ported (2026-10-10, PR #249). CN+ adaptation: lands in CN+'s own pure layout files (`AodCanvasLineLayout.kt` plus the word-chunk call site in `LyricLayoutEngine.kt`) instead of upstream's single canvas file; line-count invariance triple-checked (greedy count, last-line discount skip, width guard); 4 new cases in `AodCanvasLayoutTest` plus a constant-zeroed negative control, verified offline |
| `99ba119` item #1 + `84a0c9ce` items #2+#3 | 2026-09-28 / 2026-10-05 | **Song-info layout `stacked`/`single` + wrap-instead-of-shrink + stacked artist size + mixed-size line boxes**: per-surface `metadataLayout` (`stacked` = assembled with the surface's own per-slot separators, the historical default; `single` = one middle-dot line sized by the title font), over-wide pieces wrap onto further lines instead of shrinking the block, `metadataArtistSizePercent` (40..100, default 80, stacked only, out-of-range falls back to the default), and per-line advance by previous descent + next ascent | ✅ Ported (2026-10-10, PR #254). CN+ adaptation: the layout rides CN+'s existing per-surface customization-store chain — **no wire change** (the earlier "would land as CN+ v8" note is superseded); `LyricLayoutTextLine` gains `pieceIndex` and `layoutMetadataLines` takes per-piece measurement ports; `mixedSizeLine*` live in `AodCanvasTextMetrics.kt` with offline tests; preview kept same-source |
| Local enhancement: long-interlude countdown dots | — | **Countdown dots during long interludes** (HyperLyric-style, replaces `99ba119` item #2): a gap ≥4 s between the line end and the next line start draws three dots in the lyric-row slot, lighting up and scaling (+40%, smoothstep) as the next line approaches and fading right-to-left from 60% progress; window start +1 s (mapped to 0 on a surface that shows the next line); intro metadata keeps priority | ✅ Implemented (2026-10-10, PR #252): per-surface `interludeCountdown` (default on), CN+ wire body v10 carries the raw gap endpoints (`interludeStartMs`/`interludeEndMs`, the delay mapped per surface at the render-mapping layer), palette tokens for colors, preview same-source; `SongMetadataIntroPolicy` untouched. Tests: InterludeDotsTest (15) + wire/projector/scene/mapper additions, green offline |
| `99ba119` item #11 | 2026-09-28 | **Upstream doc sync**: ARCHITECTURE (punctuation phrase breaks; the bound-service reconnect correction); LOCKSCREEN_AOD_BEHAVIOR_SPEC (`Line`,`Word`,`Syllable` keepalive / wake-broker seams + named faults / stacked-single layout / capability ≠ reachability / named wire rejection / the local dots feature); DIAGNOSTIC_REPORTING_SPEC (root probe) | ✅ Synced (2026-10-10, PR #253), adapted: only text for items actually landed in CN+ (stock-band reserve, intro slider, ordinals and response credit stay out); both languages |
| `99ba119` item #13 | 2026-09-28 | Tests: `AodWakeBrokerRecoveryTest` + additions across the canvas/position/wire/projector/scene/backup/lifecycle/bridge suites | ✅ Landed with the 2026-10-10 batch (per-item suites listed in their rows) |
| `84a0c9ce` item #5 | 2026-10-05 | Docs (ARCHITECTURE: `forceQueryable` + body v11 source ordinals; LOCKSCREEN_AOD_BEHAVIOR_SPEC: co-timed section identity, mixed-size stack) + tests | ✅ Partial (2026-10-10): the `forceQueryable` and mixed-size-stack docs are synced (PR #253); the body-v11 ordinal text is intentionally absent (item #1 not ported); tests landed with items #2/#3 |
| `fefe5c54` item #3 | 2026-10-05 | Docs (LOCKSCREEN_AOD_BEHAVIOR_SPEC: response credit outro, backing-vocal overlap wording) + tests (ResponseCreditOutroPolicyTest, ShortBackingVocalTest, …) | ✅ Partial (2026-10-10): the backing-vocal overlap wording is synced (PR #253); the response-credit section is intentionally absent (item #1 deferred); ShortBackingVocal coverage landed with item #2 (PR #251) |
| `99ba119` item #5 | 2026-09-28 | **AOD wake-broker hardening**: `AodWakeAvailability` (ready/interactive/no_host/no_method/no_power_manager) + `resolveAodWakeAvailability` / `shouldAdoptAodWakeReference` / `shouldReportAodWakeUnavailable` / `aodWakeUnavailableDetail`; `adoptHost` seam fed by the AOD visibility hook (a plugin instance created before the hook never runs the constructor); power manager seeded from a context at SystemUI `onCreate`; the wake method resolved at install; per-distinct-reason latch, with the installer-skip summary appended to `no_host` | ✅ Ported (2026-10-03, PR #147), concept-level as planned. CN+ adaptation: the constructor hooker now hands the host to `adoptHost` and only the `VisibilityTelemetryHooker` seam (`DozeHost.setAodVisibility`, `chain.thisObject`) feeds a pre-existing instance; `observeContext` is seeded from `SystemUiLifecycleHook.bootstrap` so the Lyricon watchdog no longer needs a `DozeTriggers` instance; the host stays a `WeakReference` (CN+ divergence kept — avoids `DozeTriggers$TriggerReceiver` leakage); CN+'s retry/urgent/pickup machinery is untouched. New `AodWakeBrokerRecoveryTest` (7 cases, pure functions) |
| `99ba119` items #8+#3+#6 | 2026-09-28 | **First port batch**: ① diagnostics root-probe hardening (`ROOT_PROBE_TIMEOUT_MS = 15 s` for `id -u`; `runFirstRootBinary` tries `su` on `PATH` then six absolute locations, and only an un-spawnable binary falls through); ② named wire-decode rejection reasons (`AodStateWireDecodeOutcome` + `decodeRejectReason`: protocol_mismatch / invalid_scalars / missing_body / undecodable_body / unknown_kind; `AodStateWireBundleCodec.envelopeFromBundle` split out; `AodLyricClient` logs `reason=`); ③ `Word` documents count as timed (`isTimedDocumentType` / `isEffectiveLineLevelSync` in `AodProjectionEngine` **and** the producer's private copies), and `hasActualLyricTiming` no longer counts INTERLUDE-only rows | ✅ Ported (2026-10-03, PR #146). CN+ adaptation: the gate names land in CN+'s own decode gates; the interlude exclusion is applied to both `hasActualLyricTiming` copies (the engine's test-retained helper and the producer's production path — the earlier `748912e` item #6 note claimed an equivalent guard existed, but the code had none); tests added to DiagnosticCaptureCollectorTest / AodStateWireCodecTest / AodProjectionLifecycleTest / SpicyBridgeDocumentTest |
| `99ba119` item #7 | 2026-09-28 | **`readNumericField` in `AodPositionHook`**: read the boxed `Number` so an int/float field-width mismatch (`mTranslationY` int vs `mTranslationYStep` float on surveyed builds) no longer throws inside `runCatching` — the throw had silently disabled managed position **and** the stock-widget hold | ✅ Ported (2026-10-02, PR #136, before this table was written): CN+ `readClockGeometry` reads through `readNumericField`/`readIntField`/`readFloatField` and the write side goes through `writeNumberField` (issue #66) |
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
| `748912e` item #5 (+ `duetEnabled` from #10) | 2026-09-11 | Duet / concurrent second sung line: primary + overlapping companion (≥1 s shared window, pure time-overlap) stacked on the AOD canvas and the lockscreen card (CN+ extends upstream's AOD-only scope), each with its own karaoke sweep | ✅ Ported (2026-09-30, PR #118): selection precomputed producer-side per CN+'s no-row-selection-at-projection contract (`selectDuetLineIndex` three-stage semantics; Spicy/Lyricon/LyricInfo; SuperLyric excluded), per-surface `duetConcurrent` switch, CN+ wire body v4 carries `duetLine`; upstream slot-inheritance / per-section transitions and the v1→v9 wire codec intentionally simplified — see SPEC |
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

## Ported — `fefe5c54 → 2eaa191` (2026-10-18, PR #273)

> Upstream advanced from the recorded baseline `fefe5c54` (v0.3.199) to
> `2eaa19191f41f5106d47e74dafc14f032117e69c` (v0.3.212 / versionCode 237, 2026-10-16): **5 commits**,
> +1749/−212 over 46 files. `.github/upstream-baseline.txt` advances to `2eaa191` with PR #273.
>
> Commits: `1d45ade` (README only, +2) · `2c8cf74` (vC229 / 0.3.204, 2026-10-12 — docs/FAQ/diagnostic
> policy + the Spicy EX setup probe; 25 files, +776/−58) · `5ecb5df` (vC236 / 0.3.211, 2026-10-16 —
> bridge document identity + reducer tombstone removal, HyperOS 4 seams, named-seam logging,
> lockscreen pause-linger ownership; 22 files, +921/−151) · `f7a8287` (README only, +2/−2) ·
> `2eaa191` (vC237 / 0.3.212, 2026-10-16 — SurfaceEvidence once-per-process facts, application-hook
> candidate loop, symbol-log parameter types; 8 files, +97/−50).
>
> **Disposition (2026-10-18, PR #273) — all rows ported:** `5ecb5df` #1 (bridge document
> identity + duration tolerance) and #2 (reducer: a clear no longer retires the generation) ported
> with their tests; #3 (lockscreen pause-linger ownership) ported with a CN+ adaptation against the
> `retentionAnchor`/`edgeFor` gap branch; #4/#5 (HyperOS 4 seams + named-seam logging) ported,
> including the matching `XiaomiCapabilityResolver` five-argument `doAnimationToAod` probe;
> `2eaa191` #1/#4 (SurfaceEvidence + canvas `surfaceKind`), #5 (symbol-source log with parameter
> types), and #2/#3 (application-hook candidate pair + ordered method resolve) ported; `2c8cf74`
> (Spicy EX setup probe, report contract/policy/checklist UI/strings/ProGuard) ported with CN+
> wording; the FAQ "Spicy EX Lite/Full → Spicy EX" rename synced. #6 (AodProjectionEngine identity
> skew) and the README-only / version-bump rows are not applicable.

**`5ecb5df` item #1 — Bridge document identity + duration tolerance. ✅ Port directly — high value.**
`SpicyBridgeDocument.matches` stops requiring `producerId`/`generation` and decides on track URI plus
`documentDurationsAgree` (a document duration ≤ 0 is bounded by the state's measured duration; a real
one must be within `DOCUMENT_DURATION_TOLERANCE_MS = 1000`, because the producer rounds the track to
the whole second); `spicyBridgeDocumentTimingFault` bounds rows/fill/words against
`max(document, state)`; a new `spicyBridgeDocumentCommitFault` commits a document that arrives before
any accepted state (bounded by its own declared duration); `spicyBridgeDocumentMismatch` drops the
producer/generation arms. CN+ carries the exact strict gate verbatim —
[matches](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeDocumentStore.kt#L81-L83),
[timingFault](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeDocumentStore.kt#L111-L136),
[commit](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeDocumentStore.kt#L398-L421),
[accept](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeDocumentStore.kt#L273-L279)
— and `matches` is live on the producer path
([SpicyLyricProducer](file:///workspace/app/src/main/java/com/eza/hyperglow/producer/SpicyLyricProducer.kt#L161),
also DiagnosticReportFactory/PluginPipeline). The failure upstream fixes (a dropped document is never
re-requested, so the rest of the song projects untimed and keepalive falls back to the song-change
lease) applies to CN+ unchanged; keep CN+'s relaxed `fillEndMs` wording. Tests: the SpicyBridgeDocumentTest cases.

**`5ecb5df` item #2 — State reducer: a clear no longer retires the generation. ✅ Port directly — high value.**
`SpicyBridgeStateReducer` drops `tombstoneProducerId`/`tombstoneGeneration`; `clear` only drops the
held state and relies on the ordering already inside `accept`. CN+ carries the tombstone verbatim
([fields + accept gate](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeStore.kt#L154-L164),
[clear](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeStore.kt#L248-L257)),
so the same blackout is live here: the producer clears for a reason of its own, then resumes at the
cleared generation, and every later payload at or below it is rejected for the rest of the song.

**`5ecb5df` item #3 — Lockscreen pause-linger ownership. ⚠️ Port with CN+ adaptation.**
Only a card this pause episode already owns may arm the `After Spotify pauses` clock: the candidate
becomes `retained?.takeIf { it.pauseRetentionEligible }`, and the expiry runnable is re-derived per
retained card (`lockscreenPauseLingerRemainingMs` / `refreshPauseLingerExpiry`). CN+ has the same reuse
bug (`val candidate = retained ?: lastVisible?.let { … }`,
[LockscreenVisibilityPolicy](file:///workspace/app/src/main/java/com/eza/hyperglow/root/lockscreen/LockscreenVisibilityPolicy.kt#L97))
and the same inline `pauseLingerExpiry`
([LockscreenSurfaceController](file:///workspace/app/src/main/java/com/eza/hyperglow/root/lockscreen/LockscreenSurfaceController.kt#L78-L95),
[schedulePauseLingerExpiry](file:///workspace/app/src/main/java/com/eza/hyperglow/root/lockscreen/LockscreenSurfaceController.kt#L373-L388)),
but CN+ adds a `retentionAnchor`/`edgeFor` layer upstream does not have, so the guard must be
re-expressed against CN+'s gap branch (which already returns `pauseRetentionEligible=false`).
Symptom: a still-playing transport-gap card arms the pause clock at the gap edge and retires the
previous track's line a few seconds into an ordinary song change.

**`5ecb5df` items #4+#5 / `2eaa191` items #2+#3 — HyperOS 4 seams + named-seam logging. ⚠️ Medium (forward-looking); the logging half is cheap and portable.**
Three seam shapes for HyperOS 4 / Android 17: ① the application class is a candidate *pair*
(`SystemUIApplication` ≤ HyperOS 3, `SystemUIApplicationImpl` on HyperOS 4), resolved as a
(class, `onCreate`) pair, with a `SystemUIAppComponentFactoryBase#instantiateApplication` factory
fallback and **two independent installs** (a renamed application class no longer kills the
user-tracker hook); ② the lockscreen notification refresh falls back to the synthetic static
`access$updateNotificationState(controller, boolean)` accessor (the controller arrives as
`chain.args[0]`, not `thisObject`); ③ `doAnimationToAod` gains a five-argument candidate
`(boolean,boolean,boolean,boolean,AnimationTracker)` beside the three-argument form (Linkage +
ClockMorph). Supporting: `SymbolResolver.resolveMethodInOrder` (ordered exact candidates;
`resolveClassInOrder` was added then removed in vC237), and per-hook `missingSeam(...)` + `loaderTag(...)`
so a field log names the member that did not resolve, once per loader (CN+ currently returns
silently on every miss). CN+ has none of this —
[install](file:///workspace/app/src/main/java/com/eza/hyperglow/root/SystemUiLifecycleHook.kt#L19-L48)
is the old single-`try` form, the lockscreen hooks `onUpdateNotificationState` only, and every hook
has the bare `?: return`. **The logging half is a self-contained diagnostic win worth porting now;
the seam candidates are only verifiable on a HyperOS 4 device (upstream has none either — see the
`2c8cf74` FAQ).** Caveat if the seams are ported: upstream updated the hooks but left the capability
probe on the three-argument `doAnimationToAod`, so a port must also update
[XiaomiCapabilityResolver](file:///workspace/app/src/main/java/com/eza/hyperglow/root/capability/XiaomiCapabilityResolver.kt#L275-L288)
or the capability reports missing on the new ROM.

**`5ecb5df` item #6 — AodProjectionEngine identity-skew log. ➖ Not applicable.**
`logDocumentIdentitySkew` records producer/generation divergence for a held document. CN+'s engine
deliberately stopped reading the bridge stores
([AodProjectionEngine](file:///workspace/app/src/main/java/com/eza/hyperglow/aod/AodProjectionEngine.kt#L154))
and the document checks live in the producer — there is no held document to log against.

**`2eaa191` items #1+#4 — SurfaceEvidence + canvas `surfaceKind`. ⚠️ Medium, portable.**
A new `SurfaceEvidence` writes once-per-process facts on the always-on bootstrap channel
(`aod_surface_attached` / `…_disabled_capability|container|attach_failed`, `lockscreen_surface_attached|…`,
and `lyrics_first_frame_aod|lockscreen` when a frame actually draws a lyric block), and
`AodLyricCanvasView` gains a `surfaceKind` parameter. CN+ already has `LyricSurfaceKind` and
`HookLogger.bootstrap`, and both controllers publish `"Surface attached"` only on the
diagnostic-gated channel, so the unconditional bootstrap fact answers "did the surface take a lyric
on this ROM" without a reproduction window. The `lyrics_first_frame` site needs adapting to CN+'s own
draw path (`AodLyricCanvasView` ~4.8k lines, structurally diverged).

**`2eaa191` item #5 — SymbolResolver symbol-source log includes parameter types. ➖ Low value.**
`method:owner#name` becomes `method:owner#name(params)`. One-line diagnostic refinement; the same
commit's `resolveClassInOrder` removal is already folded into the seam item above.

**`2c8cf74` — Spicy EX setup probe + docs/FAQ/policy. ⚠️ Needs CN+ appraisal (probe); sync doc text for landed items only.**
① A new `SpicySetupEvidence` plus a fixed read-only root `SpicySetupProbe.java` reads the live LSPosed
database (Spicy EX registration/enabled and `com.spotify.music` scope for the current user) and only
the HyperGlow publishing boolean from Spotify's `SpotifyPlus.xml`, returning three status tokens
(`module`/`scope`/`publishing`); the report contract, setup policy, checklist UI, strings
(en + zh-rCN + template), and the proguard keep are extended. CN+ has none of it. The premise is
upstream-specific — "Spicy EX is the only shipped producer" and a missing Spicy EX is a **hard** setup
failure — while CN+ also runs Lyricon/SuperLyric/LyricInfo, so the hard-failure wording and the
checklist copy need a CN+ rewrite even though the per-user scope/publishing statuses remain useful.
② Docs: ARCHITECTURE (a clear does not retire the generation; document identity by track URI +
tolerance; **credit filtering is the producer's responsibility — no local filter**; the setup probe),
LOCKSCREEN_AOD_BEHAVIOR_SPEC (the same clear/identity text; no credit filter), DIAGNOSTIC_REPORTING_SPEC
+ DIAGNOSTIC_DATA_POLICY (the probe, the statuses, the "no replies" follow-up notice). Sync only the
paragraphs corresponding to items actually landed in CN+ — CN+'s
[ARCHITECTURE](file:///workspace/docs/ARCHITECTURE.md#L162) still reads "Explicit clear, generation
retirement, and disable discard both retained payloads". ③ The FAQ/README restructure (upstream
positioning incl. "check out the CN+ fork", a HyperOS 4 section, upstream-only scope wording) is
mostly N/A; only the "Spicy EX Lite or Full → Spicy EX" wording maps to CN+'s
[FAQ](file:///workspace/FAQ.md#L68).

**Not applicable rows:** `1d45ade` and `f7a8287` (README only); the version bumps `2c8cf74` vC229 /
0.3.204, `5ecb5df` vC236 / 0.3.211, `2eaa191` vC237 / 0.3.212 (CN+ has an independent version
scheme); and `5ecb5df`'s `AodLyricCanvasView` `DuetSectionId` `equals`/`hashCode` change (CN+ has zero
presence of the upstream duet machinery — see the `84a0c9ce` item #1 row).

**Port record (2026-10-18):** executed in the suggested order — #1/#2 (bridge correctness, with
tests) → #3 (lockscreen, with CN+ adaptation) → the #5 logging + #4 SurfaceEvidence → the `2c8cf74`
docs (for landed items) → the #4 seam candidates (HyperOS 4) were also ported, not deferred, together
with the five-argument `doAnimationToAod` capability probe. Full unit-test suite green except the
`root`-only `PluginInstallerTest` dex read-only assertion (environment: the sandbox runs as root, so
`canWrite()` reports true regardless of the read-only bit).

## Previous passes — all rows disposed (2026-10-10)

> The 2026-10-10 pass disposed of every row of the `1537c58 → fefe5c54` delta and moved them to the
> synced / not-synced tables with their outcomes; `.github/upstream-baseline.txt` was advanced to
> `fefe5c54`, so the upstream scan now reports only commits after it.
>
> Disposition (2026-10-10): `99ba119` #1 ported · #2 replaced by the local countdown-dots feature ·
> #4 deferred (not reproduced in CN+) · #9 ported · #11 synced · #13 landed · #10/#12 N/A.
> `84a0c9ce` #1 evaluated as unaffected (see Not Synced) · #2/#3/#4 ported · #5 partial · #6 N/A.
> `fefe5c54` #1 deferred (needs the producer side) · #2 ported · #3 partial · #4 N/A.
>
> Historical detection notes (superseded): detected 2026-10-02, upstream advanced from the recorded
> baseline `1537c58` (v0.3.178) to `99ba119` (2026-09-28, v0.3.191 / versionCode 216) — one squashed
> `update` commit, ~1457 insertions over 47 files; detected again 2026-10-07, upstream advanced to
> `fefe5c54` (v0.3.199 / versionCode 224, released 2026-10-05) — `84a0c9ce` (+760/−134 over 23
> files, vC221 / 0.3.196, no release tag of its own) and `fefe5c54` (+398/−39 over 14 files), plus a
> README-only `1d45adea`. The v0.3.199 release notes name six user-facing items: show
> SpicyLyrics.org lyrics, compact multiline tail credits, fix missing duet and backing vocal lines,
> show all credited artists and add artist text size control, improve song info spacing, and fix
> lyric connection after Spotify updates.

**Usefulness assessment (2026-10-02, cross-checked against the current CN+ tree)**

- **Ported since this assessment:** item #7 (2026-10-02, PR #136), items #8/#3/#6 (2026-10-03, PR #146) and item #5 (2026-10-03, PR #147, concept-level as advised — the visibility hook supplies `adoptHost`, `bootstrap` seeds the power manager, named faults replace the single latch) — see the synced table.
- **Medium value — as needed:**
  - **item #1 metadata layout + wrap-instead-of-shrink**: CN+ [layoutMetadataLines](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/LyricLayoutEngine.kt#L176-L203) caps at 3 lines and drops overflow, and `AodCanvasModel` scales the block when content exceeds the canvas; upstream wraps onto further lines instead. `stacked`/`single` is a new choice for CN+ (CN+ already defaults to a ` · ` single line with per-slot separators — close to upstream `single`). User-visible, but must thread CN+'s own wire/customization/preview layer.
  - **item #2 configurable intro length**: CN+ fixes 3000 ms and has diverged structurally (no `openingResolved`/`provisional`); porting must reconcile the `-1` (no cap) semantics with CN+'s `availableInterludeMs >= durationMs` gate. Preference feature.
  - **item #9 clause-punctuation wrap bonus**: CN+ has the same-shape `balancedChunkRanges` ([AodCanvasLineLayout](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodCanvasLineLayout.kt#L47)) to add `breakAfter`; pure typography polish.
- **Low value / defer:**
  - **item #4 stock-clock band reserve**: CN+ does not reserve a bottom band; it uses [avoidStockClockOverlap](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodSurfaceController.kt#L1678-L1697) (moves the lyric rect below the clock on overlap), so the upstream defect may not exist here — confirm on a top-clock device first.
  - **item #11 upstream spec sync**: value lies in doc accuracy (notably the "Android does not auto-reconnect" correction) but it should follow the code conclusions; do it alongside a port.
  - **item #13 tests**: land with their features.
- **Not applicable:** item #10 (CN+ never had duet slot memory); item #12 (CN+ version numbering).

**Usefulness assessment (2026-10-07, `99ba119` → `fefe5c54` increment, read from the patches)**

- **Worth porting as-is:** `fefe5c54` item #2 — the backing-vocal join rule is a few lines against a gate CN+ carries verbatim. `84a0c9ce` items #2/#3 — artist size plus mixed-size line boxes are a self-contained, user-visible song-info improvement, and CN+ has the same layout code shape to hang them off.
- **Needs the producer first:** `fefe5c54` item #1 (response credit) does nothing until a document field CN+ does not carry reaches the projector; port the decode and wire path first, or skip the whole item.
- **Re-confirm before porting:** `84a0c9ce` item #1 (source row ordinals). CN+ dropped the upstream duet machinery in PR #118 and owns a different wire scheme, so the failure it fixes must be reproduced on CN+ first.
- **Cheap and independent:** `84a0c9ce` item #4 (`forceQueryable`) — a one-line manifest question, verify against CN+'s own bridge discovery.
- **Not applicable:** the two version rows (`84a0c9ce` item #6, `fefe5c54` item #4).

**Usefulness assessment (2026-10-10, execution record)**

- **Ported**: `99ba119` #1/#9/#11, `84a0c9ce` #2/#3/#4, `fefe5c54` #2, plus the local countdown-dots enhancement replacing `99ba119` #2 — per-row detail in the synced table.
- **Evaluated, not ported — `84a0c9ce` #1 (source row ordinals)**: CN+ does not share the failure. The upstream section-identity machinery (`DuetSectionId` / `assignDuetSlots` / `placeDuetSectionTops` / `AodLineTransitionKey` / `layoutEquivalent`) has zero presence in CN+ (grep-verified); the observed role-swap symptom was already fixed at the source by #241/#242/#245 (`assignVoiceSlots` pins the voice slot before emission); and upstream's text-free identity key directly conflicts with CN+ #244's text-based `isSameLineTextUpdate` on real data (`乐鸣东方` 3:40 — same text, a new window instance must animate) — copying it would regress #244. Residual value recorded: (a) the duet-lock window judgement is wider than the `agent` identity CN+ already has — prefer `agent` if a device report appears; (b) the primary-line correction replay-dissolve has a wire-free low-cost fix beside `isSameLineTextUpdate`. Analysis: `sync_upstream/ws4_roword_REPORT.md` (offline probe, 8 tests).
- **Deferred**: `99ba119` #4 — CN+ shifts the lyrics down on overlap (`avoidStockClockOverlap`) and the upstream defect was not reproduced; revisit on a top-clock device. `fefe5c54` #1 — inert without the producer side (CN+ would need `responseCredit` in its document decode and wire first).

## Not Synced / Excluded

> Evaluated and deliberately not ported, or not applicable — with reasons; re-evaluate on demand.
> Date column = the upstream commit date.

| Upstream commit | Date | Content | Reason |
|---|---|---|---|
| `99ba119` item #2 | 2026-09-28 | Configurable song-intro length (`songIntroDurationMs`, 2–30 s or unlimited) | ⏸ Deliberately not ported (owner decision, 2026-10-10): the long-interlude presentation is served by the local countdown-dots feature instead (see Synced); the intro placeholder keeps its fixed 3 s |
| `99ba119` item #4 | 2026-09-28 | Stock clock-or-image band reserved on the side it actually occupies | ⏸ Deferred (2026-10-10): CN+ does not reserve a bottom band — `avoidStockClockOverlap` shifts the lyrics down on overlap — and the upstream defect was not reproduced; revisit on a top-clock device |
| `84a0c9ce` item #1 | 2026-10-05 | Co-timed section identity from source row ordinals (wire v11) | ⏸ Evaluated 2026-10-10, not ported: CN+ has zero presence of the upstream machinery; the symptom is fixed at the source by #241/#242/#245; the text-free identity key conflicts with CN+ #244's text-based `isSameLineTextUpdate` (would regress it). Residual value and the wire-free fix are recorded in the 2026-10-10 assessment; analysis in `sync_upstream/ws4_roword_REPORT.md` |
| `fefe5c54` item #1 | 2026-10-05 | Response credit outro (`responseCredit` + `ResponseCreditOutroPolicy`) | ⏸ Deferred (2026-10-10): inert without the producer side — CN+ would have to carry `responseCredit` through its own document decode and wire first |
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
5. After porting, run the full CI suite and push once everything is green
6. **Update this file**: move the item from the Not Synced table (or add a new row) into Synced / Included and update the baseline date

---

# 中文 / Chinese

> 用途：每次用户要求「同步上游更新」时，先读本文件了解已同步基线，
> 再对照上游新提交清单，判断哪些需要移植。
> 两仓库 git 历史完全独立（CN+ 为重打包独立版），无法直接 merge，
> 只能按内容手工挑选移植。

## 当前状态

- **CN+ 版本**：0.3.167 (194)（截至 2026-10-07）；上游基线已于 2026-10-10 推进到 `fefe5c54`（v0.3.199）——`1537c58 → fefe5c54` 增量的每一项均已在下方各表中处置，`.github/upstream-baseline.txt` 现记录 `fefe5c54`。
- **基线（已完整处置的上一增量）**：2026-10-05 `fefe5c54`，版本 0.3.199 (224) —— 2026-10-10 一轮全部处置：项 #2 已移植（PR #251）、项 #1 暂缓（缺生产端即惰性）、项 #3 部分落地（伴唱规则的规范文本）、项 #4 不适用。同轮处置 `84a0c9ce`（vC221 / 0.3.196）：项 #2/#3/#4 已移植、项 #1 评估为不受影响、#5 部分落地、#6 不适用。
- **上游上一个增量**：2026-09-28 `99ba119`，版本 0.3.191 (216) —— 2026-10-10 一轮全部处置：项 #1/#9/#11 已移植、#13 随批落地，项 #2 有意不移植（由本地「长间奏倒计时圆点」替代）、项 #4 暂缓、项 #10/#12 不适用。
- **最新已同步项**：2026-10-10 一批 —— `99ba119` 项 #1/#9/#11、`84a0c9ce` 项 #2/#3/#4、`fefe5c54` 项 #2，外加本地「倒计时圆点」增强；见「已同步」表。
- **上游已领先——增量已移植入工作树（2026-10-18）**：上游从基线 `fefe5c54`（v0.3.199）推进到 `2eaa191`（2026-10-16，v0.3.212 / versionCode 237）——5 个提交，46 文件 +1749/−212。该增量逐项已按「已移植（2026-10-18）」处置（见下方各节）；`.github/upstream-baseline.txt` 保持 `fefe5c54`，仅在移植 PR 落地后推进。
- **上游仓库**：https://github.com/amarinne/hyperglow（default branch: main）
- 基线核实标记（2026-09-05）：AodLyricBridgeService 已含 uid 动态匹配、
  HierarchyFields.kt 及全 hook 使用、missingProbeNames、miuix 走 Maven Central 公共仓库

## 已同步 / 已包含

> 日期列为上游提交日期；落入 CN+ 的日期见各状态列。

| 上游提交 | 日期 | 内容 | 状态 |
|---|---|---|---|
| `fefe5c54` 项 #2 | 2026-10-05 | **伴唱行加入规则放宽**：`joinsConcurrentScene` 取代裸判据 `overlap ≥ MIN_CONCURRENT_OVERLAP_MS`——显式伴唱行只要有正重叠即加入（作者编排的短应答不再被一秒门整段吞掉），非伴唱 lead 行之间偶然重叠仍守一秒门 | ✅ 已移植（2026-10-10，PR #251）。CN+ 适配：判定落在与源无关的纯函数（`producer/DuetConcurrent.kt`，CN+ 早已重构到此处）而非上游的文档存储；`DuetLineWindow` 增 `isBackground`（Spicy `role=BACKGROUND`、插件 `isHarmonyRow`；无角色源全部行守一秒门）；插件侧 `accompanimentRowIndex`（同句和声挂回活动行）同步改为正重叠。有意分歧：让位门槛保持 ≥1s（owner 2026-10-07「和声还没唱完就不换」——短应答不打断正在唱的行）。测试：`DuetConcurrentTest` +5，`PluginDuetConcurrentTest`/`PluginVoiceSlotTest` 回归绿 |
| `84a0c9ce` 项 #4 | 2026-10-05 | **`android:forceQueryable="true"`**：让 Spotify 无需改自身 manifest 即可发现本模块的 bridge service 与 provider | ✅ 已移植（2026-10-10，PR #250）。CN+ 发现：按 AOSP `AppsFilterImpl`，该标志只在声明方为系统安装时被读取——真正生效的是 priv-app/预装安装，对用户安装的模块不改变行为；声明与上游对齐并写入 manifest 注释；包可见性仍不授予访问（两端各自校验 caller UID） |
| `99ba119` 项 #9 | 2026-09-28 | **子句标点断行加权**：`balancedChunkRanges(..., breakAfter)` + `PUNCTUATION_BREAK_BONUS_FRACTION = 0.2` + `endsWithClausePunctuation`——在最小行数内，子句标点之后的断点代价低于任意切分 | ✅ 已移植（2026-10-10，PR #249）。CN+ 适配：落在 CN+ 自有纯布局文件（`AodCanvasLineLayout.kt` 与 `LyricLayoutEngine.kt` 的词块调用点），未回迁上游单文件布局；行数不变三重保证（贪心行数、末行折扣跳过、宽度前置守卫）；`AodCanvasLayoutTest` 4 条新用例 + 常量归零反向对照，均离线验证 |
| `99ba119` 项 #1 + `84a0c9ce` 项 #2+#3 | 2026-09-28 / 2026-10-05 | **歌曲信息布局 `stacked`/`single` + 换行而非缩小 + 堆叠歌手字号 + 混合字号行盒**：per-surface `metadataLayout`（`stacked`=按本面逐槽分隔符组装，历史默认；`single`=统一 ` · ` 并成一行、整行歌名字号），过宽切片换行到后续行而非缩小整块；`metadataArtistSizePercent`（40..100，默认 80，仅 stacked，越界回落默认）；行盒按「上一行 descent + 下一行 ascent」推进 | ✅ 已移植（2026-10-10，PR #254）。CN+ 适配：布局走 CN+ 既有 per-surface 自定义配置存储链——**不动 wire**（此前「应落 CN+ v8」的旧计划已被此设计取代）；`LyricLayoutTextLine` 增 `pieceIndex`，`layoutMetadataLines` 收 per-piece 测量端口；`mixedSizeLine*` 归位 `AodCanvasTextMetrics.kt` 并配离线单测；预览保持同源 |
| 本地增强：长间奏倒计时圆点 | — | **长间奏显示倒计时圆点**（HyperLyric 方案，替代 `99ba119` 项 #2）：本行 end 与下一行 start 空隙 ≥4s 时，歌词行槽位画三个圆点，随下一行临近逐点 smoothstep 点亮并放大（+40%），进度 60% 起最右先隐；窗口起点 +1s（本面显示下一行时映射为 0）；开场大元数据保持优先 | ✅ 已实现（2026-10-10，PR #252）：per-surface `interludeCountdown`（默认开），CN+ wire body v10 携带原始空隙两端（`interludeStartMs`/`interludeEndMs`，延迟在渲染映射层按面解析），取色走 palette token，预览同源；`SongMetadataIntroPolicy` 零改动。测试：InterludeDotsTest（15 例）+ wire/投影/场景/映射增补，离线全绿 |
| `99ba119` 项 #11 | 2026-09-28 | **上游文档同步**：ARCHITECTURE（标点子句断行；bound-service 重连更正）；LOCKSCREEN_AOD_BEHAVIOR_SPEC（`Line`,`Word`,`Syllable` keepalive / 唤醒 broker 接缝+具名故障 / stacked-single 布局 / 能力≠可达 / 具名 wire 拒绝 / 本地圆点特性）；DIAGNOSTIC_REPORTING_SPEC（root 探测） | ✅ 已同步（2026-10-10，PR #253），按实际落地甄别：仅抄已在 CN+ 落地的段落（原厂带预留、开场滑块、源行序号、片尾署名均不抄）；中英双语 |
| `99ba119` 项 #13 | 2026-09-28 | 测试：`AodWakeBrokerRecoveryTest` + 画布/位移/wire/投影/场景/备份/生命周期/桥接各套件增补 | ✅ 随 2026-10-10 批次落地（各套件见对应行） |
| `84a0c9ce` 项 #5 | 2026-10-05 | 文档（ARCHITECTURE：`forceQueryable` + body v11 源行序号；LOCKSCREEN_AOD_BEHAVIOR_SPEC：同刻行节身份、混合字号堆叠）+ 测试 | ✅ 部分落地（2026-10-10）：`forceQueryable` 与混合字号堆叠的文档已同步（PR #253）；body v11 序号文本有意缺席（项 #1 未移植）；测试随项 #2/#3 落地 |
| `fefe5c54` 项 #3 | 2026-10-05 | 文档（LOCKSCREEN_AOD_BEHAVIOR_SPEC：片尾署名一节、伴唱重叠措辞）+ 测试（ResponseCreditOutroPolicyTest、ShortBackingVocalTest 等） | ✅ 部分落地（2026-10-10）：伴唱重叠措辞已同步（PR #253）；片尾署名一节有意缺席（项 #1 暂缓）；ShortBackingVocal 覆盖随项 #2（PR #251）落地 |
| `99ba119` 项 #5 | 2026-09-28 | **AOD 唤醒 broker 加固**：`AodWakeAvailability`（ready/interactive/no_host/no_method/no_power_manager）+ `resolveAodWakeAvailability` / `shouldAdoptAodWakeReference` / `shouldReportAodWakeUnavailable` / `aodWakeUnavailableDetail`；`adoptHost` 接缝由 AOD 可见性 hook 供给（早于 hook 创建的插件实例不会走构造器）；电源管理器在 SystemUI `onCreate` 由 context 预置；唤醒方法在安装时解析；按「不同原因」各报一次的去重闩锁，`no_host` 附带安装器跳过原因汇总 | ✅ 已移植（2026-10-03，PR #147），按评估建议「按概念移植」。CN+ 适配：构造器 hooker 改为把宿主交给 `adoptHost`，只有 `VisibilityTelemetryHooker`（`DozeHost.setAodVisibility`，`chain.thisObject`）这条接缝能供出「早于 hook 的实例」；`observeContext` 由 `SystemUiLifecycleHook.bootstrap` 播种，Lyricon 看门狗不再依赖 `DozeTriggers` 实例；宿主保持 `WeakReference`（保留 CN+ 分叉，避免 `DozeTriggers$TriggerReceiver` 泄漏）；CN+ 的重试/紧急/拾取机制零改动。新增 `AodWakeBrokerRecoveryTest`（7 例纯函数） |
| `99ba119` 项 #8+#3+#6 | 2026-09-28 | **第一批移植**：① 诊断 root 探测加固（`id -u` 用 `ROOT_PROBE_TIMEOUT_MS = 15 s`；`runFirstRootBinary` 先试 `PATH` 的 `su`、再试六个绝对路径，仅「无法 spawn」才继续下一个）；② wire 解码拒绝具名（`AodStateWireDecodeOutcome` + `decodeRejectReason`：protocol_mismatch / invalid_scalars / missing_body / undecodable_body / unknown_kind；拆出 `AodStateWireBundleCodec.envelopeFromBundle`；`AodLyricClient` 记 `reason=`）；③ `Word` 文档计入计时（`AodProjectionEngine` 的 `isTimedDocumentType` / `isEffectiveLineLevelSync` **与** 生产者私有副本同步接受 `Word`），且 `hasActualLyricTiming` 不再把纯 INTERLUDE 行算作计时 | ✅ 已移植（2026-10-03，PR #146）。CN+ 适配：闸门名并入 CN+ 自有解码；间奏排除同时落到两处 `hasActualLyricTiming`（引擎的测试保留副本与生产者的生产路径——此前 `748912e` 项 #6 记的「等效已实现」在代码中并无对应守卫）；测试增补 DiagnosticCaptureCollectorTest / AodStateWireCodecTest / AodProjectionLifecycleTest / SpicyBridgeDocumentTest |
| `99ba119` 项 #7 | 2026-09-28 | **`AodPositionHook.readNumericField`**：按装箱 `Number` 读取，避免 int/float 字段宽度不一致（已普查机型上 `mTranslationY` 为 int、`mTranslationYStep` 为 float）在 `runCatching` 内抛异常——该异常此前静默禁用了托管位移**与**原厂控件保持 | ✅ 已移植（2026-10-02，PR #136，早于本表写成）：CN+ `readClockGeometry` 经 `readNumericField`/`readIntField`/`readFloatField` 读取，写入侧走 `writeNumberField`（issue #66） |
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
| `748912e` 项 #5（含 #10 的 `duetEnabled`） | 2026-09-11 | 对唱/并发第二歌行：主行 + 重叠并发行（共享窗口 ≥1s，纯时间轴判定）在息屏与锁屏卡片双行同显(CN+ 超出上游 AOD-only 范围)、各画各的逐字扫光 | ✅ 已移植（2026-09-30，PR #118）：依 CN+「投影不选行」契约改为生产者侧预计算（`selectDuetLineIndex` 三段式；Spicy/Lyricon/LyricInfo 三源，SuperLyric 不产出），per-surface `duetConcurrent` 开关，CN+ wire body v4 携带 `duetLine`；上游槽位继承/双段独立过渡与 v1→v9 编解码有意简化——见 SPEC |
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

## 已移植 —— `fefe5c54 → 2eaa191`（2026-10-18，待移植 PR 落地）

> 上游从已记录基线 `fefe5c54`（v0.3.199）推进到 `2eaa19191f41f5106d47e74dafc14f032117e69c`（v0.3.212 / versionCode 237，2026-10-16）：**5 个提交**，46 文件 +1749/−212。`.github/upstream-baseline.txt` 在移植 PR 落地前保持 `fefe5c54`。
>
> 提交：`1d45ade`（仅 README，+2）· `2c8cf74`（vC229 / 0.3.204，2026-10-12——docs/FAQ/诊断政策 + Spicy EX 环境探测；25 文件 +776/−58）· `5ecb5df`（vC236 / 0.3.211，2026-10-16——桥接文档身份+时长容差、状态 reducer 去墓碑、HyperOS 4 接缝、具名接缝日志、锁屏暂停驻留归属；22 文件 +921/−151）· `f7a8287`（仅 README，+2/−2）· `2eaa191`（vC237 / 0.3.212，2026-10-16——SurfaceEvidence 每进程一次事实、应用 hook 候选循环、符号日志带参数类型；8 文件 +97/−50）。
>
> **处置（2026-10-18）——全部行已移植入工作树：** `5ecb5df` #1（桥接文档身份 + 时长容差）与 #2（reducer：clear 不再退役 generation）连同测试一并移植；#3（锁屏暂停驻留归属）按 CN+ 适配移植，守卫改写为针对 `retentionAnchor`/`edgeFor` 的间隙分支；#4/#5（HyperOS 4 接缝 + 具名接缝日志）已移植，含配套的 `XiaomiCapabilityResolver` 五参 `doAnimationToAod` 探针；`2eaa191` #1/#4（SurfaceEvidence + 画布 `surfaceKind`）、#5（符号来源日志带参数类型）与 #2/#3（应用 hook 候选对 + 有序方法解析）已移植；`2c8cf74`（Spicy EX 环境探测、报告契约/政策/清单 UI/字符串/ProGuard）以 CN+ 措辞移植；FAQ 的「Spicy EX Lite/Full → Spicy EX」改名已同步。#6（AodProjectionEngine 身份偏移）与仅 README / 版本号行不适用。

**`5ecb5df` 项 #1 —— 桥接文档身份 + 时长容差。✅ 直接移植——高价值。**
`SpicyBridgeDocument.matches` 不再要求 `producerId`/`generation`，改由曲目 URI + `documentDurationsAgree` 判定（文档时长 ≤0 时以 state 实测时长为准；真实时长须落在 `DOCUMENT_DURATION_TOLERANCE_MS = 1000` 内，因为生产者把曲目长度取整到整秒）；`spicyBridgeDocumentTimingFault` 的行/填充/词边界改按 `max(document, state)` 约束；新增 `spicyBridgeDocumentCommitFault`，允许文档在 state 到达前提交（以自身声明时长约束）；`spicyBridgeDocumentMismatch` 去掉 producer/generation 两支。CN+ 原样带着同一套严格闸门——
[matches](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeDocumentStore.kt#L81-L83)、
[timingFault](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeDocumentStore.kt#L111-L136)、
[commit](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeDocumentStore.kt#L398-L421)、
[accept](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeDocumentStore.kt#L273-L279)
——且 `matches` 在生产者路径上是活的
（[SpicyLyricProducer](file:///workspace/app/src/main/java/com/eza/hyperglow/producer/SpicyLyricProducer.kt#L161)，DiagnosticReportFactory/PluginPipeline 亦用）。上游所修的故障（被丢弃的文档永不被重新请求→余下整首歌不计时、keepalive 回落到换歌租约）在 CN+ 原样成立；保留 CN+ 放宽的 `fillEndMs` 措辞。测试：同款 SpicyBridgeDocumentTest 用例。

**`5ecb5df` 项 #2 —— 状态 reducer：clear 不再退役 generation。✅ 直接移植——高价值。**
`SpicyBridgeStateReducer` 去掉 `tombstoneProducerId`/`tombstoneGeneration`；`clear` 仅丢弃持有状态，顺序判定仍由 `accept` 内部承担。CN+ 原样带着墓碑
（[字段+accept 闸门](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeStore.kt#L154-L164)、
[clear](file:///workspace/app/src/main/java/com/eza/hyperglow/bridge/SpicyBridgeStore.kt#L248-L257)），
因此同一黑屏故障在此成立：生产者因自身原因 clear 后又在被清除的 generation 上恢复，此后所有 ≤ 该 generation 的载荷整首歌都被拒。

**`5ecb5df` 项 #3 —— 锁屏暂停驻留归属。⚠️ 需 CN+ 适配后移植。**
只有本次暂停事件已拥有的卡片才能启动「Spotify 暂停之后」计时：候选改为 `retained?.takeIf { it.pauseRetentionEligible }`，到期 Runnable 按每张保留卡重新推导（`lockscreenPauseLingerRemainingMs` / `refreshPauseLingerExpiry`）。CN+ 有同样的复用缺陷（`val candidate = retained ?: lastVisible?.let { … }`，
[LockscreenVisibilityPolicy](file:///workspace/app/src/main/java/com/eza/hyperglow/root/lockscreen/LockscreenVisibilityPolicy.kt#L97)）
与同样的内联 `pauseLingerExpiry`
（[LockscreenSurfaceController](file:///workspace/app/src/main/java/com/eza/hyperglow/root/lockscreen/LockscreenSurfaceController.kt#L78-L95)、
[schedulePauseLingerExpiry](file:///workspace/app/src/main/java/com/eza/hyperglow/root/lockscreen/LockscreenSurfaceController.kt#L373-L388)），
但 CN+ 多了一层上游没有的 `retentionAnchor`/`edgeFor`，守卫须改写为针对 CN+ 的间隙分支（该分支已返回 `pauseRetentionEligible=false`）。症状：仍在播放的传输间隙卡在间隙边界上启动了暂停计时，于是普通换歌几秒后上一首的行即被退役。

**`5ecb5df` 项 #4+#5 / `2eaa191` 项 #2+#3 —— HyperOS 4 接缝 + 具名接缝日志。⚠️ 中价值（前瞻）；日志那一半便宜且可移植。**
HyperOS 4 / Android 17 的三处接缝形态：① 应用类改为候选**对**（≤HyperOS 3 为 `SystemUIApplication`，HyperOS 4 为 `SystemUIApplicationImpl`），按 (类, `onCreate`) 成对解析，并以 `SystemUIAppComponentFactoryBase#instantiateApplication` 工厂发现兜底，且**两次安装彼此独立**（应用类改名不再连带打死 user-tracker hook）；② 锁屏通知刷新回落合成静态访问器 `access$updateNotificationState(controller, boolean)`（控制器在 `chain.args[0]` 而非 `thisObject`）；③ `doAnimationToAod` 在三参形式旁新增五参候选 `(boolean,boolean,boolean,boolean,AnimationTracker)`（Linkage + ClockMorph）。配套：`SymbolResolver.resolveMethodInOrder`（有序精确候选；`resolveClassInOrder` 在 vC237 加入后又被移除），以及各 hook 的 `missingSeam(...)` + `loaderTag(...)`——每个 loader 报一次未解析的成员（CN+ 目前每次 miss 都是静默 return）。CN+ 全无此物——
[install](file:///workspace/app/src/main/java/com/eza/hyperglow/root/SystemUiLifecycleHook.kt#L19-L48)
仍是旧的单 `try` 形式，锁屏只 hook `onUpdateNotificationState`，每个 hook 都是裸 `?: return`。**日志那一半是自洽的诊断收益、值得现在就移植；接缝候选只有在 HyperOS 4 设备上才可验证（上游也没有——见 `2c8cf74` 的 FAQ）。** 若移植接缝需带一条警告：上游改了 hook 却把能力探针留在三参 `doAnimationToAod`，移植时须一并更新
[XiaomiCapabilityResolver](file:///workspace/app/src/main/java/com/eza/hyperglow/root/capability/XiaomiCapabilityResolver.kt#L275-L288)，
否则新 ROM 上能力会报缺失。

**`5ecb5df` 项 #6 —— AodProjectionEngine 身份偏移日志。➖ 不适用。**
`logDocumentIdentitySkew` 为持有文档记录 producer/generation 偏移。CN+ 引擎早已刻意不再读桥接存储
（[AodProjectionEngine](file:///workspace/app/src/main/java/com/eza/hyperglow/aod/AodProjectionEngine.kt#L154)），
文档检查落在生产者也——没有可记录的持有文档。

**`2eaa191` 项 #1+#4 —— SurfaceEvidence + 画布 `surfaceKind`。⚠️ 中价值，可移植。**
新增 `SurfaceEvidence` 在常开 bootstrap 通道上写每进程一次的事实（`aod_surface_attached` / `…_disabled_capability|container|attach_failed`、`lockscreen_surface_attached|…`，以及真正画出歌词块时的 `lyrics_first_frame_aod|lockscreen`），`AodLyricCanvasView` 增 `surfaceKind` 参数。CN+ 已有 `LyricSurfaceKind` 与 `HookLogger.bootstrap`，两个控制器都只在诊断门控通道上发 `"Surface attached"`，因此无条件 bootstrap 事实能在无复现窗口时就回答「本 ROM 上表面是否真的接管了歌词」。`lyrics_first_frame` 落点须适配 CN+ 自己的绘制路径（`AodLyricCanvasView` 约 4.8k 行，结构已分叉）。

**`2eaa191` 项 #5 —— SymbolResolver 符号来源日志带参数类型。➖ 低价值。**
`method:owner#name` 变为 `method:owner#name(params)`。一行诊断细化；同提交里 `resolveClassInOrder` 的移除已并入上面的接缝项。

**`2c8cf74` —— Spicy EX 环境探测 + docs/FAQ/政策。⚠️ 探测需 CN+ 评估；文档只抄已落地项。**
① 新增 `SpicySetupEvidence` 与固定只读的 root `SpicySetupProbe.java`，读取实时 LSPosed 库（Spicy EX 注册/启用状态、当前用户的 `com.spotify.music` 作用域）并只从 Spotify 的 `SpotifyPlus.xml` 取 HyperGlow 发布布尔，返回三个状态 token（`module`/`scope`/`publishing`）；报告契约、环境策略、清单 UI、多语言字符串（en + zh-rCN + 模板）与 proguard keep 一并扩展。CN+ 全无此物。其前提是上游特有的——「Spicy EX 是唯一随附生产者」、缺 Spicy EX 属**硬**环境失败——而 CN+ 还跑 Lyricon/SuperLyric/LyricInfo，故硬失败措辞与清单文案需按 CN+ 重写，尽管按用户的作用域/发布状态本身仍有用。② 文档：ARCHITECTURE（clear 不退役 generation；文档身份按曲目 URI + 容差；**署名过滤归生产者——无本地过滤器**；环境探测）、LOCKSCREEN_AOD_BEHAVIOR_SPEC（同样的 clear/身份文本；无署名过滤）、DIAGNOSTIC_REPORTING_SPEC + DIAGNOSTIC_DATA_POLICY（探测、状态、「不支持回复」跟进提示）。只抄对应已落地项的段落——CN+ 的
[ARCHITECTURE](file:///workspace/docs/ARCHITECTURE.md#L162)
仍写着「显式清除、generation 退役与禁用操作都会丢弃这两份保留的载荷」。③ FAQ/README 重构（上游定位，含「见 CN+ fork」、HyperOS 4 一节、上游专属作用域措辞）基本不适用；只有「Spicy EX Lite 或 Full → Spicy EX」的措辞对应 CN+ 的
[FAQ](file:///workspace/FAQ.md#L68)。

**不适用行：** `1d45ade` 与 `f7a8287`（仅 README）；版本号行 `2c8cf74` vC229 / 0.3.204、`5ecb5df` vC236 / 0.3.211、`2eaa191` vC237 / 0.3.212（CN+ 独立版本号体系）；以及 `5ecb5df` 的 `AodLyricCanvasView` `DuetSectionId` `equals`/`hashCode` 改动（CN+ 零存在上游对唱机制——见 `84a0c9ce` 项 #1 行）。

**移植记录（2026-10-18）：** 按建议顺序执行 —— #1/#2（桥接正确性，带测试）→ #3（锁屏，含 CN+ 适配）→ #5 日志 + #4 SurfaceEvidence → `2c8cf74` 文档（仅已落地项）→ #4 接缝候选（HyperOS 4）亦一并移植而非暂缓，连同五参 `doAnimationToAod` 能力探针。全量单测绿，仅 `root` 环境下的 `PluginInstallerTest` dex 只读断言失败（沙箱以 root 运行，`canWrite()` 无视只读位恒返回 true）。

## 历轮处置记录 —— 全部行已处置（2026-10-10）

> 2026-10-10 一轮把 `1537c58 → fefe5c54` 增量的每一行都处置完毕并移入「已同步 / 未同步」两表；`.github/upstream-baseline.txt` 已推进到 `fefe5c54`，上游扫描从此只报告其后的提交。
>
> 处置一览（2026-10-10）：`99ba119` #1 已移植 · #2 由本地倒计时圆点替代 · #4 暂缓（未在 CN+ 复现）· #9 已移植 · #11 已同步 · #13 随批落地 · #10/#12 不适用。`84a0c9ce` #1 评估为不受影响（见「未同步」）· #2/#3/#4 已移植 · #5 部分 · #6 不适用。`fefe5c54` #1 暂缓（缺生产端）· #2 已移植 · #3 部分 · #4 不适用。
>
> 历史侦测记录（已归档）：2026-10-02 侦测到上游从基线 `1537c58`（v0.3.178）推进到 `99ba119`（2026-09-28，v0.3.191 / versionCode 216）——单个压平的 `update` 提交，约 1457 行 / 47 文件；2026-10-07 再次侦测到推进到 `fefe5c54`（v0.3.199 / versionCode 224，2026-10-05 发布）——`84a0c9ce`（+760/−134，23 文件，vC221 / 0.3.196）与 `fefe5c54`（+398/−39，14 文件），外加只改 README 的 `1d45adea`。v0.3.199 的 release 说明列出六条用户可见项：显示 SpicyLyrics.org 歌词、紧凑多行片尾署名、修复缺失的对唱与伴唱行、显示全部署名艺人并新增歌手字号控制、改进歌曲信息间距、修复 Spotify 更新后歌词连不上。

**有用性评估（2026-10-02，逐项对照当前 CN+ 代码；移植状态更新于 2026-10-03）**

- **评估后已移植**：项 #7（2026-10-02，PR #136）、项 #8/#3/#6（2026-10-03，PR #146）与项 #5（2026-10-03，PR #147，按建议「按概念移植」——可见性 hook 供 `adoptHost`、`bootstrap` 播种电源管理器、具名故障取代单一闩锁）——见「已同步」表。
- **中等价值 —— 视需要：**
  - **项 #1 歌曲信息布局 + 换行而非缩小**：CN+ [layoutMetadataLines](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/LyricLayoutEngine.kt#L176-L203) 上限 3 行、溢出丢弃，`AodCanvasModel` 在内容超出画布时整体缩放；上游改为继续换行到更多行。`stacked`/`single` 对 CN+ 是新增选项（CN+ 默认已是 ` · ` 单行 + 逐槽分隔符，接近上游 `single`）。用户可见，但需贯通 CN+ 自有 wire/自定义/预览层。
  - **项 #2 开场时长可配置**：CN+ 固定 3000ms，且结构已分叉（无 `openingResolved`/`provisional`）；移植需调和 `-1`（不限时）语义与 CN+ 的 `availableInterludeMs >= durationMs` 门槛。属偏好功能。
  - **项 #9 子句标点断行加权**：CN+ 有同构的 `balancedChunkRanges`（[AodCanvasLineLayout](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodCanvasLineLayout.kt#L47)）可加 `breakAfter`；纯排版观感优化。
- **低价值 / 暂缓：**
  - **项 #4 原厂时钟带按侧预留**：CN+ 并非「恒预留底部」，而是用 [avoidStockClockOverlap](file:///workspace/app/src/main/java/com/eza/hyperglow/root/aod/AodSurfaceController.kt#L1678-L1697)（重叠时把歌词下移），上游所指缺陷未必存在于 CN+；建议先在顶部时钟机型上复现再定。
  - **项 #11 上游规范文档同步**：价值在文档准确性（尤其「Android 不会自动重连」更正），但应跟随代码结论；可随移植附带。
  - **项 #13 测试**：随对应功能落地。
- **不适用：** 项 #10（CN+ 从无对唱槽位机制）；项 #12（CN+ 独立版本号）。

**有用性评估（2026-10-07，`99ba119` → `fefe5c54` 增量，按 patch 逐条核对）**

- **可直接移植：** `fefe5c54` 项 #2——伴唱加入规则只是几行，改的是 CN+ 原样带着的同一道门。`84a0c9ce` 项 #2/#3——歌手字号 + 混合字号行盒是自洽且用户可见的歌曲信息改进，CN+ 有同形的布局代码可挂。
- **须先有生产端：** `fefe5c54` 项 #1（片尾署名）在 CN+ 尚未携带的文档字段到达投影层之前完全不起作用；先移植解码与 wire 路径，否则整项跳过。
- **须先复现再定：** `84a0c9ce` 项 #1（源行序号）。CN+ 在 PR #118 里丢掉了上游的对唱机制、且 wire 体系不同，本项所修的故障要先在 CN+ 上复现。
- **便宜且独立：** `84a0c9ce` 项 #4（`forceQueryable`）——一行 manifest 问题，对照 CN+ 自己的 bridge 发现机制核验即可。
- **不适用：** 两个版本号行（`84a0c9ce` 项 #6、`fefe5c54` 项 #4）。

**有用性评估（2026-10-10，执行记录）**

- **已移植**：`99ba119` #1/#9/#11、`84a0c9ce` #2/#3/#4、`fefe5c54` #2，外加替代 `99ba119` #2 的本地「倒计时圆点」——逐行细节见「已同步」表。
- **已评估、不移植 —— `84a0c9ce` #1（源行序号）**：CN+ 不共享该故障。上游那套节身份机制（`DuetSectionId` / `assignDuetSlots` / `placeDuetSectionTops` / `AodLineTransitionKey` / `layoutEquivalent`）在 CN+ **零存在**（grep 核实）；真机观察到的角色互换症状已被 #241/#242/#245 在发射前修掉（`assignVoiceSlots` 钉声部槽位）；且上游「去文本」的身份键与 CN+ #244 的文本判据 `isSameLineTextUpdate` 在真实数据上互斥（《乐鸣东方》3:40——同文本、新窗口实例必须播）——照搬会回退 #244。残余价值已记录：(a) 并发行锁的行窗判据比 CN+ 已有的 `agent` 身份弱——真机若反馈优先用 `agent`；(b) 主行纠正重播溶解有一条不需要改 wire 的低成本修法（在 `isSameLineTextUpdate` 旁）。分析见 `sync_upstream/ws4_roword_REPORT.md`（离线探针 8 例）。
- **暂缓**：`99ba119` #4——CN+ 在重叠时下移歌词（`avoidStockClockOverlap`），上游所指缺陷未复现；在顶部时钟机型上再评估。`fefe5c54` #1——缺生产端即惰性（CN+ 须先在文档解码与 wire 携带 `responseCredit`）。

## 未同步 / 未纳入

> 已评估、有意不移植（或不适用），附原因；按需再评估。日期列为上游提交日期。

| 上游提交 | 日期 | 内容 | 原因 |
|---|---|---|---|
| `99ba119` 项 #2 | 2026-09-28 | 可配置开场时长（`songIntroDurationMs`，2–30 秒或 -1 不限时） | ⏸ 有意不移植（owner 决定，2026-10-10）：长间奏呈现改由本地「倒计时圆点」承担（见「已同步」）；开场占位保持固定 3s |
| `99ba119` 项 #4 | 2026-09-28 | 原厂时钟/图片带按实际所在侧预留 | ⏸ 暂缓（2026-10-10）：CN+ 并非恒预留底部——重叠时用 `avoidStockClockOverlap` 下移歌词——上游所指缺陷未复现；在顶部时钟机型上再评估 |
| `84a0c9ce` 项 #1 | 2026-10-05 | 同刻行的节身份改由源行序号判定（wire v11） | ⏸ 2026-10-10 评估后不移植：CN+ 零存在上游机制；症状已由 #241/#242/#245 在源头修掉；去文本的身份键与 CN+ #244 的文本判据冲突（会回退 #244）。残余价值与无需 wire 的修法记于 2026-10-10 评估；分析见 `sync_upstream/ws4_roword_REPORT.md` |
| `fefe5c54` 项 #1 | 2026-10-05 | 片尾署名（`responseCredit` + `ResponseCreditOutroPolicy`） | ⏸ 暂缓（2026-10-10）：缺生产端即惰性——CN+ 须先在自己的文档解码与 wire 里携带 `responseCredit` |
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
5. 移植后跑完整 CI 套件，全绿后推送
6. **更新本文件**：把该项从「未同步」表移入（或新增到）「已同步」表，并更新基线日期
