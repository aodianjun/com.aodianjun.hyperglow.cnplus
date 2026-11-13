# 自定义歌词动画接口文档

适用范围：单词效果包里的沙箱效果程序（`EffectProgram`）、手动排版覆盖表
（`wordOverrides`）、贴纸（`drawSprite`）与 Motion 编辑器工程格式。

实现位置：`effect/EffectProgram.kt`、`effect/EffectRuntime.kt`、
`root/customization/SpriteWire.kt`、`tools/word-effect-editor/engine.js`、`motion.js`。
两边共用同一套校验与预算；App 编译与 SystemUI 校验必须给出相同结果。

---

## 1. 顶层结构

一个单词效果包：

```json
{
  "format": "hyperglow-word-effects",
  "version": 1,
  "name": "包名",
  "author": "作者",
  "effects": [
    {
      "id": "motion_lyric_abc12",
      "name": "展示名",
      "longSyllableMs": 700,
      "stagger": 0,
      "sweep": false,
      "sweepBand": 0.28,
      "unsungDim": 0.35,
      "glow": "none",
      "tracks": [],
      "colors": [],
      "program": { }
    }
  ]
}
```

- `effects` 1..64 个；单包文件 ≤64 KiB（导入时校验）。
- `program` 为 `null` 时走 DSL 轨道；非 null 时整块歌词交给沙箱程序绘制。
- 程序是**纯数据 + 沙箱字节码**：不能指定类、资源、方法、文件路径、URL、命令或可执行代码。

## 2. 程序对象

```json
{
  "version": 1,
  "constants": [{ "type": "number", "value": "0.5" }],
  "code": [{ "op": "push", "arg": 0 }],
  "maxLocals": 16,
  "outputMode": "boxes",
  "wordOverrides": []
}
```

| 字段 | 类型 | 约束 | 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `version` | int | 必须为 `1` | `1` | 程序格式版本 |
| `constants` | array | ≤128 项 | `[]` | 常量池 |
| `code` | array | 1..1024 条 | 必填 | 指令序列 |
| `maxLocals` | int | 0..24 | `16` | 局部槽数量 |
| `outputMode` | string | `draw` / `boxes` | `draw` | 直绘或盒模型 |
| `wordOverrides` | array | ≤256 项 | `[]` | 逐词手动排版覆盖表 |

常量项：`{ "type": "number" | "string" | "bool", "value": "..." }`；`type` 省略按 number；
number 必须有限；string ≤256 字符且不能包含路径/URL/命令样式内容；bool 只接受 `"true"`/`"false"`。

## 3. 指令集

栈记号：`pop → push` 为净栈变化；`-1` 表示净弹 1，`0` 表示不变。

### 3.1 数据与局部变量

| 指令 | 操作数 | 栈 | 说明 |
| --- | --- | --- | --- |
| `push` | 字面量 | +1 | 推入数字/字符串/bool（汇编期转常量） |
| `const` | 常量下标 | +1 | 推入常量池项 |
| `loadLocal` | 槽号 | +1 | 读局部变量 |
| `storeLocal` | 槽号 | -1 | 写局部变量 |
| `pop` | — | -1 | 丢弃栈顶 |
| `dup` | — | +1 | 复制栈顶 |
| `swap` | — | 0 | 交换栈顶两项 |
| `lineCount` | — | +1 | 本帧视觉行数 |
| `wordCount` | — | +1 | 本帧词数 |
| `line` | — | 0 | 压入第 i 行对象（弹下标） |
| `word` | — | 0 | 压入第 i 个词对象（弹下标） |

### 3.2 上下文字段

| 指令 | 字段 | 栈 | 说明 |
| --- | --- | --- | --- |
| `globalField` | `time` | +1 | 播放位置（毫秒） |
| | `wall` | +1 | 墙钟毫秒（`elapsedRealtime`） |
| | `baseline` | +1 | 本帧基线（像素） |
| | `textSize` | +1 | 主行字号（像素） |
| | `blockWidth` / `blockHeight` | +1 | 歌词块宽高 |
| `lineField` | 当前行字段 | +1 | 见 3.3 |
| `lineAtField` | 行字段 | 0 | 弹行对象、压字段值 |
| `canvasField` | `width` / `height` | +1 | 画布尺寸 |
| `colorField` | `sung` / `unsung` / `glow` / `accent` | +1 | 调色板 ARGB |
| `wordField` | 词字段 | 0 | 弹词对象、压字段值 |

