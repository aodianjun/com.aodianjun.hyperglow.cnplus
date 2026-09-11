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
