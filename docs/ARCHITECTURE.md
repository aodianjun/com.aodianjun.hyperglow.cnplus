# HyperGlow Architecture

# English / 英文

Status: optional lockscreen + AOD package contract

## Ownership

`HyperGlow` owns Xiaomi lockscreen/AOD lyric delivery and module-owned rendering surfaces:

```text
Spotify / Spicy EX
  -> versioned Binder, provider fallback
  -> HyperGlow app process
  -> local playback projection and row selection
  -> UID-validated callback
  -> one validated SystemUI projection
      -> non-interactive lockscreen renderer under keyguard_translation_info
      -> non-measuring ViewGroupOverlay on the full-screen SystemUI AODView root
```

Package identity:

- application ID: `com.eza.hyperglow`
- producer Binder: `com.eza.hyperglow.bridge.SpicyLyricBridgeService`
- provider authority: `com.eza.hyperglow.spicybridge`
- SystemUI callback service: `com.eza.hyperglow.aod.AodLyricBridgeService`
- Xposed scope: `com.android.systemui`, `android` (system_server), `com.miui.aod` (standalone AOD process on some devices)

Lockscreen and AOD use separate physical renderer instances backed by one immutable lyric snapshot.
Views are never reparented between hosts. Xiaomi retains ownership of parent visibility, keyguard
authentication, and doze. During a validated lyric keepalive session, HyperGlow may clamp Xiaomi's
low nonzero AOD brightness request to Xiaomi's own readable AOD level only in exact `DOZE_AOD`.
Xiaomi keeps brightness authority in pause, pocket, proximity, off, finish, and inactive-guard states.
On exact verified AOD modes, the optional scene coordinator temporarily owns native AOD content/lyric
burn-in timing and placement while lyrics are active. The native target is Xiaomi's clock container,
including custom-image styles. Xiaomi's natural target is cached and restored when the lyric scene
ends. The landscape stock-suppression gate (`aod_landscape_hide_stock`) obeys the same ownership
rule: while active it may force Xiaomi's burn-in container subtree to GONE and rewrite visibility
requests, but every affected view's host-believed visibility is recorded and restored when the gate
closes — gate close never relies on Xiaomi re-issuing visibility calls.

## Exclusions

- HyperLyric notification listener and notification renderer
- HyperLyric MediaSession/provider lyric sources
- island hooks and general lyric UI
- frame-level IPC or continuous animation
- stock Xiaomi AOD content replacement, reparenting, measurement, styling, or lifecycle mutation
- arbitrary third-party code, classes, resources, scripts, or Android views in SystemUI
- writing Xiaomi lockscreen/AOD configuration settings

## Surface Contract

- SystemUI owns one Binder client and one cached surface-neutral lyric snapshot.
- Lockscreen and AOD subscribe independently and receive the same content/timing revision.
- The lockscreen renderer is inserted at child index `0` of `keyguard_translation_info`, remains
  visual-only, and inherits Xiaomi parent alpha/scale/swipe/bouncer behavior.
- The default lockscreen policy uses Xiaomi's `getClockBottom()` anchor and places the lyric card
  below visible notification/media/zen content inside the remaining bottom-safe region.
- Native notification layout, top padding, translation, animation, measurement, and scrolling are
  never modified. Insufficient space hides optional rows, shrinks lyrics to the bounded minimum,
  then fails closed.
- Lockscreen collision refreshes are latest-only per display frame. An eligible pre-draw sampler
  watches notification/media translation, alpha, scroll, clip bounds, actual height, and clip amounts;
  unchanged frames do not run the full geometry/reflection scan.
- The optional card scrim follows current rendered row bounds rather than the full canvas. Its
  horizontal footprint follows visible media-card bounds when available, with a bounded 92% fallback.
  The full-screen stack host is never treated as content geometry.
- The AOD renderer remains in the inner `AODView` root overlay. It never participates in Xiaomi
  clock-container measurement.
- Shared line-level frame drawing traverses prebuilt layout rows with indexed loops and scalar fill
  math; it does not build filtered row/width/progress collections per frame. Ruby base-run slicing is
  still residual layout debt tracked by the audit.
