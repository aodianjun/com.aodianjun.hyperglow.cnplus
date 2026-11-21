# HyperGlow CN+ 变更文档

- **变更主题**：首次启动引导、全局搜索、引导页真实设置、Root 管理器跳转、覆盖层可读性修复
- **应用版本**：0.4.0（versionCode 300）
- **包名**：`com.aodianjun.hyperglow.cnplus`
- **日期**：2026-11-21
- **分支**：`trae/agent-NHVrHQ`
- **改动规模**：16 个文件，+1756 / -59（相对上游主线 `4c171f4`）

---

## 1. 改动总览

| # | 模块 | 改动说明 | 主要文件 |
|---|------|----------|----------|
| 1 | 首次启动引导 | 新增 4 页全屏引导覆盖层，首次安装自动弹出 | `OnboardingScreen.kt`、`MainActivity.kt` |
| 2 | 引导入口 | 设置 → 关于 新增「使用引导」，可随时重看 | `HomeScreen.kt` |
| 3 | 引导页真实设置 | 第 2 页可直接开关息屏/锁屏歌词、重启系统界面 | `OnboardingScreen.kt` |
| 4 | 引导页歌词源 | 第 3 页复用主页面歌词源组件（连接状态 / 下载地址 / 授权 / 选源） | `OnboardingScreen.kt`、`LyricSourceComponents.kt` |
| 5 | 引导页权限 | 第 4 页复用主页面权限检查组件 | `OnboardingScreen.kt`、`PermissionStatusSection.kt` |
| 6 | 全局搜索 | 状态 / 设置 / App 设置三页顶栏搜索入口 + 全屏搜索覆盖层 | `GlobalSearch.kt`、`HomeScreen.kt` |
| 7 | 模糊搜索与补全 | 子串、跳字、英文编辑距离容错 + 输入补全胶囊 | `GlobalSearch.kt` |
| 8 | 搜索定位 | 搜索结果显示所属页面/分组，选中后切页并滚动到对应位置 | `HomeScreen.kt`、`LyricAppearanceSection.kt` |
| 9 | 条件条目过滤 | 实验模式相关条目按可用性过滤，避免搜到却找不到按钮 | `GlobalSearch.kt`、`HomeScreen.kt` |
| 10 | Root 管理器跳转 | 权限区新增「打开 Root 管理器」，自动匹配 Magisk / KernelSU / APatch | `PermissionStatusSection.kt` |
| 11 | 覆盖层可读性 | 引导页与搜索页改用不透明底色，修复透字看不清 | `AppUiAppearance.kt`、`AppTopBar.kt` |
| 12 | 测试与构建 | 新增搜索与引导偏好测试；开启 Robolectric 资源加载 | `GlobalSearchTest.kt`、`OnboardingPreferencesTest.kt`、`app/build.gradle.kts` |

---

## 2. 首次启动引导

### 2.1 触发条件

新增独立偏好 `hyperglow_onboarding`（键 `completed`），不参与配置导入/导出。

满足以下**全部条件**时自动显示引导：

1. 未写入完成标记；
2. 未检测到任何既有用户配置：`aod_render`、`app_ui_appearance`、`app_navigation` 三个偏好文件均为空。

> `surface_customization`（启动时自动迁移落盘）与 `xiaomi_capabilities`（SystemUI 上报）不参与判断，
> 避免升级安装的老用户被误判为新用户。

对应实现：`OnboardingPreferences.shouldShow / markCompleted`（`OnboardingScreen.kt`）。

### 2.2 界面结构

- 全屏覆盖层，盖在主页面之上，不进入导航栈。
- `Scaffold` 使用不透明覆盖层底色 `appOverlaySurfaceColor()`。
- 顶栏使用 `AppTopBar(overlay = true)`，不做壁纸模糊。
- 顶部：非最后一页显示「跳过」。
- 底部：圆点页码 + 「下一步」；最后一页为「开始使用」。
- 页面内容不满一屏时垂直居中，超出时可整页滚动（适配小屏 / 大字号）。
- 手势返回：`PredictiveBackHandler` 优先关闭引导，不触发底层页面返回。

### 2.3 四个页面

| 页 | 标题 | 内容 |
|----|------|------|
| 1 | 欢迎使用 HyperGlow CN+ | 功能简介 + 开始前的要求清单：Root 权限、LSPosed 并勾选系统界面作用域、至少一个歌词源 |
| 2 | 启用模块 | 实时模块状态（已生效 / 尚未检测到）+ **真实开关** + 打开 LSPosed + 重启系统界面 |
| 3 | 选择歌词源 | 与主页面同源的歌词源卡片 + 未连接引导 + 选源对话框 |
| 4 | 检查权限 | 与主页面同源的权限状态组件 |

