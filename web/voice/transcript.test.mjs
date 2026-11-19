import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addFragment, closeLine, fragmentText } from './transcript.js';

test('a closing fragment without text appends nothing (no "undefined")', () => {
  const lines = [];
  // What the daemon sends: the empty closing fragment loses its text
  // field to omitempty on the wire.
  const msgs = [
    { type: 'transcript', dir: 'out', text: 'Auth finished', final: false },
    { type: 'transcript', dir: 'out', text: ' its tests.', final: false },
    JSON.parse('{"type":"transcript","dir":"out","final":true}'),
    { type: 'transcript', dir: 'in', text: 'thanks', final: false },
    JSON.parse('{"type":"transcript","dir":"in","final":true}'),
  ];
  for (const m of msgs) addFragment(lines, m.dir, fragmentText(m), !!m.final, 8);
  assert.deepEqual(lines, [
    { dir: 'out', text: 'Auth finished its tests.', final: true },
    { dir: 'in', text: 'thanks', final: true },
  ]);
  assert.ok(lines.every((l) => !l.text.includes('undefined')));
});

test('addFragment reads a missing text as empty too', () => {
  const lines = [{ dir: 'out', text: 'hello', final: false }];
  addFragment(lines, 'out', undefined, true, 8);
  assert.deepEqual(lines, [{ dir: 'out', text: 'hello', final: true }]);
});

test('an empty final with no open line adds nothing', () => {
  const lines = [];
  addFragment(lines, 'in', '', true, 8);
  assert.deepEqual(lines, []);
});

test('a typed line echoed back by --voice=off is not shown twice', () => {
  const lines = [{ dir: 'in', text: 'status?', final: true, local: true }];
  addFragment(lines, 'in', 'status?', true, 8);
  assert.equal(lines.length, 1);
  assert.equal(lines[0].local, false);
});

test('the other direction closes the open line, and only max lines stay', () => {
  const lines = [];
  addFragment(lines, 'out', 'a', false, 2);
  addFragment(lines, 'in', 'b', false, 2);
  assert.equal(lines[0].final, true);
  addFragment(lines, 'out', 'c', false, 2);
  assert.deepEqual(lines.map((l) => l.text), ['b', 'c']);
  closeLine(lines, 'out');
  assert.equal(lines[1].final, true);
});
