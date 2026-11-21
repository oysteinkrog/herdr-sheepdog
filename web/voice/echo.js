// Echo canceller for Loudspeaker mode, using the voice the page itself plays.
//
// Why: Chrome on Android plays a page at the quiet call volume while its mic
// runs with the browser's echo cancellation (measured 2026-11-21). With that
// turned off the voice plays at media volume, but the mic then hears the
// assistant. This module removes the assistant from the mic signal using the
// exact samples the page sent to the speaker (the reference), so the user can
// still talk over the assistant to interrupt it.
//
// Three parts, all at 16 kHz on the capture worklet's thread:
//   1. Delay search: the speaker-to-mic delay on a phone is 50-500 ms and not
//      known in advance. Every 0.5 s the mic's 4 ms envelope is correlated
//      with the reference's to find it.
//   2. Linear echo canceller: an NLMS adaptive filter (512 taps, 32 ms)
//      placed at that delay subtracts its estimate of the echo.
//   3. Talk detector: what is left is compared with the echo still expected
//      (learned per phone and position). While the assistant speaks the mic
//      stays silent unless the leftover is clearly louder than that: then the
//      user is talking, and the mic opens (with the 40 ms before it) for at
//      least a second, so the voice model hears the user and stops.
//
// Pure: no Web Audio, so node tests drive it with synthetic signals.

export const AEC_RATE = 16000;
const FRAME = 160; // 10 ms decision frame
const ENV_BLOCK = 64; // 4 ms envelope block for the delay search
const RING = 32768; // reference history, 2 s
const MASK = RING - 1;
const ENV_RING = 1024;
const ENV_MASK = ENV_RING - 1;
const PREROLL_FRAMES = 4; // 40 ms released with the first talk frame

export class EchoCanceller {
  /**
   * @param {object} [o]
   * @param {number} [o.taps] adaptive filter length in samples (default 512)
   * @param {number} [o.lead] filter starts this many samples before the found delay (default 96)
   * @param {number} [o.maxDelay] longest delay searched, samples (default 12800 = 800 ms)
   * @param {number} [o.mu] NLMS step size (default 0.5)
   * @param {number} [o.talkRatio] leftover power over expected echo that counts as talk (default 8, 9 dB)
   * @param {number} [o.micRatio] raw mic power over expected echo that can count as talk (default 2, 3 dB)
   * @param {number} [o.holdFrames] 10 ms frames the mic stays open after the last talk frame (default 100)
   * @param {number} [o.tailFrames] frames echo is still expected after the reference goes quiet (default 30)
   */
  constructor(o = {}) {
    this.taps = o.taps || 512;
    this.lead = o.lead ?? 96;
    this.maxDelay = o.maxDelay || 12800;
    this.mu = o.mu ?? 0.5;
    this.talkRatio = o.talkRatio ?? 8;
    this.micRatio = o.micRatio ?? 2;
    this.holdFrames = o.holdFrames ?? 100;
    this.tailFrames = o.tailFrames ?? 30;
    this.refFloor = 1e-6; // reference power below this (-60 dBFS) is silence
    this.absFloor = 3e-6; // leftover power below this never counts as talk

    // Reference written twice (i and i+RING) so the filter reads a
    // contiguous window without masking every tap.
    this.ref = new Float32Array(2 * RING);
    this.w = new Float32Array(this.taps);
    this.t = 0; // samples processed
    this.lastRef = -1e9; // sample index of the last audible reference sample
    this.delay = -1; // speaker-to-mic delay in samples; -1 = not found yet
    this.xe = 0; // energy of the reference window under the filter

    // Delay search state.
    this.envM = new Float32Array(ENV_RING);
    this.envR = new Float32Array(ENV_RING);
    this.envN = 0;
    this.accM = 0;
    this.accR = 0;
    this.accN = 0;
    this.candidate = -1;

    // Frame state.
    this.frame = new Float32Array(FRAME);
    this.fill = 0;
    this.pm = 0; // mic power accumulator
    this.pe = 0; // leftover power accumulator
    this.pr = 0; // aligned reference power accumulator
    this.lc = 0; // log of leftover / reference power during echo only
    this.lm = 0; // log of mic / reference power during echo only
    this.prDecay = 0;
    this.learned = 0; // echo-only frames seen
    this.nf = 1e-7; // leftover noise floor while the assistant is quiet
    this.open = 0; // frames left with the mic open
    this.talkHist = 0; // bit history of the last 4 talk decisions
    this.preroll = [];
    this.adapt = true;

    this.stats = { bargeIns: 0, erleDb: 0, delayMs: -1, talking: false, refActive: false, resets: 0 };
  }

