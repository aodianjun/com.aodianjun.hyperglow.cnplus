package com.eza.hyperglow.bridge

import android.content.Context
import android.os.Binder

object CallerValidator {
    private const val SPOTIFY_PACKAGE = "com.spotify.music"
    private const val MAX_VERDICTS = 16

    // LRU:满额淘汰最久未访问的判定,而不是整体清空后全部重查。
    private val verdicts = object : LinkedHashMap<Int, Boolean>(MAX_VERDICTS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Boolean>): Boolean =
            size > MAX_VERDICTS
    }

    @Synchronized
    fun isSpotify(context: Context): Boolean {
        val uid = Binder.getCallingUid()
        verdicts[uid]?.let { return it }
        return context.packageManager.getPackagesForUid(uid).orEmpty()
            .contains(SPOTIFY_PACKAGE)
            .also { verdicts[uid] = it }
    }

    /** Clears the UID verdict cache. For unit tests that need deterministic per-call results. */
    @Synchronized
    fun clearCache() {
        verdicts.clear()
    }
}
