package com.example.hyperglow.scriptconvert

/**
 * 简繁字形转换（纯函数，无 Android 依赖，可 JVM 单测）。
 *
 * 数据来自 OpenCC 词典（见 [OpenCcTables] 头注），按"仅保留有信息量的条目"裁剪：
 * - 单字表：简→繁 / 繁→简的首选字，恒等条目已剔除；
 * - 短语表：仅保留「逐字转换结果 ≠ 词条目标」的条目——既含消歧条目（干杯→乾杯），
 *   也含**防误转**的恒等条目（皇后→皇后，逐字转换会错成 皇後，必须由短语表压住）。
 *
 * 匹配策略：从左到右贪心最长匹配（短语优先，未命中回落到单字表）。这是 OpenCC
 * 分词转换的简化近似——歌词是短句，且歧义主要来自双字词，实测覆盖足够；
 * 不做整句分词，避免引入词典分词器的体积与不确定性。
 *
 * 线程安全：表构建在 [lazy] 内完成；转换只读，缓存表按容量上限整体清空，
 * 可在插件链的任意线程调用（宿主链在 Dispatchers.Default 上跑）。
 */
internal object ScriptConverter {

    /** 目标字形。 */
    enum class Target { SIMPLIFIED, TRADITIONAL }

    /** 单表容量上限：超出整体清空（歌词重复副歌多，缓存命中率可观；上限防无界增长）。 */
    private const val CACHE_LIMIT = 2048

    private class Tables(
        /** 单字映射：源字 → 目标字（首选）。 */
        val chars: Map<Char, Char>,
        /** 短语映射：源短语 → 目标短语。 */
        val phrases: Map<String, String>,
        /** 短语按首字索引，长度降序（最长匹配优先）。 */
        val phraseIndex: Map<Char, List<String>>,
        /** 可能触发任何转换的字符集合（快速通道：全不在集合内则原样返回）。 */
        val interesting: Set<Char>
    )

    private val simplifiedToTraditional: Tables by lazy {
        build(OpenCcTables.S2T_CHARS, OpenCcTables.S2T_PHRASES)
    }

    private val traditionalToSimplified: Tables by lazy {
        build(OpenCcTables.T2S_CHARS, OpenCcTables.T2S_PHRASES)
    }

    /** 每方向一个转换缓存；用 ConcurrentHashMap + 整体清空控制内存，不做 LRU（歌词串短，代价可忽略）。 */
    private val s2tCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val t2sCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun build(charText: String, phraseText: String): Tables {
        val chars = HashMap<Char, Char>(4096)
        val phrases = HashMap<String, String>(16384)
        for (line in charText.lineSequence()) {
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) continue
            val src = line.substring(0, tab)
            val dst = line.substring(tab + 1)
            // 增补平面（非 BMP）单字在 Kotlin 里是代理对，单字表放不下——走短语表按串匹配，
            // 语义等价（词典里这类条目极少，代价可忽略）。
            if (src.length == 1 && dst.length == 1) chars[src[0]] = dst[0] else phrases[src] = dst
        }
        for (line in phraseText.lineSequence()) {
            if (line.isEmpty()) continue
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab == line.length - 1) continue
            phrases[line.substring(0, tab)] = line.substring(tab + 1)
        }
        val index = HashMap<Char, MutableList<String>>()
        for (key in phrases.keys) {
            index.getOrPut(key[0]) { ArrayList(8) }.add(key)
        }
        index.values.forEach { list -> list.sortByDescending { it.length } }
        val interesting = HashSet<Char>(chars.size + index.size)
        interesting.addAll(chars.keys)
        interesting.addAll(index.keys)
        return Tables(chars, phrases, index, interesting)
    }

    /**
     * 把 [text] 转换为 [target] 字形。已经是目标字形（或纯拉丁/数字/符号）时原样返回，
     * 因此重复调用幂等，调用方可直接用 `converted != original` 判定是否发生改变。
     */
    fun convert(text: String, target: Target): String {
        if (text.isEmpty()) return text
        val tables = if (target == Target.TRADITIONAL) simplifiedToTraditional else traditionalToSimplified
        // 快速通道：不含任何可转换字（纯拉丁歌词、已转换过的整句）直接返回，避免分配。
        var interesting = false
        for (ch in text) {
            if (ch in tables.interesting) {
                interesting = true
                break
            }
        }
        if (!interesting) return text

        val cache = if (target == Target.TRADITIONAL) s2tCache else t2sCache
        cache[text]?.let { return it }

        val result = convertUncached(text, tables)
        if (cache.size >= CACHE_LIMIT) cache.clear()
        cache[text] = result
        return result
    }

    private fun convertUncached(text: String, tables: Tables): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val candidates = tables.phraseIndex[text[i]]
            if (candidates != null) {
                var matched: String? = null
                for (candidate in candidates) {
                    if (candidate.length <= text.length - i && text.startsWith(candidate, i)) {
                        matched = candidate
                        break
                    }
                }
                if (matched != null) {
                    out.append(tables.phrases.getValue(matched))
                    i += matched.length
                    continue
                }
            }
            val ch = text[i]
            out.append(tables.chars[ch] ?: ch)
            i++
        }
        return out.toString()
    }
}
