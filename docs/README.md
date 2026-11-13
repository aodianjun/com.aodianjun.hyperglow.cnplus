# 文档索引

按用途分组；新增文档请同时在这里登记。

## 外观与渲染

- [WORD_EFFECT_PACKS.md](WORD_EFFECT_PACKS.md) — **单词效果包 / 效果程序**
  - 第 1–9 节：声明式 DSL（包信封、轨道、关键帧、缓动、颜色、导入/生命周期、边界）
  - 第 10 节：**沙箱字节码程序**（整块歌词上下文、指令集、示例、安全限额、汇编工具）
  - 示例包：`samples/word-effect-pack.json`（DSL）、`samples/word-effect-program-pack.json`（程序）
  - 汇编工具：`tools/assemble_effect_program.py`
  - 在线编辑器：`tools/word-effect-editor/`（部署在
    <https://aodianjun.github.io/word-effect-studio/>；App 设置页「在线编辑单词效果」可直接打开）
- [LOCKSCREEN_AOD_BEHAVIOR_SPEC.md](LOCKSCREEN_AOD_BEHAVIOR_SPEC.md) — 锁屏/AOD 行为规范（渲染路径、换行动画、逐字效果）
- [ARCHITECTURE.md](ARCHITECTURE.md) — 整体架构（producer → plugin → projection → SystemUI/AOD）

## 插件

- [PLUGIN_DEVELOPMENT.md](PLUGIN_DEVELOPMENT.md) — 插件开发指南（API、运行时契约、打包）
- [PLUGIN_RELEASE_CONVENTIONS.md](PLUGIN_RELEASE_CONVENTIONS.md) — 插件发布规范
- [LYRIC_PRODUCER_CONTRACT.md](LYRIC_PRODUCER_CONTRACT.md) — 歌词生产者契约

## 构建、发布与回归

- [CONTRIBUTING.md](CONTRIBUTING.md) — 构建/提交约定
- [STYLE_GUIDE.md](STYLE_GUIDE.md) — 代码风格
- [REGRESSION.md](REGRESSION.md) — 回归检查清单
- [RELEASE_CONVENTIONS.md](RELEASE_CONVENTIONS.md) / [RELEASE_NOTES_TEMPLATE.md](RELEASE_NOTES_TEMPLATE.md) — 发布约定与模板
- [DEVICE_COMPAT_MATRIX.md](DEVICE_COMPAT_MATRIX.md) — 设备兼容矩阵
- [DIAGNOSTIC_REPORTING_SPEC.md](DIAGNOSTIC_REPORTING_SPEC.md) — 诊断上报规范

## LSPosed

- [LSPOSED_PUSH.md](LSPOSED_PUSH.md) — LSPosed 推送流程
- [LSPOSED_SUBMISSION.md](LSPOSED_SUBMISSION.md) — 模块仓提交规范
