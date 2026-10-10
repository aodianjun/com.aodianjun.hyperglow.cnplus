package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 对唱并发行选取([selectDuetLineIndex])的判定语义测试——移植上游 99ba119d4
 * `SpicyBridgeDocumentStore.concurrentRowsAt` 的三段式语义(覆盖/退出缓冲/预加入)。
 */
class DuetConcurrentTest {

    private fun windows(vararg ranges: Pair<Long, Long>): List<DuetLineWindow> =
        ranges.map { DuetLineWindow(it.first, it.second, false) }

    @Test
    fun coveringOverlapSelectsLatestStarted() {
        // 主行 10..30;行 0..40 与 12..28 都覆盖 position=15,取开始更晚的后者。
        val w = windows(0L to 40_000L, 10_000L to 30_000L, 12_000L to 28_000L)
        assertEquals(2, selectDuetLineIndex(w, 1, 15_000L))
    }

    @Test
    fun exactlyOneSecondOverlapJoins() {
        val w = windows(10_000L to 30_000L, 29_000L to 40_000L)
        assertEquals(1, selectDuetLineIndex(w, 0, 29_500L))
    }

    @Test
    fun overlapBelowOneSecondNeverJoins() {
        val w = windows(10_000L to 30_000L, 29_001L to 40_000L)
        assertEquals(-1, selectDuetLineIndex(w, 0, 29_500L))
    }

    @Test
    fun dyingTailDoesNotFlickerTwoLineSection() {
        // 覆盖中但共享窗口 <1s(将死的行尾:重叠 30000-29500=500ms):不加入,双行段不闪现。
        val w = windows(10_000L to 30_000L, 29_500L to 30_600L)
        assertEquals(-1, selectDuetLineIndex(w, 0, 29_900L))
    }

    @Test
    fun endedOverlapHeldByExitBuffer() {
        // position=35:行 12..33 已唱完但与主行重叠 18s → 退出缓冲保留,双行段一起淡出。
        val w = windows(10_000L to 30_000L, 12_000L to 33_000L)
        assertEquals(1, selectDuetLineIndex(w, 0, 35_000L))
    }

    @Test
    fun endedOverlapWithoutEnoughSharedWindowDoesNotHold() {
        val w = windows(10_000L to 30_000L, 29_500L to 33_000L)
        assertEquals(-1, selectDuetLineIndex(w, 0, 33_500L))
    }

    @Test
    fun soloPrimaryPreJoinsEarliestFutureOverlap() {
        // 主行窗口内最早开始的未来重叠行预加入(显示侧在窗口开始前不绘制)。
        val w = windows(10_000L to 30_000L, 20_000L to 40_000L)
        assertEquals(1, selectDuetLineIndex(w, 0, 5_000L))
    }

    @Test
    fun interludeRowsNeverJoin() {
        val w = listOf(
            DuetLineWindow(10_000L, 30_000L, false),
            DuetLineWindow(12_000L, 28_000L, true)
        )
        assertEquals(-1, selectDuetLineIndex(w, 0, 15_000L))
    }

    @Test
    fun interludePrimaryHasNoCompanion() {
        val w = listOf(
            DuetLineWindow(10_000L, 30_000L, true),
            DuetLineWindow(12_000L, 28_000L, false)
        )
        assertEquals(-1, selectDuetLineIndex(w, 0, 15_000L))
    }

    @Test
    fun outOfRangePrimaryReturnsMinusOne() {
        assertEquals(-1, selectDuetLineIndex(windows(0L to 10_000L), 5, 1_000L))
    }

    // --- 并发行相对主行独立(shouldAdoptDuetLineCandidate) ---

    /**
     * 主行换行不改屏上并发行:锁定行自己的窗口还没结束,新候选(null 或另一行)一律不采用。
     * 真机形态(《乐鸣东方》86.7–89.7 主行 + 88.6–90.5 和声):主行 89.7 换行时和声还没唱完,
     * 旧行为会把候选换成新主行的伴唱(或 null)把和声抹掉。
     */
    @Test
    fun mainLineChangeKeepsLockedDuetUntilItsOwnWindowEnds() {
        val harmony = DuetLineWindow(88_600L, 90_500L, false)
        assertEquals(false, shouldAdoptDuetLineCandidate(harmony, null, 89_700L))
        // 新主行选出的候选是另一行且不与锁定行重叠:同样不换。
        assertEquals(
            false,
            shouldAdoptDuetLineCandidate(harmony, DuetLineWindow(120_000L, 125_000L, false), 89_700L)
        )
        // 它自己的窗口结束:采用本次候选(交还常规重选;null = 并发行退场,自己到点才换)。
        assertEquals(true, shouldAdoptDuetLineCandidate(harmony, null, 90_500L))
        assertEquals(
            true,
            shouldAdoptDuetLineCandidate(harmony, DuetLineWindow(90_500L, 95_000L, false), 90_500L)
        )
    }

