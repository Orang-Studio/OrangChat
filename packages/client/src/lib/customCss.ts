import { useEffect } from "react";
import { useAuthStore } from "../stores/auth";

const STYLE_ID = "oc-custom-css";

/** Inject (or clear) the user's uploaded CSS override into a single <style>. */
export function applyCustomCss(css: string | null | undefined): void {
  let el = document.getElementById(STYLE_ID) as HTMLStyleElement | null;
  if (!css) {
    el?.remove();
    return;
  }
  if (!el) {
    el = document.createElement("style");
    el.id = STYLE_ID;
    // Append last so overrides win the cascade against the base stylesheet.
    document.head.appendChild(el);
  }
  el.textContent = css;
}

/** Keep the injected override in sync with the signed-in user's customCss. */
export function useCustomCss(): void {
  const css = useAuthStore((s) => s.user?.customCss);
  useEffect(() => {
    applyCustomCss(css ?? null);
    return () => applyCustomCss(null);
  }, [css]);
}
