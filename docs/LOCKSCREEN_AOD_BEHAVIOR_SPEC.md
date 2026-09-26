# Lockscreen + AOD Behavior Spec

# English / 英文

Status: implementation contract

This document extends `PARITY-SPEC.md` (private doc, not in the public tree). AOD rendering continues
to follow the existing parity contract. This spec defines surface visibility, privacy, continuity,
customization, and fallback.

## Shared snapshot

- SystemUI uses one validated Binder client and one immutable surface-neutral lyric snapshot. Binder state is decoded synchronously into owned immutable data under the shared count, UTF-8, and encoded-body limits before main-thread delivery.
- Lockscreen and AOD render separate views from the same content, row, timing anchor, and track
  generation.
- A newly attached surface receives the cached latest snapshot immediately.
- Stale expiry, Binder death, caller failure, or invalid payload hides every subscriber. A hidden
  state explicitly marked as a real Spotify pause may retain the last valid lyric snapshot under the
  shared bounded policy below. Terminal hidden state clears it.
- State/configuration carry the app user ID; a SystemUI user switch clears/rebinds and rejects the
  previous user's cached payload.
- Transliteration, translation, timed reading fragments, and ruby come from the current matching
  producer document once it arrives. Before that document exists (untimed tracks, or a document
  still in flight), scalar state may keep the original lyric line visible and may supply its
  auxiliary lines — a deliberate CN+ divergence from upstream v0.3.97, which drops scalar
  auxiliary lines entirely.
- A Chinese document carrying kana ruby is language-inconsistent producer data. Projection keeps
  the original lyric and rejects that row's ruby, whole-line romanization, and per-word
  romanization; it does not classify the lyric again or synthesize a replacement reading.
- A row fill end must remain inside the track duration. A producer fill end past that row's active
  window is clamped to the active end for rendering; this bounded mismatch does not discard the
  otherwise valid timed document or release keepalive.
- AOD keepalive and lockscreen screen-on policy remain independent. Neither can activate from the
  other surface alone.
- `playbackActive` comes only from the UID-validated Spotify bridge and is transported explicitly.
  Other media players cannot activate lyric keepalive.
- Live lyrics require `playbackActive=true`. The one shared `After Spotify pauses` setting applies to
  lockscreen and AOD: clear immediately, 5 seconds, 10 seconds, 30 seconds, or keep indefinitely.
  The default is 5 seconds. Other media players cannot start or extend the timer.

## Lockscreen visibility and privacy

Lockscreen lyrics are off by default for existing users. Showing media text while locked requires
explicit opt-in.

The lockscreen scene is visible only when all conditions pass:

```text
feature enabled
supported package versions and required symbol signatures
default Xiaomi lockscreen theme
primary display
keyguard showing
not bouncer/auth entry
fresh visible snapshot
minimum safe scene area
```

The view is visual-only: not clickable, focusable, long-clickable, touch-intercepting, or
accessibility-focusable. Xiaomi parent alpha/visibility remains authoritative.

The default lockscreen scene uses Xiaomi's `getClockBottom()` anchor. The optional built-in card
scrim is vertically tight to currently rendered rows, unions outgoing/incoming bounds during lyric
transitions, and follows visible media-card width when available. A bounded 92% width and dark-card
opacity are used when native media width is unavailable.

Notification geometry uses an 8 dp dead band against the last applied bounds. Smaller animation
jitter keeps the current lyric-card placement; larger movement updates collision placement normally.

Lockscreen card lifetime follows Xiaomi's stock Spotify media player:

```text
visible MiuiMediaHeaderView + current valid lyric snapshot -> show live card
visible MiuiMediaHeaderView + eligible Spotify pause inside configured timeout -> show frozen card
MiuiMediaHeaderView hidden -> hide lyric card
MiuiMediaHeaderView removed -> discard frozen card
projection disconnect/stale/invalid state -> discard frozen card
```

The frozen card projects playback position once at eligible Spotify pause receipt, then uses
`speed=0`. A 0-second timeout clears immediately. Finite timers use the original pause edge and are
not extended by replayed hidden messages or another player. Indefinite retention still clears on the
terminal conditions above.
Lockscreen may suppress automatic dim/sleep only when its explicit keep-awake setting is enabled,
the keyguard lyric scene and stock Spotify media player are visible, playback is active, and the
bouncer/authentication UI is absent. Pause, player removal, bouncer entry, surface loss, transition
to AOD, or feature disable releases the screen-on request immediately. Manual power-button sleep
remains authoritative. AOD separately preserves the last visible snapshot and position-following
state so the lyric scene and managed clock do not snap back immediately on pause. A confirmed Spotify pause
releases AOD keepalive and policy-hide suppression; Xiaomi may sleep normally while the frozen visual
snapshot remains available. Resume or a new visible snapshot replaces it normally.

When notifications are present, collision geometry comes from Xiaomi's stack-local child layout
state, not the full-screen stack host or parent-transformed global rectangles. Row height uses
`actualHeight` plus clip amounts/bounds when available. Invisible/alpha-zero children remain
reserved only during an active linkage transition; stable stale rows are ignored. `avoid` places the lyric card below the
measured native notification block inside the remaining bottom-safe region. Native notification top
padding, translation, animation, measurement, and scrolling are never modified. Optional rows hide
first, lyrics shrink to the bounded minimum, and insufficient/unknown geometry fails closed.

## AOD visibility and lifetime

- Existing AOD display behavior and migration are preserved.
- The renderer uses the inner AOD root overlay and never enters stock clock measurement.
- Native AOD position updates trigger coalesced lyric geometry refresh. The managed controller target is
  movement/scheduling authority. Collision authority prefers the visible exact `AnimationHelper`
  clock view, then the visible exact `AODUpdatePositionController.mTargetView`, then the managed
  requested target. This covers both SystemUI's bright morph and the AOD plugin's delayed/crossfaded
  rendering after `DOZE`. Recursive rendered-descendant unions remain forbidden.
- Custom-image and unmanaged AOD scenes may use measured native-content geometry before controller updates.
  Stock linkage scenes render immediately during the bright phase using exact SystemUI clock-morph
  bounds or the bounded 35% fallback, then adopt deterministic managed geometry at the verified dim
  seam.
- On exact verified normal/linkage position modes, the experimental scene coordinator may translate
  Xiaomi's native AOD content container and own burn-in timing only while lyrics are active. In
  normal mode that container includes both stock clock styles and custom-image styles.
- The `AOD clock or image` setting presents the existing policy as one choice. `Follow Xiaomi`
  leaves native-content translation to Xiaomi while HyperGlow observes the exact target and keeps
  the lyric canvas clear. Fixed and moving choices make HyperGlow the translation authority for the
  same clock-or-image container with the selected pattern.
