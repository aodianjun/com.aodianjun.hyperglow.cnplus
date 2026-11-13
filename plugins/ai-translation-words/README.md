# HyperGlow AI 逐字翻译插件 / HyperGlow AI Word-Level Translation Plugin

# 中文 / Chinese

状态：可安装的 HyperLyric 兼容插件（打包与发布遵循 `docs/PLUGIN_RELEASE_CONVENTIONS.md`）。

## 1. 插件做什么、位于管线何处

把当前歌词送给**用户自己配置的 OpenAI 兼容接口**（`chat/completions`）翻译成目标语言，
按行回写 `translation`；对**带逐字时间**的歌词行，再让模型把译文切成与源词一一对应的
片段，插件把每个片段贴回它对应源词的时间窗，回写 `translationWords`——宿主用
`PluginLyricField.TRANSLATION_WORDS` 驱动「辅助文字（翻译）逐字效果」随唱点亮。

- **阶段**：`TRANSLATION_ENHANCEMENT`；
- **更新模式**：`PATCH`（行数不变，声明 `TRANSLATION`，有逐字片段时追加 `TRANSLATION_WORDS`）；
- **调度**：宿主按会话调度插件链（逐行源每 15s 重跑），结果按曲目缓存——见 §5；
- **分批 + 渐进**（1.0.2 起，真机实测教训）：待译行按 12 行一批逐批请求（单批预算 12s、单轮总预算 30s，
  宿主每处理器 40s 上限），**缓存允许部分命中、也存部分结果**——整首一次发时几十行的歌会让端到端
  超过单次预算而整首失败（日志只有 `translation request failed ... (cooldown 10min)`，看起来就像插件没在工作）；
  现在长歌跨链轮次逐批完成，已完成的批立即上屏。

与兄弟插件 `plugins/ai-translation` 的关系：本插件是其**第二代变体**，行级翻译语义
（跳过语言 / 跳过已有翻译 / 强制翻译 / 行级过滤 / 按曲目缓存）完全一致，新增的只有
逐字片段这一条链路。**「模型搜索」同样不在本插件内**：它需要「按钮 → 异步拉取模型列表
→ 选择」的宿主 UI，而插件契约未暴露对应控件（见 §7）。

## 2. 写入与不写入的字段

写入（两项）：

| 字段 | 来源 |
|---|---|
| `translation` | 接口返回的该行译文；若片段通过校验，则以片段拼合结果为准 |
| `translationWords` | 译文片段按源词时间窗对齐后的 `PluginWord` 列表（仅在确实产出片段时写入） |

刻意**不写入**：

- `text` / `words` / `roma` / `secondary`：翻译不改歌词本身（PATCH 模式，其它字段原样保留）；
- 不新增或删除行：输入与输出严格按行 index 对应，宿主 diff 只看到上述两项变化；
- 行没有可用词时间轴（逐行源、或词文本全空）时只写 `translation`，**保留**该行原有的
  `translationWords`（不覆盖成空）；
- 关闭「逐字时间对齐」开关时完全不产出 `translationWords`，行为与兄弟插件一致。

## 3. 逐字对齐是怎么做的

1. **词时间轴**：仅当 `words` 非空、至少一个词文本非空、且这些词里至少一个
   `end > begin` 时，该行才算「带逐字时间」；文本为空的词（占位/间奏）剔除，不参与对齐。
2. **请求**：逐字行在 user payload 里多带 `tokens: [原词...]`（与上一步的窗口一一对应），
   system prompt 要求模型为这些行同时输出 `segments`，长度必须恰好等于 `tokens` 数量、
   拼接后逐字符等于 `translation`（西文词间空格写在片段内部，中文不加空格）。
3. **校验**：`segments` 数量不符或含非字符串元素时**整组片段作废**，只保留整行译文；
   通过校验时以片段拼合结果为权威译文（不采信模型另给的 `translation` 字段），并把
   拼合文本首尾被 trim 掉的空白从首/末个片段里移除。
4. **映射**：第 i 个片段继承第 i 个源词的时间窗；空串片段不产出；窗口无效
   （`end <= start`）的片段文本并入前一个片段（前面没有则贴到下一个，整行都没有有效
   窗口时退回整行窗口）；窗口完全相同的相邻片段合并；所有窗口钳制为非负。
5. **回写**：片段 → `PluginWord(begin, end, duration = end - begin, text)`。

## 4. 设置项

