## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.85-112.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.85-112.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---

## 更新日志 · v0.3.85 (versionCode 112)【预发行】

### 修复

- **画布/媒体进度条祖先 alpha 门控对齐精确零语义**:承接 0.3.84 对 AOD
  surface 的修复,将相同的 0.01 阈值祖先 alpha 门控修正扩展到
  `AodLyricCanvasView` 与 `MediaProgressView`(与 AOD 位置固定失效同源)。
  息屏时小米对视图祖先的正常压暗/淡出(`0 < α < 1`)此前会被误判为"未渲染",
  中断绘制节奏与 wake 脉冲;现仅 alpha 乘积恰为 `0f`(亮屏下用 alpha=0 隐藏
  视图的场景)才算隐藏。手写 AOD/锁屏淡入淡出与位置固定可同时正常工作。

### 优化 / CI

- **插件契约指纹守卫**:新增 CI 校验,对
  `plugins/api/.../PluginApi.kt`(HyperLyric 插件契约的 FQCN 兼容副本,需与
  上游字节级一致)计算去除注释/空白后的稳定指纹并与入库黄金值比对,防止未来
  无意的公开声明重命名/重排静默破坏预编译插件 ZIP 的免重编译加载。
- **文档私有引用标注**:STYLE_GUIDE.md 与 LOCKSCREEN_AOD_BEHAVIOR_SPEC.md
  中指向私有工作仓库、公开树不存在的文档(PARITY-SPEC / 实现状态表)统一标注
  「(私有文档,未随公开树发布)」。

### 测试

- `AodCanvasLayoutTest` 增加精确零语义断言:alpha=0f 隐藏、alpha=0.01f 与
  alpha=0.3f 均判定活跃(handoff 豁免不变),锁定"仅 0 判隐藏"的新契约。