package com.eza.hyperglow.producer

/**
 * 生产者 ingest 期的行窗/词窗时间基准统一(纯函数,全仓单一副本)。
 *
 * 背景:同一行会被上游两阶段下发(带/不带词表,真机实测 SuperLyric 同一行窗
 * `words=13 ↔ words=0`),而渲染侧按「有无词表」走两条不同路径(184/186 稳定化)。
 * 只要带词表的一笔自身不给出与词窗矛盾的行窗,两阶段就不可能给出互相矛盾的时间戳,
 * 渲染侧稳定化即可降级为防御层。
 *
 * 三类口径(只治「自相矛盾」,正常拖尾一律不动):
 * ① 词窗超出行窗(词比行还长)→ 行窗扩到词窗并集:
 *    `begin = min(词窗起点, 原行首)`、`end = max(词窗终点, 原行尾)`;
 * ② 行窗远超文本可唱估时(既有判据 [grossWindowMs])且词级跨距可信([plausibleWordSpanMs])
 *    → 行窗向词对齐(首词起点/末词终点,即既有 resetLineTimestampsFromWords 的同一语义);
 * ③ 其余(含正常拖尾:行窗比词窗并集长但差距在正常范围)→ 原样返回。
 *
 * 不带词窗的行(或阶段)没有数据可归一 → 原样返回,由渲染侧稳定化覆盖。
 * 行窗判据沿用 Lyricon P0 的既有估时/阈值,不新造口径;词窗同样失真时本函数不动行窗,
 * 交由调用方既有的丢词/钳制路径(见 [LyriconTimelineRepair])。
 */
internal object LyricTimelineNormalizer {

    /** 每字可唱时长估计(中速粤语/国语,宽松取 350ms)。 */
    internal const val MS_PER_CHAR = 350L

    /** 行窗 > 估时×[GROSS_WINDOW_MULTIPLIER] + [WINDOW_SLACK_MS] 视为「间隙吞进行窗」。 */
    internal const val GROSS_WINDOW_MULTIPLIER = 2L

    /** 行窗合理性宽限(短行/换气不误伤)。 */
    internal const val WINDOW_SLACK_MS = 2_000L

    /** 词锚定档:词跨距 ≤ 估时×[WORD_ANCHOR_MULTIPLIER] + [WORD_SPREAD_SLACK_MS] 视为真实词级。 */
    internal const val WORD_ANCHOR_MULTIPLIER = 3L

    /** 词跨距可信宽限(慢歌长音不误伤:整句长音是真,铺满间隙的合成词是假)。 */
    internal const val WORD_SPREAD_SLACK_MS = 4_000L

    /** 归一后的行窗;[beginMs]/[endMs] 与入参逐值相同即未动。 */
    internal data class LineWindow(val beginMs: Long, val endMs: Long)

    /**
     * 文本可唱估时:剥行首标记后按可见字符数(空白不计,标点随行计入偏保守)× [MS_PER_CHAR]。
     * 标记(（男）/（副歌）等)不发声,计入会把钳制目标与 gross 判定整体推偏约一秒;
     * 纯标记行(（间奏）等)按 0 字计,否则长间奏行窗被误判成损坏行。
     */
    internal fun estimatedSingMs(text: CharSequence?): Long {
        val visible = stripDuetMarkerRun(text?.toString() ?: "")
        return visible.count { !it.isWhitespace() } * MS_PER_CHAR
    }

    /** 行窗是否明显大于文本可唱估时(损坏形状)。 */
    internal fun grossWindowMs(text: CharSequence?, windowMs: Long): Boolean =
        grossWindowMs(estimatedSingMs(text), windowMs)

    /** 行窗是否明显大于估时([estimatedSingMs] 口径)。 */
    internal fun grossWindowMs(estimatedSingMs: Long, windowMs: Long): Boolean =
        estimatedSingMs > 0L && windowMs > estimatedSingMs * GROSS_WINDOW_MULTIPLIER + WINDOW_SLACK_MS

    /** 词窗跨距是否可信(词锚定档)。 */
    internal fun plausibleWordSpanMs(estimatedSingMs: Long, wordSpanMs: Long): Boolean =
        wordSpanMs <= estimatedSingMs * WORD_ANCHOR_MULTIPLIER + WORD_SPREAD_SLACK_MS

    /**
     * 三类口径的统一入口(纯函数)。[wordBeginMs]/[wordEndMs] 为词窗并集(首词起点/末词终点),
     * 无词窗传 null;[estimatedSingMs] 为 [estimatedSingMs] 口径的估时(调用方按行文本计算,
     * 使判据共享、单测可直接给具体数值)。
     */
    internal fun normalizeLineWindow(
        beginMs: Long,
        endMs: Long,
        wordBeginMs: Long?,
        wordEndMs: Long?,
        estimatedSingMs: Long
    ): LineWindow {
        if (wordBeginMs == null || wordEndMs == null) return LineWindow(beginMs, endMs)
        if (grossWindowMs(estimatedSingMs, endMs - beginMs)) {
            // ② 远超估时且词窗可信 → 向词窗对齐(既有 P0 语义)。
            // 词窗同样失真 → 行窗不动,交由调用方既有的丢词/钳制路径(不新造判据)。
            return if (plausibleWordSpanMs(estimatedSingMs, wordEndMs - wordBeginMs)) {
                LineWindow(wordBeginMs, maxOf(wordBeginMs + 1L, wordEndMs))
            } else {
                LineWindow(beginMs, endMs)
            }
        }
        // ① 词窗超出行窗(词比行还长)→ 并集扩展,两端都不得丢。
        return LineWindow(minOf(beginMs, wordBeginMs), maxOf(endMs, wordEndMs))
    }
}
