package com.eza.hyperglow.bridge

import android.os.Binder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CallerValidatorTest {

    @Before
    fun clearCache() {
        CallerValidator.clearCache()
    }

    private fun context() = RuntimeEnvironment.getApplication()

    private fun setCallerPackages(vararg packages: String) {
        shadowOf(context().packageManager)
            .setPackagesForUid(Binder.getCallingUid(), *packages)
    }

    @Test
    fun spotifyCallerIsAccepted() {
        setCallerPackages("com.spotify.music")
        assertTrue(CallerValidator.isSpotify(context()))
    }

    @Test
    fun nonSpotifyCallerIsRejected() {
        setCallerPackages("com.example.other")
        assertFalse(CallerValidator.isSpotify(context()))
    }

    @Test
    fun sharedUidContainingSpotifyIsAccepted() {
        setCallerPackages("com.example.other", "com.spotify.music")
        assertTrue(CallerValidator.isSpotify(context()))
    }

    @Test
    fun emptyPackageListIsRejected() {
        setCallerPackages()
        assertFalse(CallerValidator.isSpotify(context()))
    }

    @Test
    fun verdictsAreCachedPerUid() {
        val context = context()
        setCallerPackages("com.spotify.music")
        assertTrue(CallerValidator.isSpotify(context))

        // UID 对应包名在首次判定后变化,缓存应继续返回旧结论。
        setCallerPackages("com.example.other")
        assertTrue(CallerValidator.isSpotify(context))
    }
}