### 3.3 行 / 词字段

行字段（`lineField` / `lineAtField`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `text` | string | 当前**视觉行**文本（折行后的一段） |
| `fullText` | string | 该视觉行所属的**整首歌词行**完整文本；无折行时等于 `text` |
| `start` / `end` | number | 行窗（毫秒） |
| `progress` | number | 行进度 0..1 |
| `index` | number | 视觉行号（0 起，本块内） |
| `x` / `width` / `baseline` / `height` | number | 行几何（像素） |

词字段（`wordField`）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `text` | string | 词文本 |
| `x` / `width` / `baseline` | number | 词几何（像素） |
| `start` / `end` / `progress` | number | 词窗与词进度 |
| `index` | number | 词在本帧的全局下标 |
| `long` | bool | 是否长音节（≥`longSyllableMs`） |
| `line` | number | 词所属视觉行号 |

### 3.4 算术 / 比较 / 字符串

| 指令 | 栈 | 说明 |
| --- | --- | --- |
| `add` `sub` `mul` `div` `mod` | -1 | 二元运算（`div`/`mod` 除零返回 0） |
| `neg` `sin` `cos` `abs` `sqrt` `floor` `round` | 0 | 一元运算 |
| `min` `max` `pow` | -1 | 二元 |
| `clamp` | -2 | `clamp(value,low,high)`（弹 3 压 1） |
| `lerp` | -2 | `lerp(a,b,t)` |
| `lt` `le` `gt` `ge` `eq` `ne` | -1 | 比较，压 0/1 |
| `and` `or` | -1 | 逻辑 |
| `not` | 0 | 逻辑非 |
| `stringLength` | 0 | 字符串长度 |
| `charAt` | -1 | 弹下标与字符串，压单字符字符串 |
| `substring` | -2 | 弹 end、start、字符串 |

### 3.5 控制流

| 指令 | 栈 | 说明 |
| --- | --- | --- |
| `jump` / `end` | 0 | 无条件跳 / 结束 |
| `jz` / `jnz` | -1 | 条件为假/真时跳转 |

### 3.6 绘制状态

| 指令 | 栈 | 说明 |
| --- | --- | --- |
| `setColor` | -1 | ARGB 颜色 |
| `setAlpha` | -1 | 0..1 |
| `setTextSize` | -1 | 1..512 |
| `setTextAlign` | -1 | 0=左 1=中 2=右 |
| `setGradient` | -6 | `x0 y0 x1 y1 color0 color1` |
| `clearGradient` | 0 | 清除渐变 |
| `setShadow` | -4 | `radius dx dy color` |
| `clearShadow` | 0 | 清除阴影 |
| `setStrokeWidth` | -1 | 0..128 |
| `setFill` / `setStroke` | 0 | 填充 / 描边样式 |
| `save` / `restore` | 0 | 变换栈（深度 ≤16） |
| `translate` | -2 | 弹 y、x |
| `rotate` | -1 | 角度（度） |
| `scale` | -2 | 弹 sy、sx（0.01..100） |
| `clipRect` | -4 | 弹 y2 x2 y1 x1；盒模式为 no-op |

### 3.7 绘制与测量

| 指令 | 操作数（弹栈顺序） | 栈 | 说明 |
| --- | --- | --- | --- |
| `drawText` | y, x, text | -3 | 基线 y |
| `drawRect` | y2, x2, y1, x1 | -4 | |
| `drawRoundRect` | ry, rx, y2, x2, y1, x1 | -6 | |
| `drawCircle` | r, cy, cx | -3 | |
| `drawLine` | y2, x2, y1, x1 | -4 | 强制描边 |
| `drawOval` | y2, x2, y1, x1 | -4 | |
| `drawArc` | sweep, start, h, w, y, x | -6 | 角度制 |
| `drawSprite` | h, w, y, x, id | -5 | 见第 6 节 |
| `pathMove` / `pathLine` | y, x | -2 | |
| `pathQuad` | y, x, cy, cx | -4 | |
| `pathCubic` | y, x, cy2, cx2, cy1, cx1 | -6 | |
| `pathClose` / `drawPath` | — | 0 | |
| `measureText` | text | 0 | 弹文本、压宽度（计入 drawOps） |
| `wordOverride` | word, line, lineText | +2 | 弹 3 压 5，见第 5 节 |

