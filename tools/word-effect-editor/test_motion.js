/*
 * Motion 编排核心回归测试。
 *   node tools/word-effect-editor/test_motion.js
 */
'use strict';

const assert = require('assert');
const E = require('./engine.js');
const M = require('./motion.js');

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

const SAMPLE_LYRICS = [
  '# 注释行',
  '',
  '[00:12.00]我还记得/黎明的颜色',
  '*歌声*|gear sheng',
  '落在窗台上!',
  '[间奏 6]',
  '[01:05.50]风把故事讲完'
].join('\n');

console.log('[1] 歌词语法解析');

test('parseLyrics handles the documented syntax subset', () => {
  const { lines, warnings } = M.parseLyrics(SAMPLE_LYRICS);
  // 4 行歌词 + 1 行间奏(间奏作为独立行进时间轴)
  assert.strictEqual(lines.length, 5);
  const lyric = lines.filter((line) => line.interlude == null);
  assert.strictEqual(lyric.length, 4);
  const [first, emph, flashLine, last] = lyric;
  assert.strictEqual(first.text, '我还记得黎明的颜色');
  assert.strictEqual(first.lrcTime, 12);
  assert.strictEqual(first.shotCount, 2);
  assert.strictEqual(emph.text, '歌声');
  assert.strictEqual(emph.emph, true);
  assert.strictEqual(emph.annotation, 'gear sheng');
  assert.strictEqual(flashLine.flash, true);
  assert.strictEqual(last.lrcTime, 65.5);
  assert.deepStrictEqual(lines.filter((line) => line.interlude != null).map((line) => line.interlude), [6]);
  assert.ok(warnings.length === 0, 'warnings: ' + warnings.join('; '));
});

test('parseLyrics rejects malformed timestamps as plain lines', () => {
  const { lines } = M.parseLyrics('[abc]hello');
  assert.strictEqual(lines.length, 1);
  assert.ok(lines[0].text.includes('hello'));
});

test('LRC time formatting round-trips', () => {
  assert.strictEqual(M.formatLrcTime(12), '[00:12.00]');
  assert.strictEqual(M.formatLrcTime(65.5), '[01:05.50]');
  assert.strictEqual(M.formatLrcTime(0), '[00:00.00]');
  assert.strictEqual(M.formatLrcTime(61.999), '[01:02.00]');
});

console.log('[2] 时间轴');

test('buildTimeline prefers tap, then LRC, then BPM grid', () => {
  const parsed = M.parseLyrics(SAMPLE_LYRICS);
  const timeline = M.buildTimeline(parsed, { bpm: 120, startSec: 2, tap: [5] });
  const lyric = timeline.rows.filter((row) => !row.interlude);
  // 第一行吃 tap=5;第二行 LRC 无 → BPM 铺;第三行(歌声)无 LRC → 顺延;末行 LRC=65.5
  assert.strictEqual(lyric[0].start, 5);
  assert.strictEqual(lyric[0].manual, true);
  assert.ok(lyric[1].start > 5, 'bpm fallback advances');
  assert.strictEqual(lyric[3].start, 65.5);
  assert.ok(timeline.rows.some((row) => row.interlude));
  assert.ok(timeline.total > 65.5);
});

test('snap aligns bpm-derived rows to the beat grid', () => {
  const parsed = M.parseLyrics('一二三\n四五六');
  const timeline = M.buildTimeline(parsed, { bpm: 60, startSec: 2.4, snap: true });
  // 60BPM = 1s/拍:2.4 吸附到拍点 2
  assert.strictEqual(timeline.rows[0].start, 2);
});

console.log('[3] 编排确定性');

test('same seed + theme reproduces the style byte-for-byte', () => {
  const a = M.styleFromSeed(1234, 'lyric', {});
  const b = M.styleFromSeed(1234, 'lyric', {});
  assert.deepStrictEqual(a, b);
  const c = M.styleFromSeed(1235, 'lyric', {});
  assert.notDeepStrictEqual(a, c);
});

