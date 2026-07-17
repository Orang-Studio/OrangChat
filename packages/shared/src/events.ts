import type {
  Message,
  ServerMember,
  PresenceStatus,
  Channel,
  ChannelOverwrite,
  Role,
  Server,
  User,
  Friend,
  FriendRequest,
} from './types.js';

/**
 * The typed Socket.IO contract. Both sides import these:
 *   Server: new Server<ClientToServerEvents, ServerToClientEvents>()
 *   Client: io<ServerToClientEvents, ClientToServerEvents>()
 * so event names and payloads are checked at compile time on both ends.
 */

/** Standard acknowledgement callback shape for client→server actions. */
export type Ack<T = void> = (
  response: { ok: true; data: T } | { ok: false; error: string },
) => void;

// ── Payloads: server → client ───────────────────────────
export interface MessageDeletedPayload {
  channelId: string;
  messageId: string;
}

export interface TypingPayload {
  channelId: string;
  userId: string;
}

export interface PresencePayload {
  userId: string;
  status: PresenceStatus;
}

export interface ReactionPayload {
  channelId: string;
  messageId: string;
  emoji: string;
  userId: string;
  /** true = added, false = removed */
  added: boolean;
}

export interface VoiceStatePayload {
  channelId: string;
  userId: string;
  joined: boolean;
  muted: boolean;
  deafened: boolean;
  video: boolean;
  screenSharing: boolean;
}

/**
 * One of your own devices sitting in a voice channel.
 *
 * `sessionId` is that device's socket id, so a client tells its own entry from
 * the others by comparing against its live socket id — which is also why this is
 * only ever sent to the user it describes.
 */
export interface VoiceDevicePayload {
  sessionId: string;
  channelId: string;
}

/**
 * A soundboard clip firing in a voice channel.
 *
 * Carries the url and volume rather than only an id: the clip has to start the
 * moment it lands, and a fetch first would make every punchline late. It also
 * means a sound deleted mid-play still finishes for the people who heard it.
 */
export interface SoundboardPlayedPayload {
  channelId: string;
  soundId: string;
  /** Who pressed it. */
  userId: string;
  url: string;
  /** 0..1, set by whoever manages the board. */
  volume: number;
}

/**
 * A call on a DM or group-DM channel, and its live roster.
 *
 * The channel's participant set decides who rings, so one shape covers both 1:1
 * and group calls — there is no single callee. Media rides the same LiveKit room
 * (`voice_<channelId>`) that voice channels use; these events are signalling
 * only, so a client still emits `voice:join` after accepting.
 */
export interface DmCallPayload {
  channelId: string;
  callerId: string;
  /** Denormalised so an incoming-call popup renders without a follow-up fetch. */
  caller: User;
  /** Users still being rung. Disjoint from `participants`. */
  ringing: string[];
  /** Users connected to the call right now. */
  participants: string[];
  /** The caller opened with camera on; callees may still answer audio-only. */
  video: boolean;
  startedAt: string;
}

/**
 * One user left a call — declined, hung up, never answered, or was already busy
 * elsewhere. Sent per-user rather than per-call, so a group call survives one
 * person declining; `callOver` marks the last one out.
 */
export interface DmCallEndedPayload {
  channelId: string;
  userId: string;
  reason: 'declined' | 'cancelled' | 'ended' | 'timeout' | 'busy';
  /** True when the call itself is finished, not just this user's part in it. */
  callOver: boolean;
}

export interface MemberEventPayload {
  serverId: string;
  member: ServerMember;
}

/**
 * A new message landed in a channel the recipient belongs to. Delivered via the
 * server:<id> / user:<id> rooms (which clients never leave) so background
 * channels can update unread + mention badges. `mentions` lists the user ids
 * this message @mentioned.
 */
export interface UnreadActivityPayload {
  channelId: string;
  serverId: string | null;
  authorId: string;
  mentions: string[];
  /** Message text, for notification bodies. */
  preview: string;
  author: User;
}

export interface ErrorPayload {
  code: string;
  message: string;
}

