import { test } from 'node:test';
import assert from 'node:assert/strict';
import { fmtUSD, fmtVoiceTime, spendLine, spendTitle } from './spend.js';

test('dollar amounts: three decimals under $1, two above', () => {
  assert.equal(fmtUSD(0.0421), '$0.042');
  assert.equal(fmtUSD(3.456), '$3.46');
  assert.equal(fmtUSD(undefined), '$0.000');
});

test('voice time in seconds under a minute, else minutes', () => {
  assert.equal(fmtVoiceTime(42), '42 s');
  assert.equal(fmtVoiceTime(911), '15 min');
});

test('the summary line names today and this conversation', () => {
  const r = { ready: true, today: { total_usd: 1.234 }, conversation: { total_usd: 0.05 } };
  assert.equal(spendLine(r), 'Cost today: $1.23 · this conversation: $0.050');
  assert.match(spendLine({ ...r, ready: false }), /still counting/);
  assert.equal(spendLine(null), 'Cost: not available');
});

test('the breakdown lists voice and orchestrator', () => {
  const p = { total_usd: 0.9, voice_seconds: 600, voice_usd: 0.5, brain_steps: 12, brain_usd: 0.4 };
  assert.equal(spendTitle(p), '$0.900: voice 10 min $0.500, orchestrator 12 steps $0.400');
  assert.match(spendTitle({ ...p, brain_unpriced: true }), /no known price/);
});
