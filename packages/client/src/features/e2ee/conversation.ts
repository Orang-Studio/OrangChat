import {
  CONVERSATION_KEY_BYTES,
  E2EE_VERSION,
  EPOCH_MAX_MESSAGES,
  PAYLOAD_VERSION,
  decodeEnvelope,
  decodeMessagePayload,
  deriveMessageKeyBytes,
  encodeEnvelope,
  encodeMessagePayload,
  fromBase64,
  importAgreementPublicKey,
  importSigningPublicKey,
  openMessage,
  randomBytes,
  sealMessage,
  toBase64,
  toHex,
  unwrapConversationKey,
  wrapConversationKey,
  type E2eeDevice,
  type Message,
  type MessagePayload,
} from '@orangchat/shared';
import { getChannelE2eeState, getEpochKeys, mintEpoch, type E2eeChannelState } from './api';
import { IdentityError, checkGossip, headsToGossip, resolvePeer, verifyList } from './identity';
import { getMyDevices } from './api';
import { assertMayEncryptTo } from './strict';
import {
  knownEpochs,
  loadEpochKey,
  loadIdentity,
  reserveSeq,
  saveEpochKey,
  type LocalIdentity,
} from './keystore';

export interface SealedMessage {
  ciphertext: string;
  encEpoch: number;
  encVersion: number;
}

async function identityOrThrow(): Promise<LocalIdentity> {
  const identity = await loadIdentity();
  if (!identity) {
    throw new IdentityError('This device has no encryption identity yet.');
  }
  return identity;
}

/**
 * Every active device of every participant, but only after each participant's
 * own log has been replayed and verified. A device the server invented has no
 * authorization signature, so it never reaches this list and never receives a
 * conversation key.
 */
async function verifiedRecipients(
  state: E2eeChannelState,
  identity: LocalIdentity,
): Promise<E2eeDevice[]> {
  const userIds = [...new Set(state.memberDevices.map((d) => d.userId))];
  const out: E2eeDevice[] = [];
  for (const userId of userIds) {
    if (userId === identity.userId) {
      const mine = await getMyDevices();
      const verified = await verifyList(mine);
      out.push(...mine.devices.filter((d) => verified.authorizedDeviceIds.includes(d.id)));
      continue;
    }
    const { devices } = await resolvePeer(userId);
    out.push(...devices);
  }
  return out;
}

async function wrapForEveryone(
  conversationKey: Uint8Array,
  epochId: string,
  recipients: readonly E2eeDevice[],
) {
  const envelopes = [];
  for (const device of recipients) {
    const pub = await importAgreementPublicKey(fromBase64(device.ikDhPub));
    const wrapped = await wrapConversationKey(conversationKey, epochId, device.id, pub);
    envelopes.push({
      deviceId: device.id,
      ephemeralPub: toBase64(wrapped.ephemeralPub),
      wrapNonce: toBase64(wrapped.wrapNonce),
      wrapped: toBase64(wrapped.wrapped),
    });
  }
  return envelopes;
}

/**
 * Mints a fresh conversation key and wraps it to every verified device. The
 * epoch id is generated here rather than by the server, because every wrapping
 * is bound to it as the HKDF salt before the server ever sees the request.
 */
export async function rotate(channelId: string, options: { announce?: boolean } = {}): Promise<number> {
  const identity = await identityOrThrow();
  const state = await getChannelE2eeState(channelId);
  await assertMayEncryptTo(
    channelId,
    state.channelType,
    state.memberDevices.map((d) => d.userId),
  );
  const recipients = await verifiedRecipients(state, identity);
  if (recipients.length === 0) {
    throw new IdentityError('No verified devices to encrypt to in this conversation.');
  }

  const conversationKey = randomBytes(CONVERSATION_KEY_BYTES);
  const epochId = toHex(randomBytes(16));
  const envelopes = await wrapForEveryone(conversationKey, epochId, recipients);
  const epoch = await mintEpoch(channelId, {
    id: epochId,
    createdBy: identity.deviceId,
    envelopes,
    // The notice, when there is one, is written by the server off the back of
    // this same request - so it says a key was started because one was, not
    // because a client said so.
    ...(options.announce ? { announce: true } : {}),
  });

  await saveEpochKey(channelId, epoch.epoch, conversationKey);
  return epoch.epoch;
}

