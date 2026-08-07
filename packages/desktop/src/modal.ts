import { dialog, type BrowserWindow } from "electron";

/**
 * Opens a message box modal to `window`, but only while that window is actually
 * on screen; otherwise it opens a top-level dialog with no owner.
 *
 * A modal parented to a *hidden* window is why the app came back from the tray
 * frozen. Windows and GTK both disable input on a modal's owner for as long as
 * the modal is up, and an owner that was never shown gives the user nothing to
 * click - so the dialog waits for an answer that cannot be given, and showing
 * the window from the tray produces a window that ignores every click.
 *
 * The app spends most of its life hidden (closeToTray defaults on) and the
 * things that raise dialogs - the six-hourly update poll, a renderer crash, a
 * failed reload - all fire on their own schedule rather than the user's, so this
 * is the common case rather than an edge one. An unowned dialog is the safe
 * answer: it is always visible, always dismissible, and blocks nothing.
 */
export function messageBoxWhenVisible(
  window: BrowserWindow | null,
  options: Electron.MessageBoxOptions,
): Promise<Electron.MessageBoxReturnValue> {
  return window && !window.isDestroyed() && window.isVisible()
    ? dialog.showMessageBox(window, options)
    : dialog.showMessageBox(options);
}
