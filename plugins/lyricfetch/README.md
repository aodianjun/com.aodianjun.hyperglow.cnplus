# 在线歌词增强插件（Lyric Fetch）

把 **accompanist-lyrics-core** 与 **Lyricify-Lyrics-Helper** 两个歌词库的能力，做成 HyperLyric /
HyperGlow 插件体系里可安装的一个插件。

- 插件 id：`com.example.hyperglow.lyricfetch`
- 入口类：`com.example.hyperglow.lyricfetch.LyricFetchPlugin`
- 目标宿主：HyperLyric 插件 API v1（HyperGlow CN+ 的 `plugins/api` 契约）

## 为什么是"取词 + 解析"这个形态

插件 API 只暴露一个扩展点：`LyricProcessorExtension`——拿到的是**已结构化的** `PluginSong`。
"解析能力"要有输入，只能由插件自己去在线取原始歌词文本。因此本插件做三件事：

```
媒体信息（标题/艺人/专辑/时长）
      ↓  在线取词（Lyricify 的来源与匹配语义）
  原始歌词文本（YRC / LRC）
      ↓  多格式解析（accompanist-lyrics-core 的 AutoParser）
  行 + 词 + 翻译
      ↓  REPLACE 回写宿主
```

## 能力映射

| 来源 | 能力 | 在本插件里的落点 |
| --- | --- | --- |
| accompanist-lyrics-core（Apache-2.0，**打进插件 dex**） | LRC / Enhanced LRC / YRC / KRC / TTML / Lyricify Syllable 自动识别解析，行/音节模型、翻译字段 | `LyricsPipeline.parse` 的解析内核 |
| Lyricify-Lyrics-Helper（Apache-2.0，**语义移植**，C# 无法直接引用） | 在线来源与搜索匹配（CompareHelper 的标题/艺人/时长三维度）、翻译合并、音节合并（SyllableWordMerger）、LRCLIB Provider 语义 | `TrackMatch`、`LyricsPipeline.mergeTranslation` / `mergeSyllableWords`、`LrclibProvider` |

## 在线来源（2026-10 实测状态）

| 来源 | 端点 | 能力 | 鉴权 |
| --- | --- | --- | --- |
| 网易云音乐 | `POST /api/cloudsearch/pc`（搜索）+ `GET /api/song/lyric/v1`（歌词） | **YRC 逐字** + LRC + 翻译（tlyric） | 免鉴权 |
| QQ 音乐 | `POST u.y.qq.com/cgi-bin/musicu.fcg`（搜索 + PlayLyricInfo） | 行级 LRC（base64 包装）+ 翻译通道 | 免鉴权 |
| LRCLIB | `GET /api/get`、`/api/search` | 行级同步 LRC | 免鉴权 |

**逐字来源只有网易云**。两条被排除的路，都实测过：

- 网易云旧搜索端点 `/api/search/get/web` 对中文查询已开始返回无关结果（英文仍正常），
  因此改用 `cloudsearch/pc`；
- QQ 的 **QRC 逐字匿名不可得**：PlayLyricInfo 的 `qrc` 字段为空（`lyric` 是 base64 的行级 LRC），
  而 `lyric_download.fcg` 返回的 hex 用社区流传的 3DES 密钥
  （`!@#)(*$%123ZXC!@!@#)(NHL`）解不开（DES/3DES 多密钥与模式变体实测均失败）。
  故 QRC 解密没有进代码——不交付验证不了的解密实现。

## 行为契约

| 项 | 取值 | 说明 |
| --- | --- | --- |
| 阶段 | `LYRIC_REPLACEMENT` | 先于翻译增强类插件，换的是整张歌词表 |
| 更新模式 | `REPLACE` | 行数与时间轴都换成在线版本 |
| 行角色 | `metadata["role"] = "LEAD" / "BG"` | 与 Spicy 文档桥同一约定；和声行是独立行 |
| 分侧继承 | `isAlignedRight` 按起始时间就近（≤700ms）从原行继承 | REPLACE 不该丢掉生产者推导的对唱分侧 |
| 缓存 | 宿主 `PluginCache`（持久，进程重启仍在）+ 内存一级缓存；**含负缓存** | 逐行源每 15s 重跑插件链，必须避免重复打网络；条目 7 天过期、上限 100 条 |
| 缓存管理 | 声明 `cacheScopes` + 注册 `PluginCacheExtension` | 宿主设置页出现「缓存」区，可列出/删除单条/清空（见下） |
| 时间预算 | 总 30s（宿主上限 40s），按剩余时间逐来源下发，单请求连接 ≤3s / 读取 ≤5s | 宁可不取词，也不能把宿主链拖到超时被丢弃 |
| 失败语义 | 任何异常/超时/未命中 → 返回 `null`（透传） | 取词失败绝不能让宿主链抛异常 |
| 幂等 | 同会话重复调用命中缓存，返回同一结果 | — |

