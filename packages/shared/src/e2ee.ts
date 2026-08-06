export const E2EE_VERSION = 1;

export const ENVELOPE_MAGIC = 'OCE1';

export const DOMAIN = {
  ckWrap: 'orangchat/ck-wrap/v1',
  messageKey: 'orangchat/msg/v1',
  messageSig: 'orangchat/msg-sig/v1',
  deviceBundle: 'orangchat/device-bundle/v1',
  genesis: 'orangchat/genesis/v1',
  addDevice: 'orangchat/add-device/v1',
  revoke: 'orangchat/revoke/v1',
  logEntry: 'orangchat/device-log/v1',
  safetyNumber: 'orangchat/safety-number/v1',
  groupSafetyNumber: 'orangchat/group-safety-number/v1',
  pairSas: 'orangchat/pair-sas/v1',
  transferBundle: 'orangchat/transfer-bundle/v1',
  attachment: 'orangchat/attachment/v1',
} as const;

export const MESSAGE_NONCE = new Uint8Array(12);

export const WRAP_NONCE_BYTES = 12;

export const CONVERSATION_KEY_BYTES = 32;

export const PAIR_SECRET_BYTES = 32;

export const EPOCH_MAX_MESSAGES = 10_000;

export type DeviceLogKind = 'genesis' | 'add-device' | 'revoke';

export type E2eePlatform = 'web' | 'android' | 'desktop';

export interface MessageContext {
  channelId: string;
  epoch: number;
  senderDeviceId: string;
  senderUserId: string;
  seq: number;
}

export interface MessageEnvelope {
  version: number;
  epoch: number;
  seq: number;
  senderDeviceId: string;
  senderUserId: string;
  ciphertext: Uint8Array;
  signature: Uint8Array;
}

export interface WrappedKey {
  ephemeralPub: Uint8Array;
  wrapNonce: Uint8Array;
  wrapped: Uint8Array;
}

export interface DeviceBundle {
  userId: string;
  ikSigPub: Uint8Array;
  ikDhPub: Uint8Array;
}

export interface LogChainEntry {
  seq: number;
  payload: Uint8Array;
  entryHash: Uint8Array;
  prevHash: Uint8Array | null;
}

const textEncoder = new TextEncoder();

const textDecoder = new TextDecoder();

function subtle(): SubtleCrypto {
  const c = globalThis.crypto;
  if (!c?.subtle) throw new Error('e2ee: WebCrypto is unavailable in this environment');
  return c.subtle;
}

export function randomBytes(length: number): Uint8Array {
  const out = new Uint8Array(length);
  globalThis.crypto.getRandomValues(out);
  return out;
}

export function concatBytes(...parts: Uint8Array[]): Uint8Array {
  const total = parts.reduce((n, p) => n + p.length, 0);
  const out = new Uint8Array(total);
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

export function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i += 1) diff |= a[i]! ^ b[i]!;
  return diff === 0;
}

export function toBase64(bytes: Uint8Array): string {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 1) binary += String.fromCharCode(bytes[i]!);
  return btoa(binary);
}

export function fromBase64(value: string): Uint8Array {
  const binary = atob(value);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) out[i] = binary.charCodeAt(i);
  return out;
}

export function toHex(bytes: Uint8Array): string {
  let out = '';
  for (let i = 0; i < bytes.length; i += 1) out += bytes[i]!.toString(16).padStart(2, '0');
  return out;
}

type Field = string | number | Uint8Array;

function fieldBytes(value: Field): Uint8Array {
  if (typeof value === 'string') return textEncoder.encode(value);
  if (typeof value === 'number') {
    if (!Number.isSafeInteger(value) || value < 0) {
      throw new Error(`e2ee: ${value} is not encodable as a field`);
    }
    const out = new Uint8Array(8);
    new DataView(out.buffer).setBigUint64(0, BigInt(value), false);
    return out;
  }
  return value;
}

export function encodeFields(...fields: Field[]): Uint8Array {
  const parts: Uint8Array[] = [];
  for (const field of fields) {
    const bytes = fieldBytes(field);
    const header = new Uint8Array(4);
    new DataView(header.buffer).setUint32(0, bytes.length, false);
    parts.push(header, bytes);
  }
  return concatBytes(...parts);
}

async function sha256(input: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await subtle().digest('SHA-256', input as BufferSource));
}

async function sha512(input: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(await subtle().digest('SHA-512', input as BufferSource));
}

export function messageAad(ctx: MessageContext): Uint8Array {
  return encodeFields(
    DOMAIN.messageKey,
    ctx.channelId,
    ctx.epoch,
    ctx.senderDeviceId,
    ctx.senderUserId,
    ctx.seq,
  );
}

export function messageKeyInfo(senderDeviceId: string, seq: number): Uint8Array {
  return encodeFields(DOMAIN.messageKey, senderDeviceId, seq);
}

