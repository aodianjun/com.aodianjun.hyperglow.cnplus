package com.eza.hyperglow.root.surface

import com.eza.hyperglow.root.projection.LyricSurfaceKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacePolicyResolverTest {
    @Test
    fun extendedAodModesMapCapabilitiesButKeepStableWidgetsDisabled() {
        val policy = SurfacePolicyResolver.resolve(
            LyricSurfaceKind.AOD,
            fullAodSupported = true,
            videoDepthSupported = true
        )

        assertTrue(policy.fullAodSupported)
        assertTrue(policy.videoDepthSupported)
        // 歌曲图片有界渲染器已落地(校对取图 + 有界 JPEG + 左置单槽),AOD 与锁屏同放行。
        assertTrue(policy.artworkAllowed)
        assertFalse(policy.progressAllowed)
    }

    @Test
    fun lockscreenArtworkAllowedWithBoundedProvider() {
        val policy = SurfacePolicyResolver.resolve(LyricSurfaceKind.LOCKSCREEN)

        assertTrue(policy.artworkAllowed)
        assertTrue(policy.progressAllowed)
    }
}
