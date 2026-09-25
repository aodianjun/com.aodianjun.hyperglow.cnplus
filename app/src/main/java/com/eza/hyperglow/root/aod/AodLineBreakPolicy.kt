package com.eza.hyperglow.root.aod

/**
 * CJK 避头尾折行策略(Bridge LyricLineBreakPolicy 同表移植,issue #68 #2)。
 *
 * CN+ 的折行切片是 paint.breakText 逐字符贪心,断点会落在任意标点上——中文行
 * 经常从句读处断开。这里在量出的断点上做禁则回退:
 * - 行首禁则:句读/闭合括号/促音小假名等不得开新行;
 * - 行尾禁则:开括号/开引号不得挂在行尾。
 * 回退把断点前移,使禁则字符随其后文本一起去下一行;至多回退 [MAX_ADJUST_STEPS]
 * 次(防"。。。…"长串一路走穿),且保证相对 [start] 至少推进一个码点。
 * 拉丁/空白文本的断点不含禁则字符,行为不变。
 */
internal const val PROHIBITED_LINE_START =
    "、。，．！？：；" +
        "）」』】〕〉》〙〗" +
        "’”…ー" +
        "ぁぃぅぇぉっゃゅょゎ" +
        "ァィゥェォッャュョヮ" +
        "ヵヶ"

internal const val PROHIBITED_LINE_END =
    "（「『【〔〈《〘〖" +
        "‘“"

private const val MAX_ADJUST_STEPS = 16

internal fun isProhibitedLineStart(codePoint: Int): Boolean =
    PROHIBITED_LINE_START.any { it.code == codePoint }

internal fun isProhibitedLineEnd(codePoint: Int): Boolean =
    PROHIBITED_LINE_END.any { it.code == codePoint }

/**
 * 调整逐字符断点。[fitEnd] 是按宽度量出的绝对断点([text] 内索引);当其后还有内容
 * ([fitEnd] < [hardEnd],即此处真的发生折行)且断点命中禁则时,按码点回退。
 * 返回调整后的断点;保证 > [start](至少推进一个码点)且 ≤ [fitEnd]。
 */
internal fun adjustForCjkLineBreak(text: String, start: Int, fitEnd: Int, hardEnd: Int): Int {
    var adjusted = fitEnd
    if (adjusted >= hardEnd) return adjusted
    val minProgress = if (start < text.length) text.offsetByCodePoints(start, 1) else start + 1
    var steps = 0
    while (adjusted > minProgress && adjusted < hardEnd && steps < MAX_ADJUST_STEPS) {
        val nextCodePoint = text.codePointAt(adjusted)
        val previousCodePoint = text.codePointBefore(adjusted)
        if (!isProhibitedLineStart(nextCodePoint) && !isProhibitedLineEnd(previousCodePoint)) break
        adjusted = text.offsetByCodePoints(adjusted, -1)
        steps++
    }
    return adjusted
}
