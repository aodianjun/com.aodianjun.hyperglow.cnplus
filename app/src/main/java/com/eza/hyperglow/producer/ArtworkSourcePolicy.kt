package com.eza.hyperglow.producer

/**
 * 歌曲图片取图校对(纯函数,JVM 可测):系统播放窗口(媒体会话)可能同时挂着多个来源
 * ——正在播的音乐软件、刚退到后台的视频应用、元数据滞留的旧会话。封面只允许取自
 * 「当前播放的音乐软件」正在播、且曲目与当前歌词曲目一致的会话;任何不确定(无在播
 * 会话、曲目对不上、多个包同时命中)一律拒绝——不显示,而不是显示错的图。
 */
internal data class ArtworkSessionSample(
    val packageName: String,
    val playing: Boolean,
    val title: String,
    val artist: String
)

/**
 * 选出可取封面的会话;不满足校对条件返回 null。
 *
 * 校对规则(fail-closed):
 * 1. 只认在播会话([ArtworkSessionSample.playing],由取图侧按 PlaybackState 判定)——
 *    这就是「当前播放的音乐软件」的判定口径;
 * 2. 当前歌词曲目已知([trackTitle] 非空)时,会话曲目必须与其同曲
 *    ([isSameTrackIdentity],容忍 "(Live)"/译名括号等写法差异),防止系统播放窗口
 *    滞留的旧封面被当成当前曲目的封面;
 * 3. 命中会话必须收敛到唯一包名:多个包同时命中(同曲双开会话/数据歧义)→ 拒绝;
 * 4. 当前歌词曲目未知(空标题)时只有「恰好一个在播会话」才可信,否则拒绝。
 */
internal fun resolveVerifiedArtworkSession(
    samples: List<ArtworkSessionSample>,
    trackTitle: String,
    trackArtist: String
): ArtworkSessionSample? {
    val playing = samples.filter { it.playing && it.packageName.isNotBlank() }
    if (playing.isEmpty()) return null
    val candidates = if (trackTitle.isBlank()) {
        playing.distinctBy { it.packageName }.takeIf { it.size == 1 } ?: return null
    } else {
        playing.filter { isSameTrackIdentity(trackTitle, trackArtist, it.title, it.artist) }
    }
    if (candidates.mapTo(HashSet()) { it.packageName }.size != 1) return null
    return candidates.firstOrNull { it.title.isNotBlank() } ?: candidates.firstOrNull()
}
