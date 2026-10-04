package com.example.hyperglow.lyricfetch

/**
 * 真实响应夹具（2026-10 抓取，仅裁剪体积，字段结构原样保留）：
 * - 网易云：/api/search/get/web、/api/song/lyric/v1（yrc + tlyric）
 * - QQ 音乐：musicu.fcg 搜索、PlayLyricInfo（lyric 为真实 base64 全量载荷）
 * - LRCLIB：/api/get、/api/search
 *
 * 用真实载荷而不是手写样例，是为了钉住字段名与格式细节（QQ 的 base64 包装、
 * 网易云 YRC 的 JSON 署名行、LRCLIB 的 syncedLyrics 等）。
 */
internal object Fixtures {
    val NETEASE_SEARCH_JSON: String = """
        {"result": {"songs": [{"id": 35847388, "name": "Hello", "dt": 295502, "ar": [{"name": "Adele"}], "al": {"name": "Hello"}}, {"id": 36841430, "name": "Hello", "dt": 295502, "ar": [{"name": "Adele"}], "al": {"name": "25"}}]}}
    """

    val NETEASE_LYRIC_JSON: String = """
        {"yrc": {"lyric": "[6190,4440](6190,2190,0)Hello(8380,540,0), (8920,360,0)it's (9280,1350,0)me\n[11770,6090](11770,360,0)I (12130,210,0)was (12340,1350,0)wondering (13690,240,0)if (13930,840,0)after (14770,450,0)all (15220,270,0)these (15490,510,0)years (16000,180,0)you'd (16180,510,0)like (16690,180,0)to (16870,990,0)meet\n[17860,5220](17860,210,0)To (18070,480,0)go (18550,2340,0)over (20890,2190,0)everything"}, "lrc": {"lyric": "[00:06.220]Hello, it's me\n[00:11.320]I was wondering"}, "tlyric": {"lyric": "[by:九九Lrc歌词网～www.99Lrc.net]\n[00:06.220]你好 是我\n[00:11.320]我犹豫着要不要给你来电 不确定多年后你是否还愿相见"}}
    """

    val QQ_SEARCH_JSON: String = """
        {"music.search.SearchCgiService": {"data": {"body": {"song": {"list": [{"mid": "0039MnYb0qxYhV", "id": 97773, "name": "晴天", "interval": 269, "singer": [{"name": "周杰伦"}], "album": {"name": "叶惠美"}}]}}}}}
    """

