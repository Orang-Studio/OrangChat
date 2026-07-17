import { app, screen } from "electron";
import { readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

export interface WindowState {
  width: number;
  height: number;
  x?: number;
  y?: number;
  maximized: boolean;
}

const DEFAULTS: WindowState = { width: 1280, height: 820, maximized: false };

function file(): string {
  return join(app.getPath("userData"), "window-state.json");
}

function isVisibleOnSomeDisplay(state: WindowState): boolean {
  if (state.x === undefined || state.y === undefined) return false;
  return screen.getAllDisplays().some(({ workArea }) => {
    return (
      state.x! >= workArea.x - 8 &&
      state.y! >= workArea.y - 8 &&
      state.x! + state.width <= workArea.x + workArea.width + 8 &&
      state.y! + state.height <= workArea.y + workArea.height + 8
    );
  });
}

export function loadWindowState(): WindowState {
  let saved: Partial<WindowState>;
  try {
    saved = JSON.parse(readFileSync(file(), "utf8")) as Partial<WindowState>;
  } catch {
    return { ...DEFAULTS };
  }

  const state: WindowState = {
    width: Math.max(940, saved.width ?? DEFAULTS.width),
    height: Math.max(560, saved.height ?? DEFAULTS.height),
    x: saved.x,
    y: saved.y,
    maximized: saved.maximized ?? false,
  };

  // A monitor may have been unplugged since the last run.
  if (!isVisibleOnSomeDisplay(state)) {
    delete state.x;
    delete state.y;
  }
  return state;
}

export function saveWindowState(state: WindowState): void {
  try {
    writeFileSync(file(), JSON.stringify(state, null, 2));
  } catch {
    // Losing window geometry must never take the app down.
  }
}
