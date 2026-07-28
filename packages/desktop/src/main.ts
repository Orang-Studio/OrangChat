import {
  app,
  BrowserWindow,
  globalShortcut,
  Menu,
  ipcMain,
  session,
  dialog,
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
import { createTray, setTrayAttention } from "./tray";
import { syncAutoLaunch } from "./autoLaunch";
import { setUnreadBadge } from "./badge";
import {
  registerUpdater,
  checkForUpdates,
  checkForUpdatesReport,
  type UpdateCheckReport,
} from "./updater";

let mainWindow: BrowserWindow | null = null;
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

app.on("will-quit", () => globalShortcut.unregisterAll());

// The tray keeps the app alive after the last window is hidden.
app.on("window-all-closed", () => {
  if (!getSettings().closeToTray) quitApp();
});

void app.whenReady().then(() => {
  const defaultSession = session.defaultSession;

  const ALLOWED_PERMISSIONS = new Set([
    "media",
    "audioCapture",
    "videoCapture",
    "display-capture",
    "notifications",
    "fullscreen",
    "clipboard-sanitized-write",
  ]);

  // The window is hard-locked to APP_ORIGIN by the navigation guards, so our
  // own page is the only thing that can ever ask. Electron still reports the
  // requesting origin inconsistently across checks - notably it hands the
  // `notifications` permission an empty origin, which made Notification.permission
  // read as "denied" in the packaged app even though the request handler grants
  // it. Resolve the origin from the frame's own URL when Electron doesn't give
  // us one, and treat "unknown" as the app rather than rejecting it.
  const isAppRequester = (origin: string, contents: Electron.WebContents | null) => {
    let resolved = origin && origin !== "null" ? origin : "";
    if (!resolved) {
      try {
        resolved = new URL(contents?.getURL() ?? "").origin;
      } catch {
        resolved = "";
      }
    }
    return resolved === "" || resolved === APP_ORIGIN;
  };

  defaultSession.setPermissionRequestHandler((contents, permission, callback) => {
    callback(ALLOWED_PERMISSIONS.has(permission) && isAppRequester("", contents));
  });

  defaultSession.setPermissionCheckHandler((contents, permission, origin) => {
    return ALLOWED_PERMISSIONS.has(permission) && isAppRequester(origin, contents);
  });

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
