# Release Conventions

# English / 英文

Status: canonical release notes format

This document defines the release notes (changelog) format used for every GitHub
release. Follow it when writing notes for a new release.

## Format

Notes are written in Chinese, organized by category from most to least important:

```markdown
## 更新日志 · v{version}

### 新增功能
- ...

### 修复
- ...

### 优化
- ...

---

**Full Changelog**: https://github.com/aodianjun/hyperglow_CNplus/compare/v{previous}...v{current}
```

## Rules

- `### 新增功能` — new features, new lyrics sources, new capabilities.
- `### 修复` — bug fixes that affect correctness, stability, or CI failures.
- `### 优化` — CI, packaging, performance, or quality-of-life improvements; version bumps.
- Keep each bullet concise and concrete. Name the affected feature or fix explicitly.
- Preserve the existing "安装包用途说明" section that follows the changelog when present.
- The `Full Changelog` link uses the previous version tag and the current version tag.
- **Pre-release versions**: for every version published as a Pre-release, the body must open with the prominent notice `> ⚠️ **测试版本请勿下载**`, so that users do not mistake a test build for a stable release. CI-published debug builds always follow this rule.
- **Stable releases are immutable**: once a version has been published as a stable (non-prerelease) release, later `main` pushes must not overwrite its assets. CI skips the publish step (with a notice) instead of failing; bump `versionCode` / `versionName` to publish a new release.

## Publishing a stable release

CI only publishes APKs into a **prerelease** release that already exists, so a stable release is prepared as follows:

1. Bump `versionCode` / `versionName` in `app/build.gradle.kts`, push `main`.
2. Pre-create the release for tag `{VC}-{VN}` **as a Pre-release**, with the changelog body, pinned to the bump commit.
3. Wait for CI: both APKs are attached to that release.
4. Edit the release to clear the Pre-release flag and drop the `测试版本请勿下载` notice (for example via `PATCH /repos/{owner}/{repo}/releases/{id}` with `prerelease: false`, `make_latest: true`).

After step 4 the release is frozen: later `main` pushes skip it, and the module is mirrored to the LSPosed module repository (see [LSPOSED_PUSH.md](LSPOSED_PUSH.md)).

---

# 中文 / Chinese

状态：发布日志（release notes）的权威格式

本文档定义每个 GitHub release 所用更新日志（changelog）的格式。
撰写新版本的日志时请遵循本规范。

## 格式

日志以中文书写，按类别从最重要到最次要排序：

```markdown
## 更新日志 · v{version}

### 新增功能
- ...

### 修复
- ...

### 优化
- ...

---

**Full Changelog**: https://github.com/aodianjun/hyperglow_CNplus/compare/v{previous}...v{current}
```

## 规则

- `### 新增功能` — 新功能、新歌词源、新能力。
- `### 修复` — 影响正确性、稳定性或 CI 失败的 bug 修复。
- `### 优化` — CI、打包、性能或体验改进；版本号变更。
- 每条目保持简洁具体，明确点出所涉功能或修复。
- 保留跟在更新日志之后的"安装包用途说明"整节（如存在）。
- `Full Changelog` 链接使用上一版本 tag 与当前版本 tag。
- **预发行版本（Pre-release）**：所有以 Pre-release 发布的版本，说明正文开头必须添加醒目提示 `> ⚠️ **测试版本请勿下载**`，避免用户误将测试包当作正式版安装。CI 自动发布的 debug 版本始终遵循此规则。
- **正式发行版一经发布即冻结**：版本一旦以正式版（非预发行）发布，后续 `main` 推送不得覆盖其资产。CI 会跳过发布步骤并输出 notice（而不是判红整条流水线）；发布新版本请 bump `versionCode` / `versionName`。

## 发布正式发行版

CI 只会把 APK 灌进**已存在的预发行** release，因此正式版的准备流程是：

1. 在 `app/build.gradle.kts` 里 bump `versionCode` / `versionName`，推送 `main`。
2. 以 **Pre-release** 预创建 tag `{VC}-{VN}` 的 release（正文写更新日志），tag 钉在 bump 提交上。
3. 等 CI 把两个 APK 挂上该 release。
4. 编辑该 release，取消 Pre-release 勾选并去掉 `测试版本请勿下载` 提示（例如 `PATCH /repos/{owner}/{repo}/releases/{id}`，`prerelease: false`、`make_latest: true`）。

第 4 步之后该版本即冻结：后续 `main` 推送会跳过它，模块也会被镜像到 LSPosed 模块仓库（见 [LSPOSED_PUSH.md](LSPOSED_PUSH.md)）。
