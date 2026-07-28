import { create } from "zustand";
import type { Theme } from "@orangchat/marketplace";

/**
 * Installed colour themes. A theme overrides `--oc-*` custom properties; the
 * values were colour-validated server-side, so applying one can only recolour.
 *
 * The installed theme's vars are cached locally so the look survives a reload
 * without a round-trip - the marketplace row it came from may even be gone by
 * then, and the install should still hold.
 */
interface InstalledTheme {
  id: string;
  name: string;
  vars: Record<string, string>;
}

const STORAGE_KEY = "oc-theme-install";

function read(): InstalledTheme | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as InstalledTheme;
    return parsed && typeof parsed.vars === "object" ? parsed : null;
  } catch {
    return null;
  }
}

function write(theme: InstalledTheme | null): void {
  try {
    if (theme) localStorage.setItem(STORAGE_KEY, JSON.stringify(theme));
    else localStorage.removeItem(STORAGE_KEY);
  } catch {
    // Storage unavailable (private mode) - the install just won't persist.
  }
}

const STYLE_ID = "oc-installed-theme";

/** Push the theme's variables into a single <style> on :root, or clear it. */
function applyVars(vars: Record<string, string> | null): void {
  let el = document.getElementById(STYLE_ID) as HTMLStyleElement | null;
  if (!vars) {
    el?.remove();
    return;
  }
  if (!el) {
    el = document.createElement("style");
    el.id = STYLE_ID;
    document.head.appendChild(el);
  }
  // Re-validated client-side too: only allow-listed-looking keys and colourish
  // values reach the DOM, so a tampered localStorage can't smuggle in CSS.
  const body = Object.entries(vars)
    .filter(([k, v]) => /^--oc-[a-z0-9-]+$/.test(k) && /^[#a-zA-Z0-9(),.%/ -]+$/.test(v))
    .map(([k, v]) => `${k}: ${v};`)
    .join(" ");
  el.textContent = `:root { ${body} }`;
}

interface ThemeStore {
  installed: InstalledTheme | null;
  install: (theme: Theme | InstalledTheme) => void;
  uninstall: () => void;
}

export const useInstalledTheme = create<ThemeStore>((set) => ({
  installed: read(),

  install: (theme) => {
    const next: InstalledTheme = { id: theme.id, name: theme.name, vars: theme.vars };
    applyVars(next.vars);
    write(next);
    set({ installed: next });
  },

  uninstall: () => {
    applyVars(null);
    write(null);
    set({ installed: null });
  },
}));

/** Apply the persisted theme at boot, before React mounts. */
export function initInstalledTheme(): void {
  applyVars(read()?.vars ?? null);
}
