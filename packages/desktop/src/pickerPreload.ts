import { contextBridge, ipcRenderer } from "electron";

contextBridge.exposeInMainWorld("picker", {
  sources: () => ipcRenderer.invoke("picker:sources"),
  choose: (id: string) => ipcRenderer.send("picker:choose", id),
  cancel: () => ipcRenderer.send("picker:choose", null),
});
