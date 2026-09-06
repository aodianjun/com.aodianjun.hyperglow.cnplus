# LSPosed 推送操作手册

> 用途：每次要求"推送 LSP / 发布到 LSPosed"时，先读本文件按流程执行。
> 前置背景见 [LSPOSED_SUBMISSION.md](LSPOSED_SUBMISSION.md)（首次提交规范）。

## 原理（2026-09-06 实测修正）

LSPosed 模块仓库（modules.lsposed.org）**实际读取的是
`Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus` 这个镜像仓库的 releases**，
其下载链接指向 `assets.lsposed.org`（镜像仓库资产的 CDN）。

**官方 bot 的"编辑源仓库 release 自动同步"机制已失效**——镜像停留在 89-0.3.70
（2026-08-18）之后就没再自动同步过（0.3.71~0.3.82 全靠/需手动推送）。
因此"推送 LSP" = **手动在镜像仓库创建同名 release**。

## 关键约定

| 项 | 值 |
|---|---|
| 源仓库（代码） | `aodianjun/com.aodianjun.hyperglow.cnplus`（CI 构建产物所在地） |
| 镜像仓库（LSP 实际读取） | `Xposed-Modules-Repo/com.aodianjun.hyperglow.cnplus` |
| Release tag 格式 | `{versionCode}-{versionName}`，如 `109-0.3.82`（LSPosed 规范） |
| 版本号来源 | `app/build.gradle.kts` 的 `versionCode` / `versionName` |
| 镜像凭证 | **需要用户提供 PAT**（见下） |

## 鉴权：为什么需要 PAT

TRAE 沙箱的 gh OAuth token 会被 Xposed-Modules-Repo 组织的第三方应用访问策略拦截
（`HTTP 403: Resource not accessible by integration`，git push 同样 `denied`）。
用户账号本身有 ADMIN 权限，只是该 token 不被组织信任。

**解决**：请用户到 https://github.com/settings/tokens/new?scopes=repo 生成
classic PAT（7 天有效期即可），在会话中提供。用完提醒用户删除：
https://github.com/settings/tokens

## 流程

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

### 5. 清理

- 删除 `.lsp-push/` 临时目录（APK 约 45MB，勿提交入库）。
- 提醒用户删除临时 PAT。
- PAT **绝不写入任何文件或 git 历史**，只在命令 env 中短暂使用。

## 常见问题

- **403 Resource not accessible by integration** → 用 PAT，见"鉴权"节。
- **源 release 不存在** → 版本号没 bump 或 CI release job 没跑，先解决。
- **CI 失败**：常见为 `UiStringsContractTest`——新增 string 没同步到
  `app/translation/strings-template.xml`（values / values-zh-rCN / values-zh-rTW / template
  四处都要有）。
- **发布新版本**：bump 版本 → push main → CI 建源 release → 回到本流程第 1 步。
