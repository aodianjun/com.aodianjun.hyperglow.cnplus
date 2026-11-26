# 自动压暗移植指南

本文把 HyperGlow CN+ 里的「材质自动压暗」拆成可移植的三层：**纯曲线逻辑**、**背景亮度采样**、**渲染注入**。除 Android SDK 外不依赖 HyperGlow 或 Miuix。

当前实现来源：

| 模块 | 文件 |
|---|---|
| 曲线与配置 | `app/src/main/java/com/eza/hyperglow/customization/CustomizationModels.kt` |
| 锁屏亮度采样 | `app/src/main/java/com/eza/hyperglow/root/lockscreen/LyricCardBackdropRenderer.kt` |
| 锁屏黑层绘制 | `app/src/main/java/com/eza/hyperglow/root/lockscreen/AdaptiveLyricCardBackgroundView.kt` |
| 应用玻璃黑层 | `app/src/main/java/com/eza/hyperglow/ui/AppGlassMaterial.kt` |
| 应用背景亮度 | `app/src/main/java/com/eza/hyperglow/ui/MainActivity.kt` |
| 设置界面 | `app/src/main/java/com/eza/hyperglow/ui/CardMaterialTuningSection.kt` |

---

## 1. 行为定义

- 输入：背景亮度 `luminance`，范围 `0..1`
- 输出：黑色色层 alpha，范围 `0..1`
- 背景越亮，曲线斜率越高；起始点以下完全不压暗
- 黑层只作用于材质内部，不修改背景图或壁纸本身

曲线公式：

```text
max       = maxPercent / 100
start     = 0.15 / 0.35 / 0.55 / 0.75
progress  = (luminance - start) / (1 - start)
exponent  = 1 + sensitivity / 100 * 2

alpha     = max * progress ^ exponent
```

参数：

| 参数 | 范围 | 默认 | 含义 |
|---|---|---|---|
| `maxPercent` | 0..100 | 10 | 纯白背景下的最大黑层 alpha |
| `sensitivity` | 0..100 | 50 | 曲线指数，越高亮部斜率越大 |
| `start` | shadow/mid/highlight/white | mid | 从哪一档亮度开始压暗 |
| `sampleIntervalMs` | 0/500/1000/2000/5000 | 1000 | 亮度刷新间隔；0 = 只采一次 |

---

## 2. 纯函数核心

复制到目标模块，改包名即可：

```kotlin
import kotlin.math.pow

enum class DarkenStart(val threshold: Float) {
    Shadow(0.15f),
    Mid(0.35f),
    Highlight(0.55f),
    White(0.75f)
}

data class AutoDarkenConfig(
    val maxPercent: Int = 10,
    val sensitivity: Int = 50,
    val start: DarkenStart = DarkenStart.Mid,
    val sampleIntervalMs: Int = 1000
)

fun autoDarkenAlpha(luminance: Float, config: AutoDarkenConfig): Float {
    val max = config.maxPercent.coerceIn(0, 100) / 100f
    if (max <= 0f) return 0f

    val brightness = luminance.coerceIn(0f, 1f)
    if (brightness <= config.start.threshold) return 0f

    val progress = (
        (brightness - config.start.threshold) /
            (1f - config.start.threshold)
        ).coerceIn(0f, 1f)

    val exponent = 1f + config.sensitivity.coerceIn(0, 100) / 100f * 2f
    return (max * progress.pow(exponent)).coerceIn(0f, 1f)
}
```

---

## 3. 背景亮度采样器

目标模块如果使用壁纸或背景图，可以直接用下面这个独立类。它不依赖 Compose。

