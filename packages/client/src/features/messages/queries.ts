import {
  useInfiniteQuery,
  type InfiniteData,
  type QueryClient,
} from "@tanstack/react-query";
import { useMemo } from "react";
import type { Message, Page } from "@orangchat/shared";
import { getMessages } from "./api";

export const messageKeys = {
  channel: (channelId: string) => ["messages", channelId] as const,
};

type MessageData = InfiniteData<Page<Message>, string | undefined>;

/**
 * Cursor-paginated history. Pages arrive newest-first (page 0 = latest); the
 * hook exposes `messages` flattened to chronological order for rendering.
 */
export function useMessages(channelId: string | undefined) {
  const query = useInfiniteQuery({
    queryKey: messageKeys.channel(channelId!),
    queryFn: ({ pageParam }) => getMessages(channelId!, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: !!channelId,
    staleTime: Infinity,
  });

  const messages = useMemo(() => {
    if (!query.data) return [];
    // Pages: newest → oldest; items within a page: newest → oldest.
    const all: Message[] = [];
    for (let p = query.data.pages.length - 1; p >= 0; p--) {
      const items = query.data.pages[p]?.items ?? [];
      for (let i = items.length - 1; i >= 0; i--) all.push(items[i]!);
    }
    return all;
  }, [query.data]);

  return { ...query, messages };
}

// ── Cache mutators (used by realtime sync and optimistic sends) ──

function updateChannel(
  client: QueryClient,
  channelId: string,
  fn: (data: MessageData) => MessageData,
): void {
  client.setQueryData<MessageData>(messageKeys.channel(channelId), (data) =>
    data ? fn(data) : data,
  );
}

/** Prepend to the newest page unless the id is already present (ack + broadcast dedupe). */
export function appendMessage(client: QueryClient, message: Message): void {
  updateChannel(client, message.channelId, (data) => {
    if (data.pages.some((p) => p.items.some((m) => m.id === message.id))) {
      return data;
    }
    const [newest, ...rest] = data.pages;
    if (!newest) {
      return { ...data, pages: [{ items: [message], nextCursor: null }] };
    }
    return {
      ...data,
      pages: [{ ...newest, items: [message, ...newest.items] }, ...rest],
    };
  });
}

export function replaceMessage(client: QueryClient, message: Message): void {
  updateChannel(client, message.channelId, (data) => ({
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      items: page.items.map((m) => (m.id === message.id ? message : m)),
    })),
  }));
}

export function removeMessage(
  client: QueryClient,
  channelId: string,
  messageId: string,
): void {
  updateChannel(client, channelId, (data) => ({
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      items: page.items.filter((m) => m.id !== messageId),
    })),
  }));
}

/** Apply a reaction add/remove event to the cached message. */
export function applyReaction(
  client: QueryClient,
  payload: {
    channelId: string;
    messageId: string;
    emoji: string;
    added: boolean;
    /** Whether the reacting user is the current user (drives `me`). */
    isSelf: boolean;
  },
): void {
  updateChannel(client, payload.channelId, (data) => ({
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      items: page.items.map((m) => {
        if (m.id !== payload.messageId) return m;
        const existing = m.reactions.find((r) => r.emoji === payload.emoji);
        let reactions;
        if (payload.added) {
          reactions = existing
            ? m.reactions.map((r) =>
                r.emoji === payload.emoji
                  ? { ...r, count: r.count + 1, me: r.me || payload.isSelf }
                  : r,
              )
            : [...m.reactions, { emoji: payload.emoji, count: 1, me: payload.isSelf }];
        } else {
          reactions = m.reactions
            .map((r) =>
              r.emoji === payload.emoji
                ? { ...r, count: r.count - 1, me: r.me && !payload.isSelf }
                : r,
            )
            .filter((r) => r.count > 0);
        }
        return { ...m, reactions };
      }),
    })),
  }));
}
