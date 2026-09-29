# Hardware Regression Ledger

# English / 英文

Status: maintainer-maintained device verification ledger

README states that unit tests are necessary, not sufficient: anything touching SystemUI hooks, AOD
power, or geometry needs hardware verification that cannot run in CI. This ledger records those
hardware verifications over time so that (a) a regression has a reference point to compare against,
and (b) unverified paths stay explicit instead of silently assumed.

## Areas

- Lockscreen rendering and collision (placement, notification/media collision, scrim)
- AOD wake and keepalive (draw wake pulses, `smartHide()`/`hideDoze()` suppression, wake signal)
- Linkage transition (lockscreen ↔ AOD handoff, geometry conversion, reversal)
- Burn-in protection (managed movement, static hold, native target restore)
- Brightness and battery protection (brightness clamp, session deadline, pause release)
- Pause retention (frozen snapshot retention window)
- Position anchoring (clock anchor stabilization, stock settle drift)
- Lyric source arbitration (producer staleness rules, fallback takeover, Lyricon feed recovery)
- Landscape rotation (canvas rotation, logical frame, surface rect swap)

## Entry format

| Date | Version | Area | Result | Device + SystemUI/AOD | Evidence | Notes |
|---|---|---|---|---|---|---|

- **Version**: the versionName/versionCode under test (e.g. `0.3.113 (140)`).
- **Device + SystemUI/AOD**: same combo as a `docs/DEVICE_COMPAT_MATRIX.md` row.
- **Evidence**: one of the `docs/STYLE_GUIDE.md` section 12 labels — `device-verified`,
  `device-smoke-tested`, or `trace-observed`. Never upgrade a label without fresh evidence.
- **Result**: pass / fail / partial; a failing or partial entry names the behavior in Notes and
  links the follow-up issue. Update the same entry when the fix lands.

## Ledger

