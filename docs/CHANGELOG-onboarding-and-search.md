# HyperGlow CN+ 变更文档

- **变更主题**：首次启动引导、云端外观预设、全局全量搜索、Miuix 页面重构、柔光玻璃重构、Root 管理器跳转、覆盖层可读性修复
- **应用版本**：0.4.2（versionCode 302，按需求保持版本号不变）
- **包名**：`com.aodianjun.hyperglow.cnplus`
- **日期**：2026-11-22
- **分支**：`feat/onboarding-search-polish`（已合并最新 main：云端预设 + EdgeOne 备份 Hub）
- **改动规模**：18 个文件，+3988 / −82（相对 main `1dad17a`）

---

## 1. 改动总览

| # | 模块 | 改动说明 | 主要文件 |
|---|------|----------|----------|
| 1 | 首次启动引导 | 新增 5 页全屏引导覆盖层，首次安装自动弹出 | `OnboardingScreen.kt`、`MainActivity.kt` |
| 2 | 引导入口 | 设置 → 关于 新增「使用引导」，可随时重看 | `HomeScreen.kt` |
| 3 | 引导页真实设置 | 第 2 页可直接开关息屏/锁屏歌词、重启系统界面 | `OnboardingScreen.kt` |
| 4 | 引导页云端预设 | 第 3 页只放入口与已选名称；选择在独立子页完成，只应用外观分节 | `OnboardingScreen.kt`、`PresetHub*.kt`、`ConfigPresetStore.kt` |
| 5 | 引导页歌词源 | 第 4 页复用主页面歌词源组件（连接状态 / 下载地址 / 授权 / 选源） | `OnboardingScreen.kt`、`LyricSourceComponents.kt` |
| 6 | 引导页权限 | 第 5 页复用主页面权限检查组件 | `OnboardingScreen.kt`、`PermissionStatusSection.kt` |
| 7 | 全局搜索 | 四个 Tab 顶栏搜索入口 + 全屏搜索覆盖层；Miuix `InputField` 输入 | `GlobalSearch.kt`、`HomeScreen.kt` |
| 8 | 全量索引 | 168 条：主 Tab 全部开关、外观逐项、子页选项、配置预设、关于页入口 | `GlobalSearch.kt` |
| 9 | 搜索定位 | 分组滚动 + 行级 `BringIntoView`；子页选项直接打开对应页面 | `HomeScreen.kt`、`LyricAppearanceSection.kt` |
| 10 | 条件条目过滤 | 实验模式相关条目按可用性过滤，避免搜到却找不到按钮 | `GlobalSearch.kt`、`HomeScreen.kt` |
| 11 | Root 管理器跳转 | 权限区新增「打开 Root 管理器」，自动匹配 Magisk / KernelSU / APatch | `PermissionStatusSection.kt` |
| 12 | 覆盖层可读性 | 引导页与搜索页改用不透明底色，修复透字看不清 | `AppUiAppearance.kt`、`AppTopBar.kt` |
| 13 | 测试与构建 | 搜索索引/匹配/行级锚点测试；搜索匹配拆成纯文档逻辑，不依赖 Android 资源加载 | `GlobalSearchTest.kt`、`OnboardingPreferencesTest.kt` |

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
- 欢迎页图标改用 `HyperGlowCnPlusIcon`：组合 CN+ 自适应图标的 `ic_launcher_background` + `ic_launcher_foreground`（带「+」与青色进度条），不再引用上游旧的 `drawable/ic_launcher`。
- `Scaffold` 使用不透明覆盖层底色 `appOverlaySurfaceColor()`，并消费空白处指针事件，防止点击穿透。
- 顶栏使用 `AppTopBar(overlay = true)`，不做壁纸模糊。
- 顶部：非最后一页显示「跳过」。
- 底部：圆点页码 + 「下一步」；最后一页为「开始使用」。
- 页面内容不满一屏时垂直居中，超出时可整页滚动（适配小屏 / 大字号）。
- 手势返回：`PredictiveBackHandler` 在引导层内消费。首次启动时返回手势被吞掉（避免没看完就退出），
  手动重看时返回只关闭引导，不触发底层页面返回。

### 2.3 五个页面

