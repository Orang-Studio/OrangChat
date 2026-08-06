import type { Conversation } from '@orangchat/shared';
import { api } from '../../lib/api';
import { decryptMessage } from '../e2ee/decrypt';

async function hydrateConversation(conversation: Conversation): Promise<Conversation> {
  return {
    ...conversation,
    latestMessage: conversation.latestMessage
      ? await decryptMessage(conversation.id, conversation.latestMessage)
      : null,
  };
}

export async function listConversations(): Promise<Conversation[]> {
  const conversations = await api<Conversation[]>('/dms');
  return Promise.all(conversations.map(hydrateConversation));
}

/** One userId → dm (idempotent per pair); several → group_dm. */
export async function createDm(userIds: string[]): Promise<Conversation> {
  return hydrateConversation(
    await api<Conversation>('/dms', { method: 'POST', json: { userIds } }),
  );
}

export async function addDmParticipants(
  channelId: string,
  userIds: string[],
): Promise<Conversation> {
  return hydrateConversation(
    await api<Conversation>(`/dms/${channelId}/participants`, {
      method: 'POST',
      json: { userIds },
    }),
  );
}

/**
 * Removes a conversation from your list. A group DM is left for good; a
 * one-to-one is only closed, and comes back if the other person writes again.
 */
export const leaveDm = (channelId: string) =>
  api<{ status: 'left' | 'closed' }>(`/dms/${channelId}`, { method: 'DELETE' });
