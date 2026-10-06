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
| Mirror credentials | The workspace's classic PAT (`gh_token.txt` / `.gh_pat`) — it has admin on the mirror repo (verified 2026-10-07) |

## Current Push Status (verified 2026-10-07)

**Update this table** after every "push LSP" run; read it before the next push to see which versions are still missing.

| Source repository release | Mirror status |
|---|---|
| `193-0.3.166` | ✅ Pushed (2026-10-07, workspace classic PAT) — first stable release since 109 |
| `0.3.83` – `0.3.165` (83 Pre-releases) | ➖ Optional: pre-release test builds, skipped by convention |
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

## Authentication

The workspace token (`gh_token.txt`, sourced from `.gh_pat`) is a **classic PAT** and has
`admin` on the mirror repository — it can read, create releases and upload assets there
(verified 2026-10-07). No extra PAT is needed.

An OAuth token minted by the sandbox instead gets blocked by the Xposed-Modules-Repo
organization's third-party app access policy
(`HTTP 403: Resource not accessible by integration`; git push likewise `denied`) —
the user account has ADMIN permissions, the organization simply does not trust that token.
If you ever hit that 403, ask the user for a classic PAT
(https://github.com/settings/tokens/new?scopes=repo, 7 days is enough), use it only in the
command env, and remind them to delete it afterwards (https://github.com/settings/tokens).

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
| 镜像凭证 | 工作区的 classic PAT（`gh_token.txt` / `.gh_pat`）——实测对镜像仓有 admin（2026-10-07 核实） |

## 当前推送状态（2026-10-07 核实）

每次执行"推送 LSP"后**更新本表**，下次推送前先读此表判断还缺哪些版本。

| 源仓库 release | 镜像状态 |
|---|---|
| `193-0.3.166` | ✅ 已推送（2026-10-07，工作区 classic PAT）——109 之后的第一个正式发行版 |
| `0.3.83` – `0.3.165`（83 个预发行） | ➖ 可选：预发行测试版，按惯例不推 |
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

## 鉴权

工作区 token（`gh_token.txt`，取自 `.gh_pat`）是 **classic PAT**，对镜像仓有 `admin`——
可以读、建 release、传资产（2026-10-07 实测）。**无需另找 PAT。**

若换用沙箱签发的 OAuth token，则会被 Xposed-Modules-Repo 组织的第三方应用访问策略拦截
（`HTTP 403: Resource not accessible by integration`，git push 同样 `denied`）——
用户账号本身有 ADMIN 权限，只是那种 token 不被组织信任。真遇到 403 时再找用户要 classic PAT
（https://github.com/settings/tokens/new?scopes=repo，7 天即可），只在命令 env 里用，用完提醒删除
（https://github.com/settings/tokens）。

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

---

## Operational Notes (2026-10-07)

Learned while pushing `193-0.3.166`; read before the next push.

- **Release `created_at` comes from the tag's commit date.** Creating the release with
  `target_commitish: main` reuses the mirror's stale `main` HEAD (still the 2026-08-18
  `89-0.3.70` merge), so the release sorts *below* older versions in `GET /releases` and the
  module index keeps showing the previous "Latest Release". Do what `109-0.3.82` did:
  1. create a fresh commit — `POST /git/commits` with the mirror `main` tree, that commit as
     parent, message `{tag}` and `author`/`committer` dates set to now;
  2. create an annotated tag object (`POST /git/tags`) pointing at it;
  3. force-update `refs/tags/{tag}` (`PATCH /git/refs/tags/{tag}` with `force: true`);
  4. create the release (`tag_name: {tag}`) — `created_at` then equals the fresh commit date.
- **`make_latest` must be the string `"true"`**; sending the JSON boolean returns 422.
- **Downloading the APKs locally**: `release-assets.githubusercontent.com` fails on Windows
  with schannel `CRYPT_E_REVOCATION_OFFLINE (0x80092013)` — add `curl --ssl-no-revoke`.
  A single connection is throttled to ~50 KB/s, so fetch in parallel Range chunks
  (8 chunks: 52 MB in ~2 min). Always verify against the asset's `digest` field
  (`sha256:…`, available on the release assets API).
- **The module index is rebuilt by the org**, not by our push: `modules.lsposed.org` shows the
  new version only after its next rebuild. Verify the mirror side in the meantime with
  `GET /repos/Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus/releases/latest`.
- Source-release body edits (add/remove a marker line) do not force a rebuild; they are only
  worth trying if the README's "edit the release to retrigger the bot" note applies.

## 实操要点（2026-10-07）

推送 `193-0.3.166` 时踩到的坑，下次推之前先读。

- **release 的 `created_at` 取自 tag 指向的提交日期**。用 `target_commitish: main` 建 release 会复用
  镜像仓陈旧的 `main` HEAD（仍停在 2026-08-18 的 `89-0.3.70` 合并提交），于是该 release 在
  `GET /releases` 里排到旧版本**下面**，模块索引继续显示上一个 "Latest Release"。按 `109-0.3.82`
  的做法：① 建一个全新提交（`POST /git/commits`，树取镜像 `main` 的树、父提交为该提交、
  消息 `{tag}`、author/committer 日期设为当前）；② 建 annotated tag 对象（`POST /git/tags`）指向它；
  ③ 强制更新 `refs/tags/{tag}`（`PATCH /git/refs/tags/{tag}`，`force: true`）；④ 再建 release
  （`tag_name: {tag}`）——此时 `created_at` 就是新提交的日期。
- **`make_latest` 必须是字符串 `"true"`**，传 JSON 布尔会 422。
- **本机下载 APK**：`release-assets.githubusercontent.com` 在 Windows 上会因 schannel
  `CRYPT_E_REVOCATION_OFFLINE (0x80092013)` 失败——加 `curl --ssl-no-revoke`。单连接会被限速到
  ~50 KB/s，用并行 Range 分块（8 块：52 MB 约 2 分钟）。务必用资产的 `digest` 字段（`sha256:…`，
  releases API 提供）校验。
- **模块索引由组织侧重建**，不是我们推送就刷新：`modules.lsposed.org` 要到它下次重建才会显示新版本。
  期间用 `GET /repos/Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus/releases/latest` 核对镜像侧。
- 源仓库 release 的正文编辑（加/删一行标记）不会触发重建；只有在 README 那句「edit the release to
  retrigger the bot」确实适用时才值得一试。
