package com.eza.hyperglow.producer

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Which lyrics source feeds the projection pipeline. Persisted in user preferences and
 * selected at runtime by [LyricProducerArbiter.setPreference].
 *
 * Adding a new producer requires (a) implementing [LyricProducer] and (b) registering it
 * with the arbiter. The `spotify:track:` URI constraint stays internal to the Spicy producer
 * and MUST NOT be re-imposed at this boundary.
 */
enum class LyricSource { SPICY, LYRICON, SUPERLYRIC, LYRICINFO }

/**
 * Connection state of a [LyricProducer]. The arbiter uses this to decide when to fall back.
 *
 * - [CONNECTED] / [RECONNECTED]: producer may emit non-stale [LyricProducerState].
 * - [DISCONNECTED] / [CONNECT_TIMEOUT]: arbiter clears `active` and MAY fall back.
 *
 * Reuses the lyricon subscriber SDK's `ConnectionListener` vocabulary so the lyricon producer
 * can forward its callbacks 1:1 (Phase 3).
 */
enum class ProducerConnection { CONNECTED, RECONNECTED, DISCONNECTED, CONNECT_TIMEOUT }

/**
 * One karaoke word inside the active lyric line, normalized at the producer boundary.
 *
 * Mirrors the fields [com.eza.hyperglow.bridge.SpicyBridgeWord] carries on the Spicy path and
 * the per-word timing [io.github.proify.lyricon.lyric.model.LyricWord] carries on the lyricon
 * path. Producers MUST map their ingress word model into this type before emitting state.
 */
data class LyricWord(
    val text: String,
    val romanized: String,
    val startMs: Long,
    val endMs: Long,
    val boundaryAfter: Boolean,
    val sourceStart: Int = -1,
    val sourceEnd: Int = -1
)

/**
 * Render modes for the current state. The Spicy producer fills these from the Spicy EX
 * payload; the lyricon producer fills them from [com.eza.hyperglow.aod.AodRenderPreferences] /
 * [com.eza.hyperglow.customization.CustomizationRepository], because the lyricon `Song` model
 * carries no render-mode fields.
 */
data class ProducerRenderModes(
    val weight: String,
    val textSize: String,
    val textSizeCustom: Int,
    val secondary: String,
    val animation: String,
    val glow: String,
    val lineSyncFill: String,
    val overflow: String,
    val transition: String,
    val font: String
)

/**
 * Timing granularity of the lyrics the producer is currently emitting, used by projection to
 * pick the right render path (mirrors the Spicy document `type` + the `unsynced`/`no_lyrics`
 * distinctions the engine historically derived from `SpicyBridgeDocument`):
 *
 * - [NONE]: no lyrics data at all (no document / no song lyrics). The engine MAY fall back to
 *   the producer's `line` field for an untimed one-liner.
 * - [UNSYNCED]: lyrics data exists but is untimed (plain-text document). Engine renders "🎶".
 * - [LINE]: timed, line-level lyrics. Active row has no per-word timing.
 * - [SYLLABLE]: timed, word/syllable-level lyrics. Active row carries per-word timing in `words`.
 *
 * The lyricon producer never emits [UNSYNCED] (plain-text lyrics are ignored in `onReceiveText`);
 * it emits [LINE]/[SYLLABLE] when a song with timed lyrics is loaded, [NONE] otherwise.
 */
enum class LyricKind { NONE, UNSYNCED, LINE, SYLLABLE }

/**
 * One ruby (furigana) annotation on the active lyric line. Mirrors
 * [com.eza.hyperglow.bridge.SpicyBridgeRuby]. Empty for the lyricon path (the lyricon `Song`
 * model carries no ruby annotations).
 */
data class LyricRuby(val start: Int, val end: Int, val reading: String)

/**
 * One layout group on the active lyric line. Mirrors
 * [com.eza.hyperglow.bridge.SpicyBridgeLayoutGroup]. Empty for the lyricon path.
 */
data class LyricLayoutGroup(
    val start: Int,
    val end: Int,
    val kind: String,
    val keepTogether: Boolean,
    val confidence: Double
)

