package com.eza.hyperglow.root.surface

import com.eza.hyperglow.root.projection.LyricSurfaceKind

internal data class SurfaceRenderPolicy(
    val maxWidgets: Int,
    val artworkAllowed: Boolean,
    val progressAllowed: Boolean,
    val maximumHeightFraction: Float,
    val minimumAnimationDurationMs: Int,
    val maximumAnimationDurationMs: Int,
    val fullAodSupported: Boolean,
    val videoDepthSupported: Boolean
)

internal object SurfacePolicyResolver {
    fun resolve(
        surfaceKind: LyricSurfaceKind,
        fullAodSupported: Boolean = false,
        videoDepthSupported: Boolean = false
    ): SurfaceRenderPolicy = when (surfaceKind) {
        LyricSurfaceKind.LOCKSCREEN -> SurfaceRenderPolicy(
            maxWidgets = 8,
            artworkAllowed = false,
            progressAllowed = true,
            maximumHeightFraction = 0.8f,
            minimumAnimationDurationMs = 150,
            maximumAnimationDurationMs = 600,
            fullAodSupported = false,
            videoDepthSupported = false
        )
        LyricSurfaceKind.AOD -> SurfaceRenderPolicy(
            maxWidgets = 4,
            artworkAllowed = false,
            progressAllowed = false,
            // 更新自上游 748912e:全屏抑制画布下 AOD 高度上限放开到 0.9。CN+ 画布内部仍
            // 用最大高度档位(见 SceneCompiler,默认为内容贴合),此处仅作为硬上限不再 0.5 截断。
            maximumHeightFraction = 0.9f,
            minimumAnimationDurationMs = 150,
            maximumAnimationDurationMs = 600,
            fullAodSupported = fullAodSupported,
            videoDepthSupported = videoDepthSupported
        )
    }
}
