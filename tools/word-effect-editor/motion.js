/*
 * HyperGlow Motion — 歌词动态排版编排器核心(无 DOM,Node/浏览器共用)。
 *
 * 参考 JIZURA(https://github.com/852wa/JIZURA,MIT)的创作流概念,落到 HyperGlow
 * 盒模式管线上:轻量歌词语法 → 时间轴 → seed 驱动的编排参数 → 参数化四手法
 * (波浪/弹跳/滑入/强调)盒模式程序 → 效果包导出。预览与实机共用同一 VM。
 *
 *   node tools/word-effect-editor/test_motion.js
 */
'use strict';

/*
 * 双端加载:Node(require) 与浏览器(window.HGMotion,依赖 engine.js 的 window.WEEngine)。
 * 与 engine.js 的 UMD 包装保持同一模式,预览/实机/测试共用一份实现。
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory(require('./engine.js'));
  } else {
    root.HGMotion = factory(root.WEEngine);
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function (E) {

// ---------------------------------------------------------------------------
// 1. 歌词语法解析(JIZURA 子集)
// ---------------------------------------------------------------------------

/**
 * 语法:
 *   [mm:ss.xx]歌词        LRC 时间戳(优先采用)
 *   [间奏 8]              间奏 8 秒(省略 = 4);间奏区不渲染歌词
 *   我还记得/黎明的颜色    `/` 镜头切分点(记录 shot 边界)
 *   *黎明*                强调段(剥离;行内含强调 → 行幅度加成)
 *   行末!                 闪光(行开始瞬间全亮脉冲)
 *   歌词|注音             注音/备注(HyperGlow 的 ruby 由宿主绘制,导出时仅记录)
 *   # 开头                注释
 *   空行                  短停顿(不计行)
 *
 * 返回 { lines, warnings }。lines[i]:
 *   { text, annotation, flash, emph, shotCount, lrcTime(sec|null), interlude(sec|null) }
 */
function parseLyrics(source) {
  const warnings = [];
  const lines = [];
  const rawLines = String(source == null ? '' : source).split(/\r?\n/);
  for (const raw of rawLines) {
    let line = raw.replace(/^\uFEFF/, '');
    const trimmed = line.trim();
    if (!trimmed) continue; // 空行 = 停顿
    if (trimmed.startsWith('#')) continue; // 注释

    let lrcTime = null;
    let interlude = null;
    let text = trimmed;

    // [mm:ss.xx] 或 [间奏 n]
    const bracket = /^\[([^\]]*)\]\s*/.exec(text);
    if (bracket) {
      const inner = bracket[1].trim();
      text = text.slice(bracket[0].length);
      if (/^间奏/.test(inner)) {
        const seconds = parseFloat(inner.slice(2).trim());
        interlude = Number.isFinite(seconds) && seconds > 0 ? seconds : 4;
        if (text) {
          warnings.push('间奏行「' + inner + '」上的歌词被忽略:' + text);
          text = '';
        }
      } else {
        const lrc = parseLrcTime(inner);
        if (lrc != null) {
          lrcTime = lrc;
        } else {
          warnings.push('无法识别的时间标签 [『' + inner + '』],按普通行处理');
        }
      }
    }
    if (interlude != null) {
      lines.push({ text: '', annotation: '', flash: false, emph: false, shotCount: 1, lrcTime: null, interlude });
      continue;
    }
    if (!text) continue;

    // 行末 !(闪光;允许 !! 连用,按一次计)
    let flash = false;
    if (/!\s*$/.test(text)) {
      flash = true;
      text = text.replace(/!\s*$/, '');
    }

    // 注音:最后一个 | 之后是注音(与 JIZURA 一致,取行内首个 | 之后整段)
    let annotation = '';
    const pipe = text.indexOf('|');
    if (pipe >= 0) {
      annotation = text.slice(pipe + 1).trim();
      text = text.slice(0, pipe);
    }

    // 强调 *...*
    let emph = false;
    text = text.replace(/\*([^*]+)\*/g, (_, inner) => {
      emph = true;
      return inner;
    });
    text = text.replace(/\*/g, '');

    // 镜头切分 /
    const shotCount = text.split('/').length;
    text = text.replace(/\//g, '');
    text = text.trim();
    if (!text) {
      warnings.push('一行剥离标记后没有歌词,已跳过');
      continue;
    }

    lines.push({ text, annotation, flash, emph, shotCount, lrcTime, interlude: null });
  }
  return { lines, warnings };
}

/** "mm:ss.xx" / "mm:ss" / "ss" → 秒;失败返回 null。 */
function parseLrcTime(text) {
  const value = String(text == null ? '' : text).trim();
  const full = /^(\d+):(\d+(?:\.\d+)?)$/.exec(value);
  if (full) return parseInt(full[1], 10) * 60 + parseFloat(full[2]);
  const bare = /^\d+(?:\.\d+)?$/.test(value);
  if (bare) return parseFloat(value);
  return null;
}

/** 秒 → "[mm:ss.xx]"。 */
function formatLrcTime(seconds) {
  const total = Math.max(0, seconds);
  const minutes = Math.floor(total / 60);
  const rest = total - minutes * 60;
  const whole = Math.floor(rest);
  const hundredths = Math.round((rest - whole) * 100);
  if (hundredths >= 100) {
    return formatLrcTime(minutes * 60 + whole + 1);
  }
  return (
    '[' +
    String(minutes).padStart(2, '0') +
    ':' +
    String(whole).padStart(2, '0') +
    '.' +
    String(hundredths).padStart(2, '0') +
    ']'
  );
}

// ---------------------------------------------------------------------------
// 2. 时间轴
// ---------------------------------------------------------------------------

/**
 * 把时间分配到每行。优先级:点击同步(tap) > LRC 时间戳 > BPM 铺底。
 * 间奏行占用自己的时长,其后行顺延。可选拍点吸附。
 *
 * params: { bpm, startSec, beatsPerLine, lineDurSec, tap: number[], snap, manualTimes }
 * manualTimes: { [lyricIndex]: 秒 } —— 行列表手动改的时间,优先级最高。
 * 返回 { rows, total }。rows[i] = { ...line, index, start, end, manual }
 */
function buildTimeline(parsed, params) {
  const manualTimes = (params.manualTimes && typeof params.manualTimes === 'object')
    ? params.manualTimes
    : {};
  const bpm = clampNumber(params.bpm, 30, 300, 120);
  const beatSec = 60 / bpm;
  const lineSec = Number.isFinite(params.lineDurSec) && params.lineDurSec > 0
    ? params.lineDurSec
    : beatSec * (Number.isFinite(params.beatsPerLine) ? params.beatsPerLine : 4);
  const startSec = Number.isFinite(params.startSec) && params.startSec >= 0 ? params.startSec : 0;
  const snap = !!params.snap;
  const tap = Array.isArray(params.tap) ? params.tap.filter(Number.isFinite) : [];

  const rows = [];
  let cursor = startSec;
  let tapIndex = 0;
  let lyricIndex = 0;
  for (const line of parsed.lines) {
    if (line.interlude != null) {
      const start = cursor;
      const end = start + line.interlude;
      rows.push({ ...line, index: rows.length, start, end, manual: false, interlude: line.interlude });
      cursor = end;
      continue;
    }
    let start;
    let manual = false;
    const manualTime = manualTimes[lyricIndex];
    if (Number.isFinite(manualTime) && manualTime >= 0) {
      start = manualTime;
      manual = true;
    } else if (tapIndex < tap.length) {
      start = tap[tapIndex];
      manual = true;
      tapIndex++;
    } else if (line.lrcTime != null) {
      start = line.lrcTime;
      manual = true;
    } else {
      start = cursor;
      // BPM 铺底行吸附到拍点(吸附允许向前挪,防重叠由下一行钳制兜底)
      if (snap) {
        start = Math.round(start / beatSec) * beatSec;
      }
    }
    // 防重叠:不早于上一行的结束(tap/LRC 手动值回退时顺延;吸附回撤时兜底)
    const previousEnd = rows.length ? rows[rows.length - 1].end : -Infinity;
    if (start < previousEnd) {
      start = previousEnd;
    }
    const end = start + lineSec;
    rows.push({ ...line, index: rows.length, lyricIndex, start, end, manual });
    cursor = end;
    lyricIndex++;
  }
  const total = rows.length ? rows[rows.length - 1].end : 0;
  return { rows, total, lineSec };
}

// ---------------------------------------------------------------------------
// 3. seed 与编排参数
// ---------------------------------------------------------------------------

