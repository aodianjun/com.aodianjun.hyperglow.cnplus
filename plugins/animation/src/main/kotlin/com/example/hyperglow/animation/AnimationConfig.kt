package com.example.hyperglow.animation

/** 插件配置键与默认值：与 manifest.json 里声明的设置项一一对应。 */
object AnimationConfig {
    const val KEY_ENABLED = "animation_enabled"
    const val KEY_MODE = "animation_mode"
    const val KEY_INTENSITY = "animation_intensity"
    const val KEY_STEP_MS = "animation_step_ms"
    const val KEY_GLYPH_STYLE = "animation_glyph_style"

    const val MODE_BREATHING = "breathing"
    const val MODE_PARTICLES = "particles"
    const val MODE_RIPPLE = "ripple"
    const val MODE_SPECTRUM = "spectrum"
    const val MODE_NOTES = "notes"
    const val MODE_WAVE = "wave"

    const val STYLE_SYMBOL = "symbol"
    const val STYLE_ASCII = "ascii"

    const val DEFAULT_INTENSITY: Float = 60f
    const val MAX_INTENSITY: Float = 100f

    // 宿主 SLIDER 类型统一以 Float 存取，插件侧仍按毫秒做整型运算。
    const val DEFAULT_STEP_MS_FLOAT: Float = 120f
    const val MIN_STEP_MS: Float = 20f
    const val MAX_STEP_MS: Float = 500f
}
