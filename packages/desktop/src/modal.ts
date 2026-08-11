import { dialog, type BrowserWindow } from "electron";


export function messageBoxWhenVisible(
  window: BrowserWindow | null,
  options: Electron.MessageBoxOptions,
): Promise<Electron.MessageBoxReturnValue> {
  return window && !window.isDestroyed() && window.isVisible()
    ? dialog.showMessageBox(window, options)
    : dialog.showMessageBox(options);
}