- AOD lifetime suppression is owned by a permanent SystemUI power coordinator and is active only
  while an AOD surface is attached and the validated projection requests keepalive. Canvas
  visibility, layout success, and linkage presentation do not control Xiaomi lifetime policy.
  A configured 5-minute, 10-minute, 30-minute, 1-hour, 2-hour, or indefinite session deadline bounds
  that keepalive intent; indefinite is the default. The deadline is owned by the projection power
  session policy, which stops requesting keepalive once it elapses, so the coordinator releases
  Xiaomi lifetime suppression through the existing intent path instead of a second timer. The
  deadline spans continuous eligible Spotify playback and is not reset by track/document/heartbeat
  updates. Lockscreen-only playback cannot suppress `smartHide()` or `hideDoze()`.
- AOD brightness support observes Xiaomi's exact doze state and raw brightness request. While the
  validated lifetime guard is active in `DOZE_AOD`, a low nonzero request is clamped to Xiaomi's
  reflective `CommonUtils.BRIGHTNESS_ON` value. Zero, off, invalid, already-readable, paused,
  pausing, plain-doze, and inactive-guard requests pass through unchanged. Guard activation and
  release re-submit Xiaomi's last raw request through its adapter, preserving Xiaomi timeout policy.
- Linkage uses two renderer instances and bounded geometry/alpha handoff. Stock linkage keeps the
  lockscreen instance as the semantic source through Xiaomi's bright SystemUI clock morph. The AOD
  instance is immediately visible opposite the exact rendered SystemUI clock-morph bounds when they
  are available. A conservative 35% top reserve is the fallback only when that exact view is absent.
  This bright collision phase follows physical clock/display state independently from lyric handoff
  eligibility, so paused retained lyrics remain safe even when the current projection is hidden.
  Normal geometry begins when an API display listener observes `DOZE`. No elapsed-time fallback
  initiates a visual transfer; a missed callback leaves the visible safe layout intact. Xiaomi
  remains authoritative for native parent animation and display-state timing.
- Unknown package versions or missing required symbol signatures disable only dependent features.

## Display Layout Contract

Bridge document version 1 accepts optional row-level `layoutGroups`. Each group carries UTF-16
source range, lexical kind, keep-together intent, and confidence. AOD projects these fields through
Binder without changing timed words. The module-local persisted `Adaptive sectioning` preference
defaults on: lexical chunks and secondary-row tokens are balanced across the required line count;
Japanese particles stay with preceding phrases, Chinese dictionary phrases stay together, and Korean
authored spaces remain break points. Groups wider than the canvas may emergency-break. With the
preference off, AOD ignores layout groups and restores upstream behavior: timed words wrap greedily
in source order without balancing, but transported fragments marked as parts of the same lexical word
remain indivisible. Untimed text uses `Paint.breakText`, and each secondary row stays a single clipped
line. Transported transliteration segments keep their original word timings even on that single line;
wrapping policy never converts them to row-global progress.

Each surface profile independently selects a main-lyric wrap limit of 1 through 5 lines or no user
limit. The renderer applies that limit before layout; no-limit layout remains bounded by the validated
500-character/128-word snapshot. Surface safe areas, maximum-height policy, optional-row removal, and
fail-closed placement remain authoritative. Transliteration and translation rows are static; their
profile toggle selects bright or dimmed presentation and never changes timing cadence. Line-level
left-to-right approximation traverses the cumulative widths of wrapped main rows sequentially. The
explicit whole-block compatibility mode is the sole exception: it retains the existing simultaneous
X sweep across every visible lyric row. Word/syllable timing remains unchanged.

## Security And Lifecycle

- Producer endpoints accept only UIDs containing `com.spotify.music`.
- AOD callback accepts only system UID containing `com.android.systemui`.
- Protocol, sequence, payload size, document identity, row count, word count, timing, and text bounds fail closed. Document pipes use the declared compressed byte count as an exact frame boundary and reject short/truncated frames.
- Closed producer render-mode strings are normalized once at bridge ingress. Current values are
  preserved, known legacy aliases are canonicalized, unknown values use producer-safe defaults, and
  custom text size is clamped before state enters projection/rendering.
