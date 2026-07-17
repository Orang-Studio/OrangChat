import { useQuery, type QueryClient } from "@tanstack/react-query";
import type { Conversation } from "@orangchat/shared";
import { listConversations } from "./api";

export const dmKeys = {
  list: ["dms"] as const,
};

export function useConversations() {
  return useQuery({ queryKey: dmKeys.list, queryFn: listConversations });
}

/** Display name: explicit group name, else the other participants' names. */
export function conversationName(
  conversation: Conversation,
  selfId: string | undefined,
): string {
  if (conversation.name) return conversation.name;
  const others = conversation.participants.filter((p) => p.id !== selfId);
  if (others.length === 0) return "Just you";
  return others.map((p) => p.displayName).join(", ");
}

/** Everyone in the conversation except the current user. */
export function otherParticipants(
  conversation: Conversation,
  selfId: string | undefined,
) {
  return conversation.participants.filter((p) => p.id !== selfId);
}

const sortByActivity = (list: Conversation[]) =>
  [...list].sort((a, b) =>
    (b.lastMessageAt ?? "").localeCompare(a.lastMessageAt ?? ""),
  );

export function upsertConversation(
  client: QueryClient,
  conversation: Conversation,
): void {
  client.setQueryData<Conversation[]>(dmKeys.list, (list) => {
    if (!list) return list;
    const others = list.filter((c) => c.id !== conversation.id);
    return sortByActivity([...others, conversation]);
  });
}

/**
 * A message landed in `channelId`. If it's a known conversation, bump it to the
 * top; if we've never seen the channel (fresh incoming DM), refetch the list.
 * Returns true when the channel was a conversation (known or refetched).
 */
export function touchConversation(
  client: QueryClient,
  channelId: string,
  at: string,
): void {
  const list = client.getQueryData<Conversation[]>(dmKeys.list);
  if (!list) return;
  if (list.some((c) => c.id === channelId)) {
    client.setQueryData<Conversation[]>(
      dmKeys.list,
      sortByActivity(
        list.map((c) => (c.id === channelId ? { ...c, lastMessageAt: at } : c)),
      ),
    );
  } else {
    void client.invalidateQueries({ queryKey: dmKeys.list });
  }
}
