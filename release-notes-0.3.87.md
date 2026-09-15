> ⚠️ **测试版本请勿下载** — 此版本为预发行测试版，仅供测试与问题排查，请勿用于日常使用。
## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.87-114.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.87-114.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---

## 更新日志 · v0.3.87 (versionCode 114)【预发行】

### 新功能 · AOD 画布随设备旋转（同步上游 748912e）

- **`AodOrientationMonitor`（新文件）**：基于加速度计的 AOD 歌词画布旋转。传感器生命周期管理（注册/注销）、防抖窗口、且以纯函数 `resolveAodRotationStep` 解析方向（竖屏/横屏/反向横屏/自动），便于单测验证。
- **投影/画布携带旋转配置**：`aodRotateWithDevice` / `aodRotationMode` / `aodRotationSettleMs` / `aodCanvasAnchor`（横屏锚点 `aodCanvasAnchorLandscape`·横屏字号缩放 `aodLandscapeTextScale`·分朝向 padding）经配置模型、Wire 协议（BODY_VERSION v2，向后兼容）逐级下传至 SystemUI。
- **`aodLandscapeCanvasSize` 逻辑横屏框 + 刚性绘制变换**：`AodLyricCanvasView` 通过画布刚性 `rotate/scale` 变换实现横屏布局，配合横屏锚点、字号缩放与分朝向边距，歌词在设备倾倒时随画布旋转且不产生布局抖动。

### 新功能 · 隐藏系统 AOD 内容（自绘全屏画布时，同步上游 748912e）

- **强制接缝**：`AodSurfaceHook` 新增 `StockVisibilityHooker`，拦截框架 `View.setVisibility` —— 将受抑制容器上任何「非 GONE」请求强制改写回 `GONE`，隐藏小米系统时钟/天气等自绘元素。
- **AodPositionHook**：`suppressActive` 直通 + `holdStockPosition`（歌词时段冻结系统组件束），避免被系统时钟步进干扰。
- **系统时钟保留区**：`AodSurfaceController` 管理时钟保留区，抑制开关关闭时零开销（静态 Hook 安装 + 运行时闸门双层级）。
- 与 CN+ 既有 burnIn / stockWidget 管理机制合并；`suppressActive` 激活时弃用托管的 managed session。
- **两个独立开关接入设置页**：`Aod 画布随设备旋转` 与 `隐藏系统 AOD 内容` 各自可单独开关。

### 修复

- **合并 PR #24（issue #23）**：AOD 时钟锚点下行方向参与 holdMs 防抖；单次下行超过 200px 仍立即跟随；沉降漂移看门狗不再以 24px 强行重锚，只触发几何重算；根高度变化丢锚时继承 sinceElapsedMs，防抖记忆不随重挂清零。`AodPositionUpdateTest` 按新契约重写（2 个旧用例 → 5 个新用例）。

### 测试 / 其他

- 新增 `AodOrientationMonitorTest`（方向解析 / 稳定性判定）。
- 扩展 `AodStateWireCodecTest`、`GenerationBoundLatestTest`、`SystemUiLyricProjectionTest`，覆盖旋转/抑制新字段的编解码与投影链路。

---

**Full Changelog**: https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus/compare/113-0.3.86...114-0.3.87