- Managed-position exhaustion fallback (upstream `1537c58`) releases managed control so the scene
  follows Xiaomi's stock geometry only while `real-time clock follow` (实时跟随系统时钟) is
  enabled. In the anchored (clock-pinned) mode the integral pin keeps clock authority regardless
  of the selected pattern: exhaustion is logged without releasing control or latching, and clock
  behavior stays unchanged.
- Xiaomi movement callbacks remain observed so its latest natural target is cached, but their
  translation is suppressed during module ownership. The default `static_bottom` pattern moves the
  native clock-or-image container to the verified bottom zone once and holds it there while lyrics
  are active. Optional
  bounded timers select six-zone, four-corner, or vertical-swap positions at 30 s, 1 min, 2 min, or
  5 min intervals.
- Xiaomi linkage slot zero and later burn-in positions are fixed-grid coordinates, not random. The
  module registers the position controller at AOD-root attach and derives the initial natural target
  after valid layout instead of waiting for Xiaomi's delayed first `updateTranslation()` callback.
- Lyrics resolve inside the free region physically opposite the authoritative clock bounds. A
  managed dynamic-zone change is transactional: fade lyrics out for 150 ms, wait for Xiaomi's exact
  `DozeHost.updatePosition()` animation-completion callback, apply the destination geometry once,
  then fade lyrics in for 180 ms. A bounded 1500 ms timeout fails forward if the OEM callback is
  missed. The canvas does not continuously cross the clock path. Static managed placement remains
  visible without this movement transaction.
- Stock linkage has two physical ownership phases. While the display remains `ON`, the SystemUI
  lockscreen renderer remains the semantic source beside Xiaomi's morphing keyguard clock. Because
  Xiaomi may hide that source parent early, the prepared AOD renderer is immediately visible in the
  region opposite the exact rendered bounds captured from
  `AnimationHelper.mClockAnima.mAllContainer` (falling back to `mClockView`). Only when that exact
  view is unavailable does placement reserve the conservative top 35%. When
  `DozeService.setDozeScreenState(DOZE)` applies the
  dimmed display state, or the attached AOD root observes physical display state `DOZE`/
  `DOZE_SUSPEND`, authority settles directly into normal AOD geometry without a second module slide
  animation. The AOD root registers a display listener rather than relying only on an OEM hook.
  There is no timer-driven visual transfer: if the OEM callback is missed, the already-visible bright
  safe layout remains instead of moving early or disappearing. Waking
  before dim cancels back to the lockscreen source. Custom-image AOD retains its existing transition
  behavior.
- Bright clock collision state is physical presentation state, not semantic lyric-linkage state. It
  remains active when playback is paused and the current projection is hidden but an authorized
  retained AOD snapshot is restored. `SNAPSHOT_NOT_VISIBLE`, disabled lockscreen lyrics, or another
  semantic handoff rejection cannot make managed dim geometry override the still-morphing bright
  clock. Every observed default-display state change coalesces a geometry refresh, so entering
  `DOZE` also leaves the conservative bright slot even when no semantic transition is active.
- Outside managed placement, custom-image and unmanaged AOD scenes may still use compact measured
  stock-clock bounds. During managed placement, rendered/controller unions are forbidden because
  Xiaomi crossfade descendants and stale samples can reserve unrelated content or switch geometry
  authority mid-movement. Exact physical bounds use strict priority: SystemUI morph view, AOD
  controller target view, managed target. A physical bound is cleared when that exact view or any
  ancestor becomes hidden/transparent.
- Xiaomi may hide the stock media row before bright linkage finishes. Only while the lockscreen
  renderer is the active forward-handoff source may it retain the already-authorized frozen/latest
  snapshot without stock-media presence; normal stable lockscreen privacy policy is unchanged.
- Super-wallpaper, flip, unknown modes, invalid geometry, missing symbols, or inactive lyrics pass
  through Xiaomi's original translation unchanged.
- Disabling the feature, stock Spotify media-player removal, stale/disconnected projection, Binder
  failure, or failed surface eligibility releases static/moving ownership, cancels any module timer,
  and restores Xiaomi's last unmodified translation target. An eligible Spotify pause retains the
  frozen AOD scene and current managed clock placement only for the shared configured timeout.
- A playing song-generation change starts an 8-second presentation lease and emits a wake event so
  synced and unsynced songs may briefly present song-change metadata. Presentation policy shows the
  title and artist at lyric size for three seconds, then morphs or crossfades to persistent small song
  info when enabled; otherwise it removes the title/artist. An active opening lyric or an opening gap
  shorter than three seconds defers one full intro to the next interlude with at least three seconds
  available. This state is generation-bound and consumed at most once per song. It does not alter
  playback, pause retention, wake identity, keepalive, or AOD lifetime policy. The first accepted timed
  document for that generation emits a second wake event, allowing a synced track to restore AOD
  after an earlier unsynced track timed out. The exact verified wake broker calls Xiaomi's
  `DozeHost.fireAodState(true, "reason_keycode_goto")` only while the device is non-interactive.
- Default keepalive additionally requires a `Line` or `Syllable` document containing at least one
  positive-duration row. Timed-document arrival upgrades the current presentation lease to persistent
  keepalive without a false gap. Static, missing, loading, no-lyrics, and degenerate zero-duration
  documents release naturally when the lease expires. `Also keep AOD active without timed lyrics`
  upgrades those untimed states to persistent keepalive. The main keep-awake preference must be on
  for lease, timed, and override modes.
- `Keep AOD active for` bounds the continuous Spotify-playing lifetime session to 5 minutes,
  10 minutes, 30 minutes, 1 hour, 2 hours, or Indefinitely; Indefinitely is the default. A finite
  timer begins when keepalive first becomes active inside a continuous playing streak. Song changes,
  document changes, wake events, transport grace, and freshness heartbeats do not reset it, and a
  presentation-lease gap inside the same playing streak does not re-anchor it. Expiry releases Xiaomi
  lifetime suppression while playback may continue; Spotify pause/stop still releases immediately.
  The next eligible playback session after that non-playing edge may start a new timer.
- Xiaomi lifetime suppression is independent of canvas visibility, layout, and linkage ownership.
  It requires only an attached AOD surface, validated keepalive intent, and the exact lifetime
  capability. Expiry of the configured duration withdraws that keepalive intent at the projection,
  so no separate SystemUI timer exists. The duration is lifetime policy only; it does not
  alter wake identity, presentation leases, content capability, pause retention, or renderer state.
  Draw-wake renewal remains a separate renderer concern.
