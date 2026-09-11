# HyperGlow Diagnostic Reporting Spec

# English / 英文

Status: implementation contract

HyperGlow can submit one user-triggered private report to the shared diagnostic intake. The app
never uploads in the background, creates a GitHub issue automatically, reads arbitrary files, or
accepts server-controlled hook configuration.

## User flow

1. The user opens `Report a problem`, selects a category, and enters a nonblank description bounded
   to 4,000 UTF-8 bytes. The multiline field is top-aligned, uses a disappearing task placeholder,
   and shows a live compact byte counter at its bottom end.
2. Compatibility reports run a fixed root-access check and use current metadata immediately when
   setup is healthy. If setup is failed because `capability_report` or `systemui_hook` is missing,
   Compatibility starts the same guided capture as runtime failures:
   start, reproduce outside HyperGlow, reopen diagnostics, then finish.
3. The app shows a readable, pretty-printed view of the included JSON plus a concise link to the
   public diagnostic data policy before upload.
4. The user accepts retention and manually uploads once. A manual retry reuses the same random
   `R1-` Crockford Base32 report ID.
5. A successful receipt shows the report ID and data-policy link. The GitHub action opens a formatted
   public issue draft containing the description, report ID, app version, device model, and
   compatibility summary only. The submitted JSON remains viewable and may be explicitly exported
   through Android's system file picker.

Pending local report data expires after 30 minutes. Cancellation, timeout, or successful upload
restores the diagnostic-logging value that existed before capture and deletes temporary report data.

## Intake contract

- URL comes from build-time `DIAGNOSTIC_INTAKE_URL`; official builds use
  `https://reports.eza.dpdns.org/v1/reports`. The legacy DXF host proxies to the same intake without
  redirects during migration.
- HTTPS `POST`, `application/json`, no redirects, credentials, embedded secret, automatic retry, or
  networking dependency. Request deadline: 15 seconds.
- Client report limit: 384 KiB UTF-8.
- Envelope versions are `envelopeVersion=1`, `product=hyperglow`, and
  `productReportVersion=2`. The intake accepts any positive product report version, validates known
  allowlisted fields, and drops unknown fields; envelope version remains the transport boundary.
- Success accepts `201` for a new report and `200` for an identical-ID retry. `400`, `409`, `413`,
  `429`, `503`, redirects, timeouts, and other 5xx responses remain distinct user-visible failures.
- The intake does not reject a report for failing schema validation. A payload the contract cannot
  map, and a report past the rolling global record cap, are stored verbatim as a quarantined row for
  later maintainer mapping or pruning, and still answer `201` with a normal receipt carrying the
  client's own report ID. Client behavior is unchanged; schema drift no longer loses a report.
- A request body over the size cap is still rejected, as are transport-level failures. Proxy limits
  remain authoritative for rate, concurrency, and body size.
- The public endpoint relies on proxy limits, the request size cap, and private maintainer triage. It
  does not authenticate an app installation.

## Collected data

All accepted report data is retained indefinitely until a maintainer manually deletes or redacts it:

- HyperGlow version/build type;
- manufacturer, brand, model, device, product, Android build/security/fingerprint, locales;
- fixed-allowlist Xiaomi properties;
- HyperGlow, SystemUI, Xiaomi AOD, and Spotify package versions;
- capability protocol/age, effective profile state, raw symbol probes, resolved capabilities;
- configured surface flags, callback presence, and privacy-safe Spotify producer status/age;
- capture outcome, root status, command failures, and truncation flags.
- bounded setup state and failure keys for root, SystemUI hook/report, verified profile, Spotify
  producer bridge, and required package presence/version metadata.
- current Spotify track URI, title, artist, album, lyric provider/source, detected language, timing
  type, current line index, and bounded original/transliterated/translated lyric lines when present;
- user description;
- filtered HyperGlow logs;
- fixed SystemUI/HyperGlow process snapshot (`USER`, `UID`, `PID`, and bounded process name),
  prepended to those logs;
- fixed `/data/adb` framework evidence: LSPosed directories, matching module `module.prop`
  identity/version fields, root-solution markers, and selected LSPosed/LSPatch manager package names;
- allowed-process crash excerpt;
- HyperGlow-only LSPosed lines;
- fixed allowlist of runtime-setting values.

Never serialize artwork identity, Spotify credentials, cookies, account identity, Android ID,
serial, IMEI, Wi-Fi SSID, a complete installed-app inventory, customization documents, arbitrary
files, screenshots, full logcat, or unfiltered LSPosed logs. The process/framework evidence above
is explicitly fixed and allowlisted.
Known URI, URL, credential, and throwable-message patterns are redacted from captured lines.
Screenshots may be attached manually to the separately opened public GitHub issue when useful.

## Guided capture

Capture stores wall and elapsed start times plus the previous diagnostic-logging state. It enables
the existing runtime logging flag and publishes configuration to SystemUI. The active-capture
instruction tells the user to restart SystemUI inside the capture window so boot markers are
included, and to reproduce the failure before finishing so failure evidence lands inside the window. A non-exported alarm
expires the capture after 30 minutes; process startup also handles timeout or elapsed-clock reset.

