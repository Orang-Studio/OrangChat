import type { Conversation } from "@orangchat/shared";
import { api } from "../../lib/api";

export const listConversations = () => api<Conversation[]>("/dms");

/** One userId → dm (idempotent per pair); several → group_dm. */
export const createDm = (userIds: string[]) =>
  api<Conversation>("/dms", { method: "POST", json: { userIds } });

export const addDmParticipants = (channelId: string, userIds: string[]) =>
  api<Conversation>(`/dms/${channelId}/participants`, {
    method: "POST",
    json: { userIds },
  });

/**
 * Removes a conversation from your list. A group DM is left for good; a
 * one-to-one is only closed, and comes back if the other person writes again.
 */
export const leaveDm = (channelId: string) =>
  api<{ status: "left" | "closed" }>(`/dms/${channelId}`, { method: "DELETE" });
