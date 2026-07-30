/**
 * Wire types, deliberately duplicated rather than imported from
 * `@orangchat/shared`.
 *
 * That package is `private: true` and is consumed straight from TypeScript
 * source by the apps in this repo. Depending on it here would make this package
 * unpublishable - npm cannot resolve `workspace:*`, and the source would never
 * be compiled into `dist`. These are the only shapes a bot actually observes.
 */

export interface User {
  id: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  status: string;
  bio: string | null;
  badges: string[];
  /** True for another bot. Absent on accounts written before bots existed. */
  bot?: boolean;
  createdAt: string;
}

export interface Attachment {
  id: string;
  url: string;
  filename: string;
  contentType: string;
  size: number;
  width?: number | null;
  height?: number | null;
  spoiler?: boolean;
}

export interface Message {
  id: string;
  channelId: string;
  author: User;
  content: string;
  createdAt: string;
  editedAt: string | null;
  replyToId: string | null;
  attachments: Attachment[];
  pinned: boolean;
  /**
   * Present only on end-to-end encrypted messages, which in practice means DMs.
   * A bot has no key for these and cannot read them - see the DM note in
   * docs/BOTS.md. Its presence is the signal to ignore the message.
   */
  ciphertext?: string;
}

export interface Channel {
  id: string;
  serverId: string | null;
  name: string | null;
  type: string;
  topic: string | null;
  nsfw: boolean;
}

export interface Server {
  id: string;
  name: string;
  iconUrl: string | null;
  description: string | null;
  ownerId: string;
}

export interface TypingEvent {
  channelId: string;
  userId: string;
}

export interface ReactionEvent {
  channelId: string;
  messageId: string;
  userId: string;
  emoji: string;
}

/** Events the gateway delivers to a bot. */
export interface ClientEvents {
  /** The gateway is connected and the bot is logged in. */
  ready: (self: User) => void;
  messageCreate: (message: BotMessage) => void;
  messageUpdate: (message: Message) => void;
  messageDelete: (ref: { channelId: string; messageId: string }) => void;
  reactionAdd: (event: ReactionEvent) => void;
  reactionRemove: (event: ReactionEvent) => void;
  typingStart: (event: TypingEvent) => void;
  /** Transport-level trouble. The client reconnects on its own. */
  error: (error: Error) => void;
  disconnect: (reason: string) => void;
}

/** A received message, with the convenience methods a handler usually wants. */
export interface BotMessage extends Message {
  /** Send a message to the same channel. */
  reply(content: string): Promise<Message>;
  /** Send a message to the same channel, as a threaded reply to this one. */
  replyTo(content: string): Promise<Message>;
  react(emoji: string): Promise<void>;
  delete(): Promise<void>;
}
