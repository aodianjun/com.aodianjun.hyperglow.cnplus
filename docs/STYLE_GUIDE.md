# HyperGlow Style Guide

# English / 英文

Status: canonical implementation guide

This guide codifies the dominant safe patterns in the module. It is subordinate to
`docs/ARCHITECTURE.md`, `docs/private/PARITY-SPEC.md` (private doc, not in the public tree), and
`docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md`.
Those documents define behavior; this document defines how that behavior is implemented and
reviewed.

The strictest rules apply to code loaded into `com.android.systemui`: failures there can affect the
lockscreen, AOD, authentication flow, memory pressure, and device power. Do not mass-format or
rewrite working code merely to satisfy a preference. Correct deviations when they affect
correctness, lifecycle safety, trust boundaries, power, readability, or maintainability.

## 1. Package boundaries and visibility

### Package ownership

| Package | Ownership |
|---|---|
| `bridge` | Spotify/Spicy producer endpoints, caller validation, transport ordering, document decoding, and app-process bridge stores. |
| `aod` | App-process playback projection, render preferences, SystemUI callback service, state/configuration publication, demo control, and persisted Xiaomi capability reporting. |
| `customization` | Versioned declarative models, migration, normalization, compilation, persistence, and editor state. |
| `ui` | Activity, Compose settings/editor, preview orchestration, import/export UI, and user-triggered diagnostics. It does not own wire validation or SystemUI lifecycle policy. |
| `root` | Code loaded into SystemUI or deliberately shared with the app preview. Runtime hooks, projection, surface controllers, placement, transitions, and renderers live here. |
| `root.aod` | AOD hook, overlay surface, renderer, lifetime guard, position updates, and stock-widget coordination. |
| `root.lockscreen` | Visual-only lockscreen host, geometry, notification collision, and lockscreen-only widgets. |
| `root.projection` | One SystemUI Binder client/session, immutable lyric snapshot, replay, freshness, user isolation, and subscriber fan-out. |
| `root.surface` | Pure surface environment, placement, collision, and surface policy decisions. |
| `root.transition` | Linkage direction hooks, transition state/tokens, geometry conversion, freeze, reversal, timeout, and cleanup. |
| `root.capability` | Exact-version and exact-symbol capability resolution. |
| `root.customization` | SystemUI wire extraction and second-stage defensive configuration validation. |

Rules:

- Preserve process boundaries. SystemUI runtime code must not read app-process preferences, perform
  app repository work, or create a second bridge connection. It consumes bounded Binder data through
  `SystemUiLyricProjection`.
- App preview may reuse deterministic renderer, placement, capability-model, and snapshot types from
  `root`; that reuse does not authorize app-process services or persistence inside SystemUI paths.
- Hooks observe/install/intercept. Controllers own mutable lifecycle. Pure geometry, normalization,
  and policy belong in testable helpers or resolver/model files.
- Do not add a shared package or abstraction only to move types. Extract a shared model when it removes
  a real dependency violation or repeated mapping and remains safe in both processes.

### Visibility

- Use the narrowest visibility that supports the real call graph.
- `private` is the default for fields, constants, callbacks, reflection helpers, and file-local
  implementation details.
- SystemUI implementation types are normally `internal`. Manifest components, the Xposed entry
  class, and framework-required overrides remain externally visible as required.
- App-process models used across package boundaries may use module visibility appropriate to current
  serialization/framework constraints. Do not make a declaration public solely for a unit test.
- Mutable state stays private to its owner. Expose immutable snapshots, read-only collections, or
  operations rather than mutable properties.

## 2. Models, enums, constants, and string protocols

- Use immutable `data class` values for snapshots, wire payloads after extraction, configuration,
  geometry, placement inputs/results, capability reports, and transition transforms.
- Model an exhaustive state machine with `enum class` when states carry no payload and with a sealed
  interface/class when variants carry different data. Keep transition logic exhaustive.
- Collections stored in snapshots are owned and exposed as `List`, `Set`, or `Map`. Copy mutable
  framework/caller collections before storing them.
- Use `object` for a genuine process singleton or stateless namespace, not to hide unrelated mutable
  responsibilities.

String values are unavoidable at AIDL/`Bundle`, JSON, preferences, Intent, and UI-label boundaries.
Treat them as protocols, not casual strings:

- Define keys, protocol versions, limits, package/class names, preference keys, and stable wire values
  once in the owning codec/store/contract.
- Preserve exact spelling and case across producer and consumer copies of a protocol. AIDL method
  order and callback transaction order are ABI.
- Validate closed producer values at ingress where compatibility permits. Otherwise normalize through
  one explicit table and fail to a documented safe value. Unknown values must not accidentally enable
  a feature because they are merely nonblank.
- Decode closed internal state to enums when it removes repeated string comparisons. Do not add an
  enum layer that immediately converts back to strings everywhere.
- Keep UI labels separate from wire identifiers. User-facing text may change; stored/wire identifiers
  require migration.
- Constants use `UPPER_SNAKE_CASE`. Include units or meaning where ambiguity exists, such as `_MS`,
  `_BYTES`, `_COUNT`, `_FRACTION`, `_VERSION_CODE`, `KEY_`, or `TAG`.
- Time fields end in `Ms`; monotonic timestamps include `Elapsed` when useful. Coordinates and bounds
  name their coordinate space when local/window/stack distinctions matter.

Large immutable protocol snapshots are acceptable. At construction and mapping sites:

- Use named arguments for long constructors and whenever adjacent arguments share the same type.
- Use distinct field names and sentinel values in mapping tests so transposed values cannot pass.
- Group fields only when the group is coherent and reused or materially reduces mapping/lifecycle
  risk. Avoid wrappers that only move a long argument list elsewhere.

## 3. Nullability, validation, bounds, and fail-closed behavior

- Accept null at Android, Binder, reflection, JSON, preference, or view-discovery boundaries. Convert
  it immediately to a validated value, documented default, or disabled capability.
- Avoid `!!` in production. A missing view, symbol, payload field, user, or geometry input is a normal
  compatibility condition, not proof that null is impossible.
- Validate before expensive allocation or decode: caller UID/package, protocol version, user ID,
  generation, sequence, revision, payload byte/character length, list count, text length, timing,
  source range, hash shape, enum/string membership, and finite numeric values.
