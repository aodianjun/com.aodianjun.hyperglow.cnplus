# Contributing to HyperGlow CN+

# English / 英文

Status: contributor orientation and standing rules

This file is the entry point. The specs are the law; this file tells you which one to read and
which rules are easy to miss.

## Read in this order

1. `docs/ARCHITECTURE.md` — package/process ownership, trust boundaries, capability gates.
2. `docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md` — behavior contract for lockscreen/AOD surfaces.
3. `docs/STYLE_GUIDE.md` — implementation and review patterns (fail-closed, lifecycle, hot paths).
4. `docs/DIAGNOSTIC_REPORTING_SPEC.md` + `DIAGNOSTIC_DATA_POLICY.md` — diagnostics contract.
5. `docs/RELEASE_CONVENTIONS.md` and `UPSTREAM_SYNC.md` — release mechanics and upstream porting.

## Standing rules

- **The specs govern.** Update the governing spec before or with a behavior change; implementation
  status never silently overrides an architecture or behavior contract (STYLE_GUIDE §12).
- **`README.md` and `FAQ.md` are generated artifacts.** Propose changes via an issue instead of
  editing them directly; edits are discarded on regeneration. Files under `docs/` are the right
  place for guides like this one.
- **Unit tests are necessary, not sufficient** (README): anything touching SystemUI hooks, AOD
  power, or geometry needs hardware verification that cannot run in CI. Record the result in
  `docs/REGRESSION.md` and use the STYLE_GUIDE §12 evidence labels honestly — never claim
  `device-verified` from code review, host tests, or an old APK.
- **Large or architectural changes are worth discussing in an issue first**, so the design can be
  checked against the specs before you build it (README). Keep PRs small and focused: one
  behavioral change per PR, with the smallest focused regression test that would fail before the
  fix (STYLE_GUIDE §11).
- **Fail-closed is the default.** Unknown versions, missing symbols, malformed payloads, and
  unsupported profiles disable only the dependent feature; they never guess, reveal content, or
  mutate stock UI (STYLE_GUIDE §3).
- **Proprietary research stays out of the public tree.** Device logs, JADX output, and captures
  belong under the ignored `research/` directory (STYLE_GUIDE §12).
- **Upstream (`amarinne/hyperglow`) is ported by hand**, evaluated per commit and recorded in
  `UPSTREAM_SYNC.md`; direct merges are not possible. The `.github/workflows/upstream-scan.yml`
  workflow only *reports* new upstream commits — the baseline in `.github/upstream-baseline.txt`
  is advanced by a human PR after the port is recorded.

## CI and releases

- CI (`.github/workflows/android.yml`) runs the plugin-API fingerprint check, both trace variants
  (`default` / `notrace`), unit tests, and debug builds on every PR.
- Releases are versionCode-versionName tags built from `main` by the `release` job; prereleases
  must exist before CI publishes (see `docs/RELEASE_CONVENTIONS.md`).

## Where to ask

Open an issue with a diagnostic report attached (HyperGlow → Report a problem). Compatibility
reports are triaged against `docs/DEVICE_COMPAT_MATRIX.md`.

---

# 中文 / Chinese

状态：贡献者导读与长期规则

本文件是入口。规范才是法律；这里告诉你先读哪份、哪些规则最容易漏。

## 按此顺序阅读

1. `docs/ARCHITECTURE.md` —— 包/进程所有权、信任边界、能力门控。
2. `docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md` —— 锁屏/AOD 的行为契约。
3. `docs/STYLE_GUIDE.md` —— 实现与评审模式（fail-closed、生命周期、热路径）。
4. `docs/DIAGNOSTIC_REPORTING_SPEC.md` + `DIAGNOSTIC_DATA_POLICY.md` —— 诊断契约。
5. `docs/RELEASE_CONVENTIONS.md` 与 `UPSTREAM_SYNC.md` —— 发版机制与上游移植。

## 长期规则

- **规范说了算。** 行为变更之前或同时更新对应规范；实现状态绝不静默覆盖架构或行为契约
  （STYLE_GUIDE §12）。
- **`README.md` 与 `FAQ.md` 是生成产物。** 改动请通过 issue 提出，不要直接编辑；重新生成时
  直接修改会被丢弃。本文件这类指南放在 `docs/` 下才是正确位置。
- **单测通过是必要非充分条件**（README）：凡触碰 SystemUI hook、AOD 功耗或几何的改动，都需要
  CI 无法替代的真机验证。验证结果记入 `docs/REGRESSION.md`，并诚实使用 STYLE_GUIDE §12 的证据
  标签——绝不基于代码评审、宿主测试或旧 APK 宣称 `device-verified`。
- **大型或架构性改动值得先在 issue 中讨论**（README），让设计先对照规范检查。PR 保持小而聚焦：
  一次一个行为变更，并附上修复前会失败的最小聚焦回归测试（STYLE_GUIDE §11）。
- **fail-closed 是默认。** 未知版本、缺失符号、畸形 payload 与不支持的 profile 只禁用依赖的
  功能；绝不猜测、不泄露内容、不改原生 UI（STYLE_GUIDE §3）。
- **专有研究不进公开树。** 设备日志、JADX 输出与临时采集放在被忽略的 `research/` 目录
  （STYLE_GUIDE §12）。
- **上游（`amarinne/hyperglow`）按提交人工移植**，逐个评估并记录在 `UPSTREAM_SYNC.md`；无法直接
  merge。`.github/workflows/upstream-scan.yml` 只负责*报告*新上游提交——`.github/upstream-baseline.txt`
  中的基线只在移植记录完成后由人工 PR 推进。

## CI 与发版

- CI（`.github/workflows/android.yml`）在每个 PR 上运行插件 API 指纹检查、双 trace 变体
  （`default` / `notrace`）、单元测试与 debug 构建。
- 发版是由 `release` job 从 `main` 构建的 versionCode-versionName tag；预发行必须在 CI 发布前
  存在（见 `docs/RELEASE_CONVENTIONS.md`）。

## 在哪里提问

开 issue 并附上诊断报告（HyperGlow → 报告问题）。兼容性报告按 `docs/DEVICE_COMPAT_MATRIX.md`
分诊。
