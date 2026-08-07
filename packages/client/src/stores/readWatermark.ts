import { create } from "zustand";

interface ChannelWatermark {
  /** Newest message id at the time it was last read. */
  id: string;
  /** ISO createdAt of that message. */
  at: string;
}

interface ReadWatermarkState {
  channels: Record<string, ChannelWatermark>;
}

const STORAGE_KEY = "oc-read-watermarks";

function load(): Record<string, ChannelWatermark> {
  try {
    return JSON.parse(localStorage.getItem(STORAGE_KEY) ?? "{}") as Record<
      string,
      ChannelWatermark
    >;
  } catch {
    return {};
  }
}

// `mark()` fires once per message while the list sits at the bottom, which in
// an active channel is once per message received - too often for a synchronous
// localStorage write on every one. Writes are coalesced to at most once a
// second, and flushed early if the tab is about to go away so the last mark
// isn't lost.
const PERSIST_INTERVAL_MS = 1000;
let persistTimer: ReturnType<typeof setTimeout> | null = null;
let pending: Record<string, ChannelWatermark> | null = null;

function flushPersist(): void {
  if (persistTimer) {
    clearTimeout(persistTimer);
    persistTimer = null;
  }
  if (!pending) return;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(pending));
  } catch {
    // Storage unavailable (private mode): keep the in-memory value only.
  }
  pending = null;
}

function schedulePersist(channels: Record<string, ChannelWatermark>): void {
  pending = channels;
  if (persistTimer) return;
  persistTimer = setTimeout(flushPersist, PERSIST_INTERVAL_MS);
}

if (typeof window !== "undefined") {
  window.addEventListener("beforeunload", flushPersist);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") flushPersist();
  });
}

/**
 * Per-channel "read up to" point, kept on this device. The server owns the
 * authoritative read state; this mirror is what the client draws the unread
 * divider from. It only advances while the message list is actually at the
 * bottom, so messages that arrive while the user is scrolled up stay "new".
 */
export const useReadWatermarkStore = create<ReadWatermarkState>(() => ({
  channels: load(),
}));

export const readWatermarkActions = {
  mark(channelId: string, id: string, at: string) {
    useReadWatermarkStore.setState((s) => {
      const channels = { ...s.channels, [channelId]: { id, at } };
      schedulePersist(channels);
      return { channels };
    });
  },
};

/** ISO timestamp of the newest message read in this channel, or null. */
export function useReadWatermark(channelId: string | undefined): string | null {
  return useReadWatermarkStore((s) => (channelId ? s.channels[channelId]?.at ?? null : null));
}