- The validated lifetime guard also owns one narrow brightness override. In exact Xiaomi
  `DOZE_AOD`, a low nonzero request through `MiuiDozeBrightnessTimeoutAdapter` is clamped to Xiaomi's
  own positive `CommonUtils.BRIGHTNESS_ON` value. Requests at or above that value pass through.
  Zero/off requests and every request in `DOZE_AOD_PAUSING`, `DOZE_AOD_PAUSED`, plain `DOZE`, pulse,
  finish, unknown state, or inactive guard pass through unchanged. This keeps pocket and proximity
  pause authoritative. Guard activation and release re-submit Xiaomi's last raw request through the
  stock adapter, so Xiaomi keeps its native brightness timeout behavior and regains control on the
  stock adapter's normal delay when lyric keepalive ends.
- A transient hidden edge explicitly marked as Spotify still playing starts a bounded 30-second
  power grace after any snapshot carrying validated keepalive intent. Timed lyrics and untimed
  sessions held by `Also keep AOD active without timed lyrics` are equally eligible; lyric timing is
  content capability and never gates lifetime policy. The next visible snapshot cancels the grace
  without replaying Xiaomi hide policy. Paused/non-playing state releases immediately. This prevents short
  producer/status gaps from turning AOD off mid-song. Projection stale retains an already-active
  validated keepalive request; disconnect, explicit clear, pause, and grace expiry still release it.
- A non-playing `loading` edge during song replacement is projected as that bounded still-playing
  transport gap. Every other non-playing edge is provisional: Spotify reports the ending track as
  `ready`/not playing roughly a second before the next generation arrives, so the edge is first
  projected as the same still-playing transport gap and only becomes real pause retention when the
  producer is still non-playing on the same session after a bounded 5-second confirmation window.
  A resumed producer or a new session inside that window cancels the pending pause, so a song change
  never releases AOD lifetime or replays Xiaomi hide policy. The window opens once per session; a
  producer that keeps publishing while paused must not reopen it.
- Ready, loading, and no-lyrics visible playback all receive the same 4-second freshness heartbeat;
  unchanged fallback snapshots are refreshed instead of expiring after 5 seconds.
- The wake broker retains the latest verified `DozeHost` as one bounded recovery reference across
  AOD plugin teardown. If a persistent session has no attached AOD surface, each bounded heartbeat
  may retry the same wake identity until Xiaomi recreates the surface. Interactive-screen requests
  remain suppressed and the system AOD master setting remains authoritative.
- A keepalive edge that arrives while Xiaomi is already hiding AOD cannot be suppressed: the policy
  hide has run, its alarm can no longer be cancelled, and a wake delivered mid-animation only re-arms
  Xiaomi's own timer. That single race re-asserts the current wake identity once, on the first
  powered-off AOD display edge within the bounded hide-animation window after an inactive-to-active
  lifetime edge, and only while the surface stays attached. Recovery is armed by that guard edge only
  when AOD was still presenting, is consumed by the first off edge, and requires a fresh guard
  activation to re-arm. A session that has never dispatched a wake carries no identity and does not
  recover.
- Display power is otherwise Xiaomi's to own. A sensor or pocket pause, a deliberate sleep, an
  expired session, and a released lease all reach the same powered-off edge, and re-waking them
  fights Xiaomi in a self-sustaining loop that relights the panel every few seconds. Keepalive never
  treats a powered-off AOD display as a standing reason to wake, and the wake broker's minimum
  request interval is not a substitute for that bound.
- A confirmed Spotify pause releases the lifetime guard once, at most one confirmation window after
  the edge; its frozen card may remain only for the
  shared configured timeout, until Xiaomi sleeps, or until the stock media player is removed,
  whichever ends presentation first.
- Lockscreen attachment or visibility alone never suppresses Xiaomi hide policy.

## Lockscreen customization gestures

- `Block lock screen customization` is off by default and independent of lyric visibility.
- On the exact verified Xiaomi profile, suppression is limited to
  `KeyguardEditorHelper.onTouchEvent(MotionEvent)`, the final `tryStartEditActivity()` launch gate,
  and `LockScreenMagazineController.handleSingleClickEvent()`.
- The setting blocks the editor long press and the wallpaper-carousel single-tap preview.
- No generic lockscreen touch listener is replaced. Swipe, notification, media, power, fingerprint,
  biometric, and accessibility paths remain stock.
- Missing methods, unknown package versions, or a disabled setting pass through unchanged.

## Raise gesture remap

- HyperOS `Raise to wake` remains the sensor master switch. The module does not force-enable it and
  does not register a second pickup sensor.
- When `Raise to show AOD` is enabled on the exact verified profile, only SystemUI wake calls whose
  detail is `com.android.systemui:PICK_UP` are remapped. The module first requests AOD through the
  verified Xiaomi `DozeHost.fireAodState(true, "reason_keycode_goto")` state-machine seam, then
  always suppresses the full wake. If AOD is already sustained by active lyrics, the request is
  effectively redundant and the existing AOD remains visible.
- The remap is global for this owner device and does not depend on Spotify, lyrics, media state, or
  either lyric surface being enabled.
- Power-button, fingerprint, double-tap, notification, biometric, camera, and application wake
  reasons always pass through unchanged.
- With the setting enabled, an unavailable wake host leaves the device non-interactive rather than
  entering the lockscreen. Missing wake-hook symbols, unknown package versions, a disabled module
  setting, or disabled HyperOS `Raise to wake` retain stock behavior.

## Continuity

- Linkage uses separate lockscreen and AOD renderers; no view reparenting.
- Handoff state is bounded, reversible, and protected by monotonic tokens.
- Row selection may freeze for at most 600 ms while word/fill timing continues from the same elapsed
  time anchor.
- Track-generation change cancels the freeze immediately.
- Source and target rectangles are captured in window coordinates. Missing geometry degrades to an
  alpha-only handoff; missing target degrades to native attach/detach visibility.
- Reverse handoff waits only for a short bounded target-geometry stabilization, then starts the
  lockscreen lyric from a small positive Y offset and slides it upward while Xiaomi reveals the
  parent. The target animation must not complete while the lyric view is still invisible.
- Forward handoff preserves the AOD target alpha animation through geometry and wake refreshes;
  layout/wake callbacks must not reset the target to alpha `1` mid-transition.
- Normal line enter/exit animation is suppressed during handoff and resumes after settle.
- Xiaomi native keyguard parent animation is never overridden.
- Lockscreen reveal animates the complete card container as one unit. Text, adaptive background,
  outline, and media progress share the same alpha and upward translation timeline.

## Declarative customization

- Documents are versioned data, not plugins.
- App-process compilation performs migration, normalization, capability filtering, limits, and
  stable revision hashing. SystemUI validates again.
