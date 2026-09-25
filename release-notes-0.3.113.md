> ⚠️ **测试版本请勿下载** — 此版本为预发行测试版，仅供测试与问题排查，请勿用于日常使用。
## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.113-140.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.113-140.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---
## 更新日志 · v0.3.113

### 修复
- **LyricInfo 歌词源翻译/罗马音偶发丢失**：翻译 lane 对齐从 startMs 精确相等放宽为 ±120ms 最近行匹配——源行与翻译行的发布时间误差常达几十毫秒，旧实现会静默丢翻译；匹配带最近主行与重复文本双护栏，防止误挂到相邻歌词行（#68，#69）

### 优化
- **LyricInfo 翻译键别名扩充**：新增 translatedLyric / translateLyric / lyricTranslation / translationLrc / transLrc 五个翻译键别名，与既有 translationLyric / translation / transLyric 组成完整 8 键别名族，兼容更多播放器的 lyricInfo 下发格式（#68，#69）
- **ElrcParser 解析加固**：行文本与词文本统一剥离零宽字符（U+200B/U+2060/U+FEFF），避免污染逐字高亮词界与文本比对；词级时间轴可疑（词起点乱序，或行内存在异常大间隙）时整行降级为行级渲染，脏逐字数据不再产生错误词界高亮（#68，#69）
- **诊断报告脱敏补齐**：私有存储路径（/data/user/、/storage/emulated/、/sdcard/ 下的用户目录与文件名）在诊断报告中统一脱敏（#68，#69）

---

**Full Changelog**: https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus/compare/139-0.3.112...140-0.3.113