- The producer retains at most one bounded immutable state and one compressed document for the
  current session. Each Binder connection receives state first, then document, once; normal service
  process death uses Android's existing automatic reconnect without creating a duplicate bind.
  Explicit clear, generation retirement, and disable discard both retained payloads.
- App-to-SystemUI lyric state keeps the `onState(Bundle)` ABI but carries a versioned scalar envelope; full snapshots use one encoded body bounded to 48 KiB aggregate UTF-8 text and 64 KiB encoded bytes. Hidden and keepalive messages remain scalar-only.
- The scalar envelope carries Spotify `playbackActive` explicitly. Power policy never infers pause
  from lyric visibility, media rows, another media player, or renderer state.
- A SystemUI user switch clears cached state, rejects old-user payloads, and rebinds the app service
  with the selected Android `UserHandle`; it does not keep targeting the owner-user app instance.
- Live lyric snapshots render only while the UID-validated Spotify projection explicitly reports
  playback active. A real Spotify pause may freeze the last valid snapshot at `speed=0` for one shared
  lockscreen/AOD timeout: 0, 5, 10, or 30 seconds, or indefinitely; the default is 5 seconds. A confirmed
  Spotify pause releases keepalive so Xiaomi may sleep normally. Loading edges and every other
  non-playing edge are first published as still-playing transport grace; a pause is confirmed only
  when the producer stays non-playing on the same session past the bounded confirmation window, which
  keeps a song change from releasing lifetime between two tracks. Other media players cannot start or
  extend either policy. Media-player removal, stale state, invalid data, Binder death, detach, or
  missing Xiaomi symbols clears output and restores stock AOD placement.
- Lockscreen lyrics require explicit opt-in and hide for unsupported/custom themes, secondary
  displays, bouncer/auth entry, stale state, or insufficient safe geometry.
- While visible playback is active, including loading/no-lyrics fallback, a 4-second bounded heartbeat
  refreshes snapshot freshness.
  It pulses Xiaomi's AOD draw wake lock for 5.5 seconds only when AOD keepalive is enabled.
- Device-verified `MiuiShowStyleController.smartHide()` and `hideDoze()` policy calls are suppressed
  only during the independent Spotify-playing AOD power session or a bounded playing-state transport
  grace. Each playing song
  generation receives an 8-second presentation lease; actual timed lyrics or the explicit unsynced
  override upgrade that lease to keepalive subject to the configured session duration. A finite
  duration begins on the inactive-to-active keepalive edge and does not reset on song generations or
  heartbeat traffic. Pause/stop ends the session immediately; a later eligible playback session may
  start a new timer. A suppressed hide replays when power intent expires, the duration elapses, or
  bridge state becomes stale/cleared, and only when the captured controller generation and identity
  are still current.
- Keepalive sustains an attached AOD surface across Xiaomi smart-off and timing policy. It does not
  create an AOD surface when AOD is fully disabled in system settings.
- A generation-keyed wake signal is separately delivered through the exact Xiaomi
  `DozeHost.fireAodState(true, "reason_keycode_goto")` state-machine seam. This can restore a sleeping
  but enabled AOD for song-change presentation or later timed-lyric arrival; it does not bypass the
  system AOD master setting. The latest verified host is retained as one bounded SystemUI recovery
  reference across Xiaomi plugin teardown. While a persistent timed session is detached, bounded
  heartbeat retries reuse the current wake identity until an AOD surface returns.
- While lyrics are active, the experimental scene coordinator suppresses Xiaomi movement targets.
  The target is Xiaomi's native AOD content container, whether it currently shows a clock or custom
  image. The default static-bottom mode moves that container once to the verified bottom slot and
  holds it there while lyrics occupy the top free region. Static-top is the inverse fixed layout.
  Optional six-zone, four-corner, and vertical-swap schedules use bounded 30 s, 1 min, 2 min, or
  5 min intervals. The managed controller target is the movement/scheduling authority. Collision
  authority is ordered: the visible exact SystemUI morph clock, then the visible exact AOD
  position-controller `mTargetView`, then the managed requested target. This covers Xiaomi's
  bright-to-dim crossfade without assuming a requested translation has already rendered. Dynamic
  changes fade lyrics out, wait for Xiaomi's clock motion to settle, apply destination geometry once,
  then fade lyrics in. The full-width lyric canvas never traverses the clock's movement path.