### 2.4 第 2 页：真实写入设置

不再只是展示功能，而是直接操作：

- **在息屏显示 / 在锁屏显示** 两个 `SwitchPreference`
  - 通过 `updateCustomizationSurfaceEnabled(context, surface, enabled)` 写入外观文档，与设置页同一套逻辑；
  - 不支持当前系统版本时在 summary 中说明，但开关仍可操作，模块生效后配置自动应用。
- **打开 LSPosed**：复用 `openLsposedManager(context)`。
- **重启系统界面**：`ShellUtils.restartHookedProcesses(systemUi = true, miuAod = false, hyperglowApp = false)`，成功/失败均有 Toast 提示。
- 模块状态监听 `XiaomiCapabilityStore.PREFS`，SystemUI 上报后无需退出引导即可刷新。

### 2.5 第 3 页：歌词源（与主页面同源）

直接复用主页面组件，而不是引导页单独实现一套：

- `LyricSourceSection(onOpenSourceDialog = ...)`：显示当前歌词源与真实连接状态；
- `SourceSetupHint()`：未连接时提供歌词源下载地址、通知使用权授权入口；
- `LyricSourcePickerDialog(...)`：点选后立即生效。

因此主页面后续对歌词源交互的任何修改都会自动同步到引导页。

### 2.6 第 4 页：权限检查（与主页面同源）

- 使用 `SettingsCard { PermissionStatusSection() }`，与状态页完全一致；
- 覆盖通知权限、歌词前台服务、电池优化、Root 权限；
- Root 授权同时支持系统授权弹窗与跳转 Root 管理器手动授权；
- 删除了引导页原先自写的权限行、ActivityResult launcher 与对应 import。

### 2.7 入口与返回行为

- **设置 → 关于 → 使用引导**：新增 `ArrowPreference`，点击重新打开引导覆盖层。
- 从设置重新打开时顶栏显示返回按钮；返回只关闭引导，不写入完成标记。
- 首次启动没有返回按钮，只能「跳过」或「开始使用」；两者都会写入完成标记。

### 2.8 偏好与测试

`OnboardingPreferencesTest`（Robolectric）覆盖：

| 用例 | 断言 |
|------|------|
| `freshInstallShowsOnboarding` | 全新安装显示引导 |
| `completedFlagHidesOnboarding` | 写入完成标记后不再显示 |
| `existingUserConfigurationHidesOnboarding` | 已有任意用户配置时不显示（视为老用户） |

---

## 3. 全局搜索

### 3.1 入口

- 位置：顶栏搜索图标，出现在**状态 / 设置 / App 设置**三页。
- 关于页不出现在顶栏图标，也不参与搜索索引。
- 点击后打开全屏搜索覆盖层，输入框自动聚焦、弹出键盘。

### 3.2 界面结构

- `Scaffold` 使用 `appOverlaySurfaceColor()`，`AppTopBar(overlay = true)`，返回键关闭。
- 顶部搜索框（`TextField`，`ImeAction.Search`）。
- 有输入且存在候选时，搜索框下方显示横向滚动补全胶囊。
- 结果以 `SettingsCard` + `ArrowPreference` 列表展示，每条显示：
  - 条目名称；
  - 所属范围：「页面 · 分组」，例如「状态 · 歌词源」。

### 3.3 索引范围（45 条）

#### 状态页（13 条）

| 条目 | 定位锚点 |
|------|----------|
| 模块状态 | `STATUS_HOME` |
| 实时状态 | `STATUS_LIVE` |
| 歌词源 | `STATUS_SOURCE` |
| 当前歌词源 | `STATUS_SOURCE` |
| 过滤非音乐来源 | `STATUS_SOURCE` |
| 隐藏制作信息 | `STATUS_SOURCE` |
| 插件管理 | `STATUS_SOURCE` |
| 报告问题 | `STATUS_RUNTIME` |
| 通知权限 | `STATUS_PERMISSION` |
| 电池优化 | `STATUS_PERMISSION` |
| Root 权限 | `STATUS_PERMISSION` |
| 歌词前台服务 | `STATUS_PERMISSION` |
| 打开 Root 管理器 | `STATUS_PERMISSION` |

#### 设置 · 其他设置（6 条）

