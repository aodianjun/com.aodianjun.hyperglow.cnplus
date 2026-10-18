package com.eza.hyperglow.customization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 配置文档的保存/加载往返必须保留自定义单词效果。
 *
 * 回归点：`CustomizationRepository.toSurfaceProfile()` 是逐字段重建（canonicalize 用），
 * 漏掉 `wordEffect` 会让设置页刚选的效果在保存后立刻消失，且配置备份/导出同样丢。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class CustomizationRepositoryWordEffectTest {
    private fun context() = RuntimeEnvironment.getApplication()

    private fun spec() = WordEffectSpec(
        id = "repo_roundtrip",
        name = "往返",
        tracks = listOf(
            WordEffectTrack(
                property = "translateY",
                keyframes = listOf(
                    WordEffectKeyframe(0f, 0.1f, "easeOut"),
                    WordEffectKeyframe(1f, 0f)
                )
            )
        )
    )

    @Test
    fun saveAndLoadKeepsWordEffect() {
        val document = SceneCompiler.safeDefaultDocument().copy(
            profiles = mapOf(
                SceneCompiler.SURFACE_LOCKSCREEN to SceneCompiler.safeLockscreenProfile(),
                SceneCompiler.SURFACE_AOD to SurfaceProfile(wordEffect = spec())
            )
        )
        assertTrue(CustomizationRepository.saveDocument(context(), document))
        val loaded = CustomizationRepository.loadDocument(context())
        assertEquals(
            normalizeWordEffectSpec(spec()),
            loaded.profiles[SceneCompiler.SURFACE_AOD]?.wordEffect
        )
    }
}
