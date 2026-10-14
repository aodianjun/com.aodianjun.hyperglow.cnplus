# CN+ 插件加载失败修复 · 改动清单（2026-10-14）

**根因**：插件 dex 不带 kotlin-stdlib（约定「宿主提供」），但宿主 release 包经 R8 混淆后
kotlin-stdlib **全量改名**（release dex 里 `Lkotlin/` 类 = 0），双亲回退解析不到
`kotlin.jvm.internal.Intrinsics` → 所有 Kotlin 插件 `onLoad` 必败。debug 宿主（无 R8）能兜底，
所以此前一直没暴露。上游 HyperLyric 插件自带 kotlin（v1.1.0 自带 113 个类），child-first
ClassLoader 本来就是为「插件自带」设计的。

**修复方向**：插件自包含——kotlin-stdlib 打进 dex；宿主侧不加 keep（上游宿主没有 kotlin，
我们的插件要能跑在上游宿主上）。

仓库：`cnplus-gitee/`（Gitee 克隆，main = 5abf1b4）

---

## 第 1 组：7 个插件打包脚本（kotlin-stdlib 从排除 → 打进 dex）

### 模式 A：4 个有 runtime 依赖的脚本
`plugins/amll-ttml/build.gradle.kts`、`plugins/lyricfetch/build.gradle.kts`、
`plugins/ai-translation/build.gradle.kts`、`plugins/ai-translation-words/build.gradle.kts`

**A-1 声明处**（amll L117-121 / lyricfetch L116-120 / ai-translation L106-109 / ai-translation-words L106-109）：

```kotlin
// 改前
val runtimeJars = configurations.runtimeClasspath.get()
// kotlin-stdlib 由宿主 App 进程提供：只作 classpath，不进 dex。
val stdlibJars = runtimeJars.filter { it.name.startsWith("kotlin-stdlib") }
// 其余运行时依赖（accompanist-lyrics-core 与其传递依赖 kotlinx-serialization-json/core）
// 随插件一起进 dex。
val bundledJars = runtimeJars.filterNot { it.name.startsWith("kotlin-stdlib") }
```

```kotlin
// 改后
val runtimeJars = configurations.runtimeClasspath.get()
// 插件必须自包含：宿主 release 包经 R8 混淆，kotlin-stdlib 全量改名（无 kotlin.** keep），
// 双亲回退解析不到 kotlin.*；自带副本按 child-first 加载，才是唯一可靠来源。
// 排除 kotlin-stdlib-jdk7/jdk8 兼容壳（Kotlin 2.x 下为空 jar），防 d8 重复类。
val bundledJars = runtimeJars.filterNot { it.name.startsWith("kotlin-stdlib-jdk") }
```

**A-2 commandLine 处**（amll L133-136 / lyricfetch L132-135 / ai-translation L121-124 /
ai-translation-words L121-124）——**整块删除**：

```kotlin
stdlibJars.forEach { jar ->
    add("--classpath")
    add(jar.absolutePath)
}
```

其余不动（`--lib android.jar` 保留；插件 jar + `bundledJars` 仍是输入，L139-140 一带）。

**A-3 文件头注释**：

- amll-ttml L7-8 / lyricfetch L7-8：
  `// 2. d8 输入因此不止插件自身 jar，还要带上 runtime 依赖（kotlin-stdlib 仍由宿主提供，` +
  `//    只作为 --classpath 参与解析）。`
  → `// 2. d8 输入因此不止插件自身 jar，还要带上全部 runtime 依赖（含 kotlin-stdlib——宿主`
  `//    release 包 R8 改名后无法按原名提供 kotlin.*，插件必须自带）。`
- ai-translation L5 / ai-translation-words L5：
  `// 因此 d8 输入只有插件自身 jar；kotlin-stdlib 由宿主提供（--classpath）。`
  → `// 因此 d8 输入为插件自身 jar + kotlin-stdlib（宿主 release 包 R8 改名，无法提供 kotlin.*）。`

### 模式 B：3 个无 runtime 依赖的脚本
`plugins/scriptconvert/build.gradle.kts`、`plugins/demo/build.gradle.kts`、`plugins/template/build.gradle.kts`

**B-1 声明处**（scriptconvert L103-105 / demo L68-70 / template L70-72）：

```kotlin
// 改前
// kotlin-stdlib 由宿主 App 进程提供：作为 classpath 参与解析，不进 dex。
val stdlibJars = configurations.runtimeClasspath.get()
    .filter { it.name.startsWith("kotlin-stdlib") }
```

