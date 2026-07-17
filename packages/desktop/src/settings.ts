import { app } from "electron";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export interface Settings {
  closeToTray: boolean;
  autoLaunch: boolean;
  zoomLevel: number;
  toggleShortcut: string;
}

const DEFAULTS: Settings = {
  closeToTray: true,
  autoLaunch: false,
  zoomLevel: 0,
  toggleShortcut: "CommandOrControl+Shift+O",
};

let cache: Settings | null = null;

function file(): string {
  return join(app.getPath("userData"), "settings.json");
}

export function getSettings(): Settings {
  if (cache) return cache;
  try {
    const saved = JSON.parse(readFileSync(file(), "utf8")) as Partial<Settings>;
    cache = {
      closeToTray: saved.closeToTray ?? DEFAULTS.closeToTray,
      autoLaunch: saved.autoLaunch ?? DEFAULTS.autoLaunch,
      zoomLevel: clampZoom(saved.zoomLevel ?? DEFAULTS.zoomLevel),
      toggleShortcut: saved.toggleShortcut || DEFAULTS.toggleShortcut,
    };
  } catch {
    cache = { ...DEFAULTS };
  }
  return cache;
}

export function updateSettings(patch: Partial<Settings>): Settings {
  const next = { ...getSettings(), ...patch };
  if (patch.zoomLevel !== undefined) next.zoomLevel = clampZoom(patch.zoomLevel);
  cache = next;
  try {
    writeFileSync(file(), JSON.stringify(next, null, 2));
  } catch {
    // Settings are a convenience; a read-only profile must not crash the app.
  }
  return next;
}

export function clampZoom(level: number): number {
  if (!Number.isFinite(level)) return 0;
  return Math.min(5, Math.max(-3, level));
}
