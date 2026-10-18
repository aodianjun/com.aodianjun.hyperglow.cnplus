/*
 * Word Effect Studio — shared engine.
 *
 * Mirrors the HyperGlow word-effect pack contract:
 *   docs/WORD_EFFECT_PACKS.md
 *   app/src/main/java/com/eza/hyperglow/customization/WordEffectModels.kt
 *   app/src/main/java/com/eza/hyperglow/effect/EffectProgram.kt
 *   app/src/main/java/com/eza/hyperglow/effect/EffectRuntime.kt
 *
 * The same file is loaded by index.html (window.WEEngine) and by test_engine.js
 * (module.exports), so the preview, the assembler and the validators share one
 * implementation and can be regression-tested without a browser.
 */
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    module.exports = factory();
  } else {
    root.WEEngine = factory();
  }
})(typeof globalThis !== 'undefined' ? globalThis : this, function () {
  'use strict';

  // ---------------------------------------------------------------------------
  // Pack / effect limits (docs §1–§6)
  // ---------------------------------------------------------------------------

  const FORMAT = 'hyperglow-word-effect-pack';
  const PACK_VERSION = 1;
  const MAX_PACK_BYTES = 64 * 1024;
  const MAX_EFFECTS = 32;
  const MAX_TRACKS = 12;
  const MAX_KEYFRAMES_PER_TRACK = 16;
  const MAX_TOTAL_KEYFRAMES = 64;
  const MAX_COLORS = 8;
  const MAX_NAME_CHARS = 48;
  const ID_RE = /^[a-z0-9_]{1,32}$/;

  const PROPERTY_RANGES = Object.freeze({
    translateX: [-2, 2],
    translateY: [-2, 2],
    scale: [0.1, 3],
    rotate: [-720, 720],
    alpha: [0, 1],
    skewX: [-1, 1],
    glowRadius: [0, 1],
    glowAlpha: [0, 1],
    sweepProgress: [0, 1]
  });

  const PROPERTY_DEFAULTS = Object.freeze({
    translateX: 0,
    translateY: 0,
    scale: 1,
    rotate: 0,
    alpha: 1,
    skewX: 0,
    glowRadius: 0,
    glowAlpha: 1,
    sweepProgress: -1
  });

  const EASINGS = Object.freeze([
    'linear',
    'easeIn',
    'easeOut',
    'easeInOut',
    'easeInBack',
    'easeOutBack',
    'easeOutElastic',
    'easeOutBounce',
    'stepEnd'
  ]);
  const EASING_SET = new Set(EASINGS);

  const GLOW_MODES = Object.freeze(['none', 'long', 'all']);

  // ---------------------------------------------------------------------------
  // Program limits (docs §10)
  // ---------------------------------------------------------------------------

  const EFFECT_PROGRAM_VERSION = 1;
  const MAX_EFFECT_CONSTANTS = 64;
  const MAX_EFFECT_CODE = 512;
  const MAX_EFFECT_LOCALS = 24;
  const MAX_EFFECT_STACK = 64;
  const MAX_EFFECT_STEPS_PER_FRAME = 100000;
  const MAX_EFFECT_DRAW_OPS_PER_FRAME = 4096;
  const MAX_EFFECT_PROGRAM_ESTIMATED_BYTES = 12 * 1024;

  const EFFECT_OPS = new Set([
    'const', 'loadLocal', 'storeLocal', 'pop', 'dup', 'swap',
    'globalField', 'lineField', 'lineCount', 'line', 'lineAtField',
    'canvasField', 'colorField',
    'wordCount', 'word', 'wordField',
    'add', 'sub', 'mul', 'div', 'mod', 'neg',
    'sin', 'cos', 'abs', 'min', 'max', 'clamp', 'lerp', 'pow', 'sqrt',
    'floor', 'round',
    'lt', 'le', 'gt', 'ge', 'eq', 'ne', 'and', 'or', 'not',
    'stringLength', 'charAt', 'substring',
    'jump', 'jz', 'jnz',
    'setColor', 'setAlpha', 'setTextSize', 'setTextAlign',
    'setGradient', 'clearGradient', 'setShadow', 'clearShadow',
    'setStrokeWidth', 'setFill', 'setStroke',
    'save', 'restore', 'translate', 'rotate', 'scale', 'clipRect',
    'drawText', 'drawRect', 'drawCircle', 'drawLine', 'drawRoundRect', 'drawOval', 'drawArc',
    'pathMove', 'pathLine', 'pathQuad', 'pathCubic', 'pathClose', 'drawPath',
    'measureText', 'end'
  ]);

  const EFFECT_GLOBAL_FIELDS = new Set([
    'time', 'wall', 'baseline', 'textSize', 'blockWidth', 'blockHeight'
  ]);
  const EFFECT_LINE_FIELDS = new Set([
    'text', 'start', 'end', 'progress', 'index', 'x', 'width', 'baseline', 'height'
  ]);
  const EFFECT_CANVAS_FIELDS = new Set(['width', 'height']);
  const EFFECT_COLOR_FIELDS = new Set(['sung', 'unsung', 'glow', 'accent']);
  const EFFECT_WORD_FIELDS = new Set([
    'text', 'x', 'width', 'start', 'end', 'progress', 'index', 'long',
    'line', 'baseline'
  ]);

  const EFFECT_FIELD_OPS = {
    globalField: EFFECT_GLOBAL_FIELDS,
    lineField: EFFECT_LINE_FIELDS,
    canvasField: EFFECT_CANVAS_FIELDS,
    colorField: EFFECT_COLOR_FIELDS,
    wordField: EFFECT_WORD_FIELDS,
    lineAtField: EFFECT_LINE_FIELDS
  };

  const EFFECT_DRAW_OPS = new Set([
    'setColor', 'setAlpha', 'setTextSize', 'setTextAlign',
    'setGradient', 'clearGradient', 'setShadow', 'clearShadow',
    'setStrokeWidth', 'setFill', 'setStroke',
    'save', 'restore', 'translate', 'rotate', 'scale', 'clipRect',
    'drawText', 'drawRect', 'drawCircle', 'drawLine', 'drawRoundRect', 'drawOval', 'drawArc',
    'pathMove', 'pathLine', 'pathQuad', 'pathCubic', 'pathClose', 'drawPath', 'measureText'
  ]);

  const COMMAND_PREFIXES = ['sh', 'bash', 'zsh', 'cmd', 'su', 'exec', 'am', 'pm', 'rm', 'curl', 'wget', 'adb'];

  // ---------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------

  function clamp(value, low, high) {
    return value < low ? low : value > high ? high : value;
  }

  function isFiniteNumber(value) {
    return typeof value === 'number' && Number.isFinite(value);
  }

  function finiteOrZero(value) {
    return isFiniteNumber(value) ? value : 0;
  }

  function round3(value) {
    return Math.round(value * 1000) / 1000;
  }

  function deepClone(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function bytesOf(text) {
    if (typeof TextEncoder !== 'undefined') {
      return new TextEncoder().encode(text).length;
    }
    return Buffer.byteLength(text, 'utf8');
  }

  /** `SceneCompiler.containsUnsafeSchemaValue` 的等价实现。 */
  function containsUnsafeSchemaValue(value) {
    if (typeof value !== 'string') return false;
    const trimmed = value.trim();
    const lower = trimmed.toLowerCase();
    return value.indexOf('\u0000') >= 0 ||
      lower.indexOf('://') >= 0 ||
      lower.startsWith('file:') ||
      lower.startsWith('content:') ||
      lower.startsWith('javascript:') ||
      trimmed.startsWith('/') ||
      lower.indexOf('../') >= 0 ||
      lower.indexOf('..\\') >= 0 ||
      value.indexOf('\\') >= 0 ||
      COMMAND_PREFIXES.some((prefix) => lower === prefix || lower.startsWith(prefix + ' '));
  }

  // ---------------------------------------------------------------------------
  // Colors (ARGB signed int, exactly like Kotlin's Int)
  // ---------------------------------------------------------------------------

  function parseColor(value) {
    if (typeof value !== 'string') return null;
    const hex = value.trim().replace(/^#/, '');
    if (!/^[0-9a-fA-F]{6}$/.test(hex) && !/^[0-9a-fA-F]{8}$/.test(hex)) return null;
    const number = parseInt(hex, 16);
    return hex.length === 6 ? (0xFF000000 | number) | 0 : number | 0;
  }

  function formatColor(argb) {
    return '#' + (argb >>> 0).toString(16).toUpperCase().padStart(8, '0');
  }

  function colorChannels(argb) {
    const unsigned = argb >>> 0;
    return {
      a: (unsigned >>> 24) & 0xFF,
      r: (unsigned >>> 16) & 0xFF,
      g: (unsigned >>> 8) & 0xFF,
      b: unsigned & 0xFF
    };
  }

  function lerpArgb(from, to, t) {
    const u = clamp(t, 0, 1);
    const channel = (shift) => {
      const a = (from >>> shift) & 0xFF;
      const b = (to >>> shift) & 0xFF;
      return clamp(Math.round(a + (b - a) * u), 0, 255);
    };
    return ((channel(24) << 24) | (channel(16) << 16) | (channel(8) << 8) | channel(0)) | 0;
  }

  // ---------------------------------------------------------------------------
  // Easing (WordEffectModels.kt)
  // ---------------------------------------------------------------------------

  function easeOutBounce(u) {
    const n1 = 7.5625;
    const d1 = 2.75;
    let x = u;
    if (x < 1 / d1) return n1 * x * x;
    if (x < 2 / d1) {
      x -= 1.5 / d1;
      return n1 * x * x + 0.75;
    }
    if (x < 2.5 / d1) {
      x -= 2.25 / d1;
      return n1 * x * x + 0.9375;
    }
    x -= 2.625 / d1;
    return n1 * x * x + 0.984375;
  }

  function applyEasing(easing, t) {
    const u = isFiniteNumber(t) ? clamp(t, 0, 1) : 0;
    switch (easing) {
      case 'easeIn':
        return u * u;
      case 'easeOut':
        return 1 - (1 - u) * (1 - u);
      case 'easeInOut':
        return u < 0.5 ? 2 * u * u : 1 - 2 * (1 - u) * (1 - u);
      case 'easeInBack': {
        const c1 = 1.70158;
        const c3 = c1 + 1;
        return c3 * u * u * u - c1 * u * u;
      }
      case 'easeOutBack': {
        const c1 = 1.70158;
        const c3 = c1 + 1;
        const v = u - 1;
        return 1 + c3 * v * v * v + c1 * v * v;
      }
      case 'easeOutElastic':
        if (u <= 0) return 0;
        if (u >= 1) return 1;
        {
          const c4 = (2 * Math.PI) / 3;
          return Math.pow(2, -10 * u) * Math.sin((u * 10 - 0.75) * c4) + 1;
        }
      case 'easeOutBounce':
        return easeOutBounce(u);
      case 'stepEnd':
        return u >= 1 ? 1 : 0;
      default:
        return u;
    }
  }

  /** 未唱底字亮度台阶（AodCanvasScale.steadyTextAlpha）。 */
  function steadyTextAlpha(dimFactor) {
    return dimFactor < 0.5
      ? Math.max(0.35 * 1.6, 0.55)
      : Math.min(1, 0.85 * 1.6);
  }

  // ---------------------------------------------------------------------------
  // Track evaluation
  // ---------------------------------------------------------------------------

  function evaluateTrack(keyframes, t) {
    if (!Array.isArray(keyframes) || keyframes.length === 0) return 0;
    if (t <= keyframes[0].at) return keyframes[0].value;
    if (t >= keyframes[keyframes.length - 1].at) return keyframes[keyframes.length - 1].value;
    for (let i = 0; i < keyframes.length - 1; i++) {
      const start = keyframes[i];
      const end = keyframes[i + 1];
      if (t <= end.at) {
        const span = Math.max(end.at - start.at, 1e-6);
        const u = clamp((t - start.at) / span, 0, 1);
        const eased = applyEasing(start.easing, u);
        return start.value + (end.value - start.value) * eased;
      }
    }
    return keyframes[keyframes.length - 1].value;
  }

  function evaluateColor(keyframes, t) {
    if (!Array.isArray(keyframes) || keyframes.length === 0) return null;
    const first = parseColor(keyframes[0].color);
    if (first === null) return null;
    if (t <= keyframes[0].at) return first;
    const last = parseColor(keyframes[keyframes.length - 1].color);
    if (last === null) return null;
    if (t >= keyframes[keyframes.length - 1].at) return last;
    for (let i = 0; i < keyframes.length - 1; i++) {
      const start = keyframes[i];
      const end = keyframes[i + 1];
      if (t <= end.at) {
        const startColor = parseColor(start.color);
        const endColor = parseColor(end.color);
        if (startColor === null || endColor === null) return null;
        const span = Math.max(end.at - start.at, 1e-6);
        const u = clamp((t - start.at) / span, 0, 1);
        const eased = applyEasing(start.easing, u);
        return lerpArgb(startColor, endColor, eased);
      }
    }
    return last;
  }

  /**
   * 与 `evaluateWordEffect` 逐值等价：stagger 按词块总数封顶并归一，
   * 属性值在轨道求值后再按属性范围钳制一次（回弹/弹性会越界）。
   */
  function evaluateEffect(spec, progress, wordIndex, longSyllable, wordCount) {
    const p = isFiniteNumber(progress) ? clamp(progress, 0, 1) : 0;
    const count = Math.max(1, Math.trunc(wordCount) || 1);
    const stagger = count > 1 ? clamp(spec.stagger, 0, 0.9 / (count - 1)) : 0;
    const span = Math.max(1 - stagger * (count - 1), 0.1);
    const t = clamp((p - Math.max(0, Math.trunc(wordIndex) || 0) * stagger) / span, 0, 1);

    let translateX = 0;
    let translateY = 0;
    let scale = 1;
    let rotate = 0;
    let alpha = 1;
    let skewX = 0;
    let glowRadius = null;
    let glowAlpha = 1;
    let sweepProgress = -1;

    for (const track of spec.tracks || []) {
      if (track.longOnly && !longSyllable) continue;
      const range = PROPERTY_RANGES[track.property];
      if (!range) continue;
      const value = clamp(evaluateTrack(track.keyframes, t), range[0], range[1]);
      switch (track.property) {
        case 'translateX': translateX = value; break;
        case 'translateY': translateY = value; break;
        case 'scale': scale = value; break;
        case 'rotate': rotate = value; break;
        case 'alpha': alpha = value; break;
        case 'skewX': skewX = value; break;
        case 'glowRadius': glowRadius = value; break;
        case 'glowAlpha': glowAlpha = value; break;
        case 'sweepProgress': sweepProgress = value; break;
        default: break;
      }
    }

    return {
      translateXEm: translateX,
      translateYEm: translateY,
      scale,
      rotateDeg: rotate,
      alpha,
      skewX,
      glowRadiusEm: glowRadius,
      glowAlpha,
      sweepProgress,
      colorArgb: (spec.colors && spec.colors.length) ? evaluateColor(spec.colors, t) : null,
      /** stagger 之后的时间轴，预览 HUD 与曲线编辑用；渲染本身不消费。 */
      t
    };
  }

  // ---------------------------------------------------------------------------
  // Keyframe normalization
  // ---------------------------------------------------------------------------

  function normalizeNumericKeyframes(rawKeyframes, property) {
    const errors = [];
    const warnings = [];
    const range = PROPERTY_RANGES[property];
    if (!range) {
      errors.push('未知属性 ' + property);
      return { ok: false, errors, warnings };
    }
    if (!Array.isArray(rawKeyframes) || rawKeyframes.length === 0) {
      errors.push('轨道至少需要一个关键帧');
      return { ok: false, errors, warnings };
    }
    if (rawKeyframes.length > MAX_KEYFRAMES_PER_TRACK) {
      errors.push('单轨关键帧超过 ' + MAX_KEYFRAMES_PER_TRACK + ' 个');
      return { ok: false, errors, warnings };
    }

    const clamped = [];
    for (let i = 0; i < rawKeyframes.length; i++) {
      const frame = rawKeyframes[i];
      if (!frame || typeof frame !== 'object') {
        errors.push('第 ' + (i + 1) + ' 个关键帧不是对象');
        return { ok: false, errors, warnings };
      }
      if (!isFiniteNumber(frame.at) || !isFiniteNumber(frame.value)) {
        errors.push('第 ' + (i + 1) + ' 个关键帧的 at/value 不是有限数值');
        return { ok: false, errors, warnings };
      }
      const at = clamp(frame.at, 0, 1);
      const value = clamp(frame.value, range[0], range[1]);
      let easing = frame.easing;
      if (typeof easing !== 'string' || !EASING_SET.has(easing)) {
        if (easing !== undefined && easing !== null && easing !== '') {
          warnings.push('未知缓动 "' + easing + '"，回落 linear');
        }
        easing = 'linear';
      }
      if (at !== frame.at) warnings.push('关键帧 at=' + frame.at + ' 已钳制到 ' + at);
      if (value !== frame.value) {
        warnings.push('关键帧 value=' + frame.value + ' 已钳制到 ' + value + '（' + property + ' 范围 ' + range[0] + '..' + range[1] + '）');
      }
      clamped.push({ at, value, easing });
    }

    clamped.sort((a, b) => a.at - b.at);
    const deduped = [];
    for (const frame of clamped) {
      if (deduped.length && deduped[deduped.length - 1].at === frame.at) {
        warnings.push('at=' + frame.at + ' 有重复关键帧，保留后写的一条');
        deduped[deduped.length - 1] = frame;
      } else {
        deduped.push(frame);
      }
    }

    if (deduped.length === 1) {
      const only = deduped[0];
      return {
        ok: true,
        keyframes: [
          { at: 0, value: only.value, easing: only.easing },
          { at: 1, value: only.value, easing: only.easing }
        ],
        errors,
        warnings
      };
    }

    deduped[0] = Object.assign({}, deduped[0], { at: 0 });
    deduped[deduped.length - 1] = Object.assign({}, deduped[deduped.length - 1], { at: 1 });
    return { ok: true, keyframes: deduped, errors, warnings };
  }

  function normalizeColorKeyframes(rawKeyframes) {
    const errors = [];
    const warnings = [];
    if (rawKeyframes == null) return { ok: true, keyframes: [], errors, warnings };
    if (!Array.isArray(rawKeyframes)) {
      errors.push('colors 必须是数组');
      return { ok: false, errors, warnings };
    }
    if (rawKeyframes.length === 0) return { ok: true, keyframes: [], errors, warnings };
    if (rawKeyframes.length > MAX_COLORS) {
      errors.push('颜色关键帧超过 ' + MAX_COLORS + ' 个');
      return { ok: false, errors, warnings };
    }

    const parsed = [];
    for (let i = 0; i < rawKeyframes.length; i++) {
      const frame = rawKeyframes[i];
      if (!frame || typeof frame !== 'object') {
        errors.push('第 ' + (i + 1) + ' 个颜色关键帧不是对象');
        return { ok: false, errors, warnings };
      }
      if (!isFiniteNumber(frame.at)) {
        errors.push('第 ' + (i + 1) + ' 个颜色关键帧的 at 不是有限数值');
        return { ok: false, errors, warnings };
      }
      const argb = parseColor(frame.color);
      if (argb === null) {
        errors.push('第 ' + (i + 1) + ' 个颜色关键帧的 color 非法：' + String(frame.color));
        return { ok: false, errors, warnings };
      }
      let easing = frame.easing;
      if (typeof easing !== 'string' || !EASING_SET.has(easing)) {
        if (easing !== undefined && easing !== null && easing !== '') {
          warnings.push('未知缓动 "' + easing + '"，回落 linear');
        }
        easing = 'linear';
      }
      const at = clamp(frame.at, 0, 1);
      if (at !== frame.at) warnings.push('颜色关键帧 at=' + frame.at + ' 已钳制到 ' + at);
      parsed.push({ at, color: formatColor(argb), easing });
    }

    parsed.sort((a, b) => a.at - b.at);
    const deduped = [];
    for (const frame of parsed) {
      if (deduped.length && deduped[deduped.length - 1].at === frame.at) {
        warnings.push('at=' + frame.at + ' 有重复颜色关键帧，保留后写的一条');
        deduped[deduped.length - 1] = frame;
      } else {
        deduped.push(frame);
      }
    }
    if (deduped.length === 1) {
      const only = deduped[0];
      return {
        ok: true,
        keyframes: [
          { at: 0, color: only.color, easing: only.easing },
          { at: 1, color: only.color, easing: only.easing }
        ],
        errors,
        warnings
      };
    }
    deduped[0] = Object.assign({}, deduped[0], { at: 0 });
    deduped[deduped.length - 1] = Object.assign({}, deduped[deduped.length - 1], { at: 1 });
    return { ok: true, keyframes: deduped, errors, warnings };
  }

  // ---------------------------------------------------------------------------
  // Program normalization
  // ---------------------------------------------------------------------------

  function estimateProgramBytes(program) {
    if (!program || !Array.isArray(program.code)) return 0;
    let estimated = program.code.length * 12;
    for (const constant of program.constants || []) {
      estimated += String(constant.value == null ? '' : constant.value).length + 8;
    }
    return estimated;
  }

  function normalizeProgram(rawProgram) {
    const errors = [];
    if (rawProgram == null) return { ok: true, program: null, errors };
    if (typeof rawProgram !== 'object' || Array.isArray(rawProgram)) {
      return { ok: false, program: null, errors: ['program 必须是对象'] };
    }
    const version = rawProgram.version == null ? EFFECT_PROGRAM_VERSION : rawProgram.version;
    if (version !== EFFECT_PROGRAM_VERSION) errors.push('program.version 必须为 1');
    const constants = rawProgram.constants == null ? [] : rawProgram.constants;
    const code = rawProgram.code;
    const maxLocals = rawProgram.maxLocals == null ? 16 : rawProgram.maxLocals;

    if (!Array.isArray(constants)) errors.push('program.constants 必须是数组');
    if (!Array.isArray(code)) errors.push('program.code 必须是数组');
    if (Array.isArray(constants) && constants.length > MAX_EFFECT_CONSTANTS) {
      errors.push('常量超过 ' + MAX_EFFECT_CONSTANTS + ' 项');
    }
    if (Array.isArray(code)) {
      if (code.length === 0) errors.push('program.code 不能为空');
      if (code.length > MAX_EFFECT_CODE) errors.push('指令超过 ' + MAX_EFFECT_CODE + ' 条');
    }
    if (!Number.isInteger(maxLocals) || maxLocals < 0 || maxLocals > MAX_EFFECT_LOCALS) {
      errors.push('program.maxLocals 必须是 0..' + MAX_EFFECT_LOCALS + ' 的整数');
    }
    if (errors.length) return { ok: false, program: null, errors };

    for (let i = 0; i < constants.length; i++) {
      const constant = constants[i];
      if (!constant || typeof constant !== 'object') {
        errors.push('常量 ' + i + ' 不是对象');
        continue;
      }
      const type = constant.type == null ? 'number' : constant.type;
      if (type !== 'number' && type !== 'string' && type !== 'bool') {
        errors.push('常量 ' + i + ' 的 type 非法：' + String(constant.type));
        continue;
      }
      if (typeof constant.value !== 'string') {
        errors.push('常量 ' + i + ' 的 value 必须是字符串');
        continue;
      }
      if (type === 'number') {
        const number = Number(constant.value);
        if (!Number.isFinite(number)) errors.push('常量 ' + i + ' 不是有限数值');
      } else if (type === 'string') {
        if (constant.value.length > 256) errors.push('常量 ' + i + ' 字符串超过 256 字符');
        if (containsUnsafeSchemaValue(constant.value)) errors.push('常量 ' + i + ' 含不安全字符串');
      } else if (constant.value !== 'true' && constant.value !== 'false') {
        errors.push('常量 ' + i + ' 的 bool 值必须是 "true" / "false"');
      }
    }

    for (let i = 0; i < code.length; i++) {
      const instruction = code[i];
      if (!instruction || typeof instruction !== 'object') {
        errors.push('指令 ' + i + ' 不是对象');
        continue;
      }
      const op = instruction.op;
      if (typeof op !== 'string' || !EFFECT_OPS.has(op)) {
        errors.push('指令 ' + i + ' 的 op 非法：' + String(op));
        continue;
      }
      if (op === 'const') {
        if (!Number.isInteger(instruction.arg) || instruction.arg < 0 || instruction.arg >= constants.length) {
          errors.push('指令 ' + i + ' const 的常量下标越界');
        }
      } else if (op === 'loadLocal' || op === 'storeLocal') {
        if (!Number.isInteger(instruction.arg) || instruction.arg < 0 || instruction.arg >= maxLocals) {
          errors.push('指令 ' + i + ' ' + op + ' 的局部槽越界');
        }
      } else if (op === 'jump' || op === 'jz' || op === 'jnz') {
        if (!Number.isInteger(instruction.arg) || instruction.arg < 0 || instruction.arg > code.length) {
          errors.push('指令 ' + i + ' 的跳转目标越界');
        }
      } else if (EFFECT_FIELD_OPS[op]) {
        if (!EFFECT_FIELD_OPS[op].has(instruction.text)) {
          errors.push('指令 ' + i + ' ' + op + ' 的字段名非法：' + String(instruction.text));
        }
      }
    }

    const estimatedBytes = estimateProgramBytes({ code, constants });
    if (estimatedBytes > MAX_EFFECT_PROGRAM_ESTIMATED_BYTES) {
      errors.push('程序估算字节 ' + estimatedBytes + ' 超过 12 KiB 预算');
    }

    if (errors.length) return { ok: false, program: null, errors };
    return {
      ok: true,
      program: {
        version: EFFECT_PROGRAM_VERSION,
        constants: constants.map((constant) => ({
          type: constant.type == null ? 'number' : constant.type,
          value: constant.value
        })),
        code: code.map((instruction) => {
          const out = { op: instruction.op };
          if (instruction.arg !== undefined && instruction.arg !== null) out.arg = instruction.arg;
          if (instruction.text !== undefined && instruction.text !== null) out.text = instruction.text;
          return out;
        }),
        maxLocals
      },
      errors
    };
  }

  // ---------------------------------------------------------------------------
  // Effect / pack normalization
  // ---------------------------------------------------------------------------

  function normalizeEffectSpec(rawSpec) {
    const errors = [];
    const warnings = [];
    const fail = (message) => {
      errors.push(message);
      return { ok: false, effect: null, errors, warnings };
    };

    if (!rawSpec || typeof rawSpec !== 'object' || Array.isArray(rawSpec)) {
      return fail('效果不是对象');
    }

    const id = typeof rawSpec.id === 'string' ? rawSpec.id.trim() : '';
    if (!ID_RE.test(id) || id === 'none') {
      return fail('id 必须匹配 ^[a-z0-9_]{1,32}$ 且不能是 "none"');
    }

    const rawName = typeof rawSpec.name === 'string' ? rawSpec.name.trim().slice(0, MAX_NAME_CHARS) : '';
    let name = rawName;
    if (!name || containsUnsafeSchemaValue(rawName)) {
      if (rawName) warnings.push('name 含不安全字符串，已回落为 id');
      name = id;
    }

    const tracksRaw = rawSpec.tracks == null ? [] : rawSpec.tracks;
    const colorsRaw = rawSpec.colors == null ? [] : rawSpec.colors;
    if (!Array.isArray(tracksRaw)) return fail('tracks 必须是数组');
    if (!Array.isArray(colorsRaw)) return fail('colors 必须是数组');
    if (tracksRaw.length > MAX_TRACKS) return fail('轨道超过 ' + MAX_TRACKS + ' 条');
    if (colorsRaw.length > MAX_COLORS) return fail('颜色关键帧超过 ' + MAX_COLORS + ' 个');

    let expandedTotal = 0;
    for (const track of tracksRaw) {
      if (!track || !Array.isArray(track.keyframes)) return fail('轨道结构非法');
      expandedTotal += track.keyframes.length === 1 ? 2 : track.keyframes.length;
    }
    if (expandedTotal > MAX_TOTAL_KEYFRAMES) {
      return fail('关键帧总数（按单帧展开后）超过 ' + MAX_TOTAL_KEYFRAMES + ' 个');
    }

    const seen = new Set();
    const normalizedTracks = [];
    for (let i = 0; i < tracksRaw.length; i++) {
      const track = tracksRaw[i];
      if (!track || typeof track !== 'object') return fail('轨道 ' + i + ' 不是对象');
      const property = track.property;
      if (typeof property !== 'string' || !PROPERTY_RANGES[property]) {
        return fail('轨道 ' + i + ' 的属性非法：' + String(property));
      }
      const longOnly = track.longOnly === true;
      const key = property + '|' + longOnly;
      if (seen.has(key)) {
        return fail('同一 (property, longOnly) 组合重复：' + property + (longOnly ? ' (longOnly)' : ''));
      }
      seen.add(key);
      const normalized = normalizeNumericKeyframes(track.keyframes, property);
      if (!normalized.ok) {
        errors.push('轨道 ' + property + '：' + normalized.errors.join('；'));
        return { ok: false, effect: null, errors, warnings };
      }
      warnings.push.apply(warnings, normalized.warnings);
      normalizedTracks.push({ property, keyframes: normalized.keyframes, longOnly });
    }

    const normalizedColors = normalizeColorKeyframes(colorsRaw);
    if (!normalizedColors.ok) {
      errors.push.apply(errors, normalizedColors.errors);
      return { ok: false, effect: null, errors, warnings };
    }
    warnings.push.apply(warnings, normalizedColors.warnings);

    const rawLongSyllableMs = rawSpec.longSyllableMs == null ? 700 : rawSpec.longSyllableMs;
    let longSyllableMs = isFiniteNumber(rawLongSyllableMs) ? Math.round(rawLongSyllableMs) : 700;
    const clampedLong = clamp(longSyllableMs, 200, 3000);
    if (clampedLong !== longSyllableMs) {
      warnings.push('longSyllableMs 已钳制到 ' + clampedLong);
      longSyllableMs = clampedLong;
    }

    const rawStagger = finiteOrZero(rawSpec.stagger);
    const stagger = clamp(rawStagger, 0, 0.5);
    if (stagger !== rawStagger) warnings.push('stagger 已钳制到 ' + stagger);

    const rawSweepBand = rawSpec.sweepBand == null ? 0.28 : finiteOrZero(rawSpec.sweepBand);
    const sweepBand = clamp(rawSweepBand, 0.05, 1);
    if (sweepBand !== rawSweepBand) warnings.push('sweepBand 已钳制到 ' + sweepBand);

    const rawUnsungDim = rawSpec.unsungDim == null ? 0.35 : finiteOrZero(rawSpec.unsungDim);
    const unsungDim = clamp(rawUnsungDim, 0.1, 0.6);
    if (unsungDim !== rawUnsungDim) warnings.push('unsungDim 已钳制到 ' + unsungDim);

    let glow = rawSpec.glow == null ? 'none' : rawSpec.glow;
    if (GLOW_MODES.indexOf(glow) < 0) {
      if (glow !== 'none') warnings.push('未知 glow "' + glow + '"，回落 none');
      glow = 'none';
    }

    let program = null;
    if (rawSpec.program != null) {
      const normalizedProgram = normalizeProgram(rawSpec.program);
      if (!normalizedProgram.ok) {
        errors.push.apply(errors, normalizedProgram.errors);
        return { ok: false, effect: null, errors, warnings };
      }
      program = normalizedProgram.program;
    }

    return {
      ok: true,
      effect: {
        id,
        name,
        longSyllableMs,
        stagger,
        sweep: rawSpec.sweep !== false,
        sweepBand,
        unsungDim,
        glow,
        tracks: normalizedTracks,
        colors: normalizedColors.keyframes,
        program
      },
      errors,
      warnings
    };
  }

  function normalizePack(rawPack) {
    const errors = [];
    const warnings = [];
    if (!rawPack || typeof rawPack !== 'object' || Array.isArray(rawPack)) {
      return { ok: false, errors: ['包不是对象'], warnings, entries: [], pack: null, bytesCompact: 0, bytesPretty: 0 };
    }
    if (rawPack.format !== FORMAT) errors.push('format 必须为 ' + FORMAT);
    if (rawPack.version !== PACK_VERSION) errors.push('version 必须为 1');
    const effects = rawPack.effects;
    if (!Array.isArray(effects)) {
      errors.push('effects 必须是数组');
      return { ok: false, errors, warnings, entries: [], pack: null, bytesCompact: 0, bytesPretty: 0 };
    }
    if (effects.length < 1 || effects.length > MAX_EFFECTS) {
      errors.push('effects 数量必须在 1..' + MAX_EFFECTS + ' 之间');
    }

    const entries = [];
    const validEffects = [];
    for (let i = 0; i < effects.length; i++) {
      const result = normalizeEffectSpec(effects[i]);
      entries.push({
        index: i,
        id: effects[i] && effects[i].id,
        ok: result.ok,
        effect: result.effect,
        errors: result.errors,
        warnings: result.warnings
      });
      if (result.ok) validEffects.push(result.effect);
      for (const warning of result.warnings) warnings.push('#' + (i + 1) + ' ' + warning);
    }

    const pack = {
      format: FORMAT,
      version: PACK_VERSION,
      name: typeof rawPack.name === 'string' ? rawPack.name.slice(0, 48) : '',
      author: typeof rawPack.author === 'string' ? rawPack.author.slice(0, 48) : '',
      effects: validEffects
    };
    const seenIds = new Set();
    for (const effect of validEffects) {
      if (seenIds.has(effect.id)) {
        warnings.push('效果 id 重复：' + effect.id + '（同 id 重导入会原位替换）');
      } else {
        seenIds.add(effect.id);
      }
    }
    const pretty = JSON.stringify(pack, null, 2);
    const compact = JSON.stringify(pack);
    const bytesPretty = bytesOf(pretty);
    const bytesCompact = bytesOf(compact);
    if (bytesPretty > MAX_PACK_BYTES) {
      warnings.push('导出文件（缩进格式）' + bytesPretty + ' 字节，超过 64 KiB；请用紧凑导出');
    }
    if (bytesCompact > MAX_PACK_BYTES) {
      errors.push('紧凑导出 ' + bytesCompact + ' 字节，仍超过 64 KiB 上限');
    }

    return {
      ok: errors.length === 0 && entries.every((entry) => entry.ok),
      errors,
      warnings,
      entries,
      pack,
      bytesPretty,
      bytesCompact
    };
  }

  // ---------------------------------------------------------------------------
  // Program assembler
  // ---------------------------------------------------------------------------

  const STACK_EFFECT = {
    const: 1, loadLocal: 1, storeLocal: -1, pop: -1, dup: 1, swap: 0,
    globalField: 1, lineField: 1, lineCount: 1, line: 0, lineAtField: 0,
    canvasField: 1, colorField: 1, wordCount: 1, word: 0, wordField: 0,
    add: -1, sub: -1, mul: -1, div: -1, mod: -1, neg: 0, sin: 0, cos: 0, abs: 0,
    min: -1, max: -1, clamp: -2, lerp: -2, pow: -1, sqrt: 0, floor: 0, round: 0,
    lt: -1, le: -1, gt: -1, ge: -1, eq: -1, ne: -1, and: -1, or: -1, not: 0,
    stringLength: 0, charAt: -1, substring: -2,
    jump: 0, jz: -1, jnz: -1,
    setColor: -1, setAlpha: -1, setTextSize: -1, setTextAlign: -1,
    setGradient: -6, clearGradient: 0, setShadow: -4, clearShadow: 0,
    setStrokeWidth: -1, setFill: 0, setStroke: 0,
    save: 0, restore: 0, translate: -2, rotate: -1, scale: -2, clipRect: -4,
    drawText: -3, drawRect: -4, drawCircle: -3, drawLine: -4, drawRoundRect: -6,
    drawOval: -4, drawArc: -6,
    pathMove: -2, pathLine: -2, pathQuad: -4, pathCubic: -6, pathClose: 0, drawPath: 0,
    measureText: 0, end: 0
  };

  const STACK_NEED = {
    storeLocal: 1, pop: 1, dup: 1, swap: 2, line: 1, lineAtField: 1, word: 1, wordField: 1,
    add: 2, sub: 2, mul: 2, div: 2, mod: 2, min: 2, max: 2, pow: 2,
    lt: 2, le: 2, gt: 2, ge: 2, eq: 2, ne: 2, and: 2, or: 2,
    neg: 1, sin: 1, cos: 1, abs: 1, sqrt: 1, floor: 1, round: 1, not: 1, stringLength: 1,
    clamp: 3, lerp: 3, charAt: 2, substring: 3,
    jz: 1, jnz: 1,
    setColor: 1, setAlpha: 1, setTextSize: 1, setTextAlign: 1, setGradient: 6, setShadow: 4,
    setStrokeWidth: 1, translate: 2, rotate: 1, scale: 2, clipRect: 4,
    drawText: 3, drawRect: 4, drawCircle: 3, drawLine: 4, drawRoundRect: 6,
    drawOval: 4, drawArc: 6, pathMove: 2, pathLine: 2, pathQuad: 4, pathCubic: 6,
    measureText: 1
  };

  function stripSourceComment(line) {
    let out = '';
    let quote = null;
    let escaped = false;
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (quote) {
        out += ch;
        if (escaped) escaped = false;
        else if (ch === '\\') escaped = true;
        else if (ch === quote) quote = null;
        continue;
      }
      if (ch === '"' || ch === "'") {
        quote = ch;
        out += ch;
        continue;
      }
      if (ch === ';') break;
      if (ch === '/' && line[i + 1] === '/') break;
      out += ch;
    }
    return out;
  }

  function tokenizeSource(line) {
    const tokens = [];
    let current = '';
    let quote = null;
    let escaped = false;
    const flush = () => {
      if (current) {
        tokens.push(current);
        current = '';
      }
    };
    for (let i = 0; i < line.length; i++) {
      const ch = line[i];
      if (quote) {
        current += ch;
        if (escaped) escaped = false;
        else if (ch === '\\') escaped = true;
        else if (ch === quote) quote = null;
        continue;
      }
      if (ch === '"' || ch === "'") {
        quote = ch;
        current += ch;
        continue;
      }
      if (/\s/.test(ch)) {
        flush();
        continue;
      }
      current += ch;
    }
    flush();
    return tokens;
  }

  function parseQuotedToken(token) {
    if (token.length < 2) return null;
    const quote = token[0];
    if ((quote !== '"' && quote !== "'") || token[token.length - 1] !== quote) return null;
    if (quote === '"') {
      try {
        return JSON.parse(token);
      } catch (error) {
        return null;
      }
    }
    return token.slice(1, -1).replace(/\\'/g, "'").replace(/\\\\/g, '\\');
  }

  function literalConstant(token) {
    if (typeof token !== 'string') return null;
    if (/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(token)) {
      const value = Number(token);
      if (!Number.isFinite(value)) return null;
      return { type: 'number', value: String(value) };
    }
    if (token === 'true' || token === 'false') {
      return { type: 'bool', value: token };
    }
    if ((token[0] === '"' && token[token.length - 1] === '"') ||
        (token[0] === "'" && token[token.length - 1] === "'")) {
      const value = parseQuotedToken(token);
      if (value === null) return null;
      return { type: 'string', value };
    }
    return null;
  }

  function constantLiteral(constant) {
    if (!constant) return '0';
    if (constant.type === 'string') return JSON.stringify(constant.value);
    if (constant.type === 'bool') return constant.value;
    return constant.value;
  }

  /**
   * 文本汇编器。语法：
   *   label:                 跳转目标
   *   push 0.5 / "text" / true   自动收集常量池
   *   const 3                显式常量下标（高级）
   *   loadLocal 0 / storeLocal 1
   *   lineField progress / wordField text / globalField time / canvasField height ...
   *   jump label / jz label / jnz label
   *   add / mul / drawText / end ...
   * 汇编后会做与 EffectProgram.kt 相同的白名单校验，并沿所有跳转路径做静态栈深检查。
   */
  function assemble(source, options) {
    const opts = options || {};
    const errors = [];
    const warnings = [];
    const maxLocals = opts.maxLocals == null ? 16 : opts.maxLocals;
    if (!Number.isInteger(maxLocals) || maxLocals < 0 || maxLocals > MAX_EFFECT_LOCALS) {
      errors.push('maxLocals 必须是 0..' + MAX_EFFECT_LOCALS + ' 的整数');
    }

    const constants = [];
    const constantIndex = new Map();
    const code = [];
    const labels = new Map();
    const fixups = [];
    const sourceLines = [];

    const addConstant = (constant) => {
      const key = constant.type + '\u0000' + constant.value;
      if (constantIndex.has(key)) return constantIndex.get(key);
      const index = constants.length;
      constants.push(constant);
      constantIndex.set(key, index);
      return index;
    };

    const lines = String(source == null ? '' : source).split(/\r?\n/);
    for (let lineIndex = 0; lineIndex < lines.length; lineIndex++) {
      const raw = lines[lineIndex];
      const trimmed = raw.trim();
      if (!trimmed || trimmed.startsWith(';') || trimmed.startsWith('//') || trimmed.startsWith('#')) continue;
      const tokens = tokenizeSource(stripSourceComment(raw));
      if (!tokens.length) continue;
      let cursor = 0;

      while (cursor < tokens.length && /^[A-Za-z_][A-Za-z0-9_.-]*:$/.test(tokens[cursor])) {
        const name = tokens[cursor].slice(0, -1);
        if (labels.has(name)) {
          errors.push('第 ' + (lineIndex + 1) + ' 行：标签 ' + name + ' 重复定义');
        } else {
          labels.set(name, code.length);
        }
        cursor++;
      }
      if (cursor >= tokens.length) continue;

      const op = tokens[cursor++];
      const rest = tokens.slice(cursor);
      const instructionLine = lineIndex + 1;

      if (!EFFECT_OPS.has(op) && op !== 'push') {
        errors.push('第 ' + instructionLine + ' 行：未知操作码 ' + op);
        continue;
      }

      if (op === 'push') {
        if (!rest.length) {
          errors.push('第 ' + instructionLine + ' 行：push 缺少字面量');
          continue;
        }
        const constant = literalConstant(rest[0]);
        if (!constant) {
          errors.push('第 ' + instructionLine + ' 行：无法解析字面量 ' + rest[0]);
          continue;
        }
        if (constant.type === 'string' && constant.value.length > 256) {
          errors.push('第 ' + instructionLine + ' 行：字符串常量超过 256 字符');
          continue;
        }
        if (constant.type === 'string' && containsUnsafeSchemaValue(constant.value)) {
          errors.push('第 ' + instructionLine + ' 行：字符串常量含不安全内容');
          continue;
        }
        const index = addConstant(constant);
        if (index >= MAX_EFFECT_CONSTANTS) {
          errors.push('第 ' + instructionLine + ' 行：常量超过 ' + MAX_EFFECT_CONSTANTS + ' 项');
          continue;
        }
        code.push({ op: 'const', arg: index });
        sourceLines.push(instructionLine);
        continue;
      }

      if (op === 'const') {
        const value = Number(rest[0]);
        if (!Number.isInteger(value)) {
          errors.push('第 ' + instructionLine + ' 行：const 需要整数常量下标');
          continue;
        }
        code.push({ op: 'const', arg: value });
        sourceLines.push(instructionLine);
        continue;
      }

      if (op === 'loadLocal' || op === 'storeLocal') {
        const value = Number(rest[0]);
        if (!Number.isInteger(value) || value < 0 || value >= maxLocals) {
          errors.push('第 ' + instructionLine + ' 行：' + op + ' 的槽号必须在 0..' + (maxLocals - 1) + ' 之间');
          continue;
        }
        code.push({ op, arg: value });
        sourceLines.push(instructionLine);
        continue;
      }

      if (op === 'jump' || op === 'jz' || op === 'jnz') {
        const target = rest[0];
        if (target == null) {
          errors.push('第 ' + instructionLine + ' 行：' + op + ' 缺少目标');
          continue;
        }
        const instruction = { op, arg: 0 };
        code.push(instruction);
        sourceLines.push(instructionLine);
        if (/^-?\d+$/.test(target)) {
          instruction.arg = Number(target);
        } else {
          fixups.push({ index: code.length - 1, label: target, line: instructionLine });
        }
        continue;
      }

      if (EFFECT_FIELD_OPS[op]) {
        const field = rest[0];
        if (!EFFECT_FIELD_OPS[op].has(field)) {
          errors.push('第 ' + instructionLine + ' 行：' + op + ' 的字段名非法 ' + String(field));
          continue;
        }
        code.push({ op, text: field });
        sourceLines.push(instructionLine);
        continue;
      }

      if (rest.length) {
        warnings.push('第 ' + instructionLine + ' 行：' + op + ' 不需要参数，已忽略 ' + rest.join(' '));
      }
      code.push({ op });
      sourceLines.push(instructionLine);
    }

    for (const fixup of fixups) {
      if (!labels.has(fixup.label)) {
        errors.push('第 ' + fixup.line + ' 行：找不到标签 ' + fixup.label);
        continue;
      }
      code[fixup.index].arg = labels.get(fixup.label);
    }

    if (code.length === 0) errors.push('程序没有指令');
    if (code.length > MAX_EFFECT_CODE) errors.push('指令超过 ' + MAX_EFFECT_CODE + ' 条');
    if (constants.length > MAX_EFFECT_CONSTANTS) errors.push('常量超过 ' + MAX_EFFECT_CONSTANTS + ' 项');

    // 跳转目标 / 常量 / 局部槽校验
    for (let i = 0; i < code.length; i++) {
      const instruction = code[i];
      if (instruction.op === 'const' &&
          (!Number.isInteger(instruction.arg) || instruction.arg < 0 || instruction.arg >= constants.length)) {
        errors.push('第 ' + sourceLines[i] + ' 行：常量下标越界');
      }
      if ((instruction.op === 'jump' || instruction.op === 'jz' || instruction.op === 'jnz') &&
          (!Number.isInteger(instruction.arg) || instruction.arg < 0 || instruction.arg > code.length)) {
        errors.push('第 ' + sourceLines[i] + ' 行：跳转目标越界');
      }
    }

    // 静态栈深检查（沿所有跳转路径）
    const visited = new Map();
    const queue = [{ pc: 0, depth: 0 }];
    while (queue.length) {
      const item = queue.shift();
      const pc = item.pc;
      const depth = item.depth;
      if (pc < 0 || pc > code.length) continue;
      if (pc === code.length) continue;
      if (visited.has(pc)) {
        if (visited.get(pc) !== depth) {
          errors.push('指令 ' + pc + ' 处的栈深不一致（' + visited.get(pc) + ' vs ' + depth + '）');
        }
        continue;
      }
      visited.set(pc, depth);
      const instruction = code[pc];
      const need = STACK_NEED[instruction.op] || 0;
      if (depth < need) {
        errors.push('第 ' + sourceLines[pc] + ' 行：' + instruction.op + ' 需要 ' + need + ' 个栈值，当前只有 ' + depth);
        continue;
      }
      const effect = STACK_EFFECT[instruction.op] || 0;
      const nextDepth = depth + effect;
      if (nextDepth < 0) {
        errors.push('第 ' + sourceLines[pc] + ' 行：' + instruction.op + ' 导致栈下溢');
        continue;
      }
      if (nextDepth > MAX_EFFECT_STACK) {
        errors.push('第 ' + sourceLines[pc] + ' 行：栈深超过 ' + MAX_EFFECT_STACK);
        continue;
      }
      if (instruction.op === 'jump') {
        queue.push({ pc: instruction.arg, depth: nextDepth });
      } else if (instruction.op === 'jz' || instruction.op === 'jnz') {
        queue.push({ pc: instruction.arg, depth: nextDepth });
        queue.push({ pc: pc + 1, depth: nextDepth });
      } else if (instruction.op !== 'end') {
        queue.push({ pc: pc + 1, depth: nextDepth });
      }
    }
    for (let i = 0; i < code.length; i++) {
      if (!visited.has(i)) warnings.push('第 ' + sourceLines[i] + ' 行：指令不可达');
    }

    const drawOps = code.filter((instruction) => EFFECT_DRAW_OPS.has(instruction.op)).length;
    const program = {
      version: EFFECT_PROGRAM_VERSION,
      constants,
      code,
      maxLocals
    };
    const estimatedBytes = estimateProgramBytes(program);
    if (estimatedBytes > MAX_EFFECT_PROGRAM_ESTIMATED_BYTES) {
      errors.push('程序估算字节 ' + estimatedBytes + ' 超过 12 KiB 预算');
    }

    return {
      ok: errors.length === 0,
      program: errors.length === 0 ? program : null,
      errors,
      warnings,
      stats: {
        instructions: code.length,
        constants: constants.length,
        drawOps,
        maxLocals,
        estimatedBytes,
        /** 每条指令对应的汇编源行号（1 基），调试面板用来回链源码。 */
        lineMap: sourceLines.slice()
      }
    };
  }

  /** 把程序 JSON 反汇编成文本（`push` 字面量，标签按跳转目标生成）。 */
  function disassemble(program) {
    if (!program || !Array.isArray(program.code)) return '';
    const code = program.code;
    const constants = program.constants || [];
    const targets = new Set();
    for (const instruction of code) {
      if ((instruction.op === 'jump' || instruction.op === 'jz' || instruction.op === 'jnz') &&
          Number.isInteger(instruction.arg)) {
        targets.add(instruction.arg);
      }
    }
    const labels = new Map();
    let labelIndex = 0;
    for (const target of Array.from(targets).sort((a, b) => a - b)) {
      labels.set(target, 'L' + labelIndex++);
    }

    const out = [];
    out.push('; 反汇编自当前程序：' + code.length + ' 条指令，' + constants.length +
      ' 个常量，maxLocals=' + (program.maxLocals == null ? 16 : program.maxLocals));
    for (let i = 0; i <= code.length; i++) {
      if (labels.has(i)) out.push(labels.get(i) + ':');
      if (i === code.length) break;
      const instruction = code[i];
      const op = instruction.op;
      if (op === 'const') {
        const constant = constants[instruction.arg];
        if (constant) {
          out.push('  push ' + constantLiteral(constant));
        } else {
          out.push('  const ' + instruction.arg + '  ; 常量下标无效');
        }
      } else if (EFFECT_FIELD_OPS[op]) {
        out.push('  ' + op + ' ' + (instruction.text == null ? '' : instruction.text));
      } else if (op === 'jump' || op === 'jz' || op === 'jnz') {
        const target = Number.isInteger(instruction.arg) ? instruction.arg : 0;
        out.push('  ' + op + ' ' + (labels.get(target) || String(target)));
      } else if (instruction.arg !== undefined && instruction.arg !== null) {
        out.push('  ' + op + ' ' + instruction.arg);
      } else {
        out.push('  ' + op);
      }
    }
    return out.join('\n');
  }

  // ---------------------------------------------------------------------------
  // Bytecode VM (EffectRuntime.kt)
  // ---------------------------------------------------------------------------

  function toInt32Trunc(value) {
    if (!Number.isFinite(value)) return 0;
    if (value > 2147483647) return 2147483647;
    if (value < -2147483648) return -2147483648;
    return Math.trunc(value);
  }

  function rint(value) {
    const floor = Math.floor(value);
    const diff = value - floor;
    if (diff < 0.5) return floor;
    if (diff > 0.5) return floor + 1;
    return floor % 2 === 0 ? floor : floor + 1;
  }

  function decimalNumber(text) {
    if (!/^[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?$/.test(text)) return null;
    const value = Number(text);
    return Number.isFinite(value) ? value : null;
  }

  /**
   * 执行一帧。ctx 字段与 EffectFrameContext 对齐；sink 需实现 EffectRenderSink。
   * 返回 { ok, error, steps, drawOps }；预算/栈错误与真机一样返回 ok=false。
   */
  /**
   * 执行一帧。ctx 字段与 EffectFrameContext 对齐；sink 需实现 EffectRenderSink。
   * options.trace=true 时记录逐指令追踪（最多 options.traceLimit 条，默认 2000），
   * 供编辑器调试面板使用；正常渲染不开启，开销可忽略。
   * 返回 { ok, error, steps, drawOps, trace, traceTruncated, maxStack, locals }。
   */
  function runProgram(program, ctx, sink, options) {
    const opts = options || {};
    const traceEnabled = opts.trace === true;
    const traceLimit = Math.max(1, Math.min(20000, Number.isInteger(opts.traceLimit) ? opts.traceLimit : 2000));
    if (!program || !Array.isArray(program.code)) {
      return {
        ok: false, error: '程序为空', steps: 0, drawOps: 0,
        trace: traceEnabled ? [] : null, traceTruncated: false, maxStack: 0, locals: []
      };
    }
    const code = program.code;
    const constants = program.constants || [];
    const maxLocals = Math.max(0, Math.min(MAX_EFFECT_LOCALS, toInt32Trunc(program.maxLocals == null ? 16 : program.maxLocals)));
    const locals = new Array(maxLocals).fill(null);
    const stack = [];
    const trace = traceEnabled ? [] : null;
    let pc = 0;
    let steps = 0;
    let drawOps = 0;
    let maxStack = 0;
    let failed = false;
    let error = null;

    const finish = (ok) => ({
      ok,
      error,
      steps,
      drawOps,
      trace,
      traceTruncated: traceEnabled && steps > trace.length,
      maxStack,
      locals: locals.slice()
    });

    const fail = (message) => {
      if (!failed) {
        failed = true;
        error = message;
      }
    };
    const push = (value) => {
      if (stack.length >= MAX_EFFECT_STACK) {
        fail('操作数栈溢出');
        return;
      }
      stack.push(value);
      if (stack.length > maxStack) maxStack = stack.length;
    };
    const pop = () => {
      if (stack.length === 0) {
        fail('操作数栈下溢');
        return null;
      }
      return stack.pop();
    };
    const number = (value) => {
      if (typeof value === 'number') return Number.isFinite(value) ? value : 0;
      if (typeof value === 'boolean') return value ? 1 : 0;
      if (typeof value === 'string') {
        const parsed = decimalNumber(value);
        return parsed === null ? 0 : parsed;
      }
      return 0;
    };
    const popNumber = () => {
      const value = number(pop());
      if (!Number.isFinite(value)) {
        fail('非有限数值');
        return 0;
      }
      return value;
    };
    const string = (value) => {
      if (typeof value === 'string') return value;
      if (typeof value === 'number') {
        if (Number.isInteger(value) && Math.abs(value) < 1e15) return String(value);
        return String(value);
      }
      if (typeof value === 'boolean') return value ? 'true' : 'false';
      return '';
    };
    const popString = () => string(pop());
    const bool = (value) => {
      if (typeof value === 'boolean') return value;
      if (typeof value === 'number') return value !== 0;
      if (typeof value === 'string') return value.length > 0;
      return false;
    };
    const popBool = () => bool(pop());
    const popColor = () => {
      const value = pop();
      if (typeof value === 'string') {
        const parsed = parseColor(value);
        return parsed === null ? (0xFFFFFFFF | 0) : parsed;
      }
      if (typeof value === 'boolean') return value ? 1 : 0;
      if (typeof value === 'number') return toInt32Trunc(value);
      return 0;
    };
    const valuesEqual = (a, b) => {
      if (typeof a === 'string' && typeof b === 'string') return a === b;
      if (typeof a === 'boolean' && typeof b === 'boolean') return a === b;
      return number(a) === number(b);
    };
    const compareValues = (a, b) => {
      if (typeof a === 'string' && typeof b === 'string') {
        return a < b ? -1 : a > b ? 1 : 0;
      }
      const na = number(a);
      const nb = number(b);
      return na < nb ? -1 : na > nb ? 1 : 0;
    };
    const binary = (block) => {
      const b = popNumber();
      const a = popNumber();
      push(block(a, b));
    };
    const lineAtField = (line, field) => {
      if (!line) return field === 'text' ? '' : 0;
      switch (field) {
        case 'text': return line.text == null ? '' : line.text;
        case 'start': return line.startMs || 0;
        case 'end': return line.endMs || 0;
        case 'progress': return line.progress || 0;
        case 'index': return line.index || 0;
        case 'x': return line.x || 0;
        case 'width': return line.width || 0;
        case 'baseline': return line.baseline || 0;
        case 'height': return line.height || 0;
        default: return 0;
      }
    };
    const wordAtField = (word, field) => {
      if (!word) {
        if (field === 'text') return '';
        if (field === 'long') return false;
        return 0;
      }
      switch (field) {
        case 'text': return word.text == null ? '' : word.text;
        case 'x': return word.x || 0;
        case 'width': return word.width || 0;
        case 'start': return word.startMs || 0;
        case 'end': return word.endMs || 0;
        case 'progress': return word.progress || 0;
        case 'index': return word.index || 0;
        case 'long': return word.longSyllable === true;
        case 'line': return word.lineIndex || 0;
        case 'baseline': return word.baseline || 0;
        default: return 0;
      }
    };

    while (pc < code.length) {
      if (failed) return finish(false);
      if (++steps > MAX_EFFECT_STEPS_PER_FRAME) {
        fail('超出每帧指令预算');
        return finish(false);
      }
      const instruction = code[pc];
      if (traceEnabled && trace.length < traceLimit) {
        trace.push({
          pc,
          op: instruction.op,
          arg: instruction.arg === undefined ? null : instruction.arg,
          text: instruction.text === undefined ? null : instruction.text,
          depth: stack.length
        });
      }
      pc++;
      if (EFFECT_DRAW_OPS.has(instruction.op) && ++drawOps > MAX_EFFECT_DRAW_OPS_PER_FRAME) {
        fail('超出每帧绘制预算');
        return finish(false);
      }

      switch (instruction.op) {
        case 'const': {
          const constant = constants[instruction.arg];
          if (!constant) push(0);
          else if ((constant.type || 'number') === 'string') push(constant.value);
          else if (constant.type === 'bool') push(constant.value === 'true');
          else push(decimalNumber(constant.value) == null ? 0 : decimalNumber(constant.value));
          break;
        }
        case 'loadLocal':
          push(locals[instruction.arg] == null ? null : locals[instruction.arg]);
          break;
        case 'storeLocal': {
          const slot = instruction.arg == null ? -1 : instruction.arg;
          if (slot < 0 || slot >= locals.length) fail('局部槽越界');
          else locals[slot] = pop();
          break;
        }
        case 'pop': pop(); break;
        case 'dup': {
          if (!stack.length) fail('dup 需要栈值');
          else push(stack[stack.length - 1]);
          break;
        }
        case 'swap': {
          if (stack.length < 2) fail('swap 需要两个栈值');
          else {
            const b = pop();
            const a = pop();
            push(b);
            push(a);
          }
          break;
        }
        case 'globalField': {
          switch (instruction.text) {
            case 'time': push(ctx.timeMs || 0); break;
            case 'wall': push(ctx.wallClockMs || 0); break;
            case 'baseline': push(ctx.baseline || 0); break;
            case 'textSize': push(ctx.textSize || 0); break;
            case 'blockWidth': push(ctx.blockWidth || 0); break;
            case 'blockHeight': push(ctx.blockHeight || 0); break;
            default: push(0);
          }
          break;
        }
        case 'lineField': {
          const current = ctx.lines && ctx.lines[ctx.lineIndex || 0];
          if (current) {
            push(lineAtField(current, instruction.text));
          } else {
            switch (instruction.text) {
              case 'text': push(ctx.lineText || ''); break;
              case 'start': push(ctx.lineStartMs || 0); break;
              case 'end': push(ctx.lineEndMs || 0); break;
              case 'progress': push(ctx.lineProgress || 0); break;
              case 'index': push(ctx.lineIndex || 0); break;
              default: push(0);
            }
          }
          break;
        }
        case 'lineCount': push(ctx.lines ? ctx.lines.length : 0); break;
        case 'line': {
          const index = toInt32Trunc(popNumber());
          push(ctx.lines && index >= 0 && index < ctx.lines.length ? { kind: 'line', index } : null);
          break;
        }
        case 'lineAtField': {
          const ref = pop();
          const line = ref && ref.kind === 'line' && ctx.lines ? ctx.lines[ref.index] : null;
          push(lineAtField(line, instruction.text));
          break;
        }
        case 'canvasField':
          push(instruction.text === 'width' ? (ctx.canvasWidth || 0) : instruction.text === 'height' ? (ctx.canvasHeight || 0) : 0);
          break;
        case 'colorField': {
          const colors = ctx.colors || {};
          switch (instruction.text) {
            case 'sung': push(colors.sung || 0); break;
            case 'unsung': push(colors.unsung || 0); break;
            case 'glow': push(colors.glow || 0); break;
            case 'accent': push(colors.accent || 0); break;
            default: push(0);
          }
          break;
        }
        case 'wordCount': push(ctx.words ? ctx.words.length : 0); break;
        case 'word': {
          const index = toInt32Trunc(popNumber());
          push(ctx.words && index >= 0 && index < ctx.words.length ? { kind: 'word', index } : null);
          break;
        }
        case 'wordField': {
          const ref = pop();
          const word = ref && ref.kind === 'word' && ctx.words ? ctx.words[ref.index] : null;
          push(wordAtField(word, instruction.text));
          break;
        }
        case 'add': binary((a, b) => a + b); break;
        case 'sub': binary((a, b) => a - b); break;
        case 'mul': binary((a, b) => a * b); break;
        case 'div': binary((a, b) => (b === 0 ? 0 : a / b)); break;
        case 'mod': binary((a, b) => (b === 0 ? 0 : a % b)); break;
        case 'neg': push(-popNumber()); break;
        case 'sin': push(Math.sin(popNumber())); break;
        case 'cos': push(Math.cos(popNumber())); break;
        case 'abs': push(Math.abs(popNumber())); break;
        case 'min': binary((a, b) => Math.min(a, b)); break;
        case 'max': binary((a, b) => Math.max(a, b)); break;
        case 'clamp': {
          const high = popNumber();
          const low = popNumber();
          const value = popNumber();
          push(clamp(value, Math.min(low, high), Math.max(low, high)));
          break;
        }
        case 'lerp': {
          const t = popNumber();
          const b = popNumber();
          const a = popNumber();
          push(a + (b - a) * clamp(t, 0, 1));
          break;
        }
        case 'pow': binary((a, b) => Math.pow(a, b)); break;
        case 'sqrt': push(Math.sqrt(Math.max(0, popNumber()))); break;
        case 'floor': push(Math.floor(popNumber())); break;
        case 'round': push(rint(popNumber())); break;
        case 'lt': {
          const b = pop();
          const a = pop();
          push(compareValues(a, b) < 0);
          break;
        }
        case 'le': {
          const b = pop();
          const a = pop();
          push(compareValues(a, b) <= 0);
          break;
        }
        case 'gt': {
          const b = pop();
          const a = pop();
          push(compareValues(a, b) > 0);
          break;
        }
        case 'ge': {
          const b = pop();
          const a = pop();
          push(compareValues(a, b) >= 0);
          break;
        }
        case 'eq': {
          const b = pop();
          const a = pop();
          push(valuesEqual(a, b));
          break;
        }
        case 'ne': {
          const b = pop();
          const a = pop();
          push(!valuesEqual(a, b));
          break;
        }
        case 'stringLength': push(popString().length); break;
        case 'charAt': {
          const index = toInt32Trunc(popNumber());
          const text = popString();
          push(index >= 0 && index < text.length ? text[index] : '');
          break;
        }
        case 'substring': {
          const end = toInt32Trunc(popNumber());
          const start = toInt32Trunc(popNumber());
          const text = popString();
          const from = clamp(start, 0, text.length);
          const to = clamp(end, from, text.length);
          push(text.slice(from, to));
          break;
        }
        case 'and': {
          const b = popBool();
          const a = popBool();
          push(a && b);
          break;
        }
        case 'or': {
          const b = popBool();
          const a = popBool();
          push(a || b);
          break;
        }
        case 'not': push(!popBool()); break;
        case 'jump': pc = instruction.arg == null ? pc : instruction.arg; break;
        case 'jz': if (!popBool()) pc = instruction.arg == null ? pc : instruction.arg; break;
        case 'jnz': if (popBool()) pc = instruction.arg == null ? pc : instruction.arg; break;
        case 'setColor': sink.setColor(popColor()); break;
        case 'setAlpha': sink.setAlpha(clamp(popNumber(), 0, 1)); break;
        case 'setTextSize': sink.setTextSize(clamp(popNumber(), 1, 512)); break;
        case 'setTextAlign': sink.setTextAlign(toInt32Trunc(popNumber())); break;
        case 'setGradient': {
          const color1 = popColor();
          const color0 = popColor();
          const y1 = popNumber();
          const x1 = popNumber();
          const y0 = popNumber();
          const x0 = popNumber();
          sink.setGradient(x0, y0, x1, y1, color0, color1);
          break;
        }
        case 'clearGradient': sink.clearGradient(); break;
        case 'setShadow': {
          const color = popColor();
          const dy = clamp(popNumber(), -256, 256);
          const dx = clamp(popNumber(), -256, 256);
          const radius = clamp(popNumber(), 0, 256);
          sink.setShadow(radius, dx, dy, color);
          break;
        }
        case 'clearShadow': sink.clearShadow(); break;
        case 'setStrokeWidth': sink.setStrokeWidth(clamp(popNumber(), 0, 128)); break;
        case 'setFill': sink.setFill(); break;
        case 'setStroke': sink.setStroke(); break;
        case 'save': sink.save(); break;
        case 'restore': sink.restore(); break;
        case 'translate': {
          const y = clamp(popNumber(), -10000, 10000);
          const x = clamp(popNumber(), -10000, 10000);
          sink.translate(x, y);
          break;
        }
        case 'rotate': sink.rotate(clamp(popNumber(), -7200, 7200)); break;
        case 'scale': {
          const sy = clamp(popNumber(), 0.01, 100);
          const sx = clamp(popNumber(), 0.01, 100);
          sink.scale(sx, sy);
          break;
        }
        case 'clipRect': {
          const h = popNumber();
          const w = popNumber();
          const y = popNumber();
          const x = popNumber();
          sink.clipRect(x, y, w, h);
          break;
        }
        case 'drawText': {
          const y = popNumber();
          const x = popNumber();
          sink.drawText(popString(), x, y);
          break;
        }
        case 'drawRect': {
          const h = popNumber();
          const w = popNumber();
          const y = popNumber();
          const x = popNumber();
          sink.drawRect(x, y, w, h);
          break;
        }
        case 'drawCircle': {
          const r = popNumber();
          const cy = popNumber();
          const cx = popNumber();
          sink.drawCircle(cx, cy, r);
          break;
        }
        case 'drawLine': {
          const y2 = popNumber();
          const x2 = popNumber();
          const y1 = popNumber();
          const x1 = popNumber();
          sink.drawLine(x1, y1, x2, y2);
          break;
        }
        case 'drawRoundRect': {
          const ry = Math.max(0, popNumber());
          const rx = Math.max(0, popNumber());
          const h = popNumber();
          const w = popNumber();
          const y = popNumber();
          const x = popNumber();
          sink.drawRoundRect(x, y, w, h, rx, ry);
          break;
        }
        case 'drawOval': {
          const h = popNumber();
          const w = popNumber();
          const y = popNumber();
          const x = popNumber();
          sink.drawOval(x, y, w, h);
          break;
        }
        case 'drawArc': {
          const sweep = popNumber();
          const start = popNumber();
          const h = popNumber();
          const w = popNumber();
          const y = popNumber();
          const x = popNumber();
          sink.drawArc(x, y, w, h, start, sweep);
          break;
        }
        case 'pathMove': {
          const y = popNumber();
          const x = popNumber();
          sink.pathMove(x, y);
          break;
        }
        case 'pathLine': {
          const y = popNumber();
          const x = popNumber();
          sink.pathLine(x, y);
          break;
        }
        case 'pathQuad': {
          const y = popNumber();
          const x = popNumber();
          const cy = popNumber();
          const cx = popNumber();
          sink.pathQuad(cx, cy, x, y);
          break;
        }
        case 'pathCubic': {
          const y = popNumber();
          const x = popNumber();
          const c2y = popNumber();
          const c2x = popNumber();
          const c1y = popNumber();
          const c1x = popNumber();
          sink.pathCubic(c1x, c1y, c2x, c2y, x, y);
          break;
        }
        case 'pathClose': sink.pathClose(); break;
        case 'drawPath': sink.drawPath(); break;
        case 'measureText': push(sink.measureText(popString())); break;
        case 'end': return finish(!failed);
        default:
          return finish(false);
      }
    }
    return finish(!failed);
  }

  // ---------------------------------------------------------------------------
  // Preview model: tokenization + timing (editor-only, not part of the pack)
  // ---------------------------------------------------------------------------

  function isCjkChar(ch) {
    const code = ch.codePointAt(0);
    return (code >= 0x3400 && code <= 0x9FFF) ||
      (code >= 0xF900 && code <= 0xFAFF) ||
      (code >= 0x3040 && code <= 0x30FF) ||
      (code >= 0xAC00 && code <= 0xD7AF);
  }

  /**
   * CJK 行级源按字合成词块，英文按词；空白只做分隔，但会记在 `spaceBefore` 上，
   * 预览布局据此补一个空格宽度（否则英文会连成一片）。
   * 返回 [{ text, cjk, spaceBefore }]。
   */
  function tokenizeLine(text) {
    const source = String(text == null ? '' : text);
    const tokens = [];
    let pendingSpace = false;
    let chunk = '';

    const pushToken = (value, cjk, firstInChunk) => {
      tokens.push({
        text: value,
        cjk,
        spaceBefore: firstInChunk ? pendingSpace : false
      });
    };

    const flushChunk = () => {
      if (!chunk) return;
      const chars = Array.from(chunk);
      if (!chars.some(isCjkChar)) {
        pushToken(chunk, false, true);
      } else {
        let buffer = '';
        let first = true;
        const flushBuffer = () => {
          if (!buffer) return;
          pushToken(buffer, false, first);
          buffer = '';
          first = false;
        };
        for (const ch of chars) {
          if (isCjkChar(ch)) {
            flushBuffer();
            pushToken(ch, true, first);
            first = false;
          } else {
            buffer += ch;
          }
        }
        flushBuffer();
      }
      chunk = '';
      pendingSpace = false;
    };

    for (const ch of Array.from(source)) {
      if (/\s/.test(ch)) {
        flushChunk();
        pendingSpace = true;
      } else {
        chunk += ch;
      }
    }
    flushChunk();
    return tokens;
  }

  function wordWeight(token) {
    if (token.cjk) return 1;
    const letters = Array.from(token.text).filter((ch) => /[A-Za-z0-9\u00C0-\u024F]/.test(ch)).length;
    return letters > 0 ? letters : 0.5;
  }

  /**
   * 构造预览时间轴：行首尾相接，词窗按字符权重估算（英文长词自然落入长音节）。
   * opts.maxLines 默认 4；opts.holdMs 末尾停留默认 700。
   */
  /**
   * LRC 解析：`[mm:ss.xx]` / `[mm:ss:xx]` / `[mm:ss]`，支持一行多时间标签、
   * `[offset:±ms]` 和增强 LRC 的 `<mm:ss.xx>` 字标签（字标签只从文本里剥掉，
   * 仍按行时间分配词窗）。返回按时间排序的 [{ timeMs, text }]。
   */
  function parseLrc(source) {
    const entries = [];
    let offsetMs = 0;
    const tagRe = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;
    for (const rawLine of String(source == null ? '' : source).split(/\r?\n/)) {
      const line = rawLine.trim();
      if (!line) continue;
      const offsetMatch = /^\[offset:([+-]?\d+)\]$/i.exec(line);
      if (offsetMatch) {
        offsetMs = Number(offsetMatch[1]) || 0;
        continue;
      }
      const times = [];
      let match;
      tagRe.lastIndex = 0;
      while ((match = tagRe.exec(line))) {
        const minutes = Number(match[1]);
        const seconds = Number(match[2]);
        const fraction = match[3] == null ? 0 : Number(match[3].padEnd(3, '0').slice(0, 3));
        times.push(minutes * 60000 + seconds * 1000 + fraction);
      }
      if (!times.length) continue;
      const lyric = line
        .replace(tagRe, '')
        .replace(/<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>/g, '')
        .trim();
      if (!lyric) continue;
      for (const time of times) entries.push({ timeMs: time + offsetMs, text: lyric });
    }
    entries.sort((a, b) => a.timeMs - b.timeMs);
    return entries;
  }

  /**
   * 构造预览时间轴。默认按字符权重估算词窗；传入 lineTimings 时（与文本行对齐的
   * [{startMs,endMs}]）按行时间窗分配词窗，词时长与字符权重成正比，末词吸收舍入。
   * opts.maxLines 默认 4；opts.holdMs 末尾停留默认 700。
   */
  function buildPreviewModel(text, opts) {
    const options = opts || {};
    const maxLines = options.maxLines || 4;
    const holdMs = options.holdMs == null ? 700 : options.holdMs;
    const lineTimings = Array.isArray(options.lineTimings) ? options.lineTimings : [];
    const linesText = String(text == null ? '' : text).split(/\r?\n/).slice(0, maxLines);
    const lines = [];
    const words = [];
    let cursor = 0;

    linesText.forEach((lineText, lineIndex) => {
      const tokens = tokenizeLine(lineText);
      const timing = lineTimings[lineIndex];
      const hasTiming = timing &&
        isFiniteNumber(timing.startMs) &&
        isFiniteNumber(timing.endMs) &&
        timing.endMs > timing.startMs;
      const lineStart = hasTiming ? timing.startMs : cursor;
      const lineWords = [];
      const weights = tokens.map(wordWeight);
      const totalWeight = weights.reduce((sum, weight) => sum + weight, 0) || 1;
      const span = hasTiming ? timing.endMs - timing.startMs : 0;
      let accumulated = 0;

      tokens.forEach((token, indexInLine) => {
        let startMs;
        let endMs;
        if (hasTiming) {
          startMs = Math.round(lineStart + (span * accumulated) / totalWeight);
          accumulated += weights[indexInLine];
          endMs = indexInLine === tokens.length - 1
            ? Math.round(timing.endMs)
            : Math.round(lineStart + (span * accumulated) / totalWeight);
        } else {
          const duration = Math.round(clamp(220 + 130 * (weights[indexInLine] - 1), 220, 1200));
          startMs = cursor;
          endMs = cursor + duration;
          cursor = endMs;
        }
        const word = {
          text: token.text,
          cjk: token.cjk,
          spaceBefore: token.spaceBefore === true,
          index: words.length,
          indexInLine,
          lineIndex,
          startMs,
          endMs,
          durationMs: Math.max(0, endMs - startMs)
        };
        words.push(word);
        lineWords.push(word);
      });

      const lineEnd = hasTiming ? Math.round(timing.endMs) : cursor;
      if (hasTiming) cursor = Math.max(cursor, lineEnd);
      lines.push({
        text: lineText,
        index: lineIndex,
        startMs: Math.round(lineStart),
        endMs: lineEnd,
        words: lineWords
      });
    });

    const lastEnd = lines.length ? Math.max.apply(null, lines.map((line) => line.endMs)) : 0;
    return {
      lines,
      words,
      totalMs: Math.max(1000, lastEnd + holdMs)
    };
  }

  function progressAt(startMs, endMs, positionMs) {
    const span = endMs - startMs;
    if (span <= 0) return positionMs >= endMs ? 1 : 0;
    return clamp((positionMs - startMs) / span, 0, 1);
  }

  // ---------------------------------------------------------------------------
  // Exports
  // ---------------------------------------------------------------------------

  return {
    FORMAT,
    PACK_VERSION,
    MAX_PACK_BYTES,
    MAX_EFFECTS,
    MAX_TRACKS,
    MAX_KEYFRAMES_PER_TRACK,
    MAX_TOTAL_KEYFRAMES,
    MAX_COLORS,
    MAX_NAME_CHARS,
    PROPERTY_RANGES,
    PROPERTY_DEFAULTS,
    EASINGS,
    GLOW_MODES,
    EFFECT_PROGRAM_VERSION,
    MAX_EFFECT_CONSTANTS,
    MAX_EFFECT_CODE,
    MAX_EFFECT_LOCALS,
    MAX_EFFECT_STACK,
    MAX_EFFECT_STEPS_PER_FRAME,
    MAX_EFFECT_DRAW_OPS_PER_FRAME,
    MAX_EFFECT_PROGRAM_ESTIMATED_BYTES,
    EFFECT_OPS,
    EFFECT_GLOBAL_FIELDS,
    EFFECT_LINE_FIELDS,
    EFFECT_CANVAS_FIELDS,
    EFFECT_COLOR_FIELDS,
    EFFECT_WORD_FIELDS,
    EFFECT_DRAW_OPS,

    clamp,
    round3,
    deepClone,
    bytesOf,
    containsUnsafeSchemaValue,
    parseColor,
    formatColor,
    colorChannels,
    lerpArgb,
    applyEasing,
    steadyTextAlpha,
    evaluateTrack,
    evaluateColor,
    evaluateEffect,
    normalizeNumericKeyframes,
    normalizeColorKeyframes,
    normalizeEffectSpec,
    normalizePack,
    normalizeProgram,
    estimateProgramBytes,
    assemble,
    disassemble,
    runProgram,
    tokenizeLine,
    wordWeight,
    parseLrc,
    buildPreviewModel,
    progressAt
  };
});