- Character and UTF-8 byte limits are separate. Configuration remains below the 64 KiB hard limit;
  lyric/document counts and text use their existing explicit bounds.
- Check `Float`/`Double.isFinite()` before clamping. Clamp positions and durations only after rejecting
  impossible or hostile values at the trust boundary.
- Use `SystemClock.elapsedRealtime()` for freshness, projection anchors, retry/timeout logic, and
  lifecycle timing. Wall-clock time is only for human evidence timestamps.
- Validation returns a complete valid value or rejects/fails closed. Do not partially update global
  state before all required checks pass.
- Clear/stale/disconnect/user-switch results must not retain renderable content that can be replayed.
- Unknown Xiaomi versions, symbols, themes, displays, modes, notification geometry, or coordinate
  transforms disable only the dependent feature. They never guess a nearby member, reveal lockscreen
  content, mutate stock UI, or activate AOD keepalive/clock ownership.
- Optional content degrades in the documented order: hide optional widgets/rows, shrink within bounds,
  then hide the custom scene. Never force stock layout to make room.

## 4. Thread ownership

### App process

- Binder/provider ingress validates and publishes owned immutable state.
- Gzip, JSON/document decode, large-input hashing, SAF I/O, and shell/process work run off main.
- Mutable projection session state has one explicit serialized owner. Acceptable implementations are
  synchronized access plus generation rechecks, a single coroutine event loop, or a dedicated serial
  dispatcher. Multiple `Dispatchers.Default` coroutines must not publish from the same session state
  without a final current-generation/current-state check.
- `Job.cancel()` is not synchronization. A terminal pause/clear/release must prevent an already-running
  projection from publishing a newer visible state afterward, using join/serialization or a generation
  check immediately before publication.

### SystemUI process

- View creation, attachment, measurement/layout calls, visibility, animation, `Handler` queues,
  surface-controller state, and transition state are main-thread owned.
- Binder callbacks may run on Binder threads. They validate/extract an owned bounded message, then post
  latest-only work to main. They do not touch views.
- State shared between Binder and main threads uses one clear strategy: synchronization around the
  pending message/generation, or immutable message passing. Do not mix guarded and unguarded access.
- Do not call subscribers, framework callbacks, renderer callbacks, or Binder methods while holding a
  monitor when those calls can re-enter. Snapshot targets/state under lock, then call out.
- Document non-obvious ownership in class KDoc or a focused comment. `@MainThread`/`@WorkerThread` may
  add tooling signal, but annotations do not replace synchronization.

## 5. Handler, Runnable, and generation lifecycle

- Cancellable delayed/repeating work uses a stable `Runnable` instance or scheduler handle so the
  exact callback can be removed. A local one-shot is acceptable only when it cannot outlive or be
  superseded by its owner.
- Remove a pending replacement before posting the new one. Coalesce high-frequency geometry/state
  updates to latest-only work.
- Use a monotonically increasing generation/token whenever the target can be replaced: Binder binding,
  AOD attachment, lockscreen blueprint, user session, linkage handoff, animation, stale expiry, or
  delayed Xiaomi policy replay.
- A callback verifies its captured generation/token before touching owner state or a View. Where state
  can change during work, recheck before the final publication/mutation.
- Detach, unbind, clear, user switch, Binder death, feature disable, and controller replacement cancel
  pending work, advance generation where applicable, clear pending payloads, and release references.
- Self-rescheduling frame/heartbeat work checks active/visible/attached state before work and before
  reposting. It stops when content is static, hidden, stale, or detached.
- A completed delayed callback clears its owner field in `finally` when the field still points to that
  callback. Do not retain an old View, Xiaomi controller, Activity, Binder callback, or plugin class
  loader after completion.
- Retries and timeouts are named, bounded, and lifecycle-cancellable. No unbounded fast retry loop.

## 6. Binder callbacks and immutable payload extraction

- One-way Binder callbacks return quickly. No JSON decode, disk I/O, package-manager query,
  reflection, view work, large hashing, or renderer mapping occurs before return.
- Never retain the Binder-delivered `Bundle`, `Parcel`, mutable parcelable collection, or framework
  object after the callback returns.
- Preferred path: synchronously extract bounded primitives/strings into an immutable wire payload,
  reject malformed data, store only the latest payload for the current binding generation, and post
  it to main.
- Lyric state keeps the callback `Bundle` only as an ABI envelope. Full snapshots use one bounded
  encoded body; hidden and keepalive messages remain scalar-only. The Binder-delivered `Bundle` and
  encoded body are released before callback return, and only the decoded immutable message reaches
  `SystemUiLyricProjection`. Configuration likewise uses immutable primitive extraction because delayed
  `Bundle` copying previously caused a SystemUI OOM regression.
- Validate the configuration envelope before JSON decode: protocol, user, nonnegative revision,
  lowercase SHA-256 shape, character length, and UTF-8 byte length. SystemUI validates decoded schema
  again.
- Register/unregister, disconnect, binding death, null binding, and retry use one idempotent reset
  pattern and reject stale connection callbacks by generation and identity.
- Every exported producer/callback endpoint validates caller UID/package. Cached verdicts remain
  bounded and never convert an unknown caller into an allowed caller.
- Additive AIDL methods/fields require explicit compatibility and tests. Never reorder an existing
  AIDL callback method.

## 7. Reflection, exceptions, and logging

### Reflection and hooks

- Reflect exact verified class names, method names, parameter types, fields, and package version
  profiles. Capability detection and hook installation describe the same symbol contract.
- Missing optional symbols disable a capability. Missing required symbols abort only that hook family.
  Do not scan similarly named methods or silently hook a neighboring overload.
- Search superclasses only for a documented Xiaomi hierarchy variation.
- Set accessibility in the lookup/install path. Cache stable `Method`/`Field` objects used afterward.
  Never reflect from `onDraw` or a frame callback.
- Hook installers are idempotent per class loader. Deduplication references are weak where plugin reload
  must remain collectible.
- Hooks preserve Xiaomi ownership and call `chain.proceed()` exactly as required. Argument replacement
  uses supported libxposed APIs; do not mutate immutable `chain.args`.

### Exceptions

- Catch the narrowest expected recoverable exception. `Exception` is acceptable at a trust or
  compatibility boundary when the operation is intentionally fail-closed and consequence is explicit.
- `runCatching` is acceptable for a small optional lookup, Binder call, bind/unbind, parse attempt, or
  capability probe. It is not a blanket wrapper for a long lifecycle/render mutation.