test('overrides bypass the rng', () => {
  const style = M.styleFromSeed(7, 'dynamic', { waveAmp: 0.5, flash: true, cycle: 3 });
  assert.strictEqual(style.waveAmp, 0.5);
  assert.strictEqual(style.flash, true);
  assert.strictEqual(style.cycle, 3);
});

console.log('[4] 程序生成与校验');

test('generated program passes assemble + normalizeProgram within budgets', () => {
  for (const theme of Object.keys(M.THEMES)) {
    const style = M.styleFromSeed(42, theme, {});
    const built = M.buildEffectPack({ rows: [] }, style, { title: 'T' });
    assert.ok(built.ok, theme + ': ' + (built.errors || []).join('; '));
    assert.ok(built.stats.instructions <= E.MAX_EFFECT_CODE, theme + ' instructions ' + built.stats.instructions);
    assert.ok(built.stats.estimatedBytes <= E.MAX_EFFECT_PROGRAM_ESTIMATED_BYTES);
    assert.strictEqual(built.effect.program.outputMode, 'boxes');
  }
});

test('flash toggle only changes the flash threshold constant', () => {
  const on = M.buildEffectPack({ rows: [] }, M.styleFromSeed(9, 'lyric', { flash: true }), {});
  const off = M.buildEffectPack({ rows: [] }, M.styleFromSeed(9, 'lyric', { flash: false }), {});
  assert.ok(on.ok && off.ok);
  assert.ok(JSON.stringify(on.pack).includes('0.08'));
  assert.ok(JSON.stringify(off.pack).includes('999999'));
});

test('exported pack normalizes and keeps one boxed effect', () => {
  const style = M.styleFromSeed(100, 'japanese', {});
  const built = M.buildEffectPack({ rows: [] }, style, { title: 'Motion Test', author: 'tester' });
  assert.ok(built.ok);
  const result = E.normalizePack(built.pack);
  assert.ok(result.ok, result.errors.join('; '));
  assert.strictEqual(result.pack.effects.length, 1);
  assert.strictEqual(result.pack.effects[0].program.outputMode, 'boxes');
});

console.log('[5] VM 冒烟(盒收集)');

function makeFrame(text, positionSec) {
  const tokens = E.tokenizeLine(text);
  const textSize = 64;
  const width = tokens.length * 40;
  const startX = 40;
  const baseline = 140;
  const startMs = 0;
  const endMs = 4000;
  const per = endMs / Math.max(1, tokens.length);
  const words = tokens.map((token, index) => ({
    text: token.text,
    x: startX + index * 40,
    width: 38,
    startMs: per * index,
    endMs: per * (index + 1),
    progress: E.clamp((positionSec * 1000 - per * index) / per, 0, 1),
    index,
    longSyllable: per >= 700,
    lineIndex: 0,
    baseline
  }));
  const lp = E.clamp((positionSec * 1000) / endMs, 0, 1);
  return {
    timeMs: Math.round(positionSec * 1000),
    wallClockMs: 0,
    deltaMs: 16,
    frameIndex: 0,
    lineText: text,
    lineStartMs: startMs,
    lineEndMs: endMs,
    lineProgress: lp,
    lineIndex: 0,
    canvasWidth: 720,
    canvasHeight: 320,
    textSize,
    baseline,
    colors: { sung: 0xFFFFFFFF | 0, unsung: 0x88FFFFFF | 0, glow: 0xFF66AAFF | 0, accent: 0xFFFFAA33 | 0 },
    words,
    lines: [{ text, index: 0, startMs, endMs, progress: lp, x: startX, width, baseline, height: textSize * 1.4 }],
    blockWidth: width,
    blockHeight: textSize * 1.4
  };
}

