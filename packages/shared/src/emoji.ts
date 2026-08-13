


const NAME = "[a-z0-9_-]{2,32}";
const ID = "[a-z0-9]+";


export const EMOJI_TOKEN_SOURCE = `<(a?):(${NAME}):(${ID})>`;
/** A bare, hand-typed shortcode. Group 1 = name. */
export const EMOJI_SHORTCODE_SOURCE = `:(${NAME}):`;

/** Enough of an emoji to build its token - a subset of the full `Emoji` record. */
export interface EmojiToken {
  id: string;
  name: string;
  animated?: boolean;
}

/** The canonical token string for an emoji. The one place tokens are built. */
export function emojiToken(emoji: EmojiToken): string {
  return `<${emoji.animated ? "a" : ""}:${emoji.name}:${emoji.id}>`;
}

export type EmojiSegment =
  | { readonly kind: "text"; readonly text: string }
  | {
      readonly kind: "emoji";
      readonly animated: boolean;
      readonly name: string;
      readonly id: string;
      /** The exact source token, for surfaces that re-emit content verbatim. */
      readonly raw: string;
    };

/**
 * Split content into plain-text runs and complete emoji tokens. Tokens are
 * consumed whole; text between them is returned untouched (never empty).
 */
export function tokenizeEmoji(content: string): EmojiSegment[] {
  const re = new RegExp(EMOJI_TOKEN_SOURCE, "gi");
  const segments: EmojiSegment[] = [];
  let last = 0;
  for (let m = re.exec(content); m !== null; m = re.exec(content)) {
    if (m.index > last) segments.push({ kind: "text", text: content.slice(last, m.index) });
    segments.push({
      kind: "emoji",
      animated: m[1]!.toLowerCase() === "a",
      name: m[2]!,
      id: m[3]!,
      raw: m[0],
    });
    last = m.index + m[0].length;
  }
  if (last < content.length) segments.push({ kind: "text", text: content.slice(last) });
  return segments;
}

/**
 * Turn hand-typed `:name:` shortcodes into durable tokens while leaving any
 * existing tokens exactly as they are. `resolve` receives a lowercased name and
 * returns its emoji, or undefined to leave the shortcode as literal text
 * (unknown names stay text rather than becoming broken tokens).
 */
export function resolveShortcodes(
  content: string,
  resolve: (name: string) => EmojiToken | undefined,
): string {
  return replaceShortcodes(content, (name) => {
    const emoji = resolve(name);
    return emoji ? emojiToken(emoji) : undefined;
  });
}

/**
 * Swap every hand-typed `:name:` for whatever `resolve` returns - a token, a
 * unicode emoji - leaving existing tokens and unknown names as they are.
 */
export function replaceShortcodes(
  content: string,
  resolve: (name: string) => string | undefined,
): string {
  const shortcode = new RegExp(EMOJI_SHORTCODE_SOURCE, "gi");
  return tokenizeEmoji(content)
    .map((segment) =>
      segment.kind === "emoji"
        ? segment.raw
        : segment.text.replace(shortcode, (whole, name: string) =>
            resolve(name.toLowerCase()) ?? whole,
          ),
    )
    .join("");
}

/** Distinct emoji ids referenced by well-formed tokens in `content`, in order. */
export function customEmojiIds(content: string): string[] {
  const ids: string[] = [];
  for (const segment of tokenizeEmoji(content)) {
    if (segment.kind === "emoji" && !ids.includes(segment.id)) ids.push(segment.id);
  }
  return ids;
}