| 条目 | 定位锚点 |
|------|----------|
| 歌词增强 | `SETTINGS_ENHANCEMENT` |
| 暂停时显示内容 | `SETTINGS_PLAYBACK` |
| Spotify 暂停后 | `SETTINGS_PLAYBACK` |
| 导出配置 | `SETTINGS_BACKUP` |
| 导入配置 | `SETTINGS_BACKUP` |
| 重置默认 | `SETTINGS_BACKUP` |

#### 歌词外观编辑器（10 条）

| 条目 | 定位锚点 | 分段 |
|------|----------|------|
| 在息屏显示 | `APPEARANCE_HEADER` | 息屏 |
| 在锁屏显示 | `APPEARANCE_HEADER` | 锁屏 |
| 位置 | `APPEARANCE_PLACEMENT` | 息屏 |
| 文字和语言 | `APPEARANCE_TEXT_LANGUAGE` | 息屏 |
| 效果 | `APPEARANCE_EFFECTS` | 息屏 |
| 音乐频谱 | `APPEARANCE_SPECTRUM` | 息屏 |
| 自定义贴纸 | `APPEARANCE_SPRITES` | 息屏 |
| 颜色 | `APPEARANCE_COLORS` | 息屏 |
| 锁屏歌词卡片 | `APPEARANCE_LOCKSCREEN_CARD` | 锁屏 |
| 两个显示区域 | `APPEARANCE_BOTH_SURFACES` | 息屏 |

#### App 设置（16 条）

| 条目 | 定位锚点 |
|------|----------|
| 语言 | `APP_LANGUAGE` |
| 界面语言 | `APP_LANGUAGE` |
| 预测性返回 | `APP_NAVIGATION` |
| 返回触发阈值 | `APP_NAVIGATION` |
| 应用外观 | `APP_APPEARANCE` |
| 实验模式（条件显示） | `APP_DEVELOPER` |
| 强制歌词提供者（条件显示） | `APP_DEVELOPER` |
| 息屏调试画布边框 | `APP_DEVELOPER` |
| 诊断日志 | `APP_DEVELOPER` |
| 日志保留时长 | `APP_DEVELOPER` |
| 日志级别 | `APP_DEVELOPER` |
| 导出日志 | `APP_DEVELOPER` |
| 清空日志 | `APP_DEVELOPER` |
| 常驻前台通知 | `APP_SYSTEM` |
| 隐藏后台卡片 | `APP_SYSTEM` |
| 隐藏桌面图标 | `APP_SYSTEM` |

### 3.4 模糊匹配规则

每条索引参与匹配的字段：标题、说明、分组名、页面名、英文/中文关键词。

查询按空格分词，**每个词都必须命中**；单条结果得分为各词得分之和，按总分降序排列。

单个词的匹配分档：

| 优先级 | 规则 | 得分 |
|--------|------|------|
| 1 | 标题包含连续子串 | 1200 − 位置 |
| 2 | 说明/分组/关键词包含连续子串 | 900 − 位置 |
| 3 | 标题按顺序包含查询字符（跳字） | 560 |
| 4 | 全字段按顺序包含查询字符（跳字） | 420 |
| 5 | 英文单词编辑距离 ≤ 1（长度 ≥ 3） | 300 |

示例：

- 「歌源」可以命中「歌词来源」（跳字）；
- 「sorce」可以命中 `source`（英文编辑距离 1）；
- 「歌词 来源」两个词都命中才返回。

### 3.5 输入补全

- 从索引标题中筛选包含当前输入的候选；
- 排除与输入完全相同的标题，按首次出现位置排序，去重后最多 6 条；
- 点击胶囊即把输入替换为完整标题，同时保留继续编辑与搜索的能力。

### 3.6 条件显示条目

「实验模式」「强制歌词提供者」只在实验模式可用时渲染于界面。

搜索索引为这类条目增加 `requiresExperimental` 标记，并在过滤与补全时接收 `experimentalEligible`：

- 实验模式不可用：条目从结果与补全中完全移除，避免「搜到但页面没有这个按钮」；
- 实验模式可用：正常参与搜索与定位。

`experimentalEligible` 与设置页显示实验开关的条件一致
（`EXPERIMENTAL_ELIGIBLE` 或 `UNSUPPORTED_PROFILE`）。

### 3.7 定位与滚动机制

1. 各页 `LazyColumn` 组合时按顺序登记 `anchor → item index`
   （`HomeScreen.kt` 的 `tracked(...)`、`LyricAppearanceSection.kt` 的 `trackedItem(...)`）。
