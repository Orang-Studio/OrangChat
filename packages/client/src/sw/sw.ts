/// <reference lib="webworker" />
// Straight from the crypto module rather than the package barrel: the barrel
// pulls in the zod schemas and everything else the app needs, none of which a
// notification path has any use for.
import {
  decodeEnvelope,
  decodeMessagePayload,
  fromBase64,
  importSigningPublicKey,
  openMessage,
} from '@orangchat/shared/e2ee';

/**
 * The service worker. Three jobs: render notifications, open the encrypted ones
 * itself (docs/E2EE.md §8), and keep small media out of the network.
 *
 * The server has no body to compose for an encrypted conversation, so it sends
 * the envelope instead. Everything needed to open it is already in this origin's
 * IndexedDB - the identity key handles, the sealed conversation keys, and the
 * signing keys of devices whose chain the app has already replayed - and none of
 * it is extractable, so a worker that can *use* them still cannot leak them.
 *
 * Failure is a placeholder, never nothing. "New message from Vakaris" is an
 * acceptable notification; a swallowed exception that shows nothing is the bug
 * this file exists to avoid.
 */

declare const self: ServiceWorkerGlobalScope;

interface PushPayload {
  kind: 'message' | 'call' | 'read' | 'security';
  title: string;
  body: string;
  href?: string;
  tag: string;
  icon?: string | null;
  channelId: string;
  messageId?: string | null;
  senderId: string;
  senderName: string;
  isGroup: boolean;
  ciphertext?: string;
  encEpoch?: number;
}

/**
 * Bump to abandon everything cached under the old name. `activate` deletes any
 * cache that is not this one, so a rename is also the purge.
 */
const MEDIA_CACHE = 'orangchat-media-v1';

/**
 * How many responses to keep. Avatars and emoji are a few KB each, so this is a
 * modest amount of disk for a set that covers every face in a busy server.
 */
const MAX_ENTRIES = 600;

/**
 * Nothing larger than this is stored. The point of the cache is the small
 * images that are requested again on every single render; a big file would
 * evict hundreds of them for one hit, and the origin quota is shared with the
 * IndexedDB that holds this device's encryption keys - filling it is how a
 * browser decides to throw those away.
 */
const MAX_BYTES = 4 * 1024 * 1024;

/**
 * Which paths are safe to serve from disk without asking.
 *
 * Attachments are deliberately absent. They are access-controlled, often large,
 * and video among them is fetched by range - all three are reasons for the
 * network to stay in the loop. They carry `immutable` and an entity tag of
 * their own, so the browser's own cache already keeps them.
 */
function isCacheableMedia(url: URL): boolean {
  return (
    url.pathname.startsWith(ASSET_ROUTE) ||
    url.pathname === '/api/media/proxy' ||
    url.pathname.startsWith('/uploads/')
  );
}

const ASSET_ROUTE = '/api/media/asset/';

/**
 * Whether a cached response may have been replaced under the same url.
 *
 * An upload filename and a proxy url both name their own bytes - cache them and
 * you are done. `/api/media/asset/<kind>/<id>` names a *row*: changing a picture
 * keeps the url and only changes what it serves, which is why the server
 * revalidates that route rather than pinning it. Serving it cache-first and
 * never going back froze every face at whichever one this browser saw first.
 */
function isMutableAsset(url: URL): boolean {
  return url.pathname.startsWith(ASSET_ROUTE);
}

/**
 * Refresh a cached asset behind the response already handed back. A failure
 * leaves the stored copy alone: offline, yesterday's portrait is the best
 * answer there is.
 */
async function revalidate(cache: Cache, request: Request): Promise<void> {
  try {
    // `no-cache` forces a conditional request rather than letting the http
    // cache answer from the very entry this is trying to get past.
    const response = await fetch(request, { cache: 'no-cache' });
    if (!(await cacheable(response))) return;
    await cache.put(request, response);
    await trim(cache);
  } catch {
    /* no network: keep what we have */
  }
}

