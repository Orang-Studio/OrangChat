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

/** Who may open a new DM with you. */
export type DmPrivacy = 'everyone' | 'friends' | 'none';
/** Who may send you a friend request; 'mutual' = friends of friends. */
export type FriendRequestPrivacy = 'everyone' | 'mutual' | 'none';

export interface User {
  id: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  status: PresenceStatus;
  /** Empty while offline; may contain more than one kind for concurrent sessions. */
  devices: PresenceDevice[];
  /** Live rich presence. Empty when the user has no shareable activity. */
  activities: UserActivity[];
  /** Free-text "about me". */
  bio: string | null;
  /** Profile banner image URL. */
  bannerUrl: string | null;
  /** Profile accent as 0xRRGGBB integer; null = derive from avatar. */
  accentColor: number | null;
  /** Short free-text pronouns, e.g. "they/them". */
  pronouns: string | null;
  /** Public, sandboxed CSS the user set to theme their profile card. Rendered
   * only through the client-side sanitizer + a scoped, contained container. */
  profileCss: string | null;
  /** Awarded badge slugs; unknown ones are ignored by `resolveBadges`. */
  badges: BadgeId[];
  /**
   * A bot account. Rendered as a label beside the name - it is a property of
   * the account, not something anyone can put in a nickname to impersonate.
   * Absent on rows written before bots existed, so treat undefined as false.
   */
  bot?: boolean;
  createdAt: string;
}

/** The authenticated user's own profile - includes private fields. */
export interface SelfUser extends User {
  email: string;
  /** The user's own client CSS override (private; only sent to the owner). */
  customCss: string | null;
  /** Replaces the OrangChat mark on this user's own clients. Self-only. */
  appIconUrl: string | null;
  dmPrivacy: DmPrivacy;
  friendRequestPrivacy: FriendRequestPrivacy;
  typingIndicators: boolean;
  /** Which friend events raise a notification. Online is opt-in - it fires for
      every friend on every app launch. */
  notifyFriendRequests: boolean;
  notifyFriendAccepted: boolean;
  notifyFriendOnline: boolean;
  /**
   * "Require verification before messaging anyone new" (docs/E2EE.md §6.5).
   * Off by default. It gates only this account's own sending - it never changes
   * anybody else's policy, and it never turns encryption on or off.
   */
  e2eeStrict: boolean;
  /**
   * "Display the game you're playing". Off by default: the desktop client has
   * to read the process list to detect a game, and nothing about that should
   * start happening because an update shipped. Turning it off also clears any
   * game activity already being broadcast.
   */
  gameActivity: boolean;
  /** Whether TOTP is active. The secret is never sent to any client. */
  twoFactorEnabled: boolean;
  /** False for OAuth-only accounts, which have no password to re-confirm. */
  hasPassword: boolean;
  /** True while the account is frozen: no new sign-ins, DMs or friend requests. */
  lockdown: boolean;
}

/** One restriction in force against the account. Moderation is per-server. */
export interface StandingEntry {
  kind: 'ban' | 'timeout';
  serverId: string;
  serverName: string;
  reason: string | null;
  /** When a timeout lifts; null for bans, which don't expire. */
  expiresAt: string | null;
  createdAt: string | null;
}

/** GET /security/standing */
export interface AccountStanding {
  /** True when nothing currently restricts the account. */
  good: boolean;
  entries: StandingEntry[];
}

