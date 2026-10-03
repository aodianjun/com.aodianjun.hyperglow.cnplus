package com.eza.hyperglow.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniMarkdownTest {

    @Test
    fun `blank input yields no lines`() {
        assertTrue(parseMarkdownLines("").isEmpty())
        assertTrue(parseMarkdownLines("   \n \n").isEmpty())
    }

    @Test
    fun `heading levels are detected and deep ones collapse to three`() {
        val lines = parseMarkdownLines("# Title\n## Sub\n### Section\n#### Deep")
        assertEquals(4, lines.size)
        assertEquals(MarkdownLineKind.HEADING, lines[0].kind)
        assertEquals(1, lines[0].level)
        assertEquals(2, lines[1].level)
        assertEquals(3, lines[2].level)
        // 四级及以上折叠为三级
        assertEquals(3, lines[3].level)
    }

    @Test
    fun `hash without space is not a heading`() {
        val lines = parseMarkdownLines("#hash tag")
        assertEquals(MarkdownLineKind.PARAGRAPH, lines.single().kind)
        assertEquals("#hash tag", lines.single().spans.single().text)
    }

    @Test
    fun `bullet markers are recognized with text preserved`() {
        val lines = parseMarkdownLines("- first\n* second\n+ third")
        assertEquals(3, lines.size)
        lines.forEach { assertEquals(MarkdownLineKind.BULLET, it.kind) }
        assertEquals("first", lines[0].spans.single().text)
        assertEquals("second", lines[1].spans.single().text)
        assertEquals("third", lines[2].spans.single().text)
    }

    @Test
    fun `emphasized text is not misread as bullet`() {
        val lines = parseMarkdownLines("**bold start** and text")
        assertEquals(MarkdownLineKind.PARAGRAPH, lines.single().kind)
    }

    @Test
    fun `ordered list keeps its numeric prefix`() {
        val lines = parseMarkdownLines("1. one\n2) two")
        assertEquals(2, lines.size)
        assertEquals(MarkdownLineKind.ORDERED, lines[0].kind)
        assertEquals("1.", lines[0].orderedPrefix)
        assertEquals("one", lines[0].spans.single().text)
        assertEquals("2.", lines[1].orderedPrefix)
        assertEquals("two", lines[1].spans.single().text)
    }

    @Test
    fun `fenced code block produces monospace lines until closing fence`() {
        val lines = parseMarkdownLines("before\n```\ncode line 1\ncode line 2\n```\nafter")
        assertEquals(MarkdownLineKind.PARAGRAPH, lines[0].kind)
        assertTrue(lines[1].kind == MarkdownLineKind.CODE)
        assertTrue(lines[2].kind == MarkdownLineKind.CODE)
        assertEquals("code line 1", lines[1].spans.single().text)
        assertEquals("code line 2", lines[2].spans.single().text)
        assertEquals(MarkdownLineKind.PARAGRAPH, lines[3].kind)
        assertEquals("after", lines[3].spans.single().text)
        assertTrue(lines[1].spans.single().code)
    }

    @Test
    fun `unterminated fence consumes rest of input`() {
        val lines = parseMarkdownLines("```\nonly code\nstill code")
        assertEquals(2, lines.size)
        lines.forEach { assertEquals(MarkdownLineKind.CODE, it.kind) }
    }

    @Test
    fun `paired bold markers produce bold span`() {
        val spans = parseInlineSpans("see **HyperGlow report** page")
        assertEquals(3, spans.size)
        assertFalse(spans[0].bold)
        assertTrue(spans[1].bold)
        assertEquals("HyperGlow report", spans[1].text)
        assertFalse(spans[2].bold)
    }

    @Test
    fun `unpaired bold marker keeps tail plain`() {
        val spans = parseInlineSpans("odd **bold never closed here")
        // 奇数个 ** 分隔:末段不误判为加粗
        assertFalse(spans.last().bold)
        assertEquals("bold never closed here", spans.last().text)
    }

    @Test
    fun `code span inside bold keeps both flags`() {
        val spans = parseInlineSpans("**use `adb devices` here**")
        assertEquals(3, spans.size)
        assertTrue(spans[0].bold)
        assertEquals("use ", spans[0].text)
        assertTrue(spans[1].bold)
        assertTrue(spans[1].code)
        assertEquals("adb devices", spans[1].text)
    }

    @Test
    fun `plain text without markers is single span`() {
        val spans = parseInlineSpans("no markers at all")
        assertEquals(1, spans.size)
        assertFalse(spans[0].bold)
        assertFalse(spans[0].code)
    }

    @Test
    fun `quote prefix is stripped into paragraph`() {
        val lines = parseMarkdownLines("> quoted line")
        assertEquals(MarkdownLineKind.PARAGRAPH, lines.single().kind)
        assertEquals("quoted line", lines.single().spans.single().text)
    }

    @Test
    fun `empty lines and blank lines are dropped`() {
        val lines = parseMarkdownLines("a\n\n\nb\n   \nc")
        assertEquals(3, lines.size)
    }

    @Test
    fun `release notes sample renders structurally`() {
        val sample = """
            ## What's Changed

            - **fix(aod):** guard against re-entrancy by @someone in #123
            - **feat(ui):** flat tab row by @other in #124

            **Full Changelog**: https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus/compare/0.3.148...0.3.149
        """.trimIndent()
        val lines = parseMarkdownLines(sample)
        assertEquals(MarkdownLineKind.HEADING, lines[0].kind)
        assertEquals(2, lines[0].level)
        assertEquals(listOf(MarkdownLineKind.BULLET, MarkdownLineKind.BULLET), lines.drop(1).take(2).map { it.kind })
        // 结尾的 Full Changelog 段落是加粗前缀 + 冒号开头的普通文本
        val last = lines.last()
        assertEquals(MarkdownLineKind.PARAGRAPH, last.kind)
        assertTrue(last.spans.first().bold)
        assertEquals("Full Changelog", last.spans.first().text)
        assertTrue(last.spans.last().text.startsWith(": https://"))
    }
}