/**
 * Drops the oldest entries once the cache outgrows its budget. `keys()` yields
 * insertion order, so this is first-in-first-out rather than true LRU: a face
 * that has been on screen all day is no more protected than one seen once. That
 * is the right trade for content this cheap to re-fetch, and it avoids writing
 * a timestamp on every read.
 */
async function trim(cache: Cache): Promise<void> {
  const keys = await cache.keys();
  if (keys.length <= MAX_ENTRIES) return;
  await Promise.all(keys.slice(0, keys.length - MAX_ENTRIES).map((key) => cache.delete(key)));
}

async function cacheable(response: Response): Promise<boolean> {
  // A 206 is a slice, and the Cache API cannot represent that: stored beside
  // its url it would later be handed out as the whole file. An opaque response
  // has an unreadable status, so it can't be told apart from an error page.
  if (response.status !== 200 || response.type === 'opaque') return false;
  const declared = Number(response.headers.get('content-length'));
  return Number.isFinite(declared) && declared > 0 ? declared <= MAX_BYTES : true;
}

async function fromCacheOrNetwork(event: FetchEvent): Promise<Response> {
  const request = event.request;
  const cache = await caches.open(MEDIA_CACHE);
  const hit = await cache.match(request);
  if (hit) {
    // Stale-while-revalidate, and only where the bytes can actually move: the
    // render is never held back, but the next one shows the current picture.
    if (isMutableAsset(new URL(request.url))) event.waitUntil(revalidate(cache, request));
    return hit;
  }

  const response = await fetch(request);
  if (await cacheable(response)) {
    // Cache the clone, return the original: a body can only be read once, and
    // the caller is owed the one that streams.
    void cache
      .put(request, response.clone())
      .then(() => trim(cache))
      .catch(() => {});
  }
  return response;
}

self.addEventListener('fetch', (event: FetchEvent) => {
  const request = event.request;
  if (request.method !== 'GET') return;

  // A range request is asking for part of something it already has. `match`
  // ignores the header entirely, so answering one from the cache hands a player
  // the start of the file when it asked for the middle, and it never recovers.
  if (request.headers.has('range')) return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin || !isCacheableMedia(url)) return;

  event.respondWith(fromCacheOrNetwork(event));
});

/**
 * Sent by the app when a session ends. What is stored is only ever avatars,
 * emoji and proxied embeds, but they still describe who the last person here
 * was talking to, and that should not outlive their sign-out.
 */
self.addEventListener('message', (event: ExtendableMessageEvent) => {
  if ((event.data as { type?: string } | null)?.type === 'media-cache:clear') {
    event.waitUntil(caches.delete(MEDIA_CACHE));
  }
});

self.addEventListener('install', () => {
  // Nothing to pre-cache: this worker only keeps what the app has actually
  // asked for. Take over straight away so the first load after an update is
  // already served by the version that shipped with it.
  void self.skipWaiting();
});

self.addEventListener('activate', (event: ExtendableEvent) => {
  event.waitUntil(
    (async () => {
      const names = await caches.keys();
      await Promise.all(names.filter((name) => name !== MEDIA_CACHE).map((name) => caches.delete(name)));
      await self.clients.claim();
    })(),
  );
});

const DB_NAME = 'orangchat-e2ee';
const EPOCH_KEYS = 'epochKeys';
const HEADS = 'heads';
const DEVICE_KEYS = 'deviceKeys';

/**
 * How long an open client gets to fetch the missing keys before the retry.
 * Long enough for the epoch-key round trip, short enough that a notification
 * never feels delayed.
 */
const SYNC_RETRY_MS = 2_000;

interface StoredEpochKey {
  channelId: string;
  epoch: number;
  nonce: ArrayBuffer;
  wrapped: ArrayBuffer;
}

