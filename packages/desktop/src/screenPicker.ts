import { BrowserWindow, desktopCapturer, ipcMain } from "electron";
import type { Session } from "electron";
import { join } from "node:path";

interface PickerSource {
  id: string;
  name: string;
  thumbnail: string;
  appIcon: string | null;
  isScreen: boolean;
}

let openPicker: BrowserWindow | null = null;

async function pickSource(parent: BrowserWindow | null): Promise<string | null> {
  const sources = await desktopCapturer.getSources({
    types: ["screen", "window"],
    thumbnailSize: { width: 320, height: 180 },
    fetchWindowIcons: true,
  });

  const payload: PickerSource[] = sources
    .filter((s) => !s.thumbnail.isEmpty())
    .map((s) => ({
      id: s.id,
      name: s.name,
      thumbnail: s.thumbnail.toDataURL(),
      appIcon: s.appIcon && !s.appIcon.isEmpty() ? s.appIcon.toDataURL() : null,
      isScreen: s.id.startsWith("screen:"),
    }));

  if (payload.length === 0) return null;

  openPicker?.destroy();

  const picker = new BrowserWindow({
    width: 760,
    height: 560,
    parent: parent ?? undefined,
    modal: Boolean(parent),
    show: false,
    resizable: false,
    minimizable: false,
    maximizable: false,
    fullscreenable: false,
    title: "Choose what to share",
    backgroundColor: "#16171b",
    autoHideMenuBar: true,
    webPreferences: {
      preload: join(__dirname, "pickerPreload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  openPicker = picker;

  return new Promise<string | null>((resolve) => {
    let settled = false;
    const finish = (id: string | null) => {
      if (settled) return;
      settled = true;
      ipcMain.removeHandler("picker:sources");
      ipcMain.removeListener("picker:choose", onChoose);
      if (openPicker === picker) openPicker = null;
      if (!picker.isDestroyed()) picker.destroy();
      resolve(id);
    };

    const onChoose = (event: Electron.IpcMainEvent, id: unknown) => {
      if (event.sender !== picker.webContents) return;
      finish(typeof id === "string" && payload.some((s) => s.id === id) ? id : null);
    };

    ipcMain.handle("picker:sources", (event) =>
      event.sender === picker.webContents ? payload : [],
    );
    ipcMain.on("picker:choose", onChoose);
    picker.once("closed", () => finish(null));
    picker.once("ready-to-show", () => picker.show());
    void picker.loadFile(join(__dirname, "picker.html"));
  });
}

export function registerScreenPicker(session: Session, getParent: () => BrowserWindow | null): void {
  session.setDisplayMediaRequestHandler((request, callback) => {
    void (async () => {
      try {
        const id = await pickSource(getParent());
        if (!id) {
          callback({});
          return;
        }
        const all = await desktopCapturer.getSources({
          types: ["screen", "window"],
          thumbnailSize: { width: 0, height: 0 },
        });
        const source = all.find((s) => s.id === id);
        if (!source) {
          callback({});
          return;
        }
        callback(
          request.audioRequested ? { video: source, audio: "loopback" } : { video: source },
        );
      } catch {
        callback({});
      }
    })();
  });
}
