import { useMemo, useRef, useState } from "react";
import { Clock, Search } from "lucide-react";
import type { Emoji } from "@orangchat/shared";
import { cn } from "../../lib/cn";
import { useUsableEmojis } from "../emojis/queries";
import { useServers } from "../servers/queries";
import { EMOJI_CATEGORIES } from "./emoji-data";
import { searchEmoji } from "./emoji-search";
import { useRecentEmojis } from "./recentEmojis";
import { t } from "../../lib/i18n";

/**
 * One pick from the picker. `insert` is the composer form - a unicode
 * character, or a `:name:` shortcode - and `custom` is set when the pick was a
 * server emoji, so a caller that needs the durable `<:name:id>` token can build
 * one instead of shipping the name.
 */
export interface EmojiPick {
  insert: string;
  custom?: Emoji;
}

type Section =
  | { id: string; title: string; label: string; kind: "recent"; entries: EmojiPick[] }
  | { id: string; title: string; label: string; kind: "custom"; emojis: Emoji[] }
  | { id: string; title: string; label: string; kind: "unicode"; emojis: string[] };

const SEARCH_LIMIT = 64;

function customGroups(emojis: Emoji[], names: Record<string, string>): Section[] {
  const byServer = new Map<string, Emoji[]>();
  for (const emoji of emojis) {
    const list = byServer.get(emoji.serverId) ?? [];
    list.push(emoji);
    byServer.set(emoji.serverId, list);
  }
  return [...byServer.entries()].map(([serverId, list]) => ({
    id: `custom-${serverId}`,
    kind: "custom" as const,
    title: names[serverId] ?? t("expressionPicker.custom"),
    label: list[0]?.name ?? "?",
    emojis: list,
  }));
}

/**
 * Recent picks paired back up with the emoji they name. A shortcode whose
 * custom emoji is gone - deleted, or on a server this user has left - has
 * nothing left to draw, so it drops out.
 */
export function resolveRecent(recent: string[], custom: Emoji[]): EmojiPick[] {
  return recent.flatMap((insert) => {
    const name = /^:([^:\s]+):$/.exec(insert)?.[1];
    if (!name) return [{ insert }];
    const match = custom.find((e) => e.name.toLowerCase() === name.toLowerCase());
    return match ? [{ insert, custom: match }] : [];
  });
}

function sectionsFor(
  query: string,
  recent: EmojiPick[],
  custom: Emoji[],
  serverNames: Record<string, string>,
): Section[] {
  const q = query.trim().toLowerCase();
  if (!q) {
    return [
      ...(recent.length > 0
        ? [
            {
              id: "recent",
              kind: "recent" as const,
              title: t("expressionPicker.recentlyUsed"),
              label: "recent",
              entries: recent,
            },
          ]
        : []),
      ...customGroups(custom, serverNames),
      ...EMOJI_CATEGORIES.map((category) => ({
        id: `category-${category.name}`,
        kind: "unicode" as const,
        title: category.name,
        label: category.emojis[0] ?? "?",
        emojis: category.emojis,
      })),
    ];
  }

  const hits = custom.filter((e) => e.name.toLowerCase().includes(q));
  const unicode = searchEmoji(q, SEARCH_LIMIT).map((e) => e.char);
  return [
    ...(hits.length > 0
      ? [
          {
            id: "search-custom",
            kind: "custom" as const,
            title: t("expressionPicker.custom"),
            label: hits[0]!.name,
            emojis: hits,
          },
        ]
      : []),
    ...(unicode.length > 0
      ? [
          {
            id: "search-unicode",
            kind: "unicode" as const,
            title: t("expressionPicker.searchResults"),
            label: unicode[0]!,
            emojis: unicode,
          },
        ]
      : []),
  ];
}

function firstPick(sections: Section[]): EmojiPick | undefined {
  const section = sections[0];
  if (!section) return undefined;
  if (section.kind === "recent") return section.entries[0];
  if (section.kind === "custom") {
    const emoji = section.emojis[0];
    return emoji && { insert: `:${emoji.name}:`, custom: emoji };
  }
  const char = section.emojis[0];
  return char ? { insert: char } : undefined;
}

const CELL = "grid aspect-square place-items-center rounded-lg text-xl hover:bg-surface-2";

function EmojiButton({ pick, onPick }: { pick: EmojiPick; onPick: (pick: EmojiPick) => void }) {
  return (
    <button
      type="button"
      title={pick.custom ? `:${pick.custom.name}:` : pick.insert}
      onClick={() => onPick(pick)}
      className={CELL}
    >
      {pick.custom ? (
        <img
          src={pick.custom.url}
          alt={`:${pick.custom.name}:`}
          loading="lazy"
          className="size-6 object-contain"
        />
      ) : (
        pick.insert
      )}
    </button>
  );
}

