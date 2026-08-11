import type { BadgeId } from './badges.js';
import type { DeviceLogKind, E2eePlatform } from './e2ee.js';

export type ChannelType = 'text' | 'voice' | 'category' | 'dm' | 'group_dm';
export type PresenceStatus = 'online' | 'idle' | 'dnd' | 'offline';
export type PresenceDevice = 'mobile' | 'browser' | 'desktop';
export type ActivityKind = 'game' | 'spotify';
export interface UserActivity {
  kind: ActivityKind;
  name: string;
  details: string | null;
  url: string | null;
  imageUrl: string | null;
  startedAt: string | null;
  endsAt: string | null;
}
export type OAuthProvider = 'google' | 'discord';


export type DmPrivacy = 'everyone' | 'friends' | 'none';

export type FriendRequestPrivacy = 'everyone' | 'mutual' | 'none';

export interface User {
  id: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  status: PresenceStatus;

  devices: PresenceDevice[];

  activities: UserActivity[];

  bio: string | null;

  bannerUrl: string | null;

  accentColor: number | null;

  pronouns: string | null;

  profileCss: string | null;

  badges: BadgeId[];

  bot?: boolean;
  createdAt: string;
}


export interface SelfUser extends User {
  email: string;

  customCss: string | null;

  appIconUrl: string | null;
  dmPrivacy: DmPrivacy;
  friendRequestPrivacy: FriendRequestPrivacy;
  typingIndicators: boolean;

  notifyFriendRequests: boolean;
  notifyFriendAccepted: boolean;
  notifyFriendOnline: boolean;

  e2eeStrict: boolean;

  gameActivity: boolean;

  twoFactorEnabled: boolean;

  hasPassword: boolean;

  lockdown: boolean;
}


export interface StandingEntry {
  kind: 'ban' | 'timeout';
  serverId: string;
  serverName: string;
  reason: string | null;

  expiresAt: string | null;
  createdAt: string | null;
}


export interface AccountStanding {

  good: boolean;
  entries: StandingEntry[];
}


export interface DeviceSession {
  id: string;

  current: boolean;

  userAgent: string | null;
  ip: string | null;
  createdAt: string | null;
  lastSeenAt: string | null;
}

export interface E2eeDevice {
  id: string;
  userId: string;
  name: string;
  platform: E2eePlatform;
  ikSigPub: string;
  ikDhPub: string;
  bundleSig: string;
  authorizedBy: string | null;
  authorizationSig: string | null;
  createdAt: string;
  lastSeenAt: string;
  revokedAt: string | null;
}

export interface E2eeLogEntry {
  seq: number;
  kind: DeviceLogKind;
  payload: string;
  entryHash: string;
  prevHash: string | null;
  signature: string;
  createdAt: string;
}

export interface E2eeLogHead {
  seq: number;
  entryHash: string;
}

export interface E2eeDeviceList {
  userId: string;
  devices: E2eeDevice[];
  log: E2eeLogEntry[];
  head: E2eeLogHead | null;
}

export interface E2eeEpoch {
  id: string;
  channelId: string;
  epoch: number;
  createdBy: string;
  createdAt: string;
}

export interface E2eeEnvelope {
  epochId: string;
  deviceId: string;
  ephemeralPub: string;
  wrapNonce: string;
  wrapped: string;
}

export interface E2eeEpochKey {
  epoch: E2eeEpoch;
  envelope: E2eeEnvelope;
}

export interface E2eeTransferGrant {
  grant: string;
  transferId: string;
  expiresAt: string;
}

export interface E2eeChannelState {
  channelId: string;
  e2ee: boolean;
  epochNumber: number;
  memberDevices: E2eeDevice[];
}


export interface TwoFactorStatus {
  enabled: boolean;
  backupCodesRemaining: number;
}


export type ConnectionProvider =
  'github' | 'gitlab' | 'spotify' | 'twitch' | 'youtube' | 'reddit' | 'x' | 'steam' | 'custom';


export interface Connection {
  id: string;
  provider: ConnectionProvider;

  name: string;
  profileUrl: string | null;

  verified: boolean;

  visible: boolean;
  createdAt: string;
}


export interface ConnectionProviderInfo {
  key: ConnectionProvider;
  label: string;
}


export interface TwoFactorSetup {
  secret: string;
  otpauthUrl: string;
}


export interface BackupCodes {
  backupCodes: string[];
}


export interface Friend {
  id: string;
  user: User;
  createdAt: string;
}


