package com.eza.hyperglow.producer

import android.content.Context
import android.os.SystemClock
import com.eza.hyperglow.AppLog
import com.hchen.superlyricapi.ISuperLyricReceiver
import com.hchen.superlyricapi.SuperLyricCache
import com.hchen.superlyricapi.SuperLyricData
import com.hchen.superlyricapi.SuperLyricHelper
import com.hchen.superlyricapi.SuperLyricLine
import com.hchen.superlyricapi.SuperLyricWord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * [LyricProducer] backed by the SuperLyric Xposed module, consumed through its Binder API
 * ([com.hchen.superlyricapi.SuperLyricHelper]).
 *
 * The SuperLyric module (system-service level, "super_lyric") publishes lyrics via Binder.
 * This producer registers an [ISuperLyricReceiver] that receives each push
 * ([ISuperLyricReceiver.onLyric]) and maps it into a [LyricProducerState].
 *
 * SuperLyricApi 3.7 起服务端改为「全曲包」模型：发布端只在换歌/歌词首次解析时发一次带
 * `allLyrics` 的全量包，播放进度与行切换由服务端时钟负责（跨行时广播轻量增量包）。本生产者
 * 因此（旧版模块 ≤3.4 仍走逐行推送，所有新字段读取都带 hasXxx() 防护，行为逐字节不变）：
 * - 每笔推送先经 [SuperLyricCache.resolve] 合成（全量包入缓存 / 增量包补齐全曲视图 / 缓存
 *   缺失时向服务端自愈拉取），再选取活动行——**全量包的 `lyric` 是首行**（官方发布端约定：
 *   发布示例 `setLyric(allLines[0])`，Demo 注释「增量包为当前行，全量包为首行」），活动行必须
 *   按 `currentLyricIndex` 取，见 [selectActiveLine]；
 * - 持有全曲视图时经 [fullSongSnapshot] 直接提供整首快照，插件链直读，不再依赖宿主侧的逐行
 *   聚合（[com.eza.hyperglow.plugin.LineStreamAggregator] 自动降级为旧模块路径）；
 * - 行级 translation/secondary 映射 [LyricProducerState.translatedLine] /
 *   [LyricProducerState.romanizedLine]；`duration`/`position`/`lyricId` 映射歌长/播放点位/
 *   稳定会话标识；
 * - 注册成功与「重启歌词源」成功后主动 [SuperLyricHelper.getLatestLyric] 一次，冷启动立即
 *   出词而不必等下一次行切换（见 [maybePullLatestLyric]）。
 *
 * Contract (see `docs/LYRIC_PRODUCER_CONTRACT.md`):
 * - Requires the SuperLyric module active in the system service. Its absence MUST NOT crash
 *   HyperGlow: until the service is reachable, [connection] stays [ProducerConnection.DISCONNECTED]
 *   and [state] stays null, so the arbiter falls back automatically.
 * - [SuperLyricHelper.isAvailable] is polled periodically to track connection; any delivered
 *   [ISuperLyricReceiver.onLyric] additionally confirms a live connection.
 * - The `spotify:track:` constraint is NOT imposed here (it stays in SpicyBridgeStateReducer).
 *
 * Threading: Binder callbacks arrive on Binder threads; [MutableStateFlow] is thread-safe, so
 * emitting from any callback is safe. [fullSongSnapshot] 可能被管线协程调用，其缓存读写用锁保护。
 *
 * @param clock injectable monotonic clock (millis); defaults to [SystemClock.elapsedRealtime].
 *   Injected in unit tests so emissions run without Android's [SystemClock].
 */
