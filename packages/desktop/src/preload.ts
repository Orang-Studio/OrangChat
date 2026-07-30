import { contextBridge, ipcRenderer, webFrame } from "electron";

const versionArg = process.argv.find((a) => a.startsWith("--orangchat-version="));

contextBridge.exposeInMainWorld("orangchatDesktop", {
  isDesktop: true,
  platform: process.platform,
  version: versionArg ? versionArg.slice("--orangchat-version=".length) : null,
  setBadgeCount: (count: number) => ipcRenderer.send("badge:set", count),
  flashFrame: () => ipcRenderer.send("window:flash"),
  checkForUpdates: () => ipcRenderer.invoke("updates:check"),
  setAppIcon: (dataUrl: string | null) => ipcRenderer.send("icon:set", dataUrl),
});

// The page already calls the Notification API for DMs and mentions. Wrapping it
// in the main world lets the shell flash the taskbar without any client change.
// DOM events cross world boundaries; the CustomEvent detail deliberately does
// not, so nothing from the page is trusted here beyond "a notification fired".
const NOTIFY_EVENT = "orangchat:notification-shown";

void webFrame.executeJavaScript(`
  (() => {
    const Native = window.Notification;
    if (!Native || Native.__orangchatWrapped) return;

    const Wrapped = new Proxy(Native, {
      construct(target, args) {
        try { window.dispatchEvent(new Event(${JSON.stringify(NOTIFY_EVENT)})); } catch {}
        return Reflect.construct(target, args);
      },
    });
    Object.defineProperty(Wrapped, "__orangchatWrapped", { value: true });
    window.Notification = Wrapped;
  })();
`);

window.addEventListener(NOTIFY_EVENT, () => ipcRenderer.send("window:flash"));