- Xiaomi's latest natural target continues to be observed. It is restored immediately when lyrics
  clear/stale, the preference turns off, projection fails, or control becomes ineligible. Xiaomi
  then resumes natural scheduling without module timer activity.
- The lyric view lives in the AOD root overlay and never participates in `mTableModeContainer`
  measurement. Geometry reads and the dedicated verified translation hook are the only stock-clock
  interactions.

## User-triggered diagnostic reporting

The app process owns a dedicated Compose diagnostic destination, bounded metadata collection,
temporary guided-capture state, payload preview, HTTPS upload, and GitHub issue drafting. SystemUI
never performs network or repository work. It contributes only the UID-validated capability report
and privacy-safe `HyperGlow` log events already governed by the logging contract.

Capability protocol v2 adds report time, effective profile state, experimental state, raw exact-symbol
probe results, and the resolved capability set. The app accepts v1 during app/SystemUI process update
transitions. Raw probes include the AOD host container and lockscreen controller/host/geometry seams;
unknown profiles still resolve no runtime capabilities. Stored appearance preferences remain separate
from compatibility state.

Guided capture temporarily enables existing diagnostic logging and publishes the normal compiled
configuration. Finish executes only fixed, bounded root commands with no user-controlled shell text.
Root denial degrades to metadata-only reporting. The app process filters/redacts output, previews the
exact allowlisted JSON, uploads manually through one bounded `HttpURLConnection` request, and deletes
the temporary draft after cancellation, timeout, or success. The APK contains no intake credential.
See `docs/DIAGNOSTIC_REPORTING_SPEC.md`.

## Customization Boundary

Customization is versioned, declarative data compiled in the app process and defensively validated
again in SystemUI. SystemUI renders only a fixed internal widget registry. Imported documents may
select known widgets, anchors, semantic palette tokens, typography, main-lyric line limits, static
secondary-text brightness, and bounded transitions. They cannot name classes, resources, methods,
paths, URLs, commands, or executable code.

Lockscreen profiles also accept a bounded `backgroundStyle`: `auto`/`card` resolves to the built-in
noninteractive notification-style scrim; `none` remains transparent. AOD always sanitizes it to
`none`.

Migration preserves current AOD preferences. The initial lockscreen profile derives from those
values but remains disabled for existing users. Old preferences remain readable for one rollback
cycle after a successful migration.

## Observed Animation Capability

Device test (`0.1.9`, HyperOS AOD) confirms custom injected `View` animation is viable while the
surface remains attached. A per-word canvas loop using Spicy scale, vertical-offset, and glow
curves rendered as independent moving elements; it did not collapse into text swaps.

This raises the practical ceiling above conservative state-only estimates:

- Per-element transforms, alpha, glow, and sprite-style loops are viable.
- A 100 ms animation tick is visibly useful on this device.
- Smoothness still depends on AOD attachment and wake policy; detach, deep doze, or policy replay
  can interrupt the loop.
- Frame-perfect 60 FPS remains unverified and is not a contract.

## License

Initial transport and AOD surface code extracted and adapted from HyperLyric. HyperLyric is GPL-3.0; this package remains GPL-3.0. See `NOTICE` and `LICENSE`.

---

# 中文 / Chinese

状态：可选的锁屏 + AOD 模块契约

## 职责范围

`HyperGlow` 负责 Xiaomi 锁屏/AOD 歌词投递，以及由模块自有的渲染 surface：

```text
Spotify / Spicy EX
  -> versioned Binder, provider fallback
  -> HyperGlow app process
  -> local playback projection and row selection
  -> UID-validated callback
  -> one validated SystemUI projection
      -> non-interactive lockscreen renderer under keyguard_translation_info
      -> non-measuring ViewGroupOverlay on the full-screen SystemUI AODView root
```

模块包标识：

- application ID：`com.eza.hyperglow`
- 生产者 Binder：`com.eza.hyperglow.bridge.SpicyLyricBridgeService`
- provider authority：`com.eza.hyperglow.spicybridge`
- SystemUI 回调服务：`com.eza.hyperglow.aod.AodLyricBridgeService`
- Xposed 作用域：`com.android.systemui`、`android`（system_server）、`com.miui.aod`（部分设备上的独立 AOD 进程）

