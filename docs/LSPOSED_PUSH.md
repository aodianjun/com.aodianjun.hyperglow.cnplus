# LSPosed 推送操作手册

# English / 英文

> Purpose: whenever asked to "push LSP" / "publish to LSPosed", read this file first and follow the procedure below.
> For background on the original submission, see [LSPOSED_SUBMISSION.md](LSPOSED_SUBMISSION.md).

## How It Works (revised after live testing, 2026-09-06)

The LSPosed module repository (modules.lsposed.org) **actually reads the releases of
the mirror repository `Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus`**,
whose download links point to `assets.lsposed.org` (the CDN for mirror repository assets).

**The official bot's "edit the source-repository release to auto-sync" mechanism no longer
works** — the mirror has not auto-synced since 89-0.3.70 (2026-08-18); everything from
109-0.3.82 onwards was pushed manually. Therefore "pushing LSP" = **manually creating a
release with the same name in the mirror repository**.

## Key Conventions

| Item | Value |
|---|---|
| Source repository (code) | `aodianjun/com.aodianjun.hyperglow.cnplus` (where the CI build artifacts live) |
| Mirror repository (what LSP actually reads) | `Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus` |
| Release tag format | `{versionCode}-{versionName}`, e.g. `109-0.3.82` (LSPosed convention) |
| Version number source | `versionCode` / `versionName` in `app/build.gradle.kts` |
| Mirror credentials | **Requires a user-provided PAT** (see below) |

## Current Push Status (verified 2026-09-06)

**Update this table** after every "push LSP" run; read it before the next push to see which versions are still missing.

| Source repository release | Mirror status |
|---|---|
| `121-0.3.94` (Pre-release) | ➖ Optional: pre-release test build, may be skipped |
| `120-0.3.93` (Pre-release) | ➖ Optional: pre-release test build, may be skipped |
| `110-0.3.83` (Pre-release) | ➖ Optional: pre-release test build, may be skipped |
| `109-0.3.82` | ✅ Pushed (2026-09-06, manual PAT) |
| `108-0.3.81` | ❌ Not pushed |
| `107-0.3.80` | ❌ Not pushed |
| `106-0.3.79` | ❌ Not pushed |
| `105-0.3.78` | ❌ Not pushed |
| `98-0.3.71` (Pre-release) / `90-0.3.71` (Pre-release) | ➖ Optional: pre-release test builds, may be skipped |
| `89-0.3.70` | ✅ Synced in the bot era |
| `85-0.3.68` | ✅ Synced in the bot era |
| `87-0.3.69` | ❌ Not pushed (tag format is compliant, can be backfilled) |
| `v0.3.65` ~ `v0.3.67` | ➖ Tags carry a `v` prefix, which violates the LSPosed convention, and they predate the first submission — do not push |

> Releases 0.3.72~0.3.77 of the source repository no longer exist (cleaned up and deleted
> after publishing), so there is nothing to backfill. The module page's Latest Release is
> already 109-0.3.82; the missing historical versions only affect the completeness of the
> "View all releases" list — missing 0.3.78~0.3.81 does not block user updates. Whether to
> backfill them is up to the maintainer.

## Authentication: Why a PAT Is Needed

The TRAE sandbox's gh OAuth token is blocked by the Xposed-Modules-Repo organization's
third-party app access policy
(`HTTP 403: Resource not accessible by integration`; git push likewise `denied`).
The user account itself has ADMIN permissions — the organization simply does not trust that token.

**Solution**: ask the user to generate a classic PAT at
https://github.com/settings/tokens/new?scopes=repo (a 7-day validity is enough)
and provide it in the session. Afterwards, remind the user to delete it:
https://github.com/settings/tokens

## Procedure

### 0. Read the "Current Push Status" table

Check whether the target version has already been pushed, and whether historical versions need backfilling (see the table above).

### 1. Confirm the source repository CI is green and the release assets are fresh

```bash
gh run list --repo aodianjun/com.aodianjun.hyperglow.cnplus --limit 3
gh release view {VC}-{VN} --repo aodianjun/com.aodianjun.hyperglow.cnplus \
  --json assets --jq '[.assets[] | {name, size, updatedAt}]'
```

There should be 2 assets (release + debug variants), and `updatedAt` should be later than the last code commit.

### 2. Download the APKs and export the release body