| Date | Version | Area | Result | Device + SystemUI/AOD | Evidence | Notes |
|---|---|---|---|---|---|---|
| 2026-09-26 | 0.3.116 (143) | AOD wake and keepalive | fail | Redmi K80 Pro (`miro`) / DEV-2327.0.0.1-03022115 (22327001) | trace-observed | AOD lyrics surface never attaches on doze: `AodPowerStateMonitor.attach` NPE (null `applicationContext` in the host package context) aborts `buildSurface`, `Attach failed` on every screen-off (regression since 0.3.114 / #72). Fix (context fallback + attach isolation) lands with this entry; update to pass + device-verified after hardware re-check. |
| 2026-09-27 | 0.3.120 (147) | Lyric source arbitration | fail | Redmi K80 Pro (`miro`) / 20250121.0(202501210) | trace-observed | Home "Now playing" stuck on "No track from the active source yet" while music played: the Lyricon callback path silently died after the 0.3.120 install restart (position/playback callbacks never arrived, `isPlayingState` froze at false), both rebuild watchdogs stayed blind (gated on the frozen playing flag and a never-saw-callback baseline), and the arbiter dead-locked `active=null` (stale-sweep cleared a state the selector still deemed usable; signature dedup never re-published). Follow-up: this fix. |

## Known unverified paths

- Frame-perfect 60 FPS animation on AOD (`docs/ARCHITECTURE.md`: "remains unverified and is not a
  contract").
- Real-device `custom`/`noto-sc` typeface rendering through the unified `LyricTypefaceResolver`
  path (preview-device font unification) — pending a hardware smoke check after merge.
- Line breaking/row metrics after the shared `LyricLayoutEngine` extraction (verbatim algorithm
  move) — pending a hardware smoke check of lyric wrapping/line spacing after merge.
- AOD surface attach resilience fix (power-monitor `attach` null-context fallback + runCatching
  isolation, ArchitectureGuardTest-guarded) — pending a hardware smoke check: screen-off must show
  lyrics and `adb logcat -s HyperGlow` must show `Power state monitor attached` with no
  `Attach failed`.
- "Second line as secondary text" auxiliary presentation (`secondaryNextLine` switch: next lyric
  line drawn with secondary-text styling, replacing the standalone next-line row; its color stays
  on the next-line color setting in both forms) — pending a hardware smoke check after merge.
- Independent row alignment for song info and the second lyric line (`metadataAlignment` /
  `nextLineAlignment`, `auto` follows the resolved main lyric alignment) — pending a hardware smoke
  check after merge. Note: with both left at `auto`, an explicit main alignment already governed
  song info before this change; only `auto` main alignment with right-to-left lyrics now moves the
  song info row to follow the lyric direction (previously pinned to start), and the home preview
  secondary/metadata rows now honor row alignment like the device does (previously always start).
- Top-right restart entry on the home app bar (quick restart button replacing the former runtime-status list row, same restart dialog and ShellUtils path) — pending a hardware smoke check after merge: the icon opens the target dialog and the confirmed restart brings SystemUI/AOD back.
- Adaptive lockscreen card height (the scene rect measures the content row stack at the resolved
  content width and sizes to it; the height setting is now the upper bound and the settings estimate
  only backs pre-content placement) — pending a hardware smoke check after merge: short one-line
  content hugs its rows without a large empty gap (scrim follows), tall multi-row content no longer
  clips at the bottom, and the height setting still caps at its fraction. Note: the home preview
  keeps its proportional scenario placement (it is a placement mock, not a measurement).
- Line transition animation speed (`lineTransitionSpeed`: `Normal`/`Slow`/`Fast`, rendered directly
  below the line transition option; durations scale the 130/210 ms base by 1.0/1.5/0.6 while frame
  recipes and easing stay untouched, unknown values normalize to `Normal`) — pending a hardware
  smoke check after merge: the three settings are visibly distinct on a real line change and match
  the expected durations (Slow ≈ 195/315 ms, Fast ≈ 78/126 ms), with no change to motion paths.
- Line transition coverage of the auxiliary text (the main lyric, transliteration/translation rows
  and the next-line row now transition as one row block in the home preview, matching the device
  `drawRows` single-layer semantics that already animated the whole block; previously only the
  preview popped the auxiliary rows instantly) — pending a hardware smoke check after merge: on a
  device line change the auxiliary text fades/moves together with the main line instead of popping,
  and the preview shows the same block transition.
- Song info content and separator (`metadataParts` / `metadataSeparator`, document-level,
  shared by both surfaces): choose which slices show (title/artist/album, always in canonical
  order) and the joining separator (`newline` = one slice per line, the historical default, or
  inline joins such as ` · `); the canvas splits metadata on hard line breaks only and renders at
  most three metadata lines (was two), with the height budget extended by the extra slice lines —
  pending a hardware smoke check after merge: the default title/artist + newline looks unchanged,
  three selected parts each get their own unclipped line, and an inline separator keeps the row on
  a single line.
- Preview card adaptive height (`LyricPreviewCard` / `AppearanceLivePreview` surfaces grow with
  their lyric content instead of fixed 150/180dp boxes, clamped to 120-420dp, and hold the
  tallest content seen while the same profile is active so demo/live line re-wrapping does not
  make the card breathe) — app-preview-only change, no SystemUI/AOD surface involvement; pending
  a quick on-device look that large text sizes plus secondary/next-line/metadata rows are no
  longer clipped and the surrounding layout stays put.
- Imported font display names in the built-in style (the font choice row summary and dialog show
  the font's real name from its `name` table with the imported file name as fallback, instead of
  the generic "Custom font" label; import now accepts multi-select batch and keeps every font) —
  pending a hardware smoke check after merge: importing several fonts keeps them all listed with
  their own names on both the choice dialog and the settings summary row.
- Lyric source deadlock recovery (arbiter stale-sweep shares the selector's fault predicate and
  re-publishes a cleared active state; Lyricon watchdogs rebuild the subscription from a
  subscribe-time silence baseline with the MediaSession playback state as the playing signal;
  a stale frozen preferred state yields to a fresher source that is playing and carries lyric
  content) — pending a hardware smoke check after merge: with the lyric source on Lyricon, play
  a song in NetEase and confirm the home "Now playing" row shows the track (not "no track")
  and keeps following while playing; pause keeps the frozen lyric line on AOD and shows paused,
  not "no track". Note: intentional behavior change — a stale paused preferred state now yields
  to another source that is playing and has lyric content (it still never yields to a
  content-less source).
- Song artwork display (per-surface `artworkVisible` / `artworkShape` / `artworkSpin` on each
  surface profile — lockscreen and AOD configure them independently; documents saved before the
  move seed both profiles once from the stored global values): the verified
  current-playing-music-software album art (playing media session, package + track identity match
  against the displayed song; stale playback-window art rejected) shows in one slot immediately
  left of the song-info block, square or circle, with optional circle-only uniform rotation
  (12s/turn, same cadence gates as timed lyrics) — pending a hardware smoke check after merge:
  toggle off shows no artwork; with a song playing in the music app the artwork appears left of
  song info and is that song's cover (pause/switch tracks updates it); a non-playing or mismatched
  session shows nothing; circle clips round and spins only when the rotation switch is on (square
  never spins); layout keeps the group aligned at each song-info alignment and hidden surfaces
  draw no spin frames (power gates); toggling artwork on one surface leaves the other surface's
  artwork setting (toggle/shape/rotation) untouched, and switching the appearance editor between
  lockscreen and AOD shows each surface's own values; the slot is centered on the song-info block
  (shared optical center — not riding low) and, when taller than the text block, grows the band so
  it is neither clipped at the content edge nor by the lockscreen card, with the measured card
  height including it; the circle spin freezes during pause retention by default (intentional
  change) and keeps spinning only on surfaces with the per-surface keep-spinning-while-paused
  switch on.
- HyperLyric line-change preset replication (all 25 presets selectable under the line transition
  option under their original ids, plus the compatibility short names `Fade left`/`Landing`/
  `Slide swap` which normalize to the matching preset ids: sequential out-then-in switching with
  per-preset motion/easing/durations kept identical to HyperLyric `YoYoPresets` + daimajia 2.4 —
  quarter-width fade drifts, full-width/height slides, flips and rotations about the content
  center, zoom/landing keyframes, overshoot settles; bases 200/250/300 ms out and 300–700 ms in,
  scaled by the speed setting; historical modes stay pixel-identical) — pending a hardware smoke
  check after merge: the option lists the full preset set with HyperLyric-style labels; spot-check
  several presets (fade left/right, soft landing, slide swap, flip X/Y, rotate, zoom) look like
  their reference animations with the old line leaving first and the new one settling after; flip
  direction matches the app preview; the speed setting scales these presets too; `Fade up` is
  unchanged.
- Duet left/right split (`duetAlignment`, per surface, default on): a line whose source flag
  (`alignedRight` / `isAlignedRight`) or singer-identity metadata marks the later singer's side
  draws on the right; turning the switch off resolves every line from the main alignment instead.
  Songs whose lyric source carries no singer info are unchanged — pending a hardware smoke check
  after merge: a duet song tagged with two singers alternates left/right, turning the switch off
  lefts everything, and translation / next-line rows are unaffected.
- Lyricon position-feed log/state churn fix (a repeated position callback within the writer's
  ~40 ms update cadence no longer trips stall extrapolation: below the 500 ms floor the last
  real position is held and no state is emitted; the arbiter logs "active changed" only when
  the producer identity (source + song generation) actually changes, so routine same-source
  forwards are silent) — pending a hardware smoke check after merge: with diagnostic logging
  enabled and a song playing, `diagnostic-trace.log` no longer fills with per-frame
  `position stalled/resumed` (~45 lines/s) and per-frame arbiter `active changed` lines;
  genuine screen-off stalls still extrapolate and log once, and line changes / source
  switches still log.
- Duet marker recognition (`duetMarkers`, document-level, default on): leading （男）/（女）/（合）
  markers are hidden from display and, when the source carries no singer metadata, drive the duet
  left/right split (first marker singer left, the rest right, in order of appearance); turning it
  off shows raw marker text and keeps markers out of the split.
  Ingest timeline repair (gap-swallowing line windows re-anchored / suspect word timing dropped)
  and cross-producer seek forwarding land together — pending a hardware smoke check after merge:
  a Netease duet song (e.g. 讲男讲女) splits left/right by the （男）/（女） markers with clean text
  and lines appear at their sung times (no early next-line), the switch restores raw markers, and
  dragging the seek bar moves the lyric immediately. The clamp is deliberately conservative
  (isolated long-window rows stay untouched, so genuine held notes are safe): also confirm a slow
  ballad's held final note still enters at its real start, and an English/melisma song shows no
  late-clamped lines.
- App appearance settings rework plus background blur (theme mode / theme color / system bar icons
  become inline dropdown rows showing the current value on the row, the custom-color row carries a
  color swatch plus its hex value and opens the palette dialog with a live swatch preview, and the
  background image entry opens a picker dialog whose dim/blur sliders render live on the image
  preview with Restore default / Save as a paired commit; new `background_blur_percent` 0-100,
  default 0, config-backup round-tripped, rendered as a 0-25dp edge-unbounded blur on the app
  background layer; while the background image is active every app surface turns translucent
  glass — top bar lightest (70% surface), cards 82% surfaceContainer (status hero card follows
  the card tier), floating nav most solid (93% surfaceContainerHighest) so it stays distinct
  over the wallpaper; without a background image all surfaces keep the miuix opaque defaults;
  the app bar is a compact single line (status inset + 52dp, back/title/actions) replacing the
  two-row large title, with a top-weighted progressive backdrop blur over the wallpaper plus a
  fading scrim while the background is active (miuix-blur progressiveTextureBlur, degraded to
  scrim-only when runtime shaders are unsupported); control color (custom tint on cards/top
  bar/nav with luminance-inverted content colors, restore-default clears it) and control
  opacity (0-100% scaling the glass tiers, default 100%) are user-configurable and
  config-backup round-tripped) —
  app-UI-only change, no SystemUI/AOD surface involvement; pending a hardware
  smoke check after merge: the three dropdown rows pick values in place and persist across
  re-entry, the custom-color row appears only under Custom and its dialog previews the picked
  color before Save, the background dialog shows the current or freshly picked image with dim and
  blur applied to the preview exactly as the app background will look, Save applies the blurred
  background app-wide, and Restore default clears the image and resets both sliders; with the
  background set the top bar is no longer an opaque black band, cards and the status hero card
  read as glass over the wallpaper, and the floating nav stays clearly distinct from both the
  wallpaper and the cards; the app bar renders as one compact line (no large title) whose blur
  fades from strong at the status bar to clear at its bottom edge, and picking a custom control
  color/opacity re-tints cards, top bar and nav immediately with readable text on light tints,
  while restore-default returns to theme surfaces.
- **App-UI card glass wiring + system-bar icon honesty note (2026-09-29)** — three cards that were still plain
  miuix surfaces now take the same glass tokens as `SettingsCard`: the home stat cards ("息屏歌词" / "锁屏歌词"),
  the home "兼容性" card, and the "两个显示区域" (import/export/reset appearance) card at the bottom of the
  AOD-appearance screen. Measured on hardware before the fix: with a custom control color the top bar, status hero
  card, live-status card and floating nav re-tinted immediately while those three stayed opaque, i.e. control color
  and control opacity had no effect on them. The system-bar icon setting was also found inert on HyperOS 3: the
  appearance request does reach the window manager (verified through `dumpsys window` `mLastAppearance`, which
  follows the setting), yet the rendered icon color follows the content behind the status bar — swapping in a
  pure-white background flipped the icons dark while the theme stayed dark — so the ROM's own auto-contrast wins
  over the request. MIUI's legacy override was checked and is dead on this build: the class and the
  `EXTRA_FLAG_STATUS_BAR_DARK_MODE` constant still exist in `/system_ext/framework/miui-framework.jar`, but the
  entry point `Window.setExtraFlags(int, int)` is gone (`NoSuchMethodException` on
  `com.android.internal.policy.PhoneWindow`, captured from a deduped app log line). The "系统栏图标" row therefore
  carries a MIUI-only summary stating that the system picks the icon color from the content behind the status bar.
  Device-verified on the PR #111 build (Redmi K80 Pro, HyperOS 3): the three cards re-tint with the control
  color/opacity like every other surface, and the summary renders on that row.
- Every color picker dialog gains hex code entry (app theme color, control color, and each
  semantic lyric color on the layout screen): tapping the bottom color preview swatch reveals a
  code field — type `#66CCFF` (leading `#` optional, `#RGB`/`#RRGGBB`/`#AARRGGBB`, alpha ignored)
  and hit Apply or the keyboard done key to write the color through the same path as the sliders,
  with an inline error for invalid codes that leaves the current color untouched. App-UI only
  (no SystemUI/AOD surface change) — pending a hardware smoke check after merge: in each dialog
  tapping the swatch opens the field, a typed code updates the swatch/picker/preview live and
  survives Save, a garbage code keeps the old color and shows the error text.
- Line-change fade drift now uses the animated row block's own height (Fade-family basis fix,
  device + preview): the Fade-family drift is a quarter of that layer's row-box bounds (main lyric
  plus auxiliary and next-line rows, excluding the song-info row; the outgoing layer uses the old
  block, the incoming layer the new one) instead of the canvas content clip frame. Measured on
  hardware before the fix (`fade_out_up_fade_in_up`, Slow, AOD, 1080x2400): the outgoing line
  travelled about 169 px — exactly a quarter of the ~677 px content clip frame, while the row block
  is only ~200 px tall — sweeping across the song-info row, and the incoming line rose from the
  same order of distance below; after the fix the same preset travels a quarter of the row block
  (~50 px), which is the referenced `target.getHeight()/4`. Historical modes ignore the basis and
  stay pixel-identical; the preview now measures each layer's block height instead of approximating
  with the main row. Pending a hardware smoke check after merge: with `fade_out_up_fade_in_up`
  (plus one other up/down preset) the outgoing line no longer crosses the song-info row, the
  incoming line rises from a quarter of the block height, and the preview matches the device.
