package com.example.hyperglow.amllttml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientParseTest {

    /** 真实响应形状（取自 api.amll.dev，2026-10 实测）。 */
    private val searchBody = """
        {"status":200,"data":{"items":[
        {"id":696943504933499,"filename":"1777956361097-0-ae8pwf4l.ttml","createdAt":1777956361097,
         "musicNames":["蝴蝶"],"artistNames":["洛天依Official"],"albumNames":["再生"],
         "ncmMusicIds":["2604527599"],"qqMusicIds":[],"appleMusicIds":[],"spotifyIds":[],"isrcs":[],
         "authorIds":[],"authorUsernames":[]}
        ],"pagination":{"page":1,"pageSize":50,"total":1}}}
    """.trimIndent()

    private val getBody = """
        {"status":200,"data":{"id":1,"filename":"x.ttml","musicNames":["蝴蝶"],
         "artistNames":["洛天依Official"],"albumNames":[],"lyrics":"<tt>hello</tt>","format":"ttml"}}
    """.trimIndent()

    @Test
    fun parsesSearchResponse() {
        val items = AmllTtmlClient.parseSearch(searchBody)
        assertNotNull(items)
        assertEquals(1, items.size)
        val item = items.first()
        assertEquals(696943504933499L, item.id)
        assertEquals(listOf("蝴蝶"), item.musicNames)
        assertEquals(listOf("洛天依Official"), item.artistNames)
    }

    @Test
    fun parsesGetResponseTtml() {
        assertEquals("<tt>hello</tt>", AmllTtmlClient.parseGetTtml(getBody))
        assertNull(AmllTtmlClient.parseGetTtml("""{"status":200,"data":{"lyrics":""}}"""))
        assertNull(AmllTtmlClient.parseGetTtml("not json"))
        assertNull(AmllTtmlClient.parseGetTtml("""{"status":404,"error":"Not Found"}"""))
    }

    @Test
    fun searchParseToleratesGarbage() {
        assertNull(AmllTtmlClient.parseSearch("not json"))
        assertNull(AmllTtmlClient.parseSearch("""{"status":400,"error":"Bad Request"}"""))
        val empty = AmllTtmlClient.parseSearch("""{"status":200,"data":{"items":[],"pagination":{}}}""")
        assertNotNull(empty)
        assertTrue(empty.isEmpty())
    }

    @Test
    fun normalizeBaseUrlHandlesUserInput() {
        assertEquals("https://api.amll.dev", AmllTtmlClient.normalizeBaseUrl(null))
        assertEquals("https://api.amll.dev", AmllTtmlClient.normalizeBaseUrl("  "))
        assertEquals("https://api.amll.dev", AmllTtmlClient.normalizeBaseUrl("https://api.amll.dev/"))
        assertEquals("https://api.amll.dev", AmllTtmlClient.normalizeBaseUrl("https://api.amll.dev"))
        // 无 scheme 的输入回退默认（避免拼出非法 URL）
        assertEquals("https://api.amll.dev", AmllTtmlClient.normalizeBaseUrl("api.amll.dev"))
        assertEquals("http://192.168.1.10:3000", AmllTtmlClient.normalizeBaseUrl("http://192.168.1.10:3000/"))
    }

    @Test
    fun buildUrlEncodesParams() {
        val url = AmllTtmlClient.buildUrl(
            "https://api.amll.dev",
            "/v1/lyrics/search",
            listOf("musicName" to "蝴蝶 测试", "artistName" to "洛天依Official"),
        )
        assertTrue(url.startsWith("https://api.amll.dev/v1/lyrics/search?"))
        assertTrue(url.contains("musicName=%E8%9D%B4%E8%9D%B6+%E6%B5%8B%E8%AF%95"))
        assertTrue(url.contains("artistName="))
    }
}
