import { app, dialog, type BrowserWindow } from "electron";
import { autoUpdater } from "electron-updater";

const CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000;

let getWindow: () => BrowserWindow | null = () => null;
// Lets the caller flip main's close-to-tray guard off before we quit to install,
// so quitAndInstall() actually closes the window instead of hiding it to tray.
let prepareQuit: () => void = () => {};

let wired = false;
// A user asked for this check, so its result should be shown even when there's
// no update (the silent background poll stays quiet in that case).
let interactive = false;
// Dedupes overlapping checks (background interval vs. a menu click).
let checking = false;
let notifiedVersion: string | null = null;

function activeWindow(): BrowserWindow | null {
  const window = getWindow();
  return window && !window.isDestroyed() ? window : null;
}

function messageBox(
  options: Electron.MessageBoxOptions,
): Promise<Electron.MessageBoxReturnValue> {
  const window = activeWindow();
  return window ? dialog.showMessageBox(window, options) : dialog.showMessageBox(options);
}

async function promptToInstall(version: string): Promise<void> {
  const { response } = await messageBox({
    type: "info",
    title: "Update ready",
    message: `OrangChat ${version} is ready to install.`,
    detail: "Restart OrangChat now to finish installing it?",
    buttons: ["Restart now", "Later"],
    defaultId: 0,
    cancelId: 1,
  });

  if (response === 0) {
    prepareQuit();
    setImmediate(() => autoUpdater.quitAndInstall());
  }
}

function wireEvents(): void {
  if (wired) return;
  wired = true;

  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;
  autoUpdater.logger = null;

  autoUpdater.on("update-available", (info) => {
    // autoDownload is on, so this just tells an interactive checker that the
    // download has started; the "ready" prompt below closes the loop.
    if (!interactive) return;
    interactive = false;
    void messageBox({
      type: "info",
      title: "Update available",
      message: `OrangChat ${info.version} is available.`,
      detail: "It's downloading now. You'll be asked to restart once it's ready.",
      buttons: ["OK"],
    });
  });

  autoUpdater.on("update-not-available", (info) => {
    checking = false;
    if (!interactive) return;
    interactive = false;
    void messageBox({
      type: "info",
      title: "You're up to date",
      message: `OrangChat ${info.version ?? app.getVersion()} is the latest version.`,
      buttons: ["OK"],
    });
  });

  autoUpdater.on("error", (error) => {
    checking = false;
    // A background failure (offline, feed mid-deploy) must stay silent; only a
    // check the user asked for gets to report that it failed.
    if (!interactive) return;
    interactive = false;
    void messageBox({
      type: "error",
      title: "Update check failed",
      message: "Couldn't check for updates.",
      detail: String(error?.message ?? error),
      buttons: ["OK"],
    });
  });

  autoUpdater.on("update-downloaded", async (info) => {
    checking = false;
    interactive = false;
    if (notifiedVersion === info.version) return;
    notifiedVersion = info.version;

    await promptToInstall(info.version);
  });
}

async function runCheck(userInitiated: boolean): Promise<void> {
  if (userInitiated) interactive = true;
  if (checking) return;
  checking = true;
  try {
    await autoUpdater.checkForUpdates();
  } catch {
    // The "error" event fires too and owns the interactive dialog; just release
    // the lock so the next check can run.
    checking = false;
  }
}

export function registerUpdater(
  getWindowFn: () => BrowserWindow | null,
  prepareQuitFn: () => void = () => {},
): void {
  getWindow = getWindowFn;
  prepareQuit = prepareQuitFn;

  // Only a packaged app has the metadata (and the writable install dir) an
  // update needs; in dev, checking just logs a confusing error.
  if (!app.isPackaged) return;

  wireEvents();
  void runCheck(false);
  setInterval(() => void runCheck(false), CHECK_INTERVAL_MS);
}

/** Outcome of a renderer-initiated check; mirrors the client's UpdateCheckResult. */
export interface UpdateCheckReport {
  status: "current" | "available" | "downloading" | "error" | "dev";
  version?: string;
  message?: string;
}

/**
 * Same check as {@link checkForUpdates}, but it reports back to the caller
 * instead of opening a dialog - the in-app Settings → System button renders the
 * outcome itself. The download/restart prompt still runs through the events.
 */
export async function checkForUpdatesReport(): Promise<UpdateCheckReport> {
  if (!app.isPackaged) {
    return { status: "dev", message: "Update checks only run in the installed app." };
  }
  // A download from an earlier check is already sitting ready to install.
  if (notifiedVersion) {
    void promptToInstall(notifiedVersion);
    return { status: "available", version: notifiedVersion };
  }

  wireEvents();
  try {
    const result = await autoUpdater.checkForUpdates();
    if (!result) return { status: "error", message: "Updater is not active." };
    const version = result.updateInfo?.version;
    // electron-updater has already compared the feed against the running build.
    if (result.isUpdateAvailable) {
      return { status: "available", version };
    }
    return { status: "current", version: version ?? app.getVersion() };
  } catch (error) {
    return {
      status: "error",
      message: error instanceof Error ? error.message : String(error),
    };
  }
}

/**
 * Runs a check the user explicitly asked for (menu / tray), reporting the
 * outcome - up to date, downloading, or failed - instead of staying silent.
 */
export function checkForUpdates(): void {
  if (!app.isPackaged) {
    void messageBox({
      type: "info",
      title: "Updates unavailable",
      message: "Update checks only run in the installed app.",
      buttons: ["OK"],
    });
    return;
  }
  // A download from an earlier check may already be sitting ready.
  if (notifiedVersion) {
    void promptToInstall(notifiedVersion);
    return;
  }
  wireEvents();
  void runCheck(true);
}
