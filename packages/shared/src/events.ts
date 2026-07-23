import type {
  Message,
  ServerMember,
  PresenceStatus,
  PresenceDevice,
  UserActivity,
  Channel,
  ChannelOverwrite,
  Role,
  Server,
  User,
  Friend,
  FriendRequest,
} from './types.js';
export type Ack<T = void> = (
  response: { ok: true; data: T } | { ok: false; error: string },
) => void;

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
  devices: PresenceDevice[];
  activities: UserActivity[];
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

export interface VoiceDevicePayload {
  sessionId: string;
  channelId: string;
}

export interface SoundboardPlayedPayload {
  channelId: string;
  soundId: string;
  /** Who pressed it. */
  userId: string;
  url: string;
  /** 0..1, set by whoever manages the board. */
  volume: number;
}

export interface DmCallPayload {
  channelId: string;
  callerId: string;
  caller: User;
  ringing: string[];
  participants: string[];
  video: boolean;
  startedAt: string;
}

export interface DmCallEndedPayload {
  channelId: string;
  userId: string;
  reason: 'declined' | 'cancelled' | 'ended' | 'timeout' | 'busy';
  /** true when the call itself is finished.*/
  callOver: boolean;
}

export interface MemberEventPayload {
  serverId: string;
  member: ServerMember;
}

export interface UnreadActivityPayload {
  channelId: string;
  serverId: string | null;
  authorId: string;
  mentions: string[];
  preview: string;
  author: User;
}

export interface ErrorPayload {
  code: string;
  message: string;
}

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
  'roles:reordered': (payload: { serverId: string; roles: Role[] }) => void;
  'channel:created': (channel: Channel) => void;
  'channel:updated': (channel: Channel) => void;
  'channel:deleted': (payload: { serverId: string | null; channelId: string }) => void;
  'channels:reordered': (payload: { serverId: string; channels: Channel[] }) => void;
  'message:pins': (payload: {
    channelId: string;
    messageId: string;
    pinned: boolean;
  }) => void;
  'channel:overwrites': (payload: { channelId: string; overwrites: ChannelOverwrite[] }) => void;
  'server:updated': (server: Server) => void;
  'server:deleted': (payload: { serverId: string }) => void;
  'user:updated': (user: User) => void;
  'friend:request': (request: FriendRequest) => void;
  'friend:accepted': (friend: Friend) => void;
  'friend:request:removed': (payload: { id: string }) => void;
  'friend:removed': (payload: { userId: string }) => void;
  'voice:state': (payload: VoiceStatePayload) => void;
  'voice:devices': (payload: VoiceDevicePayload[]) => void;
  'voice:force:leave': () => void;
  'soundboard:played': (payload: SoundboardPlayedPayload) => void;
  'dm:call:ringing': (payload: DmCallPayload) => void;
  'dm:call:accepted': (payload: DmCallPayload) => void;
  'dm:call:ended': (payload: DmCallEndedPayload) => void;
  'unread:activity': (payload: UnreadActivityPayload) => void;
  'read:state': (payload: { channelId: string }) => void;
  error: (payload: ErrorPayload) => void;
}

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
  'voice:device:disconnect': (sessionId: string, ack?: Ack) => void;
  'soundboard:play': (payload: { channelId: string; soundId: string }, ack?: Ack) => void;
  'dm:call:start': (
    payload: { channelId: string; video?: boolean },
    ack?: Ack<DmCallPayload>,
  ) => void;
  'dm:call:respond': (
    payload: { channelId: string; accept: boolean },
    ack?: Ack<DmCallPayload>,
  ) => void;
  'dm:call:cancel': (channelId: string, ack?: Ack) => void;
  'dm:call:end': (channelId: string, ack?: Ack) => void;
}

export interface SocketData {
  userId: string;
  username: string;
}

export interface InterServerEvents {
  ping: () => void;
}