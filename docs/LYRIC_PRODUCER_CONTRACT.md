# Lyric Producer Contract

# English / 英文

Status: implementation contract (app-process lyric ingress)

This document publishes the contract that governs how lyric sources feed the projection pipeline.
It is the public home of the contract the source tree cites as
`.archcore/lyricon-integration/lyric-producer-contract.spec.md`; that path is not part of this
repository, so until now the invariants below were visible only inside code comments.

Scope: this file owns the app-process lyric ingress — which producer may emit, when a state becomes
visible, and what projection is allowed to read. `docs/ARCHITECTURE.md` owns process and package
boundaries; `docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md` owns what the surfaces do with a validated
snapshot.

## Producers

`LyricProducers.start` registers exactly four producers, keyed by `LyricSource`. The enum order
below is load-bearing: it is the fallback order (invariant 3).

| `LyricSource` | Producer | Ingress | Per-word timing | Whole-track input |
|---|---|---|---|---|
| `SPICY` | `SpicyLyricProducer` | Spicy EX pushes versioned Binder state and document through `SpicyLyricBridgeService` | from the bridge document | Spicy document store |
| `LYRICON` | `LyriconLyricProducer` | Lyricon subscriber SDK (`SharedMemory` position) | per-word from the rich line | `fullSongSnapshot()` |
| `SUPERLYRIC` | `SuperLyricLyricProducer` | SuperLyric system-service Binder line push | when the pushed line carries words | none — host-side `LineStreamAggregator` |
| `LYRICINFO` | `LyricInfoLyricProducer` | LyricInfo notification `MediaMetadata.extras.lyricInfo` (elrc/lrc via `ElrcParser`) | from elrc word markers | `fullSongSnapshot()` |

A producer whose source is absent MUST NOT crash the host: `connection` stays `DISCONNECTED` and
`state` stays null, so the arbiter falls back (invariant 3). The `spotify:track:` constraint stays
internal to the Spicy path and MUST NOT be re-imposed at the `LyricProducer` interface.

## Invariants

1. **Single active producer.** The arbiter exposes exactly one `active` flow. At every emission it
   equals exactly one producer's current state, or null — never a mix. `activeSource` names the
   source actually in use: the selected one when healthy, the fallback it yielded to otherwise.
2. **Forward when healthy.** When the selected producer is `CONNECTED` or `RECONNECTED` and its
   state is present and not faulted, the arbiter forwards that state.
3. **Clear and fall back otherwise.** When the selected producer is `DISCONNECTED`,
   `CONNECT_TIMEOUT`, has no state, or its state is faulted, the arbiter clears `active` to null
   and MAY fall back to another connected, non-faulted producer. Fallback walks `LyricSource`
   entries in enum order (`SPICY`, `LYRICON`, `SUPERLYRIC`, `LYRICINFO`), skipping the selected
   source.
4. **Preference switch is immediate.** On a preference change the arbiter clears `active`
   immediately — the previous producer's state stops being emitted within one frame — and begins
   emitting the newly selected producer's state only after that producer reports `CONNECTED`. The
   choice is persisted in user preferences and restored on `start`.

Projection consumers MUST read `arbiter.active` and MUST NOT read `SpicyBridgeStore.state`
directly.

## Selection refinements

Two rules refine invariant 2. Both exist because a source that is healthy by the letter can still
be the wrong thing to show:

- **Word-timing preference.** If the selected source is usable but carries no word timing while
  another connected, non-stale source does, the arbiter forwards the word-timed source instead.
  Without this, a plain-LRC source selected over a karaoke source makes per-word animation appear
  and disappear at random. When several sources qualify, the earliest in enum order wins.
- **Frozen-state yield.** A paused state is deliberately not faulted (see below), so a source whose
  callback chain died while paused can hold the selection indefinitely. If the selected state is
  stale and another source is connected, non-stale, actually playing, and carries content (word
  timing or a non-blank line), the arbiter yields to it. The content requirement is what preserves
  pause retention: the arbiter never yields to an empty source that would blank the surface.

## Staleness and the fault predicate

- `LyricProducerState.staleAfterMs` defaults to `STALE_AFTER_MS = 3_000` for every producer:
  staleness is uniform across sources.
- `isStale(state)` is `now - state.receivedAtElapsedMs > state.staleAfterMs`, measured on one
  monotonic clock (`SystemClock.elapsedRealtime` in production, injected in tests).
- `isFaulted(state)` is `isStale(state) && state.playing`. **A paused state is never faulted.**
  Position streams go naturally silent while paused, so treating a frozen paused state as stale
  would clear the surface and hand it to a source with no lyrics.

