> ⚠️ **测试版本请勿下载** — 此版本为预发行测试版，仅供测试与问题排查，请勿用于日常使用。
## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.88-115.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.88-115.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---

## 更新日志 · v0.3.88 (versionCode 115)【预发行】

### 新功能 · 按场景 AOD 亮度覆写（同步上游 748912e #9）

- **两模式亮度控制**：`AodBrightnessController.setBrightnessOverride(enabled, level)` 由 `AodSurfaceController.onCustomization` 驱动，`resolveAodBrightnessRequest` 按两种模式解析——按场景自动把超低息屏亮度钳制到可读级别（默认），或使用固定的自定义亮度档位（10–255）。
- **设置项**：新增总开关「AOD 亮度增强」，其下再分「自定义亮度」开关与 10–255 亮度滑块；总开关开启、自定义关闭时即生效按场景自动化。
- **配置字段贯通**：`aodBrightnessOverride` / `aodBrightnessLevel` 经 `AodRenderPreferences` / `CustomizationModels` / `DiagnosticLogging` 逐级下发。

### 新功能 · 配置备份 / 恢复（同步上游 748912e #4，Codec 部分）

- **类型安全编解码**：新增 `ConfigBackupCodec`，以明确的字段清单对全部设置做备份与恢复，替代原 `MainActivity` 中猜测类型的 `exportAllConfig` / `importAllConfig`，避免导入错位。已适配 CN+ 自有字段集（经 `AodRenderConfig.DEFAULTS`）。
- **新增回归测试**：`ConfigBackupCodecTest`（备份可逆性 / 字段归一化 / 容错恢复）。

### 优化 · AOD 最大高度上限放开 + 配置编译缓存（同步上游 748912e #8 / #7）

- **AOD 最大高度硬上限 `0.5→0.9`**：`SurfacePolicyResolver` 放开运行时硬天花板；AOD 内部高度仍按设计保持内容贴合（`AOD_FIXED_MAX_HEIGHT_FRACTION`）。
- **定制编译缓存**：`CustomizationRepository.loadCompiled` 在内存缓存编译结果，仅当持久输入（当前/上一文档、legacy 配置）变化时才重新解码编译，降低 10 Hz tick 的 CPU 与分配。
- **修复**：`AodBrightnessPolicyTest` 亮度覆写调用参数错位、AOD 最大高度断言 0.5→0.9。

---

**Full Changelog**: https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus/compare/114-0.3.87...115-0.3.88