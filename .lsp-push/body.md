## 安装包用途说明
- **`hyperglow-cnplus-release-v0.3.170-197.apk`（正式版）**：正常使用请安装此版本。正式签名、R8 压缩，适合日常安装使用。
- **`hyperglow-cnplus-debug-v0.3.170-197.apk`（调试版）**：提 issue 反馈问题时请安装此版本，可提供详细诊断日志辅助排查。调试签名，仅用于测试与问题排查，请勿作为日常版本长期安装。

---

## 更新日志 · v0.3.170 (versionCode 197)

### 新增功能

- **单词效果包与沙箱程序 VM**：新增声明式 DSL 与沙箱字节码程序（整块歌词上下文，渐变/阴影/描边/路径等原语）；效果规格随配置文档/备份/SystemUI 通道往返，支持效果包导入与选择器；设置页新增在线编辑器（Word Effect Studio）入口，内置三档效果保持原渲染路径。
- **无歌词/纯音乐三态与尾奏能力**：真无歌词与纯音乐共用 KEEP（保持原标记）/ MUSIC_INFO（替换为歌曲信息）/ RESTORE_DEFAULT（交还系统默认）三态，AOD 与锁屏按面独立；新增每面 0–30s 可配尾奏超时（默认 3s）——投影层保留最后一行，MUSIC_INFO 元数据为空时回落原行，到点交还系统。
- **自定义 AOD 保活时长**：保活时长支持 1 分钟–24 小时的任意值（原仅 5 个预设），设置页新增「自定义…」分钟输入对话框（1–1440），标签按时/分格式化。

### 修复

- **AOD 保活到点收尾不干净**：到点后在 `layoutSurface` 唯一显示点加 expired 门控，attach/几何刷新/自定义更新都无法把歌词面顶回来；隐藏不再只执行一次；释放时钟时清空时钟锚点与记忆几何、归零自定义偏移，抬起恢复按原厂位置重新播种。
- **AOD 投影 ticker 竞态退出**：100ms 循环不再按样本引用身份退出（arbiter 约每 100ms 写新实例会误杀循环），改为只按会话身份/播放状态判定；新增 1s 投影看门狗自动重启，并保证有限时长保活每秒重算、到点按时撤唤醒。
- **熄屏位置断供冻结歌词**：位置源停写 ≥15s 时先用 MediaSession 位置兜底（前进且不越歌长才接受），拿不到再外推；外推撞歌尾但写入端已停写时保持最后真实位置/当前行；「保持息屏活动 N 分钟」窗口改为熄屏时重置。
- **Lyricon 回调链停发**：第二位置源从外推移到看门狗循环；重订阅连续失败后整体重建 subscriber（先 destroy 再 create，60s 冷却），不再只重挂回调。
- **SuperLyric 长时间停更后整首无词**：停更 ≥2 分钟时清掉陈旧状态并强制拉取当前全量数据（模块只在跨行广播、不补发当前行）。
- **插件在 release 宿主加载失败**：插件 dex 改为自带 kotlin-stdlib（R8 混淆后宿主无法提供），并修正 `PluginClassLoaderPolicy` 的 dex 头偏移（`type_ids_off` 0x4C→0x44、`class_defs_off` 0x68→0x64）；新增插件 DEX 自包含门禁。
- **锁屏自定义主题与能力上报**：自定义锁屏主题不再被 defaultTheme 硬拦截（布局仍由时钟/通知几何与 usableArea 兜底）；通知栈 `isShown=false` 不再按全屏占位处理；能力上报等待 AOD 探针就绪，App 端忽略 4/16 临时降级，重复上报按内容去重。

### 优化

- **移植上游 fefe5c54→2eaa191 的 5 个提交**：Spicy EX 环境探测（只读 root 探针 + 报告契约/环境政策/诊断清单 UI）、桥接文档身份改按曲目 URI + 整秒时长容差、状态 reducer 去墓碑、HyperOS 4 接缝候选、SurfaceEvidence 每进程一次事实 + 画布 surfaceKind、`SymbolResolver.resolveMethodInOrder`。
- **插件开发文档**：新增 `docs/PLUGIN_DEVELOPMENT.md` 第三方插件开发标准（API、生命周期、运行时契约、打包与类加载策略）。
- **CI**：Actions 制品额度耗尽导致上传失败时降级为 warning，不再连带跳过 debug/插件/正式版发布；`publish-debug` 在无 artifact 时保留现有 debug release 不做空覆盖。

---

**Full Changelog**: https://github.com/aodianjun/com.aodianjun.hyperglow.cnplus/compare/196-0.3.169...197-0.3.170