async function unwrapFromServer(
  channelId: string,
  identity: LocalIdentity,
  epoch: number,
): Promise<Uint8Array | null> {
  const { keys } = await getEpochKeys(channelId, identity.deviceId, epoch);
  const match = keys.find((k) => k.epoch.epoch === epoch);
  if (!match) return null;
  const conversationKey = await unwrapConversationKey(
    identity.ikDh.privateKey,
    match.epoch.id,
    identity.deviceId,
    {
      ephemeralPub: fromBase64(match.envelope.ephemeralPub),
      wrapNonce: fromBase64(match.envelope.wrapNonce),
      wrapped: fromBase64(match.envelope.wrapped),
    },
  );
  await saveEpochKey(channelId, epoch, conversationKey);
  return conversationKey;
}

export async function conversationKeyFor(channelId: string, epoch: number): Promise<Uint8Array> {
  const cached = await loadEpochKey(channelId, epoch);
  if (cached) return cached;
  const identity = await identityOrThrow();
  const fetched = await unwrapFromServer(channelId, identity, epoch);
  if (!fetched) {
    throw new IdentityError('This message was encrypted for a device you no longer have.');
  }
  return fetched;
}

/** Pulls down and caches every epoch key this device is entitled to. */
export async function syncEpochKeys(channelId: string): Promise<number[]> {
  const identity = await identityOrThrow();
  const { keys } = await getEpochKeys(channelId, identity.deviceId);
  for (const key of keys) {
    if (await loadEpochKey(channelId, key.epoch.epoch)) continue;
    try {
      const conversationKey = await unwrapConversationKey(
        identity.ikDh.privateKey,
        key.epoch.id,
        identity.deviceId,
        {
          ephemeralPub: fromBase64(key.envelope.ephemeralPub),
          wrapNonce: fromBase64(key.envelope.wrapNonce),
          wrapped: fromBase64(key.envelope.wrapped),
        },
      );
      await saveEpochKey(channelId, key.epoch.epoch, conversationKey);
    } catch {
      // An envelope this device cannot open is not fatal: it belongs to an
      // epoch minted before this device existed, and that history is simply
      // unreadable here rather than an error to raise.
    }
  }
  return knownEpochs(channelId);
}

export async function currentEpoch(channelId: string): Promise<number> {
  const state = await getChannelE2eeState(channelId);
  if (state.epochNumber === 0) return rotate(channelId);
  const epochAge =
    state.currentEpochCreatedAt === null
      ? Number.POSITIVE_INFINITY
      : Date.now() - new Date(state.currentEpochCreatedAt).getTime();
  if (
    state.rotationRequired ||
    state.currentEpochMessageCount >= EPOCH_MAX_MESSAGES ||
    epochAge >= 30 * 24 * 60 * 60 * 1000
  ) {
    return rotate(channelId);
  }
  const cached = await loadEpochKey(channelId, state.epochNumber);
  if (cached) return state.epochNumber;
  await syncEpochKeys(channelId);
  if (await loadEpochKey(channelId, state.epochNumber)) return state.epochNumber;
  return rotate(channelId);
}

export interface OutgoingPayload {
  text: string;
  clientId: string;
  sentAt?: string;
  replyTo?: string | null;
  attachments?: MessagePayload['attachments'];
}

/**
 * Seals a whole payload rather than a bare string. The server assigns the
 * message id after this runs, so it cannot be bound into the AAD - which is why
 * the payload carries its own timestamp, reply target and client id, and why
 * clients render those instead of the server's copy.
 */