- Configuration target size is below 32 KiB; hard maximum is 64 KiB.
- Total widgets target at most 8; AOD visible widgets at most 4.
- Unknown widgets are dropped. No valid lyric widget falls back to the built-in safe profile.
- Lockscreen `backgroundStyle` accepts only `auto`, `card`, or `none`; AOD always resolves it to
  `none`.
- Line-level progress keeps `None`, `Top to bottom`, and a main-only `Left to right` approximate mode,
  plus a distinct explicit whole-block compatibility mode. Approximate left-to-right progress treats
  all wrapped main-lyric rows as one continuous sequence: complete one visual row left-to-right, then
  continue on the next. Normal gradient/progress animation affects only the main lyric; ruby,
  transliteration, and translation remain static. The whole-block option alone preserves the current
  simultaneous sweep across all visible lyric rows and must not normalize to main-only. Each surface
  profile independently selects bright or dimmed secondary-text presentation. Word/syllable-level
  synchronization is unchanged.
- Each surface profile may also show the upcoming lyric line (second lyric line) as secondary text.
  That presentation borrows the secondary-text sizing and the profile's bright/dim secondary
  selection but keeps the next-line color setting; when enabled it replaces the standalone next-line
  row instead of stacking with it, and when it is off the standalone next-line presentation is
  unchanged.
- Song info and the second lyric line each carry their own per-surface alignment choice (`auto`,
  `start`, `center`, `end`). `auto` follows the resolved main lyric alignment (the main `auto` still
  right-aligns right-to-left lyrics); explicit values align that row independently of the main
  lyric. Both second-line presentations (secondary-text form and standalone next-line row) share the
  one second-line alignment choice.
- Line-change animation is selectable per surface profile from a fixed vocabulary: `Auto`, `Fade up`,
  `Crossfade`, `Slide up`, `Slide left`, `Zoom`, or `None`. `Auto` keeps the lyric source's own
  preference; any explicit choice overrides the source preference, including `None`. `None` performs
  no line enter/exit animation. `Fade up` is the historical default and must remain pixel-identical
  to it. A transition draws the frozen outgoing rows against the incoming rows: the outgoing layer
  completes in 130 ms and the incoming layer in 210 ms from one elapsed anchor, and motion is limited
  to fade, upward/leftward translation, and content-centered zoom scale. Exit and enter progress
  each pass through a cubic curve (ease-in on exit, ease-out on enter) before frame recipes are
  sampled; the metadata fade stays linear. Unknown profile values normalize to `Auto`; legacy
  lowercase source aliases `continuity`, `crossfade`, and `none` map to `Fade up`, `Crossfade`, and
  `None`, and an unknown wire value is fail-safe `Fade up` — never a novel animation.
- Main lyrics accept a per-surface wrap limit of 1, 2, 3, 4, 5, or no user limit. Text size up to 200%
  must use the selected limit rather than the old fixed three-line ceiling. Safe-area geometry,
  optional-row removal, bounded minimum size, and fail-closed placement remain authoritative.
- Each surface profile stores metadata size from 50% to 200% and ruby-reading visibility. Ruby is
  shown by default and, when disabled, reserves no drawing or layout height.
- During the generation-bound song intro, matching one-line title/artist text suppresses the duplicate
  metadata row and morphs into the persistent metadata position and size after three seconds.
  Incompatible or wrapped geometry uses bounded crossfade. Neither path changes whole-surface alpha,
  the keepalive brightness policy, or placement authority.
- Imported data cannot name classes, resources, methods, paths, URLs, commands, or external bitmap
  sources.
- Reset restores the built-in safe profile.

Enabled fixed registry:

- lyrics;
- metadata;
- media_progress on lockscreen only.

Artwork accent, status text, spacer, and divider remain rejected until each has a real bounded
renderer, placement contract, privacy/power analysis, and device evidence. AOD progress/artwork
remain sanitized out.

AOD policy may further reduce luminance, bright area, animation, artwork, component count, or scene
size regardless of user/imported values.

## Migration

- Existing `aod_render` values populate the default AOD profile without visible regression.
- The initial lockscreen profile derives from AOD styling but remains disabled.
- Linked surface styling defaults on once lockscreen is enabled.
- Seamless transition defaults on only when both surfaces are enabled and linkage capabilities pass.
- Migration version is written only after successful validation/persistence.
- Legacy AOD preferences remain available for one rollback cycle.

## Capability fallback

The app displays explicit support state from the latest accepted capability report: no report,
verified, verified with missing symbols, unsupported, experimental-eligible, or experimental-active.
Configured surface preferences remain stored on unsupported profiles, but the app must describe them
as unable to run and disable runtime-dependent controls. Appearance editors remain usable for preview
and future configuration. The user can create a compatibility report containing package versions and
bounded raw-symbol evidence.

Capability report protocol v2 includes the report timestamp, effective profile state, experimental
state, raw probe set, and resolved capability set. Protocol v1 remains accepted only for app/SystemUI
update transition compatibility. Unknown profiles remain fail-closed; raw probe success alone does not
install or enable a hook.

Capabilities are independent:

```text
AOD_SURFACE
AOD_POSITION_UPDATES
AOD_LIFETIME_GUARD
AOD_WAKE_BROKER
LOCKSCREEN_HOST
LOCKSCREEN_GEOMETRY
LINKAGE_DIRECTION
LINKAGE_GEOMETRY
RAISE_TO_AOD
FULL_AOD
VIDEO_DEPTH
```

Matching includes SystemUI/AOD package versions and exact required symbol signatures. Unknown or
missing symbols disable only dependent behavior. Stock UI is never hidden, replaced, reparented,
remeasured, or restyled. Clock translation control is allowed only by the verified AOD scene policy
above and must fail back to Xiaomi's original target.

---

# 中文 / Chinese

状态：实现契约

本文档是 `PARITY-SPEC.md`（私有文档，未随公开树发布）的扩展。AOD 渲染继续遵循既有的 parity 契约。本规范定义 surface 可见性、隐私、连续性、自定义与 fallback。

## 共享 snapshot

