package com.eza.hyperglow.bridge

import android.content.Context
import android.os.Binder

object CallerValidator {
    private const val SPOTIFY_PACKAGE = "com.spotify.music"
    private const val MAX_CACHE_SIZE = 16

    /** LRU cache: access-ordered, evicts eldest on insertion when full. */
    private val verdicts = object : LinkedHashMap<Int, Boolean>(MAX_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Boolean>?) =
            size > MAX_CACHE_SIZE
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