| 设置 | 说明 | 默认 |
|---|---|---|
| OpenAI 逐字翻译 | 总开关（activationSettingKey） | 关 |
| 自动跳过语言 | 识别为勾选语言的歌词整首不翻译（中文/English/日本語/한국어/Español）；未选择=关闭 | 未选择 |
| 自动跳过已有翻译歌曲 | 当前歌词已有翻译时整首跳过（与「强制 AI 翻译」互斥） | 关 |
| 强制 AI 翻译 | 无视「已有翻译」与既有译文，重新翻译所有待翻译行（与上一项互斥） | 关 |
| 目标语言 | 自由文本（如「中文」「English」）；可识别时启用「行已是目标语言则跳过」 | 中文 |
| 逐字时间对齐 | 开：带词时间的行额外产出 `translationWords`；关：退化为纯行级翻译 | 开 |
| API Key | 只随请求头发往所配置地址；**不参与备份导出**（backup=false） | 未配置 |
| 模型名称 | 手填模型名（模型搜索见 §7） | mimo-v2.5 |
| OpenAI API 地址 | 兼容 `/chat/completions` 的地址（可自建网关） | https://api.xiaomimimo.com/v1/ |
| 自定义提示词 | 只影响译文风格，不得覆盖核心 JSON/index/segments 协议 | 信雅达风格简述 |
| temperature / top_p / max_tokens | 采样参数（高级设置）；max_tokens=0 表示不传（不限） | 1.0 / 1.0 / 0 |

## 5. 缓存管理

翻译结果按曲目缓存在宿主持久缓存（`PluginCache`，**30 天 TTL、上限 200 条**），并通过
`PluginCacheExtension`（scope id `ai_translation_words_songs`，与 manifest 一致）暴露给宿主
「歌词增强 → 缓存管理」页：可列出、删除单条、清空。**删除单条即让该曲下次重新翻译。**

- 缓存键 = 曲目身份 + 目标语言 + 模型 + API 地址 + 提示词 + temperature/top_p + 逐字开关
  的 SHA-256；
- 条目存「行文本 → 译文（+ 逐字片段）」，回填按**行文本精确匹配**（歌词源换版本/行序
  变化也稳）；
- **逐行取用、渐进补齐**（1.0.2 起）：命中行直接回填，只对缺的行发请求；每轮新译的批
  与已命中行一起写回缓存（并集），下一轮只补缺——长歌因此不会因为一次预算不够就整首失败。
  逐字行的条目若带片段，片段数必须恰好等于当前词时间轴的词数（词时间轴变过就重翻该行，
  避免片段错位点亮）；
- **只缓存成功结果**：请求失败（限流/超时）不写缓存，改由内存失败节流退避——**整轮一条都没成功**
  才退避 2 分钟（1.0.2 起由 10 分钟缩短：切歌取消也会落到这条路径，长退避会让那首歌十分钟内
  不再重试，观感就是「插件没在用」）；部分成功不设退避，下一轮继续补缺。用户也可在缓存页删除
  条目强制重翻。

## 6. 安装

1. 构建：`./gradlew :plugins:ai-translation-words:pluginZip`
   → `plugins/ai-translation-words/build/distributions/hyperglow-ai-translation-words-plugin.zip`
   （ZIP = `manifest.json` + `classes.dex`，与兄弟插件同一打包管线）；
2. 在宿主「歌词增强 → 插件管理」安装该 ZIP（与兄弟插件同一入口）；
3. 打开「OpenAI 逐字翻译」开关，填 API Key 与模型名；
4. 想让译文逐字点亮，还需在宿主里为辅助文字（翻译）行启用逐字效果；未启用时译文仍按
   整行显示，只是不逐字。

## 7. 验证情况

- **单元测试**（证据：unit-tested）：`src/test` 覆盖语言检测与目标语言归一化、行级
  「无需翻译」判断、提示词组装（协议在风格提示词之上且带不得覆盖声明）、user payload
  结构（tokens 只出现在逐字行）、响应解析（map/数组/外层包裹/围栏/越界与垃圾输入、
  片段数量不符与非字符串元素降级、片段拼合为权威译文、首尾空白归一化）、词时间轴判定
  与片段对齐（长度不符、空片段、无效窗口并入前后片段、整行窗口回退、同窗合并、钳制）、
  请求体构造（max_tokens=0 不传、无 response_format）、响应提取、缓存（往返 / 片段载荷
  / 条目元数据 / 删除失效内存副本 / 30 天 TTL / 损坏载荷按未命中）。CI 侧
  `publish-plugins` job 运行 `:plugins:ai-translation-words:test`。
- **真实接口：未验证**——对真实 LLM 端点的端到端调用需要用户自己的 Key，未在开发侧发起
  （协议/请求体/解析已由单测覆盖，但**首次使用建议先小段歌词试翻**，确认端点、模型名
  与模型对 segments 协议的遵守度）。
