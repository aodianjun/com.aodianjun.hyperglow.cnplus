package com.eza.hyperglow.producer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LyricOpeningFilter] — 开头元数据清理·内置三类:窗口(前 32 行且
 * ≤30s)、版权/制作明细/标题歌手头判定、2s 短续行。与 Bridge 词表逐字段对齐
 * (「作词/作曲」不在词表,测试固化该事实)。
 */
class LyricOpeningFilterTest {

    private fun lineAt(startMs: Long, text: String) =
        ElrcParser.parse("[00:${startMs / 1000}.${(startMs % 1000).toString().padStart(3, '0')}]" + text)[0]

    @Test
    fun copyrightLinesAreHidden_cnEnAndSymbol() {
        assertTrue(LyricOpeningFilter.isCopyrightLine("版权所有 (C) 2026 唱片公司"))
        assertTrue(LyricOpeningFilter.isCopyrightLine("Copyright © 2026 Label"))
        assertTrue(LyricOpeningFilter.isCopyrightLine("© 2026 Label"))
        assertTrue(LyricOpeningFilter.isCopyrightLine("All Rights Reserved"))
        assertTrue(LyricOpeningFilter.isCopyrightLine("未经许可，不得转载"))
        assertFalse(LyricOpeningFilter.isCopyrightLine("歌词正文第一句"))
    }

    @Test
    fun productionCreditLinesAreHidden() {
        assertTrue(LyricOpeningFilter.isProductionCreditLine("Piano: Yuki Kajiura", 1_000L))
        assertTrue(LyricOpeningFilter.isProductionCreditLine("钢琴：张三", 1_000L))
        assertTrue(LyricOpeningFilter.isProductionCreditLine("Mixed in Dolby Atmos by XYZ", 2_000L))
        // 无冒号且无 " by" 的 "-" 形态不是「角色: 内容」:不隐藏(Bridge 同语义)。
        assertFalse(LyricOpeningFilter.isProductionCreditLine("Piano - Yuki Kajiura", 1_000L))
        // 「作词/作曲」不在 Bridge 词表:保守保留,测试固化该边界。
        assertFalse(LyricOpeningFilter.isProductionCreditLine("作词：张三", 1_000L))
        assertFalse(LyricOpeningFilter.isProductionCreditLine("作曲：李四", 1_000L))
        // 超过 30s 窗口不隐藏。
        assertFalse(LyricOpeningFilter.isProductionCreditLine("Piano: Yuki Kajiura", 31_000L))
    }

    @Test
    fun titleArtistLeadIsHiddenWithin15s() {
        assertTrue(LyricOpeningFilter.isTitleArtistLead("歌名 - 歌手", 2_000L))
        assertTrue(LyricOpeningFilter.isTitleArtistLead("Title – Artist", 3_000L))
        assertTrue(LyricOpeningFilter.isTitleArtistLead("Title — Artist", 4_000L))
        // 无空格包围的连字符不算分隔符;句末标点拒绝;超 15s 拒绝。
        assertFalse(LyricOpeningFilter.isTitleArtistLead("歌名-歌手", 2_000L))
        assertFalse(LyricOpeningFilter.isTitleArtistLead("真的歌词。 - 假的", 2_000L))
        assertFalse(LyricOpeningFilter.isTitleArtistLead("歌名 - 歌手", 16_000L))
    }

    @Test
    fun fullOpeningBlockIsFiltered() {
        val lines = listOf(
            lineAt(0L, "版权所有 (C) 2026 唱片公司"),
            lineAt(1_000L, "Piano: Yuki Kajiura"),
            lineAt(2_000L, "歌名 - 歌手"),
            lineAt(2_500L, "歌手伴唱人员"),
            lineAt(5_000L, "真正的第一句歌词")
        )
        val filtered = LyricOpeningFilter.filterOpeningMetadata(lines)
        assertEquals(1, filtered.size)
        assertEquals("真正的第一句歌词", filtered[0].text)
    }

