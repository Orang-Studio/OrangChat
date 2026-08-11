import { useQuery, type QueryClient } from '@tanstack/react-query';
import type { Channel, Conversation, Message } from '@orangchat/shared';
import { listConversations } from './api';

export const dmKeys = {
  list: ['dms'] as const,
};

export function useConversations() {
  return useQuery({ queryKey: dmKeys.list, queryFn: listConversations });
}


export function conversationName(conversation: Conversation, selfId: string | undefined): string {
  if (conversation.name) return conversation.name;
  const others = conversation.participants.filter((p) => p.id !== selfId);
  if (others.length === 0) return 'Just you';
  return others.map((p) => p.displayName).join(', ');
}


export function conversationToChannel(
  conversation: Conversation,
  selfId: string | undefined,
): Channel {
  return {
    id: conversation.id,
    serverId: null,
    name: conversationName(conversation, selfId),
    type: conversation.type,
    topic: null,
    backgroundUrl: conversation.backgroundUrl,
    iconUrl: conversation.iconUrl,
    position: 0,
    parentCategoryId: null,
    nsfw: false,
    rateLimitPerUser: 0,
    userLimit: 0,
    bitrate: 64000,
  };
}


export function otherParticipants(conversation: Conversation, selfId: string | undefined) {
  return conversation.participants.filter((p) => p.id !== selfId);
}

const sortByActivity = (list: Conversation[]) =>
  [...list].sort((a, b) => (b.lastMessageAt ?? '').localeCompare(a.lastMessageAt ?? ''));

export function upsertConversation(client: QueryClient, conversation: Conversation): void {
  client.setQueryData<Conversation[]>(dmKeys.list, (list) => {
    if (!list) return list;
    const others = list.filter((c) => c.id !== conversation.id);
    return sortByActivity([...others, conversation]);
  });
}


export function touchConversation(client: QueryClient, channelId: string, at: string): void {
  const list = client.getQueryData<Conversation[]>(dmKeys.list);
  if (!list) return;
  if (list.some((c) => c.id === channelId)) {
    client.setQueryData<Conversation[]>(
      dmKeys.list,
      sortByActivity(list.map((c) => (c.id === channelId ? { ...c, lastMessageAt: at } : c))),
    );
  } else {
    void client.invalidateQueries({ queryKey: dmKeys.list });
  }
}


export function setConversationLatest(client: QueryClient, message: Message): void {
  client.setQueryData<Conversation[]>(dmKeys.list, (list) => {
    if (!list?.some((conversation) => conversation.id === message.channelId)) return list;
    return sortByActivity(
      list.map((conversation) =>
        conversation.id === message.channelId
          ? {
              ...conversation,
              lastMessageAt: message.createdAt,
              latestMessage: message,
            }
          : conversation,
      ),
    );
  });
}


export function replaceConversationLatest(client: QueryClient, message: Message): void {
  client.setQueryData<Conversation[]>(dmKeys.list, (list) =>
    list?.map((conversation) =>
      conversation.latestMessage?.id === message.id
        ? { ...conversation, latestMessage: message }
        : conversation,
    ),
  );
}
