import type { UnreadState } from "@orangchat/shared";
import { api } from "../../lib/api";


export function getUnreads() {
  return api<UnreadState[]>("/me/unreads");
}


export function markChannelUnread(channelId: string, messageId: string) {
  return api<UnreadState>(`/channels/${channelId}/unread`, {
    method: "POST",
    json: { messageId },
  });
}

/** Mark a channel fully read (clears its unread dot + mention badge). */
export function markChannelRead(channelId: string) {
  return api<{ ok: true }>(`/channels/${channelId}/read`, { method: "POST" });
}
