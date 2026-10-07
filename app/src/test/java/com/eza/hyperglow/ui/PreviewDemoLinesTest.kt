package com.eza.hyperglow.ui

import com.eza.hyperglow.root.projection.LyricDuetLine
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 演示歌词数据的钉子(LyricAppearanceSection.kt 的 demoLines / demoTrack)。
 *
 * 契约四条:
 *  - 语言分流:English 走《Take My Hand》,其余(跟随系统/简体中文)走中文演示曲;判据必须与
 *    「界面语言」设置同一来源([UiLanguage]),系统语言为英文但用户显式选了「简体中文」时以
 *    用户选择为准 —— 过去演示歌词是编译期常量,语言切换对它无影响,这里防止再退回那一形态。
 *  - 辅助内容完整:两份演示曲的每一行都要带非空 romanized 与 translated,「转写/翻译」开关
 *    在预览里才有东西可显示(辅助文字行在无内容时本就不显示)。
 *  - 中文演示曲整行注音:逐词注音段必须无缝铺满整行、且各段读音拼接后与整行罗马音逐字一致。
 *    只标首词会在预览里留下一截拼音(owner 2026-10-06 反馈的「文字上方零星的转写内容」),
 *    这里把它钉死,防止演示数据再退回半截注音。
 *  - 并发行完整:两份演示曲各至少一行带非空 duet,「显示并发歌词(对唱)」在无实时歌词时
 *    也有东西可显示;渲染侧可见性判据见 previewDuetVisible。
 */
class PreviewDemoLinesTest {

    @Test
    fun englishInterfaceUsesEnglishDemoTrack() {
        val lines = demoLines(UiLanguage.ENGLISH)
        assertTrue(
            "English demo must carry the English track lines, got: ${lines.map { it.original }}",
            lines.any { it.original.contains("Take my hand now", ignoreCase = true) }
        )
        val track = demoTrack(UiLanguage.ENGLISH)
        assertEquals("Take My Hand", track.title)
        assertEquals("DAISHI DANCE, Cécile Corbel", track.artist)
    }

    @Test
    fun chineseAndSystemKeepTheChineseDemoTrack() {
        // 跟随系统(SYSTEM)与显式简体中文都保留中文演示曲——只有显式 English 才切换。
        assertEquals(demoLines(UiLanguage.SIMPLIFIED_CHINESE), demoLines(UiLanguage.SYSTEM))
        assertEquals("蝴蝶", demoTrack(UiLanguage.SIMPLIFIED_CHINESE).title)
        assertEquals("蝴蝶", demoTrack(UiLanguage.SYSTEM).title)
        assertTrue(
            demoLines(UiLanguage.SIMPLIFIED_CHINESE).any { it.original.contains("蝴蝶") }
        )
    }

    @Test
    fun bothDemoTracksCarryAuxiliaryTextForEveryLine() {
        // 英文曲用音标(英文的「转写」)与中译;中文曲用拼音与英译。缺任一项,对应界面语言下
        // 「辅助文字」开关的预览就是空的。
        listOf(
            UiLanguage.ENGLISH to "English",
            UiLanguage.SIMPLIFIED_CHINESE to "Chinese"
        ).forEach { (language, label) ->
            demoLines(language).forEach { line ->
                assertTrue(
                    "$label demo line must carry a transliteration: ${line.original}",
                    line.romanized.isNotBlank()
                )
                assertTrue(
                    "$label demo line must carry a translation: ${line.original}",
                    line.translated.isNotBlank()
                )
            }
        }
    }

    @Test
    fun bothDemoTracksCarryAConcurrentHarmonyLine() {
        // 并发行(和声)演示数据:两份演示曲各至少一行带非空 duet —— 演示快照只从这里
        // 产出 duetLine,缺了它「显示并发歌词(对唱)」开关在无实时歌词时就看不到效果。
        listOf(
            UiLanguage.ENGLISH to "English",
            UiLanguage.SIMPLIFIED_CHINESE to "Chinese"
        ).forEach { (language, label) ->
            assertTrue(
                "$label demo track must carry at least one concurrent/harmony line",
                demoLines(language).any { !it.duet.isNullOrBlank() }
            )
        }
    }

