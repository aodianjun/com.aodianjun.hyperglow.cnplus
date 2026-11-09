# 单词效果包（Word Effect Pack）

单词效果包是一份 JSON，包含两种档位：**声明式属性**（`tracks`/`colors`）或**沙箱字节码程序**
（`program`）。两者都由 App 内的共享解释器执行，预览、锁屏、AOD 与 SystemUI 画布共用同一份
求值/执行逻辑。因此效果不需要加载第三方代码，也不会突破配置通道的信任边界。

- 无第三方代码执行：程序是受控字节码，没有反射、文件、网络、线程或 Java 互操作；只能读取
  HyperGlow 提供的歌词/时间/画布上下文，并调用受控绘制原语。
- 声明式档只组合位移、缩放、旋转、透明度、倾斜、辉光、扫光进度与颜色；程序档还可以画文字、
  矩形、圆、线、路径、裁剪与变换（见第 10 节）。
- 词级粒度：每个词块一条进度轴；CJK 行级源会按字合成词块，实际表现为逐字。
- 只影响单词绘制，不改变排版、字体、背景或歌词来源。

## 1. 包信封

```json
{
  "format": "hyperglow-word-effect-pack",
  "version": 1,
  "name": "My Pack",
  "author": "someone",
  "effects": []
}
```

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `format` | string | 必须为 `hyperglow-word-effect-pack` | 包格式标识 |
| `version` | int | 必须为 `1` | 包格式版本 |
| `name` | string | 展示用，导入后作为条目来源（截断到 48 字符） | 包名 |
| `author` | string | 展示用 | 作者 |
| `effects` | array | 1..32 项 | 效果列表 |

整包上限 64 KiB。`format`、`version`、大小或效果数量不合法时，整包拒绝导入。

文件名可以是 `.hgwordanim.json`，也可以是任意 `.json`；导入器只看内容。

## 2. 效果规格（`effects[]`）

| 字段 | 类型 | 约束 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `id` | string | `^[a-z0-9_]{1,32}$` | 必填 | 稳定 id；同 id 重导入原位替换 |
| `name` | string | ≤48 字符，空则回退为 `id` | `id` | 展示名 |
| `longSyllableMs` | int | 200..3000 | `700` | 长音节阈值（毫秒） |
| `stagger` | float | 0..0.5 | `0` | 每个词块的时间轴滞后量（进度单位） |
| `sweep` | bool | `true` / `false` | `true` | 是否保留词内扫光带 |
| `sweepBand` | float | 0.05..1 | `0.28` | 扫光带宽度占词宽比例 |
| `unsungDim` | float | 0.1..0.6 | `0.35` | 未唱字暗度因子；已唱恒为 1 |
| `glow` | string | `none` / `long` / `all` | `none` | 辉光策略；未知值回落 `none` |
| `tracks` | array | ≤12 条，每条 1..16 帧，合计 ≤64 帧 | `[]` | 数值轨道 |
| `colors` | array | ≤8 帧 | `[]` | 颜色轨道；空 = 沿用已唱/未唱调色板 |

补充约束：

- `tracks` 可以为空（纯颜色效果），但列出的每条轨道至少要有一个关键帧。
- 同一 `(property, longOnly)` 组合只能出现一次，重复会拒绝该效果。
- 同属性允许一条普通轨道加一条 `longOnly: true` 轨道；对长音节，后出现的轨道覆盖先出现的值。

## 3. 轨道属性（`tracks[].property`）

| `property` | 单位 | 范围 | 缺省（无轨道时） |
| --- | --- | --- | --- |
| `translateX` | em | -2..2 | 0 |
| `translateY` | em | -2..2 | 0 |
| `scale` | 倍率 | 0.1..3 | 1 |
| `rotate` | 度 | -720..720 | 0 |
| `alpha` | 0..1 | 0..1 | 1 |
| `skewX` | 比例 | -1..1 | 0 |
| `glowRadius` | em | 0..1 | 渲染器默认半径（行高比例） |
| `glowAlpha` | 0..1 | 0..1 | 1 |
| `sweepProgress` | 0..1 | 0..1 | 词块自身进度 |

`property` 不在上表内会拒绝该效果。数值在规范化时钳制到范围内。

`glow` 只在当前表面开启辉光时可见：`long` 仅长音节，`all` 所有音节。

## 4. 关键帧语义

数值关键帧：

```json
{ "at": 0.35, "value": 1.2, "easing": "easeOut" }
```

- `at` 是**词进度** `p ∈ [0,1]`：0 = 未唱，1 = 已唱完。不是秒数。
- `easing` 作用于**本帧到下一帧**这一段；最后一帧的 `easing` 无实际作用。
- 关键帧按 `at` 升序排序；同一 `at` 保留后写的一条（后者覆盖前者）。
- 端点自动拉伸：首帧 `at > 0` 时就地改成 `at = 0`，末帧 `at < 1` 时就地改成 `at = 1`
  （不新增帧，尺寸上限稳定）；只有一个关键帧时展开成 `0` 与 `1` 两帧，效果是常量。
