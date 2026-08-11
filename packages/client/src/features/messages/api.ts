import type { Message, Page } from "@orangchat/shared";
import { api } from "../../lib/api";
import { decryptMessages } from "../e2ee/decrypt";
import { reportKeyFor } from "../e2ee/conversation";

export const PAGE_SIZE = 50;


export async function getMessages(channelId: string, before?: string) {
  const params = new URLSearchParams({ limit: String(PAGE_SIZE) });
  if (before) params.set("before", before);
  const page = await api<Page<Message>>(`/channels/${channelId}/messages?${params}`);
  return { ...page, items: await decryptMessages(channelId, page.items) };
}

/** Pinning needs MANAGE_MESSAGES in a server; in a DM anyone in it may pin. */
export function setMessagePinned(channelId: string, messageId: string, pinned: boolean) {
  return api<void>(`/channels/${channelId}/pins/${messageId}`, {
    method: pinned ? "PUT" : "DELETE",
  });
}

export interface MessageReportReceipt {
  id: string;
  status: "received" | "already_received";
  encrypted: boolean;
}

/** Deliberately disclose one message, not its conversation key, for abuse review. */
export async function reportMessage(message: Message, reason?: string) {
  const messageKey = await reportKeyFor(message.channelId, message);
  return api<MessageReportReceipt>(`/messages/${message.id}/report`, {
    method: "POST",
    json: {
      reason: reason?.trim() || undefined,
      messageKey,
    },
  });
}
