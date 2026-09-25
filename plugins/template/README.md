# HyperGlow 插件模板 / HyperGlow Plugin Template

# 中文 / Chinese

状态：插件开发脚手架（不参与宿主构建，复制后使用）

这是一个**可直接编译**的最小插件骨架：一个默认直通（不改任何内容）的歌词处理器 +
一份合法的最小 manifest + 与 demo 相同的 d8 打包管线。`plugins/demo` 是完整 API 用法
（PluginCache、多设置项、变更协议）的参考实现，本模板是起步点。

## 从模板到你的插件

1. **复制目录**：把 `plugins/template` 整个复制为 `plugins/<你的名字>`。
2. **同步改名**（以下四处必须一致）：
   - `build.gradle.kts`：无模块名硬编码（任务名已中性化），一般不用改；
   - 源码包名：`src/main/kotlin/com/example/hyperglow/template/` → 你的包；
   - `manifest.json` 的 `id`（全局唯一，如 `com.yourname.hyperglow.<plugin>`）与
     `entry`（入口类全名）；
   - `TemplatePlugin.kt` 类名与 `entry` 保持一致。
3. **加入构建**：在根 `settings.gradle.kts` 增加 `include(":plugins:<你的名字>")`。
4. **打包**：`./gradlew :plugins:<你的名字>:pluginZip` →
   `build/distributions/hyperglow-<你的名字>-plugin.zip`（manifest.json + classes.dex）。
5. **安装**：HyperGlow 设置 → 插件管理 → 从本地 ZIP 安装。

## 契约要点（详见 docs/ARCHITECTURE.md 的 Plugin Runtime Boundary）

- `manifest.json` 只做**结构性校验**（id 格式、apiVersion ≤ 宿主、entry、settings schema）；
  无发布者签名、无运行时 API 允许名单——插件是用户自装的不可信输入，请勿要求或暗示更高权限。
- 插件跑在 **app 进程**，绝不进 SystemUI；API 类来自 `:plugins:api`（compileOnly），
  其字节级契约由 CI 指纹检查保护（`plugins/api/check-api-fingerprint.py`），不要复制改名。
- 设置项（settings）由宿主设置页渲染并经 `PluginConfig` 读回；`activationSettingKey`
  指向的开关即插件的启用开关。

## 打包清单（ZIP 内容）

- `manifest.json`（来自 `src/main/plugin/`）
- `classes.dex`（jar 经 build-tools d8 转换；kotlin-stdlib 由宿主提供，不进 dex）

---

# English / English

Status: plugin development scaffold (not part of the host build; copy before use)

This is a **compilable** minimal plugin skeleton: a pass-through lyric processor + a minimal
valid manifest + the same d8 packaging pipeline as the demo. `plugins/demo` shows the full API
surface (PluginCache, multiple settings, the change protocol); this template is the starting point.

## From template to your plugin

1. **Copy** the whole `plugins/template` directory to `plugins/<yourname>`.
2. **Rename consistently** (all four places):
   - `build.gradle.kts`: no hardcoded module name (task names are neutral); usually no change;
   - source package: `src/main/kotlin/com/example/hyperglow/template/` → your package;
   - `manifest.json` `id` (globally unique, e.g. `com.yourname.hyperglow.<plugin>`) and
     `entry` (entry class FQN);
   - the `TemplatePlugin` class name must match `entry`.
3. **Include in the build**: add `include(":plugins:<yourname>")` to the root `settings.gradle.kts`.
4. **Package**: `./gradlew :plugins:<yourname>:pluginZip` →
   `build/distributions/hyperglow-<yourname>-plugin.zip` (manifest.json + classes.dex).
5. **Install**: HyperGlow settings → Plugin management → install from local ZIP.

## Contract notes (see the Plugin Runtime Boundary section of docs/ARCHITECTURE.md)

- `manifest.json` undergoes **structural validation only** (id format, apiVersion ≤ host, entry,
  settings schema); there is no publisher signature and no runtime API allowlist — a plugin is
  user-installed untrusted input. Do not request or imply higher privileges.
- Plugins run in the **app process**, never in SystemUI; API classes come from `:plugins:api`
  (compileOnly), and their byte-level contract is guarded by the CI fingerprint check
  (`plugins/api/check-api-fingerprint.py`) — never copy or rename it.
- Settings entries are rendered by the host settings screen and read back via `PluginConfig`;
  the switch pointed to by `activationSettingKey` is the plugin's enable switch.

## ZIP contents

- `manifest.json` (from `src/main/plugin/`)
- `classes.dex` (jar converted with build-tools d8; kotlin-stdlib is provided by the host and
  never packaged into the dex)