锁屏与 AOD 使用相互独立的物理渲染器实例，并由同一份不可变的歌词 snapshot 支撑。View 不会
在不同宿主之间重新挂载。父级可见性、锁屏鉴权与 doze 的控制权始终归 Xiaomi 所有。在一次经过
校验的歌词 keepalive 会话中，HyperGlow 仅在精确处于 `DOZE_AOD` 时，才可将 Xiaomi 较低的非零
AOD 亮度请求钳制到 Xiaomi 自身可读的 AOD 亮度级别。在暂停、口袋、接近、熄灭、结束以及非活跃
保护状态下，亮度权限仍由 Xiaomi 掌握。在精确校验的 AOD 模式下，可选的场景协调器会在歌词活跃
期间临时接管原生 AOD 内容/歌词的防烧屏（burn-in）时序与摆放。接管的原生目标是 Xiaomi 的时钟
容器，包括自定义图片样式。Xiaomi 的原始目标会被缓存，并在歌词场景结束时恢复。

## 排除范围

- HyperLyric 的通知监听器与通知渲染器
- HyperLyric 的 MediaSession/provider 歌词来源
- 灵动岛（island）hook 与通用歌词 UI
- 帧级 IPC 或连续动画
- 对 Xiaomi 原生 AOD 内容的替换、重新挂载、测量、样式修改或生命周期变更
- 在 SystemUI 中注入任意第三方代码、类、资源、脚本或 Android view
- 写入 Xiaomi 锁屏/AOD 配置设置

## Surface 契约

- SystemUI 只持有一个 Binder 客户端和一份缓存的、与 surface 无关的歌词 snapshot。
- 锁屏与 AOD 各自独立订阅，并接收同一份内容/时序 revision。
- 锁屏渲染器插入在 `keyguard_translation_info` 的 child index `0` 处，始终保持纯视觉角色，
  并继承 Xiaomi 父级的 alpha/缩放/滑动/bouncer 行为。
- 默认锁屏策略使用 Xiaomi 的 `getClockBottom()` 锚点，将歌词卡片放置在可见的通知/媒体/勿扰
  内容之下、剩余的底部安全区域内。
- 原生通知布局、顶部内边距、位移、动画、测量与滚动从不被修改。空间不足时先隐藏可选行，再将
  歌词压缩到受限的最小尺寸，然后按 fail-closed 处理。
- 锁屏碰撞刷新对每个显示帧只保留最新一次。一个符合条件的 pre-draw 采样器会监视通知/媒体的
  位移、alpha、滚动、裁剪边界、实际高度与裁剪量；未变化的帧不会执行完整的几何/反射扫描。
- 可选的卡片遮罩（scrim）跟随当前实际渲染的行边界，而不是整个画布。其水平覆盖范围在可见的
  媒体卡片边界可用时跟随该边界，否则回退到受限的 92% 宽度。全屏 stack 宿主永远不会被当作
  内容几何。
- AOD 渲染器始终位于内层 `AODView` 的根 overlay 中，从不参与 Xiaomi 时钟容器的测量。
- 共享的行级帧绘制以带索引的循环和标量填充计算遍历预构建的布局行；不会每帧构建过滤后的
  行/宽度/进度集合。Ruby base-run 切片仍是遗留的布局债务，由审计项跟踪。
- AOD 生命周期抑制由常驻的 SystemUI 电源协调器负责，仅在 AOD surface 已挂载且经过校验的
  projection 请求 keepalive 时生效。画布可见性、布局成败与联动呈现都不控制 Xiaomi 的生命周期
  策略。可配置的 5 分钟、10 分钟、30 分钟、1 小时、2 小时或不限时的会话截止时间对该 keepalive
  意图构成约束；默认为不限时。该截止时间由 projection 电源会话策略持有，到期后即停止请求
  keepalive，因此协调器通过既有的 intent 路径释放 Xiaomi 生命周期抑制，而不是引入第二个定时器。
  该截止时间覆盖连续且符合条件的 Spotify 播放，不会因曲目/文档/心跳更新而重置。仅锁屏播放
  无法抑制 `smartHide()` 或 `hideDoze()`。
