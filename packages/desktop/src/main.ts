import {
  app,
  BrowserWindow,
  globalShortcut,
  Menu,
  ipcMain,
  nativeImage,
  session,
  dialog,
  systemPreferences,
} from "electron";
import { join } from "node:path";
import {
  APP_URL,
  APP_ORIGIN,
  PROTOCOL,
  APP_USER_MODEL_ID,
  isAppUrl,
  deepLinkToAppUrl,
  openExternalIfWeb,
} from "./config";
import { loadWindowState, saveWindowState, type WindowState } from "./windowState";
import { getSettings, updateSettings, clampZoom } from "./settings";
import { registerScreenPicker } from "./screenPicker";
import { registerDownloads } from "./downloads";
import { createTray, setTrayAttention, setTrayIcon } from "./tray";
import { syncAutoLaunch } from "./autoLaunch";
import { setUnreadBadge } from "./badge";
import { GamePresence } from "./gamePresence";
import {
  registerUpdater,
  checkForUpdates,
  checkForUpdatesReport,
  type UpdateCheckReport,
} from "./updater";

/** Bounds an icon data url; 256px of PNG is comfortably under this. */
const MAX_ICON_DATA_URL = 2 * 1024 * 1024;

/**
 * Point the window frame, taskbar and tray at `image`, or back at the bundled
 * mark when it is null. macOS has no per-window icon, so the frame call is a
 * no-op there and only the tray changes.
 */
function applyAppIcon(window: BrowserWindow | null, image: Electron.NativeImage | null): void {
  const bundled = join(__dirname, "..", "build", "icon.png");
  const resolved = image ?? nativeImage.createFromPath(bundled);
  if (resolved.isEmpty()) return;
  window?.setIcon(resolved);
  setTrayIcon(resolved);
}

let mainWindow: BrowserWindow | null = null;
let gamePresence: GamePresence | null = null;
let quitting = false;

if (!app.requestSingleInstanceLock()) {
  app.quit();
}

app.setAppUserModelId(APP_USER_MODEL_ID);

if (process.defaultApp) {
  if (process.argv.length >= 2) {
    app.setAsDefaultProtocolClient(PROTOCOL, process.execPath, [join(process.argv[1]!)]);
  }
} else {
  app.setAsDefaultProtocolClient(PROTOCOL);
}

function deepLinkFrom(argv: string[]): string | null {
  const arg = argv.find((a) => a.startsWith(`${PROTOCOL}://`));
  return arg ? deepLinkToAppUrl(arg) : null;
}

function showWindow(): void {
  if (!mainWindow) return;
  if (mainWindow.isMinimized()) mainWindow.restore();
  if (!mainWindow.isVisible()) mainWindow.show();
  mainWindow.focus();
}

function createWindow(startHidden: boolean): BrowserWindow {
  const state = loadWindowState();
  const settings = getSettings();

  const window = new BrowserWindow({
    width: state.width,
    height: state.height,
    x: state.x,
    y: state.y,
    minWidth: 940,
    minHeight: 560,
    show: false,
    backgroundColor: "#16171b",
    autoHideMenuBar: true,
    title: "OrangChat",
    icon: join(__dirname, "..", "build", "icon.png"),
    webPreferences: {
      preload: join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
      spellcheck: true,
      backgroundThrottling: false,
      additionalArguments: [`--orangchat-version=${app.getVersion()}`],
    },
  });

  if (state.maximized) window.maximize();

  window.once("ready-to-show", () => {
    if (!startHidden) window.show();
  });

  window.webContents.on("did-finish-load", () => {
    window.webContents.setZoomLevel(getSettings().zoomLevel);
  });

  window.webContents.on("zoom-changed", (_event, direction) => {
    const next = clampZoom(window.webContents.getZoomLevel() + (direction === "in" ? 0.5 : -0.5));
    window.webContents.setZoomLevel(next);
    updateSettings({ zoomLevel: next });
  });

  window.on("focus", () => {
    window.flashFrame(false);
    setTrayAttention(false);
  });

  const persist = () => {
    if (window.isDestroyed() || !window.isVisible()) return;
    const maximized = window.isMaximized();
    const bounds = maximized ? window.getNormalBounds() : window.getBounds();
    const next: WindowState = { ...bounds, maximized };
    saveWindowState(next);
  };
  window.on("resize", persist);
  window.on("move", persist);

  window.on("close", (event) => {
    persist();
    // Hiding rather than closing keeps voice/screen-share alive in the background.
    if (!quitting && getSettings().closeToTray) {
      event.preventDefault();
      window.hide();
    }
  });

  window.webContents.setWindowOpenHandler(({ url }) => {
    if (isAppUrl(url)) return { action: "allow" };
    openExternalIfWeb(url);
    return { action: "deny" };
  });

  window.webContents.on("will-navigate", (event, url) => {
    if (isAppUrl(url)) return;
    event.preventDefault();
    openExternalIfWeb(url);
  });

  window.webContents.on("render-process-gone", (_event, details) => {
    if (details.reason === "clean-exit") return;
    void dialog
      .showMessageBox(window, {
        type: "error",
        title: "OrangChat stopped responding",
        message: "The app crashed and needs to reload.",
        buttons: ["Reload", "Quit"],
        defaultId: 0,
      })
      .then(({ response }) => (response === 0 ? window.reload() : quitApp()));
  });

  window.webContents.on("did-fail-load", (_e, errorCode, errorDescription, validatedURL, isMainFrame) => {
    if (!isMainFrame || errorCode === -3) return;
    void showOfflineDialog(window, errorDescription, validatedURL);
  });

  window.on("closed", () => {
    if (mainWindow === window) mainWindow = null;
  });

  void window.loadURL(APP_URL);
  return window;
}

