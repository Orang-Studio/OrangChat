import type { Message, Page } from "@orangchat/shared";
import { api } from "../../lib/api";

export const PAGE_SIZE = 50;

/** Newest-first page of messages; `before` is the id of the oldest loaded message. */
export function getMessages(channelId: string, before?: string) {
  const params = new URLSearchParams({ limit: String(PAGE_SIZE) });
  if (before) params.set("before", before);
  return api<Page<Message>>(`/channels/${channelId}/messages?${params}`);
}