/** mulberry32 — 小而快的确定性 PRNG。 */
function makeRng(seed) {
  let state = (typeof seed === 'number' && Number.isFinite(seed) ? Math.floor(seed) : 1) >>> 0;
  if (state === 0) state = 0x9e3779b9;
  return function next() {
    state = (state + 0x6d2b79f5) >>> 0;
    let t = state;
    t = Math.imul(t ^ (t >>> 15), t | 1);
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61);
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

const THEMES = {
  lyric: {
    name: '抒情',
    waveAmp: [0.08, 0.16],
    waveK: [1.6, 3.0],
    rotAmp: [2, 8],
    bounceH: [0.08, 0.16],
    slideD: [0.35, 0.6],
    slideStagger: [0.02, 0.06],
    pulse: [0.10, 0.18],
    cycles: [1, 2],
    unsungDim: [0.30, 0.40]
  },
  dynamic: {
    name: '动态文字',
    waveAmp: [0.16, 0.30],
    waveK: [3.0, 6.0],
    rotAmp: [6, 14],
    bounceH: [0.18, 0.34],
    slideD: [0.5, 0.9],
    slideStagger: [0.04, 0.10],
    pulse: [0.18, 0.30],
    cycles: [2, 3],
    unsungDim: [0.34, 0.46]
  },
  typepv: {
    name: '文字PV',
    waveAmp: [0.03, 0.08],
    waveK: [1.0, 1.8],
    rotAmp: [0, 3],
    bounceH: [0.04, 0.10],
    slideD: [0.8, 1.4],
    slideStagger: [0.06, 0.14],
    pulse: [0.06, 0.14],
    cycles: [1, 1],
    unsungDim: [0.18, 0.30]
  },
  japanese: {
    name: '和风',
    waveAmp: [0.08, 0.14],
    waveK: [1.5, 2.6],
    rotAmp: [4, 10],
    bounceH: [0.10, 0.20],
    slideD: [0.4, 0.8],
    slideStagger: [0.03, 0.07],
    pulse: [0.12, 0.22],
    cycles: [2, 2],
    unsungDim: [0.28, 0.40]
  }
};

/**
 * 命名预设:每条是一组调好的完整参数(覆盖主题随机),选了即用、同预设同观感。
 * params 走 styleFromSeed 的覆盖通道,slideStagger / slideAlt 一并锁定。
 */
const PRESETS = {
  ballad: {
    name: '抒情慢歌',
    theme: 'lyric',
    params: { waveAmp: 0.10, waveK: 2.2, rotAmp: 4, bounceH: 0.12, slideD: 0.35, slideStagger: 0.03, slideAlt: true, pulse: 0.12, cycle: 2, flash: false, unsungDim: 0.34 }
  },
  amll: {
    name: 'AMLL 大字卡点',
    theme: 'typepv',
    params: { waveAmp: 0.04, waveK: 1.2, rotAmp: 0, bounceH: 0.32, slideD: 0.0, slideStagger: 0.05, slideAlt: false, pulse: 0.30, cycle: 1, flash: true, unsungDim: 0.44 }
  },
  cascade: {
    name: '逐词瀑布',
    theme: 'dynamic',
    params: { waveAmp: 0.0, waveK: 2.0, rotAmp: 0, bounceH: 0.05, slideD: 0.9, slideStagger: 0.10, slideAlt: false, pulse: 0.10, cycle: 1, flash: false, unsungDim: 0.38 }
  },
  typewriter: {
    name: '打字机',
    theme: 'typepv',
    params: { waveAmp: 0.0, waveK: 1.0, rotAmp: 0, bounceH: 0.0, slideD: 0.7, slideStagger: 0.14, slideAlt: false, pulse: 0.06, cycle: 1, flash: false, unsungDim: 0.25 }
  },
  japanese: {
    name: '和风摇曳',
    theme: 'japanese',
    params: { waveAmp: 0.12, waveK: 1.8, rotAmp: 7, bounceH: 0.10, slideD: 0.5, slideStagger: 0.04, slideAlt: true, pulse: 0.14, cycle: 2, flash: false, unsungDim: 0.32 }
  },
  hype: {
    name: '热血全开',
    theme: 'dynamic',
    params: { waveAmp: 0.30, waveK: 5.5, rotAmp: 16, bounceH: 0.38, slideD: 0.8, slideStagger: 0.06, slideAlt: true, pulse: 0.36, cycle: 3, flash: true, unsungDim: 0.46 }
  },
  sparkle: {
    name: '星空闪烁',
    theme: 'lyric',
    params: { waveAmp: 0.14, waveK: 3.2, rotAmp: 6, bounceH: 0.14, slideD: 0.3, slideStagger: 0.04, slideAlt: true, pulse: 0.22, cycle: 2, flash: true, unsungDim: 0.40 }
  },
  clean: {
    name: '极简纯净',
    theme: 'typepv',
    params: { waveAmp: 0.02, waveK: 1.0, rotAmp: 0, bounceH: 0.04, slideD: 0.15, slideStagger: 0.02, slideAlt: false, pulse: 0.05, cycle: 1, flash: false, unsungDim: 0.30 }
  },
  focus: {
    name: '对焦呼吸',
    theme: 'lyric',
    params: {
      waveAmp: 0, waveK: 1.0, rotAmp: 0, bounceH: 0, slideD: 0,
      slideStagger: 0, slideAlt: false, pulse: 0.02, cycle: 1, flash: false,
      unsungDim: 0.55, choreoMode: 'fixed', layoutMode: 'h', graphicMode: 'none',
      focusMode: 'soft', singleMove: 0
    }
  },
  annotate: {
    name: '手绘圈注',
    theme: 'typepv',
    params: {
      waveAmp: 0.02, waveK: 1.0, rotAmp: 0, bounceH: 0.02, slideD: 0,
      slideStagger: 0, slideAlt: false, pulse: 0.04, cycle: 1, flash: false,
      unsungDim: 0.42, choreoMode: 'fixed', layoutMode: 'h', graphicMode: 'none',
      annotationMode: 'circle', singleMove: 1
    }
  },
  embers: {
    name: '金色星火',
    theme: 'lyric',
    palette: { glow: '#ffc45e' },
    params: {
      waveAmp: 0.06, waveK: 1.8, rotAmp: 2, bounceH: 0.10, slideD: 0,
      slideStagger: 0, slideAlt: false, pulse: 0.10, cycle: 1, flash: true,
      unsungDim: 0.38, choreoMode: 'fixed', layoutMode: 'h', graphicMode: 'glow',
      particleMode: 'ember', singleMove: 2
    }
  },
  starlight: {
    name: '星芒爆闪',
    theme: 'typepv',
    params: {
      waveAmp: 0.05, waveK: 1.5, rotAmp: 0, bounceH: 0.12, slideD: 0,
      slideStagger: 0, slideAlt: false, pulse: 0.12, cycle: 1, flash: true,
      unsungDim: 0.30, choreoMode: 'fixed', layoutMode: 'h', graphicMode: 'none',
      particleMode: 'sparkle', singleMove: 3
    }
  },
  inkline: {
    name: '线条留白',
    theme: 'lyric',
    params: {
      waveAmp: 0.02, waveK: 1.0, rotAmp: 0, bounceH: 0.03, slideD: 0,
      slideStagger: 0, slideAlt: false, pulse: 0.03, cycle: 1, flash: false,
      unsungDim: 0.52, choreoMode: 'fixed', layoutMode: 'ink', graphicMode: 'none',
      focusMode: 'soft', lineartMode: 'ink', singleMove: 0
    }
  },
  poster: {
    name: '画报侧栏',
    theme: 'typepv',
    params: {
      waveAmp: 0.02, waveK: 1.2, rotAmp: 0, bounceH: 0.05, slideD: 0,
      slideStagger: 0, slideAlt: false, pulse: 0.04, cycle: 1, flash: false,
      unsungDim: 0.44, choreoMode: 'fixed', layoutMode: 'poster', graphicMode: 'panel',
      singleMove: 1
    }
  }
};

/**
 * 通用显示的编排顺序(运行时按当前行内容派发手法,不绑定具体歌词):
 *   cycle      轮换 —— (块内词序) mod 轮换周期
 *   fixed      固定顺序 —— 行字符数 mod 轮换周期(每首歌一致)
 *   songRandom 每歌随机 —— (字符数·A + 词数·B) mod 轮换周期(A/B 由 seed 定,歌与歌分配不同)
 */
const CHOREO_MODES = { cycle: 1, fixed: 1, songRandom: 1 };

/**
 * 排版构图模式:
 *   auto     按行内容轮换(字符数 + 词数*B mod 4):横排/竖排/错落/散点
 *   h        横排(词原始位置)
 *   v        竖排(整行收成一列,居中)
 *   stagger  错落(大小间隔 + 基线上下错位)
 *   scatter  散点(确定性漂移 + 大小抖动)
 *   ink      线条留白:短句(≤7 词)靠右竖排,长句左侧横排(视频「简易线条动画」)
 *   poster   画报侧栏:左侧浮动横排,句子内按内容放大个别词(视频「地尽头」)
 */
const LAYOUT_MODES = { auto: 1, h: 1, v: 1, stagger: 1, scatter: 1, ink: 1, poster: 1 };

/**
 * 图形装饰模式(每行画在文字之前的 accent 图形):
 *   auto    按行内容轮换(字符数 + 词数*C mod 4):无 / 下划线 / 框线 / 印章圈
 *   none    不画
 *   line    下划线
 *   frame   框线
 *   stamp   行尾印章圈
 *   glow    行底辉光条(auto 不会选中,仅显式选择,配合粒子预设)
 *   panel   画报侧栏:右侧竖线/短粗线/三点 + 歌词下点状虚线(视频「地尽头」)
 */
const GRAPHIC_MODES = { auto: 1, none: 1, line: 1, frame: 1, stamp: 1, glow: 1, panel: 1 };

/**
 * 手动排版参数归一化:锚点(0..1 画布比例)、整体缩放/旋转、逐词覆盖表。
 * 覆盖表按「行号+词号」查表,由 wordOverride 指令读取,不占程序指令预算。
 */
function normalizeManual(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const finite = (value, fallback) => (Number.isFinite(Number(value)) ? Number(value) : fallback);
  const clamp = (value, low, high) => Math.min(high, Math.max(low, value));
  const overrides = [];
  if (Array.isArray(raw.overrides)) {
    for (const item of raw.overrides.slice(0, 256)) {
      if (!item || !Number.isInteger(item.line) || item.line < 0) continue;
      if (!Number.isInteger(item.word) || item.word < 0) continue;
      overrides.push({
        line: item.line,
        word: item.word,
        lineText: typeof item.lineText === 'string'
          ? item.lineText.split(String.fromCharCode(0)).join('').slice(0, 256)
          : '',
        dx: clamp(finite(item.dx, 0), -20, 20),
        dy: clamp(finite(item.dy, 0), -20, 20),
        scale: clamp(finite(item.scale, 1), 0.05, 8),
        rotate: clamp(finite(item.rotate, 0), -720, 720),
        alpha: clamp(finite(item.alpha, 1), 0, 1)
      });
    }
  }
  const sprites = [];
  const animTypes = ['none', 'float', 'pulse', 'spin', 'fade', 'pop'];
  if (Array.isArray(raw.sprites)) {
    for (const item of raw.sprites.slice(0, 6)) {
      if (!item || !Number.isInteger(item.id) || item.id < 0 || item.id > 255) continue;
      sprites.push({
        id: item.id,
        x: clamp(finite(item.x, 0.35), 0, 1),
        y: clamp(finite(item.y, 0.35), 0, 1),
        w: clamp(finite(item.w, 0.12), 0.01, 1),
        h: clamp(finite(item.h, 0.12), 0.01, 1),
        rotate: clamp(finite(item.rotate, 0), -720, 720),
        alpha: clamp(finite(item.alpha, 1), 0, 1),
        anim: animTypes.indexOf(item.anim) >= 0 ? item.anim : 'none',
        amp: clamp(finite(item.amp, 0.08), 0, 0.5),
        speed: clamp(finite(item.speed, 1), 0.05, 360),
        phase: clamp(finite(item.phase, 0), -100, 100)
      });
    }
  }
  return {
    anchorX: clamp(finite(raw.anchorX, 0.5), 0, 1),
    anchorY: clamp(finite(raw.anchorY, 0.5), 0, 1),
    scale: clamp(finite(raw.scale, 1), 0.2, 3),
    rotate: clamp(finite(raw.rotate, 0), -180, 180),
    overrides,
    sprites
  };
}

const round4 = (value) => Math.round(value * 10000) / 10000;
const pickRange = (rng, range) => range[0] + (range[1] - range[0]) * rng();

/**
 * seed + 主题 + 手动覆盖 → 编排参数。相同 seed/主题/覆盖 ⇒ 逐字节相同输出。
 * overrides: { waveAmp, waveK, rotAmp, bounceH, slideD, pulse, cycle, flash, unsungDim }(数值直接采用)
 */
function styleFromSeed(seed, themeKey, overrides) {
  const theme = THEMES[themeKey] || THEMES.lyric;
  const rng = makeRng(seed);
  const over = overrides || {};
  const jitter = (key, fallback) => {
    if (Number.isFinite(over[key])) return over[key];
    return round4(pickRange(rng, theme[key]));
  };
  const cycleList = theme.cycles;
  const cycle = Number.isInteger(over.cycle) && over.cycle >= 1 && over.cycle <= 3
    ? over.cycle
    : cycleList[Math.floor(rng() * cycleList.length)];
  const style = {
    seed,
    theme: themeKey,
    themeName: theme.name,
    waveAmp: jitter('waveAmp'),
    waveK: round4(jitter('waveK') * (rng() < 0.5 ? 1 : -1)),
    rotAmp: jitter('rotAmp'),
    bounceH: jitter('bounceH'),
    slideD: jitter('slideD'),
    slideStagger: Number.isFinite(over.slideStagger)
      ? over.slideStagger
      : round4(pickRange(rng, theme.slideStagger || [0.02, 0.08])),
    slideAlt: over.slideAlt === undefined ? rng() < 0.5 : !!over.slideAlt,
    choreoMode: over.choreoMode in CHOREO_MODES ? over.choreoMode : 'cycle',
    layoutMode: over.layoutMode in LAYOUT_MODES ? over.layoutMode : 'auto',
    graphicMode: over.graphicMode in GRAPHIC_MODES ? over.graphicMode : 'auto',
    // 新增效果层(均为逐词/逐行程序内的确定性绘制,不新增 VM 指令):
    //   focusMode      soft = 词周围叠两枚慢速重影,远处更虚(失焦/对焦呼吸)
    //   annotationMode circle = 当前演唱词手绘圈注(随词进度描出并淡出)
    //   particleMode   ember = 金色余烬圆点;sparkle = 内置星芒贴图爆闪
    //   singleMove     只编译指定手法(0..3),给新增效果腾指令预算
    focusMode: over.focusMode === 'soft' ? 'soft' : 'none',
    annotationMode: over.annotationMode === 'circle' ? 'circle' : 'none',
    particleMode: over.particleMode === 'ember' || over.particleMode === 'sparkle'
      ? over.particleMode
      : 'none',
    // ink:当前词/整行周围的手绘弧线与短横线(视频「简易线条动画」的线条笔触)
    lineartMode: over.lineartMode === 'ink' ? 'ink' : 'none',
    singleMove: Number.isInteger(over.singleMove)
      ? ((over.singleMove % 4) + 4) % 4
      : null,
    // 手动排版:非空时忽略生成器的构图/手法/装饰,按锚点+逐词覆盖绘制
    manual: normalizeManual(over.manual),
    pulse: jitter('pulse'),
    cycle,
    flash: over.flash === undefined ? rng() < 0.5 : !!over.flash,
    unsungDim: Number.isFinite(over.unsungDim)
      ? over.unsungDim
      : round4(pickRange(rng, theme.unsungDim))
  };
  // 新增效果层默认走单一手法:失焦重影/圈注/粒子都要额外指令,四手法派发会撞 512 上限;
  // 显式 singleMove 优先,未指定时按 seed 确定性选一条手法(改滑杆离预设后依然可编译)。
  if (style.singleMove === null &&
      (style.focusMode !== 'none' || style.annotationMode !== 'none' ||
        style.particleMode !== 'none' || style.lineartMode !== 'none' ||
        style.layoutMode === 'ink' || style.layoutMode === 'poster')) {
    style.singleMove = seed % 4;
  }
  // 旋向随机翻转:仅对未显式覆盖的 waveAmp 生效(覆盖值按用户给的原样用)
  if (!Number.isFinite(over.waveAmp) && rng() < 0.5) style.waveAmp = -style.waveAmp;
  style.choreoA = Math.round((1 + rng() * 3) * 100) / 100;
  // 排版自动轮换的混合系数(与手法系数分开,互不干扰)
  style.layoutSeedB = 1 + Math.round(rng() * 3);
  style.graphicSeedC = 1 + Math.round(rng() * 3);
  style.choreoB = Math.round((1 + rng() * 3) * 100) / 100;
  return style;
}

// ---------------------------------------------------------------------------
// 4. 编排 → 盒模式程序(汇编源码)
// ---------------------------------------------------------------------------

/** 手动贴纸层:画在所有文字之前(背景层),支持漂浮/呼吸/旋转/淡入淡出/入场弹出。 */
function manualSpriteCode(m) {
  const code = [];
  for (const sprite of m.sprites) {
    const cxBase = sprite.x + sprite.w / 2;
    const cyBase = sprite.y + sprite.h / 2;
    const anim = sprite.anim;
    const phase = sprite.phase;
    // 中心(局部 16/17):旋转/缩放的回平移锚点
    code.push(
      'canvasField width', 'push ' + round4(cxBase), 'mul', 'storeLocal 16',
      'canvasField height', 'push ' + round4(cyBase), 'mul', 'storeLocal 17'
    );
    // 旋转(局部 18)
    if (anim === 'spin') {
      code.push(
        'globalField time', 'push 0.001', 'mul', 'push ' + round4(sprite.speed), 'mul',
        'push ' + round4(sprite.rotate), 'add', 'storeLocal 18'
      );
    } else {
      code.push('push ' + round4(sprite.rotate), 'storeLocal 18');
    }
    // 缩放(局部 19):呼吸/入场弹出
    if (anim === 'pulse') {
      code.push(
        'globalField time', 'push 0.001', 'mul', 'push ' + round4(sprite.speed), 'mul',
        'push ' + round4(phase), 'add', 'sin',
        'push ' + round4(sprite.amp), 'mul', 'push 1', 'add', 'storeLocal 19'
      );
    } else if (anim === 'pop') {
      code.push(
        'globalField time', 'push 0.001', 'mul', 'push 2', 'mul',
        'push 1', 'min', 'storeLocal 19'
      );
    }
    // 透明度
    if (anim === 'fade') {
      code.push(
        'globalField time', 'push 0.001', 'mul', 'push ' + round4(sprite.speed), 'mul',
        'push ' + round4(phase), 'add', 'sin',
        'push 0.5', 'mul', 'push 0.5', 'add',
        'push 0.6', 'mul', 'push 0.4', 'add',
        'push ' + round4(sprite.alpha), 'mul', 'setAlpha'
      );
    } else if (anim === 'pop') {
      code.push('loadLocal 19', 'push ' + round4(sprite.alpha), 'mul', 'setAlpha');
    } else {
      code.push('push ' + round4(sprite.alpha), 'setAlpha');
    }
    code.push(
      'save',
      'loadLocal 16', 'loadLocal 17', 'translate',
      'loadLocal 18', 'rotate'
    );
    if (anim === 'pulse' || anim === 'pop') {
      code.push('loadLocal 19', 'loadLocal 19', 'scale');
    }
    code.push(
      'loadLocal 16', 'push -1', 'mul',
      'loadLocal 17', 'push -1', 'mul',
      'translate'
    );
    if (anim === 'float') {
      code.push(
        'globalField time', 'push 0.001', 'mul', 'push ' + round4(sprite.speed), 'mul',
        'push ' + round4(phase), 'add', 'sin',
        'push ' + round4(sprite.amp), 'mul', 'canvasField width', 'mul',
        'globalField time', 'push 0.001', 'mul', 'push ' + round4(sprite.speed), 'mul',
        'push ' + round4(phase * 0.8), 'add', 'cos',
        'push ' + round4(sprite.amp * 0.6), 'mul', 'canvasField height', 'mul',
        'translate'
      );
    }
    code.push(
      'push ' + sprite.id,
      'canvasField width', 'push ' + round4(sprite.x), 'mul',
      'canvasField height', 'push ' + round4(sprite.y), 'mul',
      'canvasField width', 'push ' + round4(sprite.w), 'mul',
      'canvasField height', 'push ' + round4(sprite.h), 'mul',
      'drawSprite',
      'restore'
    );
  }
  return code;
}

/**
 * 手动排版程序:锚点平移 + 逐词覆盖(wordOverride) + 绕词中心旋转绘制。
 * 不含生成器的构图/手法/装饰;所有自由度来自 program.wordOverrides 数据表。
 */
function buildManualSource(style, num) {
  const m = style.manual;
  const ax = m.anchorX - 0.5;
  const ay = m.anchorY - 0.5;
  return [
    '; HyperGlow Manual · anchor=' + num(m.anchorX) + ',' + num(m.anchorY) +
      ' · scale=' + num(m.scale) + ' · rotate=' + num(m.rotate),
    'colorField sung',
    'setColor',
    ...manualSpriteCode(m),
    'wordCount',
    'storeLocal 1',
    'push 0',
    'storeLocal 0',
    'loop:',
    '  loadLocal 0',
    '  loadLocal 1',
    '  lt',
    '  jz done',
    '  loadLocal 0',
    '  word',
    '  storeLocal 6',
    // 词所属视觉行(3)与行对象(2):逐词覆盖按整行文本匹配
    '  loadLocal 6',
    '  wordField line',
    '  storeLocal 3',
    '  loadLocal 3',
    '  line',
    '  storeLocal 2',
    // 默认 alpha(词进度)与旋转 0
    '  loadLocal 6',
    '  wordField progress',
    '  push 0.65',
    '  mul',
    '  push 0.35',
    '  add',
    '  storeLocal 14',
    '  push 0',
    '  storeLocal 19',
    // 锚点平移后的位置
    '  loadLocal 6',
    '  wordField x',
    '  canvasField width',
    '  push ' + num(ax),
    '  mul',
    '  add',
    '  storeLocal 10',
    '  loadLocal 6',
    '  wordField baseline',
    '  canvasField height',
    '  push ' + num(ay),
    '  mul',
    '  add',
    '  storeLocal 11',
    '  push ' + num(m.scale),
    '  storeLocal 12',
    // 逐词覆盖:压入 整行文本 + 视觉行号 + 词号,再取 dx,dy,scale,rotate,alpha
    '  loadLocal 2',
    '  lineAtField fullText',
    '  loadLocal 3',
    '  loadLocal 0',
    '  wordOverride',
    '  loadLocal 14',
    '  mul',
    '  storeLocal 14',
    '  storeLocal 19',
    '  loadLocal 12',
    '  mul',
    '  storeLocal 12',
    '  globalField textSize',
    '  mul',
    '  loadLocal 11',
    '  add',
    '  storeLocal 11',
    '  globalField textSize',
    '  mul',
    '  loadLocal 10',
    '  add',
    '  storeLocal 10',
    // 整体旋转
    '  loadLocal 19',
    '  push ' + num(m.rotate),
    '  add',
    '  storeLocal 19',
    // 字号 / 枢轴 / 透明度
    '  loadLocal 12',
    '  globalField textSize',
    '  mul',
    '  setTextSize',
    '  loadLocal 10',
    '  loadLocal 6',
    '  wordField text',
    '  measureText',
    '  push 0.5',
    '  mul',
    '  add',
    '  storeLocal 8',
    '  loadLocal 11',
    '  globalField textSize',
    '  loadLocal 12',
    '  mul',
    '  push 0.35',
    '  mul',
    '  sub',
    '  storeLocal 9',
    '  loadLocal 14',
    '  setAlpha',
    '  save',
    '  loadLocal 8',
    '  loadLocal 9',
    '  translate',
    '  loadLocal 19',
    '  rotate',
    '  loadLocal 8',
    '  push -1',
    '  mul',
    '  loadLocal 9',
    '  push -1',
    '  mul',
    '  translate',
    '  loadLocal 6',
    '  wordField text',
    '  loadLocal 10',
    '  loadLocal 11',
    '  drawText',
    '  restore',
    'nextw:',
    '  loadLocal 0',
    '  push 1',
    '  add',
    '  storeLocal 0',
    '  jump loop',
    'done:',
    '  end'
  ];
}

/**
 * 生成汇编源码。骨架 = 公共 prologue + 「(行号+词序) mod cycle」分派 + 四个参数化手法分支
 * (单行块内也能四手法轮换;多行折行时行号参与轮换):
 *   0 = wave(绕词中心的旋转 + 正弦纵向浮动)
 *   1 = bounce(词进度驱动的升起/回落)
 *   2 = slide(词进度前半程的左右滑入,方向按词序交替)
 *   3 = pulse(绕词中心的强调脉冲缩放)
 * 闪光(!)= alpha 与词 alpha 取 max:行开始瞬间全亮。
 *
 * 全部旋转变换经 translate(pivot)→rotate→translate(-pivot) 实现 —— 盒渲染器
 * concat 的是完整矩阵,程序侧用平移组合出绕词中心的旋转,与直绘 DSL 的词中心
 * 枢轴语义一致。
 */
function buildProgramSource(style) {
  const num = (value) => {
    const rounded = round4(Number(value) || 0);
    return String(rounded);
  };
  if (style.manual) return buildManualSource(style, num).join(String.fromCharCode(10));
  const flashT = style.flash ? 0.08 : 999999;
  // 只编译单一手法:新增效果层(失焦/圈注/粒子)靠它把 512 条指令预算让出来。
  const singleMove = Number.isInteger(style.singleMove) ? style.singleMove : null;

  // 公共 prologue:颜色 / 闪光分量(枢轴在排版层之后计算,依赖 xBase/yBase/scale)
  const prologue = [
    'colorField sung',
    'setColor',
    // flashAlpha = min(max(lp / flashT, 0), 1);flash 关闭时 flashT 巨大 → 恒 0
    'loadLocal 5',
    'push ' + num(flashT),
    'div',
    'push 1',
    'min',
    'push 0',
    'max',
    'storeLocal 4'
  ];

  const wordAlphaCore = [
    'loadLocal 6',
    'wordField progress',
    'push 0.65',
    'mul',
    'push 0.35',
    'add',
    'loadLocal 4',
    'max'
  ];
  // 失焦模式:远处词按 focus 距离再压暗(配合 shadowLayer 形成软边而不是双影)
  const focusDim = style.focusMode === 'soft' ? 0.5 : 0;
  const wordAlpha = focusDim > 0
    ? [
      ...wordAlphaCore,
      'loadLocal 14',
      'push ' + num(focusDim),
      'mul',
      'push 1',
      'swap',
      'sub',
      'mul',
      'setAlpha'
    ]
    : [...wordAlphaCore, 'setAlpha'];

  // ---- 排版构图:逐词算 xBase(10)/yBase(11)/scale(12) ----
  // 横排:词原始位置
  const layoutHorizontal = [
    'loadLocal 6',
    'wordField x',
    'storeLocal 10',
    'loadLocal 6',
    'wordField baseline',
    'storeLocal 11',
    'push 1',
    'storeLocal 12'
  ];

  // 竖排:整行收成一列,列内按可用高度压缩步进,水平居中(measureText 求半宽)
  const layoutVertical = [
    'canvasField width',
    'push 0.5',
    'mul',
    'loadLocal 6',
    'wordField text',
    'measureText',
    'push 0.5',
    'mul',
    'sub',
    'storeLocal 10',
    // spacing = min(ts*1.15, canvasH*0.8/n) → local 13 复用为 spacing(mode 此后不再需要)
    'globalField textSize',
    'push 1.15',
    'mul',
    'canvasField height',
    'push 0.8',
    'mul',
    'loadLocal 1',
    'div',
    'min',
    'storeLocal 13',
    // yBase = (canvasH - n*spacing)/2 + 0.8*ts + i*spacing
    'canvasField height',
    'loadLocal 1',
    'loadLocal 13',
    'mul',
    'sub',
    'push 2',
    'div',
    'globalField textSize',
    'push 0.8',
    'mul',
    'add',
    'loadLocal 0',
    'loadLocal 13',
    'mul',
    'add',
    'storeLocal 11',
    'push 1',
    'storeLocal 12'
  ];

  // 错落:大小间隔 + 基线上下错位(cos(iπ) = ±1,免分支)
  const layoutStagger = [
    'loadLocal 6',
    'wordField x',
    'storeLocal 10',
    'loadLocal 6',
    'wordField baseline',
    'loadLocal 0',
    'push 3.14159',
    'mul',
    'cos',
    'push 0.22',
    'mul',
    'globalField textSize',
    'mul',
    'add',
    'storeLocal 11',
    'loadLocal 0',
    'push 3.14159',
    'mul',
    'cos',
    'push 0.24',
    'mul',
    'push 1.06',
    'add',
    'storeLocal 12'
  ];

  // 散点:词位置确定性漂移 + 轻微大小抖动
  const layoutScatter = [
    'loadLocal 6',
    'wordField x',
    'loadLocal 0',
    'push 1.9',
    'mul',
    'sin',
    'push 0.28',
    'mul',
    'canvasField width',
    'mul',
    'add',
    'storeLocal 10',
    'loadLocal 6',
    'wordField baseline',
    'loadLocal 0',
    'push 2.3',
    'mul',
    'cos',
    'push 0.9',
    'mul',
    'globalField textSize',
    'mul',
    'add',
    'storeLocal 11',
    'loadLocal 0',
    'push 1.3',
    'mul',
    'sin',
    'push 0.12',
    'mul',
    'push 1',
    'add',
    'storeLocal 12'
  ];

  const layoutModes = [layoutHorizontal, layoutVertical, layoutStagger, layoutScatter];

  // ---- 画报/留白构图:画布锚点 + 逐词游标(按实际字宽推进,允许句内放大) ----
  // locals:16=游标 X,17=上一视觉行号(跨词持久;循环前初始化为 -1)。
  const packedHorizontal = (maxWidthFraction, scaleLines) => [
    'loadLocal 6',
    'wordField line',
    'loadLocal 17',
    'ne',
    'jz LP1',
    // 新视觉行:游标回到左侧锚点(按行字数做轻微水平错位)
    'canvasField width',
    'push 0.06',
    'mul',
    'loadLocal 2',
    'lineAtField text',
    'stringLength',
    'push 3',
    'mod',
    'canvasField width',
    'push 0.015',
    'mul',
    'mul',
    'add',
    'storeLocal 16',
    'loadLocal 6',
    'wordField line',
    'storeLocal 17',
    // 整行缩放系数:长句自动缩到可用宽度(画报左侧留出侧栏)
    'canvasField width',
    'push ' + num(maxWidthFraction),
    'mul',
    'loadLocal 2',
    'lineAtField text',
    'measureText',
    'push 1',
    'max',
    'div',
    'push 1',
    'min',
    'storeLocal 18',
    'LP1:',
    'loadLocal 16',
    'storeLocal 10',
    ...scaleLines,
    // 游标推进 = 字宽*scale + 0.06em
    'loadLocal 6',
    'wordField text',
    'measureText',
    'loadLocal 12',
    'mul',
    'globalField textSize',
    'push 0.06',
    'mul',
    'add',
    'loadLocal 16',
    'add',
    'storeLocal 16',
    // 基线 = 0.46H + (行字数 mod 3 - 1)*0.05H(每行浮在不同高度)
    'canvasField height',
    'push 0.46',
    'mul',
    'loadLocal 2',
    'lineAtField text',
    'stringLength',
    'push 3',
    'mod',
    'push 1',
    'sub',
    'canvasField height',
    'push 0.05',
    'mul',
    'mul',
    'add',
    'storeLocal 11'
  ];

  const posterScale = [
    'loadLocal 18',
    'loadLocal 0',
    'loadLocal 2',
    'lineAtField text',
    'stringLength',
    'add',
    'push 6',
    'mod',
    'push 1',
    'eq',
    'push 0.55',
    'mul',
    'push 1',
    'add',
    'mul',
    'storeLocal 12'
  ];
  const inkScale = ['loadLocal 18', 'storeLocal 12'];

  // 留白·短句:靠右竖排一列(顶部起排,行内按可用高度压缩步进)
  const layoutInkColumn = [
    'canvasField width',
    'push 0.82',
    'mul',
    'loadLocal 6',
    'wordField text',
    'measureText',
    'push 0.5',
    'mul',
    'sub',
    'storeLocal 10',
    'globalField textSize',
    'push 1.15',
    'mul',
    'canvasField height',
    'push 0.6',
    'mul',
    'loadLocal 1',
    'div',
    'min',
    'storeLocal 13',
    'canvasField height',
    'push 0.16',
    'mul',
    'loadLocal 0',
    'loadLocal 13',
    'mul',
    'add',
    'globalField textSize',
    'push 0.8',
    'mul',
    'add',
    'storeLocal 11',
    'push 1',
    'storeLocal 12'
  ];

  const layoutInk = [
    'wordCount',
    'push 7',
    'le',
    'jz InkH',
    ...indent(layoutInkColumn),
    'jump InkDone',
    'InkH:',
    ...indent(packedHorizontal(0.66, inkScale)),
    'InkDone:'
  ];

  const layoutPoster = packedHorizontal(0.56, posterScale);
  layoutModes.push(layoutInk, layoutPoster);
  const fixedMode = { h: 0, v: 1, stagger: 2, scatter: 3, ink: 4, poster: 5 }[style.layoutMode];
  const layoutSelector = Number.isInteger(fixedMode)
    ? ['push ' + num(fixedMode), 'storeLocal 13']
    : [
        // auto:(行字符数 + 词数*B) mod 4 —— 每行构图随内容轮换,同一首歌稳定
        'loadLocal 2',
        'lineAtField text',
        'stringLength',
        'wordCount',
        'push ' + num(style.layoutSeedB),
        'mul',
        'add',
        'push 4',
        'mod',
        'storeLocal 13'
      ];

  const layoutDispatch = [
    'loadLocal 13',
    'dup',
    'push 0',
    'eq',
    'jz LH1',
    'pop',
    ...indent(layoutModes[0]),
    'jump LHDone',
    'LH1:',
    'dup',
    'push 1',
    'eq',
    'jz LH2',
    'pop',
    ...indent(layoutModes[1]),
    'jump LHDone',
    'LH2:',
    'dup',
    'push 2',
    'eq',
    'jz LH3',
    'pop',
    ...indent(layoutModes[2]),
    'jump LHDone',
    'LH3:',
    'pop',
    ...indent(layoutModes[3]),
    'LHDone:'
  ];

  // 排版完成后:按 scale 设字号;枢轴改用排好版的基准
  const layoutApply = [
    'loadLocal 12',
    'globalField textSize',
    'mul',
    'setTextSize',
    // pivotX = xBase + width/2
    'loadLocal 10',
    'loadLocal 6',
    'wordField width',
    'push 2',
    'div',
    'add',
    'storeLocal 8',
    // pivotY = yBase - ts*scale*0.35
    'loadLocal 11',
    'globalField textSize',
    'loadLocal 12',
    'mul',
    'push 0.35',
    'mul',
    'sub',
    'storeLocal 9'
  ];

  // ---- 新增效果层:全部复用既有 VM 指令,不扩指令集 ----
  // 1 对焦呼吸(soft focus):每词叠两枚对角慢速重影,离"焦点"越远越虚。
  // 焦点 = 行进度在词序列上滑动;重影 alpha 与偏移都随距离增长。
  const focusEmission = style.focusMode === 'soft'
    ? [
      // d = min(|i - lp*(n-1)| / max(n-1,1), 1)
      'loadLocal 0',
      'loadLocal 5',
      'loadLocal 1',
      'push 1',
      'sub',
      'mul',
      'sub',
      'abs',
      'loadLocal 1',
      'push 1',
      'sub',
      'push 1',
      'max',
      'div',
      'push 1',
      'min',
      'storeLocal 14',
      // 先清上一次的柔边,重影不带阴影
      'clearShadow',
      // o = ts*0.03*(0.6+0.8d)
      'globalField textSize',
      'push 0.03',
      'mul',
      'loadLocal 14',
      'push 0.8',
      'mul',
      'push 0.6',
      'add',
      'mul',
      'storeLocal 15',
      // 两向淡重影(补足失焦方向感)
      'loadLocal 14',
      'push 0.22',
      'mul',
      'push 0.04',
      'add',
      'setAlpha',
      'loadLocal 6', 'wordField text',
      'loadLocal 10', 'loadLocal 15', 'add',
      'loadLocal 11',
      'drawText',
      'loadLocal 6', 'wordField text',
      'loadLocal 10', 'loadLocal 15', 'sub',
      'loadLocal 11',
      'drawText',
      // 正文由手法分支绘制:给它挂随距离加宽的 shadowLayer,形成硬件可用的软边
      'globalField textSize',
      'push 0.40',
      'mul',
      'loadLocal 14',
      'mul',
      'push 0',
      'push 0',
      'colorField sung',
      'setShadow'
    ]
    : [];

  // 2 手绘圈注(circle):当前演唱词外圈一道带缺口的弧,随词进度描出并淡出。
  const ringEmission = style.annotationMode === 'circle'
    ? [
      'colorField sung',
      'setColor',
      'globalField textSize',
      'push 0.045',
      'mul',
      'setStrokeWidth',
      'setStroke',
      'loadLocal 6', 'wordField progress', 'storeLocal 14',
      // a = min(p*8,1) * (1 - clamp((p-0.75)/0.25,0,1)) * 0.9
      'loadLocal 14', 'push 8', 'mul', 'push 1', 'min', 'storeLocal 15',
      'loadLocal 14', 'push 0.75', 'sub', 'push 0.25', 'div',
      'push 0', 'max', 'push 1', 'min',
      'push 1', 'swap', 'sub',
      'loadLocal 15', 'mul', 'push 0.9', 'mul',
      'setAlpha',
      'loadLocal 10', 'globalField textSize', 'push 0.10', 'mul', 'sub',
      'loadLocal 11', 'globalField textSize', 'push 1.05', 'mul', 'sub',
      'loadLocal 6', 'wordField text', 'measureText', 'globalField textSize', 'push 0.20', 'mul', 'add',
      'globalField textSize', 'push 1.28', 'mul',
      // start = -30 + sin(i*2.3)*8
      'loadLocal 0', 'push 2.3', 'mul', 'sin', 'push 8', 'mul', 'push -30', 'add',
      // sweep = 120 + 220*min(p*2.5,1) + cos(i*1.7)*12
      'loadLocal 14', 'push 2.5', 'mul', 'push 1', 'min', 'push 220', 'mul', 'push 120', 'add',
      'loadLocal 0', 'push 1.7', 'mul', 'cos', 'push 12', 'mul', 'add',
      'drawArc',
      'setFill'
    ]
    : [];

  // 3 线条笔触(ink):每词一道随进度描出的弧 + 每 3 词一条短横线(视频「简易线条动画」)
  const lineartEmission = style.lineartMode === 'ink'
    ? [
      'colorField sung',
      'setColor',
      'globalField textSize',
      'push 0.03',
      'mul',
      'setStrokeWidth',
      'setStroke',
      // a = min(p*6,1) * (1 - clamp((p-0.85)/0.15,0,1)) * 0.85
      'loadLocal 6', 'wordField progress', 'push 6', 'mul', 'push 1', 'min',
      'loadLocal 6', 'wordField progress', 'push 0.85', 'sub', 'push 0.15', 'div',
      'push 0', 'max', 'push 1', 'min',
      'push 1', 'swap', 'sub',
      'mul', 'push 0.85', 'mul',
      'setAlpha',
      // 弧:以实际排版锚点(local10/11)+ 实测字宽定位,竖排/画报模式也贴合
      'loadLocal 10', 'globalField textSize', 'push 0.08', 'mul', 'sub',
      'loadLocal 11', 'globalField textSize', 'push 1.05', 'mul', 'sub',
      'loadLocal 6', 'wordField text', 'measureText', 'globalField textSize', 'push 0.16', 'mul', 'add',
      'globalField textSize', 'push 1.22', 'mul',
      // start = 120 + (i mod 4)*55 + sin(i*1.9+0.7)*35(弧口绕词四向散开)
      'loadLocal 0', 'push 4', 'mod', 'push 55', 'mul', 'push 120', 'add',
      'loadLocal 0', 'push 1.9', 'mul', 'push 0.7', 'add', 'sin', 'push 35', 'mul', 'add',
      // sweep = 55 + (i mod 3)*40 + cos(i*2.1)*18
      'loadLocal 0', 'push 3', 'mod', 'push 40', 'mul', 'push 55', 'add',
      'loadLocal 0', 'push 2.1', 'mul', 'cos', 'push 18', 'mul', 'add',
      'drawArc',
      'setFill',
      // 每 3 词一条短横线:x2 = local10 + 0.32em*(i mod 3 == 0)
      'loadLocal 10',
      'loadLocal 11', 'globalField textSize', 'push 0.34', 'mul', 'add',
      'loadLocal 10',
      'globalField textSize', 'push 0.32', 'mul',
      'loadLocal 0', 'push 3', 'mod', 'push 0', 'eq', 'mul',
      'add',
      'loadLocal 11', 'globalField textSize', 'push 0.34', 'mul', 'add',
      'drawLine'
    ]
    : [];

  // 画报点线:紧跟实际排版锚点(仅首个词发一次),避免与画布居中/左浮动布局脱节
  const panelRuleEmission = style.graphicMode === 'panel'
    ? [
      'loadLocal 0', 'push 0', 'eq', 'jz PRD',
      'colorField sung', 'setColor', 'setFill',
      'loadLocal 2', 'lineAtField text', 'measureText', 'storeLocal 15',
      'push 0', 'storeLocal 14',
      'PR1:',
      'loadLocal 14', 'push 7', 'lt', 'jz PRD',
      'loadLocal 10',
      'loadLocal 15', 'push 0.55', 'mul', 'loadLocal 14', 'mul', 'push 7', 'div', 'add',
      'loadLocal 11', 'globalField textSize', 'push 0.55', 'mul', 'add',
      'globalField textSize', 'push 0.008', 'mul',
      'drawCircle',
      'loadLocal 14', 'push 1', 'add', 'storeLocal 14',
      'jump PR1',
      'PRD:'
    ]
    : [];

  // 3 金色星火(ember):每个词唱到时抛两粒余烬圆点,确定性地漂移/淡出。
  const emberEmission = style.particleMode === 'ember'
    ? [
      'colorField glow',
      'setColor',
      'setFill',
      'loadLocal 6', 'wordField progress', 'storeLocal 14',
      // A
      'push 1', 'loadLocal 14', 'sub', 'push 0', 'max', 'push 0.8', 'mul', 'push 0.05', 'add',
      'setAlpha',
      'loadLocal 10', 'loadLocal 6', 'wordField text', 'measureText', 'push 0.5', 'mul', 'add',
      'loadLocal 0', 'push 2.3', 'mul', 'loadLocal 5', 'push 7', 'mul', 'add', 'sin',
      'globalField textSize', 'push 0.45', 'mul', 'mul', 'add',
      'loadLocal 11',
      'loadLocal 0', 'push 0.37', 'mul', 'loadLocal 5', 'push 1.3', 'mul', 'add',
      'push 1', 'mod', 'push 1.1', 'mul', 'push 0.15', 'add',
      'globalField textSize', 'mul', 'sub',
      'globalField textSize', 'push 0.035', 'mul',
      'drawCircle',
      // B
      'push 1', 'loadLocal 14', 'sub', 'push 0', 'max', 'push 0.5', 'mul', 'push 0.04', 'add',
      'setAlpha',
      'loadLocal 10', 'loadLocal 6', 'wordField text', 'measureText', 'push 0.5', 'mul', 'add',
      'loadLocal 0', 'push 3.1', 'mul', 'loadLocal 5', 'push 5', 'mul', 'add', 'cos',
      'globalField textSize', 'push 0.55', 'mul', 'mul', 'add',
      'loadLocal 11',
      'loadLocal 0', 'push 0.61', 'mul', 'loadLocal 5', 'push 0.9', 'mul', 'add',
      'push 1', 'mod', 'push 1.6', 'mul', 'push 0.2', 'add',
      'globalField textSize', 'mul', 'sub',
      'globalField textSize', 'push 0.022', 'mul',
      'drawCircle'
    ]
    : [];

  // 4 星芒爆闪(sparkle):行级 12 颗内置四角星(sparkle 贴图),开场爆闪后持续微闪。
  const sparkleCount = 12;
  const sparkleEmission = style.particleMode === 'sparkle'
    ? [
      'colorField sung',
      'setColor',
      'setFill',
      'push 0',
      'storeLocal 14',
      'SPL1:',
      'loadLocal 14',
      'push ' + sparkleCount,
      'lt',
      'jz SPLDone',
      // t = 0.5 + 0.5*sin(lp*16 + i*2.4)
      'loadLocal 2', 'lineAtField progress', 'push 16', 'mul',
      'loadLocal 14', 'push 2.4', 'mul', 'add', 'sin', 'push 0.5', 'mul', 'push 0.5', 'add',
      'storeLocal 15',
      // alpha = (0.25 + 0.75*(1-min(lp*1.6,1))) * (0.35+0.65t) * 0.95
      'push 1', 'loadLocal 2', 'lineAtField progress', 'push 1.6', 'mul', 'min',
      'neg', 'push 1', 'add',
      'push 0.75', 'mul', 'push 0.25', 'add',
      'loadLocal 15', 'push 0.65', 'mul', 'push 0.35', 'add',
      'mul', 'push 0.95', 'mul',
      'setAlpha',
      'push 1',
      // x = lx + lw * frac(i*0.618)
      'loadLocal 2', 'lineAtField x',
      'loadLocal 2', 'lineAtField width',
      'loadLocal 14', 'push 0.618', 'mul', 'push 1', 'mod', 'mul', 'add',
      // y = baseline - em*(0.9 + 1.2*frac(i*0.377))
      'loadLocal 2', 'lineAtField baseline',
      'globalField textSize',
      'loadLocal 14', 'push 0.377', 'mul', 'push 1', 'mod',
      'push 1.2', 'mul', 'push 0.9', 'add', 'mul', 'sub',
      // w = h = em*(0.10 + 0.22t)
      'globalField textSize',
      'loadLocal 15', 'push 0.22', 'mul', 'push 0.10', 'add', 'mul',
      'globalField textSize',
      'loadLocal 15', 'push 0.22', 'mul', 'push 0.10', 'add', 'mul',
      'drawSprite',
      'loadLocal 14', 'push 1', 'add', 'storeLocal 14',
      'jump SPL1',
      'SPLDone:'
    ]
    : [];

  // 手法 0:wave —— pivot 旋转 + 纵向正弦
  const wave = [
    ...wordAlpha,
    'save',
    'loadLocal 8',
    'loadLocal 9',
    'translate',
    'loadLocal 5',
    'push ' + num(Math.abs(style.waveK)),
    'mul',
    'sin',
    'push ' + num(style.rotAmp),
    'mul',
    'rotate',
    'loadLocal 8',
    'push -1',
    'mul',
    'loadLocal 9',
    'push -1',
    'mul',
    'translate',
    'loadLocal 6',
    'wordField text',
    'loadLocal 10',
    'loadLocal 11',
    'loadLocal 5',
    'push ' + num(Math.abs(style.waveK)),
    'mul',
    'sin',
    'push ' + num(style.waveAmp),
    'mul',
    'globalField textSize',
    'mul',
    'add',
    'drawText',
    'restore'
  ];

  // 手法 1:bounce —— 词进度前半程升起、后半程回落
  const bounce = [
    ...wordAlpha,
    'loadLocal 6',
    'wordField text',
    'loadLocal 10',
    'loadLocal 11',
    'loadLocal 6',
    'wordField progress',
    'push 2',
    'mul',
    'push 1',
    'min',
    'push 3.14159',
    'mul',
    'sin',
    'push ' + num(style.bounceH),
    'mul',
    'globalField textSize',
    'mul',
    'sub',
    'drawText'
  ];

  // 手法 2:slide —— 级联滑入(词序 × stagger 错峰),前半程进入,方向可配
  const slideDirCode = style.slideAlt
    ? ['loadLocal 0', 'push 2', 'mod', 'push 2', 'mul', 'push 1', 'sub']
    : ['push -1'];
  const slide = [
    'loadLocal 6',
    'wordField progress',
    'loadLocal 0',
    'push ' + num(style.slideStagger),
    'mul',
    'sub',
    'push 2',
    'mul',
    'push 0',
    'max',
    'push 1',
    'min',
    'storeLocal 7',
    'loadLocal 7',
    'push 0.65',
    'mul',
    'push 0.35',
    'add',
    'loadLocal 4',
    'max',
    'setAlpha',
    'loadLocal 6',
    'wordField text',
    'loadLocal 10',
    ...slideDirCode,
    'push ' + num(style.slideD),
    'mul',
    'globalField textSize',
    'mul',
    'loadLocal 7',
    'push 1',
    'sub',
    'mul',
    'add',
    'loadLocal 11',
    'drawText'
  ];

  // 手法 3:pulse —— 绕词中心的强调缩放
  const pulse = [
    ...wordAlpha,
    'save',
    'loadLocal 8',
    'loadLocal 9',
    'translate',
    'loadLocal 6',
    'wordField progress',
    'push 3.14159',
    'mul',
    'sin',
    'push ' + num(style.pulse),
    'mul',
    'push 1',
    'add',
    'dup',
    'scale',
    'loadLocal 8',
    'push -1',
    'mul',
    'loadLocal 9',
    'push -1',
    'mul',
    'translate',
    'loadLocal 6',
    'wordField text',
    'loadLocal 10',
    'loadLocal 11',
    'drawText',
    'restore'
  ];

  // 手法派发:按编排顺序模式生成压栈片段(结果 = 槽位号 0..cycle-1)
  const choreoDispatch = style.choreoMode === 'fixed'
    ? [
      '  loadLocal 2',
      '  lineAtField text',
      '  stringLength',
      '  push ' + num(style.cycle),
      '  mod'
    ]
    : style.choreoMode === 'songRandom'
      ? [
      '  loadLocal 2',
      '  lineAtField text',
      '  stringLength',
      '  push ' + num(style.choreoA),
      '  mul',
      '  wordCount',
      '  push ' + num(style.choreoB),
      '  mul',
      '  add',
      '  push ' + num(style.cycle),
      '  mod'
    ]
      : [
      '  loadLocal 3',
      '  loadLocal 0',
      '  add',
      '  push ' + num(style.cycle),
      '  mod'
    ];

  // ---- 图形装饰层:按行内容派发(画在文字之前,每行一次,不进词循环) ----
  // 公共绘制状态:accent 色、描边、随行进度渐显
  const decoCommon = [
    'colorField accent',
    'setColor',
    'push 2',
    'setStrokeWidth',
    'setStroke',
    'lineField progress',
    'push 4',
    'mul',
    'push 1',
    'min',
    'push 0.9',
    'mul',
    'setAlpha'
  ];

  // 1 下划线:沿行底画一条 accent 线
  const decoLine = [
    'loadLocal 2',
    'lineAtField x',
    'loadLocal 2',
    'lineAtField baseline',
    'globalField textSize',
    'push 0.42',
    'mul',
    'add',
    'loadLocal 2',
    'lineAtField x',
    'loadLocal 2',
    'lineAtField width',
    'add',
    'loadLocal 2',
    'lineAtField baseline',
    'globalField textSize',
    'push 0.42',
    'mul',
    'add',
    'drawLine'
  ];

  // 2 框线:行四周描边框(留 0.2em 边距)
  const decoFrame = [
    'loadLocal 2',
    'lineAtField x',
    'globalField textSize',
    'push 0.2',
    'mul',
    'sub',
    'loadLocal 2',
    'lineAtField baseline',
    'globalField textSize',
    'push 1.15',
    'mul',
    'sub',
    'loadLocal 2',
    'lineAtField width',
    'globalField textSize',
    'push 0.4',
    'mul',
    'add',
    'globalField textSize',
    'push 1.55',
    'mul',
    'drawRect'
  ];

  // 3 印章圈:行尾一颗 accent 圆环
  const decoStamp = [
    'loadLocal 2',
    'lineAtField x',
    'loadLocal 2',
    'lineAtField width',
    'add',
    'globalField textSize',
    'push 0.45',
    'mul',
    'add',
    'loadLocal 2',
    'lineAtField baseline',
    'globalField textSize',
    'push 0.25',
    'mul',
    'sub',
    'globalField textSize',
    'push 0.32',
    'mul',
    'drawCircle'
  ];

  // 4 行底辉光条:暖色光带 + 阴影扩散(auto 不会选中,供粒子预设显式使用)
  const decoGlow = [
    'colorField glow',
    'setColor',
    'globalField textSize',
    'push 0.5',
    'mul',
    'push 0',
    'push 0',
    'colorField glow',
    'setShadow',
    'globalField textSize',
    'push 0.09',
    'mul',
    'setStrokeWidth',
    'push 0.55',
    'setAlpha',
    'loadLocal 2',
    'lineAtField x',
    'loadLocal 2',
    'lineAtField baseline',
    'globalField textSize',
    'push 0.32',
    'mul',
    'add',
    'loadLocal 2',
    'lineAtField x',
    'loadLocal 2',
    'lineAtField width',
    'add',
    'loadLocal 2',
    'lineAtField baseline',
    'globalField textSize',
    'push 0.32',
    'mul',
    'add',
    'drawLine',
    'clearShadow'
  ];

  // 5 画报侧栏:右侧竖线 + 短粗线 + 三颗点 + 歌词下点状虚线(视频「地尽头」)
  const decoPanel = [
    // 面板块:右侧 0.70..0.96W 的半透明底(标题区)
    'setFill',
    'colorField unsung',
    'setColor',
    'push 0.18',
    'setAlpha',
    'canvasField width', 'push 0.70', 'mul',
    'canvasField height', 'push 0.06', 'mul',
    'canvasField width', 'push 0.26', 'mul',
    'canvasField height', 'push 0.88', 'mul',
    'drawRect',
    'colorField sung',
    'setColor',
    'push 0.55',
    'setAlpha',
    'globalField textSize',
    'push 0.035',
    'mul',
    'setStrokeWidth',
    'canvasField width', 'push 0.70', 'mul',
    'canvasField height', 'push 0.10', 'mul',
    'canvasField width', 'push 0.70', 'mul',
    'canvasField height', 'push 0.90', 'mul',
    'drawLine',
    'globalField textSize',
    'push 0.075',
    'mul',
    'setStrokeWidth',
    'canvasField width', 'push 0.715', 'mul',
    'canvasField height', 'push 0.12', 'mul',
    'canvasField width', 'push 0.715', 'mul',
    'canvasField height', 'push 0.34', 'mul',
    'drawLine',
    'setFill',
    // 三颗点:0.12H / 0.50H / 0.88H
    'push 0',
    'storeLocal 14',
    'PN1:',
    'loadLocal 14', 'push 3', 'lt', 'jz PNDone',
    'canvasField width', 'push 0.706', 'mul',
    'canvasField height', 'push 0.12', 'mul',
    'loadLocal 14', 'push 0.38', 'mul', 'canvasField height', 'mul', 'add',
    'canvasField width', 'push 0.006', 'mul',
    'drawCircle',
    'loadLocal 14', 'push 1', 'add', 'storeLocal 14',
    'jump PN1',
    'PNDone:',
    // 条码短横线:0.72..0.79W,三条
    'push 0', 'storeLocal 14',
    'BT1:',
    'loadLocal 14', 'push 3', 'lt', 'jz BTDone',
    'canvasField width', 'push 0.72', 'mul',
    'canvasField height', 'push 0.74', 'mul', 'loadLocal 14', 'push 0.03', 'mul', 'canvasField height', 'mul', 'add',
    'canvasField width', 'push 0.79', 'mul',
    'canvasField height', 'push 0.74', 'mul', 'loadLocal 14', 'push 0.03', 'mul', 'canvasField height', 'mul', 'add',
    'drawLine',
    'loadLocal 14', 'push 1', 'add', 'storeLocal 14',
    'jump BT1',
    'BTDone:'
  ];

  const fixedGraphic = { none: 0, line: 1, frame: 2, stamp: 3, glow: 4, panel: 5 }[style.graphicMode];
  const decoSelector = Number.isInteger(fixedGraphic)
    ? ['push ' + num(fixedGraphic), 'storeLocal 13']
    : [
        // auto:(行字符数 + 词数*C) mod 4 —— 装饰随行轮换,同曲稳定
        'loadLocal 2',
        'lineAtField text',
        'stringLength',
        'wordCount',
        'push ' + num(style.graphicSeedC),
        'mul',
        'add',
        'push 4',
        'mod',
        'storeLocal 13'
      ];

  const decoDispatch = [
    'loadLocal 13',
    'dup',
    'push 0',
    'eq',
    'jz DG1',
    'pop',
    'jump DGDone',
    'DG1:',
    'dup',
    'push 1',
    'eq',
    'jz DG2',
    'pop',
    ...indent(decoLine),
    'jump DGDone',
    'DG2:',
    'dup',
    'push 2',
    'eq',
    'jz DG3',
    'pop',
    ...indent(decoFrame),
    'jump DGDone',
    'DG3:',
    ...(fixedGraphic === 4 || fixedGraphic === 5
      ? [
        'dup',
        'push 3',
        'eq',
        'jz DG4',
        'pop',
        ...indent(decoStamp),
        'jump DGDone',
        'DG4:',
        ...(fixedGraphic === 5
          ? [
            'dup',
            'push 4',
            'eq',
            'jz DG5',
            'pop',
            ...indent(decoGlow),
            'jump DGDone',
            'DG5:',
            'pop',
            ...indent(decoPanel),
            'DGDone:'
          ]
          : [
            'pop',
            ...indent(decoGlow),
            'DGDone:'
          ])
      ]
      : [
        'pop',
        ...indent(decoStamp),
        'DGDone:'
      ])
  ];

  const decoEmission = [
    '  push 0',
    '  line',
    '  storeLocal 2',
    ...indent(decoSelector),
    ...indent(decoCommon),
    ...indent(decoDispatch)
  ];

  // 固定排版时只编译所选构图块;auto 保留选择器 + 四块派发(行为逐字节不变)。
  const layoutSection = Number.isInteger(fixedMode)
    ? indent(layoutModes[fixedMode])
    : [...indent(layoutSelector), ...indent(layoutDispatch)];

  // 单一手法时只编译该分支(为新增效果层腾预算);否则保留原四手法派发。
  const moveBodies = [wave, bounce, slide, pulse];
  const moveSection = singleMove !== null
    ? indent(moveBodies[singleMove])
    : [
      ...choreoDispatch,
      '  dup',
      '  push 0',
      '  eq',
      '  jz L1',
      '  pop',
      ...indent(wave),
      '  jump nextw',
      'L1:',
      '  dup',
      '  push 1',
      '  eq',
      '  jz L2',
      '  pop',
      ...indent(bounce),
      '  jump nextw',
      'L2:',
      '  dup',
      '  push 2',
      '  eq',
      '  jz L3',
      '  pop',
      ...indent(slide),
      '  jump nextw',
      'L3:',
      '  pop',
      ...indent(pulse)
    ];

  // 游标布局(ink/poster)的跨词状态:16=游标 X,17=上一视觉行号。
  const cursorInit = style.layoutMode === 'ink' || style.layoutMode === 'poster'
    ? ['push -1', 'storeLocal 16', 'push -1', 'storeLocal 17']
    : [];

  const lines = [
    '; HyperGlow Motion · 主题=' + style.themeName + ' · seed=' + style.seed +
      ' · 排版=' + style.layoutMode + ' · 图形=' + style.graphicMode,
    ...decoEmission,
    ...sparkleEmission,
    'wordCount',
    'storeLocal 1',
    ...cursorInit,
    'push 0',
    'storeLocal 0',
    'loop:',
    '  loadLocal 0',
    '  loadLocal 1',
    '  lt',
    '  jz done',
    '  loadLocal 0',
    '  word',
    '  storeLocal 6',
    '  loadLocal 6',
    '  wordField line',
    '  storeLocal 3',
    '  loadLocal 3',
    '  line',
    '  storeLocal 2',
    '  loadLocal 2',
    '  lineAtField progress',
    '  storeLocal 5',
    ...indent(prologue),
    ...layoutSection,
    ...indent(layoutApply),
    ...indent(panelRuleEmission),
    ...indent(ringEmission),
    ...indent(lineartEmission),
    ...indent(emberEmission),
    ...indent(focusEmission),
    ...moveSection,
    'nextw:',
    '  loadLocal 0',
    '  push 1',
    '  add',
    '  storeLocal 0',
    '  jump loop',
    'done:',
    '  end'
  ];
  return lines.join('\n');

  function indent(list) {
    return list.map((row) => '  ' + row);
  }
}

/**
 * 编排 → HyperGlow 效果包。返回 { pack, effect, stats } 或 { ok:false, errors }。
 * meta: { title, author }
 */
function buildEffectPack(timeline, style, meta) {
  const source = buildProgramSource(style);
  const assembled = E.assemble(source, { maxLocals: 20 });
  if (!assembled.ok) {
    return { ok: false, errors: assembled.errors };
  }
  // 汇编产物没有 outputMode 概念,盒模式在此显式注入(归一化会原样保留)。
  const manualRows = timeline && Array.isArray(timeline.rows)
    ? timeline.rows.filter((row) => !row.interlude && row.text)
    : [];
  const manualOverrides = style.manual
    ? style.manual.overrides.map((item) => Object.assign({}, item, {
      // 显式 lineText 优先;否则按当前工程行号映射整行文本
      lineText: item.lineText || (manualRows[item.line] && manualRows[item.line].text) || ''
    }))
    : undefined;
  const normalized = E.normalizeProgram({
    ...assembled.program,
    outputMode: E.EFFECT_OUTPUT_BOXES,
    wordOverrides: manualOverrides
  });
  if (!normalized.ok) {
    return { ok: false, errors: normalized.errors };
  }
  const seedTag = (typeof style.seed === 'number' ? Math.floor(Math.abs(style.seed)) : 0)
    .toString(36)
    .replace(/[^a-z0-9]/g, '') || '0';
  const effect = {
    id: ('motion_' + style.theme + '_' + seedTag).slice(0, 32),
    name: (String((meta && meta.title) || ('Motion · ' + style.themeName))).slice(0, 48),
    longSyllableMs: 700,
    stagger: 0,
    sweep: false,
    sweepBand: 0.28,
    unsungDim: E.clamp(style.unsungDim, 0.1, 0.6),
    glow: 'none',
    tracks: [],
    colors: [],
    program: normalized.program
  };
  const checked = E.normalizeEffectSpec(effect);
  if (!checked.ok) {
    return { ok: false, errors: checked.errors };
  }
  const pack = {
    format: E.FORMAT,
    version: E.PACK_VERSION,
    name: 'HyperGlow Motion · ' + style.themeName,
    author: String((meta && meta.author) || 'HyperGlow Motion').slice(0, 64),
    effects: [checked.effect]
  };
  const packResult = E.normalizePack(pack);
  if (!packResult.ok) {
    return { ok: false, errors: packResult.errors };
  }
  return {
    ok: true,
    pack: packResult.pack,
    effect: checked.effect,
    stats: {
      instructions: assembled.stats.instructions,
      constants: assembled.stats.constants,
      drawOps: assembled.stats.drawOps,
      estimatedBytes: assembled.stats.estimatedBytes
    }
  };
}

// ---------------------------------------------------------------------------
// 5. 预览:帧上下文 + 盒渲染
// ---------------------------------------------------------------------------

/**
 * 构建与实机整块路径同构的 EffectFrameContext:
 * 只喂「活跃行」的字符单元(CJK 逐字、西文按词,与宿主行级源合成交径一致),
 * 行窗/词窗按时间轴均分。t 秒;measure(text, sizePx) → px 宽。
 */
function buildFrameContext(timeline, tSeconds, palette, measure, canvasWidth, canvasHeight) {
  const rows = timeline.rows.filter((row) => !row.interlude && row.text);
  let active = null;
  for (const row of rows) {
    if (tSeconds >= row.start && tSeconds < row.end) {
      active = row;
      break;
    }
  }
  if (!active) {
    // 播放头落在间奏/空隙:找下一个可渲染行,预览整块留空(与实机一致)
    return null;
  }
  const activeIndex = rows.indexOf(active);
  const span = Math.max(1, (active.end - active.start) * 1000);
  const startMs = active.start * 1000;
  const positionMs = tSeconds * 1000;
  const lineProgress = E.clamp((positionMs - startMs) / span, 0, 1);

  const tokens = E.tokenizeLine(active.text);
  const textSize = 64;
  const gapEm = 0.18;
  const widths = tokens.map((token) => measure(token.text, textSize) + (token.spaceBefore ? textSize * gapEm : 0));
  const totalWidth = widths.reduce((sum, width) => sum + Math.max(0, width), 0);
  const canvasW = Number.isFinite(canvasWidth) ? canvasWidth : 720;
  const startX = Math.max(8, (canvasW - totalWidth) / 2);
  const baseline = Math.max(textSize, (Number.isFinite(canvasHeight) ? canvasHeight : 320) * 0.42);

  const words = [];
  let cursorX = startX;
  let unitStartMs = startMs;
  const perUnitMs = span / Math.max(1, tokens.length);
  tokens.forEach((token, index) => {
    const width = Math.max(1, widths[index] - (token.spaceBefore ? textSize * gapEm : 0));
    const unitEndMs = startMs + perUnitMs * (index + 1);
    words.push({
      text: token.text,
      x: cursorX,
      width,
      startMs: unitStartMs,
      endMs: unitEndMs,
      progress: E.clamp((positionMs - unitStartMs) / Math.max(1, unitEndMs - unitStartMs), 0, 1),
      index,
      longSyllable: unitEndMs - unitStartMs >= 700,
      lineIndex: 0,
      baseline
    });
    cursorX += Math.max(0, widths[index]);
    unitStartMs = unitEndMs;
  });

  return {
    timeMs: Math.round(positionMs),
    wallClockMs: typeof performance !== 'undefined' ? performance.now() : Date.now(),
    deltaMs: 0,
    frameIndex: 0,
    lineText: active.text,
    lineStartMs: startMs,
    lineEndMs: startMs + span,
    lineProgress,
    lineIndex: 0,
    canvasWidth: canvasW,
    canvasHeight: Number.isFinite(canvasHeight) ? canvasHeight : 320,
    textSize,
    baseline,
    colors: palette,
    words,
    lines: [
      {
        text: active.text,
        fullText: active.text,
        index: 0,
        startMs,
        endMs: startMs + span,
        progress: lineProgress,
        x: startX,
        width: totalWidth,
        baseline,
        height: textSize * 1.4
      }
    ],
    blockWidth: totalWidth,
    blockHeight: textSize * 1.4
  };
}

/**
 * 盒 → Canvas2D(与实机 EffectBoxRenderer / 主编辑器 drawBoxesOnContext 同语义)。
 * ctx 须处于 CSS 像素坐标;drawTextFont(sizePx) 返回该字号下的 CSS font 字符串。
 */
function renderBoxes(ctx, boxes, drawTextFont, cssRgb, cssColor, spriteImages) {
  for (const box of boxes) {
    const t = box.transform;
    const transformed = !(t.m0 === 1 && t.m1 === 0 && t.m2 === 0 &&
      t.m3 === 0 && t.m4 === 1 && t.m5 === 0);
    if (transformed) {
      ctx.save();
      // Canvas transform(a,b,c,d,e,f):x' = a·x + c·y + e
      ctx.transform(t.m0, t.m3, t.m1, t.m4, t.m2, t.m5);
    }
    const paint = box.paint;
    const color = paint.gradient ? cssRgb(paint.gradient.color0) : cssRgb(paint.color);
    ctx.globalAlpha = E.clamp(paint.alpha, 0, 1);
    if (paint.shadow && paint.shadow.set) {
      ctx.shadowBlur = paint.shadow.radius;
      ctx.shadowOffsetX = paint.shadow.dx;
      ctx.shadowOffsetY = paint.shadow.dy;
      ctx.shadowColor = cssColor(paint.shadow.color, 1);
    } else {
      ctx.shadowBlur = 0;
      ctx.shadowOffsetX = 0;
      ctx.shadowOffsetY = 0;
      ctx.shadowColor = 'transparent';
    }
    const stroke = paint.style === 1;
    switch (box.type) {
      case 'text': {
        ctx.font = drawTextFont(E.clamp(paint.textSize, 1, 512));
        ctx.textAlign = paint.align === 1 ? 'center' : paint.align === 2 ? 'right' : 'left';
        ctx.textBaseline = 'alphabetic';
        ctx.fillStyle = color;
        ctx.fillText(box.text, box.x, box.baseline);
        break;
      }
      case 'rect': {
        ctx.beginPath();
        ctx.rect(box.x, box.y, Math.max(0, box.width), Math.max(0, box.height));
        fillOrStroke(ctx, color, stroke, paint.strokeWidth);
        break;
      }
      case 'roundRect': {
        ctx.beginPath();
        if (typeof ctx.roundRect === 'function') {
          ctx.roundRect(box.x, box.y, Math.max(0, box.width), Math.max(0, box.height),
            [Math.max(0, box.rx), Math.max(0, box.ry)]);
        } else {
          ctx.rect(box.x, box.y, Math.max(0, box.width), Math.max(0, box.height));
        }
        fillOrStroke(ctx, color, stroke, paint.strokeWidth);
        break;
      }
      case 'circle': {
        ctx.beginPath();
        ctx.arc(box.cx, box.cy, Math.max(0, box.radius), 0, Math.PI * 2);
        fillOrStroke(ctx, color, stroke, paint.strokeWidth);
        break;
      }
      case 'line': {
        ctx.beginPath();
        ctx.moveTo(box.x1, box.y1);
        ctx.lineTo(box.x2, box.y2);
        ctx.lineWidth = Math.max(1, E.clamp(paint.strokeWidth, 0, 128));
        ctx.strokeStyle = color;
        ctx.stroke();
        break;
      }
      case 'oval': {
        ctx.beginPath();
        ctx.ellipse(
          box.x + Math.max(0, box.width) / 2,
          box.y + Math.max(0, box.height) / 2,
          Math.max(0, box.width) / 2,
          Math.max(0, box.height) / 2,
          0, 0, Math.PI * 2
        );
        fillOrStroke(ctx, color, stroke, paint.strokeWidth);
        break;
      }
      case 'arc': {
        ctx.beginPath();
        const rx = Math.max(0, box.width) / 2;
        const ry = Math.max(0, box.height) / 2;
        if (rx > 0 && ry > 0) {
          ctx.ellipse(
            box.x + rx, box.y + ry, rx, ry, 0,
            (box.start * Math.PI) / 180,
            ((box.start + box.sweep) * Math.PI) / 180
          );
        }
        fillOrStroke(ctx, color, stroke, paint.strokeWidth);
        break;
      }
      case 'sprite': {
        const img = spriteImages && spriteImages[box.spriteId];
        if (img && img.complete) {
          ctx.globalAlpha = E.clamp(paint.alpha, 0, 1);
          ctx.drawImage(img, box.x, box.y, Math.max(0, box.width), Math.max(0, box.height));
        } else if (box.spriteId >= 64) {
          // 64..255 为用户导入贴纸:字节只存在设备上,网页预览画虚线占位框;
          // 导出后在 App「自定义贴纸」导入同编号图片即可在锁屏/AOD 显示。
          ctx.save();
          ctx.globalAlpha = E.clamp(paint.alpha, 0, 1) * 0.6;
          ctx.setLineDash([6, 4]);
          ctx.strokeStyle = color;
          ctx.strokeRect(box.x, box.y, Math.max(0, box.width), Math.max(0, box.height));
          ctx.restore();
        }
        break;
      }
      case 'path': {
        ctx.beginPath();
        for (const segment of box.segments) {
          switch (segment.kind) {
            case 0: ctx.moveTo(segment.x0, segment.y0); break;
            case 1: ctx.lineTo(segment.x0, segment.y0); break;
            case 2: ctx.quadraticCurveTo(segment.x0, segment.y0, segment.x1, segment.y1); break;
            case 3: ctx.bezierCurveTo(segment.x0, segment.y0, segment.x1, segment.y1, segment.x2, segment.y2); break;
            case 4: ctx.closePath(); break;
          }
        }
        fillOrStroke(ctx, color, stroke, paint.strokeWidth);
        break;
      }
    }
    if (transformed) ctx.restore();
  }

  function fillOrStroke(context, color, useStroke, strokeWidth) {
    context.lineWidth = Math.max(1, E.clamp(strokeWidth, 0, 128));
    if (useStroke) {
      context.strokeStyle = color;
      context.stroke();
    } else {
      context.fillStyle = color;
      context.fill();
    }
  }
}

// ---------------------------------------------------------------------------
// 6. 导出
// ---------------------------------------------------------------------------

/** 时间轴 → LRC 文本(间奏行不写)。 */
function toLrc(timeline) {
  const out = ['[re:HyperGlow Motion]'];
  for (const row of timeline.rows) {
    if (row.interlude || !row.text) continue;
    out.push(formatLrcTime(row.start) + row.text);
  }
  return out.join('\n');
}

/** 工程 JSON(可回灌继续编辑)。 */
function toProject(data) {
  return {
    format: 'hyperglow-motion-project',
    version: 1,
    lyrics: data.lyrics,
    bpm: data.bpm,
    startSec: data.startSec,
    lineDurSec: data.lineDurSec,
    snap: data.snap,
    tap: data.tap,
    seed: data.seed,
    theme: data.theme,
    overrides: data.overrides || {},
    manual: data.manual || null,
    manualImages: data.manualImages || {},
    palette: data.palette
  };
}

function clampNumber(value, min, max, fallback) {
  const number = Number(value);
  if (!Number.isFinite(number)) return fallback;
  return E.clamp(number, min, max);
}

  return {
    parseLyrics,
    parseLrcTime,
    formatLrcTime,
    buildTimeline,
    makeRng,
    THEMES,
    PRESETS,
    CHOREO_MODES,
    LAYOUT_MODES,
    GRAPHIC_MODES,
    styleFromSeed,
    buildProgramSource,
    buildEffectPack,
    buildFrameContext,
    renderBoxes,
    toLrc,
    toProject
  };
});