  /**
   * Feed one block. mic and ref are 16 kHz and the same length.
   * @param {Float32Array} mic
   * @param {Float32Array} ref the samples the page played, before the speaker
   * @returns {Float32Array[]} output chunks to send (zeros while the assistant speaks and the user does not)
   */
  process(mic, ref) {
    const out = [];
    const n = Math.min(mic.length, ref.length);
    for (let i = 0; i < n; i++) {
      const x = ref[i];
      const p = this.t & MASK;
      this.ref[p] = x;
      this.ref[p + RING] = x;
      if (x > 1e-3 || x < -1e-3) this.lastRef = this.t;
      const m = mic[i];
      this.envelope(m, x);

      let e = m;
      let ra = 0;
      if (this.delay >= 0) {
        // Aligned reference sample, for the power used by the talk detector.
        ra = this.ref[((this.t - this.delay) & MASK) + RING];
        const off = Math.max(0, this.delay - this.lead);
        const base = ((this.t - off) & MASK) + RING; // newest sample under the filter
        const taps = this.taps;
        const xNew = this.ref[base];
        const xOld = this.ref[base - taps];
        this.xe += xNew * xNew - xOld * xOld;
        if (this.xe < 0) this.xe = 0;
        const w = this.w;
        const r = this.ref;
        let y = 0;
        for (let k = 0; k < taps; k++) y += w[k] * r[base - k];
        e = m - y;
        if (this.adapt && this.xe > taps * this.refFloor) {
          const g = (this.mu * e) / (this.xe + taps * 1e-6);
          for (let k = 0; k < taps; k++) w[k] += g * r[base - k];
        }
      }
      this.pm += m * m;
      this.pe += e * e;
      this.pr += ra * ra;
      this.frame[this.fill++] = e;
      if (this.fill === FRAME) this.endFrame(out);
      this.t++;
    }
    return out;
  }

  envelope(m, x) {
    this.accM += Math.abs(m);
    this.accR += Math.abs(x);
    if (++this.accN < ENV_BLOCK) return;
    const j = this.envN & ENV_MASK;
    this.envM[j] = this.accM / ENV_BLOCK;
    this.envR[j] = this.accR / ENV_BLOCK;
    this.envN++;
    this.accM = this.accR = 0;
    this.accN = 0;
    if (this.envN % 125 === 0) this.searchDelay(); // every 0.5 s
  }

  // searchDelay correlates the mic envelope with the reference envelope over
  // the last 2 s and moves the filter when one lag clearly wins, twice in a row.
  searchDelay() {
    const L = Math.floor(this.maxDelay / ENV_BLOCK);
    const W = Math.min(500, this.envN - L - 1);
    if (W < 150) return;
    const end = this.envN - 1;
    let active = 0;
    for (let i = 0; i < W + L; i++) if (this.envR[(end - i) & ENV_MASK] > 1e-3) active++;
    if (active < 40) return; // the assistant was mostly quiet: nothing to learn from
    let sm = 0;
    let smm = 0;
    for (let i = 0; i < W; i++) {
      const v = this.envM[(end - i) & ENV_MASK];
      sm += v;
      smm += v * v;
    }
    const varM = smm - (sm * sm) / W;
    if (varM <= 0) return;
    let best = -1;
    let bestC = 0;
    for (let lag = 0; lag <= L; lag++) {
      let sr = 0;
      let srr = 0;
      let smr = 0;
      for (let i = 0; i < W; i++) {
        const mv = this.envM[(end - i) & ENV_MASK];
        const rv = this.envR[(end - i - lag) & ENV_MASK];
        sr += rv;
        srr += rv * rv;
        smr += mv * rv;
      }
      const varR = srr - (sr * sr) / W;
      if (varR <= 0) continue;
      const c = (smr - (sm * sr) / W) / Math.sqrt(varM * varR);
      if (c > bestC) {
        bestC = c;
        best = lag;
      }
    }
    if (best < 0 || bestC < 0.4) return;
    const d = best * ENV_BLOCK;
    if (this.delay >= 0 && Math.abs(d - this.delay) <= 2 * ENV_BLOCK) {
      this.candidate = -1;
      return; // same place: keep the trained filter
    }
    if (this.delay >= 0 && (this.candidate < 0 || Math.abs(d - this.candidate) > 2 * ENV_BLOCK)) {
      this.candidate = d; // a move needs a second agreeing search
      return;
    }
    this.setDelay(d);
  }

