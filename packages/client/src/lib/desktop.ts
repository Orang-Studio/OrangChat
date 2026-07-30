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
  /** Absent in shells built before the updater IPC existed (≤ 0.1.3). */
  checkForUpdates?: () => Promise<UpdateCheckResult>;
  /**
   * Replace the window/tray/taskbar icon with a data url, or null to restore
   * the bundled mark. Absent in shells built before the custom-icon IPC.
   */
  setAppIcon?: (dataUrl: string | null) => void;
}

export const desktop: DesktopBridge | undefined = (
  window as unknown as { orangchatDesktop?: DesktopBridge }
).orangchatDesktop;
