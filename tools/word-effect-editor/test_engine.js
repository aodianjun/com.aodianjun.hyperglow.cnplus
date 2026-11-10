/*
 * Node regression tests for the Word Effect Studio engine.
 *
 *   node tools/word-effect-editor/test_engine.js
 *
 * Covers: pack/effect normalization against both shipped sample packs, DSL
 * evaluation values, program validation, the text assembler, VM execution,
 * disassemble → assemble round-trips and the documented edge cases.
 */
'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const E = require('./engine.js');
const T = require('./templates.js');

// 样例包位置对两种布局都稳健:主仓(tools/word-effect-editor → ../../samples)
// 与独立发布仓(文件在仓库根 → ./samples)。
const samplesDir = [
  path.resolve(__dirname, '..', '..', 'samples'),
  path.resolve(__dirname, 'samples'),
  __dirname
].find((dir) => fs.existsSync(path.join(dir, 'word-effect-pack.json')));
if (!samplesDir) {
  throw new Error('samples/word-effect-pack.json not found relative to ' + __dirname);
}
const dslSamplePath = path.join(samplesDir, 'word-effect-pack.json');
const programSamplePath = path.join(samplesDir, 'word-effect-program-pack.json');

let passed = 0;
function test(name, fn) {
  try {
    fn();
    passed++;
    console.log('  ok  ' + name);
  } catch (error) {
    console.error('  FAIL ' + name);
    console.error(error && error.stack ? error.stack : error);
    process.exitCode = 1;
  }
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function baseEffect(extra) {
  return Object.assign({ id: 'test', name: 'Test', tracks: [], colors: [] }, extra || {});
}

function expectOk(spec, label) {
  const result = E.normalizeEffectSpec(spec);
  assert.ok(result.ok, (label || 'normalize') + ': ' + result.errors.join('; '));
  return result.effect;
}

// ---------------------------------------------------------------------------
// Recording sink for VM traces
// ---------------------------------------------------------------------------

class RecordingSink {
  constructor() {
    this.calls = [];
    this.color = 0;
    this.alpha = 1;
    this.textSize = 44;
    this.align = 0;
  }
  save() { this.calls.push(['save']); }
  restore() { this.calls.push(['restore']); }
  translate(x, y) { this.calls.push(['translate', x, y]); }
  rotate(deg) { this.calls.push(['rotate', deg]); }
  scale(sx, sy) { this.calls.push(['scale', sx, sy]); }
  setColor(argb) { this.color = argb; this.calls.push(['setColor', argb]); }
  setAlpha(alpha) { this.alpha = alpha; this.calls.push(['setAlpha', alpha]); }
  setTextSize(px) { this.textSize = px; this.calls.push(['setTextSize', px]); }
  setTextAlign(align) { this.align = align; this.calls.push(['setTextAlign', align]); }
  drawText(text, x, y) { this.calls.push(['drawText', text, x, y]); }
  drawRect(x, y, w, h) { this.calls.push(['drawRect', x, y, w, h]); }
  drawCircle(cx, cy, r) { this.calls.push(['drawCircle', cx, cy, r]); }
  drawLine(x1, y1, x2, y2) { this.calls.push(['drawLine', x1, y1, x2, y2]); }
  setGradient(x0, y0, x1, y1, c0, c1) { this.calls.push(['setGradient', x0, y0, x1, y1, c0, c1]); }
  clearGradient() { this.calls.push(['clearGradient']); }
  setShadow(r, dx, dy, color) { this.calls.push(['setShadow', r, dx, dy, color]); }
  clearShadow() { this.calls.push(['clearShadow']); }
  setStrokeWidth(w) { this.calls.push(['setStrokeWidth', w]); }
  setFill() { this.calls.push(['setFill']); }
  setStroke() { this.calls.push(['setStroke']); }
  drawRoundRect(x, y, w, h, rx, ry) { this.calls.push(['drawRoundRect', x, y, w, h, rx, ry]); }
  drawOval(x, y, w, h) { this.calls.push(['drawOval', x, y, w, h]); }
  drawArc(x, y, w, h, start, sweep) { this.calls.push(['drawArc', x, y, w, h, start, sweep]); }
  pathMove(x, y) { this.calls.push(['pathMove', x, y]); }
  pathLine(x, y) { this.calls.push(['pathLine', x, y]); }
  pathQuad(cx, cy, x, y) { this.calls.push(['pathQuad', cx, cy, x, y]); }
  pathCubic(c1x, c1y, c2x, c2y, x, y) { this.calls.push(['pathCubic', c1x, c1y, c2x, c2y, x, y]); }
  pathClose() { this.calls.push(['pathClose']); }
  drawPath() { this.calls.push(['drawPath']); }
  clipRect(x, y, w, h) { this.calls.push(['clipRect', x, y, w, h]); }
  measureText(text) { this.calls.push(['measureText', text]); return text.length * 20; }
}

// 记录每个 drawText 生效时的 alpha / scale / shadow，用于验证邻字联动
class LinkSink extends RecordingSink {
  constructor() {
    super();
    this.scaleValue = 1;
    this.shadowRadius = null;
    this.stateStack = [];
    this.draws = [];
  }
  save() {
    this.stateStack.push([this.scaleValue, this.shadowRadius, this.alpha]);
    super.save();
  }
  restore() {
    const state = this.stateStack.pop();
    if (state) {
      this.scaleValue = state[0];
      this.shadowRadius = state[1];
      this.alpha = state[2];
    }
    super.restore();
  }
  scale(sx, sy) { this.scaleValue = sx; super.scale(sx, sy); }
  setShadow(r, dx, dy, color) { this.shadowRadius = r; super.setShadow(r, dx, dy, color); }
  clearShadow() { this.shadowRadius = null; super.clearShadow(); }
  drawText(text, x, y) {
    this.draws.push({ text, x, y, alpha: this.alpha, scale: this.scaleValue, shadow: this.shadowRadius });
    super.drawText(text, x, y);
  }
}

function makeContext() {
  const line0 = {
    text: '夜色渐浓', index: 0, startMs: 0, endMs: 1200, progress: 0.6,
    x: 100, width: 300, baseline: 160, height: 60
  };
  const line1 = {
    text: 'Hello world', index: 1, startMs: 1200, endMs: 2400, progress: 0.2,
    x: 120, width: 280, baseline: 230, height: 60
  };
  const words = [
    { text: '夜', x: 100, width: 44, startMs: 0, endMs: 300, progress: 0.9, index: 0, longSyllable: false, lineIndex: 0, baseline: 160 },
    { text: '色', x: 144, width: 44, startMs: 300, endMs: 600, progress: 0.5, index: 1, longSyllable: false, lineIndex: 0, baseline: 160 },
    { text: '渐', x: 188, width: 44, startMs: 600, endMs: 900, progress: 0.2, index: 2, longSyllable: false, lineIndex: 0, baseline: 160 },
    { text: '浓', x: 232, width: 44, startMs: 900, endMs: 1200, progress: 0, index: 3, longSyllable: false, lineIndex: 0, baseline: 160 },
    { text: 'Hello', x: 120, width: 110, startMs: 1200, endMs: 1900, progress: 0.8, index: 4, longSyllable: true, lineIndex: 1, baseline: 230 },
    { text: 'world', x: 230, width: 100, startMs: 1900, endMs: 2400, progress: 0.3, index: 5, longSyllable: false, lineIndex: 1, baseline: 230 }
  ];
  return {
    timeMs: 1234,
    wallClockMs: 50000,
    baseline: 160,
    textSize: 44,
    blockWidth: 300,
    blockHeight: 130,
    canvasWidth: 720,
    canvasHeight: 320,
    colors: {
      sung: 0xFFFFFFFF | 0,
      unsung: 0xFF888888 | 0,
      glow: 0xFF66AAFF | 0,
      accent: 0xFFFFAA33 | 0
    },
    lines: [line0, line1],
    words,
    lineIndex: 0
  };
}

// ---------------------------------------------------------------------------
// 1. DSL sample pack
// ---------------------------------------------------------------------------

console.log('\n[1] DSL sample pack normalization');

test('samples/word-effect-pack.json loads and every effect normalizes', () => {
  const pack = readJson(dslSamplePath);
  assert.strictEqual(pack.format, E.FORMAT);
  const result = E.normalizePack(pack);
  assert.ok(result.ok, result.errors.join('; '));
  assert.strictEqual(result.entries.length, 6);
  assert.strictEqual(result.pack.effects.length, 6);
});

test('normalization is idempotent for every sample effect', () => {
  const pack = readJson(dslSamplePath);
  for (const effect of pack.effects) {
    const once = expectOk(effect, effect.id);
    const twice = expectOk(once, effect.id + ' twice');
    assert.deepStrictEqual(twice, once, effect.id + ' is not idempotent');
  }
});

test('normalization is idempotent for every built-in DSL template', () => {
  for (const key of Object.keys(T.DSL)) {
    const once = expectOk(T.DSL[key], key);
    const twice = expectOk(once, key + ' twice');
    assert.deepStrictEqual(twice, once, key + ' is not idempotent');
  }
});

test('bounce evaluates to the documented values at p = 0.35', () => {
  const effect = expectOk(T.DSL.bounce);
  const frame = E.evaluateEffect(effect, 0.35, 0, false, 1);
  assert.ok(Math.abs(frame.translateYEm - (-0.28)) < 1e-5, 'translateY=' + frame.translateYEm);
  assert.ok(Math.abs(frame.scale - 1.18) < 1e-5, 'scale=' + frame.scale);
  assert.strictEqual(frame.t, 0.35);
});

test('stagger lags later words and completes the last word at p=1', () => {
  const effect = expectOk(T.DSL.wave);
  const count = 5;
  const firstMid = E.evaluateEffect(effect, 0.5, 0, false, count);
  const lastMid = E.evaluateEffect(effect, 0.5, count - 1, false, count);
  assert.ok(firstMid.t > lastMid.t, 'first.t=' + firstMid.t + ' last.t=' + lastMid.t);
  assert.ok(Math.abs(lastMid.t) < 1e-6, 'last.t at p=0.5 should be 0, got ' + lastMid.t);
  const lastEnd = E.evaluateEffect(effect, 1, count - 1, false, count);
  assert.ok(Math.abs(lastEnd.t - 1) < 1e-6, 'last.t=' + lastEnd.t);
});

test('longOnly tracks are skipped for short syllables', () => {
  const effect = expectOk(baseEffect({
    tracks: [{
      property: 'scale',
      longOnly: true,
      keyframes: [{ at: 0, value: 2, easing: 'linear' }, { at: 1, value: 2, easing: 'linear' }]
    }]
  }));
  assert.strictEqual(E.evaluateEffect(effect, 0.5, 0, false, 1).scale, 1);
  assert.strictEqual(E.evaluateEffect(effect, 0.5, 0, true, 1).scale, 2);
});

test('a glow window track lights only the word being sung', () => {
  const effect = expectOk(baseEffect({
    glow: 'all',
    tracks: [{
      property: 'glowAlpha',
      keyframes: [
        { at: 0, value: 0, easing: 'easeOut' },
        { at: 0.12, value: 1, easing: 'linear' },
        { at: 0.88, value: 1, easing: 'linear' },
        { at: 1, value: 0, easing: 'linear' }
      ]
    }]
  }));
  assert.strictEqual(E.evaluateEffect(effect, 0, 0, false, 1).glowAlpha, 0);
  assert.strictEqual(E.evaluateEffect(effect, 0.5, 0, false, 1).glowAlpha, 1);
  assert.strictEqual(E.evaluateEffect(effect, 1, 0, false, 1).glowAlpha, 0);
});

// ---------------------------------------------------------------------------
// 2. Program sample pack
// ---------------------------------------------------------------------------

console.log('\n[2] Program sample pack');

test('samples/word-effect-program-pack.json loads and every program validates', () => {
  const pack = readJson(programSamplePath);
  const result = E.normalizePack(pack);
  assert.ok(result.ok, result.errors.join('; '));
  assert.strictEqual(result.pack.effects.length, 6);
  for (const effect of result.pack.effects) {
    assert.ok(effect.program, effect.id + ' lost its program');
    const normalized = E.normalizeProgram(effect.program);
    assert.ok(normalized.ok, effect.id + ': ' + normalized.errors.join('; '));
  }
});

test('every sample program executes one frame and draws something', () => {
  const pack = readJson(programSamplePath);
  for (const effect of pack.effects) {
    if (effect.program.outputMode === 'boxes') {
      // 盒模式:直绘 sink 不适用,走盒收集器并断言盒非空。
      const frame = makeContext();
      frame.lines = [];
      const sink = E.createBoxCollectorSink((text) => text.length * 10, frame.colors.unsung, frame.textSize);
      const result = E.runProgram(effect.program, frame, sink);
      assert.ok(result.ok && !sink.failed, effect.id + ': ' + result.error);
      assert.ok(sink.boxes.length > 0, effect.id + ' emitted no boxes');
      continue;
    }
    const sink = new RecordingSink();
    const result = E.runProgram(effect.program, makeContext(), sink);
    assert.ok(result.ok, effect.id + ': ' + result.error);
    assert.ok(result.drawOps > 0, effect.id + ' drew nothing');
    assert.ok(sink.calls.some((call) => call[0] === 'drawText'), effect.id + ' did not draw text');
  }
});

test('sample program disassemble → assemble keeps the draw trace', () => {
  const pack = readJson(programSamplePath);
  for (const effect of pack.effects) {
    const source = E.disassemble(effect.program);
    const assembled = E.assemble(source, { maxLocals: effect.program.maxLocals });
    assert.ok(assembled.ok, effect.id + ' reassembly: ' + assembled.errors.join('; '));
    assert.strictEqual(assembled.stats.instructions, effect.program.code.length, effect.id + ' instruction count');
    const sinkA = new RecordingSink();
    const sinkB = new RecordingSink();
    const ctx = makeContext();
    const runA = E.runProgram(effect.program, ctx, sinkA);
    const runB = E.runProgram(assembled.program, makeContext(), sinkB);
    assert.ok(runA.ok && runB.ok, effect.id + ' run failed');
    assert.deepStrictEqual(sinkB.calls, sinkA.calls, effect.id + ' draw trace differs');
  }
});

// ---------------------------------------------------------------------------
// 3. Built-in program templates
// ---------------------------------------------------------------------------

console.log('\n[3] Built-in program templates');

for (const key of Object.keys(T.PROGRAM)) {
  test('template ' + key + ' assembles, validates and runs', () => {
    const template = T.PROGRAM[key];
    const result = E.assemble(template.source, { maxLocals: template.maxLocals });
    assert.ok(result.ok, result.errors.join('; '));
    const normalized = E.normalizeProgram(result.program);
    assert.ok(normalized.ok, normalized.errors.join('; '));
    const sink = new RecordingSink();
    const run = E.runProgram(result.program, makeContext(), sink);
    assert.ok(run.ok, run.error);
    assert.ok(run.drawOps > 0, 'drew nothing');
    assert.ok(result.stats.instructions <= E.MAX_EFFECT_CODE, 'instruction limit');
    assert.ok(result.stats.estimatedBytes <= E.MAX_EFFECT_PROGRAM_ESTIMATED_BYTES, 'byte limit');
    assert.ok(sink.calls.length > 0, 'no calls');
  });
}

test('template round-trips through disassemble → assemble', () => {
  for (const key of Object.keys(T.PROGRAM)) {
    const template = T.PROGRAM[key];
    const first = E.assemble(template.source, { maxLocals: template.maxLocals });
    assert.ok(first.ok, key + ': ' + first.errors.join('; '));
    const second = E.assemble(E.disassemble(first.program), { maxLocals: template.maxLocals });
    assert.ok(second.ok, key + ' roundtrip: ' + second.errors.join('; '));
    const sinkA = new RecordingSink();
    const sinkB = new RecordingSink();
    assert.ok(E.runProgram(first.program, makeContext(), sinkA).ok);
    assert.ok(E.runProgram(second.program, makeContext(), sinkB).ok);
    assert.deepStrictEqual(sinkB.calls, sinkA.calls, key + ' trace differs');
  }
});

test('char_link links adjacent characters with a same-line distance falloff', () => {
  const template = T.PROGRAM.char_link;
  const assembled = E.assemble(template.source, { maxLocals: template.maxLocals });
  assert.ok(assembled.ok, assembled.errors.join('; '));
  const ctx = makeContext();
  ctx.lines = [ctx.lines[0]];
  ctx.words = ctx.words.slice(0, 4).map((word, index) => Object.assign({}, word, {
    progress: [1, 0.5, 0, 0][index]
  }));
  const sink = new LinkSink();
  const run = E.runProgram(assembled.program, ctx, sink);
  assert.ok(run.ok, run.error);
  assert.deepStrictEqual(sink.draws.map((draw) => draw.text), ['夜', '色', '渐', '浓']);
  const [left, head, right, far] = sink.draws;
  assert.ok(Math.abs(head.scale - 1.1) < 1e-9, 'head scale');
  assert.ok(head.shadow > 0, 'head glow');
  assert.ok(Math.abs(left.scale - (1 + 0.1 * 2 / 3)) < 1e-9, 'left neighbor scale');
  assert.ok(left.shadow > 0, 'left neighbor glow');
  assert.ok(Math.abs(right.alpha - (0.35 + 0.65 * (2 / 3))) < 1e-9, 'right neighbor alpha');
  assert.ok(Math.abs(right.scale - (1 + 0.1 * 2 / 3)) < 1e-9, 'right neighbor scale');
  assert.ok(far.alpha < right.alpha, 'falloff alpha');
  assert.ok(Math.abs(far.scale - (1 + 0.1 / 3)) < 1e-9, 'two-away scale');
  assert.ok(far.shadow > 0, 'two-away glow');
  assert.strictEqual(sink.draws.length, 4);
});

test('hello draws the first line at its own x / baseline with an alpha floor', () => {
  const template = T.PROGRAM.hello;
  const assembled = E.assemble(template.source, { maxLocals: template.maxLocals });
  assert.ok(assembled.ok, assembled.errors.join('; '));
  const sink = new LinkSink();
  const run = E.runProgram(assembled.program, makeContext(), sink);
  assert.ok(run.ok, run.error);
  assert.strictEqual(sink.draws.length, 1);
  const draw = sink.draws[0];
  assert.strictEqual(draw.text, '夜色渐浓');
  assert.ok(Math.abs(draw.x - 100) < 1e-9, 'line x');
  assert.ok(Math.abs(draw.y - 160) < 1e-9, 'line baseline');
  assert.ok(Math.abs(draw.alpha - (0.35 + 0.65 * 0.6)) < 1e-9, 'alpha floor');
});

test('gradient aligns the gradient and sweep band to the first line block', () => {
  const template = T.PROGRAM.gradient;
  const assembled = E.assemble(template.source, { maxLocals: template.maxLocals });
  assert.ok(assembled.ok, assembled.errors.join('; '));
  const sink = new RecordingSink();
  const run = E.runProgram(assembled.program, makeContext(), sink);
  assert.ok(run.ok, run.error);
  const gradient = sink.calls.find((call) => call[0] === 'setGradient');
  assert.ok(gradient, 'no gradient');
  const [, x0, y0, x1, y1] = gradient;
  assert.ok(Math.abs(x0 - 100) < 1e-9, 'gradient x0');
  assert.ok(Math.abs(y0 - 115) < 1e-9, 'gradient y0');
  assert.ok(Math.abs(x1 - 400) < 1e-9, 'gradient x1');
  assert.ok(Math.abs(y1 - 245) < 1e-9, 'gradient y1');
  const band = sink.calls.find((call) => call[0] === 'drawRoundRect');
  assert.ok(band, 'no sweep band');
  const [, bx, by, bw, bh, brx, bry] = band;
  assert.ok(Math.abs(bx - 289.6) < 1e-9, 'band x');
  assert.ok(Math.abs(by - 115) < 1e-9, 'band y');
  assert.ok(Math.abs(bw - 48) < 1e-9, 'band width');
  assert.ok(Math.abs(bh - 60) < 1e-9, 'band height');
  assert.ok(Math.abs(brx - 30) < 1e-9, 'band radius x');
  assert.ok(Math.abs(bry - 30) < 1e-9, 'band radius y');
});

// ---------------------------------------------------------------------------
// 4. Normalization edge cases
// ---------------------------------------------------------------------------

console.log('\n[4] Normalization edge cases');

test('single keyframe expands to 0/1 constants', () => {
  const effect = expectOk(baseEffect({
    tracks: [{ property: 'scale', keyframes: [{ at: 0.5, value: 2, easing: 'easeOut' }] }]
  }));
  assert.deepStrictEqual(effect.tracks[0].keyframes, [
    { at: 0, value: 2, easing: 'easeOut' },
    { at: 1, value: 2, easing: 'easeOut' }
  ]);
});

test('endpoints stretch in place without adding frames', () => {
  const effect = expectOk(baseEffect({
    tracks: [{ property: 'scale', keyframes: [{ at: 0.2, value: 0.5, easing: 'linear' }, { at: 0.8, value: 2, easing: 'linear' }] }]
  }));
  assert.deepStrictEqual(effect.tracks[0].keyframes.map((frame) => frame.at), [0, 1]);
  assert.deepStrictEqual(effect.tracks[0].keyframes.map((frame) => frame.value), [0.5, 2]);
});

test('duplicate at keeps the last frame', () => {
  const effect = expectOk(baseEffect({
    tracks: [{ property: 'scale', keyframes: [{ at: 0.5, value: 1.2, easing: 'linear' }, { at: 0.5, value: 1.8, easing: 'linear' }] }]
  }));
  assert.deepStrictEqual(effect.tracks[0].keyframes.map((frame) => frame.value), [1.8, 1.8]);
});

test('unknown easing falls back to linear', () => {
  const effect = expectOk(baseEffect({
    tracks: [{ property: 'scale', keyframes: [{ at: 0, value: 1, easing: 'nope' }, { at: 1, value: 2, easing: 'linear' }] }]
  }));
  assert.strictEqual(effect.tracks[0].keyframes[0].easing, 'linear');
});

test('unknown property rejects the effect', () => {
  const result = E.normalizeEffectSpec(baseEffect({
    tracks: [{ property: 'bogus', keyframes: [{ at: 0, value: 1, easing: 'linear' }] }]
  }));
  assert.strictEqual(result.ok, false);
});

test('duplicate (property, longOnly) rejects the effect', () => {
  const result = E.normalizeEffectSpec(baseEffect({
    tracks: [
      { property: 'scale', keyframes: [{ at: 0, value: 1, easing: 'linear' }] },
      { property: 'scale', keyframes: [{ at: 0, value: 2, easing: 'linear' }] }
    ]
  }));
  assert.strictEqual(result.ok, false);
});

test('id "none" rejects the effect', () => {
  assert.strictEqual(E.normalizeEffectSpec(baseEffect({ id: 'none' })).ok, false);
});

test('6-digit colors normalize to #AARRGGBB', () => {
  const effect = expectOk(baseEffect({
    colors: [{ at: 0, color: '#aabbcc', easing: 'linear' }]
  }));
  assert.deepStrictEqual(effect.colors.map((frame) => frame.color), ['#FFAABBCC', '#FFAABBCC']);
});

test('unsafe names fall back to id', () => {
  const effect = expectOk(baseEffect({ id: 'safe_id', name: 'javascript:alert(1)' }));
  assert.strictEqual(effect.name, 'safe_id');
});

test('13 tracks reject', () => {
  const tracks = [];
  for (let i = 0; i < 13; i++) {
    tracks.push({ property: 'scale', longOnly: i % 2 === 0, keyframes: [{ at: 0, value: 1, easing: 'linear' }] });
  }
  // Only two (property, longOnly) combinations are legal; use distinct properties
  // by cycling through the property table to isolate the count check.
  const properties = Object.keys(E.PROPERTY_RANGES);
  const uniqueTracks = [];
  for (let i = 0; i < 13; i++) {
    uniqueTracks.push({ property: properties[i % properties.length], longOnly: i >= properties.length, keyframes: [{ at: 0, value: 1, easing: 'linear' }] });
  }
  assert.strictEqual(E.normalizeEffectSpec(baseEffect({ tracks: uniqueTracks })).ok, false);
});

test('17 keyframes in one track reject', () => {
  const keyframes = [];
  for (let i = 0; i < 17; i++) keyframes.push({ at: i / 16, value: 1, easing: 'linear' });
  assert.strictEqual(E.normalizeEffectSpec(baseEffect({
    tracks: [{ property: 'scale', keyframes }]
  })).ok, false);
});

test('64 expanded keyframes pass, 65 reject', () => {
  const makeTrack = (property, count, longOnly) => {
    const keyframes = [];
    for (let i = 0; i < count; i++) keyframes.push({ at: i / (count - 1), value: 1, easing: 'linear' });
    return { property, longOnly, keyframes };
  };
  const ok = E.normalizeEffectSpec(baseEffect({
    tracks: [makeTrack('scale', 16, false), makeTrack('alpha', 16, false), makeTrack('rotate', 16, false), makeTrack('skewX', 16, false)]
  }));
  assert.strictEqual(ok.ok, true, ok.errors.join('; '));
  const bad = E.normalizeEffectSpec(baseEffect({
    tracks: [makeTrack('scale', 16, false), makeTrack('alpha', 16, false), makeTrack('rotate', 16, false), makeTrack('skewX', 13, false), makeTrack('glowAlpha', 13, false)]
  }));
  assert.strictEqual(bad.ok, false);
});

test('program version 2 rejects', () => {
  assert.strictEqual(E.normalizeEffectSpec(baseEffect({
    program: { version: 2, maxLocals: 0, constants: [], code: [{ op: 'end' }] }
  })).ok, false);
});

test('unsafe string constants reject', () => {
  assert.strictEqual(E.normalizeEffectSpec(baseEffect({
    program: { version: 1, maxLocals: 0, constants: [{ type: 'string', value: 'http://evil' }], code: [{ op: 'const', arg: 0 }, { op: 'pop' }, { op: 'end' }] }
  })).ok, false);
});

test('program byte budget rejects oversized programs', () => {
  const constants = [];
  for (let i = 0; i < 64; i++) constants.push({ type: 'string', value: 'a'.repeat(256) });
  const code = [];
  for (let i = 0; i < 512; i++) code.push({ op: 'const', arg: 0 });
  code.push({ op: 'end' });
  const result = E.normalizeProgram({ version: 1, maxLocals: 0, constants, code });
  assert.strictEqual(result.ok, false);
});

test('pack with 33 effects rejects', () => {
  const effects = [];
  for (let i = 0; i < 33; i++) effects.push(baseEffect({ id: 'e' + i }));
  const result = E.normalizePack({ format: E.FORMAT, version: 1, name: 'x', author: '', effects });
  assert.strictEqual(result.ok, false);
});

test('duplicate effect ids produce a warning', () => {
  const result = E.normalizePack({
    format: E.FORMAT,
    version: 1,
    name: 'dup',
    author: '',
    effects: [baseEffect({ id: 'same' }), baseEffect({ id: 'same' })]
  });
  assert.strictEqual(result.ok, true, result.errors.join('; '));
  assert.ok(result.warnings.some((warning) => warning.includes('id 重复')), result.warnings.join('; '));
});

// ---------------------------------------------------------------------------
// 5. Assembler diagnostics
// ---------------------------------------------------------------------------

console.log('\n[5] Assembler diagnostics');

function assembleFails(source) {
  const result = E.assemble(source, { maxLocals: 8 });
  assert.strictEqual(result.ok, false, 'expected failure for:\n' + source);
  assert.ok(result.errors.length > 0);
  return result;
}

test('normalizeProgram preserves and validates wordOverrides', () => {
  const base = { version: 1, constants: [], code: [{ op: 'end' }], maxLocals: 0 };
  const ok = E.normalizeProgram(Object.assign({}, base, {
    wordOverrides: [{ line: 0, word: 1, dx: 1, dy: -1, scale: 1.5, rotate: 10, alpha: 0.5 }]
  }));
  assert.strictEqual(ok.ok, true, ok.errors.join('; '));
  assert.strictEqual(ok.program.wordOverrides.length, 1);
  assert.strictEqual(ok.program.wordOverrides[0].scale, 1.5);
  const badScale = E.normalizeProgram(Object.assign({}, base, {
    wordOverrides: [{ line: 0, word: 1, scale: 0 }]
  }));
  assert.strictEqual(badScale.ok, false);
  const badLine = E.normalizeProgram(Object.assign({}, base, {
    wordOverrides: [{ line: -1, word: 0 }]
  }));
  assert.strictEqual(badLine.ok, false);
});

test('missing push operand fails', () => {
  assembleFails('push\nend');
});

test('unknown opcode fails', () => {
  assembleFails('bogus\nend');
});

test('stack underflow fails', () => {
  const result = assembleFails('push 1\nadd\nend');
  assert.ok(result.errors.some((error) => error.includes('需要 2 个栈值')), result.errors.join('; '));
});

test('drawSprite assembly accounts for five stack operands', () => {
  const result = E.assemble(
    'push 1\npush 10\npush 20\npush 30\npush 40\ndrawSprite\nend',
    { maxLocals: 8 }
  );
  assert.strictEqual(result.ok, true, result.errors.join('; '));
  assert.strictEqual(result.program.code.length, 7);
  // 少推一个操作数必须在汇编期就报栈不足,而不是留到运行期。
  assembleFails('push 1\npush 10\npush 20\npush 30\ndrawSprite\nend');
});

test('missing label fails', () => {
  assembleFails('jump nowhere\nend');
});

test('duplicate label fails', () => {
  assembleFails('x:\nx:\nend');
});

test('local slot out of range fails', () => {
  assembleFails('loadLocal 8\nend');
});

test('unknown field name fails', () => {
  assembleFails('lineField bogus\nend');
});

test('infinite loop is stopped by the step budget', () => {
  const result = E.assemble('loop:\n  jump loop', { maxLocals: 0 });
  assert.ok(result.ok, result.errors.join('; '));
  const run = E.runProgram(result.program, makeContext(), new RecordingSink());
  assert.strictEqual(run.ok, false);
  assert.ok(run.error.includes('预算'), run.error);
  assert.ok(run.steps > 100000);
});

test('trace mode records instructions, stack depth and locals', () => {
  const template = T.PROGRAM.bounce;
  const assembled = E.assemble(template.source, { maxLocals: template.maxLocals });
  assert.ok(assembled.ok, assembled.errors.join('; '));
  const run = E.runProgram(assembled.program, makeContext(), new RecordingSink(), {
    trace: true,
    traceLimit: 500
  });
  assert.ok(run.ok, run.error);
  assert.ok(Array.isArray(run.trace));
  assert.ok(run.trace.length > 0);
  assert.strictEqual(run.traceTruncated, false);
  assert.ok(run.maxStack > 0);
  assert.strictEqual(run.locals.length, template.maxLocals);
  assert.deepStrictEqual(run.trace[0], { pc: 0, op: 'wordCount', arg: null, text: null, depth: 0 });
  assert.strictEqual(assembled.stats.lineMap.length, assembled.program.code.length);
  assert.ok(assembled.stats.lineMap[0] >= 1);
});

test('trace truncation is reported for runaway loops', () => {
  const assembled = E.assemble('loop:\n  jump loop', { maxLocals: 0 });
  assert.ok(assembled.ok, assembled.errors.join('; '));
  const run = E.runProgram(assembled.program, makeContext(), new RecordingSink(), {
    trace: true,
    traceLimit: 100
  });
  assert.strictEqual(run.ok, false);
  assert.strictEqual(run.trace.length, 100);
  assert.strictEqual(run.traceTruncated, true);
});

test('normal rendering does not allocate a trace', () => {
  const template = T.PROGRAM.hello;
  const assembled = E.assemble(template.source, { maxLocals: template.maxLocals });
  const run = E.runProgram(assembled.program, makeContext(), new RecordingSink());
  assert.strictEqual(run.trace, null);
  assert.strictEqual(run.traceTruncated, false);
});

test('static stack checker accepts a balanced loop', () => {
  const result = E.assemble([
    'push 0',
    'storeLocal 0',
    'loop:',
    '  loadLocal 0',
    '  push 3',
    '  lt',
    '  jz done',
    '  loadLocal 0',
    '  push 1',
    '  add',
    '  storeLocal 0',
    '  jump loop',
    'done:',
    '  end'
  ].join('\n'), { maxLocals: 1 });
  assert.ok(result.ok, result.errors.join('; '));
  assert.strictEqual(result.warnings.filter((warning) => warning.includes('不可达')).length, 0);
});

// ---------------------------------------------------------------------------
// 6. Preview model
// ---------------------------------------------------------------------------

console.log('\n[6] Preview model');

test('CJK splits per character, Latin stays per word', () => {
  const tokens = E.tokenizeLine('夜色渐浓 Hello, world');
  assert.deepStrictEqual(tokens.map((token) => token.text), ['夜', '色', '渐', '浓', 'Hello,', 'world']);
});

test('whitespace is recorded as spaceBefore for preview layout', () => {
  const tokens = E.tokenizeLine('夜色 渐浓');
  assert.deepStrictEqual(tokens.map((token) => token.text), ['夜', '色', '渐', '浓']);
  assert.deepStrictEqual(tokens.map((token) => token.spaceBefore), [false, false, true, false]);
});

test('unsungDim uses the documented brightness step', () => {
  assert.ok(Math.abs(E.steadyTextAlpha(0.35) - 0.56) < 1e-9, String(E.steadyTextAlpha(0.35)));
  assert.strictEqual(E.steadyTextAlpha(0.5), 1);
  assert.strictEqual(E.steadyTextAlpha(0.6), 1);
});

test('preview model has contiguous word windows and a trailing hold', () => {
  const model = E.buildPreviewModel('夜色渐浓\nHello world');
  assert.strictEqual(model.lines.length, 2);
  assert.strictEqual(model.words.length, 6);
  for (let i = 1; i < model.words.length; i++) {
    assert.strictEqual(model.words[i].startMs, model.words[i - 1].endMs);
  }
  assert.ok(model.totalMs > model.words[model.words.length - 1].endMs);
  assert.ok(model.words.every((word) => word.durationMs > 0));
});

test('long English words cross the 700ms long-syllable threshold', () => {
  const model = E.buildPreviewModel('Hello world');
  const hello = model.words.find((word) => word.text === 'Hello');
  assert.ok(hello.durationMs >= 700, 'Hello duration=' + hello.durationMs);
});

test('parseLrc reads tags, multiple timestamps, offset and enhanced word tags', () => {
  const entries = E.parseLrc([
    '[ar:someone]',
    '[offset:+120]',
    '[00:12.34]夜色渐浓',
    '[00:16.780][00:20.00]Every word',
    '[00:25.5]<00:25.5>Hello <00:26.0>world'
  ].join('\n'));
  assert.deepStrictEqual(entries.map((entry) => entry.text), [
    '夜色渐浓', 'Every word', 'Every word', 'Hello world'
  ]);
  assert.deepStrictEqual(entries.map((entry) => entry.timeMs), [
    12340 + 120, 16780 + 120, 20000 + 120, 25500 + 120
  ]);
});

test('lineTimings distribute word windows inside each line span', () => {
  const model = E.buildPreviewModel('夜色\nEvery word', {
    lineTimings: [
      { startMs: 1000, endMs: 2000 },
      { startMs: 3000, endMs: 4500 }
    ]
  });
  const first = model.lines[0];
  const second = model.lines[1];
  assert.strictEqual(first.startMs, 1000);
  assert.strictEqual(first.endMs, 2000);
  assert.strictEqual(first.words[0].startMs, 1000);
  assert.strictEqual(first.words[first.words.length - 1].endMs, 2000);
  assert.strictEqual(second.startMs, 3000);
  assert.strictEqual(second.endMs, 4500);
  assert.strictEqual(second.words[second.words.length - 1].endMs, 4500);
  assert.ok(model.totalMs >= 4500);
  const firstDurations = first.words.reduce((sum, word) => sum + word.durationMs, 0);
  assert.strictEqual(firstDurations, 1000);
  const secondDurations = second.words.reduce((sum, word) => sum + word.durationMs, 0);
  assert.strictEqual(secondDurations, 1500);
});

// ---------------------------------------------------------------------------
// 7. Size accounting
// ---------------------------------------------------------------------------

console.log('\n[7] Size accounting');

test('sample packs stay inside the 64 KiB limit', () => {
  for (const file of [dslSamplePath, programSamplePath]) {
    const pack = readJson(file);
    const result = E.normalizePack(pack);
    assert.ok(result.bytesPretty <= E.MAX_PACK_BYTES, file + ': ' + result.bytesPretty);
    assert.ok(result.bytesCompact <= result.bytesPretty);
  }
});

test('estimated program bytes match the documented formula', () => {
  const program = {
    code: [{ op: 'end' }, { op: 'end' }],
    constants: [{ type: 'number', value: '1.5' }]
  };
  assert.strictEqual(E.estimateProgramBytes(program), 2 * 12 + (3 + 8));
});

// ---------------------------------------------------------------------------
// 8. Box output mode (mirrors EffectBoxModeTest.kt)
// ---------------------------------------------------------------------------

console.log('\n[8] Box output mode');

function makeFullContext() {
  const base = makeContext();
  // runProgram 的整块路径需要 lines;补齐两行与 lineIndex(与 Kotlin 整块上下文同构)。
  base.lines = [
    { text: '夜色渐浓', index: 0, startMs: 0, endMs: 1200, progress: 0.6, x: 100, width: 300, baseline: 160, height: 60 },
    { text: 'Hello world', index: 1, startMs: 1200, endMs: 2400, progress: 0.2, x: 120, width: 280, baseline: 230, height: 60 }
  ];
  base.lineIndex = 0;
  return base;
}

const stubMeasure = (text) => text.length * 10;

function collectBoxes(program, ctx) {
  const frame = ctx || makeFullContext();
  const sink = E.createBoxCollectorSink(
    stubMeasure,
    (frame.colors && frame.colors.unsung) || 0,
    frame.textSize || 44
  );
  const result = E.runProgram(program, frame, sink);
  return { result, failed: sink.failed, boxes: sink.boxes };
}

test('drawText becomes a text box with captured paint state', () => {
  const { result, failed, boxes } = collectBoxes({
    outputMode: 'boxes',
    maxLocals: 0,
    constants: [
      { value: '1' },                       // 0: alpha
      { value: '-15654349' },               // 1: 颜色 0xFF112233(带符号十进制)
      { value: '72' },                      // 2: 字号
      { type: 'string', value: '你' },       // 3: 文本
      { value: '15' },                      // 4: x
      { value: '120' }                      // 5: baseline
    ],
    code: [
      { op: 'const', arg: 1 }, { op: 'setColor' },
      { op: 'const', arg: 0 }, { op: 'setAlpha' },
      { op: 'const', arg: 2 }, { op: 'setTextSize' },
      { op: 'const', arg: 3 }, { op: 'const', arg: 4 }, { op: 'const', arg: 5 },
      { op: 'drawText' },
      { op: 'end' }
    ]
  }, makeFullContext());
  assert.ok(result.ok, result.error);
  assert.ok(!failed);
  assert.strictEqual(boxes.length, 1);
  assert.strictEqual(boxes[0].type, 'text');
  assert.strictEqual(boxes[0].text, '你');
  assert.strictEqual(boxes[0].x, 15);
  assert.strictEqual(boxes[0].baseline, 120);
  assert.strictEqual(boxes[0].paint.color, 0xFF112233 | 0);
  assert.strictEqual(boxes[0].paint.alpha, 1);
  assert.strictEqual(boxes[0].paint.textSize, 72);
  const t = boxes[0].transform;
  assert.strictEqual(t.m0, 1); assert.strictEqual(t.m1, 0); assert.strictEqual(t.m2, 0);
  assert.strictEqual(t.m3, 0); assert.strictEqual(t.m4, 1); assert.strictEqual(t.m5, 0);
});

test('translate+rotate bakes the same matrix as direct canvas semantics', () => {
  // Kotlin golden:translate(100,50) → rotate(90°) → drawText("X",10,20) ⇒ 点 (10,20) → (80,60)。
  const { result, failed, boxes } = collectBoxes({
    outputMode: 'boxes',
    maxLocals: 0,
    constants: [
      { type: 'string', value: 'X' },
      { value: '10' }, { value: '20' },
      { value: '100' }, { value: '50' }, { value: '90' }
    ],
    code: [
      { op: 'const', arg: 3 }, { op: 'const', arg: 4 }, { op: 'translate' },
      { op: 'const', arg: 5 }, { op: 'rotate' },
      { op: 'const', arg: 0 }, { op: 'const', arg: 1 }, { op: 'const', arg: 2 },
      { op: 'drawText' },
      { op: 'end' }
    ]
  }, makeFullContext());
  assert.ok(result.ok && !failed);
  const t = boxes[0].transform;
  const x = t.m0 * 10 + t.m1 * 20 + t.m2;
  const y = t.m3 * 10 + t.m4 * 20 + t.m5;
  assert.ok(Math.abs(x - 80) < 1e-3, 'x=' + x);
  assert.ok(Math.abs(y - 60) < 1e-3, 'y=' + y);
});

test('box count budget fails the frame (no partial boxes)', () => {
  const loopStart = 2;
  const code = [
    { op: 'const', arg: 0 }, { op: 'storeLocal', arg: 0 },
    { op: 'loadLocal', arg: 0 }, { op: 'const', arg: 2 }, { op: 'lt' },
    { op: 'jz', arg: 0 }, // 占位
    { op: 'const', arg: 3 }, { op: 'const', arg: 3 }, { op: 'const', arg: 3 }, { op: 'drawText' },
    { op: 'loadLocal', arg: 0 }, { op: 'const', arg: 1 }, { op: 'add' }, { op: 'storeLocal', arg: 0 },
    { op: 'jump', arg: loopStart },
    { op: 'end' }
  ];
  code[5] = { op: 'jz', arg: code.length - 1 };
  const { failed } = collectBoxes({
    outputMode: 'boxes',
    maxLocals: 1,
    constants: [
      { value: '0' }, { value: '1' },
      { value: String(E.MAX_EFFECT_BOXES + 8) },
      { type: 'string', value: 'w' }
    ],
    code
  });
  assert.ok(failed);
});

test('path segment budget fails the frame', () => {
  const code = [];
  for (let i = 0; i < E.MAX_EFFECT_PATH_POINTS_PER_BOX + 1; i++) {
    code.push({ op: 'const', arg: 1 }, { op: 'const', arg: 0 }, { op: 'pathLine' });
  }
  code.push({ op: 'drawPath' }, { op: 'end' });
  const { failed } = collectBoxes({
    outputMode: 'boxes',
    maxLocals: 0,
    constants: [{ value: '0' }, { value: '1' }],
    code
  });
  assert.ok(failed);
});

test('normalizeProgram preserves boxes outputMode and drops unknown values', () => {
  const boxes = E.normalizeProgram({ outputMode: 'boxes', code: [{ op: 'end' }] });
  assert.ok(boxes.ok);
  assert.strictEqual(boxes.program.outputMode, 'boxes');
  const unknown = E.normalizeProgram({ outputMode: 'particles', code: [{ op: 'end' }] });
  assert.ok(unknown.ok);
  assert.strictEqual(unknown.program.outputMode, undefined);
  const legacy = E.normalizeProgram({ code: [{ op: 'end' }] });
  assert.ok(legacy.ok);
  assert.strictEqual(legacy.program.outputMode, undefined);
});

test('Kotlin parity: JS box collector reproduces EffectBoxModeTest goldens', () => {
  // 与 EffectBoxModeTest.rotateScaleCompositionStaysExactAsMatrix 同一程序:
  // scale(2,3) → rotate(90°):M = S·R = [2c -2s; 3s 3c]。
  const { result, failed, boxes } = collectBoxes({
    outputMode: 'boxes',
    maxLocals: 0,
    constants: [
      { type: 'string', value: 'Y' },
      { value: '2' }, { value: '3' }, { value: '90' }
    ],
    code: [
      { op: 'const', arg: 1 }, { op: 'const', arg: 2 }, { op: 'scale' },
      { op: 'const', arg: 3 }, { op: 'rotate' },
      { op: 'const', arg: 0 }, { op: 'const', arg: 0 }, { op: 'const', arg: 0 },
      { op: 'drawText' },
      { op: 'end' }
    ]
  }, makeFullContext());
  assert.ok(result.ok && !failed);
  const t = boxes[0].transform;
  const rad = (90 * Math.PI) / 180;
  const c = Math.cos(rad);
  const s = Math.sin(rad);
  assert.ok(Math.abs(t.m0 - 2 * c) < 1e-4, 'm0=' + t.m0);
  assert.ok(Math.abs(t.m1 - (-2 * s)) < 1e-4, 'm1=' + t.m1);
  assert.ok(Math.abs(t.m3 - 3 * s) < 1e-4, 'm3=' + t.m3);
  assert.ok(Math.abs(t.m4 - 3 * c) < 1e-4, 'm4=' + t.m4);
});

test('drawSprite becomes a sprite box and drops unknown ids', () => {
  const program = {
    outputMode: 'boxes',
    maxLocals: 0,
    constants: [{ value: '7' }, { value: '10' }, { value: '20' }, { value: '40' }, { value: '40' }],
    code: [
      { op: 'const', arg: 0 }, { op: 'const', arg: 1 }, { op: 'const', arg: 2 },
      { op: 'const', arg: 3 }, { op: 'const', arg: 4 }, { op: 'drawSprite' }, { op: 'end' }
    ]
  };
  const { result, failed, boxes } = collectBoxes(program);
  assert.ok(result.ok && !failed);
  assert.strictEqual(boxes.length, 1);
  assert.strictEqual(boxes[0].type, 'sprite');
  assert.strictEqual(boxes[0].spriteId, 7);
  assert.strictEqual(boxes[0].x, 10);
  assert.strictEqual(boxes[0].width, 40);

  // 越界编号:静默丢弃,帧不失败
  const bad = Object.assign({}, program, {
    constants: [{ value: '300' }, { value: '0' }, { value: '0' }, { value: '10' }, { value: '10' }]
  });
  const badResult = collectBoxes(bad);
  assert.ok(badResult.result.ok && !badResult.failed);
  assert.strictEqual(badResult.boxes.length, 0);
});

console.log('\n' + passed + ' tests passed' + (process.exitCode ? ' — with failures' : ''));