- `at` 与 `value` 都会被钳制；非有限数值拒绝该效果。
- 未知 `easing` 回落 `linear`（不拒绝，避免旧包整体失效）。
- 未知 `property`、非法颜色、空轨道、超限数量会拒绝该效果。
- `id` 不能是 `none`（选择器的「关闭」哨兵）；`name` 若含反斜杠、`://`、前导 `/` 等
  不安全字符串，会回落到 `id`（避免整份外观配置在下次加载时被拒）。

`stagger` 的作用方式：先按词块总数 `n` 把步长封顶到 `min(stagger, 0.9/(n-1))`，
再按 `t = clamp((p - i·s) / (1 - (n-1)·s), 0, 1)` 求值。这样最后一个词块在 `p=1`
时恰好走完，不会因为 `i·s > 1` 永久冻结在首帧；后一个词块仍然滞后，用于波浪、多米诺
一类效果。`0` 表示所有词块共用同一时间轴。

`longOnly`：轨道只对长音节生效。长音节指该词块时长 `durationMs >= longSyllableMs`；短音节
直接跳过该轨道，使用其余轨道或默认值。

## 5. 缓动（`easing`）

| 名称 | 形态 |
| --- | --- |
| `linear` | 匀速 |
| `easeIn` | 二次加速进入 |
| `easeOut` | 二次减速退出 |
| `easeInOut` | 前半加速、后半减速 |
| `easeInBack` | 起步先回拉再前进 |
| `easeOutBack` | 结束前冲过头再回位 |
| `easeOutElastic` | 弹性收敛，末尾有衰减振荡 |
| `easeOutBounce` | 落地弹跳衰减 |
| `stepEnd` | 保持起始值，段末跳到终值 |

## 6. 颜色关键帧（`colors[]`）

```json
{ "at": 0.5, "color": "#34C759", "easing": "linear" }
```

- 支持 `#RRGGBB` 与 `#AARRGGBB`；非法十六进制会拒绝该效果。
- 相邻颜色按 **RGB 线性插值**；绘制时的 alpha 由行亮度（已唱/未唱与 `unsungDim`）统一决定，
  颜色里的 alpha 不用于最终透明度。
- `at`、排序、同 `at` 覆盖、端点补齐规则与数值关键帧一致。
- 最多 8 帧；为空时沿用表面自身的已唱/未唱调色板。
- 颜色按 `stagger` 后的同一时间轴 `t` 求值，因此彩虹类效果会随词块依次推进。

## 7. 最小完整示例

```json
{
  "format": "hyperglow-word-effect-pack",
  "version": 1,
  "name": "Hello Pack",
  "author": "you",
  "effects": [
    {
      "id": "hello",
      "name": "Hello",
      "longSyllableMs": 700,
      "stagger": 0.05,
      "sweep": true,
      "sweepBand": 0.3,
      "unsungDim": 0.35,
      "glow": "all",
      "tracks": [
        {
          "property": "scale",
          "keyframes": [
            { "at": 0.0, "value": 0.8, "easing": "easeOut" },
            { "at": 0.5, "value": 1.15, "easing": "easeInOut" },
            { "at": 1.0, "value": 1.0, "easing": "linear" }
          ]
        },
        {
          "property": "glowAlpha",
          "keyframes": [
            { "at": 0.0, "value": 0.0, "easing": "easeOut" },
            { "at": 0.5, "value": 1.0, "easing": "easeIn" },
            { "at": 1.0, "value": 0.2, "easing": "linear" }
          ]
        }
      ],
      "colors": [
        { "at": 0.0, "color": "#FF7A00", "easing": "linear" },
        { "at": 1.0, "color": "#FFD166", "easing": "linear" }
      ]
    }
  ]
}
```

## 8. 导入与生命周期

可直接导入的示例包：`samples/word-effect-pack.json`（`bounce` / `spin` / `wave` /
`rainbow` / `glow_pulse` / `elastic`）。

导入路径：**Settings -> lyric appearance -> effects -> import word effect pack**
（设置 -> 歌词外观 -> 效果 -> 导入单词效果包）。

- 导入按条目处理：结构非法的条目逐条拒绝并报告序号/id，合法条目照常进入选择器。
- 选择某个效果时，**整份规格**写入 `SurfaceProfile.wordEffect`，随配置文档编译下发。
  因此删除效果包、换设备或重新导入同名包，都不会破坏已经选中的效果。
- 同 `id` 重导入是原位替换；选择器最多保留 32 条，超出时丢弃最早导入的条目。
- 已选效果进入 profile 后是自包含的：不依赖包文件、不依赖导入顺序。

## 9. 边界

- **词级粒度**：动画以词块为单位求值；CJK 行级源按字合成词块，所以中文是逐字，英文是逐词。
  没有字符内路径、笔顺或字形级控制。
- **按表面选择**：每个表面（锁屏 / AOD / SystemUI 等）各自从 profile 选择效果，互不共享。
- **无宿主代码**：声明式档没有代码；程序档是受控字节码，只有数学、跳转与绘制指令，
  没有脚本引擎、反射、文件、网络、线程或 Java 互操作。
- **图元边界**：声明式档只消费 `translateX` / `translateY` / `scale` / `rotate` / `alpha` /
  `skewX` / `glowRadius` / `glowAlpha` / `sweepProgress` 与颜色；程序档额外提供文字、矩形、
  圆、线、路径、裁剪与变换。两者都没有 shader、粒子、3D 变换或任意宿主 API。
