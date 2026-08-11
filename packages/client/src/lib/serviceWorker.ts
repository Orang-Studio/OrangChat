
export function registerServiceWorker(): void {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  const start = () => void navigator.serviceWorker.register("/sw.js").catch(() => {});
  if (document.readyState === "complete") start();
  else window.addEventListener("load", start, { once: true });
}


export function clearCachedMedia(): void {
  if (typeof navigator === "undefined" || !("serviceWorker" in navigator)) return;
  navigator.serviceWorker.controller?.postMessage({ type: "media-cache:clear" });
}
