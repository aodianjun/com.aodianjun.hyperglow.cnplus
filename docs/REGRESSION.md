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
- Auxiliary secondary-text sourcing (translation/transliteration content across producer and
  plugin boundaries)

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
| 2026-09-29 | 0.3.129 (156) | Lyric source arbitration | fail | Redmi K80 Pro (`miro`) / OS3.0.6.0.WOMCNXM | trace-observed | Whole song showed no lyrics (stable placeholder for the last 2m43s) on《淑女的品格》: the SDK delivered the subscribe backfill `onSongChanged` 12 ms *before* the `connected` callback, the late connect callback re-armed the issue #56 backfill window, and the real track change 2 minutes later was misclassified as a re-sync (kept the old position, dropped the exact-match residual filters, opened the plausibility gate). The previous track's frozen shared-memory residual (164072 ms) was then accepted as the real position, the active line jumped to the song tail (`idx=58/62`) and clamped to the stable placeholder once extrapolation hit the duration. The same residual was correctly rejected minutes later on two other tracks with the gate closed — the gate itself is intact; only the classification raced. Fix (backfill window armed only at subscribe actions, residual filters stay armed through a re-sync, a changed-id backfill resets the timeline with the gate kept open) lands with this entry; update to pass + device-verified after hardware re-check. |
| 2026-09-29 | 0.3.129 (156) | Auxiliary secondary-text sourcing | fail | Redmi K80 Pro (`miro`) / OS3.0.6.0.WOMCNXM | trace-observed | Auxiliary translation line missing on the AOD for Sunshine (OneRepublic, NetEase CloudMusic via LyriconProvider): the render configuration was correct (`secondaryMode=Translation` on both surfaces) and the render gate is pass-through, while the plugin chain's `跳过 AI 翻译: reason=existing_translation` at chain time proves the bridge input carried non-blank translation text (the plugin skip loop reads only `PluginLyricLine.getTranslation` with `isBlank`, the same predicate as the display gate) and all 67 rows were displayed as the active line — the visual symptom itself is a user report, not a capture. Verified adjacent defect: the host dropped the translation word list at every producer/plugin boundary (`translationWords` never crossed the bridge, word-only plugin results never applied). Fix (translation text/word-list redundancy pair honored at the three boundaries) lands with this entry; residual suspects for the symptom are sparse translation coverage in the installed provider build or a runtime display condition — re-check on hardware and update to pass + device-verified. |
| 2026-10-01 | 0.3.133 (160) | Auxiliary secondary-text sourcing | fail | Redmi K80 Pro (`miro`) / OS3.0.6.0.WOMCNXM | device-verified | The "second line as secondary text" auxiliary form rendered the next lyric line at ≈0.9× the main size — visually a second main line — on the lockscreen: `LIVE_CARD_SIZE_MULTIPLIER=0.68` scales the main to ~15.6 sp while the 14/13 sp absolute readability floors cap the auxiliary sizes (on-device glyph measurements 44/40/37 px match the 15.6/14/13 sp formulas exactly; screenshots archived). Fix (the floors are capped relative to the effective main size; the preview shares the formulas and is fixed by the same change) lands with this entry; update to pass + device-verified after hardware re-check. |

## Known unverified paths

- Translation redundancy pair across the producer/plugin bridge (`translationWords` backfills the translation text when the text is missing, crosses `PluginLyricLine.translationWords` unchanged, and word-only plugin results apply on the reverse path; unit-tested) — pending a hardware smoke check after merge: a source delivering word-level translations only must show the auxiliary translation line on the AOD, and a song whose translations arrive as plain text must be unchanged.
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
  on the next-line color setting in both forms) — the 2026-10-01 device finding (the aux-styled
  next line rendered at ≈0.9× the main size, reading as a second main line) is fixed by capping
  the 14/13 sp absolute readability floors relative to the effective main size; pending a hardware
  smoke check after merge: the aux-styled second line must be clearly smaller than the main line
  at Normal and Custom size settings, on both surfaces. Device re-check on 0.3.135 (162) passed for
  both surfaces: lockscreen next/main ink ratio measured 0.88 → 0.58 against the 0.90 → 0.62
  formula prediction, AOD (xlarge) shows the three-tier contrast (evidence archived under
  `adbdiag/live3/`).
- "Show auxiliary text for the second line" (`nextLineAux` switch: the second lyric line also
  brings its own auxiliary rows — transliteration and/or translation per the secondary-text mode —
  giving four rows: first line, its auxiliary text, second line, the second line's auxiliary text)
  — device-verified on 0.3.137 (164) for the four-row order on the AOD (LyricInfo source carrying
  per-line translations) and the lockscreen; the second line's auxiliary rows now take the second
  line's own rendered line count as their wrap budget instead of the main line's (owner 2026-10-02
  report), and in the line-change animation they belong to the promotion/enter groups (following
  the second line) instead of exiting with the main-line group — pending a hardware smoke check of
  both the wrap and the animation on lines that wrap/advance differently from the main line. The
  upcoming line's auxiliary text is now also enriched from the plugin row table (SuperLyric
  line-stream + a translation plugin yields the fourth row; without a plugin result the producer
  value is kept), pending a hardware smoke check on the SuperLyric fallback path. Note: sources
  with no upcoming-line auxiliary data anywhere simply render the rows they have.