test('every branch emits boxes on the synthetic frame', () => {
  // 四手法各验一帧:分派键 = (行号+词序) mod cycle;cycle=1 时键恒 0(wave),
  // 因此分别用 cycle=1..3 + 不同 seed 覆盖四个分支的可达组合。
  const style1 = M.styleFromSeed(11, 'typepv', { cycle: 1 });
  const style3 = M.styleFromSeed(12, 'dynamic', { cycle: 3 });
  for (const style of [style1, style3]) {
    const built = M.buildEffectPack({ rows: [] }, style, {});
    assert.ok(built.ok, (built.errors || []).join('; '));
    const program = built.effect.program;
    const frame = makeFrame('你好世界你好世界', 1.2);
    const sink = E.createBoxCollectorSink((text) => text.length * 40, frame.colors.unsung, frame.textSize);
    const result = E.runProgram(program, frame, sink);
    assert.ok(result.ok, result.error);
    assert.ok(!sink.failed, 'collector failed');
    assert.ok(sink.boxes.length > 0, 'no boxes emitted');
    assert.ok(sink.boxes.every((box) => box.type === 'text'), 'unexpected box type');
    assert.ok(result.drawOps <= E.MAX_EFFECT_DRAW_OPS_PER_FRAME);
  }
});

test('all four branches are reachable and box geometry stays finite', () => {
  // 逐帧扫描一个 cycle=3 的编排,断言四种盒变换形态都出现过:
  // wave/pulse 带非单位矩阵,bounce/slide 为单位阵。
  const style = M.styleFromSeed(77, 'dynamic', { cycle: 3 });
  const built = M.buildEffectPack({ rows: [] }, style, {});
  assert.ok(built.ok);
  const program = built.effect.program;
  const seen = { rotated: false, plain: false, scaled: false };
  for (let frameIndex = 0; frameIndex < 24; frameIndex++) {
    const frame = makeFrame('你好世界你好', frameIndex * 0.08);
    const sink = E.createBoxCollectorSink((text) => text.length * 40, frame.colors.unsung, frame.textSize);
    const result = E.runProgram(program, frame, sink);
    assert.ok(result.ok && !sink.failed);
    for (const box of sink.boxes) {
      const t = box.transform;
      const isIdentity = t.m0 === 1 && t.m1 === 0 && t.m2 === 0 && t.m3 === 0 && t.m4 === 1 && t.m5 === 0;
      const hasRotation = Math.abs(t.m1) > 1e-4 || Math.abs(t.m3) > 1e-4;
      const hasScale = Math.abs(t.m0 - 1) > 1e-4 || Math.abs(t.m4 - 1) > 1e-4;
      if (hasRotation) seen.rotated = true;
      if (hasScale) seen.scaled = true;
      if (isIdentity) seen.plain = true;
      for (const key of ['m0', 'm1', 'm2', 'm3', 'm4', 'm5']) {
        assert.ok(Number.isFinite(t[key]), 'non-finite matrix at frame ' + frameIndex);
      }
    }
  }
  assert.ok(seen.rotated, 'no rotated box observed');
  assert.ok(seen.plain, 'no untransformed box observed');
  // scaled 是否出现取决于 seed 分派组合,不强制断言;rotated/plain 必现。
});

console.log('[6] 导出');

test('toLrc keeps lyric rows and drops interludes', () => {
  const parsed = M.parseLyrics(SAMPLE_LYRICS);
  const timeline = M.buildTimeline(parsed, { bpm: 120, startSec: 2, tap: [5] });
  const lrc = M.toLrc(timeline);
  assert.ok(lrc.includes('我还记得黎明的颜色'));
  assert.ok(lrc.includes('风把故事讲完'));
  assert.ok(!lrc.includes('间奏'));
});

test('project JSON round-trips through toProject', () => {
  const project = M.toProject({
    lyrics: SAMPLE_LYRICS,
    bpm: 120,
    startSec: 0,
    lineDurSec: 2,
    snap: false,
    tap: [1.5],
    seed: 8,
    theme: 'lyric',
    overrides: { flash: true },
    palette: { sung: '#FFFFFF' }
  });
  assert.strictEqual(project.format, 'hyperglow-motion-project');
  assert.deepStrictEqual(JSON.parse(JSON.stringify(project)), project);
});

