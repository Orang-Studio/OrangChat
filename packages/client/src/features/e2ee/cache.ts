import type { MessagePayload, SealedAttachmentRef } from '@orangchat/shared';
import {
  allCachedMessages,
  cachedMessagesIn,
  deleteCachedMessage,
  getCachedMessage,
  openLocal,
  putCachedMessage,
  sealLocal,
} from './keystore';


export interface CachedMessage {
  id: string;
  channelId: string;
  authorId: string;
  createdAt: string;
  text: string;
  sentAt: string;
  replyTo: string | null;
  attachments: SealedAttachmentRef[];
}

interface StoredBody {
  text: string;
  sentAt: string;
  replyTo: string | null;
  attachments: SealedAttachmentRef[];
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();


const hot = new Map<string, CachedMessage>();

export async function rememberMessage(
  channelId: string,
  message: { id: string; authorId: string; createdAt: string },
  payload: MessagePayload,
): Promise<CachedMessage> {
  const entry: CachedMessage = {
    id: message.id,
    channelId,
    authorId: message.authorId,
    createdAt: message.createdAt,
    text: payload.text,
    sentAt: payload.sentAt || message.createdAt,
    replyTo: payload.replyTo ?? null,
    attachments: payload.attachments ?? [],
  };
  hot.set(entry.id, entry);

  const body: StoredBody = {
    text: entry.text,
    sentAt: entry.sentAt,
    replyTo: entry.replyTo,
    attachments: entry.attachments,
  };
  try {
    const sealed = await sealLocal(encoder.encode(JSON.stringify(body)));
    await putCachedMessage({
      id: entry.id,
      channelId: entry.channelId,
      authorId: entry.authorId,
      createdAt: entry.createdAt,
      ...sealed,
    });
  } catch {
  }
  return entry;
}

async function hydrate(record: Awaited<ReturnType<typeof getCachedMessage>>) {
  if (!record) return null;
  try {
    const body = JSON.parse(decoder.decode(await openLocal(record))) as StoredBody;
    const entry: CachedMessage = {
      id: record.id,
      channelId: record.channelId,
      authorId: record.authorId,
      createdAt: record.createdAt,
      text: body.text,
      sentAt: body.sentAt,
      replyTo: body.replyTo ?? null,
      attachments: body.attachments ?? [],
    };
    hot.set(entry.id, entry);
    return entry;
  } catch {
    await deleteCachedMessage(record.id).catch(() => {});
    return null;
  }
}

export async function recallMessage(id: string): Promise<CachedMessage | null> {
  const cached = hot.get(id);
  if (cached) return cached;
  return hydrate(await getCachedMessage(id).catch(() => null));
}


export async function warmChannel(channelId: string): Promise<void> {
  try {
    const records = await cachedMessagesIn(channelId);
    for (const record of records) {
      if (!hot.has(record.id)) await hydrate(record);
    }
  } catch {
  }
}

export async function forgetMessage(id: string): Promise<void> {
  hot.delete(id);
  await deleteCachedMessage(id).catch(() => {});
}

export interface LocalSearchHit {
  id: string;
  channelId: string;
  authorId: string;
  createdAt: string;
  text: string;
}


export async function searchLocal(
  query: string,
  options: { channelId?: string; channelIds?: readonly string[]; limit?: number } = {},
): Promise<LocalSearchHit[]> {
  const needle = query.trim().toLowerCase();
  if (needle.length === 0) return [];

  const records = options.channelId
    ? await cachedMessagesIn(options.channelId)
    : await allCachedMessages();
  const scope = options.channelIds ? new Set(options.channelIds) : null;

  const hits: LocalSearchHit[] = [];
  for (const record of records) {
    if (scope && !scope.has(record.channelId)) continue;
    const entry = hot.get(record.id) ?? (await hydrate(record));
    if (!entry) continue;
    if (!entry.text.toLowerCase().includes(needle)) continue;
    hits.push({
      id: entry.id,
      channelId: entry.channelId,
      authorId: entry.authorId,
      createdAt: entry.createdAt,
      text: entry.text,
    });
  }

  hits.sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
  return hits.slice(0, options.limit ?? 50);
}
