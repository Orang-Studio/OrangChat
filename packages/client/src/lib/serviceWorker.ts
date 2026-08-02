/**
 * Registers the service worker at start-up.
 *
 * It used to be registered only from `enablePushNotifications`, which made the
 * media cache a side effect of allowing notifications: anyone who declined them
 * re-downloaded every avatar and emoji from the network for the life of the
 * session. Notifications still need the worker; the worker no longer needs
 * them.
 *
 * Registration is idempotent - the browser returns the existing registration
 * for a url and scope it already has - so the push path calling this too is
 * fine, and nothing here waits on the network before the app renders.
 */
export function registerServiceWorker(): void {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  // A worker installed from a stale page is worse than none: registration is
  // deferred to load so it never competes with the app's first paint.
  const start = () => void navigator.serviceWorker.register("/sw.js").catch(() => {});
  if (document.readyState === "complete") start();
  else window.addEventListener("load", start, { once: true });
}

/**
 * Asks the worker to drop cached media. Best effort by design: if no worker
 * controls this page there is nothing cached to drop.
 */
export function clearCachedMedia(): void {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  navigator.serviceWorker.controller?.postMessage({ type: "media-cache:clear" });
}