| 页 | 标题 | 内容 |
|----|------|------|
| 1 | 欢迎使用 HyperGlow CN+ | 功能简介 + 开始前的要求清单：Root 权限、LSPosed 并勾选系统界面作用域、至少一个歌词源 |
| 2 | 启用模块 | 实时模块状态（已生效 / 尚未检测到）+ **真实开关** + 打开 LSPosed + 重启系统界面 |
| 3 | 选择外观预设 | `CustomizationPresets.ALL` 六个预设一键套用，应用后同步运行时 |
| 4 | 选择歌词源 | 与主页面同源的歌词源卡片 + 未连接引导 + 选源对话框 |
| 5 | 检查权限 | 与主页面同源的权限状态组件 |

### 2.4 第 2 页：真实写入设置

不再只是展示功能，而是直接操作：

- **在息屏显示 / 在锁屏显示** 两个 `SwitchPreference`
  - 通过 `updateCustomizationSurfaceEnabled(context, surface, enabled)` 写入外观文档，与设置页同一套逻辑；
  - 不支持当前系统版本时在 summary 中说明，但开关仍可操作，模块生效后配置自动应用。
- **打开 LSPosed**：复用 `openLsposedManager(context)`。
- **重启系统界面**：`ShellUtils.restartHookedProcesses(systemUi = true, miuAod = false, hyperglowApp = false)`，成功/失败均有 Toast 提示。
- 模块状态监听 `XiaomiCapabilityStore.PREFS`，SystemUI 上报后无需退出引导即可刷新。

### 2.5 第 3 页：云端外观预设（选择在子页）

第 3 页本身只保留入口：

- `ArrowPreference` 显示「选择云端预设」；已应用后显示预设名 + 「已应用 · 点按更换」；
- 点按打开独立全屏子页 `CloudPresetPickerPage`，返回只关闭子页，不退出整个引导。

子页行为：

- 先读 `PresetHubStore` 本地缓存（离线也能展示上次结果），再 `PresetHubClient.fetch` 拉取云端清单；
- 只列包含 `ConfigPresetSection.APPEARANCE` 分节的云端预设，卡片用 `PresetPreviewCard` 大预览；
- 点选后 `ConfigPresetStore.decode(..., setOf(APPEARANCE))` + `apply`，**只应用外观分节**，
  不改行为、语言、歌词源与插件设置；
- 加载 / 失败 / 云端为空三种状态分别有 `BasicComponent` 文案与「重试」按钮；
- 应用成功回到第 3 页并显示预设名；网络不可用且无缓存时可直接返回跳过。

### 2.6 第 4 页：歌词源（与主页面同源）

直接复用主页面组件，而不是引导页单独实现一套：

- `LyricSourceSection(onOpenSourceDialog = ...)`：显示当前歌词源与真实连接状态；
- `SourceSetupHint()`：未连接时提供歌词源下载地址、通知使用权授权入口；
- `LyricSourcePickerDialog(...)`：点选后立即生效。

因此主页面后续对歌词源交互的任何修改都会自动同步到引导页。

### 2.7 第 5 页：权限检查（与主页面同源）

- 使用 `SettingsCard { PermissionStatusSection() }`，与状态页完全一致；
- 覆盖通知权限、歌词前台服务、电池优化、Root 权限；
- Root 授权同时支持系统授权弹窗与跳转 Root 管理器手动授权；
- 删除了引导页原先自写的权限行、ActivityResult launcher 与对应 import。

### 2.8 入口与返回行为

- **步骤式引导**：`HorizontalPager(userScrollEnabled = false)`，只能走底部「上一步 / 下一步」，
  避免误滑到未准备的分页；第 1 页不显示「上一步」。
- **设置 → 关于 → 使用引导**：新增 `ArrowPreference`，点击重新打开引导覆盖层。
- 手动重看时 `onboardingManual = true`：顶栏显示返回按钮，返回只关闭引导，不写入完成标记。
- 首次启动没有返回按钮，返回手势也会被引导层吞掉，只能「跳过」或「开始使用」；两者都会写入完成标记。
- `showOnboarding` 与 `onboardingManual` 使用 `rememberSaveable`：旋转屏幕不会把手动打开的引导丢掉，
  也不会把首次启动的引导误判为已完成。

### 2.9 偏好与测试

`OnboardingPreferencesTest`（Robolectric）覆盖：

| 用例 | 断言 |
|------|------|
| `freshInstallShowsOnboarding` | 全新安装显示引导 |
| `completedFlagHidesOnboarding` | 写入完成标记后不再显示 |
| `existingUserConfigurationHidesOnboarding` | 已有任意用户配置时不显示（视为老用户） |

---

## 3. 全局搜索

### 3.1 入口

