export interface UpdateCheckResult {
  status: "current" | "available" | "downloading" | "error" | "dev";
  version?: string;
  message?: string;
}

/** The bridge the Electron preload exposes; undefined in a plain browser. */
export interface DesktopBridge {
  isDesktop: true;
  platform: string;
  version: string | null;
  setBadgeCount: (count: number) => void;
  flashFrame: () => void;
  checkForUpdates: () => Promise<UpdateCheckResult>;
}

export const desktop: DesktopBridge | undefined = (
  window as unknown as { orangchatDesktop?: DesktopBridge }
).orangchatDesktop;
