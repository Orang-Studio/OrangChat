import type { QueryClient } from "@tanstack/react-query";
import type { Channel, Conversation, Role, Server, ServerMember, User } from "@orangchat/shared";
import { socket } from "../../lib/socket";
import { useAuthStore } from "../../stores/auth";
import {
  appendMessage,
  applyReaction,
  removeMessage,
  replaceMessage,
} from "../messages/queries";
import { serverKeys } from "../servers/queries";
import type { ServerDetail } from "../servers/api";
import { dmKeys, touchConversation } from "../dms/queries";
import {
  addFriend,
  addRequest,
  removeFriendFromCache,
  removeRequest,
  removeRequestByUser,
  updateFriendUser,
} from "../friends/queries";
import { playSoundboardClip } from "../soundboard/playback";
import { useVoiceStore, voiceActions } from "../voice/store";
import { callActions } from "../voice/callStore";
import { unreadActions } from "../../stores/unread";
import { getActiveChannel } from "../unread/active";
import { markChannelRead } from "../unread/api";
import { notify } from "../../lib/notifications";
import { bumpTyping, clearTyping } from "./typing";

let registered = false;

/**
 * Wire server→client socket events into the TanStack Query caches. Called once
 * at startup; idempotent so StrictMode/HMR can't double-subscribe.
 */
