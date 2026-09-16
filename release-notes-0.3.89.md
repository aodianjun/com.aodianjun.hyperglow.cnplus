> ⚠️ **测试版本请勿下载** — 此版本为预发行测试版，仅供测试与问题排查，请勿用于日常使用。
## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.89-116.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.89-116.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---

## 更新日志 · v0.3.89 (versionCode 116)【预发行】

### 新功能 · AOD 时钟锚定行为调整（本地需求）

- **暂停 / 没有播放时不钉住系统时钟位置**：关闭「实时跟随系统时钟」（锚定模式）时，系统时钟此前会一直保持在钉住位置、不随防烧屏沉降下移。现在仅在**播放活跃**时钉住；暂停或没有播放时，时钟回到系统自身位置、随防烧屏正常移动。

### 新功能 · 自定义系统时钟 Y 轴高度（本地需求）

- **新增「时钟垂直偏移」滑块**：在关闭「实时跟随系统时钟」时显示，用滑块 (-480..480 px，负值上移、正值下移) 微调被钉住的系统时钟垂直位置，方便把时钟对准到期望高度。
- **配置贯通**：新增 `aodClockYOffset` 偏好，经 `AodRenderPreferences` / `CustomizationModels`（`CompiledCustomization`）/ `RuntimeCustomization`（`DiagnosticLogging`）/ `ConfigBackupCodec` 逐级下发；`AodPositionHook.setClockYOffset` 把偏移叠加到被钉住的时钟 Y（经 `lastStockTranslationY` 锚点，避免逐帧累积）。

### 优化 · HookRegistry 中心注册 + 热重载改造（同步上游 748912e #3）

- **统一注册中心**：所有 hook 改为经 `HookRegistry.hook(module, FEATURE_ID, …)` 安装（替代内联 `deoptimize`/`hook`），按模块统一管理句柄与稳定 hook ID。
- **热重载生命周期**：`onHotReloading`（lyric 会话活跃时拒绝重载）→ `retireGeneration`（退役本代句柄）→ `onHotReloaded`（unhook 旧句柄 + 从重派生宿主应用重装 + 重跑 `SystemUiLifecycleHook.bootstrap`）；AOD 控制器/监测器在重载时做 `cancelPendingForReload`/`stop` 清理。
- **`HookRegistryTest`** 覆盖 hook ID 稳定性；`AntiFreezeHook`（system_server）按设计保持直装。

### 其他

- 更新 README：补充「感谢 / 参考项目」目录。

---

**Full Changelog**: https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus/compare/115-0.3.88...116-0.3.89