# 上游同步状态（amarinne/hyperglow → CN+）

> 用途：每次用户要求“同步上游更新”时，先读本文件了解已同步基线，
> 再对照上游新提交清单，判断哪些需要移植。
> 两仓库 git 历史完全独立（CN+ 为重打包独立版），无法直接 merge，
> 只能按内容手工挑选移植。

## 当前状态

- **CN+ 版本**：0.3.82 (109)，上游基线截至 `f9dfa01` + `8422d78`（按内容手工移植）
- **上游最新**：2026-09-01 `8422d78`，版本 0.3.97 (109)
- **上游仓库**：https://github.com/amarinne/hyperglow（default branch: main）
- 基线核实标记（2026-09-05）：AodLyricBridgeService 已含 uid 动态匹配、
  HierarchyFields.kt 及全 hook 使用、missingProbeNames、miuix 走 Maven Central 公共仓库

## 已同步 / 已包含

| 上游提交 | 日期 | 内容 | 状态 |
|---|---|---|---|
| `8422d78` | 2026-09-01 | 中文歌出现日语假名注音 ruby 时拒绝显示（hasLanguageInconsistentKanaRuby/isKana、language 字段贯通、fillEndMs 越行尾合法化 + lineEndMs 渲染钳制） | ✅ 已移植（2026-09-05，按 CN+ 投影层结构改写） |
| `6216fdc` | 2026-08-08 | 版本锁定退役：XiaomiProfileState 增加 AVAILABLE、capability 计数展示（availableCapabilityCount/totalCapabilityCount）、移除 verifiedRuntimeProfile 版本 pin、summary 改为 available=n/total、DiagnosticSetupPolicy 可运行状态集 | ✅ 已移植（2026-09-05；保留 CN+ 实验模式本地覆写逻辑） |
| `c5b1ffa` | 2026-08-11 | DiagnosticContract 校验增加 "available" 状态 | ✅ 已移植（2026-09-05） |
| `f9dfa01` | 2026-08-09 | SystemUI uid 动态匹配 + HierarchyFields 字段链遍历 + 探针缺失日志 | ✅ 已同步（UpdateChecker 部分除外，CN+ 用自己的 VersionCheck.kt） |
| `8d89b10` | 2026-08-06 | AodStateProjector 引入等大重构 | ✅ 已同步 |
| `2608031` | 2026-08-05 | 移除凭据 miuix 仓库（改 Maven Central） | ✅ 已同步 |
| `bf988be` | 2026-08-03 | bump 0.3.50 | ➖ 版本号不适用（CN+ 独立版本号体系） |
| 更早提交 | ≤2026-08-03 | FAQ / 诊断政策 / 历史 | ➖ 未逐个核对（基线整体已含） |

## 待同步（基线之后未被移植的提交）

| 上游提交 | 日期 | 内容 | CN+ 相关性 | 建议 |
|---|---|---|---|---|
| `b0254d5` | 2026-09-01 | AodBrightnessHook（新文件 231 行，hook SystemUI 亮度适配器）+ AodPowerCoordinator 等 | 中 | 可选：新功能，需评估 CN+ AOD 亮度场景是否需要 |
| `0424ae9` | 2026-08-22 | 大批次（~3000 行）：ConfigBackupCodec（配置备份/恢复）、SettingsSession、LucideIcons、LauncherEntryPolicy、通知图标 | 中 | 可选：配置备份用户价值高，但牵扯 MainActivity/PreferenceSettingsStore，工程量大 |
| `ced2769` | 2026-08-13 | AodLifetimePolicy 重构 + 诊断规范 | 低-中 | 暂缓：多为测试与文档 |
| `cc1f62f` | 2026-08-15 | SpicyBridgeDocumentStore/SpicyLyricBridgeService/AodStateBridge 增强 + 测试 | 低 | 暂缓：主体是测试扩充 |

## 同步时的操作流程

1. `git fetch upstream main`（remote `upstream` = https://github.com/amarinne/hyperglow ，已配置）
2. 对照上方“待同步”表，逐个 `git show <sha>` 审查改动
3. 按内容手工移植到 CN+（注意 CN+ 已深度分叉：Lyricon 生产器栈、版本号、CN 音乐应用适配）
4. 版本号不跟随上游（CN+ 独立体系）；UpdateChecker/VersionCheck 功能二选一
5. 移植后跑 CI（554+ 测试），全绿后推送
6. **更新本文件**：把已移植提交移入“已同步”表并更新基线日期
