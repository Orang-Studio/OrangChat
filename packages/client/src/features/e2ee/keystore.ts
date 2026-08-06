import { randomBytes } from '@orangchat/shared';

const DB_NAME = 'orangchat-e2ee';
const DB_VERSION = 4;
const IDENTITY = 'identity';
const PINS = 'pins';
const EPOCH_KEYS = 'epochKeys';
const HEADS = 'heads';
const MESSAGES = 'messages';
const QUEUE = 'queue';
const DEVICE_KEYS = 'deviceKeys';
const OFFLINE = 'offline';
const SETTINGS = 'settings';

export interface LocalIdentity {
  userId: string;
  deviceId: string;
  identityGeneration: string;
  ikSig: CryptoKeyPair;
  ikDh: CryptoKeyPair;
  ikSigPub: Uint8Array;
  ikDhPub: Uint8Array;
}

export interface PinnedPeer {
  userId: string;
  genesisCommitment: string;
  verifiedAt: string | null;
  headSeq: number;
  headHash: string;
  /**
   * Every entry hash this device has replayed for the account, indexed by
   * sequence. Keeping the whole (very short) list is what lets a head gossiped
   * by somebody else be checked at *its* sequence rather than only at ours - a
   * server that equivocated at seq 2 and then moved on would otherwise be
   * invisible once our own head passed it.
   */
  entryHashes: string[];
}

interface StoredIdentity {
  id: 'self';
  userId: string;
  deviceId: string;
  identityGeneration: string;
  ikSigPrivate: CryptoKey;
  ikSigPublic: CryptoKey;
  ikDhPrivate: CryptoKey;
  ikDhPublic: CryptoKey;
  ikSigPub: ArrayBuffer;
  ikDhPub: ArrayBuffer;
}

interface StoredEpochKey {
  id: string;
  channelId: string;
  epoch: number;
  nonce: ArrayBuffer;
  wrapped: ArrayBuffer;
  nextSeq: number;
}

let dbPromise: Promise<IDBDatabase> | null = null;

function openDb(): Promise<IDBDatabase> {
  if (dbPromise) return dbPromise;
  dbPromise = new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, DB_VERSION);
    request.onupgradeneeded = () => {
      const db = request.result;
      if (!db.objectStoreNames.contains(IDENTITY))
        db.createObjectStore(IDENTITY, { keyPath: 'id' });
      if (!db.objectStoreNames.contains(PINS)) db.createObjectStore(PINS, { keyPath: 'userId' });
      if (!db.objectStoreNames.contains(EPOCH_KEYS)) {
        const store = db.createObjectStore(EPOCH_KEYS, { keyPath: 'id' });
        store.createIndex('channelId', 'channelId');
      }
      if (!db.objectStoreNames.contains(HEADS)) db.createObjectStore(HEADS, { keyPath: 'userId' });
      if (!db.objectStoreNames.contains(MESSAGES)) {
        const store = db.createObjectStore(MESSAGES, { keyPath: 'id' });
        store.createIndex('channelId', 'channelId');
      }
      if (!db.objectStoreNames.contains(QUEUE)) db.createObjectStore(QUEUE, { keyPath: 'id' });
      if (!db.objectStoreNames.contains(DEVICE_KEYS)) {
        db.createObjectStore(DEVICE_KEYS, { keyPath: 'id' });
      }
      if (!db.objectStoreNames.contains(OFFLINE)) {
        db.createObjectStore(OFFLINE, { keyPath: 'id' });
      }
      // Device-local preferences the service worker has to be able to read.
      // A worker cannot see localStorage, so anything that has to hold while
      // the app is closed - which is exactly when a push arrives - lives here.
      if (!db.objectStoreNames.contains(SETTINGS)) {
        db.createObjectStore(SETTINGS, { keyPath: 'key' });
      }
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
  });
  return dbPromise;
}