- 位置：顶栏搜索图标，**状态 / 设置 / App 设置 / 关于** 四个 Tab 都有。
- 关于页本身也参与索引（使用引导、帮助、更新日志、贡献者、开源许可）。
- 点击后打开全屏搜索覆盖层，输入框自动聚焦、弹出键盘。

### 3.2 界面结构

- `Scaffold` 使用 `appOverlaySurfaceColor()`，`AppTopBar(overlay = true)`，返回键关闭。
- 覆盖层根节点加 `blockOverlayPointerInput()`，消费空白处的指针事件，避免点击穿透到下层页面。
- 顶部使用 Miuix `InputField`（搜索图标 + 清除按钮，`ImeAction.Search`）；打开后自动展开、
  请求焦点并拉起输入法，搜索键或选中结果时先收起键盘。
- 有输入且存在候选时，搜索框下方显示横向滚动补全胶囊。
- 结果按「页面 · 分组」用 `SmallTitle` + `Card` 聚合，`LazyColumn` 以组为 item：
  - 组标题显示范围，例如「状态 · 歌词源」；
  - 组内 `ArrowPreference` 显示条目名称与说明；长结果不再整块组合。

### 3.3 索引范围（170 条）

不再只索引「分组标题」，而是把每个开关 / 下拉项 / 滑杆 / 颜色行都收进索引：

| 范围 | 条数 | 说明 |
|------|------|------|
| 状态页 | 14 | 模块状态、实时状态、配置预设入口、歌词源卡片、运行时、权限区全部行 |
| 设置 · 其他设置 | 7 | 歌词增强、暂停行为、配置预设、配置备份 |
| 歌词外观 · 分组标题 | 9 | 两个总开关 + 位置/文字/效果/频谱/贴纸/颜色/锁屏卡/两区域 |
| 歌词外观 · 逐项 | 85 | 46 个开关/滑杆/动作行 + 32 个下拉项 + 7 个语义色行 |
| App 设置 | 16 | 语言、导航、应用外观入口、开发者选项、系统集成 |
| App 外观子页 | 14 | 主题、控件色、字体、背景图/变暗/模糊、系统栏图标、玻璃材质/HDR/模糊强度 |
| 息屏行为子页 | 20 | 保持常亮、时钟跟随、亮度、防烧屏、旋转、边距等 |
| 关于页 | 5 | 使用引导、帮助、更新日志、贡献者、开源许可 |

关键覆盖点：

- 外观编辑器里的「字体」「字号」「字重」「逐字效果」「辉光」「频谱开关/灵敏度」
  「锁屏歌词卡片」「播放进度」「语义色」等都能直接搜到；
- 通用外观项选中时**保持当前曲面**（在锁屏外观里搜索不会跳回息屏）；
  锁屏专属项（高度、卡片颜色、播放进度、保持唤醒等）固定切到锁屏分段；
- 子页选项带 `destination`，选中直接打开「应用外观」或「息屏行为」页面；
- 合并 main 新增的「配置预设」后，状态页入口与「配置备份」行也已补进索引；
  main 移除内置预设包后，对应的旧索引条目同步删除；玻璃材质/HDR 高光/模糊强度也已补进 App 外观子页条目。

### 3.4 模糊匹配规则

每条索引参与匹配的字段：标题、说明、分组名、页面名、英文/中文关键词。

查询按空格分词，**每个词都必须命中**；单条结果得分为各词得分之和，按总分降序排列。

单个词的匹配分档：

| 优先级 | 规则 | 得分 |
|--------|------|------|
| 1 | 标题包含连续子串 | 1200 − 位置 |
| 2 | 说明/分组/关键词包含连续子串 | 900 − 位置 |
| 3 | 标题按顺序包含查询字符（跳字，长度 ≥ 3 或 2 个汉字） | 560 |
| 4 | 全字段按顺序包含查询字符（跳字，同上） | 420 |
| 5 | 英文单词编辑距离 ≤ 1（长度 ≥ 3，纯 ASCII） | 300 |

示例：

- 「歌源」可以命中「歌词来源」（跳字）；
- 「sorce」可以命中 `source`（英文编辑距离 1）；
- 「歌词 来源」两个词都命中才返回。
- 单字「歌」只走连续子串；两字母英文（如 `sg`）不做跳字，避免把整张索引捞回来。

### 3.5 输入补全

- 从索引标题中筛选包含当前输入的候选；
- 排除与输入完全相同的标题，按首次出现位置排序，去重后最多 6 条；
- 点击胶囊即把输入替换为完整标题，同时保留继续编辑与搜索的能力。