The selector and the stale sweep MUST use the same predicate. They did not once: the sweep cleared a
frozen state that the selector still considered usable, and signature de-duplication then never
re-published it, dead-locking `active` at null with the placeholder stuck on screen (0.3.120,
recorded in `docs/REGRESSION.md`). `shouldPublishActive` is the fix — a cleared `active` is
re-published even when the signature is unchanged.

## Threading model

- The arbiter owns one `CoroutineScope(Dispatchers.Default + SupervisorJob())`.
- The arbitration loop re-evaluates every `ARBITRATION_TICK_MS = 100` ms. It re-reads `.value` each
  tick rather than `combine()`-ing flows, so the staleness check stays time-aware; `combine` would
  not re-emit merely because time passed.
- The stale sweep runs every `STALE_SWEEP_TICK_MS = 500` ms and clears `active` when the currently
  forwarded state becomes faulted between ticks.
- `active`, `activeSource`, and `preference` are `StateFlow`, so collectors are race-free and
  redundant emissions are de-duplicated.
- `setPreference` is thread-safe and may be called from the UI thread.
- Producers emit from whatever thread their ingress uses — Spicy and Lyricon callbacks, SuperLyric
  Binder threads, notification callbacks. `MutableStateFlow` is thread-safe, so emitting from a
  callback thread needs no extra synchronization.
- `LyricProducers` publishes the arbiter through a `@Volatile` instance; consumers must call
  `start(context)` first.
- `start` and `stop` are idempotent. `restartAll()` rebuilds **every registered producer's**
  subscription (all four sources) and deliberately keeps the current `active`, so the surface does
  not blank while the source recovers. It covers all sources rather than only the selected one
  because the lyrics actually on screen may come from a fallback producer, and the default
  preference (`SPICY`) has no app-side subscription to rebuild. A source whose `restart()` throws
  must not stop the remaining sources; the arbiter isolates each call.

## State machine

Producer connection:

```text
DISCONNECTED ──connect──▶ CONNECTED ──drop──▶ DISCONNECTED
      ▲                       │                     │
      │                  reconnect                  │
      └───────────────────────┴──▶ RECONNECTED ─────┘
                   subscribe timeout ──▶ CONNECT_TIMEOUT
```

`CONNECTED` and `RECONNECTED` are equally usable; `DISCONNECTED` and `CONNECT_TIMEOUT` are both
unusable. The vocabulary is reused from the Lyricon subscriber SDK's `ConnectionListener` so that
producer can forward its callbacks 1:1.

Arbiter output per tick:

```text
selected producer connected && state != null && !isFaulted
    ├─ selected has word timing ──────────────────▶ forward selected
    ├─ selected has no word timing, and a word-timed
    │  source is connected and non-stale ─────────▶ forward the word-timed source
    ├─ selected is stale but not faulted (paused), and
    │  a fresher playing source has content ──────▶ forward that source
    └─ otherwise ─────────────────────────────────▶ forward selected
otherwise ────────────────────────────────────────▶ fallback in enum order, else null
```

## Clause index

Comments in the source cite numbered clauses of the private spec. Their meanings, resolved against
the implementation, are:

| Cited as | Meaning |
|---|---|
| clause 3 | clear `active` and fall back when the selected producer is disconnected or stale |
| clause 4 | a preference change stops the previous producer's state within one frame |
| clause 5 | producers normalize their ingress into `LyricProducerState` before emitting |
| clause 6 | producers compute the active line before emitting; projection must not re-select it |
| clause 8 | projection must not re-select the active row from a raw rows list |
| clause 9 | the Spicy producer populates the active-row fields from `SpicyBridgeDocumentStore` |
| clause 30 | the engine's single ingress is `arbiter.active`; it reads no bridge store for projection |

Clause numbers are the private spec's and are not renumbered here — this document states behavior,
and the table only resolves the citations. All seven are satisfied by the current implementation;
`AodProjectionEngine` still calls `SpicyBridgeStore.expireIfStale()` on a background loop, but that
keeps the store's lifecycle intact and does not feed projection.

## Conformance

`LyricProducerArbiterTest` asserts the single-active-producer invariant with an injected clock and
`computeActiveOnce()`, covering selection, fallback, preference switch, and timeout. Producers are
additionally covered by `SpicyLyricProducerTest`, `LyriconLyricProducerTest`,
`SuperLyricNextLineTest`, and the `LyricInfo` payload and opening-filter tests.

Unit tests are necessary, not sufficient: anything that changes which source is visible on a
surface needs the hardware verification path described in `docs/REGRESSION.md` under
"Lyric source arbitration".

---

# 中文 / Chinese

状态：实现契约（app 进程内的歌词入口）

本文发布的是歌词源如何喂给投影管线的契约。源码中引用的
`.archcore/lyricon-integration/lyric-producer-contract.spec.md` 指向的就是这份契约；该路径不属于
本仓库，因此在此之前下列不变量只能从代码注释里读到。

