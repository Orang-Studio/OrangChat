import { create } from "zustand";
import { useShallow } from "zustand/react/shallow";
import type {
  Attachment,
  ClientToServerEvents,
  Message,
  SealedAttachmentRef,
} from "@orangchat/shared";
import { socket } from "../../lib/socket";
import { useAuthStore } from "../../stores/auth";
import { seal } from "../e2ee/conversation";
import { allQueued, deleteQueued, openLocal, putQueued, sealLocal } from "../e2ee/keystore";
import { isEncrypted } from "../e2ee/store";
import { StrictModeError } from "../e2ee/strict";

export interface OutgoingMessagePayload {
  channelId: string;
  content: string;
  replyToId?: string;
  attachmentIds?: string[];
  spoilerAttachmentIds?: string[];
  /** Already-uploaded objects shown while the server message is pending. */
  optimisticAttachments?: Attachment[];
  /** Keys and filenames for attachments the server cannot read (§7). */
  sealedAttachments?: SealedAttachmentRef[];
}

interface PendingOutgoing {
  localId: string;
  authorId: string;
  payload: OutgoingMessagePayload;
  message: Message;
  /** Set once sealed, so the broadcast echo can be matched to this entry. */
  sealedCiphertext?: string;
  /** Set while strict mode is holding this back; see §6.5. */
  awaitingVerification?: boolean;
}

interface OutboxState {
  entries: PendingOutgoing[];
  error: string | null;
}

export const useMessageOutbox = create<OutboxState>(() => ({
  entries: [],
  error: null,
}));

let appendConfirmed: ((message: Message) => void | Promise<void>) | null = null;
let registered = false;
let flushing = false;

class SocketDisconnectedError extends Error {}

const encoder = new TextEncoder();
const decoder = new TextDecoder();

const removeEntry = (localId: string) => {
  useMessageOutbox.setState((state) => ({
    entries: state.entries.filter((entry) => entry.localId !== localId),
  }));
  void deleteQueued(localId).catch(() => {});
};

/**
 * A message strict mode will not release yet has to survive a reload, and it has
 * to survive it without the server ever seeing it. Sealing it under the same
 * non-extractable vault key as the conversation keys is what makes "queued
 * locally, encrypted at rest" true rather than aspirational.
 */
async function persistQueued(entry: PendingOutgoing): Promise<void> {
  try {
    const sealed = await sealLocal(
      encoder.encode(
        JSON.stringify({ payload: entry.payload, createdAt: entry.message.createdAt }),
      ),
    );
    await putQueued({
      id: entry.localId,
      authorId: entry.authorId,
      channelId: entry.payload.channelId,
      queuedAt: entry.message.createdAt,
      ...sealed,
    });
  } catch {
    // Losing persistence costs the message on reload, not the send in flight.
  }
}

/** Reloads anything strict mode was holding when the tab last closed. */
export async function restoreQueuedMessages(): Promise<void> {
  const author = useAuthStore.getState().user;
  if (!author) return;

  let records;
  try {
    records = await allQueued();
  } catch {
    return;
  }

  const restored: PendingOutgoing[] = [];
  for (const record of records) {
    if (record.authorId !== author.id) {
      await deleteQueued(record.id).catch(() => {});
      continue;
    }
    try {
      const body = JSON.parse(decoder.decode(await openLocal(record))) as {
        payload: OutgoingMessagePayload;
        createdAt: string;
      };
      restored.push({
        localId: record.id,
        authorId: record.authorId,
        payload: body.payload,
        awaitingVerification: true,
        message: {
          id: record.id,
          channelId: body.payload.channelId,
          author,
          content: body.payload.content,
          createdAt: body.createdAt,
          editedAt: null,
          replyToId: body.payload.replyToId ?? null,
          attachments: body.payload.optimisticAttachments ?? [],
          reactions: [],
          pinned: false,
          pinnedAt: null,
        },
      });
    } catch {
      await deleteQueued(record.id).catch(() => {});
    }
  }

  if (restored.length === 0) return;
  useMessageOutbox.setState((state) => ({
    entries: [
      ...restored.filter((r) => !state.entries.some((e) => e.localId === r.localId)),
      ...state.entries,
    ],
  }));
  void flushOutbox();
}

/**
 * Sealing happens here rather than when the message is queued, so a message
 * that waited out a disconnect is encrypted under the epoch that is current
 * when it actually goes, not the one that was current when it was typed.
 */
async function emitPending(entry: PendingOutgoing): Promise<Message> {
  if (!socket.connected) throw new SocketDisconnectedError();
  const {
    optimisticAttachments: _optimisticAttachments,
    sealedAttachments,
    ...plainPayload
  } = entry.payload;

  let wirePayload: Parameters<ClientToServerEvents["message:send"]>[0] = plainPayload;
  if (isEncrypted(entry.payload.channelId)) {
    const sealed = await seal(entry.payload.channelId, {
      text: entry.payload.content,
      clientId: entry.localId,
      sentAt: entry.message.createdAt,
      replyTo: entry.payload.replyToId ?? null,
      attachments: sealedAttachments,
    });
    entry.sealedCiphertext = sealed.ciphertext;
    // Filenames and spoiler flags are inside the ciphertext now; sending them
    // again in the clear would hand back exactly what §7 just took away.
    wirePayload = {
      ...plainPayload,
      spoilerAttachmentIds: undefined,
      content: "",
      ...sealed,
    };
  }

  return new Promise((resolve, reject) => {
    const disconnected = () => {
      cleanup();
      reject(new SocketDisconnectedError());
    };
    const cleanup = () => socket.off("disconnect", disconnected);
    socket.once("disconnect", disconnected);
    socket.emit("message:send", wirePayload, (response) => {
      cleanup();
      if (response.ok) resolve(response.data);
      else reject(new Error(response.error));
    });
  });
}