    @Test
    fun demoConcurrentRowsRenderAsHarmonyInTheAuxLane() {
        // 演示副行都是带括号的回声句(x-bg 形态):装配成和声行(harmony=true),预览走辅助行
        // 车道——与实机 role=BG 的 x-bg 同源;真对唱(不同演唱者并排)只在实机快照里出现。
        // 文本为空的行不产出并发行(与实机 duetLine 文本为空整条丢弃同口径)。
        listOf(
            UiLanguage.ENGLISH to "English",
            UiLanguage.SIMPLIFIED_CHINESE to "Chinese"
        ).forEach { (language, label) ->
            val duets = demoLines(language).mapNotNull { demoDuetLine(it) }
            assertTrue("$label demo must produce concurrent lines", duets.isNotEmpty())
            assertTrue(
                "$label demo concurrent rows must be harmony echoes",
                duets.all { it.harmony }
            )
        }
        assertTrue(
            "blank demo duet must not produce a line",
            demoDuetLine(DemoLine("x", "x", "x", emptyList(), duet = "  ")) == null
        )
    }

    @Test
    fun concurrentRowVisibilityFollowsTheSurfaceSwitch() {
        // 渲染侧判据(纯函数):并发行可见 = 本面「显示并发歌词(对唱)」开启 && 快照带非空
        // 并发行(与实机 LyricCanvasMapper 的门控同源)。开关关闭或文本剥空时不得上屏。
        val line = LyricDuetLine(text = "（也要飞向那片蓝天）")
        assertTrue(previewDuetVisible(true, line))
        assertTrue(!previewDuetVisible(false, line))
        assertTrue(!previewDuetVisible(true, null))
        assertTrue(!previewDuetVisible(true, LyricDuetLine(text = "   ")))
    }

    @Test
    fun englishDemoLinesCarryNoRuby() {
        // 英文曲没有振假名:注音开关在英文界面下没有对象,不填假注音。
        demoLines(UiLanguage.ENGLISH).forEach { line ->
            assertTrue("ruby must stay empty: ${line.original}", line.ruby.isEmpty())
        }
    }

    @Test
    fun chineseDemoRubyCoversTheWholeLine() {
        demoLines(UiLanguage.SIMPLIFIED_CHINESE).forEach { line ->
            val segments = line.ruby.sortedBy { it.start }
            assertTrue("ruby must not be empty: ${line.original}", segments.isNotEmpty())
            assertEquals("ruby must start at the line head: ${line.original}", 0, segments.first().start)
            assertEquals(
                "ruby must end at the line tail: ${line.original}",
                line.original.length,
                segments.last().end
            )
            segments.zipWithNext().forEach { (left, right) ->
                assertTrue(
                    "ruby segments must not overlap: ${line.original}",
                    right.start >= left.end
                )
                // 段间只允许空白(空格):非空白字符漏标即「半截注音」。
                assertTrue(
                    "only whitespace may sit between ruby segments: ${line.original}",
                    line.original.substring(left.end, right.start).all { it.isWhitespace() }
                )
            }
            segments.forEach { segment ->
                assertTrue("ruby segment must not be empty: ${line.original}", segment.end > segment.start)
                assertTrue(
                    "ruby segment must not span whitespace: ${line.original}",
                    line.original.substring(segment.start, segment.end).none { it.isWhitespace() }
                )
                // 逐字注音:一段的读音音节数必须等于该段的字数,下标写偏会在这里暴露。
                assertEquals(
                    "reading syllables must match the annotated characters: ${line.original} " +
                        "(${segment.reading})",
                    segment.end - segment.start,
                    syllableCount(segment.reading)
                )
            }
            // 注音与整行罗马音同源:两处读音逐字一致(拼接去空白后比较,分词写法可不同)。
            assertEquals(
                "ruby readings must match the line transliteration: ${line.original}",
                line.romanized.replace(" ", ""),
                segments.joinToString("") { it.reading.replace(" ", "") }
            )
        }
    }

    @Test
    fun bothDemoLineSetsAreNonEmptyAndSizedForCycling() {
        // 演示快照按 index 循环取下一行,空表会让预览整块空白。
        assertTrue(demoLines(UiLanguage.ENGLISH).size >= 2)
        assertTrue(demoLines(UiLanguage.SIMPLIFIED_CHINESE).size >= 2)
    }

    /**
     * 读音的音节数:元音连续段的个数。zh/ch/sh/ng 这类辅音串与词间空格都不计;
     * 带调元音(ǐ、è 等)不是 ASCII 辅音,天然算元音。
     */
    private fun syllableCount(reading: String): Int {
        var count = 0
        var inVowelRun = false
        reading.lowercase(Locale.ROOT).forEach { ch ->
            val vowel = !ch.isWhitespace() && ch !in CONSONANTS
            if (vowel && !inVowelRun) count++
            inVowelRun = vowel
        }
        return count
    }

    private companion object {
        private const val CONSONANTS = "bcdfghjklmnpqrstvwxyz"
    }
}
