# 音乐频谱契约（Spectrum Contract v2）

> 参考项目 [`hyper-instrument/hyper-spectrum`](https://github.com/hyper-instrument/hyper-spectrum)
> 是 XAS/XANES 科学光谱运行时，不含音频捕获或可视化代码。本实现只借用其工程原则：
> 版本化契约、确定性管线、边界 fail-closed。

## 1. 三种数据源

### 1.1 音乐律动（默认，不监听）

- 只读歌词快照的 `playbackActive`：为真才出频谱，为假立即衰减。
- `playbackActive` 由歌词源（Spotify / QQ 音乐 / 网易云 / 酷狗等音乐 App）发布，
  因此不会对通知音、系统提示音、通话起反应。
- 不申请 `RECORD_AUDIO`、不启动 MediaProjection、不采集任何音频。
- 律动是确定性合成：单调时钟 + 约 109 BPM 脉冲包络 + 低频偏置 + 哈希噪声，
  暂停即衰减到零。

### 1.2 SystemUI 特权回环（`loopback`，移植 HyperWave）

- 设置页「频谱数据源」选 `loopback` 后自动生效，不需要录音授权或 MediaProjection 弹窗。
- SystemUI 或独立的 `com.miui.aod` 息屏进程通过隐藏 `AudioMixingRule` / `AudioMix` /
  `AudioPolicy` 注册一条 `USAGE_MEDIA`、48kHz、PCM_FLOAT、单声道、`routeFlags=3`
  的 playback copy；
  `createAudioRecordSink()` 拿到 `AudioRecord`，交给 Kotlin `SpectrumAnalyzer`。
- system_server 侧 hook `AudioService.isPolicyRegisterAllowed`，只对 SystemUI / miui.aod UID
  且格式命中的单个 mix 放行；SystemUI/miui.aod 侧 hook
  `AudioMix.canBeUsedForPrivilegedMediaCapture` 与 `AudioMix$Builder.build`，
  用线程局部 exact-format 作用域放行格式校验。原始音频输出不被重定向。
- 采集只在「频谱开启 + `playbackActive` + 画布可见」时启动；AOD/锁屏两面合并
  成一个会话，全部释放后停止。
- ROM 不支持（hook 未装、AudioService 方法改名、native 拒绝）时 fail-closed，
  画布回退到 1.1 的合成律动，不崩溃、不伪造音频。
- 源项目：[`leset0ng/hyperwave`](https://github.com/leset0ng/hyperwave)（Apache-2.0）。

### 1.3 App 侧系统回放（可选监听，`capture`）

- 用户在设置页把「频谱数据源」切到 `capture`，再打开「捕获系统回放」才启用。
- MediaProjection + `AudioPlaybackCaptureConfiguration` → `AudioRecord` → FFT。
- 授权、前台服务、失败回退与 v1 相同；未选择时完全不启动。

## 2. 帧契约（capture / loopback 源）

`SpectrumFrame`：

| 字段 | 约束 |
| --- | --- |
| `protocol` | 1 |
| `sequence` | 非负单调 |
| `capturedAtElapsedMs` | `elapsedRealtime`，非负 |
| `active` | 当前是否有能量 |
| `rms` | 有限，`[0,1]` |
| `bands` | 长度 8..32，全部有限且 `[0,1]` |

- 解码时协议/长度/NaN/Inf/越界整帧丢弃；轻微越界钳制。
- 发布节流 45ms（约 20Hz）；停播发静默帧。
- 帧只走独立 AIDL 旁路，不参与歌词 revision/keepalive。

## 3. 渲染

- **背景层（默认）**：频谱画在歌词框内、文字之前，作为透明背景；不额外占高。
- **独立条带（可选）**：关闭「背景层」后画在歌词块下方，按 `heightPercent` 预留高度。
- 样式：
  - `bars`：底部向上的柱状 + 峰值帽；
  - `mirror`：以歌词框/条带垂直中心为轴上下对称，并画一条淡中轴线；
  - `line`：横向折线。
- 配色：`accent` / `primary` / `gradient`；透明度 `opacity`（10..100，默认 40）。
- 帧率：歌词逐字/过渡 16ms；仅频谱时 AOD 100ms（10fps）、锁屏 33ms（30fps）；
  系统省电再减半；停播衰减到零即停帧。
- 透明度只影响频谱，不改歌词颜色/发光。
- 设置页预览按同一套布局/参数绘制：`background` 背景层在歌词内容之前、`bars` 带峰值帽、
  `mirror` 带中轴线、`customColor`/`sensitivity`/`opacity`/`yOffsetPercent` 同源；
  有实时歌词时按 `playbackActive` 门控，暂停即衰减，演示态持续循环。预览侧没有
  SystemUI 回环帧，`loopback`/`capture` 源使用与实机失败回退相同的律动。

## 4. 配置

`SpectrumSpec`：

| 字段 | 默认 | 归一化 |
| --- | --- | --- |
| `enabled` | false | — |
| `source` | `rhythm` | `rhythm/loopback/capture` |
| `style` | `bars` | `bars/mirror/line` |
| `barCount` | 24 | 12..32 |
| `heightPercent` | 100 | 50..200（仅条带模式） |
| `sensitivity` | 100 | 50..200 |
| `color` | `accent` | `accent/primary/gradient` |
| `background` | true | 歌词框内透明背景；false=独立条带 |
| `opacity` | 40 | 10..100 |
| `yOffsetPercent` | 0 | -50..50，频谱整体垂直偏移（息屏可调） |
| `customColor` | `""` | `#RGB/#RRGGBB/#AARRGGBB`；空=跟随 `color` 预设 |

- 关闭时 `normalizeSpectrum` 收敛到默认值，编译 JSON 省略 `spectrum` 字段，
  旧 SystemUI 的 hash 往返仍兼容。
- 开启时字段随 `CompiledCustomization` 下发；app/SystemUI 必须同版本（升级后重启 SystemUI）。

## 5. 权限与失败模式

- 默认律动：无任何音频权限。
- loopback：不申请 `RECORD_AUDIO`/MediaProjection，依赖 LSPosed 作用域包含 SystemUI、
  息屏进程（如有）与系统框架；注册策略需要 SystemUI/miui.aod UID 与匹配格式，
  失败自动回退律动。
- capture：`RECORD_AUDIO` + `FOREGROUND_SERVICE_MEDIA_PROJECTION`，用户显式授权。
- 被采集 App 禁止回放捕获（DRM 等）时无帧/静音，频谱衰减，不伪造数据。
- MediaProjection 授权不能跨进程持久化；App 进程被杀后需重新授权。
- 采集服务只在 `source=capture` 且开关打开时运行；切回律动会停止未被其他面使用的服务。

## 6. 测试

- `SpectrumAnalyzerTest`：单音落点、低频、静音、NaN/Inf。
- `SpectrumWireCodecTest`：Bundle 往返、协议/长度/NaN 拒绝、越界钳制。
- `SceneCompilerTest`：频谱归一化、widget 同步、canonicalize 保留、关闭时字段省略、
  开启时 app→JSON→SystemUI 校验 hash 一致、`loopback` 源保真与未知源回退。
