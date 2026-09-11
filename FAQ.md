# HyperGlow FAQ

# English / 英文

### It does not work on my phone

Submit a report:

**HyperGlow → Report a problem** (or the **Send compatibility report** entry, when shown)

Pick the category that matches the failure: **Compatibility**, **AOD surface**, **Lock screen
surface**, **Spotify bridge**, **System UI crash or restart**, **Configuration**, or **Other**.

A healthy-setup Compatibility report uploads current metadata immediately. Every other category
starts a guided capture: restart System UI, reproduce the failure until it is visible, then return
to HyperGlow and finish. Review the included data, accept the data policy, and upload once.

Current testing covers one device only:

- Redmi K80 Pro (`miro`).
- Android 16.
- SystemUI `16.03.251211.r` (`202501210`).
- Xiaomi AOD `DEV-2327.0.0.1-03022115` (`22327001`).

Other Xiaomi models and software versions may behave differently. Compatibility is not guaranteed.
Submit the report above so support can be evaluated from the exact SystemUI and AOD versions.

### Requirements

- Xiaomi HyperOS.
- Rooted LSPosed.
- A supported lyrics source: Spicy EX in Spotify, or Lyricon / SuperLyric / LyricInfo in the
  matching Chinese music app.
- HyperGlow scoped to System UI, Android system, and Xiaomi AOD.
- System AOD enabled for AOD lyrics.

### Which LSPosed scope?

System UI, Android system (`android`), and Xiaomi AOD (`com.miui.aod`) — all three.

Spicy EX uses a separate Spotify-only scope.

### No capability report appears

1. Launch HyperGlow once.
2. Verify LSPosed scope.
3. Reload the hooks, or use HyperGlow's restart action (System UI and AOD).
4. Reopen HyperGlow.

Still missing? Submit a compatibility report.

### No lyrics appear

Check:

- The music app is actively playing.
- The lyrics source module is installed and active (Spicy EX for Spotify; Lyricon / SuperLyric /
  LyricInfo for their apps).
- For Spicy EX: the HyperGlow bridge is enabled in Spicy EX.
- HyperGlow profile supported.
- Bridge connections between the music app and SystemUI are present.
- Current track has usable lyrics.

### Does it support non-Xiaomi devices?

No.

### Does Spicy EX Lite work?

Yes.

### Battery or burn-in risk?

Extended AOD use consumes more power and increases display-retention risk. Prefer a finite duration
and suitable movement mode.

### Does HyperGlow download lyrics?

No. Lyrics arrive locally from the active lyrics source module (Spicy EX, Lyricon, SuperLyric, or
LyricInfo).

Network access is used only for an explicit diagnostic upload.

### Are diagnostics uploaded automatically?

No. Any diagnostic report upload requires your manual confirmation.

---

# 中文 / Chinese

### 在我的手机上不起作用

请提交报告：

**HyperGlow → 报告问题（Report a problem）**（或应用显示的**发送兼容性报告（Send compatibility report）**入口）

选择与故障匹配的分类：**兼容性（Compatibility）**、**AOD 表面（AOD surface）**、**锁屏表面
（Lock screen surface）**、**Spotify 桥接（Spotify bridge）**、**System UI 崩溃或重启（System UI
crash or restart）**、**配置（Configuration）**或**其他（Other）**。

环境正常的兼容性报告会立即上传当前元数据；其余所有分类都会启动引导采集：重启系统界面、
复现故障直到其可见，然后回到 HyperGlow 完成采集。检查包含的数据、接受数据政策后单次上传。

目前的测试仅覆盖一台设备：

- Redmi K80 Pro（`miro`）。
- Android 16。
- SystemUI `16.03.251211.r`（`202501210`）。
- Xiaomi AOD `DEV-2327.0.0.1-03022115`（`22327001`）。

其他 Xiaomi 机型和软件版本的表现可能有所不同，不保证兼容性。
请提交上述报告，以便根据确切的 SystemUI 和 AOD 版本评估能否提供支持。

### 前置要求

- Xiaomi HyperOS。
- 已 root 的 LSPosed。
- 受支持的歌词源：Spotify 使用 Spicy EX，或对应中文音乐 App 使用 Lyricon / SuperLyric /
  LyricInfo。
- HyperGlow 作用域已限定为 System UI、Android 系统与 Xiaomi AOD。
- 已启用系统 AOD 以显示 AOD 歌词。

### LSPosed 作用域选什么？

System UI、Android 系统（`android`）与 Xiaomi AOD（`com.miui.aod`）——三个都要选。

Spicy EX 使用单独的、仅限 Spotify 的作用域。

### 没有出现能力报告

1. 启动一次 HyperGlow。
2. 检查 LSPosed 作用域。
3. 重新加载 hook，或使用 HyperGlow 内的重启动作（系统界面与 AOD）。
4. 重新打开 HyperGlow。

仍然没有出现？请提交兼容性报告。

### 没有歌词显示

请检查：

- 音乐 App 正在播放。
- 歌词源模块已安装并启用（Spotify 用 Spicy EX；Lyricon / SuperLyric / LyricInfo 用于各自的
  App）。
- 若使用 Spicy EX：Spicy EX 中的 HyperGlow 桥接已启用。
- HyperGlow 配置文件受支持。
- 音乐 App 与 SystemUI 的桥接连接已建立。
- 当前曲目有可用的歌词。

### 支持非 Xiaomi 设备吗？

不支持。

### Spicy EX Lite 能用吗？

能用。

### 耗电或烧屏风险？

长时间使用 AOD 会消耗更多电量并加大显示残留（烧屏）风险。
建议选用有限的显示时长和合适的移动模式。

### HyperGlow 会下载歌词吗？

不会。歌词由当前启用的歌词源模块（Spicy EX、Lyricon、SuperLyric 或 LyricInfo）在本地传送。

网络访问仅用于您明确发起的诊断上传。

### 诊断数据会自动上传吗？

不会。任何诊断报告的上传都需要您手动确认。