class SuperLyricLyricProducer(
    private val clock: () -> Long = SystemClock::elapsedRealtime
) : LyricProducer {

    override val id: LyricSource = LyricSource.SUPERLYRIC

    private val mutableConnection = MutableStateFlow(ProducerConnection.DISCONNECTED)
    override val connection: StateFlow<ProducerConnection> = mutableConnection.asStateFlow()

    private val mutableState = MutableStateFlow<LyricProducerState?>(null)
    override val state: StateFlow<LyricProducerState?> = mutableState.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var started = false

    // Session/sequence for arbiter dedup (producerId:generation:sequence).
    private var generation: Int = 0
    private var sequence: Long = 0L

    // Monotonic time of the last delivered lyric (0 = never received / not playing). Used as a
    // heartbeat: if lyrics stop being delivered while we were previously receiving them, we force
    // re-register to recover the IPC callback path (guards against service-side silent drops).
    private var lastLyricElapsedMs: Long = 0L
    private var lastForceReRegisterElapsed: Long = 0L

    // 冷启动主动拉取（getLatestLyric）的限流状态，见 [maybePullLatestLyric]。
    private var lastPullLatestElapsedMs: Long = 0L
    private var pulledLatestOnce = false

    // 最近一笔携带全曲（allLyrics）的数据（resolve 合成后的视图），[fullSongSnapshot] 直读。
    // 只进不出：后续不带 allLyrics 的增量包不清掉它；换歌时被新的全曲包覆盖。
    @Volatile
    private var latestFullData: SuperLyricData? = null

    // [fullSongSnapshot] 结果缓存：按「allLyrics 数组身份 + 会话三元组 + 歌长」失效。数组引用
    // 来自 resolve 缓存合成（同一会话内 SuperLyricCache 持有同一实例），所以同会话重复调用
    // 只做引用比较；换歌/重新入缓存后身份变化才重建（接口 KDoc 要求实现廉价）。
    private val snapshotLock = Any()
    private var snapshotSource: Any? = null
    private var snapshotTrackUri: String? = null
    private var snapshotDurationMs: Long = -1L
    private var snapshotGeneration: Int = -1

    @Volatile
    private var snapshotCache: LyricSongSnapshot? = null

    internal val receiver = object : ISuperLyricReceiver.Stub() {
        override fun onLyric(publisher: String?, data: SuperLyricData?) {
            AppLog.i(
                "SuperLyricLyricProducer",
                "onLyric: publisher=$publisher title=${data?.title} " +
                    "lyric=${data?.lyric?.text?.take(24)} allLyrics=${data?.allLyricsCount} " +
                    "index=${data?.currentLyricIndex}"
            )
            lastLyricElapsedMs = clock()
            mutableConnection.value = ProducerConnection.CONNECTED
            handleIncoming(data)
        }

        override fun onStop(publisher: String?, data: SuperLyricData?) {
            AppLog.i("SuperLyricLyricProducer", "onStop: publisher=$publisher")
            // Playback paused/stopped: clear the active state so the arbiter can fall back / idle.
            lastLyricElapsedMs = 0L
            mutableState.value = null
        }
    }

    override fun start(context: Context) {
        if (started) {
            AppLog.i("SuperLyricLyricProducer", "start: already started (no-op)")
            return
        }
        started = true
        AppLog.i("SuperLyricLyricProducer", "start: registering receiver")
        ensureReceiverRegistered()
        // 冷启动立即出词：注册后主动拉一次（旧服务端/无播放时静默忽略）。
        maybePullLatestLyric("start")
        // 诊断：本库编译版本 vs 服务端实现版本（旧服务端不支持查询时返回 -1）。
        scope.launch {
            val apiVersion = runCatching { SuperLyricHelper.getApiVersion() }.getOrNull()
            val serverApiVersion = runCatching { SuperLyricHelper.getServerApiVersion() }.getOrNull()
            AppLog.i(
                "SuperLyricLyricProducer",
                "apiVersion=$apiVersion serverApiVersion=$serverApiVersion"
            )
        }
        // Poll the service availability so the arbiter can fall back when SuperLyric is absent.
        scope.launch { availabilityLoop() }
    }

    override fun stop() {
        if (!started) {
            AppLog.i("SuperLyricLyricProducer", "stop: not started (no-op)")
            return
        }
        started = false
        runCatching { SuperLyricHelper.unregisterReceiver(receiver) }
            .onFailure { AppLog.w("SuperLyricLyricProducer", "stop: unregister error", it) }
        scope.cancel()
        mutableConnection.value = ProducerConnection.DISCONNECTED
        mutableState.value = null
        clearFullSongData()
        AppLog.i("SuperLyricLyricProducer", "stop: done")
    }

    /**
     * 「重启歌词源」:注销并重新注册 Binder 接收器,重新武装服务端回调路径。
     * 与 [maybeForceReRegisterOnStall] 同一动作,但由用户显式触发、不看停滞阈值。
     */
    override fun restart() {
        if (!started) {
            AppLog.i("SuperLyricLyricProducer", "restart: not started (no-op)")
            return
        }
        scope.launch {
            val ok = runCatching {
                SuperLyricHelper.unregisterReceiver(receiver)
                SuperLyricHelper.registerReceiver(receiver)
                SuperLyricHelper.isReceiverRegistered(receiver)
            }.getOrDefault(false)
            if (ok) {
                mutableConnection.value = ProducerConnection.RECONNECTED
                AppLog.i("SuperLyricLyricProducer", "restart: receiver re-registered")
                // 重建回调链后同样主动拉一次:重建到下一次行切换之间不再空窗。
                maybePullLatestLyric("restart")
            } else {
                AppLog.w("SuperLyricLyricProducer", "restart: re-register failed")
            }
        }
    }

    /**
     * Registers [receiver] with SuperLyric, tolerating the SuperLyric module being absent.
     * 3.4 及更早的 API 在管理器未挂载时 `registerReceiver` 抛 `IllegalStateException`
     * （3.7 起改为静默忽略），所以调用点不设防会在无 SuperLyric 的设备上崩启动；
     * 注册失败保持 DISCONNECTED，由 availability 循环在服务可达后重试。
     */
    private fun ensureReceiverRegistered() {
        val already = runCatching { SuperLyricHelper.isReceiverRegistered(receiver) }
            .getOrDefault(false)
        if (already) {
            if (mutableConnection.value != ProducerConnection.CONNECTED &&
                mutableConnection.value != ProducerConnection.RECONNECTED
            ) {
                mutableConnection.value = ProducerConnection.CONNECTED
            }
            return
        }
        val ok = runCatching {
            SuperLyricHelper.registerReceiver(receiver)
            SuperLyricHelper.isReceiverRegistered(receiver)
        }.getOrDefault(false)
        if (ok) {
            mutableConnection.value = ProducerConnection.CONNECTED
            AppLog.i("SuperLyricLyricProducer", "receiver registered")
            // 晚到的注册（模块后于宿主可用）同样补一次主动拉取。
            maybePullLatestLyric("registered")
        } else {
            AppLog.w(
                "SuperLyricLyricProducer",
                "receiver registration deferred; SuperLyric manager not attached"
            )
        }
    }

    private suspend fun availabilityLoop() {
        while (scope.isActive) {
            val available = runCatching { SuperLyricHelper.isAvailable() }.getOrDefault(false)
            if (available) {
                ensureReceiverRegistered()
                maybeForceReRegisterOnStall()
            } else {
                if (mutableConnection.value != ProducerConnection.DISCONNECTED) {
                    AppLog.i("SuperLyricLyricProducer", "service gone -> DISCONNECTED")
                    mutableConnection.value = ProducerConnection.DISCONNECTED
                    mutableState.value = null
                    clearFullSongData()
                }
            }
            delay(AVAILABILITY_POLL_MS)
        }
    }

    /**
     * Recovers from a "frozen lyrics" state: if we have previously received lyrics (so the user is
     * clearly playing) but no `onLyric` has arrived for a long time, the service-side IPC delivery
     * may have been silently dropped while `isReceiverRegistered` still reports registered. Force a
     * fresh unregister+register to re-arm the callback path.
     *
     * 这条路径只重新武装回调，不主动拉取（与 [maybePullLatestLyric] 不叠加）：停滞时拉回的
     * 是同一笔旧行，只会和 12s 停滞窗口的语义打架。
     */
    private fun maybeForceReRegisterOnStall() {
        val last = lastLyricElapsedMs
        if (last <= 0L) return // never received lyrics / not playing: nothing to stall on
        val now = clock()
        val stalledFor = now - last
        if (stalledFor < STALL_RECOVERY_MS) return
        if (now - lastForceReRegisterElapsed < FORCE_RE_REGISTER_COOLDOWN_MS) return
        lastForceReRegisterElapsed = now
        val ok = runCatching {
            SuperLyricHelper.unregisterReceiver(receiver)
            SuperLyricHelper.registerReceiver(receiver)
            SuperLyricHelper.isReceiverRegistered(receiver)
        }.getOrDefault(false)
        if (ok) {
            mutableConnection.value = ProducerConnection.RECONNECTED
            AppLog.i(
                "SuperLyricLyricProducer",
                "force re-register after $stalledFor ms without lyric (recovered)"
            )
        } else {
            AppLog.w(
                "SuperLyricLyricProducer",
                "force re-register failed after $stalledFor ms stall; will retry"
            )
        }
    }

    /**
     * 冷启动自愈:注册成功后主动向服务端拉取当前在播曲目的全量数据(3.7 起提供)。
     * 服务端只在跨行时广播增量包——冷启动/接收器被杀重启后若不主动拉取,就要等下一次行切换
     * 才出词。旧服务端没有该事务(调用抛错)或当前无播放(返回 null)时静默忽略。
     *
     * 去重/限流:
     * - [PULL_LATEST_COOLDOWN_MS] 内不重复发起:availability 循环每 5s 走一次注册,
     *   「注册成功」分支在服务端反复丢注册状态时会被反复命中,限流防重复 IPC;
     * - 拉回的数据只在「当前无状态」或「曲目不同」时发射:同曲已有实时推送时拉取值更旧,
     *   不得回退覆盖(实时推送才是权威)。
     */
    private fun maybePullLatestLyric(reason: String) {
        if (!started) return
        val now = clock()
        if (pulledLatestOnce && now - lastPullLatestElapsedMs < PULL_LATEST_COOLDOWN_MS) return
        pulledLatestOnce = true
        lastPullLatestElapsedMs = now
        scope.launch {
            val pulled = runCatching { SuperLyricHelper.getLatestLyric() }.getOrNull()
                ?: return@launch
            if (!started) return@launch
            val current = mutableState.value
            if (current != null && current.trackUri == trackUriOf(pulled)) {
                AppLog.i(
                    "SuperLyricLyricProducer",
                    "cold-start pull: current track already live, skipped ($reason)"
                )
                return@launch
            }
            AppLog.i(
                "SuperLyricLyricProducer",
                "cold-start pull: emitting latest lyric ($reason) title=${pulled.title}"
            )
            handleIncoming(pulled)
        }
    }

    /**
     * 处理一笔推送:先经 [SuperLyricCache.resolve] 合成,再选取活动行并发射。
     *
     * resolve 的语义(SuperLyricApi-3.7 源码):全量包原样返回并写入缓存;增量包命中缓存时合成
     * 出全曲视图;缓存缺失时向服务端拉取当前全量数据自愈(同曲目校验);无法补齐则原样返回。
     * 异常/空一律回落原始包——旧版模块(≤3.4)的逐行包在所有分支下都与原始包等价,旧路径不变。
     */
    private fun handleIncoming(rawData: SuperLyricData?) {
        val resolved = runCatching { SuperLyricCache.resolve(rawData) }.getOrNull() ?: rawData
        if (resolved != null && resolved.hasAllLyrics()) {
            latestFullData = resolved
        }
        // 「下标可信」判定:raw 自带当前行、却没带下标(旧式单行包,或未带下标的增量包)时,
        // resolve 合成出的下标可能来自缓存(旧),不能按全曲包下标推导——活动行与下一行/
        // 行下标一律退回旧逐行语义,避免「行取推送行、下一行却按旧下标取」的自相矛盾。
        val pushedLineIsFresher = rawData != null && rawData.hasLyric() &&
            !rawData.hasAllLyrics() && !rawData.hasCurrentLyricIndex()
        val indexedRow = !pushedLineIsFresher && resolved != null && resolved.hasAllLyrics() &&
            resolved.hasCurrentLyricIndex()
        emit(selectActiveLine(resolved, indexedRow), resolved, indexedRow)
    }

    /**
     * 活动行选取(3.7 全曲包的坑):
     * - [SuperLyricCache.resolve] 对全量包**原样返回 incoming**,而官方发布端约定全量包的
     *   `lyric` 是**首行**(Demo 注释:「增量包为当前行,全量包为首行」;发布示例
     *   `setLyric(allLines[0])`)——冷启动拿到的全量包若不看 `currentLyricIndex`,整首歌会被
     *   钉在第一行。故 [indexedRow] 为真时,活动行取 `getLyricAt(currentLyricIndex)`
     *   (getLyricAt 自带越界防护,取不到再回落 lyric)。
     * - 下标不可信(见 [handleIncoming] 的判定)时以推送行为准,等价于旧逐行语义。
     */
    private fun selectActiveLine(resolved: SuperLyricData?, indexedRow: Boolean): SuperLyricLine? {
        if (resolved == null) return null
        if (indexedRow) {
            resolved.getLyricAt(resolved.getCurrentLyricIndex())?.let { return it }
        }
        return resolved.getLyric()
    }

    /**
     * 整首歌快照:3.7+ 全曲包(allLyrics)直读,插件链每会话只取一次。行窗口语义与
     * SpicyBridgeDocument 一致,行级 translation/secondary 随行过桥(词表原样携带)。
     * 无 allLyrics(旧模块逐行推送)返回 null,管线自动回落到 LineStreamAggregator。
     *
     * 实现廉价:结果按「allLyrics 数组身份 + 会话三元组 + 歌长」缓存,同会话重复调用只做
     * 引用比较,不重新映射。会话三元组(producerId/generation/trackUri)与 [emit] 用同一
     * 表达式构造,保证管线校验成立。
     */
    override fun fullSongSnapshot(): LyricSongSnapshot? {
        val data = latestFullData ?: return null
        val lines = data.getAllLyrics() ?: return null
        if (lines.isEmpty()) return null
        val trackUri = trackUriOf(data)
        val durationMs = durationOf(data, lines)
        synchronized(snapshotLock) {
            val cached = snapshotCache
            if (cached != null && snapshotSource === lines && snapshotTrackUri == trackUri &&
                snapshotDurationMs == durationMs && snapshotGeneration == generation
            ) {
                return cached
            }
        }
        val snapshot = LyricSongSnapshot(
            producerId = PRODUCER_ID,
            generation = generation,
            trackUri = trackUri,
            durationMs = durationMs,
            // 与 Lyricon 同口径:快照行过一遍时间轴净化(词/行窗矛盾收敛、按起点排序),
            // 使插件链看到的行窗口与活动行在 emit 侧归一后的口径一致。
            rows = LyricTimelineSanitizer.sanitizeSnapshotRows(
                lines.map { line ->
                    LyricSongRow(
                        startMs = line.startTime,
                        endMs = line.endTime,
                        text = line.text,
                        translation = line.getTranslation().orEmpty(),
                        roma = line.getSecondary().orEmpty(),
                        words = line.words?.map { it.toLyricWord() }?.takeIf { it.isNotEmpty() }
                    )
                }
            )
        )
        synchronized(snapshotLock) {
            snapshotSource = lines
            snapshotTrackUri = trackUri
            snapshotDurationMs = durationMs
            snapshotGeneration = generation
            snapshotCache = snapshot
        }
        return snapshot
    }

    /** 快照歌长:优先服务端总时长,缺失时退回末行结束时间。 */
    private fun durationOf(data: SuperLyricData, lines: Array<SuperLyricLine>): Long =
        data.takeIf { it.hasDuration() }?.duration
            ?: lines.lastOrNull()?.endTime
            ?: 0L

    /** 会话标识:3.7 起 `lyricId` 是稳定身份(也是缓存自愈的键),优先用它;旧模块退回标题。 */
    private fun trackUriOf(data: SuperLyricData?): String =
        data?.takeIf { it.hasLyricId() }?.lyricId
            ?.let { "superlyric:$it" }
            ?: "superlyric:${data?.title.orEmpty()}"

    /** 会话结束后丢弃全曲数据与快照缓存(不跨会话保留整首行数组)。 */
    private fun clearFullSongData() {
        latestFullData = null
        synchronized(snapshotLock) {
            snapshotSource = null
            snapshotTrackUri = null
            snapshotDurationMs = -1L
            snapshotGeneration = -1
            snapshotCache = null
        }
    }

    /**
     * Build and emit a [LyricProducerState] from the selected active [SuperLyricLine] (or null to
     * keep only metadata).
     *
     * 下一行:
     * - 3.7 全曲包路径(下标可信,[indexedRow] 为真):下一行取 `allLyrics[idx+1]`,起点用其
     *   真实 `startTime`(末行/无时间则诚实留空),行级罗马音/翻译一并下发。
     * - 旧路径(无 allLyrics,或下标不可信):`secondary` 是下一行(SuperLyricApi-3.4 反编译
     *   语义,12:34 抓取时 arbiter 回落到本通道、nextLine 却写死空串的病因)。发布方不推
     *   secondary 时 nextLine 诚实留空,`nextLineStartMs` 用当前行结束时间近似(下一行 ≈
     *   当前行结束后开始)。
     *
     * Staleness: unlike Lyricon's ~60 Hz position feed, this channel only pushes ON LINE CHANGE.
     * Long interludes (instrumental bridges, spaced lyrics) legitimately produce >3 s gaps, which
     * the arbiter's uniform 3 s staleness misreads as a dead source, flapping the fallback chain
     * mid-song. A 12 s window tolerates line gaps while still falling back on a genuinely dead
     * publisher well inside a typical verse.
     */
    private fun emit(line: SuperLyricLine?, data: SuperLyricData?, indexedRow: Boolean) {
        val now = clock()
        if (line == null) {
            if (data == null) {
                mutableState.value = null
                return
            }
            // Metadata-only push (no active line): still surface the track so the UI has a title.
        }
        val words = line?.words?.map { it.toLyricWord() }
            ?.takeIf { it.isNotEmpty() }
        // 行窗/词窗基准统一(见 [LyricTimelineNormalizer]):同一行两阶段下发(带/不带词表,
        // 真机实测同一行窗 words=13 ↔ words=0)时,带词表的一笔不得给出与词窗自相矛盾的行窗。
        // 只治词窗越出行窗(并集扩展)与行窗远超可唱估时(向词对齐);正常拖尾与无词表一笔原样。
        val window = line?.let {
            LyricTimelineNormalizer.normalizeLineWindow(
                beginMs = it.startTime,
                endMs = it.endTime,
                wordBeginMs = words?.minOf { word -> word.startMs },
                wordEndMs = words?.maxOf { word -> word.endMs },
                estimatedSingMs = LyricTimelineNormalizer.estimatedSingMs(it.text)
            )
        }
        // SuperLyric pushes only the active line, so per-word timing implies SYLLABLE karaoke.
        val lyricKind = when {
            line == null -> LyricKind.NONE
            words != null -> LyricKind.SYLLABLE
            else -> LyricKind.LINE
        }

        // ---- 3.7 全曲包通道:下标/整曲元数据可用时优先取用,旧路径逐字节保持 ----
        val allLyrics = data?.takeIf { it.hasAllLyrics() }?.allLyrics
        val lyricIndex = if (indexedRow && data != null) data.currentLyricIndex else -1
        val nextAll = if (lyricIndex >= 0) allLyrics?.getOrNull(lyricIndex + 1) else null
        // 旧路径(无 allLyrics 或下标不可信):`secondary` 是下一行,保留旧启发式。
        val legacySecondary = data?.takeIf { it.hasSecondary() }?.secondary

        // 翻译/罗马音:全曲包路径优先行级字段(3.7 行模型),缺失时回落 data 级;旧路径沿用
        // data 级翻译、罗马音恒空(旧版模块 data.secondary 是下一行,不能当罗马音用)。
        val translatedLine = if (allLyrics != null) {
            line?.takeIf { it.hasTranslation() }?.translation
                ?: data?.takeIf { it.hasTranslation() }?.translation?.text.orEmpty()
        } else {
            data?.translation?.text.orEmpty()
        }
        val romanizedLine = if (allLyrics != null) {
            line?.takeIf { it.hasSecondary() }?.secondary
                ?: data?.takeIf { it.hasSecondary() }?.secondary?.text.orEmpty()
        } else {
            ""
        }

        // 歌长/进度:3.7 起服务端给出整曲时长与当前点位;缺失(旧模块)保持旧值(行末/行首)。
        // 歌长下限钳到行窗末尾、进度钳进 [0, 歌长]:AOD 快照校验要求 lineEndMs ≤ durationMs、
        // positionMs ∈ [0, durationMs],否则整帧被拒收(歌词整段不刷新)。
        val durationMs = data?.takeIf { it.hasDuration() }?.duration
            ?.coerceAtLeast(window?.endMs ?: 0L)
            ?: (line?.endTime ?: 0L)
        val positionMs = data?.takeIf { it.hasPosition() }?.position
            ?.coerceIn(0L, durationMs)
            ?: (line?.startTime ?: 0L)

        sequence++
        mutableState.value = LyricProducerState(
            producerId = PRODUCER_ID,
            generation = generation,
            sequence = sequence,
            status = "ready",
            trackUri = trackUriOf(data),
            title = data?.title.orEmpty(),
            artist = data?.artist.orEmpty(),
            album = data?.album.orEmpty(),
            imageId = "",
            line = line?.text.orEmpty(),
            romanizedLine = romanizedLine,
            translatedLine = translatedLine,
            // 全曲包路径下发真实行下标;旧路径保持 0(既有行为,AodStateProjector 以 >=0 判活动行)。
            lineIndex = if (lyricIndex >= 0) lyricIndex else 0,
            positionMs = positionMs,
            durationMs = durationMs,
            sampledAtElapsedMs = now,
            speed = 1f,
            playing = line != null,
            receivedAtElapsedMs = now,
            words = words,
            renderModes = defaultRenderModes(),
            lyricKind = lyricKind,
            alignedRight = false,
            lineStartMs = window?.beginMs ?: 0L,
            lineEndMs = window?.endMs ?: 0L,
            ruby = emptyList(),
            layoutGroups = emptyList(),
            hasTimedLyrics = line != null,
            nextLineStartMs = if (lyricIndex >= 0) {
                nextAll?.startTime?.takeIf { it > 0L }
            } else {
                legacySecondary?.startTime ?: window?.endMs?.takeIf { it > 0L }
            },
            nextLine = if (lyricIndex >= 0) {
                nextAll?.text.orEmpty()
            } else {
                legacySecondary?.text.orEmpty()
            },
            nextLineRomanized = nextAll?.takeIf { it.hasSecondary() }?.secondary.orEmpty(),
            nextLineTranslated = nextAll?.takeIf { it.hasTranslation() }?.translation.orEmpty(),
            staleAfterMs = LINE_EVENT_STALE_AFTER_MS
        )
    }

    private fun SuperLyricWord.toLyricWord() = LyricWord(
        text = word,
        romanized = "",
        startMs = startTime,
        endMs = endTime,
        boundaryAfter = false
    )

    companion object {
        private const val PRODUCER_ID = "superlyric"
        private const val AVAILABILITY_POLL_MS = 5_000L

        /**
         * Staleness override for this channel: SuperLyric pushes only on line change, so a
         * >3 s gap is a normal interlude, not a dead source. See [emit].
         */
        internal const val LINE_EVENT_STALE_AFTER_MS = 12_000L

        /** No `onLyric` for this long while playing → the callback path is likely dropped. */
        private const val STALL_RECOVERY_MS = 60_000L

        /** Minimum gap between two forced re-registers, so a persistent stall doesn't hammer IPC. */
        private const val FORCE_RE_REGISTER_COOLDOWN_MS = 30_000L

        /**
         * 冷启动主动拉取的最小间隔:覆盖 availability 循环两拍(2×5s),既能在注册后立刻补拉,
         * 又不会在服务端反复丢注册状态时反复拉取整首歌词。
         */
        private const val PULL_LATEST_COOLDOWN_MS = 10_000L

        /** Default render modes when customization is unavailable; matches the other producers. */
        private fun defaultRenderModes() = ProducerRenderModes(
            weight = "Medium",
            textSize = "normal",
            textSizeCustom = 100,
            secondary = "Main only",
            // 兜底值必须过得了 aod/AodRenderPreferences.normalizeAodAnimation（只放行
            // Minimal / BetterLyrics，其余回落 Gradient）：历史遗留的 "Karaoke fill" 会被
            // 静默改写成 Gradient，是个纯误导的默认值。
            animation = "Gradient",
            glow = "Off",
            lineSyncFill = "Top to bottom",
            overflow = "Wrap",
            transition = "Fade up",
            font = "spotify"
        )
    }
}
