/// <reference lib="webworker" />
import {
  decodeEnvelope,
  decodeMessagePayload,
  fromBase64,
  importSigningPublicKey,
  openMessage,
} from '@orangchat/shared/e2ee';



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


const MEDIA_CACHE = 'orangchat-media-v1';


const MAX_ENTRIES = 600;


const MAX_BYTES = 4 * 1024 * 1024;


function isCacheableMedia(url: URL): boolean {
  return (
    url.pathname.startsWith(ASSET_ROUTE) ||
    url.pathname === '/api/media/proxy' ||
    url.pathname.startsWith('/uploads/')
  );
}

const ASSET_ROUTE = '/api/media/asset/';


function isMutableAsset(url: URL): boolean {
  return url.pathname.startsWith(ASSET_ROUTE);
}


async function revalidate(cache: Cache, request: Request): Promise<void> {
  try {
    const response = await fetch(request, { cache: 'no-cache' });
    if (!(await cacheable(response))) return;
    await cache.put(request, response);
    await trim(cache);
  } catch {

  }
}


async function trim(cache: Cache): Promise<void> {
  const keys = await cache.keys();
  if (keys.length <= MAX_ENTRIES) return;
  await Promise.all(keys.slice(0, keys.length - MAX_ENTRIES).map((key) => cache.delete(key)));
}

async function cacheable(response: Response): Promise<boolean> {
  if (response.status !== 200 || response.type === 'opaque') return false;
  const declared = Number(response.headers.get('content-length'));
  return Number.isFinite(declared) && declared > 0 ? declared <= MAX_BYTES : true;
}

async function fromCacheOrNetwork(event: FetchEvent): Promise<Response> {
  const request = event.request;
  const cache = await caches.open(MEDIA_CACHE);
  const hit = await cache.match(request);
  if (hit) {
    if (isMutableAsset(new URL(request.url))) event.waitUntil(revalidate(cache, request));
    return hit;
  }

  const response = await fetch(request);
  if (await cacheable(response)) {
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

  if (request.headers.has('range')) return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin || !isCacheableMedia(url)) return;

  event.respondWith(fromCacheOrNetwork(event));
});


self.addEventListener('message', (event: ExtendableMessageEvent) => {
  if ((event.data as { type?: string } | null)?.type === 'media-cache:clear') {
    event.waitUntil(caches.delete(MEDIA_CACHE));
  }
});

self.addEventListener('install', () => {
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
const SETTINGS = 'settings';
const NOTIFICATION_PREVIEWS = 'notificationPreviews';


const SYNC_RETRY_MS = 2_000;

interface StoredEpochKey {
  channelId: string;
  epoch: number;
  nonce: ArrayBuffer;
  wrapped: ArrayBuffer;
}


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
 * Whether this device is willing to show what a message said.
 *
 * Read per push rather than cached: the worker outlives any one setting change,
 * and a user who has just turned previews off is owed that on the next
 * notification, not the next restart. Written by the app (keystore.setSetting);
 * a browser that has never opened it has no record, and the default stands.
 */
async function previewsEnabled(): Promise<boolean> {
  const db = await openDb();
  if (!db) return true;
  try {
    const record = await get<{ key: string; value: boolean }>(db, SETTINGS, NOTIFICATION_PREVIEWS);
    return typeof record?.value === 'boolean' ? record.value : true;
  } finally {
    db.close();
  }
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

      // Told not to show message text: the envelope is never opened - the keys
      // stay unused rather than producing a plaintext this device has been told
      // to withhold - and the server's own preview for an unencrypted channel is
      // dropped with it. A setting that hid only the encrypted half would be a
      // setting that lies. Calls and security notices carry no message text.
      let body = payload.body;
      const previews = payload.kind === 'message' ? await previewsEnabled() : true;
      if (!previews) {
        body = fallbackBody(payload);
      } else if (payload.ciphertext) {
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