function run<T>(
  store: string,
  mode: IDBTransactionMode,
  body: (store: IDBObjectStore) => IDBRequest<T>,
): Promise<T> {
  return openDb().then(
    (db) =>
      new Promise<T>((resolve, reject) => {
        const tx = db.transaction(store, mode);
        const request = body(tx.objectStore(store));
        request.onsuccess = () => resolve(request.result);
        request.onerror = () => reject(request.error);
      }),
  );
}

export async function loadIdentity(): Promise<LocalIdentity | null> {
  const stored = await run<StoredIdentity | undefined>(IDENTITY, 'readonly', (s) => s.get('self'));
  if (!stored) return null;
  return {
    userId: stored.userId,
    deviceId: stored.deviceId,
    identityGeneration: stored.identityGeneration,
    ikSig: { privateKey: stored.ikSigPrivate, publicKey: stored.ikSigPublic },
    ikDh: { privateKey: stored.ikDhPrivate, publicKey: stored.ikDhPublic },
    ikSigPub: new Uint8Array(stored.ikSigPub),
    ikDhPub: new Uint8Array(stored.ikDhPub),
  };
}

export async function saveIdentity(identity: LocalIdentity): Promise<void> {
  const record: StoredIdentity = {
    id: 'self',
    userId: identity.userId,
    deviceId: identity.deviceId,
    identityGeneration: identity.identityGeneration,
    ikSigPrivate: identity.ikSig.privateKey,
    ikSigPublic: identity.ikSig.publicKey,
    ikDhPrivate: identity.ikDh.privateKey,
    ikDhPublic: identity.ikDh.publicKey,
    ikSigPub: identity.ikSigPub.slice().buffer,
    ikDhPub: identity.ikDhPub.slice().buffer,
  };
  await run(IDENTITY, 'readwrite', (s) => s.put(record));
}

export async function pinPeer(pin: PinnedPeer): Promise<void> {
  await run(PINS, 'readwrite', (s) => s.put(pin));
}

export async function getPin(userId: string): Promise<PinnedPeer | null> {
  const pin = await run<PinnedPeer | undefined>(PINS, 'readonly', (s) => s.get(userId));
  return pin ?? null;
}

/**
 * Drops everything this device had committed to about an account, putting it
 * back to first-contact state. The only legitimate caller is someone accepting
 * an identity change out loud - a pin is a commitment, and quietly discarding
 * one is indistinguishable from losing the memory that catches a server out.
 */
export async function deletePin(userId: string): Promise<void> {
  await run(PINS, 'readwrite', (s) => s.delete(userId));
}

/**
 * The AES key that seals conversation keys at rest. Non-extractable, so an
 * exfiltrated IndexedDB dump is inert without this origin's key handles.
 */
async function vaultKey(): Promise<CryptoKey> {
  const existing = await run<{ id: string; key: CryptoKey } | undefined>(HEADS, 'readonly', (s) =>
    s.get('vault'),
  );
  if (existing) return (existing as unknown as { key: CryptoKey }).key;
  const key = await crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, [
    'encrypt',
    'decrypt',
  ]);
  await run(HEADS, 'readwrite', (s) => s.put({ userId: 'vault', key }));
  return key;
}

export async function saveEpochKey(
  channelId: string,
  epoch: number,
  conversationKey: Uint8Array,
): Promise<void> {
  const key = await vaultKey();
  const nonce = randomBytes(12);
  const wrapped = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce as BufferSource },
    key,
    conversationKey as BufferSource,
  );
  const record: StoredEpochKey = {
    id: `${channelId}:${epoch}`,
    channelId,
    epoch,
    nonce: nonce.slice().buffer,
    wrapped,
    nextSeq: 0,
  };
  await run(EPOCH_KEYS, 'readwrite', (s) => s.put(record));
}

/**
 * Hands out the next per-message sequence number and persists the increment in
 * the same transaction. The whole safety argument for the fixed message nonce
 * rests on this number never repeating for one (conversation key, device), so a
 * counter that could be read twice would be a nonce reuse, not a hiccup.
 */