- "Show auxiliary text for the second line" premise relaxation (`nextLineAux` switch: premised on
  the second lyric line actually showing — exposed when either "show next lyric line" or "second
  line as secondary text" is on; when on, the second lyric line itself renders with secondary-text
  styling even with `secondaryNextLine` off, premised on `showNextLine`, and its auxiliary rows are
  appended in the standalone dimmed form too — device canvas and preview share the same decision) —
  owner 2026-10-04 report that the premise chain did not hold (the fourth row previously existed
  only in the auxiliary form: the standalone form could neither enable it in the settings page nor
  append it at draw time, and the `SurfaceProfile` KDoc promise "renders aux-styled even with
  `secondaryNextLine` off" was never implemented — this lands it) — pending a hardware smoke check:
  with only "show next lyric line" + "show auxiliary text for the second line" on, the second line
  renders aux-styled with its auxiliary row(s); turning the latter off returns the dimmed standalone
  row without auxiliary rows; both switches off shows nothing; both surfaces.
- Independent row alignment for song info and the second lyric line (`metadataAlignment` /
  `nextLineAlignment`, `auto` follows the resolved main lyric alignment) — pending a hardware smoke
  check after merge. Note: with both left at `auto`, an explicit main alignment already governed
  song info before this change; only `auto` main alignment with right-to-left lyrics now moves the
  song info row to follow the lyric direction (previously pinned to start), and the home preview
  secondary/metadata rows now honor row alignment like the device does (previously always start).
- Top-right restart entry on the home app bar (quick restart button replacing the former runtime-status list row, same restart dialog and ShellUtils path) — pending a hardware smoke check after merge: the icon opens the target dialog and the confirmed restart brings SystemUI/AOD back.
- Restart dialog's lyric-source target now rebuilds **all four** producers (was: only the selected one) and is decoupled from the hooked-process restart (the root kill is dispatched first, so a lyric-source rebuild can no longer swallow it) — pending a hardware smoke check after merge: with the preference left at the default `Spicy` while the lyrics actually on screen come from a fallback source (e.g. `SuperLyric`), ticking the lyric-source switch and confirming must recover that fallback source (previously a no-op); ticking it together with System UI/AOD must still restart both processes; one source failing to rebuild must not stop the remaining ones.
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
- Song info content and separator (`metadataParts` / `metadataSeparators`, per-surface — lockscreen
  and AOD configure them independently; a surface that never set them inherits the document-level
  default, so old documents upgrade unchanged): choose which slices show (title/artist/album, always
  in canonical order) and the joining separator (`newline` = one slice per line, the historical
  default, or inline joins such as ` · `); the canvas splits metadata on hard line breaks only and
  renders at most three metadata lines (was two), with the height budget extended by the extra slice
  lines. The snapshot carries raw title/artist/album and each surface re-assembles them from its own
  profile — pending a hardware smoke check after merge: the default title/artist + newline looks
  unchanged, three selected parts each get their own unclipped line, an inline separator keeps the
  row on a single line, and changing slices/separators on AOD leaves the lockscreen's song-info
  assembly and layout budget untouched (and vice versa).
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
- Show concurrent lines (duet) (`duetConcurrent`, per surface, default on, AOD only): a sung line
  overlapping the primary line by at least one second renders stacked next to the primary block
  with its own karaoke sweep, fading in muted over 180 ms; while it shows the standalone next-line
  row is replaced, and the combined block shrinks by one shared scale when it exceeds the lyric
  area — pending a hardware smoke check after merge: a duet song on each wired source (Spicy /
  Lyricon / LyricInfo) shows both lines through their shared window, the concurrent line follows
  the duet left/right split, turning the switch off restores the exact solo rendering, the
  lockscreen card shows both lines through its own switch, SuperLyric songs show no concurrent line, and the next-line row
  returns when the duet window ends.
- Lyricon position-feed log/state churn fix (a repeated position callback within the writer's
  ~40 ms update cadence no longer trips stall extrapolation: below the 500 ms floor the last
  real position is held and no state is emitted; the arbiter logs "active changed" only when
  the producer identity (source + song generation) actually changes, so routine same-source
  forwards are silent) — pending a hardware smoke check after merge: with diagnostic logging
  enabled and a song playing, `diagnostic-trace.log` no longer fills with per-frame
  `position stalled/resumed` (~45 lines/s) and per-frame arbiter `active changed` lines;
  genuine screen-off stalls still extrapolate and log once, and line changes / source
  switches still log.
- Duet marker recognition (`duetMarkers`, per-surface — lockscreen and AOD configure it
  independently, default on; a surface that never set it inherits the document-level default):
  leading （男）/（女）/（合） markers are hidden from display and, when the source carries no singer
  metadata, drive the duet left/right split (first marker singer left, the rest right, in order of
  appearance); turning it off shows raw marker text and keeps markers out of the split. Section
  markers (（副歌）/（间奏） and the like) hide the same way but never join the split; a pure marker
  line (only a marker, no lyric text) keeps displaying as-is and counts zero in the sing-time
  estimate. The snapshot is shared by both surfaces and only carries the raw line text plus two
  precomputed split variants (metadata-identity and marker-recognition); hiding markers and choosing
  the split are deferred to each surface's own switch, so toggling one surface never affects the other.
  Ingest timeline repair (gap-swallowing line windows re-anchored / suspect word timing dropped)
  and cross-producer seek forwarding land together — pending a hardware smoke check after merge:
  a Netease duet song (e.g. 讲男讲女) splits left/right by the （男）/（女） markers with clean text
  and lines appear at their sung times (no early next-line), a section-marker line strips clean
  (e.g. （副歌）爱你一万年 renders as 爱你一万年) with the split unaffected, an interlude-only line （间奏）
  stays as-is and is never re-anchored, the switch restores raw markers, and
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
- Every color picker dialog gains preset colors plus hex code entry (app theme color, control
  color, and each semantic lyric color on the layout screen): tapping the bottom color preview
  swatch reveals a preset swatch grid (warm gold / ice blue / mint green / sakura pink / butter
  yellow / lavender / black / white — tap to apply, the active one ringed) and a code field —
  type `#66CCFF` (leading `#` optional, `#RGB`/`#RRGGBB`/`#AARRGGBB`, alpha ignored) and hit
  Apply or the keyboard done key to write the color through the same path as the sliders, with
  an inline error for invalid codes that leaves the current color untouched. App-UI only
  (no SystemUI/AOD surface change) — pending a hardware smoke check after merge: in each dialog
  tapping the swatch opens the panel, a preset tap or typed code updates the
  swatch/picker/preview live and survives Save, a garbage code keeps the old color and shows
  the error text.
- Line-change fade drift now uses the animated row block's own height (Fade-family basis fix,
  device + preview): the Fade-family drift is a quarter of that layer's row-box bounds (main lyric
  plus auxiliary and next-line rows, excluding the song-info row; the outgoing layer uses the old
  block, the incoming layer the new one) instead of the canvas content clip frame. Measured on
  hardware before the fix (`fade_out_up_fade_in_up`, Slow, AOD, 1080x2400): the outgoing line
  travelled about 169 px — exactly a quarter of the ~677 px content clip frame, while the row block
  is only ~200 px tall — sweeping across the song-info row, and the incoming line rose from the
  same order of distance below; the fix targets a quarter of the row block (~50 px for this
  preset), which is the referenced `target.getHeight()/4` — to be confirmed by the smoke check.
  Driver: `animatedBlockHeightPx` over the layer's own row boxes. Historical modes ignore the basis and
  stay pixel-identical; the preview now measures each layer's block height instead of approximating
  with the main row. Pending a hardware smoke check after merge: with `fade_out_up_fade_in_up`
  (plus one other up/down preset) the outgoing line no longer crosses the song-info row, the
  incoming line rises from a quarter of the block height, and the preview matches the device.