```kotlin
// 改后
// 插件自包含：kotlin-stdlib 打进 dex（宿主 release 包 R8 改名后无法按原名提供）。
val bundledJars = configurations.runtimeClasspath.get()
    .filterNot { it.name.startsWith("kotlin-stdlib-jdk") }
```

**B-2 commandLine 处**（scriptconvert L117-120 / demo L82-85 / template L84-87）——**整块删除**；
并在插件 jar 之后补一行（scriptconvert L123 后 / demo L88 后 / template L90 后）：

```kotlin
bundledJars.forEach { jar -> add(jar.absolutePath) }
```

**B-3 文件头注释**（scriptconvert L8 / demo L8 / template L10）：
`// 管线：jar → build-tools d8（--lib android.jar，--classpath kotlin-stdlib）→ zip。`
→ `// 管线：jar → build-tools d8（--lib android.jar；kotlin-stdlib 与其余 runtime 依赖一起进 dex）→ zip。`

> 注：template 是插件作者的复制源，必须与其余 6 个同步改。

---

## 第 2 组：宿主诊断修复（dex 头偏移写错）

`app/src/main/java/com/eza/hyperglow/plugin/PluginClassLoaderPolicy.kt`：

```kotlin
// 改前（L92-93）
private const val TYPE_IDS_OFF = 0x4C    // 实际读到 proto_ids_off
private const val CLASS_DEFS_OFF = 0x68  // 实际读到 data_size
```

```kotlin
// 改后
private const val TYPE_IDS_OFF = 0x44    // DEX 头 type_ids_off
private const val CLASS_DEFS_OFF = 0x64  // DEX 头 class_defs_off
```

（症状：解析必失败被 runCatching 吞掉，日志恒打 `classes=0 bundled(kotlin=0 kotlinx=0 androidx=0)`，
真实值 537 类/407 kotlinx——排障时会被带偏。）

可选：在该文件类注释补一句「宿主 release dex 内不存在任何 kotlin.\*\* 类（R8 全量改名），
kotlin.\* 只能由插件自带——回退双亲不是备选」。

`app/src/test/java/com/eza/hyperglow/plugin/PluginClassLoaderPolicyTest.kt`（fixture 与 parser 同错，
掩盖了 bug）：

```kotlin
// 改前（L98/L100）
buf.putInt(0x4C, typeIdsOff)
...
buf.putInt(0x68, classDefsOff)
```

```kotlin
// 改后
buf.putInt(0x44, typeIdsOff)   // 真 DEX 头偏移：fixture 必须与真实布局一致
...
buf.putInt(0x64, classDefsOff)
```

改完后现有用例 `dex overlap counts bundled packages` 即成为回归测试：老 parser 在新 fixture 上
会越界返回 null → assertNotNull 失败。

---

## 第 3 组：规范文档 `docs/PLUGIN_RELEASE_CONVENTIONS.md`（6 处 + §7 门禁）

| 行 | 改前 | 改后 |
| --- | --- | --- |
| EN L48 | `- **Must not** contain \`kotlin-stdlib\` — the host provides it.` | `- **Must** contain \`kotlin-stdlib\` — a Kotlin plugin carries its own copy. The host cannot provide it: the release app is R8-minified with no \`kotlin.**\` keep, so every stdlib class is renamed and parent-first fallback resolves nothing. Bundled copies load child-first — that is exactly why this works.` |
| EN L51-53 | `The d8 step must therefore take *all* runtime jars except \`kotlin-stdlib\` as inputs, with \`kotlin-stdlib\` supplied only as \`--classpath\`.` | `The d8 step must therefore take *all* runtime jars — including \`kotlin-stdlib\` — as inputs. (Exclude the empty \`kotlin-stdlib-jdk7/jdk8\` shims if they ever appear in the resolved graph, to avoid duplicate-class errors.)` |
| EN 清单 L148 | `[ ] dex: no plugin-API / kotlin-stdlib / platform classes bundled` | `[ ] dex: bundles the plugin's own classes + runtime deps incl. kotlin-stdlib; no plugin-API / platform classes` |
| ZH L197 | `- **不得**包含 \`kotlin-stdlib\` —— 宿主提供。` | `- **必须**包含 \`kotlin-stdlib\` —— Kotlin 插件自带一份。宿主无法提供：release 包经 R8 混淆、无 \`kotlin.**\` keep，stdlib 全量改名，双亲回退解析不到；自带副本按子优先加载，这正是它能工作的原因。` |
| ZH L200-201 | `因此 d8 步骤必须把**除 \`kotlin-stdlib\` 外的所有运行时 jar** 作为输入，\`kotlin-stdlib\` 只作 \`--classpath\`。` | `因此 d8 步骤必须把**全部运行时 jar（含 \`kotlin-stdlib\`）** 作为输入。（若依赖图出现空的 \`kotlin-stdlib-jdk7/jdk8\` 兼容壳，排除它们以免重复类。）` |
| ZH 清单 L281 | `[ ] dex：未捆绑插件 API / kotlin-stdlib / 平台类` | `[ ] dex：已捆绑插件自身类与运行时依赖（含 kotlin-stdlib）；未捆绑插件 API / 平台类` |
| §7 EN L120-124 / ZH L255-259 | 「自动化门禁」列表 | 加一条：`- \`check-plugin-dex.py\` 校验每个构建出的 ZIP：dex 引用 \`kotlin.jvm.internal.Intrinsics\` 时必须定义它（即 kotlin-stdlib 已自带），且不得捆绑插件 API 包。` |

