# 上游同步状态（amarinne/hyperglow → CN+）

> 用途：每次用户要求“同步上游更新”时，先读本文件了解已同步基线，
> 再对照上游新提交清单，判断哪些需要移植。
> 两仓库 git 历史完全独立（CN+ 为重打包独立版），无法直接 merge，
> 只能按内容手工挑选移植。

## 当前状态

- **CN+ 版本**：0.3.82 (109)，上游基线截至 `8d89b10`（约 2026-08-06）
- **上游最新**：2026-09-01 `8422d78`，版本 0.3.97 (109)
- **上游仓库**：https://github.com/amarinne/hyperglow（default branch: main）

## 已同步 / 已包含（基线 `8d89b10` 及之前）

| 上游提交 | 日期 | 内容 | 状态 |
|---|---|---|---|
| `8d89b10` | 2026-08-06 | AodStateProjector 引入等大重构 | ✅ 基线（树内已有） |
| `2608031` | 2026-08-05 | 移除凭据 miuix 仓库 | ⚠️ 未同步（树内 gradle 仍引用 miuix） |
| `bf988be` | 2026-08-03 | bump 0.3.50 | ➖ 版本号不适用（CN+ 独立版本号体系） |
| `5a64511` / `7f8e1f5` | 2026-08-03 | FAQ / 杂项 | ➖ 未核对（文档为主） |
| `4e8f7b9` | 2026-08-01 | 诊断数据政策 | ➖ 未核对 |
| `301fc59` / `f668928` / `da44ba5` / `a704bec` | ≤2026-08-01 | 历史提交 | ➖ 未核对 |

## 待同步（上游 `8d89b10` 之后的提交，倒序=最新在前）

| 上游提交 | 日期 | 内容 | CN+ 相关性 | 说明 |
|---|---|---|---|---|
| `8422d78` | 2026-09-01 | 日语假名注音与歌曲语言不一致时拒绝显示 ruby（AodStateProjector + SpicyBridgeDocumentStore） | 中 | 日文歌注音误显场景；CN+ 的 AodStateProjector 已深度修改，需手工比对移植 |
| `b0254d5` | 2026-09-01 | AodBrightnessPolicy（AOD 亮度策略）等 + 测试 | 中 | |
| `0424ae9` | 2026-08-22 | 大批次：SettingsSession、ConfigBackupCodec（配置备份）、LauncherEntryPolicy、图标注册表（~3000 行） | 中 | |
| `cc1f62f` | 2026-08-15 | AodLifetime/GenerationBoundLatest/投影测试扩充 + SpicyBridgeDocument 增强 | 低（多为测试） | |
| `ced2769` | 2026-08-13 | AodLifetimePolicy 重构 + 诊断规范更新 | 中 | |
| `c5b1ffa` | 2026-08-11 | 修复诊断报告校验（DiagnosticContract） | 低 | |
| `f9dfa01` | 2026-08-09 | **动态匹配 SystemUI 的 resolved uid/resolve 字段**（XiaomiCapabilityResolver、SystemUiClockMorphHook）+ 新增 UpdateChecker | **高** | HyperOS 兼容性核心；注意 CN+ 已有自己的 VersionCheck.kt（update/UpdateChecker 功能重叠，移植时跳过或替换） |
| `6216fdc` | 2026-08-08 | 取消版本锁定，模块正常显示工作状态 | 中 | |

## 同步时的操作流程

1. `git fetch upstream main`（remote `upstream` = https://github.com/amarinne/hyperglow ，已配置）
2. 对照上方“待同步”表，逐个 `git show <sha>` 审查改动
3. 按内容手工移植到 CN+（注意 CN+ 已深度分叉：Lyricon 生产器栈、版本号、CN 音乐应用适配）
4. 版本号不跟随上游（CN+ 独立体系）；UpdateChecker/VersionCheck 功能二选一
5. 移植后跑 CI（554+ 测试），全绿后推送
6. **更新本文件**：把已移植提交移入“已同步”表并更新基线日期
