// Driving mode's screen wake lock (wakelock.js): held while wanted and
// visible, asked for again after the browser drops it on a hide, released
// when turned off, and harmless where the API is missing.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createWakeLock } from './wakelock.js';

function fakeDoc() {
  const listeners = {};
  return {
    visibilityState: 'visible',
    addEventListener: (t, f) => { (listeners[t] ||= []).push(f); },
    removeEventListener: (t, f) => { listeners[t] = (listeners[t] || []).filter((x) => x !== f); },
    fire(t) { for (const f of listeners[t] || []) f(); },
  };
}

function fakeNav({ fail = false } = {}) {
  const nav = { requests: 0, live: [] };
  nav.wakeLock = {
    async request(type) {
      assert.equal(type, 'screen');
      nav.requests++;
      if (fail) throw new Error('NotAllowedError');
      const ls = [];
      const s = {
        released: false,
        addEventListener: (t, f) => { if (t === 'release') ls.push(f); },
        async release() { s.released = true; for (const f of ls) f(); },
        drop() { s.released = true; for (const f of ls) f(); }, // the browser releasing it on hide
      };
      nav.live.push(s);
      return s;
    },
  };
  return nav;
}

const tick = () => new Promise((r) => setTimeout(r, 0));

test('holds the lock and takes it again when the page comes back', async () => {
  const nav = fakeNav();
  const doc = fakeDoc();
  const wl = createWakeLock({ nav, doc });
  assert.equal(wl.status(), 'off');
  await wl.set(true);
  assert.equal(wl.status(), 'on');
  assert.equal(nav.requests, 1);

  doc.visibilityState = 'hidden';
  nav.live[0].drop();
  doc.fire('visibilitychange');
  await tick();
  assert.equal(wl.status(), 'waiting');
  assert.equal(nav.requests, 1, 'no request while hidden');

  doc.visibilityState = 'visible';
  doc.fire('visibilitychange');
  await tick();
  assert.equal(wl.status(), 'on');
  assert.equal(nav.requests, 2);
});

test('turning it off releases the lock and stops re-taking it', async () => {
  const nav = fakeNav();
  const doc = fakeDoc();
  const wl = createWakeLock({ nav, doc });
  await wl.set(true);
  await wl.set(false);
  assert.equal(wl.status(), 'off');
  assert.equal(nav.live[0].released, true);
  doc.fire('visibilitychange');
  await tick();
  assert.equal(nav.requests, 1);
});

test('a refused request reports failed and is tried again on the next show', async () => {
  const nav = fakeNav({ fail: true });
  const doc = fakeDoc();
  const wl = createWakeLock({ nav, doc });
  await wl.set(true);
  assert.equal(wl.status(), 'failed');
  assert.match(wl.error(), /NotAllowed/);
  doc.fire('visibilitychange');
  await tick();
  assert.equal(nav.requests, 2);
});

test('no Wake Lock API: unsupported, and set() is harmless', async () => {
  const wl = createWakeLock({ nav: {}, doc: fakeDoc() });
  assert.equal(wl.supported, false);
  await wl.set(true);
  assert.equal(wl.status(), 'unsupported');
});