async function confirmEntry(entry: PendingOutgoing, sent: Message): Promise<void> {
  // Keep the optimistic plaintext visible until the confirmed wire row has
  // been opened and inserted. Encrypted acks carry an empty `content`; removing
  // the pending row first makes the message vanish until history is reloaded.
  await appendConfirmed?.(sent);
  removeEntry(entry.localId);
}

function markAwaitingVerification(localId: string): void {
  useMessageOutbox.setState((state) => ({
    entries: state.entries.map((entry) =>
      entry.localId === localId ? { ...entry, awaitingVerification: true } : entry,
    ),
  }));
}

async function flushOutbox(): Promise<void> {
  if (flushing || !socket.connected) return;
  flushing = true;
  try {
    // Strict mode blocks one conversation, not the outbox: a message held back
    // for an unverified DM must not stop everything else from going.
    const blocked = new Set<string>();
    while (socket.connected) {
      const entry = useMessageOutbox
        .getState()
        .entries.find((candidate) => !blocked.has(candidate.payload.channelId));
      if (!entry) break;

      // Never leak a queued message into a different account after logout.
      if (entry.authorId !== useAuthStore.getState().user?.id) {
        removeEntry(entry.localId);
        continue;
      }

      try {
        const sent = await emitPending(entry);
        await confirmEntry(entry, sent);
      } catch (error) {
        if (error instanceof SocketDisconnectedError) break;
        if (error instanceof StrictModeError) {
          // Nothing was wrapped and nothing was sent. The message waits here,
          // sealed at rest, until the contact is verified.
          blocked.add(entry.payload.channelId);
          markAwaitingVerification(entry.localId);
          await persistQueued(entry);
          continue;
        }
        removeEntry(entry.localId);
        useMessageOutbox.setState({
          error: error instanceof Error ? error.message : "Failed to send message",
        });
      }
    }
  } finally {
    flushing = false;
    // Covers a message being queued after the loop saw an empty outbox but
    // before this flush completed.
    if (socket.connected && useMessageOutbox.getState().entries.some((e) => !e.awaitingVerification)) {
      queueMicrotask(() => void flushOutbox());
    }
  }
}

/** Install the reconnect flush once alongside the other realtime handlers. */
export function registerMessageOutbox(
  onConfirmed: (message: Message) => void | Promise<void>,
): void {
  appendConfirmed = onConfirmed;
  if (registered) return;
  registered = true;
  socket.on("connect", () => void flushOutbox());
  void restoreQueuedMessages();
}

/** Called once a contact is verified, to release whatever strict mode held. */
export function retryBlockedMessages(): void {
  useMessageOutbox.setState((state) => ({
    entries: state.entries.map((entry) => ({ ...entry, awaitingVerification: false })),
  }));
  void flushOutbox();
}

/** Insert a local row immediately; delivery happens now or after reconnect. */
export function queueMessage(payload: OutgoingMessagePayload): Message {
  const author = useAuthStore.getState().user;
  if (!author) throw new Error("Not signed in");
  const localId = `pending:${crypto.randomUUID()}`;
  const message: Message = {
    id: localId,
    channelId: payload.channelId,
    author,
    content: payload.content,
    createdAt: new Date().toISOString(),
    editedAt: null,
    replyToId: payload.replyToId ?? null,
    attachments: payload.optimisticAttachments ?? [],
    reactions: [],
    pinned: false,
    pinnedAt: null,
  };
  useMessageOutbox.setState((state) => ({
    entries: [...state.entries, { localId, authorId: author.id, payload, message }],
    error: null,
  }));
  void flushOutbox();
  return message;
}

/** The server broadcasts before acknowledging. Treat the matching echo as the
 * confirmation so an ack lost during disconnect cannot cause a duplicate. */
export function confirmPendingFromBroadcast(message: Message): void {
  const entry = useMessageOutbox.getState().entries.find(
    (candidate) =>
      candidate.authorId === message.author.id &&
      candidate.payload.channelId === message.channelId &&
      (message.ciphertext
        ? candidate.sealedCiphertext === message.ciphertext
        : candidate.payload.content === message.content) &&
      (candidate.payload.replyToId ?? null) === message.replyToId,
  );
  if (entry) removeEntry(entry.localId);
}

export function usePendingMessages(channelId: string | undefined): Message[] {
  const selfId = useAuthStore((state) => state.user?.id);
  return useMessageOutbox(
    useShallow((state) =>
      state.entries
        .filter((entry) => entry.authorId === selfId && entry.payload.channelId === channelId)
        .map((entry) => entry.message),
    ),
  );
}

/** True while strict mode is holding messages back for this conversation. */
export function useBlockedByVerification(channelId: string | undefined): boolean {
  return useMessageOutbox((state) =>
    state.entries.some(
      (entry) => entry.payload.channelId === channelId && entry.awaitingVerification === true,
    ),
  );
}

export const messageOutboxActions = {
  dismissError: () => useMessageOutbox.setState({ error: null }),
};
