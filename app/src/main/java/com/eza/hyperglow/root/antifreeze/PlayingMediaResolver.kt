package com.eza.hyperglow.root.antifreeze

import android.os.SystemClock
import java.lang.reflect.Method

/**
 * 动态识别"当前正在播放媒体"的 app uid。
 *
 * 数据源：system_server 内部 AudioService.getActivePlaybackConfigurations()。
 * ServiceManager 是 @hide API（编译期不可见），全部走反射；
 * 进程内自调用，绕开 AudioManager 公共 API 的 MODIFY_AUDIO_ROUTING 权限要求，
 * 同时避免在 system_server 里构造 Context。
 *
 * 结果缓存数秒：冻结调度可能在同一时刻批量处理多个进程，
 * 缓存既降低开销，也保证同一批调用判定一致。
 */
object PlayingMediaResolver {
    private const val CACHE_MS = 3000L
    private const val PLAYER_STATE_STARTED = 2

    @Volatile
    private var cachedAt = 0L

    @Volatile
    private var cachedUids: Set<Int> = emptySet()

    @Volatile
    private var getServiceMethod: Method? = null

    fun isActiveMediaUid(uid: Int): Boolean = uid in activeMediaUids()

    fun activeMediaUids(): Set<Int> {
        val now = SystemClock.elapsedRealtime()
        val cached = cachedUids
        if (cachedAt != 0L && now - cachedAt < CACHE_MS) return cached
        val fresh = runCatching { query() }.getOrDefault(emptySet())
        cachedAt = now
        cachedUids = fresh
        return fresh
    }

    private fun audioService(): Any? = runCatching {
        var method = getServiceMethod
        if (method == null) {
            method = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)
            getServiceMethod = method
        }
        method.invoke(null, "audio")
    }.getOrNull()

    private fun query(): Set<Int> {
        val service = audioService() ?: return emptySet()
        val list = runCatching {
            val method = service.javaClass.getMethod("getActivePlaybackConfigurations")
            method.invoke(service) as? List<*>
        }.getOrNull() ?: return emptySet()
        val out = mutableSetOf<Int>()
        for (item in list) {
            if (item == null) continue
            val state = runCatching {
                item.javaClass.getMethod("getPlayerState").invoke(item) as? Int
            }.getOrNull() ?: continue
            if (state != PLAYER_STATE_STARTED) continue
            val uid = runCatching {
                item.javaClass.getMethod("getClientUid").invoke(item) as? Int
            }.getOrNull() ?: continue
            // 只收普通应用 uid（首用户 10000-199999），屏蔽 system/root/媒体路由进程
            if (uid in 10000..199999) out.add(uid)
        }
        return out
    }
}
