# 歌词字形转换插件（Script Convert）

HyperLyric / HyperGlow 插件体系的补充成员：把歌词在**简体 ⇄ 繁体**之间整体转换。

- 插件 id：`com.example.hyperglow.scriptconvert`
- 入口类：`com.example.hyperglow.scriptconvert.ScriptConvertPlugin`
- 目标宿主：HyperLyric 插件 API v1（HyperGlow CN+ 的 `plugins/api` 契约）

## 为什么需要它

宿主与歌词生产者都不做字形转换：简体源给简体、繁体源给繁体。用户听到的歌词字形
完全取决于上游（Apple Music / Spotify 的台湾发行版常给繁体，港台平台常给繁体，
部分源又只有简体）。本插件在**显示前**补这一层，只改内容、不动时间轴。

## 行为契约

| 项 | 取值 | 说明 |
| --- | --- | --- |
| 阶段 | `TRANSLATION_ENHANCEMENT` | 在歌词替换类处理器之后运行 |
| 更新模式 | `PATCH` | 行数与行索引不变，只覆盖声明过的行字段 |
| 转换字段 | `TEXT`、`WORDS`、`TRANSLATION`、`TRANSLATION_WORDS` | 词表与文本同源转换，卡拉OK 词界不变 |
| 不动字段 | `begin`/`end`/`duration`、`isAlignedRight`、行 `metadata`、`roma` | 时间轴、对唱分侧、行角色、罗马音一律保留 |
| 不碰字段 | `secondary`/`secondaryWords` | 宿主回向（`PluginSongBridge.enrichState`）不消费这两个字段，声明它们只会制造"看起来改了、实际不生效"的死键 |
| 幂等 | 是 | 目标字形与源一致、或整首无可转换字符时返回 `null`，宿主保持透传 |
| 性能 | 首次约 70 ms 建表，之后每首歌约 1 ms | 词典懒加载；整首转换带字符串缓存 |

**插件间顺序**：宿主 `PluginRuntime.processChain` 外层按**安装顺序**遍历插件、内层才按
阶段排序。因此想让翻译类插件的产物也被转换，请把本插件**安装在翻译插件之后**。

## 设置项

| key | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `scriptconvert_enabled` | switch | `false` | 激活开关（`activationSettingKey`） |
| `scriptconvert_target` | select | `simplified` | `simplified` 繁→简；`traditional` 简→繁 |
| `scriptconvert_translation` | switch | `true` | 是否同时转换翻译行 |

默认目标为「简体」：对大陆用户，最常见的场景是繁体源转简体；已是简体时转换是空操作，
不会产生意外改写。

## 接入方式（在 HyperGlow CN+ 仓库内）

1. 把本目录复制为 `plugins/scriptconvert`；
2. 在根 `settings.gradle.kts` 增加 `include(":plugins:scriptconvert")`；
3. 打包：`./gradlew :plugins:scriptconvert:pluginZip`
   → `plugins/scriptconvert/build/distributions/hyperglow-scriptconvert-plugin.zip`
   （内含 `manifest.json` + `classes.dex`）；
4. 安装：HyperGlow 设置 → 插件管理 → 从本地 ZIP 安装。

若要正式发布，建议把 `com.example.hyperglow.scriptconvert` 换成自己的命名空间——
四处同步改：目录名、源码包名、`manifest.json` 的 `id`/`entry`、入口类名
（与 `plugins/template/README.md` 的改名步骤一致）。

打包需要 Android SDK build-tools（`d8`）与 `android.jar`，定位规则与 `plugins/template`
一致（`local.properties` 的 `sdk.dir` → `ANDROID_HOME`）。纯 Kotlin/JVM 模块，
`:plugins:api` 为 `compileOnly`，`kotlin-stdlib` 由宿主提供、不进 dex。

## 本地开发

```bash
./gradlew :plugins:scriptconvert:test     # 转换器 + 处理器行为单测
python tools/gen_opencc_tables.py         # 重新生成转换表（会校验上游 sha256）
```

## 数据来源与许可

