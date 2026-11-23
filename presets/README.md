# Migrated appearance presets

These six presets were previously hard-coded in the Android app as the “预设包 / Preset packs”
option. That in-app option has been removed; the presets now live here as portable config
preset JSON and are published to the cloud preset hub (`presets` release, `presets.json`).

| File | Name | Notes |
| --- | --- | --- |
| `classic_dark.json` | 经典暗夜 | 出厂观感：渐变扫光、无辉光、黑色卡片 |
| `warm_gold.json` | 暖金辉光 | BetterLyrics 逐字暖金辉光，单行歌曲信息 |
| `ice_blue.json` | 冰蓝夜色 | 冰蓝渐变扫光 + 翻译辅助行 + 半透明深灰卡 |
| `sakura.json` | 樱花甜心 | 樱粉逐字辉光 + 音标辅助行 + 旋转圆形封面 |
| `mint.json` | 薄荷清新 | 极简动画、居中薄荷色、单行歌曲信息 |
| `showcase.json` | 全开体验 | 全部开启：逐字辉光、双辅助行、下一行、封面与和声 |

- `manifest.json` is the same shape the app reads from the cloud hub
  (`format: hyperglow-preset-hub`, `version: 1`, `presets: [...]`).
- Each individual file is one entry of that manifest.
- The preset model is section-agnostic: the app discovers sections from the top-level keys of the
  config backup envelope, so settings added by future versions are captured automatically. The
  hub preserves unknown sections verbatim instead of applying a fixed allowlist.
- These six curated files currently carry only the `customization` section: applying one replaces
  the whole lyrics appearance document (both lock screen and AOD) and leaves behaviour, app
  appearance and plugin settings untouched. Presets created in the app may carry every section.
- The cloud copies are curated (`author: aodianjun`, no anonymous `owner`), so they cannot be
  overwritten through the anonymous upload path.

To update the live hub, merge these entries into the `presets.json` asset of the `presets`
release (same GitHub repository) and replace the asset. The app's cloud list will pick them up
on the next refresh.
