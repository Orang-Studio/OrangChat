import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import type { Message } from "@orangchat/shared";
import { socket } from "../../lib/socket";
import { withAck } from "../../lib/socketAck";
import { useAuthStore } from "../../stores/auth";
import { messageKeys } from "../messages/queries";
import { unreadActions } from "../../stores/unread";
import { clearConversationNotifications } from "../../lib/notifications";
import { queueMessage, type OutgoingMessagePayload } from "./outbox";

export const sendMessage = async (payload: OutgoingMessagePayload) => {
  const message = queueMessage(payload);
  unreadActions.clear(payload.channelId);
  void clearConversationNotifications(payload.channelId).catch(() => {});
  return message;
};

export const editMessage = (payload: {
  channelId: string;
  messageId: string;
  content: string;
}) => withAck<Message>((ack) => socket.emit("message:edit", payload, ack));

export const deleteMessage = (payload: { channelId: string; messageId: string }) =>
  withAck<void>((ack) => socket.emit("message:delete", payload, ack));

export const toggleReaction = (
  payload: { channelId: string; messageId: string; emoji: string },
  currentlyMine: boolean,
) => socket.emit(currentlyMine ? "reaction:remove" : "reaction:add", payload);

/** No-op when the user turned typing indicators off in privacy settings. */
export const emitTyping = (channelId: string) => {
  if (useAuthStore.getState().user?.typingIndicators === false) return;
  socket.emit("typing:start", channelId);
};

/**
 * Join the channel's Socket.IO room while mounted so channel-scoped events
 * (messages, typing, reactions) arrive. Re-joins after reconnects.
 *
 * The room only delivers what is sent while we are in it, and the message cache
 * never goes stale on its own, so every join must also resync history or the
 * gap it leaves is invisible until a reload. Ordering matters: joining first
 * means a message racing the refetch arrives twice (appendMessage dedupes by
 * id) instead of falling between the two and being lost.
 */
export function useChannelRoom(
  channelId: string | undefined,
  /** Conversations are joined for the socket's whole life; see the cleanup. */
  isConversation = false,
): void {
  const client = useQueryClient();

  useEffect(() => {
    if (!channelId) return;

    const join = () =>
      socket.emit("channel:join", channelId, (res) => {
        if (!res.ok) return;
        void client.invalidateQueries({ queryKey: messageKeys.channel(channelId) });
      });

    if (socket.connected) join();
    socket.on("connect", join);

    return () => {
      socket.off("connect", join);
      // The server joins every conversation room on connect so DMs the user is
      // not looking at still land (unread badges, sidebar order). Closing the
      // view must not undo that - only server channels are ours to leave.
      if (socket.connected && !isConversation) socket.emit("channel:leave", channelId);
    };
  }, [channelId, isConversation, client]);
}
