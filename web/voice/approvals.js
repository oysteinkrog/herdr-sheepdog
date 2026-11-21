// Open approval questions on /voice: mirrors mods/sheepdog/model/questions.js
// and ApprovalFlow in SheepdogKit. Every tap goes through POST /api/pending/answer
// (the supervisor's AnswerTap rail) with an Idempotency-Key. Pure logic + safe
// escaped HTML so node --test covers it without DOM globals.

import { b64url } from './pairing.js';

export const LINGER_MS = 4000;
export const GONE = 'That question was already answered or has gone.';

const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

const answerable = (q) => q && q.kind === 'answer_prompt' && Array.isArray(q.options) && q.options.length > 0;

/** Open answerable questions from /api/state, oldest first. */
export function openQuestions(app) {
  const list = app && Array.isArray(app.questions) && app.questions.length
    ? app.questions
    : app && app.pending_action ? [app.pending_action] : [];
  return list.filter(answerable);
}

export function summaryOf(q) {
  for (const text of [q && q.summary, q && q.spoken_summary, q && q.question]) {
    if (text && String(text).trim()) return String(text).trim();
  }
  return 'An agent is waiting for your answer.';
}

export const labelOf = (option) => (option && option.title && String(option.title).trim()) || (option && option.label) || '';

/** A multi-select question: taps tick boxes, Submit sends them. */
export const isMulti = (q) => Boolean(q && q.multi_select);

export const askerOf = (q) => (q && q.agent && String(q.agent).trim()) || 'An agent';

/** Generates an ASCII Idempotency-Key for POST /api/pending/answer. */
export function newIdempotencyKey() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return b64url(bytes);
}

/** Browser tab title with waiting-question count when > 0. */
export function pageTitle(waitingCount) {
  return waitingCount > 0 ? `(${waitingCount}) sheepdog voice` : 'sheepdog voice';
}

/**
 * Maps keys '1'–'9' to an option on the first open question when the user is
 * not typing in a field or viewing a modal card.
 */
export function approvalDigitOption(cards, key, { inField, metaKey, ctrlKey, altKey, dialogOpen } = {}) {
  if (inField || metaKey || ctrlKey || altKey || dialogOpen) return null;
  if (typeof key !== 'string' || key.length !== 1 || key < '1' || key > '9') return null;
  const idx = Number(key);
  const firstOpen = (cards || []).find((c) => c && c.phase === 'open');
  if (!firstOpen || !firstOpen.pending || !Array.isArray(firstOpen.pending.options)) return null;
  const option = firstOpen.pending.options.find((o) => o.index === idx);
  return option ? { question: firstOpen.pending, option } : null;
}

export function createApprovals({
  postAnswer,
  newKey = newIdempotencyKey,
  now = Date.now,
  refresh = async () => {},
  onChange = () => {},
}) {
  let phases = {};
  // Ticked options of each open multi-select question, by question id:
  // a Set of option indexes, starting from the boxes the screen showed.
  let ticks = {};

  const notify = () => onChange();
  const phaseOf = (id) => phases[id] || { phase: 'open', error: null };
  const busy = () => Object.values(phases).some((p) => p.phase === 'sending');

  function ticked(q) {
    if (!ticks[q.id]) ticks = { ...ticks, [q.id]: new Set((q.options || []).filter((o) => o.checked).map((o) => o.index)) };
    return ticks[q.id];
  }

  /** A multi-select tap: tick or untick, nothing is sent. */
  function toggle(q, option) {
    if (!q || !option || phaseOf(q.id).phase !== 'open') return;
    const set = new Set(ticked(q));
    if (set.has(option.index)) set.delete(option.index);
    else set.add(option.index);
    ticks = { ...ticks, [q.id]: set };
    notify();
  }

  /** Sends a multi-select question's ticked options and submits it. */
  function submit(q) {
    if (!q || !isMulti(q)) return Promise.resolve();
    const set = ticked(q);
    const chosen = (q.options || []).filter((o) => set.has(o.index));
    if (chosen.length === 0) return Promise.resolve();
    return send(q, { options: chosen.map((o) => o.index) }, null, `You chose ${chosen.map(labelOf).join(', ')}`);
  }

  /** Closes the question without an answer (esc on the agent's screen). */
  function dismiss(q) {
    return send(q, { dismiss: true }, null, 'You dismissed the question');
  }

  async function answer(q, option) {
    if (isMulti(q)) {
      toggle(q, option);
      return;
    }
    if (!option) return;
    return send(q, null, option, `You chose ${labelOf(option)}`);
  }

  async function send(q, extra, option, outcome) {
    if (!q || busy() || phaseOf(q.id).phase !== 'open') return;
    phases = { ...phases, [q.id]: { phase: 'sending', option, extra, pending: q, at: now() } };
    notify();
    try {
      const res = await postAnswer(q.id, option ? option.index : 0, newKey(), extra || undefined);
      if (res.status >= 200 && res.status < 300) {
        phases = {
          ...phases,
          [q.id]: {
            phase: 'answered',
            option,
            outcome,
            spoken: (res.body && res.body.spoken) || null,
            pending: q,
            at: now(),
          },
        };
      } else if (res.status === 409) {
        phases = { ...phases, [q.id]: { phase: 'gone', message: GONE, pending: q, at: now() } };
      } else {
        const msg = (res.body && res.body.error) || `Sheepdog refused (${res.status}).`;
        phases = { ...phases, [q.id]: { phase: 'open', error: msg } };
      }
    } catch (_) {
      phases = { ...phases, [q.id]: { phase: 'open', error: 'Sheepdog did not answer. Try again.' } };
    }
    notify();
    await refresh();
  }

  function cards(app) {
    const open = openQuestions(app);
    const ids = new Set(open.map((q) => q.id));
    const lingering = Object.entries(phases)
      .filter(([id, p]) => !ids.has(id) && (p.phase === 'answered' || p.phase === 'gone' || p.phase === 'sending'))
      .map(([, p]) => p.pending);
    return [...lingering, ...open].map((pending) => ({ pending, ...phaseOf(pending.id) }));
  }

  function prune(app) {
    const ids = new Set(openQuestions(app).map((q) => q.id));
    const t = now();
    const kept = Object.entries(phases).filter(([id, p]) => {
      if (p.phase === 'sending') return true;
      if (p.phase === 'open') return ids.has(id);
      return ids.has(id) || t - p.at < LINGER_MS;
    });
    for (const id of Object.keys(ticks)) if (!ids.has(id)) { const { [id]: _, ...rest } = ticks; ticks = rest; }
    if (kept.length === Object.keys(phases).length) return;
    phases = Object.fromEntries(kept);
    notify();
  }

  return { answer, toggle, submit, dismiss, ticked, cards, prune, busy };
}

