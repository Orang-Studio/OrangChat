import { create } from "zustand";
import { useShallow } from "zustand/react/shallow";
import type { UnreadState } from "@orangchat/shared";


export const UNREAD_COUNT_CAP = 100;

interface ChannelUnread {
  unread: boolean;
  unreadCount: number;
  mentionCount: number;
  serverId: string | null;
}

interface UnreadStore {

  channels: Record<string, ChannelUnread>;
}

export const useUnreadStore = create<UnreadStore>(() => ({ channels: {} }));

export const unreadActions = {

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

  set(state: UnreadState) {
    useUnreadStore.setState((s) => ({
      channels: {
        ...s.channels,
        [state.channelId]: {
          serverId: state.serverId,
          unread: state.unread,
          unreadCount: state.unreadCount,
          mentionCount: state.mentionCount,
        },
      },
    }));
  },


  clear(channelId: string) {
    useUnreadStore.setState((s) => {
      if (!s.channels[channelId]) return s;
      const next = { ...s.channels };
      delete next[channelId];
      return { channels: next };
    });
  },
};


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


export function useDmUnreadTotal(): number {
  return useUnreadStore((s) => {
    let total = 0;
    for (const c of Object.values(s.channels)) {
      if (c.serverId === null) total += c.unreadCount;
    }
    return Math.min(total, UNREAD_COUNT_CAP);
  });
}