```bash
mkdir -p .lsp-push
gh release download {VC}-{VN} --repo aodianjun/com.aodianjun.hyperglow.cnplus --dir .lsp-push
gh release view {VC}-{VN} --repo aodianjun/com.aodianjun.hyperglow.cnplus \
  --json body --jq '.body' > .lsp-push/body.md
```

(Optional) Review/complete the body content per [RELEASE_CONVENTIONS.md](RELEASE_CONVENTIONS.md).

### 3. Create the release in the mirror repository with the PAT (this is "pushing LSP")

```bash
export GH_TOKEN={user-provided PAT}
gh release create {VC}-{VN} \
  --repo Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus \
  --title "HyperGlow CN+ {VC}-{VN}" \
  --notes-file .lsp-push/body.md \
  .lsp-push/hyperglow-cnplus-release-v{VN}-{VC}.apk \
  .lsp-push/hyperglow-cnplus-debug-v{VN}-{VC}.apk
unset GH_TOKEN
```

If the mirror release already exists but its assets are stale, use `gh release upload --clobber` + `gh release edit` instead.

### 4. Verify

```bash
export GH_TOKEN={PAT}
gh release view {VC}-{VN} --repo Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus \
  --json assets --jq '[.assets[] | {name, size}]'
unset GH_TOKEN
```

Asset names and byte sizes must exactly match those of the source repository. The "Latest Release"
on the module page
https://modules.lsposed.org/module/com.aodianjun.hyperglow.cnplus
should then switch to the new tag (CDN/page lag is usually on the order of minutes).

### 5. Cleanup and Bookkeeping

- Delete the `.lsp-push/` temporary directory (the APKs total ~45MB; never commit them).
- Remind the user to delete the temporary PAT.
- The PAT must **never be written to any file or git history**; it exists only briefly in the command env.
- **Update the "Current Push Status" table in this document** (mark the pushed versions), then commit and push to the source repository main.

## FAQ

- **403 Resource not accessible by integration** → use a PAT, see the "Authentication" section.
- **Source release does not exist** → the version number was not bumped, or the CI release job did not run; fix that first.
- **CI failure**: commonly `UiStringsContractTest` — a newly added string was not synced into
  `app/translation/strings-template.xml` (it must be present in all four places:
  values / values-zh-rCN / values-zh-rTW / template).
- **Publishing a new version**: bump the version → push main → CI creates the source release → return to step 1 of this procedure.

---

# 中文 / Chinese

> 用途：每次要求"推送 LSP / 发布到 LSPosed"时，先读本文件按流程执行。
> 前置背景见 [LSPOSED_SUBMISSION.md](LSPOSED_SUBMISSION.md)（首次提交规范）。

## 原理（2026-09-06 实测修正）

LSPosed 模块仓库（modules.lsposed.org）**实际读取的是
`Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus` 这个镜像仓库的 releases**，
其下载链接指向 `assets.lsposed.org`（镜像仓库资产的 CDN）。

**官方 bot 的"编辑源仓库 release 自动同步"机制已失效**——
镜像在 89-0.3.70（2026-08-18）之后就没再自动同步过（109-0.3.82 起为手动推送）。
因此"推送 LSP" = **手动在镜像仓库创建同名 release**。

## 关键约定

| 项 | 值 |
|---|---|
| 源仓库（代码） | `aodianjun/com.aodianjun.hyperglow.cnplus`（CI 构建产物所在地） |
| 镜像仓库（LSP 实际读取） | `Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus` |
| Release tag 格式 | `{versionCode}-{versionName}`，如 `109-0.3.82`（LSPosed 规范） |
| 版本号来源 | `app/build.gradle.kts` 的 `versionCode` / `versionName` |
| 镜像凭证 | **需要用户提供 PAT**（见下） |

## 当前推送状态（2026-09-06 核实）

每次执行"推送 LSP"后**更新本表**，下次推送前先读此表判断还缺哪些版本。

| 源仓库 release | 镜像状态 |
|---|---|
| `121-0.3.94`（Pre-release） | ➖ 可选：预发行测试版，可不推 |
| `120-0.3.93`（Pre-release） | ➖ 可选：预发行测试版，可不推 |
| `110-0.3.83`（Pre-release） | ➖ 可选：预发行测试版，可不推 |
| `109-0.3.82` | ✅ 已推送（2026-09-06，手动 PAT） |
| `108-0.3.81` | ❌ 未推送 |
| `107-0.3.80` | ❌ 未推送 |
| `106-0.3.79` | ❌ 未推送 |
| `105-0.3.78` | ❌ 未推送 |
| `98-0.3.71`（Pre-release）/ `90-0.3.71`（Pre-release） | ➖ 可选：预发行测试版，可不推 |
| `89-0.3.70` | ✅ bot 时代同步 |
| `85-0.3.68` | ✅ bot 时代同步 |
| `87-0.3.69` | ❌ 未推送（tag 格式合规，可补） |
| `v0.3.65` ~ `v0.3.67` | ➖ tag 带 `v` 前缀不符合 LSPosed 规范，且早于首次提交，不推 |

