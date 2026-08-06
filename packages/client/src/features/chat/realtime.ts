import type { QueryClient } from '@tanstack/react-query';
import type {
  Channel,
  Conversation,
  Friend,
  Role,
  Server,
  ServerMember,
  User,
} from '@orangchat/shared';
import { socket } from '../../lib/socket';
import { useAuthStore } from '../../stores/auth';
import {
  appendMessage,
  applyReaction,
  removeMessage,
  replaceMessage,
  setMessagePinnedInCache,
} from '../messages/queries';
import { serverKeys } from '../servers/queries';
import { applyEventBroadcast, removeEvent, setEventInterestCount } from '../events/queries';
import { getServerNotificationPrefs } from '../servers/notificationPrefs';
import type { ServerDetail } from '../servers/api';
import {
  dmKeys,
  replaceConversationLatest,
  setConversationLatest,
  touchConversation,
} from '../dms/queries';
import {
  addFriend,
  addRequest,
  friendKeys,
  removeFriendFromCache,
  removeRequest,
  removeRequestByUser,
  updateFriendUser,
} from '../friends/queries';
import { playSoundboardClip } from '../soundboard/playback';
import { useVoiceStore, voiceActions } from '../voice/store';
import { callActions } from '../voice/callStore';
import { unreadActions } from '../../stores/unread';
import { getActiveChannel } from '../unread/active';
import { markChannelRead } from '../unread/api';
import {
  clearConversationNotifications,
  messagePreviewsEnabled,
  notify,
} from '../../lib/notifications';
import { maybeNotifyFriendOnline } from './friendPresence';
import { bumpTyping, clearTyping } from './typing';
import { matchPendingLocalId, registerMessageOutbox, resolvePending } from './outbox';
import { decryptMessage, forgetDecrypted } from '../e2ee/decrypt';
import { syncEpochKeys } from '../e2ee/conversation';
import { noteEpoch, refreshChannelState, useE2eeStore } from '../e2ee/store';

let registered = false;

/**
 * A device joining or leaving any account we share a conversation with
 * invalidates the current epoch for those conversations: the key would be
 * readable by a device that should no longer have it, or unreadable by one that
 * should. Refreshing state is enough - the next send mints the new epoch.
 */
async function onDeviceGraphChanged(client: QueryClient, userId: string): Promise<void> {
  void client;
  void userId;
  const channels = Object.keys(useE2eeStore.getState().channels);
  await Promise.all(channels.map((id) => refreshChannelState(id).catch(() => undefined)));
}

/**
 * Wire server→client socket events into the TanStack Query caches. Called once
 * at startup; idempotent so StrictMode/HMR can't double-subscribe.
 */
