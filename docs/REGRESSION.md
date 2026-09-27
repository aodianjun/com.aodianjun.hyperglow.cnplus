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

## 已知未验证路径

- AOD 上逐帧 60 FPS 动画（`docs/ARCHITECTURE.md`："remains unverified and is not a contract"）。
- 实机 `custom`/`noto-sc` 字体经统一 `LyricTypefaceResolver` 路径渲染（预览/实机字体同源）——合并后待真机冒烟确认。
- 共享 `LyricLayoutEngine` 抽取后的断行/行距（算法逐字迁移）——合并后待真机冒烟歌词折行与行距无回归。
- AOD surface 挂载韧性修复（power monitor `attach` 空 context 回退 + runCatching 隔离，ArchitectureGuardTest 守卫）——合并后待真机冒烟：息屏必须显示歌词，`adb logcat -s HyperGlow` 出现 `Power state monitor attached` 且无 `Attach failed`。
- 「辅助文字显示第二行歌词」呈现（`secondaryNextLine` 开关：下一行歌词按辅助文字样式绘制并取代独立下一行行；两种形态颜色均走「下一行颜色」设置）——合并后待真机冒烟确认。
- 歌曲信息/第二行歌词独立对齐（`metadataAlignment`/`nextLineAlignment`，`auto` 跟随主歌词对齐的解析结果）——合并后待真机冒烟确认。注意：两者默认 `auto` 时，主对齐显式值原本就作用于歌曲信息；行为变化仅在主对齐 `auto` 且歌词右起（RTL）时歌曲信息改为跟随歌词方向（原先固定起始侧），以及主页预览的副文本/歌曲信息行从此与实机一样按行对齐渲染（原先恒起始侧）。
- 首页顶栏右上角重启入口（快捷重启按钮，取代原运行状态列表行，重启对话框与 ShellUtils 路径不变）——合并后待真机冒烟：图标可打开目标选择对话框，确认后 SystemUI/AOD 正常重启。
- 锁屏卡片自适应高度（场景矩形按已定内容宽实测内容行堆叠高度定高；「高度」设置改为上限，基于设置的高度估算仅在内容就绪前兜底位置）——合并后待真机冒烟：单行短歌词卡片贴合内容无大空档（scrim 跟随），多行/辅助行长内容底部不再被裁切，「高度」设置仍按占比封顶。注意：主页预览保持按占比的情景放置（它是放置模拟，不做实测）。
- 歌曲信息内容与分隔符（`metadataParts`/`metadataSeparator`，文档级全局，息屏与锁屏共用）：可选显示哪些切片（歌名/歌手/专辑，恒按规范顺序）与连接分隔符（`newline` 每切片一行=历史默认，或 ` · ` 等行内连接）；画布歌曲信息只按硬换行拆行且最多 3 行（原 2 行），高度预算随切片行数追加——合并后待真机冒烟：默认「歌名/歌手+换行」与历史一致、选满 3 部分各占一行不裁切、行内分隔符保持单行。
- 预览卡片自适应高度（`LyricPreviewCard` / `AppearanceLivePreview` 面板高度随歌词内容增长，取代固定 150/180dp，钳制在 120-420dp；同一配置生效期间保持已见最大内容高度，演示行循环/逐行折行变化不会让卡片高度来回呼吸）——仅应用内预览改动，不涉及 SystemUI/AOD surface；待真机看一眼：大字号 + 副文本/下一行/歌曲信息全开时内容不再被裁切，周围布局不跳动。
- 今后凡有没有真机证据的功能落地，先在这里登记；取得证据后移除。

## 台账的使用方式

- 合并触碰某领域的改动前，先查该领域最近一条记录；若改动可能使其回归，评审时要求补充新的真机证据。
- 台账记录历史，不替代 `docs/private/LOCKSCREEN_AOD_IMPLEMENTATION_STATUS.md`（私有文档，
  保存当前工作包按构建的验证明细）。