    @Test
    fun continuationOnlyFollowsTitleArtistCredit() {
        // 续行紧跟标题歌手头 → 隐藏;版权行后的短行 → 保留。
        val afterCredit = listOf(
            lineAt(0L, "歌名 - 歌手"),
            lineAt(1_000L, "和声合唱团")
        )
        assertEquals(0, LyricOpeningFilter.filterOpeningMetadata(afterCredit).size)

        val afterCopyright = listOf(
            lineAt(0L, "版权所有 (C) 2026"),
            lineAt(1_000L, "和声合唱团")
        )
        // 版权行隐藏,但其后不是标题歌手头 → 续行规则不触发,和声行保留。
        val afterCopyrightFiltered = LyricOpeningFilter.filterOpeningMetadata(afterCopyright)
        assertEquals(1, afterCopyrightFiltered.size)
        assertEquals("和声合唱团", afterCopyrightFiltered[0].text)
    }

    @Test
    fun lyricContentAfterCreditIsKept() {
        // 真实歌词行(≥8 个 CJK 字符)即使紧跟标题歌手头也不隐藏:
        // 歌词头隐藏,歌词行经 looksLikeLyricContent 判定为正文而保留。
        val lines = listOf(
            lineAt(0L, "歌名 - 歌手"),
            lineAt(1_000L, "这是一句足够长而且真实存在的歌词内容")
        )
        val filtered = LyricOpeningFilter.filterOpeningMetadata(lines)
        assertEquals(1, filtered.size)
        assertEquals("这是一句足够长而且真实存在的歌词内容", filtered[0].text)
    }

    @Test
    fun linesBeyondWindowAreAlwaysKept() {
        // 第 34 行(索引 33 ≥ 32)的版权行超出候选窗口:保留。
        val beyondLineCount = (0 until 40).map { index ->
            if (index == 33) lineAt(0L, "版权所有 (C) 2026") else lineAt((index + 1) * 1_000L, "第${index}句歌词")
        }
        assertEquals(40, LyricOpeningFilter.filterOpeningMetadata(beyondLineCount).size)

        // 时间超过 30s 的版权行:保留。
        val lateCopyright = listOf(
            lineAt(0L, "第一句歌词"),
            lineAt(31_000L, "版权所有 (C) 2026")
        )
        assertEquals(2, LyricOpeningFilter.filterOpeningMetadata(lateCopyright).size)
    }

    @Test
    fun normalLyricsPassThroughUnchanged() {
        val lines = listOf(
            lineAt(1_000L, "第一句歌词"),
            lineAt(5_000L, "第二句歌词"),
            lineAt(9_000L, "第三句歌词")
        )
        val filtered = LyricOpeningFilter.filterOpeningMetadata(lines)
        assertEquals(lines, filtered)
    }

    @Test
    fun emptyInputReturnsEmpty() {
        assertEquals(0, LyricOpeningFilter.filterOpeningMetadata(emptyList()).size)
    }

    // --- 制作名单行识别(「不显示非歌词内容」开关的判定核心)---

    @Test
    fun creditLinesAreDetected_cnEnAndBracketForms() {
        assertTrue(LyricCreditLineFilter.isCreditLine("作词：张三"))
        assertTrue(LyricCreditLineFilter.isCreditLine("作曲：李四"))
        assertTrue(LyricCreditLineFilter.isCreditLine("编曲：王五"))
        assertTrue(LyricCreditLineFilter.isCreditLine("词曲：赵六"))
        assertTrue(LyricCreditLineFilter.isCreditLine("作词 : 张三"))
        assertTrue(LyricCreditLineFilter.isCreditLine("Composer: John"))
        assertTrue(LyricCreditLineFilter.isCreditLine("lyricist: Bob"))
        assertTrue(LyricCreditLineFilter.isCreditLine("Arranger / Alice"))
        // 括号标签型(含无名字的裸标签)
        assertTrue(LyricCreditLineFilter.isCreditLine("【作词】 张三"))
        assertTrue(LyricCreditLineFilter.isCreditLine("(作曲) 李四"))
        assertTrue(LyricCreditLineFilter.isCreditLine("【作词】"))
        // 空格隔开的英文分工写明
        assertTrue(LyricCreditLineFilter.isCreditLine("Lyrics by Bob"))
        assertTrue(LyricCreditLineFilter.isCreditLine("Composed by John"))
        assertTrue(LyricCreditLineFilter.isCreditLine("Music by Someone"))
        // 繁体与全角同样归一后命中(zh-Hant 歌词源常见)
        assertTrue(LyricCreditLineFilter.isCreditLine("作詞：山田"))
        assertTrue(LyricCreditLineFilter.isCreditLine("編曲：山田"))
        assertTrue(LyricCreditLineFilter.isCreditLine("填詞：李四"))
        assertTrue(LyricCreditLineFilter.isCreditLine("ＬＹＲＩＣＩＳＴ：John"))
        // 「标签: 内容」形态本身即名单,内容长短不影响判定
        assertTrue(LyricCreditLineFilter.isCreditLine("词曲：这是一句够长的歌词正文内容"))
    }