转换数据来自 [OpenCC](https://github.com/BYVoid/OpenCC) 词典（Apache-2.0），
钉在上游 commit `3ac34aa439a9908dd49fa92b5174b46314787ac2`：

| 文件 | 用途 | sha256 |
| --- | --- | --- |
| `STCharacters.txt` | 简→繁 单字 | `a0ca1601…c03582b` |
| `STPhrases.txt` | 简→繁 短语 | `f6eab5e5…94e2925a` |
| `TSCharacters.txt` | 繁→简 单字 | `9ff46a7d…3ae1642d` |
| `TSPhrases.txt` | 繁→简 短语 | `9a23666e…b608cef102` |

`tools/gen_opencc_tables.py` 生成 `OpenCcTables.kt`（约 295 KB 源码），并做两处裁剪：

1. **单字表**只保留首选字，剔除恒等条目；
2. **短语表**只保留「逐字转换结果 ≠ 词条目标」的条目——既保住消歧条目
   （`干杯 → 乾杯`），也保住**防误转**的恒等条目（`皇后 → 皇后`：逐字转换会错成 `皇後`）。

生成物是 OpenCC 数据的衍生物，随本插件按 Apache-2.0 分发；上游版权与许可声明见
OpenCC 仓库 `LICENSE`。

## 致谢

- **[OpenCC](https://github.com/BYVoid/OpenCC)**（Apache-2.0，作者 BYVoid 等）—— 简繁转换
  词典数据的唯一来源；生成器钉住上游 commit 并逐文件校验 sha256，词典裁剪规则写在
  `tools/gen_opencc_tables.py` 里。
- **[Lyricify-Lyrics-Helper](https://github.com/WXRIW/Lyricify-Lyrics-Helper)**（Apache-2.0，
  作者 WXRIW）—— 「歌词字形转换」这一能力项的来源参考（其 `ChineseHelper` 提供简繁转换），
  本插件按插件体系的需要重写为纯 Kotlin 实现。

## 已知限制

- 匹配策略是从左到右的**贪心最长匹配**（短语优先，未命中回落单字表），不是 OpenCC 的
  完整分词转换。歌词是短句、歧义集中在双字词，实测覆盖足够；不做整句分词以避免引入
  词典分词器的体积与不确定性。
- 只做 OpenCC 基础 `s2t`/`t2s` 字形，不含区域变体（台湾 `s2tw`、香港 `s2hk` 的用词差异，
  如 `麪條` vs `麵條`）。需要时可在生成器里换用对应词表。
- 不转换歌曲元数据（标题/歌手/专辑），只转歌词内容。
- 与翻译插件的先后关系取决于安装顺序（见上）。

## 验证情况

本地以 `kotlin-compiler-embeddable 2.4.20` 编译并通过：

- `ScriptConverterTest`（9 个用例）与 `ScriptConvertPluginTest`（5 个用例）全绿；
- 端到端复核：把处理器结果喂给宿主真实的 `PluginChainMerger`，断言 PATCH 被接受、
  行数与时间轴/分侧/行角色/roma 不变、二次处理为空操作（幂等）、往返转换正确、
  翻译开关生效；
- 人工复核样例：`干杯→乾杯`、`头发→頭髮`、`皇后→皇后`、`台湾→臺灣`、`一只→一隻`
  而独立 `只` 保持 `只`、纯拉丁行不变。

**尚未验证**：`d8` 打包与真机安装（本机无 Android SDK build-tools）。

---

# Script Convert Plugin (English summary)

A HyperLyric-API-v1 lyric processor that converts displayed lyrics between Simplified and
Traditional Chinese. It runs in the `TRANSLATION_ENHANCEMENT` stage, returns PATCH results
touching only `TEXT`/`WORDS`/`TRANSLATION`/`TRANSLATION_WORDS`, and deliberately never writes
`secondary`/`secondaryWords` (the host does not consume them on the way back). Conversion is
idempotent and a no-op when the text already matches the target script. Data comes from the
OpenCC dictionaries (Apache-2.0, pinned commit); `tools/gen_opencc_tables.py` regenerates the
embedded tables with sha256 verification. Plugins run in installation order, so install this
plugin *after* translation plugins to also convert their output.
