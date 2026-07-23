import { api } from "../../lib/api";

/**
 * Composer drafts. Written to localStorage first so an unsent message survives
 * a reload or an offline moment, then mirrored to the server (debounced) so it
 * follows the user to another device. The server copy is authoritative only
 * when this device has no local draft, e.g. a fresh login.
 */

const SYNC_DEBOUNCE_MS = 800;
const keyFor = (channelId: string) => `oc:draft:${channelId}`;

// channels whose local draft hasn't been confirmed on the server yet.
const dirty = new Set<string>();
const timers = new Map<string, ReturnType<typeof setTimeout>>();

function readLocal(channelId: string): string {
  try {
    return localStorage.getItem(keyFor(channelId)) ?? "";
  } catch {
    return "";
  }
}

function writeLocal(channelId: string, content: string): void {
  try {
    if (content) localStorage.setItem(keyFor(channelId), content);
    else localStorage.removeItem(keyFor(channelId));
  } catch {
    // private-mode / quota: server sync still runs, so not fatal.
  }
}

async function pushToServer(channelId: string): Promise<void> {
  const content = readLocal(channelId);
  try {
    await api(`/channels/${channelId}/draft`, { method: "PUT", json: { content } });
    if (readLocal(channelId) === content) dirty.delete(channelId);
  } catch {
    // stay dirty; flushDrafts() retries when the network is back.
  }
}

/** Store a draft locally now and schedule a server sync. */
export function saveDraft(channelId: string, content: string): void {
  writeLocal(channelId, content);
  dirty.add(channelId);
  clearTimeout(timers.get(channelId));
  timers.set(
    channelId,
    setTimeout(() => void pushToServer(channelId), SYNC_DEBOUNCE_MS),
  );
}

/** Store a draft and push it immediately, e.g. when leaving the channel. */
export function saveDraftNow(channelId: string, content: string): void {
  writeLocal(channelId, content);
  dirty.add(channelId);
  clearTimeout(timers.get(channelId));
  void pushToServer(channelId);
}

/** Drop a draft everywhere, e.g. after its message is sent. */
export function clearDraft(channelId: string): void {
  writeLocal(channelId, "");
  dirty.delete(channelId);
  clearTimeout(timers.get(channelId));
  void api(`/channels/${channelId}/draft`, { method: "DELETE" }).catch(() => {});
}

/** The draft to show when opening a channel: local first, else the server's. */
export async function loadDraft(channelId: string): Promise<string> {
  const local = readLocal(channelId);
  if (local) return local;
  try {
    const { content } = await api<{ content: string | null }>(
      `/channels/${channelId}/draft`,
    );
    return content ?? "";
  } catch {
    return "";
  }
}

/** Retry every unsynced draft; call when connectivity returns. */
export function flushDrafts(): void {
  for (const channelId of dirty) void pushToServer(channelId);
}

if (typeof window !== "undefined") {
  window.addEventListener("online", flushDrafts);
}
