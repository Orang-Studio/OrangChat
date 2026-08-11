

export interface User {
  id: string;
  username: string;
  displayName: string;
  avatarUrl: string | null;
  status: string;
  bio: string | null;
  badges: string[];

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


export interface ClientEvents {

  ready: (self: User) => void;
  messageCreate: (message: BotMessage) => void;
  messageUpdate: (message: Message) => void;
  messageDelete: (ref: { channelId: string; messageId: string }) => void;
  reactionAdd: (event: ReactionEvent) => void;
  reactionRemove: (event: ReactionEvent) => void;
  typingStart: (event: TypingEvent) => void;

  error: (error: Error) => void;
  disconnect: (reason: string) => void;
}


export interface BotMessage extends Message {

  reply(content: string): Promise<Message>;

  replyTo(content: string): Promise<Message>;
  react(emoji: string): Promise<void>;
  delete(): Promise<void>;
}