- Add new entries here whenever a feature lands without device evidence, and remove them once
  evidence exists.

## How this ledger is used

- Before merging a change that touches an area, check the most recent entry for that area; if the
  change could regress it, ask for fresh device evidence as part of the review.
- The ledger records history; it does not replace `docs/private/LOCKSCREEN_AOD_IMPLEMENTATION_STATUS.md`
  (private doc), which keeps the per-build verification detail for the current work package.

---

# 中文 / Chinese

状态：维护者维护的真机验证台账

README 明确"单测通过是必要非充分条件"：凡触碰 SystemUI hook、AOD 功耗或几何的改动，都需要 CI
无法替代的真机验证。本台账按时间记录这些真机验证，目的有二：(a) 回归出现时有可比对的基准；
(b) 未验证路径保持显式，而不是被默默当作已验证。

## 领域分类

- 锁屏渲染与碰撞（放置、通知/媒体碰撞、scrim）
- AOD 唤醒与 keepalive（draw wake 脉冲、`smartHide()`/`hideDoze()` 抑制、唤醒信号）
- Linkage 过渡（锁屏 ↔ AOD 交接、几何转换、反转）
- 防烧屏（托管移动、静态保持、原生目标还原）
- 亮度与电池保护（亮度钳制、会话时长上限、暂停释放）
- Pause 保留（冻结快照保留窗口）
- 位置锚定（时钟锚点稳定、stock settle 漂移）
- 歌词源仲裁（生产者 staleness 谓词、回退接管、Lyricon 供数恢复）
- 横屏旋转（画布旋转、逻辑帧、surface rect 交换）