- SystemUI 使用一个经过校验的 Binder 客户端和一个不可变的、surface 中立的歌词 snapshot。Binder 状态在投递到主线程之前，同步解码为自有不可变数据，并遵守共享的数量、UTF-8 与编码体积限制。
- 锁屏与 AOD 基于相同的内容、行、时间锚点与曲目 generation 渲染各自的视图。
- 新附加的 surface 会立即收到缓存的最新 snapshot。
- 过期、Binder 死亡、调用方失败或无效 payload 会隐藏所有订阅方。被显式标记为真实 Spotify 暂停的隐藏状态，可以按照下文的共享有界策略保留最后的有效歌词 snapshot。终态隐藏状态会将其清除。
- 状态/配置携带应用用户 ID；SystemUI 用户切换时会清除/重新绑定，并拒绝前一用户的缓存 payload。
- 音译、翻译、带时值的朗读片段与注音（ruby）来自当前匹配的生产者文档（一旦到达）。在该文档存在之前（未带时值的曲目，或文档仍在传输中），标量状态可以保持原歌词行可见，并可以提供其辅助行——这是相对上游 v0.3.97 的一个有意的 CN+ 分歧，上游会完全丢弃标量辅助行。
- 携带假名注音（kana ruby）的中文文档属于语言不一致的生产者数据。Projection 保留原歌词，并拒绝该行的 ruby、整行罗马音与逐词罗马音；不会重新对歌词进行语言分类，也不会合成替代朗读。
- 行填充结束点必须保持在曲目时长之内。超出该行生效窗口的生产者填充结束点，在渲染时会被钳制到生效结束点；这一有界失配不会导致本来有效的带时值文档被丢弃，也不会释放 keepalive。
- AOD keepalive 与锁屏亮屏策略保持相互独立。任一策略都不能仅凭另一 surface 的状态而激活。
- `playbackActive` 仅来自经过 UID 校验的 Spotify bridge，并被显式传输。其他媒体播放器无法激活歌词 keepalive。
- 实时歌词要求 `playbackActive=true`。唯一的共享设置 `After Spotify pauses` 同时适用于锁屏和 AOD：立即清除、5 秒、10 秒、30 秒或无限期保留。默认为 5 秒。其他媒体播放器无法启动或延长该计时器。

## 锁屏可见性与隐私

对现有用户而言，锁屏歌词默认关闭。在锁定状态下显示媒体文本需要显式选择加入。

锁屏场景只有在所有条件都通过时才可见：

```text
feature enabled
supported package versions and required symbol signatures
default Xiaomi lockscreen theme
primary display
keyguard showing
not bouncer/auth entry
fresh visible snapshot
minimum safe scene area
```

该视图仅为视觉呈现：不可点击、不可聚焦、不可长按、不拦截触摸、不可被无障碍聚焦。Xiaomi 父视图的 alpha/可见性始终是权威来源。

默认锁屏场景使用 Xiaomi 的 `getClockBottom()` 锚点。可选的内置卡片 scrim 在垂直方向与当前渲染的行紧密贴合，在歌词过渡期间对离场/入场边界取并集，并在可用时跟随可见的媒体卡片宽度。当原生媒体宽度不可用时，使用有界的 92% 宽度与深色卡片不透明度。

通知几何对上次应用的边界采用 8 dp 死区。较小的动画抖动保持当前歌词卡片的位置不变；较大的移动则正常更新碰撞位置。

锁屏卡片生命周期遵循 Xiaomi 原生 Spotify 媒体播放器：

```text
visible MiuiMediaHeaderView + current valid lyric snapshot -> show live card
visible MiuiMediaHeaderView + eligible Spotify pause inside configured timeout -> show frozen card
MiuiMediaHeaderView hidden -> hide lyric card
MiuiMediaHeaderView removed -> discard frozen card
projection disconnect/stale/invalid state -> discard frozen card
```

冻结卡片在收到符合条件的 Spotify 暂停时对播放位置做一次投影，随后使用 `speed=0`。0 秒超时立即清除。有限计时器使用原始暂停沿，不会因重放的隐藏消息或另一个播放器而延长。无限期保留仍会在上述终止条件下清除。
只有当显式的保持唤醒设置已启用、锁屏歌词场景与原生 Spotify 媒体播放器可见、播放处于活动状态、且不存在 bouncer/认证 UI 时，锁屏才可能抑制自动变暗/休眠。暂停、播放器移除、bouncer 进入、surface 丢失、切换到 AOD 或禁用功能，都会立即释放亮屏请求。手动电源键休眠始终具有最高权威。AOD 单独保留最后可见 snapshot 与位置跟随状态，因此歌词场景和受管时钟不会在暂停时立即回跳。已确认的 Spotify 暂停会释放 AOD keepalive 与策略隐藏抑制；Xiaomi 可以正常休眠，同时冻结的视觉 snapshot 仍然可用。恢复播放或新的可见 snapshot 会正常替换它。

当通知存在时，碰撞几何来自 Xiaomi 的栈内（stack-local）子项布局状态，而不是全屏栈宿主或经父变换的全局矩形。行高度使用 `actualHeight` 加上可用的裁剪量/边界。不可见/alpha 为零的子项仅在 linkage 过渡进行期间保持占位；稳定的过期行会被忽略。`avoid` 将歌词卡片放置在测得的原生通知块下方、剩余的底部安全区域内。原生通知的顶部内边距、位移、动画、测量与滚动从不被修改。可选行先隐藏，歌词收缩到有界最小值，几何不足/未知时按 fail-closed 处理。

## AOD 可见性与生命周期

