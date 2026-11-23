# HyperGlow 配置预设 Hub · EdgeOne 备用入口

HyperIsland-Hub 的 EdgeOne 后台结构（`cloud-functions/` + Node.js 运行时）可以参考，但它存的是
HyperIsland 的配置格式、用的是 EdgeOne Blob。这里不复用它的数据层，只复用 EdgeOne Pages 的
函数形态，做一个和 Cloudflare Worker **同协议**的 HyperGlow 备用入口：

- `GET /api/presets`：读 GitHub Release 里的 `presets.json`（公开，不需要 Token）
- `POST /api/presets`：匿名上传/更新，函数用服务端 `GITHUB_TOKEN` 写同一个 Release
- `POST /api/presets/delete`：匿名删除自己上传的条目；设备 `owner` 哈希必须匹配
- `GET /api/health`：自检，返回仓库/标签与 `tokenConfigured` 布尔值

两个入口写同一份 GitHub Release，所以 App 不需要在两套数据之间同步；Cloudflare 不可用时把
App 的匿名上传地址切到 EdgeOne（或填成「备用地址」，App 会自动回退）。

## 部署

1. 把本仓库推到 GitHub。
2. EdgeOne 控制台 → **Pages** → 新建项目 → 关联该仓库。
3. 项目配置：

   | 项 | 值 |
   | --- | --- |
   | 根目录 | `tools/preset-hub-edgeone` |
   | 构建命令 | 留空 |
   | 输出目录 | 留空 |

4. 项目设置 → 环境变量：

   - `GITHUB_TOKEN`（Secret）：GitHub fine-grained PAT，仓库 `Contents: Read and write`
   - `GITHUB_REPO`：`aodianjun/com.aodianjun.hyperglow.cnplus`
   - `GITHUB_TAG`：`presets`
   - `OWNER_SALT`：随机长串，**必须与 Cloudflare Worker 的 `OWNER_SALT` 相同**，否则同一条预设
     在两个入口的 `owner` 哈希对不上，会被判成 `id_taken`

   改完环境变量要**重新部署一次**才生效。

5. 访问 `/api/health`：

   ```json
   { "ok": true, "repo": "aodianjun/com.aodianjun.hyperglow.cnplus",
     "tag": "presets", "tokenConfigured": true }
   ```

6. 访问 `/api/presets`，应返回当前的云端清单（或空清单）。
   如果 `tokenConfigured=true` 但 GET 返回 502，说明 EdgeOne 函数到 `api.github.com` 的出网被限制，
   需要在项目/套餐里放开外部请求，或换一个允许出网的边缘产品。

7. 在 EdgeOne 控制台给项目配置 **按 IP 速率限制 / CC 防护**。函数本身只做协议校验和 owner 归属，
   不做全局限流。

## 接到 App

HyperGlow → 配置预设 → 云端「设置」：

- **匿名上传地址**：继续填 Cloudflare 的 `https://presets.aodianjun.cn`
- **备用匿名上传地址**：填 EdgeOne 项目的默认域名（或绑定到该项目的自定义域名），
  只填根地址，例如 `https://hyperglow-preset-hub.<edgeone-domain>`
- 仓库/标签保持默认，GitHub Token 留空

App 上传时会先走主地址；主地址网络不可用时自动尝试备用地址。两个入口写同一个 Release，
所以云端列表不会分叉。

## 本地联调

```bash
cd tools/preset-hub-edgeone
npm install -g edgeone
edgeone pages link
edgeone pages dev
```

`@edgeone/pages-blob` 不是必需依赖：这个版本用 GitHub Release 存清单，不需要 Blob 命名空间。
