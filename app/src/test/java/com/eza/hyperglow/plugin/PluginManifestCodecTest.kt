package com.eza.hyperglow.plugin

import com.lidesheng.hyperlyric.plugin.api.PluginSettingInputType
import com.lidesheng.hyperlyric.plugin.api.PluginSettingType
import com.lidesheng.hyperlyric.plugin.api.PluginSettingValuePresentation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PluginManifestCodecTest {

    /**
     * 官方 HyperLyric 插件包的真实 manifest.json：顶层与设置的 wire 字段名是
     * `type` / `valuePresentation` / `inputType`（对应宿主属性 typeWire / valuePresentationWire / inputTypeWire）。
     */
    private val realisticManifest = """
        {
          "id": "hyperlyric.ai.translation",
          "name": "OpenAI 翻译",
          "apiVersion": 1,
          "entry": "com.lidesheng.hyperlyric.plugin.ai.translation.AiTranslationPlugin",
          "activationSettingKey": "enabled",
          "settings": [
            { "type": "switch", "key": "enabled", "title": "OpenAI 翻译", "default": false },
            { "type": "multiSelect", "key": "skip_languages", "title": "跳过语言", "options": [
                { "value": "zh", "label": "中文" }, { "value": "en", "label": "英文" } ] },
            { "type": "text", "key": "endpoint", "title": "接口", "valuePresentation": "endAction" },
            { "type": "slider", "key": "temperature", "title": "温度", "inputType": "number", "min": 0, "max": 2, "step": 0.1 },
            { "type": "select", "key": "model", "title": "模型", "options": [ { "value": "gpt", "label": "GPT" } ] }
          ]
        }
    """.trimIndent()

    @Test
    fun decodesRealisticManifestWithWireFieldNames() {
        val manifest = PluginManifestCodec.decode(realisticManifest)
        assertNotNull(manifest)
        manifest!!
        assertEquals("hyperlyric.ai.translation", manifest.id)

        assertEquals(5, manifest.settings.size)

        // switch: type=switch → typeWire=switch，默认 false。
        val switch = manifest.settings[0]
        assertEquals("switch", switch.typeWire)
        assertFalse(switch.defaultBoolean())

        // multiSelect + options。
        val multiselect = manifest.settings[1]
        assertEquals(PluginSettingType.MULTI_SELECT, multiselect.type)
        assertEquals(2, multiselect.options.size)

        // text + valuePresentation=endAction（wire 名被正确反序列化到 valuePresentationWire）。
        val text = manifest.settings[2]
        assertEquals("text", text.typeWire)
        assertEquals("endAction", text.valuePresentationWire)
        assertEquals(PluginSettingValuePresentation.END_ACTION, text.valuePresentation)

        // slider + inputType=number。
        val slider = manifest.settings[3]
        assertEquals("slider", slider.typeWire)
        assertEquals("number", slider.inputTypeWire)
        assertEquals(PluginSettingInputType.NUMBER, slider.inputType)

        // select 需 options，validate 应通过。
        assertNull(manifest.validate())
    }

    @Test
    fun settingMissingRequiredTypeIsRejectedByValidate() {
        // type 是必填。setting 缺 type 时整包仍能解析（顶层字段齐全），但 validate() 拒绝。
        val manifest = PluginManifestCodec.decode("""
            { "id": "a.b.c", "name": "x", "entry": "x.Y", "settings": [ { "key": "k", "title": "t" } ] }
        """.trimIndent())
        assertNotNull(manifest)
        assertTrue(manifest!!.validate().orEmpty().contains("unknown setting type"))
    }

    @Test
    fun manifestUsingInternalPropertyNamesIsRejected() {
        // wire 名才是线上契约："typeWire" 是未知字段被忽略，settings 的 type 缺失，
        // typeWire 兜底为 null，整包仍可解析、由 validate() 给出明确拒绝信息
        // （而不是让 decode 静默产出半个 manifest 或直接吞异常返回 null）。
        val internalNamed = realisticManifest.replace(
            "\"type\": \"switch\"",
            "\"typeWire\": \"switch\""
        )

        val manifest = PluginManifestCodec.decode(internalNamed)

        assertNotNull(manifest)
        assertNull(manifest!!.settings[0].typeWire)
        assertEquals("unknown setting type: null", manifest.validate())
    }

    @Test
    fun unknownWireTypeValueSurfacesThroughValidate() {
        // 未知 type 值同样不炸解析：typeWire 原样保留、type 枚举为 null，
        // validate() 必须把原始值报出来，方便插件作者定位拼写错误。
        val unknownType = realisticManifest.replace(
            "\"type\": \"switch\"",
            "\"type\": \"magicToggle\""
        )

        val manifest = PluginManifestCodec.decode(unknownType)

        assertNotNull(manifest)
        assertEquals("unknown setting type: magicToggle", manifest!!.validate())
    }
}

