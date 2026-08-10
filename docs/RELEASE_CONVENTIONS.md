# Release Conventions

Status: canonical release notes format

This document defines the release notes (更新日志 / changelog) format used for every GitHub
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
- **预发行版本（Pre-release）**：所有以 Pre-release 发布的版本，说明正文开头必须添加醒目提示 `> ⚠️ **测试版本请勿下载**`，避免用户误将测试包当作正式版安装。CI 自动发布的 debug 版本始终遵循此规则。