## 条目格式

| 日期 | 版本 | 领域 | 结果 | 设备 + SystemUI/AOD | 证据 | 备注 |
|---|---|---|---|---|---|---|

- **版本**：被测的 versionName/versionCode（如 `0.3.113 (140)`）。
- **设备 + SystemUI/AOD**：与 `docs/DEVICE_COMPAT_MATRIX.md` 中某一行相同的组合。
- **证据**：`docs/STYLE_GUIDE.md` 第 12 节标签之一——`device-verified`、`device-smoke-tested`、
  `trace-observed`。没有新证据绝不升级标签。
- **结果**：pass / fail / partial；fail 与 partial 必须在备注里点名具体行为并关联后续 issue，
  修复落地后更新同一条目。

## 台账

| 日期 | 版本 | 领域 | 结果 | 设备 + SystemUI/AOD | 证据 | 备注 |
|---|---|---|---|---|---|---|
| 2026-09-26 | 0.3.116 (143) | AOD 唤醒与 keepalive | fail | Redmi K80 Pro (`miro`) / DEV-2327.0.0.1-03022115 (22327001) | trace-observed | 息屏时 AOD 歌词 surface 从未挂载：`AodPowerStateMonitor.attach` NPE（宿主包 context 的 `applicationContext` 为 null）炸掉 `buildSurface`，每次息屏 `Attach failed`（0.3.114 / #72 引入的回归）。修复（context 回退 + attach 隔离）随本条目落地；真机复验后更新为 pass + device-verified。 |
| 2026-09-27 | 0.3.120 (147) | 歌词源仲裁 | fail | Redmi K80 Pro (`miro`) / 20250121.0(202501210) | trace-observed | 播放中主页「正在播放」常驻「当前歌词源暂无曲目。」：0.3.120 装机重启后 Lyricon 回调链静默死亡（位置/播放状态回调不再到达，`isPlayingState` 冻结 false），两个重建看门狗均为盲区（以冻结的 playing 与「从未收到回调」基线为条件），仲裁器 active 死锁在 null（staleSweep 清掉选源仍视为可用的状态，sig 去重后永不重发）。后续：本修复。 |

