import type { Message, Page } from "@orangchat/shared";
import { api } from "../../lib/api";

export interface SearchParams {
  q: string;
  channelId?: string;
  authorId?: string;
  /** Offset for pagination; the previous page's `nextCursor`. */
  offset?: number;
  limit?: number;
}

/** Search a server's readable text channels. `nextCursor` is the next offset. */
export function searchMessages(serverId: string, params: SearchParams) {
  const qs = new URLSearchParams({ q: params.q });
  if (params.channelId) qs.set("channelId", params.channelId);
  if (params.authorId) qs.set("authorId", params.authorId);
  if (params.limit) qs.set("limit", String(params.limit));
  if (params.offset) qs.set("offset", String(params.offset));
  return api<Page<Message>>(`/servers/${serverId}/search?${qs}`);
}
