import { useMemo } from "react";
import { create } from "zustand";

/** How long a `typing` event keeps a user in the indicator without a refresh. */
const TYPING_TTL_MS = 6_000;

interface TypingState {
  /** channelId → userId → expiry epoch ms. */
  byChannel: Record<string, Record<string, number>>;
}

export const useTypingStore = create<TypingState>(() => ({ byChannel: {} }));

const expiryTimers = new Map<string, ReturnType<typeof setTimeout>>();

/** Record a typing event; the entry self-expires after the TTL. */
export function bumpTyping(channelId: string, userId: string): void {
  useTypingStore.setState((s) => ({
    byChannel: {
      ...s.byChannel,
      [channelId]: {
        ...s.byChannel[channelId],
        [userId]: Date.now() + TYPING_TTL_MS,
      },
    },
  }));

  const key = `${channelId}:${userId}`;
  clearTimeout(expiryTimers.get(key));
  expiryTimers.set(
    key,
    setTimeout(() => {
      expiryTimers.delete(key);
      clearTyping(channelId, userId);
    }, TYPING_TTL_MS),
  );
}

/** Drop a user from a channel's typing set (expiry, or their message arrived). */
export function clearTyping(channelId: string, userId: string): void {
  useTypingStore.setState((s) => {
    const channel = s.byChannel[channelId];
    if (!channel?.[userId]) return s;
    const { [userId]: _, ...rest } = channel;
    return { byChannel: { ...s.byChannel, [channelId]: rest } };
  });
}

/** User ids currently typing in a channel (excluding the current user). */
export function useTypingUserIds(
  channelId: string | undefined,
  selfId: string | undefined,
): string[] {
  // Select the per-channel record (stable reference) and derive outside the
  // selector - returning a fresh array from a zustand selector loops renders.
  const channel = useTypingStore((s) =>
    channelId ? s.byChannel[channelId] : undefined,
  );
  return useMemo(() => {
    if (!channel) return [];
    const now = Date.now();
    return Object.keys(channel).filter(
      (id) => id !== selfId && (channel[id] ?? 0) > now,
    );
  }, [channel, selfId]);
}