    val QQ_PLAYLYRIC_JSON: String = """
        {"req_1": {"code": 0, "data": {"lyric": "W3RpOuaZtOWkqV0KW2FyOuWRqOadsOS8pl0KW2FsOuWPtuaDoOe+jl0KW2J5Ol0KW29mZnNldDowXQpbMDA6MDAuMDBd5pm05aSpIC0g5ZGo5p2w5LymIChKYXkgQ2hvdSkKWzAwOjAyLjI1Xeivje+8muWRqOadsOS8pgpbMDA6MDQuNTBd5puy77ya5ZGo5p2w5LymClswMDowNi43NV3nvJbmm7LvvJrlkajmnbDkvKYKWzAwOjA5LjAwXeWItuS9nOS6uu+8muWRqOadsOS8pgpbMDA6MTEuMjVd5ZCI5aOw77ya5ZGo5p2w5LymClswMDoxMy41MF3lkIjlo7DnvJblhpnvvJrlkajmnbDkvKYKWzAwOjE1Ljc1XeWQieS7lu+8muiUoeenkeS/ikFnYWluClswMDoxOC4wMF3otJ3mlq/vvJrpmYjku7vkvZEKWzAwOjIwLjI1Xem8k++8mumZiOafj+W3ngpbMDA6MjIuNTFd5b2V6Z+z5Yqp55CG77ya5YiY5YuH5b+XClswMDoyNC43Nl3lvZXpn7Plt6XnqIvvvJrmnajnkZ7ku6PvvIhBbGZhIFN0dWRpb++8iQpbMDA6MjcuMDFd5re36Z+z5bel56iL77ya5p2o5aSn57qs77yI5p2o5aSn57qs5b2V6Z+z5bel5L2c5a6k77yJClswMDoyOS4yNl3mlYXkuovnmoTlsI/pu4ToirEKWzAwOjMyLjcxXeS7juWHuueUn+mCo+W5tOWwsemjmOedgApbMDA6MzYuMjRd56ul5bm055qE6I2h56eL5Y2DClswMDozOS43NV3pmo/orrDlv4bkuIDnm7TmmYPliLDnjrDlnKgKWzAwOjQyLjkxXVJlIFNvIFNvIFNpIERvIFNpIExhIApbMDA6NDUuOTNdU28gTGEgU2kgU2kgU2kgU2kgTGEgU2kgTGEgU28gClswMDo0OS44N13lkLnnnYDliY3lpY/mnJvnnYDlpKnnqboKWzAwOjUzLjIwXeaIkeaDs+i1t+iKseeTo+ivleedgOaOieiQvQpbMDA6NTYuNzJd5Li65L2g57+Y6K++55qE6YKj5LiA5aSpClswMDo1OC44M13oirHokL3nmoTpgqPkuIDlpKkKWzAxOjAwLjYwXeaVmeWupOeahOmCo+S4gOmXtApbMDE6MDIuMzJd5oiR5oCO5LmI55yL5LiN6KeBClswMTowNC4xMl3mtojlpLHnmoTkuIvpm6jlpKkKWzAxOjA1LjgxXeaIkeWlveaDs+WGjea3i+S4gOmBjQpbMDE6MDkuOTld5rKh5oOz5Yiw5aSx5Y6755qE5YuH5rCU5oiR6L+Y55WZ552AClswMToxNi4xMl3lpb3mg7Plho3pl67kuIDpgY0KWzAxOjE3Ljk3XeS9oOS8muetieW+hei/mOaYr+emu+W8gApbMDE6MjQuOTFd5Yiu6aOO6L+Z5aSp5oiR6K+V6L+H5o+h552A5L2g5omLClswMTozMC40NV3kvYblgY/lgY/pm6jmuJDmuJDlpKfliLDmiJHnnIvkvaDkuI3op4EKWzAxOjM4Ljg4Xei/mOimgeWkmuS5heaIkeaJjeiDveWcqOS9oOi6q+i+uQpbMDE6NDUuNDRd562J5Yiw5pS+5pm055qE6YKj5aSp5Lmf6K645oiR5Lya5q+U6L6D5aW95LiA54K5ClswMTo1Mi44N13ku47liY3ku47liY3mnInkuKrkurrniLHkvaDlvojkuYUKWzAxOjU4LjU0XeS9huWBj+WBj+mjjua4kOa4kOaKiui3neemu+WQueW+l+Wlvei/nApbMDI6MDYuOTRd5aW95LiN5a655piT5Y+I6IO95YaN5aSa54ix5LiA5aSpClswMjoxMy41MF3kvYbmlYXkuovnmoTmnIDlkI7kvaDlpb3lg4/ov5jmmK/or7Tkuobmi5zmi5wKWzAyOjM0LjkwXeS4uuS9oOe/mOivvueahOmCo+S4gOWkqQpbMDI6MzYuODhd6Iqx6JC955qE6YKj5LiA5aSpClswMjozOC42Nl3mlZnlrqTnmoTpgqPkuIDpl7QKWzAyOjQwLjM5XeaIkeaAjuS5iOeci+S4jeingQpbMDI6NDIuMTVd5raI5aSx55qE5LiL6Zuo5aSpClswMjo0My44N13miJHlpb3mg7Plho3mt4vkuIDpgY0KWzAyOjQ4LjAwXeayoeaDs+WIsOWkseWOu+eahOWLh+awlOaIkei/mOeVmeedgApbMDI6NTQuMTVd5aW95oOz5YaN6Zeu5LiA6YGNClswMjo1Ni4wM13kvaDkvJrnrYnlvoXov5jmmK/nprvlvIAKWzAzOjAyLjkyXeWIrumjjui/meWkqeaIkeivlei/h+aPoeedgOS9oOaJiwpbMDM6MDguNDld5L2G5YGP5YGP6Zuo5riQ5riQ5aSn5Yiw5oiR55yL5L2g5LiN6KeBClswMzoxNi45NF3ov5jopoHlpJrkuYXmiJHmiY3og73lnKjkvaDouqvovrkKWzAzOjIzLjQzXeetieWIsOaUvuaZtOeahOmCo+WkqeS5n+iuuOaIkeS8muavlOi+g+WlveS4gOeCuQpbMDM6MzAuODdd5LuO5YmN5LuO5YmN5pyJ5Liq5Lq654ix5L2g5b6I5LmFClswMzozNy4xNF3lgY/lgY/po47muJDmuJDmiorot53nprvlkLnlvpflpb3ov5wKWzAzOjQ0Ljg4XeWlveS4jeWuueaYk+WPiOiDveWGjeWkmueIseS4gOWkqQpbMDM6NTEuNDJd5L2G5pWF5LqL55qE5pyA5ZCO5L2g5aW95YOP6L+Y5piv6K+05LqG5ouc5oucClswMzo1OC40OV3liK7po47ov5nlpKnmiJHor5Xov4fmj6HnnYDkvaDmiYsKWzA0OjAxLjk3XeS9huWBj+WBj+mbqOa4kOa4kOWkp+WIsOaIkeeci+S9oOS4jeingQpbMDQ6MDUuNjVd6L+Y6KaB5aSa5LmF5oiR5omN6IO95aSf5Zyo5L2g6Lqr6L65ClswNDowOS4wN13nrYnliLDmlL7mmbTpgqPlpKnkuZ/orrjmiJHkvJrmr5TovoPlpb3kuIDngrkKWzA0OjEyLjkyXeS7juWJjeS7juWJjeacieS4quS6uueIseS9oOW+iOS5hQpbMDQ6MTUuOTFd5L2G5YGP5YGP6Zuo5riQ5riQ5oqK6Led56a75ZC55b6X5aW96L+cClswNDoxOS4zOF3lpb3kuI3lrrnmmJPlj4jog73lho3lpJrniLHkuIDlpKkKWzA0OjIyLjg2XeS9huaVheS6i+eahOacgOWQjuS9oOWlveWDj+i/mOaYr+ivtOS6huaLnA==", "trans": "", "qrc": ""}}}
    """

    val LRCLIB_GET_JSON: String = """
        {"id": 197097, "trackName": "Hello", "artistName": "Adele", "albumName": "25", "duration": 296.0, "syncedLyrics": "[00:06.22] Hello, it's me\n[00:11.84] I was wondering if after all these years you'd like to meet\n[00:17.96] To go over everything"}
    """

    val LRCLIB_SEARCH_JSON: String = """
        [{"id": 29805894, "trackName": "Hello, Dolly!", "artistName": "Bette Midler ; Hello, Dolly! Ensemble", "albumName": "Hello, Dolly!", "duration": 401.0, "syncedLyrics": "[00:23.62] Hello, Harry\n[00:27.15] Well, Hello Louie"}, {"id": 20784791, "trackName": "Hello Hello Hello", "artistName": "Good Morning America", "albumName": "Hello Hello Hello", "duration": 233.0, "syncedLyrics": "[00:17.08] カラフルなランドセル\n[00:21.99] 背負う子供たち"}]
    """
}