2. 选中搜索结果后：
   - 关闭搜索覆盖层；
   - 若目标在「设置」页，先切换顶部分段：外观条目按 `appearanceSection` 切到息屏/锁屏，其余切到「其他设置」；
   - 切换到目标 Tab（`pagerState.animateScrollToPage`）。
3. 等待目标列表组合并登记锚点（最多 3 秒），随后 `scrollToItem(index)`。
4. 外观编辑器使用独立的 `appearanceListState`，搜索可直接滚到编辑器内部的具体分组。

### 3.8 测试

`GlobalSearchTest`（Robolectric，`sdk = 36`，`zh-rCN`）：

| 用例 | 覆盖点 |
|------|--------|
| `exactTitleStillMatches` | 精确标题命中 |
| `subsequenceFuzzyMatchFindsSkippedCharacters` | 跳字模糊匹配（歌源 → 歌词来源） |
| `multiTokenQueryRequiresEveryToken` | 多词 AND 语义 |
| `typoInEnglishKeywordStillMatches` | 英文拼写容错（sorce → source） |
| `completionsReturnTitlesContainingInput` | 输入补全 |
| `experimentalEntriesAreHiddenWhenIneligible` | 条件条目按实验模式过滤 |
| `blankQueryReturnsNothing` | 空查询不返回结果/补全 |

---

## 4. Root 权限支持跳转 Root 管理器

位置：状态页「权限状态」卡片，Root 权限行之后。

- 新增「打开 Root 管理器」行：
  - 标题：「打开 Root 管理器」；
  - 说明：「在 Magisk / KernelSU / APatch 中为 HyperGlow 授权」。
- 依次尝试以下包名的启动 Intent：

| 管理器 | 包名 |
|--------|------|
| Magisk | `com.topjohnwu.magisk` |
| KernelSU | `me.weishu.kernelsu` |
| KernelSU Next | `com.rifsxd.ksunext` |
| APatch | `me.bmax.apatch` |

- 使用 `PackageManager.getLaunchIntentForPackage` 获取带 LAUNCHER category 的完整启动 Intent；
- 找到并成功启动即返回；
- 一个都没找到时 Toast 提示「未找到可用的 Root 管理器」。

引导页第 4 页复用同一权限组件，因此也自动具备该入口。

---

## 5. 覆盖层可读性修复

**问题**：引导页与全局搜索是覆盖在其它页面之上的全屏层，之前沿用普通页面底色。
启用背景图时普通页面底色带透明度，导致下层页面文字/卡片透上来，出现叠字、看不清。

**修复**：

- `AppUiAppearance.kt` 新增 `appOverlaySurfaceColor()`：恒定返回 `MiuixTheme.colorScheme.surface`，不做透明度处理。
- `AppTopBar.kt` 新增 `overlay` 参数：
  - `overlay = true` 时跳过壁纸模糊（避免透出下层内容）；
  - 底色使用不透明的 `appOverlaySurfaceColor()`。
- 引导页与全局搜索页的 `Scaffold` 使用 `appOverlaySurfaceColor()`，顶栏传 `overlay = true`。

---

## 6. 字符串与本地化

新增 33 条字符串，同步维护 4 份文件：

- `app/src/main/res/values/strings.xml`
- `app/src/main/res/values-zh-rCN/strings.xml`
- `app/src/main/res/values-zh-rTW/strings.xml`
- `app/translation/strings-template.xml`

| 分组 | 字符串 |
|------|--------|
| 引导入口 | `title_onboarding`、`summary_onboarding` |
| 引导流程 | `onboarding_skip`、`onboarding_next`、`onboarding_start`、`onboarding_welcome_title`、`onboarding_welcome_summary` |
| 开始前要求 | `onboarding_requirements_title`、`onboarding_requirement_root`、`onboarding_requirement_lsposed`、`onboarding_requirement_source` |
| 启用模块 | `onboarding_module_title`、`onboarding_module_summary`、`onboarding_module_status`、`onboarding_module_detected`、`onboarding_module_pending`、`onboarding_module_restart_hint`、`onboarding_action_open_lsposed`、`onboarding_action_restart_systemui`、`onboarding_display_title` |
| 歌词源 | `onboarding_source_title`、`onboarding_source_summary`、`onboarding_source_permission_hint` |
| 权限 | `onboarding_permission_title`、`onboarding_permission_summary`、`onboarding_action_grant_root` |
| Root 管理器 | `action_open_root_manager`、`summary_open_root_manager`、`toast_root_manager_not_found` |
| 全局搜索 | `search_title`、`search_placeholder`、`search_empty`、`search_scope` |

中文示例：

