# HyperGlow Diagnostic Data Policy

# English / 英文

HyperGlow does not send diagnostic data anywhere on its own. When you open **Report a problem**, the
app collects the data described below into a local draft, shows you the exact payload for review,
and — after you accept this policy — finalizes it locally into a receipt plus a ready-to-open
GitHub issue draft. Nothing leaves the device unless you personally attach or paste it somewhere.
There are no background uploads, analytics, remote configuration, automatic GitHub issues, cookies,
or embedded API credentials.

## Included data

- Your description and chosen category.
- HyperGlow, System UI, Xiaomi AOD, Spotify, Android, device, build, and locale metadata.
- HyperGlow capability/symbol results and allowlisted runtime settings.
- Current song title, artist, album, track URI of the active lyrics source, lyric
  provider/language/timing information, and bounded current original/transliterated/translated
  lyric lines when available.
- If you explicitly run guided capture: filtered HyperGlow logs (the SystemUI logcat slice plus the
  app-process log mirror), allowed-process crash excerpts, and HyperGlow-only LSPosed lines.
- If guided capture runs with root: a fixed process snapshot for SystemUI and HyperGlow containing
  USER, UID, PID, and bounded process name; selected framework evidence under `/data/adb`, including
  presence of the LSPosed log directories, matching module `module.prop` id/name/version/versionCode,
  detected root-solution marker (`ksu`, `ap`, or `magisk`), and only manager package names matching
  `lsposed`, `lspatch`, or `edxposed`.

## Never included

- Artwork identifiers.
- Spotify tokens, cookies, account details, Android ID, serial, IMEI, or Wi-Fi SSID.
- Full logcat, unfiltered LSPosed logs, screenshots, imported customization files, arbitrary files,
  or a complete installed-app inventory. Framework evidence is limited to the fixed paths and
  selected package-name patterns listed above.
- Your source IP in the application or report record. Network infrastructure may process it
  normally while handling the HTTPS request.

## Storage and retention

The report exists only on your device. HyperGlow operates no intake endpoint and stores nothing
server-side. The temporary draft expires after 30 minutes and is deleted after cancellation or
finalization; the finalized receipt and issue draft are kept only while the screen is open.

If you choose to open a GitHub issue, you share exactly what the draft shows — voluntarily. The
report ID is a local reference for correlating issues; it cannot retrieve report contents from
anywhere.

## GitHub issues

The generated GitHub issue draft contains only your description, report ID, HyperGlow version,
device model, compatibility summary, song identity, provider, language, and timing type. Lyric
text, private diagnostic logs, and settings are not added to the GitHub issue. Screenshots can be
attached manually in GitHub when useful.

To delete or redact something you already posted, edit or delete the issue yourself, or ask the
maintainer through the issue. Do not post additional private diagnostic data in GitHub.

---

# 中文 / Chinese

HyperGlow 自身不会向任何地方发送诊断数据。当您打开**报告问题（Report a problem）**时，应用会把
下述数据收集为一个本地草稿，向您展示确切的载荷内容，并在您接受本政策后于本地定稿为回执与一份
可直接开启的 GitHub issue 草稿。除非您亲自把内容附加或粘贴到某处，否则任何数据都不会离开设备。
不存在后台上传、分析统计、远程配置、自动创建 GitHub issue、Cookie 或内嵌的 API 凭据。

## 包含的数据

- 您的描述和所选类别。
- HyperGlow、System UI、Xiaomi AOD、Spotify、Android、设备、构建版本及区域设置的元数据。
- HyperGlow 的能力/符号检测结果及白名单内的运行时设置。
- 当前歌曲的标题、艺术家、专辑、活跃歌词源的曲目 URI、歌词提供方/语言/时间轴信息，
  以及可用时有限的当前原文/音译/翻译歌词行。
- 如果您明确运行引导式采集：经筛选的 HyperGlow 日志（SystemUI logcat 切片及 App 进程日志
  镜像）、允许进程的崩溃摘录，以及仅涉及 HyperGlow 的 LSPosed 日志行。
- 如果引导式采集以 root 权限运行：SystemUI 和 HyperGlow 的固定进程 snapshot，包含 USER、UID、PID
  及有限长度的进程名；`/data/adb` 下选取的框架证据，包括 LSPosed 日志目录是否存在、匹配模块的
  `module.prop` id/name/version/versionCode、检测到的 root 方案标识（`ksu`、`ap` 或 `magisk`），
  以及仅与 `lsposed`、`lspatch` 或 `edxposed` 匹配的管理器包名。

## 永不包含

- 封面图标识。
- Spotify 令牌、Cookie、账户详情、Android ID、序列号、IMEI 或 Wi-Fi SSID。
- 完整 logcat、未筛选的 LSPosed 日志、屏幕截图、导入的自定义文件、任意文件
  或完整的已安装应用清单。框架证据仅限于上文列出的固定路径和选定的包名模式。
- 应用或报告记录中的来源 IP。网络基础设施在处理 HTTPS 请求时
  可能会正常处理该地址。

## 存储与保留

报告仅存在于您的设备上。HyperGlow 不运营任何接收端点，也不在服务端存储任何内容。临时草稿在
30 分钟后过期，并在取消或定稿后删除；定稿的回执与 issue 草稿仅在页面打开期间保留。

如果您选择开启 GitHub issue，您分享的就是草稿所展示的内容——完全自愿。报告 ID 只是用于关联
issue 的本地引用，无法从任何地方取回报告内容。

## GitHub issue

生成的 GitHub issue 草稿仅包含您的描述、报告 ID、HyperGlow 版本、设备型号、兼容性摘要、
歌曲标识、提供方、语言及时间轴类型。歌词文本、私密诊断日志和设置不会被添加到 GitHub issue 中。
如有需要，可以在 GitHub 中手动附加屏幕截图。

如需删除或脱敏您已发布的内容，请自行编辑或删除该 issue，或通过 issue 联系维护者。
请勿在 GitHub 中发布额外的私密诊断数据。
