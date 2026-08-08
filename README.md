<div align="center">

# HyperGlow CN+

Animated lock screen and always-on display lyrics for HyperOS 3, with support for Chinese music apps.

Requires root, LSPosed and a lyrics source ([Spicy EX](https://github.com/amarinne/spicy-ex) or [Lyricon](https://github.com/tomakino/lyricon)).

**简体中文** · [English](README.md) · [中文](README.zh-CN.md)

</div>

## Features

- Lyrics on the HyperOS lock screen and AOD from multiple sources:
  - **Spicy EX** (Spotify, international).
  - **Lyricon** (popular Chinese music apps — QQ Music, NetEase Cloud Music, Kugou, etc.).
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

## Install

APK from [Releases](https://github.com/aodianjun/hyperglow/releases).

1. Enable HyperGlow in LSPosed.
2. Enable your lyrics source:
   - **Spotify**: enable Spicy EX for Spotify in LSPosed, then enable **Publish lyrics to HyperGlow** in Spicy EX.
   - **Chinese music apps**: enable [Lyricon](https://github.com/tomakino/lyricon) for SystemUI in LSPosed.
3. Set HyperGlow battery usage to **No restrictions**.

> [!NOTE]
> Tested on Xiaomi 14 (`houji`).
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