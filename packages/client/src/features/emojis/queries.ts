import { useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import type { Emoji } from "@orangchat/shared";
import { listServerEmojis, listUsableEmojis } from "./api";

export const emojiKeys = {
  usable: ["emojis", "usable"] as const,
  server: (serverId: string) => ["emojis", "server", serverId] as const,
};

/** Every custom emoji the viewer can type, across all their servers. */
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

/**
 * id → emoji, for resolving `<:name:id>` while rendering. Messages can name an
 * emoji the viewer has no access to (it was deleted, or lives in a server they
 * are not in); those simply miss and render as text.
 */
export function useEmojiMap(): Record<string, Emoji> {
  const { data } = useUsableEmojis();
  return useMemo(
    () => Object.fromEntries((data ?? []).map((e) => [e.id, e])),
    [data],
  );
}

/** The token that goes into message content. */
export const emojiToken = (emoji: Emoji) =>
  `<${emoji.animated ? "a" : ""}:${emoji.name}:${emoji.id}>`;
