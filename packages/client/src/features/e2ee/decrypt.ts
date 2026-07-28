import type { Attachment, Message, MessagePayload } from '@orangchat/shared';
import { rememberSealedAttachments, sealedAttachmentsOf } from './attachments';
import { forgetMessage, recallMessage, recallMessageSync, rememberMessage } from './cache';
import { open } from './conversation';
import { noteEpoch } from './store';

/**
 * What a message reads as when this device cannot open it. Shown in place of the
 * body rather than swallowed: a message that exists and cannot be read is a fact
 * the user needs, and an empty bubble looks like a bug.
 */
const UNREADABLE = "This message can't be read on this device.";
const TAMPERED = 'This message failed its authenticity check and was not shown.';

function isTamper(error: unknown): boolean {
  const message = error instanceof Error ? error.message : '';
  return (
    message.includes('signature') ||
    message.includes('authenticity') ||
    message.includes("not on the sender's account") ||
    message.includes('does not belong to its author') ||
    error instanceof DOMException
  );
}

/**
 * Attachments in an encrypted conversation are described entirely by the message
 * payload: the server's row is a storage id and a byte count, so the name, type
 * and dimensions all come from in here.
 */
function attachmentsFrom(payload: MessagePayload, server: Attachment[]): Attachment[] {
  const refs = payload.attachments ?? [];
  if (refs.length === 0) return server;
  return refs.map((ref) => {
    const row = server.find((a) => a.id === ref.attachmentId);
    return {
      id: ref.attachmentId,
      url: row?.url ?? `/attachments/${ref.attachmentId}`,
      filename: ref.filename,
      contentType: ref.contentType,
      size: ref.size,
      ...(ref.width === undefined ? {} : { width: ref.width }),
      ...(ref.height === undefined ? {} : { height: ref.height }),
      ...(ref.spoiler ? { spoiler: true } : {}),
      ...(row?.storage ? { storage: row.storage } : {}),
      ...(row?.expiresAt ? { expiresAt: row.expiresAt } : {}),
    } satisfies Attachment;
  });
}

function applyPayload(message: Message, payload: MessagePayload): Message {
  rememberSealedAttachments(payload.attachments);
  return {
    ...message,
    content: payload.text,
    attachments: attachmentsFrom(payload, message.attachments),
    // The server assigns the message id, so it can lie about ordering and
    // timestamps. It cannot lie about what the sender wrote down here.
    ...(payload.sentAt ? { createdAt: payload.sentAt } : {}),
    ...(payload.replyTo !== undefined && payload.replyTo !== null
      ? { replyToId: payload.replyTo }
      : {}),
  };
}

/**
 * Replaces an encrypted message's empty `content` with its plaintext, so every
 * downstream consumer - rendering, replies, local search - keeps working on
 * plain messages and never has to know about envelopes.
 */
export async function decryptMessage(channelId: string, message: Message): Promise<Message> {
  if (!message.ciphertext) return message;
  if (message.encEpoch) noteEpoch(channelId, message.encEpoch);

  const cached = await recallMessage(message.id);
  if (cached) {
    rememberSealedAttachments(cached.attachments);
    return applyPayload(message, {
      v: 1,
      text: cached.text,
      sentAt: cached.sentAt,
      clientId: '',
      replyTo: cached.replyTo,
      attachments: cached.attachments,
    });
  }

  try {
    const payload = await open(channelId, message);
    await rememberMessage(
      channelId,
      { id: message.id, authorId: message.author.id, createdAt: message.createdAt },
      payload,
    );
    return applyPayload(message, payload);
  } catch (error) {
    return { ...message, content: isTamper(error) ? TAMPERED : UNREADABLE };
  }
}

export async function decryptMessages(
  channelId: string,
  messages: readonly Message[],
): Promise<Message[]> {
  if (!messages.some((m) => m.ciphertext)) return [...messages];
  return Promise.all(messages.map((message) => decryptMessage(channelId, message)));
}

/** Called when a message is edited so the old plaintext is not shown again. */
export function forgetDecrypted(messageId: string): void {
  void forgetMessage(messageId);
}

/** Synchronous read for paths that must not await, e.g. reply previews. */
export function decryptedTextSync(messageId: string): string | null {
  return recallMessageSync(messageId)?.text ?? null;
}

export { sealedAttachmentsOf };
