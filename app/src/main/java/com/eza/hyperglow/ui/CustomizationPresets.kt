package com.eza.hyperglow.ui

import android.content.Context
import android.widget.Toast
import com.eza.hyperglow.R
import com.eza.hyperglow.customization.CustomizationDocument
import com.eza.hyperglow.customization.CustomizationRepository
import com.eza.hyperglow.customization.SceneCompiler
import com.eza.hyperglow.customization.SurfaceProfile

/**
 * 歌词外观预设包：一键应用整套「息屏 + 锁屏」歌词观感（配色/动画/辉光/字体/歌曲信息/
 * 封面/辅助行等），新装应用不必逐项手调。
 *
 * 实现口径：
 * - 预设是**纯数据**（完整的 [CustomizationDocument]，两个曲面都给全套 profile），落盘
 *   走 [CustomizationRepository.saveDocument]——它内部 canonicalize 会把文档重建成当前
 *   版本的规范形，预设代码不与文档版本演进耦合；
 * - 预设只动**外观文档**，不触碰应用级行为偏好（AOD 开关/保活/亮度等）——外观一键换、
 *   行为不劫持；
 * - 配色 token 用渲染侧语义色键（与 SettingsPalette.PaletteColor 同一 词表），hex 直接
 *   落 token，取色器里可见可继续微调。
 */
internal object CustomizationPresets {

    /**
     * 一个预设：稳定 id + 文档工厂。工厂按调用构造新文档（同一预设可重复应用），
     * 锁屏与息屏共用一份观感 profile，仅 enabled 各自为 true——预设的意义就是
     * 「装完即用」，两个面都直接点亮。
     */
    internal data class Preset(
        val id: String,
        val nameRes: Int,
        val summaryRes: Int,
        val build: () -> CustomizationDocument
    )

    /** 预设清单；顺序即选择器展示顺序，「经典暗夜」在最上（等于回到出厂观感）。 */
    internal val ALL: List<Preset> = listOf(
        Preset(
            id = "classic_dark",
            nameRes = R.string.preset_classic_dark_name,
            summaryRes = R.string.preset_classic_dark_summary
        ) { classicDark() },
        Preset(
            id = "warm_gold",
            nameRes = R.string.preset_warm_gold_name,
            summaryRes = R.string.preset_warm_gold_summary
        ) { warmGold() },
        Preset(
            id = "ice_blue",
            nameRes = R.string.preset_ice_blue_name,
            summaryRes = R.string.preset_ice_blue_summary
        ) { iceBlue() },
        Preset(
            id = "sakura",
            nameRes = R.string.preset_sakura_name,
            summaryRes = R.string.preset_sakura_summary
        ) { sakura() },
        Preset(
            id = "mint",
            nameRes = R.string.preset_mint_name,
            summaryRes = R.string.preset_mint_summary
        ) { mint() },
        Preset(
            id = "showcase",
            nameRes = R.string.preset_showcase_name,
            summaryRes = R.string.preset_showcase_summary
        ) { showcase() }
    )

    /**
     * 应用预设：整份文档替换保存（外观是整体切换，不做字段合并——合并会让
     * 「换预设换不干净」）。成功返回 true 并触发运行时同步回调。
     */
    internal fun apply(context: Context, preset: Preset): Boolean =
        CustomizationRepository.saveDocument(context, preset.build())

    /** 应用成功的 toast（失败时 saveDocument 返回 false，由调用方提示）。 */
    internal fun toastApplied(context: Context, preset: Preset, applied: Boolean) {
        Toast.makeText(
            context,
            context.getString(
                if (applied) R.string.toast_preset_applied else R.string.toast_preset_apply_failed,
                context.getString(preset.nameRes)
            ),
            Toast.LENGTH_LONG
        ).show()
    }

    // --- 预设定义（纯数据） ---

    private fun document(
        id: String,
        name: String,
        profile: SurfaceProfile
    ): CustomizationDocument = CustomizationDocument(
        id = id,
        name = name,
        linkSurfaces = false,
        metadataParts = "title,artist",
        profiles = linkedMapOf(
            SceneCompiler.SURFACE_LOCKSCREEN to profile,
            SceneCompiler.SURFACE_AOD to profile
        )
    )

    /** 1. 经典暗夜：出厂观感（渐变扫光、无辉光、黑卡），一键回到默认。 */
    private fun classicDark(): CustomizationDocument = document(
        "preset_classic_dark",
        "Classic Dark",
        SurfaceProfile(
            enabled = true,
            backgroundStyle = "card",
            cardColor = "black",
            cardAlpha = 85
        )
    )