- 「打开 Root 管理器」/「在 Magisk / KernelSU / APatch 中为 HyperGlow 授权」/「未找到可用的 Root 管理器」
- 「搜索」/「搜索设置」/「没有找到相关设置」/「%1$s · %2$s」
- 「使用引导」/「重新查看安装与上手步骤」

---

## 7. 验证记录

### 7.1 单元测试

```
./gradlew :app:testDebugUnitTest --console=plain
```

结果：`BUILD SUCCESSFUL`

覆盖：

- `GlobalSearchTest`：7 个用例全部通过；
- `OnboardingPreferencesTest`：3 个用例全部通过；
- 既有 `UiStringsContractTest` 通过。

### 7.2 构建

```
./gradlew :app:assembleDebug --console=plain
```

结果：`BUILD SUCCESSFUL in 44s`

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 52 MB，Debug 签名）

### 7.3 部署

| 项 | 值 |
|----|----|
| 站点 | `https://37jf66nh10g1fv1rs64.traesite.cn` |
| 可见性 | 私有 |
| 部署类型 | Node.js 20 静态文件服务（FunctionWeb） |
| 下载文件 | `HyperGlow-CNPlus-0.4.0-debug.apk` |
| 部署 ID | `dep_6af6a7a496c416ccc7fbeb15` |
| 状态 | 成功（100%） |

> APK 超过 50 MiB，无法使用普通静态托管，因此通过 `prepare-static-function.mjs` 转为
> Node.js 20 静态服务器部署。

---

## 8. 文件清单

### 8.1 新增文件

| 文件 | 说明 |
|------|------|
| `app/src/main/java/com/eza/hyperglow/ui/OnboardingScreen.kt` | 首次启动引导的完整实现（4 页、偏好、页脚） |
| `app/src/main/java/com/eza/hyperglow/ui/GlobalSearch.kt` | 全局搜索索引、模糊匹配、补全、搜索覆盖层 |
| `app/src/test/java/com/eza/hyperglow/ui/OnboardingPreferencesTest.kt` | 引导显示条件测试 |
| `app/src/test/java/com/eza/hyperglow/ui/GlobalSearchTest.kt` | 搜索匹配/补全/条件过滤测试 |

### 8.2 修改文件

| 文件 | 说明 |
|------|------|
| `app/src/main/java/com/eza/hyperglow/ui/HomeScreen.kt` | 搜索入口与覆盖层、锚点登记与滚动、关于页引导入口 |
| `app/src/main/java/com/eza/hyperglow/ui/MainActivity.kt` | 引导覆盖层挂载、返回处理、关于页回调 |
| `app/src/main/java/com/eza/hyperglow/ui/PermissionStatusSection.kt` | Root 管理器跳转 |
| `app/src/main/java/com/eza/hyperglow/ui/LyricAppearanceSection.kt` | 外观编辑器列表项锚点登记与外部 `LazyListState` |
| `app/src/main/java/com/eza/hyperglow/ui/LyricSourceComponents.kt` | `openLsposedManager` / `lyricSourceLabel` 改为 `internal` 供引导页复用 |
| `app/src/main/java/com/eza/hyperglow/ui/AppUiAppearance.kt` | 新增不透明覆盖层底色 |
| `app/src/main/java/com/eza/hyperglow/ui/AppTopBar.kt` | 新增 `overlay` 模式 |
| `app/build.gradle.kts` | 单元测试加载 Android 资源 |
| `app/src/main/res/values/strings.xml` | 新增英文字符串 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 新增简体中文字符串 |
| `app/src/main/res/values-zh-rTW/strings.xml` | 新增繁体中文字符串 |
| `app/translation/strings-template.xml` | 翻译模板同步 |

---

## 9. 备注与已知限制

1. **Debug 构建**：当前交付 APK 为 Debug 签名，仅供测试安装。
2. **搜索范围**：关于页不参与搜索，也没有搜索入口（按需求只覆盖其余页面）。
3. **条件条目**：实验模式关闭时，「实验模式」「强制歌词提供者」不会出现在搜索结果中；
   开启实验模式后即可正常搜索并定位。
4. **引导页复用**：第 3、4 页直接复用主页面组件，后续主页面交互调整会自动同步。
5. **未引用字符串**：`onboarding_source_permission_hint`、`onboarding_action_grant_root`
   在改为复用主页面组件后已无代码引用，目前保留在资源文件中未删除。
6. **首次启动判定**：只要检测到任一用户配置（`aod_render` / `app_ui_appearance` / `app_navigation`），
   即视为老用户，不再自动弹出引导；仍可从设置 → 关于手动打开。
