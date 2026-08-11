import { create } from "zustand";
import type { Theme } from "@orangchat/marketplace";


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
  }
}

const STYLE_ID = "oc-installed-theme";
let active = true;


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
    if (active) applyVars(next.vars);
    write(next);
    set({ installed: next });
  },

  uninstall: () => {
    applyVars(null);
    write(null);
    set({ installed: null });
  },
}));

/** Apply or temporarily suppress the persisted theme without uninstalling it. */
export function setInstalledThemeActive(next: boolean): void {
  active = next;
  applyVars(next ? (read()?.vars ?? null) : null);
}

/** Apply the persisted theme at boot, before React mounts. */
export function initInstalledTheme(initiallyActive = true): void {
  setInstalledThemeActive(initiallyActive);
}
