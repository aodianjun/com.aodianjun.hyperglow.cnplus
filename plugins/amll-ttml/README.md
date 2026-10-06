# HyperGlow AMLL TTML 插件 / HyperGlow AMLL TTML Plugin

# 中文 / Chinese

状态：可安装的 HyperLyric 兼容插件（打包与发布遵循 `docs/PLUGIN_RELEASE_CONVENTIONS.md`）。

## 1. 插件做什么、位于管线何处

从 [AMLL TTML DataBase](https://github.com/amll-dev/amll-ttml-db)（官方 API：`https://api.amll.dev`）
获取高质量 **TTML 逐字歌词**：逐字时间轴、内嵌翻译、音译（罗马音）、和声（背景人声）行、
**对唱左右分侧**（TTML `ttm:agent` 语义）。命中时整表替换当前歌词；未命中保持原歌词源。

- **阶段**：`LYRIC_REPLACEMENT`（先于翻译增强类插件）；
- **更新模式**：`REPLACE`（整表替换）；
- **变更声明**：`changedLyricFields = TEXT`，并按实际内容追加 `WORDS` / `TRANSLATION` / `ROMA`；
- **调度**：宿主按会话调度插件链（逐行源每 15s 重跑），取词结果必须缓存——见 §5。

## 2. 写入与不写入的字段

写入（`PluginLyricLine`）：

| 字段 | 来源 |
|---|---|
| `begin` / `end` / `duration` | TTML `<p>` 时间轴（钳制非负、按 begin 排序） |
| `text` | 主行音节拼接 |
| `words` | TTML `<span>` 逐字音节（begin/end/text） |
| `translation` | TTML 内嵌翻译（`x-translation` / iTunes translation 元数据） |
| `roma` | TTML 音译（`x-roman` / transliteration 元数据） |
| `isAlignedRight` | 对唱分侧：`ttm:agent` 推导的 alignment == End（可开关） |
| `metadata["role"]` | `LEAD`（主行）/ `BG`（和声行）——与 Spicy 文档桥、lyricfetch 同一约定 |

刻意**不写入**：

- `secondary` / `secondaryWords`：和声行以**独立 BG 行**表达，不占用辅助文字字段——
  `secondary` 在宿主侧是「辅助文字（转写/翻译）」的专属语义，混用会让和声出现在辅助行；
- 歌曲级字段（`name`/`artist`/`album`/`duration`）：插件只提供歌词，不修正元数据；
- `translationWords`：TTML 翻译是行级文本，无词级时间轴可映射。

## 3. 在线来源与实测状态

- **数据源**：AMLL TTML DataBase，经官方 API 服务 [amll-ttml-api](https://github.com/amll-dev/amll-ttml-api)
  （`GET /v1/lyrics/search`、`GET /v1/lyrics/get`，契约见官方
  [OpenAPI 规范](https://amll.dev/api/ttml/openapi.yaml)）。
- **实测（2026-10）**：`search`/`get` 均 200；`get?ncmMusicId=2604527599` 返回 22 KB TTML
  （逐字 39 行）；限流为单 IP 每秒 50 次（插件远低于该频率，且全部结果缓存）。
- ⚠️ 官方 HyperLyric 插件（`hyperlyric.amll.ttml` 1.1.0，HyperLyric 7.3–7.5 时期）用的是**旧版
  参数名**（`title`/`artist`），当前 API 已不接受（实测 400 "Missing valid search parameters"）——
  旧插件对新歌只能靠旧缓存工作。本插件按现行契约实现，并补上上游内置版后来才有的能力：
  **歌词 API 地址可配**、**启用对唱表演**、和声/翻译开关。

## 4. 设置项

| 设置 | 说明 | 默认 |
|---|---|---|
| 启用 AMLL TTML 逐字歌词 | 总开关（activationSettingKey） | 关 |
| 歌词 API 地址 | API 服务地址（自建服务可改；非法值回退官方地址） | `https://api.amll.dev` |
| 启用对唱表演 | 对唱行左右分侧 | 开 |
| 使用在线翻译 | 挂载 TTML 内嵌翻译 | 开 |
| 保留和声行 | 和声（`x-bg`）行作为独立行输出 | 开 |
| 仅升级为逐字歌词 | 当前已是逐字歌词时不替换 | 开 |

## 5. 匹配与缓存管理

**标题变体搜索（1.0.1 起）**：AMLL 库检索近乎精确匹配，而播放器元数据的标题常带版本
后缀（如网易云显示「蝴蝶 (Cocoon Broken)」）。取词时按序尝试三个标题变体，任一命中即停：

1. **原文**（trim 后）——库里存了带后缀/带标点的版本时，原文才能区分 Live/Remastered；
2. **仅剥括号内容与 feat. 从句**（保留空格与标点，如 `Orchelia's vox (feat. …)` →
   `Orchelia's vox`）——覆盖「库里是带空格标点的拉丁标题、播放器多一个括号后缀」的形态；
3. **全量规范化**（仅字母数字与 CJK）——覆盖「库里也是去标点形态」。

全部变体无命中才写入未命中缓存；搜索或取词的**传输失败**不写缓存（瞬时故障不再需要
用户手动删缓存条目，网络恢复后自动重试；请求成功但库中该条不可用仍照旧写未命中）。

取词结果（命中与未命中）按曲目缓存在宿主持久缓存（`PluginCache`，7 天 TTL、上限 100 条），
并通过 `PluginCacheExtension`（scope id `amll_ttml_songs`，与 manifest 一致）暴露给宿主
「歌词增强 → 缓存管理」页：可列出、删除单条、清空。**缓存键为规范化标题**（与上面第 3 个
变体同源），因此同一首歌的不同标题写法（带/不带括号后缀）**共享同一条结果**，负缓存同样
只按曲目身份记录、不会只毒化其中一种写法。**缓存格式已升到 v2**：设备上旧 v1 条目解码
失败即视为未命中并自动从缓存页剔除（正文一并删除），下次播放自动重新取词，无需手动
清缓存；删除单条仍可随时手动强制重取词（内存副本同步失效）。缓存键只含曲目身份 + API
地址，渲染开关（对唱/翻译/和声）切换即时生效、不重新联网。

## 6. 验证情况

- **单元测试**（证据：unit-tested）：`src/test` 覆盖真实样本解析（蝴蝶 TTML：39 行映射、
  首行 11 词、全 LEAD、时间轴非负）、对唱合成样本（agent 翻转：v1 左 → v2 右 → 回 v1 左；
  开关关闭全不分侧）、和声/翻译合成样本（独立 BG 行、翻译挂载与开关）、匹配打分（精确 1.0 /
  翻唱硬否决 / 阈值）、响应解析（真实响应形状）、API 地址规范化与 URL 编码；1.0.1 起新增
  **标题变体钉子**（原文/剥括号/全量规范化三态与去重）、**Processor 级回归**（脚本化假
  client：原文 0 结果 → 剥括号变体命中 → REPLACE+WORDS，且两次查询 musicName 不同；
  搜索/取词传输失败不写负缓存）、**缓存格式升级**（v1 记录 get 未命中、缓存页剔除并删正文）。
  本地以 kotlin-compiler-embeddable 编译真实源码跑通全部用例（31/31）；CI 侧
  `publish-plugins` job 运行 `:plugins:amll-ttml:test`。
- **真实接口**（证据：real-api）：`api.amll.dev` 的 search/get 端点按 §3 实测。
- **真机**：**未验证**——宿主渲染行为（对唱分侧、和声行、缓存页交互）待安装 ZIP 后冒烟。

## 7. 已知限制

- 搜索无时长维度：DataBase 检索结果不含时长，同名翻唱/伴奏靠**艺人硬否决**区分
  （标题归一化后相等但艺人无交集 → 拒绝），可能漏选（宁缺毋滥）。
- 平台 ID 精确探测不可用：宿主 `PluginMediaInfo` 不提供网易云/QQ 等平台 ID，只能按
  歌名 + 艺人模糊搜索后校验（上游内置版的「歌曲 ID 平台探测」依赖其 hook 侧元数据）。
- 与官方插件的缓存不共享（键格式不同）：从官方插件迁移后首次播放需重新联网取词。
- 库中行级（非逐字）老条目在「仅升级为逐字歌词」开启时不采用（关闭该开关即采用）。
- 和声行不参与对唱分侧（分侧由主行表达）。

## 8. 致谢与许可

- [AMLL TTML DataBase](https://github.com/amll-dev/amll-ttml-db) / [amll-ttml-api](https://github.com/amll-dev/amll-ttml-api)
  （MIT / Apache-2.0 双许可，amll-dev）——歌词数据与 API 服务；歌词数据版权归各平台与权利人
  所有，本插件只读取公开接口、不绕过任何鉴权或付费墙。
- [accompanist-lyrics-core](https://github.com/6xingyv/accompanist-lyrics-core)（Apache-2.0，
  作者 6xingyv）——TTML 解析内核（`TTMLParser`），**随插件打进 dex**。
- 匹配打分（标题/艺人归一化与硬否决）与缓存索引模式与同仓库 `plugins/lyricfetch` 同源
  （同仓库代码复用）；其打分语义又对齐 [Lyricify-Lyrics-Helper](https://github.com/WXRIW/Lyricify-Lyrics-Helper)
  （Apache-2.0）的 CompareHelper，仅移植行为与参数、未复制代码。

---

# English / English

Status: installable HyperLyric-compatible plugin (packaging and release follow
`docs/PLUGIN_RELEASE_CONVENTIONS.md`).

## 1. What it does, where it sits in the pipeline

Fetches high-quality **word-level TTML lyrics** from the
[AMLL TTML DataBase](https://github.com/amll-dev/amll-ttml-db) (official API: `https://api.amll.dev`):
per-syllable timing, embedded translations, romanization, background-vocal (`x-bg`) rows, and
**duet left/right alignment** (TTML `ttm:agent` semantics). Replaces the whole lyric table on a
hit; keeps the current source on a miss.

- **Stage**: `LYRIC_REPLACEMENT`; **update mode**: `REPLACE`;
- **Changed fields**: `TEXT` plus `WORDS` / `TRANSLATION` / `ROMA` as present;
- The host re-runs the plugin chain per session (line-stream sources every 15 s), so lookups are
  cached — see §5.

## 2. Fields written / deliberately not written

Written: `begin`/`end`/`duration`, `text`, `words` (per-syllable), `translation`, `roma`,
`isAlignedRight` (duet, switchable), `metadata["role"]` = `LEAD`/`BG`.

Deliberately not written: `secondary`/`secondaryWords` (background vocals are emitted as separate
BG rows — `secondary` carries the host's auxiliary-text semantics); song-level metadata; and
`translationWords` (TTML translations are line-level text with no word timing).

## 3. Sources and verified status

AMLL TTML DataBase via its official [amll-ttml-api](https://github.com/amll-dev/amll-ttml-api)
(`/v1/lyrics/search`, `/v1/lyrics/get`; [OpenAPI](https://amll.dev/api/ttml/openapi.yaml)).
Verified 2026-10: both endpoints return 200; `get?ncmMusicId=2604527599` returns 22 KB of TTML.
Note: the official HyperLyric plugin (1.1.0) uses legacy parameter names (`title`/`artist`) that
the current API rejects (400); this plugin implements the current contract and adds the
capabilities the in-tree version gained later (configurable API base URL, duet performance,
background/translation switches).

## 4. Settings

See the Chinese section's table (master switch, API base URL, duet, translation, background
vocals, upgrade-only).

## 5. Matching and cache

**Title-variant search (since 1.0.1)**: the AMLL database search is near-exact, while player
metadata titles often carry a version suffix (e.g. "蝴蝶 (Cocoon Broken)"). Lookup tries three
title variants in order and stops at the first hit:

1. **Raw** (trimmed) — only the raw title distinguishes Live/Remastered when the database stores
   the suffixed form;
2. **Brackets/feat. stripped** (spaces and punctuation kept: `Orchelia's vox (feat. …)` →
   `Orchelia's vox`) — for Latin titles stored with spaces/punctuation;
3. **Fully normalized** (letters/digits/CJK only) — for titles stored without punctuation.

A miss is cached only when every variant missed. A **transport failure** (search or fetch)
never writes a cache entry — a transient blip no longer needs a manual cache delete; a
successful response whose entry is unusable still caches a miss as before.

Lookups (hits and misses) are cached per song in the host's persistent cache (7-day TTL,
100-entry cap) and exposed via `PluginCacheExtension` (scope id `amll_ttml_songs`) to the host's
cache page — list / delete one / clear all. The **cache key is the normalized title** (same
normalization as variant 3), so different spellings of the same song (with/without a bracketed
suffix) **share one entry**, and a negative cache entry is keyed by track identity too. The
**cache format is now v2**: legacy v1 entries on device fail to decode, count as misses, and are
pruned from the cache page (payload removed) — the next play refetches automatically, no manual
clearing needed. Deleting one entry still forces a manual refetch (the in-memory copy is
invalidated too). The key contains only the track identity plus the API base URL, so render
switches apply instantly without refetching.

## 6. Verification status

- unit-tested: `src/test` covers real-sample parsing (39-row butterfly TTML), synthetic duet
  samples (agent flip left→right→left; switch off), background/translation samples, match scoring
  (exact / cover veto / threshold), response parsing, URL building; since 1.0.1 also title-variant
  pins (raw / stripped / normalized, deduped), a processor-level regression with a scripted fake
  client (raw search empty → stripped variant hits → REPLACE+WORDS, two distinct musicName
  queries; transport failures write no cache entry), and cache-format upgrade (v1 records read as
  misses and are pruned from the cache page). Run locally against the real sources via
  kotlin-compiler-embeddable (31/31); CI runs `:plugins:amll-ttml:test` in the `publish-plugins`
  job.
- real-api: endpoints verified as in §3.
- **Device: not verified** — host-rendered behaviour (duet sides, background rows, cache page)
  awaits a device smoke test.

## 7. Known limitations

No duration dimension in search (artist hard-veto separates covers); platform-ID probing is
unavailable (host `PluginMediaInfo` carries no platform IDs); cache is not shared with the
official plugin; line-level (non-word) database entries are skipped while "upgrade only" is on;
background rows never carry duet alignment.

## 8. Credits & licenses

See the Chinese section — AMLL TTML DataBase / amll-ttml-api (MIT / Apache-2.0; data belongs to
the platforms and rights holders; public endpoints only), accompanist-lyrics-core (Apache-2.0,
bundled into the dex), and match/cache patterns shared with `plugins/lyricfetch` in this
repository.