- **有界**：整包 ≤64 KiB，每包 1..32 个效果，每效果 ≤12 条轨道、≤16 帧/轨道、≤64 帧合计、
  ≤8 个颜色帧。
- **裁剪**：启用自定义效果时，实机画布的帧级裁剪放宽到整帧，避免大位移/缩放被歌词块切平；
  这也意味着大效果可以画到时钟/歌曲信息区域。应用内预览的卡片边界仍按内容高度收紧，
  极端位移在预览里可能被卡片裁切，实机为准。
- **规范化幂等**：导入与消费两侧都会重新规范化；越界值被钳制而不是报错，结构性错误才拒绝。

## 10. 高级：效果程序（Program）

第 1–9 节是声明式 DSL（属性 + 轨道 + 关键帧）。本节是高级档：效果条目可以携带一段
**沙箱字节码** `program`，由 HyperGlow 的 `EffectVm` 在渲染时逐帧解释执行。DSL 表达不了的
逐词独立变换、条件绘制、绕词中心缩放/旋转，以及跨换行视觉行的整块绘制，都可以用它完成。

### 10.1 架构

分工只有两层：

- **HyperGlow 提供上下文与绘制原语**：`EffectFrameContext` 给出歌词、行/词时间、播放位置、
  画布尺寸、基线与调色板，并给出整块歌词的**全部视觉行**（`lines`）与块宽高
  （`blockWidth` / `blockHeight`）；`EffectRenderSink` 提供 `save` / `restore`、
  `translate` / `rotate` / `scale`、`clipRect`、`setColor` / `setAlpha` / `setTextSize` /
  `setTextAlign`、渐变/阴影/描边状态，`drawText` / `drawRect` / `drawCircle` / `drawLine` /
  `drawRoundRect` / `drawOval` / `drawArc`，`pathMove` / `pathLine` / `pathQuad` /
  `pathCubic` / `pathClose` / `drawPath` 与 `measureText`。
- **效果文件只提供程序**：一段字节码，只能读上下文、做数值运算、条件跳转、调用上述原语。
  程序每帧执行一次，负责绘制**整个歌词块**——词/行循环由程序自己写。

程序没有反射、文件、网络、线程、Java 互操作或动态类加载指令；它唯一能产生的外部效果就是
`EffectRenderSink` 上的绘制调用。`program` 与 `tracks` / `colors` 可以同时存在，但程序优先：
只要 `program` 非空，该效果走 VM，DSL 轨道不再参与。

```json
{
  "id": "my_program_effect",
  "name": "My Program Effect",
  "program": {
    "version": 1,
    "maxLocals": 8,
    "constants": [
      { "type": "number", "value": "0.5" },
      { "type": "string", "value": "hello" }
    ],
    "code": [
      { "op": "const", "arg": 0 },
      { "op": "lineField", "text": "progress" },
      { "op": "mul" },
      { "op": "end" }
    ]
  }
}
```

`program` 字段：

| 字段 | 类型 | 约束 | 默认值 | 说明 |
| --- | --- | --- | --- | --- |
| `version` | int | 必须为 `1` | `1` | 程序格式版本 |
| `constants` | array | ≤64 项 | `[]` | 常量池 |
| `code` | array | 1..512 条 | 必填 | 指令序列 |
| `maxLocals` | int | 0..24 | `16` | 局部变量槽数量 |

常量项是 `{ "type": "number" | "string" | "bool", "value": "..." }`。`type` 省略时按
`number` 解释；number 必须是有限数；string 最长 256 字符；bool 只接受 `"true"` / `"false"`。

### 10.2 上下文

程序每帧拿到的是只读快照，不能写回歌词或播放状态。上下文是**整块歌词**：`lines` 是换行后的
全部视觉行，`words` 跨行；程序每帧执行一次，自己决定画哪些词/行。

| 读取方式 | 字段 | 类型 | 含义 |
| --- | --- | --- | --- |
| `globalField` | `time` | number | 播放位置（毫秒） |
| `globalField` | `wall` | number | 真实时钟（`elapsedRealtime`，毫秒） |
| `globalField` | `baseline` | number | **首行**文字基线 y（画布像素） |
| `globalField` | `textSize` | number | 当前字号（像素） |
| `globalField` | `blockWidth` / `blockHeight` | number | 整块歌词的宽 / 高（像素） |
| `lineField` | `text` | string | 主行歌词 |
| `lineField` | `start` / `end` | number | 主行起止时间（毫秒） |
| `lineField` | `progress` | number | 主行进度 0..1 |
| `lineField` | `index` | number | 主行序号 |
| `lineCount` | — | number | 视觉行数量 n |
| `line` | `i` → `ref` | — | 取第 i 个视觉行引用 |
| `lineAtField` | `ref` → `value` | — | 读视觉行字段（见下） |
| `canvasField` | `width` / `height` | number | 画布尺寸（像素） |
| `colorField` | `sung` / `unsung` / `glow` / `accent` | number | 调色板 ARGB |
| `wordField` | `text` | string | 词块文本 |
| `wordField` | `x` | number | 词块左边界（画布像素） |
| `wordField` | `width` | number | 词块宽度（像素） |
| `wordField` | `start` / `end` | number | 词块起止时间（毫秒） |
| `wordField` | `progress` | number | 词块进度 0..1 |
| `wordField` | `index` | number | 词块序号 |
| `wordField` | `long` | bool | 是否长音节 |
| `wordField` | `line` | number | 词所属视觉行下标 |
| `wordField` | `baseline` | number | 词所属视觉行的基线 y（像素） |