**升级策略**（设置项 `lyricfetch_upgrade_only`，默认开）：只在在线版本**有逐字**而当前没有时才替换；
关闭则"只要匹配到就替换"（即使在线版本只有行级）。当前歌词已是逐字时直接透传，省一次网络往返。

## 缓存管理

宿主的插件缓存管理有两道门，插件必须都配合，否则用户既看不到也清不掉：

1. **`manifest.json` 声明 `cacheScopes`** —— 设置页才会出现「缓存」区（显示宿主侧占用）。
   更关键的是：`HostPluginContext.cache` **只在声明了 scope 时**才是真实的文件缓存
   （`files/plugin_cache/<pluginId>/`，每插件 16 MiB 配额），否则是 `NoopPluginCache`，
   写入会被静默丢弃——不声明 scope 的插件"用了 cache"其实什么也没存下。
2. **注册 `PluginCacheExtension`** —— 缓存页据此列出条目、删除单条、清空。
   不注册时宿主只能显示「未提供缓存管理」，用户唯一的办法是清空整个缓存目录。

本插件的实现（`LyricCache`）：

- 条目按「曲目 + 设置」键存储（命中存原文，未命中存负缓存），**跨进程重启仍在**；
- `PluginCache` 没有键枚举能力，因此每次写入同步维护一条索引记录
  （id / 标题「曲名 — 艺人」/ 摘要「来源 · 行数 · 逐字 · 含翻译」/ 大小 / 时间），
  `listEntries()` 直接读索引并剔除已消失的条目；
- `clearEntry` 会**同时清掉内存一级缓存**，否则界面上删了、插件仍在用旧结果；
- 条目 7 天过期（在线歌词会更新，过期即重新取词）、最多 100 条（按时间淘汰最旧的）。

用户在 设置 → 插件管理 → 在线歌词增强 → 缓存 里能看到取过哪些歌、每条的大小与时间，
匹配错了删掉那一条即可重新取词，不必重启 App。

`scriptconvert` **有意不暴露缓存**：它的记忆表是纯函数派生（输入相同则输出相同），
清不清都不改变任何可见行为，给个按钮反而误导。

## 设置项

| key | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `lyricfetch_enabled` | switch | `false` | 激活开关（`activationSettingKey`） |
| `lyricfetch_provider` | select | `auto` | `auto` 依次尝试网易云 → QQ → LRCLIB；也可指定单一来源 |
| `lyricfetch_upgrade_only` | switch | `true` | 仅当在线版本有逐字时才替换 |
| `lyricfetch_translation` | switch | `true` | 使用在线翻译（网易云 tlyric / QQ trans） |
| `lyricfetch_merge_syllables` | switch | `true` | 拉丁音节合并为整词（逐字母高亮 → 逐词高亮） |

## 接入方式（在 HyperGlow CN+ 仓库内）

1. 把本目录复制为 `plugins/lyricfetch`；
2. 在根 `settings.gradle.kts` 增加 `include(":plugins:lyricfetch")`；
3. 打包：`./gradlew :plugins:lyricfetch:pluginZip`
   → `plugins/lyricfetch/build/distributions/hyperglow-lyricfetch-plugin.zip`；
4. 安装：HyperGlow 设置 → 插件管理 → 从本地 ZIP 安装。

与 template/demo 的两处构建差异（都写在 `build.gradle.kts` 注释里）：

- 依赖 `com.mocharealm.accompanist:lyrics-core:0.4.7`（Maven Central，纯 Kotlin/JVM，无传递依赖）；
- **该依赖随插件打进 dex**（宿主契约允许插件自带类，child-first 加载），因此 `dexPlugin` 任务的
  d8 输入除插件自身 jar 外还包含 runtime 依赖；`kotlin-stdlib` 仍由宿主提供、只作 `--classpath`。

若要正式发布，建议把 `com.example.hyperglow.lyricfetch` 换成自己的命名空间（目录名、包名、
`manifest.json` 的 `id`/`entry`、入口类名四处同步改）。

