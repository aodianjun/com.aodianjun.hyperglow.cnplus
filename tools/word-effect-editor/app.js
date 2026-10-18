/*
 * Word Effect Studio — editor UI.
 *
 * Three columns: effect list, live preview, inspector. The preview mirrors the
 * HyperGlow renderer (word transforms around the word center, sweep band with a
 * CLAMP gradient, unsung brightness step) and executes program effects in the
 * same sandbox VM semantics as EffectRuntime.kt.
 */
(function () {
  'use strict';

  const E = window.WEEngine;
  const T = window.WETemplates;
  if (!E || !T) {
    document.body.innerHTML = '<pre style="padding:24px;color:#f88">engine.js / templates.js 加载失败</pre>';
    return;
  }

  const $ = (selector, root) => (root || document).querySelector(selector);
  const $$ = (selector, root) => Array.from((root || document).querySelectorAll(selector));
  const FONT_STACKS = {
    sans: '"Noto Sans SC", "Microsoft YaHei", "PingFang SC", system-ui, -apple-system, "Segoe UI", sans-serif',
    serif: '"Noto Serif SC", "Songti SC", "SimSun", Georgia, serif',
    mono: '"JetBrains Mono", "Cascadia Code", Consolas, "Microsoft YaHei", monospace'
  };
  const STORAGE_KEY = 'hg-word-effect-studio.v1';
  const SVG_NS = 'http://www.w3.org/2000/svg';

  const PROPERTY_LABELS = {
    translateX: '位移 X (em)',
    translateY: '位移 Y (em)',
    scale: '缩放',
    rotate: '旋转 (°)',
    alpha: '透明度',
    skewX: '倾斜',
    glowRadius: '辉光半径 (em)',
    glowAlpha: '辉光强度',
    sweepProgress: '扫光进度'
  };

  const EASING_LABELS = {
    linear: 'linear 匀速',
    easeIn: 'easeIn 缓入',
    easeOut: 'easeOut 缓出',
    easeInOut: 'easeInOut 缓入缓出',
    easeInBack: 'easeInBack 回拉',
    easeOutBack: 'easeOutBack 冲过头',
    easeOutElastic: 'easeOutElastic 弹性',
    easeOutBounce: 'easeOutBounce 弹跳',
    stepEnd: 'stepEnd 段末跳变'
  };

  const THEMES = {
    dark: {
      bg: '#0b0e14',
      palette: { sung: '#FFFFFF', unsung: '#FFFFFF', glow: '#8AB4FF', accent: '#FFB86C' }
    },
    aod: {
      bg: '#000000',
      palette: { sung: '#FFFFFF', unsung: '#FFFFFF', glow: '#8AB4FF', accent: '#FFB86C' }
    },
    light: {
      bg: '#f2f3f7',
      palette: { sung: '#14161B', unsung: '#14161B', glow: '#3B82F6', accent: '#F59E0B' }
    }
  };

  /** 默认包保持官方样例的六个效果；模板下拉提供全部内置模板。 */
  const DEFAULT_DSL_KEYS = ['bounce', 'spin', 'wave', 'rainbow', 'glow_pulse', 'elastic'];

  /** 辉光预设：只组合规范里的 glow 策略 + glowAlpha/glowRadius 轨道。 */
  const GLOW_PRESETS = {
    steady: {
      glow: 'all',
      tracks: [
        { property: 'glowAlpha', keyframes: [{ at: 0, value: 1, easing: 'linear' }, { at: 1, value: 1, easing: 'linear' }] },
        { property: 'glowRadius', keyframes: [{ at: 0, value: 0.36, easing: 'linear' }, { at: 1, value: 0.36, easing: 'linear' }] }
      ]
    },
    breath: {
      glow: 'all',
      tracks: [
        { property: 'glowAlpha', keyframes: [{ at: 0, value: 0, easing: 'easeOut' }, { at: 0.5, value: 1, easing: 'easeIn' }, { at: 1, value: 0.2, easing: 'linear' }] },
        { property: 'glowRadius', keyframes: [{ at: 0, value: 0.1, easing: 'easeOut' }, { at: 0.5, value: 0.6, easing: 'easeIn' }, { at: 1, value: 0.2, easing: 'linear' }] }
      ]
    },
    singing: {
      glow: 'all',
      // 辉光窗口按 stagger 后的 t 求值；设 0 才能让窗口严格对齐每个词自己的 p。
      stagger: 0,
      tracks: [
        {
          property: 'glowAlpha',
          keyframes: [
            { at: 0, value: 0, easing: 'easeOut' },
            { at: 0.12, value: 1, easing: 'linear' },
            { at: 0.88, value: 1, easing: 'linear' },
            { at: 1, value: 0, easing: 'linear' }
          ]
        },
        {
          property: 'glowRadius',
          keyframes: [
            { at: 0, value: 0.36, easing: 'linear' },
            { at: 1, value: 0.36, easing: 'linear' }
          ]
        }
      ]
    },
    long_pulse: {
      glow: 'long',
      tracks: [
        { property: 'glowAlpha', longOnly: true, keyframes: [{ at: 0, value: 0, easing: 'easeOut' }, { at: 0.45, value: 1, easing: 'easeIn' }, { at: 1, value: 0.5, easing: 'linear' }] },
        { property: 'glowRadius', longOnly: true, keyframes: [{ at: 0, value: 0.1, easing: 'easeOut' }, { at: 0.45, value: 0.55, easing: 'easeIn' }, { at: 1, value: 0.3, easing: 'linear' }] }
      ]
    },
    fade_in: {
      glow: 'all',
      tracks: [
        { property: 'glowAlpha', keyframes: [{ at: 0, value: 0, easing: 'easeOut' }, { at: 0.45, value: 1, easing: 'linear' }, { at: 1, value: 1, easing: 'linear' }] },
        { property: 'glowRadius', keyframes: [{ at: 0, value: 0, easing: 'easeOut' }, { at: 0.45, value: 0.4, easing: 'linear' }, { at: 1, value: 0.4, easing: 'linear' }] }
      ]
    },
    flicker: {
      glow: 'all',
      tracks: [
        {
          property: 'glowAlpha',
          keyframes: [
            { at: 0, value: 0.2, easing: 'stepEnd' },
            { at: 0.15, value: 1, easing: 'linear' },
            { at: 0.3, value: 0.3, easing: 'stepEnd' },
            { at: 0.45, value: 1, easing: 'linear' },
            { at: 0.6, value: 0.4, easing: 'stepEnd' },
            { at: 0.8, value: 1, easing: 'linear' },
            { at: 1, value: 0.7, easing: 'linear' }
          ]
        },
        {
          property: 'glowRadius',
          keyframes: [
            { at: 0, value: 0.2, easing: 'linear' },
            { at: 0.5, value: 0.5, easing: 'linear' },
            { at: 1, value: 0.3, easing: 'linear' }
          ]
        }
      ]
    },
    off: { glow: 'none', tracks: [] }
  };

  const GLOW_PRESET_LABELS = {
    steady: '常亮',
    breath: '呼吸',
    singing: '仅正在唱',
    long_pulse: '长音节脉冲',
    fade_in: '渐入',
    flicker: '闪烁',
    off: '关闭'
  };

  const state = {
    pack: { format: E.FORMAT, version: 1, name: '', author: '', effects: [] },
    selected: 0,
    preview: {
      text: '夜色渐浓 我听见风在唱\nEvery word a little light',
      playing: true,
      positionMs: 0,
      speed: 1,
      loop: true,
      textSize: 44,
      guideBoxes: true,
      guideLong: true,
      glowEnabled: true,
      theme: 'dark',
      palette: Object.assign({}, THEMES.dark.palette),
      maxLines: 4,
      canvasHeight: 380,
      autoFit: true,
      fontFamily: 'sans',
      bgColor: null,
      lineTimings: null,
      lrcSource: ''
    },
    drawer: false,
    jsonEditing: false,
    lrcEntries: [],
    panels: { sidebarWidth: 244, inspectorWidth: 402, drawerHeight: 320 },
    dirty: true,
    needsDraw: true,
    previewCache: null,
    model: null,
    layout: null,
    layoutKey: '',
    ticksKey: '',
    programError: null,
    normalization: null,
    undo: [],
    redo: [],
    asmTimer: null,
    jsonTimer: null,
    saveTimer: null,
    drag: null,
    lastTs: 0,
    offscreen: null
  };

  // ---------------------------------------------------------------------------
  // Utilities
  // ---------------------------------------------------------------------------

  function esc(value) {
    return String(value == null ? '' : value)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  }

  function cssColor(argb, alphaMul) {
    const channels = E.colorChannels(argb | 0);
    const alpha = alphaMul == null ? channels.a / 255 : E.clamp(alphaMul, 0, 1);
    return 'rgba(' + channels.r + ', ' + channels.g + ', ' + channels.b + ', ' + alpha + ')';
  }

  /** 忽略颜色自带 alpha 的 RGB 字符串（Paint.alpha 会覆盖 color 的 alpha）。 */
  function cssRgb(argb) {
    const channels = E.colorChannels(argb | 0);
    return 'rgb(' + channels.r + ', ' + channels.g + ', ' + channels.b + ')';
  }

  /** 忽略颜色自带 alpha，用给定 alpha 的 rgba。 */
  function cssRgba(argb, alpha) {
    const channels = E.colorChannels(argb | 0);
    return 'rgba(' + channels.r + ', ' + channels.g + ', ' + channels.b + ', ' + E.clamp(alpha, 0, 1) + ')';
  }

  function hex6(hex) {
    const argb = E.parseColor(hex);
    if (argb == null) return '#ffffff';
    const c = E.colorChannels(argb);
    return '#' + [c.r, c.g, c.b].map((value) => value.toString(16).padStart(2, '0')).join('');
  }

  function formatNumber(value) {
    if (!Number.isFinite(value)) return '0';
    return String(E.round3(value));
  }

  function toast(message, kind) {
    const node = $('#toast');
    node.textContent = message;
    node.className = kind === 'err' ? 'show err' : 'show';
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { node.className = kind === 'err' ? 'err' : ''; }, 3200);
  }

  function slug(value) {
    return String(value || 'word-effect-pack')
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 40) || 'word-effect-pack';
  }

  function download(text, filename) {
    const blob = new Blob([text], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1500);
  }

  function currentEffect() {
    return state.pack.effects[state.selected] || null;
  }

  function publicEffect(effect) {
    return JSON.parse(JSON.stringify(effect, (key, value) => (key.startsWith('_') ? undefined : value)));
  }

  function publicPack() {
    return {
      format: E.FORMAT,
      version: E.PACK_VERSION,
      name: state.pack.name || '',
      author: state.pack.author || '',
      effects: state.pack.effects.map(publicEffect)
    };
  }

  function hydrateEffect(rawEffect) {
    const effect = JSON.parse(JSON.stringify(rawEffect || {}));
    effect.tracks = Array.isArray(effect.tracks) ? effect.tracks : [];
    effect.colors = Array.isArray(effect.colors) ? effect.colors : [];
    if (effect.program) {
      effect._asm = typeof effect._asm === 'string' && effect._asm
        ? effect._asm
        : E.disassemble(effect.program);
      effect._maxLocals = Number.isInteger(effect._maxLocals)
        ? effect._maxLocals
        : (Number.isInteger(effect.program.maxLocals) ? effect.program.maxLocals : 16);
    } else {
      effect._asm = typeof effect._asm === 'string' ? effect._asm : '';
      effect._maxLocals = 16;
    }
    effect._debug = null;
    return effect;
  }

  function uniqueId(base) {
    let stem = String(base || 'effect').toLowerCase().replace(/[^a-z0-9_]/g, '_').replace(/^_+|_+$/g, '');
    if (!stem) stem = 'effect';
    stem = stem.slice(0, 28);
    let candidate = stem;
    if (candidate === 'none') candidate = 'effect';
    if (!state.pack.effects.some((effect) => effect.id === candidate)) return candidate;
    for (let index = 2; index < 1000; index++) {
      const next = (stem + '_' + index).slice(0, 32);
      if (!state.pack.effects.some((effect) => effect.id === next)) return next;
    }
    return 'effect_' + Date.now().toString(36).slice(-6);
  }

  function markDirty() {
    state.dirty = true;
    state.needsDraw = true;
  }

  // ---------------------------------------------------------------------------
  // Undo / redo
  // ---------------------------------------------------------------------------

  function snapshot() {
    return JSON.stringify(
      { pack: state.pack, selected: state.selected },
      (key, value) => (key === '_debug' ? undefined : value)
    );
  }

  function pushUndo() {
    state.undo.push(snapshot());
    if (state.undo.length > 60) state.undo.shift();
    state.redo.length = 0;
    updateUndoButtons();
  }

  function undo() {
    if (!state.undo.length) return;
    state.redo.push(snapshot());
    restoreSnapshot(JSON.parse(state.undo.pop()));
  }

  function redo() {
    if (!state.redo.length) return;
    state.undo.push(snapshot());
    restoreSnapshot(JSON.parse(state.redo.pop()));
  }

  function restoreSnapshot(snap) {
    if (!snap || !snap.pack || !Array.isArray(snap.pack.effects) || !snap.pack.effects.length) return;
    state.pack = {
      format: E.FORMAT,
      version: E.PACK_VERSION,
      name: snap.pack.name || '',
      author: snap.pack.author || '',
      effects: snap.pack.effects.map(hydrateEffect)
    };
    state.selected = E.clamp(snap.selected || 0, 0, state.pack.effects.length - 1);
    state.model = null;
    state.layoutKey = '';
    state.preview.positionMs = 0;
    renderAll();
    updateUndoButtons();
    scheduleSave();
  }

  function updateUndoButtons() {
    $('#undoBtn').disabled = state.undo.length === 0;
    $('#redoBtn').disabled = state.redo.length === 0;
  }

  // ---------------------------------------------------------------------------
  // Sidebar
  // ---------------------------------------------------------------------------

  function renderSidebar() {
    const list = $('#effectList');
    list.innerHTML = state.pack.effects.map((effect, index) => {
      const normalized = E.normalizeEffectSpec(publicEffect(effect));
      const errorClass = normalized.ok ? '' : ' has-error';
      const selectedClass = index === state.selected ? ' selected' : '';
      const badge = effect.program ? '<span class="badge prog">PROG</span>' : '';
      return '<div class="effect-item' + selectedClass + errorClass + '" data-index="' + index + '" role="button" tabindex="0">' +
        '<span class="effect-dot"></span>' +
        '<span class="effect-meta">' +
          '<span class="effect-name">' + esc(effect.name || effect.id || '未命名') + '</span>' +
          '<span class="effect-id">' + esc(effect.id || '?') + (normalized.ok ? '' : ' · 结构错误') + '</span>' +
        '</span>' +
        badge +
        '<span class="effect-actions">' +
          '<span class="mini-btn" data-act="dup" data-index="' + index + '" title="复制效果">⧉</span>' +
          '<span class="mini-btn" data-act="del" data-index="' + index + '" title="删除效果">×</span>' +
        '</span>' +
      '</div>';
    }).join('');
    $('#effectCount').textContent = state.pack.effects.length;
  }

  function selectEffect(index) {
    if (index < 0 || index >= state.pack.effects.length) return;
    if (index === state.selected) return;
    state.selected = index;
    state.model = null;
    state.layoutKey = '';
    state.preview.positionMs = 0;
    state.dirty = true;
    renderAll();
    resetInspectorScroll();
  }

  // ---------------------------------------------------------------------------
  // Inspector
  // ---------------------------------------------------------------------------

  function propertyOptions(selected) {
    return Object.keys(E.PROPERTY_RANGES).map((property) =>
      '<option value="' + property + '"' + (property === selected ? ' selected' : '') + '>' +
      esc(PROPERTY_LABELS[property] || property) + '</option>'
    ).join('');
  }

  function easingOptions(selected) {
    return E.EASINGS.map((easing) =>
      '<option value="' + easing + '"' + (easing === selected ? ' selected' : '') + '>' +
      esc(EASING_LABELS[easing] || easing) + '</option>'
    ).join('');
  }

  function sliderRow(label, key, min, max, step, value, hint) {
    const numeric = Number(value);
    const safe = Number.isFinite(numeric) ? numeric : min;
    return '<div class="field">' +
      '<div class="field-head"><label>' + esc(label) + '</label>' +
      (hint ? '<span class="hint">' + esc(hint) + '</span>' : '') + '</div>' +
      '<div class="slider-row">' +
        '<input type="range" data-fx="' + key + '" min="' + min + '" max="' + max + '" step="' + step + '" value="' + safe + '" aria-label="' + esc(label) + '">' +
        '<input type="number" data-fx="' + key + '" min="' + min + '" max="' + max + '" step="' + step + '" value="' + safe + '" aria-label="' + esc(label) + '（数值）">' +
      '</div>' +
    '</div>';
  }

  function inspectorHeader(effect, errors) {
    const badge = effect.program
      ? '<span class="badge prog">PROGRAM</span>'
      : '<span class="badge">DSL</span>';
    const errorBadge = '<span class="badge err" id="inspErrorBadge"' +
      (errors.length ? '' : ' style="display:none"') + '>' + errors.length + ' 错误</span>';
    return '<div class="insp-head">' +
      '<div><div class="insp-title">' + esc(effect.name || effect.id) + '</div>' +
      '<div class="insp-sub">' + esc(effect.id || '?') + '</div></div>' +
      '<div class="insp-badges">' + badge + errorBadge + '</div>' +
    '</div>';
  }

  function identitySection(effect) {
    return '<div class="section">' +
      '<div class="section-title">标识</div>' +
      '<div class="field">' +
        '<div class="field-head"><label>id</label><span class="hint">^[a-z0-9_]{1,32}$，不能是 none</span></div>' +
        '<input class="input mono" data-fx="id" value="' + esc(effect.id || '') + '" spellcheck="false" aria-label="效果 id">' +
      '</div>' +
      '<div class="field">' +
        '<div class="field-head"><label>展示名</label><span class="hint">≤48 字符</span></div>' +
        '<input class="input" data-fx="name" value="' + esc(effect.name || '') + '" maxlength="48" aria-label="效果展示名">' +
      '</div>' +
      '<div class="inline-row">' +
        '<button class="btn small" data-act="move-up">↑ 上移</button>' +
        '<button class="btn small" data-act="move-down">↓ 下移</button>' +
        '<button class="btn small" data-act="dup-effect">复制</button>' +
        '<button class="btn small danger" data-act="del-effect">删除</button>' +
      '</div>' +
      '<div class="inline-row" style="margin-top:6px">' +
        '<button class="btn small" data-act="export-effect">导出此效果</button>' +
        '<button class="btn small" data-act="copy-effect">复制 JSON</button>' +
      '</div>' +
    '</div>';
  }

  function timingSection(effect) {
    const unsungAlpha = Math.round(E.steadyTextAlpha(effect.unsungDim == null ? 0.35 : effect.unsungDim) * 100);
    return '<div class="section">' +
      '<div class="section-title">时间与粒度</div>' +
      sliderRow('长音节阈值 (ms)', 'longSyllableMs', 200, 3000, 10, effect.longSyllableMs == null ? 700 : effect.longSyllableMs, 'durationMs ≥ 阈值') +
      sliderRow('词块滞后 stagger', 'stagger', 0, 0.5, 0.01, effect.stagger == null ? 0 : effect.stagger, '按词数自动封顶') +
      '<div class="field">' +
        '<label class="check"><input type="checkbox" data-fx="sweep"' + (effect.sweep !== false ? ' checked' : '') + '> 保留词内扫光带</label>' +
      '</div>' +
      sliderRow('扫光带宽 sweepBand', 'sweepBand', 0.05, 1, 0.01, effect.sweepBand == null ? 0.28 : effect.sweepBand, '占词宽比例') +
      sliderRow('未唱暗度 unsungDim', 'unsungDim', 0.1, 0.6, 0.01, effect.unsungDim == null ? 0.35 : effect.unsungDim, '实际亮度 ' + unsungAlpha + '%') +
    '</div>';
  }

  function glowSection(effect) {
    const glow = effect.glow || 'none';
    const activePreset = glowPresetKey(effect);
    const presetButton = (key) =>
      '<button class="btn small' + (activePreset === key ? ' active' : '') +
      '" data-act="glow-preset" data-preset="' + key + '" aria-pressed="' +
      (activePreset === key ? 'true' : 'false') + '">' + GLOW_PRESET_LABELS[key] + '</button>';
    return '<div class="section">' +
      '<div class="section-title">辉光 <span class="spacer"></span><span class="hint">' +
        (state.preview.glowEnabled ? '预览表面已开启' : '预览表面未开启') + '</span></div>' +
      (effect.program
        ? '<div class="hint" style="color:#c8a9ff;margin-bottom:7px">当前效果走程序档，DSL 辉光不参与渲染；移除程序后生效。</div>'
        : '') +
      '<div class="field">' +
        '<div class="field-head"><label>策略 glow</label><span class="hint">none / long / all</span></div>' +
        '<select class="input" data-fx="glow" aria-label="辉光策略">' +
          '<option value="none"' + (glow === 'none' ? ' selected' : '') + '>none 关闭</option>' +
          '<option value="long"' + (glow === 'long' ? ' selected' : '') + '>long 仅长音节</option>' +
          '<option value="all"' + (glow === 'all' ? ' selected' : '') + '>all 全部音节</option>' +
        '</select>' +
      '</div>' +
      '<div class="inline-row" style="margin-bottom:8px;flex-wrap:wrap">' +
        '<label class="check"><input type="checkbox" id="glowSurface"' + (state.preview.glowEnabled ? ' checked' : '') + '> 表面辉光（预览）</label>' +
        '<label class="check" style="margin-left:auto">辉光色 <input type="color" id="glowColor" value="' + hex6(state.preview.palette.glow) + '" aria-label="辉光颜色"></label>' +
      '</div>' +
      '<div class="glow-presets">' +
        presetButton('steady') +
        presetButton('breath') +
        presetButton('singing') +
        presetButton('long_pulse') +
        presetButton('fade_in') +
        presetButton('flicker') +
        presetButton('off') +
      '</div>' +
      '<div class="glow-preset-state" id="glowPresetState">' +
        (activePreset
          ? '当前预设：<b>' + esc(GLOW_PRESET_LABELS[activePreset]) + '</b>'
          : '当前：<b>自定义</b>（手动轨道）') +
      '</div>' +
      '<canvas id="glowPreview" class="glow-preview" aria-label="辉光双样本预览"></canvas>' +
      '<div class="glow-readout" id="glowReadout">—</div>' +
      '<div class="hint" style="margin-top:6px">long 只对 durationMs ≥ 长音节阈值的词生效。「仅正在唱」把 glowAlpha 两端压到 0，并把 stagger 设为 0，只有 0 &lt; p &lt; 1 的词会亮；需要错位波浪可再手动调回 stagger（辉光窗口会跟着错位）。词表的辉光列表示策略命中，实际亮度看 glowAlpha。</div>' +
    '</div>';
  }

  function keyframeRow(track, keyframe, trackIndex, keyframeIndex) {
    const range = E.PROPERTY_RANGES[track.property] || [-1, 1];
    const count = track.keyframes.length;
    return '<div class="kf-row">' +
      '<span class="kf-index">' + (keyframeIndex + 1) + '</span>' +
      '<input type="number" data-kf="at" data-ti="' + trackIndex + '" data-ki="' + keyframeIndex + '" min="0" max="1" step="0.01" value="' + formatNumber(keyframe.at) + '" title="词进度 at (0..1)" aria-label="词进度 at">' +
      '<input type="number" data-kf="value" data-ti="' + trackIndex + '" data-ki="' + keyframeIndex + '" min="' + range[0] + '" max="' + range[1] + '" step="0.01" value="' + formatNumber(keyframe.value) + '" title="值（' + range[0] + '..' + range[1] + '）" aria-label="关键帧值">' +
      '<select data-kf="easing" data-ti="' + trackIndex + '" data-ki="' + keyframeIndex + '" aria-label="缓动">' + easingOptions(keyframe.easing || 'linear') + '</select>' +
      '<button class="kf-del" data-act="del-kf" data-ti="' + trackIndex + '" data-ki="' + keyframeIndex + '"' + (count <= 1 ? ' disabled' : '') + ' title="删除关键帧">×</button>' +
    '</div>';
  }

  function trackCard(track, trackIndex) {
    const keyframes = Array.isArray(track.keyframes) ? track.keyframes : [];
    return '<div class="track-card" data-track-card="' + trackIndex + '">' +
      '<div class="track-head">' +
        '<select data-track-prop="' + trackIndex + '">' + propertyOptions(track.property) + '</select>' +
        '<label class="check" title="只对 durationMs ≥ longSyllableMs 的词块生效">' +
          '<input type="checkbox" data-track-long="' + trackIndex + '"' + (track.longOnly ? ' checked' : '') + '> 长' +
        '</label>' +
        '<button class="kf-del" data-act="del-track" data-ti="' + trackIndex + '" title="删除轨道">×</button>' +
      '</div>' +
      '<svg class="curve" data-curve="' + trackIndex + '" viewBox="0 0 320 96" preserveAspectRatio="none"></svg>' +
      '<div class="kf-list">' +
        (keyframes.length
          ? keyframes.map((keyframe, keyframeIndex) => keyframeRow(track, keyframe, trackIndex, keyframeIndex)).join('')
          : '<div class="hint">没有关键帧，轨道会被拒绝</div>') +
      '</div>' +
      '<div class="track-foot">' +
        '<button class="btn small" data-act="add-kf" data-ti="' + trackIndex + '">+ 关键帧</button>' +
        '<button class="btn small" data-act="add-kf-at-t" data-ti="' + trackIndex + '" title="在当前预览词的 t 处加帧">在 t 加帧</button>' +
        '<span class="hint">点曲线加点 · 拖动改值 · 右键删点</span>' +
      '</div>' +
    '</div>';
  }

  function tracksSection(effect) {
    const existing = new Set(effect.tracks.map((track) => track.property + '|' + (track.longOnly === true)));
    const available = Object.keys(E.PROPERTY_RANGES).filter((property) => !existing.has(property + '|false'));
    return '<div class="section">' +
      '<div class="section-title">数值轨道 <span class="spacer"></span><span class="hint">' + effect.tracks.length + ' / ' + E.MAX_TRACKS + '</span></div>' +
      effect.tracks.map(trackCard).join('') +
      '<div class="add-track">' +
        '<select id="newTrackProperty" aria-label="新轨道属性">' +
          (available.length
            ? available.map((property) => '<option value="' + property + '">' + esc(PROPERTY_LABELS[property]) + '</option>').join('')
            : '<option value="">没有可添加的属性</option>') +
        '</select>' +
        '<button class="btn small" data-act="add-track"' + (available.length ? '' : ' disabled') + '>添加轨道</button>' +
      '</div>' +
      '<div class="hint" style="margin-top:6px">同一属性允许一条普通轨道 + 一条「长」轨道；导出时端点自动拉伸到 0 / 1。</div>' +
    '</div>';
  }

  function colorBar(effect) {
    const normalized = E.normalizeColorKeyframes(effect.colors || []);
    const colors = normalized.ok ? normalized.keyframes : [];
    if (!colors.length) {
      return '<div class="color-bar empty">未设置颜色轨道，沿用表面调色板</div>';
    }
    const stops = [];
    for (let index = 0; index <= 24; index++) {
      const t = index / 24;
      const argb = E.evaluateColor(colors, t);
      stops.push(cssRgb(argb == null ? 0xFFFFFFFF : argb) + ' ' + (t * 100).toFixed(1) + '%');
    }
    return '<div class="color-bar" style="background:linear-gradient(90deg, ' + stops.join(', ') + ')"></div>';
  }

  function colorRow(keyframe, colorIndex) {
    return '<div class="kf-row color-row">' +
      '<span class="kf-index">' + (colorIndex + 1) + '</span>' +
      '<input type="color" data-color="color" data-ci="' + colorIndex + '" value="' + hex6(keyframe.color) + '" aria-label="颜色">' +
      '<input type="text" data-color="hex" data-ci="' + colorIndex + '" value="' + esc(keyframe.color) + '" spellcheck="false" title="#RRGGBB 或 #AARRGGBB" aria-label="颜色十六进制">' +
      '<input type="number" data-color="at" data-ci="' + colorIndex + '" min="0" max="1" step="0.01" value="' + formatNumber(keyframe.at) + '" title="词进度 at" aria-label="颜色关键帧 at">' +
      '<select data-color="easing" data-ci="' + colorIndex + '" aria-label="颜色缓动">' + easingOptions(keyframe.easing || 'linear') + '</select>' +
      '<button class="kf-del" data-act="del-color" data-ci="' + colorIndex + '" title="删除颜色帧">×</button>' +
    '</div>';
  }

  function colorsSection(effect) {
    return '<div class="section">' +
      '<div class="section-title">颜色轨道 <span class="spacer"></span><span class="hint">' + effect.colors.length + ' / ' + E.MAX_COLORS + '</span></div>' +
      colorBar(effect) +
      '<div class="kf-list">' +
        (effect.colors.length
          ? effect.colors.map(colorRow).join('')
          : '<div class="hint">为空时沿用已唱 / 未唱调色板</div>') +
      '</div>' +
      '<div class="track-foot">' +
        '<button class="btn small" data-act="add-color"' + (effect.colors.length >= E.MAX_COLORS ? ' disabled' : '') + '>+ 颜色帧</button>' +
        '<span class="hint">相邻颜色按 RGB 线性插值</span>' +
      '</div>' +
    '</div>';
  }

  function asmStatsText(effect) {
    const program = effect.program;
    if (!program) return '';
    const stats = effect._asmStats || {
      instructions: program.code.length,
      constants: (program.constants || []).length,
      drawOps: program.code.filter((instruction) => E.EFFECT_DRAW_OPS.has(instruction.op)).length,
      maxLocals: program.maxLocals,
      estimatedBytes: E.estimateProgramBytes(program)
    };
    return stats.instructions + ' 指令 · ' + stats.constants + ' 常量 · ' + stats.drawOps +
      ' 绘制 · ' + stats.maxLocals + ' 槽 · ' + (stats.estimatedBytes / 1024).toFixed(1) + ' KiB';
  }

  function programSection(effect) {
    if (!effect.program) {
      return '<div class="section">' +
        '<div class="section-title">程序档 <span class="spacer"></span><span class="hint">沙箱字节码</span></div>' +
        '<div class="hint" style="line-height:1.6;margin-bottom:8px">程序每帧执行一次，自己绘制整块歌词；<b style="color:#c8a9ff">program 非空时优先于 tracks / colors</b>。</div>' +
        '<div class="add-track">' +
          '<select id="programTemplate" aria-label="程序模板">' +
            T.programTemplateList().map((template) =>
              '<option value="' + template.key + '">' + esc(template.name) + '</option>'
            ).join('') +
          '</select>' +
          '<button class="btn small" data-act="add-program">添加程序档</button>' +
        '</div>' +
        '<div class="hint" id="programTemplateHint">' + esc(T.PROGRAM.hello.description || '') + '</div>' +
      '</div>';
    }
    const errors = (function () {
      if (Array.isArray(effect._asmErrors) && effect._asmErrors.length) return effect._asmErrors;
      if (effect.program) {
        const check = E.normalizeProgram(effect.program);
        if (!check.ok) return check.errors;
      }
      return [];
    })();
    const warnings = Array.isArray(effect._asmWarnings) ? effect._asmWarnings : [];
    const statusClass = errors.length ? 'bad' : 'ok';
    const statusText = errors.length
      ? '汇编失败：' + errors.length + ' 个错误（预览使用上一次成功汇编的程序）'
      : '✓ 可以运行 · ' + asmStatsText(effect);
    const messageHtml = (error) => {
      const lineMatch = /第 (\d+) 行/.exec(error);
      return '<li' + (lineMatch ? ' data-line="' + lineMatch[1] + '"' : '') + '>' + esc(error) + '</li>';
    };
    const messages = errors.map(messageHtml)
      .concat(warnings.map((warning) => '<li class="warn">' + esc(warning) + '</li>'))
      .join('');
    const templateKey = programTemplateKey(effect);
    const selectedTemplate = templateKey || 'hello';
    const templateHint = templateKey
      ? (T.PROGRAM[templateKey].description || '')
      : '当前程序是自定义的；选一个模板可以替换。';
    return '<div class="section">' +
      '<div class="section-title">程序档 <span class="spacer"></span><span class="badge prog">PROGRAM</span></div>' +
      '<div class="program-note"><span>⚙</span><div><b>程序优先</b>：当前效果走 VM，tracks / colors 不参与渲染。程序负责绘制整块歌词（含换行后的每一行）；多行块请用 <code>wordField baseline</code>，不要用 <code>globalField baseline</code>（早期单行示例除外）。</div></div>' +
      programBaselineLint(effect) +
      '<details class="program-help">' +
        '<summary>新手说明 · 最小程序 / 常用字段</summary>' +
        '<div class="program-help-body">' +
          '<p>程序每帧执行一次，负责把整块歌词画出来。先选一个模板跑通，再改数字最省事。</p>' +
          '<p>最小结构：遍历 <code>wordCount</code>，取 <code>word(i)</code> 的 <code>text / x / baseline</code>，设颜色和透明度，再 <code>drawText</code>，最后 <code>end</code>。</p>' +
          '<pre class="program-snippet">' + esc(T.PROGRAM.loop.source) + '</pre>' +
          '<div class="program-help-fields">' +
            '<span><code>wordField text/x/width/baseline/progress</code> 词文本与几何</span>' +
            '<span><code>colorField sung/unsung/glow/accent</code> 调色板</span>' +
            '<span><code>drawText text x baseline</code> 画词</span>' +
            '<span><code>save / restore</code> 成对包住逐词变换</span>' +
          '</div>' +
          '<div class="hint">完整字段与指令见 docs/WORD_EFFECT_PACKS.md §10；也可以直接选下面的模板。</div>' +
        '</div>' +
      '</details>' +
      '<div class="program-template-row">' +
        '<select id="programTemplateReplace" aria-label="程序模板">' +
          T.programTemplateList().map((template) =>
            '<option value="' + template.key + '"' + (template.key === selectedTemplate ? ' selected' : '') + '>' +
            esc(template.name) + '</option>'
          ).join('') +
        '</select>' +
        '<button class="btn small" data-act="replace-program-template">用此模板替换</button>' +
      '</div>' +
      '<div class="hint" id="programTemplateReplaceHint">' + esc(templateHint) + '</div>' +
      '<div class="field">' +
        '<div class="field-head"><label>汇编源</label><span class="hint">push 自动收集常量 · label: 跳转</span></div>' +
        '<div class="asm-wrap">' +
          '<div class="asm-gutter" id="asmGutter" aria-hidden="true"></div>' +
          '<textarea class="asm-editor" id="asmEditor" spellcheck="false" aria-label="程序汇编源">' + esc(effect._asm || '') + '</textarea>' +
        '</div>' +
      '</div>' +
      '<div class="inline-row" style="margin-bottom:8px;flex-wrap:wrap">' +
        '<label class="hint">maxLocals</label>' +
        '<input class="input mono" style="width:64px" type="number" min="0" max="24" step="1" id="asmLocals" value="' + (effect._maxLocals == null ? 16 : effect._maxLocals) + '" aria-label="maxLocals">' +
        '<button class="btn small" data-act="debug-frame">调试当前帧</button>' +
        '<button class="btn small" data-act="disasm">反汇编当前程序</button>' +
        '<button class="btn small danger" data-act="remove-program">移除程序</button>' +
      '</div>' +
      '<div id="asmStatus" class="asm-status ' + statusClass + '">' + esc(statusText) + '</div>' +
      (messages ? '<ul class="msg-list" id="asmErrors">' + messages + '</ul>' : '') +
      '<div id="asmDebug">' + renderAsmDebug(effect) + '</div>' +
    '</div>';
  }

  function programTemplateKey(effect) {
    const source = (effect._asm || '').trim();
    for (const key of Object.keys(T.PROGRAM)) {
      if (T.PROGRAM[key].source.trim() === source) return key;
    }
    return null;
  }

  function programContextSection(effect) {
    return '<div class="section">' +
      '<div class="section-title">程序上下文</div>' +
      sliderRow('长音节阈值 (ms)', 'longSyllableMs', 200, 3000, 10,
        effect.longSyllableMs == null ? 700 : effect.longSyllableMs, 'word.long 用') +
      '<div class="hint" style="line-height:1.6">程序每帧拿到整块歌词、行/词时间、画布尺寸和调色板；字段与绘制原语见 docs/WORD_EFFECT_PACKS.md §10.2。</div>' +
    '</div>';
  }

  function dslBackupSection(effect) {
    return '<details class="dsl-backup">' +
      '<summary>DSL 轨道（程序优先，不参与渲染）</summary>' +
      timingSection(effect) +
      glowSection(effect) +
      tracksSection(effect) +
      colorsSection(effect) +
    '</details>';
  }

  function programBaselineLint(effect) {
    const source = effect._asm || '';
    if (!/globalField\s+baseline/.test(source)) return '';
    const canFix = !!fixBaselineSource(source);
    return '<div class="program-warn">' +
      '<div><b>多行基线</b>：程序里用了 <code>globalField baseline</code>。多行歌词会把后面的行叠到首行；多行块请用 <code>wordField baseline</code>（官方早期单行示例可忽略）。</div>' +
      (canFix ? '<button class="btn small" data-act="fix-baseline">修正多行基线</button>' : '') +
    '</div>';
  }

  function fixBaselineSource(source) {
    const lines = String(source || '').split(/\r?\n/);
    const output = [];
    let changed = false;
    for (let index = 0; index < lines.length; index++) {
      const line = lines[index];
      if (line.trim() !== 'globalField baseline') {
        output.push(line);
        continue;
      }
      const indent = line.match(/^\s*/)[0];
      const next = (lines[index + 1] || '').trim();
      // cy = baseline - textSize * 0.35 模式：后面紧跟 globalField textSize。
      if (next === 'globalField textSize') {
        let local = null;
        for (let back = index - 1; back >= Math.max(0, index - 12); back--) {
          const refMatch = /^loadLocal\s+(\d+)$/.exec(lines[back].trim());
          if (refMatch) {
            local = refMatch[1];
            break;
          }
        }
        if (local != null) {
          output.push(indent + 'loadLocal ' + local);
          output.push(indent + 'wordField baseline');
          changed = true;
          continue;
        }
      }
      // drawText 的基线：往前找 wordField x 对应的 loadLocal。
      let local = null;
      for (let back = index - 1; back >= Math.max(0, index - 10); back--) {
        const fieldMatch = /^wordField\s+(\w+)$/.exec(lines[back].trim());
        if (!fieldMatch) continue;
        if (fieldMatch[1] === 'x' || fieldMatch[1] === 'baseline') {
          for (let ref = back - 1; ref >= Math.max(0, back - 4); ref--) {
            const refMatch = /^loadLocal\s+(\d+)$/.exec(lines[ref].trim());
            if (refMatch) {
              local = refMatch[1];
              break;
            }
          }
        }
        break;
      }
      if (local == null) {
        output.push(line);
        continue;
      }
      output.push(indent + 'loadLocal ' + local);
      output.push(indent + 'wordField baseline');
      changed = true;
    }
    return changed ? output.join('\n') : null;
  }

  function issuesSection(errors, warnings) {
    const items = errors.map((error) => '<li>' + esc(error) + '</li>')
      .concat(warnings.map((warning) => '<li class="warn">' + esc(warning) + '</li>'));
    return '<div class="section" id="validationSection">' +
      '<div class="section-title">校验 <span class="spacer"></span><span class="hint">' +
        (errors.length ? errors.length + ' 错误' : '结构通过') + '</span></div>' +
      (items.length ? '<ul class="msg-list">' + items.join('') + '</ul>' : '<ul class="msg-list"><li class="ok">✓ 结构合法，导出时只做数值钳制</li></ul>') +
    '</div>';
  }

  function formatVmValue(value) {
    if (value === null || value === undefined) return 'null';
    if (typeof value === 'string') return JSON.stringify(value);
    if (typeof value === 'number') return Number.isInteger(value) ? String(value) : value.toFixed(3);
    if (typeof value === 'boolean') return value ? 'true' : 'false';
    if (value && value.kind) return value.kind + '[' + value.index + ']';
    return String(value);
  }

  function renderAsmDebug(effect) {
    const debug = effect._debug;
    if (!debug) {
      return '<div class="asm-debug-hint">点「调试当前帧」查看指令追踪、栈深与局部槽。</div>';
    }
    const trace = Array.isArray(debug.trace) ? debug.trace : [];
    const shown = trace.slice(0, 120);
    const rows = shown.map((entry, index) => {
      const sourceLine = debug.lineMap && debug.lineMap[entry.pc] != null
        ? debug.lineMap[entry.pc]
        : '—';
      const operand = entry.op === 'const'
        ? '#' + entry.arg
        : (entry.text != null ? entry.text : (entry.arg != null ? String(entry.arg) : ''));
      return '<tr><td>' + (index + 1) + '</td><td>' + entry.pc + '</td><td>' + sourceLine + '</td>' +
        '<td>' + esc(entry.op) + '</td><td>' + esc(operand) + '</td><td>' + entry.depth + '</td></tr>';
    }).join('');
    const locals = (debug.locals || []).map((value, index) =>
      '<span class="local"><b>' + index + '</b> ' + esc(formatVmValue(value)) + '</span>'
    ).join('');
    return '<div class="asm-debug">' +
      '<div class="debug-stats">' +
        '<span class="badge ' + (debug.ok ? 'ok' : 'err') + '">' + (debug.ok ? '成功' : '失败') + '</span>' +
        '<span>步数 ' + debug.steps + '</span>' +
        '<span>绘制 ' + debug.drawOps + '</span>' +
        '<span>最大栈 ' + debug.maxStack + '</span>' +
        '<span>位置 ' + (debug.positionMs / 1000).toFixed(2) + 's</span>' +
        '<span>词 ' + esc(debug.word || '—') + '</span>' +
      '</div>' +
      (debug.error ? '<div class="debug-error">' + esc(debug.error) + '</div>' : '') +
      '<div class="debug-locals">' + (locals || '<span class="hint">无局部槽</span>') + '</div>' +
      (debug.traceTruncated ? '<div class="hint">追踪已截断（只记录前 500 条）</div>' : '') +
      '<div class="debug-scroll"><table class="debug-table"><thead><tr>' +
        '<th>#</th><th>pc</th><th>源行</th><th>op</th><th>参数</th><th>栈深</th>' +
      '</tr></thead><tbody>' + rows + '</tbody></table></div>' +
    '</div>';
  }

  function debugCurrentFrame(effect) {
    if (!effect.program) return;
    if (!effect._asmLineMap && effect._asm) {
      const assembled = E.assemble(effect._asm, {
        maxLocals: effect._maxLocals == null ? 16 : effect._maxLocals
      });
      if (assembled.ok) effect._asmLineMap = assembled.stats.lineMap;
    }
    const wrap = $('#previewWrap');
    const cssWidth = Math.max(200, Math.round(wrap.clientWidth || 720));
    const cssHeight = Math.max(240, Math.round(wrap.clientHeight || 380));
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const layout = getLayout(cssWidth, cssHeight);
    const positionMs = E.clamp(state.preview.positionMs, 0, layout.totalMs);
    const palette = paletteArgb();
    const frameContext = buildProgramContext(layout, positionMs, cssWidth, cssHeight, palette, effect);
    const temp = document.createElement('canvas');
    temp.width = Math.max(1, Math.round(cssWidth * dpr));
    temp.height = Math.max(1, Math.round(cssHeight * dpr));
    const tempContext = temp.getContext('2d');
    tempContext.setTransform(dpr, 0, 0, dpr, 0, 0);
    const sink = new CanvasSink(tempContext, palette, layout.textSize, fontStack());
    const result = E.runProgram(effect.program, frameContext, sink, { trace: true, traceLimit: 500 });
    sink.restoreAll();
    const current = currentWordState();
    effect._debug = Object.assign({}, result, {
      positionMs,
      word: current ? current.word.text : '',
      lineMap: effect._asmLineMap || null
    });
    renderInspector();
  }

  /** 输入过程中只刷新校验区与错误徽标，不重建整个检查器（保住焦点与滚动）。 */
  function refreshInspectorValidation(effect) {
    if (!effect) return;
    const normalized = E.normalizeEffectSpec(publicEffect(effect));
    const section = $('#validationSection');
    if (section) section.outerHTML = issuesSection(normalized.errors || [], normalized.warnings || []);
    const badge = $('#inspErrorBadge');
    if (badge) {
      if (normalized.errors && normalized.errors.length) {
        badge.textContent = normalized.errors.length + ' 错误';
        badge.style.display = '';
      } else {
        badge.style.display = 'none';
      }
    }
  }

  function renderInspector() {
    const host = $('#inspector');
    const effect = currentEffect();
    if (!effect) {
      host.innerHTML = '<div class="empty-state">没有效果<br>点击左侧「+ DSL」或「+ 程序」开始</div>';
      updatePreviewHeader(null);
      return;
    }
    const previousScroll = host.scrollTop;
    const normalized = E.normalizeEffectSpec(publicEffect(effect));
    const sections = [
      inspectorHeader(effect, normalized.errors || []),
      identitySection(effect)
    ];
    if (effect.program) {
      sections.push(programSection(effect));
      sections.push(programContextSection(effect));
      sections.push(dslBackupSection(effect));
    } else {
      sections.push(timingSection(effect));
      sections.push(glowSection(effect));
      sections.push(tracksSection(effect));
      sections.push(colorsSection(effect));
      sections.push(programSection(effect));
    }
    sections.push(issuesSection(normalized.errors || [], normalized.warnings || []));
    host.innerHTML = sections.join('');
    $$('svg.curve', host).forEach((svg) => {
      const track = effect.tracks[Number(svg.dataset.curve)];
      if (track) {
        drawCurve(svg, track);
        bindCurveEvents(svg);
      }
    });
    const asmEditor = $('#asmEditor');
    if (asmEditor) {
      asmEditor.addEventListener('scroll', () => {
        const gutter = $('#asmGutter');
        if (gutter) gutter.scrollTop = asmEditor.scrollTop;
      });
      updateAsmGutter();
    }
    host.scrollTop = previousScroll;
    updatePreviewHeader(effect);
    drawGlowPreview();
  }

  function updatePreviewHeader(effect) {
    $('#previewEffectName').textContent = effect ? (effect.name || effect.id || '未命名') : '—';
    const badge = $('#previewBadge');
    if (!effect) {
      badge.textContent = '—';
      badge.className = 'badge';
    } else if (effect.program) {
      badge.textContent = 'PROGRAM';
      badge.className = 'badge prog';
    } else {
      badge.textContent = 'DSL';
      badge.className = 'badge';
    }
    $('#previewHint').textContent = effect && effect.program
      ? '程序绘制整块歌词'
      : '词级进度 0 → 1';
  }

  // ---------------------------------------------------------------------------
  // Curve editor
  // ---------------------------------------------------------------------------

  function svgElement(name, attributes) {
    const node = document.createElementNS(SVG_NS, name);
    if (attributes) {
      for (const key of Object.keys(attributes)) node.setAttribute(key, attributes[key]);
    }
    return node;
  }

  const CURVE_W = 320;
  const CURVE_H = 96;
  const CURVE_PAD = 8;

  function curvePoint(track, at, value) {
    const range = E.PROPERTY_RANGES[track.property] || [0, 1];
    const min = range[0];
    const max = range[1];
    const span = Math.max(1e-6, max - min);
    return {
      x: CURVE_PAD + E.clamp(at, 0, 1) * (CURVE_W - 2 * CURVE_PAD),
      y: CURVE_H - CURVE_PAD - ((E.clamp(value, min, max) - min) / span) * (CURVE_H - 2 * CURVE_PAD)
    };
  }

  function curveValueFromY(track, y) {
    const range = E.PROPERTY_RANGES[track.property] || [0, 1];
    const span = Math.max(1e-6, range[1] - range[0]);
    const ratio = (CURVE_H - CURVE_PAD - y) / (CURVE_H - 2 * CURVE_PAD);
    return E.clamp(range[0] + ratio * span, range[0], range[1]);
  }

  function endpointIndices(track) {
    const frames = track.keyframes || [];
    let first = -1;
    let last = -1;
    frames.forEach((keyframe, index) => {
      if (!Number.isFinite(keyframe.at)) return;
      if (first < 0 || keyframe.at < frames[first].at) first = index;
      if (last < 0 || keyframe.at > frames[last].at) last = index;
    });
    return { first, last };
  }

  function drawCurve(svg, track) {
    while (svg.firstChild) svg.removeChild(svg.firstChild);
    const range = E.PROPERTY_RANGES[track.property] || [0, 1];
    const normalized = E.normalizeNumericKeyframes(track.keyframes, track.property);
    const keyframes = normalized.ok ? normalized.keyframes : [];

    for (let index = 0; index <= 4; index++) {
      const gx = CURVE_PAD + (index * (CURVE_W - 2 * CURVE_PAD)) / 4;
      const gy = CURVE_PAD + (index * (CURVE_H - 2 * CURVE_PAD)) / 4;
      svg.appendChild(svgElement('line', {
        x1: gx, y1: CURVE_PAD, x2: gx, y2: CURVE_H - CURVE_PAD,
        stroke: '#1b2130', 'stroke-width': 1
      }));
      svg.appendChild(svgElement('line', {
        x1: CURVE_PAD, y1: gy, x2: CURVE_W - CURVE_PAD, y2: gy,
        stroke: '#1b2130', 'stroke-width': 1
      }));
    }

    const defaultValue = E.PROPERTY_DEFAULTS[track.property];
    if (Number.isFinite(defaultValue) && defaultValue >= range[0] && defaultValue <= range[1]) {
      const y = curvePoint(track, 0, defaultValue).y;
      svg.appendChild(svgElement('line', {
        x1: CURVE_PAD, y1: y, x2: CURVE_W - CURVE_PAD, y2: y,
        stroke: '#2b3446', 'stroke-width': 1, 'stroke-dasharray': '3 4'
      }));
    }

    if (keyframes.length) {
      let path = '';
      const samples = 160;
      for (let index = 0; index <= samples; index++) {
        const t = index / samples;
        const value = E.evaluateTrack(keyframes, t);
        const point = curvePoint(track, t, value);
        path += (index === 0 ? 'M' : 'L') + point.x.toFixed(2) + ' ' + point.y.toFixed(2) + ' ';
      }
      svg.appendChild(svgElement('path', {
        d: path + 'L' + (CURVE_W - CURVE_PAD) + ' ' + (CURVE_H - CURVE_PAD) +
          ' L' + CURVE_PAD + ' ' + (CURVE_H - CURVE_PAD) + ' Z',
        fill: 'rgba(110,168,254,0.07)', stroke: 'none'
      }));
      svg.appendChild(svgElement('path', {
        d: path, fill: 'none', stroke: '#6ea8fe', 'stroke-width': 1.6,
        'stroke-linejoin': 'round', 'stroke-linecap': 'round'
      }));
    }

    const rawKeyframes = Array.isArray(track.keyframes) ? track.keyframes : [];
    const endpoints = endpointIndices(track);
    rawKeyframes.forEach((keyframe, index) => {
      if (!Number.isFinite(keyframe.at) || !Number.isFinite(keyframe.value)) return;
      const point = curvePoint(track, keyframe.at, keyframe.value);
      const pinned = rawKeyframes.length > 1 && (index === endpoints.first || index === endpoints.last);
      svg.appendChild(svgElement('circle', {
        cx: point.x, cy: point.y, r: 4,
        fill: pinned ? '#9aa6bd' : '#ffffff',
        stroke: '#6ea8fe', 'stroke-width': 1.6,
        'data-ki': index,
        style: 'cursor:grab'
      }));
    });
  }

  function bindCurveEvents(svg) {
    svg.addEventListener('pointerdown', onCurvePointerDown);
    svg.addEventListener('contextmenu', onCurveContextMenu);
  }

  function curveLocalPoint(svg, event) {
    const rect = svg.getBoundingClientRect();
    return {
      x: ((event.clientX - rect.left) / Math.max(1, rect.width)) * CURVE_W,
      y: ((event.clientY - rect.top) / Math.max(1, rect.height)) * CURVE_H
    };
  }

  function nearestKeyframe(track, point) {
    let hit = -1;
    let best = 12;
    (track.keyframes || []).forEach((keyframe, index) => {
      if (!Number.isFinite(keyframe.at) || !Number.isFinite(keyframe.value)) return;
      const at = curvePoint(track, keyframe.at, keyframe.value);
      const distance = Math.hypot(at.x - point.x, at.y - point.y);
      if (distance < best) {
        best = distance;
        hit = index;
      }
    });
    return hit;
  }

  function onCurvePointerDown(event) {
    const effect = currentEffect();
    if (!effect) return;
    const svg = event.currentTarget;
    const trackIndex = Number(svg.dataset.curve);
    const track = effect.tracks[trackIndex];
    if (!track) return;
    const point = curveLocalPoint(svg, event);
    const hit = nearestKeyframe(track, point);
    if (hit < 0) {
      const at = E.clamp((point.x - CURVE_PAD) / (CURVE_W - 2 * CURVE_PAD), 0, 1);
      const value = curveValueFromY(track, point.y);
      pushUndo();
      track.keyframes.push({ at: E.round3(at), value: E.round3(value), easing: 'linear' });
      markDirty();
      renderInspector();
      scheduleJson();
      scheduleSave();
      return;
    }
    event.preventDefault();
    state.drag = {
      svg,
      trackIndex,
      keyframeIndex: hit,
      pointerId: event.pointerId,
      moved: false,
      pendingUndo: true
    };
    svg.setPointerCapture(event.pointerId);
    window.addEventListener('pointermove', onCurvePointerMove);
    window.addEventListener('pointerup', onCurvePointerUp);
  }

  function onCurvePointerMove(event) {
    const drag = state.drag;
    if (!drag) return;
    const effect = currentEffect();
    const track = effect && effect.tracks[drag.trackIndex];
    if (!track) return;
    const keyframe = track.keyframes[drag.keyframeIndex];
    if (!keyframe) return;
    if (drag.pendingUndo) {
      pushUndo();
      drag.pendingUndo = false;
    }
    const point = curveLocalPoint(drag.svg, event);
    const count = track.keyframes.length;
    const endpoints = endpointIndices(track);
    let at = E.clamp((point.x - CURVE_PAD) / (CURVE_W - 2 * CURVE_PAD), 0, 1);
    if (count > 1 && drag.keyframeIndex === endpoints.first) at = 0;
    if (count > 1 && drag.keyframeIndex === endpoints.last) at = 1;
    keyframe.at = E.round3(at);
    keyframe.value = E.round3(curveValueFromY(track, point.y));
    drag.moved = true;
    drawCurve(drag.svg, track);
    const card = drag.svg.closest('.track-card');
    const row = card ? card.querySelectorAll('.kf-row')[drag.keyframeIndex] : null;
    if (row) {
      const atInput = row.querySelector('[data-kf="at"]');
      const valueInput = row.querySelector('[data-kf="value"]');
      if (atInput) atInput.value = formatNumber(keyframe.at);
      if (valueInput) valueInput.value = formatNumber(keyframe.value);
    }
    markDirty();
    refreshGlowPresetState(effect);
    scheduleJson();
    scheduleSave();
  }

  function onCurvePointerUp() {
    const drag = state.drag;
    state.drag = null;
    window.removeEventListener('pointermove', onCurvePointerMove);
    window.removeEventListener('pointerup', onCurvePointerUp);
    if (drag && drag.moved) {
      renderInspector();
    }
  }

  function onCurveContextMenu(event) {
    const effect = currentEffect();
    if (!effect) return;
    const svg = event.currentTarget;
    const track = effect.tracks[Number(svg.dataset.curve)];
    if (!track) return;
    const hit = nearestKeyframe(track, curveLocalPoint(svg, event));
    if (hit < 0) return;
    event.preventDefault();
    if (track.keyframes.length <= 1) {
      toast('每条轨道至少保留一个关键帧');
      return;
    }
    pushUndo();
    track.keyframes.splice(hit, 1);
    markDirty();
    renderInspector();
    scheduleJson();
    scheduleSave();
  }

  // ---------------------------------------------------------------------------
  // Preview: layout
  // ---------------------------------------------------------------------------

  const measureCanvas = document.createElement('canvas');
  const measureContext = measureCanvas.getContext('2d');

  function fontStack() {
    return FONT_STACKS[state.preview.fontFamily] || FONT_STACKS.sans;
  }

  function fontString(size) {
    return '600 ' + size + 'px ' + fontStack();
  }

  function timingKey() {
    return JSON.stringify(state.preview.lineTimings || null);
  }

  function getModel() {
    const maxLines = state.preview.maxLines || 4;
    const key = timingKey();
    if (!state.model || state.model.text !== state.preview.text ||
        state.model.timingKey !== key || state.model.maxLines !== maxLines) {
      state.model = E.buildPreviewModel(state.preview.text, {
        maxLines,
        lineTimings: state.preview.lineTimings || null
      });
      state.model.text = state.preview.text;
      state.model.timingKey = key;
      state.model.maxLines = maxLines;
      state.layoutKey = '';
    }
    return state.model;
  }

  function getLayout(cssWidth, cssHeight) {
    const model = getModel();
    const fitKey = state.preview.autoFit !== false ? 'fit' : 'raw';
    const key = cssWidth + '|' + cssHeight + '|' + state.preview.textSize + '|' + fitKey;
    if (state.layout && state.layoutKey === key) return state.layout;
    const baseTextSize = state.preview.textSize;
    measureContext.font = fontString(baseTextSize);
    let naturalWidth = 0;
    for (const line of model.lines) {
      const spaceWidth = measureContext.measureText(' ').width;
      let width = 0;
      line.words.forEach((word, index) => {
        if (index > 0 && word.spaceBefore) width += spaceWidth;
        width += measureContext.measureText(word.text).width;
      });
      if (width > naturalWidth) naturalWidth = width;
    }
    let textSize = baseTextSize;
    if (state.preview.autoFit !== false && naturalWidth > cssWidth - 32) {
      textSize = Math.max(14, Math.round(baseTextSize * ((cssWidth - 32) / naturalWidth) * 10) / 10);
      measureContext.font = fontString(textSize);
    }
    const lineHeight = textSize * 1.55;
    const lineCount = Math.max(1, model.lines.length);
    const centerY = cssHeight / 2;
    measureContext.font = fontString(textSize);

    const lines = model.lines.map((line, lineIndex) => {
      const spaceWidth = measureContext.measureText(' ').width;
      const metrics = line.words.map((word, wordIndex) => ({
        gap: wordIndex > 0 && word.spaceBefore ? spaceWidth : 0,
        width: measureContext.measureText(word.text).width
      }));
      const total = metrics.reduce((sum, metric) => sum + metric.gap + metric.width, 0);
      const startX = (cssWidth - total) / 2;
      const baseline = centerY - ((lineCount - 1) / 2) * lineHeight +
        lineIndex * lineHeight + textSize * 0.35;
      let x = startX;
      line.words.forEach((word, wordIndex) => {
        x += metrics[wordIndex].gap;
        word.x = x;
        word.width = metrics[wordIndex].width;
        word.baseline = baseline;
        word.lineHeight = lineHeight;
        x += metrics[wordIndex].width;
      });
      return {
        text: line.text,
        index: lineIndex,
        startMs: line.startMs,
        endMs: line.endMs,
        words: line.words,
        x: startX,
        width: total,
        baseline,
        height: lineHeight
      };
    });

    const layout = {
      lines,
      words: model.lines.flatMap((line) => line.words),
      blockWidth: Math.max(1, ...lines.map((line) => line.width)),
      blockHeight: lineCount * lineHeight,
      cssWidth,
      cssHeight,
      textSize,
      lineHeight,
      totalMs: model.totalMs
    };
    state.layout = layout;
    state.layoutKey = key;
    return layout;
  }

  function previewEffect() {
    if (!state.dirty && state.previewCache !== undefined) return state.previewCache;
    const effect = currentEffect();
    if (!effect) {
      state.previewCache = null;
      state.dirty = false;
      return null;
    }
    const raw = publicEffect(effect);
    if (!/^[a-z0-9_]{1,32}$/.test(raw.id || '') || raw.id === 'none') raw.id = 'preview';
    const normalized = E.normalizeEffectSpec(raw);
    state.previewCache = normalized.ok ? normalized.effect : null;
    state.dirty = false;
    return state.previewCache;
  }

  // ---------------------------------------------------------------------------
  // Preview: drawing
  // ---------------------------------------------------------------------------

  function paletteArgb() {
    const palette = state.preview.palette;
    return {
      sung: E.parseColor(palette.sung) == null ? 0xFFFFFFFF | 0 : E.parseColor(palette.sung),
      unsung: E.parseColor(palette.unsung) == null ? 0xFFFFFFFF | 0 : E.parseColor(palette.unsung),
      glow: E.parseColor(palette.glow) == null ? 0xFF8AB4FF | 0 : E.parseColor(palette.glow),
      accent: E.parseColor(palette.accent) == null ? 0xFFFFB86C | 0 : E.parseColor(palette.accent)
    };
  }

  function applyWordTransform(context, frame, word, textSize) {
    if (frame.translateXEm || frame.translateYEm) {
      context.translate(frame.translateXEm * textSize, frame.translateYEm * textSize);
    }
    const pivotX = word.x + word.width / 2;
    const pivotY = word.baseline;
    if (frame.rotateDeg) {
      context.translate(pivotX, pivotY);
      context.rotate((frame.rotateDeg * Math.PI) / 180);
      context.translate(-pivotX, -pivotY);
    }
    if (frame.scale !== 1) {
      context.translate(pivotX, pivotY);
      context.scale(frame.scale, frame.scale);
      context.translate(-pivotX, -pivotY);
    }
    if (frame.skewX) {
      context.translate(pivotX, pivotY);
      context.transform(1, 0, frame.skewX, 1, 0, 0);
      context.translate(-pivotX, -pivotY);
    }
  }

  function drawGuides(context, layout) {
    const textSize = layout.textSize;
    context.save();
    context.lineWidth = 1;
    const guideBg = state.preview.bgColor || (THEMES[state.preview.theme] || THEMES.dark).bg;
    const guideArgb = E.parseColor(guideBg);
    const lightGuideBg = guideArgb != null &&
      (((guideArgb >>> 16) & 255) + ((guideArgb >>> 8) & 255) + (guideArgb & 255)) > 384;
    context.strokeStyle = lightGuideBg
      ? 'rgba(0,0,0,0.09)'
      : 'rgba(255,255,255,0.055)';
    context.setLineDash([3, 4]);
    for (const line of layout.lines) {
      context.beginPath();
      context.moveTo(18, line.baseline);
      context.lineTo(layout.cssWidth - 18, line.baseline);
      context.stroke();
    }
    context.setLineDash([]);
    context.strokeStyle = 'rgba(110,168,254,0.17)';
    for (const word of layout.words) {
      context.strokeRect(word.x, word.baseline - textSize * 0.78, Math.max(1, word.width), textSize * 0.95);
    }
    if (state.preview.guideLong) {
      const threshold = (previewEffect() && previewEffect().longSyllableMs) || 700;
      context.fillStyle = 'rgba(255,184,108,0.75)';
      for (const word of layout.words) {
        if (word.durationMs >= threshold) {
          context.fillRect(word.x, word.baseline - textSize * 0.86, Math.min(Math.max(1, word.width), 18), 2);
        }
      }
    }
    context.restore();
  }

  function drawPlainText(context, layout) {
    const palette = paletteArgb();
    context.save();
    context.font = fontString(layout.textSize);
    context.textBaseline = 'alphabetic';
    context.textAlign = 'left';
    context.fillStyle = cssRgba(palette.unsung, 0.75);
    for (const word of layout.words) {
      context.fillText(word.text, word.x, word.baseline);
    }
    context.restore();
  }

  function drawDsl(context, effect, layout, positionMs) {
    const palette = paletteArgb();
    const textSize = layout.textSize;
    const dimAlpha = E.steadyTextAlpha(effect.unsungDim);
    context.font = fontString(textSize);
    context.textBaseline = 'alphabetic';
    context.textAlign = 'left';

    for (const line of layout.lines) {
      const wordCount = Math.max(1, line.words.length);
      for (const word of line.words) {
        const progress = E.progressAt(word.startMs, word.endMs, positionMs);
        const longSyllable = word.durationMs >= effect.longSyllableMs;
        const frame = E.evaluateEffect(effect, progress, word.indexInLine, longSyllable, wordCount);
        const sung = progress >= 1;
        const baseColor = frame.colorArgb == null
          ? (sung ? palette.sung : palette.unsung)
          : frame.colorArgb;
        const baseAlpha = (sung ? 1 : dimAlpha) * frame.alpha;
        const sweepColor = frame.colorArgb == null ? palette.sung : frame.colorArgb;
        const sweepAlpha = frame.alpha;

        const glowApplies = state.preview.glowEnabled && effect.glow !== 'none' &&
          (effect.glow === 'all' || longSyllable) && frame.glowAlpha > 0;
        if (glowApplies) {
          context.save();
          applyWordTransform(context, frame, word, textSize);
          context.globalAlpha = E.clamp(sweepAlpha * frame.glowAlpha, 0, 1);
          context.shadowColor = cssRgb(palette.glow);
          context.shadowBlur = (frame.glowRadiusEm == null ? 0.36 : frame.glowRadiusEm) * textSize;
          context.fillStyle = cssRgb(sweepColor);
          context.fillText(word.text, word.x, word.baseline);
          context.restore();
        }

        context.save();
        applyWordTransform(context, frame, word, textSize);
        context.globalAlpha = E.clamp(baseAlpha, 0, 1);
        context.fillStyle = cssRgb(baseColor);
        context.fillText(word.text, word.x, word.baseline);
        context.restore();

        const sweepProgress = frame.sweepProgress >= 0 ? frame.sweepProgress : progress;
        const sweepActive = effect.sweep && (
          frame.sweepProgress >= 0 || (progress > 0 && progress < 1)
        );
        if (sweepActive) {
          const band = Math.max(1, word.width * E.clamp(effect.sweepBand, 0.05, 1));
          const start = word.x - band + (word.width + band) * E.clamp(sweepProgress, 0, 1);
          context.save();
          applyWordTransform(context, frame, word, textSize);
          // 在词的局部坐标里建渐变：创建与绘制使用同一 CTM，避免变换后光带错位。
          const gradient = context.createLinearGradient(start, 0, start + band, 0);
          gradient.addColorStop(0, cssRgba(sweepColor, 1));
          gradient.addColorStop(0.45, cssRgba(sweepColor, 184 / 255));
          gradient.addColorStop(1, cssRgba(sweepColor, 0));
          context.globalAlpha = E.clamp(sweepAlpha, 0, 1);
          context.fillStyle = gradient;
          context.fillText(word.text, word.x, word.baseline);
          context.restore();
        }
      }
    }
  }

  class CanvasSink {
    constructor(context, colors, textSize, fontFamily) {
      this.ctx = context;
      this.colors = colors;
      this.color = colors.unsung;
      this.alpha = 1;
      this.textSize = E.clamp(textSize, 1, 512);
      this.align = 0;
      this.gradientSpec = null;
      this.shadow = null;
      this.strokeWidth = 1;
      this.style = 'fill';
      this.path = new Path2D();
      this.pathPoints = 0;
      this.depth = 0;
      this.baseDepth = 0;
      this.fontFamily = fontFamily;
    }
    save() {
      if (this.depth - this.baseDepth < 16) {
        this.ctx.save();
        this.depth++;
      }
    }
    restore() {
      if (this.depth > this.baseDepth) {
        this.ctx.restore();
        this.depth--;
      }
    }
    restoreAll() {
      while (this.depth > this.baseDepth) {
        this.ctx.restore();
        this.depth--;
      }
    }
    translate(x, y) { this.ctx.translate(x, y); }
    rotate(deg) { this.ctx.rotate((deg * Math.PI) / 180); }
    scale(sx, sy) { this.ctx.scale(sx, sy); }
    setColor(argb) { this.color = argb | 0; }
    setAlpha(alpha) { this.alpha = E.clamp(alpha, 0, 1); }
    setTextSize(px) { this.textSize = E.clamp(px, 1, 512); }
    setTextAlign(align) { this.align = align === 1 ? 1 : align === 2 ? 2 : 0; }
    setGradient(x0, y0, x1, y1, color0, color1) {
      this.gradientSpec = { x0, y0, x1, y1, color0, color1 };
    }
    clearGradient() { this.gradientSpec = null; }
    setShadow(radius, dx, dy, color) {
      this.shadow = {
        radius: E.clamp(radius, 0, 256),
        dx: E.clamp(dx, -256, 256),
        dy: E.clamp(dy, -256, 256),
        color
      };
    }
    clearShadow() { this.shadow = null; }
    setStrokeWidth(width) { this.strokeWidth = E.clamp(width, 0, 128); }
    setFill() { this.style = 'fill'; }
    setStroke() { this.style = 'stroke'; }
    fontString() { return fontString(this.textSize); }
    applyShadow() {
      const ctx = this.ctx;
      if (this.shadow) {
        ctx.shadowBlur = this.shadow.radius;
        ctx.shadowOffsetX = this.shadow.dx;
        ctx.shadowOffsetY = this.shadow.dy;
        ctx.shadowColor = cssColor(this.shadow.color, 1);
      } else {
        ctx.shadowBlur = 0;
        ctx.shadowOffsetX = 0;
        ctx.shadowOffsetY = 0;
        ctx.shadowColor = 'transparent';
      }
    }
    currentPaint() {
      if (this.gradientSpec) {
        const spec = this.gradientSpec;
        try {
          const gradient = this.ctx.createLinearGradient(spec.x0, spec.y0, spec.x1, spec.y1);
          gradient.addColorStop(0, cssColor(spec.color0, 1));
          gradient.addColorStop(1, cssColor(spec.color1, 1));
          return gradient;
        } catch (error) {
          return cssRgb(this.color);
        }
      }
      return cssRgb(this.color);
    }
    applyShapePaint() {
      const ctx = this.ctx;
      ctx.globalAlpha = this.alpha;
      const paint = this.currentPaint();
      ctx.fillStyle = paint;
      ctx.strokeStyle = paint;
      ctx.lineWidth = Math.max(1, this.strokeWidth);
      this.applyShadow();
    }
    drawText(text, x, y) {
      const ctx = this.ctx;
      ctx.font = this.fontString();
      ctx.textAlign = this.align === 1 ? 'center' : this.align === 2 ? 'right' : 'left';
      ctx.textBaseline = 'alphabetic';
      ctx.globalAlpha = this.alpha;
      ctx.fillStyle = this.currentPaint();
      this.applyShadow();
      ctx.fillText(text, x, y);
    }
    drawRect(x, y, w, h) {
      const ctx = this.ctx;
      ctx.beginPath();
      ctx.rect(x, y, Math.max(0, w), Math.max(0, h));
      this.applyShapePaint();
      if (this.style === 'stroke') ctx.stroke(); else ctx.fill();
    }
    drawCircle(cx, cy, r) {
      const ctx = this.ctx;
      ctx.beginPath();
      ctx.arc(cx, cy, Math.max(0, r), 0, Math.PI * 2);
      this.applyShapePaint();
      if (this.style === 'stroke') ctx.stroke(); else ctx.fill();
    }
    drawLine(x1, y1, x2, y2) {
      const ctx = this.ctx;
      this.style = 'stroke';
      ctx.beginPath();
      ctx.moveTo(x1, y1);
      ctx.lineTo(x2, y2);
      this.applyShapePaint();
      ctx.stroke();
    }
    roundRectPath(ctx, x, y, w, h, rx, ry) {
      const radiusX = Math.max(0, Math.min(rx, w / 2));
      const radiusY = Math.max(0, Math.min(ry, h / 2));
      ctx.moveTo(x + radiusX, y);
      ctx.lineTo(x + w - radiusX, y);
      ctx.quadraticCurveTo(x + w, y, x + w, y + radiusY);
      ctx.lineTo(x + w, y + h - radiusY);
      ctx.quadraticCurveTo(x + w, y + h, x + w - radiusX, y + h);
      ctx.lineTo(x + radiusX, y + h);
      ctx.quadraticCurveTo(x, y + h, x, y + h - radiusY);
      ctx.lineTo(x, y + radiusY);
      ctx.quadraticCurveTo(x, y, x + radiusX, y);
      ctx.closePath();
    }
    drawRoundRect(x, y, w, h, rx, ry) {
      const ctx = this.ctx;
      const width = Math.max(0, w);
      const height = Math.max(0, h);
      ctx.beginPath();
      if (typeof ctx.roundRect === 'function') {
        ctx.roundRect(x, y, width, height, [Math.max(0, rx), Math.max(0, ry)]);
      } else {
        this.roundRectPath(ctx, x, y, width, height, rx, ry);
      }
      this.applyShapePaint();
      if (this.style === 'stroke') ctx.stroke(); else ctx.fill();
    }
    drawOval(x, y, w, h) {
      const ctx = this.ctx;
      ctx.beginPath();
      ctx.ellipse(x + w / 2, y + h / 2, Math.max(0, w / 2), Math.max(0, h / 2), 0, 0, Math.PI * 2);
      this.applyShapePaint();
      if (this.style === 'stroke') ctx.stroke(); else ctx.fill();
    }
    drawArc(x, y, w, h, start, sweep) {
      const ctx = this.ctx;
      ctx.beginPath();
      ctx.ellipse(
        x + w / 2, y + h / 2,
        Math.max(0, w / 2), Math.max(0, h / 2),
        0,
        (start * Math.PI) / 180,
        ((start + sweep) * Math.PI) / 180,
        false
      );
      this.applyShapePaint();
      if (this.style === 'stroke') ctx.stroke(); else ctx.fill();
    }
    pathMove(x, y) {
      if (this.pathPoints >= 4096) return;
      this.pathPoints++;
      this.path.moveTo(x, y);
    }
    pathLine(x, y) {
      if (this.pathPoints >= 4096) return;
      this.pathPoints++;
      this.path.lineTo(x, y);
    }
    pathQuad(cx, cy, x, y) {
      if (this.pathPoints >= 4096) return;
      this.pathPoints++;
      this.path.quadraticCurveTo(cx, cy, x, y);
    }
    pathCubic(c1x, c1y, c2x, c2y, x, y) {
      if (this.pathPoints >= 4096) return;
      this.pathPoints++;
      this.path.bezierCurveTo(c1x, c1y, c2x, c2y, x, y);
    }
    pathClose() {
      if (this.pathPoints >= 4096) return;
      this.pathPoints++;
      this.path.closePath();
    }
    drawPath() {
      const ctx = this.ctx;
      this.applyShapePaint();
      if (this.style === 'stroke') ctx.stroke(this.path); else ctx.fill(this.path);
      this.path = new Path2D();
      this.pathPoints = 0;
    }
    clipRect(x, y, w, h) {
      const ctx = this.ctx;
      ctx.beginPath();
      ctx.rect(x, y, Math.max(0, w), Math.max(0, h));
      ctx.clip();
    }
    measureText(text) {
      this.ctx.font = this.fontString();
      return this.ctx.measureText(text).width;
    }
  }

  function buildProgramContext(layout, positionMs, cssWidth, cssHeight, palette, effect) {
    const lines = layout.lines.map((line) => ({
      text: line.text,
      index: line.index,
      startMs: line.startMs,
      endMs: line.endMs,
      progress: E.progressAt(line.startMs, line.endMs, positionMs),
      x: line.x,
      width: line.width,
      baseline: line.baseline,
      height: line.height
    }));
    const words = layout.words.map((word) => ({
      text: word.text,
      x: word.x,
      width: word.width,
      startMs: word.startMs,
      endMs: word.endMs,
      progress: E.progressAt(word.startMs, word.endMs, positionMs),
      index: word.index,
      longSyllable: word.durationMs >= (effect.longSyllableMs || 700),
      lineIndex: word.lineIndex,
      baseline: word.baseline
    }));
    return {
      timeMs: positionMs,
      wallClockMs: performance.now(),
      baseline: lines.length ? lines[0].baseline : 0,
      textSize: layout.textSize,
      blockWidth: layout.blockWidth,
      blockHeight: layout.blockHeight,
      canvasWidth: cssWidth,
      canvasHeight: cssHeight,
      colors: palette,
      lines,
      words,
      lineIndex: 0
    };
  }

  function drawProgram(context, effect, layout, positionMs, cssWidth, cssHeight, dpr) {
    if (!state.offscreen) state.offscreen = document.createElement('canvas');
    const offscreen = state.offscreen;
    const pixelWidth = Math.max(1, Math.round(cssWidth * dpr));
    const pixelHeight = Math.max(1, Math.round(cssHeight * dpr));
    if (offscreen.width !== pixelWidth || offscreen.height !== pixelHeight) {
      offscreen.width = pixelWidth;
      offscreen.height = pixelHeight;
    }
    const offContext = offscreen.getContext('2d');
    offContext.setTransform(dpr, 0, 0, dpr, 0, 0);
    offContext.clearRect(0, 0, cssWidth, cssHeight);

    const palette = paletteArgb();
    const frameContext = buildProgramContext(layout, positionMs, cssWidth, cssHeight, palette, effect);
    offContext.save();
    const sink = new CanvasSink(offContext, palette, layout.textSize, fontStack());
    const result = E.runProgram(effect.program, frameContext, sink);
    sink.restoreAll();
    offContext.restore();
    if (result.ok) {
      context.drawImage(offscreen, 0, 0, cssWidth, cssHeight);
    } else {
      state.programError = '程序执行失败：' + (result.error || '未知错误') + '（实机会整行不绘制）';
    }
  }

  function updateOverlay(effect) {
    const overlay = $('#canvasOverlay');
    const pills = [];
    if (state.programError) {
      pills.push('<div class="overlay-pill err">' + esc(state.programError) + '</div>');
    }
    if (!effect) {
      pills.push('<div class="overlay-pill err">效果结构非法，预览回退为纯文本</div>');
    } else if (effect.program && !effect.program.code.some((instruction) => instruction.op === 'drawText')) {
      pills.push('<div class="overlay-pill hint">程序没有 drawText：画布上不会出现歌词</div>');
    } else if (effect.program && state.preview.guideBoxes) {
      pills.push('<div class="overlay-pill">词框只是参考线，不会被导出</div>');
    }
    overlay.innerHTML = pills.join('');
  }

  function drawPreview() {
    const canvas = $('#preview');
    const wrap = $('#previewWrap');
    const cssWidth = Math.max(200, Math.round(wrap.clientWidth || 720));
    const cssHeight = Math.max(240, Math.round(wrap.clientHeight || 380));
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const pixelWidth = Math.max(1, Math.round(cssWidth * dpr));
    const pixelHeight = Math.max(1, Math.round(cssHeight * dpr));
    if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
      canvas.width = pixelWidth;
      canvas.height = pixelHeight;
    }
    canvas.style.width = cssWidth + 'px';
    canvas.style.height = cssHeight + 'px';
    const context = canvas.getContext('2d');
    context.setTransform(dpr, 0, 0, dpr, 0, 0);

    const theme = THEMES[state.preview.theme] || THEMES.dark;
    const background = state.preview.bgColor || theme.bg;
    context.clearRect(0, 0, cssWidth, cssHeight);
    context.fillStyle = background;
    context.fillRect(0, 0, cssWidth, cssHeight);
    if (state.preview.theme !== 'aod') {
      const vignette = context.createRadialGradient(
        cssWidth / 2, cssHeight * 0.45, 8,
        cssWidth / 2, cssHeight * 0.45, Math.max(cssWidth, cssHeight) * 0.72
      );
      vignette.addColorStop(0, 'rgba(110,168,254,0.055)');
      vignette.addColorStop(1, 'rgba(0,0,0,0)');
      context.fillStyle = vignette;
      context.fillRect(0, 0, cssWidth, cssHeight);
    }

    const layout = getLayout(cssWidth, cssHeight);
    state.programError = null;
    if (state.preview.guideBoxes) drawGuides(context, layout);

    const effect = previewEffect();
    const positionMs = E.clamp(state.preview.positionMs, 0, layout.totalMs);
    try {
      if (!effect) {
        drawPlainText(context, layout);
      } else if (effect.program) {
        drawProgram(context, effect, layout, positionMs, cssWidth, cssHeight, dpr);
      } else {
        drawDsl(context, effect, layout, positionMs);
      }
    } catch (error) {
      state.programError = '预览异常：' + (error && error.message ? error.message : String(error));
    }
    updateOverlay(effect);
  }

  // ---------------------------------------------------------------------------
  // Preview: transport / scrub / HUD
  // ---------------------------------------------------------------------------

  function buildTicks() {
    const model = getModel();
    const total = Math.max(1, model.totalMs);
    const ticks = [];
    for (const line of model.lines) {
      ticks.push({ at: line.startMs / total, major: true });
      for (const word of line.words) {
        ticks.push({ at: word.startMs / total, major: false });
      }
    }
    $('#scrubTicks').innerHTML = ticks.map((tick) =>
      '<i class="' + (tick.major ? 'major' : '') + '" style="left:' +
      (E.clamp(tick.at, 0, 1) * 100).toFixed(3) + '%"></i>'
    ).join('');
    state.ticksKey = state.preview.text + '|' + timingKey() + '|' + (state.preview.maxLines || 4);
  }

  function renderTimingTable() {
    const model = getModel();
    const effect = previewEffect();
    const threshold = effect ? effect.longSyllableMs : 700;
    const longCount = model.words.filter((word) => word.durationMs >= threshold).length;
    const sourceLabel = state.preview.lineTimings ? ' · LRC' : '';
    $('#timingSummary').textContent = model.words.length + ' 词 · ' + longCount + ' 长 · 阈值 ' +
      threshold + 'ms' + sourceLabel + ' · 点击跳转';
    $('#timingBody').innerHTML = model.words.map((word) => {
      const isLong = word.durationMs >= threshold;
      const glowMode = effect ? effect.glow : 'none';
      const glows = glowMode !== 'none' && (glowMode === 'all' || isLong);
      return '<tr data-start="' + word.startMs + '"' + (isLong ? ' class="long"' : '') +
        ' title="点击跳到该词">' +
        '<td class="w">' + esc(word.text) + '</td>' +
        '<td>' + (word.lineIndex + 1) + '</td>' +
        '<td>' + (word.startMs / 1000).toFixed(2) + '</td>' +
        '<td>' + word.durationMs + '</td>' +
        '<td>' + (isLong ? '<span class="badge warn">长</span>' : '短') + '</td>' +
        '<td>' + (glows ? '<span class="glow-dot" title="该词会绘制辉光"></span>' : '—') + '</td>' +
      '</tr>';
    }).join('');
  }

  /** 当前播放位置对应的词、词进度与 stagger 后的 t（HUD 与「在 t 加帧」共用）。 */
  function currentWordState() {
    const model = getModel();
    if (!model.words.length) return null;
    const position = E.clamp(state.preview.positionMs, 0, model.totalMs);
    let word = model.words.find((candidate) => position >= candidate.startMs && position < candidate.endMs);
    if (!word) word = model.words[model.words.length - 1];
    const progress = E.progressAt(word.startMs, word.endMs, position);
    const line = model.lines[word.lineIndex];
    const wordCount = Math.max(1, line ? line.words.length : 1);
    const effect = previewEffect();
    const longSyllable = word.durationMs >= (effect ? effect.longSyllableMs : 700);
    const frame = effect
      ? E.evaluateEffect(effect, progress, word.indexInLine, longSyllable, wordCount)
      : null;
    return { model, position, word, progress, wordCount, longSyllable, effect, frame };
  }

  function drawGlowPreview() {
    const canvas = $('#glowPreview');
    if (!canvas) return;
    const cssWidth = Math.max(200, Math.round(canvas.clientWidth || 300));
    const cssHeight = 74;
    const dpr = Math.min(2, window.devicePixelRatio || 1);
    const pixelWidth = Math.max(1, Math.round(cssWidth * dpr));
    const pixelHeight = Math.max(1, Math.round(cssHeight * dpr));
    if (canvas.width !== pixelWidth || canvas.height !== pixelHeight) {
      canvas.width = pixelWidth;
      canvas.height = pixelHeight;
    }
    const context = canvas.getContext('2d');
    context.setTransform(dpr, 0, 0, dpr, 0, 0);
    const theme = THEMES[state.preview.theme] || THEMES.dark;
    context.fillStyle = state.preview.bgColor || theme.bg;
    context.fillRect(0, 0, cssWidth, cssHeight);

    const effect = previewEffect();
    const palette = paletteArgb();
    const current = currentWordState();
    const progress = current ? current.progress : 0.5;
    const indexInLine = current ? current.word.indexInLine : 0;
    const wordCount = current ? current.wordCount : 1;
    const samples = [
      { label: '长音节', long: true, x: cssWidth * 0.26 },
      { label: '短音节', long: false, x: cssWidth * 0.74 }
    ];

    for (const sample of samples) {
      const frame = effect
        ? E.evaluateEffect(effect, progress, indexInLine, sample.long, wordCount)
        : null;
      const applies = !!(effect && !effect.program && state.preview.glowEnabled &&
        effect.glow !== 'none' && (effect.glow === 'all' || sample.long) &&
        frame && frame.glowAlpha > 0);
      const radius = frame && frame.glowRadiusEm != null ? frame.glowRadiusEm : 0.36;
      const alpha = frame ? E.clamp(frame.glowAlpha, 0, 1) : 1;

      if (applies) {
        context.save();
        context.textAlign = 'center';
        context.textBaseline = 'middle';
        context.font = fontString(22);
        context.shadowColor = cssRgb(palette.glow);
        context.shadowBlur = radius * 22;
        context.globalAlpha = alpha;
        context.fillStyle = cssRgb(palette.sung);
        context.fillText(sample.label, sample.x, 30);
        context.restore();
      }
      context.save();
      context.textAlign = 'center';
      context.textBaseline = 'middle';
      context.font = fontString(22);
      context.globalAlpha = 1;
      context.fillStyle = cssRgb(palette.sung);
      context.fillText(sample.label, sample.x, 30);
      context.restore();

      context.save();
      context.textAlign = 'center';
      context.textBaseline = 'middle';
      context.font = fontString(9);
      context.fillStyle = applies ? 'rgba(255,200,120,0.85)' : 'rgba(255,255,255,0.3)';
      context.fillText(applies ? '辉光' : '无辉光', sample.x, 58);
      context.restore();
    }

    const readout = $('#glowReadout');
    if (!readout) return;
    if (!effect) {
      readout.textContent = '效果结构非法，无法计算辉光。';
      return;
    }
    if (effect.program) {
      readout.textContent = '程序档优先：DSL 辉光不参与渲染。';
      return;
    }
    if (effect.glow === 'none') {
      readout.textContent = 'glow=none：不绘制辉光。';
      return;
    }
    if (!state.preview.glowEnabled) {
      readout.textContent = '表面辉光未开启：预览里看不到辉光。';
      return;
    }
    if (!current) {
      readout.textContent = '示例歌词为空。';
      return;
    }
    const long = current.longSyllable;
    const phase = current.progress <= 0 ? '未开始' : current.progress >= 1 ? '已唱完' : '正在唱';
    const frame = current.frame;
    const applies = effect.glow === 'all' || long;
    const radius = frame && frame.glowRadiusEm != null ? frame.glowRadiusEm : 0.36;
    const alpha = frame ? E.clamp(frame.glowAlpha, 0, 1) : 1;
    const hasLong = current.model.words.some((word) =>
      word.durationMs >= (effect.longSyllableMs || 700));
    readout.textContent =
      '当前词：' + (long ? '长音节' : '短音节') + ' · ' + phase + ' · ' +
      (applies
        ? '辉光 半径 ' + radius.toFixed(2) + 'em · alpha ' + alpha.toFixed(2)
        : '不触发（glow=long）') +
      (!hasLong && effect.glow === 'long' ? ' · 当前示例没有长音节' : '');
  }

  function updateTransport() {
    const model = getModel();
    const total = Math.max(1, model.totalMs);
    const position = E.clamp(state.preview.positionMs, 0, total);
    $('#timeReadout').innerHTML = '<b>' + (position / 1000).toFixed(2) + 's</b> / ' +
      (total / 1000).toFixed(2) + 's';
    $('#scrubFill').style.width = (position / total * 100).toFixed(3) + '%';
    $('#scrubThumb').style.left = (position / total * 100).toFixed(3) + '%';
    $('#scrub').setAttribute('aria-valuenow', String(Math.round((position / total) * 1000)));
    $('#scrub').setAttribute('aria-valuetext', (position / 1000).toFixed(2) + 's / ' + (total / 1000).toFixed(2) + 's');
    $('#playBtn').textContent = state.preview.playing ? '❚❚' : '▶';
    if (state.ticksKey !== state.preview.text + '|' + timingKey() + '|' + (state.preview.maxLines || 4)) buildTicks();
    drawGlowPreview();

    const hud = $('#wordHud');
    const current = currentWordState();
    if (!current) {
      hud.innerHTML = '<span class="chip">示例歌词为空</span>';
      return;
    }
    const { word, progress, longSyllable, effect, frame } = current;
    const showT = effect && !effect.program && frame;
    hud.innerHTML =
      '<span class="chip">词 <b>' + esc(word.text) + '</b></span>' +
      '<span class="chip">p <b>' + progress.toFixed(2) + '</b></span>' +
      '<span class="chip">t <b>' + (showT ? frame.t.toFixed(2) : '—') + '</b></span>' +
      '<span class="chip">' + (longSyllable ? '长音节' : '短音节') + ' · ' + word.durationMs + 'ms</span>' +
      '<span class="chip">行 ' + (word.lineIndex + 1) + '/' + model.lines.length + '</span>' +
      '<span class="chip">' + model.words.length + ' 词</span>';
  }

  function seekBy(deltaMs) {
    state.preview.playing = false;
    state.preview.positionMs = E.clamp(state.preview.positionMs + deltaMs, 0, getModel().totalMs);
    state.needsDraw = true;
    updateTransport();
  }

  function seekToWord(direction) {
    const model = getModel();
    const position = state.preview.positionMs;
    let target = null;
    if (direction < 0) {
      for (let index = model.words.length - 1; index >= 0; index--) {
        if (model.words[index].startMs < position - 5) {
          target = model.words[index];
          break;
        }
      }
    } else {
      for (const word of model.words) {
        if (word.startMs > position + 5) {
          target = word;
          break;
        }
      }
    }
    if (!target) return;
    state.preview.playing = false;
    state.preview.positionMs = target.startMs;
    state.needsDraw = true;
    updateTransport();
  }

  // ---------------------------------------------------------------------------
  // LRC timing
  // ---------------------------------------------------------------------------

  function formatLrcTime(ms) {
    const total = Math.max(0, Math.round(ms));
    const minutes = Math.floor(total / 60000);
    const seconds = Math.floor((total % 60000) / 1000);
    const millis = total % 1000;
    return '[' + String(minutes).padStart(2, '0') + ':' +
      String(seconds).padStart(2, '0') + '.' + String(millis).padStart(3, '0') + ']';
  }

  function estimateLineMs(text) {
    const tokens = E.tokenizeLine(text);
    return tokens.reduce((sum, token) =>
      sum + Math.round(E.clamp(220 + 130 * (E.wordWeight(token) - 1), 220, 1200)), 0) || 1000;
  }

  function parseLrcSource() {
    const source = $('#lrcSource').value;
    state.preview.lrcSource = source;
    const entries = E.parseLrc(source);
    state.lrcEntries = entries;
    $('#lrcStart').innerHTML = entries.map((entry, index) =>
      '<option value="' + index + '">' +
      esc(formatLrcTime(entry.timeMs) + ' ' + entry.text.slice(0, 28)) +
      '</option>'
    ).join('');
    const maxCount = Math.min(6, Math.max(1, entries.length));
    $('#lrcCount').innerHTML = Array.from({ length: maxCount }, (_, index) => index + 1)
      .map((count) => '<option value="' + count + '"' +
        (count === Math.min(2, maxCount) ? ' selected' : '') + '>' + count + '</option>')
      .join('');
    $('#lrcStatus').textContent = entries.length
      ? '解析到 ' + entries.length + ' 行；选择片段后点「应用片段」'
      : '没有解析到 [mm:ss.xx] 时间标签';
    scheduleSave();
    return entries;
  }

  function applyLrcFragment() {
    let entries = state.lrcEntries;
    if (!Array.isArray(entries) || !entries.length) entries = parseLrcSource();
    if (!entries.length) {
      toast('没有可用的 LRC 行', 'err');
      return;
    }
    const start = E.clamp(Number($('#lrcStart').value) || 0, 0, entries.length - 1);
    const count = E.clamp(Number($('#lrcCount').value) || 1, 1, Math.min(6, entries.length - start));
    const selected = entries.slice(start, start + count);
    const base = selected[0].timeMs;
    const timings = selected.map((entry, index) => {
      const next = selected[index + 1] || entries[start + index + 1] || null;
      const end = next
        ? next.timeMs - base
        : entry.timeMs - base + estimateLineMs(entry.text);
      return {
        startMs: entry.timeMs - base,
        endMs: Math.max(entry.timeMs - base + 1, end)
      };
    });
    state.preview.text = selected.map((entry) => entry.text).join('\n');
    state.preview.lineTimings = timings;
    state.preview.lrcSource = $('#lrcSource').value;
    state.preview.maxLines = Math.max(state.preview.maxLines || 4, count);
    state.preview.positionMs = 0;
    state.model = null;
    state.layoutKey = '';
    state.ticksKey = '';
    state.needsDraw = true;
    syncPreviewControls();
    renderTimingTable();
    $('#lrcSummary').textContent = '已应用 ' + count + ' 行 · ' + formatLrcTime(selected[0].timeMs);
    $('#lrcStatus').textContent = '已应用 ' + count + ' 行；预览时间轴从 0 计';
    scheduleSave();
    toast('已应用 LRC 片段（' + count + ' 行）');
  }

  function clearLrcTimings() {
    state.preview.lineTimings = null;
    state.model = null;
    state.layoutKey = '';
    state.ticksKey = '';
    state.preview.positionMs = 0;
    state.needsDraw = true;
    renderTimingTable();
    $('#lrcSummary').textContent = '粘贴 LRC 可导入真实行时间';
    $('#lrcStatus').textContent = '已恢复自动时长（保留当前歌词文本）';
    toast('已恢复自动时长');
    scheduleSave();
  }

  function togglePlay() {
    state.preview.playing = !state.preview.playing;
    state.needsDraw = true;
    scheduleSave();
  }

  function restart() {
    state.preview.positionMs = 0;
    state.needsDraw = true;
    scheduleSave();
  }

  function scrubToEvent(event) {
    const rect = $('#scrub').getBoundingClientRect();
    const fraction = E.clamp((event.clientX - rect.left) / Math.max(1, rect.width), 0, 1);
    state.preview.positionMs = fraction * getModel().totalMs;
    state.needsDraw = true;
  }

  // ---------------------------------------------------------------------------
  // JSON / validation
  // ---------------------------------------------------------------------------

  function refreshJson() {
    const result = E.normalizePack(publicPack());
    state.normalization = result;
    const pretty = JSON.stringify(result.pack, null, 2);
    $('#jsonOut').textContent = pretty;

    const issues = [];
    for (const error of result.errors) {
      issues.push('<div class="issue-item err"><b>包</b> ' + esc(error) + '</div>');
    }
    for (const entry of result.entries) {
      for (const error of entry.errors) {
        issues.push('<div class="issue-item err"><b>#' + (entry.index + 1) + ' ' +
          esc(entry.id || '?') + '</b> ' + esc(error) + '</div>');
      }
    }
    for (const warning of result.warnings) {
      issues.push('<div class="issue-item warn">' + esc(warning) + '</div>');
    }
    if (!issues.length) {
      issues.push('<div class="issue-item">✓ 没有发现问题。导出的 JSON 就是左侧内容。</div>');
    }
    $('#drawerIssues').innerHTML = issues.join('');
    const errorCount = result.errors.length +
      result.entries.reduce((sum, entry) => sum + entry.errors.length, 0);
    $('#issueSummary').textContent = errorCount + ' 错误 · ' + result.warnings.length + ' 警告';

    const overLimit = result.bytesPretty > E.MAX_PACK_BYTES;
    $('#jsonStatus').innerHTML =
      '<span class="badge ' + (result.ok ? 'ok' : 'err') + '">' + (result.ok ? '可导入' : '有问题') + '</span>' +
      '<span class="badge">' + result.pack.effects.length + ' / ' + state.pack.effects.length + ' 效果</span>' +
      '<span class="badge">' + (result.bytesPretty / 1024).toFixed(1) + ' KiB</span>' +
      '<span class="badge">紧凑 ' + (result.bytesCompact / 1024).toFixed(1) + ' KiB</span>' +
      (state.jsonEditing ? '<span class="badge warn">编辑中</span>' : '') +
      (overLimit ? '<span class="badge err">超过 64 KiB</span>' : '');

    $('#packStats').textContent = state.pack.effects.length + ' 个效果 · ' +
      (result.bytesPretty / 1024).toFixed(1) + ' KiB / 64 KiB';
    const meter = $('#sizeMeter');
    meter.className = 'size-meter' +
      (result.bytesPretty > E.MAX_PACK_BYTES ? ' bad' :
        result.bytesPretty > E.MAX_PACK_BYTES * 0.8 ? ' warn' : '');
    meter.querySelector('span').style.width =
      Math.min(100, (result.bytesPretty / E.MAX_PACK_BYTES) * 100).toFixed(2) + '%';
  }

  function scheduleJson() {
    clearTimeout(state.jsonTimer);
    state.jsonTimer = setTimeout(refreshJson, 180);
  }

  function openDrawer() {
    state.drawer = true;
    $('#drawer').classList.add('open');
    const split = $('#drawerSplit');
    if (split) split.hidden = false;
    state.layoutKey = '';
    state.needsDraw = true;
  }

  function toggleDrawer() {
    state.drawer = !state.drawer;
    $('#drawer').classList.toggle('open', state.drawer);
    const split = $('#drawerSplit');
    if (split) split.hidden = !state.drawer;
    state.layoutKey = '';
    state.needsDraw = true;
  }

  function applyPack(parsed, options) {
    const opts = options || {};
    if (!parsed || parsed.format !== E.FORMAT) {
      throw new Error('format 不是 ' + E.FORMAT);
    }
    if (!Array.isArray(parsed.effects) || !parsed.effects.length) {
      throw new Error('effects 不是非空数组');
    }
    pushUndo();
    if (opts.append) {
      const appended = [];
      for (const rawEffect of parsed.effects) {
        const effect = hydrateEffect(rawEffect);
        effect.id = uniqueId(rawEffect && rawEffect.id ? rawEffect.id : 'effect');
        state.pack.effects.push(effect);
        appended.push(effect);
      }
      state.selected = state.pack.effects.length - appended.length;
    } else {
      state.pack = {
        format: E.FORMAT,
        version: E.PACK_VERSION,
        name: typeof parsed.name === 'string' ? parsed.name : '',
        author: typeof parsed.author === 'string' ? parsed.author : '',
        effects: parsed.effects.map(hydrateEffect)
      };
      state.selected = 0;
    }
    state.preview.positionMs = 0;
    state.model = null;
    state.layoutKey = '';
    state.ticksKey = '';
    markDirty();
    renderAll();
    resetInspectorScroll();
    scheduleSave();
    return E.normalizePack(publicPack());
  }

  function startJsonEdit() {
    const pack = publicPack();
    $('#jsonEditor').value = JSON.stringify(pack, null, 2);
    $('#jsonOut').hidden = true;
    $('#jsonEditor').hidden = false;
    $('#jsonEditBar').hidden = false;
    $('#jsonEditBtn').hidden = true;
    state.jsonEditing = true;
    refreshJson();
    $('#jsonEditor').focus();
  }

  function cancelJsonEdit() {
    state.jsonEditing = false;
    $('#jsonEditor').hidden = true;
    $('#jsonOut').hidden = false;
    $('#jsonEditBar').hidden = true;
    $('#jsonEditBtn').hidden = false;
    refreshJson();
  }

  function applyJsonEdit() {
    try {
      const parsed = JSON.parse($('#jsonEditor').value);
      const result = applyPack(parsed, { append: false });
      state.jsonEditing = false;
      cancelJsonEdit();
      if (!result.ok) {
        openDrawer();
        toast('已应用 JSON，但有 ' +
          result.entries.filter((entry) => !entry.ok).length + ' 个效果结构非法', 'err');
      } else {
        toast('已应用 JSON（' + result.pack.effects.length + ' 个效果）');
      }
    } catch (error) {
      toast('JSON 应用失败：' + (error && error.message ? error.message : String(error)), 'err');
    }
  }

  function formatJsonEdit() {
    try {
      const parsed = JSON.parse($('#jsonEditor').value);
      $('#jsonEditor').value = JSON.stringify(parsed, null, 2);
      toast('已格式化');
    } catch (error) {
      toast('JSON 语法错误：' + (error && error.message ? error.message : String(error)), 'err');
    }
  }

  function exportPack(options) {
    const opts = options || {};
    const result = E.normalizePack(publicPack());
    const invalidEntries = result.entries.filter((entry) => !entry.ok);
    if (result.errors.length) {
      openDrawer();
      toast('包级错误：' + result.errors[0], 'err');
      return;
    }
    if (!result.pack.effects.length) {
      toast('没有可导出的效果', 'err');
      openDrawer();
      return;
    }
    if (invalidEntries.length && !opts.skipInvalid) {
      openDrawer();
      toast('存在结构错误，已打开校验面板；修复后可导出，或使用「紧凑导出」跳过非法条目', 'err');
      return;
    }
    const text = opts.compact
      ? JSON.stringify(result.pack)
      : JSON.stringify(result.pack, null, 2);
    const bytes = E.bytesOf(text);
    if (bytes > E.MAX_PACK_BYTES) {
      toast('导出文件 ' + bytes + ' 字节，超过 64 KiB 上限', 'err');
      return;
    }
    download(text, slug(result.pack.name) + '.hgwordanim.json');
    const brokenAsm = state.pack.effects.filter((effect) =>
      Array.isArray(effect._asmErrors) && effect._asmErrors.length
    ).length;
    toast('已导出 ' + (bytes / 1024).toFixed(1) + ' KiB' +
      (invalidEntries.length ? '（跳过 ' + invalidEntries.length + ' 个非法效果）' : '') +
      (brokenAsm ? '（' + brokenAsm + ' 个程序档使用上次成功汇编版本）' : ''));
  }

  function copyJson() {
    const result = E.normalizePack(publicPack());
    const text = JSON.stringify(result.pack, null, 2);
    copyText(text, () => toast('已复制规范化 JSON（' + (E.bytesOf(text) / 1024).toFixed(1) + ' KiB）'));
  }

  function copyText(text, done) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done).catch(() => fallbackCopy(text, done));
    } else {
      fallbackCopy(text, done);
    }
  }

  function copySingleEffect(effect) {
    const result = E.normalizeEffectSpec(publicEffect(effect));
    if (!result.ok) {
      toast('效果结构非法：' + result.errors[0], 'err');
      return;
    }
    const text = JSON.stringify(result.effect, null, 2);
    copyText(text, () => toast('已复制效果 JSON（' + (E.bytesOf(text) / 1024).toFixed(1) + ' KiB）'));
  }

  function exportSingleEffect(effect) {
    const result = E.normalizeEffectSpec(publicEffect(effect));
    if (!result.ok) {
      toast('效果结构非法：' + result.errors[0], 'err');
      return;
    }
    const pack = {
      format: E.FORMAT,
      version: E.PACK_VERSION,
      name: (effect.name || effect.id || '效果').slice(0, 48),
      author: state.pack.author || '',
      effects: [result.effect]
    };
    const text = JSON.stringify(pack, null, 2);
    const bytes = E.bytesOf(text);
    if (bytes > E.MAX_PACK_BYTES) {
      toast('导出文件超过 64 KiB', 'err');
      return;
    }
    download(text, slug(effect.id || 'effect') + '.hgwordanim.json');
    toast('已导出单个效果（' + (bytes / 1024).toFixed(1) + ' KiB）');
  }

  function fallbackCopy(text, done) {
    const textarea = document.createElement('textarea');
    textarea.value = text;
    textarea.style.position = 'fixed';
    textarea.style.opacity = '0';
    document.body.appendChild(textarea);
    textarea.select();
    try {
      document.execCommand('copy');
      done();
    } catch (error) {
      toast('复制失败，请手动选择 JSON', 'err');
    }
    textarea.remove();
  }

  // ---------------------------------------------------------------------------
  // Inspector events
  // ---------------------------------------------------------------------------

  function syncPaired(target, key, value) {
    $$('[data-fx="' + key + '"]', $('#inspector')).forEach((node) => {
      if (node !== target) node.value = value;
    });
  }

  function updateInspectorTitles(effect) {
    const title = $('#inspector .insp-title');
    const sub = $('#inspector .insp-sub');
    if (title) title.textContent = effect.name || effect.id;
    if (sub) sub.textContent = effect.id || '?';
  }

  function onInspectorInput(event) {
    const effect = currentEffect();
    if (!effect) return;
    const target = event.target;

    if (target.id === 'asmEditor') {
      effect._asm = target.value;
      updateAsmGutter();
      clearTimeout(state.asmTimer);
      state.asmTimer = setTimeout(() => assembleProgram(effect), 320);
      scheduleSave();
      return;
    }
    if (target.id === 'asmLocals') {
      effect._maxLocals = E.clamp(Math.trunc(Number(target.value) || 0), 0, 24);
      assembleProgram(effect);
      scheduleSave();
      return;
    }
    if (target.id === 'glowSurface') {
      state.preview.glowEnabled = target.checked;
      $('#glowEnabled').checked = target.checked;
      state.needsDraw = true;
      drawGlowPreview();
      scheduleSave();
      return;
    }
    if (target.id === 'glowColor') {
      state.preview.palette.glow = target.value.toUpperCase();
      $('#palGlow').value = target.value;
      state.needsDraw = true;
      drawGlowPreview();
      scheduleSave();
      return;
    }
    if (target.dataset.fx) {
      const key = target.dataset.fx;
      let value;
      if (target.type === 'checkbox') value = target.checked;
      else if (target.type === 'number' || target.type === 'range') value = Number(target.value);
      else value = target.value;
      if (key === 'longSyllableMs') value = Math.round(value);
      effect[key] = value;
      markDirty();
      if (key === 'id' || key === 'name') {
        updateInspectorTitles(effect);
        renderSidebar();
      }
      if (key === 'unsungDim') {
        const hint = target.closest('.field') && target.closest('.field').querySelector('.hint');
        if (hint) hint.textContent = '实际亮度 ' + Math.round(E.steadyTextAlpha(value) * 100) + '%';
      }
      syncPaired(target, key, value);
      refreshInspectorValidation(effect);
      if (key === 'longSyllableMs') renderTimingTable();
      if (key === 'glow') {
        renderTimingTable();
        drawGlowPreview();
      }
      if (key === 'glow' || key === 'stagger') refreshGlowPresetState(effect);
      scheduleJson();
      scheduleSave();
      return;
    }
    if (target.hasAttribute('data-track-prop')) {
      const track = effect.tracks[Number(target.dataset.trackProp)];
      if (!track) return;
      track.property = target.value;
      markDirty();
      renderInspector();
      scheduleJson();
      scheduleSave();
      return;
    }
    if (target.hasAttribute('data-track-long')) {
      const track = effect.tracks[Number(target.dataset.trackLong)];
      if (!track) return;
      track.longOnly = target.checked;
      markDirty();
      renderInspector();
      scheduleJson();
      scheduleSave();
      return;
    }
    if (target.dataset.kf) {
      const track = effect.tracks[Number(target.dataset.ti)];
      if (!track) return;
      const keyframe = track.keyframes[Number(target.dataset.ki)];
      if (!keyframe) return;
      const field = target.dataset.kf;
      keyframe[field] = field === 'easing' ? target.value : Number(target.value);
      markDirty();
      const svg = $('#inspector svg.curve[data-curve="' + target.dataset.ti + '"]');
      if (svg) drawCurve(svg, track);
      refreshGlowPresetState(effect);
      refreshInspectorValidation(effect);
      scheduleJson();
      scheduleSave();
      return;
    }
    if (target.dataset.color === 'color' || target.dataset.color === 'at' || target.dataset.color === 'easing') {
      const colorIndex = Number(target.dataset.ci);
      const keyframe = effect.colors[colorIndex];
      if (!keyframe) return;
      if (target.dataset.color === 'color') {
        const existing = E.parseColor(keyframe.color);
        const alpha = existing == null ? 255 : (existing >>> 24) & 0xFF;
        const rgb = parseInt(target.value.slice(1), 16);
        keyframe.color = E.formatColor(((alpha << 24) | rgb) | 0);
        const row = target.closest('.kf-row');
        const hexInput = row && row.querySelector('[data-color="hex"]');
        if (hexInput) hexInput.value = keyframe.color;
      } else if (target.dataset.color === 'at') {
        keyframe.at = Number(target.value);
      } else {
        keyframe.easing = target.value;
      }
      markDirty();
      const bar = target.closest('.section') && target.closest('.section').querySelector('.color-bar');
      if (bar) {
        const wrapper = document.createElement('div');
        wrapper.innerHTML = colorBar(effect);
        bar.replaceWith(wrapper.firstChild);
      }
      refreshInspectorValidation(effect);
      scheduleJson();
      scheduleSave();
    }
  }

  function onInspectorChange(event) {
    const effect = currentEffect();
    if (!effect) return;
    const target = event.target;
    if (target.id === 'programTemplateReplace' || target.id === 'programTemplate') {
      const template = T.PROGRAM[target.value];
      const hint = $('#' + (target.id === 'programTemplateReplace' ? 'programTemplateReplaceHint' : 'programTemplateHint'));
      if (hint && template) hint.textContent = template.description || '';
      return;
    }
    if (target.dataset.color === 'hex') {
      const colorIndex = Number(target.dataset.ci);
      const keyframe = effect.colors[colorIndex];
      if (!keyframe) return;
      keyframe.color = target.value;
      markDirty();
      const row = target.closest('.kf-row');
      const colorInput = row && row.querySelector('[data-color="color"]');
      if (colorInput) colorInput.value = hex6(keyframe.color);
      renderInspector();
      scheduleJson();
      scheduleSave();
    }
  }

  function defaultKeyframes(property) {
    if (property === 'sweepProgress') {
      return [
        { at: 0, value: 0, easing: 'linear' },
        { at: 1, value: 1, easing: 'linear' }
      ];
    }
    const value = E.PROPERTY_DEFAULTS[property] == null ? 0 : E.PROPERTY_DEFAULTS[property];
    return [
      { at: 0, value, easing: 'linear' },
      { at: 1, value, easing: 'linear' }
    ];
  }

  function addKeyframe(effect, trackIndex) {
    const track = effect.tracks[trackIndex];
    if (!track) return;
    const sorted = track.keyframes.slice().sort((a, b) => a.at - b.at);
    let at = 0.5;
    if (sorted.length >= 2) {
      let bestGap = -1;
      for (let index = 0; index < sorted.length - 1; index++) {
        const gap = sorted[index + 1].at - sorted[index].at;
        if (gap > bestGap) {
          bestGap = gap;
          at = (sorted[index].at + sorted[index + 1].at) / 2;
        }
      }
    } else if (sorted.length === 1) {
      at = sorted[0].at < 0.5 ? 0.75 : 0.25;
    }
    const normalized = E.normalizeNumericKeyframes(track.keyframes, track.property);
    const value = normalized.ok ? E.evaluateTrack(normalized.keyframes, at) : 0;
    track.keyframes.push({ at: E.round3(at), value: E.round3(value), easing: 'linear' });
  }

  function applyGlowPreset(effect, presetKey) {
    const preset = GLOW_PRESETS[presetKey];
    if (!preset) return;
    const remaining = presetKey === 'off'
      ? effect.tracks.slice()
      : effect.tracks.filter((track) =>
        track.property !== 'glowAlpha' && track.property !== 'glowRadius');
    if (remaining.length + preset.tracks.length > E.MAX_TRACKS) {
      toast('轨道超过 ' + E.MAX_TRACKS + ' 条，先删掉一些轨道');
      return;
    }
    pushUndo();
    effect.glow = preset.glow;
    if (preset.stagger != null) effect.stagger = preset.stagger;
    if (presetKey !== 'off') {
      effect.tracks = remaining;
      for (const track of preset.tracks) {
        effect.tracks.push({
          property: track.property,
          longOnly: track.longOnly === true,
          keyframes: track.keyframes.map((keyframe) => ({ ...keyframe }))
        });
      }
    }
    markDirty();
    renderInspector();
    renderTimingTable();
    toast('已应用辉光预设：' + (GLOW_PRESET_LABELS[presetKey] || presetKey));
    scheduleJson();
    scheduleSave();
  }

  function glowTracksSignature(tracks) {
    return tracks
      .filter((track) => track.property === 'glowAlpha' || track.property === 'glowRadius')
      .map((track) => {
        const normalized = E.normalizeNumericKeyframes(track.keyframes, track.property);
        return {
          property: track.property,
          longOnly: track.longOnly === true,
          keyframes: normalized.ok ? normalized.keyframes : null
        };
      })
      .sort((a, b) => (a.property + a.longOnly).localeCompare(b.property + b.longOnly));
  }

  function glowPresetKey(effect) {
    if (!effect) return null;
    if (effect.glow === 'none') return 'off';
    const effectTracks = glowTracksSignature(effect.tracks || []);
    for (const key of Object.keys(GLOW_PRESETS)) {
      if (key === 'off') continue;
      const preset = GLOW_PRESETS[key];
      if (preset.glow !== effect.glow) continue;
      if (preset.stagger != null && effect.stagger !== preset.stagger) continue;
      if (JSON.stringify(glowTracksSignature(preset.tracks)) === JSON.stringify(effectTracks)) {
        return key;
      }
    }
    return null;
  }

  function refreshGlowPresetState(effect) {
    const active = glowPresetKey(effect);
    document.querySelectorAll('[data-act="glow-preset"]').forEach((button) => {
      const isActive = button.dataset.preset === active;
      button.classList.toggle('active', isActive);
      button.setAttribute('aria-pressed', isActive ? 'true' : 'false');
    });
    const stateLabel = $('#glowPresetState');
    if (stateLabel) {
      stateLabel.innerHTML = active
        ? '当前预设：<b>' + esc(GLOW_PRESET_LABELS[active]) + '</b>'
        : '当前：<b>自定义</b>（手动轨道）';
    }
  }

  function onInspectorClick(event) {
    const effect = currentEffect();
    if (!effect) return;
    const lineItem = event.target.closest('.msg-list li[data-line]');
    if (lineItem) {
      const editor = $('#asmEditor');
      if (editor) {
        const lineNumber = Number(lineItem.dataset.line);
        const lines = editor.value.split('\n');
        let start = 0;
        for (let index = 0; index < lineNumber - 1 && index < lines.length; index++) {
          start += lines[index].length + 1;
        }
        const end = start + (lines[lineNumber - 1] || '').length;
        editor.focus();
        editor.setSelectionRange(start, end);
        const lineHeight = parseFloat(getComputedStyle(editor).lineHeight) || 17;
        const scrollTop = Math.max(0, (lineNumber - 3) * lineHeight);
        editor.scrollTop = scrollTop;
        requestAnimationFrame(() => {
          editor.scrollTop = scrollTop;
          updateAsmGutter();
        });
        updateAsmGutter();
      }
      return;
    }
    const button = event.target.closest('[data-act]');
    if (!button) return;
    const action = button.dataset.act;

    if (action === 'glow-preset') {
      applyGlowPreset(effect, button.dataset.preset);
      return;
    }

    if (action === 'fix-baseline') {
      const fixed = fixBaselineSource(effect._asm || '');
      if (!fixed) {
        toast('没有找到可自动修正的基线模式', 'err');
        return;
      }
      pushUndo();
      effect._asm = fixed;
      assembleProgram(effect);
      renderInspector();
      toast('已把 drawText 的基线改为 wordField baseline');
      return;
    }

    if (action === 'add-track') {
      const select = $('#newTrackProperty');
      const property = select ? select.value : '';
      if (!property) return;
      if (effect.tracks.length >= E.MAX_TRACKS) {
        toast('轨道最多 ' + E.MAX_TRACKS + ' 条');
        return;
      }
      if (effect.tracks.some((track) => track.property === property && track.longOnly !== true)) {
        toast('该属性已有普通轨道；可勾选现有轨道的「长」创建长音节轨道');
        return;
      }
      pushUndo();
      effect.tracks.push({ property, longOnly: false, keyframes: defaultKeyframes(property) });
      markDirty();
      toast('已添加轨道：' + (PROPERTY_LABELS[property] || property));
      renderInspector();
      scheduleJson();
      scheduleSave();
      return;
    }

    if (action === 'del-track') {
      pushUndo();
      effect.tracks.splice(Number(button.dataset.ti), 1);
      markDirty();
      renderInspector();
      scheduleJson();
      scheduleSave();
      return;
    }

    if (action === 'add-kf') {
      const track = effect.tracks[Number(button.dataset.ti)];
      if (!track) return;
      if (track.keyframes.length >= E.MAX_KEYFRAMES_PER_TRACK) {
        toast('单条轨道最多 ' + E.MAX_KEYFRAMES_PER_TRACK + ' 个关键帧');
        return;
      }
      pushUndo();
      addKeyframe(effect, Number(button.dataset.ti));
      markDirty();
      toast('已添加关键帧');
      renderInspector();
      scheduleJson();
      scheduleSave();
      return;
    }

    if (action === 'add-kf-at-t') {
      const track = effect.tracks[Number(button.dataset.ti)];
      if (!track) return;
      if (track.keyframes.length >= E.MAX_KEYFRAMES_PER_TRACK) {
        toast('单条轨道最多 ' + E.MAX_KEYFRAMES_PER_TRACK + ' 个关键帧');
        return;
      }
      const current = currentWordState();
      if (!current) {
        toast('示例歌词为空');
        return;
      }
      const at = E.round3(current.frame ? current.frame.t : current.progress);
      if (track.keyframes.some((keyframe) => Math.abs(keyframe.at - at) < 0.005)) {
        toast('当前 t=' + at + ' 已有关键帧');
        return;
      }
      const normalized = E.normalizeNumericKeyframes(track.keyframes, track.property);
      const value = normalized.ok ? E.evaluateTrack(normalized.keyframes, at) : 0;
      pushUndo();
      track.keyframes.push({ at, value: E.round3(value), easing: 'linear' });
      markDirty();
      toast('已在 t=' + at + ' 加帧');
      renderInspector();
      scheduleJson();
      scheduleSave();
      return;
    }

    if (action === 'del-kf') {
      const track = effect.tracks[Number(button.dataset.ti)];
      if (!track || track.keyframes.length <= 1) return;
      pushUndo();
      track.keyframes.splice(Number(button.dataset.ki), 1);
      markDirty();
      renderInspector();
      scheduleJson();
      scheduleSave();
      return;
    }

    if (action === 'add-color') {
      pushUndo();
      const normalized = E.normalizeEffectSpec(publicEffect(effect));
      const existing = normalized.ok && normalized.effect.colors.length
        ? E.evaluateColor(normalized.effect.colors, 0.5)
        : null;
      effect.colors.push({
        at: 0.5,
        color: existing == null ? '#FFFFFFFF' : E.formatColor(existing),
        easing: 'linear'
      });
      markDirty();
      toast('已添加颜色帧');
      renderInspector();
      scheduleJson();
      scheduleSave();
      return;
    }

    if (action === 'del-color') {
      pushUndo();
      effect.colors.splice(Number(button.dataset.ci), 1);
      markDirty();
      renderInspector();
      scheduleJson();
      scheduleSave();
      return;
    }

    if (action === 'add-program') {
      const select = $('#programTemplate');
      const key = select ? select.value : 'bounce';
      const template = T.PROGRAM[key];
      applyProgramTemplate(effect, key);
      if (template) toast('已添加程序档：' + template.name);
      return;
    }

    if (action === 'replace-program-template') {
      const select = $('#programTemplateReplace');
      const key = select ? select.value : 'hello';
      const template = T.PROGRAM[key];
      if (!template) return;
      applyProgramTemplate(effect, key);
      toast('已用模板「' + template.name + '」替换程序');
      return;
    }

    if (action === 'debug-frame') {
      debugCurrentFrame(effect);
      return;
    }

    if (action === 'remove-program') {
      pushUndo();
      effect.program = null;
      effect._asm = '';
      effect._asmErrors = [];
      effect._asmWarnings = [];
      effect._asmStats = null;
      effect._asmLineMap = null;
      effect._debug = null;
      markDirty();
      renderInspector();
      renderSidebar();
      scheduleJson();
      scheduleSave();
      return;
    }

    if (action === 'disasm') {
      if (!effect.program) return;
      pushUndo();
      effect._asm = E.disassemble(effect.program);
      effect._asmErrors = [];
      effect._asmWarnings = [];
      assembleProgram(effect);
      renderInspector();
      return;
    }

    if (action === 'dup-effect') {
      duplicateEffect(state.selected);
      return;
    }
    if (action === 'del-effect') {
      deleteEffect(state.selected);
      return;
    }
    if (action === 'export-effect') {
      exportSingleEffect(effect);
      return;
    }
    if (action === 'copy-effect') {
      copySingleEffect(effect);
      return;
    }
    if (action === 'move-up') {
      moveEffect(state.selected, -1);
      return;
    }
    if (action === 'move-down') {
      moveEffect(state.selected, 1);
    }
  }

  function assembleProgram(effect) {
    const result = E.assemble(effect._asm || '', { maxLocals: effect._maxLocals == null ? 16 : effect._maxLocals });
    effect._asmStats = result.stats;
    effect._asmErrors = result.errors;
    effect._asmWarnings = result.warnings;
    effect._debug = null;
    if (result.ok) {
      effect.program = result.program;
      effect._asmLineMap = result.stats.lineMap;
    } else {
      effect._asmLineMap = null;
    }
    markDirty();
    if (currentEffect() === effect) {
      updateAsmStatus(effect);
      refreshInspectorValidation(effect);
    }
    scheduleJson();
    scheduleSave();
  }

  function updateAsmStatus(effect) {
    const status = $('#asmStatus');
    if (!status) return;
    const errors = Array.isArray(effect._asmErrors) ? effect._asmErrors : [];
    const warnings = Array.isArray(effect._asmWarnings) ? effect._asmWarnings : [];
    status.className = 'asm-status ' + (errors.length ? 'bad' : 'ok');
    status.textContent = errors.length
      ? '汇编失败：' + errors.length + ' 个错误（预览使用上一次成功汇编的程序）'
      : '✓ 可以运行 · ' + asmStatsText(effect);
    let list = $('#asmErrors');
    if (!list && (errors.length || warnings.length)) {
      list = document.createElement('ul');
      list.className = 'msg-list';
      list.id = 'asmErrors';
      status.parentNode.appendChild(list);
    }
    if (list) {
      list.innerHTML = errors.map((error) => {
        const lineMatch = /第 (\d+) 行/.exec(error);
        return '<li' + (lineMatch ? ' data-line="' + lineMatch[1] + '"' : '') + '>' + esc(error) + '</li>';
      }).concat(warnings.map((warning) => '<li class="warn">' + esc(warning) + '</li>'))
        .join('');
      if (!errors.length && !warnings.length) list.remove();
    }
  }

  function updateAsmGutter() {
    const editor = $('#asmEditor');
    const gutter = $('#asmGutter');
    if (!editor || !gutter) return;
    const lineCount = editor.value.split('\n').length;
    if (gutter.dataset.lines !== String(lineCount)) {
      gutter.dataset.lines = String(lineCount);
      const lines = [];
      for (let index = 1; index <= lineCount; index++) lines.push(String(index));
      gutter.textContent = lines.join('\n');
    }
    gutter.scrollTop = editor.scrollTop;
  }

  function applyProgramTemplate(effect, key) {
    const template = T.PROGRAM[key];
    if (!template) return;
    const result = E.assemble(template.source, { maxLocals: template.maxLocals });
    if (!result.ok) {
      toast('模板汇编失败：' + result.errors[0], 'err');
      return;
    }
    pushUndo();
    effect.program = result.program;
    effect._asm = template.source;
    effect._maxLocals = template.maxLocals;
    effect._asmStats = result.stats;
    effect._asmErrors = [];
    effect._asmWarnings = result.warnings;
    effect._asmLineMap = result.stats.lineMap;
    effect._debug = null;
    markDirty();
    renderInspector();
    renderSidebar();
    scheduleJson();
    scheduleSave();
  }

  // ---------------------------------------------------------------------------
  // Effect list actions
  // ---------------------------------------------------------------------------

  function makeDslEffect() {
    const id = uniqueId('effect');
    return hydrateEffect({
      id,
      name: '新效果',
      longSyllableMs: 700,
      stagger: 0,
      sweep: true,
      sweepBand: 0.28,
      unsungDim: 0.35,
      glow: 'none',
      tracks: [{
        property: 'scale',
        keyframes: [
          { at: 0, value: 1, easing: 'easeOut' },
          { at: 0.5, value: 1.12, easing: 'easeInOut' },
          { at: 1, value: 1, easing: 'linear' }
        ]
      }],
      colors: []
    });
  }

  function makeProgramEffect() {
    const template = T.PROGRAM.bounce;
    const result = E.assemble(template.source, { maxLocals: template.maxLocals });
    const effect = hydrateEffect({
      id: uniqueId('program_effect'),
      name: '程序效果',
      longSyllableMs: 700,
      stagger: 0,
      sweep: true,
      sweepBand: 0.28,
      unsungDim: 0.35,
      glow: 'none',
      tracks: [],
      colors: [],
      program: result.ok ? result.program : null
    });
    effect._asm = template.source;
    effect._maxLocals = template.maxLocals;
    effect._asmStats = result.stats;
    effect._asmErrors = result.errors;
    effect._asmWarnings = result.warnings;
    effect._asmLineMap = result.ok ? result.stats.lineMap : null;
    effect._debug = null;
    return effect;
  }

  function duplicateEffect(index) {
    const source = state.pack.effects[index];
    if (!source) return;
    pushUndo();
    const copy = hydrateEffect(publicEffect(source));
    copy.id = uniqueId((source.id || 'effect') + '_copy');
    copy.name = ((source.name || source.id || '效果') + ' 副本').slice(0, 48);
    if (typeof source._asm === 'string' && source._asm) copy._asm = source._asm;
    copy._maxLocals = source._maxLocals == null ? 16 : source._maxLocals;
    copy._asmStats = source._asmStats;
    copy._asmErrors = source._asmErrors || [];
    copy._asmWarnings = source._asmWarnings || [];
    copy._asmLineMap = source._asmLineMap || null;
    copy._debug = null;
    state.pack.effects.splice(index + 1, 0, copy);
    state.selected = index + 1;
    markDirty();
    renderAll();
    resetInspectorScroll();
    scheduleSave();
  }

  function deleteEffect(index) {
    if (state.pack.effects.length <= 1) {
      toast('至少保留一个效果');
      return;
    }
    pushUndo();
    state.pack.effects.splice(index, 1);
    state.selected = E.clamp(index, 0, state.pack.effects.length - 1);
    markDirty();
    renderAll();
    resetInspectorScroll();
    scheduleSave();
  }

  function moveEffect(index, delta) {
    const target = index + delta;
    if (target < 0 || target >= state.pack.effects.length) return;
    pushUndo();
    const effects = state.pack.effects;
    const temp = effects[index];
    effects[index] = effects[target];
    effects[target] = temp;
    state.selected = target;
    markDirty();
    renderAll();
    resetInspectorScroll();
    scheduleSave();
  }

  // ---------------------------------------------------------------------------
  // Persistence
  // ---------------------------------------------------------------------------

  function scheduleSave() {
    clearTimeout(state.saveTimer);
    state.saveTimer = setTimeout(saveNow, 450);
  }

  function saveNow() {
    try {
      const pack = JSON.parse(JSON.stringify(
        state.pack,
        (key, value) => (key === '_debug' ? undefined : value)
      ));
      localStorage.setItem(STORAGE_KEY, JSON.stringify({
        pack,
        selected: state.selected,
        preview: state.preview,
        layout: state.panels
      }));
    } catch (error) {
      // 存储满/隐私模式：忽略，编辑器仍可用。
    }
  }

  function defaultPack() {
    return {
      format: E.FORMAT,
      version: E.PACK_VERSION,
      name: 'Starter Word Effects',
      author: 'HyperGlow',
      effects: DEFAULT_DSL_KEYS.map((key) => hydrateEffect(T.DSL[key]))
    };
  }

  function loadInitial() {
    let stored = null;
    try {
      stored = JSON.parse(localStorage.getItem(STORAGE_KEY));
    } catch (error) {
      stored = null;
    }
    if (stored && stored.pack && stored.pack.format === E.FORMAT &&
        Array.isArray(stored.pack.effects) && stored.pack.effects.length) {
      state.pack = {
        format: E.FORMAT,
        version: E.PACK_VERSION,
        name: stored.pack.name || '',
        author: stored.pack.author || '',
        effects: stored.pack.effects.map(hydrateEffect)
      };
      state.selected = E.clamp(stored.selected || 0, 0, state.pack.effects.length - 1);
      if (stored.preview && typeof stored.preview === 'object') {
        Object.assign(state.preview, stored.preview);
      }
    } else {
      state.pack = defaultPack();
      state.selected = 0;
    }
    if (!THEMES[state.preview.theme]) {
      state.preview.theme = 'dark';
      state.preview.palette = Object.assign({}, THEMES.dark.palette);
    }
    if (!state.preview.palette || typeof state.preview.palette !== 'object') {
      state.preview.palette = Object.assign({}, THEMES[state.preview.theme].palette);
    }
    state.preview.textSize = E.clamp(Number(state.preview.textSize) || 44, 24, 72);
    state.preview.speed = Number(state.preview.speed) || 1;
    state.preview.maxLines = E.clamp(Math.trunc(Number(state.preview.maxLines) || 4), 1, 6);
    state.preview.canvasHeight = E.clamp(Math.trunc(Number(state.preview.canvasHeight) || 380), 260, 720);
    if (typeof state.preview.autoFit !== 'boolean') state.preview.autoFit = true;
    if (!FONT_STACKS[state.preview.fontFamily]) state.preview.fontFamily = 'sans';
    if (typeof state.preview.bgColor !== 'string' || E.parseColor(state.preview.bgColor) == null) {
      state.preview.bgColor = null;
    }
    if (!Array.isArray(state.preview.lineTimings)) state.preview.lineTimings = null;
    if (typeof state.preview.lrcSource !== 'string') state.preview.lrcSource = '';
    const storedLayout = stored && stored.layout && typeof stored.layout === 'object' ? stored.layout : {};
    state.panels.sidebarWidth = E.clamp(Math.trunc(Number(storedLayout.sidebarWidth) || 244), 180, 420);
    state.panels.inspectorWidth = E.clamp(Math.trunc(Number(storedLayout.inspectorWidth) || 402), 320, 640);
    state.panels.drawerHeight = E.clamp(Math.trunc(Number(storedLayout.drawerHeight) || 320), 200, 600);
  }

  // ---------------------------------------------------------------------------
  // Static event wiring
  // ---------------------------------------------------------------------------

  function renderAll() {
    state.dirty = true;
    state.needsDraw = true;
    $('#packName').value = state.pack.name || '';
    $('#packAuthor').value = state.pack.author || '';
    renderSidebar();
    renderInspector();
    renderTimingTable();
    refreshJson();
    updateUndoButtons();
    updatePreviewHeader(currentEffect());
  }

  function resetInspectorScroll() {
    const host = $('#inspector');
    if (host) host.scrollTop = 0;
  }

  function applyLayout() {
    const workspace = $('.workspace');
    if (workspace) {
      workspace.style.setProperty('--sidebar-w', state.panels.sidebarWidth + 'px');
      workspace.style.setProperty('--inspector-w', state.panels.inspectorWidth + 'px');
    }
    document.documentElement.style.setProperty('--drawer-h', state.panels.drawerHeight + 'px');
    state.layoutKey = '';
    state.needsDraw = true;
  }

  function setupSplitter(element, axis, getStart, applySize) {
    if (!element) return;
    let drag = null;
    const finish = (event) => {
      if (!drag) return;
      drag = null;
      element.classList.remove('dragging');
      try {
        element.releasePointerCapture(event.pointerId);
      } catch (error) {
        // 指针捕获已释放时忽略。
      }
      state.layoutKey = '';
      state.needsDraw = true;
      scheduleSave();
    };
    element.addEventListener('pointerdown', (event) => {
      event.preventDefault();
      drag = {
        start: axis === 'x' ? event.clientX : event.clientY,
        size: getStart()
      };
      element.classList.add('dragging');
      element.setPointerCapture(event.pointerId);
    });
    element.addEventListener('pointermove', (event) => {
      if (!drag) return;
      const current = axis === 'x' ? event.clientX : event.clientY;
      applySize(drag.size, current - drag.start);
      state.layoutKey = '';
      state.needsDraw = true;
      scheduleSave();
    });
    element.addEventListener('pointerup', finish);
    element.addEventListener('pointercancel', finish);
  }

  function bindStaticEvents() {
    $('#packName').addEventListener('input', (event) => {
      state.pack.name = event.target.value;
      scheduleJson();
      scheduleSave();
    });
    $('#packAuthor').addEventListener('input', (event) => {
      state.pack.author = event.target.value;
      scheduleJson();
      scheduleSave();
    });

    $('#effectList').addEventListener('click', (event) => {
      const actionButton = event.target.closest('[data-act]');
      if (actionButton) {
        event.stopPropagation();
        const index = Number(actionButton.dataset.index);
        if (actionButton.dataset.act === 'dup') duplicateEffect(index);
        else if (actionButton.dataset.act === 'del') deleteEffect(index);
        return;
      }
      const item = event.target.closest('.effect-item');
      if (item) selectEffect(Number(item.dataset.index));
    });
    $('#effectList').addEventListener('keydown', (event) => {
      if (event.key !== 'Enter' && event.key !== ' ') return;
      const item = event.target.closest('.effect-item');
      if (item) {
        event.preventDefault();
        selectEffect(Number(item.dataset.index));
      }
    });

    $('#addDsl').addEventListener('click', () => {
      pushUndo();
      state.pack.effects.push(makeDslEffect());
      state.selected = state.pack.effects.length - 1;
      state.preview.positionMs = 0;
      markDirty();
      renderAll();
      resetInspectorScroll();
      scheduleSave();
    });
    $('#addProgram').addEventListener('click', () => {
      pushUndo();
      state.pack.effects.push(makeProgramEffect());
      state.selected = state.pack.effects.length - 1;
      state.preview.positionMs = 0;
      markDirty();
      renderAll();
      resetInspectorScroll();
      scheduleSave();
    });

    $('#dslTemplate').innerHTML = T.dslTemplateList().map((template) =>
      '<option value="' + template.key + '">' + esc(template.name) + '</option>'
    ).join('');
    $('#addTemplate').addEventListener('click', () => {
      const template = T.DSL[$('#dslTemplate').value];
      if (!template) return;
      pushUndo();
      const effect = hydrateEffect(template);
      effect.id = uniqueId(template.id || 'effect');
      state.pack.effects.push(effect);
      state.selected = state.pack.effects.length - 1;
      state.preview.positionMs = 0;
      markDirty();
      renderAll();
      resetInspectorScroll();
      scheduleSave();
    });

    $('#newPack').addEventListener('click', () => {
      pushUndo();
      state.pack = {
        format: E.FORMAT,
        version: E.PACK_VERSION,
        name: '我的效果包',
        author: '',
        effects: [makeDslEffect()]
      };
      state.selected = 0;
      state.preview.positionMs = 0;
      markDirty();
      renderAll();
      resetInspectorScroll();
      scheduleSave();
    });

    $('#importBtn').addEventListener('click', () => $('#importFile').click());
    $('#importFile').addEventListener('change', async (event) => {
      const file = event.target.files && event.target.files[0];
      event.target.value = '';
      if (!file) return;
      try {
        const text = await file.text();
        const parsed = JSON.parse(text);
        const append = $('#importMode').value === 'append';
        const result = applyPack(parsed, { append });
        if (!result.ok) {
          openDrawer();
          toast((append ? '已追加' : '已导入') + '，但有 ' +
            result.entries.filter((entry) => !entry.ok).length + ' 个效果结构非法', 'err');
        } else {
          toast((append ? '已追加 ' : '已导入 ') +
            (append ? parsed.effects.length : result.pack.effects.length) + ' 个效果');
        }
      } catch (error) {
        toast('导入失败：' + (error && error.message ? error.message : String(error)), 'err');
      }
    });

    $('#exportBtn').addEventListener('click', () => exportPack());
    $('#copyBtn').addEventListener('click', copyJson);
    $('#downloadJson').addEventListener('click', () => exportPack({ skipInvalid: true }));
    $('#downloadCompact').addEventListener('click', () => exportPack({ compact: true, skipInvalid: true }));
    $('#jsonEditBtn').addEventListener('click', startJsonEdit);
    $('#jsonApply').addEventListener('click', applyJsonEdit);
    $('#jsonFormat').addEventListener('click', formatJsonEdit);
    $('#jsonCancel').addEventListener('click', cancelJsonEdit);
    $('#drawerToggle').addEventListener('click', toggleDrawer);
    $('#undoBtn').addEventListener('click', undo);
    $('#redoBtn').addEventListener('click', redo);

    $('#inspector').addEventListener('input', onInspectorInput);
    $('#inspector').addEventListener('change', onInspectorChange);
    $('#inspector').addEventListener('click', onInspectorClick);

    $('#playBtn').addEventListener('click', togglePlay);
    $('#restartBtn').addEventListener('click', restart);
    $('#speed').addEventListener('change', (event) => {
      state.preview.speed = Number(event.target.value) || 1;
      scheduleSave();
    });
    $('#loop').addEventListener('change', (event) => {
      state.preview.loop = event.target.checked;
      scheduleSave();
    });

    const scrub = $('#scrub');
    let scrubbing = false;
    scrub.addEventListener('pointerdown', (event) => {
      scrubbing = true;
      scrub.setPointerCapture(event.pointerId);
      scrubToEvent(event);
    });
    scrub.addEventListener('pointermove', (event) => {
      if (scrubbing) scrubToEvent(event);
    });
    scrub.addEventListener('pointerup', () => { scrubbing = false; scheduleSave(); });
    scrub.addEventListener('pointercancel', () => { scrubbing = false; });
    scrub.addEventListener('keydown', (event) => {
      if (event.key === 'ArrowLeft') {
        event.preventDefault();
        seekBy(-50);
      } else if (event.key === 'ArrowRight') {
        event.preventDefault();
        seekBy(50);
      }
    });

    $('#sampleText').addEventListener('input', (event) => {
      const hadTimings = !!state.preview.lineTimings;
      state.preview.text = event.target.value;
      state.preview.lineTimings = null;
      if (hadTimings) $('#lrcSummary').textContent = '粘贴 LRC 可导入真实行时间';
      state.model = null;
      state.layoutKey = '';
      state.ticksKey = '';
      const model = getModel();
      state.preview.positionMs = E.clamp(state.preview.positionMs, 0, model.totalMs);
      state.needsDraw = true;
      renderTimingTable();
      scheduleSave();
    });
    $('#timingBody').addEventListener('click', (event) => {
      const row = event.target.closest('tr[data-start]');
      if (!row) return;
      state.preview.playing = false;
      state.preview.positionMs = Number(row.dataset.start) + 1;
      state.needsDraw = true;
      updateTransport();
    });
    $('#textSize').addEventListener('input', (event) => {
      state.preview.textSize = E.clamp(Number(event.target.value) || 44, 24, 72);
      state.layoutKey = '';
      state.needsDraw = true;
      scheduleSave();
    });
    $('#autoFit').addEventListener('change', (event) => {
      state.preview.autoFit = event.target.checked;
      state.layoutKey = '';
      state.needsDraw = true;
      scheduleSave();
    });
    $('#guideBoxes').addEventListener('change', (event) => {
      state.preview.guideBoxes = event.target.checked;
      state.needsDraw = true;
      scheduleSave();
    });
    $('#guideLong').addEventListener('change', (event) => {
      state.preview.guideLong = event.target.checked;
      state.needsDraw = true;
      scheduleSave();
    });
    $('#glowEnabled').addEventListener('change', (event) => {
      state.preview.glowEnabled = event.target.checked;
      if ($('#glowSurface')) $('#glowSurface').checked = event.target.checked;
      state.needsDraw = true;
      drawGlowPreview();
      scheduleSave();
    });

    const paletteInputs = {
      palSung: 'sung',
      palUnsung: 'unsung',
      palGlow: 'glow',
      palAccent: 'accent'
    };
    Object.keys(paletteInputs).forEach((id) => {
      $('#' + id).addEventListener('input', (event) => {
        state.preview.palette[paletteInputs[id]] = event.target.value.toUpperCase();
        if (id === 'palGlow' && $('#glowColor')) $('#glowColor').value = event.target.value;
        state.needsDraw = true;
        drawGlowPreview();
        scheduleSave();
      });
    });

    $('#previewTheme').addEventListener('change', (event) => {
      state.preview.theme = THEMES[event.target.value] ? event.target.value : 'dark';
      state.preview.palette = Object.assign({}, THEMES[state.preview.theme].palette);
      state.preview.bgColor = THEMES[state.preview.theme].bg;
      syncPaletteInputs();
      state.needsDraw = true;
      scheduleSave();
    });
    $('#maxLines').addEventListener('change', (event) => {
      state.preview.maxLines = E.clamp(Number(event.target.value) || 4, 1, 6);
      state.model = null;
      state.layoutKey = '';
      state.ticksKey = '';
      state.needsDraw = true;
      renderTimingTable();
      scheduleSave();
    });
    $('#canvasHeight').addEventListener('input', (event) => {
      state.preview.canvasHeight = E.clamp(Number(event.target.value) || 380, 260, 720);
      $('#previewWrap').style.height = state.preview.canvasHeight + 'px';
      state.layoutKey = '';
      state.needsDraw = true;
      scheduleSave();
    });
    $('#fontFamily').addEventListener('change', (event) => {
      state.preview.fontFamily = FONT_STACKS[event.target.value] ? event.target.value : 'sans';
      state.layoutKey = '';
      state.needsDraw = true;
      scheduleSave();
    });
    $('#palBg').addEventListener('input', (event) => {
      state.preview.bgColor = event.target.value.toUpperCase();
      state.needsDraw = true;
      scheduleSave();
    });
    $('#lrcSource').addEventListener('input', (event) => {
      state.preview.lrcSource = event.target.value;
      scheduleSave();
    });
    $('#lrcParse').addEventListener('click', parseLrcSource);
    $('#lrcApply').addEventListener('click', applyLrcFragment);
    $('#lrcClear').addEventListener('click', clearLrcTimings);

    document.addEventListener('keydown', (event) => {
      const tag = (event.target.tagName || '').toLowerCase();
      const editing = tag === 'input' || tag === 'textarea' || tag === 'select' || event.target.isContentEditable;
      const modifier = event.ctrlKey || event.metaKey;
      if (modifier && event.key.toLowerCase() === 's') {
        event.preventDefault();
        exportPack();
        return;
      }
      if (modifier && event.key.toLowerCase() === 'z') {
        event.preventDefault();
        if (event.shiftKey) redo(); else undo();
        return;
      }
      if (modifier && event.key.toLowerCase() === 'y') {
        event.preventDefault();
        redo();
        return;
      }
      if (modifier && event.key === 'Enter') {
        const effect = currentEffect();
        const active = document.activeElement;
        const inAsmEditor = active && active.id === 'asmEditor';
        if (effect && effect.program && (inAsmEditor || !editing)) {
          event.preventDefault();
          clearTimeout(state.asmTimer);
          assembleProgram(effect);
        }
        return;
      }
      if (editing) return;
      if (event.code === 'Space') {
        event.preventDefault();
        togglePlay();
      } else if (event.key === '[') {
        selectEffect(state.selected - 1);
      } else if (event.key === ']') {
        selectEffect(state.selected + 1);
      } else if (event.key === ',') {
        event.preventDefault();
        seekBy(-50);
      } else if (event.key === '.') {
        event.preventDefault();
        seekBy(50);
      } else if (event.key === 'Home') {
        event.preventDefault();
        restart();
      } else if (event.key === 'j' || event.key === 'J') {
        event.preventDefault();
        seekToWord(-1);
      } else if (event.key === 'k' || event.key === 'K') {
        event.preventDefault();
        seekToWord(1);
      }
    });

    setupSplitter($('#sidebarSplit'), 'x',
      () => state.panels.sidebarWidth,
      (start, delta) => {
        state.panels.sidebarWidth = E.clamp(start + delta, 180, 420);
        applyLayout();
      });
    setupSplitter($('#inspectorSplit'), 'x',
      () => state.panels.inspectorWidth,
      (start, delta) => {
        state.panels.inspectorWidth = E.clamp(start - delta, 320, 640);
        applyLayout();
      });
    setupSplitter($('#drawerSplit'), 'y',
      () => state.panels.drawerHeight,
      (start, delta) => {
        state.panels.drawerHeight = E.clamp(start - delta, 200, 600);
        applyLayout();
      });
    setupSplitter($('#canvasSplit'), 'y',
      () => state.preview.canvasHeight,
      (start, delta) => {
        state.preview.canvasHeight = E.clamp(start + delta, 260, 720);
        $('#previewWrap').style.height = state.preview.canvasHeight + 'px';
        $('#canvasHeight').value = String(state.preview.canvasHeight);
      });

    const observer = new ResizeObserver(() => {
      state.layoutKey = '';
      state.needsDraw = true;
    });
    observer.observe($('#previewWrap'));
  }

  function syncPaletteInputs() {
    $('#palSung').value = hex6(state.preview.palette.sung);
    $('#palUnsung').value = hex6(state.preview.palette.unsung);
    $('#palGlow').value = hex6(state.preview.palette.glow);
    $('#palAccent').value = hex6(state.preview.palette.accent);
    $('#palBg').value = hex6(state.preview.bgColor || (THEMES[state.preview.theme] || THEMES.dark).bg);
  }

  function syncPreviewControls() {
    $('#sampleText').value = state.preview.text;
    $('#textSize').value = state.preview.textSize;
    $('#autoFit').checked = state.preview.autoFit !== false;
    $('#guideBoxes').checked = !!state.preview.guideBoxes;
    $('#guideLong').checked = !!state.preview.guideLong;
    $('#glowEnabled').checked = !!state.preview.glowEnabled;
    $('#previewTheme').value = state.preview.theme;
    $('#maxLines').value = String(state.preview.maxLines || 4);
    $('#canvasHeight').value = String(state.preview.canvasHeight || 380);
    $('#fontFamily').value = state.preview.fontFamily || 'sans';
    $('#previewWrap').style.height = (state.preview.canvasHeight || 380) + 'px';
    $('#lrcSource').value = state.preview.lrcSource || '';
    $('#speed').value = String(state.preview.speed);
    $('#loop').checked = !!state.preview.loop;
    syncPaletteInputs();
  }

  // ---------------------------------------------------------------------------
  // Frame loop
  // ---------------------------------------------------------------------------

  function tick(timestamp) {
    try {
      const delta = state.lastTs ? Math.min(64, timestamp - state.lastTs) : 16;
      state.lastTs = timestamp;
      if (state.preview.playing) {
        const total = getModel().totalMs;
        let position = state.preview.positionMs + delta * state.preview.speed;
        if (position >= total) {
          if (state.preview.loop) position = 0;
          else {
            position = total;
            state.preview.playing = false;
          }
        }
        state.preview.positionMs = position;
        state.needsDraw = true;
      }
      if (state.needsDraw) {
        state.needsDraw = false;
        drawPreview();
        updateTransport();
      }
    } catch (error) {
      state.programError = '渲染异常：' + (error && error.message ? error.message : String(error));
      try {
        updateOverlay(previewEffect());
      } catch (ignored) {
        // 错误展示本身失败时不再抛，保持 RAF 循环。
      }
    }
    requestAnimationFrame(tick);
  }

  // ---------------------------------------------------------------------------
  // Boot
  // ---------------------------------------------------------------------------

  loadInitial();
  applyLayout();
  syncPreviewControls();
  bindStaticEvents();
  renderAll();
  try {
    drawPreview();
    updateTransport();
  } catch (error) {
    state.programError = '首帧渲染异常：' + (error && error.message ? error.message : String(error));
  }
  requestAnimationFrame(tick);
})();
