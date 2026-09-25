package com.eza.hyperglow.root.aod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AodPerfSampler] — disabled 时零采样,enabled 时固定数组聚合、
 * 每报告窗口一条汇总(count/avg/max),零逐帧输出。
 */
class AodPerfSamplerTest {

    @Test
    fun disabledSamplerNeverSamplesOrReports() {
        var sinkCalls = 0
        var clock = 0L
        val sampler = AodPerfSampler(
            enabled = { false },
            clockNanos = { clock },
            sink = { sinkCalls++ },
            reportIntervalNanos = 1_000L
        )
        val started = sampler.begin()
        assertEquals(AodPerfSampler.NO_SAMPLE, started)
        clock = 16_000L
        sampler.end(AodPerfSampler.Metric.DRAW, started)
        assertEquals(0, sinkCalls)
    }

    @Test
    fun aggregatesCountAvgAndMaxOverReportingWindow() {
        var clock = 0L
        val reports = mutableListOf<String>()
        val sampler = AodPerfSampler(
            enabled = { true },
            clockNanos = { clock },
            sink = { reports.add(it) },
            reportIntervalNanos = 1_000_000L // 1ms 测试窗口
        )
        // 六帧:100us / 300us / 200us ×4。窗口从首次采样起算,第 6 帧的 end 把
        // now-windowStart 推过 1ms,触发一条汇总;窗口随后清零重开。
        val s1 = sampler.begin(); clock += 100_000L; sampler.end(AodPerfSampler.Metric.DRAW, s1)
        val s2 = sampler.begin(); clock += 300_000L; sampler.end(AodPerfSampler.Metric.DRAW, s2)
        val s3 = sampler.begin(); clock += 200_000L; sampler.end(AodPerfSampler.Metric.DRAW, s3)
        val s4 = sampler.begin(); clock += 200_000L; sampler.end(AodPerfSampler.Metric.DRAW, s4)
        val s5 = sampler.begin(); clock += 200_000L; sampler.end(AodPerfSampler.Metric.DRAW, s5)
        assertEquals(0, reports.size)
        val s6 = sampler.begin(); clock += 200_000L; sampler.end(AodPerfSampler.Metric.DRAW, s6)
        assertEquals(1, reports.size)
        assertTrue(reports[0], reports[0].contains("draw={count=6"))
        assertTrue(reports[0], reports[0].contains("avgUs=200"))
        assertTrue(reports[0], reports[0].contains("maxUs=300"))
        // 新窗口从零开始计数:窗口内不再出第二条汇总。
        val s7 = sampler.begin(); clock += 100_000L; sampler.end(AodPerfSampler.Metric.DRAW, s7)
        assertEquals(1, reports.size)
    }

    @Test
    fun enablingMidStreamStartsFreshWindow() {
        var clock = 0L
        var enabled = false
        val reports = mutableListOf<String>()
        val sampler = AodPerfSampler(
            enabled = { enabled },
            clockNanos = { clock },
            sink = { reports.add(it) },
            reportIntervalNanos = 1_000L
        )
        sampler.end(AodPerfSampler.Metric.DRAW, sampler.begin())
        assertEquals(0, reports.size)
        // 启用后:首帧只开窗(无输出),时钟跨过报告窗口的第二帧才出第一条汇总。
        enabled = true
        clock = 5_000L
        sampler.end(AodPerfSampler.Metric.DRAW, sampler.begin())
        assertEquals(0, reports.size)
        clock = 9_000L
        sampler.end(AodPerfSampler.Metric.DRAW, sampler.begin())
        assertEquals(1, reports.size)
    }
}
