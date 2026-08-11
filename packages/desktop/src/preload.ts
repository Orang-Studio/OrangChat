import { contextBridge, ipcRenderer, webFrame } from "electron";

const versionArg = process.argv.find((a) => a.startsWith("--orangchat-version="));

type GamePresenceReport = { gameId: string } | { name: string } | null;
type GameOverride = { process: string; name: string };

contextBridge.exposeInMainWorld("orangchatDesktop", {
  isDesktop: true,
  platform: process.platform,
  version: versionArg ? versionArg.slice("--orangchat-version=".length) : null,
  setBadgeCount: (count: number) => ipcRenderer.send("badge:set", count),
  flashFrame: () => ipcRenderer.send("window:flash"),
  checkForUpdates: () => ipcRenderer.invoke("updates:check"),
  setAppIcon: (dataUrl: string | null) => ipcRenderer.send("icon:set", dataUrl),
  setGamePresenceEnabled: (enabled: boolean) => ipcRenderer.send("game:set-enabled", enabled),
  listGameProcesses: (): Promise<string[]> => ipcRenderer.invoke("game:list-processes"),
  getGameOverrides: (): Promise<GameOverride[]> => ipcRenderer.invoke("game:get-overrides"),
  setGameOverrides: (overrides: GameOverride[]): Promise<GameOverride[]> =>
    ipcRenderer.invoke("game:set-overrides", overrides),
  onGameDetected: (callback: (report: GamePresenceReport) => void) => {
    const listener = (_event: Electron.IpcRendererEvent, report: GamePresenceReport) =>
      callback(report);
    ipcRenderer.on("game:detected", listener);
    return () => ipcRenderer.removeListener("game:detected", listener);
  },
});

const NOTIFY_EVENT = "orangchat:notification-shown";

void webFrame.executeJavaScript(`
  (() => {
    const mark = ${JSON.stringify(NOTIFY_EVENT)};

    const Native = window.Notification;
    if (Native && !Native.__orangchatWrapped) {
      const Wrapped = new Proxy(Native, {
        construct(target, args) {
          try { window.dispatchEvent(new Event(mark)); } catch {}
          return Reflect.construct(target, args);
        },
      });
      Object.defineProperty(Wrapped, "__orangchatWrapped", { value: true });
      window.Notification = Wrapped;
    }

    const Original = ServiceWorkerRegistration.prototype.showNotification;
    ServiceWorkerRegistration.prototype.showNotification = function (...args) {
      try { window.dispatchEvent(new Event(mark)); } catch {}
      return Original.apply(this, args);
    };
  })();
`);

window.addEventListener(NOTIFY_EVENT, () => ipcRenderer.send("window:flash"));
