import { create } from "zustand";

/**
 * GIFs the user has favourited, kept on the device.
 *
 * Device-local rather than server-side for the same reason as the rest of
 * prefs: it's a scratchpad, not account data, and a bookmark is only a URL
 * plus a label. Nothing here is worth a table, a migration, or a sync story.
 */
export interface FavoriteGif {
  /** The image URL; also the identity, since the same GIF saved twice is one. */
  url: string;
  label: string;
  savedAt: number;
}

const STORAGE_KEY = "oc-favorite-gifs";

/** Enough to be useful, small enough to stay inside the localStorage budget. */
const MAX_SAVED = 100;

function read(): FavoriteGif[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) return [];
    return parsed.filter(
      (g): g is FavoriteGif =>
        typeof g === "object" && g !== null && typeof (g as FavoriteGif).url === "string",
    );
  } catch {
    return [];
  }
}

function write(gifs: FavoriteGif[]): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(gifs));
  } catch {
    // Storage unavailable (private mode) - favourites just won't persist.
  }
}

interface FavoriteGifStore {
  gifs: FavoriteGif[];
  toggle: (gif: Omit<FavoriteGif, "savedAt">) => void;
  remove: (url: string) => void;
}

export const useFavoriteGifs = create<FavoriteGifStore>((set, get) => ({
  gifs: read(),

  toggle: ({ url, label }) => {
    const existing = get().gifs;
    const next = existing.some((g) => g.url === url)
      ? existing.filter((g) => g.url !== url)
      : // Newest first, so the picker leads with what was just saved.
        [{ url, label, savedAt: Date.now() }, ...existing].slice(0, MAX_SAVED);
    write(next);
    set({ gifs: next });
  },

  remove: (url) => {
    const next = get().gifs.filter((g) => g.url !== url);
    write(next);
    set({ gifs: next });
  },
}));

export const isGifFavorite = (gifs: FavoriteGif[], url: string) => gifs.some((g) => g.url === url);

/**
 * Whether an attachment is a GIF. Checks the declared type first and falls back
 * to the extension, since an upload can arrive as application/octet-stream.
 */
export function isGif(contentType: string | null | undefined, filename: string): boolean {
  if (contentType === "image/gif") return true;
  return contentType == null && filename.toLowerCase().endsWith(".gif");
}
