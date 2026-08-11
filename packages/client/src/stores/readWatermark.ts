import { create } from "zustand";

interface ChannelWatermark {

  id: string;

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


export function useReadWatermark(channelId: string | undefined): string | null {
  return useReadWatermarkStore((s) => (channelId ? s.channels[channelId]?.at ?? null : null));
}
