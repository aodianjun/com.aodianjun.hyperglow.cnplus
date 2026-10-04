package com.example.hyperglow.scriptconvert

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 转换器行为钉子。用例取自 OpenCC 的典型歧义字词：
 * - 干/幹/乾（干杯 → 乾杯，逐字会错成 幹杯）；
 * - 发/發/髮（头发 → 頭髮，逐字默认 發）；
 * - 后/後（皇后 → 皇后，逐字会错成 皇後）；
 * - 台/臺（台湾 → 臺灣）。
 * 这些正是"短语表必须压住单字表"的证据，改动词典裁剪逻辑时先看这里。
 */
class ScriptConverterTest {

    private val s2t = ScriptConverter.Target.TRADITIONAL
    private val t2s = ScriptConverter.Target.SIMPLIFIED

    @Test
    fun singleCharactersConvert() {
        assertEquals("發", ScriptConverter.convert("发", s2t))
        assertEquals("後", ScriptConverter.convert("后", s2t))
        assertEquals("发", ScriptConverter.convert("發", t2s))
        assertEquals("后", ScriptConverter.convert("後", t2s))
    }

    @Test
    fun phraseDisambiguationWins() {
        assertEquals("乾杯", ScriptConverter.convert("干杯", s2t))
        assertEquals("頭髮", ScriptConverter.convert("头发", s2t))
        assertEquals("臺灣", ScriptConverter.convert("台湾", s2t))
        assertEquals("乾淨", ScriptConverter.convert("干净", s2t))
    }

    @Test
    fun identityPhraseBlocksWrongCharConversion() {
        // 皇后 是 OpenCC 的恒等条目：逐字转换会产出 皇後，短语表必须把它压回 皇后。
        assertEquals("皇后", ScriptConverter.convert("皇后", s2t))
        // 已经是繁体时不应被再次改写（幂等）。
        assertEquals("皇后", ScriptConverter.convert("皇后", t2s))
    }

    @Test
    fun traditionalToSimplifiedPhrases() {
        assertEquals("头发", ScriptConverter.convert("頭髮", t2s))
        assertEquals("以后", ScriptConverter.convert("以後", t2s))
        assertEquals("里面", ScriptConverter.convert("裡面", t2s))
        assertEquals("干净", ScriptConverter.convert("乾淨", t2s))
    }

    @Test
    fun latinDigitsAndPunctuationUntouched() {
        assertEquals("hello world 123", ScriptConverter.convert("hello world 123", s2t))
        assertEquals("hello world 123", ScriptConverter.convert("hello world 123", t2s))
        assertEquals("", ScriptConverter.convert("", s2t))
        assertEquals("!!!???,,,", ScriptConverter.convert("!!!???,,,", t2s))
    }

    @Test
    fun mixedTextConvertsOnlyChinese() {
        assertEquals("我愛你 baby 123", ScriptConverter.convert("我爱你 baby 123", s2t))
        assertEquals("我爱你 baby 123", ScriptConverter.convert("我愛你 baby 123", t2s))
    }

    @Test
    fun zeroWidthAndEmojiSurvive() {
        assertEquals("愛\u200B你🎵", ScriptConverter.convert("爱\u200B你🎵", s2t))
        assertEquals("爱\u200B你🎵", ScriptConverter.convert("愛\u200B你🎵", t2s))
    }

    @Test
    fun conversionIsIdempotent() {
        val source = "我爱你 头发 干杯 皇后"
        val once = ScriptConverter.convert(source, s2t)
        assertEquals(once, ScriptConverter.convert(once, s2t))
        val back = ScriptConverter.convert(once, t2s)
        assertEquals(back, ScriptConverter.convert(back, t2s))
    }

    @Test
    fun alreadyTargetScriptIsReturnedUnchanged() {
        // 同一实例语义：无任何可转换字符时快速通道原样返回。
        val simplified = "我爱你"
        assertEquals(simplified, ScriptConverter.convert(simplified, t2s))
        val traditional = "我愛你"
        assertEquals(traditional, ScriptConverter.convert(traditional, s2t))
    }
}