### 3.6 条件显示条目

只有「实验模式」是条件条目：它仅在实验模式可用时渲染于设置页
（`experimentalEligible = EXPERIMENTAL_ELIGIBLE || UNSUPPORTED_PROFILE`）。

搜索索引为这类条目增加 `requiresExperimental` 标记，并在过滤与补全时接收 `experimentalEligible`：

- 实验模式不可用：条目从结果与补全中完全移除，避免「搜到但页面没有这个按钮」；
- 实验模式可用：正常参与搜索与定位。

「强制歌词提供者」在设置页始终渲染，因此**不**带 `requiresExperimental`，
否则会出现「按钮在、却搜不到」的反向不一致。

### 3.7 定位与滚动机制

1. 各页 `LazyColumn` 组合时按顺序登记 `anchor → item index`
   （`HomeScreen.kt` 的 `tracked(...)`、`LyricAppearanceSection.kt` 的 `trackedItem(...)`）。
2. 每个可搜索的开关/下拉/滑杆/颜色行在组合时登记一个 `BringIntoViewRequester`
   （`GlobalSearchRowAnchors`，key 由 `SearchRowKeys` 或枚举名生成）。
3. 选中搜索结果后：
   - 关闭搜索覆盖层并收起输入法；
   - 子页选项（带 `destination`）直接 `onOpenAppAppearance / onOpenAodBehavior`，不切主页分页；
   - 若目标在「设置」页：外观通用项保持当前曲面，锁屏专属项切到锁屏，其他设置切到「其他设置」；
   - 切换到目标 Tab（`pagerState.animateScrollToPage`）。
4. **先等外层分页停到目标 Tab**（`currentPage == 目标 && !isScrollInProgress`，最多 3 秒），
   再等分组锚点登记（最多 2 秒），随后 `scrollToItem(index)`。
5. 若条目有 `rowAnchor`：等行登记（最多 1.5 秒）后调用 `bringIntoView()`，把具体开关带进视口；
   条件开关未渲染时自动回落到分组标题。
6. 外观锚点集合由 `GlobalSearchAnchor.APPEARANCE_ANCHORS / isAppearanceAnchor` 显式判定，
   外观编辑器使用独立的 `appearanceListState`。

### 3.8 测试

`GlobalSearchTest`（纯 JUnit，不加载 Android 资源）：

匹配逻辑拆成 `resolveGlobalSearchDocuments(context)`（一次性解析文案）与
`filterGlobalSearchDocuments(documents, query, experimentalEligible)`（纯匹配），
测试直接构造 `GlobalSearchDocument`，不再依赖 Robolectric 的 `isIncludeAndroidResources`。
后者会改变全局测试环境，实测会让无关的 `PluginRuntimeTest` 出现 5 个超时失败。

| 用例 | 覆盖点 |
|------|--------|
| `exactTitleStillMatches` | 精确标题命中 |
| `subsequenceFuzzyMatchFindsSkippedCharacters` | 跳字模糊匹配（歌源 → 歌词来源） |
| `twoLetterAsciiQueryDoesNotSpraySubsequenceHits` | 两字母英文不做跳字，避免整表命中 |
| `singleChineseCharacterStillMatchesContiguousText` | 单字仍可连续子串命中 |
| `multiTokenQueryRequiresEveryToken` | 多词 AND 语义 |
| `typoInEnglishKeywordStillMatches` | 英文拼写容错（sorce → source） |
| `completionsReturnTitlesContainingInput` | 输入补全 |
| `experimentalEntriesAreHiddenWhenIneligible` | 条件条目按实验模式过滤 |
| `forceProviderSearchableWhenExperimentalIneligible` | 非条件条目不被误过滤 |
| `indexCoversAppearanceRowsAndAbout` | 外观逐项/子页/关于页索引覆盖，行级锚点存在 |
| `appearanceAnchorsAreClassifiedExplicitly` | 外观锚点显式分类 |
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
- `AndroidManifest.xml` 的 `<queries>` 同步声明上述四个包名：Android 11+ 未声明的包对
  `getLaunchIntentForPackage` 不可见，缺声明会把已安装的管理器误判为「未找到」。
  本应用虽声明了 `forceQueryable`，但它只在系统安装场景生效（普通安装不生效），不能替代 `<queries>`。

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

