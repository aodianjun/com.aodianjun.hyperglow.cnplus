# HyperGlow 配置预设匿名 Hub

一个 Cloudflare Worker：给 App 提供**不需要 GitHub Token** 的匿名上传接口，Worker 在服务端持有
GitHub Token，校验后直接更新仓库的 `presets` Release 资产。读取仍然走公开 Release，不需要签名。

## 部署

```bash
cd tools/preset-hub-worker
npm install -g wrangler        # 只需一次；或 npx wrangler
wrangler login                 # 登录你的 Cloudflare 账号
```

1. 建一个 fine-grained GitHub Token：仓库 `aodianjun/com.aodianjun.hyperglow.cnplus`，
   权限 **Contents: Read and write**。
2. 把 Token 放进 Worker secret（不要写进 wrangler.toml）：
   ```bash
   wrangler secret put GITHUB_TOKEN
   ```
3. 可选：加盐 + 限流 + 失败恢复
   ```bash
   wrangler secret put OWNER_SALT
   wrangler kv namespace create RATE_LIMIT
   # 把输出的 id 填到 wrangler.toml 的 [[kv_namespaces]]
   ```
   绑定 KV 后：每 IP 每小时 10 次限流；删除旧资产前会写一份 `manifest_backup`，
   若上传新资产失败，下一次上传会从备份恢复整份清单。不绑 KV 也能用，但没有限流/恢复。
4. 部署：
   ```bash
   wrangler deploy
   ```
   得到 `https://hyperglow-preset-hub.<your-subdomain>.workers.dev`。

## 接入 App

设置 → 配置预设 → 云端「设置」：

- 仓库/标签保持默认，或改成你自己的；
- **匿名上传地址** 填 Worker 根地址（不带 `/api/presets`）；
- 本机不再需要 GitHub Token。

之后本地预设详情点「上传到云端」，选择要公开的分节即可。Worker 会自动创建/更新
`presets` 预发布 Release 与 `presets.json` 资产。

## API

### `POST /api/presets`

请求头：

- `Content-Type: application/json`
- `X-HyperGlow-Device: <128 字符以内的设备标识>`（App 自动生成并持久化）

请求体：

```json
{
  "id": "本机 UUID（可选；缺省由 Worker 生成）",
  "name": "预设名（≤20）",
  "note": "备注（≤60，可空）",
  "author": "署名（≤20，可空）",
  "payload": {
    "format": "hyperglow-config-backup",
    "version": 1,
    "customization": { "...": "..." }
  }
}
```

`payload` 只允许以下分节（未知键/`pluginSettings` 直接 400）：

`customization`、`renderPreferences`、`appUiAppearance`、`lyricSource`、`uiLanguage`、
`appNavigation`、`diagnostics`。

成功：`200 {"ok":true,"id":"...","updatedAt":169...}`。

失败：

| HTTP | error | 含义 |
| --- | --- | --- |
| 400 | `invalid_json` / `invalid_preset` / `missing_device` | 请求不合法 |
| 409 | `id_taken` | 同 id 条目属于另一台设备（或由 Token 上传），不能覆盖 |
| 413 | `too_large` / `manifest_too_large` | 超过单条 256 KiB / 清单 8 MiB |
| 429 | `rate_limited` | 每 IP 每小时 10 次（需绑定 KV） |
| 500 | `server_not_configured` | 没配 `GITHUB_TOKEN` |
| 502 | `github_*` / `upload_*` / `asset_*` | GitHub 侧失败 |

### `GET /api/presets`

直接返回当前的 `presets.json` 清单（公开，CORS 允许所有来源）。

### `GET /health`

返回仓库/标签与接口说明，不含任何 Token。

## 安全设计

- 分节白名单 + 固定 backup format/version，拒绝插件设置（可能含 API Key）。
- 单条 256 KiB、清单 8 MiB、最多 100 条。
- 匿名归属：`SHA-256(设备标识 + OWNER_SALT)` 写进条目的 `owner`；只有同一设备能覆盖自己的条目，
  不能凭 id 劫持别人。
- 下载资产 302 到签名 CDN 时**不带** GitHub Token。
- 服务端 Token 只在 Worker secret 中，日志/响应都不包含。
- 依赖 GitHub Release 本身的公开性；仓库必须是 public，否则 App 匿名读不到。

## 自测

无网络冒烟测试（mock GitHub API，覆盖首次上传/同设备更新/跨设备 409/插件拒绝/列表/限流/CORS）：

```bash
cd tools/preset-hub-worker
node test.mjs
```

线上接口：

```bash
curl -s https://<worker>/health

curl -s -X POST https://<worker>/api/presets \
  -H "Content-Type: application/json" \
  -H "X-HyperGlow-Device: test-device-0001" \
  -d '{"id":"test-preset-1","name":"Test","note":"","author":"tester",
       "payload":{"format":"hyperglow-config-backup","version":1,
       "customization":{"id":"doc"}}}'

curl -s https://<worker>/api/presets | head -c 400
```