export async function seal(channelId: string, outgoing: OutgoingPayload): Promise<SealedMessage> {
  const identity = await identityOrThrow();
  let epoch = await currentEpoch(channelId);
  let seq = await reserveSeq(channelId, epoch);

  // A counter that has run past the epoch's budget gets a new key rather than a
  // longer run under the old one.
  if (seq >= EPOCH_MAX_MESSAGES) {
    epoch = await rotate(channelId);
    seq = await reserveSeq(channelId, epoch);
  }

  const state = await getChannelE2eeState(channelId);
  await assertMayEncryptTo(
    channelId,
    state.channelType,
    state.memberDevices.map((d) => d.userId),
  );

  const payload: MessagePayload = {
    v: PAYLOAD_VERSION,
    text: outgoing.text,
    sentAt: outgoing.sentAt ?? new Date().toISOString(),
    clientId: outgoing.clientId,
    replyTo: outgoing.replyTo ?? null,
    ...(outgoing.attachments?.length ? { attachments: outgoing.attachments } : {}),
    // Heads ride inside the ciphertext, so a server cannot see which of its
    // claims are being cross-checked, let alone tailor them per recipient.
    heads: await headsToGossip(state.memberDevices.map((d) => d.userId)),
  };

  const conversationKey = await conversationKeyFor(channelId, epoch);
  const envelope = await sealMessage(
    conversationKey,
    {
      channelId,
      epoch,
      senderDeviceId: identity.deviceId,
      senderUserId: identity.userId,
      seq,
    },
    encodeMessagePayload(payload),
    identity.ikSig.privateKey,
  );

  return {
    ciphertext: toBase64(encodeEnvelope(envelope)),
    encEpoch: epoch,
    encVersion: E2EE_VERSION,
  };
}

/**
 * Opens a message and, crucially, checks who wrote it. Everyone in the
 * conversation holds the same conversation key, so a valid GCM tag only proves
 * *someone* here wrote it; the per-sender signature is what makes authorship
 * mean anything, and an unsigned message is a hard failure rather than a
 * degraded render.
 */
export async function open(channelId: string, message: Message): Promise<MessagePayload> {
  if (!message.ciphertext) {
    throw new IdentityError('This message carries no ciphertext.');
  }
  const envelope = decodeEnvelope(fromBase64(message.ciphertext));
  const conversationKey = await conversationKeyFor(channelId, envelope.epoch);

  // Our own devices get the same treatment as anyone else's. Skipping the chain
  // replay here would let the server invent a device on *our* account and
  // attribute a message to it, which is the same attack from the inside.
  const identity = await identityOrThrow();
  let devices: E2eeDevice[];
  if (envelope.senderUserId === identity.userId) {
    const mine = await getMyDevices();
    const verified = await verifyList(mine);
    devices = mine.devices.filter((d) => verified.authorizedDeviceIds.includes(d.id));
  } else {
    devices = (await resolvePeer(envelope.senderUserId)).devices;
  }

  const sender = devices.find((d) => d.id === envelope.senderDeviceId);
  if (!sender) {
    throw new IdentityError("This message came from a device that is not on the sender's account.");
  }
  if (sender.userId !== message.author.id) {
    throw new IdentityError("This message's signing device does not belong to its author.");
  }

  const verifyingKey = await importSigningPublicKey(fromBase64(sender.ikSigPub));
  const plaintext = await openMessage(conversationKey, channelId, envelope, verifyingKey);
  const payload = decodeMessagePayload(plaintext);

  // Cross-checking the sender's view of everyone's device log is the gossip half
  // of §6.1. It never blocks rendering - the message is already authenticated -
  // but a disagreement raises the hard failure on its own.
  if (payload.heads?.length) void checkGossip(payload.heads);

  return payload;
}

/**
 * Material for a deliberate abuse report. This is one HKDF-derived AES key,
 * never the conversation key; it lets the server authenticate and open only
 * the original ciphertext named by this message.
 */
export async function reportKeyFor(
  channelId: string,
  message: Message,
): Promise<string | undefined> {
  if (!message.ciphertext) return undefined;
  const envelope = decodeEnvelope(fromBase64(message.ciphertext));
  const conversationKey = await conversationKeyFor(channelId, envelope.epoch);
  return toBase64(
    await deriveMessageKeyBytes(conversationKey, envelope.senderDeviceId, envelope.seq),
  );
}