/**
 * Opens the app's database without ever upgrading it. A service worker that
 * created the schema would race the page and could win with an older idea of
 * what the stores are; if the app has not run yet there is simply nothing to
 * decrypt.
 */
function openDb(): Promise<IDBDatabase | null> {
  return new Promise((resolve) => {
    let request: IDBOpenDBRequest;
    try {
      request = indexedDB.open(DB_NAME);
    } catch {
      return resolve(null);
    }
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => resolve(null);
    request.onblocked = () => resolve(null);
  });
}

function get<T>(db: IDBDatabase, store: string, key: IDBValidKey): Promise<T | null> {
  return new Promise((resolve) => {
    if (!db.objectStoreNames.contains(store)) return resolve(null);
    try {
      const request = db.transaction(store, 'readonly').objectStore(store).get(key);
      request.onsuccess = () => resolve((request.result as T) ?? null);
      request.onerror = () => resolve(null);
    } catch {
      resolve(null);
    }
  });
}

async function conversationKey(
  db: IDBDatabase,
  channelId: string,
  epoch: number,
): Promise<Uint8Array | null> {
  const record = await get<StoredEpochKey>(db, EPOCH_KEYS, `${channelId}:${epoch}`);
  if (!record) return null;
  const vault = await get<{ key: CryptoKey }>(db, HEADS, 'vault');
  if (!vault?.key) return null;
  try {
    const plaintext = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: new Uint8Array(record.nonce) },
      vault.key,
      record.wrapped,
    );
    return new Uint8Array(plaintext);
  } catch {
    return null;
  }
}

/**
 * Opens one message, with the sender's signature checked exactly as it is in the
 * app. Everybody in a conversation holds the same conversation key, so a valid
 * GCM tag proves only that *someone* there wrote it; skipping the per-sender
 * signature here would make a notification the one place authorship is not
 * established.
 */
async function decrypt(payload: PushPayload): Promise<string | null> {
  if (!payload.ciphertext) return null;

  const db = await openDb();
  if (!db) return null;

  try {
    const envelope = decodeEnvelope(fromBase64(payload.ciphertext));

    // An epoch minted while this device was asleep has no cached key here, and
    // the worker holds no access token to go and fetch its envelope. That is a
    // placeholder case by design - epochs rotate on membership changes and long
    // intervals, not per message, so it is the exception rather than the rule.
    const key = await conversationKey(db, payload.channelId, envelope.epoch);
    if (!key) return null;

    const sender = await get<{ ikSigPub: string; userId: string }>(
      db,
      DEVICE_KEYS,
      envelope.senderDeviceId,
    );
    if (!sender || sender.userId !== envelope.senderUserId) return null;

    const verifying = await importSigningPublicKey(fromBase64(sender.ikSigPub));
    const plaintext = await openMessage(key, payload.channelId, envelope, verifying);
    return decodeMessagePayload(plaintext).text;
  } catch {
    return null;
  } finally {
    db.close();
  }
}

function fallbackBody(payload: PushPayload): string {
  return payload.isGroup ? `New message from ${payload.senderName}` : 'New message';
}

/**
 * Decrypt, and when the first pass fails because a key has not reached this
 * device yet, have an open client fetch it before giving up. Rotations sync on
 * the next channel open or socket event, so a tab that was asleep through one
 * holds no key for it; an open (even unfocused) tab can sync on demand and a
 * retry a moment later usually has the envelope it needs. With no window open
 * there is nothing to ask - the placeholder stands.
 */
async function decryptWithSync(payload: PushPayload): Promise<string | null> {
  const direct = await decrypt(payload);
  if (direct) return direct;

  const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  if (windows.length === 0) return null;
  windows.forEach((client) =>
    client.postMessage({ type: 'e2ee:sync', channelId: payload.channelId }),
  );
  await new Promise((resolve) => setTimeout(resolve, SYNC_RETRY_MS));
  return decrypt(payload);
}