```kotlin
import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class WallpaperLuminanceSampler(
    private val context: Context,
    private val onLuminance: (Float) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wallpaper-luminance").apply { isDaemon = true }
    }

    private var intervalMs = 1000
    private var runnable: Runnable? = null
    private var inFlight = false
    private var loadAttempted = false
    private var wallpaper: Bitmap? = null

    @Volatile
    private var released = false

    fun start(intervalMs: Int) {
        this.intervalMs = intervalMs.takeIf { it in SAMPLE_INTERVALS } ?: 1000
        ensureLoaded()
        restart()
    }

    fun stop() {
        runnable?.let(handler::removeCallbacks)
        runnable = null
    }

    fun release() {
        released = true
        stop()
        executor.shutdownNow()
        wallpaper?.recycle()
        wallpaper = null
    }

    private fun ensureLoaded() {
        if (released || wallpaper != null || loadAttempted) return
        loadAttempted = true
        executor.execute {
            val loaded = loadWallpaper()
            val luminance = loaded?.let(::averageLuminance) ?: wallpaperColorsLuminance()
            handler.post {
                if (released) {
                    loaded?.recycle()
                    return@post
                }
                wallpaper = loaded
                onLuminance(luminance)
            }
        }
    }

    private fun restart() {
        stop()
        if (released || intervalMs <= 0) return
        val tick = object : Runnable {
            override fun run() {
                if (released) return
                refresh()
                handler.postDelayed(this, intervalMs.toLong())
            }
        }
        runnable = tick
        handler.postDelayed(tick, intervalMs.toLong())
    }

    private fun refresh() {
        if (inFlight || released) return
        inFlight = true
        executor.execute {
            val luminance = runCatching {
                wallpaper?.let(::averageLuminance) ?: wallpaperColorsLuminance()
            }.getOrDefault(0f)
            handler.post {
                inFlight = false
                if (!released) onLuminance(luminance)
            }
        }
    }

    private fun loadWallpaper(): Bitmap? {
        val manager = runCatching { WallpaperManager.getInstance(context) }.getOrNull() ?: return null

        val lockBitmap = runCatching {
            @Suppress("DEPRECATION")
            manager.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use { file ->
                BitmapFactory.decodeFileDescriptor(file.fileDescriptor)
            }
        }.getOrNull()
        if (lockBitmap != null) return lockBitmap

        val lockDrawable = runCatching {
            (manager.getDrawable(WallpaperManager.FLAG_LOCK) as? BitmapDrawable)?.bitmap
        }.getOrNull()
        if (lockDrawable != null) return lockDrawable

        // 动态壁纸没有静态锁屏图，返回 null，由主色估算亮度。
        if (runCatching { manager.wallpaperInfo }.getOrNull() != null) return null

        return runCatching { (manager.drawable as? BitmapDrawable)?.bitmap }.getOrNull()
    }

    private fun averageLuminance(source: Bitmap): Float {
        val stepX = (source.width / 32).coerceAtLeast(1)
        val stepY = (source.height / 32).coerceAtLeast(1)
        var sum = 0.0
        var count = 0
        var y = 0
        while (y < source.height) {
            var x = 0
            while (x < source.width) {
                val color = source.getPixel(x, y)
                sum += (
                    0.2126 * android.graphics.Color.red(color) +
                        0.7152 * android.graphics.Color.green(color) +
                        0.0722 * android.graphics.Color.blue(color)
                    ) / 255.0
                count++
                x += stepX
            }
            y += stepY
        }
        return if (count == 0) 0f else (sum / count).toFloat()
    }

    private fun wallpaperColorsLuminance(): Float {
        val colors = runCatching {
            WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_LOCK)
        }.getOrNull() ?: return 0f
        val color = colors.primaryColor.toArgb()
        return (
            0.2126 * android.graphics.Color.red(color) +
                0.7152 * android.graphics.Color.green(color) +
                0.0722 * android.graphics.Color.blue(color)
            ).toFloat() / 255f
    }

    private companion object {
        val SAMPLE_INTERVALS = listOf(0, 500, 1000, 2000, 5000)
    }
}
```

线程约束：

- 解码壁纸、抽样、读取 `WallpaperColors` 全部在后台线程
- `onLuminance` 一定在主线程回调
- `loadAttempted` 防止动态壁纸没有静态图时反复重载
- `inFlight` 防止多个采样任务重叠
- `release()` 必须移除 Handler 回调并回收位图

---

## 4. 渲染注入

### 4.1 Compose + Miuix blur

在模糊管线的色层里追加黑色 `BlendColorEntry`：