- **真机（1.0.1，2026-10-07）**：插件在真机上被正常加载与调度（`enabled=true`、DeepSeek 端点），
  `translated 54 line(s)` 与 `accepted result ... changedLyricFields=[TRANSLATION]` 证明翻译链路通；
  同时暴露两个缺陷并已在 1.0.2 修复：①整首一次发的请求会超过单次预算（`threw after 32221ms`）
  导致整首无产出；②失败退避 10 分钟过长——切歌取消也会落到该路径，那首歌十分钟内不再重试。
  逐字产出仍待带词时间轴的曲目复验（当次播放的曲目为行级源，日志 `0 with word timing`）。

## 8. 已知限制

- **逐字效果取决于源是否提供词级时间轴**（真机实测）：源带词时间（Spicy、amll-ttml 插件、
  lyricfetch 的网易云逐字、Lyricon 带 YRC 的歌）时翻译辅助行按真实词窗点亮；**行级源
  （LyricInfo / SuperLyric）只会得到行级译文**——日志会显示 `(0 with word timing)`。
- **「自动跳过语言」会把整首跳过**（例如勾了「中文」时中文歌一行都不翻）：这是用户自己的
  设置，1.0.2 起该跳过会记在 info 级日志（原先 debug 级，默认日志级别下看不见，容易被当成
  「插件没在用」）。
- **无模型搜索**：插件契约的 UI 能力（manifest 声明 + 宿主渲染）没有「按钮触发异步列表」
  这一控件，模型名需手填；
- 语言检测是**字符集启发式**：日文歌若纯汉字歌词会被归为中文；混合语言按占比归类；
- 逐字效果取决于模型对 segments 协议的遵守度：片段数量不符时该行自动降级为整行译文
  （不产出错位的 `translationWords`），不同模型的切分粒度也可能不同；
- 对齐以**源词的窗口**为准：片段划分与源词划分不一致时，观感取决于模型的切分是否贴合
  旋律断句（提示词已要求，但不保证）；
- 一次请求发送**全部待翻译行**：超长歌词可能触及模型上下文/输出上限（受 max_tokens 与
  模型本身约束），未做分块；
- 翻译质量取决于所选模型与提示词；不同模型对 JSON 协议的遵守度不同（解析器已做容错，
  仍无法解析时按失败退避处理）。

## 9. 致谢与许可

- **无捆绑第三方库**：HTTP 用 JDK `java.net`，JSON 用平台 `org.json`（compileOnly，不进 dex）；
- 输入输出**协议语义**与官方 HyperLyric 翻译插件（`hyperlyric.ai.translation`，GPL-3.0，
  作者 lidesheng）对齐，**提示词与实现为独立撰写**（未复制其代码或提示词文本）；逐字
  片段协议为本插件自行设计；
- 默认 API 地址与模型为小米 MiMo 示例端点（用户可改为任意 OpenAI 兼容服务）；歌词文本
  仅发送至用户自己配置的服务，版权归各平台与权利人所有。

---

# English / English

Status: installable HyperLyric-compatible plugin (packaging and release follow
`docs/PLUGIN_RELEASE_CONVENTIONS.md`).

## 1. What it does, where it sits in the pipeline

Sends the current lyrics to the **user-configured OpenAI-compatible endpoint**
(`chat/completions`), writes translations back per line, and — for lines that carry word-level
timing — asks the model to split the translation into one fragment per source word, maps each
fragment back onto its source word's time window and writes `translationWords`, which the host
uses (`PluginLyricField.TRANSLATION_WORDS`) to light up the auxiliary-text karaoke row.

- **Stage**: `TRANSLATION_ENHANCEMENT`; **update mode**: `PATCH` (`TRANSLATION`, plus
  `TRANSLATION_WORDS` when at least one row got fragments);
- **Batched & progressive** (since 1.0.2): lines are sent 12 per request (12 s per batch, 30 s per
  processor run), and the cache accepts partial hits and stores partial results, so a long song
  completes across chain runs instead of failing wholesale on one oversized request.
- This is a second-generation variant of the sibling plugin `plugins/ai-translation`: the
  line-level semantics (skip languages / skip existing / force / per-line filter / per-song
  cache) are identical; only the word-fragment pipeline is new. Model search is not included
  (needs a host UI control the plugin contract does not expose).

## 2. Fields written / deliberately not written

`translation` and, when fragments survive validation, `translationWords`
(`PluginWord(begin, end, duration, text)`). `text`/`words`/`roma`/`secondary` are untouched, no
rows are added or removed. A line without usable word timing gets translation only, and its
existing `translationWords` (if any) is preserved. With the "word-level timing" switch off the
plugin behaves exactly like the sibling: translation only.