export async function reserveSeq(channelId: string, epoch: number): Promise<number> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(EPOCH_KEYS, 'readwrite');
    const store = tx.objectStore(EPOCH_KEYS);
    const get = store.get(`${channelId}:${epoch}`);
    get.onsuccess = () => {
      const record = get.result as StoredEpochKey | undefined;
      if (!record) {
        reject(new Error('e2ee: no conversation key for this epoch'));
        return;
      }
      const seq = record.nextSeq ?? 0;
      record.nextSeq = seq + 1;
      const put = store.put(record);
      put.onsuccess = () => resolve(seq);
      put.onerror = () => reject(put.error);
    };
    get.onerror = () => reject(get.error);
  });
}

export async function loadEpochKey(channelId: string, epoch: number): Promise<Uint8Array | null> {
  const record = await run<StoredEpochKey | undefined>(EPOCH_KEYS, 'readonly', (s) =>
    s.get(`${channelId}:${epoch}`),
  );
  if (!record) return null;
  const key = await vaultKey();
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: new Uint8Array(record.nonce) },
    key,
    record.wrapped,
  );
  return new Uint8Array(plaintext);
}

export interface EpochKeyEntry {
  channelId: string;
  epoch: number;
  key: Uint8Array;
}

/**
 * Every conversation key this device holds - what actually crosses the room in
 * a transfer (§4). The identity key does not and cannot: it is non-extractable,
 * and the new device generates its own. This is the part that makes old
 * messages readable there.
 */
export async function allEpochKeys(): Promise<EpochKeyEntry[]> {
  const records = await run<StoredEpochKey[]>(EPOCH_KEYS, 'readonly', (s) => s.getAll());
  const key = await vaultKey();
  const out: EpochKeyEntry[] = [];
  for (const record of records) {
    try {
      const plaintext = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv: new Uint8Array(record.nonce) },
        key,
        record.wrapped,
      );
      out.push({
        channelId: record.channelId,
        epoch: record.epoch,
        key: new Uint8Array(plaintext),
      });
    } catch {
      // A record this origin can no longer open is not one to pass on.
    }
  }
  return out;
}

export async function knownEpochs(channelId: string): Promise<number[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(EPOCH_KEYS, 'readonly');
    const request = tx.objectStore(EPOCH_KEYS).index('channelId').getAll(channelId);
    request.onsuccess = () =>
      resolve((request.result as StoredEpochKey[]).map((r) => r.epoch).sort((a, b) => a - b));
    request.onerror = () => reject(request.error);
  });
}

export interface SealedRecord {
  nonce: ArrayBuffer;
  wrapped: ArrayBuffer;
}

/**
 * Seals anything else this device keeps at rest under the same non-extractable
 * vault key. The local message cache is load-bearing now - search, reply
 * previews and notification history all read from it - so it is a second copy
 * of every decrypted message and has to be no easier to lift than the first.
 */
export async function sealLocal(plaintext: Uint8Array): Promise<SealedRecord> {
  const key = await vaultKey();
  const nonce = randomBytes(12);
  const wrapped = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: nonce as BufferSource },
    key,
    plaintext as BufferSource,
  );
  return { nonce: nonce.slice().buffer, wrapped };
}

export async function openLocal(record: SealedRecord): Promise<Uint8Array> {
  const key = await vaultKey();
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: new Uint8Array(record.nonce) },
    key,
    record.wrapped,
  );
  return new Uint8Array(plaintext);
}

export interface CachedMessageRecord extends SealedRecord {
  id: string;
  channelId: string;
  authorId: string;
  createdAt: string;
}

export async function putCachedMessage(record: CachedMessageRecord): Promise<void> {
  await run(MESSAGES, 'readwrite', (s) => s.put(record));
}

export async function getCachedMessage(id: string): Promise<CachedMessageRecord | null> {
  const record = await run<CachedMessageRecord | undefined>(MESSAGES, 'readonly', (s) => s.get(id));
  return record ?? null;
}

export async function deleteCachedMessage(id: string): Promise<void> {
  await run(MESSAGES, 'readwrite', (s) => s.delete(id));
}

