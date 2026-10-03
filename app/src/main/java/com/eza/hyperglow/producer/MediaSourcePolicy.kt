package com.eza.hyperglow.producer

/**
 * [android.media.AudioAttributes] 内容类型常量镜像（JVM 可测，与平台数值对齐）。
 * 与 [MediaPlayback] 镜像 `PlaybackState` 的既有做法一致：判定逻辑不依赖 Android 类。
 */
internal object MediaContentType {
    const val UNKNOWN = 0
    const val SPEECH = 1
    const val MUSIC = 2
    const val MOVIE = 3
    const val SONIFICATION = 4
}

/** 「当前音频源是什么」的判定结果。 */
internal enum class MediaSourceKind {
    /** 音乐（显式声明 MUSIC，或未知来源的 fail-open 放行）。 */
    MUSIC,

    /** 视频/影视（显式声明 MOVIE，或命中已知视频应用包名）。 */
    VIDEO,

    /** 语音内容（播客/有声书等，显式声明 SPEECH）。 */
    SPEECH,

    /** 提示音/系统音效等非媒体内容（显式声明 SONIFICATION）。 */
    OTHER,

    /** 来源未声明、也不在已知包名表内 —— 无从判断。 */
    UNKNOWN
}

/**
 * 当前音频源是否为音乐 —— 视频播放不再触发歌词显示（真机实证：LyricInfo 生产者的
 * 「任意在播会话」兜底会把抖音/哔哩哔哩的视频会话当歌曲，视频标题随之进入歌词链）。
 *
 * 判定输入是**播放器应用身份**（MediaSession 的包名 / Lyricon 上报的播放器包名）与
 * 会话**显式声明的音频内容类型**（`AudioAttributes.contentType`，经
 * `MediaController.playbackInfo` 读取）：
 *
 * 1. 包名命中 [VIDEO_PACKAGES] → [MediaSourceKind.VIDEO]；
 * 2. 内容类型显式声明 MOVIE → [MediaSourceKind.VIDEO]，SPEECH / SONIFICATION 各自归类；
 * 3. 其余（含未声明内容类型、未知包名）→ [MediaSourceKind.MUSIC] / [MediaSourceKind.UNKNOWN]，
 *    一律**放行**。
 *
 * fail-open 是刻意的：平台默认属性是 `USAGE_MEDIA + CONTENT_TYPE_UNKNOWN`
 * （AOSP `MediaSessionRecord.DEFAULT_ATTRIBUTES`），绝大多数应用从不声明内容类型；
 * 若按「未声明即非音乐」处理，会把所有未知音乐应用误伤成不显示歌词。因此只排除
 * **可证实的非音乐源**，宁漏勿错。
 */
internal object MediaSourcePolicy {

    /**
     * 已知的视频/短视频/直播类应用包名。只收录「确定不用来听歌」的包：条目正确与否
     * 只影响漏判（放行），不会误伤音乐应用 —— 未列出的包一律放行。
     */
    private val VIDEO_PACKAGES: Set<String> = setOf(
        // 哔哩哔哩（手机 / HD / 国际版）
        "tv.danmaku.bili",
        "tv.danmaku.bilibilihd",
        "com.bilibili.app.in",
        // 抖音 / 抖音极速版 / 抖音火山版
        "com.ss.android.ugc.aweme",
        "com.ss.android.ugc.aweme.lite",
        "com.ss.android.ugc.live",
        // 快手 / 快手极速版
        "com.smile.gifmaker",
        "com.kuaishou.nebula",
        // 长视频
        "com.tencent.qqlive",
        "com.qiyi.video",
        "com.youku.phone",
        "com.hunantv.imgo.activity",
        "com.ss.android.article.video",
        "tv.acfundanmaku.video",
        // 直播
        "air.tv.douyu.android",
        "com.duowan.kiwi",
        // 社交/短视频平台
        "com.sina.weibo",
        "com.xingin.xhs",
        // YouTube
        "com.google.android.youtube"
    )

    /** 判定给定来源的类型；输入缺失（null/空白/未知内容类型）时 fail-open 放行。 */
    fun classify(packageName: String?, contentType: Int?): MediaSourceKind {
        val pkg = packageName?.trim()?.lowercase().orEmpty()
        if (pkg.isNotEmpty() && pkg in VIDEO_PACKAGES) return MediaSourceKind.VIDEO
        return when (contentType) {
            MediaContentType.MOVIE -> MediaSourceKind.VIDEO
            MediaContentType.SPEECH -> MediaSourceKind.SPEECH
            MediaContentType.SONIFICATION -> MediaSourceKind.OTHER
            MediaContentType.MUSIC -> MediaSourceKind.MUSIC
            else -> MediaSourceKind.UNKNOWN
        }
    }

    /** 显式声明的非音乐内容类型（包名表之外的第二个判据）。 */
    fun isNonMusicKind(kind: MediaSourceKind): Boolean =
        kind == MediaSourceKind.VIDEO ||
            kind == MediaSourceKind.SPEECH ||
            kind == MediaSourceKind.OTHER

    /**
     * 该来源是否允许进入歌词显示链。[filterEnabled] 为 false（用户关闭「视频/非音乐
     * 音频不显示歌词」）时恒为 true，恢复历史行为。
     */
    fun isLyricEligible(
        packageName: String?,
        contentType: Int?,
        filterEnabled: Boolean = true
    ): Boolean = !filterEnabled || !isNonMusicKind(classify(packageName, contentType))
}