> 源仓库 0.3.72~0.3.77 的 release 已不存在（发布后被整理删除），无需也无法补推。
> 模块页 Latest Release 已是 109-0.3.82，历史版本仅影响"View all releases"列表完整性，
> 缺 0.3.78~0.3.81 不影响用户更新——是否补推由维护者决定。

## 鉴权：为什么需要 PAT

TRAE 沙箱的 gh OAuth token 会被 Xposed-Modules-Repo 组织的第三方应用访问策略拦截
（`HTTP 403: Resource not accessible by integration`，git push 同样 `denied`）。
用户账号本身有 ADMIN 权限，只是该 token 不被组织信任。

**解决**：请用户到 https://github.com/settings/tokens/new?scopes=repo 生成
classic PAT（7 天有效期即可），在会话中提供。用完提醒用户删除：
https://github.com/settings/tokens

## 流程

### 0. 读"当前推送状态"表

确认目标版本是否已推送、是否需要补推历史版本（见上表）。

### 1. 确认源仓库 CI 绿、release 资产新鲜

```bash
gh run list --repo aodianjun/com.aodianjun.hyperglow.cnplus --limit 3
gh release view {VC}-{VN} --repo aodianjun/com.aodianjun.hyperglow.cnplus \
  --json assets --jq '[.assets[] | {name, size, updatedAt}]'
```

资产应有 2 个（release + debug 变体），`updatedAt` 晚于最后一次代码提交。

### 2. 下载 APK 并导出 release body

```bash
mkdir -p .lsp-push
gh release download {VC}-{VN} --repo aodianjun/com.aodianjun.hyperglow.cnplus --dir .lsp-push
gh release view {VC}-{VN} --repo aodianjun/com.aodianjun.hyperglow.cnplus \
  --json body --jq '.body' > .lsp-push/body.md
```

（可选）按 [RELEASE_CONVENTIONS.md](RELEASE_CONVENTIONS.md) 核对/补充 body 内容。

### 3. 用 PAT 在镜像仓库创建 release（即"推送 LSP"）

```bash
export GH_TOKEN={用户提供的PAT}
gh release create {VC}-{VN} \
  --repo Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus \
  --title "HyperGlow CN+ {VC}-{VN}" \
  --notes-file .lsp-push/body.md \
  .lsp-push/hyperglow-cnplus-release-v{VN}-{VC}.apk \
  .lsp-push/hyperglow-cnplus-debug-v{VN}-{VC}.apk
unset GH_TOKEN
```

镜像仓库 release 已存在而资产陈旧时改用 `gh release upload --clobber` + `gh release edit`。

### 4. 验证

```bash
export GH_TOKEN={PAT}
gh release view {VC}-{VN} --repo Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus \
  --json assets --jq '[.assets[] | {name, size}]'
unset GH_TOKEN
```

资产名和字节数必须与源仓库完全一致。模块页
https://modules.lsposed.org/module/com.aodianjun.hyperglow.cnplus
的 "Latest Release" 随后应变为新 tag（CDN/页面有延迟，通常分钟级）。

### 5. 清理与登记

- 删除 `.lsp-push/` 临时目录（APK 约 45MB，勿提交入库）。
- 提醒用户删除临时 PAT。
- PAT **绝不写入任何文件或 git 历史**，只在命令 env 中短暂使用。
- **更新本文档"当前推送状态"表**（标记已推送版本），提交推送到源仓库 main。

## 常见问题

- **403 Resource not accessible by integration** → 用 PAT，见"鉴权"节。
- **源 release 不存在** → 版本号没 bump 或 CI release job 没跑，先解决。
- **CI 失败**：常见为 `UiStringsContractTest`——新增 string 没同步到
  `app/translation/strings-template.xml`（values / values-zh-rCN / values-zh-rTW / template
  四处都要有）。
- **发布新版本**：bump 版本 → push main → CI 建源 release → 回到本流程第 1 步。