/**
 * The full emoji picker - search, jump-to-category rail, recents, this
 * viewer's custom emoji, then the standard set. Sized by its parent: it fills
 * whatever box it is given and scrolls inside it.
 */
export function EmojiPickerPanel({
  onPick,
  autoFocus = false,
  className,
}: {
  onPick: (pick: EmojiPick) => void;
  autoFocus?: boolean;
  className?: string;
}) {
  const { data: emojis } = useUsableEmojis();
  const { data: servers } = useServers();
  const record = useRecentEmojis((s) => s.record);
  const [openingRecent] = useState(useRecentEmojis((s) => s.emojis));
  const [query, setQuery] = useState("");
  const scroller = useRef<HTMLDivElement>(null);
  const sectionRefs = useRef(new Map<string, HTMLElement>());

  const custom = useMemo(() => emojis ?? [], [emojis]);
  const serverNames = useMemo(
    () => Object.fromEntries((servers ?? []).map((s) => [s.id, s.name])),
    [servers],
  );
  const recent = useMemo(
    () => resolveRecent(openingRecent, custom),
    [openingRecent, custom],
  );
  const sections = useMemo(
    () => sectionsFor(query, recent, custom, serverNames),
    [query, recent, custom, serverNames],
  );

  const pick = (picked: EmojiPick) => {
    record(picked.insert);
    onPick(picked);
  };

  const jumpTo = (id: string) => {
    const target = sectionRefs.current.get(id);
    const box = scroller.current;
    if (!target || !box) return;
    box.scrollTo({ top: target.offsetTop - box.offsetTop, behavior: "smooth" });
  };

  return (
    <div className={cn("flex min-h-0 flex-1 flex-col", className)}>
      <div className="relative mb-1.5">
        <Search
          aria-hidden
          className="pointer-events-none absolute left-2.5 top-1/2 size-3.5 -translate-y-1/2 text-ink-muted"
        />
        <input
          value={query}
          autoFocus={autoFocus}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            if (event.key !== "Enter") return;
            const first = firstPick(sections);
            if (first) {
              event.preventDefault();
              pick(first);
            }
          }}
          placeholder={t("expressionPicker.searchEmoji")}
          aria-label={t("expressionPicker.searchEmoji")}
          className="w-full rounded-lg border border-border bg-surface-1 py-1.5 pl-8 pr-3 text-sm outline-none focus:border-primary"
        />
      </div>

      {/* Jump rail - one stop per section, so the standard set stays reachable
          without dragging through a thousand emoji. */}
      {sections.length > 1 && (
        <div className="mb-1.5 flex shrink-0 gap-0.5 overflow-x-auto border-b border-border pb-1.5">
          {sections.map((section) => (
            <button
              key={section.id}
              type="button"
              title={section.title}
              aria-label={section.title}
              onClick={() => jumpTo(section.id)}
              className="grid size-7 shrink-0 place-items-center rounded-md text-base hover:bg-surface-2"
            >
              {section.kind === "recent" ? (
                <Clock aria-hidden className="size-4 text-ink-muted" />
              ) : section.kind === "custom" ? (
                <img
                  src={section.emojis[0]?.url}
                  alt=""
                  loading="lazy"
                  className="size-4 object-contain"
                />
              ) : (
                section.label
              )}
            </button>
          ))}
        </div>
      )}

      <div ref={scroller} className="min-h-0 flex-1 overflow-y-auto">
        {sections.length === 0 && (
          <p className="grid h-full place-items-center text-xs text-ink-muted">
            {t("expressionPicker.noEmojiFound")}
          </p>
        )}
        {sections.map((section) => (
          <section
            key={section.id}
            ref={(node) => {
              if (node) sectionRefs.current.set(section.id, node);
              else sectionRefs.current.delete(section.id);
            }}
          >
            <h3 className="sticky top-0 z-10 bg-surface-4 px-1 py-1.5 text-[11px] font-semibold uppercase tracking-wide text-ink-muted">
              {section.title}
            </h3>
            <div className="grid grid-cols-8">
              {section.kind === "recent"
                ? section.entries.map((entry) => (
                    <EmojiButton key={entry.insert} pick={entry} onPick={pick} />
                  ))
                : section.kind === "custom"
                  ? section.emojis.map((emoji) => (
                      <EmojiButton
                        key={emoji.id}
                        pick={{ insert: `:${emoji.name}:`, custom: emoji }}
                        onPick={pick}
                      />
                    ))
                  : section.emojis.map((emoji) => (
                      <EmojiButton key={emoji} pick={{ insert: emoji }} onPick={pick} />
                    ))}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
