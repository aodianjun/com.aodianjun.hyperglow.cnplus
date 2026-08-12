# LSPosed 模块仓库提交说明

用于提交到 [Xposed Modules Repository](https://modules.lsposed.org)（提交页：https://modules.lsposed.org/submission）。

## 仓库信息

| 项 | 值 |
|---|---|
| 仓库名 | `aodianjun/hyperglow_CNplus` |
| 模块包名（applicationId） | `com.aodianjun.hyperglow.cnplus` |
| 仓库描述（模块名） | `Animated lock screen and always-on display lyrics for HyperOS 3` |
| Release tag | `87-0.3.69`（VersionCode-VersionName 格式） |
| 发布 APK | `hyperglow-cnplus-release-v0.3.69-87.apk` |

## 提交前检查清单

- [x] 仓库有非空 description（作模块名）
- [x] 至少一个有效 release，含 apk 资产
- [x] release tag 格式为 `VersionCode-VersionName`（`87-0.3.69`）
- [x] `META-INF/xposed/module.prop`（现代 API：minApiVersion / targetApiVersion / staticScope）
- [x] `META-INF/xposed/java_init.list`（模块入口）
- [x] `META-INF/xposed/scope.list`（作用域：com.android.systemui）
- [x] `android:label` = HyperGlow CN+（模块显示名）
- [x] LICENSE（GPL-3.0）与 NOTICE（保留衍生版权声明）

## 提交陈述（可粘贴）

> **HyperGlow CN+** — Animated lock screen and always-on display lyrics for HyperOS 3.
> A standalone, fast-maintained fork of HyperGlow with support for Chinese music apps
> (QQ Music, NetEase Cloud Music, Kugou, etc.) via Lyricon / SuperLyric / LyricInfo,
> and Spotify via Spicy EX.
>
> 独立维护的分支版本，支持国内音乐软件歌词源，包名 `com.aodianjun.hyperglow.cnplus`，
> 与原作者版本互不冲突。代码继承自 GPL-3.0 的 HyperLyric / HyperGlow，已保留版权声明。

## 发布规范核对（来自 Xposed-Modules-Repo）

1. 仓库名需为模块包名（`com.aodianjun.hyperglow.cnplus`）—— 当前为 `hyperglow_CNplus`，
   建议改名以符合规范，GitHub 会自动重定向旧链接。
2. 仓库描述非空，作为模块名称。
3. 至少一个有效 release；release 至少含一个 apk 资产，tag 名格式为 `VersionCode-VersionName`。
4. 最佳实践：创建 release 时 bot 会自动修正 tag 名。

## 后续维护

- 每次发版用 `VersionCode-VersionName` 作为 tag（如 `87-0.3.69`）。
- 更新时编辑 release 内容（不仅是资产），以触发 bot 同步。