async function showOfflineDialog(window: BrowserWindow, reason: string, url: string): Promise<void> {
  const { response } = await dialog.showMessageBox(window, {
    type: "warning",
    title: "Can't reach OrangChat",
    message: "Can't reach chat.oranges.lt",
    detail: `${reason}\n\nCheck your internet connection and try again.`,
    buttons: ["Retry", "Quit"],
    defaultId: 0,
    cancelId: 1,
  });
  if (response === 0) void window.loadURL(url || APP_URL);
  else quitApp();
}

function quitApp(): void {
  quitting = true;
  app.quit();
}

function buildMenu(): void {
  Menu.setApplicationMenu(
    Menu.buildFromTemplate([
      {
        label: "&File",
        submenu: [
          { label: "Reload", accelerator: "CmdOrCtrl+R", click: () => mainWindow?.reload() },
          { type: "separator" },
          { label: "Quit", accelerator: "CmdOrCtrl+Q", click: quitApp },
        ],
      },
      {
        label: "&Edit",
        submenu: [
          { role: "undo" },
          { role: "redo" },
          { type: "separator" },
          { role: "cut" },
          { role: "copy" },
          { role: "paste" },
          { role: "selectAll" },
        ],
      },
      {
        label: "&View",
        submenu: [
          { role: "resetZoom" },
          { role: "zoomIn" },
          { role: "zoomOut" },
          { type: "separator" },
          { role: "togglefullscreen" },
          { role: "toggleDevTools" },
        ],
      },
      {
        label: "&Help",
        submenu: [{ label: "Check for Updates…", click: () => checkForUpdates() }],
      },
    ]),
  );
}

app.on("second-instance", (_event, argv) => {
  const link = deepLinkFrom(argv);
  showWindow();
  if (link) void mainWindow?.loadURL(link);
});

app.on("before-quit", () => {
  quitting = true;
});

app.on("will-quit", () => {
  gamePresence?.dispose();
  globalShortcut.unregisterAll();
});

// The tray keeps the app alive after the last window is hidden.
app.on("window-all-closed", () => {
  if (!getSettings().closeToTray) quitApp();
});

