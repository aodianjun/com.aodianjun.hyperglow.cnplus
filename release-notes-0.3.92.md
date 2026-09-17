> ⚠️ **测试版本请勿下载** — 此版本为预发行测试版，仅供测试与问题排查，请勿用于日常使用。
## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.92-119.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.92-119.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---

## 更新日志 · v0.3.92 (versionCode 119)【预发行】

### 修复 · 息屏画布无法从横屏回落竖屏导致歌词不显示（issue #30）

- **根因**：0.3.91 让旋转真正触发后，`resolveAodRotationStep` 在竖持（`absX <= absY`）时仍返回 `null`，而监视器 `evaluate(null)` 被定义为「无变化」直接早退——于是切横屏后再回正、甚至充电/竖持时都不再产生任何旋转步进，画布停留在横屏逻辑帧却被放进竖屏视口，内容被裁到屏幕外、歌词不可见。
- **修复**：`resolveAodRotationStep` 在 `landscape` / `landscape_reverse` / `auto` 三种模式下，竖持一律返回显式 `AodOrientationStep.PORTRAIT`（而不是 `null`），让 `evaluate` 走既有防抖路径从横屏回落竖屏；`null` 仅保留给非有限重力等无效读数，避免脏读数来回抖。
- **补充单测**：竖持返回 `PORTRAIT`、平置（|gx|≈|gy|≈0）兜底为 `PORTRAIT`、非有限输入仍返回 `null`。

### 优化 · 所有歌词绘制路径共享同一逻辑裁剪（上游 2885511 项 #4）

- 息屏歌词画布 `drawRows` 顶层统一施加 `clipRect(padLeft, padTop, ow-padRight, oh-padBottom)`，让原文 / 注音 / 翻译 / 逐字扫光 / 发光块等**所有**歌词绘制路径都被限制在周围 padding 框内——即使整词不可分或动画越界超出其测量宽度也不越出边界；同时移除 `drawText` 中逐行重复的 `save/clipRect/restore`，由这一处统一边界取代。

---

**Full Changelog**: https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus/compare/118-0.3.91...119-0.3.92