# LSPosed 推送操作手册

> 用途：每次要求"推送 LSP / 发布到 LSPosed"时，先读本文件按流程执行。
> 前置背景见 [LSPOSED_SUBMISSION.md](LSPOSED_SUBMISSION.md)（首次提交规范）。

## 原理

LSPosed 模块仓库（modules.lsposed.org）**不通过任何 API 手动推送**：
bot 监听本 GitHub 仓库的 release 变化，**编辑 release 内容（body）即触发重新同步**。
因此"推送 LSP" = 确保 release 资产正确 + 编辑一次 release 说明。

## 关键约定

| 项 | 值 |
|---|---|
| 仓库 | `aodianjun/com.aodianjun.hyperglow.cnplus`（下称 `$REPO`） |
| Release tag 格式 | `{versionCode}-{versionName}`，如 `109-0.3.82`（LSPosed 规范） |
| 版本号来源 | `app/build.gradle.kts` 的 `versionCode` / `versionName` |
| CI | `.github/workflows/android.yml`，push 到 `main` 即触发 |

## 流程

### 1. 确认 CI 绿

```bash
gh run list --repo $REPO --limit 3
```

最新一次 push 到 main 的 `Verify HyperGlow` 必须 success
（含 `build` ×2 变体 + `release` job；release job 在 `environment: release` 下，
配置了签名 secrets 时出正式签名包）。

### 2. 确认 release 存在且资产新鲜

```bash
gh release view {VC}-{VN} --repo $REPO \
  --json assets,isPrerelease --jq '{isPrerelease, assets: [.assets[] | {name, updatedAt}]}'
```

- 资产应有 2 个：`hyperglow-cnplus-release-v{VN}-{VC}.apk`（正式）+
  `hyperglow-cnplus-debug-v{VN}-{VC}.apk`（调试）。
- **注意**：release job 每次 push main 都会以当前代码重建并 `--clobber` 覆盖同 tag 资产
  （版本号未 bump 时）。`updatedAt` 必须晚于最后一次代码提交，否则说明资产是旧构建。
- release 不存在 → 说明版本号还没 bump 或 CI 没跑到 release job，先解决再继续。

### 3. 补全/核对 release 说明

格式遵循 [RELEASE_CONVENTIONS.md](RELEASE_CONVENTIONS.md)。要点：

- 保留 CI 自动写入的"安装包用途说明"块（正式版/调试版各自用途）。
- 更新日志按 新增功能 / 修复 / 优化 分类，中文书写。
- 末尾 `Full Changelog` 链接指向上一版本 tag。
- 后续向同版本追加了代码（如 hotfix、上游移植），要在更新日志中补充说明段落，
  不能只改资产不动 body——bot 只认 body 变化。

操作：拉取现有 body → 编辑 → 写回。

```bash
gh release view {VC}-{VN} --repo $REPO --json body --jq '.body' > body.md
# 编辑 body.md
gh release edit {VC}-{VN} --repo $REPO \
  --title "HyperGlow CN+ {VC}-{VN}" --notes-file body.md
```

**上一步 `gh release edit` 成功即视为已推送 LSP**（bot 异步同步，无需其他操作）。

### 4. 验证（可选，bot 有延迟）

模块页：https://modules.lsposed.org/module/com.aodianjun.hyperglow.cnplus
确认版本号变为 `{VN} ({VC})`。若长时间未更新，检查 release body 是否真的发生了变化。

## 常见问题

- **token**：本地沙箱先 `gh auth setup-git` 再 push，否则 `could not read Username`。
- **CI 失败**：常见为 `UiStringsContractTest`——新增 string 没同步到
  `app/translation/strings-template.xml`（values / values-zh-rCN / values-zh-rTW / template
  四处都要有）。
- **发布新版本**：bump `versionCode`/`versionName` → push main → CI 自动创建新 tag 的
  release 并上传资产 → 回到本流程第 3 步补说明。
- **临时文件**：编辑 body 用的 `body.md` 用完删除，勿提交入库。