- AOD 亮度支持会观测 Xiaomi 的精确 doze 状态与原始亮度请求。当经过校验的生命周期守护在
  `DOZE_AOD` 中生效时，较低的非零请求会被钳制到 Xiaomi 反射读取的
  `CommonUtils.BRIGHTNESS_ON` 值。零值、熄灭、无效、已可读、已暂停、正在暂停、普通 doze 以及
  非活跃保护的请求原样放行。守护的启用与释放都会通过其 adapter 重新提交 Xiaomi 的最后一次
  原始请求，保留 Xiaomi 的超时策略。
- 联动（linkage）使用两个渲染器实例与受限的几何/alpha 交接。原生联动在 Xiaomi 亮屏 SystemUI
  时钟形变期间，始终以锁屏实例作为语义源。当确切的已渲染 SystemUI 时钟形变边界可用时，AOD
  实例会立即出现在与其相对的位置。仅当该确切 view 缺失时，才回退使用保守的 35% 顶部预留。
  这一亮屏碰撞阶段独立于歌词交接资格、跟随物理时钟/屏幕状态，因此即使当前 projection 被隐藏，
  暂停后保留的歌词依然安全。当 API 级显示监听器观测到 `DOZE` 时才开始正常几何交接。任何基于
  经过时间的回退都不会发起视觉移交；错过的回调会让当前可见的安全布局保持原状。原生父级动画
  与显示状态时序的权威始终归 Xiaomi。
- 未知的软件包版本或缺失的必需符号签名只会禁用依赖它们的功能。

## 显示布局契约

Bridge 文档版本 1 接受可选的行级 `layoutGroups`。每个分组携带 UTF-16 源区间、词法类别、保持
同组的意图以及置信度。AOD 通过 Binder 投递这些字段，且不改变逐时歌词词。模块本地持久化的
`Adaptive sectioning` 偏好默认开启：词法块与次级行 token 会在所需行数内均衡分布；日语助词与其
前置短语保持同组，中文词典词组保持完整，韩语中手工输入的空格仍作为可断行点。宽度超过画布的
分组可以进行紧急断行。关闭该偏好后，AOD 会忽略布局分组并恢复上游行为：逐时词按源顺序贪婪
换行、不做均衡，但被标记为同一词法单词组成部分的传输片段仍不可拆分。非逐时文本使用
`Paint.breakText`，每个次级行保持为单条被裁剪的行。传输的音译片段即便位于该单一行上，也保留
其原始词级时序；换行策略永远不会把它们转换为整行全局进度。

每个 surface profile 独立选择 1 到 5 行的主歌词换行上限，或不设用户上限。渲染器在布局之前
应用该上限；无上限的布局仍受经过校验的 500 字符/128 词 snapshot 约束。surface 安全区、最大
高度策略、可选行移除与 fail-closed 摆放规则始终具有最高权威。音译行与翻译行是静态的；对应的
profile 开关只选择亮色或暗色呈现，从不改变时序节奏。行级的从左到右近似会按顺序遍历已换行
主行的累计宽度。显式的整块兼容模式是唯一例外：它保留现有的对所有可见歌词行同时进行的 X 方向
扫描。词/音节时序保持不变。

## 安全与生命周期

- 生产者端点只接受包含 `com.spotify.music` 的 UID。
- AOD 回调只接受包含 `com.android.systemui` 的系统 UID。
- 协议、序列号、载荷大小、文档标识、行数、词数、时序与文本边界一律 fail closed。文档管道以声明的压缩字节数作为精确的帧边界，并拒绝过短/截断的帧。
- 封闭的生产者渲染模式字符串会在 bridge 入口处一次性归一化。当前值原样保留，已知的遗留别名
  转换为规范形式，未知值使用对生产者安全的默认值，自定义文字大小会在状态进入 projection/渲染
  之前被钳制。
- 生产者为当前会话至多保留一份受限的不可变状态和一份压缩文档。每个 Binder 连接先接收一次
  状态，再接收一次文档；正常的服务进程死亡走 Android 既有的自动重连，不会产生重复绑定。
  显式清除、generation 退役与禁用操作都会丢弃这两份保留的载荷。
