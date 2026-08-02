import { execFile } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import {
  getSettings,
  sanitizeGameOverrides,
  updateSettings,
  type GameOverride,
} from "./settings";

export type GamePresenceReport = { gameId: string } | { name: string } | null;

interface RegistryGame {
  id: string;
  executables: string[];
}

const POLL_INTERVAL_MS = 15_000;
const PROCESS_TIMEOUT_MS = 5_000;

function normalizeProcessName(value: string): string {
  return (
    value
      .trim()
      .replace(/^"|"$/g, "")
      .replace(/\\/g, "/")
      .split("/")
      .pop()
      ?.toLowerCase()
      .replace(/\.app$/i, "") ?? ""
  );
}

function loadRegistry(): RegistryGame[] {
  try {
    // games.json is copied in at build time by scripts/copy-assets.mjs, and it
    // is the generated file rather than a bare array: `{ version, note, games }`.
    // The server compiles the same file in, so the two always agree on which
    // process belongs to which id.
    const parsed = JSON.parse(readFileSync(join(__dirname, "games.json"), "utf8")) as unknown;
    const games = (parsed as { games?: unknown })?.games;
    if (!Array.isArray(games)) return [];
    return games.flatMap((item): RegistryGame[] => {
      if (!item || typeof item !== "object") return [];
      const candidate = item as { id?: unknown; executables?: unknown };
      if (typeof candidate.id !== "string" || !Array.isArray(candidate.executables)) return [];
      const executables = candidate.executables
        .filter((value): value is string => typeof value === "string")
        .map(normalizeProcessName)
        .filter(Boolean);
      return executables.length > 0 ? [{ id: candidate.id, executables }] : [];
    });
  } catch {
    return [];
  }
}

const registry = loadRegistry();

function firstCsvField(row: string): string {
  const quoted = row.match(/^"((?:[^"]|"")*)"/);
  if (quoted) return quoted[1]!.replace(/""/g, '"');
  return row.split(",", 1)[0] ?? "";
}

function processCommand(): { file: string; args: string[] } {
  return process.platform === "win32"
    ? { file: "tasklist", args: ["/fo", "csv", "/nh"] }
    : { file: "ps", args: ["-eo", "comm="] };
}

export async function listRunningProcesses(): Promise<string[]> {
  const { file, args } = processCommand();
  const stdout = await new Promise<string>((resolve, reject) => {
    execFile(
      file,
      args,
      { timeout: PROCESS_TIMEOUT_MS, windowsHide: true, maxBuffer: 4 * 1024 * 1024 },
      (error, output) => (error ? reject(error) : resolve(output)),
    );
  });
  const names = stdout
    .split(/\r?\n/)
    .map((row) => normalizeProcessName(process.platform === "win32" ? firstCsvField(row) : row))
    .filter(Boolean);
  return [...new Set(names)].sort((a, b) => a.localeCompare(b));
}

function reportKey(report: GamePresenceReport): string {
  if (!report) return "none";
  return "gameId" in report ? `game:${report.gameId}` : `name:${report.name}`;
}

export class GamePresence {
  private enabled: boolean | undefined;
  private timer: ReturnType<typeof setInterval> | undefined;
  private lastReportKey: string | undefined;
  private scanning = false;

  constructor(private readonly report: (value: GamePresenceReport) => void) {}

  setEnabled(enabled: boolean): void {
    if (this.enabled === enabled) return;
    this.enabled = enabled;
    clearInterval(this.timer);
    this.timer = undefined;

    if (!enabled) {
      this.lastReportKey = "none";
      this.report(null);
      return;
    }

    void this.scan();
    this.timer = setInterval(() => void this.scan(), POLL_INTERVAL_MS);
  }

  async listProcesses(): Promise<string[]> {
    if (this.enabled !== true) return [];
    try {
      return await listRunningProcesses();
    } catch {
      return [];
    }
  }

  getOverrides(): GameOverride[] {
    return getSettings().gameOverrides;
  }

  setOverrides(value: unknown): GameOverride[] {
    const gameOverrides = sanitizeGameOverrides(value);
    updateSettings({ gameOverrides });
    if (this.enabled) void this.scan();
    return gameOverrides;
  }

  dispose(): void {
    clearInterval(this.timer);
    this.timer = undefined;
  }

  private async scan(): Promise<void> {
    if (!this.enabled || this.scanning) return;
    this.scanning = true;
    try {
      const running = new Set(await listRunningProcesses());
      if (!this.enabled) return;

      let next: GamePresenceReport = null;
      for (const game of registry) {
        if (game.executables.some((executable) => running.has(executable))) {
          next = { gameId: game.id };
          break;
        }
      }
      if (!next) {
        const override = getSettings().gameOverrides.find((item) => running.has(item.process));
        if (override) next = { name: override.name };
      }

      const key = reportKey(next);
      if (key !== this.lastReportKey) {
        this.lastReportKey = key;
        this.report(next);
      }
    } catch {
      // A timed-out process listing should not make a running game flicker off.
    } finally {
      this.scanning = false;
    }
  }
}