新增 46 条字符串（含云端预设子页/引导页脚/搜索提示等），同步维护 4 份文件：

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
| 外观预设 | `onboarding_preset_title`、`onboarding_preset_summary`、`onboarding_preset_applied`、`onboarding_preset_loading`、`onboarding_preset_load_failed`、`onboarding_preset_empty`、`onboarding_preset_retry`、`onboarding_preset_cloud_section`、`onboarding_preset_pick`、`onboarding_preset_pick_summary`、`onboarding_preset_change` |
| 引导页脚 | `onboarding_back` |
| 歌词源 | `onboarding_source_title`、`onboarding_source_summary` |
| 权限 | `onboarding_permission_title`、`onboarding_permission_summary` |
| Root 管理器 | `action_open_root_manager`、`summary_open_root_manager`、`toast_root_manager_not_found` |
| 全局搜索 | `search_title`、`search_placeholder`、`search_hint`、`search_empty`、`search_scope` |

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

- 全量 1909 个用例，0 失败 / 0 错误；
- `GlobalSearchTest`：12 个用例全部通过（纯 JUnit，不加载 Android 资源），
  其中 `indexCoversAppearanceRowsAndAbout` 断言索引 ≥150 条、外观行/子页/关于页均有覆盖；
- `OnboardingPreferencesTest`：3 个用例全部通过；
- 既有 `UiStringsContractTest`、`PluginRuntimeTest`、`PresetHubTest`、`ConfigPresetStoreTest` 通过。

> 曾尝试用 `isIncludeAndroidResources = true` 让搜索测试直接读资源，实测会使无关的
> `PluginRuntimeTest` 出现 5 个超时失败（关闭后恢复）。因此搜索匹配改为
> 「资源解析 + 纯文档匹配」，测试不再改变全局测试环境。

### 7.2 构建

```
./gradlew :app:assembleDebug --console=plain
```

结果：`BUILD SUCCESSFUL`（增量 51s）

产物：`app/build/outputs/apk/debug/app-debug.apk`（约 56 MB，Debug 签名）
版本校验：`versionCode=302 / versionName=0.4.2`（按需求不升版本）

### 7.3 部署

本次仅本地构建验证，未部署站点；0.4.0 的旧部署信息不再适用于当前产物。

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
| `app/src/main/java/com/eza/hyperglow/ui/HomeComponents.kt` | 新增 `HyperGlowCnPlusIcon`（CN+ 自适应图标组合），欢迎页/关于页回落共用 |
| `app/src/main/java/com/eza/hyperglow/ui/LyricAppearanceSection.kt` | 外观编辑器列表项锚点登记与外部 `LazyListState` |
| `app/src/main/java/com/eza/hyperglow/ui/LyricSourceComponents.kt` | `openLsposedManager` / `lyricSourceLabel` 改为 `internal` 供引导页复用 |
| `app/src/main/java/com/eza/hyperglow/ui/AppUiAppearance.kt` | 新增不透明覆盖层底色 |
| `app/src/main/java/com/eza/hyperglow/ui/AppTopBar.kt` | 新增 `overlay` 模式 |
| `app/src/main/AndroidManifest.xml` | `<queries>` 声明 Root 管理器包，修复 Android 11+ 跳转误报 |
| `app/src/main/res/values/strings.xml` | 新增英文字符串 |
| `app/src/main/res/values-zh-rCN/strings.xml` | 新增简体中文字符串 |
| `app/src/main/res/values-zh-rTW/strings.xml` | 新增繁体中文字符串 |
| `app/translation/strings-template.xml` | 翻译模板同步 |

---

## 9. 备注与已知限制

1. **Debug 构建**：当前交付 APK 为 Debug 签名，仅供测试安装；版本号保持 0.4.2（302）。
2. **搜索范围**：四个 Tab 都有搜索入口；索引覆盖主 Tab 全部开关、歌词外观逐项、应用外观/息屏行为子页、
   配置预设入口、关于页入口。插件管理、歌词增强里的插件项是运行时动态生成的，不在静态索引内。
3. **条件条目**：实验模式不可用时，只有「实验模式」从搜索结果中移除；
   「强制歌词提供者」在设置页始终可见，因此始终可搜索。
   条件开关（如纯音乐文本、第二行辅助文字）未渲染时，搜索会停在所属分组标题。
