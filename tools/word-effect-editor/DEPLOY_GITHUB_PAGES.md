# 部署到 GitHub Pages

Word Effect Studio 是纯静态页面（`index.html` + `engine.js` + `templates.js` + `app.js`），
没有构建步骤、没有后端、没有外部网络请求，GitHub Pages 可以直接托管。

## 方案 A：单独一个仓库（推荐）

1. 在 GitHub 新建一个 public 仓库，例如 `word-effect-studio`。
2. 把四个文件放到仓库根目录：`index.html`、`engine.js`、`templates.js`、`app.js`。
   可以在本地生成一份干净目录再上传：

   ```bash
   python tools/word-effect-editor/make_site.py --out build/word-effect-studio
   ```

   输出目录会被根目录 `.gitignore` 的 `**/build/` 忽略，适合临时打包上传。
3. 仓库 **Settings → Pages → Build and deployment**：
   - Source：**Deploy from a branch**
   - Branch：`main` / `(root)`
4. 等一分钟，访问 `https://<用户名>.github.io/<仓库名>/`。

## 方案 B：现有仓库 + GitHub Actions

仓库带了 `.github/workflows/pages-word-effect-editor.yml`：它只把 `tools/word-effect-editor`
里的四个站点文件暂存到 `_site` 再发布，不会把测试和 README 传上去。

1. 仓库 **Settings → Pages → Source** 选 **GitHub Actions**。
2. 推送到 `main`，或在 Actions 页手动运行 `Deploy Word Effect Studio to GitHub Pages`。
3. 访问 `https://<用户名>.github.io/<仓库名>/`（站点在仓库根，不是 `/tools/...`）。

> 本仓库当前以 Gitee 为主远端，GitHub Actions 只在 GitHub 仓库上运行。要在 GitHub 上跑，
> 需要有对应的 GitHub 仓库或镜像，并把工作流推过去；本机现有 GitHub token 没有 workflow
> 权限时，改 `.github/workflows/` 的推送会被 GitHub 以 404 拒绝，建议在 GitHub 网页上传该文件
> 或用有 workflow scope 的 token。

## 注意

- `localStorage` 按 origin 隔离：从 localhost 打开时保存的包不会自动带到 Pages 上，在 Pages 上
  重新导入即可。
- 页面是公开的；导入的包只存在浏览器本地，不会上传到 GitHub。
- `test_engine.js` / `README.md` 不是运行所需的文件，只是开发工具。
- 只上传四个文件时，README 里的 `../../docs/WORD_EFFECT_PACKS.md` 链接在 Pages 上不可用；
  编辑器本身不依赖它。
- 四个文件的引用全是相对路径，放在仓库根或任意子路径都能工作；自定义域名同理。