    @Test
    fun ordinaryLyricsAreNeverTreatedAsCreditLines() {
        // 保守原则的核心:宁可漏判(照常显示),不可错判(吞掉真歌词)。
        assertFalse(LyricCreditLineFilter.isCreditLine("真正的第一句歌词"))
        assertFalse(LyricCreditLineFilter.isCreditLine("我作曲给你听")) // 句中出现,非行首分工
        // 行首是角色词前缀但不成标签的真歌词:没有分隔符/右括号,绝不吞。
        assertFalse(LyricCreditLineFilter.isCreditLine("作曲家的梦想"))
        assertFalse(LyricCreditLineFilter.isCreditLine("作词人的自白"))
        assertFalse(LyricCreditLineFilter.isCreditLine("I was composed and calm"))
        assertFalse(LyricCreditLineFilter.isCreditLine(""))
        assertFalse(LyricCreditLineFilter.isCreditLine("   "))
        // 词边界:recomposed 内部的 composed 不能命中(左侧须非字母数字)。
        assertFalse(LyricCreditLineFilter.isCreditLine("recomposed the melody"))
        // 只有角色词、没有分隔/收尾时不算名单。
        assertFalse(LyricCreditLineFilter.isCreditLine("作词"))
        assertFalse(LyricCreditLineFilter.isCreditLine("作曲"))
    }

    @Test
    fun classifyCreditLinesRespectsTheSwitch() {
        // 开关关闭:一律不判为名单(保持历史行为)。
        val off = classifyCreditLines("作词：张三", "作曲：李四", hideCredits = false)
        assertFalse(off.lineIsCredit)
        assertFalse(off.nextLineIsCredit)

        // 开关打开:分别归类主行与下一行。
        val on = classifyCreditLines("作词：张三", "真正的歌词", hideCredits = true)
        assertTrue(on.lineIsCredit)
        assertFalse(on.nextLineIsCredit)

        val both = classifyCreditLines("作词：张三", "作曲：李四", hideCredits = true)
        assertTrue(both.lineIsCredit)
        assertTrue(both.nextLineIsCredit)

        // 空白行不参与归类。
        val blank = classifyCreditLines("", "", hideCredits = true)
        assertFalse(blank.lineIsCredit)
        assertFalse(blank.nextLineIsCredit)
    }

    // --- 真机实测回归(2026-10-10,网易云《乐鸣东方》开场名单)---
    // 用户反馈「开关无效」的根因:初版只认 作词/作曲/编曲 等六个词,而真机名单里
    // 复合标签(弦乐监制/音乐总监/民族伴唱监制)与乐器长尾(二胡/古琴/天琴/骨笛/南音洞箫…)
    // 占绝大多数,全部漏判。以下用例逐行取自设备 diagnostic-trace.log 原文,勿删。

