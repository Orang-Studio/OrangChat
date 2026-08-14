import { create } from "zustand";

const STORAGE_KEY = "oc-recent-emojis";

const MAX_SAVED = 32;

function read(): string[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter((e): e is string => typeof e === "string" && e.length > 0);
  } catch {
    return [];
  }
}

function write(emojis: string[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(emojis));
  } catch {
    // A full or blocked store just means recents do not survive the session.
  }
}

interface RecentEmojiStore {
  /**
   * Exactly what the picker inserts, newest first - a unicode character, or a
   * `:name:` shortcode for a custom emoji - so an entry replays verbatim.
   */
  emojis: string[];
  record: (insert: string) => void;
}

export const useRecentEmojis = create<RecentEmojiStore>((set, get) => ({
  emojis: read(),

  record: (insert) => {
    if (!insert) return;
    const next = [insert, ...get().emojis.filter((e) => e !== insert)].slice(0, MAX_SAVED);
    write(next);
    set({ emojis: next });
  },
}));
