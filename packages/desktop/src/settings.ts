import { app } from "electron";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export interface Settings {
  closeToTray: boolean;
  autoLaunch: boolean;
  zoomLevel: number;
  toggleShortcut: string;
  gameOverrides: GameOverride[];
}

export interface GameOverride {
  process: string;
  name: string;
}

const DEFAULTS: Settings = {
  closeToTray: true,
  autoLaunch: false,
  zoomLevel: 0,
  toggleShortcut: "CommandOrControl+Shift+O",
  gameOverrides: [],
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
      gameOverrides: sanitizeGameOverrides(saved.gameOverrides),
    };
  } catch {
    cache = { ...DEFAULTS };
  }
  return cache;
}

export function updateSettings(patch: Partial<Settings>): Settings {
  const next = { ...getSettings(), ...patch };
  if (patch.zoomLevel !== undefined) next.zoomLevel = clampZoom(patch.zoomLevel);
  if (patch.gameOverrides !== undefined) {
    next.gameOverrides = sanitizeGameOverrides(patch.gameOverrides);
  }
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

export function sanitizeGameOverrides(value: unknown): GameOverride[] {
  if (!Array.isArray(value)) return [];

  const overrides = new Map<string, GameOverride>();
  for (const item of value.slice(0, 100)) {
    if (!item || typeof item !== "object") continue;
    const candidate = item as { process?: unknown; name?: unknown };
    if (typeof candidate.process !== "string" || typeof candidate.name !== "string") continue;
    const processName = candidate.process
      .trim()
      .replace(/\\/g, "/")
      .split("/")
      .pop()
      ?.toLowerCase()
      .replace(/\.app$/i, "");
    const name = candidate.name.trim();
    if (!processName || !name || processName.length > 260 || name.length > 128) continue;
    overrides.set(processName, { process: processName, name });
  }
  return [...overrides.values()];
}
