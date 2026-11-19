// The /voice transcript lines (docs/dev/ws-contract.md "transcript"):
// incremental fragments joined into lines, one per utterance. Pure
// helpers over the lines array, no DOM, so node --test covers them;
// app.js renders the lines.

/** The text of a transcript message. The daemon's closing fragment is
 * `{"text":"","final":true}` in the contract, but the Go encoder drops an
 * empty text (omitempty), so it arrives with no text at all: read that
 * as "". Appending the missing field gave every line a trailing
 * "undefined". */
export function fragmentText(m) {
  return m && typeof m.text === 'string' ? m.text : '';
}

/** Marks the last line final when it runs in direction dir. */
export function closeLine(lines, dir) {
  const last = lines[lines.length - 1];
  if (last && last.dir === dir) last.final = true;
}

/** Adds one fragment to lines (at most max lines are kept). */
export function addFragment(lines, dir, text, final, max) {
  text = typeof text === 'string' ? text : '';
  const last = lines[lines.length - 1];
  // A typed utterance is echoed locally; --voice=off sends it back as an
  // `in` transcript, so skip that duplicate.
  if (dir === 'in' && final && last && last.dir === 'in' && last.local && last.text === text) {
    last.local = false;
    return;
  }
  // Output fragments may never carry final (contract): the other
  // direction starting is the boundary, and so is a flush.
  closeLine(lines, dir === 'in' ? 'out' : 'in');
  // An empty final closes an open line; with none open there is nothing to show.
  if (text === '' && final && !(last && last.dir === dir && !last.final)) return;
  if (last && last.dir === dir && !last.final) {
    last.text += text;
    last.final = final;
  } else {
    lines.push({ dir, text, final });
  }
  while (lines.length > max) lines.shift();
}
