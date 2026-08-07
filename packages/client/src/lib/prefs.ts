import { create } from "zustand";
import { applyBrandReplacement } from "./brandReplacement";

/**
 * Device-local preferences. These never reach the server: they describe this
 * browser (which camera, how big the text), not the account.
 */
export interface LocalPrefs {
  fontScale: number;
  reducedMotion: boolean;
  highContrast: boolean;
  underlineLinks: boolean;
  messageDensity: "cozy" | "compact";
  sendOnEnter: boolean;
  iHateAdas: boolean;
  micDeviceId: string;
  cameraDeviceId: string;
  speakerDeviceId: string;
  joinMuted: boolean;
  joinWithVideo: boolean;
}

const STORAGE_KEY = "oc-prefs";

export const DEFAULT_PREFS: LocalPrefs = {
  fontScale: 1,
  reducedMotion: false,
  highContrast: false,
  underlineLinks: false,
  messageDensity: "cozy",
  sendOnEnter: true,
  iHateAdas: false,
  micDeviceId: "default",
  cameraDeviceId: "default",
  speakerDeviceId: "default",
  joinMuted: false,
  joinWithVideo: false,
};

export const FONT_SCALE_MIN = 0.85;
export const FONT_SCALE_MAX = 1.5;

function read(): LocalPrefs {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return { ...DEFAULT_PREFS };
    const parsed = JSON.parse(raw) as Partial<LocalPrefs>;
    return { ...DEFAULT_PREFS, ...parsed };
  } catch {
    return { ...DEFAULT_PREFS };
  }
}

function write(prefs: LocalPrefs): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(prefs));
  } catch {
    // Storage unavailable (private mode) - prefs just won't persist.
  }
}

export const usePrefs = create<LocalPrefs>(() => read());

/**
 * Push prefs into the DOM. Font scale rides on the root font-size so every
 * rem-based Tailwind utility follows it; the rest are data attributes that
 * index.css keys off.
 */
export function applyPrefs(prefs: LocalPrefs): void {
  const root = document.documentElement;
  const scale = Math.min(Math.max(prefs.fontScale, FONT_SCALE_MIN), FONT_SCALE_MAX);
  root.style.fontSize = `${16 * scale}px`;
  root.dataset.reduceMotion = prefs.reducedMotion ? "true" : "false";
  root.dataset.contrast = prefs.highContrast ? "high" : "normal";
  root.dataset.underlineLinks = prefs.underlineLinks ? "true" : "false";
  root.dataset.density = prefs.messageDensity;
  applyBrandReplacement(prefs.iHateAdas);
}

export function setPref<K extends keyof LocalPrefs>(key: K, value: LocalPrefs[K]): void {
  // Once the user picks a value here (or in a past session), the OS setting
  // stops driving it - the choice is theirs.
  explicitlySet.add(key);
  const next = { ...usePrefs.getState(), [key]: value };
  usePrefs.setState(next, true);
  write(next);
  applyPrefs(next);
}

export const getPrefs = () => usePrefs.getState();

/** Preferences the user has set themselves, in any session. */
const explicitlySet = new Set<keyof LocalPrefs>();

/** Apply an OS preference change to a key the user never touched. Kept out of
 * localStorage on purpose: persisted, it would read as a choice next session
 * and stop the OS from ever being followed again. */
function followOs(key: "reducedMotion" | "highContrast", matches: boolean): void {
  if (explicitlySet.has(key)) return;
  const next = { ...usePrefs.getState(), [key]: matches };
  usePrefs.setState(next, true);
  applyPrefs(next);
}

/** Honour the OS-level motion and contrast settings until the user overrides
 * them here - and keep following them if the OS setting changes mid-session. */
export function initPrefs(): void {
  const stored = read();
  let parsed: Partial<LocalPrefs> = {};
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(STORAGE_KEY);
  } catch {
    /* ignore */
  }
  try {
    parsed = JSON.parse(raw ?? "{}") as Partial<LocalPrefs>;
  } catch {
    /* ignore */
  }
  // A choice made in an earlier session still counts as made.
  for (const key of Object.keys(parsed)) explicitlySet.add(key as keyof LocalPrefs);
  usePrefs.setState(stored, true);
  if (!explicitlySet.has("reducedMotion")) {
    followOs("reducedMotion", window.matchMedia?.("(prefers-reduced-motion: reduce)").matches ?? false);
  }
  if (!explicitlySet.has("highContrast")) {
    followOs("highContrast", window.matchMedia?.("(prefers-contrast: more)").matches ?? false);
  }
  window.matchMedia?.("(prefers-reduced-motion: reduce)").addEventListener("change", (e) =>
    followOs("reducedMotion", e.matches),
  );
  window.matchMedia?.("(prefers-contrast: more)").addEventListener("change", (e) =>
    followOs("highContrast", e.matches),
  );
  applyPrefs(usePrefs.getState());
}