export interface FriendRequest {
  id: string;
  user: User;
  direction: 'incoming' | 'outgoing';
  createdAt: string;
}

export type MessageNotificationLevel = 'all' | 'mentions';

export interface Server {
  id: string;
  name: string;
  iconUrl: string | null;
  description: string | null;
  bannerUrl: string | null;

  systemChannelId: string | null;

  afkChannelId: string | null;

  afkTimeout: number;
  defaultMessageNotifications: MessageNotificationLevel;
  ownerId: string;
  createdAt: string;
}


export interface Emoji {
  id: string;
  serverId: string;
  name: string;
  url: string;
  animated: boolean;
  creatorId: string | null;
  createdAt: string;
}


export interface Sound {
  id: string;
  serverId: string;
  name: string;
  url: string;

  duration: number;

  emoji: string | null;

  volume: number;
  creatorId: string | null;
  createdAt: string;
}

export interface Role {
  id: string;
  serverId: string;
  name: string;
  color: number;

  permissions: string;

  position: number;

  hoist: boolean;

  mentionable: boolean;
}

export interface ServerMember {
  id: string;
  serverId: string;
  userId: string;
  nickname: string | null;

  timedOutUntil: string | null;
  joinedAt: string;
  roleIds: string[];
  user: User;
}

export interface Channel {
  id: string;
  serverId: string | null;
  name: string | null;
  type: ChannelType;
  topic: string | null;

  backgroundUrl: string | null;

  iconUrl: string | null;
  position: number;
  parentCategoryId: string | null;
  nsfw: boolean;

  rateLimitPerUser: number;

  userLimit: number;

  bitrate: number;
}

export type OverwriteType = 'role' | 'member';


export interface ChannelOverwrite {
  id: string;
  channelId: string;
  type: OverwriteType;

  targetId: string;

  allow: string;
  deny: string;
}

export interface Attachment {
  id: string;

  url: string;
  filename: string;
  contentType: string;
  size: number;
  width?: number;
  height?: number;

  duration?: number;

  thumbnailUrl?: string;

  storage?: 'local' | 'cloudinary' | 'orangmove';

  flagged?: boolean;

  spoiler?: boolean;

  expiresAt?: string | null;
}

export interface Reaction {
  emoji: string;
  count: number;

  me: boolean;
}

export interface Message {
  id: string;
  channelId: string;
  author: User;
  content: string;

  emojis?: Emoji[];
  createdAt: string;
  editedAt: string | null;
  replyToId: string | null;
  attachments: Attachment[];
  reactions: Reaction[];
  pinned: boolean;
  pinnedAt: string | null;
  ciphertext?: string | null;
  encEpoch?: number | null;
  encVersion?: number | null;

  systemNotice?: string | null;

  systemData?: unknown;

  clientId?: string | null;
}


export interface AuditLogEntry {
  id: string;

  action: string;
  targetId: string | null;
  targetType: 'server' | 'channel' | 'role' | 'member' | null;

  changes: Record<string, { old?: unknown; new?: unknown }>;
  reason: string | null;
  createdAt: string;
  actor: User | null;
}

export interface Conversation {
  id: string;
  type: 'dm' | 'group_dm';
  name: string | null;
  participants: User[];

  backgroundUrl: string | null;

  iconUrl: string | null;
  lastMessageAt: string | null;
  latestMessage: Message | null;
}

export interface Invite {
  code: string;
  serverId: string;
  inviterId: string;
  expiresAt: string | null;
  maxUses: number | null;
  uses: number;
}


export type InviteStatus = 'ok' | 'expired' | 'exhausted' | 'banned' | 'alreadyMember';


export interface InvitePreview {
  code: string;
  server: Server;
  memberCount: number;
  inviterName: string | null;
  expiresAt: string | null;
  status: InviteStatus;
}


export interface Page<T> {
  items: T[];

  nextCursor: string | null;
}

export interface AuthTokens {
  accessToken: string;

  expiresIn: number;
}

export interface AuthResult {
  user: SelfUser;
  tokens: AuthTokens;
}


export interface ScheduledEvent {
  id: string;
  serverId: string;

  channelId: string | null;
  creatorId: string | null;
  name: string;
  description: string | null;
  location: string | null;
  startsAt: string;
  endsAt: string | null;
  createdAt: string;
  interestedCount: number;

  interested: boolean;
}

export interface UnreadState {
  channelId: string;

  serverId: string | null;

  unread: boolean;

  unreadCount: number;

  mentionCount: number;
}
