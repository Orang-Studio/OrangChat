import { app, Menu, nativeImage, Tray, type BrowserWindow } from "electron";
import { join } from "node:path";
import { getSettings, updateSettings } from "./settings";
import { applyAutoLaunch } from "./autoLaunch";
import { checkForUpdates } from "./updater";

let tray: Tray | null = null;

export function createTray(getWindow: () => BrowserWindow | null, onQuit: () => void): Tray {
  const icon = nativeImage
    .createFromPath(join(__dirname, "..", "build", "icon.png"))
    .resize({ width: 16, height: 16 });

  tray = new Tray(icon);
  tray.setToolTip("OrangChat");

  const toggle = () => {
    const window = getWindow();
    if (!window) return;
    if (window.isVisible() && !window.isMinimized()) {
      window.hide();
    } else {
      if (window.isMinimized()) window.restore();
      window.show();
      window.focus();
    }
  };

  const rebuild = () => {
    const settings = getSettings();
    tray?.setContextMenu(
      Menu.buildFromTemplate([
        { label: "Open OrangChat", click: toggle },
        { type: "separator" },
        {
          label: "Start with Windows",
          type: "checkbox",
          checked: settings.autoLaunch,
          click: (item) => {
            updateSettings({ autoLaunch: item.checked });
            applyAutoLaunch(item.checked);
            rebuild();
          },
        },
        {
          label: "Close to tray",
          type: "checkbox",
          checked: settings.closeToTray,
          click: (item) => {
            updateSettings({ closeToTray: item.checked });
            rebuild();
          },
        },
        { type: "separator" },
        { label: "Check for updates…", click: () => checkForUpdates() },
        { label: "Reload", click: () => getWindow()?.reload() },
        { label: "Quit OrangChat", click: onQuit },
      ]),
    );
  };

  rebuild();
  tray.on("click", toggle);
  return tray;
}

/** Swap the tray image, e.g. when the user picks a custom app icon. */
export function setTrayIcon(image: Electron.NativeImage): void {
  tray?.setImage(image.resize({ width: 16, height: 16 }));
}

export function setTrayAttention(active: boolean): void {
  tray?.setToolTip(active ? "OrangChat - new activity" : "OrangChat");
}

export function destroyTray(): void {
  tray?.destroy();
  tray = null;
}

app.on("before-quit", destroyTray);