/** One live session, as shown on the devices screen. */
export interface DeviceSession {
  id: string;
  /** True for the session making the request. */
  current: boolean;
  /** Raw User-Agent; the client turns it into a device name. */
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

/** GET /security/2fa */
export interface TwoFactorStatus {
  enabled: boolean;
  backupCodesRemaining: number;
}

/** Registry key of a linkable platform; "custom" is a hand-entered link. */
export type ConnectionProvider =
  'github' | 'gitlab' | 'spotify' | 'twitch' | 'youtube' | 'reddit' | 'x' | 'steam' | 'custom';

/** An external account linked for display on a profile card. */
export interface Connection {
  id: string;
  provider: ConnectionProvider;
  /** Handle on the remote platform, or the label for a custom link. */
  name: string;
  profileUrl: string | null;
  /** True only when an OAuth/OpenID round trip proved control of the account.
   * Custom links are always false and are shown without a checkmark. */
  verified: boolean;
  /** Whether it appears on the profile others see. Owner-only field. */
  visible: boolean;
  createdAt: string;
}

/** A provider this deployment has credentials for, so it can be offered. */
export interface ConnectionProviderInfo {
  key: ConnectionProvider;
  label: string;
}

/** POST /security/2fa/setup - the secret to scan, before enabling. */
export interface TwoFactorSetup {
  secret: string;
  otpauthUrl: string;
}

/** Recovery codes, returned in plaintext only at generation time. */
export interface BackupCodes {
  backupCodes: string[];
}

/** An accepted friend: the other user plus the friendship id. */
export interface Friend {
  id: string;
  user: User;
  createdAt: string;
}

/** A pending friend request, tagged by direction relative to the viewer. */
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
  /** Text channel for join/system notices. May be null; never a voice channel. */
  systemChannelId: string | null;
  /** Voice channel idle members are moved to. Null disables it. */
  afkChannelId: string | null;
  /** Seconds of voice inactivity before the AFK move. One of 60/300/900/1800/3600. */
  afkTimeout: number;
  defaultMessageNotifications: MessageNotificationLevel;
  ownerId: string;
  createdAt: string;
}

/**
 * A server's custom emoji. Written into message content as `<:name:id>`, or
 * `<a:name:id>` when animated - the id is what resolves, so a rename never
 * breaks messages that already used it.
 */
export interface Emoji {
  id: string;
  serverId: string;
  name: string;
  url: string;
  animated: boolean;
  creatorId: string | null;
  createdAt: string;
}

/** A soundboard clip, playable into any of its server's voice channels. */
export interface Sound {
  id: string;
  serverId: string;
  name: string;
  url: string;
  /** Seconds, measured server-side on upload. */
  duration: number;
  /** Unicode emoji for the button; decorative. */
  emoji: string | null;
  /** Playback gain 0..1, applied client-side. */
  volume: number;
  creatorId: string | null;
  createdAt: string;
}

export interface Role {
  id: string;
  serverId: string;
  name: string;
  color: number;
  /** Decimal-string-encoded permission bitfield. */
  permissions: string;
  /** 0 is always @everyone. Higher outranks lower; the owner outranks all. */
  position: number;
  /** Show this role's members as their own group in the member list. */
  hoist: boolean;
  /** Anyone may @mention it, not just holders of MENTION_EVERYONE. */
  mentionable: boolean;
}

export interface ServerMember {
  id: string;
  serverId: string;
  userId: string;
  nickname: string | null;
  /** ISO instant the timeout expires, or null when not timed out. A past value
   *  is simply expired -- compare against the current time, do not assume null. */
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
  /** Shared DM chat background (Messenger-style). Plaintext like avatars. */
  backgroundUrl: string | null;
  position: number;
  parentCategoryId: string | null;
  nsfw: boolean;
  /** Slowmode, seconds between messages per member. 0 = off. Text channels only. */
  rateLimitPerUser: number;
  /** Voice only. 0 = unlimited. */
  userLimit: number;
  /** Voice only, bits per second. */
  bitrate: number;
}

export type OverwriteType = 'role' | 'member';

/** A per-channel permission overwrite for a role or a specific member. */
export interface ChannelOverwrite {
  id: string;
  channelId: string;
  type: OverwriteType;
  /** roleId when type='role', userId when type='member'. */
  targetId: string;
  /** Decimal-string permission bitfields granted / revoked on this channel. */
  allow: string;
  deny: string;
}