export function registerRealtime(client: QueryClient): void {
  if (registered) return;
  registered = true;

  const selfId = () => useAuthStore.getState().user?.id;

  const updateDetail = (
    serverId: string,
    fn: (detail: ServerDetail) => ServerDetail,
  ) => {
    client.setQueryData<ServerDetail>(serverKeys.detail(serverId), (detail) =>
      detail ? fn(detail) : detail,
    );
  };

  /** True when the channel belongs to a cached server (not a DM). */
  const isServerChannel = (channelId: string) => {
    const details = client.getQueriesData<ServerDetail>({ queryKey: ["server"] });
    return details.some(([, d]) => d?.channels.some((c) => c.id === channelId));
  };

  // ── Messages ──────────────────────────────────────────
  socket.on("message:new", (message) => {
    appendMessage(client, message);
    // Their message landed - stop showing them as typing.
    clearTyping(message.channelId, message.author.id);
    // DM traffic reorders the conversation list.
    if (!isServerChannel(message.channelId)) {
      touchConversation(client, message.channelId, message.createdAt);
    }
  });

  // Unread dots, mention badges, and notifications. Delivered via server/user
  // rooms so it fires even for channels the user isn't actively viewing.
  socket.on("unread:activity", (p) => {
    const me = selfId();
    if (!me || p.authorId === me) return;
    const mentioned = p.mentions.includes(me);

    // Already looking at it (and the tab is focused) - keep it read.
    if (getActiveChannel() === p.channelId && !document.hidden) {
      void markChannelRead(p.channelId).catch(() => {});
      return;
    }

    unreadActions.bump(p.channelId, p.serverId, mentioned);

    // Notify only for DMs and @mentions - never for every server message.
    const isDm = p.serverId === null;
    if (isDm || mentioned) {
      notify({
        title: isDm ? p.author.displayName : `${p.author.displayName} mentioned you`,
        body: p.preview.slice(0, 140),
        icon: p.author.avatarUrl ?? undefined,
        href: isDm
          ? `/dms/${p.channelId}`
          : `/servers/${p.serverId}/channels/${p.channelId}`,
        tag: p.channelId,
      });
    }
  });

  socket.on("message:updated", (message) => replaceMessage(client, message));

  socket.on("message:deleted", ({ channelId, messageId }) =>
    removeMessage(client, channelId, messageId),
  );

  socket.on("reaction", (payload) =>
    applyReaction(client, { ...payload, isSelf: payload.userId === selfId() }),
  );

  socket.on("typing", ({ channelId, userId }) => {
    if (userId !== selfId()) bumpTyping(channelId, userId);
  });

  // ── Presence ──────────────────────────────────────────
  socket.on("presence", ({ userId, status }) => {
    // Update the member row in every cached server detail.
    const details = client.getQueriesData<ServerDetail>({ queryKey: ["server"] });
    for (const [key, detail] of details) {
      if (!detail?.members.some((m) => m.userId === userId)) continue;
      client.setQueryData<ServerDetail>(key, {
        ...detail,
        members: detail.members.map((m) =>
          m.userId === userId ? { ...m, user: { ...m.user, status } } : m,
        ),
      });
    }

    // Keep conversation avatars in sync with live presence as well.
    client.setQueryData<Conversation[]>(dmKeys.list, (list) =>
      list?.map((conversation) => ({
        ...conversation,
        participants: conversation.participants.map((participant) =>
          participant.id === userId ? { ...participant, status } : participant,
        ),
      })),
    );
  });

  // ── Channels ──────────────────────────────────────────
  const upsertChannel = (channel: Channel) => {
    if (!channel.serverId) return;
    updateDetail(channel.serverId, (detail) => {
      const exists = detail.channels.some((c) => c.id === channel.id);
      const channels = exists
        ? detail.channels.map((c) => (c.id === channel.id ? channel : c))
        : [...detail.channels, channel];
      return { ...detail, channels };
    });
  };
  socket.on("channel:created", upsertChannel);
  socket.on("channel:updated", upsertChannel);

  socket.on("channel:deleted", ({ serverId, channelId }) => {
    if (!serverId) return;
    updateDetail(serverId, (detail) => ({
      ...detail,
      channels: detail.channels.filter((c) => c.id !== channelId),
    }));
    client.removeQueries({ queryKey: ["messages", channelId] });
  });

  // ── Membership ────────────────────────────────────────
  socket.on("member:joined", ({ serverId, member }) => {
    updateDetail(serverId, (detail) => {
      const others = detail.members.filter(
        (m: ServerMember) => m.userId !== member.userId,
      );
      return { ...detail, members: [...others, member] };
    });
  });

  socket.on("member:updated", ({ serverId, member }) => {
    updateDetail(serverId, (detail) => ({
      ...detail,
      members: detail.members.map((m) => (m.userId === member.userId ? member : m)),
    }));
    // Role changes on ourselves change what the UI may show.
    if (member.userId === selfId()) {
      client.invalidateQueries({ queryKey: serverKeys.permissions(serverId) });
    }
  });

  socket.on("member:left", ({ serverId, userId }) => {
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
  socket.on("role:created", upsertRole);
  socket.on("role:updated", upsertRole);

  socket.on("role:deleted", ({ serverId, roleId }) => {
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
  socket.on("server:updated", (server) => {
    client.setQueryData<Server[]>(serverKeys.list, (list) =>
      list?.map((s) => (s.id === server.id ? server : s)),
    );
    updateDetail(server.id, (detail) => ({ ...detail, server }));
  });

  socket.on("server:deleted", ({ serverId }) => {
    client.setQueryData<Server[]>(serverKeys.list, (list) =>
      list?.filter((s) => s.id !== serverId),
    );
    client.removeQueries({ queryKey: serverKeys.detail(serverId) });
    client.removeQueries({ queryKey: serverKeys.permissions(serverId) });
  });

  // ── User profiles ─────────────────────────────────────
  socket.on("user:updated", (user: User) => {
    const details = client.getQueriesData<ServerDetail>({ queryKey: ["server"] });
    for (const [key, detail] of details) {
      if (!detail?.members.some((m) => m.userId === user.id)) continue;
      client.setQueryData<ServerDetail>(key, {
        ...detail,
        members: detail.members.map((m) =>
          m.userId === user.id ? { ...m, user: { ...user, status: m.user.status } } : m,
        ),
      });
    }
    client.setQueryData<Conversation[]>(dmKeys.list, (list) =>
      list?.map((c) => ({
        ...c,
        participants: c.participants.map((p) => (p.id === user.id ? user : p)),
      })),
    );
    updateFriendUser(client, user);
  });

  // ── Friends ───────────────────────────────────────────
  socket.on("friend:request", (request) => addRequest(client, request));
  socket.on("friend:accepted", (friend) => {
    addFriend(client, friend);
    // Whichever pending request this resolved is now gone.
    removeRequestByUser(client, friend.user.id);
  });
  socket.on("friend:request:removed", ({ id }) => removeRequest(client, id));
  socket.on("friend:removed", ({ userId }) => removeFriendFromCache(client, userId));

  // ── Voice ─────────────────────────────────────────────
  socket.on("voice:state", (payload) => voiceActions.applyVoiceState(payload));
  socket.on("voice:devices", (payload) => voiceActions.applyDevices(payload));
  // Another of our devices hung this one up. Silent: the person did this on
  // purpose, from a screen they are looking at, and does not need telling.
  socket.on("voice:force:leave", () => void voiceActions.leave({ silent: true }));
  socket.on("soundboard:played", (payload) =>
    playSoundboardClip(payload, useVoiceStore.getState().session),
  );

  // ── DM calls ──────────────────────────────────────────
  socket.on("dm:call:ringing", (payload) => {
    // The server only rings other people, but guard anyway so a stray echo of
    // our own call cannot pop a dialog at us.
    if (payload.callerId === selfId()) return;
    callActions.applyRinging(payload);
    notify({
      title: `Incoming ${payload.video ? "video" : "voice"} call`,
      body: `${payload.caller.displayName} is calling`,
      icon: payload.caller.avatarUrl ?? undefined,
      href: `/dms/${payload.channelId}`,
      tag: `call:${payload.channelId}`,
    });
  });
  socket.on("dm:call:accepted", (payload) => callActions.applyAccepted(payload));
  socket.on("dm:call:ended", (payload) => callActions.applyEnded(payload, selfId()));

  // After any (re)connect, data may have changed while we were offline.
  socket.io.on("reconnect", () => {
    client.invalidateQueries({ queryKey: ["server"] });
    client.invalidateQueries({ queryKey: serverKeys.list });
    client.invalidateQueries({ queryKey: ["messages"] });
    client.invalidateQueries({ queryKey: dmKeys.list });
    client.invalidateQueries({ queryKey: ["friends"] });
    voiceActions.restoreAfterReconnect();
    callActions.resetAfterReconnect();
  });
}