export async function messageSignaturePayload(
  ctx: MessageContext,
  ciphertext: Uint8Array,
  nonce: Uint8Array,
  aad: Uint8Array,
): Promise<Uint8Array> {
  const digest = await sha256(concatBytes(ciphertext, nonce, aad));
  return encodeFields(
    DOMAIN.messageSig,
    ctx.channelId,
    ctx.epoch,
    ctx.senderDeviceId,
    ctx.senderUserId,
    ctx.seq,
    digest,
  );
}

export function encodeEnvelope(envelope: MessageEnvelope): Uint8Array {
  if (envelope.version !== E2EE_VERSION) {
    throw new Error(`e2ee: cannot encode envelope version ${envelope.version}`);
  }
  const magic = textEncoder.encode(ENVELOPE_MAGIC);
  const head = new Uint8Array(1);
  head[0] = envelope.version;
  return concatBytes(
    magic,
    head,
    encodeFields(
      envelope.epoch,
      envelope.seq,
      envelope.senderDeviceId,
      envelope.senderUserId,
      envelope.signature,
      envelope.ciphertext,
    ),
  );
}

export function decodeEnvelope(bytes: Uint8Array): MessageEnvelope {
  if (bytes.length < 5 || textDecoder.decode(bytes.subarray(0, 4)) !== ENVELOPE_MAGIC) {
    throw new Error('e2ee: not a message envelope');
  }
  const version = bytes[4]!;
  if (version !== E2EE_VERSION) {
    throw new Error(`e2ee: unsupported envelope version ${version}`);
  }

  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let at = 5;
  const next = (): Uint8Array => {
    if (at + 4 > bytes.length) throw new Error('e2ee: truncated envelope');
    const length = view.getUint32(at, false);
    at += 4;
    if (at + length > bytes.length) throw new Error('e2ee: truncated envelope');
    const slice = bytes.slice(at, at + length);
    at += length;
    return slice;
  };
  const nextNumber = (): number => {
    const raw = next();
    if (raw.length !== 8) throw new Error('e2ee: malformed integer in envelope');
    const value = new DataView(raw.buffer, raw.byteOffset, raw.byteLength).getBigUint64(0, false);
    if (value > BigInt(Number.MAX_SAFE_INTEGER)) throw new Error('e2ee: integer out of range');
    return Number(value);
  };

  const epoch = nextNumber();
  const seq = nextNumber();
  const senderDeviceId = textDecoder.decode(next());
  const senderUserId = textDecoder.decode(next());
  const signature = next();
  const ciphertext = next();
  if (at !== bytes.length) throw new Error('e2ee: trailing bytes in envelope');

  return { version, epoch, seq, senderDeviceId, senderUserId, ciphertext, signature };
}

export const PAYLOAD_VERSION = 1;

/**
 * An attachment's real identity: its key, its name and its type all live in the
 * message plaintext, so the server's copy of the row is a storage id and a byte
 * count and nothing else.
 */
export interface SealedAttachmentRef {
  /** Client-minted id the ciphertext is bound to; not the server's row id. */
  fileId: string;
  /** The uploaded row the sealed bytes live in. */
  attachmentId: string;
  /** base64 AES-256 key for this file alone. */
  key: string;
  /** base64 12-byte nonce. */
  nonce: string;
  filename: string;
  contentType: string;
  size: number;
  /** Seconds, probed on the sender's device before the file was sealed. */
  duration?: number;
  width?: number;
  height?: number;
  spoiler?: boolean;
  /** A client-made preview, sealed as its own blob under its own key. */
  thumb?: {
    fileId: string;
    attachmentId: string;
    key: string;
    nonce: string;
    contentType: string;
    size: number;
  };
}

/**
 * A log head as one participant saw it, carried inside an already-encrypted
 * message. A server that equivocates has to keep every lie consistent across
 * every device of every participant forever; one mismatch is a hard failure.
 */
export interface GossipedHead {
  userId: string;
  seq: number;
  /** base64 entry hash at that sequence. */
  entryHash: string;
}

/**
 * What actually gets encrypted. The server assigns the message cuid after the
 * client has already sealed, so it cannot be bound in the AAD - the payload
 * therefore carries its own timestamp, reply target and client id, and clients
 * display those rather than the server's copy.
 */
export interface MessagePayload {
  v: number;
  text: string;
  /** The sender's own clock, not the server's. */
  sentAt: string;
  /** Client-side id, minted before sealing. */
  clientId: string;
  replyTo?: string | null;
  attachments?: SealedAttachmentRef[];
  heads?: GossipedHead[];
}

export function encodeMessagePayload(payload: MessagePayload): Uint8Array {
  return textEncoder.encode(JSON.stringify({ ...payload, v: PAYLOAD_VERSION }));
}

