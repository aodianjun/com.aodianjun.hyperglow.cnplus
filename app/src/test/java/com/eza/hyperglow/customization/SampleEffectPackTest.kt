package com.eza.hyperglow.customization

import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 仓库里的示例效果包必须能被**真实的** Kotlin 规范化器接受（防止汇编工具/文档与
 * `EffectProgram` 的 opcode/字段/限额漂移）。
 */
class SampleEffectPackTest {
    @Test
    fun programPackIsAcceptedByTheRealNormalizer() {
        val file = listOf(
            File("../samples/word-effect-program-pack.json"),
            File("samples/word-effect-program-pack.json")
        ).firstOrNull { it.isFile } ?: return
        val pack = SceneCompiler.json.decodeFromString<WordEffectPack>(file.readText())
        assertEquals(WordEffectPackStore.PACK_FORMAT, pack.format)
        assertEquals(WordEffectPackStore.PACK_VERSION, pack.version)
        assertTrue(pack.effects.isNotEmpty())
        for (effect in pack.effects) {
            val normalized = normalizeWordEffectSpec(effect)
            assertNotNull("effect ${effect.id} rejected", normalized)
            assertNotNull("effect ${effect.id} lost its program", normalized!!.program)
        }
    }
}
