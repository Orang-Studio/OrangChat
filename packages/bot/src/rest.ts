import type { Channel, Message, Server, User } from './types.js';

export class OrangChatApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = 'OrangChatApiError';
  }
}


export class Rest {
  constructor(
    private readonly baseUrl: string,
    private readonly token: string,
  ) {}

  async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const res = await fetch(`${this.baseUrl}${path}`, {
      method,
      headers: {
        // `Bot`, not `Bearer`: the scheme states which credential is being
        // presented rather than leaving the server to guess from its shape.
        Authorization: `Bot ${this.token}`,
        ...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
      },
      body: body === undefined ? undefined : JSON.stringify(body),
    });

    if (!res.ok) {
      // Errors come back as `{ error }`, but a proxy or a crash can produce
      // something else entirely - never let that surface as a JSON parse error.
      const text = await res.text().catch(() => '');
      let message = text || res.statusText;
      try {
        const parsed = JSON.parse(text) as { error?: string };
        if (parsed.error) message = parsed.error;
      } catch {
        /* not JSON; the raw text is the best we have */
      }
      throw new OrangChatApiError(res.status, message);
    }

    if (res.status === 204) return undefined as T;
    return (await res.json()) as T;
  }

  /**
   * The bot's own account. Not `/auth/me` - that belongs to the human account
   * surface and refuses bot tokens outright.
   */
  me(): Promise<User> {
    return this.request<User>('GET', '/bot/me');
  }

  servers(): Promise<Server[]> {
    return this.request<Server[]>('GET', '/servers');
  }

  channel(channelId: string): Promise<Channel> {
    return this.request<Channel>('GET', `/channels/${channelId}`);
  }

  sendMessage(
    channelId: string,
    content: string,
    options: { replyToId?: string; attachmentIds?: string[] } = {},
  ): Promise<Message> {
    return this.request<Message>('POST', `/channels/${channelId}/messages`, {
      content,
      replyToId: options.replyToId,
      attachmentIds: options.attachmentIds,
    });
  }

  history(channelId: string, options: { before?: string; limit?: number } = {}): Promise<{
    items: Message[];
    nextCursor: string | null;
  }> {
    const params = new URLSearchParams();
    if (options.before) params.set('before', options.before);
    if (options.limit) params.set('limit', String(options.limit));
    const query = params.toString();
    return this.request('GET', `/channels/${channelId}/messages${query ? `?${query}` : ''}`);
  }
}