## 本地开发

```bash
./gradlew :plugins:lyricfetch:test     # 39 个单测（纯函数 + 真实响应夹具）
```

## 验证情况（本地实跑）

- **46 个单元测试全绿**，其中响应解析全部用**真实抓取的载荷**（`src/test/.../Fixtures.kt`）：
  网易云 cloudsearch/YRC+tlyric、QQ 搜索 + 真实 base64 歌词全量载荷、LRCLIB get/search；
  另含 7 个缓存管理用例（条目元数据、负缓存标注、删单条/清空后重新取词、
  **跨实例命中持久缓存**、TTL 过期）；
- **真实 API 端到端 12 项断言全过**（三个来源实测）：
  - Adele《Hello》：网易云 → 51 行、逐字、49 行翻译；QQ → 47 行行级；LRCLIB → 行级；
  - 周杰伦《晴天》：网易云搜索首位的翻唱版（"晴天 (原唱 周杰伦) - RyaVocal"）被**艺人硬否决**
    挡掉 → NetEase MISS；QQ 命中正确曲目（行级）；该曲免鉴权来源没有逐字版本，
    插件**干净返回 null**、不做错误替换；
  - Coldplay《Yellow》：网易云 → 40 行、逐字、15 行翻译；
  - 处理器产出 `REPLACE`，`role=LEAD`，行/词时间轴与翻译均正确。
- **未验证**：`d8` 打包与真机安装（本机无 Android SDK build-tools）；
  以及宿主内实际渲染效果（插件结果在 AOD/锁屏的表现）。

## 已知限制

- 三个端点都是**非官方接口**，随时可能变更；响应解析已按"两代字段名兼容 + 缺字段不崩"写，
  但接口整体下线时只能换源。
- 逐字来源只有网易云一家；QQ 无 QRC、LRCLIB 只有行级。无逐字可升级时插件按设计返回 null。
- 匹配依赖宿主提供的媒体信息质量；媒体会话若只有标题（无艺人/时长），误配风险上升
  （阈值 `MIN_SCORE=0.62`，艺人或时长任一维度能对上才容易过线）。
- 插件之间按**安装顺序**串行：本插件在 `LYRIC_REPLACEMENT` 阶段替换整表，
  翻译增强类插件若安装在其后仍能正常处理替换后的歌词。
- 不做未同步歌词（无时间轴）——宿主渲染需要时间轴，纯文本结果会被丢弃。

## 许可与致谢

- **[accompanist-lyrics-core](https://github.com/6xingyv/accompanist-lyrics-core)**（Apache-2.0，
  作者 6xingyv / Mocha Realm）—— 多格式歌词解析内核（LRC / Enhanced LRC / YRC / KRC / TTML /
  Lyricify Syllable 自动识别），作为依赖**随插件打进 dex**。
- **[Lyricify-Lyrics-Helper](https://github.com/WXRIW/Lyricify-Lyrics-Helper)**（Apache-2.0，
  作者 WXRIW）—— 在线来源语义、搜索匹配打分（标题/艺人/时长三维度）、翻译合并与音节合并
  按其行为移植（C# → Kotlin），未复制其代码文件。
- **在线来源**：网易云音乐、QQ 音乐、LRCLIB。歌词数据与版权的权利归各平台与权利人所有；
  插件只读取公开接口、不绕过鉴权或付费墙，缓存也只保留播放取词所需的范围。

---

# Lyric Fetch Plugin (English summary)

A HyperLyric-API-v1 plugin that combines two lyric libraries into an installable plugin:
it looks up the current song online (NetEase / QQ Music / LRCLIB, all anonymous endpoints),
parses the raw text with accompanist-lyrics-core (LRC / Enhanced LRC / YRC / KRC / TTML /
Lyricify Syllable via `AutoParser`), merges translation and latin syllables (Lyricify
semantics), and returns a REPLACE result. It runs in the `LYRIC_REPLACEMENT` stage, caches per
song including negative results in the **host-owned persistent cache** (declared via
`cacheScopes`; entries expire after 7 days, capped at 100) and exposes them through a
`PluginCacheExtension`, so the host's cache page can list and delete entries — a wrong match
can be re-fetched without restarting the app. It also enforces a 30s total network budget
under the host's 40s processor limit. Word-level timing is only available from NetEase (QQ's
QRC is not reachable anonymously; the community 3DES key no longer decrypts it), so with the
default "upgrade only" policy the plugin stays a no-op when no better version exists.
