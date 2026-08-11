## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.69-87.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.69-87.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---

## 更新日志 (v0.3.69 / versionCode 87)

> 相比 v0.3.68（versionCode 85）

### ✨ 歌词渲染效果（AOD / 锁屏）
- 修复 AOD 整行仅发光、无扫光移动：行级同步时统一归一到水平渐变扫光，与预览效果一致
- 修复锁屏歌词整行全亮但扫光带不推进：改为持续驱动动画刷新，扫光带随播放进度正常移动
- 行级同步时叠加"当前演唱词微光"：整行扫光基础上，当前演唱词小幅放大/光斑，兼顾饱满发光与逐字节奏（预览与实机对齐）
- 软光渲染优化：改用独立柔和光晕层绘制，修复硬件加速下 shadow+shader 同置导致发光丢失的问题；已唱词持续发光，当前演唱词额外增强，词间不闪烁

### 🎵 歌词源（Spicy EX / Lyricon / SuperLyric）
- 歌词源仲裁新增词级时间戳检测：首个已连接、非 stale 且带逐字时间戳的源（如 SuperLyric 卡拉OK）优先于普通行级 LRC，避免"无词级时间→单词动画缺失"
- 无可用歌词源时输出全源状态汇总日志（连接/新鲜度/播放进度一行尽览），便于定位无歌词时是哪一环断了
- 修复 Lyricon 位置流恢复抖动：NetEase ~60Hz 停顿恢复时允许小幅回退（300ms 容差）保持单调推进，行不再来回跳
- Spicy EX 连接状态实时化：从硬编码"始终连接"改为按 SpicyBridgeStore 数据新鲜度动态判定，未安装/空闲时如实显示未连接
- SuperLyric 新增歌词心跳守护：歌词停止推送时强制重新注册，恢复 IPC 回调路径

### 📐 AOD 布局与放置
- 新增 AOD 时钟锚点稳定：抗时钟边界快速振荡（媒体头切换挤压时钟数百像素），歌词位置不再跳动
- 自定义垂直偏置锚点可自由漫游：可放到系统时钟上方、屏幕中部，不再被限制在时钟下方

### 🛠 兼容性（HyperOS / 小米）
- DozeTriggers 类多候选定位：兼容 HyperOS DEV 中 `com.miui.aod.doze` 迁移到 AOSP `com.android.systemui.doze` 包
- 字段反射沿父类链查找：ROM 重构把字段提升到基类后仍能正确读取，探测与钩子站点一致
- 修复 SystemUI 调用者校验：HyperOS 3 小米 17 系列 SystemUI 跑在普通应用 uid，改为按包名解析 uid 校验，不再硬编码 1000
- 启动日志追加缺失探测项名（missing=...），字段报告可直接定位缺失能力

### 🖥 主页 UI
- 新增锁屏 / 息屏歌词实时预览卡片：未连接歌词源时播放演示动画，真实源接入后自动切换实时快照
- 新增 Spicy EX 未连接提示：选中 Spicy 但未在推送歌词时，引导安装 Spicy EX、打开 Spotify

### 📝 文案与说明
- 更新各歌词源描述；Spicy 源描述改为"需 Spicy EX + Spotify 播放"
- 新增锁屏/息屏预览、Spicy 未连接提示等文案（中英双语）

### 🧪 测试与构建
- 新增 LyricProducerArbiter、LyriconLyricProducer、AodPositionUpdate、LockscreenSurfaceController 等单元测试，更新 AodStateProjector / AodCanvasLayout 测试
- CI：正式 Release tag 改为 `VersionCode-VersionName` 格式（符合 LSPosed 模块仓库规范）；debug 预发行每次构建删除重建并串行上传，避免并行覆盖；预发行版本说明开头加醒目警示
- 独立包名 fork（`com.aodianjun.hyperglow.cnplus`），NOTICE 补全衍生版权声明

---

## 待修复清单
- 预览发光效果未分行
- AOD 显示发光效果待完善
- AOD 逐字效果待完善

---
> 本版本为当前最新正式版（Latest）。下载过测试/预发行版本的设备，检测到 versionCode 87 高于预发行版即可收到更新提示。