```kotlin
val alpha = autoDarkenAlpha(luminance, config)

blendColors(
    BlurColors(
        blendColors = buildList {
            add(BlendColorEntry(tint, BlurBlendMode.SrcOver))
            if (alpha > 0f) {
                add(
                    BlendColorEntry(
                        Color.Black.copy(alpha = alpha),
                        BlurBlendMode.SrcOver
                    )
                )
            }
        }
    )
)
```

HyperGlow 对应：

- `AppGlassMaterial.appGlassMaterial()`
- `rememberAppGlassComponentStyle()`（官方玻璃组件）

### 4.2 Canvas / 自绘 View

黑层必须画在背景模糊与 tint 之后、文字和边缘高光之前：

```kotlin
val alpha = autoDarkenAlpha(luminance, config)
if (alpha > 0f) {
    paint.color = android.graphics.Color.argb(
        (alpha * 255f).roundToInt().coerceIn(0, 255),
        0, 0, 0
    )
    canvas.drawRoundRect(bounds, radius, radius, paint)
}
```

HyperGlow 对应 `AdaptiveLyricCardBackgroundView.drawMaterialDarken()`。

### 4.3 自定义 shader

如果目标模块已有 AGSL / RenderEffect 管线，把 alpha 作为 uniform：

```glsl
uniform float uDarkenAlpha;
...
rgb = mix(rgb, float3(0.0), uDarkenAlpha);
```

这样不需要额外 View 层。

---

## 5. 配置持久化

目标模块的配置类加入：

```kotlin
val darkenMaxPercent: Int = 10
val darkenSensitivity: Int = 50
val darkenStart: String = "mid"
val darkenSampleMs: Int = 1000
```

归一化：

- `darkenMaxPercent` / `darkenSensitivity` → `0..100`
- `darkenStart` → 只接受 `shadow/mid/highlight/white`
- `darkenSampleMs` → 只接受 `0/500/1000/2000/5000`

旧配置缺字段时使用默认值，不要改变已有 JSON 的序列化结构。

---

## 6. 设置界面

推荐控件：

- 压暗起始点：内联 `ArrowPreference` + `RadioButtonPreference`
- 采样频率：同样内联选择
- 压暗上限 / 灵敏度：两个 `SliderPreference`

不要用 `OverlayDropdownPreference`：如果设置页在 `WindowDialog` 或没有 Overlay 宿主的容器里，点击不会展开。HyperGlow 已经踩过这个坑，改成内联单选后正常。

---

## 7. 移植检查表

- [ ] 配置字段与归一化已加入
- [ ] `autoDarkenAlpha()` 已复制，包名已替换
- [ ] 采样器只在页面可见时启动，销毁时 `release()`
- [ ] 采样频率 0 时只采一次
- [ ] 静态壁纸/背景图走 32×32 抽样
- [ ] 动态壁纸走 `WallpaperColors`
- [ ] 黑层在模糊/tint 之后、内容之前
- [ ] 纯色材质跳过压暗
- [ ] 系统实时模糊路径也走卡片本体黑层，不只改静态渲染器
- [ ] 应用背景图本身不被修改
- [ ] 设置页不使用 Overlay 下拉

---

## 8. HyperGlow 专有部分（移植时舍弃）

| HyperGlow 实现 | 移植替换 |
|---|---|
| `CardMaterialTuning` | 目标模块自己的配置类 |
| `SurfaceProfile` | 目标模块的 profile / JSON |
| `LocalAppBackgroundLuminance` | 目标模块的 CompositionLocal 或字段 |
| `BlurColors` / `GlassColorLayer` | 目标模块的模糊管线 |
| `AdaptiveLyricCardBackgroundView` | 目标模块的卡片/面板 View |
| `LyricCardBackdropRenderer` | 目标模块的壁纸/背景采样器 |
| 中文字符串资源 | 目标模块自己的 strings |

---

## 9. 已知限制

- 应用侧只采样「应用背景图」；系统壁纸不在这条路径里，没有背景图时应用玻璃亮度为 0
- 动态壁纸的 `WallpaperColors` 只是主色估算，不是逐像素平均
- 亮度采样频率越高，动态壁纸下 `WallpaperColors` 调用越频繁
- 起始点是亮度阈值，不是空间位置；起始点以下不压暗
- 压暗是材质层效果，不会改变壁纸、背景图或截图内容
