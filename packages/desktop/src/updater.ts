import { app, dialog, type BrowserWindow } from "electron";
import { autoUpdater } from "electron-updater";

const CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;

let notifiedVersion: string | null = null;

export function registerUpdater(getWindow: () => BrowserWindow | null): void {
  // Only a packaged app has the metadata (and the writable install dir) an
  // update needs; in dev, checking just logs a confusing error.
  if (!app.isPackaged) return;

  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.logger = null;

  autoUpdater.on("update-downloaded", async (info) => {
    if (notifiedVersion === info.version) return;
    notifiedVersion = info.version;

    const window = getWindow();
    const options = {
      type: "info" as const,
      title: "Update ready",
      message: `OrangChat ${info.version} is ready to install.`,
      detail: "The update installs when you restart. Restart now?",
      buttons: ["Restart now", "Later"],
      defaultId: 0,
      cancelId: 1,
    };

    const { response } = window
      ? await dialog.showMessageBox(window, options)
      : await dialog.showMessageBox(options);

    // quitAndInstall() routes through app.quit(), so main's before-quit hook
    // clears the close-to-tray guard and the window really closes.
    if (response === 0) setImmediate(() => autoUpdater.quitAndInstall());
  });

  autoUpdater.on("error", () => {
    // An unreachable or half-published update feed must never block startup.
  });

  void check();
  setInterval(check, CHECK_INTERVAL_MS);
}

async function check(): Promise<void> {
  try {
    await autoUpdater.checkForUpdates();
  } catch {
    // Offline, or the feed is mid-deploy; the next interval retries.
  }
}