## 已知未验证路径

- AOD 上逐帧 60 FPS 动画（`docs/ARCHITECTURE.md`："remains unverified and is not a contract"）。
- 实机 `custom`/`noto-sc` 字体经统一 `LyricTypefaceResolver` 路径渲染（预览/实机字体同源）——合并后待真机冒烟确认。
- 共享 `LyricLayoutEngine` 抽取后的断行/行距（算法逐字迁移）——合并后待真机冒烟歌词折行与行距无回归。
- AOD surface 挂载韧性修复（power monitor `attach` 空 context 回退 + runCatching 隔离，ArchitectureGuardTest 守卫）——合并后待真机冒烟：息屏必须显示歌词，`adb logcat -s HyperGlow` 出现 `Power state monitor attached` 且无 `Attach failed`。
- 「辅助文字显示第二行歌词」呈现（`secondaryNextLine` 开关：下一行歌词按辅助文字样式绘制并取代独立下一行行；两种形态颜色均走「下一行颜色」设置）——合并后待真机冒烟确认。
- 歌曲信息/第二行歌词独立对齐（`metadataAlignment`/`nextLineAlignment`，`auto` 跟随主歌词对齐的解析结果）——合并后待真机冒烟确认。注意：两者默认 `auto` 时，主对齐显式值原本就作用于歌曲信息；行为变化仅在主对齐 `auto` 且歌词右起（RTL）时歌曲信息改为跟随歌词方向（原先固定起始侧），以及主页预览的副文本/歌曲信息行从此与实机一样按行对齐渲染（原先恒起始侧）。
- 首页顶栏右上角重启入口（快捷重启按钮，取代原运行状态列表行，重启对话框与 ShellUtils 路径不变）——合并后待真机冒烟：图标可打开目标选择对话框，确认后 SystemUI/AOD 正常重启。
- 锁屏卡片自适应高度（场景矩形按已定内容宽实测内容行堆叠高度定高；「高度」设置改为上限，基于设置的高度估算仅在内容就绪前兜底位置）——合并后待真机冒烟：单行短歌词卡片贴合内容无大空档（scrim 跟随），多行/辅助行长内容底部不再被裁切，「高度」设置仍按占比封顶。注意：主页预览保持按占比的情景放置（它是放置模拟，不做实测）。
- 换行动画速率（`lineTransitionSpeed`：`Normal`/`Slow`/`Fast`，位于换行动画选项正下方；时长按
  130/210ms 基准 ×1.0/×1.5/×0.6，帧配方与缓动不变，未知值规范化为 `Normal`）——合并后待真机
  冒烟：三档换行时长肉眼可辨且与设置一致（Slow ≈ 195/315ms、Fast ≈ 78/126ms），运动轨迹不变。