4. **子页定位**：应用外观/息屏行为条目会直接打开对应子页；子页本身没有逐行锚点，进入后需按页面内的分组查看。
5. **引导页复用**：第 4、5 页直接复用主页面歌词源/权限组件，后续主页面交互调整会自动同步。
6. **云端预设子页**：第 3 页只放入口；选择在独立子页完成，先读本地缓存再拉云端清单。
   只展示含外观分节的预设，应用时只写外观分节，不改行为/语言/歌词源/插件设置。
   无网络且无缓存时显示失败态与「重试」，返回即可跳过。
7. **首次启动返回**：首次启动的引导层会吞掉返回手势，避免未看完就退出导致下次启动重复弹出；
   手动重看时返回只关闭引导。
8. **首次启动判定**：只要检测到任一用户配置（`aod_render` / `app_ui_appearance` / `app_navigation`），
   即视为老用户，不再自动弹出引导；仍可从设置 → 关于手动打开。
9. **Miuix 重构**：引导页改为步骤式（禁滑动、底部上一步/下一步）、`SmallTitle + Card` 分组、
   12.dp 卡片边距；搜索页改用 `InputField`，结果按分组用 `SmallTitle + Card` 懒加载。
10. **欢迎页图标**：改用 CN+ 自适应图标组合（background + foreground），不再使用上游旧的扁平 `ic_launcher`。

---

## 10. 柔光玻璃重构（引导页 + 搜索页）

在两页覆盖层上接入应用已有的玻璃材质系统（`AppGlassMaterial` / `appGlassMaterial`），
不改用户外观设置：材质选「柔光玻璃」时是柔光，选其他档位时跟随全局，`NONE`/不支持 shader 时回落实色。

### 10.1 背景

- 搜索页使用新增的 `AppSoftGlassBackground`：实色 `surface` + 两团低透明度径向柔光；
- **不在整屏套 `appGlassMaterial`**：柔光玻璃的边缘高光会在屏幕四边画出一圈亮线，像给页面加了边框；
- 引导页按 HyperIsland 参考改为干净的 `appOverlaySurfaceColor()` 实色底，不再叠柔光/玻璃背景；
- 玻璃材质只用于真正需要材质的表面：搜索输入框、搜索卡片、内页卡片与搜索顶栏；
- 两页 `Scaffold` 容器透明或实色，由各自背景层决定。

### 10.2 顶栏

- `AppTopBar` 新增 `glassOverlay` 参数：覆盖层也可以走玻璃材质与渐变遮罩；
- 引导页、云端预设子页、全局搜索顶栏统一传 `glassOverlay = true`。

### 10.3 引导页（按 HyperIsland 参考）

- 去掉 `AppTopBar`，改为 HyperIsland 式 56.dp 普通头部：
  - 第 1 页不显示标题，内页显示应用名（`title4` SemiBold）；
  - 右上角关闭图标代替「跳过」文字；手动重看时左侧显示返回图标。
- 欢迎页完全对齐参考：116.dp 应用图标 + 28.dp 间距 + 40.sp 粗体应用名，居中；
  不再有副标题、要求清单、步骤圆环或底部玻璃横条。
- 「开始之前」清单移到第 2 页「启用模块」顶部。
- 内页 `StepHero` 改为 70.dp 主色图标（模块/预设/歌词源/权限），标题用 `title1`、说明用 `body1`。
- 底部操作改为两个**等权** `Button`：第 1 页「上一步」禁用，主按钮走 `buttonColorsPrimary`；
  去掉页码圆点，与参考一致。
- 内容最大宽度 560.dp 居中，卡片仍是 `SmallTitle + Card` 分组。

### 10.4 搜索页

- `InputField` 改为玻璃胶囊（透明容器 + `appGlassMaterial(RoundedCornerShape(50))`）；
- 补全胶囊、结果分组 Card 同样走玻璃；分组标题仍用 `SmallTitle`；
- 结果卡片的 `cornerRadius` 与 `CardDefaults.CornerRadius` 对齐，边缘高光不被方形 shader 拉歪。

### 10.5 搜索索引

- 玻璃相关条目已补入索引：`玻璃材质`、`HDR 高光`、`模糊强度`（App 外观子页），
  以及锁屏歌词卡片的 `卡片材质`、`卡片 HDR 高光`、`材质模糊强度`、`材质微调`（行级锚点）；当前共 170 条。

### 10.6 验证

- 代码编译通过；玻璃 API 使用 0.9.4 现有 `miuix-blur`（未升级依赖）。
- 视觉/性能（整屏玻璃模糊、卡片边缘高光）仍需真机或模拟器确认；当前环境无可用设备。

