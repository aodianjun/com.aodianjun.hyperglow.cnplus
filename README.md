<div align="center">

# HyperGlow CN+

Animated lock screen and always-on display lyrics for HyperOS 3, with support for Chinese music apps.
HyperOS 3 的锁屏与息屏（AOD）歌词动画，支持国内音乐软件。

Requires root, LSPosed and a lyrics source ([Spicy EX](https://github.com/amarinne/spicy-ex), [Lyricon](https://github.com/tomakino/lyricon), [SuperLyric](https://github.com/HChenX/SuperLyric) or [LyricInfo](https://github.com/limczhh/LyricInfo)).
需要 root、LSPosed 以及一个歌词源（[Spicy EX](https://github.com/amarinne/spicy-ex)、[Lyricon](https://github.com/tomakino/lyricon)、[SuperLyric](https://github.com/HChenX/SuperLyric) 或 [LyricInfo](https://github.com/limczhh/LyricInfo)）。

</div>

---

# English / 英文

## Features

- Lyrics on the HyperOS lock screen and AOD from multiple sources:
  - **Spicy EX** (Spotify, international).
  - **Lyricon** (popular Chinese music apps — QQ Music, NetEase Cloud Music, Kugou, etc.).
  - **SuperLyric** (active-line push via Binder, works with many music apps).
  - **LyricInfo** (injects elrc/lrc lyrics into the media session metadata of supported apps).
- Line-, word- and syllable-synchronized karaoke.
- Transliteration and translation with Spicy EX Full.

- AOD clock placement and burn-in movement.
- Keep AOD active while lyrics are visible.
- Keep the lock screen awake while music is playing.
- Raise to show AOD instead of the full lock screen.

## Requirements

- Rooted HyperOS 3.
- [LSPosed](https://github.com/LSPosed/LSPosed).
- At least one lyrics source:
  - Spotify with Spicy EX Lite or Full (**Publish lyrics to HyperGlow** enabled).
  - A Chinese music app with [Lyricon](https://github.com/tomakino/lyricon) active in SystemUI.
  - [SuperLyric](https://github.com/HChenX/SuperLyric) active in the system service.
  - [LyricInfo](https://github.com/limczhh/LyricInfo) active in the music app, plus notification access for HyperGlow.

## Install

APK from [Releases](https://github.com/aodianjun/hyperglow/releases).

1. Enable HyperGlow in LSPosed.
2. Enable your lyrics source:
   - **Spotify**: enable Spicy EX for Spotify in LSPosed, then enable **Publish lyrics to HyperGlow** in Spicy EX.
   - **Chinese music apps**: enable [Lyricon](https://github.com/tomakino/lyricon) for SystemUI in LSPosed.
   - **SuperLyric**: enable [SuperLyric](https://github.com/HChenX/SuperLyric) in LSPosed.
   - **LyricInfo**: enable [LyricInfo](https://github.com/limczhh/LyricInfo) for the music app in LSPosed, and grant HyperGlow notification access.
3. Set HyperGlow battery usage to **No restrictions**.

> [!NOTE]
> Tested on Redmi K80 Pro.
> Will eat battery.
> `Raise to show AOD` requires the system **Raise to wake** option enabled.

## Build

JDK 21 and an Android SDK are required. No credentials or accounts are needed — every dependency
resolves from Google's Maven repository and Maven Central.

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

That produces an installable debug APK under `app/build/outputs/apk/debug/`. Released builds are
signed with a private key that is not in this repository, so a build from source will not share the
signing lineage of the published releases: installing your own build over a release requires
uninstalling first, and the LSPosed module has to be re-enabled afterwards.

## Contributing

Read the specs in [`docs/`](docs/) before changing behavior — `ARCHITECTURE.md` for process
boundaries and trust, `LOCKSCREEN_AOD_BEHAVIOR_SPEC.md` for lockscreen/AOD visibility, lifetime, and
power rules, `STYLE_GUIDE.md` for conventions. They are the contract; code that contradicts them is
a bug even when it works.

This repository is generated from a private working repository, so a few things are worth knowing
before opening a pull request:

- Changes are limited to what exists here. A pull request that adds files outside this tree cannot
  be integrated as written.
- `README.md`, `FAQ.md`, and `.gitignore` are generated. Edits to them are lost; raise the change in
  an issue instead.
- Every accepted change is verified on the maintainer's device before release. Unit tests passing is
  necessary, not sufficient — anything touching SystemUI hooks, AOD power, or geometry needs
  hardware verification that cannot run in CI.
- Large or architectural changes are worth discussing in an issue first, so the design can be
  checked against the specs before you build it.

## License

[GPL-3.0](LICENSE). See [NOTICE](NOTICE).

---

# 中文 / Chinese

## 功能特性

- 通过多种来源在 HyperOS 锁屏与 AOD 上显示歌词：
  - **Spicy EX**（国际版，Spotify）。
  - **Lyricon**（热门国内音乐软件 —— QQ音乐、网易云音乐、酷狗音乐等）。
  - **SuperLyric**（通过 Binder 实时推送当前歌词行，支持众多音乐软件）。
  - **LyricInfo**（向受支持应用的媒体会话元数据注入 elrc/lrc 歌词）。
- 支持逐行、逐词、逐音节同步的卡拉OK。
- 搭配 Spicy EX Full 支持音译与翻译。

- AOD 时钟位置与防烧屏位移。
- 歌词显示时保持 AOD 常亮。
- 播放音乐时保持锁屏常亮。
- 拿起手机显示 AOD 而非完整锁屏。

## 环境要求

- 已 root 的 HyperOS 3。
- [LSPosed](https://github.com/LSPosed/LSPosed)。
- 至少一个歌词源：
  - Spotify + Spicy EX Lite 或 Full（需开启 **将歌词发布到 HyperGlow**）。
  - 国内音乐软件 + [Lyricon](https://github.com/tomakino/lyricon)（在 SystemUI 作用域启用）。
  - [SuperLyric](https://github.com/HChenX/SuperLyric)（在系统服务中启用）。
  - [LyricInfo](https://github.com/limczhh/LyricInfo)（在音乐软件中启用，并为 HyperGlow 授予通知使用权）。

## 安装

从 [Releases](https://github.com/aodianjun/hyperglow/releases) 下载 APK。

1. 在 LSPosed 中启用 HyperGlow。
2. 启用你的歌词源：
   - **Spotify**：在 LSPosed 中为 Spotify 启用 Spicy EX，然后在 Spicy EX 中开启 **将歌词发布到 HyperGlow**。
   - **国内音乐软件**：在 LSPosed 中为 SystemUI 启用 [Lyricon](https://github.com/tomakino/lyricon)。
   - **SuperLyric**：在 LSPosed 中启用 [SuperLyric](https://github.com/HChenX/SuperLyric)。
   - **LyricInfo**：在 LSPosed 中为音乐软件启用 [LyricInfo](https://github.com/limczhh/LyricInfo)，并为 HyperGlow 授予通知使用权。
3. 将 HyperGlow 的电池使用设置为 **无限制**。

> [!NOTE]
> 已在 Redmi K80 Pro 上测试。
> 会比较耗电。
> `拿起显示 AOD` 需要系统开启 **抬起唤醒** 选项。

## 构建

需要 JDK 21 与 Android SDK。无需任何凭据或账号 —— 所有依赖均从 Google 的 Maven 仓库和 Maven Central 解析。

```sh
JAVA_HOME=/path/to/jdk21 ./gradlew :app:testDebugUnitTest :app:assembleDebug
```

该命令会在 `app/build/outputs/apk/debug/` 下生成可安装的调试 APK。正式发布版使用不在本仓库中的私钥签名，因此从源码构建不会与已发布版本共享签名链：用自己构建的版本覆盖安装正式版需要先卸载，之后还需要在 LSPosed 中重新启用模块。

## 参与贡献

在改动行为之前，请阅读 [`docs/`](docs/) 中的规范 —— `ARCHITECTURE.md` 说明进程边界与信任、`LOCKSCREEN_AOD_BEHAVIOR_SPEC.md` 说明锁屏/AOD 的可见性、生命周期与电源规则、`STYLE_GUIDE.md` 说明代码约定。它们是约定；与它们相矛盾的代码即使能用也是 bug。

本仓库由私有工作仓库生成，提交 Pull Request 前有几件事值得了解：

- 改动仅限于本仓库已有的内容。新增超出本目录文件的 PR 无法按原样合并。
- `README.md`、`FAQ.md` 和 `.gitignore` 为自动生成，对它们的修改会被丢弃；请改为在 issue 中提出。
- 每个被接受的改动在发布前都会在维护者的设备上验证。单元测试通过是必要条件而非充分条件 —— 任何涉及 SystemUI 挂钩、AOD 电源或几何布局的改动都需要无法在 CI 中运行的硬件验证。
- 大型或架构性改动值得先在 issue 中讨论，以便在动手前对照规范检查设计方案。

## 许可证

[GPL-3.0](LICENSE)。参见 [NOTICE](NOTICE)。