## 3. How the alignment works

1. A line is word-timed iff `words` is non-null, has at least one non-blank word, and at least
   one of those has `end > begin`; blank-text words are dropped.
2. Word-timed lines carry `tokens` in the user payload; the system prompt requires `segments`
   of exactly that length whose concatenation equals `translation` (spaces live inside the
   fragment for Latin scripts, none for Chinese).
3. Fragment validation is exact: a wrong count or a non-string element drops the whole fragment
   set (translation is kept). When valid, the fragments' concatenation is the authoritative
   translation, with outer whitespace moved out of the first/last non-empty fragment.
4. Fragment i inherits token i's window; empty fragments emit nothing; invalid windows
   (`end <= start`) merge into the previous emitted fragment (or prefix the next one, or fall
   back to the whole-line window); identical adjacent windows merge; windows are clamped to
   non-negative.

## 4. Settings

See the Chinese table: master switch, skip languages (multi-select, off by default), skip songs
with existing translation, force translation (mutually exclusive), target language,
word-level timing alignment (default on), API key (excluded from backups), model, base URL,
custom prompt, temperature / top_p / max_tokens.

## 5. Install

Build with `./gradlew :plugins:ai-translation-words:pluginZip` →
`build/distributions/hyperglow-ai-translation-words-plugin.zip` (manifest.json + classes.dex),
then install that ZIP from the host's plugin manager (same entry as the sibling plugin), enable
the switch and fill in the API key and model. For per-word highlighting the host must also
enable the word-level effect for the auxiliary (translation) text row.

## 6. Cache

Per-song results in the host's persistent cache (30-day TTL, 200-entry cap), exposed via
`PluginCacheExtension` (`ai_translation_words_songs`). Key = SHA-256 of track identity + target +
model + base URL + prompt + sampling params + word-timing flag; entries store
text → translation (+ fragments, matched by line text). Cache hits are **per line** and the stored
payload is the **union** of everything translated so far (since 1.0.2), so a long song fills in
across runs; for word-timed lines a cached fragment set must match the current token count. Only
successful results are cached; when a whole run resolves nothing the plugin backs off in memory for
**2 minutes** (shortened from 10 in 1.0.2 — a track cancelled by a song switch lands on the same
path, and a long back-off makes that song look like the plugin is not working).

## 7. Verification status

- unit-tested: language detection, target normalization, skip rules, prompt assembly, payload
  structure (tokens only on word-timed lines), response parsing (map/array/wrapped/fenced/
  garbage, fragment-count mismatch and non-string elements degrading to translation-only,
  fragment concatenation as the authoritative translation, edge normalization), word-timing
  detection and fragment alignment (length mismatch, empty fragments, invalid windows,
  whole-line fallback, identical-window merge, clamping), request body (max_tokens=0 omitted,
  no response_format), response extraction, cache round-trip / fragment payload / metadata /
  deletion / TTL / corrupted payloads. CI runs `:plugins:ai-translation-words:test` in the
  `publish-plugins` job.
- **Real endpoint: not verified** — an end-to-end call needs the user's own key and was not made
  during development; try a short lyric first.
- **Device (1.0.1, 2026-10-07)**: the plugin loads and runs on a real device (`translated 54
  line(s)`, `accepted result ... changedLyricFields=[TRANSLATION]`), which also exposed the two
  defects fixed in 1.0.2 (one oversized request blew the per-run budget; the 10-minute back-off
  also caught song-switch cancellations). Word-level output still needs a track whose source
  carries per-word timing (the track played then was line-level: `0 with word timing`).

## 8. Known limitations

Word-level output requires a source that carries per-word timing (Spicy, the amll-ttml plugin,
lyricfetch's NetEase word-level results, Lyricon tracks with YRC); line-level sources (LyricInfo /
SuperLyric) yield a line translation only — the log then shows `(0 with word timing)`. The
"skip languages" setting skips whole songs by design (logged at info level since 1.0.2, it used to
be debug-only and therefore invisible). No model search (contract UI limitation); heuristic
language detection (kanji-only Japanese classifies as Chinese); alignment follows source-word
windows, so the visual result depends on the model's segmentation; translation quality depends on
the chosen model and prompt.

## 9. Credits & licenses

No bundled third-party libraries (JDK `java.net` HTTP, platform `org.json`). Protocol semantics
aligned with the official HyperLyric translation plugin (GPL-3.0, author lidesheng); prompts and
implementation independently written (the fragment protocol is original to this plugin). Default
endpoint/model are the Xiaomi MiMo sample; lyric text goes only to the user's own configured
service.