    @Test
    fun deviceCreditLinesAreAllDetected() {
        val deviceCredits = listOf(
            "二胡：皓原",
            "作曲：李建衡",
            "作词：元和令/付茂华",
            "南音拍板：王彩娥@泉州南音乐团",
            "南音洞箫：王锦超@泉州南音乐团",
            "南音琵琶：陈洋@泉州南音乐团",
            "古琴 ：成子",
            "古筝：哔哔啵啵璇",
            "吉他：李萌@乐人无数",
            "和音：周弦 张想想",
            "唢呐：川子",
            "天琴 ：韦晴晴",
            "弦乐监制：李朋",
            "弦乐：国际首席爱乐乐团",
            "录音：张凯博@99studio",
            "民族伴唱监制：陈一磊",
            "民族伴唱：梁文珍 蔡京原 马美荣 黄珍婷 邓颍榕",
            "混音/母带：罗文Rown",
            "琵琶：章益@敦煌古乐团、杨柳音子@音若子兮",
            "编曲：1AN孙毅然 李建衡",
            "调校：Creuzer",
            "音乐总监：李建衡@乐人无数",
            "音频编辑：王飞@乐人无数",
            "骨笛/竹笛：罗萌",
        )
        for (line in deviceCredits) {
            assertTrue("未识别为名单: <" + line + ">", LyricCreditLineFilter.isCreditLine(line))
        }
    }

    @Test
    fun deviceLyricLinesAreNeverHidden() {
        val deviceLyrics = listOf(
            "一弦一调唤知己 山海风流鸣笙簧",
            "万物皆有声 随风作乐章",
            "万物皆有声 随风作乐章",
            "万物皆有声 随风作乐章",
            "万物皆有声 随风作乐章",
            "万物皆有声 随风作乐章",
            "万物皆有声 随风作乐章（万籁添情长 痴醉一生何妨",
            "万籁添情长 痴醉一生何妨",
            "万籁添情长 痴醉一生何妨",
            "乐鸣东方",
            "人海举目可望",
            "人间百相 总让我神往",
            "人间百相 总让我神往",
            "人间百相 总让我神往",
            "吹拂时间枝桠",
            "咚咚",
            "咚咚 传遍此间他乡",
            "咚咚 传遍此间他乡",
            "咚咚 传遍此间他乡",
            "咚咚 大地踏梦起歌",
            "咚咚 大地踏梦起歌",
            "咚咚 大地踏梦起歌",
            "咚咚 流水破开寒霜",
            "咚咚 流水破开寒霜",
            "咚咚 流水破开寒霜",
            "咚咚 流水破开寒霜",
            "咚咚 金石击起辉光",
            "咚咚 金石击起辉光",
            "咚咚 金石击起辉光",
            "咚咚 金石击起辉光",
            "唤炽心无双 千秋同所向",
            "唤花肆意生长",
            "唤长风 燃云苍",
            "唤长风万象 浩气燃云苍",
            "天地为引 归巢为依",
            "天地为引 归巢为依",
            "天地为引 归巢为依",
            "天地为引 归巢为依",
            "天地为引 归巢为依",
            "寻到心底那声回响",
            "少年狂",
            "岁月来偿少年狂",
            "愿风不再斑驳脸庞",
            "我踏梦飞天而上",
            "我随雨唤醒庙堂",
            "旌鼓声声唤红装 唤岁月来偿",
            "时光磨红的手掌 终将奏一场",
            "琵琶拨醒千窟遐想",
            "看过沙海如浪",
            "蔓草共舞霓裳",
            "触摸不朽胡杨",
            "闻弦何愁知音",
            "雨啊 共诉琴声悠长",
            "风啊 何时来自云上",
            "飞鸟越过沧海渔唱",
            "龚世旺 王立军 陈荣杰 杨   逍 苏佳浇@广西",
        )
        for (line in deviceLyrics) {
            assertFalse("真歌词被误吞: <" + line + ">", LyricCreditLineFilter.isCreditLine(line))
        }
    }

    @Test
    fun colonBearingLyricsAreNotCreditLabels() {
        // 带冒号的真歌词必须安全:单字角色词只做整标签匹配(歌词/插曲),
        // 英文按词边界匹配(sp 不能命中 space)。
        assertFalse(LyricCreditLineFilter.isCreditLine("歌词：一句歌词"))
        assertFalse(LyricCreditLineFilter.isCreditLine("插曲：某个插曲"))
        assertFalse(LyricCreditLineFilter.isCreditLine("老师说：安静"))
        assertFalse(LyricCreditLineFilter.isCreditLine("space: the final frontier"))
        assertFalse(LyricCreditLineFilter.isCreditLine("他说：我爱你"))
    }
}
