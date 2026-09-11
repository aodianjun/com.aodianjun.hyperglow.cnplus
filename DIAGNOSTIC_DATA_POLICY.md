# HyperGlow Diagnostic Data Policy

# English / 英文

HyperGlow sends a diagnostic report only after you open **Report a problem**, review the included
data, accept this policy, and tap **Upload once**. There are no background uploads, analytics,
remote configuration, automatic GitHub issues, cookies, or embedded API credentials.

## Included data

- Your description and chosen category.
- HyperGlow, System UI, Xiaomi AOD, Spotify, Android, device, build, and locale metadata.
- HyperGlow capability/symbol results and allowlisted runtime settings.
- Current song title, artist, album, Spotify track URI, lyric provider/language/timing information,
  and bounded current original/transliterated/translated lyric lines when available.
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

Reports are private. Accepted report data is retained indefinitely until a maintainer manually
deletes or redacts it. There is no automatic expiry. Temporary report data on the phone expires after
30 minutes and is deleted after cancellation or successful upload.

If the intake cannot map a report onto its known fields, it stores the report exactly as your phone
sent it instead of discarding it, so a newer app version is never silently dropped. That stored copy
holds only what this policy already describes, is private in the same way as every other report, and
a maintainer can delete or redact it on request.

The report ID is a private-storage reference, not a public download key. It cannot retrieve report
contents from the intake endpoint.

## GitHub issues

Opening GitHub creates a separate public draft containing your description, report ID, HyperGlow
version, device model, compatibility summary, song identity, provider, language, and timing type.
Lyric text, private diagnostic logs, and settings are not added to the GitHub issue. Screenshots can
be attached manually in GitHub when useful.

To request deletion or redaction, open a HyperGlow issue with the report ID and the requested action.
Do not post additional private diagnostic data in GitHub.

---

# 中文 / Chinese

只有在您打开**报告问题（Report a problem）**、查看所包含的数据、接受本政策并点按**单次上传（Upload once）**后，
HyperGlow 才会发送诊断报告。不存在后台上传、分析统计、远程配置、自动创建 GitHub issue、Cookie
或内嵌的 API 凭据。

## 包含的数据

- 您的描述和所选类别。
- HyperGlow、System UI、Xiaomi AOD、Spotify、Android、设备、构建版本及区域设置的元数据。
- HyperGlow 的能力/符号检测结果及白名单内的运行时设置。
- 当前歌曲的标题、艺术家、专辑、Spotify 曲目 URI、歌词提供方/语言/时间轴信息，
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

报告均为私密数据。已接受的报告数据将无限期保留，直到维护者手动删除或对其脱敏处理。
不存在自动过期机制。手机上的临时报告数据在 30 分钟后过期，并在取消或成功上传后删除。

如果接收端无法将报告映射到其已知字段，会按手机发送时的原样存储该报告而非将其丢弃，因此较新的
应用版本绝不会被静默丢弃。该存储副本仅包含本政策已描述的内容，与其他所有报告同样私密，维护者
可根据请求删除或对其脱敏处理。

报告 ID 是私密存储的引用，并非公开的下载密钥，无法用于从接收端点获取报告内容。

## GitHub issue

打开 GitHub 时会创建一个单独的公开草稿，其中包含您的描述、报告 ID、HyperGlow 版本、设备型号、
兼容性摘要、歌曲标识、提供方、语言及时间轴类型。歌词文本、私密诊断日志和设置不会被添加到
GitHub issue 中。如有需要，可以在 GitHub 中手动附加屏幕截图。

如需请求删除或脱敏处理，请提交一个 HyperGlow issue，并附上报告 ID 和所请求的操作。
请勿在 GitHub 中发布额外的私密诊断数据。
