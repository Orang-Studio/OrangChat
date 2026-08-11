import {
  useInfiniteQuery,
  type InfiniteData,
  type QueryClient,
} from "@tanstack/react-query";
import { useMemo } from "react";
import type { Message, Page } from "@orangchat/shared";
import { getMessages } from "./api";
import { usePendingMessages } from "../chat/outbox";

export const messageKeys = {
  channel: (channelId: string) => ["messages", channelId] as const,
};

type MessageData = InfiniteData<Page<Message>, string | undefined>;


export function useMessages(channelId: string | undefined) {
  const pending = usePendingMessages(channelId);
  const query = useInfiniteQuery({
    queryKey: messageKeys.channel(channelId!),
    queryFn: ({ pageParam }) => getMessages(channelId!, pageParam),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled: !!channelId,
    staleTime: Infinity,
  });

  const messages = useMemo(() => {
    if (!query.data) return pending;
    const all: Message[] = [];
    const confirmedLocalIds = new Set<string>();
    for (let p = query.data.pages.length - 1; p >= 0; p--) {
      const items = query.data.pages[p]?.items ?? [];
      for (let i = items.length - 1; i >= 0; i--) {
        const item = items[i]!;
        all.push(item);
        if (item.clientId) confirmedLocalIds.add(item.clientId);
      }
    }
    const stillPending = pending.filter((message) => !confirmedLocalIds.has(message.id));
    return [...all, ...stillPending].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  }, [query.data, pending]);

  const pendingMessageIds = useMemo(() => new Set(pending.map((message) => message.id)), [pending]);
  return { ...query, messages, pendingMessageIds };
}


function updateChannel(
  client: QueryClient,
  channelId: string,
  fn: (data: MessageData) => MessageData,
): void {
  client.setQueryData<MessageData>(messageKeys.channel(channelId), (data) =>
    data ? fn(data) : data,
  );
}


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


export function setMessagePinnedInCache(
  client: QueryClient,
  channelId: string,
  messageId: string,
  pinned: boolean,
): void {
  updateChannel(client, channelId, (data) => ({
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      items: page.items.map((m) =>
        m.id === messageId
          ? { ...m, pinned, pinnedAt: pinned ? new Date().toISOString() : null }
          : m,
      ),
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


export function applyReaction(
  client: QueryClient,
  payload: {
    channelId: string;
    messageId: string;
    emoji: string;
    added: boolean;

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