## 4. 输出模式

- `draw`（默认）：经 `EffectRenderSink` 直接调用受控 Canvas 原语；旧行为逐值不变。
- `boxes`：输出有界定位盒（text/rect/roundRect/circle/line/oval/arc/path/sprite），
  由宿主 `EffectBoxRenderer` 统一绘制；实机与 App 预览共用。
- 模式互斥：`boxes` 程序调用 `render()` 返回 false；`draw` 程序调用 `collectBoxes()` 返回 null。
- 盒预算：单帧 512 盒、单路径 256 段、全帧路径 4096 段、单文字盒 512 字符；
  超限或执行失败整帧中止，**不提交部分盒**。

## 5. 逐词手动排版覆盖（wordOverrides）

### 5.1 数据结构

```json
{
  "line": 0,
  "word": 3,
  "lineText": "水从天上来落在窗台前的光",
  "dx": 1.5,
  "dy": -0.8,
  "scale": 1.8,
  "rotate": 20,
  "alpha": 0.7
}
```

| 字段 | 类型 | 范围/默认 | 说明 |
| --- | --- | --- | --- |
| `line` | int | ≥0，默认 0 | 视觉行号（0 起） |
| `word` | int | ≥0，默认 0 | 词号（本帧 `wordField index`） |
| `lineText` | string | ≤256，默认 `""` | 整首歌词行文本；非空时**优先按文本匹配** |
| `dx` / `dy` | number | 绝对值 ≤20，默认 0 | 位移，单位 em |
| `scale` | number | 0.05..8，默认 1 | 缩放 |
| `rotate` | number | 绝对值 ≤720，默认 0 | 旋转（度） |
| `alpha` | number | 0..1，默认 1 | 透明度倍率 |

### 5.2 查找规则

`wordOverride` 弹三个操作数：`lineText`（string）、`line`（int）、`word`（int），压 5 个值：
`dx, dy, scale, rotate, alpha`。

1. `lineText` 非空且表中有 `(lineText, word)` → 用它；
2. 否则用 `(line, word)`（视觉行号）匹配；
3. 都没有 → 压恒等值 `0 0 1 0 1`。

`lineText` 匹配跨折行稳定：同一整首歌词行的所有视觉行 `fullText` 相同；重复歌词行会同时命中
（同一句重复出现时按同一排版处理）。需要区分重复句时使用视觉行号键。

### 5.3 预算与校验

- 最多 256 条；`lineText` ≤256 字符且不能含 `NUL`；数值必须有限且在范围内；
- 每条按 `48 + lineText.length` 字节计入 16 KiB 估算预算；
- 任一条非法 → 整个程序校验失败（不静默丢弃）；
- 空表 = 旧程序行为逐值不变。

### 5.4 新指令

`wordOverride` 的汇编示例：

```
loadLocal 2
lineAtField fullText
loadLocal 3        ; 词所属视觉行号
loadLocal 0        ; 词号
wordOverride       ; → dx dy scale rotate alpha
```

未命中时返回 `0 0 1 0 1`，可直接乘/加到排版参数上，无需分支。

## 6. 贴纸（drawSprite）

### 6.1 编号

- `0..15`：内置素材（`assets/deco/<id>.png`：星、四角星、圆环、三点、胶带、波浪、箭头、
  心、音符、月牙、角框、点阵、半调、下划线、斜杠、标签框）；`16..63` 为内置保留。
- `64..255`：用户导入贴纸。App「外观 → 自定义贴纸」导入后分配编号；配置发布时图片字节经
  只读 `ParcelFileDescriptor` 传输，SystemUI 按 sha256 + 尺寸/像素预算校验后解码。
- 贴图盒只携带编号，不携带路径/URL/字节。

### 6.2 绘制语义