---

## 第 4 组：插件 README（3 个文件 4 处）

| 文件:行 | 改前 | 改后 |
| --- | --- | --- |
| `plugins/template/README.md:37` | `- \`classes.dex\`（jar 经 build-tools d8 转换；kotlin-stdlib 由宿主提供，不进 dex）` | `- \`classes.dex\`（jar 经 build-tools d8 转换；含 kotlin-stdlib 在内的全部运行时依赖，插件自包含）` |
| `plugins/template/README.md:77-78` | `- \`classes.dex\` (jar converted with build-tools d8; kotlin-stdlib is provided by the host and never packaged into the dex)` | `- \`classes.dex\` (jar converted with build-tools d8; self-contained — every runtime dependency including kotlin-stdlib is packaged into the dex)` |
| `plugins/lyricfetch/README.md:120` | `d8 输入除插件自身 jar 外还包含 runtime 依赖；\`kotlin-stdlib\` 仍由宿主提供、只作 \`--classpath\`。` | `d8 输入包含插件自身 jar 与全部 runtime 依赖（含 \`kotlin-stdlib\`）；插件自包含——宿主 release 包 R8 改名后无法按原名提供 kotlin。` |
| `plugins/scriptconvert/README.md:56` | `\`:plugins:api\` 为 \`compileOnly\`，\`kotlin-stdlib\` 由宿主提供、不进 dex。` | `\`:plugins:api\` 为 \`compileOnly\`（宿主提供）；\`kotlin-stdlib\` 随插件打进 dex（插件自包含）。` |

（ai-translation / ai-translation-words / amll-ttml 的 README 无此表述，不必动。）

---

## 第 5 组：CI 防回归门禁

### 5.1 新文件 `plugins/check-plugin-dex.py`

```python
#!/usr/bin/env python3
"""插件 ZIP 自包含性检查（PLUGIN_RELEASE_CONVENTIONS §3/§7）。

对每个 hyperglow-*-plugin.zip 断言：
- ZIP 条目恰为 {manifest.json, classes.dex}；
- dex 若引用 kotlin.jvm.internal.Intrinsics，则必须定义它（= kotlin-stdlib 已随插件打进
  dex）。宿主 release 包经 R8 全量改名，无法按原名提供 kotlin.*，回退双亲不是备选；
- dex 未捆绑插件 API 包（com.lidesheng.hyperlyric.plugin.api.**，由宿主提供）。
"""
import json
import struct
import sys
import zipfile

API_PREFIX = "Lcom/lidesheng/hyperlyric/plugin/api/"
INTRINSICS = "Lkotlin/jvm/internal/Intrinsics;"


def dex_classes(data: bytes) -> list[str]:
    def u32(off: int) -> int:
        return struct.unpack_from("<I", data, off)[0]

    if data[:4] != b"dex\n":
        raise ValueError("not a dex file")
    string_ids_off = u32(0x3C)
    type_ids_off = u32(0x44)
    class_defs_size = u32(0x60)
    class_defs_off = u32(0x64)

    def get_string(idx: int) -> str:
        p = u32(string_ids_off + idx * 4)
        while data[p] & 0x80:
            p += 1
        p += 1
        end = data.index(b"\x00", p)
        return data[p:end].decode("utf-8", "replace")

    return [
        get_string(u32(type_ids_off + u32(class_defs_off + i * 32) * 4))
        for i in range(class_defs_size)
    ]


def check(path: str) -> list[str]:
    with zipfile.ZipFile(path) as z:
        entries = set(z.namelist())
        manifest = json.loads(z.read("manifest.json"))
        dex = z.read("classes.dex")
    problems = []
    if entries != {"manifest.json", "classes.dex"}:
        problems.append(f"zip entries must be exactly [manifest.json, classes.dex], got {sorted(entries)}")
    defined = dex_classes(dex)
    if INTRINSICS.encode() in dex and INTRINSICS not in defined:
        problems.append("dex references kotlin.jvm.internal.Intrinsics but does not define it "
                        "(kotlin-stdlib not bundled)")
    api = [c for c in defined if c.startswith(API_PREFIX)]
    if api:
        problems.append(f"dex bundles plugin-API classes (host-provided): {api[:3]}")
    kotlin = sum(1 for c in defined if c.startswith("Lkotlin/"))
    kotlinx = sum(1 for c in defined if c.startswith("Lkotlinx/"))
    print(f"[{'FAIL' if problems else 'OK'}] {path} id={manifest.get('id')} "
          f"v{manifest.get('version')} classes={len(defined)} kotlin={kotlin} kotlinx={kotlinx}")
    for p in problems:
        print(f"       - {p}")
    return problems


def main(paths: list[str]) -> int:
    bad = [p for p in paths if check(p)]
    return 1 if bad else 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: check-plugin-dex.py <zip> [<zip> ...]", file=sys.stderr)
        sys.exit(2)
    sys.exit(main(sys.argv[1:]))
```