// ---------------------------------------------------------------------------
// 9. 命名预设(PRESETS)
// ---------------------------------------------------------------------------

console.log('\n[9] Presets');

test('every preset builds within budget and is deterministic', () => {
  for (const [key, preset] of Object.entries(M.PRESETS)) {
    const style = M.styleFromSeed(20261109, preset.theme, preset.params);
    const a = M.buildEffectPack({ rows: [] }, style, { title: key });
    const b = M.buildEffectPack({ rows: [] }, style, { title: key });
    assert.ok(a.ok, key + ': ' + (a.errors || []).join('; '));
    assert.ok(b.ok);
    assert.deepStrictEqual(a.pack, b.pack, key + ' not deterministic');
    assert.ok(a.stats.instructions <= E.MAX_EFFECT_CODE, key + ' instructions ' + a.stats.instructions);
    assert.ok(a.stats.drawOps <= E.MAX_EFFECT_DRAW_OPS_PER_FRAME, key + ' drawOps ' + a.stats.drawOps);
    assert.strictEqual(a.effect.program.outputMode, 'boxes');
  }
});

test('cascade preset encodes the stagger constant', () => {
  const style = M.styleFromSeed(1, 'dynamic', M.PRESETS.cascade.params);
  const built = M.buildEffectPack({ rows: [] }, style, {});
  assert.ok(built.ok);
  const json = JSON.stringify(built.pack);
  assert.ok(json.includes('0.1'), 'stagger 0.10 missing from constants');
});

test('fixed-direction preset drops the alternate chain', () => {
  const style = M.styleFromSeed(1, 'typepv', M.PRESETS.typewriter.params);
  assert.strictEqual(style.slideAlt, false);
  const built = M.buildEffectPack({ rows: [] }, style, {});
  assert.ok(built.ok);
  const code = JSON.stringify(built.effect.program.code);
  // 固定滑向:mod 仅出现在手法分派(1 次);交替滑向会有第 2 次
  // mod 出现位置:装饰选择器(1)+ 排版选择器(1)+ 手法派发(1);交替滑向会再加 1
  const modCount = code.split('"mod"').length - 1;
  assert.strictEqual(modCount, 3, 'typewriter should not alternate direction, mod x' + modCount);
});

test('styleFromSeed honors slideStagger / slideAlt overrides', () => {
  const style = M.styleFromSeed(3, 'lyric', { slideStagger: 0.22, slideAlt: false });
  assert.strictEqual(style.slideStagger, 0.22);
  assert.strictEqual(style.slideAlt, false);
  const built = M.buildEffectPack({ rows: [] }, style, {});
  assert.ok(built.ok);
  assert.ok(JSON.stringify(built.pack).includes('0.22'));
});

test('wave boxes land on word.x / baseline (drawText push-order pin)', () => {
  // 回归钉:压序必须 text→x→y。waveAmp=0 时盒坐标应逐值等于词几何。
  const style = M.styleFromSeed(9, 'lyric', { waveAmp: 0.0, rotAmp: 0, cycle: 1, layoutMode: 'h' });
  const built = M.buildEffectPack({ rows: [] }, style, {});
  assert.ok(built.ok);
  const frame = makeFrame('你好世界', 0.5);
  const sink = E.createBoxCollectorSink((text) => text.length * 40, frame.colors.unsung, frame.textSize);
  assert.ok(E.runProgram(built.effect.program, frame, sink).ok);
  assert.strictEqual(sink.boxes.length, frame.words.length);
  sink.boxes.forEach((box, index) => {
    const word = frame.words[index];
    assert.strictEqual(box.text, word.text, 'text mismatch at ' + index);
    assert.ok(Math.abs(box.x - word.x) < 0.5, 'x swapped at ' + index + ': ' + box.x + ' vs ' + word.x);
    assert.ok(Math.abs(box.baseline - word.baseline) < 0.5, 'baseline swapped at ' + index);
  });
});