    /** 真正重叠的新候选(此刻已开唱、与锁定行共享窗口 ≥1s)才提前切换。 */
    @Test
    fun genuinelyOverlappingNewCandidateSwitchesEarly() {
        val locked = DuetLineWindow(0L, 10_000L, false)
        val overlapping = DuetLineWindow(4_000L, 15_000L, false)
        assertEquals(true, shouldAdoptDuetLineCandidate(locked, overlapping, 5_000L))
        // 未开唱(预加入形态)不提前切换。
        assertEquals(false, shouldAdoptDuetLineCandidate(locked, overlapping, 3_000L))
        // 共享窗口 <1s 的将死行尾不切换(与 selectDuetLineIndex 同一门槛)。
        assertEquals(
            false,
            shouldAdoptDuetLineCandidate(locked, DuetLineWindow(9_500L, 12_000L, false), 9_700L)
        )
    }

    /** 同一行窗(候选没换)不触发切换;未锁定时候选直接采用。 */
    @Test
    fun sameWindowKeepsAndUnlockedAdopts() {
        val window = DuetLineWindow(1_000L, 5_000L, false)
        assertEquals(
            false,
            shouldAdoptDuetLineCandidate(window, DuetLineWindow(1_000L, 5_000L, false), 2_000L)
        )
        assertEquals(true, shouldAdoptDuetLineCandidate(null, window, 2_000L))
        assertEquals(true, shouldAdoptDuetLineCandidate(null, null, 2_000L))
    }

    // --- 伴唱行加入规则(上游 fefe5c54 的 joinsConcurrentScene) ---

    /** 主行 + 伴唱行:共享窗口不足 1s 仍加入(作者编排的短应答不被一秒门吞掉)。 */
    @Test
    fun authoredBackingVocalBelowOneSecondStillJoins() {
        val lead = DuetLineWindow(10_000L, 30_000L, false)
        val backing = DuetLineWindow(29_100L, 31_000L, false, isBackground = true)
        // 覆盖段:共享 900ms < 1s,非伴唱行会落空(见 overlapBelowOneSecondNeverJoins)。
        assertEquals(1, selectDuetLineIndex(listOf(lead, backing), 0, 29_500L))
        // 退出缓冲段同样放宽:已唱完的伴唱行不与主行一起消失。
        assertEquals(1, selectDuetLineIndex(listOf(lead, backing), 0, 31_000L))
        // 预加入段:主行独唱时伴唱行提前占位,加入瞬间布局不移动。
        assertEquals(1, selectDuetLineIndex(listOf(lead, backing), 0, 20_000L))
    }

    /** 只有**正**重叠才算同场景:首尾相接(共享窗口为 0)的伴唱行不加入。 */
    @Test
    fun backingVocalWithoutSharedWindowDoesNotJoin() {
        val lead = DuetLineWindow(10_000L, 30_000L, false)
        val backing = DuetLineWindow(30_000L, 31_000L, false, isBackground = true)
        assertEquals(-1, selectDuetLineIndex(listOf(lead, backing), 0, 29_500L))
    }

    /** 非伴唱行之间(lead 行偶然重叠)仍守一秒门:将死的行尾不闪现双行段。 */
    @Test
    fun leadRowOverlapBelowOneSecondStillDoesNotJoin() {
        val lead = DuetLineWindow(10_000L, 30_000L, false)
        val tail = DuetLineWindow(29_100L, 31_000L, false)
        assertEquals(-1, selectDuetLineIndex(listOf(lead, tail), 0, 29_500L))
    }

    /** 判定两侧对称:主行是伴唱行、候选是 lead 行时同样放宽。 */
    @Test
    fun backingPrimaryRelaxesTheGateForALeadCandidate() {
        val backing = DuetLineWindow(10_000L, 30_000L, false, isBackground = true)
        val lead = DuetLineWindow(29_100L, 31_000L, false)
        assertEquals(1, selectDuetLineIndex(listOf(backing, lead), 0, 29_500L))
    }

    /**
     * 短伴唱**不打断**已上屏的并发行:加入门槛放宽了,让位门槛没有(owner 2026-10-07
     * 「主行换行时和声还没唱完就不换」)。屏上没有锁定行时短伴唱照常按加入判据上屏。
     */
    @Test
    fun shortBackingVocalDoesNotDisplaceTheLockedDuetLine() {
        val locked = DuetLineWindow(0L, 10_000L, false)
        val shortBacking = DuetLineWindow(9_200L, 12_000L, false, isBackground = true)
        assertEquals(false, shouldAdoptDuetLineCandidate(locked, shortBacking, 9_500L))
        // 未上屏(无锁定行):短伴唱直接采用。
        assertEquals(true, shouldAdoptDuetLineCandidate(null, shortBacking, 9_500L))
    }
}