- Because `runCatching` catches `Throwable`, SystemUI mutation code must not suppress
  `OutOfMemoryError`, `StackOverflowError`, `ThreadDeath`, or other VM-fatal errors. Prefer `try/catch
  (Exception)` there, or explicitly rethrow fatal errors before fallback handling.
- A recoverable failure rejects input, disables the dependent capability, performs idempotent cleanup,
  or schedules a bounded retry. Empty catches are forbidden.
- Put mandatory cleanup in `finally`. Cleanup is safe to call repeatedly and on partial state.

### Logging

- `error`: an invariant/operation failure that makes the dependent feature unusable or leaves cleanup
  uncertain.
- `warn`: rejected malformed input, optional symbol loss, bounded retry/fallback, or recoverable
  framework/Binder failure needing diagnosis.
- `info`: module/hook installation, capability summary, attach/detach, connection state, accepted
  linkage/ownership transition, and change-only geometry/eligibility decisions.
- Normal debug and release builds compile with `TRACE_LOGGING_AVAILABLE=true`, but persisted runtime
  diagnostic logging defaults off. The app publishes the effective flag through the compiled
  configuration bridge so app-process and SystemUI `info` tracing changes without a restart.
- `-PtraceLogging=false` is a hard compile-time ceiling for a deliberately stripped artifact. Runtime
  preferences cannot bypass it. `warn` and `error` remain unconditional in every build.
- A finite fixed-stage SystemUI bootstrap series is the sole `info` exception to the runtime trace
  gate when trace logging is compiled in. It exists to diagnose a missing bridge/configuration path;
  it may contain only a fixed stage name and bounded build, probe-count/profile, user, attempt, and
  process-class scalars.
- Diagnostic logging is runtime state, not appearance/profile state. Do not include it in imported or
  exported customization documents. Hot-path counters and diagnostic-string construction must check
  the runtime trace gate before doing work.
- Deduplicate or rate-limit recurring diagnostics. Never log per frame, every 16/100/250 ms tick,
  every heartbeat, or unchanged eligibility/geometry.
- Do not log full lyrics, metadata, imported JSON, Binder payloads, user identifiers, file content, or
  shell output. Log revision/generation/token and fail-closed consequence when useful.

## 8. View measure, layout, and draw separation

- Surface controllers discover Xiaomi hosts, compute transforms/placement, and attach, measure, and
  lay out module views. Render Views do not choose their host or mutate Xiaomi parents.
- AOD uses a manually measured/layout child in the inner `AODView` root overlay. It never enters
  `mTableModeContainer` measurement or changes stock clock measurement, content, style, or lifecycle.
- Lockscreen uses the verified index-0 child under `keyguard_translation_info`, remains visual-only,
  and inherits native parent alpha/scale/visibility. No click, long-click, focus, touch interception,
  or accessibility focus.
- `setContent`/configuration changes normalize values, resolve palette/typeface/style, snapshot outgoing
  transition state, and rebuild content-dependent layout.
- `onSizeChanged` and padding/size changes rebuild size-dependent layout.
- `onMeasure` determines the module View's own dimensions only. No reflection, Binder work, or parent
  mutation.
- `onDraw` reads cached layout/render state and performs elapsed-time projection, clipping, alpha,
  transforms, and draw calls. It does not load assets, query preferences, reflect, create Binder data,
  change layout parameters, call `requestLayout()`, or perform file/package I/O.
- Restore every Canvas save/saveLayer. When drawing outgoing/incoming snapshots, preserve and restore
  Paint/typeface/palette state so a new line cannot restyle the old snapshot.
- Name and convert coordinate spaces explicitly. Do not mix root-local, parent-local, stack-local, and
  window coordinates.
- Repeated geometry callbacks compare resolved bounds before replacing layout parameters or requesting
  layout. Prevent recursive `requestLayout()` loops.

## 9. Allocation policy for AOD/SystemUI hot paths

Hot paths include timed `onDraw` at 16 ms, demo ticks, media progress at 250 ms, position callbacks,
and repeated notification geometry callbacks.

- Move text measurement, wrapping, ruby/source mapping, row partitioning, typeface loading, palette
  resolution, and stable geometry construction to content/size/configuration changes.
- Reuse Paints, typefaces, arrays, and mutable Android geometry objects owned by the View/controller.
- Avoid collection pipelines (`map`, `filter`, `filterNot`, `buildList`), regex, substring creation,
  string interpolation, new `Rect`/`Paint`/`Bundle`, reflection, and diagnostic-string construction in
  steady-state frame code when an indexed loop or cached result is practical.
- Small bounded existing allocations are not proof of a user-visible defect, but are not the preferred
  pattern. Remove deterministic per-frame allocation when changing the affected code.
- Do not claim zero allocation, battery improvement, jank reduction, or safe software-layer cost from
  inspection alone. Use allocation/frame/device evidence.
- Only visible or transitioning surfaces schedule frames. Hidden/static/detached surfaces stop.
- Intentional recurring allocation or software-rendering cost in AOD/SystemUI needs a concise rationale
  and device evidence before broad enablement.

## 10. Naming, function structure, arguments, and comments

### Naming

- Types are nouns. `Controller` owns lifecycle/mutation; `Coordinator` combines owners; `Store` owns
  validated state; `Resolver`/`Engine` computes policy; `Codec` encodes/decodes; `Hook`
  installs/intercepts; `Snapshot`, `State`, `Environment`, and `Result` are immutable values.
- Functions are verbs. Predicates start with `is`, `has`, `can`, `should`, or `supports`.
- Boolean names are positive and scoped: `aodEnabled`, `sceneVisible`,
  `positionFollowingEnabled`. Avoid double negatives.
- Use `current`, `latest`, `pending`, `cached`, `expected`, and `active` only when their lifecycle
  meanings differ and are clear.

### Functions and files

- Prefer one primary responsibility per function and one primary production type per file. Related
  small immutable models/helpers may share a file.
- There is no mechanical line-count limit. Extract when a function mixes validation, decoding,
  persistence, lifecycle mutation, geometry, and drawing; when nesting obscures fail-closed exits; or
  when extraction creates a useful pure test seam.
- Large renderer/controller files may remain cohesive when splitting would duplicate mutable state or
  add allocation/indirection. New work should still isolate pure decisions from Android lifecycle code.