/**
 * Whether a focused window is already showing the route this notification would
 * open. `href` is the app's own route for the conversation (`/dms/<id>`, or the
 * channel path inside a server), and a WindowClient's url follows client-side
 * navigation, so comparing the two answers "is the user reading this right now"
 * without needing to ask the page.
 */
async function isConversationOnScreen(href: string | undefined): Promise<boolean> {
  if (!href) return false;
  const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
  return windows.some((client) => {
    if (!(client as WindowClient).focused) return false;
    try {
      return new URL(client.url).pathname === new URL(href, self.location.origin).pathname;
    } catch {
      return false;
    }
  });
}

/**
 * The browser can retire a push subscription on its own - a key rotation, a
 * storage eviction - and the endpoint the server holds then points at nothing.
 * Nothing surfaces: pushes are accepted and silently dropped until somebody
 * reopens the app, which is why this looked like notifications that "stopped
 * working" rather than a subscription that had been replaced.
 *
 * Resubscribing here restores delivery immediately; an open client re-registers
 * the new endpoint, and if there is none, the app's own restore on next load
 * picks up the subscription this handler created.
 */
self.addEventListener('pushsubscriptionchange', (event: Event) => {
  const change = event as PushSubscriptionChangeEvent;
  (event as ExtendableEvent).waitUntil(
    (async () => {
      const key = change.oldSubscription?.options?.applicationServerKey;
      if (!key) return;
      const renewed = await self.registration.pushManager
        .subscribe({ userVisibleOnly: true, applicationServerKey: key })
        .catch(() => null);
      if (!renewed) return;
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      windows.forEach((client) =>
        client.postMessage({ type: 'push:resubscribed', subscription: renewed.toJSON() }),
      );
    })(),
  );
});

interface PushSubscriptionChangeEvent extends ExtendableEvent {
  readonly oldSubscription: PushSubscription | null;
  readonly newSubscription: PushSubscription | null;
}

self.addEventListener('push', (event: PushEvent) => {
  event.waitUntil(
    (async () => {
      const payload = event.data?.json() as PushPayload | undefined;
      if (!payload) return;

      // A read on another device: take back this channel's notification rather
      // than showing anything new.
      if (payload.kind === 'read') {
        const existing = await self.registration.getNotifications({ tag: payload.tag });
        existing.forEach((n) => n.close());
        return;
      }

      // Redundant only when the user is looking at this very conversation. A
      // focused tab reading something else is not told about a message at all
      // if we stop here, and that is a real hole rather than a tidy-up: the
      // socket the app relies on instead is exactly what has failed whenever a
      // push is the thing that got through. Security notices never collapse -
      // they are about the account, not the conversation on screen.
      if (payload.kind !== 'security' && (await isConversationOnScreen(payload.href))) return;

      let body = payload.body;
      if (payload.ciphertext) {
        body = (await decryptWithSync(payload)) ?? fallbackBody(payload);
      }

      await self.registration.showNotification(payload.title || payload.senderName, {
        body,
        icon: payload.icon || '/icon.svg',
        badge: '/icon.svg',
        tag: payload.tag,
        renotify: payload.kind === 'call' || payload.kind === 'security',
        requireInteraction: payload.kind === 'call' || payload.kind === 'security',
        data: { href: payload.href },
      } as NotificationOptions);
    })(),
  );
});

self.addEventListener('notificationclick', (event: NotificationEvent) => {
  event.notification.close();
  const href = (event.notification.data as { href?: string } | null)?.href || '/';
  event.waitUntil(
    (async () => {
      const url = new URL(href, self.location.origin).href;
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
      for (const client of windows) {
        if (new URL(client.url).origin === self.location.origin) {
          await (client as WindowClient).focus();
          client.postMessage({ type: 'notification:navigate', href });
          return;
        }
      }
      await self.clients.openWindow(url);
    })(),
  );
});