`lineAtField` 的字段：`text`（string）、`start` / `end`（毫秒）、`progress`（0..1）、
`index`、`x` / `width` / `baseline` / `height`（画布像素）。`lineField` 只读宿主传入的
主行（通常是首行）；要遍历整块的所有视觉行，用 `lineCount` + `line` + `lineAtField`。
`line(i)` 下标越界时得到空引用，`text` 为 `""`、其余字段为 `0`。

`wordCount` 压入词块数量；`word` 弹出一个下标（转 int）并压入词引用；`wordField` 弹出词引用
并压入对应字段。下标越界时：`text` 为 `""`、`long` 为 `false`、其余为 `0`。

**多行块请用 `word.baseline`**：`globalField baseline` 只是首行基线；换行后每个词应画在
自己的 `word.baseline` 上。`word.line` 给出词所属视觉行下标，可与 `lineAtField` 的行几何
配合使用。

应用内预览的 `accent` 目前回落为 `glow`（实机 AOD 传真实 accent）；需要跨端一致的调色板
请优先用 `sung` / `unsung` / `glow`。

### 10.3 指令集

栈记法：右侧是栈顶；`a b → c` 表示先压 `a` 再压 `b`，执行后压入 `c`。二元运算统一为
**先弹出 b，再弹出 a，计算 `a op b`**。跳转目标是 `code` 数组的指令下标，允许
`0..code.length`（`code.length` 表示程序末尾）。

#### 常量与局部变量

| op | 栈 | 语义 |
| --- | --- | --- |
| `const` | → `c[arg]` | 压入常量池第 `arg` 项 |
| `loadLocal` | → `locals[arg]` | 压入局部槽 |
| `storeLocal` | `v` → | 弹出并写入局部槽 |
| `pop` | `v` → | 丢弃栈顶 |
| `dup` | `v` → `v v` | 复制栈顶 |
| `swap` | `a b` → `b a` | 交换栈顶两项 |

#### 上下文

| op | 栈 | 语义 |
| --- | --- | --- |
| `globalField` | → `value` | 读全局字段（`text` 指定字段名） |
| `lineField` | → `value` | 读主行字段（`text` 指定字段名） |
| `lineCount` | → `n` | 压入视觉行数量 |
| `line` | `i` → `ref` | 按下标取视觉行引用 |
| `lineAtField` | `ref` → `value` | 读视觉行字段（`text` 指定字段名） |
| `canvasField` | → `value` | 读画布宽/高 |
| `colorField` | → `argb` | 读调色板颜色（number） |
| `wordCount` | → `n` | 压入词块数量 |
| `word` | `i` → `ref` | 按下标取词引用 |
| `wordField` | `ref` → `value` | 读词字段（`text` 指定字段名） |

#### 算术与数学

| op | 栈 | 语义 |
| --- | --- | --- |
| `add` / `sub` / `mul` | `a b` → `a op b` | 加 / 减 / 乘 |
| `div` / `mod` | `a b` → `a op b` | 除 / 取模；除数为 0 时结果 0 |
| `neg` | `a` → `-a` | 取负 |
| `sin` / `cos` | `a` → `sin(a)` / `cos(a)` | 三角函数，弧度 |
| `abs` | `a` → `abs(a)` | 绝对值 |
| `min` / `max` | `a b` → `min/max` | 两值取小 / 大 |
| `clamp` | `value low high` → `v` | 钳制到 `min(low,high)..max(low,high)` |
| `lerp` | `a b t` → `v` | `a + (b-a)*clamp(t,0,1)` |
| `pow` | `a b` → `a^b` | 幂 |
| `sqrt` | `a` → `sqrt(a)` | 负值按 0 处理 |
| `floor` / `round` | `a` → `floor(a)` / `round(a)` | 向下取整 / 四舍五入 |

#### 比较与逻辑

| op | 栈 | 语义 |
| --- | --- | --- |
| `lt` / `le` / `gt` / `ge` | `a b` → `bool` | `a < b` / `a <= b` / `a > b` / `a >= b` |
| `eq` / `ne` | `a b` → `bool` | 相等 / 不等 |
| `and` / `or` | `a b` → `bool` | 逻辑与 / 或 |
| `not` | `a` → `bool` | 逻辑非 |

两个操作数都是字符串时按字典序比较；`eq` / `ne` 对两个布尔按布尔相等比较；其余按数值比较。
真值判断：数值 0、空字符串、`false`、null 为假，其余为真。

#### 字符串（逐字符布局）

| op | 栈 | 语义 |
| --- | --- | --- |
| `stringLength` | `s` → `n` | 字符串长度 |
| `charAt` | `s i` → `ch` | 取第 `i` 个字符；越界返回 `""` |
| `substring` | `s start end` → `sub` | 取 `[start, end)`；越界自动钳制 |

配合 `measureText` 与 `lineField text`，程序可以自行逐字符布局并做任意字形级动画
（注意：词时间窗仍是词块粒度，字符时间需要自己按几何/词窗分摊）。

