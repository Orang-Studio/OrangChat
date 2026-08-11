import { create } from "zustand";


export type NotificationLevel = "all" | "mentions" | "none";

export interface ServerNotificationPrefs {

  mutedUntil: number | "forever" | null;
  level: NotificationLevel;
}

const DEFAULTS: ServerNotificationPrefs = { mutedUntil: null, level: "mentions" };

export const MUTE_DURATIONS: { label: string; ms: number | "forever" }[] = [
  { label: "For 15 Minutes", ms: 15 * 60_000 },
  { label: "For 1 Hour", ms: 60 * 60_000 },
  { label: "For 8 Hours", ms: 8 * 60 * 60_000 },
  { label: "For 24 Hours", ms: 24 * 60 * 60_000 },
  { label: "Until I turn it back on", ms: "forever" },
];

export const LEVEL_LABEL: Record<NotificationLevel, string> = {
  all: "All Messages",
  mentions: "Only @mentions",
  none: "Nothing",
};

const STORAGE_KEY = "oc-server-notifications";

type PrefsMap = Record<string, ServerNotificationPrefs>;

function read(): PrefsMap {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as PrefsMap) : {};
  } catch {
    return {};
  }
}

function write(map: PrefsMap): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(map));
  } catch {
  }
}

interface PrefsStore {
  servers: PrefsMap;
}

export const useServerNotifications = create<PrefsStore>(() => ({ servers: read() }));

function patch(serverId: string, changes: Partial<ServerNotificationPrefs>): void {
  const current = useServerNotifications.getState().servers;
  const next: PrefsMap = {
    ...current,
    [serverId]: { ...DEFAULTS, ...current[serverId], ...changes },
  };
  write(next);
  useServerNotifications.setState({ servers: next });
}

export const serverNotificationActions = {
  mute(serverId: string, duration: number | "forever") {
    patch(serverId, {
      mutedUntil: duration === "forever" ? "forever" : Date.now() + duration,
    });
  },
  unmute(serverId: string) {
    patch(serverId, { mutedUntil: null });
  },
  setLevel(serverId: string, level: NotificationLevel) {
    patch(serverId, { level });
  },
};

function resolve(prefs: ServerNotificationPrefs | undefined): ServerNotificationPrefs {
  if (!prefs) return DEFAULTS;
  if (typeof prefs.mutedUntil === "number" && prefs.mutedUntil <= Date.now()) {
    return { ...prefs, mutedUntil: null };
  }
  return prefs;
}


export function useServerNotificationPrefs(serverId: string): ServerNotificationPrefs {
  return resolve(useServerNotifications((s) => s.servers[serverId]));
}


export function getServerNotificationPrefs(serverId: string): ServerNotificationPrefs {
  return resolve(useServerNotifications.getState().servers[serverId]);
}
