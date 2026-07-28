import type { UnreadState } from "@orangchat/shared";
import { api } from "../../lib/api";

/** Channels with unread messages or pending mentions for the current user. */
export function getUnreads() {
  return api<UnreadState[]>("/me/unreads");
}

/** Rewind the read cursor so this message and everything newer are unread. */
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
