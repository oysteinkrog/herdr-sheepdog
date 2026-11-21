// Capture worklet: microphone (or the file test source) at the context's
// native rate → anti-alias low-pass → 16 kHz → Int16 → 20 ms frames →
// silence gate → posted to the page, which sends each one as a WebSocket
// binary frame.
//
// Runs on the audio rendering thread. Mute is honoured here as well as in
// the page, so a muted client posts nothing at all (contract: muted means
// silence, not zeros). The silence gate withholds near-zero frames after
// a quiet stretch for the same reason (docs/dev/history/e2e-results.md): the page is
// told about each transition so the daemon can log it.
import { CapturePipeline, SilenceGate, levelOf, FRAME_SAMPLES, FIRFilter, Resampler, lowpassCoefficients, floatToInt16 } from './audio-util.js';
import { EchoCanceller, AEC_RATE } from './echo.js';

const ECHO_STATS_EVERY_BLOCKS = 375; // ~1 s at 48 kHz

// Downsampler: the context rate to 16 kHz, the same filter and resampler as
// CapturePipeline, so the mic and the speaker signal stay sample-aligned.
class Downsampler {
  constructor(inRate) {
    this.filter = inRate > AEC_RATE ? new FIRFilter(lowpassCoefficients(31, AEC_RATE * 0.45, inRate)) : null;
    this.resampler = inRate === AEC_RATE ? null : new Resampler(inRate, AEC_RATE);
  }

  process(block) {
    let x = block;
    if (this.filter) x = this.filter.process(x);
    if (this.resampler) x = this.resampler.process(x);
    return x;
  }
}

const LEVEL_EVERY_BLOCKS = 16; // ~43 ms at 48 kHz

class CaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const o = (options && options.processorOptions) || {};
    this.pipeline = new CapturePipeline(sampleRate, o.outRate || 16000, o.frameSamples || FRAME_SAMPLES);
    this.gate = o.gate === false ? null : new SilenceGate(o.gate || {});
    this.muted = !!o.muted;
    this.setEcho(!!o.echo);
    this.zeros = new Float32Array(128);
    this.silence = new Float32Array(128);
    this.blocks = 0;
    this.peak = 0;
    this.rms = 0;
    this.port.onmessage = (e) => {
      const m = e.data;
      if (m.type === 'mute') this.muted = !!m.on;
      // Loudspeaker mode: while the assistant speaks the mic hears it
      // (no echo cancellation), so the page ducks the mic: it sends
      // silence instead, and the gate withholds it as usual.
      if (m.type === 'duck') this.ducked = !!m.on;
      // Talk mode: remove the voice from the mic with the page's own echo
      // canceller (echo.js), fed by input 1, what the speaker plays.
      if (m.type === 'echo') this.setEcho(!!m.on);
    };
  }

  setEcho(on) {
    if (on === !!this.echo) return;
    this.echo = on ? new EchoCanceller() : null;
    this.micDown = on ? new Downsampler(sampleRate) : null;
    this.refDown = on ? new Downsampler(sampleRate) : null;
  }

  // frames runs one block through the pipeline: straight, or through the
  // echo canceller first.
  frames(block, refBlock) {
    if (!this.echo) return this.pipeline.process(block);
    const mic = this.micDown.process(block);
    const ref = this.refDown.process(refBlock);
    const out = [];
    for (const chunk of this.echo.process(mic, ref)) out.push(...this.pipeline.chunker.push(floatToInt16(chunk)));
    if (this.blocks % ECHO_STATS_EVERY_BLOCKS === 0) {
      const st = this.echo.stats;
      this.port.postMessage({ type: 'echo', delayMs: st.delayMs, erleDb: Math.round(st.erleDb * 10) / 10, bargeIns: st.bargeIns, talking: st.talking });
    }
    return out;
  }

  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    const refCh = inputs[1] && inputs[1][0];
    // A source that has ended (file playback finished) leaves the input
    // empty; treat that as silence so frames keep flowing like a quiet mic
    // (the gate then closes after its hold time).
    const block = ch && ch.length && (!this.ducked || this.echo) ? ch : this.silence;
    const refBlock = refCh && refCh.length === block.length ? refCh : this.zeros.length === block.length ? this.zeros : new Float32Array(block.length);
    if (this.muted) {
      // Drop everything while muted. The pipeline keeps its filter state,
      // which is fine: a mute is a discontinuity anyway.
      if (this.blocks++ % LEVEL_EVERY_BLOCKS === 0) this.port.postMessage({ type: 'level', peak: 0, rms: 0, muted: true, gated: false, gatedFrames: this.gatedFrames() });
      return true;
    }
    const lv = levelOf(block);
    if (lv.peak > this.peak) this.peak = lv.peak;
    this.rms += lv.rms;
    if (++this.blocks % LEVEL_EVERY_BLOCKS === 0) {
      this.port.postMessage({
        type: 'level', peak: this.peak, rms: this.rms / LEVEL_EVERY_BLOCKS, muted: false,
        gated: !!(this.gate && this.gate.gated), gatedFrames: this.gatedFrames(),
      });
      this.peak = 0;
      this.rms = 0;
    }
    for (const frame of this.frames(block, refBlock)) {
      if (!this.gate) {
        this.post(frame);
        continue;
      }
      const r = this.gate.push(frame);
      if (r.transition) {
        this.port.postMessage({
          type: 'silence',
          on: r.transition === 'on',
          peak: this.gate.peak,
          speechEndMs: r.speechEndMs,
          audioTime: currentTime,
        });
      }
      for (const f of r.send) this.post(f);
    }
    return true;
  }

  gatedFrames() {
    return this.gate ? this.gate.gatedFrames : 0;
  }

  post(frame) {
    // Transfer the buffer: no copy, and the page owns it from here.
    this.port.postMessage({ type: 'frame', data: frame.buffer }, [frame.buffer]);
  }
}

registerProcessor('sheepdog-capture', CaptureProcessor);