- 既有的 AOD 显示行为与迁移得到保留。
- 渲染器使用 AOD 根部内侧 overlay，绝不进入原生时钟测量。
- 原生 AOD 位置更新触发合并后的歌词几何刷新。受管控制器目标是移动/调度权威。碰撞判定权威优先选择可见的精确 `AnimationHelper` 时钟视图，其次是可见的精确 `AODUpdatePositionController.mTargetView`，再次是受管请求目标。这同时覆盖 SystemUI 的亮屏形变与 AOD 插件在 `DOZE` 之后延迟/交叉淡化的渲染。递归的渲染后代并集仍然被禁止。
- 自定义图像与不受管的 AOD 场景可以在控制器更新之前使用测得的原生内容几何。原生 linkage 场景在亮屏阶段立即渲染，使用精确的 SystemUI 时钟形变边界或有界的 35% fallback，然后在经验证的暗屏接缝处采用确定性的受管几何。
- 在精确验证的普通/linkage 位置模式下，实验性场景协调器仅在歌词处于活动状态时，才可能平移 Xiaomi 的原生 AOD 内容容器并接管 burn-in 计时。在普通模式下，该容器同时包含原生时钟样式与自定义图像样式。
- `AOD clock or image` 设置将既有策略呈现为一个选项。`Follow Xiaomi` 将原生内容平移留给 Xiaomi，HyperGlow 只观察精确目标并保持歌词画布干净。固定与移动选项则让 HyperGlow 成为同一时钟或图像容器的平移权威，并应用所选模式。
- 受管位置重试耗尽的回落（上游 `1537c58`）仅在开启「实时跟随系统时钟」时释放托管控制、让场景跟随 Xiaomi 原厂几何。锚定（时钟钉住）模式下 integral pin 始终拥有时钟权威（与所选图案无关）：耗尽仅记日志，不释放托管控制、不置 latch，时钟行为保持不变。
- Xiaomi 的移动回调仍被监听，以便缓存其最新自然目标，但在模块接管期间其平移被抑制。默认的 `static_bottom` 模式将原生时钟或图像容器一次性移动到经验证的底部区域，并在歌词活动期间保持在那里。可选的有界计时器可按 30 秒、1 分钟、2 分钟或 5 分钟的间隔选择六分区、四角或垂直交换位置。
- Xiaomi linkage 的零号槽位及后续 burn-in 位置都是固定网格坐标，并非随机。模块在 AOD 根附加时注册位置控制器，并在有效布局后推导初始自然目标，而不是等待 Xiaomi 延迟的首次 `updateTranslation()` 回调。
- 歌词在物理上与权威时钟边界相对的空闲区域内解析。受管动态区域变更是事务性的：歌词淡出 150 毫秒，等待 Xiaomi 精确的 `DozeHost.updatePosition()` 动画完成回调，一次性应用目标几何，然后歌词淡入 180 毫秒。若错过 OEM 回调，有界的 1500 毫秒超时会向前失败（fails forward）。画布不会持续穿越时钟路径。静态受管位置无需该移动事务即可保持可见。
- 原生 linkage 有两个物理所有权阶段。当显示屏保持 `ON` 时，SystemUI 锁屏渲染器仍是 Xiaomi 形变锁屏时钟旁边的语义源。因为 Xiaomi 可能提前隐藏该源父视图，准备好的 AOD 渲染器会立即可见，位于从 `AnimationHelper.mClockAnima.mAllContainer`（fallback 到 `mClockView`）捕获的精确渲染边界的对侧区域。只有在该精确视图不可用时，位置才会保留保守的顶部 35%。当 `DozeService.setDozeScreenState(DOZE)` 应用暗屏显示状态，或附加的 AOD 根观察到物理显示状态 `DOZE`/`DOZE_SUSPEND` 时，权威直接落定为正常 AOD 几何，而没有第二次模块滑动动画。AOD 根会注册显示监听器，而不是仅依赖 OEM hook。不存在计时器驱动的视觉转移：如果错过 OEM 回调，已可见的亮屏安全布局保持不变，而不是提前移动或消失。在变暗之前唤醒会取消并回到锁屏源。自定义图像 AOD 保留其既有过渡行为。
- 亮屏时钟碰撞状态是物理呈现状态，而不是语义上的歌词 linkage 状态。当播放暂停且当前 projection 被隐藏、但已授权保留的 AOD snapshot 被恢复时，该状态仍保持活动。`SNAPSHOT_NOT_VISIBLE`、已禁用的锁屏歌词或其他语义交接拒绝，都不能让受管暗屏几何覆盖仍在形变的亮屏时钟。每一次观察到的默认显示状态变更都会合并一次几何刷新，因此进入 `DOZE` 时即使没有语义过渡在进行，也会离开保守的亮屏槽位。
- 在受管位置之外，自定义图像与不受管的 AOD 场景仍可使用紧凑测量的原生时钟边界。在受管位置期间，禁止渲染/控制器边界并集，因为 Xiaomi 交叉淡化后代与过期采样可能预留无关内容，或在移动中途切换几何权威。精确物理边界采用严格优先级：SystemUI 形变视图、AOD 控制器目标视图、受管目标。当该精确视图或任一祖先变为隐藏/透明时，对应物理边界即被清除。
- Xiaomi 可能在亮屏 linkage 完成之前隐藏原生媒体行。仅当锁屏渲染器是活动的前向交接源时，才允许在没有原生媒体存在的情况下保留已授权的冻结/最新 snapshot；正常稳定的锁屏隐私策略保持不变。
- 超级壁纸、翻盖、未知模式、无效几何、缺失符号或非活动歌词，均原样通过 Xiaomi 的原始平移。
- 禁用功能、原生 Spotify 媒体播放器移除、projection 过期/断开、Binder 失败或 surface 资格校验失败，会释放静态/移动所有权，取消任何模块计时器，并恢复 Xiaomi 上一次未被修改的平移目标。符合条件的 Spotify 暂停仅在共享的配置超时内保留冻结的 AOD 场景与当前受管时钟位置。
- 播放中的曲目 generation 变更会启动一个 8 秒的呈现租约并发出一次 wake 事件，使已同步与未同步的曲目都能短暂呈现换歌元数据。呈现策略以歌词字号显示标题和艺术家 3 秒，随后（在启用时）形变或交叉淡化到持久的小号曲目信息；否则移除标题/艺术家。正在活动的开场歌词或短于 3 秒的开场间隙，会将一整段 intro 推迟到下一个可用时间不少于 3 秒的 interlude。该状态绑定于 generation，每首歌曲最多消耗一次。它不改变播放、暂停保留、wake 身份、keepalive 或 AOD 生命周期策略。该 generation 的第一个被接受的带时值文档会发出第二个 wake 事件，允许已同步曲目在前一个未同步曲目超时后恢复 AOD。精确验证的 wake broker 仅在设备处于非交互状态时调用 Xiaomi 的 `DozeHost.fireAodState(true, "reason_keycode_goto")`。
- 默认 keepalive 还额外要求存在包含至少一个正时长行的 `Line` 或 `Syllable` 文档。带时值文档到达会将当前呈现租约升级为持久 keepalive，且不产生虚假间隙。静态、缺失、加载中、无歌词以及退化的零时长文档在租约到期时自然释放。`Also keep AOD active without timed lyrics` 将这些未带时值状态升级为持久 keepalive。主保持唤醒偏好必须开启，租约、带时值与覆盖模式才能生效。
- `Keep AOD active for` 将连续 Spotify 播放的生命周期会话限定为 5 分钟、10 分钟、30 分钟、1 小时、2 小时或无限期；默认为无限期。有限计时器在 keepalive 首次于连续播放段内变为活动时启动。切歌、文档变更、wake 事件、transport grace 与新鲜度心跳都不会重置它，同一播放段内的呈现租约间隙也不会重新锚定它。到期会释放 Xiaomi 生命周期抑制，播放可以继续；Spotify 暂停/停止仍然立即释放。该非播放沿之后的下一个符合条件的播放会话可以启动新计时器。
- Xiaomi 生命周期抑制独立于画布可见性、布局与 linkage 所有权。它只要求已附加的 AOD surface、经校验的 keepalive 意图以及精确的生命周期能力。所配置时长的到期会在 projection 处撤回该 keepalive 意图，因此不存在单独的 SystemUI 计时器。该时长仅属于生命周期策略；它不改变 wake 身份、呈现租约、内容能力、暂停保留或渲染器状态。绘制唤醒的续期仍是渲染器侧的独立关注点。
- 经校验的生命周期守卫还拥有一个窄幅亮度覆盖。在精确的 Xiaomi `DOZE_AOD` 状态下，通过 `MiuiDozeBrightnessTimeoutAdapter` 的较低非零请求会被钳制到 Xiaomi 自身的正 `CommonUtils.BRIGHTNESS_ON` 值。达到或高于该值的请求直接通过。零/关闭请求，以及 `DOZE_AOD_PAUSING`、`DOZE_AOD_PAUSED`、普通 `DOZE`、pulse、finish、未知状态或非活动守卫中的所有请求，均原样通过。这使口袋与接近传感暂停保持权威。守卫激活与释放时会通过原生适配器重新提交 Xiaomi 的上一次原始请求，因此 Xiaomi 保持其原生亮度超时行为，并在歌词 keepalive 结束时按原生适配器的正常延迟重新获得控制。
- 一个被显式标记为 Spotify 仍在播放的瞬态隐藏沿，会在任何携带经校验 keepalive 意图的 snapshot 之后启动一个有界的 30 秒电源 grace。带时值歌词与由 `Also keep AOD active without timed lyrics` 保持的未带时值会话同样符合条件；歌词时值属于内容能力，绝不作为生命周期策略的门控。下一个可见 snapshot 会取消该 grace 而不重放 Xiaomi 隐藏策略。暂停/非播放状态立即释放。这防止短暂的生产者/状态间隙在歌曲中途中关闭 AOD。Projection 过期会保留已活动的经校验 keepalive 请求；断开连接、显式清除、暂停与 grace 到期仍然会释放它。
- 换歌期间的非播放 `loading` 沿被投影为该有界的仍在播放 transport 间隙。其他所有非播放沿都是暂定的：Spotify 会在下一个 generation 到达前大约一秒将结束曲目报告为 `ready`/未播放，因此该沿首先被投影为同样的仍在播放 transport 间隙，只有在有界的 5 秒确认窗口之后、生产者仍在同一会话上处于非播放状态时，才成为真正的暂停保留。在该窗口内恢复的生产者或新会话会取消待定暂停，因此切歌永远不会释放 AOD 生命周期或重放 Xiaomi 隐藏策略。该窗口每会话只打开一次；在暂停时仍持续发布的生产者不得重新打开它。
- ready、loading 与无歌词的可见播放都接收相同的 4 秒新鲜度心跳；未变化的 fallback snapshot 会被刷新，而不是在 5 秒后过期。
- wake broker 将最近验证的 `DozeHost` 保留为一个有界的恢复引用，跨越 AOD 插件卸载周期。如果持久会话没有附加的 AOD surface，每个有界心跳都可以重试相同的 wake 身份，直到 Xiaomi 重建该 surface。交互式亮屏请求保持被抑制，系统 AOD 总开关设置保持权威。
- 在 Xiaomi 已经开始隐藏 AOD 时到达的 keepalive 沿无法被抑制：策略隐藏已经执行，其 alarm 已无法取消，动画中途送达的 wake 只会重新武装 Xiaomi 自己的计时器。这一唯一竞态会在"非活动→活动"生命周期沿之后的有界隐藏动画窗口内、第一个 AOD 显示关闭沿上，一次性重新断言当前 wake 身份，且仅当 surface 保持附加时如此。恢复仅在该守卫沿发生时 AOD 仍在呈现的情况下被武装，由第一个关闭沿消耗，并需要新的守卫激活才能重新武装。从未派发过 wake 的会话不携带身份，也不会恢复。
- 显示电源除此之外归 Xiaomi 所有。传感器或口袋暂停、主动休眠、过期会话与已释放的租约都会到达同一个显示关闭沿，重新唤醒它们会与 Xiaomi 形成自我维持的循环，每隔几秒重新点亮面板。Keepalive 从不把已关闭的 AOD 显示当作持续的唤醒理由，wake broker 的最小请求间隔也不能替代该约束。
- 已确认的 Spotify 暂停会释放生命周期守卫一次，最迟在该沿之后一个确认窗口内；其冻结卡片仅可在共享的配置超时内、直到 Xiaomi 休眠或直到原生媒体播放器被移除之前保留，以最先结束呈现者为准。
- 仅锁屏附加或可见本身绝不会抑制 Xiaomi 隐藏策略。

