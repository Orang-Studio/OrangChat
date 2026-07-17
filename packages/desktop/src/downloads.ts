import { Notification, shell, type BrowserWindow, type Session } from "electron";

export function registerDownloads(session: Session, getWindow: () => BrowserWindow | null): void {
  const active = new Map<string, { received: number; total: number }>();

  const paint = () => {
    const window = getWindow();
    if (!window || window.isDestroyed()) return;
    if (active.size === 0) {
      window.setProgressBar(-1);
      return;
    }
    let received = 0;
    let total = 0;
    for (const item of active.values()) {
      received += item.received;
      total += item.total;
    }
    window.setProgressBar(total > 0 ? received / total : 2);
  };

  session.on("will-download", (_event, item) => {
    const key = `${item.getURL()}:${item.getStartTime()}`;
    active.set(key, { received: 0, total: item.getTotalBytes() });

    item.on("updated", (_e, state) => {
      active.set(key, {
        received: item.getReceivedBytes(),
        total: item.getTotalBytes(),
      });
      if (state === "interrupted") active.delete(key);
      paint();
    });

    item.once("done", (_e, state) => {
      active.delete(key);
      paint();
      if (state !== "completed") return;

      const savePath = item.getSavePath();
      const notification = new Notification({
        title: "Download complete",
        body: item.getFilename(),
      });
      notification.on("click", () => shell.showItemInFolder(savePath));
      notification.show();
    });

    paint();
  });
}