`drawSprite` 弹 `id, x, y, w, h`（先压 id，再 x、y、w、h）。素材按当前 `setColor` 着色
（PorterDuff SRC_IN），`setAlpha` 控制透明度；阴影/描边状态同样生效。编号越界或图片缺失
静默跳过该盒，不整帧失败。

### 6.3 编辑器贴纸字段

Motion 手动模式的贴纸在导出时编译为 `drawSprite`：

| 字段 | 类型 | 范围/默认 | 说明 |
| --- | --- | --- | --- |
| `id` | int | 0..255 | 素材编号 |
| `x` / `y` | number | 0..1（画布比例） | 左上角 |
| `w` / `h` | number | 0.01..1（画布比例） | 尺寸 |
| `rotate` | number | -720..720 | 度 |
| `alpha` | number | 0..1 | 透明度 |
| `anim` | string | `none`/`float`/`pulse`/`spin`/`fade`/`pop` | 动画 |
| `amp` | number | 0..0.5 | 幅度 |
| `speed` | number | 0.05..360 | 速度（spin 为度/秒，其余为角频率） |
| `phase` | number | -100..100 | 相位（编辑器自动给，一般不用手填） |

最多 6 张；画在文字之前（背景层）。本机预览图只存工程 JSON，不进效果包。

### 6.4 动画公式

令 `t = globalField time / 1000`：

| 动画 | 公式 |
| --- | --- |
| `float` | `cx = cx0 + sin(t*speed+phase)*amp*W`；`cy = cy0 + cos(t*speed+phase*0.8)*amp*0.6*H` |
| `pulse` | `scale = 1 + sin(t*speed+phase)*amp` |
| `spin` | `rotate = baseRotate + t*speed` |
| `fade` | `alpha = baseAlpha * (0.4 + 0.6*(0.5+0.5*sin(t*speed+phase)))` |
| `pop` | `k = min(1, t*2)`；`scale = k`，`alpha = baseAlpha*k`（仅开头约 0.5 秒） |

旋转/缩放围绕贴纸中心；动画从整首开始计时。

## 7. Motion 工程格式（工程 JSON）

`format = "hyperglow-motion-project"`，与效果包分开保存，用于回灌编辑器：

```json
{
  "format": "hyperglow-motion-project",
  "version": 1,
  "lyrics": "...",
  "bpm": 120,
  "startSec": 0,
  "lineDurSec": 0,
  "snap": false,
  "tap": [],
  "seed": 1,
  "theme": "lyric",
  "overrides": {},
  "manual": {
    "enabled": true,
    "anchorX": 0.5,
    "anchorY": 0.5,
    "scale": 1,
    "rotate": 0,
    "overrides": [],
    "sprites": []
  },
  "manualImages": { "80": "data:image/png;base64,..." },
  "palette": { "sung": "#ffffff", "glow": "#66aaff" }
}
```

- `manual.overrides` 的项与第 5 节字段一致（编辑器用 `line` 索引，导出时自动补 `lineText`）。
- `manualImages` 只用于网页预览，按贴纸编号索引；**永不写入效果包**。
- 工程文件不受 64 KiB 包上限约束；图片已缩到 ≤256px。

## 8. 预算与失败语义

| 项目 | 上限 | 超限结果 |
| --- | --- | --- |
| 指令数 | 1024 | 程序非法 |
| 常量数 | 128 | 程序非法 |
| 估算字节 | 16 KiB（`code*12 + constants + overrides*48`） | 程序非法 |
| 单帧步数 | 100,000 | 该帧中止 |
| 单帧 drawOps | 4096 | 该帧中止 |
| 盒数 | 512 | 整帧不提交 |
| 路径段 | 单盒 256 / 全帧 4096 | 整帧不提交 |
| 文字盒字符 | 512 | 整帧不提交 |
| 单词效果包 | 64 KiB | 导入拒绝 |

失败语义：程序校验失败 → 整个效果被拒绝（不回落 DSL）；运行超限 → 整块不绘制，
不回退内置动画。编辑器导出用紧凑 JSON，避免 1024 条程序缩进后超过 64 KiB 包上限。

## 9. 信任边界