## 锁屏自定义手势

- `Block lock screen customization` 默认关闭，且独立于歌词可见性。
- 在精确验证的 Xiaomi profile 上，抑制仅限于 `KeyguardEditorHelper.onTouchEvent(MotionEvent)`、最终的 `tryStartEditActivity()` 启动门，以及 `LockScreenMagazineController.handleSingleClickEvent()`。
- 该设置屏蔽编辑器长按与壁纸轮播单击预览。
- 不替换任何通用锁屏触摸监听器。滑动、通知、媒体、电源、指纹、生物识别与无障碍路径保持原生。
- 方法缺失、包版本未知或设置已禁用时，均原样通过。

## 抬起手势重映射

- HyperOS 的 `Raise to wake` 仍是传感器主开关。模块不会强制启用它，也不会注册第二个拾起传感器。
- 当 `Raise to show AOD` 在精确验证的 profile 上启用时，仅 detail 为 `com.android.systemui:PICK_UP` 的 SystemUI 唤醒调用会被重映射。模块首先通过已验证的 Xiaomi `DozeHost.fireAodState(true, "reason_keycode_goto")` 状态机接缝请求 AOD，然后总是抑制完整唤醒。如果 AOD 已由活动歌词维持，该请求实际上是冗余的，现有 AOD 保持可见。
- 该重映射对此机主设备是全局的，不依赖 Spotify、歌词、媒体状态或任一歌词 surface 的启用状态。
- 电源键、指纹、双击、通知、生物识别、相机与应用唤醒原因始终原样通过。
- 在该设置启用时，唤醒宿主不可用会使设备保持非交互状态，而不是进入锁屏。wake hook 符号缺失、包版本未知、模块设置禁用或 HyperOS `Raise to wake` 禁用时，均保留原生行为。

## 连续性

- Linkage 使用相互独立的锁屏与 AOD 渲染器；不做视图重新父级化（reparenting）。
- 交接状态是有界的、可逆的，并受单调令牌保护。
- 行选择最多可冻结 600 毫秒，同时逐字/填充时值仍从同一 elapsed 时间锚点继续。
- 曲目 generation 变更会立即取消冻结。
- 源与目标矩形以窗口坐标捕获。几何缺失时退化为仅 alpha 交接；目标缺失时退化为原生附加/分离可见性。
- 反向交接仅等待一个短的有界目标几何稳定期，然后从较小的正 Y 偏移启动锁屏歌词，并在 Xiaomi 显示父视图的同时向上滑动。目标动画不得在歌词视图仍不可见时完成。
- 前向交接在几何与 wake 刷新期间保持 AOD 目标 alpha 动画；布局/wake 回调不得在过渡中途将目标重置为 alpha `1`。
- 正常的行进入/退出动画在交接期间被抑制，并在稳定后恢复。
- Xiaomi 原生锁屏父视图动画从不被覆盖。
- 锁屏显示动画将完整卡片容器作为一个整体。文本、自适应背景、描边与媒体进度共享同一 alpha 与向上平移时间线。