// ── Events the server emits to clients ──────────────────
export interface ServerToClientEvents {
  'message:new': (message: Message) => void;
  'message:updated': (message: Message) => void;
  'message:deleted': (payload: MessageDeletedPayload) => void;
  typing: (payload: TypingPayload) => void;
  presence: (payload: PresencePayload) => void;
  reaction: (payload: ReactionPayload) => void;
  'member:joined': (payload: MemberEventPayload) => void;
  'member:updated': (payload: MemberEventPayload) => void;
  'member:left': (payload: { serverId: string; userId: string }) => void;
  'role:created': (role: Role) => void;
  'role:updated': (role: Role) => void;
  'role:deleted': (payload: { serverId: string; roleId: string }) => void;
  /** Bulk reorder; carries every role in the server, highest position first. */
  'roles:reordered': (payload: { serverId: string; roles: Role[] }) => void;
  'channel:created': (channel: Channel) => void;
  'channel:updated': (channel: Channel) => void;
  'channel:deleted': (payload: { serverId: string | null; channelId: string }) => void;
  /** Bulk reorder/re-parent; carries every channel in the server, by position. */
  'channels:reordered': (payload: { serverId: string; channels: Channel[] }) => void;
  /** A message was pinned or unpinned; re-fetch GET /channels/:id/pins to refresh. */
  'message:pins': (payload: {
    channelId: string;
    messageId: string;
    pinned: boolean;
  }) => void;
  'channel:overwrites': (payload: { channelId: string; overwrites: ChannelOverwrite[] }) => void;
  'server:updated': (server: Server) => void;
  'server:deleted': (payload: { serverId: string }) => void;
  /** A user's public profile changed; fanned out to servers they share. */
  'user:updated': (user: User) => void;
  /** An inbound friend request arrived. */
  'friend:request': (request: FriendRequest) => void;
  /** A friend request you were part of was accepted (you are now friends). */
  'friend:accepted': (friend: Friend) => void;
  /** A pending request (in/outbound) was declined or cancelled. */
  'friend:request:removed': (payload: { id: string }) => void;
  /** A friend removed you (or you removed them, echoed). */
  'friend:removed': (payload: { userId: string }) => void;
  'voice:state': (payload: VoiceStatePayload) => void;
  /** Which of *your own* devices are in voice. Sent to your user:<id> room. */
  'voice:devices': (payload: VoiceDevicePayload[]) => void;
  /** Another of your devices hung this one up. Leave, quietly. */
  'voice:force:leave': () => void;
  /** Someone hit the soundboard; everyone in the voice room plays it. */
  'soundboard:played': (payload: SoundboardPlayedPayload) => void;
  /** You are being rung. Delivered to the callee's user:<id> room. */
  'dm:call:ringing': (payload: DmCallPayload) => void;
  /** Someone answered; carries the call's updated roster. */
  'dm:call:accepted': (payload: DmCallPayload) => void;
  /** Someone dropped out, or the call finished outright (`callOver`). */
  'dm:call:ended': (payload: DmCallEndedPayload) => void;
  /** New-message activity for unread dots, mention badges, and notifications. */
  'unread:activity': (payload: UnreadActivityPayload) => void;
  error: (payload: ErrorPayload) => void;
}

// ── Events clients emit to the server ───────────────────
export interface ClientToServerEvents {
  'channel:join': (channelId: string, ack?: Ack) => void;
  'channel:leave': (channelId: string, ack?: Ack) => void;
  'message:send': (
    payload: {
      channelId: string;
      content: string;
      replyToId?: string;
      attachmentIds?: string[];
      spoilerAttachmentIds?: string[];
    },
    ack?: Ack<Message>,
  ) => void;
  'message:edit': (
    payload: { channelId: string; messageId: string; content: string },
    ack?: Ack<Message>,
  ) => void;
  'message:delete': (
    payload: { channelId: string; messageId: string },
    ack?: Ack,
  ) => void;
  'typing:start': (channelId: string) => void;
  'reaction:add': (payload: { channelId: string; messageId: string; emoji: string }) => void;
  'reaction:remove': (payload: { channelId: string; messageId: string; emoji: string }) => void;
  'presence:update': (status: PresenceStatus) => void;
  'voice:join': (channelId: string, ack?: Ack<{ token: string; url: string }>) => void;
  'voice:leave': (channelId: string, ack?: Ack) => void;
  'voice:update': (
    payload: {
      channelId: string;
      muted?: boolean;
      deafened?: boolean;
      video?: boolean;
      screenSharing?: boolean;
    },
    ack?: Ack,
  ) => void;
  /** Hang up one of your *other* devices, by its sessionId. */
  'voice:device:disconnect': (sessionId: string, ack?: Ack) => void;
  /** Play a soundboard clip to everyone in a server voice channel you are in. */
  'soundboard:play': (payload: { channelId: string; soundId: string }, ack?: Ack) => void;
  /** Ring every other participant of a dm/group_dm channel. */
  'dm:call:start': (
    payload: { channelId: string; video?: boolean },
    ack?: Ack<DmCallPayload>,
  ) => void;
  /** Answer or decline a call you are being rung for. */
  'dm:call:respond': (
    payload: { channelId: string; accept: boolean },
    ack?: Ack<DmCallPayload>,
  ) => void;
  /** Give up on a call that is still ringing (caller side). */
  'dm:call:cancel': (channelId: string, ack?: Ack) => void;
  /** Hang up a call you are connected to. */
  'dm:call:end': (channelId: string, ack?: Ack) => void;
}

/** Per-socket data attached server-side after auth (Socket.IO `socket.data`). */
export interface SocketData {
  userId: string;
  username: string;
}

/** Inter-server events (Redis adapter). Empty for now; reserved for typing. */
export interface InterServerEvents {
  ping: () => void;
}