- 程序只能携带数值、字符串、bool 与字节码；字符串不能是路径/URL/命令，长度 ≤256；
- `wordOverrides` 只能携带行号、词号、数值与行文本，不能指定资源或代码；
- 贴纸编号 64..255 只是编号；图片字节由 App 侧导入、经 fd 通道下发、SystemUI 校验后解码，
  文档里不出现路径/URL；
- 未知操作码、越界下标/槽号/跳转、非有限数值都会使程序被拒；归一化幂等，App 与 SystemUI
  两侧结果一致。

## 10. 兼容性

| 特性 | 要求 |
| --- | --- |
| `outputMode: boxes` | 支持盒模型的版本 |
| `wordOverrides` / `wordOverride` / `fullText` | 2026-11-10 之后构建的 APK（本仓库 `fc3c41a` 起） |
| 指令数 >512 或常量 >64 | 同上（本仓库 `fc3c41a` 起） |
| 自定义贴纸 64..255 | 支持「自定义贴纸」导入与 fd 通道的版本 |
| 手动贴纸动画 | 仅依赖 `drawSprite` + `globalField time`，程序侧无新指令 |

旧版 App 遇到未知指令会判整份效果非法；升级 APK 后重新导入即可。

## 11. 最小示例

### 11.1 逐词覆盖（完整可运行汇编）

下面这段程序遍历全部词，按 `wordOverride` 应用位移/缩放/旋转/透明度，并绕词中心旋转绘制。
（贴纸写法相同：在词循环之前 push id,x,y,w,h 调 `drawSprite`。）

```
colorField sung
setColor
wordCount
storeLocal 1
push 0
storeLocal 0
loop:
  loadLocal 0
  loadLocal 1
  lt
  jz done
  loadLocal 0
  word
  storeLocal 6
  ; 默认位置/缩放/旋转/透明度
  loadLocal 6
  wordField x
  storeLocal 10
  loadLocal 6
  wordField baseline
  storeLocal 11
  push 1
  storeLocal 12
  push 0
  storeLocal 19
  loadLocal 6
  wordField progress
  push 0.65
  mul
  push 0.35
  add
  storeLocal 14
  ; 覆盖:整行文本 + 视觉行 + 词号
  loadLocal 2
  lineAtField fullText
  loadLocal 3
  loadLocal 0
  wordOverride
  ; 栈: dx dy scale rotate alpha
  loadLocal 14
  mul
  storeLocal 14
  storeLocal 19
  loadLocal 12
  mul
  storeLocal 12
  globalField textSize
  mul
  loadLocal 11
  add
  storeLocal 11
  globalField textSize
  mul
  loadLocal 10
  add
  storeLocal 10
  ; 字号与枢轴
  loadLocal 12
  globalField textSize
  mul
  setTextSize
  loadLocal 10
  loadLocal 6
  wordField text
  measureText
  push 0.5
  mul
  add
  storeLocal 8
  loadLocal 11
  globalField textSize
  loadLocal 12
  mul
  push 0.35
  mul
  sub
  storeLocal 9
  loadLocal 14
  setAlpha
  ; 绕词中心旋转绘制
  save
  loadLocal 8
  loadLocal 9
  translate
  loadLocal 19
  rotate
  loadLocal 8
  push -1
  mul
  loadLocal 9
  push -1
  mul
  translate
  loadLocal 6
  wordField text
  loadLocal 10
  loadLocal 11
  drawText
  restore
  loadLocal 0
  push 1
  add
  storeLocal 0
  jump loop
done:
  end
```

### 11.2 程序 JSON（词覆盖片段）

```json
{
  "version": 1,
  "maxLocals": 20,
  "constants": [{ "type": "string", "value": "水从天上来" }],
  "code": [
    { "op": "loadLocal", "arg": 2 },
    { "op": "lineAtField", "text": "fullText" },
    { "op": "loadLocal", "arg": 3 },
    { "op": "loadLocal", "arg": 0 },
    { "op": "wordOverride" }
  ],
  "outputMode": "boxes",
  "wordOverrides": [
    { "line": 0, "word": 2, "lineText": "水从天上来", "dx": 1.5, "dy": -0.8,
      "scale": 1.8, "rotate": 20, "alpha": 0.7 }
  ]
}
```

（片段只演示取覆盖；完整程序需自行消费 5 个值并绘制。）
