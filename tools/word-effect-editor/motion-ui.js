(function () {
'use strict';


'use strict';

// engine.js → window.WEEngine;motion.js → window.HGMotion(UMD,与主编辑器同一份实现)
const E = window.WEEngine;
const M = window.HGMotion;

let motionActive = false;

const state = {
  lyricsSrc: '',
  parsed: { lines: [], warnings: [] },
  timeline: { rows: [], total: 0, lineSec: 2 },
  tap: [],
  manualTimes: {},
  seed: 1,
  presetKey: 'ballad',
  choreoMode: 'cycle',
  layoutMode: 'auto',
  graphicMode: 'auto',
  focusMode: 'none',
  annotationMode: 'none',
  particleMode: 'none',
  lineartMode: 'none',
  manual: { enabled: false, anchorX: 0.5, anchorY: 0.5, scale: 1, rotate: 0, line: 0, word: 0, overrides: {}, sprites: [], sprite: 0, images: {} },
  theme: 'lyric',
  overrides: {},
  palette: { sung: 0xFFFFFFFF, unsung: 0x88FFFFFF, glow: 0xFF66AAFF, accent: 0xFFFFAA33 },
  built: null,        // { pack, effect, stats }
  playing: false,
  virtualClockStart: 0,
  virtualClockOffset: 0,
  lastFrameAt: 0,
  activeRowIndex: -1
};

const $ = (id) => document.getElementById('m-' + id);

// ---------------------------------------------------------------------------
// 调色 / 字体
// ---------------------------------------------------------------------------

function cssRgb(argb) {
  const c = E.colorChannels(argb | 0);
  return 'rgb(' + c.r + ',' + c.g + ',' + c.b + ')';
}
function cssColor(argb, alphaMul) {
  const c = E.colorChannels(argb | 0);
  const a = Math.max(0, Math.min(1, (c.a / 255) * (alphaMul == null ? 1 : alphaMul)));
  return 'rgba(' + c.r + ',' + c.g + ',' + c.b + ',' + a.toFixed(3) + ')';
}
const FONT_STACK = '"Noto Sans SC","PingFang SC","Microsoft YaHei",system-ui,sans-serif';
const fontString = (size) => '600 ' + size + 'px ' + FONT_STACK;

// 内置装饰素材(与 APK assets/deco 一致):预载供 sprite 盒绘制
const spriteImages = {};
for (let decorIndex = 0; decorIndex < 16; decorIndex++) {
  const image = new Image();
  image.src = 'deco/' + decorIndex + '.png';
  spriteImages[decorIndex] = image;
}

const measureCanvas = document.createElement('canvas');
const measureCtx = measureCanvas.getContext('2d');
function measure(text, sizePx) {
  measureCtx.font = fontString(sizePx);
  return measureCtx.measureText(text).width;
}

// ---------------------------------------------------------------------------
// 解析 → 时间轴 → 编排 → 程序
// ---------------------------------------------------------------------------

function readControls() {
  state.seed = Math.abs(Math.trunc(Number($('seed').value) || 1)) || 1;
  state.theme = $('theme').value;
  state.overrides = {
    waveAmp: Number($('waveAmp').value),
    waveK: Number($('waveK').value),
    rotAmp: Number($('rotAmp').value),
    bounceH: Number($('bounceH').value),
    slideD: Number($('slideD').value),
    slideStagger: Number($('slideStagger').value),
    slideAlt: $('slideAlt').value === '1',
    pulse: Number($('pulse').value),
    unsungDim: Number($('unsungDim').value),
    cycle: Number($('cycle').value),
    choreoMode: $('choreo').value,
    layoutMode: $('layout').value,
    graphicMode: $('graphic').value,
    focusMode: $('focus').value,
    annotationMode: $('annotate').value,
    particleMode: $('particle').value,
    lineartMode: $('lineart').value,
    flash: $('flash').checked,
    manual: manualPayload()
  };
  const sung = E.parseColor($('sungColor').value) || 0xFFFFFFFF;
  const glow = E.parseColor($('glowColor').value) || 0xFF66AAFF;
  const dim = E.clamp(state.overrides.unsungDim, 0.1, 0.6);
  const dimAlpha = Math.round(255 * (1 - dim) + 40);
  state.palette = {
    sung,
    unsung: (dimAlpha << 24 | (sung & 0xFFFFFF)) >>> 0,
    glow,
    accent: glow
  };
}

function refresh() {
  state.lyricsSrc = $('lyrics').value;
  state.parsed = M.parseLyrics(state.lyricsSrc);
  const parseStatus = [];
  if (state.parsed.warnings.length) parseStatus.push(state.parsed.warnings.join('\n'));
  parseStatus.push('已解析 ' + state.parsed.lines.filter((l) => l.interlude == null).length + ' 行歌词' +
    (state.parsed.lines.some((l) => l.interlude != null) ? '(含间奏)' : ''));
  $('parseStatus').textContent = parseStatus.join('\n');

  state.timeline = M.buildTimeline(state.parsed, {
    bpm: Number($('bpm').value) || 120,
    startSec: Number($('startSec').value) || 0,
    lineDurSec: Number($('lineDur').value) || 0,
    snap: $('snap').checked,
    tap: state.tap.slice(),
    manualTimes: state.manualTimes
  });
  readControls();
  const style = M.styleFromSeed(state.seed, state.theme, state.overrides);
  state.style = style;
  state.built = M.buildEffectPack(state.timeline, style, {
    title: 'Motion · ' + style.themeName
  });
  if (state.built.ok) {
    const stats = state.built.stats;
    $('programStats').innerHTML =
      '程序 <b>' + stats.instructions + '</b> 条指令(上限 512) · 常量 <b>' + stats.constants +
      '</b> · 逐帧绘制 <b>' + stats.drawOps + '</b>(上限 4096) · 估算 <b>' +
      (stats.estimatedBytes / 1024).toFixed(1) + '</b> KiB(上限 12) — 全部在预算内即可导入';
    $('frameStatus').textContent = '';
  } else {
    $('programStats').innerHTML = '<span style="color:var(--bad)">程序生成失败:' +
      state.built.errors.join(';') + '</span>';
    state.built = null;
  }
  renderLineList();
  renderManualPanel();
  // 暂停时 refresh 也要重绘预览,否则改滑杆/拖词看不到变化
  if (!state.playing) {
    try { drawFrame(currentTimeSec()); } catch (error) { /* 预览尚未就绪 */ }
  }
}

// ---------------------------------------------------------------------------
// 行列表
// ---------------------------------------------------------------------------

function renderLineList() {
  const list = $('lineList');
  const rows = state.timeline.rows;
  list.innerHTML = '';
  rows.forEach((row) => {
    const div = document.createElement('div');
    div.className = 'linerow' + (row.interlude ? ' interlude' : '') +
      (row.index === state.activeRowIndex ? ' active' : '');
    div.dataset.index = String(row.index);
    if (row.interlude) {
      div.innerHTML = '<span class="badge">间奏</span><span class="text">' +
        row.interlude + ' 秒(不渲染歌词)</span>';
    } else {
      const timeInput = document.createElement('input');
      timeInput.type = 'number';
      timeInput.step = '0.1';
      timeInput.value = row.start.toFixed(2);
      timeInput.title = '行开始时间(秒);修改后该行锁定为手动时间';
      timeInput.addEventListener('change', () => {
        const value = Number(timeInput.value);
        if (Number.isFinite(value) && value >= 0) {
          state.manualTimes[lyricIndexOf(row)] = value;
          refresh();
        }
      });
      const jump = document.createElement('button');
      jump.className = 'small';
      jump.textContent = '◎';
      jump.title = '跳到这行';
      jump.addEventListener('click', () => seekTo(row.start));
      const text = document.createElement('span');
      text.className = 'text';
      text.textContent = row.text;
      const badges = document.createElement('span');
      if (row.flash) badges.innerHTML += '<span class="badge flash">闪</span> ';
      if (row.emph) badges.innerHTML += '<span class="badge emph">强</span> ';
      if (row.shotCount > 1) badges.innerHTML += '<span class="badge">' + row.shotCount + '镜头</span>';
      if (row.manual) badges.innerHTML += '<span class="badge">手动</span>';
      div.append(timeInput, jump, text, badges);
    }
    list.appendChild(div);
  });
}

function lyricIndexOf(row) {
  // 行列表手动时间按「歌词行序号」记(间奏行不占号)
  let index = -1;
  for (const other of state.timeline.rows) {
    if (other.interlude) continue;
    index++;
    if (other.index === row.index) return index;
  }
  return index;
}

// ---------------------------------------------------------------------------
// 播放与预览
// ---------------------------------------------------------------------------

const audio = $('music');
let speed = 1;

function currentTimeSec() {
  if (audio.src && !audio.paused) return audio.currentTime;
  if (!state.playing) return state.virtualClockOffset;
  return state.virtualClockOffset + (performance.now() - state.virtualClockStart) / 1000 * speed;
}

function seekTo(seconds) {
  const total = Math.max(0.1, state.timeline.total);
  const t = Math.max(0, Math.min(total - 0.01, seconds));
  if (audio.src) {
    audio.currentTime = t;
    if (!audio.paused) audio.play();
  }
  state.virtualClockOffset = t;
  state.virtualClockStart = performance.now();
  drawFrame(t);
}

$('playBtn').addEventListener('click', () => {
  state.playing = !state.playing;
  if (state.playing) {
    if (audio.src) {
      audio.play().catch(() => {});
    } else {
      state.virtualClockStart = performance.now();
    }
    $('playBtn').textContent = '⏸ 暂停';
  } else {
    audio.pause();
    state.virtualClockOffset = currentTimeSec();
    $('playBtn').textContent = '▶ 播放';
  }
});
$('speed').addEventListener('change', () => {
  speed = Number($('speed').value) || 1;
  if (audio.src && audio.playbackRate !== undefined) audio.playbackRate = speed;
  state.virtualClockOffset = currentTimeSec();
  state.virtualClockStart = performance.now();
});
$('loop').addEventListener('change', () => {});

const previewImageCache = {};

function previewImageFor(id) {
  const data = state.manual && state.manual.images ? state.manual.images[id] : null;
  if (!data) return null;
  const key = String(id) + ':' + data.length;
  const cached = previewImageCache[id];
  if (cached && cached.key === key) return cached.image;
  const image = new Image();
  image.onload = () => {
    if (!state.playing) {
      try { drawFrame(currentTimeSec()); } catch (error) { /* ignore */ }
    }
  };
  image.src = data;
  previewImageCache[id] = { key, image };
  return image;
}

function drawFrame(tSeconds) {
  const canvas = $('preview');
  const ctx = canvas.getContext('2d');
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.clearRect(0, 0, canvas.width, canvas.height);
  const total = Math.max(0.01, state.timeline.total);
  $('timecode').textContent = formatTime(tSeconds) + ' / ' + formatTime(total);

  // 活跃行高亮
  const rows = state.timeline.rows;
  let activeIndex = -1;
  for (const row of rows) {
    if (tSeconds >= row.start && tSeconds < row.end) { activeIndex = row.index; break; }
  }
  if (activeIndex !== state.activeRowIndex) {
    state.activeRowIndex = activeIndex;
    renderLineList();
  }

  if (!state.built) return;
  const frame = M.buildFrameContext(
    state.timeline, tSeconds, state.palette, measure, canvas.width, canvas.height
  );
  if (!frame) return; // 间奏/空隙:留空
  const sink = E.createBoxCollectorSink(measure, frame.colors.unsung, frame.textSize);
  const result = E.runProgram(state.built.effect.program, frame, sink);
  if (!result.ok || sink.failed) {
    $('frameStatus').textContent = '程序执行失败:' + (result.error || '预算超限') + '(实机会整块不绘制)';
    return;
  }
  $('frameStatus').textContent = '';
  // 背景网格暗示画布边界
  ctx.strokeStyle = 'rgba(255,255,255,0.06)';
  ctx.strokeRect(8.5, 8.5, canvas.width - 17, canvas.height - 17);
  const scale = canvas.width / frame.canvasWidth;
  ctx.save();
  ctx.scale(scale, scale);
  const previewSprites = Object.assign({}, spriteImages);
  if (state.manual && state.manual.images) {
    for (const id of Object.keys(state.manual.images)) {
      const image = previewImageFor(Number(id));
      if (image) previewSprites[id] = image;
    }
  }
  M.renderBoxes(ctx, sink.boxes, fontString, cssRgb, cssColor, previewSprites);
  ctx.restore();
  if (state.manual && state.manual.enabled && state.manual.sprites && state.manual.sprites.length) {
    ctx.save();
    ctx.setLineDash([6, 4]);
    ctx.lineWidth = 2;
    state.manual.sprites.forEach((sprite, index) => {
      ctx.strokeStyle = index === state.manual.sprite
        ? 'rgba(245,165,12,0.95)'
        : 'rgba(245,165,12,0.35)';
      ctx.strokeRect(
        sprite.x * canvas.width, sprite.y * canvas.height,
        Math.max(8, sprite.w * canvas.width), Math.max(8, sprite.h * canvas.height)
      );
    });
    ctx.restore();
  }
}

function formatTime(seconds) {
  const total = Math.max(0, seconds);
  const minutes = Math.floor(total / 60);
  const rest = total - minutes * 60;
  return String(minutes).padStart(2, '0') + ':' + rest.toFixed(2).padStart(5, '0');
}

function tick() {
  if (!motionActive) {
    requestAnimationFrame(tick);
    return;
  }
  if (state.playing) {
    let t = currentTimeSec();
    const total = Math.max(0.01, state.timeline.total);
    if (t >= total) {
      if (audio.src) {
        if ($('loop').checked) { audio.currentTime = 0; audio.play().catch(() => {}); t = 0; }
        else { state.playing = false; $('playBtn').textContent = '▶ 播放'; }
      } else if ($('loop').checked) {
        state.virtualClockOffset = 0;
        state.virtualClockStart = performance.now();
        t = 0;
      } else {
        state.playing = false;
        state.virtualClockOffset = 0;
        $('playBtn').textContent = '▶ 播放';
      }
    }
    drawFrame(t);
  }
  requestAnimationFrame(tick);
}

// ---------------------------------------------------------------------------
// 音乐 / 点击同步
// ---------------------------------------------------------------------------

$('loadMusic').addEventListener('click', () => $('musicFile').click());
$('musicFile').addEventListener('change', () => {
  const file = $('musicFile').files[0];
  if (!file) return;
  audio.src = URL.createObjectURL(file);
  audio.playbackRate = speed;
  state.playing = false;
  $('playBtn').textContent = '▶ 播放';
});

$('tapButton').addEventListener('click', () => doTap());
$('tapUndo').addEventListener('click', () => {
  state.tap.pop();
  state.manualTimes = {};
  refresh();
});
$('tapClear').addEventListener('click', () => {
  state.tap = [];
  state.manualTimes = {};
  refresh();
});
$('retime').addEventListener('click', () => {
  state.tap = [];
  state.manualTimes = {};
  refresh();
});

function doTap() {
  const t = currentTimeSec();
  state.tap.push(Math.round(t * 100) / 100);
  state.manualTimes = {};
  refresh();
}

document.addEventListener('keydown', (event) => {
  if (!motionActive) return;
  if (event.target && /INPUT|TEXTAREA|SELECT/.test(event.target.tagName)) return;
  if (event.code === 'Space' && $('syncMode').checked) {
    event.preventDefault();
    doTap();
  } else if (event.code === 'Backspace' && $('syncMode').checked) {
    event.preventDefault();
    state.tap.pop();
    state.manualTimes = {};
    refresh();
  } else if (event.code === 'KeyR' && !event.ctrlKey && !event.metaKey) {
    event.preventDefault();
    randomAll();
  }
});

// ---------------------------------------------------------------------------
// 编排控制
// ---------------------------------------------------------------------------

function randomAll() {
  const themes = Object.keys(M.THEMES);
  const theme = themes[Math.floor(Math.random() * themes.length)];
  state.theme = theme;
  state.seed = Math.floor(Math.random() * 0xffffff) + 1;
  state.overrides = {}; // 清空手动覆盖,吃主题+seed 全随机
  setManualEnabled(false);
  state.presetKey = 'custom';
  $('preset').value = 'custom';
  syncControlsFromStyle();
  refresh();
}

function syncControlsFromStyle() {
  $('theme').value = state.theme;
  $('seed').value = state.seed;
  // 有覆盖(预设/滑杆)时按覆盖同步,否则按主题随机同步
  const over = state.overrides && Object.keys(state.overrides).length ? state.overrides : {};
  const style = M.styleFromSeed(state.seed, state.theme, over);
  const set = (id, value) => { $(id).value = value; };
  set('waveAmp', Math.abs(style.waveAmp));
  set('waveK', Math.abs(style.waveK));
  set('rotAmp', style.rotAmp);
  set('bounceH', style.bounceH);
  set('slideD', style.slideD);
  set('slideStagger', style.slideStagger);
  set('slideAlt', style.slideAlt ? '1' : '0');
  set('pulse', style.pulse);
  set('unsungDim', style.unsungDim);
  set('cycle', style.cycle);
  // 预设可能改写排版/图形/顺序:先回填 state,再让 readControls 读到同一份值。
  state.choreoMode = style.choreoMode;
  state.layoutMode = style.layoutMode;
  state.graphicMode = style.graphicMode;
  $('choreo').value = style.choreoMode;
  $('layout').value = style.layoutMode;
  $('graphic').value = style.graphicMode;
  $('focus').value = style.focusMode;
  $('annotate').value = style.annotationMode;
  $('particle').value = style.particleMode;
  $('lineart').value = style.lineartMode;
  $('flash').checked = style.flash;
  $('preset').value = state.presetKey;
}

// 应用命名预设:滑杆/开关整体回填到预设参数,效果确定性一致
function applyPreset(key) {
  const preset = M.PRESETS[key];
  if (!preset) return;
  state.presetKey = key;
  state.theme = preset.theme;
  state.overrides = Object.assign({}, preset.params);
  setManualEnabled(false);
  // 预设自带调色(如金色星火的暖色辉光)一并回填到颜色输入框。
  if (preset.palette) {
    if (preset.palette.sung) $('sungColor').value = preset.palette.sung;
    if (preset.palette.glow) $('glowColor').value = preset.palette.glow;
  }
  syncControlsFromStyle();
  refresh();
}

for (const id of ['bpm', 'startSec', 'lineDur', 'snap', 'choreo', 'layout', 'graphic',
  'focus', 'annotate', 'particle', 'lineart']) {
  $(id).addEventListener('change', refresh);
}
for (const id of ['theme', 'cycle', 'seed', 'waveAmp', 'waveK', 'rotAmp', 'bounceH',
  'slideD', 'slideStagger', 'slideAlt', 'pulse', 'unsungDim', 'flash', 'sungColor', 'glowColor']) {
  $(id).addEventListener('input', () => {
    if (id !== 'theme' && id !== 'seed') {
      // 手动调整任一参数 → 预设回落「自定义」
      state.presetKey = 'custom';
      $('preset').value = 'custom';
    }
    refresh();
  });
}
// 预设选择器
$('preset').innerHTML = Object.keys(M.PRESETS).map((key) =>
  '<option value="' + key + '">' + M.PRESETS[key].name + '</option>'
).join('') + '<option value="custom">自定义(当前滑杆)</option>';
$('preset').addEventListener('change', () => {
  const key = $('preset').value;
  if (key !== 'custom') applyPreset(key);
});
$('dice').addEventListener('click', () => {
  state.seed = Math.floor(Math.random() * 0xffffff) + 1;
  $('seed').value = state.seed;
  refresh();
});
$('randomAll').addEventListener('click', randomAll);

// ---------------------------------------------------------------------------
// 手动排版(逐词覆盖):锚点 + 每词位移/缩放/旋转/透明度
// ---------------------------------------------------------------------------

function manualPayload() {
  const m = state.manual;
  if (!m || !m.enabled) return null;
  const overrides = Object.keys(m.overrides || {}).map((key) => m.overrides[key]);
  return {
    enabled: true,
    anchorX: m.anchorX,
    anchorY: m.anchorY,
    scale: m.scale,
    rotate: m.rotate,
    overrides,
    sprites: (m.sprites || []).map((sprite) => Object.assign({}, sprite))
  };
}

function manualRows() {
  return state.timeline.rows.filter((row) => !row.interlude && row.text);
}

function manualKey(line, word) {
  return line + ':' + word;
}

function manualOverride() {
  return state.manual.overrides[manualKey(state.manual.line, state.manual.word)] || null;
}

function manualIdentity(ov) {
  return !ov || (ov.dx === 0 && ov.dy === 0 && ov.scale === 1 && ov.rotate === 0 && ov.alpha === 1);
}

function writeManualOverride(patch) {
  const key = manualKey(state.manual.line, state.manual.word);
  const base = state.manual.overrides[key] || {
    line: state.manual.line, word: state.manual.word,
    dx: 0, dy: 0, scale: 1, rotate: 0, alpha: 1
  };
  const next = Object.assign({}, base, patch, { line: state.manual.line, word: state.manual.word });
  if (manualIdentity(next)) delete state.manual.overrides[key];
  else state.manual.overrides[key] = next;
}

function setManualEnabled(enabled) {
  state.manual.enabled = !!enabled;
  if ($('manualOn')) $('manualOn').checked = state.manual.enabled;
  if (state.manual.enabled) {
    state.presetKey = 'custom';
    if ($('preset')) $('preset').value = 'custom';
  }
}

function seekManualLine() {
  const rows = manualRows();
  const row = rows[state.manual.line];
  if (!row) return;
  if (window.HGMotionUI && typeof window.HGMotionUI.seek === 'function') {
    window.HGMotionUI.seek(row.start + Math.min(1, (row.end - row.start) * 0.3));
  }
}

function renderManualPanel() {
  if (!$('manualOn')) return;
  const m = state.manual;
  $('manualOn').checked = !!m.enabled;
  $('manualAx').value = m.anchorX;
  $('manualAy').value = m.anchorY;
  $('manualScale').value = m.scale;
  $('manualRotate').value = m.rotate;

  const rows = manualRows();
  if (m.line >= rows.length) m.line = Math.max(0, rows.length - 1);
  const lineSelect = $('manualLine');
  lineSelect.innerHTML = rows.map((row, index) =>
    '<option value="' + index + '">' + (index + 1) + '. ' + String(row.text).slice(0, 24) + '</option>'
  ).join('');
  lineSelect.value = String(m.line);

  const row = rows[m.line];
  const tokens = row ? E.tokenizeLine(row.text) : [];
  if (m.word >= tokens.length) m.word = Math.max(0, tokens.length - 1);
  const wordsBox = $('manualWords');
  wordsBox.innerHTML = tokens.map((token, index) => {
    const ov = m.overrides[manualKey(m.line, index)];
    const active = index === m.word ? 'background:var(--accent);color:#111;' : '';
    return '<button class="small" data-word="' + index + '" style="margin:2px;' + active + '">' +
      token.text + (ov ? ' •' : '') + '</button>';
  }).join('');
  wordsBox.querySelectorAll('button').forEach((button) => {
    button.addEventListener('click', () => {
      state.manual.word = Number(button.getAttribute('data-word'));
      renderManualPanel();
    });
  });

  const ov = manualOverride();
  $('wDx').value = ov ? ov.dx : 0;
  $('wDy').value = ov ? ov.dy : 0;
  $('wScale').value = ov ? ov.scale : 1;
  $('wRotate').value = ov ? ov.rotate : 0;
  $('wAlpha').value = ov ? ov.alpha : 1;
  const count = Object.keys(m.overrides).length;
  $('manualHint').textContent = m.enabled
    ? '已覆盖 ' + count + ' 个词;点词选中后拖滑杆,预览实时更新'
    : '开启后忽略生成器;当前只预览生成器效果';

  const sprites = m.sprites || [];
  if (m.sprite >= sprites.length) m.sprite = Math.max(0, sprites.length - 1);
  const spriteList = $('spriteList');
  spriteList.innerHTML = sprites.map((sprite, index) => {
    const active = index === m.sprite ? 'background:var(--accent);color:#111;' : '';
    return '<button class="small" data-sprite="' + index + '" style="margin:2px;' + active + '">贴纸 #' + sprite.id + '</button>';
  }).join('');
  spriteList.querySelectorAll('button').forEach((button) => {
    button.addEventListener('click', () => {
      state.manual.sprite = Number(button.getAttribute('data-sprite'));
      renderManualPanel();
    });
  });
  const selectedSprite = sprites[m.sprite] || null;
  $('sx').value = selectedSprite ? selectedSprite.x : 0.4;
  $('sy').value = selectedSprite ? selectedSprite.y : 0.4;
  $('sw').value = selectedSprite ? selectedSprite.w : 0.2;
  $('sh').value = selectedSprite ? selectedSprite.h : 0.2;
  $('srot').value = selectedSprite ? selectedSprite.rotate : 0;
  $('salpha').value = selectedSprite ? selectedSprite.alpha : 1;
  $('sanim').value = selectedSprite ? selectedSprite.anim : 'none';
  $('samp').value = selectedSprite ? selectedSprite.amp : 0.08;
  $('sspeed').value = selectedSprite ? selectedSprite.speed : 1;
}

$('manualOn').addEventListener('change', () => {
  setManualEnabled($('manualOn').checked);
  refresh();
});
for (const config of [
  ['manualAx', 'anchorX'], ['manualAy', 'anchorY'],
  ['manualScale', 'scale'], ['manualRotate', 'rotate']
]) {
  const id = config[0];
  const key = config[1];
  $(id).addEventListener('input', () => {
    if (!state.manual.enabled) setManualEnabled(true);
    state.manual[key] = Number($(id).value);
    state.presetKey = 'custom';
    $('preset').value = 'custom';
    refresh();
  });
}
$('manualLine').addEventListener('change', () => {
  state.manual.line = Number($('manualLine').value);
  state.manual.word = 0;
  renderManualPanel();
  seekManualLine();
});
for (const config of [
  ['wDx', 'dx'], ['wDy', 'dy'], ['wScale', 'scale'], ['wRotate', 'rotate'], ['wAlpha', 'alpha']
]) {
  const id = config[0];
  const key = config[1];
  $(id).addEventListener('input', () => {
    if (!state.manual.enabled) setManualEnabled(true);
    writeManualOverride({ [key]: Number($(id).value) });
    state.presetKey = 'custom';
    $('preset').value = 'custom';
    refresh();
  });
}
$('wReset').addEventListener('click', () => {
  delete state.manual.overrides[manualKey(state.manual.line, state.manual.word)];
  refresh();
});
$('lineReset').addEventListener('click', () => {
  const prefix = state.manual.line + ':';
  for (const key of Object.keys(state.manual.overrides)) {
    if (key.indexOf(prefix) === 0) delete state.manual.overrides[key];
  }
  refresh();
});
$('manualClear').addEventListener('click', () => {
  state.manual.overrides = {};
  refresh();
});
$('spritePreset').addEventListener('change', () => {
  $('spriteId').value = $('spritePreset').value;
});
$('spriteAdd').addEventListener('click', () => {
  if (!state.manual.enabled) setManualEnabled(true);
  const id = Math.max(0, Math.min(255, Math.round(Number($('spriteId').value) || 0)));
  state.manual.sprites.push({ id, x: 0.42, y: 0.42, w: 0.12, h: 0.12, rotate: 0, alpha: 1, anim: 'none', amp: 0.08, speed: 1, phase: state.manual.sprites.length * 1.3 });
  state.manual.sprite = state.manual.sprites.length - 1;
  state.presetKey = 'custom';
  $('preset').value = 'custom';
  refresh();
});
for (const config of [
  ['sx', 'x'], ['sy', 'y'], ['sw', 'w'], ['sh', 'h'], ['srot', 'rotate'], ['salpha', 'alpha']
]) {
  const id = config[0];
  const key = config[1];
  $(id).addEventListener('input', () => {
    if (!state.manual.enabled) setManualEnabled(true);
    const sprite = state.manual.sprites[state.manual.sprite];
    if (!sprite) return;
    sprite[key] = Number($(id).value);
    state.presetKey = 'custom';
    $('preset').value = 'custom';
    refresh();
  });
}
$('spriteDelete').addEventListener('click', () => {
  state.manual.sprites.splice(state.manual.sprite, 1);
  if (state.manual.sprite >= state.manual.sprites.length) {
    state.manual.sprite = Math.max(0, state.manual.sprites.length - 1);
  }
  refresh();
});
$('sanim').addEventListener('change', () => {
  const sprite = state.manual.sprites[state.manual.sprite];
  if (!sprite) return;
  sprite.anim = $('sanim').value;
  state.presetKey = 'custom';
  $('preset').value = 'custom';
  refresh();
});
for (const config of [['samp', 'amp'], ['sspeed', 'speed']]) {
  const id = config[0];
  const key = config[1];
  $(id).addEventListener('input', () => {
    const sprite = state.manual.sprites[state.manual.sprite];
    if (!sprite) return;
    sprite[key] = Number($(id).value);
    state.presetKey = 'custom';
    $('preset').value = 'custom';
    refresh();
  });
}
$('spriteImage').addEventListener('click', () => $('spriteFile').click());
$('spriteFile').addEventListener('change', () => {
  const file = $('spriteFile').files[0];
  const sprite = state.manual.sprites[state.manual.sprite];
  $('spriteFile').value = '';
  if (!file || !sprite) return;
  const reader = new FileReader();
  reader.onload = () => {
    const image = new Image();
    image.onload = () => {
      const ratio = Math.min(1, 256 / Math.max(1, image.width, image.height));
      const canvas = document.createElement('canvas');
      canvas.width = Math.max(1, Math.round(image.width * ratio));
      canvas.height = Math.max(1, Math.round(image.height * ratio));
      canvas.getContext('2d').drawImage(image, 0, 0, canvas.width, canvas.height);
      state.manual.images[sprite.id] = canvas.toDataURL('image/png');
      refresh();
    };
    image.src = String(reader.result);
  };
  reader.readAsDataURL(file);
});

// ---- 画布直接拖动:命中选中的行内词 → 改位移;空白处拖动 → 改整体锚点 ----
let manualDrag = null;
let manualDragFrame = 0;

function previewMetrics() {
  const canvas = $('preview');
  const W = canvas.width || 720;
  const H = canvas.height || 320;
  const rows = manualRows();
  const row = rows[state.manual.line];
  if (!row) return null;
  const tokens = E.tokenizeLine(row.text);
  const ts = 64;
  const widths = tokens.map((token) => measure(token.text, ts) + (token.spaceBefore ? ts * 0.18 : 0));
  const total = widths.reduce((sum, width) => sum + width, 0);
  const startX = Math.max(8, (W - total) / 2);
  const baseline = Math.max(ts, H * 0.42);
  let cursor = startX;
  const points = tokens.map((token, index) => {
    const ov = state.manual.overrides[manualKey(state.manual.line, index)] || {};
    const scale = (ov.scale || 1) * state.manual.scale;
    const cx = cursor + widths[index] / 2 +
      (state.manual.anchorX - 0.5) * W + (ov.dx || 0) * ts;
    const cy = baseline + (state.manual.anchorY - 0.5) * H + (ov.dy || 0) * ts -
      ts * scale * 0.35;
    cursor += widths[index];
    return { index, x: cx, y: cy, radius: Math.max(28, ts * scale * 0.55) };
  });
  const spriteRects = (state.manual.sprites || []).map((sprite, index) => ({
    index,
    x: sprite.x * W,
    y: sprite.y * H,
    w: Math.max(8, sprite.w * W),
    h: Math.max(8, sprite.h * H)
  }));
  return { points, spriteRects, W, H };
}

function canvasEventPoint(event, metrics) {
  const canvas = $('preview');
  const rect = canvas.getBoundingClientRect();
  return {
    x: (event.clientX - rect.left) * (metrics.W / Math.max(1, rect.width)),
    y: (event.clientY - rect.top) * (metrics.H / Math.max(1, rect.height))
  };
}

function scheduleManualRefresh() {
  if (manualDragFrame) return;
  manualDragFrame = requestAnimationFrame(() => {
    manualDragFrame = 0;
    refresh();
  });
}

$('preview').addEventListener('pointerdown', (event) => {
  if (!state.manual.enabled) return;
  const metrics = previewMetrics();
  if (!metrics) return;
  const point = canvasEventPoint(event, metrics);
  const spriteHit = (metrics.spriteRects || []).find((rect) =>
    point.x >= rect.x && point.x <= rect.x + rect.w &&
    point.y >= rect.y && point.y <= rect.y + rect.h);
  if (spriteHit) {
    state.manual.sprite = spriteHit.index;
    renderManualPanel();
    const sprite = state.manual.sprites[spriteHit.index];
    manualDrag = {
      mode: 'sprite', W: metrics.W, H: metrics.H,
      startX: point.x, startY: point.y, sx: sprite.x, sy: sprite.y
    };
    event.preventDefault();
    return;
  }
  const hit = metrics.points.find((item) =>
    Math.hypot(item.x - point.x, item.y - point.y) <= item.radius);
  if (hit) {
    state.manual.word = hit.index;
    renderManualPanel();
    const ov = state.manual.overrides[manualKey(state.manual.line, hit.index)] || {};
    manualDrag = {
      mode: 'word', W: metrics.W, H: metrics.H,
      startX: point.x, startY: point.y, dx: ov.dx || 0, dy: ov.dy || 0
    };
  } else {
    manualDrag = {
      mode: 'anchor', W: metrics.W, H: metrics.H,
      startX: point.x, startY: point.y,
      ax: state.manual.anchorX, ay: state.manual.anchorY
    };
  }
  event.preventDefault();
});

window.addEventListener('pointermove', (event) => {
  if (!manualDrag) return;
  const point = canvasEventPoint(event, manualDrag);
  if (manualDrag.mode === 'sprite') {
    const sprite = state.manual.sprites[state.manual.sprite];
    if (sprite) {
      sprite.x = Math.min(1, Math.max(0, manualDrag.sx + (point.x - manualDrag.startX) / manualDrag.W));
      sprite.y = Math.min(1, Math.max(0, manualDrag.sy + (point.y - manualDrag.startY) / manualDrag.H));
    }
  } else if (manualDrag.mode === 'word') {
    writeManualOverride({
      dx: manualDrag.dx + (point.x - manualDrag.startX) / 64,
      dy: manualDrag.dy + (point.y - manualDrag.startY) / 64
    });
  } else {
    state.manual.anchorX = Math.min(1, Math.max(0,
      manualDrag.ax + (point.x - manualDrag.startX) / manualDrag.W));
    state.manual.anchorY = Math.min(1, Math.max(0,
      manualDrag.ay + (point.y - manualDrag.startY) / manualDrag.H));
  }
  scheduleManualRefresh();
});

window.addEventListener('pointerup', () => {
  manualDrag = null;
});

// ---------------------------------------------------------------------------
// 导出
// ---------------------------------------------------------------------------

function download(filename, content, mime) {
  const blob = new Blob([content], { type: mime || 'application/json' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1500);
}

$('exportPack').addEventListener('click', () => {
  if (!state.built) return;
  download('hyperglow-motion-' + state.theme + '-' + state.seed + '.json',
    JSON.stringify(state.built.pack, null, 2));
});
$('exportLrc').addEventListener('click', () => {
  download('motion-' + state.seed + '.lrc', M.toLrc(state.timeline), 'text/plain');
});
$('saveProject').addEventListener('click', () => {
  download('motion-project-' + state.seed + '.json', JSON.stringify(M.toProject({
    lyrics: state.lyricsSrc,
    bpm: Number($('bpm').value) || 120,
    startSec: Number($('startSec').value) || 0,
    lineDurSec: Number($('lineDur').value) || 0,
    snap: $('snap').checked,
    tap: state.tap,
    seed: state.seed,
    theme: state.theme,
    overrides: state.overrides,
    manual: manualPayload(),
    manualImages: state.manual.images,
    palette: {
      sung: $('sungColor').value,
      glow: $('glowColor').value
    }
  })));
});
$('loadProjectBtn').addEventListener('click', () => $('projectFile').click());
$('projectFile').addEventListener('change', () => {
  const file = $('projectFile').files[0];
  if (!file) return;
  const reader = new FileReader();
  reader.onload = () => {
    try {
      const project = JSON.parse(String(reader.result));
      if (project.format !== 'hyperglow-motion-project') throw new Error('不是 Motion 工程');
      $('lyrics').value = String(project.lyrics || '');
      $('bpm').value = project.bpm || 120;
      $('startSec').value = project.startSec || 0;
      $('lineDur').value = project.lineDurSec || 0;
      $('snap').checked = !!project.snap;
      state.tap = Array.isArray(project.tap) ? project.tap.slice() : [];
      state.manualTimes = {};
      state.theme = project.theme || 'lyric';
      state.seed = project.seed || 1;
      state.overrides = project.overrides || {};
      const savedManual = project.manual;
      state.manual = {
        enabled: !!(savedManual && savedManual.enabled),
        anchorX: savedManual && Number.isFinite(savedManual.anchorX) ? savedManual.anchorX : 0.5,
        anchorY: savedManual && Number.isFinite(savedManual.anchorY) ? savedManual.anchorY : 0.5,
        scale: savedManual && Number.isFinite(savedManual.scale) ? savedManual.scale : 1,
        rotate: savedManual && Number.isFinite(savedManual.rotate) ? savedManual.rotate : 0,
        line: 0,
        word: 0,
        overrides: {},
        sprites: [],
        sprite: 0,
        images: {}
      };
      if (savedManual && Array.isArray(savedManual.overrides)) {
        for (const ov of savedManual.overrides) {
          if (!ov || !Number.isInteger(ov.line) || !Number.isInteger(ov.word)) continue;
          state.manual.overrides[manualKey(ov.line, ov.word)] = {
            line: ov.line,
            word: ov.word,
            dx: Number.isFinite(ov.dx) ? ov.dx : 0,
            dy: Number.isFinite(ov.dy) ? ov.dy : 0,
            scale: Number.isFinite(ov.scale) ? ov.scale : 1,
            rotate: Number.isFinite(ov.rotate) ? ov.rotate : 0,
            alpha: Number.isFinite(ov.alpha) ? ov.alpha : 1
          };
        }
      }
      if (project.manualImages && typeof project.manualImages === 'object') {
        state.manual.images = project.manualImages;
      }
      if (savedManual && Array.isArray(savedManual.sprites)) {
        for (const sprite of savedManual.sprites) {
          if (!sprite || !Number.isInteger(sprite.id) || sprite.id < 0 || sprite.id > 255) continue;
          state.manual.sprites.push({
            id: sprite.id,
            x: Number.isFinite(sprite.x) ? sprite.x : 0.38,
            y: Number.isFinite(sprite.y) ? sprite.y : 0.38,
            w: Number.isFinite(sprite.w) ? sprite.w : 0.22,
            h: Number.isFinite(sprite.h) ? sprite.h : 0.22,
            rotate: Number.isFinite(sprite.rotate) ? sprite.rotate : 0,
            alpha: Number.isFinite(sprite.alpha) ? sprite.alpha : 1
          });
        }
      }
      if (project.palette) {
        if (project.palette.sung) $('sungColor').value = project.palette.sung;
        if (project.palette.glow) $('glowColor').value = project.palette.glow;
      }
      syncControlsFromStyle();
      // 覆盖值回填滑杆
      const over = state.overrides || {};
      const setIf = (id, key, fallbackId) => {
        if (Number.isFinite(over[key])) $(id).value = over[key];
      };
      setIf('waveAmp', 'waveAmp', 'waveAmp');
      setIf('waveK', 'waveK', 'waveK');
      setIf('rotAmp', 'rotAmp', 'rotAmp');
      setIf('bounceH', 'bounceH', 'bounceH');
      setIf('slideD', 'slideD', 'slideD');
      setIf('pulse', 'pulse', 'pulse');
      setIf('unsungDim', 'unsungDim', 'unsungDim');
      if (Number.isFinite(over.cycle)) $('cycle').value = String(over.cycle);
      if (over.flash !== undefined) $('flash').checked = !!over.flash;
      refresh();
    } catch (error) {
      $('parseStatus').textContent = '工程打开失败:' + error.message;
    }
  };
  reader.readAsText(file);
});

// ---------------------------------------------------------------------------
// 启动
// ---------------------------------------------------------------------------

$('lyrics').value = [
  '# 把下面的歌词换成你自己的;每行开始的瞬间按空格打点',
  '[00:00.50]夜色轻轻*落下*',
  '街灯把影子拉长!',
  '[间奏 4]',
  '而我还记得|na yi ge xia tian',
  '黎明之前的合唱'
].join('\n');
state.seed = 20261109;
$('seed').value = state.seed;
applyPreset('ballad');
requestAnimationFrame(tick);


  // ---- AI 切词:调用 OpenAI 兼容接口,按语义切行并回填歌词 ----
  const AI_STORE_KEY = 'hyperglow-motion-ai';
  const aiSettings = (() => {
    try { return JSON.parse(localStorage.getItem(AI_STORE_KEY) || '{}'); }
    catch (error) { return {}; }
  })();
  const aiField = (id, key) => {
    const el = $(id);
    if (aiSettings[key]) el.value = aiSettings[key];
    el.addEventListener('change', () => {
      const next = {};
      for (const [k, src] of [['endpoint', 'aiEndpoint'], ['model', 'aiModel'], ['key', 'aiKey']]) {
        next[k] = $(src).value.trim();
      }
      try { localStorage.setItem(AI_STORE_KEY, JSON.stringify(next)); } catch (error) {}
    });
  };
  aiField('aiEndpoint', 'endpoint');
  aiField('aiModel', 'model');
  aiField('aiKey', 'key');

  const AI_SYSTEM_PROMPT = [
    '你是歌词排版助手。把用户给的歌词整理成适合逐行卡拉OK显示的歌词行:',
    '1. 按语义和气口切分,每行一句,行长尽量均衡(约 6-14 个字);',
    '2. 保留原文措辞,不要改写、不要翻译、不要增删内容;',
    '3. 副歌/高潮句可在整段外包一层 *强调* 标记;特别炸的句子行末加 ! (闪光);',
    '4. 需要分镜的长句用 / 标记切分点;注音用 歌词|注音;',
    '5. 间奏用 [间奏 秒数] 单独一行;不要输出任何解释、序号或代码块,只输出整理后的歌词行。'
  ].join('\n');

  $('aiRun').addEventListener('click', async () => {
    const status = $('aiStatus');
    const endpoint = $('aiEndpoint').value.trim();
    const model = $('aiModel').value.trim();
    const key = $('aiKey').value.trim();
    const raw = $('lyrics').value.trim();
    if (!endpoint || !model || !key) {
      status.textContent = '请先填接口地址、模型和密钥';
      return;
    }
    if (!raw) {
      status.textContent = '歌词是空的';
      return;
    }
    status.textContent = 'AI 切词中…';
    try {
      const resp = await fetch(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'Authorization': 'Bearer ' + key },
        body: JSON.stringify({
          model,
          temperature: 0.4,
          messages: [
            { role: 'system', content: AI_SYSTEM_PROMPT },
            { role: 'user', content: raw }
          ]
        })
      });
      if (!resp.ok) throw new Error('HTTP ' + resp.status);
      const data = await resp.json();
      let out = data.choices && data.choices[0] && data.choices[0].message
        ? data.choices[0].message.content : '';
      if (out.startsWith('```')) {
        out = out.slice(out.indexOf('\n') + 1);
      }
      if (out.endsWith('```')) {
        out = out.slice(0, out.length - 3);
      }
      out = out.trim();
      if (!out) throw new Error('AI 返回为空');
      $('lyrics').value = out;
      refresh();
      status.textContent = '✓ AI 切词完成,已回填歌词(可手动微调)';
    } catch (error) {
      status.textContent = '✗ AI 切词失败:' + (error && error.message ? error.message : error) +
        '(常见原因:接口不允许浏览器跨域、密钥无效)';
    }
  });

  // ---- 单页模式切换与主编辑器桥 ----
  function setVisible(visible) {
    motionActive = visible;
    document.body.classList.toggle('motion-mode', visible);
    if (!visible) {
      state.playing = false;
      $('playBtn').textContent = '▶ 播放';
      audio.pause();
      state.virtualClockOffset = currentTimeSec();
    } else {
      refresh();
    }
  }
  document.getElementById('m-back').addEventListener('click', () => setVisible(false));
  document.getElementById('motionToggle').addEventListener('click', () => setVisible(true));

  // 生成的效果直接加入主编辑器当前包(经 app.js 公开桥,与「+ 程序」同路径)
  document.getElementById('m-addPack').addEventListener('click', () => {
    const status = $('addPackStatus');
    if (!state.built) {
      status.textContent = '程序尚未就绪,无法加入';
      return;
    }
    const studio = window.WEStudio;
    if (!studio || typeof studio.addEffect !== 'function') {
      status.textContent = '主编辑器未就绪';
      return;
    }
    const id = studio.addEffect(state.built.effect);
    status.textContent = id
      ? '✓ 已加入当前效果包:「' + id + '」(左侧效果列表末尾,可继续微调后随整包导出)'
      : '✗ 加入失败(主编辑器拒绝)';
  });

  // 单页对外接口
  window.HGMotionUI = { setVisible, isActive: () => motionActive, seek: (sec) => seekTo(sec) };
})();