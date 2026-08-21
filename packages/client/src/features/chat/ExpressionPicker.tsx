import { useEffect, useState } from "react";
import * as Popover from "@radix-ui/react-popover";
import { Smile, X } from "lucide-react";
import { cn } from "../../lib/cn";
import { EmojiPickerPanel } from "./EmojiPicker";
import { useFavoriteGifs } from "./favoriteGifs";
import { t } from "../../lib/i18n";

interface KlipyFile {
  url: string;
  width: number;
  height: number;
}

interface KlipyRawGif {
  slug?: string;
  title?: string;
  type?: string;
  file?: Record<string, { gif?: KlipyFile }>;
}

interface KlipyResponse {
  result?: boolean;
  data?: { data?: KlipyRawGif[] };
}

interface KlipyGif {
  slug: string;
  title: string;
  previewUrl: string;
  url: string;
  width: number;
  height: number;
}

const API_KEY = import.meta.env.VITE_KLIPY_API_KEY?.trim();
const API_ROOT = "https://api.klipy.com/api/v1";

function customerId(): string {
  const storageKey = "orangchat:klipy-customer-id";
  try {
    const existing = localStorage.getItem(storageKey);
    if (existing) return existing;
    const created = crypto.randomUUID();
    localStorage.setItem(storageKey, created);
    return created;
  } catch {
    return "orangchat-web";
  }
}

function rendition(raw: KlipyRawGif, names: string[]): KlipyFile | undefined {
  for (const name of names) {
    const file = raw.file?.[name]?.gif;
    if (file?.url) return file;
  }
  return undefined;
}

async function loadGifs(query: string, signal: AbortSignal): Promise<KlipyGif[]> {
  if (!API_KEY) throw new Error("GIF search is not configured");
  const searching = query.trim().length > 0;
  const url = new URL(`${API_ROOT}/${encodeURIComponent(API_KEY)}/gifs/${searching ? "search" : "trending"}`);
  url.searchParams.set("page", "1");
  url.searchParams.set("per_page", "30");
  if (searching) url.searchParams.set("q", query.trim());

  const response = await fetch(url, { signal });
  if (!response.ok) throw new Error("Couldn't load GIFs");
  const body = (await response.json()) as KlipyResponse;
  return (body.data?.data ?? []).flatMap((raw) => {
    if (raw.type !== "gif" || !raw.slug) return [];
    const preview = rendition(raw, ["sm", "xs", "md", "hd"]);
    const full = rendition(raw, ["md", "hd", "sm", "xs"]);
    if (!preview || !full) return [];
    return [{
      slug: raw.slug,
      title: raw.title || "GIF",
      previewUrl: preview.url,
      url: full.url,
      width: full.width || preview.width || 1,
      height: full.height || preview.height || 1,
    }];
  });
}