- Use early returns for failed guards and unsupported capabilities. Keep the successful path readable.

### Arguments

- Prefer named arguments in multiline calls and calls with multiple adjacent Booleans, Strings,
  numbers, nullable values, or collections.
- A parameter object is justified when a coherent group crosses several methods or boundaries, not
  only to satisfy an arbitrary argument count.
- Avoid sentinel-heavy nullable argument trains. Use a validated immutable value or sealed result when
  states have materially different meanings.

### User-facing copy

- Prefer one concise, self-explanatory heading, label, or row title. Do not add subtitles, helper text,
  or descriptive copy beneath headings, labels, cards, or settings by default.
- Add supporting copy only when the user asks for it, or when it is required to prevent misunderstanding
  or error: an unavailable or disabled capability, a power/performance cost, an external prerequisite,
  a nonobvious scope, or a destructive confirmation. Never use it to restate the heading.
- A preference `summary` that shows the current value or runtime state is not descriptive copy and is
  not covered by the rule above.
- Data-handling and policy disclosure in the diagnostic report flow is required consent text, not
  helper copy. Keep it complete.

### User-facing forms

- Multiline input starts at top/start. A task-focused placeholder disappears after input begins.
- Put a compact live limit counter inside the field at bottom/end. Reserve padding so text never
  overlaps it. Keep byte-bound contracts byte-accurate even when the counter copy is terse.
- Do not bury limits in placeholder prose or repeat policy explanations beside every action. Keep
  labels short and put detailed disclosure in the linked policy.
- Primary actions may use the full row. Secondary actions must accommodate translated labels with
  wrap-content height, bounded wrapping, or stacking; fixed-height clipping and overflow are defects.
- All user-facing form text comes from locale XML, including placeholders, counters, states, and
  actions.

### Comments and formatting

- Explain invariants, ABI constraints, Xiaomi quirks, coordinate spaces, lifecycle ownership,
  fail-closed reasons, sanctioned parity deltas, and device-observed workarounds.
- Do not narrate obvious Kotlin syntax.
- A Xiaomi workaround names the verified symbol/version context and points to the governing research,
  diagnostic, behavior spec, or implementation-status evidence.
- Temporary traces include a bounded purpose/removal condition and are removed after evidence is
  captured. Never leave lyric-content tracing enabled in production.
- Follow existing Kotlin formatting: four spaces, no tabs, standard naming, no wildcard imports, and
  formatter-friendly multiline calls with trailing commas where used. Do not reformat unrelated work.

## 11. Tests

- Test names are lower camel case and state behavior/condition/outcome, for example
  `activeNotificationStateWithoutCachedBoundsFailsClosed`.
- A nontrivial test separates arrange, act, and assert with blank lines. Keep one behavior under test.
- Use pure JVM tests for bounds, normalization, projection identity, placement, timing, row selection,
  source/ruby ranges, state machines, generation/token rejection, capability policy, and schema
  security.
- Extract the smallest pure decision helper instead of building mock-heavy Android tests.
- Boundary tests cover valid, missing, malformed, oversized, stale, duplicate/reordered,
  future-version, wrong-user, non-finite, invalid-range, and unknown-enum/value cases as applicable.
- Lifecycle tests cover current, stale, superseded, cancelled, detached, user-switched, disconnected,
  reversed, and timeout callbacks.
- DTO/codec mapping tests use distinct sentinel values for every same-typed field.
- A correctness fix adds the smallest focused regression test that would fail before the fix, then the
  relevant broader host gate runs.
- Host tests prove deterministic logic, not Xiaomi runtime behavior, visual parity, power, process
  memory, hook viability, or animation smoothness.

## 12. Documentation and device evidence

- Update the governing spec before or with a behavior change. Implementation status does not silently
  override architecture or behavior contracts.
- Update `docs/ARCHITECTURE.md` for package/process ownership, Binder flow, trust boundaries,
  capability gates, surface attachment, lifetime policy, or customization schema changes.
- Update `docs/private/PARITY-SPEC.md` (private doc, not in the public tree) for renderer behavior or
  sanctioned AOD deltas.
- Update `docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md` for visibility, privacy, collision, continuity,
  keepalive, fallback, or customization-policy changes.
- Update the relevant research/diagnostic record for Xiaomi symbol, hook, geometry, or version findings.
  Mark historical diagnostics as superseded rather than presenting them as current architecture.
- `docs/private/LOCKSCREEN_AOD_IMPLEMENTATION_STATUS.md` (private doc, not in the public tree) records exact host-test
  count/result, build result, APK SHA-256, install time, device/serial, SystemUI PID/restart reason, capability
  summary, observed behavior, crash/OOM/safe-mode result, and remaining device gates.
- Use evidence labels precisely:
  - `unit-tested` / `host-verified`: deterministic local tests only;
  - `trace-observed`: supported by runtime logs/trace, not necessarily visually confirmed;
  - `device-smoke-tested`: module loaded and basic flow survived;
  - `device-verified`: the named behavior was exercised on the named build/device with required
    visual/log/crash evidence.
- Never claim device verification from code review, host tests, an old APK, or a screenshot that does
  not exercise the relevant flow.
- Proprietary APKs, JADX output, device logs, screenshots, and temporary captures remain under ignored
  `research/`. Do not copy internal research artifacts into public release repositories.
- Changes affecting SystemUI memory, AOD cadence/power, burn-in movement, linkage, lockscreen privacy,
  notification geometry, software rendering, or Xiaomi reflection require fresh device evidence before
  broad enablement.

## 13. Review rule

Apply this guide prospectively and during touched-code review. Existing deviations are candidates for
focused audit findings, not permission to repeat them and not justification for unrelated cleanup.
Prefer the smallest coherent correction with focused tests and honest host/device evidence.

---

# 中文 / Chinese

状态：权威实现指南

本指南将模块中占主导地位的安全模式加以成文化。它从属于 `docs/ARCHITECTURE.md`、`docs/private/PARITY-SPEC.md`（私有文档，未随公开树发布）与 `docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md`。那些文档定义行为；本文档定义这些行为如何被实现与评审。

最严格的规则适用于加载进 `com.android.systemui` 的代码：那里的失败可能影响锁屏、AOD、认证流程、内存压力与设备功耗。不要仅为满足某个偏好而批量格式化或重写可正常工作的代码。只有当偏差影响正确性、生命周期安全、信任边界、功耗、可读性或可维护性时才予以纠正。