/**
 * 对唱并发行(第二行)候选:与主行播放窗口重叠达到 [MIN_CONCURRENT_OVERLAP_MS] 的另一
 * 唱词行,由持有整首行表的生产者在发射前预计算(见 [selectDuetLineIndex],上游
 * amarinne/hyperglow 99ba119d4 同语义)。null = 当前没有并发行,或该源不持有整首行表
 * (SuperLyric 只推当前行,不产出并发行)。字段语义与主行对齐;lineEndMs 已按各源的
 * 行窗口钳制口径对齐(如 Spicy 取 min(fillEndMs, endMs))。并发行 v1 不携带
 * ruby/layoutGroups(三个接入源中只有 Spicy 可产,normalize 侧还要为文本 trim 重算
 * 区间;并入 v2 再评估)。
 */
data class LyricDuetLine(
    val text: String,
    val romanized: String = "",
    val translated: String = "",
    val alignedRight: Boolean = false,
    /**
     * 「识别对唱标记」开启时的分侧取值(标记作为演唱者身份兜底);关闭时用 [alignedRight]
     * (元数据身份版)。两套由生产者预计算,渲染侧按各面自己的开关选用,见
     * [LyricProducerState.alignedRightMarkers]。
     */
    val alignedRightMarkers: Boolean = false,
    val lineStartMs: Long = 0L,
    val lineEndMs: Long = 0L,
    val words: List<LyricWord> = emptyList()
)

/**
 * Producer-agnostic lyrics state consumed by [com.eza.hyperglow.aod.AodProjectionEngine].
 *
 * This is the single ingress-to-projection boundary. Producers MUST normalize their ingress
 * payload into this shape before emitting. Fields mirror [com.eza.hyperglow.bridge.SpicyBridgeState]
 * so the Spicy path wraps without loss; the lyricon path computes `line`/`lineIndex`/`words`
 * from the active [io.github.proify.lyricon.lyric.model.RichLyricLine] for the current
 * `positionMs` (read from the subscriber's `SharedMemory`).
 *
 * `words` is null when the producer has no per-word timing (line-level lyrics); non-null for
 * syllable/word-level karaoke.
 *
 * The active-row fields (`lyricKind`, `alignedRight`, `lineStartMs`, `lineEndMs`, `ruby`,
 * `layoutGroups`, `hasTimedLyrics`, `nextLineStartMs`) describe the line the producer has
 * selected for the current `positionMs`. Producers MUST compute the active line before emitting
 * (spec clause 6); projection MUST NOT re-select the line from a raw rows list. All row fields
 * carry defaults so a producer that only emits line-level state (e.g. the Spicy path until its
 * document coupling is migrated) still constructs a valid [LyricProducerState].
 *
 * `staleAfterMs` matches `SpicyBridgeStore.STALE_AFTER_MS = 3000ms` uniformly for both
 * producers (see the spec's uniform-staleness invariant).
 */
data class LyricProducerState(
    val producerId: String,
    val generation: Int,
    val sequence: Long,
    val status: String,
    val trackUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val imageId: String,
    val line: String,
    val romanizedLine: String,
    val translatedLine: String,
    val lineIndex: Int,
    val positionMs: Long,
    val durationMs: Long,
    val sampledAtElapsedMs: Long,
    val speed: Float,
    val playing: Boolean,
    val receivedAtElapsedMs: Long,
    val words: List<LyricWord>?,
    val renderModes: ProducerRenderModes,
    val lyricKind: LyricKind = LyricKind.NONE,
    /**
     * 对唱左右分侧的**元数据身份版**取值(源显式值 / 演唱者身份推导,不含行首标记兜底)。
     */
    val alignedRight: Boolean = false,
    /**
     * 对唱左右分侧的**标记识别版**取值:元数据身份缺失时以行首「（男）/（女）/（合）」标记兜底。
     * 生产者预计算两套,渲染侧按各面自己的「识别对唱标记」开关选用(见
     * [com.eza.hyperglow.customization.SurfaceProfile.duetMarkers]),从而实现息屏/锁屏按面独立。
     */
    val alignedRightMarkers: Boolean = false,
    val lineStartMs: Long = 0L,
    val lineEndMs: Long = 0L,
    val ruby: List<LyricRuby> = emptyList(),
    val layoutGroups: List<LyricLayoutGroup> = emptyList(),
    val hasTimedLyrics: Boolean = false,
    val nextLineStartMs: Long? = null,
    /** Text of the lyric line that follows the active line (blank when none). */
    val nextLine: String = "",
    /** 下一行歌词的罗马音/音标(blank when none);「显示第二行辅助文字」消费。 */
    val nextLineRomanized: String = "",
    /** 下一行歌词的翻译(blank when none);「显示第二行辅助文字」消费。 */
    val nextLineTranslated: String = "",
    /**
     * Document language tag (BCP-47-ish, e.g. "zh-Hant" / "ja"), blank when unknown.
     * 投影层用其检测「中文歌被错误标注日语假名注音」并拒绝显示 ruby/罗马音
     * （上游 8422d78）。来源:SpicyBridgeDocument.language;Lyricon 无此概念,保持空。
     */
    val language: String = "",
    /**
     * 对唱并发行候选(见 [LyricDuetLine]);是否显示由息屏「显示并发歌词(对唱)」开关
     * ([com.eza.hyperglow.customization.SurfaceProfile.duetConcurrent])在投影层决定,
     * 仅息屏面消费,锁屏恒 solo。
     */
    val duetLine: LyricDuetLine? = null,
    val staleAfterMs: Long = STALE_AFTER_MS
) {
    companion object {
        /**
         * Uniform staleness threshold for all producers. Mirrors
         * `SpicyBridgeStore.STALE_AFTER_MS`. The arbiter clears `active` when a state older
         * than this is still current.
         */
        const val STALE_AFTER_MS = 3_000L
    }
}