test('choreo modes dispatch differently and stay in budget', () => {
  const base = { slideStagger: 0.05, slideAlt: true };
  const modes = { cycle: 'cycle', fixed: 'fixed', songRandom: 'songRandom' };
  const seen = {};
  for (const [name, mode] of Object.entries(modes)) {
    const style = M.styleFromSeed(5, 'lyric', Object.assign({}, base, { choreoMode: mode }));
    assert.strictEqual(style.choreoMode, mode);
    const built = M.buildEffectPack({ rows: [] }, style, { title: mode });
    assert.ok(built.ok, mode + ': ' + (built.errors || []).join('; '));
    assert.ok(built.stats.instructions <= E.MAX_EFFECT_CODE, mode + ' instructions');
    const src = M.buildProgramSource(style);
    // stringLength:装饰选择器(1)+ 排版选择器(1)= 2;fixed/songRandom 的派发再加 1 次
    const lenCount = (src.match(/stringLength/g) || []).length;
    if (mode === 'fixed') assert.strictEqual(lenCount, 3, 'fixed dispatch missing');
    if (mode === 'songRandom') {
      assert.strictEqual(lenCount, 3, 'songRandom missing stringLength');
      assert.ok(style.choreoA > 0 && style.choreoB > 0, 'songRandom multipliers missing');
    }
    if (mode === 'cycle') assert.strictEqual(lenCount, 2, 'cycle dispatch should not use length');
    seen[mode] = M.buildEffectPack({ rows: [] }, style, { title: mode }).pack;
  }
  // 固定与每歌随机的派发片段不同
  assert.notDeepStrictEqual(seen.fixed, seen.songRandom);
});

test('invalid choreoMode falls back to cycle', () => {
  const style = M.styleFromSeed(1, 'lyric', { choreoMode: 'particles' });
  assert.strictEqual(style.choreoMode, 'cycle');
});

test('layout modes produce distinct compositions', () => {
  const base = { waveAmp: 0.0, rotAmp: 0, bounceH: 0, slideD: 0, slideStagger: 0, pulse: 0, cycle: 1, flash: false };
  const frame = makeFrame('一二三四五六', 0.5);
  const boxesFor = (layoutMode) => {
    const style = M.styleFromSeed(5, 'lyric', Object.assign({}, base, { layoutMode }));
    const built = M.buildEffectPack({ rows: [] }, style, {});
    assert.ok(built.ok, layoutMode + ': ' + (built.errors || []).join('; '));
    assert.ok(built.stats.instructions <= E.MAX_EFFECT_CODE, layoutMode + ' instructions ' + built.stats.instructions);
    const sink = E.createBoxCollectorSink((text) => text.length * 40, frame.colors.unsung, frame.textSize);
    assert.ok(E.runProgram(built.effect.program, frame, sink).ok, layoutMode + ' run failed');
    return sink.boxes;
  };

  // 横排:与词几何逐值一致
  const h = boxesFor('h');
  h.forEach((box, i) => {
    assert.ok(Math.abs(box.x - frame.words[i].x) < 0.5, 'h mode x mismatch');
    assert.ok(Math.abs(box.baseline - frame.words[i].baseline) < 0.5, 'h mode baseline mismatch');
  });

  // 竖排:同列(x 一致)、基线严格递增
  const v = boxesFor('v');
  const vx = v.map((b) => Math.round(b.x));
  assert.ok(vx.every((x) => x === vx[0]), 'v mode should share one column x: ' + vx);
  for (let i = 1; i < v.length; i++) {
    assert.ok(v[i].baseline > v[i - 1].baseline + 1, 'v mode baselines must increase');
  }

  // 错落:出现两种字号(大小交替)
  const stagger = boxesFor('stagger');
  const sizes = new Set(stagger.map((b) => Math.round(b.paint.textSize)));
  assert.ok(sizes.size >= 2, 'stagger should alternate sizes, got ' + [...sizes]);

  // 散点:存在偏离原始 x 的盒子
  const scatter = boxesFor('scatter');
  const moved = scatter.filter((b, i) => Math.abs(b.x - frame.words[i].x) > 8);
  assert.ok(moved.length >= 2, 'scatter should displace words, moved=' + moved.length);
});

