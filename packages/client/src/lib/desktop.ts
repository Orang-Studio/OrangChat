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


export interface DesktopBridge {
  isDesktop: true;
  platform: string;
  version: string | null;
  setBadgeCount: (count: number) => void;
  flashFrame: () => void;

  checkForUpdates?: () => Promise<UpdateCheckResult>;

  setAppIcon?: (dataUrl: string | null) => void;

  setGamePresenceEnabled?: (enabled: boolean) => void;
  listGameProcesses?: () => Promise<string[]>;
  getGameOverrides?: () => Promise<GameOverride[]>;
  setGameOverrides?: (overrides: GameOverride[]) => Promise<GameOverride[]>;
  onGameDetected?: (callback: (report: GamePresenceReport) => void) => () => void;
}

export const desktop: DesktopBridge | undefined = (
  window as unknown as { orangchatDesktop?: DesktopBridge }
).orangchatDesktop;
