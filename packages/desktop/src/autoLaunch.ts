import { app } from "electron";

// Squirrel/NSIS installs run from a versioned folder via a stable Update.exe
// stub, so pointing the login item at execPath would break on every update.
export function applyAutoLaunch(enabled: boolean): void {
  if (process.platform !== "win32" && process.platform !== "darwin") return;
  app.setLoginItemSettings({
    openAtLogin: enabled,
    openAsHidden: true,
    args: ["--hidden"],
  });
}

export function syncAutoLaunch(desired: boolean): void {
  if (process.platform !== "win32" && process.platform !== "darwin") return;
  const current = app.getLoginItemSettings({ args: ["--hidden"] }).openAtLogin;
  if (current !== desired) applyAutoLaunch(desired);
}