- Lyricon backfill/real-change classification race fix (issue #56): the re-sync decision window is
  armed only at subscribe actions (`start()`, forced resubscribe, disconnect, connect timeout) and
  no longer re-armed by a late `onConnected`/`onReconnected` — the SDK was observed delivering the
  subscribe backfill `onSongChanged` 12 ms before the connect callback, which re-opened the window
  and made a real track change look like a re-sync (see the 2026-09-29 ledger row). Defense in
  depth: the exact-match residual filters stay armed through a re-sync (a frozen writer keeps
  re-sending the previous song's last value at ~60 Hz), and a backfill carrying a changed song id
  resets the timeline to 0 with the previous song's last position registered for exact-match
  rejection while the plausibility gate stays open (the new song may already be mid-playback).
  Unit-tested (backfill-before-connect ordering, cross-reconnect song change, frozen residual
  surviving a re-sync; same-id re-delivery still re-syncs) — pending a hardware smoke check after
  merge: play a track past the middle, let the lyric feed reconnect (or watch a track change land
  right around a reconnect), and confirm the lyrics stay on the current line instead of jumping to
  the song tail placeholder; with the writer frozen (screen off) the old-song value must never
  jump the active line.
- App text color and font settings land in the app-appearance screen: text color recolors the
  primary text tokens only (`onBackground`/`onSurface`/`onSurfaceContainer` — row titles, bare
  texts, card content and icons that follow the content color), while secondary text (summaries,
  section titles, trailing action labels) keeps the theme hierarchy; the font row offers follow
  system (default), serif and monospace plus every font already imported for the lyric rendering
  (labeled by import name; the app process reads its own private font files directly), and a
  deleted custom font falls back to follow system in both the picker and the render. Both settings
  persist in `app_ui_appearance` prefs and round-trip through config backup. App-UI only (no
  SystemUI/AOD surface change) — pending a hardware smoke check after merge: picking a text color
  re-tints row titles and card text immediately while summaries stay theme-colored, picking a font
  re-renders the whole app UI in that font across screens, follow-system restores the platform
  default, and both survive config backup export/import.
- HyperOS dynamic island doze retention guard (defensive fix for a stock SystemUI race): while
  `PowerManager.isInteractive` is false the guard forces the island window root
  (`miui.systemui.dynamicisland.window.DynamicIslandWindowView`) GONE with a restore ledger and a
  bounded one-second re-assert, and rewrites host visibility requests on that root while recording
  the host's latest intent; on the next interactive observation the belief state is restored.
  Device evidence (24122RKC7C / HyperOS 3, 2026-10-02): the stock hide chain is transition-event
  driven (keyguard-showing → island tempHidden → `hideAllElementSurface` + `relayoutToMini`); a
  sleep followed 3 s later by a policy-initiated wake (`WAKE_REASON_UNKNOWN`) and trusted-device
  unlock consumed the pending lock transition, leaving the expanded island shown while the display
  was already in `DOZE` (captured 02:40:19.5→02:40:21.6); when that wake does not rescue the state
  the island persists over AOD — the reported "AOD sometimes retains the pre-screen-off super
  island". 7/7 clean-sleep trials confirm the island is stock-hidden in doze, so suppression only
  restores stock intent. Pending a hardware smoke check after merge: sleep with music playing and
  the island expanded, confirm the AOD shows no island pill (logcat `AodIslandGuard` gate lines on
  doze entry), wake and confirm the island reappears normally on the lockscreen/unlocked screen,
  and confirm repeated sleep/wake cycles never leave the island stuck.
  Live-occurrence follow-up (2026-10-02 16:53 device time, guard active): during a real doze
  session the island root entered doze VISIBLE and the host re-asserted it visible faster than
  once per second, so the 1s poll alone left visible gaps (the reporter still saw the retained
  island). The enforcement seam was upgraded to rewrite `View.setFlags` visibility requests at
  the call site (plus `View.setVisibility`), which must hold the root GONE without flicker;
  pending a re-check on the upgraded build: the `cause=reassert` log cycle goes silent during
  doze and the island stays invisible for the whole session.
- Landscape fullscreen lyric centering (the "landscape fullscreen" switch +
  `aodLandscapeFullscreenSafeMarginPercent`): the content block now anchors inside the same safe
  area the adaptive scale fills (`landscapeSafeRegion`, region top = canvas padding + safe inset).
  Previously the safe inset was only subtracted from the region height while the region still
  started at the canvas padding, so the whole block sat one inset off-center (≈54 px at the default
  6%, ≈92 px after the 1.7× scale — the reported "not in the middle"). The metadata and
  no-metadata branches now share one anchoring path (visual block bounds + safe region), so the two
  can no longer disagree on the centering basis. Pending a hardware smoke check after merge: with
  the default 6% margin the landscape lyrics sit in the middle of the view (equal margins on both
  sides; logcat `Landscape content block anchored … center=… frameCenter=…` must show
  `center == frameCenter`), and the landscape vertical anchor still moves the block toward either
  edge.
- Demo lyric lines follow the interface language (`LyricLayoutScreen.demoLines` / `demoTrack`: an English interface shows the English demo track, every other selection keeps the Chinese demo track; unit-tested) — app-preview-only change, no SystemUI/AOD surface involvement; pending a hardware smoke check after merge: switch the interface language to English and confirm the home and appearance previews show the English demo track, then switch back and confirm the Chinese demo returns.
- In-app preview demo fallback while transport is paused (`ProducerCollectors.presentsLivePreview`: a non-playing producer state no longer takes over the home/appearance preview, which falls back to the built-in demo lines — the arbiter deliberately keeps a paused state forwarded, so the preview previously froze on the last line sung before the pause; unit-tested) — app-preview-only change, no SystemUI/AOD surface involvement; pending a hardware smoke check after merge: pause playback and confirm the preview cycles the demo lines again, and confirm the live lyric returns on resume.
- Word-by-word auxiliary text (`secondaryWordKaraoke` switch, per surface: the first line's
  transliteration/translation rows light up word by word through the same shared karaoke renderer as
  the main line — real per-word windows when the source carries word-level romanization, otherwise
  synthesized from the row's own line window; the rows keep the auxiliary color and bright/dim
  setting, and the `BetterLyrics` float/scale/glow flavour follows the surface's animation mode; the
  second line and its own auxiliary rows never take the effect) — pending a hardware smoke check
  after merge: with the switch on, the auxiliary translation lights up in step with the sung line on
  both surfaces (and floats/scales with the BetterLyrics mode when that is selected), the app preview
  shows the same effect, and with the switch off the auxiliary rows render exactly as before.
- Lyrics no longer sit flush against the canvas content clip (canvas effect allowance: the row stack
  reserves the vertical overdraw of its outermost rows — the glow halo radius (36% of the text size)
  and the `BetterLyrics` unsung-word sink — before the clip edge; the leading row gap is credited
  against the top, the trailing edge is charged in full and grows the measured lockscreen card height
  by the same amount; pure functions `canvasEffectAllowancePx` / `canvasEffectEdgeNeeds` are
  unit-tested, `ArchitectureGuardTest.canvasKeepsRenderEffectsOffTheContentClipEdge` machine-gates the
  wiring into both placement and measurement, and a keyed W-level self-check (`Effect clip check: …`)
  reports any shape whose overdraw the allowance missed) — pending a hardware smoke check after
  merge: with glow on (and the `BetterLyrics` mode selected) the halo of the first and last lyric
  rows fades out instead of ending on a hard line at the canvas/card edge, the card is only as much
  taller as the room reserved, logcat shows no `Effect clip check` warning, and with glow off in a
  non-BetterLyrics mode the layout is pixel-identical to before.
- Music-vs-non-music source eligibility (`MediaSourcePolicy` + the "Hide lyrics for video and other
  non-music audio" switch, default on): a source is excluded only when its player package is a known
  video app (Bilibili, Douyin, Kuaishou, YouTube, …) or its session declares an explicit
  MOVIE/SPEECH/SONIFICATION content type; everything else — including the platform-default
  `CONTENT_TYPE_UNKNOWN` — fails open. LyricInfo skips such sessions when picking (the issue #5
  fallback keeps working for music apps), Lyricon releases the track and silences its watchdogs
  while the active player is non-music; SuperLyric (music-only module) and Spicy (Spotify-only by
  UID) carry no gate. Unit-tested (`MediaSourcePolicyTest` + the Lyricon producer gate cases) —
  pending a hardware smoke check after merge: playing a video in a listed app must show no lyric
  card while music playback (including a music app without injected lyrics) is unchanged, and the
  switch must restore the old behavior when turned off.
- App-internal predictive back navigation (miuix-nav `NavDisplay` back stack replaces the
  `editingSurface` string + `AnimatedContent` switcher; the manifest sets
  `android:enableOnBackInvokedCallback="true"`; the five screens' `androidx.activity` `BackHandler`s
  are removed — `NavDisplay` owns system back, miuix dialogs keep consuming it while open, and the
  four About pages, which previously had no back handling at all, now pop with the rest; the covered
  page fades out via `appNavTransition` instead of resting at the miuix-default alpha 0.9, which
  would show through the transparent page surface of the background-image mode) — app-only change,
  no SystemUI/AOD surface involvement; pending a hardware smoke check after merge: the system back
  gesture follows the finger 1:1 on every sub-page (Home → any sub-page), releasing past the
  threshold pops to Home while releasing early springs back, back on the Home root still exits the
  app, tapping a sub-page item twice does not wedge the stack, and the transition reads correctly
  with a background image set. App → Navigation adds a predictive-back switch and a back trigger
  threshold: with the switch off the page no longer follows the finger (back still pops on
  release), with the threshold above 0% a release short of it springs the page back instead of
  popping (a quick flick still goes back), and a back button / hardware back is never gated by
  the threshold.
- Lyric appearance settings now live in the Settings tab (the standalone appearance editor page and
  its per-surface appearance entry rows are gone): the editor body - collapsible live preview plus
  the placement / text & language / effects / colours / lock-screen card / both-surfaces sections -
  renders inside the Settings tab's AOD and lock-screen segments (`LyricAppearanceSection`), with
  the per-surface lyrics switch as the first row and the AOD behaviour entry / lock-screen wake
  switches as the last; the home status card that used to open the editor now switches to the
  matching segment. App-only change, no SystemUI/AOD surface involvement; pending a hardware smoke
  check after merge: both segments show the full appearance settings inline with the live preview
  pinned above the list (collapsing it frees the space for the long list), every control still
  edits the same document, switching segments swaps the edited surface, and the home status card
  lands on the right segment.
- `BetterLyrics` does no in-word sweep at all (the shared karaoke core `LyricWordKaraokeRenderer`
  gains the pure predicate `karaokeSweepEnabled(betterLyrics) = !betterLyrics`: every word block
  lights up solid the moment it starts being sung, with no fill front anywhere — an incoming row
  after a line change included — while the halo still hangs on the long block only, and every
  non-BetterLyrics mode keeps the in-word sweep unchanged). Scope history: the first rule covered
  only the long syllable (2026-10-04), the second the row carrying one (2026-10-04 night) — both
  left "rows whose syllables are all short keep sweeping" in place, and on-device probes
  (NetEase 《蝴蝶》) showed the reported look unchanged because that song's syllables are all
  ~200 ms, so no rule ever fired; owner review on 2026-10-05 settled on disabling the sweep for the
  whole preset. Unit-tested (`BetterLyricsWordEffectsTest.betterLyricsDisablesTheInWordSweep`) —
  pending a hardware smoke check after merge: no line in the `BetterLyrics` mode shows a
  left-to-right fill front (before or after a line change), the 1.15 long-syllable scale, the halo
  on the long block and the unsung sink / sung float are unchanged, and non-BetterLyrics modes are
  pixel-identical to before. (Superseded by the scope-B restore below, 2026-10-05: short-syllable
  sweeps are back; only long syllables keep the solid fill.)
- Same-line content stabilisation (`shouldAdoptLineEnhancements` in `AodCanvasLayoutPolicy.kt`, wired
  into `AodLyricCanvasView.stabilizeLineEnhancements`): upstream emits the same line twice (with and
  without `words`; verified on device — `words=13 ↔ words=0` for the same line window), and the
  layout engine picks a different wrap path per form, so every switch re-flowed the block (owner
  screen recording, frame by frame: the next line dropped 80 px at the line change, the fill front
  retreated and re-filled). A line now keeps the wrap form it was first seen with; a "words appear"
  upgrade is accepted only within 300 ms before the line starts, "words disappear" downgrades and
  mid-line word-text changes are rejected. Unit-tested (`LineEnhancementStabilityTest`) — pending a
  hardware smoke check after merge: no row re-flow at line changes (the next line keeps its
  position), no fill retreat/re-fill mid-line, and word-timed lines still light up on their real
  word windows when the words arrive before the line starts.
- MIUI long-screenshot proxy (`LongScreenshotScrollProxyView` + `LongScreenshotDragAccumulator`,
  installed from `MainActivity.installLongScreenshotProxy` on Xiaomi/Redmi/POCO builds): MIUI's
  `LongScreenshotUtils$ContentPort` cannot pick a main scroll view inside a Compose host — in debug
  builds the host's exact class name hits its "not scrollable" branch (device log
  `can not run invoke canScrollVertically on background thread`), while in release builds R8 renames
  the class so MIUI falls back to `view.canScrollVertically(1)`, which is also false for Compose
  without an active gesture; the capture then degrades to a single screen (`scrolledY == 0
  isEnd:true`). The proxy sits under the Compose host (real touches never reach it), reports itself
  scrollable so MIUI selects it, forwards MIUI's injected drags to the host on the main thread, and
  reports the accumulated drag as its `scrollY` (capped at 60k px so a capture cannot run away; a
  new long screenshot after a pause starts counting from zero). Unit-tested
  (`LongScreenshotDragAccumulatorTest`) — pending a hardware smoke check: on a Xiaomi device a long
  screenshot of a long page yields a multi-screen stitched image, normal touch/scroll behaviour is
  unchanged, and logcat shows `long screenshot proxy installed`. The existing device evidence comes
  from a debug build; the release-build path (renamed class -> fallback branch) still needs device
  confirmation.
- Producer-ingest line/word window normalization (`LyricTimelineNormalizer`, wired into the Lyricon
  P0 repair and the SuperLyric line-push emit): only self-contradictory shapes are treated — a word
  window sticking out of its line window expands the line window to the union, and a line window
  far beyond the plausible singing span with a plausible word span re-anchors to the words (the
  existing Lyricon judge/thresholds, now a single shared copy); a normal tail (e.g. an 8000 ms line
  window with a 3000 ms word union and a 3000 ms estimate) is returned unchanged, and a line
  delivered without words keeps its window as-is. LyricInfo already word-anchors every worded line
  at ingest and Spicy clamps its fill window at emit per upstream 8422d78, so both stay untouched.
  Unit-tested (`LyricTimelineNormalizerTest`, `LyriconTimelineRepairTest`,
  `SuperLyricTimelineNormalizeTest`) — pending a hardware smoke check after merge: on Lyricon and
  SuperLyric sources the line-change cadence and fill front of normal songs are unchanged (normal
  tails byte-identical), and a line whose word window sticks out no longer contradicts its own line
  window (no mid-line fill retreat/re-fill).
- `BetterLyrics` sweep restored under scope B (the shared karaoke core `LyricWordKaraokeRenderer`'s
  pure predicate becomes `karaokeSweepEnabled(betterLyrics, longSyllable) = !(betterLyrics && longSyllable)`,
  evaluated per word block inside the draw loop): in the `BetterLyrics` mode a long syllable
  (≥700 ms) still lights up solid the moment it starts being sung with no fill front (the halo
  stays on the long block when glow is on), while every other syllable goes back to the historical
  in-word fill band; non-BetterLyrics modes keep sweeping everything. Scope history: long syllable
  only (2026-10-04) → the row carrying one (2026-10-04 night) → the whole preset, 0.3.156 (183),
  PR #177 → this restore (2026-10-05): the four jump causes that forced the whole-preset-off are
  fixed (same-line form stabilisation, next-line text stabilisation, position-driven transition
  clock, producer-ingest window normalisation), so short-syllable sweeps no longer carry that jump.
  Unit-tested (`BetterLyricsWordEffectsTest.betterLyricsDisablesTheInWordSweepOnlyForLongSyllables`);
  stub-compiled `draw()` behaviour verified in both directions (the same assertions FAIL on the
  pre-change file, whose short syllables have no sweep) — pending a hardware smoke check after
  merge: in the `BetterLyrics` mode a long syllable lights up solid (halo on the long block only
  when glow is on) while short syllables show the left-to-right fill band, with no jump at line
  changes; non-BetterLyrics modes pixel-identical to before.
- Plugin-chain merged timeline normalization (`PluginChainMerger.normalizeMergedTimeline`, called
  once from `PluginRuntime.processChain` after the merge loop, before the result is handed
  downstream): when an accepted processor result declares `WORDS`, the plugin word table (text and
  timestamps) applies wholesale and the merged document's line windows run through the same
  `LyricTimelineNormalizer` gate as the producer ingest — union expansion (word window beyond the
  line window) and word re-anchoring (gross line window with a plausible word span) only, normal
  tails byte-identical; without a `WORDS` declaration the merged document is returned as merged
  (the host word table already passed the ingest normalization). Unit-tested
  (`PluginTimelineNormalizeTest`) — pending a hardware smoke check after merge: with a word-timing
  plugin (e.g. lyricfetch) active, a line whose plugin word window sticks out keeps its full
  karaoke fill without an early line hand-off, normal songs keep the same line-change cadence, and
  a plugin that does not declare `WORDS` leaves host rows untouched.

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
- 辅助文本取数（翻译/音译内容在生产者与插件边界上的传递）

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
| 2026-09-29 | 0.3.129 (156) | 歌词源仲裁 | fail | Redmi K80 Pro (`miro`) / OS3.0.6.0.WOMCNXM | trace-observed | 《淑女的品格》整首无歌词（最后 2m43s 全占位）：SDK 先投递订阅补发的 `onSongChanged`、12ms 后才回调 `connected`，迟到的连接回调把 issue #56 补发判定窗口重新打开，2 分钟后的真·切歌被误判为重同步（保留旧位置、清空精确匹配残留过滤、门控敞开）；上一首冻结的共享内存残留（164072ms）被当真实位置接受，活动行跳到歌尾（`idx=58/62`），外推到歌长后钳制稳定占位。同一残留值几分钟后在另两首歌上被关闸的门控正确拒收 —— 门控本身无损，问题只在判定被乱序抢先。修复（判定窗口只在订阅动作处武装 + 重同步分支保留残留过滤 + 补发换歌 id 时归零但门控保持敞开）随本条目落地；真机复验后更新为 pass + device-verified。 |
| 2026-09-29 | 0.3.129 (156) | 辅助文本取数 | fail | Redmi K80 Pro (`miro`) / OS3.0.6.0.WOMCNXM | trace-observed | Sunshine（OneRepublic，网易云经 LyriconProvider）息屏翻译辅助行缺失：渲染配置正确（两个 surface 均 `secondaryMode=Translation`）、渲染门控是直通的；而插件链入链时的 `跳过 AI 翻译: reason=existing_translation` 证明桥输入带非空翻译文本（插件 skip 循环只读 `PluginLyricLine.getTranslation` 加 `isBlank`，与显示门控同一谓词），且 67 行全部作为活动行上过屏 —— 视觉症状本身是用户目击、无截图。已证实的相邻缺陷：宿主在每个生产者/插件边界都丢翻译词表（`translationWords` 从不过桥、只给词表的插件结果从不回填）。修复（翻译文本/词表冗余对在三处边界按兜底取文）随本条目落地；症状的残余嫌疑为已装 provider 构建的翻译覆盖稀疏或运行时显示条件 —— 真机复验后更新为 pass + device-verified。 |
| 2026-10-01 | 0.3.133 (160) | 辅助文本取数 | fail | Redmi K80 Pro (`miro`) / OS3.0.6.0.WOMCNXM | device-verified | 「辅助文字显示第二行歌词」的辅助形态把下一行歌词渲染到 ≈0.9 倍主行字号、读作第二条主行（锁屏）：`LIVE_CARD_SIZE_MULTIPLIER=0.68` 把主行压到 ≈15.6sp，而 14/13sp 绝对可读性下限顶死了辅助字号（真机字形实测 44/40/37px 与 15.6/14/13sp 公式逐一对上；截图已存档）。修复（下限按有效主行字号等比封顶；预览共用同一公式、随本修复一并生效）随本条目落地；真机复验后更新为 pass + device-verified。 |

## 已知未验证路径

- 主行渲染路径的预览/实机同源决策（`planOriginalLine`：静态全亮 / 词级卡拉OK / 共享扫光块三选一，实机画布与 App 内预览读同一份；`BetterLyrics` 档不再被共享行级扫光门拦下；整块横扫只在显式选择该档时出现）——合并后待真机冒烟：带行窗的逐字源（Spicy/LyricInfo/SuperLyric）在 BetterLyrics 档下必须出逐字卡拉OK（此前被 `shouldUseSharedLineLevelSweep` 拦到逐行扫光，表现即「预览有逐字效果、实机没有」）；Gradient 档仍逐行推进；「行进度效果」四档逐档生效（None 静态、Top to bottom 纵向、main only 逐行、whole block 整块）；App 内预览与实机在换歌引导态之外逐档一致（此前逐字源预览整块、实机逐行分叉）。
- 翻译冗余对在生产者/插件桥上的传递（文本缺失时由 `translationWords` 拼出兜底译文、词表原样穿过 `PluginLyricLine.translationWords`、只给词表的插件结果在回向同规则回填；已单测）——合并后待真机冒烟：只带词级翻译的源必须显示翻译辅助行，译文以纯文本到达的曲目显示不变。
- AOD 上逐帧 60 FPS 动画（`docs/ARCHITECTURE.md`："remains unverified and is not a contract"）。
- 实机 `custom`/`noto-sc` 字体经统一 `LyricTypefaceResolver` 路径渲染（预览/实机字体同源）——合并后待真机冒烟确认。
- 共享 `LyricLayoutEngine` 抽取后的断行/行距（算法逐字迁移）——合并后待真机冒烟歌词折行与行距无回归。
- AOD surface 挂载韧性修复（power monitor `attach` 空 context 回退 + runCatching 隔离，ArchitectureGuardTest 守卫）——合并后待真机冒烟：息屏必须显示歌词，`adb logcat -s HyperGlow` 出现 `Power state monitor attached` 且无 `Attach failed`。
- 「辅助文字显示第二行歌词」呈现（`secondaryNextLine` 开关：下一行歌词按辅助文字样式绘制并取代独立下一行行；两种形态颜色均走「下一行颜色」设置）——2026-10-01 真机发现辅助形态以 ≈0.9 倍主行字号渲染、读作第二条主行（`LIVE_CARD_SIZE_MULTIPLIER=0.68` 缩小主行 + 14/13sp 绝对下限顶死辅助字号），修复为下限按有效主行字号等比封顶；合并后待真机冒烟：常规与自定义字号档下辅助形态的第二行都必须明显小于主行，两个 surface 均需确认。已在 0.3.135（162）真机复验通过：锁屏同图 next/main 字形比 0.88 → 0.58（公式预测 0.90 → 0.62），息屏（xlarge）三层对比清楚（证据存档 `adbdiag/live3/`）。
- 「显示第二行辅助文字」（`nextLineAux` 开关：第二行歌词自身也带出辅助文字行——音标/翻译按辅助文字模式取用——四行呈现：第一行歌词、第一行辅助文字、第二行歌词、第二行辅助文字）——0.3.137（164）真机已验证四行顺序（息屏 LyricInfo 源带逐行翻译时）与锁屏呈现；第二行辅助行的折行档改为跟随第二行自身呈现的行数（owner 2026-10-02 反馈「换行效果要跟着第二行不是第一个」），换行动画中第二行辅助行亦改归晋级/入场组（跟随第二行，不再随主行组退场，owner 2026-10-02 反馈），下一行的辅助文字另随插件行表富化回填（SuperLyric 逐行流 + 翻译插件即可出第四行；无插件结果时保留生产者值），待真机复核折行、动画与 SuperLyric 回退三个场景。注：任何来源都没有下一行辅助文字数据时只呈现有内容的部分。
- 「显示第二行辅助文字」前提放宽（`nextLineAux`：以第二行歌词行实际显示为前提——「显示下一行歌词」或「辅助文字显示第二行歌词」任一开启时露出；开启时第二行歌词行本身也按辅助文字形态呈现——即使「辅助文字显示第二行歌词」关闭，此时以「显示下一行歌词」为前提；第二行歌词行以独立暗行呈现时同样追加其辅助文字行，实机与预览同源）——owner 2026-10-04 反馈前提链不成立（原实现第四行只存在于辅助形态：独立暗行形态下既无法在设置页开启、绘制分支也不追加；SurfaceProfile KDoc 中「开启时即使 secondaryNextLine 关也按辅助形态画」的原始约定从未实现，本修复将其落实）；合并后待真机冒烟：「显示下一行歌词」+「显示第二行辅助文字」开启（「辅助文字显示第二行歌词」关）时第二行按辅助形态呈现并带第四行，关闭「显示第二行辅助文字」后回到独立暗行且无第四行，两个开关全关不显示第二行，两个 surface 均需确认。
- 歌曲信息/第二行歌词独立对齐（`metadataAlignment`/`nextLineAlignment`，`auto` 跟随主歌词对齐的解析结果）——合并后待真机冒烟确认。注意：两者默认 `auto` 时，主对齐显式值原本就作用于歌曲信息；行为变化仅在主对齐 `auto` 且歌词右起（RTL）时歌曲信息改为跟随歌词方向（原先固定起始侧），以及主页预览的副文本/歌曲信息行从此与实机一样按行对齐渲染（原先恒起始侧）。
- 首页顶栏右上角重启入口（快捷重启按钮，取代原运行状态列表行，重启对话框与 ShellUtils 路径不变）——合并后待真机冒烟：图标可打开目标选择对话框，确认后 SystemUI/AOD 正常重启。
- 重启对话框「歌词源」目标改为重建**全部四个**源（原为只重建选中源），并与挂钩进程重启解耦（先派发 root 杀进程，歌词源重建不再能把它吞掉）——合并后待真机冒烟：首选源保持默认 `Spicy`、屏上歌词实际来自回退源（如 `SuperLyric`）时，勾选「歌词源」并确认必须能恢复该回退源（此前为空转）；「歌词源」与系统界面/AOD 同时勾选时两个进程仍正常重启；单个源重建失败不得影响其余源。
- 锁屏卡片自适应高度（场景矩形按已定内容宽实测内容行堆叠高度定高；「高度」设置改为上限，基于设置的高度估算仅在内容就绪前兜底位置）——合并后待真机冒烟：单行短歌词卡片贴合内容无大空档（scrim 跟随），多行/辅助行长内容底部不再被裁切，「高度」设置仍按占比封顶。注意：主页预览保持按占比的情景放置（它是放置模拟，不做实测）。
- 换行动画速率（`lineTransitionSpeed`：`Normal`/`Slow`/`Fast`，位于换行动画选项正下方；时长按
  130/210ms 基准 ×1.0/×1.5/×0.6，帧配方与缓动不变，未知值规范化为 `Normal`）——合并后待真机
  冒烟：三档换行时长肉眼可辨且与设置一致（Slow ≈ 195/315ms、Fast ≈ 78/126ms），运动轨迹不变。
- 换行动画覆盖辅助文字（主歌词、音标/翻译辅助行与下一行歌词在主页预览中整块同层进退，与实机
  `drawRows` 单层语义对齐——实机本就整块过渡，此前仅预览对辅助行瞬切）——合并后待真机冒烟：
  实机换行时辅助文字随主行一起淡入淡出/位移而非瞬切，预览呈现与实机一致。
- 歌曲信息内容与分隔符（`metadataParts`/`metadataSeparators`，per-surface，锁屏与息屏各自独立；未显式设置的曲面继承文档级默认值，旧文档升级语义不变）：可选显示哪些切片（歌名/歌手/专辑，恒按规范顺序）与连接分隔符（`newline` 每切片一行=历史默认，或 ` · ` 等行内连接）；画布歌曲信息只按硬换行拆行且最多 3 行（原 2 行），高度预算随切片行数追加。快照携带原始歌名/歌手/专辑，由各渲染面按本面配置重新组装——合并后待真机冒烟：默认「歌名/歌手+换行」与历史一致、选满 3 部分各占一行不裁切、行内分隔符保持单行；在息屏改切片/分隔符后锁屏的歌曲信息组装与布局预算均不联动，反向同样独立。
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
- 显示并发歌词（对唱）（`duetConcurrent`，每 surface 独立，默认开启，仅息屏）：与主行播放窗口重叠达到 1 秒的唱词行紧邻主行块堆叠、各画各的逐字扫光，加入时 180ms 静音淡入，在场时取代独立「下一行」行，整块超出歌词区按共享系数缩小——合并后待真机冒烟：三个接入源（Spicy/Lyricon/LyricInfo）的对唱歌曲在共享窗口内两行同显、并发行跟随「对唱分侧」、关闭开关后与 solo 呈现逐字一致、锁屏卡片按自己的开关同样双行同显、SuperLyric 歌曲无并发行、对唱窗口结束后独立下一行恢复。
- Lyricon 位置通道日志/状态刷屏修复（写入端 ~40ms 更新节奏内的重复位置回调不再触发停滞外推：低于 500ms 下限保持最后真实位置且不发状态；仲裁器仅在来源身份（源+歌曲代）真变时才记「active changed」，同源例行转发不再逐帧刷日志）——合并后待真机冒烟：开诊断日志播歌，`diagnostic-trace.log` 不再被逐帧 `position stalled/resumed`（约 45 行/秒）与逐帧 `active changed` 刷满轮转；真实息屏停滞仍外推且各记一条，换行/换源日志保留。
- 识别对唱标记（`duetMarkers`，per-surface，锁屏与息屏各自独立、默认开启；未显式设置的曲面继承文档级默认值）：行首（男）/（女）/（合）标记被隐去并（无元数据时）驱动对唱左右分侧（按标记出现顺序，先出现者居左）；（副歌）/（间奏）等段落标记同样隐去但不参与分侧；纯标记行（只有标记没有歌词）保留原样显示且可唱估时按 0 字计；关闭后原样显示标记、标记不参与分侧。快照只下发原始行文本与两套预计算分侧（元数据身份版/标记识别版），隐去与选侧推迟到各渲染面按本面开关执行——改一面的开关不联动另一面。同行落地 ingest 时间轴修复（间隙吞进行窗向词对齐/钳制、可疑词级降级）与跨源 seek 转发——合并后待真机冒烟：网易云对唱曲（如《讲男讲女》）按（男）/（女）出现顺序左右分侧且文本无标记、带段落标记的行文本干净（如「（副歌）爱你一万年」显示为「爱你一万年」）且分侧不变、整行（间奏）原样保留不被重锚、逐句起唱点正确（下一句不再提前上屏）、关闭开关恢复原样标记、拖动进度条歌词立即跟手。钳制刻意保守（孤立长窗行原样保留，真实长音安全）：另验慢歌收尾长音按真实起唱点上屏、英文/拉长音歌曲无误钳。
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
- 全部取色弹窗支持预设颜色与颜色代码输入（应用外观的主题色/控件颜色、歌词布局的九个语义歌词色）：
  点按弹窗底部色块预览展开面板——预设色块网格（暖金色/冰蓝色/薄荷绿/樱花粉/奶油黄/薰衣草/黑色/白色，
  点按即应用，当前项描边高亮）与代码输入框（`#66CCFF`，`#` 可省，支持 `#RGB`/`#RRGGBB`/`#AARRGGBB`，
  忽略透明度），「应用」或回车按与滑杆相同的路径写入；非法代码就地提示且不改动当前颜色。仅应用内改动
  （不涉及 SystemUI/AOD surface）——合并后待真机冒烟：三个取色弹窗点色块都能展开面板、预设点按或输入
  代码后色块/色板/预览同步更新且保存后保留、乱码代码保持原色并出现错误提示。
- 换行淡出漂移基准改为行块自身高度（Fade 族基准修复，实机 + 预览）：Fade 族位移取该层行块自身
  行盒包围盒高的 1/4（主歌词 + 辅助文字 + 下一行，不含歌曲信息行；退场层用旧行块、入场层用新行块），
  不再取画布内容裁剪框。修复前真机实测（`fade_out_up_fade_in_up` + Slow，息屏，1080x2400）：旧行
  上移约 169px——正是约 677px 内容裁剪框高的 1/4，而行块高只有约 200px——整块扫过歌曲信息行，
  新行又从同量级的下方升起；修复后同档位移即为行块高的 1/4（该档预计约 50px，与参考实现
  `target.getHeight()/4` 同义），待下述冒烟确认。历史档不受影响（不读该参数），逐像素不变；预览改为测量各层行块
  实测高，不再用主行高近似。合并后待真机冒烟：`fade_out_up_fade_in_up`（再加一档上下向预设）旧行
  不再穿过歌曲信息行、新行自行块高 1/4 处升起，且预览与实机一致。
- Lyricon 补发/真切歌判定乱序修复（issue #56）：重同步判定窗口只在订阅动作处武装
  （`start()`、强制重建订阅、断连、连接超时），不再被迟到的 `onConnected`/`onReconnected`
  重新打开 —— 真机实测 SDK 会先投递补发的 `onSongChanged`、12ms 后才回调连接回调，窗口被
  重开后真切歌被误判为重同步（见 2026-09-29 台账条目）。纵深防御：重同步分支保留精确匹配
  残留过滤器（写入端冻结时旧值以 ~60Hz 续吐）；补发携带的歌 id 变化时时间轴归零并登记旧歌
  末位置精确拒收，但合理性门控保持敞开（新歌可能已播到歌中途）。单元测试覆盖（补发先于
  连接回调、跨重连切歌、冻结残留经重同步仍拒；同 id 重发仍按重同步）——合并后待真机冒烟：
  放歌到中段后让歌词源发生重连（或恰好在重连前后切歌），歌词须跟随当前行而不是跳歌尾占位；
  息屏写入端冻结时旧歌残留值绝不允许把活动行跳走。
- 应用外观页新增文字颜色与字体设置：文字颜色只覆盖主文字 token（`onBackground`/`onSurface`/
  `onSurfaceContainer`——行标题、裸文本、卡片内容与跟随内容色的图标），summary/小节标题/行尾
  动作等次级文字保持主题层级不被抹平；字体选项为跟随系统（默认）、衬线、等宽，外加歌词渲染
  已导入的全部字体（按导入名展示，应用进程直读自己的私有字体文件），已删除字体的旧令牌在
  选择与渲染两端都回落跟随系统。两项均存于 `app_ui_appearance` 偏好并随配置备份往返。仅应用内
  改动（不涉及 SystemUI/AOD surface）——合并后待真机冒烟：选文字颜色后行标题与卡片文字立即
  变色而 summary 保持主题色、选字体后整个应用界面跨屏换字体、跟随系统恢复平台默认、两项随
  备份导出导入。
- 息屏(AOD)渲染模式改从编译后的 AOD profile 解析(`AodStateProjector.projectToDisplay`:weight / 字号档与自定义百分比 / 辅助文字模式 / 逐字动画 / 发光 / 行进度效果 / 折行裁剪 / 字体族 / 换行动画 / 主对齐 / 歌曲信息锚点 / 自适应分节),producer 的 `renderModes` 降为兜底(`compiled` 缺失时行为与改前一致)。
  根因(现场反馈「BetterLyrics 的效果在预览上看得见,实机上没有」):投影层此前只读 `state.renderModes`,而全仓只有 Lyricon 会从 profile 回填它(`LyriconRenderModeMapping.toProducerRenderModes` 是唯一调用点);LyricInfo / SuperLyric 发的是硬编码默认值,Spicy 的桥白名单(`normalizeSpicyBridgeRenderModes`)又不含 `BetterLyrics`,于是这些源下 `animation` 恒为硬编码值再被 `normalizeAodAnimation` 退回 `Gradient`——同一条链上的字号档、字重、辅助文字、发光、字体、换行动画、行进度效果同样不生效。另三项(主对齐 / 歌曲信息锚点 / 自适应分节)历史上读旧 SharedPreferences,而 `ui/` 已无任何写入方(`SettingsPrefs.updateAlignment` 等在 `ui/` 下零调用点),恒取默认值。锁屏面不走这条链(`LyricCanvasMapper` 直接读 SystemUI 侧 customization bundle),所以现象只在息屏出现——这也解释了 PR #138/#139/#140 的验证为何没发现(其「设备验证」栏一直是「待真机冒烟」,且备注里的锁屏观察恰好是好的那条路)。
  同时清理:三个生产者的 `defaultRenderModes()` 兜底值 `animation = "Karaoke fill"` 改为 `"Gradient"`(该遗留名过不了 `normalizeAodAnimation`,是纯误导);Spicy 桥归一化白名单补入 `BetterLyrics`(被静默改写会在归一化处再退回 Gradient)。
  测试:`AodStateProjectorTest` 的「渲染模式透传」改为「编译 profile 覆盖 producer renderModes」,新增 `BetterLyrics` 现场形状的回归用例与 `compiled` 缺失时的兜底用例;UNSYNCED 的换行动画断言同步改口径(`Auto` → `Fade up`)并补一条兜底对照。`ArchitectureGuardTest` 新增两道机器门:投影层必须以 `aodProfile?.… ?: …` 取渲染模式;`producer/` 下不得再出现 `animation = "Karaoke fill"`。
  合并后待真机冒烟(**不声称 device-verified**):歌词源分别选 LyricInfo / SuperLyric / Spicy,息屏与锁屏各验一遍——① 逐字动画选 `BetterLyrics` + 发光开,息屏出现长音节放大/辉光与未唱下沉/已唱上浮(对照 `adb logcat -s HyperGlow` 里 `AodLyricCanvasView` 的 `Render mode: anim=BetterLyrics …`,`anim=Gradient` 即未生效);② 字号档、字重、辅助文字模式、字体族、行进度效果在息屏生效;③ 主对齐(居中/右对齐)、歌曲信息锚点(底部)、自适应分节开关在息屏生效;④ 切回 Lyricon 行为不变(该源本就取 profile,改后仍取 profile,只是路径统一)。
  已知相邻缺口(本 PR 未含,需各自独立处理,均为息屏独有):`aod/AodStateWire` 的 `normalizeAodTransition` 只放行 5 个历史档,25 个 HyperLyrics 预设 id 在息屏会被静默改写为 `Fade up`(锁屏正常)——修它要过 wire 出口白名单,属协议面改动;`metadataSizePercent` 从未过 AOD wire(`AodCanvasContent` 恒为 100),故息屏的「歌曲信息字号」不生效——修它要新增 wire 字段与 BODY_VERSION 递增。两者都请另开 issue/PR,不要混进本 PR。
- 换行动画改为逐行三段式（owner 2026-09-30 定案，实机 + 预览）：此前整块行块（主歌词 +
  辅助文字 + 下一行）单层进退——第一行与第二行用同个动画一起消失/移动、新两行用同个
  动画一起出现；历史档退场/入场还共用同一 elapsed 叠加进行，旧行未走完新行已进场，
  同一句歌词在两层各画一次（真机实测「歌词重叠」根因）。现按「内容是否延续」逐行分流、
  严格序列「退场 → 晋级位移 → 入场」，任意时刻至多一段在播：离场行组（旧行组 = 主歌词 +
  音标/翻译）播所选档退场半段（如「向上渐隐＆向上渐现」的「向上渐隐」）；内容延续的旧
  「下一行」（== 新「主行」）不播半段，只做槽位平移 + 按两槽字号比等比放大 + 自旧行亮度
  升至全亮（基准 220 毫秒 × 速率，FastOutSlowIn，枢轴取行块中心，同 HyperLyric 下一句
  晋级）；新到行（新下一行/新辅助文字）播所选档入场半段（如「向上渐现」）。词表每档
  「X＆Y」两半段分别作用于离场行与进场行；跳行/拖动/跨曲/无下一行行时无晋级段，旧行组
  整体退场、新行组整体进场。歌曲信息行（固定行）不参与。历史档由此由叠加改为严格序列
  （行为变更，owner 明确「之前的动画还没结束新的动画就出现导致歌词重叠」为待修问题）；
  帧配方、缓动曲线、时长与 Fade 族行块基准（PR #113）不变，预览按同一三段时间线分层
  渲染、行高与槽位实测。**已在 160-0.3.133 调试构建上真机验证（Redmi K80 Pro / HyperOS 3 /
  1080x2400，息屏 AOD，`fade_out_up_fade_in_up`，adb screenrecord 65fps 逐帧列簇几何）**：
  换行时旧当前行列簇单独右移淡出、旧「下一行」列簇**原地不动**（修复「两行一起消失」）；
  随后下一行列簇自 [358-418] 平移约 100px 至当前行槽位 [460-542] 并转亮（晋升位移）；
  最后新行在腾出的下一行槽位淡入。三段严格序列、同一句歌词同一时刻只在一层出现
  （无重叠）。跨曲场景（无内容延续）为旧行组整体退场 + 新行组整体进场，符合条款。
- 超级岛息屏残留守卫（对原生 SystemUI 竞态的防御性修复）：设备非交互（`PowerManager.isInteractive`
  为 false）期间，守卫把岛窗口根视图（`miui.systemui.dynamicisland.window.DynamicIslandWindowView`）
  按台账强制 GONE，并以有界 1 秒周期复断言；对宿主发到该根的可见性请求改写为 GONE 并记录宿主最新
  意图；下一次交互态观察时按信念态恢复。真机取证（24122RKC7C / HyperOS 3，2026-10-02）：原生隐藏链
  完全由状态迁移事件驱动（keyguard-showing → 岛 tempHidden → `hideAllElementSurface` +
  `relayoutToMini`）；息屏 3 秒后一次系统策略唤醒（`WAKE_REASON_UNKNOWN`）加蓝牙信任解锁吞掉了未
  落地的锁定迁移，显示器已进入 `DOZE` 而展开态的岛仍为可见（02:40:19.5→02:40:21.6 实拍窗口）；该
  唤醒不发生时岛就以可见态整段留在 AOD 上——即用户报告的「AOD 有时把息屏前的超级岛保留进去」。
  7/7 干净息屏轮次确认岛在 doze 期间本就被系统隐藏，抑制只是恢复系统自身意图。合并后待真机冒烟：
  播放音乐且岛展开时息屏，确认 AOD 无岛残留（息屏进入时 logcat 有 `AodIslandGuard` 闸门行）、唤醒后
  岛在锁屏/解锁画面正常重现、反复息屏/唤醒循环岛不卡留。
  真实复现跟进（2026-10-02 16:53 设备时间，守卫已生效）：真实 doze 会话中岛根以 VISIBLE 态进入
  doze，且宿主以高于每秒一次的频率把它重新置回可见——仅靠 1 秒轮询会在两次压制之间留下可见间隙
  （报告者仍看到了残留岛）。已把强制接缝升级为在调用点同步改写 `View.setFlags` 的可见性请求
  （连同 `View.setVisibility`），必须让岛根在 doze 期间无闪烁地持续保持 GONE；升级包待复验：
  doze 期间 `cause=reassert` 日志周期归于静默、整段息屏岛不再可见。
- 横屏全屏化歌词居中（「横屏歌词全屏化」开关 + `aodLandscapeFullscreenSafeMarginPercent`）：内容块改为在
  自适应缩放所填的同一安全区内锚定（`landscapeSafeRegion`，区间起点 = 画布内边距 + 安全边界）。此前安全边界
  只从区间高度里减掉、区间起点仍取画布内边距，整块因此偏向一侧一个安全边界（默认 6% 时约 54px，放大 1.7 倍
  后约 92px——即反馈的「全屏化后歌词不在正中间」）；同时有无元数据两条分支统一走同一条锚定路径（视觉块包围盒
  + 安全区），不再出现「一条居中、另一条贴边」的口径分叉。合并后待真机冒烟：默认 6% 安全边界下横屏歌词在
  视野正中（两侧留白相等，logcat `Landscape content block anchored … center=… frameCenter=…` 中
  `center == frameCenter`），且「横屏垂直锚点」设置仍能把整块推向顶/底。
- 演示歌词跟随界面语言（`LyricLayoutScreen.demoLines` / `demoTrack`：English 显示英文演示曲，其余选择保留中文演示曲；已有单测）——仅应用内预览改动，不涉及 SystemUI/AOD surface；合并后待真机冒烟：界面语言切到 English，主页与外观预览显示英文演示曲；切回后中文演示曲恢复。
- 传输暂停时 App 内预览回退演示歌词（`ProducerCollectors.presentsLivePreview`：不在播的生产者状态不再接管主页/外观预览，改回退内置演示歌词行——仲裁器有意保留暂停时的冻结状态，此前预览会一直钉在暂停前那句歌词上；已有单测）——仅应用内预览改动，不涉及 SystemUI/AOD surface；合并后待真机冒烟：暂停播放后预览重新循环演示歌词，恢复播放后实时歌词重新接管。
- 辅助文字逐字效果（`secondaryWordKaraoke` 开关，每个 surface 独立：第一行音译/翻译行经与主行同一共享逐字渲染核心随歌词逐字点亮——源带词级音译时间时按真实词窗，否则按该行自身行窗口合成；行沿用辅助行颜色与亮/暗辅助文字档，「BetterLyrics」档的浮动/放大/辉光随该面逐字动画档；第二行歌词及其自身的辅助行不参与）——合并后待真机冒烟：两曲面开启后辅助翻译随演唱行同步逐字点亮（选 BetterLyrics 时同主行一起浮动/放大），App 内预览呈现同一效果，关闭开关时辅助行与改前逐字节一致。
- 歌词不再贴住画布内容裁剪框（画布效果余量：内容块按最外侧两行的绘制外扩量——辉光光晕半径（字号 × 36%）与「BetterLyrics」档未唱字下沉量——预留垂直余量；顶部抵扣首行行前距，底部块尾无现成留白按全额计入并同步计入锁屏卡片实测高度；纯函数 `canvasEffectAllowancePx` / `canvasEffectEdgeNeeds` 已有单测，`ArchitectureGuardTest.canvasKeepsRenderEffectsOffTheContentClipEdge` 机器门钉住「放置 + 自适应高度两侧同接共享余量」，另有按几何签名去重的 W 级自检（`Effect clip check: …`）兜未知形状）——合并后待真机冒烟：发光开启（且选中「BetterLyrics」档）时首/末行歌词的辉光在画布/卡片边缘自然淡出、不再被切平成一条直线，卡片只按让出的余量长高，logcat 无 `Effect clip check` 告警行，关闭发光且非 BetterLyrics 档时布局与改前逐像素一致。
- 「当前音频源是不是音乐」判定（`MediaSourcePolicy` + 「视频等非音乐音频不显示歌词」开关，默认开启）：只有播放器包名命中已知视频应用表（哔哩哔哩、抖音、快手、YouTube 等）或会话显式声明 MOVIE/SPEECH/SONIFICATION 内容类型时才排除，其余（含平台默认的 `CONTENT_TYPE_UNKNOWN`）一律 fail-open 放行。LyricInfo 挑选会话时跳过这类会话（issue #5 兜底对音乐应用仍然有效），Lyricon 在活动播放器为非音乐期间释放曲目并静默看门狗；SuperLyric（只挂钩音乐应用的模块）与 Spicy（UID 校验限定 Spotify）不加门控。已有单测（`MediaSourcePolicyTest` + Lyricon 生产者门控用例）——合并后待真机冒烟：在清单内应用播放视频必须不出现歌词卡片，音乐播放（含没有注入歌词的音乐应用兜底路径）行为不变，关闭开关后恢复历史行为。
- App 内预测性返回导航（miuix-nav `NavDisplay` 返回栈取代 `editingSurface` 字符串 + `AnimatedContent` 切换器；manifest 置 `android:enableOnBackInvokedCallback="true"`；五个屏的 `androidx.activity` `BackHandler` 全部移除——系统返回由 `NavDisplay` 接管，弹窗打开时仍由 miuix 弹窗自身消费，此前完全没有返回处理的关于页四屏现在也随栈返回；被覆盖页经 `appNavTransition` 淡出，而不是停在 miuix 默认的 alpha 0.9——背景图片模式下页面容器色透明，0.9 会让下层卡片透出）——仅应用内改动，不涉及 SystemUI/AOD surface；合并后待真机冒烟：任意子页（主页 → 子页）的系统返回手势 1:1 跟手，过阈值松手回到主页、未过阈值弹回原页，主页根上返回仍退出应用，同一入口连点两次不会卡住返回栈，设置背景图片时转场观感正确。App → 界面导航新增「预测性返回」开关与「返回触发阈值」：开关关闭后页面不再跟手（松手仍返回上一页）；阈值大于 0% 时拖动不足该比例松手会弹回原页（轻快一甩仍返回）；返回键/顶栏返回按钮不受阈值限制。
- 歌词外观设置并入「设置」页（独立外观编辑器页与「息屏外观/锁屏外观」入口行取消）：编辑器本体——可折叠实时预览 + 位置/文字与语言/效果/颜色/锁屏卡片/两个显示区域——改由 `LyricAppearanceSection` 渲染在设置页的息屏/锁屏分段内（该面歌词总开关排在列表首，息屏行为入口/锁屏唤醒排在列表尾）；原先打开编辑器的状态页外观卡改为切到对应分段。仅应用内改动，不涉及 SystemUI/AOD surface；合并后待真机冒烟：两个分段直接铺开全部外观项、实时预览常驻列表上方（折叠后让位给长列表），各控件改的仍是同一份文档、切分段即换编辑面，状态屏外观卡落在正确分段。
- 「BetterLyrics」档整档不做逐字扫光（共享逐字渲染核心 `LyricWordKaraokeRenderer` 的纯函数 `karaokeSweepEnabled(betterLyrics) = !betterLyrics`：所有词块「开始唱即整块亮起」，不出现填充前缘——换行后的新行同理；辉光仍只挂长音节词块；非 BetterLyrics 档保持词内扫光不变）。口径沿革：只关长音节（2026-10-04）→ 关「含长音节的整行」（2026-10-04 晚）——两版都留下「行内音节全短时整行照旧逐字填」的残留；真机探针（网易云《蝴蝶》）实测该曲每个词首窗仅 200ms 级，任何口径都不触发，故观感与改前无差别；owner 2026-10-05 复核后定案整档关闭。已有单测（`BetterLyricsWordEffectsTest.betterLyricsDisablesTheInWordSweep`）——合并后待真机冒烟：BetterLyrics 档任何行（含换行后新行）都没有从左往右的填充前缘，长音节放大 1.15、光晕只挂长音节、未唱下沉/已唱上浮不变，非 BetterLyrics 档与改前逐像素一致。（已被下方口径 B 恢复条目取代，2026-10-05：短音节扫光回归，仅长音节保持整块亮起。）
- 同行内容稳定化（`shouldAdoptLineEnhancements`，`AodCanvasLayoutPolicy.kt`，接入 `AodLyricCanvasView.stabilizeLineEnhancements`）：上游对同一行两阶段下发（带/不带词表，真机实测同一行窗下 `words=13 ↔ words=0`），而折行引擎按形态走两条路径，于是每次切换都重排——owner 录屏逐帧实测：换行瞬间下一行下移 80px、填充前缘倒退重填。现在同一行只认第一次的折行形态；「无词→带词」升级仅在行开始前 300ms 内接受，「带词→无词」回退与演唱中词表文本变化一律拒绝。已有单测（`LineEnhancementStabilityTest`）——合并后待真机冒烟：换行时不再重排（下一行位置不动）、填充不再倒退重填，词表在行开始前到达的行仍按真实词窗点亮。
- 下一行文本稳定化（`isNextLineStale`，接入 `AodLyricCanvasView.stabilizeLineEnhancements`）：换行时旧「下一行」被晋级成主行，而上游 `nextLine` 要等下一句推来才推进——真机录屏实测换行后 0.5s 内下一行行与主行同文，随后文本切换改变行集合/行高，表现为「换行动画后跳一下」。现在与主行同文的下一行视为「未就绪」，沿用上一版文本，行占位与行高保持稳定。已有单测（`LineEnhancementStabilityTest`）——合并后待真机冒烟：换行后下一行行不出现与主行同文的重复、也不因文本切换而跳位；真正的新下一行到达后正常替换。
- 换行动画改吃「歌词时钟」（`lineTransitionClockAtPosition` + 位置高水位，接入 `AodLyricCanvasView` 三段式过渡）：过渡进度不再用挂钟（`elapsedRealtime` 起点）计时，改由歌词位置推导——过渡开始时记下位置（同源 `projectedPosition()`），各段按位置推进量在既有时间线上换算，段顺序/时长配方/缓动全不变。修复目标：内容（位置/行窗/`nextLine`）在飞行途中才到齐时目标几何被中途重算，表现为「换行后两段位移/单帧跳」（60fps 逐帧实测 813 帧/13.55s/三次换行）。暂停（位置冻结）时过渡冻结在当前进度；位置跳变（seek/拖动，倒退超过 300ms 采样回漂容差）时立即结束过渡而不反向「追」位置。已有单测（`AodCanvasTransitionTest.positionClock*`）——合并后待真机冒烟：60fps 录屏逐帧核晋级行 y 单调、单帧位移 ≤8px、无方向反转；暂停时过渡冻结在当前进度、恢复播放后续播；拖动进度条 seek 时过渡立即结束（静态新内容）、不出现反向追赶。
- MIUI 长截屏代理（`LongScreenshotScrollProxyView` + `LongScreenshotDragAccumulator`，由 `MainActivity.installLongScreenshotProxy` 在小米/Redmi/POCO 上安装）：MIUI 的 `LongScreenshotUtils$ContentPort` 在 Compose 宿主里选不出主滚动视图——debug 包宿主类名命中其「不可滚动」精确匹配分支（真机日志 `can not run invoke canScrollVertically on background thread`），release 包该类名已被 R8 改名、落到 `view.canScrollVertically(1)` 兜底分支（Compose 宿主无进行中手势时同样返回 false），长截屏因此退化成「只截当前一屏」（`scrolledY == 0 isEnd:true`）。代理挂在 Compose 宿主之下（真实触摸到不了它），自报可滚动让 MIUI 选中，把 MIUI 注入的假拖拽在主线程转发给宿主，并以累计拖拽位移充当 `scrollY`（封顶 60k px 防失控，暂停后重新发起的长截屏从零计数）。已有单测（`LongScreenshotDragAccumulatorTest`）——合并后待真机冒烟：小米设备上对长页面截长屏得到多屏拼接长图，正常触摸/滚动行为不变，logcat 出现 `long screenshot proxy installed`。⚠️ 现有真机证据取自 debug 包；release 包（类名被改名→兜底分支）这一环待真机复核。
- 生产者 ingest 行窗/词窗基准统一（`LyricTimelineNormalizer`，接入 Lyricon P0 修复与 SuperLyric 逐行推送 emit）：只治自相矛盾的两类形状——词窗超出行窗时行窗扩到并集；行窗远超可唱估时且词级跨距可信时向词对齐（沿用既有 Lyricon 判据/阈值，全仓单一副本）；正常拖尾（如行窗 8000ms、词窗并集 3000ms、估时 3000ms）原样返回，不带词窗的一笔保持行窗原样。LyricInfo 已在 ingest 对带词行无条件词锚定、Spicy 的行尾钳制是上游 8422d78 语义，两者评估后不动。已有单测（`LyricTimelineNormalizerTest`、`LyriconTimelineRepairTest`、`SuperLyricTimelineNormalizeTest`）——合并后待真机冒烟：Lyricon 与 SuperLyric 源下正常歌曲的换行节奏与填充前缘不变（正常拖尾逐字节一致），词窗越出行窗的行不再自相矛盾（不再出现演唱中填充前缘倒退重填）。
- 「BetterLyrics」档恢复逐字扫光（口径 B；共享逐字渲染核心 `LyricWordKaraokeRenderer` 的纯函数改为 `karaokeSweepEnabled(betterLyrics, longSyllable) = !(betterLyrics && longSyllable)`，在 draw 循环内逐词块判定）：长音节（≥700ms）仍「开始唱即整块按已唱色亮起」、不出现填充前缘（发光开启时光晕只挂长音节），其余音节恢复历史词内扫光带；非 BetterLyrics 档长/短音节全部扫光不变。口径沿革：只关长音节（2026-10-04）→ 关「含长音节的整行」（2026-10-04 晚）→ 整档关闭（0.3.156 (183)，PR #177）→ 本次恢复短音节扫光（2026-10-05）：当初逼出整档关闭的四条跳变成因已修（同行形态稳定化 / 下一行文本稳定化 / 位置时钟过渡 / 摄取归一），短音节扫光不再带当初的跳变观感。已有单测（`BetterLyricsWordEffectsTest.betterLyricsDisablesTheInWordSweepOnlyForLongSyllables`），并已用桩 `android.graphics` 编译真实渲染文件实调 `draw()` 双向验证（同一套断言在改前文件上按预期 FAIL：短音节无扫光渐变）——合并后待真机冒烟：BetterLyrics 档长音节整块亮起（发光开启时光晕只挂长音节）、短音节逐字扫光带恢复、换行无跳变；非 BetterLyrics 档与改前逐像素一致。
- 插件链合并结果的行窗/词窗归一（`PluginChainMerger.normalizeMergedTimeline`，在 `PluginRuntime.processChain` 合并循环之后、交给下游之前的单一落点调用）：本链有被接受的处理器结果声明 `WORDS` 时，插件词表（文本 + 时间戳）整份生效、其词窗即最终值，合并文档逐行过 ingest 同一套 `LyricTimelineNormalizer` 归一——① 词窗超出行窗 → 行窗扩到并集；② 行窗远超可唱估时且词级跨距可信 → 向词对齐；③ 其余（含正常拖尾）逐字节原样；未声明 `WORDS`（宿主词表）时合并结果原样返回，不重复归一宿主词窗。已有单测（`PluginTimelineNormalizeTest`）——合并后待真机冒烟：装带词级时间的插件（如 lyricfetch）时，插件词窗越出行窗的行不再提前交接/词级卡拉OK不再中途消失，正常歌曲换行节奏与改前一致，未声明 `WORDS` 的插件对宿主行零影响。
- 今后凡有没有真机证据的功能落地，先在这里登记；取得证据后移除。

## 台账的使用方式

- 合并触碰某领域的改动前，先查该领域最近一条记录；若改动可能使其回归，评审时要求补充新的真机证据。
- 台账记录历史，不替代 `docs/private/LOCKSCREEN_AOD_IMPLEMENTATION_STATUS.md`（私有文档，
  保存当前工作包按构建的验证明细）。
