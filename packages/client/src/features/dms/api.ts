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