## 声明式自定义
- 换行动画可在每个 surface profile 中从固定词表选择：`Auto`、`Fade up`、`Crossfade`、`Slide up`、`Slide left`、`Zoom` 或 `None`。`Auto` 保持歌词源自身的偏好；任何显式选择一票否决源偏好，包括 `None`。`None` 不执行任何行进入/退出动画。`Fade up` 是历史默认，必须与历史效果逐像素一致。过渡将冻结的旧行层与新行层叠加渲染：退场层在 130 毫秒内完成、入场层在 210 毫秒内完成，二者共用同一 elapsed 锚点；运动仅限于淡入淡出、上移/左移位移与绕内容中心的缩放。退场/入场进度先经 cubic 缓动（退场 easeIn、入场 easeOut）再查帧配方，元数据淡出保持线性。profile 未知值规范化为 `Auto`；历史小写来源别名 `continuity`、`crossfade` 与 `none` 分别映射为 `Fade up`、`Crossfade` 与 `None`，wire 未知值 fail-safe 为 `Fade up`——绝不引入新动画。

- 文档是带版本的数据，而不是插件。
- 应用进程编译执行迁移、规范化、能力过滤、限制与稳定的 revision 哈希。SystemUI 会再次校验。
- 配置目标大小低于 32 KiB；硬性上限为 64 KiB。
- 组件总数目标最多为 8；AOD 可见组件最多为 4。
- 未知组件会被丢弃。不存在有效歌词组件时，退回到内置安全 profile。
- 锁屏 `backgroundStyle` 仅接受 `auto`、`card` 或 `none`；AOD 始终将其解析为 `none`。
- 行级进度保留 `None`、`Top to bottom` 与仅主歌词的 `Left to right` 近似模式，另加一个独立的显式整块兼容模式。近似从左到右进度将所有换行的主歌词行视为一个连续序列：先自左向右完成一个视觉行，然后在下一行继续。正常的渐变/进度动画只作用于主歌词；ruby、音译与翻译保持静态。仅整块选项保留当前对所有可见歌词行的同时扫过效果，且不得规范化为仅主歌词。每个 surface profile 独立选择亮色或暗色的次要文本呈现。逐字/音节级同步保持不变。
- 每个 surface profile 还可以把下一行歌词（第二行歌词）作为辅助文字呈现。该呈现沿用辅助文字的字号与该 profile 的亮/暗辅助文字选择，但颜色仍使用「下一行颜色」设置；开启时取代独立的下一行歌词行而不与之叠加，关闭时独立下一行呈现保持不变。
- 歌曲信息与第二行歌词各自携带每个 surface 独立的对齐选择（`auto`、`start`、`center`、`end`）。`auto` 跟随主歌词对齐的解析结果（主对齐 `auto` 时仍按歌词方向右对齐）；显式值使该行独立于主歌词对齐。第二行歌词的两种呈现形态（辅助文字形态与独立下一行行）共用同一个第二行对齐选择。
- 主歌词接受每个 surface 1、2、3、4、5 行或不设用户限制的换行上限。高达 200% 的文本大小必须使用所选上限，而不是旧的固定三行上限。安全区几何、可选行移除、有界最小尺寸与 fail-closed 位置策略保持权威。
- 每个 surface profile 存储从 50% 到 200% 的元数据大小与 ruby 朗读可见性。Ruby 默认显示，禁用时不占用绘制或布局高度。
- 在绑定 generation 的歌曲 intro 期间，匹配的单行标题/艺术家文本会抑制重复的元数据行，并在三秒后形变为持久的元数据位置与大小。不兼容或换行的几何使用有界交叉淡化。两条路径都不改变整个 surface 的 alpha、keepalive 亮度策略或位置权威。
- 导入的数据不能指定类、资源、方法、路径、URL、命令或外部位图来源。
- 重置会恢复内置安全 profile。

已启用的固定注册表：

- lyrics；
- metadata；
- 仅锁屏的 media_progress。

artwork 强调色、状态文本、占位符（spacer）与分隔线，在各自拥有真实的有限渲染器、放置契约、隐私/功耗分析与设备证据之前，仍然被拒绝。AOD 进度/作品图仍然被清除出去。

无论用户/导入值如何，AOD 策略都可以进一步降低亮度、亮区面积、动画、作品图、组件数量或场景大小。

## 迁移

- 既有的 `aod_render` 值填充默认 AOD profile，不产生可见回归。
- 初始锁屏 profile 派生自 AOD 样式，但保持禁用。
- 链接 surface 样式在锁屏启用后默认开启。
- 无缝过渡仅在两个 surface 都已启用且 linkage 能力通过时默认开启。
- 迁移版本仅在成功校验/持久化之后写入。
- 旧版 AOD 偏好在一个回滚周期内保持可用。

## 能力 fallback

应用显示来自最近接受的能力报告的显式支持状态：无报告、已验证、已验证但缺失符号、不支持、实验性符合条件或实验性活动。已配置的 surface 偏好在不受支持的 profile 上仍会存储，但应用必须将其描述为无法运行并禁用依赖运行时的控件。外观编辑器仍可用于预览和未来配置。用户可以创建包含包版本与有限原始符号证据的兼容性报告。

能力报告协议 v2 包含报告时间戳、生效 profile 状态、实验状态、原始探测集与已解析能力集。协议 v1 仅因应用/SystemUI 升级过渡兼容而被继续接受。未知 profile 保持 fail-closed；仅原始探测成功不会安装或启用任何 hook。

各能力相互独立：

```text
AOD_SURFACE
AOD_POSITION_UPDATES
AOD_LIFETIME_GUARD
AOD_WAKE_BROKER
LOCKSCREEN_HOST
LOCKSCREEN_GEOMETRY
LINKAGE_DIRECTION
LINKAGE_GEOMETRY
RAISE_TO_AOD
FULL_AOD
VIDEO_DEPTH
```

匹配包括 SystemUI/AOD 包版本与精确的必需符号签名。未知或缺失的符号仅禁用依赖行为。原生 UI 从不被隐藏、替换、重新父级化、重新测量或重新样式化。时钟平移控制仅允许由上文验证过的 AOD 场景策略执行，且必须能退回到 Xiaomi 的原始目标。
