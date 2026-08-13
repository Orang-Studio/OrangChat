import { EMOJI_TABLE } from "./emoji-shortcodes";

export interface UnicodeEmoji {
  char: string;
  /** Primary shortcode, without the surrounding colons. */
  name: string;
  aliases: string[];
  keywords: string[];
}

let entries: UnicodeEmoji[] | undefined;
let byShortcode: Map<string, UnicodeEmoji> | undefined;
let byChar: Map<string, UnicodeEmoji> | undefined;

function load(): UnicodeEmoji[] {
  if (entries) return entries;
  entries = EMOJI_TABLE.split("\n").map((row) => {
    const [char = "", codes = "", keywords = ""] = row.split("\t");
    const aliases = codes.split(",");
    return { char, name: aliases[0]!, aliases, keywords: keywords.split(" ") };
  });
  byShortcode = new Map();
  byChar = new Map();
  for (const entry of entries) {
    byChar.set(entry.char, entry);
    for (const alias of entry.aliases) byShortcode.set(alias, entry);
  }
  return entries;
}

export function unicodeEmojis(): UnicodeEmoji[] {
  return load();
}

/** `sunflower` -> `🌻`. Names are matched lowercase, aliases included. */
export function emojiForShortcode(name: string): string | undefined {
  load();
  return byShortcode!.get(name.toLowerCase())?.char;
}

/** `🌻` -> `sunflower`, for writing the short form back into a composer. */
export function shortcodeForEmoji(char: string): string | undefined {
  load();
  return (byChar!.get(char) ?? byChar!.get(char.replace("️", "")))?.name;
}

/**
 * Shortcode search for the composer's `:xx` panel. Whole-name matches first,
 * then names starting with the query, then anything mentioning it.
 */
export function searchEmoji(query: string, limit = 24): UnicodeEmoji[] {
  const q = query.toLowerCase();
  if (!q) return [];
  const exact: UnicodeEmoji[] = [];
  const prefix: UnicodeEmoji[] = [];
  const loose: UnicodeEmoji[] = [];
  for (const entry of load()) {
    if (entry.aliases.some((a) => a === q)) exact.push(entry);
    else if (entry.aliases.some((a) => a.startsWith(q))) prefix.push(entry);
    else if (
      entry.aliases.some((a) => a.includes(q)) ||
      entry.keywords.some((k) => k.startsWith(q))
    ) {
      loose.push(entry);
    }
    if (exact.length >= limit) break;
  }
  return [...exact, ...prefix, ...loose].slice(0, limit);
}