## 1. 包边界与可见性

### 包所有权

| 包 | 所有权 |
|---|---|
| `bridge` | Spotify/Spicy 生产者端点、调用方校验、传输排序、文档解码与应用进程 bridge 存储。 |
| `aod` | 应用进程播放 projection、渲染偏好、SystemUI 回调服务、状态/配置发布、演示控制与持久化的 Xiaomi 能力上报。 |
| `customization` | 带版本的声明式模型、迁移、规范化、编译、持久化与编辑器状态。 |
| `ui` | Activity、Compose 设置/编辑器、预览编排、导入/导出 UI 与用户触发的诊断。它不负责 wire 校验或 SystemUI 生命周期策略。 |
| `root` | 加载进 SystemUI 或有意与应用预览共享的代码。运行时 hook、projection、surface 控制器、放置、过渡与渲染器位于此处。 |
| `root.aod` | AOD hook、overlay surface、渲染器、生命周期守卫、位置更新与原生组件协调。 |
| `root.lockscreen` | 仅视觉的锁屏宿主、几何、通知碰撞与仅限锁屏的组件。 |
| `root.projection` | 单一 SystemUI Binder 客户端/会话、不可变歌词 snapshot、重放、新鲜度、用户隔离与订阅方分发。 |
| `root.surface` | 纯粹的 surface 环境、放置、碰撞与 surface 策略决策。 |
| `root.transition` | linkage 方向 hook、过渡状态/令牌、几何转换、冻结、反转、超时与清理。 |
| `root.capability` | 精确版本与精确符号的能力解析。 |
| `root.customization` | SystemUI wire 提取与第二阶段防御性配置校验。 |

规则：

- 维持进程边界。SystemUI 运行时代码不得读取应用进程偏好、执行应用仓库工作或创建第二个 bridge 连接。它通过 `SystemUiLyricProjection` 消费有界的 Binder 数据。
- 应用预览可以复用来自 `root` 的确定性渲染器、放置、能力模型与 snapshot 类型；该复用并未授权在 SystemUI 路径内使用应用进程服务或持久化。
- Hook 负责观察/安装/拦截。控制器拥有可变生命周期。纯几何、规范化与策略应放在可测试的辅助类或解析器/模型文件中。
- 不要仅为搬运类型而添加共享包或抽象。只有当抽取共享模型能消除真实的依赖违规或重复映射，并且在两个进程中都安全时，才这样做。

### 可见性

- 使用能支撑真实调用图的最窄可见性。
- 字段、常量、回调、反射辅助与文件内实现细节默认使用 `private`。
- SystemUI 实现类型通常为 `internal`。Manifest 组件、Xposed 入口类与框架要求的重写仍按要求保持外部可见。
- 跨包边界使用的应用进程模型可以采用适合当前序列化/框架约束的模块可见性。不得仅为单元测试而将声明设为 public。
- 可变状态保持为对其所有者私有。暴露不可变 snapshot、只读集合或操作，而不是可变属性。

## 2. 模型、枚举、常量与字符串协议

- 为 snapshot、提取后的 wire payload、配置、几何、放置输入/结果、能力报告与过渡变换使用不可变的 `data class` 值。
- 当状态不携带 payload 时用 `enum class` 建模穷举状态机，当变体携带不同数据时用 sealed 接口/类建模。保持过渡逻辑穷举。
- 存储 snapshot 中的集合应为自有集合，并以 `List`、`Set` 或 `Map` 暴露。存储前先复制可变的框架/调用方集合。
- 用 `object` 实现真正的进程单例或无状态命名空间，而不是用来隐藏不相关的可变职责。

字符串值在 AIDL/`Bundle`、JSON、偏好、Intent 与 UI 标签边界不可避免。把它们当作协议，而不是随意的字符串：

- 键、协议版本、限制、包/类名、偏好键与稳定的 wire 值，只在所属的编解码器/存储/契约中定义一次。
- 在协议的生产者与消费者副本之间保持精确拼写与大小写。AIDL 方法顺序与回调事务顺序是 ABI。
- 在兼容性允许的入口处校验封闭的生产者值。否则通过一张显式表进行规范化，并失效到一个有文档记载的安全值。未知值不得仅仅因为非空白就意外启用某个功能。
- 当能消除重复字符串比较时，将封闭的内部状态解码为枚举。不要添加一个又在各处立即转换回字符串的枚举层。
- UI 标签与 wire 标识符分开。面向用户的文本可以更改；存储/wire 标识符需要迁移。
- 常量使用 `UPPER_SNAKE_CASE`。在存在歧义时包含单位或含义，例如 `_MS`、`_BYTES`、`_COUNT`、`_FRACTION`、`_VERSION_CODE`、`KEY_` 或 `TAG`。
- 时间字段以 `Ms` 结尾；单调时间戳在有用时包含 `Elapsed`。当局部/窗口/栈的区分重要时，坐标与边界应命名其坐标空间。

大型不可变协议 snapshot 是可接受的。在构造与映射处：

- 对长构造器以及相邻参数同类型时使用命名参数。
- 在映射测试中使用互异的字段名与哨兵值，使被转置的值无法通过。
- 仅当字段组内聚且被复用，或能实质降低映射/生命周期风险时才分组。避免只把长参数列表搬到别处的包装器。

## 3. 可空性、校验、边界与 fail-closed 行为

- 在 Android、Binder、反射、JSON、偏好或视图发现边界接受 null。立即将其转换为经过校验的值、有文档记载的默认值或已禁用的能力。
- 生产代码中避免 `!!`。视图、符号、payload 字段、用户或几何输入缺失是正常的兼容性状况，而不是 null 不可能的证明。
- 在昂贵的分配或解码之前进行校验：调用方 UID/包名、协议版本、用户 ID、generation、序列、revision、payload 字节/字符长度、列表数量、文本长度、时值、来源区间、哈希形状、枚举/字符串成员资格与有限数值。
- 字符限制与 UTF-8 字节限制是分开的。配置保持在 64 KiB 硬性上限以下；歌词/文档数量与文本使用既有的显式边界。
- 钳制前先检查 `Float`/`Double.isFinite()`。只有在信任边界处拒绝了不可能或恶意的值之后，才对位置和时长进行钳制。
- 新鲜度、projection 锚点、重试/超时逻辑与生命周期计时使用 `SystemClock.elapsedRealtime()`。墙上时钟时间只用于供人阅读的证据时间戳。
- 校验要么返回完整的有效值，要么拒绝/fail closed。在所有必需检查通过之前，不要部分更新全局状态。
- 清除/过期/断开/用户切换的结果不得保留可被重放的渲染内容。
- 未知的 Xiaomi 版本、符号、主题、显示屏、模式、通知几何或坐标变换只禁用依赖的功能。绝不猜测邻近成员、泄露锁屏内容、修改原生 UI 或激活 AOD keepalive/时钟所有权。
- 可选内容按文档记载的顺序降级：隐藏可选组件/行、在有界范围内收缩、然后隐藏自定义场景。绝不强迫原生布局腾出空间。