#### 跳转

| op | 栈 | 语义 |
| --- | --- | --- |
| `jump` | → | 无条件跳到 `arg` |
| `jz` | `cond` → | 弹出条件；为假时跳到 `arg` |
| `jnz` | `cond` → | 弹出条件；为真时跳到 `arg` |

#### 绘制状态

| op | 栈 | 语义 |
| --- | --- | --- |
| `setColor` | `color` → | 颜色可以是 number ARGB，或 `#RRGGBB` / `#AARRGGBB` 字符串 |
| `setAlpha` | `a` → | 钳制到 0..1 |
| `setTextSize` | `px` → | 钳制到 1..512 |
| `setTextAlign` | `align` → | 0 = 左对齐，1 = 居中，2 = 右对齐 |
| `setGradient` | `x0 y0 x1 y1 color0 color1` → | 设置线性渐变；颜色规则同 `setColor` |
| `clearGradient` | → | 清除渐变，回到纯色 |
| `setShadow` | `radius dx dy color` → | 设置阴影；`radius` 钳制到 0..256，`dx` / `dy` 到 ±256 |
| `clearShadow` | → | 清除阴影 |
| `setStrokeWidth` | `w` → | 描边宽度；钳制到 0..128 |
| `setFill` | → | 后续图形按填充绘制 |
| `setStroke` | → | 后续图形按描边绘制 |
| `save` / `restore` | → | 保存 / 恢复画布状态 |
| `translate` | `x y` → | 平移；x/y 钳制到 ±10,000 像素 |
| `rotate` | `deg` → | 旋转；钳制到 ±7,200 度 |
| `scale` | `sx sy` → | 缩放；各自钳制到 0.01..100 |
| `clipRect` | `x y w h` → | 设置裁剪矩形 |

#### 绘制原语

| op | 栈 | 语义 |
| --- | --- | --- |
| `drawText` | `text x y` → | 绘制文字；`y` 是基线 |
| `drawRect` | `x y w h` → | 绘制矩形 |
| `drawCircle` | `cx cy r` → | 绘制圆 |
| `drawLine` | `x1 y1 x2 y2` → | 绘制线段 |
| `drawRoundRect` | `x y w h rx ry` → | 绘制圆角矩形；`rx` / `ry` 负数按 0 |
| `drawOval` | `x y w h` → | 绘制椭圆 |
| `drawArc` | `x y w h start sweep` → | 绘制圆弧；角度单位为度 |
| `pathMove` | `x y` → | 路径移动到 |
| `pathLine` | `x y` → | 路径连线到 |
| `pathQuad` | `cx cy x y` → | 二次贝塞尔曲线到 `(x, y)`，控制点 `(cx, cy)` |
| `pathCubic` | `c1x c1y c2x c2y x y` → | 三次贝塞尔曲线到 `(x, y)` |
| `pathClose` | → | 闭合路径 |
| `drawPath` | → | 绘制当前路径 |

**参数按自然顺序压栈，VM 反向弹出。** 例如 `drawText(word.text, word.x, word.baseline)`
要依次压入 `text`、`x`、`baseline` 三项；`translate(cx, cy)` 先压 `cx` 再压 `cy`；
`drawRect(x, y, w, h)` 依次压 `x`、`y`、`w`、`h`；`drawRoundRect(x, y, w, h, rx, ry)`
依次压六项；`setGradient(x0, y0, x1, y1, color0, color1)` 同理。

#### 测量与结束

| op | 栈 | 语义 |
| --- | --- | --- |
| `measureText` | `text` → `width` | 测量文字宽度（像素） |
| `end` | → | 正常结束本帧，返回成功 |

### 10.4 最小示例

下面的程序用行进度作透明度，把整行文字画在 `(40, 0.1 * 画布高度)`：

```json
{
  "version": 1,
  "maxLocals": 0,
  "constants": [
    { "type": "number", "value": "40" },
    { "type": "number", "value": "0.1" }
  ],
  "code": [
    { "op": "lineField", "text": "progress" },
    { "op": "setAlpha" },
    { "op": "lineField", "text": "text" },
    { "op": "const", "arg": 0 },
    { "op": "const", "arg": 1 },
    { "op": "canvasField", "text": "height" },
    { "op": "mul" },
    { "op": "drawText" },
    { "op": "end" }
  ]
}
```

执行过程：`lineField progress` 压入行进度，`setAlpha` 弹出它并设置透明度；随后压入
`text`、`40`、`0.1`，`canvasField height` 压入画布高度，`mul` 弹出高度与 `0.1` 得到 y，
最后 `drawText` 依次弹出 y、x、text 完成绘制。

### 10.5 示例程序

`samples/word-effect-program-pack.json` 目前有五个程序：`program_bounce`、`program_spin`
是逐词示例；`program_line_wave`、`program_line_gradient`、`program_particles` 是整块示例，
演示 `lines`、`blockWidth` / `blockHeight`、`word.line` / `word.baseline` 与新绘制原语。

#### 逐词弹跳（`program_bounce`）

伪代码：

