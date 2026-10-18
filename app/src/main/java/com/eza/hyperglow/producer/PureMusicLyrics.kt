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
