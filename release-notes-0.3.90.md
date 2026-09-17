> ⚠️ **测试版本请勿下载** — 此版本为预发行测试版，仅供测试与问题排查，请勿用于日常使用。
## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.90-117.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.90-117.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---

## 更新日志 · v0.3.90 (versionCode 117)【预发行】

### 修复 · 没有播放时仍显示曲目信息（本地 issue #27）

- **AOD / 概览不再残留旧曲目**：Lyricon SDK 对「会话仍 active 但已真正停止」的播放器（如网易云：active=true 且保留 metadata，仅 playback state 变为 null）不会触发 `onPlaybackStateChanged(false)`，导致停止后 AOD / 概览长期显示上一首曲目。新增 MediaSession 真状态巡检：`LyriconLyricProducer` 定时读取对应会话的播放状态，连续判别为 `NONE / STOPPED / null` 时按 `onSongChanged(null)` 清空曲目。
- **防误清除**：仅当没有任何会话仍在播放同名曲目时才清除；暂停归为暂停、保留既有驻留与超时链路，不做误清。
- **概览页收敛**：非播放态不再展示旧曲目，概览摘要回到「已暂停」态。

### 修复 · 息屏画布随设备旋转后内容被裁剪（PR #28）

- **旋转只转像素、不重排布局**：0.3.87 引入的「息屏画布随设备旋转」此前仅做像素旋转，布局层仍按竖屏宽度折行，横屏后内容被裁成屏幕中间竖条、表现「旋转无效」。现补上逻辑横屏框（交换宽高）：布局 / 行锚定 / 裁剪 / 图层边界 / 缩放枢轴全部改用逻辑横屏坐标，绘制期经 rotate + 刚性平移精确铺回竖屏视口。
- **横屏内边距参数生效**：`paddingLandscapeXPercent / YPercent` 自此真正参与横屏排版（X 对应长边、Y 对应短边），竖屏内边距行为保持不变。
- **新增单测**：逻辑横屏框互换性与还原性验证。

### 优化

- 修复 issue #27 停止检测守卫的可空性类型不匹配（`Song.name` 与守卫参数），保证 CI 编译通过。

---

**Full Changelog**: https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus/compare/116-0.3.89...117-0.3.90