- App 到 SystemUI 的歌词状态保留 `onState(Bundle)` ABI，但携带版本化的标量信封；完整 snapshot 使用单一编码体，限制为 UTF-8 文本合计 48 KiB、编码后 64 KiB。隐藏与 keepalive 消息保持纯标量。
- 标量信封显式携带 Spotify 的 `playbackActive`。电源策略绝不会从歌词可见性、媒体行、其他媒体
  播放器或渲染器状态推断暂停。
- SystemUI 用户切换会清除缓存状态、拒绝旧用户载荷，并使用选定的 Android `UserHandle` 重新绑定
  app 服务；不会继续指向机主用户的 app 实例。
- 实时歌词 snapshot 仅在经过 UID 校验的 Spotify projection 显式报告播放处于活跃状态时渲染。
  真实的 Spotify 暂停可将最后一份有效 snapshot 以 `speed=0` 冻结一段锁屏/AOD 共享的超时时间：
  0、5、10 或 30 秒，或无限期；默认为 5 秒。已确认的 Spotify 暂开会释放 keepalive，让 Xiaomi
  正常休眠。加载边缘及其他所有非播放边缘会先以“仍处于播放中”的传输宽限发布；只有当生产者在
  同一会话上越过受限的确认窗口仍保持非播放时，暂停才被确认，从而避免两首曲目之间的换歌释放
  生命周期。其他媒体播放器无法启动或延长上述任一策略。媒体播放器移除、状态过期、数据无效、
  Binder 死亡、surface 脱离或 Xiaomi 符号缺失都会清除输出并恢复原生 AOD 摆放。
- 锁屏歌词需要显式开启，并在遇到不受支持/自定义主题、副屏、bouncer/鉴权入口、过期状态或安全
  几何不足时隐藏。
- 当可见播放处于活跃状态（包括加载中/无歌词回退）时，一个间隔 4 秒的受限心跳会刷新 snapshot
  的新鲜度。
  仅在启用 AOD keepalive 时，它才会为 Xiaomi 的 AOD 绘制 wake lock 发放 5.5 秒的脉冲。
- 仅在独立的 Spotify 播放期 AOD 电源会话或受限的播放状态传输宽限期间，设备已验证的
  `MiuiShowStyleController.smartHide()` 与 `hideDoze()` 策略调用才会被抑制。每个播放中的歌曲
  generation 获得一个 8 秒的呈现租约；实际的逐时歌词或显式的非同步覆盖会依据配置的会话时长
  把该租约升级为 keepalive。有限时长从非活跃到活跃的 keepalive 边沿开始计算，不会因歌曲
  generation 或心跳流量而重置。暂停/停止会立即结束会话；之后符合条件的播放会话可启动新的
  计时。被抑制的隐藏操作会在电源意图到期、时长耗尽或 bridge 状态过期/清除时重放，且仅在捕获
  的控制器 generation 与身份仍然匹配当前状态时执行。
- keepalive 让已挂载的 AOD surface 在 Xiaomi 智能熄灭与时序策略下持续存在。当系统设置中 AOD
  被完全禁用时，它不会创建 AOD surface。
- 一个以 generation 为键的唤醒信号会通过 Xiaomi 精确的
  `DozeHost.fireAodState(true, "reason_keycode_goto")` 状态机接缝单独下发。它可以为换歌呈现
  或后续逐时歌词的到达而恢复休眠但已启用的 AOD；不会绕过系统 AOD 总开关。最新的已验证 host
  会作为一份受限的 SystemUI 恢复引用保留，跨越 Xiaomi 插件的 teardown。当一个持续的逐时会话
  处于脱离状态时，受限的心跳重试会复用当前的唤醒身份，直到 AOD surface 返回。