范围：本文件负责 app 进程内的歌词入口——哪个生产者可以发射、一份状态何时可见、投影层允许读
什么。进程与包边界归 `docs/ARCHITECTURE.md`；surface 拿到经过校验的 snapshot 之后怎么做归
`docs/LOCKSCREEN_AOD_BEHAVIOR_SPEC.md`。

## 生产者

`LyricProducers.start` 恰好注册四个生产者，以 `LyricSource` 为键。下表的枚举顺序是承重的：它就
是回退顺序（不变量 3）。

| `LyricSource` | 生产者 | 入口 | 词级时间戳 | 整首输入 |
|---|---|---|---|---|
| `SPICY` | `SpicyLyricProducer` | Spicy EX 经 `SpicyLyricBridgeService` 推送带版本的 Binder 状态与文档 | 来自 bridge 文档 | Spicy 文档库 |
| `LYRICON` | `LyriconLyricProducer` | Lyricon 订阅 SDK（位置取自 `SharedMemory`） | 来自富行模型的逐词时序 | `fullSongSnapshot()` |
| `SUPERLYRIC` | `SuperLyricLyricProducer` | SuperLyric 系统服务 Binder 逐行推送 | 推送行自带词时才有 | 无——宿主侧 `LineStreamAggregator` |
| `LYRICINFO` | `LyricInfoLyricProducer` | LyricInfo 通知 `MediaMetadata.extras.lyricInfo`（elrc/lrc，经 `ElrcParser`） | 来自 elrc 词标记 | `fullSongSnapshot()` |

源不存在时生产者绝不能让宿主崩溃：`connection` 保持 `DISCONNECTED`、`state` 保持 null，由仲裁者
自动回退（不变量 3）。`spotify:track:` 约束停留在 Spicy 路径内部，不得在 `LyricProducer` 接口上
重新施加。

## 不变量

1. **单一活动生产者。** 仲裁者只暴露一条 `active` flow。每一次发射它都恰好等于某一个生产者的当前
   状态，或为 null——绝不含混。`activeSource` 指明实际在用的源：健康时是被选中的那个，让位时是
   实际接管的那一个。
2. **健康即转发。** 被选中的生产者处于 `CONNECTED` 或 `RECONNECTED`，且状态存在、未被判故障时，
   仲裁者转发该状态。
3. **否则清空并回退。** 被选中的生产者处于 `DISCONNECTED`、`CONNECT_TIMEOUT`、无状态，或状态被
   判故障时，仲裁者把 `active` 清为 null，并可回退到另一个已连接且未故障的生产者。回退按
   `LyricSource` 的枚举顺序（`SPICY`、`LYRICON`、`SUPERLYRIC`、`LYRICINFO`）遍历，跳过被选中的源。
4. **换源立即生效。** 偏好变化时仲裁者立即清空 `active`——上一个生产者的状态在一帧内停止发射
   ——并且只有在新选中的生产者报告 `CONNECTED` 之后才开始发射它的状态。选择会持久化到用户偏好，
   并在 `start` 时恢复。

投影消费者必须读 `arbiter.active`，不得直接读 `SpicyBridgeStore.state`。

## 选择细化

有两条规则对不变量 2 做细化。它们存在的原因相同：一个字面上健康的源，仍可能不是该显示的东西。

- **词级时间戳优先。** 被选中的源可用但不带词级时间戳，而另一个已连接、非 stale 的源带词级时间
  戳时，仲裁者改转发带词级数据的那一个。没有这条规则时，把普通 LRC 源选在卡拉OK 源之上，会让逐
  字动画时有时无。多个源同时命中时，按枚举顺序取最早的一个。
- **冻结态让位。** 暂停状态被有意排除在「故障」之外（见下），因此一个在暂停期间回调链死亡的源可
  以长期霸占选中位。若被选中状态已 stale，而另一个源已连接、非 stale、确实在播且带内容（词级时
  间戳或非空歌词行），仲裁者让位给它。「带内容」这一门槛正是暂停保留语义的护栏：仲裁者绝不向一个
  会把 surface 清空的空源让位。

## Staleness 与故障谓词

- `LyricProducerState.staleAfterMs` 对所有生产者都默认为 `STALE_AFTER_MS = 3_000`：staleness 在
  各源之间是统一的。
- `isStale(state)` 即 `now - state.receivedAtElapsedMs > state.staleAfterMs`，在同一只单调时钟上
  测量（生产环境用 `SystemClock.elapsedRealtime`，测试注入）。
- `isFaulted(state)` 即 `isStale(state) && state.playing`。**暂停状态永不判故障。** 位置流在暂停
  时天然静默，把冻结的暂停状态当作 stale 会清空 surface，并把它交给一个没有歌词的源。