```text
count = wordCount
i = 0
loop:
  if i >= count: goto done
  save
  w = word(i)
  cx = w.x + w.width / 2
  cy = baseline - textSize * 0.35        # 词的视觉中心
  p  = w.progress
  s  = 1 + 0.18 * sin(p * 3.14159)
  translate(cx, cy)
  scale(s, s)
  translate(-cx, -cy)
  setColor(sung)
  setAlpha(0.35 + 0.65 * p)
  drawText(w.text, w.x, baseline)
  restore
  i = i + 1
  jump loop
done:
  end
```

实际程序用槽 0–5 保存 `i`、`count`、`cx`、`cy`、`p`、`s`，用槽 7 保存 `word(i)` 的词引用
（槽 6 未用），所以 `maxLocals = 8`。`program_bounce` 为 70 条指令、7 个常量；
`program_spin` 为 57 条指令、5 个常量、`maxLocals = 5`。两者都远低于 512 条上限。
这两个是早期逐词示例，按单行布局编写，使用首行基线（`globalField baseline`）；
多行块应参考下面的整块示例。

#### 整块波浪（`program_line_wave`）

先用 `lineCount` / `line` / `lineAtField` 遍历全部视觉行，在每行基线下方画一条圆角下划线；
再遍历所有词，用 `word.index` 与 `word.progress` 求波相位，并按 `word.baseline` 绘制：

```text
for each line i:
  x, w = line.x, line.width
  h    = line.height * 0.08
  y    = line.baseline + line.height * 0.14
  setColor(sung)
  setAlpha(0.12 + 0.28 * line.progress)
  drawRoundRect(x, y, w, h, h/2, h/2)

for each word:
  p     = word.progress
  phase = word.index * 0.9 + p * 3.14159
  lift  = sin(phase) * textSize * 0.15
  s     = 1 + 0.12 * sin(phase)
  cx    = word.x + word.width / 2
  cy    = word.baseline - textSize * 0.35 + lift
  translate(cx, cy); scale(s, s); translate(-cx, -cy)
  setColor(sung); setAlpha(0.35 + 0.65 * p)
  drawText(word.text, word.x, word.baseline)
  restore
```

`program_line_wave` 为 145 条指令、13 个常量、`maxLocals = 8`。

#### 整块渐变（`program_line_gradient`）

```text
setGradient(0, 0, blockWidth, blockHeight, sung, accent)
for each word:
  setAlpha(0.30 + 0.70 * word.progress)
  drawText(word.text, word.x, word.baseline)

clearGradient
bandW = blockWidth * 0.16
h     = line(0).height
x     = (blockWidth + 2 * bandW) * lineProgress - bandW
y     = line(0).baseline - h * 0.75
setAlpha(0.08 + 0.10 * (0.5 + 0.5 * sin(time / 260)))
setColor(glow)
drawRoundRect(x, y, bandW, h, h/2, h/2)
```

整块文字共用一条从 `sung` 到 `accent` 的渐变；高亮带按 `lineField progress` 从左向右扫过
首行，透明度由 `time` 驱动呼吸。`program_line_gradient` 为 96 条指令、11 个常量、
`maxLocals = 8`。

#### 粒子（`program_particles`）

先按 `word.baseline` 画出所有词，再画 28 个粒子：

```text
for i in 0..27:
  line = lines[i % lineCount]
  x    = line.x + line.width * (0.5 + 0.5 * sin(wall / 1800 + i * 2.399))
  y    = line.baseline - line.height * (0.2 + 0.6 * (0.5 + 0.5 * cos(wall / 2100 + i * 1.7)))
  r    = 1.5 + 1.5 * (0.5 + 0.5 * sin(wall / 900 + i * 0.9))
  setAlpha(0.12 + 0.30 * (0.5 + 0.5 * sin(wall / 700 + i * 1.3)))
  setColor(accent)
  drawCircle(x, y, r)
```

粒子分摊到各视觉行，位置/半径/透明度全部由真实时钟 `wall` 驱动，与播放进度无关。
`program_particles` 为 137 条指令、18 个常量、`maxLocals = 8`；每帧固定 28 个
`drawCircle`，加上词绘制仍远低于 4096 的绘制预算。

### 10.6 安全与限额

- **无宿主访问**：指令白名单里没有反射、文件、网络、线程、Java 互操作、动态类加载，
  也没有任何能读写 HyperGlow 内部状态的指令。程序只能通过 `EffectRenderSink` 绘制。
- **每帧预算**：`MAX_EFFECT_STEPS_PER_FRAME = 100_000` 条指令，绘制/测量/状态原语另有
  `MAX_EFFECT_DRAW_OPS_PER_FRAME = 4096` 的上限。`setTextAlign` / `setGradient` /
  `clearGradient` / `setShadow` / `clearShadow` / `setStrokeWidth` / `setFill` /
  `setStroke` 与 `drawRoundRect` / `drawOval` / `drawArc` / `pathQuad` / `pathCubic`
  都计入这 4096 次预算（`EFFECT_DRAW_OPS`）。超过任一预算时 VM 返回 `false`；该行不再
  绘制（宿主只记录节流日志，**不回退到 DSL**，避免半帧绘制后重复叠加）。死循环不会卡死
  渲染线程，但会持续烧预算，应避免。
- **native 资源上限**：save 深度 ≤16，单条路径 ≤4096 点；`restore` 不会越过 VM 开始时的
  Canvas 深度，VM 结束后宿主会把未配对的 save 全部弹掉。
