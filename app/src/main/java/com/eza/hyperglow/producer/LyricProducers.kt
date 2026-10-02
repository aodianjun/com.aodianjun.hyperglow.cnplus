package com.eza.hyperglow.producer

import android.content.Context
import com.eza.hyperglow.customization.CustomizationRepository

/**
 * Process-wide holder for the [LyricProducerArbiter] and its two producers.
 *
 * Created once at app startup (see [HyperGlowApplication]); projection consumers read
 * [arbiter].[LyricProducerArbiter.active] instead of `SpicyBridgeStore.state` directly,
 * per the `lyric-producer-contract` spec.
 *
 * Phase 3 status: the lyricon producer now emits complete, engine-ready [LyricProducerState]
 * (active line + per-word progress via `TimingNavigator`, plus the row-level fields
 * `lyricKind`/`lineStartMs`/`lineEndMs`/`hasTimedLyrics`/`nextLineStartMs` — spec clause 6).
 * The Spicy producer still wraps `SpicyBridgeStore.state` 1:1 and emits the row-level fields at
 * defaults; its per-word timing lives in `SpicyBridgeDocumentStore`, which `AodProjectionEngine`
 * still reads directly for its `project()` internals on the Spicy path (spec clause 9 — the one
 * remaining deviation).
 *
 * The engine's switch to `arbiter.active` as its sole ingress is the final step: it requires
 * the Spicy producer to populate the row-level fields from `SpicyBridgeDocumentStore` (computing
 * the active row via `primaryRowAt`) so the engine can stop reading the document store. Until
 * then `arbiter.active` mirrors `SpicyBridgeStore.state` for the Spicy path, keeping it
 * regression-free.
 */
object LyricProducers {
    @Volatile private var instance: LyricProducerArbiter? = null
    @Volatile private var lyricInfoProducer: LyricInfoLyricProducer? = null
    @Volatile private var registeredProducers: List<LyricProducer> = emptyList()

    val arbiter: LyricProducerArbiter
        get() = instance ?: error("LyricProducers not started; call start(context) first")

    @Synchronized
    fun start(context: Context) {
        if (instance != null) return
        val spicy = SpicyLyricProducer()
        val lyricon = LyriconLyricProducer()
        val superLyric = SuperLyricLyricProducer()
        val lyricInfo = LyricInfoLyricProducer()
        val producers = listOf<LyricProducer>(spicy, lyricon, superLyric, lyricInfo)
        val arbiter = LyricProducerArbiter(
            mapOf(
                LyricSource.SPICY to spicy,
                LyricSource.LYRICON to lyricon,
                LyricSource.SUPERLYRIC to superLyric,
                LyricSource.LYRICINFO to lyricInfo
            )
        )
        arbiter.start(context.applicationContext)
        instance = arbiter
        lyricInfoProducer = lyricInfo
        registeredProducers = producers
        // 启动即装载文档级派生配置(歌词时间偏移/对唱标记/渲染模式),不等首次文档变更。
        registeredProducers.forEach { it.onCustomizationChanged() }
        // 文档保存/导入/重置 → 生产者侧派生缓存(对唱标记开关/分侧快照/渲染模式)即刻重算,
        // 不必等下次切歌(见 LyriconLyricProducer.onCustomizationChanged)。
        CustomizationRepository.onChange = { onCustomizationChanged() }
    }

    /** Test/preview accessor; returns null before [start]. */
    fun arbiterOrNull(): LyricProducerArbiter? = instance

    /**
     * Called by [LyricInfoNotificationListener] when notification access is granted; lets the
     * LyricInfo producer re-enumerate cross-app sessions immediately.
     */
    fun onLyricInfoListenerConnected() {
        lyricInfoProducer?.onListenerConnected()
    }

    /**
     * 跨源 seek 转发(见 [LyricProducer.onExternalSeek]):[from] 检测到拖动进度条后,
     * 其余生产者立即采用该权威位置跟手——各位置源独立,不转发的一方要等自己的残值
     * 拒绝窗/冻结源恢复才追上(2026-09-28 真机实测滞后 14s)。
     */
    fun notifyExternalSeek(from: LyricSource, positionMs: Long) {
        registeredProducers.forEach { producer ->
            if (producer.id != from) producer.onExternalSeek(positionMs)
        }
    }

    /** 外部设置变更(文档保存/导入/重置)时通知生产者刷新派生缓存(偏移/标记/渲染模式)。 */
    fun onCustomizationChanged() {
        registeredProducers.forEach { producer -> producer.onCustomizationChanged() }
    }
}