Finish runs only fixed root commands. User text never enters a command. Each command has a five-second
timeout:

- the HyperGlow-tagged main/system logcat slice since capture started (`-T <capture-start>`),
  maximum 160 KiB, plus the app-process log mirror (maximum 96 KiB) folded into the same logs
  section;
- fixed SystemUI/HyperGlow process listing (`USER`, `UID`, `PID`, and bounded process name), merged
  into that bounded log section;
- crash-buffer blocks whose process is HyperGlow, SystemUI, or Spotify, maximum 64 KiB;
- lines from the newest LSPosed module log only, after a fixed 512 KiB tail bound, containing
  `HyperGlow` or `com.eza.hyperglow`, maximum 64 KiB.

Root denial produces a metadata-only report. Oversized sections preserve the first 25% and newest
75% with an explicit truncation marker. Line-based sections discard partial boundary lines so a
retained LSPosed fragment cannot lose its module-identity prefix.

SystemUI bootstrap emits a small fixed-stage series whenever trace logging is compiled in. These
events do not depend on bridge-delivered runtime diagnostic configuration, since they diagnose the
case where that bridge never connects. The first event includes the APK build code and declared
Xposed minimum/target API. Events contain only fixed stage names plus bounded build,
probe-count/profile, user, attempt, and process-class scalars; they contain no lyric, media, or
payload data.

## Compatibility status

Capability protocol v2 carries report time, effective profile state, experimental state, raw symbol
probes, and resolved capabilities. The app accepts v1 while app and SystemUI processes transition.

Runtime status is one of:

- `No SystemUI report`;
- `Verified profile`;
- `Verified profile missing symbols`;
- `Unsupported profile`;
- `Experimental eligible`;
- `Experimental active`.

Unsupported surfaces preserve stored configuration but cannot present it as active. Runtime-dependent
controls are disabled; appearance editors remain available. Experimental hook activation is outside
this checkpoint and remains fail-closed until the staged rollout prerequisites are satisfied.

---

# 中文 / Chinese

状态：实现契约

HyperGlow 可以向共享的诊断 intake 提交一份由用户触发的私密报告。app 从不在后台上传、
自动创建 GitHub issue、读取任意文件，也不接受由服务器控制的 hook 配置。

## 用户流程

1. 用户打开 `Report a problem`，选择一个分类，并填写一段限制为 4,000 UTF-8 字节的非空描述。
   多行输入框顶部对齐，使用输入后消失的任务占位符，并在其底端显示一个紧凑的实时字节
   计数器。
2. 兼容性报告会执行固定的 root 权限检查，并在环境健康时立即使用当前元数据。如果环境因
   缺少 `capability_report` 或 `systemui_hook` 而处于失败状态，兼容性会启动与运行时故障
   相同的引导采集：开始，在 HyperGlow 之外复现问题，重新打开诊断，然后完成。
3. 上传前，app 会展示一份可读的、格式化打印的 JSON 内容视图，并附带指向公开诊断数据政策
   的简明链接。
4. 用户同意数据保留并手动上传一次。手动重试会复用同一个随机的 `R1-` Crockford Base32
   报告 ID。
5. 成功回执会显示报告 ID 与数据政策链接。GitHub action 会打开一个格式化的公开 issue
   草稿，其中仅包含描述、报告 ID、app 版本、设备型号与兼容性摘要。已提交的 JSON 仍可
   查看，并可通过 Android 系统文件选择器显式导出。

待处理的本地报告数据会在 30 分钟后过期。取消、超时或上传成功都会恢复采集之前存在的
诊断日志值，并删除临时报告数据。

## Intake 契约

- URL 来自构建期的 `DIAGNOSTIC_INTAKE_URL`；官方构建使用
  `https://reports.eza.dpdns.org/v1/reports`。迁移期间，旧 DXF 主机直接代理到同一 intake，
  不做重定向。
- HTTPS `POST`、`application/json`，无重定向、无凭据、无内嵌密钥、无自动重试、无网络库
  依赖。请求截止时间：15 秒。
- 客户端报告大小上限：384 KiB UTF-8。
- 信封版本为 `envelopeVersion=1`、`product=hyperglow` 与 `productReportVersion=2`。intake
  接受任意正数的产品报告版本，校验已知的白名单字段并丢弃未知字段；信封版本始终是传输
  边界。
- 成功时，新报告接受 `201`，相同 ID 的重试接受 `200`。`400`、`409`、`413`、`429`、
  `503`、重定向、超时以及其他 5xx 响应仍是彼此独立、用户可见的失败。
- intake 不会因 schema 校验失败而拒绝报告。契约无法映射的载荷，以及超出滚动全局记录上限
  的报告，会被原样存储为一条隔离记录，供维护者后续映射或清理，并且仍会返回 `201` 与
  携带客户端自身报告 ID 的正常回执。客户端行为不变；schema 漂移不再丢失报告。
- 超过大小上限的请求体仍会被拒绝，传输层失败同样会被拒绝。代理侧对速率、并发与请求体
  大小的限制始终具有最高权威。