- 歌词活跃期间，实验性的场景协调器会抑制 Xiaomi 的位移目标。抑制目标是 Xiaomi 的原生 AOD
  内容容器，无论其当前显示时钟还是自定义图片。默认的 static-bottom 模式会将该容器一次性移动
  到已验证的底部槽位，并在歌词占据顶部空闲区域期间保持不动。static-top 是相反的固定布局。
  可选的六分区、四角与垂直互换调度使用受限的 30 秒、1 分钟、2 分钟或 5 分钟间隔。受管理的
  控制器目标是位移/调度的权威。碰撞判定按顺序进行：可见且精确的 SystemUI 形变时钟，其次是
  可见且精确的 AOD 位置控制器 `mTargetView`，最后是受管理的请求目标。这覆盖了 Xiaomi 亮屏到
  暗屏的交叉淡变，且不假设请求的位移已经渲染完成。动态变化会先淡出歌词，等待 Xiaomi 的时钟
  运动稳定，一次性应用目标几何，然后再淡入歌词。全宽歌词画布永远不会穿过时钟的位移路径。
- Xiaomi 的最新原始目标会持续被观测。当歌词清除/过期、偏好关闭、projection 失败或控制不再
  符合条件时，它会立即恢复。随后 Xiaomi 在没有模块定时器活动的情况下恢复自然调度。
- 歌词 view 位于 AOD 根 overlay 中，从不参与 `mTableModeContainer` 的测量。几何读取与专用的
  已验证 translation hook 是与原生时钟仅有的交互。

## 用户触发的诊断上报

app 进程负责专用的 Compose 诊断入口、受限的元数据收集、临时的引导采集状态、载荷预览、HTTPS
上传以及 GitHub issue 起草。SystemUI 从不执行网络或仓库操作，只贡献经 UID 校验的 capability
报告以及已受日志契约约束的、隐私安全的 `HyperGlow` 日志事件。

capability 协议 v2 新增上报时间、生效 profile 状态、实验性状态、原始的精确符号 probe 结果
以及解析出的 capability 集合。app 在 app/SystemUI 进程更新过渡期间接受 v1。原始 probe 覆盖
AOD 宿主容器以及锁屏控制器/宿主/几何接缝；未知 profile 仍解析为无任何运行时 capability。
已存储的外观偏好与兼容性状态保持相互独立。

引导采集会临时启用既有的诊断日志并发布正常编译的配置。完成操作只执行固定的、受限的 root 命令，
不含任何用户可控的 shell 文本。root 被拒绝时降级为仅元数据上报。app 进程会对输出进行过滤/脱敏，
预览确切的白名单 JSON，通过一次受限的 `HttpURLConnection` 请求手动上传，并在取消、超时或成功后
删除临时草稿。APK 中不包含任何 intake 凭据。参见 `docs/DIAGNOSTIC_REPORTING_SPEC.md`。

## 自定义边界

自定义内容是在 app 进程中编译的带版本声明式数据，并在 SystemUI 中再次进行防御性校验。SystemUI
只渲染固定的内部 widget 注册表。导入的文档可以选择已知的 widget、锚点、语义化调色板 token、
排版、主歌词行数上限、静态次级文本亮度以及受限的过渡效果。它们不能指定类、资源、方法、路径、
URL、命令或任何可执行代码。

锁屏 profile 还接受受限的 `backgroundStyle`：`auto`/`card` 解析为内置的非交互通知式遮罩；
`none` 保持透明。AOD 始终将其规范化为 `none`。

迁移会保留当前的 AOD 偏好。初始锁屏 profile 派生自这些值，但对既有用户保持禁用。旧偏好在迁移
成功后的一个回滚周期内仍可读取。

## 实测动画能力

设备实测（`0.1.9`，HyperOS AOD）证实，在 surface 保持挂载期间，自定义注入的 `View` 动画是
可行的。一个逐词画布循环使用 Spicy 的缩放、垂直偏移与发光曲线，作为独立运动元素进行渲染；
它没有退化为文本切换。

这将实际能力上限提升到了高于保守的纯状态估算：

- 逐元素变换、alpha、发光与精灵式循环均可行。
- 在该设备上，100 毫秒的动画 tick 具有肉眼可见的效果。
- 流畅度仍取决于 AOD 挂载与唤醒策略；脱离、深度 doze 或策略重放都可能中断循环。
- 帧级完美的 60 FPS 尚未验证，不构成契约。

## 许可证

初始传输与 AOD surface 代码提取并改编自 HyperLyric。HyperLyric 采用 GPL-3.0；本模块包同样采用
GPL-3.0。参见 `NOTICE` 与 `LICENSE`。