function recordShare(slug: string) {
  if (!API_KEY) return;
  void fetch(`${API_ROOT}/${encodeURIComponent(API_KEY)}/gifs/share/${encodeURIComponent(slug)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ customer_id: customerId() }),
    keepalive: true,
  }).catch(() => undefined);
}

function GifPanel({ onPick }: { onPick: (url: string) => void }) {
  const favoriteGifs = useFavoriteGifs((s) => s.gifs);
  const removeFavorite = useFavoriteGifs((s) => s.remove);
  const [query, setQuery] = useState("");
  const [gifs, setGifs] = useState<KlipyGif[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    const timer = window.setTimeout(() => {
      void loadGifs(query, controller.signal)
        .then(setGifs)
        .catch((reason: unknown) => {
          if (reason instanceof DOMException && reason.name === "AbortError") return;
          setGifs([]);
          setError(reason instanceof Error ? reason.message : "Couldn't load GIFs");
        })
        .finally(() => {
          if (!controller.signal.aborted) setLoading(false);
        });
    }, query.trim() ? 300 : 0);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [query]);

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2">
      <input
        value={query}
        onChange={(event) => setQuery(event.target.value)}
        placeholder={t("expressionPicker.searchKlipy")}
        aria-label={t("expressionPicker.searchKlipyGifs")}
        className="w-full rounded-lg border border-border bg-surface-1 px-3 py-2 text-sm outline-none focus:border-primary"
      />
      <div className="min-h-0 flex-1 overflow-y-auto">
        {/* Bookmarks lead, and only while browsing: a search is a request for
            something specific, and saved GIFs aren't part of that answer. */}
        {favoriteGifs.length > 0 && !query.trim() && (
          <div className="mb-2">
            <p className="mb-1.5 text-xs font-semibold uppercase tracking-wide text-ink-muted">
              {t("expressionPicker.favourites")}
            </p>
            <div className="columns-2 gap-1.5">
              {favoriteGifs.map((gif) => (
                <div key={gif.url} className="group relative mb-1.5 break-inside-avoid">
                  <button
                    type="button"
                    title={gif.label}
                    onClick={() => onPick(gif.url)}
                    className="block w-full overflow-hidden rounded-lg bg-surface-1 focus-visible:outline-2 focus-visible:outline-primary"
                  >
                    <img src={gif.url} alt={gif.label} loading="lazy" className="w-full" />
                  </button>
                  <button
                    type="button"
                    aria-label={`Remove ${gif.label} from favourites`}
                    onClick={() => removeFavorite(gif.url)}
                    className="absolute right-1 top-1 rounded-md bg-black/60 p-1 text-white opacity-0 transition-opacity focus-visible:opacity-100 group-hover:opacity-100"
                  >
                    <X aria-hidden className="size-3.5" />
                  </button>
                </div>
              ))}
            </div>
          </div>
        )}
        {loading && gifs.length === 0 ? (
          <p className="grid h-full place-items-center text-xs text-ink-muted">{t("expressionPicker.loadingGifs")}</p>
        ) : error ? (
          <p role="alert" className="grid h-full place-items-center px-4 text-center text-xs text-danger">{error}</p>
        ) : gifs.length === 0 ? (
          <p className="grid h-full place-items-center text-xs text-ink-muted">{t("expressionPicker.noGifsFound")}</p>
        ) : (
          <div className="columns-2 gap-1.5">
            {gifs.map((gif) => (
              <button
                key={gif.slug}
                type="button"
                title={gif.title}
                onClick={() => {
                  recordShare(gif.slug);
                  onPick(gif.url);
                }}
                className="mb-1.5 block w-full break-inside-avoid overflow-hidden rounded-lg bg-surface-1 focus-visible:outline-2 focus-visible:outline-primary"
              >
                <img
                  src={gif.previewUrl}
                  alt={gif.title}
                  loading="lazy"
                  width={gif.width}
                  height={gif.height}
                  className="h-auto w-full object-cover transition-opacity hover:opacity-85"
                />
              </button>
            ))}
          </div>
        )}
      </div>
      <a
        href="https://klipy.com"
        target="_blank"
        rel="noopener noreferrer"
        className="self-end text-[10px] font-medium text-ink-muted hover:text-ink"
      >
        {t("expressionPicker.poweredByKlipy")}
      </a>
    </div>
  );
}

export function ExpressionPicker({
  onEmoji,
  onGif,
}: {
  onEmoji: (emoji: string) => void;
  onGif: (url: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [tab, setTab] = useState<"emoji" | "gifs">("emoji");

  return (
    <Popover.Root open={open} onOpenChange={setOpen}>
      <Popover.Trigger asChild>
        <button
          type="button"
          aria-label={t("expressionPicker.addEmojiOrGif")}
          className="rounded-lg p-2 text-ink-muted transition-colors hover:bg-surface-1 hover:text-ink"
        >
          <Smile aria-hidden className="size-5" />
        </button>
      </Popover.Trigger>
      <Popover.Portal>
        <Popover.Content
          side="top"
          align="start"
          sideOffset={8}
          className="z-50 flex h-[26rem] w-[min(22rem,calc(100vw-1.5rem))] flex-col rounded-xl border border-border bg-surface-4 p-2 shadow-2xl"
        >
          <div className="mb-2 grid grid-cols-2 rounded-lg bg-surface-1 p-1">
            {(["emoji", "gifs"] as const).map((value) => (
              <button
                key={value}
                type="button"
                onClick={() => setTab(value)}
                className={cn(
                  "rounded-md px-3 py-1.5 text-xs font-semibold transition-colors",
                  tab === value ? "bg-surface-4 text-ink shadow-sm" : "text-ink-muted hover:text-ink",
                )}
              >
                {value === "emoji" ? "Emoji" : "GIFs"}
              </button>
            ))}
          </div>
          {tab === "emoji" ? (
            <EmojiPickerPanel
              onPick={({ insert }) => {
                onEmoji(insert);
                setOpen(false);
              }}
            />
          ) : (
            <GifPanel onPick={(url) => { onGif(url); setOpen(false); }} />
          )}
        </Popover.Content>
      </Popover.Portal>
    </Popover.Root>
  );
}