- 换行动画覆盖辅助文字（主歌词、音标/翻译辅助行与下一行歌词在主页预览中整块同层进退，与实机
  `drawRows` 单层语义对齐——实机本就整块过渡，此前仅预览对辅助行瞬切）——合并后待真机冒烟：
  实机换行时辅助文字随主行一起淡入淡出/位移而非瞬切，预览呈现与实机一致。
- 歌曲信息内容与分隔符（`metadataParts`/`metadataSeparator`，文档级全局，息屏与锁屏共用）：可选显示哪些切片（歌名/歌手/专辑，恒按规范顺序）与连接分隔符（`newline` 每切片一行=历史默认，或 ` · ` 等行内连接）；画布歌曲信息只按硬换行拆行且最多 3 行（原 2 行），高度预算随切片行数追加——合并后待真机冒烟：默认「歌名/歌手+换行」与历史一致、选满 3 部分各占一行不裁切、行内分隔符保持单行。
- 预览卡片自适应高度（`LyricPreviewCard` / `AppearanceLivePreview` 面板高度随歌词内容增长，取代固定 150/180dp，钳制在 120-420dp；同一配置生效期间保持已见最大内容高度，演示行循环/逐行折行变化不会让卡片高度来回呼吸）——仅应用内预览改动，不涉及 SystemUI/AOD surface；待真机看一眼：大字号 + 副文本/下一行/歌曲信息全开时内容不再被裁切，周围布局不跳动。
- 导入字体按内置样式展示名字（字体选择行摘要与对话框显示字体 name 表真名、导入文件名兜底，不再显示「自定义字体」泛称；导入支持一次多选批量且全部保留）——合并后待真机冒烟：一次导入多个字体全部保留，对话框与设置摘要行各自显示自己的字体名。
- 歌词源死锁恢复（staleSweep 与选源共用同一故障谓词、清空后强制补发；Lyricon 看门狗以
  订阅时刻为静默基线并以 MediaSession 真实播放态兜底重建订阅；冻结的暂停态让位给正在播放
  且带歌词内容的更新鲜源）——合并后待真机冒烟：歌词源选 Lyricon，网易云放歌，主页「正在
  播放」显示曲目而非「暂无曲目」且播放中持续跟随；暂停后 AOD 保持冻结歌词行并显示已暂停，
  而不是「暂无曲目」。注意：有意行为变更——首选源冻结暂停时会把位置让给「在播且有内容」
  的其他源（仍然绝不让位给无歌词内容的源）。
