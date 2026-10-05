# HyperGlow AI 翻译插件 / HyperGlow AI Translation Plugin

# 中文 / Chinese

状态：可安装的 HyperLyric 兼容插件（打包与发布遵循 `docs/PLUGIN_RELEASE_CONVENTIONS.md`）。

## 1. 插件做什么、位于管线何处

把当前歌词送给**用户自己配置的 OpenAI 兼容接口**（`chat/completions`）翻译成目标语言，
按行回写 `translation`。

- **阶段**：`TRANSLATION_ENHANCEMENT`；
- **更新模式**：`PATCH`（行数不变，只声明 `TRANSLATION`）；
- **调度**：宿主按会话调度插件链（逐行源每 15s 重跑），结果按曲目缓存——见 §5。

与官方插件（`hyperlyric.ai.translation` 1.0.0，HyperLyric 7.3–7.5 时期）的关系：官方插件随
7.6「移除插件系统」下线、功能收回内置；上游内置版后续修掉了「自动跳过语言为空时不生效」的
问题。本插件为**独立重写**（输入输出协议语义对齐，提示词为独立撰写），空集合=不过滤的语义
天然正确。**「模型搜索」不在本插件内**：它需要「按钮 → 异步拉取模型列表 → 选择」的宿主 UI，
而插件契约未暴露对应控件（见 §7）。

## 2. 写入与不写入的字段

写入（仅一项）：

| 字段 | 来源 |
|---|---|
| `translation` | 接口返回的该行译文（按输入 index 对应） |

刻意**不写入**：

- `text` / `words` / `roma` / `metadata`：翻译不改歌词本身（PATCH 模式，行数与其它字段原样保留）；
- `translationWords`：接口输出是行级译文，没有词级时间轴可映射；
- 不新增或删除行：输入与输出严格按行 index 对应，宿主 diff 只看到 `translation` 变化。

## 3. 数据流与来源

- 插件**只向用户自己配置的 API 地址**发送请求（默认示例端点 `https://api.xiaomimimo.com/v1/`，
  模型默认 `mimo-v2.5`；地址、模型、Key 均可改）。发送内容是：歌曲名/艺术家（语境）、
  目标语言、以及**待翻译行的歌词文本**；**API Key 仅作为请求头发往该地址**。
- 插件自身不连接任何其他第三方服务，不做遥测。
- 请求体不含 `response_format`（很多第三方兼容端点不支持它；JSON 输出由提示词协议约束，
  解析器对常见偏差——Markdown 围栏、数组形式、外层包裹——做了容错）。

## 4. 设置项

| 设置 | 说明 | 默认 |
|---|---|---|
| OpenAI 翻译 | 总开关（activationSettingKey） | 关 |
| 自动跳过语言 | 识别为勾选语言的歌词整首不翻译（中文/English/日本語/한국어/Español）；未选择=关闭 | 未选择 |
| 自动跳过已有翻译歌曲 | 当前歌词已有翻译时整首跳过（与「强制 AI 翻译」互斥） | 关 |
| 强制 AI 翻译 | 无视「已有翻译」与既有译文，重新翻译所有待翻译行（与上一项互斥） | 关 |
| 目标语言 | 自由文本（如「中文」「English」）；可识别时启用「行已是目标语言则跳过」 | 中文 |
| API Key | 只随请求头发往所配置地址；**不参与备份导出**（backup=false） | 未配置 |
| 模型名称 | 手填模型名（模型搜索见 §7） | mimo-v2.5 |
| OpenAI API 地址 | 兼容 `/chat/completions` 的地址（可自建网关） | https://api.xiaomimimo.com/v1/ |
| 自定义提示词 | 只影响译文风格，不得覆盖核心 JSON/index 协议 | 信雅达风格简述 |
| temperature / top_p / max_tokens | 采样参数（高级设置）；max_tokens=0 表示不传（不限） | 1.0 / 1.0 / 0 |

## 5. 缓存管理

翻译结果按曲目缓存在宿主持久缓存（`PluginCache`，**30 天 TTL、上限 200 条**），并通过
`PluginCacheExtension`（scope id `ai_translation_songs`，与 manifest 一致）暴露给宿主
「歌词增强 → 缓存管理」页：可列出、删除单条、清空。**删除单条即让该曲下次重新翻译。**

- 缓存键 = 曲目身份 + 目标语言 + 模型 + API 地址 + 提示词 + temperature/top_p 的 SHA-256；
- 条目存「行文本 → 译文」配对，回填按**行文本精确匹配**（歌词源换版本/行序变化也稳）；
- **只缓存成功结果**：请求失败（限流/超时）不写缓存，由内存失败节流退避（同曲目+配置
  10 分钟内不重试），避免逐行源的 15s 链调度反复打网络。

## 6. 验证情况

- **单元测试**（证据：unit-tested）：`src/test` 覆盖语言检测（中/日/韩/英/西、括号注记剥离、
  纯符号）、目标语言归一化、行级「无需翻译」判断、提示词组装（协议在风格提示词之上且带
  不得覆盖声明）、user payload 结构、响应解析（index map / 数组 / 外层包裹 / 围栏 / 越界与
  垃圾输入）、请求体构造（max_tokens=0 不传）、响应提取、缓存（往返 / 条目元数据 / 删除
  失效内存副本 / 30 天 TTL / 损坏载荷按未命中）。本地以 kotlin-compiler-embeddable 编译
  真实源码 + JUnitCore 实跑全部断言；CI 侧 `publish-plugins` job 运行
  `:plugins:ai-translation:test`。