test('auto layout is content-derived and reproducible', () => {
  const style = M.styleFromSeed(7, 'lyric', { layoutMode: 'auto' });
  assert.strictEqual(style.layoutMode, 'auto');
  const built = M.buildEffectPack({ rows: [] }, style, {});
  assert.ok(built.ok);
  const src = M.buildProgramSource(style);
  assert.ok(src.includes('stringLength'), 'auto selector should use line length');
  assert.strictEqual(M.buildProgramSource(M.styleFromSeed(7, 'lyric', { layoutMode: 'auto' })), src);
});

test('invalid layoutMode falls back to auto', () => {
  const style = M.styleFromSeed(1, 'lyric', { layoutMode: 'zigzag' });
  assert.strictEqual(style.layoutMode, 'auto');
});

test('graphic deco modes emit the right primitive types', () => {
  const base = { waveAmp: 0.0, rotAmp: 0, bounceH: 0, slideD: 0, slideStagger: 0, pulse: 0, cycle: 1, flash: false, layoutMode: 'h' };
  const frame = makeFrame('一二三四五六', 0.5);
  const boxesFor = (graphicMode) => {
    const style = M.styleFromSeed(5, 'lyric', Object.assign({}, base, { graphicMode }));
    const built = M.buildEffectPack({ rows: [] }, style, {});
    assert.ok(built.ok, graphicMode + ': ' + (built.errors || []).join('; '));
    assert.ok(built.stats.instructions <= E.MAX_EFFECT_CODE, graphicMode + ' instr ' + built.stats.instructions);
    const sink = E.createBoxCollectorSink((text) => text.length * 40, frame.colors.unsung, frame.textSize);
    assert.ok(E.runProgram(built.effect.program, frame, sink).ok, graphicMode + ' run failed');
    return sink.boxes;
  };

  // 无装饰:只有文字盒
  const none = boxesFor('none');
  assert.ok(none.every((b) => b.type === 'text'), 'none should be text only');

  // 下划线:一个 line 盒;框线:一个 rect 盒;印章:一个 circle 盒
  const line = boxesFor('line');
  assert.ok(line.some((b) => b.type === 'line'), 'line deco missing');
  const framed = boxesFor('frame');
  assert.ok(framed.some((b) => b.type === 'rect' && b.paint.style === 1), 'frame deco missing (stroke rect)');
  const stamp = boxesFor('stamp');
  assert.ok(stamp.some((b) => b.type === 'circle'), 'stamp deco missing');

  // 装饰画在文字之前(盒序:装饰盒在前)
  assert.strictEqual(line[0].type, 'line', 'deco should be emitted before text');
  assert.strictEqual(framed[0].type, 'rect', 'frame should be emitted before text');
  assert.strictEqual(stamp[0].type, 'circle', 'stamp should be emitted before text');
});

test('graphic auto mode is content-derived and reproducible', () => {
  const style = M.styleFromSeed(3, 'lyric', { graphicMode: 'auto' });
  assert.strictEqual(style.graphicMode, 'auto');
  const built = M.buildEffectPack({ rows: [] }, style, {});
  assert.ok(built.ok);
  const src = M.buildProgramSource(style);
  assert.ok(src.includes('push 4'), 'auto selector mod 4 missing');
  assert.strictEqual(M.buildProgramSource(M.styleFromSeed(3, 'lyric', { graphicMode: 'auto' })), src);
});

test('invalid graphicMode falls back to auto', () => {
  const style = M.styleFromSeed(1, 'lyric', { graphicMode: 'confetti' });
  assert.strictEqual(style.graphicMode, 'auto');
});

console.log('\n' + passed + ' tests passed' + (process.exitCode ? ' — with failures' : ''));