  setDelay(d) {
    this.delay = d;
    this.candidate = -1;
    this.w.fill(0);
    this.stats.resets++;
    // Recompute the window energy for the new position.
    const off = Math.max(0, d - this.lead);
    const base = ((this.t - off) & MASK) + RING;
    let xe = 0;
    for (let k = 0; k < this.taps; k++) xe += this.ref[base - k] * this.ref[base - k];
    this.xe = xe;
    this.stats.delayMs = Math.round((d / AEC_RATE) * 1000);
  }

  endFrame(out) {
    const pm = this.pm / FRAME;
    const pe = this.pe / FRAME;
    const pr = this.pr / FRAME;
    this.pm = this.pe = this.pr = 0;
    this.fill = 0;
    // Expected echo follows the reference with some reverberation: the
    // loudest of the last 4 frames. Any reference in the tail window means
    // echo is still possible.
    // Room echo dies away over 100-300 ms: the expected echo is the
    // reference power held with a decay of 1.5 dB per frame.
    this.prDecay = Math.max(pr, this.prDecay * 0.7);
    const prWin = this.prDecay;
    // Echo is possible until the delay plus the room's tail after the last
    // audible reference sample. Before the delay is found, assume the
    // longest delay searched.
    const reach = (this.delay >= 0 ? this.delay : this.maxDelay) + this.tailFrames * FRAME;
    const refActive = this.t - this.lastRef < reach;

    let talk = false;
    if (refActive && this.delay >= 0) {
      // Talk needs both: the leftover well above the echo the filter
      // usually leaves, and the raw mic above the echo alone. The second
      // test stops a distorting speaker (which the linear filter cannot
      // remove) from looking like the user.
      const expected = Math.exp(this.lc) * prWin;
      const expectedMic = Math.exp(this.lm) * prWin;
      talk = this.learned >= 30 && pe > this.talkRatio * expected + 4 * this.nf && pe > this.absFloor
        && pm > this.micRatio * expectedMic;
      // Learn only on frames where the voice is at its held level, not in
      // its fading tail: the tail would teach a too-low expected echo.
      if (!talk && this.open === 0 && pr > this.refFloor && pr >= 0.5 * prWin) {
        // Echo only: learn how much echo is left and how much the filter
        // removes. Rising fast and falling slowly keeps the estimate near the
        // loud end of what echo alone does, so echo peaks do not look like talk.
        const ratio = Math.log((pe + 1e-12) / (prWin + 1e-12));
        const a = this.learned < 30 ? 0.3 : ratio > this.lc ? 0.2 : 0.01;
        this.lc += a * (ratio - this.lc);
        const mratio = Math.log((pm + 1e-12) / (prWin + 1e-12));
        const b = this.learned < 30 ? 0.3 : mratio > this.lm ? 0.2 : 0.01;
        this.lm += b * (mratio - this.lm);
        this.learned++;
        const erle = 10 * Math.log10((pm + 1e-12) / (pe + 1e-12));
        this.stats.erleDb += 0.05 * (erle - this.stats.erleDb);
        // A filter that adds echo instead of removing it has diverged.
        if (pe > 4 * pm && pm > this.absFloor) {
          this.w.fill(0);
          this.stats.resets++;
        }
      }
    } else if (!refActive) {
      // Quiet assistant: track the room's noise floor (fast down, slow up).
      this.nf = pe < this.nf ? pe : this.nf * 1.002 + pe * 0.0005;
    }
    this.talkHist = ((this.talkHist << 1) | (talk ? 1 : 0)) & 0xf;
    const recentTalk = popcount4(this.talkHist);
    const wasOpen = this.open > 0;
    if (refActive && recentTalk >= 3) {
      if (!wasOpen) this.stats.bargeIns++;
      this.open = this.holdFrames;
    } else if (this.open > 0) {
      this.open--;
    }
    // Freeze adaptation while the user may be talking: their voice would
    // otherwise pull the filter away from the echo.
    this.adapt = !talk && this.open === 0;

    const frame = this.frame.slice();
    this.stats.refActive = refActive;
    this.stats.talking = refActive && this.open > 0;
    if (!refActive || this.open > 0) {
      if (refActive && !wasOpen) for (const f of this.preroll) out.push(f);
      this.preroll = [];
      out.push(frame);
    } else {
      this.preroll.push(frame);
      if (this.preroll.length > PREROLL_FRAMES) this.preroll.shift();
      out.push(new Float32Array(FRAME));
    }
  }
}

function popcount4(v) {
  return (v & 1) + ((v >> 1) & 1) + ((v >> 2) & 1) + ((v >> 3) & 1);
}
