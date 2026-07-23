import { create } from "zustand";
import { useShallow } from "zustand/react/shallow";
import type { Attachment, Message } from "@orangchat/shared";
import { socket } from "../../lib/socket";
import { useAuthStore } from "../../stores/auth";

export interface OutgoingMessagePayload {
  channelId: string;
  content: string;
  replyToId?: string;
  attachmentIds?: string[];
  spoilerAttachmentIds?: string[];
  /** Already-uploaded objects shown while the server message is pending. */
  optimisticAttachments?: Attachment[];
}

interface PendingOutgoing {
  localId: string;
  authorId: string;
  payload: OutgoingMessagePayload;
  message: Message;
}

interface OutboxState {
  entries: PendingOutgoing[];
  error: string | null;
}

export const useMessageOutbox = create<OutboxState>(() => ({
  entries: [],
  error: null,
}));

let appendConfirmed: ((message: Message) => void) | null = null;
let registered = false;
let flushing = false;

class SocketDisconnectedError extends Error {}

const removeEntry = (localId: string) =>
  useMessageOutbox.setState((state) => ({
    entries: state.entries.filter((entry) => entry.localId !== localId),
  }));

function emitPending(entry: PendingOutgoing): Promise<Message> {
  if (!socket.connected) return Promise.reject(new SocketDisconnectedError());
  const {
    optimisticAttachments: _optimisticAttachments,
    ...wirePayload
  } = entry.payload;

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

function confirmEntry(entry: PendingOutgoing, sent: Message): void {
  removeEntry(entry.localId);
  appendConfirmed?.(sent);
}

async function flushOutbox(): Promise<void> {
  if (flushing || !socket.connected) return;
  flushing = true;
  try {
    while (socket.connected) {
      const entry = useMessageOutbox.getState().entries[0];
      if (!entry) break;

      // Never leak a queued message into a different account after logout.
      if (entry.authorId !== useAuthStore.getState().user?.id) {
        removeEntry(entry.localId);
        continue;
      }

      try {
        const sent = await emitPending(entry);
        confirmEntry(entry, sent);
      } catch (error) {
        if (error instanceof SocketDisconnectedError) break;
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
    if (socket.connected && useMessageOutbox.getState().entries.length > 0) {
      queueMicrotask(() => void flushOutbox());
    }
  }
}

/** Install the reconnect flush once alongside the other realtime handlers. */
export function registerMessageOutbox(onConfirmed: (message: Message) => void): void {
  appendConfirmed = onConfirmed;
  if (registered) return;
  registered = true;
  socket.on("connect", () => void flushOutbox());
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
      candidate.payload.content === message.content &&
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

export const messageOutboxActions = {
  dismissError: () => useMessageOutbox.setState({ error: null }),
};