## 4. 线程所有权

### 应用进程

- Binder/provider 入口负责校验并发布自有不可变状态。
- Gzip、JSON/文档解码、大输入哈希、SAF I/O 与 shell/进程工作不得在主线程运行。
- 可变的 projection 会话状态拥有一个显式的串行化所有者。可接受的实现是同步访问加 generation 复查、单一协程事件循环或专用串行调度器。多个 `Dispatchers.Default` 协程在没有最终当前 generation/当前状态检查的情况下，不得从同一会话状态发布。
- `Job.cancel()` 不是同步。终态暂停/清除/释放必须防止已在运行的 projection 之后发布更新的可见状态，方法是 join/串行化，或在发布前立即进行 generation 检查。

### SystemUI 进程

- 视图创建、附加、测量/布局调用、可见性、动画、`Handler` 队列、surface 控制器状态与过渡状态归主线程所有。
- Binder 回调可能在 Binder 线程上运行。它们校验/提取一个自有的有界消息，然后向主线程投递 latest-only 工作。它们不触碰视图。
- Binder 与主线程之间共享的状态使用一种清晰的策略：围绕待处理消息/generation 做同步，或不可变消息传递。不要混合有保护与无保护的访问。
- 当订阅方、框架回调、渲染器回调或 Binder 方法可能重入时，不得在持有 monitor 期间调用它们。先在锁内对目标/状态做 snapshot，再在锁外调用。
- 在类 KDoc 或聚焦的注释中记录不明显的所有权。`@MainThread`/`@WorkerThread` 可以增加工具信号，但注解不能替代同步。

## 5. Handler、Runnable 与 generation 生命周期

- 可取消的延迟/重复工作使用稳定的 `Runnable` 实例或调度器句柄，以便能移除精确的回调。局部一次性回调只有在其不会比所有者存活更久或被所有者取代时才可接受。
- 先移除待处理的替换再投递新的。把高频几何/状态更新合并为 latest-only 工作。
- 只要目标可能被替换就使用单调递增的 generation/令牌：Binder 绑定、AOD 附加、锁屏蓝图、用户会话、linkage 交接、动画、过期或延迟的 Xiaomi 策略重放。
- 回调在触碰所有者状态或 View 之前先校验其捕获的 generation/令牌。若状态在工作期间可能改变，在最终发布/变更前复查。
- 分离、解绑、清除、用户切换、Binder 死亡、功能禁用与控制器替换都会取消待处理工作、在适用时推进 generation、清除待处理 payload 并释放引用。
- 自我重调度的帧/心跳工作在工作前与重投递前检查活动/可见/附加状态。当内容静态、隐藏、过期或分离时停止。
- 已完成的延迟回调在 `finally` 中、当字段仍指向该回调时清除其所有者字段。完成后不要保留旧的 View、Xiaomi 控制器、Activity、Binder 回调或插件类加载器。
- 重试与超时必须有命名、有界且可随生命周期取消。不允许无界的快速重试循环。

## 6. Binder 回调与不可变 payload 提取

- 单向 Binder 回调必须快速返回。返回前不做 JSON 解码、磁盘 I/O、包管理器查询、反射、视图工作、大型哈希或渲染器映射。
- 回调返回后，绝不保留 Binder 传递的 `Bundle`、`Parcel`、可变 parcelable 集合或框架对象。
- 首选路径：同步将有界的基本类型/字符串提取到不可变 wire payload 中，拒绝畸形数据，只为当前绑定 generation 存储最新 payload，并将其投递到主线程。
- 歌词状态仅将回调 `Bundle` 作为 ABI 信封保留。完整 snapshot 使用单一有界编码体；隐藏与 keepalive 消息保持仅标量。Binder 传递的 `Bundle` 与编码体在回调返回前被释放，只有解码后的不可变消息会到达 `SystemUiLyricProjection`。配置同样使用不可变基本类型提取，因为延迟的 `Bundle` 拷贝曾导致 SystemUI OOM 回归。
- 在 JSON 解码前校验配置信封：协议、用户、非负 revision、小写 SHA-256 形状、字符长度与 UTF-8 字节长度。SystemUI 会对已解码 schema 再次校验。
- 注册/注销、断开连接、绑定死亡、空绑定与重试使用一种幂等重置模式，并按 generation 与身份拒绝过期的连接回调。
- 每个导出的生产者/回调端点都校验调用方 UID/包名。缓存的判定保持有界，绝不把未知调用方转换为允许的调用方。
- 新增的 AIDL 方法/字段需要显式的兼容性说明与测试。绝不重排既有的 AIDL 回调方法。

## 7. 反射、异常与日志

### 反射与 hook

- 只反射精确验证过的类名、方法名、参数类型、字段与包版本 profile。能力检测与 hook 安装描述同一符号契约。
- 可选符号缺失只禁用对应能力。必需符号缺失只中止该 hook 家族。不要扫描名称相似的方法或静默 hook 邻近的重载。
- 仅为有文档记载的 Xiaomi 层级变体搜索父类。
- 在查找/安装路径中设置可访问性。缓存之后使用的稳定 `Method`/`Field` 对象。绝不在 `onDraw` 或帧回调中反射。
- Hook 安装器对每个类加载器幂等。在插件重载必须保持可回收的地方，去重引用使用弱引用。
- Hook 保持 Xiaomi 所有权，并完全按需调用 `chain.proceed()`。参数替换使用受支持的 libxposed API；不要修改不可变的 `chain.args`。

### 异常