export async function cachedMessagesIn(channelId: string): Promise<CachedMessageRecord[]> {
  const db = await openDb();
  return new Promise((resolve, reject) => {
    const tx = db.transaction(MESSAGES, 'readonly');
    const request = tx.objectStore(MESSAGES).index('channelId').getAll(channelId);
    request.onsuccess = () => resolve(request.result as CachedMessageRecord[]);
    request.onerror = () => reject(request.error);
  });
}

export async function allCachedMessages(): Promise<CachedMessageRecord[]> {
  return run<CachedMessageRecord[]>(MESSAGES, 'readonly', (s) => s.getAll());
}

export interface OfflineSnapshotRecord extends SealedRecord {
  id: 'app';
  userId: string;
}

export async function putOfflineSnapshot(record: OfflineSnapshotRecord): Promise<void> {
  await run(OFFLINE, 'readwrite', (s) => s.put(record));
}

export async function getOfflineSnapshot(): Promise<OfflineSnapshotRecord | null> {
  const record = await run<OfflineSnapshotRecord | undefined>(OFFLINE, 'readonly', (s) =>
    s.get('app'),
  );
  return record ?? null;
}

export async function clearOfflineSnapshot(): Promise<void> {
  await run(OFFLINE, 'readwrite', (s) => s.clear());
}

export interface QueuedRecord extends SealedRecord {
  id: string;
  authorId: string;
  channelId: string;
  queuedAt: string;
}

/**
 * Messages that strict mode is holding back. §6.5 is specific that these are
 * "queued locally, encrypted at rest, and never handed to the server" - not sent
 * with a warning - so they are sealed under the vault key exactly like the
 * conversation keys are.
 */
export async function putQueued(record: QueuedRecord): Promise<void> {
  await run(QUEUE, 'readwrite', (s) => s.put(record));
}

export async function allQueued(): Promise<QueuedRecord[]> {
  return run<QueuedRecord[]>(QUEUE, 'readonly', (s) => s.getAll());
}

export async function deleteQueued(id: string): Promise<void> {
  await run(QUEUE, 'readwrite', (s) => s.delete(id));
}

export interface KnownDeviceKey {
  id: string;
  userId: string;
  /** base64 SPKI. */
  ikSigPub: string;
}

/**
 * Signing keys of devices whose whole chain this app has already replayed and
 * accepted. Nothing writes here without that check having passed first.
 *
 * The service worker is why this exists: §2 makes verifying the sender's
 * signature mandatory before a message is rendered, and a notification is a
 * rendering. Without a local copy of the sender's key the notification path
 * would either skip the check or show nothing, and neither is acceptable.
 */
export async function rememberDeviceKeys(devices: readonly KnownDeviceKey[]): Promise<void> {
  const db = await openDb();
  await new Promise<void>((resolve, reject) => {
    const tx = db.transaction(DEVICE_KEYS, 'readwrite');
    const store = tx.objectStore(DEVICE_KEYS);
    for (const device of devices) store.put(device);
    tx.oncomplete = () => resolve();
    tx.onerror = () => reject(tx.error);
  });
}

/** Whether notifications on this device may show what a message said. */
export const NOTIFICATION_PREVIEWS = 'notificationPreviews';

/**
 * Read a device-local setting the service worker also reads.
 *
 * Defaulting to `fallback` on a missing record matters more than it looks: a
 * browser that has never opened the app has no store to read, and a push that
 * arrives first must behave like the default rather than like "off".
 */
export async function getSetting(key: string, fallback: boolean): Promise<boolean> {
  const record = await run<{ key: string; value: boolean } | undefined>(SETTINGS, 'readonly', (s) =>
    s.get(key),
  ).catch(() => undefined);
  return typeof record?.value === 'boolean' ? record.value : fallback;
}

export async function setSetting(key: string, value: boolean): Promise<void> {
  await run(SETTINGS, 'readwrite', (s) => s.put({ key, value }));
}
