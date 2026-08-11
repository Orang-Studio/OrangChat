import { app, nativeImage, type BrowserWindow } from "electron";
import { join } from "node:path";

export function setUnreadBadge(window: BrowserWindow | null, count: number): void {
  const n = Math.max(0, Math.trunc(count));

  if (process.platform !== "win32") {
    app.setBadgeCount(n);
    return;
  }
  if (!window || window.isDestroyed()) return;

  if (n === 0) {
    window.setOverlayIcon(null, "");
    return;
  }

  const file = join(__dirname, "..", "build", "badges", `${n > 9 ? "9plus" : n}.png`);
  const image = nativeImage.createFromPath(file);
  if (image.isEmpty()) return;
  window.setOverlayIcon(image, `${n} unread ${n === 1 ? "message" : "messages"}`);
}
