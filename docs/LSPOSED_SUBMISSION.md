# LSPosed 模块仓库提交说明

# English / 英文

For submission to the [Xposed Modules Repository](https://modules.lsposed.org) (submission page: https://modules.lsposed.org/submission).

## Repository Information

| Item | Value |
|---|---|
| Repository name | `aodianjun/hyperglow_CNplus` |
| Module package name (applicationId) | `com.aodianjun.hyperglow.cnplus` |
| Repository description (module name) | `Animated lock screen and always-on display lyrics for HyperOS 3` |
| Release tag | `85-0.3.68` (VersionCode-VersionName format) |
| Released APK | `hyperglow-cnplus-release-v0.3.68-85.apk` |

## Pre-submission Checklist

- [x] Repository has a non-empty description (used as the module name)
- [x] At least one valid release containing an apk asset
- [x] Release tag format is `VersionCode-VersionName` (`85-0.3.68`)
- [x] `META-INF/xposed/module.prop` (modern API: minApiVersion / targetApiVersion / staticScope)
- [x] `META-INF/xposed/java_init.list` (module entry point)
- [x] `META-INF/xposed/scope.list` (scope: com.android.systemui, android, com.miui.aod)
- [x] `android:label` = HyperGlow CN+ (module display name)
- [x] LICENSE (GPL-3.0) and NOTICE (derivative copyright notices preserved)

## Submission Statement (paste-ready)

> **HyperGlow CN+** — Animated lock screen and always-on display lyrics for HyperOS 3.
> A standalone, fast-maintained fork of HyperGlow with support for Chinese music apps
> (QQ Music, NetEase Cloud Music, Kugou, etc.) via Lyricon / SuperLyric / LyricInfo,
> and Spotify via Spicy EX.
>
> An independently maintained fork with lyrics sources for Chinese music apps, package name
> `com.aodianjun.hyperglow.cnplus`, co-installable with the original author's version
> without conflict. Code is inherited from the GPL-3.0 HyperLyric / HyperGlow; copyright
> notices are preserved.

## Release Convention Checks (from Xposed-Modules-Repo)

1. The repository name must be the module package name (`com.aodianjun.hyperglow.cnplus`) — it is currently `hyperglow_CNplus`; renaming to comply is recommended, and GitHub will automatically redirect old links.
2. The repository description must be non-empty; it serves as the module name.
3. At least one valid release; the release must contain at least one apk asset, and the tag name format is `VersionCode-VersionName`.
4. Best practice: when creating a release, the bot automatically corrects the tag name.

## Ongoing Maintenance

- Use `VersionCode-VersionName` as the tag for every release (e.g. `86-0.3.69`).
- When updating, edit the release content (not just the assets) to trigger the bot sync.

---

# 中文 / Chinese

用于提交到 [Xposed Modules Repository](https://modules.lsposed.org)（提交页：https://modules.lsposed.org/submission）。

## 仓库信息

| 项 | 值 |
|---|---|
| 仓库名 | `aodianjun/hyperglow_CNplus` |
| 模块包名（applicationId） | `com.aodianjun.hyperglow.cnplus` |
| 仓库描述（模块名） | `Animated lock screen and always-on display lyrics for HyperOS 3` |
| Release tag | `85-0.3.68`（VersionCode-VersionName 格式） |
| 发布 APK | `hyperglow-cnplus-release-v0.3.68-85.apk` |

## 提交前检查清单

- [x] 仓库有非空 description（作模块名）
- [x] 至少一个有效 release，含 apk 资产
- [x] release tag 格式为 `VersionCode-VersionName`（`85-0.3.68`）
- [x] `META-INF/xposed/module.prop`（现代 API：minApiVersion / targetApiVersion / staticScope）
- [x] `META-INF/xposed/java_init.list`（模块入口）
- [x] `META-INF/xposed/scope.list`（作用域：com.android.systemui、android、com.miui.aod）
- [x] `android:label` = HyperGlow CN+（模块显示名）
- [x] LICENSE（GPL-3.0）与 NOTICE（保留衍生版权声明）

## 提交陈述（可粘贴）

> **HyperGlow CN+** —— HyperOS 3 的锁屏与息屏（AOD）歌词动画。
> 独立维护、快速迭代的 HyperGlow 分支，通过 Lyricon / SuperLyric / LyricInfo
> 支持国内音乐软件（QQ 音乐、网易云音乐、酷狗等），通过 Spicy EX 支持 Spotify。
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

- 每次发版用 `VersionCode-VersionName` 作为 tag（如 `86-0.3.69`）。
- 更新时编辑 release 内容（不仅是资产），以触发 bot 同步。