- 歌曲图片显示(per-surface `artworkVisible`/`artworkShape`/`artworkSpin`,锁屏与息屏各自独立;旧文档的文档级全局值在首次读取时一次性播种到两个曲面):经校对的「当前播放的音乐软件」专辑图(在播媒体会话且包名/曲目身份与当前歌曲一致;系统播放窗口滞留的旧封面拒收)显示在歌曲信息块左侧单槽,方形/圆形可选,圆形可选匀速旋转(12 秒/圈,与逐字歌词共用节拍门)——合并后待真机冒烟:开关关闭无封面;音乐 App 播放时封面出现在歌曲信息左侧且是当前歌曲的封面(暂停/切歌随之更新);非在播或曲目不匹配的会话不显示;圆形为圆形裁切且仅旋转开关打开时旋转(方形恒不旋转);各歌曲信息对齐下「图片+文本」成组落位不散架;隐藏 surface 不起旋转帧(功耗门控);锁屏与息屏的图片开关/形状/旋转互不联动(改一边另一边不变);图片与歌曲信息块共用视觉中线(不再整体偏低),图片高于文本块时带高随图片增长、不被内容框/锁屏卡片裁切,卡片实测高包含图片高度;暂停驻留期间圆形封面默认停转(有意行为变更),仅打开「音乐暂停时继续旋转」的曲面继续旋转。
- HyperLyric 换行预设全量复刻(换行动画选项内 25 个预设按原 id 列出,另有兼容短名
  `Fade left`/`Landing`/`Slide swap` 归一到对应预设 id:序列式退场→换字→进场,各档运动/
  缓动/时长与 HyperLyric `YoYoPresets` + daimajia 2.4 逐项一致——1/4 宽高淡出漂移、整宽高
  滑出滑入、绕内容中心翻转/旋转、缩放/着陆关键帧、过冲落位;基准退场 200/250/300ms、
  入场 300–700ms,随动画速率档缩放;历史档保持逐像素不变)——合并后待真机冒烟:选项列出
  全量预设且标签为 HyperLyric 式命名;抽查数档(左右淡隐、柔缓着陆、滑出滑入、X/Y 翻转、
  旋转、缩放)观感与参考动画一致(旧行先离场、新行收位);翻转方向与应用内预览一致;速率档
  对预设同样生效;`Fade up` 与历史一致。
- 对唱分侧（`duetAlignment`，每 surface 独立，默认开启）：源显式标记（`alignedRight`/`isAlignedRight`）或演唱者身份元数据判为后位歌手的行绘制在右侧；关闭开关后所有行按主对齐解析。歌词源不带演唱者信息的曲目零变化——合并后待真机冒烟：歌词源标注了两位演唱者的对唱歌曲左右交替、关闭开关后全部居左、翻译/下一行行不受影响。
- Lyricon 位置通道日志/状态刷屏修复（写入端 ~40ms 更新节奏内的重复位置回调不再触发停滞外推：低于 500ms 下限保持最后真实位置且不发状态；仲裁器仅在来源身份（源+歌曲代）真变时才记「active changed」，同源例行转发不再逐帧刷日志）——合并后待真机冒烟：开诊断日志播歌，`diagnostic-trace.log` 不再被逐帧 `position stalled/resumed`（约 45 行/秒）与逐帧 `active changed` 刷满轮转；真实息屏停滞仍外推且各记一条，换行/换源日志保留。
- 识别对唱标记（`duetMarkers`，文档级全局，默认开启）：行首（男）/（女）/（合）标记被隐去并（无元数据时）驱动对唱左右分侧（按标记出现顺序，先出现者居左）；关闭后原样显示标记、标记不参与分侧。同行落地 ingest 时间轴修复（间隙吞进行窗向词对齐/钳制、可疑词级降级）与跨源 seek 转发——合并后待真机冒烟：网易云对唱曲（如《讲男讲女》）按（男）/（女）出现顺序左右分侧且文本无标记、逐句起唱点正确（下一句不再提前上屏）、关闭开关恢复原样标记、拖动进度条歌词立即跟手。钳制刻意保守（孤立长窗行原样保留，真实长音安全）：另验慢歌收尾长音按真实起唱点上屏、英文/拉长音歌曲无误钳。
- 应用外观设置页重做并新增背景模糊（主题模式/主题颜色/系统栏图标改为行内下拉直接选、行上显示当前值；
  自定义颜色行带色块与 hex 值、弹窗内色板取色并有色块实时预览；背景图片入口打开预览弹窗，变暗/模糊
  滑杆实时作用于预览、「恢复默认/保存」成对提交；新增 `background_blur_percent` 0-100，默认 0，
  随配置备份往返，按 0-25dp 无界边缘模糊渲染在应用背景层；背景图片生效时全部应用表面玻璃化——
  顶栏最透（surface 70%）、卡片次之（surfaceContainer 82%，主页状态大卡随卡片档）、底部悬浮导航最实
  （surfaceContainerHighest 93%）以保住壁纸上的区分度；未设背景图时全部维持 miuix 默认实色；应用栏改为紧凑
  单行（状态栏内边距 + 52dp，返回/标题/动作），背景生效时对壁纸做「上强下弱」渐进式 backdrop 模糊
  （miuix-blur progressiveTextureBlur）叠加渐隐遮罩（RuntimeShader 不支持时退化为仅遮罩）；控件颜色
  （卡片/顶栏/导航自定义着色，内容色按亮度反转，恢复默认清除）与控件不透明度（0-100% 缩放三档玻璃化，
  默认 100%）可自定义并随配置备份往返）——仅应用内改动，
  不涉及 SystemUI/AOD surface；合并后待真机冒烟：三处下拉行就地选值且重进保留、自定义颜色行仅在「自定义」下出现且弹窗
  保存前可见所选颜色、背景弹窗预览与实际背景观感一致（变暗/模糊即时可见）、保存后应用背景即为模糊
  效果、恢复默认清除图片并复位两根滑杆；设背景后顶栏不再是一整块黑、卡片与主页状态大卡呈玻璃质感透出壁纸、
  底部悬浮导航与壁纸/卡片都有明确区分；应用栏呈单行紧凑形态（无大标题）且模糊从状态栏处最强、
  向底边渐隐；自定义控件颜色/不透明度后卡片、顶栏、导航立即变色且浅色下文字仍可读，恢复默认回到主题表面。
