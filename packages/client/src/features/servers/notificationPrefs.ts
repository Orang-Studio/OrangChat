import { create } from "zustand";


export type NotificationLevel = "all" | "mentions" | "none";

export interface ServerNotificationPrefs {

  mutedUntil: number | "forever" | null;
  level: NotificationLevel;
}

const DEFAULTS: ServerNotificationPrefs = { mutedUntil: null, level: "mentions" };

export const MUTE_DURATIONS: { labelKey: MuteDurationKey; ms: number | "forever" }[] = [
  { labelKey: "notificationPrefs.for15Minutes", ms: 15 * 60_000 },
  { labelKey: "notificationPrefs.for1Hour", ms: 60 * 60_000 },
  { labelKey: "notificationPrefs.for8Hours", ms: 8 * 60 * 60_000 },
  { labelKey: "notificationPrefs.for24Hours", ms: 24 * 60 * 60_000 },
  { labelKey: "notificationPrefs.untilTurnedBackOn", ms: "forever" },
];

type MuteDurationKey =
  | "notificationPrefs.for15Minutes"
  | "notificationPrefs.for1Hour"
  | "notificationPrefs.for8Hours"
  | "notificationPrefs.for24Hours"
  | "notificationPrefs.untilTurnedBackOn";

export const LEVEL_LABEL: Record<NotificationLevel, string> = {
  all: "All Messages",
  mentions: "Only @mentions",
  none: "Nothing",
};

const SERVER_STORAGE_KEY = "oc-server-notifications";
const DM_STORAGE_KEY = "oc-dm-notifications";

type PrefsMap = Record<string, ServerNotificationPrefs>;

function read(storageKey: string): PrefsMap {
  try {
    const raw = localStorage.getItem(storageKey);
    return raw ? (JSON.parse(raw) as PrefsMap) : {};
  } catch {
    return {};
  }
}

function write(storageKey: string, map: PrefsMap): void {
  try {
    localStorage.setItem(storageKey, JSON.stringify(map));
  } catch {
  }
}

interface PrefsStore {
  servers: PrefsMap;
  dms: PrefsMap;
}

export const useServerNotifications = create<PrefsStore>(() => ({
  servers: read(SERVER_STORAGE_KEY),
  dms: read(DM_STORAGE_KEY),
}));

function patch(
  scope: "servers" | "dms",
  id: string,
  changes: Partial<ServerNotificationPrefs>,
): void {
  const current = useServerNotifications.getState()[scope];
  const next: PrefsMap = {
    ...current,
    [id]: { ...DEFAULTS, ...current[id], ...changes },
  };
  write(scope === "servers" ? SERVER_STORAGE_KEY : DM_STORAGE_KEY, next);
  useServerNotifications.setState({ [scope]: next } as Pick<PrefsStore, typeof scope>);
}

const muteValue = (duration: number | "forever") =>
  duration === "forever" ? ("forever" as const) : Date.now() + duration;

export const serverNotificationActions = {
  mute(serverId: string, duration: number | "forever") {
    patch("servers", serverId, { mutedUntil: muteValue(duration) });
  },
  unmute(serverId: string) {
    patch("servers", serverId, { mutedUntil: null });
  },
  setLevel(serverId: string, level: NotificationLevel) {
    patch("servers", serverId, { level });
  },
};

export const dmNotificationActions = {
  mute(conversationId: string, duration: number | "forever") {
    patch("dms", conversationId, { mutedUntil: muteValue(duration) });
  },
  unmute(conversationId: string) {
    patch("dms", conversationId, { mutedUntil: null });
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

export function useDmMuted(conversationId: string): boolean {
  return resolve(useServerNotifications((s) => s.dms[conversationId])).mutedUntil !== null;
}

export function isDmMuted(conversationId: string): boolean {
  return resolve(useServerNotifications.getState().dms[conversationId]).mutedUntil !== null;
}