选源方与 stale sweep 必须使用同一谓词。曾经不是：sweep 清掉了一个选源方仍视为可用的冻结态，而签
名去重让它永远不再被发布，`active` 死锁在 null、占位文案常驻（0.3.120，记录在
`docs/REGRESSION.md`）。`shouldPublishActive` 就是修复——即使签名未变，被清空的 `active` 也必须
补发。

## 线程模型

- 仲裁者持有一个 `CoroutineScope(Dispatchers.Default + SupervisorJob())`。
- 仲裁循环每 `ARBITRATION_TICK_MS = 100` 毫秒重新评估一次。它每 tick 重读 `.value`，而不是
  `combine()` 各条 flow，以保持 staleness 判定与时间相关；`combine` 不会仅因时间流逝而重新发射。
- stale sweep 每 `STALE_SWEEP_TICK_MS = 500` 毫秒跑一次，在两次 tick 之间发现当前转发的状态变成
  故障时清空 `active`。
- `active`、`activeSource`、`preference` 都是 `StateFlow`，因此收集端无竞态，重复发射会被去重。
- `setPreference` 线程安全，可以从 UI 线程调用。
- 生产者从各自入口的线程发射——Spicy 与 Lyricon 的回调、SuperLyric 的 Binder 线程、通知回调。
  `MutableStateFlow` 线程安全，因此从回调线程发射无需额外同步。
- `LyricProducers` 通过 `@Volatile` 实例发布仲裁者；消费者必须先调用 `start(context)`。
- `start` 与 `stop` 幂等。`restartAll()` 重建**全部已注册生产者**的订阅（四个源），并有意保留当前
  `active`，避免恢复期间画面闪空。之所以不是只重建被选中源：屏上歌词可能来自回退生产者，而默认首选
  `SPICY` 应用侧无可重建的订阅。某个源的 `restart()` 抛异常不得影响其余源，仲裁器逐源隔离。

## 状态机

生产者连接状态：

```text
DISCONNECTED ──connect──▶ CONNECTED ──drop──▶ DISCONNECTED
      ▲                       │                     │
      │                  reconnect                  │
      └───────────────────────┴──▶ RECONNECTED ─────┘
                   订阅超时 ──▶ CONNECT_TIMEOUT
```

`CONNECTED` 与 `RECONNECTED` 同等可用；`DISCONNECTED` 与 `CONNECT_TIMEOUT` 都不可用。该词表复用
自 Lyricon 订阅 SDK 的 `ConnectionListener`，使该源可以把回调 1:1 转发。

仲裁者每 tick 的输出：

```text
被选中生产者已连接 && state != null && !isFaulted
    ├─ 被选中源带词级时间戳 ─────────────────────▶ 转发被选中源
    ├─ 被选中源无词级时间戳，且存在已连接、
    │  非 stale 的带词级源 ──────────────────────▶ 转发带词级时间戳的源
    ├─ 被选中源 stale 但未判故障（暂停），且存在
    │  更新的、在播且带内容的源 ─────────────────▶ 转发该源
    └─ 其他情况 ─────────────────────────────────▶ 转发被选中源
否则 ────────────────────────────────────────────▶ 按枚举顺序回退，否则 null
```

## 条款索引

源码注释引用的是私有规范的编号条款。对照实现解析后的含义如下：

| 引用为 | 含义 |
|---|---|
| clause 3 | 被选中生产者断开或 stale 时清空 `active` 并回退 |
| clause 4 | 偏好变化时上一个生产者的状态在一帧内停止发射 |
| clause 5 | 生产者在发射前把入口数据归一化为 `LyricProducerState` |
| clause 6 | 生产者在发射前算出活动行；投影不得重新选择 |
| clause 8 | 投影不得从原始行表重新选择活动行 |
| clause 9 | Spicy 生产者从 `SpicyBridgeDocumentStore` 填充活动行字段 |
| clause 30 | 引擎的唯一入口是 `arbiter.active`；投影不读任何 bridge store |

条款编号属于私有规范，本文不重新编号——本文陈述行为，该表只用于解释引用。七条在当前实现中均已
满足；`AodProjectionEngine` 仍会在后台循环里调用 `SpicyBridgeStore.expireIfStale()`，但那只是维持
该 store 的生命周期，并不喂给投影。

## 符合性

`LyricProducerArbiterTest` 以注入时钟和 `computeActiveOnce()` 断言单一活动生产者不变量，覆盖选择、
回退、换源与超时。生产者另有 `SpicyLyricProducerTest`、`LyriconLyricProducerTest`、
`SuperLyricNextLineTest` 以及 LyricInfo 的 payload 与片头过滤测试覆盖。

单测通过是必要非充分条件：任何改变「哪个源在 surface 上可见」的改动，都需要走
`docs/REGRESSION.md` 中「歌词源仲裁」一节所述的真机验证路径。
