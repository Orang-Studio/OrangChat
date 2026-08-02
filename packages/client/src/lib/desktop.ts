export interface UpdateCheckResult {
  status: "current" | "available" | "downloading" | "error" | "dev";
  version?: string;
  message?: string;
}

export interface GameOverride {
  process: string;
  name: string;
}

export type GamePresenceReport = { gameId: string } | { name: string } | null;

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
  /** Absent in desktop shells built before game presence support. */
  setGamePresenceEnabled?: (enabled: boolean) => void;
  listGameProcesses?: () => Promise<string[]>;
  getGameOverrides?: () => Promise<GameOverride[]>;
  setGameOverrides?: (overrides: GameOverride[]) => Promise<GameOverride[]>;
  onGameDetected?: (callback: (report: GamePresenceReport) => void) => () => void;
}

export const desktop: DesktopBridge | undefined = (
  window as unknown as { orangchatDesktop?: DesktopBridge }
).orangchatDesktop;
