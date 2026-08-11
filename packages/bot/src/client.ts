import { io, type Socket } from 'socket.io-client';

import { Rest } from './rest.js';
import type { BotMessage, ClientEvents, Message, ReactionEvent, TypingEvent, User } from './types.js';

export interface ClientOptions {

  token: string;

  baseUrl?: string;
}

const DEFAULT_BASE_URL = 'https://orangchat.lt';

type Handler = (...args: never[]) => void;


export class Client {
  readonly rest: Rest;
  private readonly baseUrl: string;
  private readonly token: string;
  private socket: Socket | null = null;
  private handlers = new Map<keyof ClientEvents, Set<Handler>>();
  private self: User | null = null;

  constructor(options: ClientOptions) {
    if (!options.token) throw new Error('A bot token is required');
    this.token = options.token;
    this.baseUrl = (options.baseUrl ?? DEFAULT_BASE_URL).replace(/\/$/, '');
    this.rest = new Rest(`${this.baseUrl}/api`, this.token);
  }

  /** The bot's own account, once `login()` has resolved. */
  get user(): User | null {
    return this.self;
  }

  on<E extends keyof ClientEvents>(event: E, handler: ClientEvents[E]): this {
    const set = this.handlers.get(event) ?? new Set<Handler>();
    set.add(handler as Handler);
    this.handlers.set(event, set);
    return this;
  }

  off<E extends keyof ClientEvents>(event: E, handler: ClientEvents[E]): this {
    this.handlers.get(event)?.delete(handler as Handler);
    return this;
  }

  private emit<E extends keyof ClientEvents>(event: E, ...args: Parameters<ClientEvents[E]>): void {
    for (const handler of this.handlers.get(event) ?? []) {
      try {
        (handler as (...a: unknown[]) => void)(...args);
      } catch (error) {
        // A throwing handler must not take the gateway down with it. `error` is
        // reported through the same channel so it is not silently swallowed.
        if (event === 'error') return;
        this.emit('error', error instanceof Error ? error : new Error(String(error)));
      }
    }
  }

  /** Connect to the gateway. Resolves once the bot is identified and ready. */
  async login(): Promise<User> {
    const self = await this.rest.me();
    this.self = self;

    const socket = io(this.baseUrl, {
      // `Bot <token>` in the handshake, mirroring the REST scheme. The server
      // reads this field for people's JWTs too and branches on the prefix.
      auth: { token: `Bot ${this.token}` },
      transports: ['websocket'],
      reconnection: true,
    });
    this.socket = socket;

    socket.on('message:new', (raw: Message) => {
      // A bot has no key for an encrypted message and would only see an empty
      // body. Dropping it here keeps that from looking like a blank message.
      if (raw.ciphertext) return;
      // Bots that reply to themselves loop forever. This is the single most
      // common way a first bot takes a server down, so it is prevented here
      // rather than left to every handler to remember.
      if (raw.author?.id === this.self?.id) return;
      this.emit('messageCreate', this.wrap(raw));
    });
    socket.on('message:updated', (raw: Message) => this.emit('messageUpdate', raw));
    socket.on('message:deleted', (ref: { channelId: string; messageId: string }) =>
      this.emit('messageDelete', ref),
    );
    socket.on('reaction:add', (e: ReactionEvent) => this.emit('reactionAdd', e));
    socket.on('reaction:remove', (e: ReactionEvent) => this.emit('reactionRemove', e));
    socket.on('typing', (e: TypingEvent) => this.emit('typingStart', e));
    socket.on('disconnect', (reason: string) => this.emit('disconnect', reason));
    socket.on('connect_error', (error: Error) => this.emit('error', error));

    await new Promise<void>((resolve, reject) => {
      const onConnect = () => {
        socket.off('connect_error', onError);
        resolve();
      };
      const onError = (error: Error) => {
        socket.off('connect', onConnect);
        reject(new Error(`Gateway connection failed: ${error.message}`));
      };
      socket.once('connect', onConnect);
      socket.once('connect_error', onError);
    });

    this.emit('ready', self);
    return self;
  }

  disconnect(): void {
    this.socket?.disconnect();
    this.socket = null;
  }

  /** Send a message to a channel. */
  sendMessage(channelId: string, content: string, replyToId?: string): Promise<Message> {
    return this.rest.sendMessage(channelId, content, { replyToId });
  }

  /**
   * Editing, deleting and reacting are gateway operations rather than REST
   * ones - that is where the server implements them, and it is what people's
   * clients use too.
   */
  private ack<T>(event: string, payload: unknown): Promise<T> {
    const socket = this.socket;
    if (!socket) return Promise.reject(new Error('Not connected - call login() first'));
    return new Promise<T>((resolve, reject) => {
      socket.timeout(10_000).emit(
        event,
        payload,
        (transportError: Error | null, res: { ok?: boolean; data?: T; error?: string } | undefined) => {
          if (transportError) return reject(transportError);
          if (!res?.ok) return reject(new Error(res?.error ?? `${event} failed`));
          resolve(res.data as T);
        },
      );
    });
  }

  editMessage(channelId: string, messageId: string, content: string): Promise<Message> {
    return this.ack<Message>('message:edit', { channelId, messageId, content });
  }

  deleteMessage(channelId: string, messageId: string): Promise<void> {
    return this.ack<void>('message:delete', { channelId, messageId });
  }

  addReaction(channelId: string, messageId: string, emoji: string): Promise<void> {
    return this.ack<void>('reaction:add', { channelId, messageId, emoji });
  }

  removeReaction(channelId: string, messageId: string, emoji: string): Promise<void> {
    return this.ack<void>('reaction:remove', { channelId, messageId, emoji });
  }

  private wrap(raw: Message): BotMessage {
    return {
      ...raw,
      reply: (content: string) => this.sendMessage(raw.channelId, content),
      replyTo: (content: string) => this.sendMessage(raw.channelId, content, raw.id),
      react: (emoji: string) => this.addReaction(raw.channelId, raw.id, emoji),
      delete: () => this.deleteMessage(raw.channelId, raw.id),
    };
  }
}
