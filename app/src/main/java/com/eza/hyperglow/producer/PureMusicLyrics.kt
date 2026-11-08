package com.eza.hyperglow.producer

/**
 * 纯音乐歌词标记检测(移植 HyperLyric limczhh/HyperLyric `PureMusicLyricDetector`)。
 *
 * 部分「纯音乐」曲目(如网易云音乐)会把整首歌的歌词填成一行标记
 * [PURE_MUSIC_MARKER]。这类曲目并非真的没有歌词,但把标记当歌词渲染没有意义,
 * 由「无歌词/纯音乐歌词行为」交给用户在「保持原标记 / 替换为歌曲信息 / 交还系统默认」
 * 三者间选择(见 [com.eza.hyperglow.customization.SurfaceProfile.noLyricsBehavior])。
 *
 * 判定与参考实现逐字对齐:剥离标点与空白后与标记精确相等,避免「纯音乐，请欣赏」
 * 「纯音乐请欣赏 🎶」等带标点/表情的变体漏判。
 */
private const val PURE_MUSIC_MARKER = "纯音乐请欣赏"

/** 单行歌词文本剥离非字母数字字符后恰为纯音乐标记。 */
internal fun isPureMusicMarkerLine(text: String?): Boolean {
    if (text.isNullOrBlank()) return false
    val normalized = text.filter { it.isLetterOrDigit() }
    return normalized == PURE_MUSIC_MARKER
}

/**
 * 整首歌的歌词行集合只要含一行纯音乐标记即为纯音乐曲目。歌曲级结论,随整首快照/整首
 * 文本变化重算;流式单行源按当前行文本用 [isPureMusicMarkerLine] 判定。
 */
internal fun isPureMusicSongLyrics(lines: List<String?>): Boolean =
    lines.any(::isPureMusicMarkerLine)

/**
 * 整首快照([LyricSongSnapshot.rows])含一行纯音乐标记即为纯音乐曲目。投影层以整首快照
 * 为首选输入(拿不到快照的逐行源回落当前行文本),结果随快照/曲目变化重算。
 */
internal fun isPureMusicSong(rows: List<LyricSongRow>): Boolean =
    rows.any { isPureMusicMarkerLine(it.text) }

/**
 * 曲目级纯音乐结论:优先整首行文本([fullSongRows],仅当与当前会话匹配时非 null),
 * 否则回落当前行文本(流式单行源)。两端判定同源,只在这里分派一次。
 */
internal fun resolvePureMusic(fullSongRows: List<LyricSongRow>?, currentLine: String?): Boolean =
    if (fullSongRows != null) isPureMusicSong(fullSongRows) else isPureMusicMarkerLine(currentLine)

/**
 * 空行兜底的确认窗口(ms):播放越过该位置后「整首没有任何歌词行」仍成立,才按纯音乐处理。
 * 切歌后源侧歌词往往慢一拍到点(网络拉取/通知栏异步),立即判定会让每首有歌词的歌在
 * 开头闪一下占位;实测歌词源都在首秒内到齐,3s 与「无歌词/纯音乐识别」调研建议一致。
 */
internal const val PURE_MUSIC_EMPTY_ROWS_CONFIRM_MS = 3_000L

/**
 * 曲目级纯音乐结论(「纯音乐识别」开启时的增强判定,由投影层调用):
 * 1. 标记文本命中(最可靠,即时生效)——整首任一行是纯音乐标记,或流式源当前行是标记,
 *    同 [resolvePureMusic];
 * 2. 「整首没有任何歌词行」兜底——两路信号取其一:
 *    - 整首快照存在但一行都没有(SuperLyric 3.7+ allLyrics 为空;Lyricon 对空歌词不产出
 *      快照,走下一路);
 *    - producer 歌级声明 `lyricKind == NONE` 且当前行空白(Lyricon `lines=0` 的稳定信号,
 *      onSongChanged 即知,不依赖快照);
 *    且都要求曲目时长有效、播放已越过 [PURE_MUSIC_EMPTY_ROWS_CONFIRM_MS] 确认窗口。
 *    当前行非空白时不接管:屏幕上还有真实内容(如 Spicy 未匹配到文档时桥接的一行),
 *    换成占位文本反而抹掉真歌词;确认窗口同时给 Spicy 的「暂未匹配到文档」留出完成匹配
 *    的时机(它对 no-document 也报 NONE),避免有歌词的歌闪占位。
 *
 * 只在调用方(投影层)按「纯音乐识别」开关门控;关闭时走 [resolvePureMusic] 历史路径。
 */
internal fun resolvePureMusicEnhanced(
    fullSongRows: List<LyricSongRow>?,
    currentLine: String?,
    durationMs: Long,
    positionMs: Long,
    playing: Boolean,
    producerNoLyrics: Boolean = false
): Boolean {
    if (resolvePureMusic(fullSongRows, currentLine)) return true
    val trackHasNoRows = (fullSongRows != null && fullSongRows.isEmpty()) ||
        (producerNoLyrics && currentLine.isNullOrBlank())
    if (!trackHasNoRows) return false
    // 时长无效(直播流/未知总长)不判:position 无从谈「越过了确认窗口」。
    if (durationMs <= 0L) return false
    return playing && positionMs >= PURE_MUSIC_EMPTY_ROWS_CONFIRM_MS
}