/**
 * Messages sealed before the payload format existed are bare UTF-8 text. They
 * are still readable, and decoding them as text rather than failing is the
 * difference between old history rendering and old history looking corrupted.
 */
export function decodeMessagePayload(bytes: Uint8Array): MessagePayload {
  const text = textDecoder.decode(bytes);
  const legacy = (): MessagePayload => ({
    v: PAYLOAD_VERSION,
    text,
    sentAt: '',
    clientId: '',
  });
  if (!text.startsWith('{')) return legacy();
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return legacy();
  }
  if (typeof parsed !== 'object' || parsed === null) return legacy();
  const record = parsed as Record<string, unknown>;
  if (typeof record.v !== 'number' || typeof record.text !== 'string') return legacy();
  if (record.v > PAYLOAD_VERSION) {
    throw new Error(`e2ee: this message needs a newer version of OrangChat (payload v${record.v})`);
  }
  return {
    v: record.v,
    text: record.text,
    sentAt: typeof record.sentAt === 'string' ? record.sentAt : '',
    clientId: typeof record.clientId === 'string' ? record.clientId : '',
    replyTo: typeof record.replyTo === 'string' ? record.replyTo : null,
    attachments: Array.isArray(record.attachments)
      ? (record.attachments as SealedAttachmentRef[])
      : undefined,
    heads: Array.isArray(record.heads) ? (record.heads as GossipedHead[]) : undefined,
  };
}

export type GossipVerdict = 'agrees' | 'fork' | 'ahead';

/**
 * Checks one gossiped head against the entry hashes this device replayed for
 * that account. `known[seq]` is what we saw at that sequence, so a head we can
 * reach and disagree with is an equivocating server, and a head beyond our own
 * is a log the server has not shown us yet.
 */
export function checkGossipedHead(head: GossipedHead, known: readonly string[]): GossipVerdict {
  if (head.seq >= known.length) return 'ahead';
  return known[head.seq] === head.entryHash ? 'agrees' : 'fork';
}

export function attachmentAad(fileId: string): Uint8Array {
  return encodeFields(DOMAIN.attachment, fileId);
}

export interface SealedFile {
  fileId: string;
  key: Uint8Array;
  nonce: Uint8Array;
  bytes: Uint8Array;
}

/**
 * Seals one file under a key of its own. The filename and content type travel
 * in the message payload rather than beside the bytes, so the storage tier only
 * ever holds a length and an opaque blob.
 */
export async function sealFile(plaintext: Uint8Array): Promise<SealedFile> {
  const fileId = toHex(randomBytes(16));
  const raw = randomBytes(32);
  const nonce = randomBytes(WRAP_NONCE_BYTES);
  const key = await subtle().importKey('raw', raw as BufferSource, 'AES-GCM', false, ['encrypt']);
  const bytes = new Uint8Array(
    await subtle().encrypt(
      {
        name: 'AES-GCM',
        iv: nonce as BufferSource,
        additionalData: attachmentAad(fileId) as BufferSource,
      },
      key,
      plaintext as BufferSource,
    ),
  );
  return { fileId, key: raw, nonce, bytes };
}

export async function openFile(
  rawKey: Uint8Array,
  nonce: Uint8Array,
  fileId: string,
  ciphertext: Uint8Array,
): Promise<Uint8Array> {
  const key = await subtle().importKey('raw', rawKey as BufferSource, 'AES-GCM', false, [
    'decrypt',
  ]);
  const plaintext = await subtle().decrypt(
    {
      name: 'AES-GCM',
      iv: nonce as BufferSource,
      additionalData: attachmentAad(fileId) as BufferSource,
    },
    key,
    ciphertext as BufferSource,
  );
  return new Uint8Array(plaintext);
}

async function hkdf(
  ikm: Uint8Array,
  salt: Uint8Array,
  info: Uint8Array,
  length: number,
): Promise<Uint8Array> {
  const key = await subtle().importKey('raw', ikm as BufferSource, 'HKDF', false, ['deriveBits']);
  const bits = await subtle().deriveBits(
    { name: 'HKDF', hash: 'SHA-256', salt: salt as BufferSource, info: info as BufferSource },
    key,
    length * 8,
  );
  return new Uint8Array(bits);
}

/**
 * The per-message AES key. It is safe to disclose this one derived value when a
 * recipient deliberately reports a message: HKDF does not reveal the
 * conversation key or any other message key.
 */
export async function deriveMessageKeyBytes(
  conversationKey: Uint8Array,
  senderDeviceId: string,
  seq: number,
): Promise<Uint8Array> {
  return hkdf(conversationKey, new Uint8Array(0), messageKeyInfo(senderDeviceId, seq), 32);
}