### 5.2 `.github/workflows/android.yml`（publish-plugins job，L193 之后加一步）

```yaml
      - name: Verify plugin DEX self-containment
        run: python3 plugins/check-plugin-dex.py plugins/*/build/distributions/hyperglow-*-plugin.zip
```

并把 L190-191 注释里「这一步在 PR 上也会执行:它是插件打包管线唯一的自动化验证」更新为
「……唯一的自动化验证；紧随其后的 check-plugin-dex.py 校验 dex 自包含性（kotlin-stdlib 已自带、
未捆绑插件 API）」。

> publish-plugins job 在 `pull_request` 与 `main` 推送时都会跑 build 步骤，新门禁 PR 即可拦住。

---

## 第 6 组：版本号（5 个已发布插件，按 §2 semver 递增）

| 文件 | 改前 | 改后 |
| --- | --- | --- |
| `plugins/lyricfetch/src/main/plugin/manifest.json` | 1.0.1 | 1.0.2 |
| `plugins/scriptconvert/src/main/plugin/manifest.json` | 1.0.0 | 1.0.1 |
| `plugins/amll-ttml/src/main/plugin/manifest.json` | 1.0.2 | 1.0.3 |
| `plugins/ai-translation/src/main/plugin/manifest.json` | 1.0.1 | 1.0.2 |
| `plugins/ai-translation-words/src/main/plugin/manifest.json` | 1.0.2 | 1.0.3 |

（demo / template 不发布，不 bump。CI release 说明里的 LF_VER… 会自动取新版本。）

---

## 不要动的（防误改）

1. **不要**把 `kotlin.` 加进 `PluginClassLoaderPolicy.sharedPrefixes`——kotlin 必须子优先
   （插件自带副本胜出），这正是 child-first 设计；
2. **不要**给宿主 proguard 加 `-keep class kotlin.**`——只救 CN+ 自家宿主，且违背「插件自包含」
   契约（上游 HyperLyric 宿主根本没有 kotlin 类，我们的插件必须能跑在它上面）；
3. `plugins/api` 契约与 fingerprint 不动；`sharedPrefixes` 现有名单不动。

---

## 改完后的验证与发布流程

1. **构建 + 门禁**：`./gradlew :plugins:*:test :plugins:*:pluginZip` →
   `python3 plugins/check-plugin-dex.py plugins/*/build/distributions/hyperglow-*-plugin.zip`
   （应输出 5 行 OK，kotlin 计数 ~1000+）。
2. **合并 main** → `publish-plugins` job 自动重建滚动 `plugins` 预发行（资产名固定，版本号已 bump）。
3. **真机（release 宿主）**：重装两个插件 → 应见 `loaded=true`；修复后的诊断行给出真实
   `classes=NNN bundled(kotlin=NNN …)`。
4. 各插件 README「验证情况」按 §5.6 补记真机结论（含本次 release 宿主暴露的问题）。

## 体积与影响面

- 影响面：5 个已发布插件 + demo/template（全部 Kotlin）。
- 体积：不 shrink 时每插件 zip 约 260KB → ~1MB（+kotlin-stdlib 全量）；后续可选优化=改用 R8
  对插件做 shrink（上游做法：kotlin 只留用到的 ~百来个类）。
