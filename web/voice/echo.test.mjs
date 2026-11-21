// Loudspeaker echo canceller (echo.js): real speech from testdata/audio played
// through a simulated phone (delay, room echo, speaker distortion, noise).
// The mic must stay closed while only the assistant is heard, and open
// quickly when the user talks over it.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { EchoCanceller, AEC_RATE } from './echo.js';
import { Resampler } from './audio-util.js';

function readWav(name) {
  const b = readFileSync(new URL(`../../testdata/audio/${name}`, import.meta.url));
  let off = 12;
  let rate = 0;
  let data = null;
  while (off + 8 <= b.length) {
    const id = b.toString('ascii', off, off + 4);
    const len = b.readUInt32LE(off + 4);
    if (id === 'fmt ') rate = b.readUInt32LE(off + 12);
    if (id === 'data') data = b.subarray(off + 8, off + 8 + len);
    off += 8 + len + (len & 1);
  }
  const pcm = new Float32Array(data.length >> 1);
  for (let i = 0; i < pcm.length; i++) pcm[i] = data.readInt16LE(i * 2) / 32768;
  if (rate === AEC_RATE) return pcm;
  return new Resampler(rate, AEC_RATE).process(pcm);
}

// rand is a fixed pseudo-random sequence so every run is the same.
function rand(seed) {
  let s = seed >>> 0;
  return () => ((s = (s * 1664525 + 1013904223) >>> 0) / 2 ** 32) * 2 - 1;
}

// phone turns what the page plays into what the mic hears: delay, a short
// room response, a speaker that distorts when loud, and some noise.
function phone(ref, { delayMs = 180, gain = 0.8, seed = 1 } = {}) {
  const r = rand(seed);
  const d = Math.round((delayMs / 1000) * AEC_RATE);
  const ir = new Float32Array(400); // 25 ms
  ir[0] = 1;
  for (let k = 1; k < ir.length; k++) ir[k] = r() * 0.3 * Math.exp(-k / 80);
  const out = new Float32Array(ref.length);
  for (let t = 0; t < ref.length; t++) {
    let y = 0;
    for (let k = 0; k < ir.length; k++) {
      const i = t - d - k;
      if (i >= 0) y += ir[k] * ref[i];
    }
    y = Math.tanh(1.5 * gain * y) / 1.5; // speaker distortion
    out[t] = y + r() * 3e-4;
  }
  return out;
}

function run(ref, mic, block = 43) {
  const aec = new EchoCanceller();
  const timeline = []; // [seconds, talking, bargeIns]
  let outSamples = 0;
  let outEnergyByTime = [];
  for (let i = 0; i < ref.length; i += block) {
    const chunks = aec.process(mic.subarray(i, i + block), ref.subarray(i, i + block));
    for (const c of chunks) {
      let e = 0;
      for (const v of c) e += v * v;
      outEnergyByTime.push([outSamples / AEC_RATE, e / c.length]);
      outSamples += c.length;
    }
    timeline.push([i / AEC_RATE, aec.stats.talking, aec.stats.bargeIns]);
  }
  return { aec, timeline, outEnergyByTime };
}

function concat(...parts) {
  const n = parts.reduce((a, p) => a + p.length, 0);
  const out = new Float32Array(n);
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

const reply = readWav('reply-briefing.wav');
const question = readWav('question.wav');
// The assistant speaks for 7.4 s (the reply twice), then 1 s of quiet.
const ref = concat(reply, reply, new Float32Array(AEC_RATE));

test('echo alone never opens the mic', () => {
  const mic = phone(ref);
  const { aec } = run(ref, mic);
  assert.equal(aec.stats.bargeIns, 0, 'false barge-in on echo only');
  assert.ok(Math.abs(aec.stats.delayMs - 180) <= 10, `delay ${aec.stats.delayMs} ms`);
  assert.ok(aec.stats.erleDb > 6, `echo removed only ${aec.stats.erleDb.toFixed(1)} dB`);
});

test('talking over the assistant opens the mic within 150 ms', () => {
  const echo = phone(ref);
  const startS = 4.5;
  const mic = echo.slice();
  const at = Math.round(startS * AEC_RATE);
  for (let i = 0; i < question.length && at + i < mic.length; i++) mic[at + i] += question[i] * 0.7;
  const { aec, timeline } = run(ref, mic);
  const first = timeline.find(([, talking]) => talking);
  assert.ok(first, 'the user was never heard');
  assert.ok(first[0] >= startS, `opened at ${first[0].toFixed(2)} s, before the user spoke`);
  assert.ok(first[0] <= startS + 0.15, `opened late, at ${first[0].toFixed(2)} s`);
  assert.equal(aec.stats.bargeIns, 1);
});

test('a different delay and a louder speaker are found too', () => {
  const mic = phone(ref, { delayMs: 340, gain: 1.6, seed: 7 });
  const { aec } = run(ref, mic);
  assert.ok(Math.abs(aec.stats.delayMs - 340) <= 10, `delay ${aec.stats.delayMs} ms`);
  assert.equal(aec.stats.bargeIns, 0, 'false barge-in on echo only');
});

test('the mic passes through while the assistant is quiet', () => {
  const silent = new Float32Array(AEC_RATE * 2);
  const mic = new Float32Array(silent.length);
  mic.set(question.subarray(0, Math.min(question.length, mic.length)), 0);
  const { outEnergyByTime } = run(silent, mic);
  const loud = outEnergyByTime.filter(([, e]) => e > 1e-5).length;
  assert.ok(loud > 50, `only ${loud} frames of the user came through`);
});