- **程序规模**：`code` 1..512 条，`constants` ≤64 项，`maxLocals` 0..24，操作数栈 ≤64。
  常量字符串 ≤256 字符且过不安全字符串扫描；number 必须有限；版本必须为 `1`。未知 op、
  未知字段名、越界的常量下标/局部槽/跳转目标都会使该效果被拒绝。保守估算字节数 ≤12 KiB，
  保证锁屏 + AOD 各带一份时仍留在配置 64 KiB 内；`tools/assemble_effect_program.py`
  会在组装时提前拒绝超过该估算的程序。
- **坐标与状态钳制**：`translate` 的 x/y 钳制到 ±10,000 像素；`rotate` 钳制到 ±7,200 度；
  `scale` 钳制到 0.01..100；`setAlpha` 钳制到 0..1；`setTextSize` 钳制到 1..512；
  `setShadow` 的 radius 钳制到 0..256、dx/dy 到 ±256；`setStrokeWidth` 钳制到 0..128；
  `drawRoundRect` 的 rx/ry 负数按 0。`setTextAlign` 传 0/1/2 表示左/中/右。
  `setColor` 与 `setGradient` 的颜色接受 number ARGB 或合法的 `#RRGGBB` / `#AARRGGBB`
  字符串，非法颜色回落白色。
- **与 64 KiB 的关系**：效果包导入上限是 64 KiB（`WordEffectPackStore.MAX_PACK_BYTES`）；
  选中效果后整份规格会写进 `SurfaceProfile.wordEffect`，随配置文档下发，而
  `SceneCompiler.MAX_CONFIG_BYTES` 同样是 64 KiB。程序 JSON 是逐条指令的对象数组：
  紧凑编码下 512 条指令约 17–20 KiB；如果包文件按缩进格式保存，同样规模约 36–41 KiB，
  两个满编效果就会逼近整包上限。因此要**共享常量、复用局部槽、避免长字符串常量**，
  否则程序会挤占同一份配置里其他外观字段或其它效果的空间。
  `tools/assemble_effect_program.py` 会打印生成包的实际字节数。

### 10.7 组装工具

`tools/assemble_effect_program.py` 是一个小型汇编器：用 `(op, arg, text)` 元组列表和常量
列表描述程序，自动解析标签、检查 op/字段名/下标/栈深，并输出包 JSON。

```bash
# 生成 samples/word-effect-program-pack.json（五个示例程序）
python tools/assemble_effect_program.py

# 只做自检和校验，不写文件
python tools/assemble_effect_program.py --check

# 把 JSON 打到标准输出
python tools/assemble_effect_program.py --stdout
```

作为库使用：

```python
from tools.assemble_effect_program import assemble

program = assemble(
    [
        ("wordCount", None, None),
        ("storeLocal", 1, None),
        ("const", 0, None),              # i = 0
        ("storeLocal", 0, None),
        ("label", "loop"),
        ("loadLocal", 0, None),
        ("loadLocal", 1, None),
        ("lt", None, None),
        ("jz", "done", None),
        ("loadLocal", 0, None),
        ("word", None, None),
        ("wordField", None, "text"),
        ("loadLocal", 0, None),
        ("word", None, None),
        ("wordField", None, "x"),
        ("loadLocal", 0, None),
        ("word", None, None),
        ("wordField", None, "baseline"),
        ("drawText", None, None),
        ("loadLocal", 0, None),
        ("const", 1, None),
        ("add", None, None),
        ("storeLocal", 0, None),
        ("jump", "loop", None),
        ("label", "done"),
        ("end", None, None),
    ],
    constants=[0, 1],
    max_locals=2,
)
```

工具里的 op 白名单、字段白名单与数值上限均从 `EffectProgram.kt` 复制（含 `lineCount` /
`line` / `lineAtField`、全部新绘制/状态原语与 `blockWidth` / `blockHeight` 等字段）；
静态栈深检查会沿所有跳转路径验证栈平衡，能提前发现“少压一个参数”或“循环出口栈深不一致”
这类错误。它还会校验 12 KiB 估算字节数，并打印每个程序的指令数、常量数、绘制指令数与
整包字节数。

## 11. 盒模型输出模式（Boxes Output Mode）

第 1–10 节的**直绘模式**（`outputMode` 缺省 `"draw"`）让程序逐帧直接调用受控绘制原语。
本节是第二种输出模式：程序不再触碰画布，而是**输出一组定位盒**，由宿主统一渲染。

### 11.1 程序字段

```json
{
  "version": 1,
  "outputMode": "boxes",
  "constants": [...],
  "code": [...],
  "maxLocals": 8
}
```

| 取值 | 行为 |
| --- | --- |
| `"draw"`（缺省） | 直绘：经 `EffectRenderSink` 直接调用绘制原语（第 10 节语义，逐字节不变）。 |
| `"boxes"` | 盒模型：绘制指令被翻译成盒数据；宿主在绘制线程按盒统一渲染。 |

- 归一化时未知值回落 `"draw"`（幂等；App 编译与 SystemUI 校验两侧结果一致，
  不触发 `validate_rewrote_fields`）。
