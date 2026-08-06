import { create } from 'zustand';
import { getChannelE2eeState, type E2eeChannelState } from './api';

interface E2eeStoreState {
  channels: Record<string, E2eeChannelState>;
  /**
   * Channels this client has ever seen encrypted. `Channel.e2ee` is a latch on
   * the server, and this is the client's own copy of it: a server that flipped
   * the flag back would be trying to talk us into sending plaintext, so we
   * refuse based on what we remember rather than what we are told.
   */
  latched: Record<string, true>;
}

const LATCH_KEY = 'orangchat.e2ee.latched';

function loadLatched(): Record<string, true> {
  try {
    const raw = localStorage.getItem(LATCH_KEY);
    return raw ? (JSON.parse(raw) as Record<string, true>) : {};
  } catch {
    return {};
  }
}

function persistLatched(latched: Record<string, true>): void {
  try {
    localStorage.setItem(LATCH_KEY, JSON.stringify(latched));
  } catch {
    // A full or blocked storage is not a reason to fail a send; the server-side
    // latch still holds and this is the belt to its braces.
  }
}

export const useE2eeStore = create<E2eeStoreState>(() => ({
  channels: {},
  latched: loadLatched(),
}));

export function isEncrypted(channelId: string): boolean {
  const state = useE2eeStore.getState();
  return state.latched[channelId] === true || state.channels[channelId]?.e2ee === true;
}

export async function refreshChannelState(channelId: string): Promise<E2eeChannelState> {
  const state = await getChannelE2eeState(channelId);
  useE2eeStore.setState((prev) => {
    const latched = state.e2ee ? { ...prev.latched, [channelId]: true as const } : prev.latched;
    if (state.e2ee && prev.latched[channelId] !== true) persistLatched(latched);
    return { channels: { ...prev.channels, [channelId]: state }, latched };
  });
  return state;
}

export function noteEpoch(channelId: string, epoch: number): void {
  useE2eeStore.setState((prev) => {
    const existing = prev.channels[channelId];
    const latched = { ...prev.latched, [channelId]: true as const };
    if (prev.latched[channelId] !== true) persistLatched(latched);
    return {
      latched,
      channels: {
        ...prev.channels,
        [channelId]: existing
          ? { ...existing, e2ee: true, epochNumber: Math.max(existing.epochNumber, epoch) }
          : {
              channelId,
              // A ciphertext only ever arrives in a direct conversation, and the
              // real state replaces this the moment it is fetched.
              channelType: 'dm',
              e2ee: true,
              epochNumber: epoch,
              capable: true,
              rotationRequired: false,
              currentEpochCreatedAt: new Date().toISOString(),
              currentEpochMessageCount: 0,
              memberDevices: [],
            },
      },
    };
  });
}