- **应用内卡片玻璃化补齐 + 系统栏图标说明（2026-09-29）** —— 三处此前仍是 miuix 实色的卡片改为与 `SettingsCard`
  同一套玻璃化取色：主页「息屏歌词 / 锁屏歌词」两张统计卡、主页「兼容性」卡，以及息屏外观页底部「两个显示区域」
  （导入 / 导出 / 重置外观）卡。修复前真机实测：设自定义控件颜色后顶栏、状态大卡、实时状态卡与悬浮导航立即变色，
  而这三处保持不透明，即控件颜色与控件不透明度对它们完全无效。同时查清「系统栏图标」在 HyperOS 3 上不生效的原因：
  外观请求确实下发到了窗口管理器（`dumpsys window` 的 `mLastAppearance` 随设置变化），但实际图标颜色由状态栏背后的
  内容决定——换成纯白背景后图标立刻变深、而主题仍是深色——即被 ROM 自身的自动反色压过。MIUI 的旧覆盖通道在本版本上
  已失效：`MiuiWindowManager.LayoutParams.EXTRA_FLAG_STATUS_BAR_DARK_MODE` 的类与常量在
  `/system_ext/framework/miui-framework.jar` 里仍然存在，但入口方法 `Window.setExtraFlags(int, int)` 已被移除
  （应用日志抓到 `NoSuchMethodException: com.android.internal.policy.PhoneWindow.setExtraFlags`）。因此「系统栏图标」
  行在 MIUI/HyperOS 上增加一条说明，告知图标颜色由系统按状态栏背后的内容自动决定。已在 PR #111 构建上真机验证
  （Redmi K80 Pro / HyperOS 3）：三处卡片与其余控件同样随控件颜色 / 不透明度变化，说明文案在该行正常显示。
- 全部取色弹窗支持颜色代码输入（应用外观的主题色/控件颜色、歌词布局的九个语义歌词色）：点按弹窗底部
  色块预览展开代码输入框，输入 `#66CCFF`（`#` 可省，支持 `#RGB`/`#RRGGBB`/`#AARRGGBB`，忽略透明度）
  后点「应用」或回车即按与滑杆相同的路径写入；非法代码就地提示且不改动当前颜色。仅应用内改动
  （不涉及 SystemUI/AOD surface）——合并后待真机冒烟：三个取色弹窗点色块都能展开输入框、输入代码后
  色块/色板/预览同步更新且保存后保留、乱码代码保持原色并出现错误提示。
- 换行淡出漂移基准改为行块自身高度（Fade 族基准修复，实机 + 预览）：Fade 族位移取该层行块自身
  行盒包围盒高的 1/4（主歌词 + 辅助文字 + 下一行，不含歌曲信息行；退场层用旧行块、入场层用新行块），
  不再取画布内容裁剪框。修复前真机实测（`fade_out_up_fade_in_up` + Slow，息屏，1080x2400）：旧行
  上移约 169px——正是约 677px 内容裁剪框高的 1/4，而行块高只有约 200px——整块扫过歌曲信息行，
  新行又从同量级的下方升起；修复后同档位移为行块高的 1/4（约 50px），与参考实现
  `target.getHeight()/4` 一致。历史档不受影响（不读该参数），逐像素不变；预览改为测量各层行块
  实测高，不再用主行高近似。合并后待真机冒烟：`fade_out_up_fade_in_up`（再加一档上下向预设）旧行
  不再穿过歌曲信息行、新行自行块高 1/4 处升起，且预览与实机一致。
- 今后凡有没有真机证据的功能落地，先在这里登记；取得证据后移除。

## 台账的使用方式

- 合并触碰某领域的改动前，先查该领域最近一条记录；若改动可能使其回归，评审时要求补充新的真机证据。
- 台账记录历史，不替代 `docs/private/LOCKSCREEN_AOD_IMPLEMENTATION_STATUS.md`（私有文档，
  保存当前工作包按构建的验证明细）。