- 捕获最窄的预期可恢复异常。在信任或兼容性边界处，当操作有意 fail-closed 且后果明确时，`Exception` 是可接受的。
- `runCatching` 适用于小型可选查找、Binder 调用、绑定/解绑、解析尝试或能力探测。它不是长生命周期/渲染变更的万能包装。
- 由于 `runCatching` 捕获 `Throwable`，SystemUI 变更代码不得吞掉 `OutOfMemoryError`、`StackOverflowError`、`ThreadDeath` 或其他 VM 致命错误。在那里优先使用 `try/catch (Exception)`，或在 fallback 处理前显式重新抛出致命错误。
- 可恢复失败会拒绝输入、禁用依赖能力、执行幂等清理或调度有界重试。禁止空 catch。
- 强制清理放入 `finally`。清理应可重复调用且对部分状态安全。

### 日志

- `error`：使依赖功能不可用或使清理状态不确定的不变式/操作失败。
- `warn`：被拒绝的畸形输入、可选符号丢失、有界重试/fallback，或需要诊断的可恢复框架/Binder 失败。
- `info`：模块/hook 安装、能力摘要、附加/分离、连接状态、已接受的 linkage/所有权过渡，以及仅在变化时记录的几何/资格决策。
- 普通调试与发布构建以 `TRACE_LOGGING_AVAILABLE=true` 编译，但持久化的运行时诊断日志默认关闭。应用通过编译好的配置 bridge 发布生效标志，使应用进程与 SystemUI 的 `info` 追踪无需重启即可变更。
- `-PtraceLogging=false` 是刻意精简产物的硬性编译期上限。运行时偏好无法绕过它。`warn` 与 `error` 在每个构建中保持无条件。
- 当追踪日志被编译进来时，一个有限的固定阶段 SystemUI 引导序列是运行时追踪门控的唯一 `info` 例外。它用于诊断缺失的 bridge/配置路径；它只能包含固定的阶段名称与有界的构建、探测数/profile、用户、尝试与进程类别标量。
- 诊断日志是运行时状态，不是外观/profile 状态。不要把它包含在导入或导出的自定义文档中。热路径计数器与诊断字符串构造必须先检查运行时追踪门控再做事。
- 对重复出现的诊断去重或限速。绝不逐帧、每 16/100/250 毫秒滴答、每次心跳或对未变化的资格/几何记录日志。
- 不要记录完整歌词、元数据、导入的 JSON、Binder payload、用户标识符、文件内容或 shell 输出。在有用时记录 revision/generation/令牌与 fail-closed 后果。

## 8. 视图测量、布局与绘制分离

- Surface 控制器发现 Xiaomi 宿主、计算变换/放置，并附加、测量与布局模块视图。渲染 View 不选择自己的宿主，也不修改 Xiaomi 父视图。
- AOD 在 `AODView` 根部内侧 overlay 中使用手动测量/布局的子视图。它绝不进入 `mTableModeContainer` 测量，也不改变原生时钟的测量、内容、样式或生命周期。
- 锁屏使用 `keyguard_translation_info` 下经验证的第 0 号子视图，保持仅视觉，并继承原生父视图的 alpha/缩放/可见性。无点击、长按、聚焦、触摸拦截或无障碍聚焦。
- `setContent`/配置变更会规范化取值、解析调色板/字体/样式、对离场过渡状态做 snapshot，并重建依赖内容的布局。
- `onSizeChanged` 与内边距/尺寸变更重建依赖尺寸的布局。
- `onMeasure` 只决定模块 View 自身的尺寸。不做反射、Binder 工作或父视图修改。
- `onDraw` 读取缓存的布局/渲染状态，并执行 elapsed 时间 projection、裁剪、alpha、变换与绘制调用。它不加载资源、查询偏好、反射、创建 Binder 数据、更改布局参数、调用 `requestLayout()` 或执行文件/包 I/O。
- 恢复每一次 Canvas save/saveLayer。绘制离场/入场 snapshot 时，保存并恢复 Paint/字体/调色板状态，使新行不能给旧 snapshot 重新应用样式。
- 显式命名并转换坐标空间。不要混用根局部、父局部、栈局部与窗口坐标。
- 重复的几何回调在替换布局参数或请求布局之前，先比较已解析的边界。防止递归 `requestLayout()` 循环。

## 9. AOD/SystemUI 热路径的分配策略

热路径包括 16 毫秒一次的带时值 `onDraw`、演示滴答、250 毫秒一次的媒体进度、位置回调与重复的通知几何回调。

- 把文本测量、换行、ruby/来源映射、行划分、字体加载、调色板解析与稳定几何构造移到内容/尺寸/配置变更时进行。
- 复用由 View/控制器持有的 Paint、字体、数组与可变 Android 几何对象。
- 在索引循环或缓存结果可行时，避免在稳态帧代码中使用集合管线（`map`、`filter`、`filterNot`、`buildList`）、正则、子字符串创建、字符串插值、新建 `Rect`/`Paint`/`Bundle`、反射与诊断字符串构造。
- 既有的小型有界分配不是用户可见缺陷的证据，但也不是首选模式。在修改受影响代码时移除确定性的每帧分配。
- 不要仅凭检查就宣称零分配、电量改善、卡顿减少或软件层成本安全。使用分配/帧/设备证据。
- 只有可见或过渡中的 surface 调度帧。隐藏/静态/分离的 surface 停止。
- AOD/SystemUI 中有意的周期性分配或软件渲染成本，在广泛启用之前需要简明的理由与设备证据。

## 10. 命名、函数结构、参数与注释

### 命名

- 类型是名词。`Controller` 拥有生命周期/变更；`Coordinator` 组合多个所有者；`Store` 拥有已校验状态；`Resolver`/`Engine` 计算策略；`Codec` 编码/解码；`Hook` 安装/拦截；`Snapshot`、`State`、`Environment` 与 `Result` 是不可变值。
- 函数是动词。谓词以 `is`、`has`、`can`、`should` 或 `supports` 开头。
- 布尔名称是正向且有范围的：`aodEnabled`、`sceneVisible`、`positionFollowingEnabled`。避免双重否定。
- 只有当 `current`、`latest`、`pending`、`cached`、`expected` 与 `active` 的生命周期含义互不相同且清晰时才使用。

### 函数与文件

