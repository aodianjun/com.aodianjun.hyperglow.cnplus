## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.83-110.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.83-110.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---

## 更新日志 · v0.3.83 (versionCode 110)

### 新增功能

- **AOD 歌词亮度钳制（上游 `b0254d5`）**：DOZE_AOD 状态下歌词激活时，小米会把屏幕亮度压到远低于可读的水平，导致 AOD 歌词看不清。现在低于可读亮度的请求会被钳制到小米自己的 `BRIGHTNESS_ON` 值；暂停、口袋、脉冲等其它状态完全不动小米原有亮度逻辑，避免与省电机制对抗。
- **诊断证据链补全（上游 `b0254d5` / `cc1f62f` / `ced2769`）**：
  - **App 进程日志镜像**：HyperOS 会丢弃应用进程的 logcat 输出，用户报告里 App 侧决策完全不可见。现在 App 日志会落盘镜像并自动折进诊断报告（按捕获起点过滤、96 KiB 专属预算、照常脱敏），root 被拒的报告也带 App 侧证据。
  - **projection / 文档拒绝原因命名**：原先 19 处静默丢弃（revision 回退、时间戳陈旧、payload 身份失配、时序分叉等）全部改为报出具体原因并去重——"这首歌没歌词"的报告现在能直接分清是生产者停发还是被谁拒绝。
  - **draw wake 脉冲结局分类**：唤醒锁脉冲失败从笼统一句细分为 MISSING_WAKE_LOCK / MISSING_METHOD / INVOCATION_FAILED，去重记录不刷屏。
  - **postHandoff 表面全景快照**：AOD→锁屏联动结束后的 +1s/+7s 各记一次表面状态一行快照，补上竞态高发点的取证盲区。
- **应用内重启可选目标进程**：模块作用域含 SystemUI、`android`、`com.miui.aod` 三个包，但"重启"动作原先只杀 SystemUI，更新模块后 MiuiAOD 仍跑旧代码导致 hook 迟迟不生效。现在重启对话框可分别勾选 SystemUI（锁屏与锁屏编辑同在此进程）和 AOD 进程；两者都选时先杀 AOD 再杀 SystemUI，一次生效；仅选 AOD 时进程未在运行也视为成功（下次启动自然生效）。`android`（system_server）仍只能整机重启，不由此入口处理。
- **AOD 亮度增强开关**：新增应用内设置（息屏行为，默认开启，保持既有行为）。开启时歌词显示期间把过低的 doze 亮度钳制到可读级别；关闭后不再钳制、完全遵循系统原有息屏亮度（歌词可能难以看清）。开关随配置实时下发 AOD 进程，切换后立即对当前息屏生效。

### 修复

- **诊断报告只认 Spotify**：报告工厂原先只读 Spicy 桥并强制 `spotify:track:` URI，Lyricon / SuperLyric / LyricInfo 用户的媒体证据永远为空，且没装 Spotify 会被误判 setup failed 强制引导采集。现在改读生产者仲裁者的活跃源（四源全覆盖），`spotify_package` 仅在无备用歌词源连接时才算硬失败；wire 字段名保留以兼容 intake allowlist。
- **AOD 租约与唤醒竞态（上游 `b0254d5`）**：同 revision keepalive 心跳合并进 pending 快照，避免陈旧 `keepAlive=false` 过期 AOD 租约；wake identity 前移消费，杜绝拒绝后的 attach/wake 循环；alpha 链检测——小米通过祖先 alpha 隐藏 AODView 时不再误判渲染活跃。

### 优化

- **诊断采集窗口按时间过滤**：logcat 从 `-t 4000`（行数截断）改为 `-T <捕获起始时间>`，只收集捕获开始后的日志；引导文案明确要求先复现故障再完成采集。
- **文档全面双语化**：11 份文档（FAQ、数据政策、架构、诊断规范、行为规范、风格指南、发布规范等）全部改为中英双语；"报告问题"相关文档与 App 当前实际对齐（7 个报告分类、三包作用域、四歌词源）。
- **文案与文档修正**：保持息屏活动时长弹窗中「暂停 Spotify 后」改为「暂停音乐后」（不再绑定特定播放器）；FAQ 测试平台信息更正为 Redmi K80 Pro（`miro`）。
- 上游 `8422d78`（v0.3.97）评估收尾：辅助行来源、假名拒绝、fillEnd 钳制补入行为规范文档；测试增至 588 个全部通过。

---

**Full Changelog**: https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus/compare/109-0.3.82...110-0.3.83
