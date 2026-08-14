import { useEffect } from "react";
import { useQueryClient } from "@tanstack/react-query";
import type { Message } from "@orangchat/shared";
import { socket } from "../../lib/socket";
import { withAck } from "../../lib/socketAck";
import { useAuthStore } from "../../stores/auth";
import { messageKeys } from "../messages/queries";
import { unreadActions } from "../../stores/unread";
import { clearConversationNotifications } from "../../lib/notifications";
import { queueMessage, type AttachmentPatch, type OutgoingMessagePayload } from "./outbox";
import { seal } from "../e2ee/conversation";
import { sealedAttachmentsOf } from "../e2ee/decrypt";
import { isEncrypted } from "../e2ee/store";

export const sendMessage = async (
  payload: OutgoingMessagePayload,
  awaitAttachments?: () => Promise<AttachmentPatch>,
) => {
  const message = queueMessage(payload, awaitAttachments);
  unreadActions.clear(payload.channelId);
  void clearConversationNotifications(payload.channelId).catch(() => {});
  return message;
};

export const editMessage = async (message: Message, content: string) => {
  if (!isEncrypted(message.channelId)) {
    return withAck<Message>((ack) =>
      socket.emit("message:edit", {
        channelId: message.channelId,
        messageId: message.id,
        content,
      }, ack),
    );
  }

  const attachments = message.attachments
    .map((attachment) => sealedAttachmentsOf(attachment.id))
    .filter((value) => value !== null);
  const sealed = await seal(message.channelId, {
    text: content,
    clientId: crypto.randomUUID(),
    sentAt: message.createdAt,
    replyTo: message.replyToId,
    attachments: attachments.length > 0 ? attachments : undefined,
  });
  return withAck<Message>((ack) =>
    socket.emit("message:edit", {
      channelId: message.channelId,
      messageId: message.id,
      content: "",
      ...sealed,
    }, ack),
  );
};

export const deleteMessage = (payload: { channelId: string; messageId: string }) =>
  withAck<void>((ack) => socket.emit("message:delete", payload, ack));

export const toggleReaction = (
  payload: { channelId: string; messageId: string; emoji: string },
  currentlyMine: boolean,
) => socket.emit(currentlyMine ? "reaction:remove" : "reaction:add", payload);


export const emitTyping = (channelId: string) => {
  if (useAuthStore.getState().user?.typingIndicators === false) return;
  socket.emit("typing:start", channelId);
};


export function useChannelRoom(
  channelId: string | undefined,

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
      if (socket.connected && !isConversation) socket.emit("channel:leave", channelId);
    };
  }, [channelId, isConversation, client]);
}