- **真实接口：未验证**——对真实 LLM 端点的端到端调用需要用户自己的 Key，未在开发侧发起
  （协议/请求体/解析已由单测覆盖，但**首次使用建议先小段歌词试翻**，确认端点与模型名可用）。
- **真机：未验证**——宿主渲染（翻译行显示、缓存页交互）待安装 ZIP 后冒烟。

## 7. 已知限制

- **无模型搜索**：插件契约的 UI 能力（manifest 声明 + 宿主渲染）没有「按钮触发异步列表」
  这一控件，模型名需手填（可在缓存页/日志确认识别结果）；
- 语言检测是**字符集启发式**：日文歌若纯汉字歌词会被归为中文；混合语言按占比归类；
- 「无意义衬词」（la la la 等）不单独过滤：短行/纯符号行已跳过，但衬词识别依赖词表，
  本插件不做（官方插件的同类过滤亦为启发式）；
- 一次请求发送**全部待翻译行**：超长歌词可能触及模型上下文/输出上限（受 max_tokens 与
  模型本身约束），未做分块；
- 翻译质量取决于所选模型与提示词；不同模型对 JSON 协议的遵守度不同（解析器已做容错，
  仍无法解析时按失败退避处理）。

## 8. 致谢与许可

- **无捆绑第三方库**：HTTP 用 JDK `java.net`，JSON 用平台 `org.json`（compileOnly，不进 dex）；
- 输入输出**协议语义**与官方 HyperLyric 翻译插件（`hyperlyric.ai.translation`，GPL-3.0，
  作者 lidesheng）对齐，**提示词与实现为独立撰写**（未复制其代码或提示词文本）；
- 默认 API 地址与模型为小米 MiMo 示例端点（用户可改为任意 OpenAI 兼容服务）；歌词文本
  仅发送至用户自己配置的服务，版权归各平台与权利人所有。

---

# English / English

Status: installable HyperLyric-compatible plugin (packaging and release follow
`docs/PLUGIN_RELEASE_CONVENTIONS.md`).

## 1. What it does, where it sits in the pipeline

Sends the current lyrics to the **user-configured OpenAI-compatible endpoint**
(`chat/completions`) and writes translations back per line.

- **Stage**: `TRANSLATION_ENHANCEMENT`; **update mode**: `PATCH` (only `TRANSLATION` declared);
- Related to the official plugin (`hyperlyric.ai.translation` 1.0.0): it went offline when the
  plugin system was removed in 7.6 and the feature moved in-tree; the in-tree version later fixed
  "empty skip-languages not taking effect". This is an **independent re-implementation** with
  protocol-compatible semantics (empty set = no filtering) and independently written prompts.
  **Model search is not included** (needs a host UI control the plugin contract does not expose).

## 2. Fields written / deliberately not written

Only `translation` is written. `text`/`words`/`roma`/`metadata` are untouched (PATCH mode),
no `translationWords` (line-level output has no word timing), and no rows are added or removed.

## 3. Data flow

Requests go **only to the API base URL the user configured** (default sample:
`https://api.xiaomimimo.com/v1/`, model `mimo-v2.5`). Payload: song title/artist, target
language, and the lyric lines to translate; the API key travels only as an Authorization header
to that address. No other third-party calls, no telemetry. `response_format` is not sent (many
compatible endpoints reject it); the JSON protocol is enforced by the prompt and the parser
tolerates common deviations (fences, array forms, wrapped arrays).

## 4. Settings

See the Chinese table: master switch, skip languages (multi-select, off by default), skip songs
with existing translation, force translation (mutually exclusive), target language, API key
(excluded from backups), model, base URL, custom prompt, temperature / top_p / max_tokens.

## 5. Cache

Per-song results in the host's persistent cache (30-day TTL, 200-entry cap), exposed via
`PluginCacheExtension` (`ai_translation_songs`). Key = SHA-256 of track identity + target +
model + base URL + prompt + sampling params; entries store text→translation pairs (matched by
line text). Only successful results are cached; failures back off in memory (10-minute cooldown).

## 6. Verification status

- unit-tested: language detection, target normalization, skip rules, prompt assembly, payload
  structure, response parsing (map/array/wrapped/fenced/garbage), request body (max_tokens=0
  omitted), response extraction, cache round-trip / metadata / deletion / TTL / corrupted
  payloads. Compiled locally against real sources and run via JUnitCore; CI runs
  `:plugins:ai-translation:test` in the `publish-plugins` job.
- **Real endpoint: not verified** — an end-to-end call needs the user's own key and was not made
  during development; try a short lyric first.
- **Device: not verified.**

## 7. Known limitations

No model search (contract UI limitation); heuristic language detection (kanji-only Japanese
classifies as Chinese); no filler-word filter; single request for all lines (no chunking);
translation quality depends on the chosen model and prompt.

## 8. Credits & licenses

No bundled third-party libraries (JDK `java.net` HTTP, platform `org.json`). Protocol semantics
aligned with the official HyperLyric translation plugin (GPL-3.0, author lidesheng);
prompts and implementation independently written. Default endpoint/model are the Xiaomi MiMo
sample; lyric text goes only to the user's own configured service.