/**
 * A lyrics source feeding the projection pipeline through one normalized boundary.
 *
 * Contract (see `.archcore/lyricon-integration/lyric-producer-contract.spec.md`):
 * - Emits a nullable [LyricProducerState] via [state]; null means "no current state".
 * - Reports [connection] so the arbiter can fall back on disconnect/timeout.
 * - [start] / [stop] are idempotent and lifecycle-bound; called once by the arbiter.
 *
 * Implementations MUST NOT impose producer-specific constraints (e.g. `spotify:track:`) at
 * this interface — those stay internal to the producer.
 */
interface LyricProducer {
    val id: LyricSource
    val state: StateFlow<LyricProducerState?>
    val connection: StateFlow<ProducerConnection>

    fun start(context: Context)
    fun stop()

    /**
     * 整首歌歌词快照（插件处理链的整首输入，见 [LyricSongSnapshot]）；null = 该源没有
     * 整首数据（纯逐行源如 SuperLyric，由宿主侧 LineStreamAggregator 聚合代替）。
     * 快照的 producerId/generation/trackUri 必须与当前 [state] 的三元组一致。
     * 实现注意：本方法可能被高频路径间接调用，实现应廉价（读内存数组/缓存），
     * 复杂的解析必须在状态更新时完成。
     */
    fun fullSongSnapshot(): LyricSongSnapshot? = null

    /**
     * 其他生产者检测到 seek 时的跨源转发(见 [LyricProducers.notifyExternalSeek])。
     * 各生产者的位置源彼此独立(如 Lyricon 共享内存 vs LyricInfo MediaSession),一方先
     * 观测到拖动进度条时应让其余生产者立即跟手,而不是各等各的残值拒绝窗/冻结源恢复
     * (2026-09-28 真机实测可滞后十余秒)。默认无操作;有 seek 语义的实现覆写。
     */
    fun onExternalSeek(positionMs: Long) {}

    /**
     * 「重启歌词源」入口:强制重建本源的订阅/回调链路,用于回调链静默卡死时在不重启
     * 应用(=不重启 SystemUI/AOD)的前提下恢复。各源的重建方式不同:
     * - Lyricon:重建活动播放器订阅,SDK 会补发当前歌曲(与重启等效);
     * - SuperLyric:注销并重新注册 Binder 接收器,重新武装回调路径;
     * - LyricInfo:重新注册 MediaSession 会话监听并重新挑选活动会话。
     *
     * 由外部推送驱动、应用侧无可重建订阅的源(如 Spicy EX)保持默认无操作。
     * 默认实现为空;实现必须幂等,且不得向调用方抛异常(内部自行容错)。
     */
    fun restart() {}

    /**
     * 外部设置变更(文档保存/导入/重置)时刷新生产者侧派生缓存(如「歌词时间偏移」)。
     * 默认无操作;有派生缓存的实现覆写(见 [LyricProducers.onCustomizationChanged])。
     */
    fun onCustomizationChanged() {}
}