- 旧程序没有该字段，解码为缺省 `"draw"`，**零迁移**。
- 直绘入口 `EffectVm.render()` 拒绝执行 `"boxes"` 程序；盒入口 `EffectVm.collectBoxes()`
  拒绝执行 `"draw"` 程序——模式互斥由 VM 强制，误用视为失败而非静默换道。

### 11.2 指令语义

与直绘完全同一套指令集与预算（100k 步/帧、4096 次绘制/测量/状态调用/帧），差别只在
绘制/状态指令的**去向**：

| 指令 | 盒模式行为 |
| --- | --- |
| `setColor` / `setAlpha` / `setTextSize` / `setTextAlign` / `setGradient` / `clearGradient` / `setShadow` / `clearShadow` / `setStrokeWidth` / `setFill` / `setStroke` | 更新「当前绘制状态」；emit 时快照进盒。 |
| `save` / `restore` / `translate` / `rotate` / `scale` | 维护矩阵栈（深度上限 16，后乘语义与 Canvas 一致）；每次绘制 emit 时把**完整 6 元素矩阵**烘焙进盒。存完整矩阵而不是平移/旋转/缩放三元组：rotate→scale→rotate 一类序列会产生错切，分解会失真。 |
| `drawText` / `drawRect` / `drawRoundRect` / `drawCircle` / `drawLine` / `drawOval` / `drawArc` | 生成对应类型盒（几何 + 状态快照 + 烘焙矩阵）。线段强制描边（与直绘一致）。 |
| `pathMove` / `pathLine` / `pathQuad` / `pathCubic` / `pathClose` / `drawPath` | 累积路径段；`drawPath` 时打包成路径盒并清空。 |
| `clipRect` | no-op（盒可被自由摆放，逐盒裁剪语义与 MV 排版冲突；仍计入 draw 预算）。 |
| `measureText` | 由宿主提供文本测量（计入 draw 预算），与对齐无关。 |

### 11.3 盒类型与字段

全部为纯数据（基础类型/字符串/数值），不含 Android `Paint`/`Path`/`Shader` 对象，
不能指定类、资源、方法、路径、URL——与第 9 节信任边界一致：

| 盒类型 | 字段 |
| --- | --- |
| `text` | `text` / `x` / `baseline`（y 为基线，与直绘 `drawText` 同约定） |
| `rect` / `roundRect` / `oval` / `arc` | 左上角 `x/y` + `width/height`（roundRect 另有 `rx/ry`，arc 另有 `startAngle/sweepAngle`） |
| `circle` | 圆心 `cx/cy` + `radius` |
| `line` | `x1/y1/x2/y2` |
| `path` | 有序段列表：`kind`（0=move 1=line 2=quad 3=cubic 4=close）+ 段参数 |

每盒附带：

- `paint`：`color` / `alpha` / `textSize` / `align` / `style`（0=fill 1=stroke）/
  `strokeWidth` / `gradient`（六参数线性渐变，可空）/ `shadow`（radius/dx/dy/color，`set=false` 表示无）；
- `transform`：`m0..m5` 完整仿射矩阵（行主序，列向量点变换
  `x' = m0·x + m1·y + m2; y' = m3·x + m4·y + m5`）。

坐标仍为程序坐标空间（逻辑画布像素）；变换由宿主渲染器在绘制期对每盒
`save → concat 矩阵 → 绘制 → restore`，因此**与直绘的 Canvas 矩阵语义逐值一致**——
同一段程序在两种模式下落点相同。

### 11.4 盒预算与失败语义

| 预算 | 上限 | 超限行为 |
| --- | --- | --- |
| 单帧盒数 | 512 | 收集失败 |
| 单路径盒段数 | 256 | 收集失败 |
| 单帧路径总段数 | 4096 | 收集失败 |
| 单文字盒字符数 | 512 | 收集失败 |
| 矩阵栈深度 | 16 | 超出部分静默忽略（与直绘 save 上限一致） |
| 步数 / 绘制调用 / 程序字节 | 与直绘相同 | 执行失败 |

失败语义优于直绘：**collectBoxes 返回 null，不提交部分盒**（直绘程序中止时可能留下
已画的部分输出；盒模式要么整帧成功、要么整帧不画）。宿主对 null 只记节流日志，
不回退内置路径（与直绘失败语义一致）。

### 11.5 宿主渲染与三端一致

- 实机与 App 预览共用 `EffectBoxRenderer`（App 侧统一渲染器），Studio 编辑器用
  Canvas2D 版 `drawBoxesOnContext`——三端同一份求值（VM 逐值一致，有 golden 对拍）+
  同一套盒→绘制映射。
- 放置与自适应测量：盒模式的垂直外扩取保守常量 2em（`BOX_MODE_VERTICAL_OVERDRAW_EM`，
  并入 `effectEdges`，与 DSL 估算取大者）；绘制期仍有整帧裁剪兜底。盒可超出歌词块，
  但不会越出画布表面——与直绘程序同政策。
- 字体/字距继承宿主主 Paint（盒模式暂无 `setTypeface` / `setLetterSpacing` 指令）。

### 11.6 编辑器支持

Word Effect Studio 的程序编辑器提供「输出模式」选择（draw / boxes）；汇编器编译时把
所选模式写入 `program.outputMode`，导入/导出/复制/模板均保留。盒模式下编辑器预览走
收集器 + 盒渲染器，预算超限与实机同样整块不绘制并提示。