    /** 2. 暖金辉光：BetterLyrics 逐字发光 + 暖金主色，单行歌曲信息。 */
    private fun warmGold(): CustomizationDocument = document(
        "preset_warm_gold",
        "Warm Gold",
        SurfaceProfile(
            enabled = true,
            animation = "BetterLyrics",
            glow = "On",
            weight = "Bold",
            palette = mapOf(
                "sungText" to "#FFD9A0",
                "glow" to "#FFD9A0",
                "unsungText" to "#8C8378",
                "nextLineText" to "#6E675C",
                "metadataText" to "#B3A88F",
                "accent" to "#FFD9A0",
                "secondaryText" to "#D9C9A8"
            ),
            lineTransition = "Fade up",
            backgroundStyle = "card",
            cardColor = "black",
            cardAlpha = 70,
            metadataVisible = true,
            metadataAnchor = "top",
            metadataLayout = "single",
            metadataSeparators = "dot",
            showNextLine = true
        )
    )

    /** 3. 冰蓝夜色：渐变扫光 + 冰蓝主色 + 半透明深灰卡，翻译辅助行。 */
    private fun iceBlue(): CustomizationDocument = document(
        "preset_ice_blue",
        "Ice Blue",
        SurfaceProfile(
            enabled = true,
            animation = "Gradient",
            glow = "On",
            secondaryMode = "Translation",
            palette = mapOf(
                "sungText" to "#A9D9FF",
                "glow" to "#A9D9FF",
                "unsungText" to "#5A6B7C",
                "nextLineText" to "#46586A",
                "metadataText" to "#9FB8CC",
                "accent" to "#7FB8E8",
                "secondaryText" to "#A9D9FF"
            ),
            lineTransition = "Slide up",
            backgroundStyle = "card",
            cardColor = "dark_gray",
            cardAlpha = 55,
            metadataVisible = true,
            metadataAnchor = "top",
            metadataLayout = "stacked",
            showNextLine = false
        )
    )

    /** 4. 樱花甜心：逐字发光 + 樱粉主色 + 旋转封面 + 音标辅助行。 */
    private fun sakura(): CustomizationDocument = document(
        "preset_sakura",
        "Sakura",
        SurfaceProfile(
            enabled = true,
            animation = "BetterLyrics",
            glow = "On",
            fontFamily = "apple",
            secondaryMode = "Transliteration",
            secondaryWordKaraoke = true,
            palette = mapOf(
                "sungText" to "#FFC9DE",
                "glow" to "#FFB7D0",
                "unsungText" to "#8A7580",
                "nextLineText" to "#6E5A66",
                "metadataText" to "#C9A8B8",
                "accent" to "#FF9EC4",
                "secondaryText" to "#E8C9D8"
            ),
            lineTransition = "zoom_out_zoom_in",
            backgroundStyle = "card",
            cardColor = "black",
            cardAlpha = 60,
            metadataVisible = true,
            metadataAnchor = "top",
            metadataLayout = "stacked",
            artworkVisible = true,
            artworkShape = "circle",
            artworkSpin = true
        )
    )

    /** 5. 薄荷清新：极简档 + 居中对齐 + 薄荷绿，单行歌曲信息。 */
    private fun mint(): CustomizationDocument = document(
        "preset_mint",
        "Mint",
        SurfaceProfile(
            enabled = true,
            animation = "Minimal",
            glow = "Off",
            alignment = "center",
            palette = mapOf(
                "sungText" to "#B8F0C9",
                "glow" to "#B8F0C9",
                "unsungText" to "#5C7064",
                "nextLineText" to "#4A5C52",
                "metadataText" to "#9CB8A8",
                "accent" to "#7FD9A0",
                "secondaryText" to "#A8D9BC"
            ),
            lineTransition = "Crossfade",
            backgroundStyle = "card",
            cardColor = "blur",
            cardAlpha = 65,
            metadataVisible = true,
            metadataAnchor = "top",
            metadataLayout = "single",
            metadataSeparators = "slash"
        )
    )

    /** 6. 全开体验：逐字发光 + 双辅助行 + 下一行 + 封面 + 和声，功能全演示。 */
    private fun showcase(): CustomizationDocument = document(
        "preset_showcase",
        "Showcase",
        SurfaceProfile(
            enabled = true,
            animation = "BetterLyrics",
            glow = "On",
            weight = "Medium",
            secondaryMode = "Transliteration,Translation,BackgroundVocal",
            secondaryWordKaraoke = true,
            secondaryTextBright = true,
            showNextLine = true,
            secondaryNextLine = true,
            nextLineAux = true,
            rubyVisible = true,
            interludeCountdown = true,
            duetConcurrent = true,
            palette = mapOf(
                "sungText" to "#D9C9FF",
                "glow" to "#C0AFFF",
                "unsungText" to "#6E6880",
                "nextLineText" to "#585270",
                "metadataText" to "#B0A8C9",
                "accent" to "#B79CFF",
                "secondaryText" to "#CFC4E8"
            ),
            lineTransition = "slide_out_left_slide_in_right",
            lineTransitionSpeed = "Fast",
            backgroundStyle = "card",
            cardColor = "black",
            cardAlpha = 65,
            metadataVisible = true,
            metadataAnchor = "top",
            metadataLayout = "stacked",
            artworkVisible = true,
            artworkShape = "circle",
            artworkSpin = true,
            artworkSpinWhenPaused = true
        )
    )
}
