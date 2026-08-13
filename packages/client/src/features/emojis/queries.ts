import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  emojiToken,
  replaceShortcodes,
  resolveShortcodes,
  type Emoji,
} from "@orangchat/shared";
import { emojiForShortcode } from "../chat/emoji-search";
import { listServerEmojis, listUsableEmojis } from "./api";

export { emojiToken };

export const emojiKeys = {
  usable: ["emojis", "usable"] as const,
  server: (serverId: string) => ["emojis", "server", serverId] as const,
};


export function useUsableEmojis() {
  return useQuery({ queryKey: emojiKeys.usable, queryFn: listUsableEmojis });
}

export function useServerEmojis(serverId: string | undefined) {
  return useQuery({
    queryKey: emojiKeys.server(serverId ?? ""),
    queryFn: () => listServerEmojis(serverId as string),
    enabled: !!serverId,
  });
}


export function useEmojiMap(): Record<string, Emoji> {
  const { data } = useUsableEmojis();
  return useMemo(
    () => Object.fromEntries((data ?? []).map((e) => [e.id, e])),
    [data],
  );
}


export function withMessageEmojis(
  usable: Record<string, Emoji>,
  messageEmojis: Emoji[] | undefined,
): Record<string, Emoji> {
  if (!messageEmojis?.length) return usable;
  return {
    ...usable,
    ...Object.fromEntries(messageEmojis.map((emoji) => [emoji.id, emoji])),
  };
}


export function normalizeCustomEmojiNames(
  content: string,
  emojis: Record<string, Emoji>,
): string {
  const byName = new Map(
    Object.values(emojis).map((emoji) => [emoji.name.toLowerCase(), emoji]),
  );
  // Custom emoji win the name; whatever is left can still be a standard one.
  const withCustom = resolveShortcodes(content, (name) => byName.get(name));
  return replaceShortcodes(withCustom, emojiForShortcode);
}
