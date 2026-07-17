import { create } from "zustand";
import { useShallow } from "zustand/react/shallow";
import type { UnreadState } from "@orangchat/shared";

/** Counts stop here; the UI renders the cap as "99+". Matches the server's cap. */
export const UNREAD_COUNT_CAP = 100;

interface ChannelUnread {
  unread: boolean;
  unreadCount: number;
  mentionCount: number;
  serverId: string | null;
}

interface UnreadStore {
  /** Keyed by channelId. Absent = fully read. */
  channels: Record<string, ChannelUnread>;
}

export const useUnreadStore = create<UnreadStore>(() => ({ channels: {} }));

export const unreadActions = {
  /** Replace all state from GET /me/unreads. */
  hydrate(list: UnreadState[]) {
    const channels: Record<string, ChannelUnread> = {};
    for (const u of list) {
      channels[u.channelId] = {
        unread: u.unread,
        unreadCount: u.unreadCount,
        mentionCount: u.mentionCount,
        serverId: u.serverId,
      };
    }
    useUnreadStore.setState({ channels });
  },

  /** A new message arrived in a channel the user isn't currently reading. */
  bump(channelId: string, serverId: string | null, mentioned: boolean) {
    useUnreadStore.setState((s) => {
      const prev = s.channels[channelId];
      return {
        channels: {
          ...s.channels,
          [channelId]: {
            serverId,
            unread: true,
            unreadCount: Math.min((prev?.unreadCount ?? 0) + 1, UNREAD_COUNT_CAP),
            mentionCount: (prev?.mentionCount ?? 0) + (mentioned ? 1 : 0),
          },
        },
      };
    });
  },

  /** The user opened / read a channel - clear its unread + mentions. */
  clear(channelId: string) {
    useUnreadStore.setState((s) => {
      if (!s.channels[channelId]) return s;
      const next = { ...s.channels };
      delete next[channelId];
      return { channels: next };
    });
  },
};

/** Unread + mention state for a single channel. */
export function useChannelUnread(channelId: string): ChannelUnread {
  return useUnreadStore(
    (s) => s.channels[channelId] ?? EMPTY,
  );
}

const EMPTY: ChannelUnread = {
  unread: false,
  unreadCount: 0,
  mentionCount: 0,
  serverId: null,
};

/** Aggregate unread/mention totals for a whole server (for the rail badge). */
export function useServerUnread(serverId: string): {
  unread: boolean;
  unreadCount: number;
  mentionCount: number;
} {
  return useUnreadStore(
    useShallow((s) => {
      let unread = false;
      let unreadCount = 0;
      let mentionCount = 0;
      for (const c of Object.values(s.channels)) {
        if (c.serverId === serverId) {
          unread = unread || c.unread;
          unreadCount += c.unreadCount;
          mentionCount += c.mentionCount;
        }
      }
      return { unread, unreadCount: Math.min(unreadCount, UNREAD_COUNT_CAP), mentionCount };
    }),
  );
}

/** Total unread messages across every DM/group DM (for the home rail badge). */
export function useDmUnreadTotal(): number {
  return useUnreadStore((s) => {
    let total = 0;
    for (const c of Object.values(s.channels)) {
      if (c.serverId === null) total += c.unreadCount;
    }
    return Math.min(total, UNREAD_COUNT_CAP);
  });
}
