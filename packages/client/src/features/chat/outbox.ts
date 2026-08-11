import { useMemo } from "react";
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
import { toast } from "../../stores/toasts";
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

  optimisticAttachments?: Attachment[];

  sealedAttachments?: SealedAttachmentRef[];
}

interface PendingOutgoing {
  localId: string;
  authorId: string;
  payload: OutgoingMessagePayload;
  message: Message;

  sealedCiphertext?: string;

  awaitingVerification?: boolean;

  failed?: boolean;
  failure?: string;
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
  }
}


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
          clientId: record.id,
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
  await appendConfirmed?.({ ...sent, clientId: entry.localId });
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
    const blocked = new Set<string>();

    const currentAuthor = useAuthStore.getState().user?.id;
    for (const stale of useMessageOutbox.getState().entries) {
      if (stale.authorId !== currentAuthor) removeEntry(stale.localId);
    }

    while (socket.connected) {
      const entry = useMessageOutbox
        .getState()
        .entries.find((candidate) => !blocked.has(candidate.payload.channelId) && !candidate.failed);
      if (!entry) break;

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
          blocked.add(entry.payload.channelId);
          markAwaitingVerification(entry.localId);
          await persistQueued(entry);
          continue;
        }
        const failure = error instanceof Error ? error.message : "Failed to send message";
        useMessageOutbox.setState((state) => ({
          entries: state.entries.map((candidate) =>
            candidate.localId === entry.localId
              ? { ...candidate, failed: true, failure }
              : candidate,
          ),
        }));
        toast.error(`Message not sent: ${failure}`);
      }
    }
  } finally {
    flushing = false;
    // Covers a message being queued after the loop saw an empty outbox but
    // before this flush completed. Only entries the loop would actually pick
    // up count: a rejected one is skipped by the `find` above, so counting it
    // here would schedule a flush that does nothing and schedules another -
    // a microtask loop, which never yields and so hangs the tab.
    if (
      socket.connected &&
      useMessageOutbox.getState().entries.some((e) => !e.awaitingVerification && !e.failed)
    ) {
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
    clientId: localId,
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

/** The server broadcasts before acknowledging. Find the local row this echo
 * confirms, so it can be stamped and reconciled rather than duplicated.
 *
 * Excludes failed rows: they stay on screen until retried or discarded, and
 * without this a later identical send (retyped after the first attempt
 * failed) would match the old failed entry by content and silently retire it,
 * leaving the user unable to tell which attempt actually went through. */
export function matchPendingLocalId(message: Message): string | undefined {
  return useMessageOutbox.getState().entries.find(
    (candidate) =>
      !candidate.failed &&
      candidate.authorId === message.author.id &&
      candidate.payload.channelId === message.channelId &&
      (message.ciphertext
        ? candidate.sealedCiphertext === message.ciphertext
        : candidate.payload.content === message.content) &&
      (candidate.payload.replyToId ?? null) === message.replyToId,
  )?.localId;
}

/** Retire the local row once its confirmed replacement is in the cache. */
export function resolvePending(localId: string): void {
  removeEntry(localId);
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

/** Send again a message the server previously refused, leaving it visible
 * meanwhile so the user can see what they are re-sending. */
export function retryMessage(localId: string): void {
  useMessageOutbox.setState((state) => ({
    entries: state.entries.map((entry) =>
      entry.localId === localId
        ? { ...entry, failed: false, failure: undefined }
        : entry,
    ),
  }));
  void flushOutbox();
}

/** Drop a rejected message for good. The server will not take it and the user
 * has said so; the words are theirs to abandon. */
export function discardMessage(localId: string): void {
  removeEntry(localId);
}

/** Failed rows for one conversation: the text stays in the list, flagged red,
 * with its server error, until it is retried or discarded.
 *
 * The selector returns the entries themselves - references the store owns - so
 * `useShallow` can match them across calls. Mapping to fresh objects in there
 * instead would hand `useSyncExternalStore` a new snapshot on every call, which
 * is an unbounded re-render; the shaping belongs in the memo below.
 */
export function useFailedMessages(
  channelId: string | undefined,
): { localId: string; message: Message; failure: string }[] {
  const selfId = useAuthStore((state) => state.user?.id);
  const failed = useMessageOutbox(
    useShallow((state) =>
      state.entries.filter(
        (entry) =>
          entry.authorId === selfId &&
          entry.payload.channelId === channelId &&
          entry.failed,
      ),
    ),
  );
  return useMemo(
    () =>
      failed.map((entry) => ({
        localId: entry.localId,
        message: entry.message,
        failure: entry.failure ?? "Failed to send message",
      })),
    [failed],
  );
}