export interface Attachment {
  id: string;
  /** Origin-relative, like `/attachments/<id>.png` or `/orangmove/file/<token>`. */
  url: string;
  filename: string;
  contentType: string;
  size: number;
  width?: number;
  height?: number;
  /**
   * Seconds, for audio and video. Captured once by the sender's client at
   * upload time, so a receiver can show how long the file is without loading
   * any of it.
   */
  duration?: number;
  /**
   * A still image of the video's first frame, stored next to the bytes at
   * upload time. Receivers show it as the preview instead of a dark box, which
   * is what a `<video>` without a poster looks like until play is pressed.
   * Absent on videos uploaded before this existed.
   */
  thumbnailUrl?: string;
  /**
   * Where the bytes live. Files over 10MB go to OrangMove, which is an
   * ephemeral store - see `expiresAt`. Everything else is `cloudinary`, or
   * `local` when Cloudinary is unconfigured or the row predates the switch.
   * Only `orangmove` expires; treat the other two as permanent.
   */
  storage?: 'local' | 'cloudinary' | 'orangmove';
  /**
   * Images only: omni-moderation judged this inappropriate at upload time.
   * Clients hide the pixels behind an explicit viewer opt-in. Absent on messages
   * sent before moderation existed, and whenever it's unconfigured.
   */
  flagged?: boolean;
  /**
   * The author chose to hide this behind a cover the viewer has to click. Unlike
   * `flagged` it's presentation, not policy: reveal is always allowed.
   */
  spoiler?: boolean;
  /**
   * When the file is deleted, for `orangmove` storage only (its reaper caps
   * files at an hour). Null/absent means it's kept as long as the message.
   * Past this point the url 404s, so render it as expired rather than broken.
   */
  expiresAt?: string | null;
}

export interface Reaction {
  emoji: string;
  count: number;
  /** Whether the requesting user has reacted with this emoji. */
  me: boolean;
}

export interface Message {
  id: string;
  channelId: string;
  author: User;
  content: string;
  /**
   * Existing custom emoji referenced by this message. These are renderable
   * even when the viewer cannot use the emoji themselves.
   */
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
  /**
   * Set by the server when it wrote this message about the conversation rather
   * than a person typing it - see `SystemNoticeKind`. Never accepted from a
   * client, which is what makes a notice trustworthy: `author` is the person
   * whose action it describes, not the author of the claim.
   */
  systemNotice?: string | null;
  /** A notice's payload, for the kinds that are a card rather than a sentence. */
  systemData?: unknown;
  /**
   * The outbox id of the local row this message confirms. Stamped by the sender's
   * own client when it recognises its send coming back, never sent over the wire.
   * Rendering keys off it so a pending row becomes its confirmed self in place
   * instead of being unmounted and replaced.
   */
  clientId?: string | null;
}

/** One entry in a server's audit log. `actor` is null when the account that made
 *  the change has since been deleted -- the entry itself is never removed. */
export interface AuditLogEntry {
  id: string;
  /** e.g. "role.create", "channel.update", "member.kick" */
  action: string;
  targetId: string | null;
  targetType: 'server' | 'channel' | 'role' | 'member' | null;
  /** { field: { old?: unknown; new?: unknown } } - only fields that changed. */
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
  /** Shared DM chat background. Plaintext like avatars, never E2EE. */
  backgroundUrl: string | null;
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

/** Why an invite can't be used, or that it can. */
export type InviteStatus = 'ok' | 'expired' | 'exhausted' | 'banned' | 'alreadyMember';

/** What an invite link resolves to, before anyone commits to joining. */
export interface InvitePreview {
  code: string;
  server: Server;
  memberCount: number;
  inviterName: string | null;
  expiresAt: string | null;
  status: InviteStatus;
}

/** Cursor-paginated list envelope (used for message history, etc.). */
export interface Page<T> {
  items: T[];
  /** Opaque cursor for the next page, or null when exhausted. */
  nextCursor: string | null;
}

export interface AuthTokens {
  accessToken: string;
  /** Access token expiry in seconds. Refresh token rides in an httpOnly cookie. */
  expiresIn: number;
}

export interface AuthResult {
  user: SelfUser;
  tokens: AuthTokens;
}

/** Unread + mention state for one channel (from GET /me/unreads). */
export interface ScheduledEvent {
  id: string;
  serverId: string;
  /** Voice/text channel the event happens in, when it isn't external. */
  channelId: string | null;
  creatorId: string | null;
  name: string;
  description: string | null;
  location: string | null;
  startsAt: string;
  endsAt: string | null;
  createdAt: string;
  interestedCount: number;
  /** Whether the requesting user marked themselves interested. */
  interested: boolean;
}

export interface UnreadState {
  channelId: string;
  /** Server the channel belongs to, or null for a DM/group DM. */
  serverId: string | null;
  /** There are messages the user hasn't read. */
  unread: boolean;
  /** Unread messages from other people, saturating at 100. */
  unreadCount: number;
  /** How many times the user was @mentioned since last reading. */
  mentionCount: number;
}