/** Renders the stack of open / lingering question cards as escaped HTML. */
export function renderApprovalCardsHTML(cards, busy, ticked = () => new Set()) {
  if (!Array.isArray(cards) || cards.length === 0) return '';
  let primaryOpenAssigned = false;
  return cards.map((card) => {
    const q = card.pending;
    const settled = card.phase === 'answered' || card.phase === 'gone';
    const isPrimary = !settled && !primaryOpenAssigned;
    if (isPrimary) primaryOpenAssigned = true;
    const who = esc(askerOf(q));
    const heading = settled ? `${who} asked` : `${who} needs your answer`;
    const dotCls = settled ? 'open' : 'connecting';
    const cardCls = `card approval-card${card.phase === 'answered' ? ' answered' : isPrimary ? ' primary' : ''}`;

    let body = `<div class="approval-head"><span class="dot ${dotCls}" aria-hidden="true"></span><strong>${heading}</strong></div>`;
    body += `<p class="approval-summary">${esc(summaryOf(q))}</p>`;
    if (q.command && String(q.command).trim()) {
      body += `<pre class="code approval-cmd"><code>${esc(String(q.command).trim())}</code></pre>`;
    }
    if (card.phase === 'answered') {
      body += `<p class="approval-outcome">✓ ${esc(card.outcome)}</p>`;
      if (card.spoken) body += `<p class="muted small">${esc(card.spoken)}</p>`;
    } else if (card.phase === 'gone') {
      body += `<p class="muted small">${esc(card.message)}</p>`;
    } else {
      const enabled = card.phase === 'open' && !busy;
      const multi = isMulti(q);
      const set = multi ? ticked(q) : null;
      const buttons = (q.options || []).map((opt) => {
        const sending = card.phase === 'sending' && card.option && card.option.index === opt.index;
        const on = multi && set.has(opt.index);
        const box = multi ? `<span class="approval-box" aria-hidden="true">${on ? '☑' : '☐'}</span>` : '';
        const lbl = esc(labelOf(opt));
        const keyHint = isPrimary && opt.index >= 1 && opt.index <= 9 ? `<kbd aria-hidden="true">${opt.index}</kbd> ` : '';
        const rec = opt.recommended ? ' <span class="approval-rec">Recommended</span>' : '';
        const desc = opt.description && String(opt.description).trim()
          ? `<span class="approval-opt-desc muted small">${esc(String(opt.description).trim())}</span>`
          : '';
        const sendingTag = sending ? '<span class="muted small">Sending…</span>' : '';
        const aria = esc(`${askerOf(q)}: ${labelOf(opt)}${opt.recommended ? ' (Recommended)' : ''}`);
        const pressed = multi ? ` aria-pressed="${on}"` : '';
        return `<button type="button" class="approval-opt${sending ? ' sending' : ''}${on ? ' ticked' : ''}" data-pending-id="${esc(q.id)}" data-option-index="${Number(opt.index)}" aria-label="${aria}"${pressed}${enabled ? '' : ' disabled'}>` +
          `${box}<span class="approval-opt-main"><span>${keyHint}${lbl}${rec}</span>${desc}</span>${sendingTag}</button>`;
      }).join('');
      if (multi) body += '<p class="muted small approval-hint">Tick one or more, then Submit.</p>';
      body += `<div class="approval-options">${buttons}</div>`;
      const sendingAction = card.phase === 'sending' && card.extra;
      const submit = multi
        ? `<button type="button" class="approval-submit primary" data-pending-id="${esc(q.id)}" data-action="submit"${enabled && set.size > 0 ? '' : ' disabled'}>${sendingAction && card.extra.options ? 'Sending…' : `Submit${set.size ? ` (${set.size})` : ''}`}</button>`
        : '';
      const dismiss = `<button type="button" class="approval-dismiss" data-pending-id="${esc(q.id)}" data-action="dismiss" title="Close the question without an answer (presses Esc on the agent's screen)"${enabled ? '' : ' disabled'}>${sendingAction && card.extra.dismiss ? 'Sending…' : 'Dismiss (Esc)'}</button>`;
      body += `<div class="approval-actions">${submit}${dismiss}</div>`;
    }
    if (card.error) {
      body += `<p class="error approval-error">${esc(card.error)}</p>`;
    }
    return `<article class="${cardCls}" data-card-id="${esc(q.id)}">${body}</article>`;
  }).join('');
}
