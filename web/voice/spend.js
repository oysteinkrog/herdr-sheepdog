// The cost line on /voice: GET /api/spend (internal/web/spend.go) shown
// as plain text. Pure helpers, no DOM, so node --test covers them.

/** A dollar amount: three decimals under $1, two from $1 up. */
export function fmtUSD(n) {
  const v = Number(n) || 0;
  return `$${v.toFixed(v < 1 ? 3 : 2)}`;
}

/** Whole minutes, or seconds under a minute. */
export function fmtVoiceTime(seconds) {
  const s = Math.round(Number(seconds) || 0);
  return s < 60 ? `${s} s` : `${Math.round(s / 60)} min`;
}

/** The one-line summary under the controls. */
export function spendLine(r) {
  if (!r || !r.today) return 'Cost: not available';
  const est = r.ready ? '' : ' (still counting)';
  return `Cost today: ${fmtUSD(r.today.total_usd)} · this conversation: ${fmtUSD(r.conversation.total_usd)}${est}`;
}

/** One period's breakdown, for the Cost details list. */
export function spendTitle(p) {
  if (!p) return '–';
  const unpriced = p.brain_unpriced ? ' (a model with no known price is counted at $2 / $10 per million tokens)' : '';
  return `${fmtUSD(p.total_usd)}: voice ${fmtVoiceTime(p.voice_seconds)} ${fmtUSD(p.voice_usd)}, orchestrator ${p.brain_steps} steps ${fmtUSD(p.brain_usd)}${unpriced}`;
}