- 每个函数一个主要职责，每个文件一个主要生产类型。相关的小型不可变模型/辅助类可以共享一个文件。
- 没有机械的行数限制。当函数混杂校验、解码、持久化、生命周期变更、几何与绘制时；当嵌套掩盖 fail-closed 出口时；或当抽取能创建有用的纯测试缝隙时，进行抽取。
- 大型渲染器/控制器文件在拆分会复制可变状态或增加分配/间接层时，可以保持内聚。新代码仍应把纯决策与 Android 生命周期代码隔离。
- 对失败的守卫与不支持的能力使用提前返回。保持成功路径可读。

### 参数

- 在多行调用以及含有多个相邻布尔、字符串、数字、可空值或集合的调用中，优先使用命名参数。
- 当一个内聚的参数组跨越多个方法或边界时，参数对象才是合理的，而不仅仅为了满足任意的参数数量。
- 避免充满哨兵值的可空参数长串。当状态含义实质不同时，使用经过校验的不可变值或 sealed 结果。

### 面向用户的文案

- 优先使用一个简洁、自解释的标题、标签或行标题。默认不要在标题、标签、卡片或设置下方添加副标题、辅助文字或描述性文案。
- 仅当用户要求，或为防止误解或错误而必需时才添加辅助文案：不可用或已禁用的能力、功耗/性能成本、外部前提、非显而易见的范围或破坏性确认。绝不用它复述标题。
- 显示当前值或运行状态的偏好 `summary` 不是描述性文案，不受上述规则约束。
- 诊断报告流程中的数据处理与策略披露是必需的同意文本，不是辅助文案。保持其完整。

### 面向用户的表单

- 多行输入从顶部/起始处开始。任务导向的占位文本在开始输入后消失。
- 将紧凑的实时限额计数器放在输入框内部底部/末尾。预留内边距使文本绝不与之重叠。即使计数器文案简短，也要保持字节边界契约的精确字节。
- 不要把限制埋在占位文本里，也不要在每个操作旁重复策略说明。保持标签简短，把详细披露放在链接的策略里。
- 主要操作可以使用整行。次要操作必须通过 wrap-content 高度、有界换行或堆叠来适应翻译后的标签；固定高度裁剪与溢出是缺陷。
- 所有面向用户的表单文本都来自 locale XML，包括占位文本、计数器、状态与操作。

### 注释与格式

- 解释不变式、ABI 约束、Xiaomi 怪癖、坐标空间、生命周期所有权、fail-closed 原因、经批准的 parity 偏差与设备观察到的变通。
- 不要叙述显而易见的 Kotlin 语法。
- Xiaomi 变通要写明已验证的符号/版本上下文，并指向相关的研究、诊断、行为规范或实现状态证据。
- 临时追踪包含有界的用途/移除条件，并在采集到证据后移除。绝不把歌词内容追踪留在生产环境启用。
- 遵循既有的 Kotlin 格式：四个空格、无制表符、标准命名、无通配导入，以及含尾随逗号的、对格式化工具友好的多行调用（在使用处）。不要重新格式化无关代码。

## 11. 测试

- 测试名称为小驼峰，并表达行为/条件/结果，例如 `activeNotificationStateWithoutCachedBoundsFailsClosed`。
- 非平凡测试用空行分隔 arrange、act 与 assert。每次只测一个行为。
- 对边界、规范化、projection 恒等性、放置、时值、行选择、来源/ruby 区间、状态机、generation/令牌拒绝、能力策略与 schema 安全使用纯 JVM 测试。
- 抽取最小的纯决策辅助函数，而不是构建重度依赖 mock 的 Android 测试。
- 边界测试在适用时覆盖有效、缺失、畸形、超大、过期、重复/重排、未来版本、错误用户、非有限、无效区间与未知枚举/值等情形。
- 生命周期测试覆盖当前、过期、被取代、已取消、已分离、已切换用户、已断开、已反转与超时的回调。
- DTO/编解码器映射测试为每个同类型字段使用互异的哨兵值。
- 正确性修复会添加一个在修复前会失败的最小聚焦回归测试，然后运行相关的更宽泛的宿主门禁。
- 宿主测试证明确定性逻辑，而不是 Xiaomi 运行时行为、视觉 parity、功耗、进程内存、hook 可行性或动画流畅度。

## 12. 文档与设备证据

- 在行为变更之前或同时更新相关规范。实现状态不会静默覆盖架构或行为契约。
- 当包/进程所有权、Binder 流、信任边界、能力门控、surface 附加、生命周期策略或自定义 schema 变更时，更新 `docs/ARCHITECTURE.md`。
- 渲染器行为或经批准的 AOD 偏差变更时，更新 `docs/private/PARITY-SPEC.md`（私有文档，未随公开树发布）。
- 可见性、隐私、碰撞、连续性、keepalive、fallback 或自定义策略变更时，更新 `docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md`。
- 对 Xiaomi 符号、hook、几何或版本的发现，更新相关研究/诊断记录。把历史诊断标记为已取代，而不是当作当前架构呈现。
- `docs/private/LOCKSCREEN_AOD_IMPLEMENTATION_STATUS.md`（私有文档，未随公开树发布）记录精确的宿主测试数量/结果、构建结果、APK SHA-256、安装时间、设备/序列号、SystemUI PID/重启原因、能力摘要、观察到的行为、崩溃/OOM/安全模式结果与剩余设备门禁。
- 精确使用证据标签：
  - `unit-tested` / `host-verified`：仅确定性本地测试；
  - `trace-observed`：由运行时日志/追踪支持，不一定经过视觉确认；
  - `device-smoke-tested`：模块已加载且基本流程存活；
  - `device-verified`：所命名行为已在所命名的构建/设备上执行，并具备所需的视觉/日志/崩溃证据。
- 绝不基于代码评审、宿主测试、旧 APK 或未执行相关流程的截图宣称设备验证。
- 专有 APK、JADX 输出、设备日志、截图与临时采集保留在被忽略的 `research/` 下。不要把内部研究产物复制到公开发布仓库。
- 影响 SystemUI 内存、AOD 节奏/功耗、burn-in 移动、linkage、锁屏隐私、通知几何、软件渲染或 Xiaomi 反射的变更，在广泛启用之前需要新的设备证据。

## 13. 评审规则

本指南既适用于前瞻性开发，也适用于被触碰代码的评审。既有的偏差是聚焦审计发现的候选，既不是重复它们的许可，也不是无关清理的理由。优先选择最小的连贯修正，辅以聚焦的测试与诚实的宿主/设备证据。
