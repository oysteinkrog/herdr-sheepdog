// Keeps the screen on while /voice is open (driving mode), with the Screen
// Wake Lock API. The browser drops the lock whenever the page is hidden
// (screen off, app switch), so it is asked for again each time the page
// becomes visible. No DOM beyond what is passed in, so node --test covers it.

/**
 * @param {object} o
 * @param {object} o.nav       navigator (wakeLock.request)
 * @param {object} o.doc       document (visibilityState, add/removeEventListener)
 * @param {() => void} [o.onChange]  called when status() changes
 */
export function createWakeLock({ nav, doc, onChange = () => {} }) {
  const supported = Boolean(nav && nav.wakeLock && typeof nav.wakeLock.request === 'function');
  let wanted = false;
  let sentinel = null;
  let pending = null;
  let lastError = '';

  const status = () => {
    if (!supported) return 'unsupported';
    if (!wanted) return 'off';
    if (sentinel) return 'on';
    if (lastError) return 'failed';
    return 'waiting'; // wanted, page hidden or request in flight
  };

  async function acquire() {
    if (!supported || !wanted || sentinel || pending) return;
    if (doc.visibilityState !== 'visible') return;
    pending = (async () => {
      try {
        const s = await nav.wakeLock.request('screen');
        if (!wanted) {
          await s.release().catch(() => {});
          return;
        }
        sentinel = s;
        lastError = '';
        s.addEventListener('release', () => {
          if (sentinel === s) sentinel = null;
          onChange();
        });
      } catch (err) {
        lastError = (err && err.message) || String(err);
      } finally {
        pending = null;
        onChange();
      }
    })();
    await pending;
  }

  const onVisible = () => {
    if (doc.visibilityState === 'visible') acquire();
  };
  doc.addEventListener('visibilitychange', onVisible);

  return {
    supported,
    status,
    error: () => lastError,
    async set(on) {
      wanted = Boolean(on);
      if (wanted) {
        await acquire();
      } else if (sentinel) {
        const s = sentinel;
        sentinel = null;
        await s.release().catch(() => {});
      }
      onChange();
    },
    dispose() {
      doc.removeEventListener('visibilitychange', onVisible);
    },
  };
}