export async function deriveMessageKey(
  conversationKey: Uint8Array,
  senderDeviceId: string,
  seq: number,
): Promise<CryptoKey> {
  const raw = await deriveMessageKeyBytes(conversationKey, senderDeviceId, seq);
  return subtle().importKey('raw', raw as BufferSource, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

export async function sealMessage(
  conversationKey: Uint8Array,
  ctx: MessageContext,
  plaintext: Uint8Array,
  signingKey: CryptoKey,
): Promise<MessageEnvelope> {
  const key = await deriveMessageKey(conversationKey, ctx.senderDeviceId, ctx.seq);
  const aad = messageAad(ctx);
  const ciphertext = new Uint8Array(
    await subtle().encrypt(
      { name: 'AES-GCM', iv: MESSAGE_NONCE as BufferSource, additionalData: aad as BufferSource },
      key,
      plaintext as BufferSource,
    ),
  );
  const payload = await messageSignaturePayload(ctx, ciphertext, MESSAGE_NONCE, aad);
  const signature = new Uint8Array(
    await subtle().sign({ name: 'ECDSA', hash: 'SHA-256' }, signingKey, payload as BufferSource),
  );
  return {
    version: E2EE_VERSION,
    epoch: ctx.epoch,
    seq: ctx.seq,
    senderDeviceId: ctx.senderDeviceId,
    senderUserId: ctx.senderUserId,
    ciphertext,
    signature,
  };
}

export async function openMessage(
  conversationKey: Uint8Array,
  channelId: string,
  envelope: MessageEnvelope,
  senderIkSigPub: CryptoKey,
): Promise<Uint8Array> {
  const ctx: MessageContext = {
    channelId,
    epoch: envelope.epoch,
    senderDeviceId: envelope.senderDeviceId,
    senderUserId: envelope.senderUserId,
    seq: envelope.seq,
  };
  const aad = messageAad(ctx);
  const payload = await messageSignaturePayload(ctx, envelope.ciphertext, MESSAGE_NONCE, aad);
  const signed = await subtle().verify(
    { name: 'ECDSA', hash: 'SHA-256' },
    senderIkSigPub,
    envelope.signature as BufferSource,
    payload as BufferSource,
  );
  if (!signed) throw new Error('e2ee: message signature does not verify');

  const key = await deriveMessageKey(conversationKey, envelope.senderDeviceId, envelope.seq);
  const plaintext = await subtle().decrypt(
    { name: 'AES-GCM', iv: MESSAGE_NONCE as BufferSource, additionalData: aad as BufferSource },
    key,
    envelope.ciphertext as BufferSource,
  );
  return new Uint8Array(plaintext);
}

export function wrapAad(epochId: string, deviceId: string): Uint8Array {
  return encodeFields(DOMAIN.ckWrap, epochId, deviceId);
}

async function wrapKeyFor(sharedSecret: Uint8Array, epochId: string): Promise<CryptoKey> {
  const raw = await hkdf(
    sharedSecret,
    textEncoder.encode(epochId),
    textEncoder.encode(DOMAIN.ckWrap),
    32,
  );
  return subtle().importKey('raw', raw as BufferSource, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

export async function wrapConversationKey(
  conversationKey: Uint8Array,
  epochId: string,
  deviceId: string,
  recipientIkDhPub: CryptoKey,
): Promise<WrappedKey> {
  const ephemeral = await subtle().generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, [
    'deriveBits',
  ]);
  const shared = new Uint8Array(
    await subtle().deriveBits(
      { name: 'ECDH', public: recipientIkDhPub },
      ephemeral.privateKey,
      256,
    ),
  );
  const key = await wrapKeyFor(shared, epochId);
  const wrapNonce = randomBytes(WRAP_NONCE_BYTES);
  const wrapped = new Uint8Array(
    await subtle().encrypt(
      {
        name: 'AES-GCM',
        iv: wrapNonce as BufferSource,
        additionalData: wrapAad(epochId, deviceId) as BufferSource,
      },
      key,
      conversationKey as BufferSource,
    ),
  );
  const ephemeralPub = new Uint8Array(await subtle().exportKey('spki', ephemeral.publicKey));
  return { ephemeralPub, wrapNonce, wrapped };
}

export async function unwrapConversationKey(
  ourIkDhPriv: CryptoKey,
  epochId: string,
  deviceId: string,
  envelope: WrappedKey,
): Promise<Uint8Array> {
  const ephemeralPub = await subtle().importKey(
    'spki',
    envelope.ephemeralPub as BufferSource,
    { name: 'ECDH', namedCurve: 'P-256' },
    false,
    [],
  );
  const shared = new Uint8Array(
    await subtle().deriveBits({ name: 'ECDH', public: ephemeralPub }, ourIkDhPriv, 256),
  );
  const key = await wrapKeyFor(shared, epochId);
  const plaintext = await subtle().decrypt(
    {
      name: 'AES-GCM',
      iv: envelope.wrapNonce as BufferSource,
      additionalData: wrapAad(epochId, deviceId) as BufferSource,
    },
    key,
    envelope.wrapped as BufferSource,
  );
  return new Uint8Array(plaintext);
}

export function deviceBundleBytes(bundle: DeviceBundle): Uint8Array {
  return encodeFields(DOMAIN.deviceBundle, bundle.userId, bundle.ikSigPub, bundle.ikDhPub);
}

export function genesisStatementBytes(
  bundle: DeviceBundle,
  identityGeneration: string,
): Uint8Array {
  return encodeFields(
    DOMAIN.genesis,
    bundle.userId,
    bundle.ikSigPub,
    bundle.ikDhPub,
    identityGeneration,
  );
}

export async function genesisCommitment(
  bundle: DeviceBundle,
  identityGeneration: string,
): Promise<Uint8Array> {
  return sha256(genesisStatementBytes(bundle, identityGeneration));
}

export function addDeviceStatementBytes(bundle: DeviceBundle, transferId: string): Uint8Array {
  return encodeFields(DOMAIN.addDevice, bundle.userId, bundle.ikSigPub, bundle.ikDhPub, transferId);
}

export function revokeStatementBytes(
  userId: string,
  deviceId: string,
  revokedAt: string,
): Uint8Array {
  return encodeFields(DOMAIN.revoke, userId, deviceId, revokedAt);
}

export async function logEntryHash(
  prevHash: Uint8Array | null,
  payload: Uint8Array,
): Promise<Uint8Array> {
  return sha256(encodeFields(DOMAIN.logEntry, prevHash ?? new Uint8Array(0), payload));
}

export function logSignatureBytes(entryHash: Uint8Array): Uint8Array {
  return encodeFields(DOMAIN.logEntry, entryHash);
}

export async function importSigningPublicKey(spki: Uint8Array): Promise<CryptoKey> {
  return subtle().importKey(
    'spki',
    spki as BufferSource,
    { name: 'ECDSA', namedCurve: 'P-256' },
    true,
    ['verify'],
  );
}

export async function importAgreementPublicKey(spki: Uint8Array): Promise<CryptoKey> {
  return subtle().importKey(
    'spki',
    spki as BufferSource,
    { name: 'ECDH', namedCurve: 'P-256' },
    true,
    [],
  );
}

async function verifySig(
  spki: Uint8Array,
  message: Uint8Array,
  signature: Uint8Array,
): Promise<boolean> {
  try {
    const key = await importSigningPublicKey(spki);
    return await subtle().verify(
      { name: 'ECDSA', hash: 'SHA-256' },
      key,
      signature as BufferSource,
      message as BufferSource,
    );
  } catch {
    return false;
  }
}

function startsWith(haystack: Uint8Array, prefix: Uint8Array): boolean {
  return (
    haystack.length >= prefix.length && bytesEqual(haystack.subarray(0, prefix.length), prefix)
  );
}

export type LogChainProblem =
  | { kind: 'seq-gap'; at: number }
  | { kind: 'prev-mismatch'; at: number }
  | { kind: 'hash-mismatch'; at: number }
  | { kind: 'genesis-has-prev' }
  | { kind: 'empty' };

export async function verifyLogChain(
  entries: readonly LogChainEntry[],
): Promise<LogChainProblem | null> {
  if (entries.length === 0) return { kind: 'empty' };
  let prev: Uint8Array | null = null;
  for (let i = 0; i < entries.length; i += 1) {
    const entry = entries[i]!;
    if (entry.seq !== i) return { kind: 'seq-gap', at: i };
    if (i === 0 && entry.prevHash !== null) return { kind: 'genesis-has-prev' };
    if (i > 0 && (entry.prevHash === null || !bytesEqual(entry.prevHash, prev!))) {
      return { kind: 'prev-mismatch', at: i };
    }
    const expected = await logEntryHash(entry.prevHash, entry.payload);
    if (!bytesEqual(expected, entry.entryHash)) return { kind: 'hash-mismatch', at: i };
    prev = entry.entryHash;
  }
  return null;
}

export interface DeviceRecord {
  id: string;
  ikSigPub: Uint8Array;
  ikDhPub: Uint8Array;
  bundleSig: Uint8Array;
  authorizedBy: string | null;
  authorizationSig: Uint8Array | null;
  revoked: boolean;
}

export interface LogRecord extends LogChainEntry {
  kind: DeviceLogKind;
  signature: Uint8Array;
}

export type IdentityProblem =
  | { kind: 'chain'; problem: LogChainProblem }
  | { kind: 'no-genesis' }
  | { kind: 'multiple-genesis' }
  | { kind: 'bad-bundle-signature'; deviceId: string }
  | { kind: 'unknown-statement'; seq: number }
  | { kind: 'bad-entry-signature'; seq: number }
  | { kind: 'unauthorized-signer'; seq: number }
  | { kind: 'bad-authorization'; deviceId: string }
  | { kind: 'unintroduced-device'; deviceId: string };

export interface VerifiedIdentity {
  genesisDeviceId: string;
  genesisCommitment: Uint8Array;
  /** The only devices a conversation key may be wrapped to. */
  authorizedDeviceIds: string[];
  head: { seq: number; entryHash: Uint8Array };
}

/**
 * Replays a user's device log and decides, without trusting the server, which
 * devices legitimately belong to them. A device counts only when an already
 * authorized device signed it into the log, so no amount of database access can
 * introduce one.
 */
export async function verifyIdentity(
  userId: string,
  devices: readonly DeviceRecord[],
  log: readonly LogRecord[],
): Promise<{ ok: true; identity: VerifiedIdentity } | { ok: false; problem: IdentityProblem }> {
  const chain = await verifyLogChain(log);
  if (chain) return { ok: false, problem: { kind: 'chain', problem: chain } };

  const byId = new Map(devices.map((d) => [d.id, d]));
  const genesisDevices = devices.filter((d) => d.authorizedBy === null);
  if (genesisDevices.length === 0) return { ok: false, problem: { kind: 'no-genesis' } };
  if (genesisDevices.length > 1) return { ok: false, problem: { kind: 'multiple-genesis' } };

  for (const device of devices) {
    const bundle: DeviceBundle = {
      userId,
      ikSigPub: device.ikSigPub,
      ikDhPub: device.ikDhPub,
    };
    if (!(await verifySig(device.ikSigPub, deviceBundleBytes(bundle), device.bundleSig))) {
      return { ok: false, problem: { kind: 'bad-bundle-signature', deviceId: device.id } };
    }
  }

  const authorized = new Set<string>();
  const introduced = new Set<string>();
  let genesisCommitment: Uint8Array | null = null;

  const matchByPrefix = (payload: Uint8Array, domain: string): DeviceRecord | undefined =>
    devices.find((d) => startsWith(payload, encodeFields(domain, userId, d.ikSigPub, d.ikDhPub)));

  for (const entry of log) {
    if (entry.kind === 'genesis') {
      const device = matchByPrefix(entry.payload, DOMAIN.genesis);
      if (!device || device.id !== genesisDevices[0]!.id) {
        return { ok: false, problem: { kind: 'unknown-statement', seq: entry.seq } };
      }
      if (
        !(await verifySig(device.ikSigPub, logSignatureBytes(entry.entryHash), entry.signature))
      ) {
        return { ok: false, problem: { kind: 'bad-entry-signature', seq: entry.seq } };
      }
      genesisCommitment = await sha256(entry.payload);
      authorized.add(device.id);
      introduced.add(device.id);
      continue;
    }

    if (entry.kind === 'add-device') {
      const device = matchByPrefix(entry.payload, DOMAIN.addDevice);
      if (!device || device.authorizedBy === null) {
        return { ok: false, problem: { kind: 'unknown-statement', seq: entry.seq } };
      }
      const authorizer = byId.get(device.authorizedBy);
      if (!authorizer || !authorized.has(authorizer.id)) {
        return { ok: false, problem: { kind: 'unauthorized-signer', seq: entry.seq } };
      }
      if (
        !device.authorizationSig ||
        !(await verifySig(authorizer.ikSigPub, entry.payload, device.authorizationSig))
      ) {
        return { ok: false, problem: { kind: 'bad-authorization', deviceId: device.id } };
      }
      if (
        !(await verifySig(authorizer.ikSigPub, logSignatureBytes(entry.entryHash), entry.signature))
      ) {
        return { ok: false, problem: { kind: 'bad-entry-signature', seq: entry.seq } };
      }
      authorized.add(device.id);
      introduced.add(device.id);
      continue;
    }

    const target = devices.find((d) =>
      startsWith(entry.payload, encodeFields(DOMAIN.revoke, userId, d.id)),
    );
    if (!target) return { ok: false, problem: { kind: 'unknown-statement', seq: entry.seq } };

    let signedByAnAuthorizedDevice = false;
    for (const candidate of devices) {
      if (!authorized.has(candidate.id)) continue;
      if (
        await verifySig(candidate.ikSigPub, logSignatureBytes(entry.entryHash), entry.signature)
      ) {
        signedByAnAuthorizedDevice = true;
        break;
      }
    }
    if (!signedByAnAuthorizedDevice) {
      return { ok: false, problem: { kind: 'bad-entry-signature', seq: entry.seq } };
    }
    authorized.delete(target.id);
  }

  for (const device of devices) {
    if (!device.revoked && !introduced.has(device.id)) {
      return { ok: false, problem: { kind: 'unintroduced-device', deviceId: device.id } };
    }
  }

  const last = log[log.length - 1]!;
  return {
    ok: true,
    identity: {
      genesisDeviceId: genesisDevices[0]!.id,
      genesisCommitment: genesisCommitment!,
      authorizedDeviceIds: [...authorized],
      head: { seq: last.seq, entryHash: last.entryHash },
    },
  };
}

function digitGroups(digest: Uint8Array, groups: number): string {
  const out: string[] = [];
  for (let i = 0; i < groups; i += 1) {
    const chunk = digest.subarray(i * 5, i * 5 + 5);
    let value = 0;
    for (const byte of chunk) value = value * 256 + byte;
    out.push((value % 100000).toString().padStart(5, '0'));
  }
  return out.join(' ');
}

export async function safetyNumber(
  commitmentA: Uint8Array,
  commitmentB: Uint8Array,
): Promise<string> {
  const [low, high] =
    toHex(commitmentA) <= toHex(commitmentB)
      ? [commitmentA, commitmentB]
      : [commitmentB, commitmentA];
  const digest = await sha512(encodeFields(DOMAIN.safetyNumber, low, high));
  return digitGroups(digest, 12);
}

export async function groupSafetyNumber(commitments: readonly Uint8Array[]): Promise<string> {
  const sorted = [...commitments].sort((a, b) => (toHex(a) < toHex(b) ? -1 : 1));
  const digest = await sha512(encodeFields(DOMAIN.groupSafetyNumber, ...sorted));
  return digitGroups(digest, 12);
}

export const SAFETY_NUMBER_DIGITS = 60;

/**
 * A typed safety code reduced to the only thing that carries meaning. Somebody
 * copying digits off a phone call produces spaces in the wrong places, dashes,
 * newlines and trailing whitespace; none of that is a mismatch, and treating it
 * as one teaches people that the check is broken rather than that they are.
 *
 * Returns null for anything that is not exactly the full code, which keeps a
 * half-entered number from ever being compared and reported as wrong.
 */
export function normalizeSafetyNumber(input: string): string | null {
  const digits = input.replace(/\D/g, '');
  if (digits.length !== SAFETY_NUMBER_DIGITS) return null;
  return (digits.match(/.{5}/g) ?? []).join(' ');
}

/**
 * Whether a code read out over some other channel is the one this device
 * derived. Both sides compute the number from the same two pinned commitments,
 * so equality here means the identity this device was handed is the identity
 * the other person is holding - which is exactly what a server standing in the
 * middle cannot arrange.
 */
export function safetyNumbersMatch(typed: string, expected: string): boolean {
  const left = normalizeSafetyNumber(typed);
  const right = normalizeSafetyNumber(expected);
  return left !== null && left === right;
}

export async function pairSas(sharedSecret: Uint8Array, pairSecret: Uint8Array): Promise<string> {
  const digest = await sha256(encodeFields(DOMAIN.pairSas, sharedSecret, pairSecret));
  let value = 0;
  for (let i = 0; i < 4; i += 1) value = value * 256 + digest[i]!;
  return (value % 1000000).toString().padStart(6, '0');
}

export async function transferBundleKey(
  sharedSecret: Uint8Array,
  pairSecret: Uint8Array,
): Promise<CryptoKey> {
  const raw = await hkdf(
    concatBytes(sharedSecret, pairSecret),
    new Uint8Array(0),
    textEncoder.encode(DOMAIN.transferBundle),
    32,
  );
  return subtle().importKey('raw', raw as BufferSource, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

export const QR_KIND = {
  signIn: 'login',
  deviceTransfer: 'device-transfer',
  contactVerify: 'verify',
} as const;

export type QrKind = (typeof QR_KIND)[keyof typeof QR_KIND];

export interface DeviceTransferQr {
  kind: typeof QR_KIND.deviceTransfer;
  transferId: string;
  ikSigPub: Uint8Array;
  ikDhPub: Uint8Array;
  pairSecret: Uint8Array;
}

/** Short desktop-displayed invitation scanned by the phone being added. */
export interface DeviceTransferInviteQr {
  kind: typeof QR_KIND.deviceTransfer;
  mode: 'invite';
  transferId: string;
  pairSecret: Uint8Array;
}

export interface ContactVerifyQr {
  kind: typeof QR_KIND.contactVerify;
  userId: string;
  ikSigPub: Uint8Array;
  ikDhPub: Uint8Array;
  genesisCommitment: Uint8Array;
}

export function encodeDeviceTransferQr(payload: Omit<DeviceTransferQr, 'kind'>): string {
  const params = new URLSearchParams({
    v: String(E2EE_VERSION),
    m: 'device',
    t: payload.transferId,
    s: toBase64(payload.ikSigPub),
    d: toBase64(payload.ikDhPub),
    p: toBase64(payload.pairSecret),
  });
  return `orangchat://${QR_KIND.deviceTransfer}?${params.toString()}`;
}

export function encodeDeviceTransferInviteQr(
  payload: Omit<DeviceTransferInviteQr, 'kind' | 'mode'>,
): string {
  const params = new URLSearchParams({
    v: String(E2EE_VERSION),
    m: 'invite',
    t: payload.transferId,
    p: toBase64(payload.pairSecret),
  });
  return `orangchat://${QR_KIND.deviceTransfer}?${params.toString()}`;
}

export function encodeContactVerifyQr(payload: Omit<ContactVerifyQr, 'kind'>): string {
  const params = new URLSearchParams({
    v: String(E2EE_VERSION),
    u: payload.userId,
    s: toBase64(payload.ikSigPub),
    d: toBase64(payload.ikDhPub),
    g: toBase64(payload.genesisCommitment),
  });
  return `orangchat://${QR_KIND.contactVerify}?${params.toString()}`;
}

export function qrKindOf(raw: string): QrKind | null {
  const match = /^orangchat:\/\/([a-z-]+)\?/.exec(raw.trim());
  const kind = match?.[1];
  if (!kind) return null;
  return (Object.values(QR_KIND) as string[]).includes(kind) ? (kind as QrKind) : null;
}

function paramsOf(raw: string, expected: QrKind): URLSearchParams {
  const kind = qrKindOf(raw);
  if (kind === null) throw new Error('e2ee: not an OrangChat code');
  if (kind !== expected) throw new Error(`e2ee: this is a ${kind} code, not a ${expected} code`);
  const query = raw.trim().slice(raw.trim().indexOf('?') + 1);
  const params = new URLSearchParams(query);
  if (params.get('v') !== String(E2EE_VERSION)) {
    throw new Error('e2ee: this code was made by a different app version');
  }
  return params;
}

function required(params: URLSearchParams, key: string): string {
  const value = params.get(key);
  if (!value) throw new Error(`e2ee: code is missing ${key}`);
  return value;
}

function transferId(params: URLSearchParams): string {
  const value = required(params, 't');
  if (!/^[0-9a-f]{32}$/.test(value)) {
    throw new Error('e2ee: code has an invalid transfer id');
  }
  return value;
}

export function decodeDeviceTransferQr(raw: string): DeviceTransferQr {
  const params = paramsOf(raw, QR_KIND.deviceTransfer);
  if (params.get('m') === 'invite') {
    throw new Error('e2ee: this transfer invitation must be scanned by the new device');
  }
  return {
    kind: QR_KIND.deviceTransfer,
    transferId: transferId(params),
    ikSigPub: fromBase64(required(params, 's')),
    ikDhPub: fromBase64(required(params, 'd')),
    pairSecret: fromBase64(required(params, 'p')),
  };
}

export function decodeDeviceTransferInviteQr(raw: string): DeviceTransferInviteQr {
  const params = paramsOf(raw, QR_KIND.deviceTransfer);
  if (params.get('m') !== 'invite') {
    throw new Error('e2ee: this device code must be scanned by an authorized device');
  }
  return {
    kind: QR_KIND.deviceTransfer,
    mode: 'invite',
    transferId: transferId(params),
    pairSecret: fromBase64(required(params, 'p')),
  };
}

export function decodeContactVerifyQr(raw: string): ContactVerifyQr {
  const params = paramsOf(raw, QR_KIND.contactVerify);
  return {
    kind: QR_KIND.contactVerify,
    userId: required(params, 'u'),
    ikSigPub: fromBase64(required(params, 's')),
    ikDhPub: fromBase64(required(params, 'd')),
    genesisCommitment: fromBase64(required(params, 'g')),
  };
}

/**
 * §6.5 requires a downgrade to be visible to both people, and there is no
 * system-message channel to say it on - so it goes as an ordinary encrypted,
 * signed message, exactly as unforgeable as anything else in the conversation.
 *
 * Both clients render this one sentence as a centred notice rather than as a
 * bubble, which is only possible while they agree on it to the character. That
 * makes the string itself the contract: changing it turns every notice already
 * sent back into a plain message, so treat it as append-only and add a new
 * constant instead.
 */
export const STRICT_DISABLED_NOTICE =
  'Turned off the requirement to verify before messaging in this conversation.';

/**
 * Whether a message is that notice. Nothing is authenticated here beyond the
 * text: anyone could type the same sentence, and the notice is attributed to
 * whoever sent it, so a forged one only ever says something about its own
 * author.
 */
export function isStrictDisabledNotice(content: string): boolean {
  return content.trim() === STRICT_DISABLED_NOTICE;
}