void app.whenReady().then(() => {
  const defaultSession = session.defaultSession;

  // The window is hard-locked to APP_ORIGIN by the navigation guards and the
  // window-open handler, so "is this our own page asking" is the entire security
  // question - and once the answer is yes, there is nothing to gain by second-
  // guessing which capability it asked for.
  //
  // An allowlist of permission *names* used to sit here and it failed closed in
  // both directions: Electron spells the same capability differently between the
  // request and the check path (`media` vs `audioCapture`/`videoCapture`), and
  // any name nobody thought of is a silent denial. That is how the packaged app
  // ended up with no microphone and no camera at all - getUserMedia rejects with
  // NotAllowedError and there is nothing in the UI to explain it.
  const isAppRequester = (origin: string, contents: Electron.WebContents | null): boolean => {
    for (const candidate of [origin, contents?.getURL()]) {
      if (!candidate || candidate === "null") continue;
      try {
        // The screen picker is a bundled file:// page of ours, and its origin
        // serializes to the string "null" rather than anything comparable.
        return candidate.startsWith("file:") || new URL(candidate).origin === APP_ORIGIN;
      } catch {
        return false;
      }
    }
    // Electron hands several checks - notifications and the media device checks
    // among them - an empty origin and a WebContents whose URL it will not
    // resolve either. The window cannot be anywhere but APP_ORIGIN, so an
    // absent origin is not evidence of a foreign caller.
    return true;
  };

  defaultSession.setPermissionRequestHandler((contents, permission, callback) => {
    const allowed = isAppRequester("", contents);
    if (!allowed) console.warn(`[permissions] denied ${permission} to ${contents?.getURL()}`);
    callback(allowed);
  });

  defaultSession.setPermissionCheckHandler((contents, permission, origin) =>
    isAppRequester(origin, contents),
  );

  // Only reached on macOS, where the OS gates capture behind TCC and Chromium
  // will not raise the prompt on its own: without this, the first getUserMedia
  // rejects instead of asking. Windows and Linux resolve immediately.
  if (process.platform === "darwin") {
    void systemPreferences.askForMediaAccess("microphone");
    void systemPreferences.askForMediaAccess("camera");
  }

  registerScreenPicker(defaultSession, () => mainWindow);
  registerDownloads(defaultSession, () => mainWindow);

  ipcMain.on("badge:set", (event, count: unknown) => {
    if (!isTrustedSender(event)) return;
    if (typeof count !== "number" || !Number.isFinite(count)) return;
    setUnreadBadge(mainWindow, count);
  });

  ipcMain.on("window:flash", (event) => {
    if (!isTrustedSender(event)) return;
    if (!mainWindow || mainWindow.isFocused()) return;
    mainWindow.flashFrame(true);
    setTrayAttention(true);
  });

  // The page owns the user's chosen icon; the frame, tray and taskbar are ours.
  // Only a data url is accepted - a remote url would have main fetch on the
  // page's behalf, outside the session and permission handlers set up above.
  ipcMain.on("icon:set", (event, dataUrl: unknown) => {
    if (!isTrustedSender(event)) return;
    if (dataUrl === null) {
      applyAppIcon(mainWindow, null);
      return;
    }
    if (typeof dataUrl !== "string" || !dataUrl.startsWith("data:image/")) return;
    if (dataUrl.length > MAX_ICON_DATA_URL) return;
    const image = nativeImage.createFromDataURL(dataUrl);
    if (image.isEmpty()) return;
    applyAppIcon(mainWindow, image);
  });

  gamePresence = new GamePresence((report) => {
    if (!mainWindow || mainWindow.isDestroyed()) return;
    mainWindow.webContents.send("game:detected", report);
  });

  ipcMain.on("game:set-enabled", (event, enabled: unknown) => {
    if (!isTrustedSender(event)) return;
    if (typeof enabled !== "boolean") return;
    gamePresence?.setEnabled(enabled);
  });

  ipcMain.handle("game:list-processes", async (event) => {
    if (!isTrustedSender(event)) return [];
    return gamePresence?.listProcesses() ?? [];
  });

  ipcMain.handle("game:get-overrides", (event) => {
    if (!isTrustedSender(event)) return [];
    return gamePresence?.getOverrides() ?? [];
  });

  ipcMain.handle("game:set-overrides", (event, overrides: unknown) => {
    if (!isTrustedSender(event)) return [];
    return gamePresence?.setOverrides(overrides) ?? [];
  });

  // Settings → System runs the check from the page and renders the outcome, so
  // this one answers the caller instead of opening a dialog.
  ipcMain.handle("updates:check", async (event): Promise<UpdateCheckReport> => {
    if (!isTrustedSender(event)) {
      return { status: "error", message: "Untrusted sender" };
    }
    return checkForUpdatesReport();
  });

  const settings = getSettings();
  syncAutoLaunch(settings.autoLaunch);

  buildMenu();

  const startHidden = process.argv.includes("--hidden");
  mainWindow = createWindow(startHidden);

  createTray(() => mainWindow, quitApp);
  registerUpdater(
    () => mainWindow,
    () => {
      quitting = true;
    },
  );

  if (settings.toggleShortcut) {
    try {
      globalShortcut.register(settings.toggleShortcut, () => {
        if (mainWindow?.isVisible() && mainWindow.isFocused()) mainWindow.hide();
        else showWindow();
      });
    } catch {
      // A shortcut already claimed by another app is not fatal.
    }
  }

  const link = deepLinkFrom(process.argv);
  if (link) void mainWindow.loadURL(link);
});

function isTrustedSender(
  event: Electron.IpcMainEvent | Electron.IpcMainInvokeEvent,
): boolean {
  try {
    return new URL(event.senderFrame?.url ?? "").origin === APP_ORIGIN;
  } catch {
    return false;
  }
}
