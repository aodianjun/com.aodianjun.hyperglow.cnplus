## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.84-111.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.84-111.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---

## 更新日志 · v0.3.84 (versionCode 111)【预发行】

### 修复

- **AOD 位置固定失效(0.3.83 回归)**:0.3.83 的租约/唤醒竞态修复给
  `isSurfaceRenderActive()` 加了祖先 alpha 链门控(alpha×transitionAlpha ≤ 0.01
  即判未渲染),而该判定同时控制 draw-wake 续期与唤醒请求——息屏时小米对 AODView
  祖先的正常压暗/淡出被误判为"未渲染",唤醒脉冲停止、doze 不再合成新帧,
  `AodPositionHook` 应用的时钟固定位移不可见。现仅 alpha 乘积恰为 0(亮屏下小米
  用 alpha=0 藏 AODView 的场景)才视为隐藏;`setDrawWakeRenewalActive` 关闭沿
  记录 `effAlpha`/`alphaChain` 现场,诊断报告可直接定位。
- **文档拒绝原因分类学**:gzip 体非法 JSON 现走命名拒绝
  `malformed json <message>`,不再以异常逃出命名拒绝体系;服务层异常型拒绝
  附带 `error.message`,`requiredXxx` 的 `missing $key` 信息可达诊断报告。

### 新增 / 改进

- **LyricInfo 通道消费 Bridge Provider v5 字段**:在既有 limczhh/LyricInfo
  方言之上新增 `rawLyric`(逐字)、`translationLyric`(规范翻译)、`roma`
  (罗马音)三条 lane 的消费——装任一家 Provider 模块(含 ColorOS Live Lyrics
  Bridge Providers v5 与 limczhh/LyricInfo 完整版)即可获得逐字卡拉 OK 与
  罗马音,无需新增 hook;新增 8 个单元测试覆盖三种方言与 lane 优先级。
- **CI**:工作流新增 `pull_request` 触发,分支 PR 不再完全没有 CI
  (PR #12/#13/#14 均需手动 dispatch)。
- **AntiFreeze 匹配守卫**:匹配播放进程时按 pid(≤4194304)/uid(≥10000)
  合理范围守卫,避免把 flags/常量等无关整数误判成播放应用(fail-open 方向
  不变,真实命中不受影响)。
- **CallerValidator 判定缓存改 16 条 LRU**:不再满额整体清空,第 17 个不同
  UID 出现时其余判定不再失效。

### 测试

- 单元测试 588 → 596,CI 双变体全部通过;涉及 hook 与诊断行为的改动仍需
  真机复核(红米 K80 Pro)。