- 该公开端点依赖代理限制、请求大小上限以及维护者的私下分诊，不对 app 安装做身份验证。

## 采集的数据

所有被接受的报告数据都会无限期保留，直到维护者手动删除或脱敏：

- HyperGlow 版本/构建类型；
- 厂商、品牌、型号、设备、产品、Android 构建/安全补丁/指纹、语言区域；
- 固定白名单的 Xiaomi 属性；
- HyperGlow、SystemUI、Xiaomi AOD 与 Spotify 的软件包版本；
- capability 协议/时效、生效 profile 状态、原始符号 probe、解析出的 capability；
- 已配置的 surface 开关、回调存在性以及隐私安全的 Spotify 生产者状态/时效；
- 采集结果、root 状态、命令失败与截断标记。
- 受限的环境状态与失败键，涵盖 root、SystemUI hook/报告、已验证 profile、Spotify 生产者
  bridge 以及必需软件包的存在性/版本元数据；
- 当前 Spotify 曲目 URI、标题、艺术家、专辑、歌词提供者/来源、检测到的语言、时序类型、
  当前行索引，以及存在时受限的原文/音译/翻译歌词行；
- 用户描述；
- 过滤后的 HyperGlow 日志；
- 固定的 SystemUI/HyperGlow 进程快照（`USER`、`UID`、`PID` 与受限的进程名），前置到这些
  日志之前；
- 固定的 `/data/adb` 框架凭据：LSPosed 目录、匹配的模块 `module.prop` 身份/版本字段、
  root 方案标记以及选定的 LSPosed/LSPatch 管理器包名；
- 允许进程的崩溃摘录；
- 仅与 HyperGlow 相关的 LSPosed 行；
- 固定的运行时设置值白名单。

绝不序列化封面图标识、Spotify 凭据、cookie、账号身份、Android ID、序列号、IMEI、
Wi-Fi SSID、完整的已安装应用清单、自定义文档、任意文件、截图、完整 logcat 或未过滤的
LSPosed 日志。上述进程/框架凭据是显式固定且在白名单内的。
已知的 URI、URL、凭据与 throwable 消息模式会从采集的行中脱敏。
截图可在单独打开的公开 GitHub issue 中按需手动附加。

## 引导采集

采集会存储起始的墙上时间与流逝时间，以及之前的诊断日志开关状态。它会启用既有的运行时
日志开关并向 SystemUI 发布配置。采集进行中的提示会告知用户在采集窗口内重启 SystemUI，
以包含启动标记，并在完成前复现故障，使故障证据落在窗口之内。一个未导出的 alarm
会在 30 分钟后使采集过期；进程启动时同样会处理超时或流逝时钟重置。

完成操作只运行固定的 root 命令。用户文本永远不会进入命令。每条命令的超时时间为
5 秒：

- 自采集开始以来带 HyperGlow 标签的 main/system logcat 切片（`-T <capture-start>`），
  最大 160 KiB，外加 App 进程日志镜像（最大 96 KiB），并入同一受限日志段；
- 固定的 SystemUI/HyperGlow 进程列表（`USER`、`UID`、`PID` 与受限的进程名），合并进该
  受限的日志段；
- 进程为 HyperGlow、SystemUI 或 Spotify 的 crash 缓冲区块，最大 64 KiB；
- 仅来自最新 LSPosed 模块日志、经固定 512 KiB 尾部截取后包含 `HyperGlow` 或
  `com.eza.hyperglow` 的行，最大 64 KiB。

root 被拒绝时生成仅含元数据的报告。超限的段落会保留最前 25% 与最新 75%，并附带显式
截断标记。基于行的段落会丢弃不完整的边界行，确保被保留的 LSPosed 片段不会丢失其模块
身份前缀。

只要编译时启用了 trace 日志，SystemUI 引导阶段就会输出一组少量固定阶段的事件。这些事件
不依赖经 bridge 下发的运行时诊断配置，因为它们要诊断的正是该 bridge 从未连接的情形。
第一个事件包含 APK 构建号与声明的 Xposed 最低/目标 API。事件只包含固定的阶段名，以及
受限的构建、probe 数量/profile、用户、尝试次数与进程类别等标量；不含任何歌词、媒体或
负载数据。

## 兼容性状态

capability 协议 v2 携带上报时间、生效 profile 状态、实验性状态、原始符号 probe 以及
解析出的 capability。app 在 app 与 SystemUI 进程过渡期间接受 v1。

运行时状态为以下之一：

- `No SystemUI report`（无 SystemUI 报告）；
- `Verified profile`（已验证 profile）；
- `Verified profile missing symbols`（已验证 profile 缺少符号）；
- `Unsupported profile`（不受支持的 profile）；
- `Experimental eligible`（实验性可用）；
- `Experimental active`（实验性已启用）。

不受支持的 surface 会保留已存储的配置，但无法将其呈现为激活状态。依赖运行时的控件会被
禁用；外观编辑器保持可用。实验性 hook 的激活不在本检查点范围内，在满足分阶段推出的前置
条件之前保持 fail-closed。