export function registerRealtime(client: QueryClient): void {
  if (registered) return;
  registered = true;
  registerMessageOutbox(async (message) => {
    const decrypted = await decryptMessage(message.channelId, message);
    appendMessage(client, decrypted);
    setConversationLatest(client, decrypted);
  });

  const selfId = () => useAuthStore.getState().user?.id;

  const updateDetail = (serverId: string, fn: (detail: ServerDetail) => ServerDetail) => {
    client.setQueryData<ServerDetail>(serverKeys.detail(serverId), (detail) =>
      detail ? fn(detail) : detail,
    );
  };

  /** True when the channel belongs to a cached server (not a DM). */
  const isServerChannel = (channelId: string) => {
    const details = client.getQueriesData<ServerDetail>({ queryKey: ['server'] });
    return details.some(([, d]) => d?.channels.some((c) => c.id === channelId));
  };

  // ── Messages ──────────────────────────────────────────
  socket.on('message:new', (message) => {
    // Matched before the open: the row has to be stamped with the local id it
    // replaces, so it renders under the same key and the pending styling simply
    // falls away instead of the row being swapped out.
    const localId = matchPendingLocalId(message);
    void decryptMessage(message.channelId, message).then((decrypted) => {
      appendMessage(client, localId ? { ...decrypted, clientId: localId } : decrypted);
      if (!isServerChannel(message.channelId)) setConversationLatest(client, decrypted);
      // Do not remove the optimistic plaintext until its confirmed replacement
      // is renderable. Otherwise a slower E2EE open leaves a blank gap.
      if (localId) resolvePending(localId);
    });
    // Their message landed - stop showing them as typing.
    clearTyping(message.channelId, message.author.id);
    // DM traffic reorders the conversation list.
    if (!isServerChannel(message.channelId)) {
      touchConversation(client, message.channelId, message.createdAt);
    }
  });

  // Unread dots, mention badges, and notifications. Delivered via server/user
  // rooms so it fires even for channels the user isn't actively viewing.
  socket.on('unread:activity', (p) => {
    const me = selfId();
    if (!me || p.authorId === me) return;
    const mentioned = p.mentions.includes(me);

    // Already looking at it (and the tab is focused) - keep it read.
    if (getActiveChannel() === p.channelId && !document.hidden) {
      void markChannelRead(p.channelId).catch(() => {});
      return;
    }

    unreadActions.bump(p.channelId, p.serverId, mentioned);

    // DMs always alert. A server alerts per its own notification setting, which
    // defaults to @mentions only - and a muted server never does.
    const isDm = p.serverId === null;
    const prefs = isDm ? null : getServerNotificationPrefs(p.serverId!);
    const shouldNotify = isDm
      ? true
      : prefs!.mutedUntil === null &&
        (prefs!.level === 'all' || (prefs!.level === 'mentions' && mentioned));

    if (shouldNotify) {
      notify({
        title: isDm
          ? p.author.displayName
          : mentioned
            ? `${p.author.displayName} mentioned you`
            : p.author.displayName,
        // The title already names the author, so the withheld body says only
        // that there is something to read. Withholding it here matters as much
        // as in the worker: this is the path that fires while a tab is open,
        // and a shade that stays quiet only when the app is closed is not a
        // setting anyone asked for.
        body: messagePreviewsEnabled() ? p.preview.slice(0, 140) : 'New message',
        icon: p.author.avatarUrl ?? undefined,
        href: isDm ? `/dms/${p.channelId}` : `/servers/${p.serverId}/channels/${p.channelId}`,
        tag: p.channelId,
      });
    }
  });

  socket.on('unread:state', (state) => unreadActions.set(state));

  socket.on('event:created', (event) => applyEventBroadcast(client, event));
  socket.on('event:updated', (event) => applyEventBroadcast(client, event));
  socket.on('event:interest', ({ serverId, eventId, interestedCount }) =>
    setEventInterestCount(client, serverId, eventId, interestedCount),
  );
  socket.on('event:deleted', ({ serverId, eventId }) => removeEvent(client, serverId, eventId));

  socket.on('read:state', ({ channelId }) => {
    unreadActions.clear(channelId);
    void clearConversationNotifications(channelId).catch(() => {});
  });

  socket.on('message:updated', (message) => {
    forgetDecrypted(message.id);
    void decryptMessage(message.channelId, message).then((decrypted) => {
      replaceMessage(client, decrypted);
      replaceConversationLatest(client, decrypted);
    });
  });

  socket.on('e2ee:epoch', ({ channelId, epoch }) => {
    noteEpoch(channelId, epoch.epoch);
    void syncEpochKeys(channelId).catch(() => {});
  });

  // A device appearing or leaving an account changes who a conversation key may
  // be wrapped to, so the next send has to rotate rather than reuse the epoch.
  socket.on('e2ee:device:added', ({ userId }) => void onDeviceGraphChanged(client, userId));
  socket.on('e2ee:device:revoked', ({ userId }) => void onDeviceGraphChanged(client, userId));

  socket.on('message:deleted', ({ channelId, messageId }) => {
    removeMessage(client, channelId, messageId);
    void client.invalidateQueries({ queryKey: dmKeys.list });
  });

  socket.on('message:pins', ({ channelId, messageId, pinned }) =>
    setMessagePinnedInCache(client, channelId, messageId, pinned),
  );

  socket.on('reaction', (payload) =>
    applyReaction(client, { ...payload, isSelf: payload.userId === selfId() }),
  );

  socket.on('typing', ({ channelId, userId }) => {
    if (userId !== selfId()) bumpTyping(channelId, userId);
  });

  // ── Presence ──────────────────────────────────────────

  socket.on('presence', ({ userId, status, devices, activities }) => {
    maybeNotifyFriendOnline(client, userId, status);
    if (userId === selfId()) {
      useAuthStore.getState().user &&
        useAuthStore.setState((state) => ({
          user: state.user ? { ...state.user, status, devices, activities } : null,
        }));
    }
    // Update the member row in every cached server detail.
    const details = client.getQueriesData<ServerDetail>({ queryKey: ['server'] });
    for (const [key, detail] of details) {
      if (!detail?.members.some((m) => m.userId === userId)) continue;
      client.setQueryData<ServerDetail>(key, {
        ...detail,
        members: detail.members.map((m) =>
          m.userId === userId ? { ...m, user: { ...m.user, status, devices, activities } } : m,
        ),
      });
    }

    // Keep conversation avatars in sync with live presence as well.
    client.setQueryData<Conversation[]>(dmKeys.list, (list) =>
      list?.map((conversation) => ({
        ...conversation,
        participants: conversation.participants.map((participant) =>
          participant.id === userId ? { ...participant, status, devices, activities } : participant,
        ),
      })),
    );

    client.setQueryData<Friend[]>(friendKeys.list, (list) =>
      list?.map((friend) =>
        friend.user.id === userId
          ? { ...friend, user: { ...friend.user, status, devices, activities } }
          : friend,
      ),
    );
  });

  // ── Channels ──────────────────────────────────────────
  const upsertChannel = (channel: Channel) => {
    if (!channel.serverId) {
      // DM background changes (http::channels PUT /background) arrive here;
      // conversations have no other editable field, so this is the whole update.
      client.setQueryData<Conversation[]>(dmKeys.list, (list) =>
        list?.map((c) =>
          c.id === channel.id ? { ...c, backgroundUrl: channel.backgroundUrl } : c,
        ),
      );
      return;
    }
    updateDetail(channel.serverId, (detail) => {
      const exists = detail.channels.some((c) => c.id === channel.id);
      const channels = exists
        ? detail.channels.map((c) => (c.id === channel.id ? channel : c))
        : [...detail.channels, channel];
      return { ...detail, channels };
    });
  };
  socket.on('channel:created', upsertChannel);
  socket.on('channel:updated', upsertChannel);

  socket.on('channel:deleted', ({ serverId, channelId }) => {
    if (!serverId) return;
    updateDetail(serverId, (detail) => ({
      ...detail,
      channels: detail.channels.filter((c) => c.id !== channelId),
    }));
    client.removeQueries({ queryKey: ['messages', channelId] });
  });

  // ── Membership ────────────────────────────────────────
  socket.on('member:joined', ({ serverId, member }) => {
    updateDetail(serverId, (detail) => {
      const others = detail.members.filter((m: ServerMember) => m.userId !== member.userId);
      return { ...detail, members: [...others, member] };
    });
  });

  socket.on('member:updated', ({ serverId, member }) => {
    updateDetail(serverId, (detail) => ({
      ...detail,
      members: detail.members.map((m) =>
        m.userId === member.userId
          ? {
              ...member,
              user: {
                ...member.user,
                status: m.user.status,
                devices: m.user.devices,
                activities: m.user.activities,
              },
            }
          : m,
      ),
    }));
    // Role changes on ourselves change what the UI may show.
    if (member.userId === selfId()) {
      client.invalidateQueries({ queryKey: serverKeys.permissions(serverId) });
    }
  });

  socket.on('member:left', ({ serverId, userId }) => {
    if (userId === selfId()) {
      // We were kicked or banned: drop the server entirely.
      client.setQueryData<Server[]>(serverKeys.list, (list) =>
        list?.filter((s) => s.id !== serverId),
      );
      client.removeQueries({ queryKey: serverKeys.detail(serverId) });
      client.removeQueries({ queryKey: serverKeys.permissions(serverId) });
      return;
    }
    updateDetail(serverId, (detail) => ({
      ...detail,
      members: detail.members.filter((m) => m.userId !== userId),
    }));
  });

  // ── Roles ─────────────────────────────────────────────
  const upsertRole = (role: Role) => {
    updateDetail(role.serverId, (detail) => {
      const exists = detail.roles.some((r) => r.id === role.id);
      const roles = exists
        ? detail.roles.map((r) => (r.id === role.id ? role : r))
        : [...detail.roles, role];
      return { ...detail, roles };
    });
    // A permissions edit on any of our roles changes our effective bitfield.
    client.invalidateQueries({ queryKey: serverKeys.permissions(role.serverId) });
  };
  socket.on('role:created', upsertRole);
  socket.on('role:updated', upsertRole);

  socket.on('role:deleted', ({ serverId, roleId }) => {
    updateDetail(serverId, (detail) => ({
      ...detail,
      roles: detail.roles.filter((r) => r.id !== roleId),
      members: detail.members.map((m) => ({
        ...m,
        roleIds: m.roleIds.filter((id) => id !== roleId),
      })),
    }));
    client.invalidateQueries({ queryKey: serverKeys.permissions(serverId) });
  });

  // ── Servers ───────────────────────────────────────────
  socket.on('server:updated', (server) => {
    client.setQueryData<Server[]>(serverKeys.list, (list) =>
      list?.map((s) => (s.id === server.id ? server : s)),
    );
    updateDetail(server.id, (detail) => ({ ...detail, server }));
  });

  socket.on('server:deleted', ({ serverId }) => {
    client.setQueryData<Server[]>(serverKeys.list, (list) =>
      list?.filter((s) => s.id !== serverId),
    );
    client.removeQueries({ queryKey: serverKeys.detail(serverId) });
    client.removeQueries({ queryKey: serverKeys.permissions(serverId) });
  });

  // ── User profiles ─────────────────────────────────────
  socket.on('user:updated', (user: User) => {
    const details = client.getQueriesData<ServerDetail>({ queryKey: ['server'] });
    for (const [key, detail] of details) {
      if (!detail?.members.some((m) => m.userId === user.id)) continue;
      client.setQueryData<ServerDetail>(key, {
        ...detail,
        members: detail.members.map((m) =>
          m.userId === user.id
            ? {
                ...m,
                user: {
                  ...user,
                  status: m.user.status,
                  devices: m.user.devices,
                  activities: m.user.activities,
                },
              }
            : m,
        ),
      });
    }
    client.setQueryData<Conversation[]>(dmKeys.list, (list) =>
      list?.map((c) => ({
        ...c,
        participants: c.participants.map((p) =>
          p.id === user.id
            ? { ...user, status: p.status, devices: p.devices, activities: p.activities }
            : p,
        ),
      })),
    );
    updateFriendUser(client, user);
  });

  // ── Friends ───────────────────────────────────────────
  socket.on('friend:request', (request) => {
    addRequest(client, request);
    if (useAuthStore.getState().user?.notifyFriendRequests) {
      notify({
        title: 'New friend request',
        body: `${request.user.displayName} wants to be friends.`,
        icon: request.user.avatarUrl ?? undefined,
        href: '/friends',
        tag: `friend-request:${request.user.id}`,
      });
    }
  });
  socket.on('friend:accepted', (friend) => {
    addFriend(client, friend);
    // Whichever pending request this resolved is now gone.
    removeRequestByUser(client, friend.user.id);
    if (useAuthStore.getState().user?.notifyFriendAccepted) {
      notify({
        title: 'Friend request accepted',
        body: `${friend.user.displayName} is now your friend.`,
        icon: friend.user.avatarUrl ?? undefined,
        href: '/friends',
        tag: `friend-accepted:${friend.user.id}`,
      });
    }
  });
  socket.on('friend:request:removed', ({ id }) => removeRequest(client, id));
  socket.on('friend:removed', ({ userId }) => removeFriendFromCache(client, userId));

  // ── Voice ─────────────────────────────────────────────
  socket.on('voice:state', (payload) => voiceActions.applyVoiceState(payload));
  socket.on('voice:devices', (payload) => voiceActions.applyDevices(payload));
  // Another of our devices hung this one up. Silent: the person did this on
  // purpose, from a screen they are looking at, and does not need telling.
  socket.on('voice:force:leave', () => void voiceActions.leave({ silent: true }));
  socket.on('soundboard:played', (payload) =>
    playSoundboardClip(payload, useVoiceStore.getState().session),
  );

  // ── DM calls ──────────────────────────────────────────
  socket.on('dm:call:ringing', (payload) => {
    // The server only rings other people, but guard anyway so a stray echo of
    // our own call cannot pop a dialog at us.
    if (payload.callerId === selfId()) return;
    callActions.applyRinging(payload);
    notify({
      title: `Incoming ${payload.video ? 'video' : 'voice'} call`,
      body: `${payload.caller.displayName} is calling`,
      icon: payload.caller.avatarUrl ?? undefined,
      href: `/dms/${payload.channelId}`,
      tag: `call:${payload.channelId}`,
    });
  });
  socket.on('dm:call:accepted', (payload) => callActions.applyAccepted(payload));
  socket.on('dm:call:ended', (payload) => callActions.applyEnded(payload, selfId()));

  // After any (re)connect, data may have changed while we were offline.
  socket.io.on('reconnect', () => {
    client.invalidateQueries({ queryKey: ['server'] });
    client.invalidateQueries({ queryKey: serverKeys.list });
    client.invalidateQueries({ queryKey: ['messages'] });
    client.invalidateQueries({ queryKey: dmKeys.list });
    client.invalidateQueries({ queryKey: ['friends'] });
    voiceActions.restoreAfterReconnect();
    callActions.resetAfterReconnect();
  });
}
