import {
  addDeviceStatementBytes,
  checkGossipedHead,
  decodeContactVerifyQr,
  deviceBundleBytes,
  encodeContactVerifyQr,
  fromBase64,
  genesisStatementBytes,
  groupSafetyNumber,
  logEntryHash,
  logSignatureBytes,
  randomBytes,
  revokeStatementBytes,
  safetyNumber,
  toBase64,
  toHex,
  verifyIdentity,
  type DeviceRecord,
  type E2eeDevice,
  type E2eeDeviceList,
  type E2eeLogEntry,
  type GossipedHead,
  type LogRecord,
  type VerifiedIdentity,
} from '@orangchat/shared';
import { raiseSecurityAlert } from './alerts';
import {
  enrolAuthorizedDevice,
  enrolGenesisDevice,
  getMyDevices,
  getPeerDevices,
  markDeviceSeen,
  revokeE2eeDevice,
  type LogEntryPayload,
} from './api';
import {
  deletePin,
  getPin,
  loadIdentity,
  pinPeer,
  rememberDeviceKeys,
  saveIdentity,
  type LocalIdentity,
} from './keystore';

export class IdentityError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'IdentityError';
  }
}

export function deviceName(): string {
  const ua = navigator.userAgent;
  const browser = ua.includes('Firefox')
    ? 'Firefox'
    : ua.includes('Edg/')
      ? 'Edge'
      : ua.includes('Chrome')
        ? 'Chrome'
        : ua.includes('Safari')
          ? 'Safari'
          : 'Browser';
  const os = ua.includes('Windows')
    ? 'Windows'
    : /Mac OS|Macintosh/.test(ua)
      ? 'macOS'
      : ua.includes('Android')
        ? 'Android'
        : ua.includes('Linux')
          ? 'Linux'
          : null;
  return os ? `${browser} on ${os}` : browser;
}

export function platform(): 'web' | 'desktop' {
  return navigator.userAgent.toLowerCase().includes('electron') ? 'desktop' : 'web';
}

/**
 * Both private keys are generated non-extractable, so their bytes never exist in
 * page memory. Code that can use a key while the tab is open is a bad day; code
 * that can copy one owns every future conversation on every device that trusts
 * it, and that is the difference this buys.
 */
export async function generateKeys(): Promise<{
  ikSig: CryptoKeyPair;
  ikDh: CryptoKeyPair;
  ikSigPub: Uint8Array;
  ikDhPub: Uint8Array;
}> {
  const ikSig = (await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, false, [
    'sign',
    'verify',
  ])) as CryptoKeyPair;
  const ikDh = (await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, false, [
    'deriveBits',
  ])) as CryptoKeyPair;
  return {
    ikSig,
    ikDh,
    ikSigPub: new Uint8Array(await crypto.subtle.exportKey('spki', ikSig.publicKey)),
    ikDhPub: new Uint8Array(await crypto.subtle.exportKey('spki', ikDh.publicKey)),
  };
}

export async function sign(key: CryptoKey, message: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(
    await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, message as BufferSource),
  );
}

async function buildLogEntry(
  signingKey: CryptoKey,
  payload: Uint8Array,
  prevHash: Uint8Array | null,
): Promise<LogEntryPayload> {
  const entryHash = await logEntryHash(prevHash, payload);
  const signature = await sign(signingKey, logSignatureBytes(entryHash));
  return {
    payload: toBase64(payload),
    prevHash: prevHash ? toBase64(prevHash) : null,
    entryHash: toBase64(entryHash),
    signature: toBase64(signature),
  };
}

export function toDeviceRecord(device: E2eeDevice): DeviceRecord {
  return {
    id: device.id,
    ikSigPub: fromBase64(device.ikSigPub),
    ikDhPub: fromBase64(device.ikDhPub),
    bundleSig: fromBase64(device.bundleSig),
    authorizedBy: device.authorizedBy,
    authorizationSig: device.authorizationSig ? fromBase64(device.authorizationSig) : null,
    revoked: device.revokedAt !== null,
  };
}

export function toLogRecord(entry: E2eeLogEntry): LogRecord {
  return {
    seq: entry.seq,
    kind: entry.kind,
    payload: fromBase64(entry.payload),
    entryHash: fromBase64(entry.entryHash),
    prevHash: entry.prevHash ? fromBase64(entry.prevHash) : null,
    signature: fromBase64(entry.signature),
  };
}

/**
 * Validates a device list against its own log rather than against the server's
 * word. Nothing downstream may wrap a conversation key without this returning.
 */
export async function verifyList(list: E2eeDeviceList): Promise<VerifiedIdentity> {
  const result = await verifyIdentity(
    list.userId,
    list.devices.map(toDeviceRecord),
    list.log.map(toLogRecord),
  );
  if (!result.ok) {
    throw new IdentityError(describeProblem(result.problem));
  }

  // Only devices that survived the replay above are written down, and only
  // here. The notification path reads them to check a sender's signature
  // without being able to re-run this, so what it finds has to have been
  // verified already.
  await rememberDeviceKeys(
    list.devices
      .filter((device) => result.identity.authorizedDeviceIds.includes(device.id))
      .map((device) => ({ id: device.id, userId: device.userId, ikSigPub: device.ikSigPub })),
  ).catch(() => {});

  return result.identity;
}

function describeProblem(problem: { kind: string } & Record<string, unknown>): string {
  switch (problem.kind) {
    case 'chain':
      return "This account's device log has been rewritten or is missing entries.";
    case 'no-genesis':
    case 'multiple-genesis':
      return "This account's encryption identity is not well-formed.";
    case 'bad-bundle-signature':
    case 'bad-authorization':
      return 'A device on this account is not signed by a device that could authorize it.';
    case 'unauthorized-signer':
    case 'bad-entry-signature':
      return 'A device-log entry is not signed by an authorized device.';
    case 'unintroduced-device':
      return 'A device appears on this account that was never added by an authorized device.';
    default:
      return "This account's encryption identity could not be verified.";
  }
}

function entryHashesOf(list: E2eeDeviceList): string[] {
  const hashes: string[] = [];
  for (const entry of list.log) hashes[entry.seq] = entry.entryHash;
  return hashes;
}

/**
 * Refuses to move forward on a head that rewound, forked, or dropped an entry
 * this device already saw. A pinned head is a commitment to everything before
 * it, so a server that wants to show a different history has to contradict
 * something this device has already written down.
 */
async function checkAgainstPin(
  userId: string,
  identity: VerifiedIdentity,
  hashes: readonly string[],
): Promise<void> {
  const pin = await getPin(userId);
  if (!pin) return;

  if (pin.genesisCommitment !== toBase64(identity.genesisCommitment)) {
    raiseSecurityAlert({
      userId,
      kind: 'identity-changed',
      subject: userId,
      detail:
        'The encryption identity on this account was replaced. Until it is verified again, anything sent to it could be read by whoever replaced it.',
    });
    throw new IdentityError(
      "This account's encryption identity changed. Verify them again before continuing.",
    );
  }

  if (pin.headSeq > identity.head.seq) {
    raiseSecurityAlert({
      userId,
      kind: 'log-rollback',
      subject: userId,
      detail:
        "This account's device log is shorter than it was. Entries do not disappear, so the server is showing a history it has rewritten.",
    });
    throw new IdentityError("This account's device log went backwards.");
  }

  // A log that grew is normal; a log that grew *and changed something we already
  // committed to* is the equivocation the whole chain exists to catch.
  for (let seq = 0; seq < pin.entryHashes.length; seq += 1) {
    const seen = pin.entryHashes[seq];
    if (seen === undefined) continue;
    if (hashes[seq] === undefined) {
      raiseSecurityAlert({
        userId,
        kind: 'log-omission',
        subject: userId,
        detail: "An entry this device already saw is missing from this account's device log.",
      });
      throw new IdentityError("An entry is missing from this account's device log.");
    }
    if (hashes[seq] !== seen) {
      raiseSecurityAlert({
        userId,
        kind: 'log-fork',
        subject: userId,
        detail:
          "This account's device log now says something different about an entry this device already recorded. That is only possible if the server is showing different histories to different people.",
      });
      throw new IdentityError("This account's device log has forked.");
    }
  }
}

export async function enrol(userId: string): Promise<LocalIdentity> {
  const existing = await loadIdentity();
  if (existing && existing.userId === userId) return existing;

  const { ikSig, ikDh, ikSigPub, ikDhPub } = await generateKeys();
  const bundle = { userId, ikSigPub, ikDhPub };
  const bundleSig = await sign(ikSig.privateKey, deviceBundleBytes(bundle));

  const list = await getMyDevices();
  const active = list.devices.filter((d) => d.revokedAt === null);

  if (active.length > 0) {
    throw new IdentityError(
      'This account already has an encryption identity. Add this device from one that is already signed in.',
    );
  }

  const identityGeneration = toHex(randomBytes(16));
  const payload = genesisStatementBytes(bundle, identityGeneration);
  const log = await buildLogEntry(ikSig.privateKey, payload, null);

  const device = await enrolGenesisDevice({
    name: deviceName(),
    platform: platform(),
    ikSigPub: toBase64(ikSigPub),
    ikDhPub: toBase64(ikDhPub),
    bundleSig: toBase64(bundleSig),
    identityGeneration,
    log,
  });

  const identity: LocalIdentity = {
    userId,
    deviceId: device.id,
    identityGeneration,
    ikSig,
    ikDh,
    ikSigPub,
    ikDhPub,
  };
  await saveIdentity(identity);
  await selfMonitor(userId);
  return identity;
}

/**
 * The step that closes first contact without asking a human anything: every
 * account audits its own log, so a genesis this device never created shows up
 * here as a hard failure rather than on the stranger being lied to.
 */
export async function selfMonitor(userId: string): Promise<VerifiedIdentity> {
  const identity = await loadIdentity();
  const list = await getMyDevices();
  const verified = await verifyList(list);

  if (identity && identity.userId === userId) {
    const mine = list.devices.find((d) => d.id === identity.deviceId);
    if (!mine) {
      throw new IdentityError(
        'This device is missing from your own device log. Someone has rewritten it.',
      );
    }
    if (!verified.authorizedDeviceIds.includes(identity.deviceId)) {
      throw new IdentityError('This device is no longer authorized on your account.');
    }
  }

  const hashes = entryHashesOf(list);
  await checkAgainstPin(userId, verified, hashes);
  await pinPeer({
    userId,
    genesisCommitment: toBase64(verified.genesisCommitment),
    verifiedAt: (await getPin(userId))?.verifiedAt ?? null,
    headSeq: verified.head.seq,
    headHash: toBase64(verified.head.entryHash),
    entryHashes: hashes,
  });
  return verified;
}

/** Verifies a peer, pins their commitment, and returns who may hold a key. */
export async function resolvePeer(userId: string): Promise<{
  identity: VerifiedIdentity;
  devices: E2eeDevice[];
}> {
  const list = await getPeerDevices(userId);
  const identity = await verifyList(list);
  const hashes = entryHashesOf(list);
  await checkAgainstPin(userId, identity, hashes);

  const pin = await getPin(userId);
  await pinPeer({
    userId,
    genesisCommitment: toBase64(identity.genesisCommitment),
    verifiedAt: pin?.verifiedAt ?? null,
    headSeq: identity.head.seq,
    headHash: toBase64(identity.head.entryHash),
    entryHashes: hashes,
  });

  return {
    identity,
    devices: list.devices.filter((d) => identity.authorizedDeviceIds.includes(d.id)),
  };
}

/**
 * The heads this device would tell somebody else about: its own, plus every
 * account it has pinned. They ride inside already-encrypted messages, so the
 * server cannot see which heads are being compared, let alone tailor them.
 */
export async function headsToGossip(peerUserIds: readonly string[]): Promise<GossipedHead[]> {
  const identity = await loadIdentity();
  const wanted = new Set(peerUserIds);
  if (identity) wanted.add(identity.userId);

  const heads: GossipedHead[] = [];
  for (const userId of wanted) {
    const pin = await getPin(userId);
    if (pin) heads.push({ userId, seq: pin.headSeq, entryHash: pin.headHash });
  }
  return heads;
}

/**
 * Checks heads somebody else reported against what this device replayed. A head
 * beyond ours is not yet a problem - it means the server has not shown us those
 * entries - so it triggers one refetch, and a head still beyond us after that is
 * the server withholding entries from one participant while serving them to
 * another, which is the same equivocation by omission.
 */
export async function checkGossip(heads: readonly GossipedHead[]): Promise<void> {
  for (const head of heads) {
    const pin = await getPin(head.userId);
    if (!pin) continue;

    let verdict = checkGossipedHead(head, pin.entryHashes);
    if (verdict === 'ahead') {
      // resolvePeer re-verifies and re-pins; a fork shows up as a throw there.
      try {
        await resolvePeer(head.userId);
      } catch {
        return;
      }
      const refreshed = await getPin(head.userId);
      verdict = refreshed ? checkGossipedHead(head, refreshed.entryHashes) : 'ahead';
      if (verdict === 'ahead') {
        raiseSecurityAlert({
          userId: head.userId,
          kind: 'log-omission',
          subject: head.userId,
          detail:
            'Someone in this conversation has seen device-log entries for this account that the server will not show this device.',
        });
        continue;
      }
    }
    if (verdict === 'fork') {
      raiseSecurityAlert({
        userId: head.userId,
        kind: 'log-fork',
        subject: head.userId,
        detail:
          'Someone in this conversation was shown a different device log for this account than this device was. A server that does that is lying to at least one of you.',
      });
    }
  }
}

export async function safetyNumberWith(peerUserId: string): Promise<string | null> {
  const identity = await loadIdentity();
  if (!identity) return null;
  const mine = await getPin(identity.userId);
  const theirs = await getPin(peerUserId);
  if (!mine || !theirs) return null;
  return safetyNumber(fromBase64(mine.genesisCommitment), fromBase64(theirs.genesisCommitment));
}

/**
 * One number for the whole group, over every member's pinned genesis commitment.
 * It changes when membership changes or when anyone resets their identity, which
 * is the point: it is a one-glance confirmation that everyone is in the same
 * group with the same people. Informational, never a gate (§6.3).
 */
export async function groupSafetyNumberWith(
  peerUserIds: readonly string[],
): Promise<string | null> {
  const identity = await loadIdentity();
  if (!identity) return null;
  const commitments: Uint8Array[] = [];
  for (const userId of [identity.userId, ...peerUserIds]) {
    const pin = await getPin(userId);
    if (!pin) return null;
    commitments.push(fromBase64(pin.genesisCommitment));
  }
  return groupSafetyNumber(commitments);
}

export async function markVerified(peerUserId: string): Promise<void> {
  const pin = await getPin(peerUserId);
  if (!pin) throw new IdentityError("Fetch this contact's identity before verifying it.");
  await pinPeer({ ...pin, verifiedAt: new Date().toISOString() });
}

export async function clearVerified(peerUserId: string): Promise<void> {
  const pin = await getPin(peerUserId);
  if (!pin) return;
  await pinPeer({ ...pin, verifiedAt: null });
}

/**
 * The way out of an identity change, and the only one. Losing every device is a
 * real thing that happens to real people, and before this there was no path back
 * from it: `checkAgainstPin` throws on the mismatch before `resolvePeer` can
 * write the new commitment, so every contact of somebody who started over was
 * left unable to read or send, permanently, on every device.
 *
 * Dropping the pin first is what makes the re-verification below possible - it
 * is the pin that rejects the new identity, so the account has to be treated as
 * a stranger again before it can be looked at. The chain replay in `resolvePeer`
 * still runs in full, so this forgives a *changed* identity, never a malformed
 * one.
 *
 * Deliberately not offered for the other alert kinds. A fork, a rollback or an
 * omission is a server contradicting itself, and there is no honest story that
 * ends with the user clicking past it; only `identity-changed` has one.
 */
export async function acceptIdentityChange(peerUserId: string): Promise<VerifiedIdentity> {
  const identity = await loadIdentity();
  if (identity && peerUserId === identity.userId) {
    throw new IdentityError('This is your own account, not a contact.');
  }

  const previous = await getPin(peerUserId);
  await deletePin(peerUserId);
  try {
    const { identity: verified } = await resolvePeer(peerUserId);
    return verified;
  } catch (error) {
    // A replay that fails now is a different problem from the one being
    // forgiven, and dropping the pin was only ever a means to re-examine the
    // account. Putting it back keeps the old commitment enforced.
    if (previous) await pinPeer(previous);
    throw error;
  }
}

/**
 * This account's own verification code: public material only, and the value it
 * commits to is the genesis identity rather than a loose key, so scanning it
 * pins the same thing safety numbers are derived from (§6.7).
 */
export async function myContactQr(): Promise<string> {
  const identity = await loadIdentity();
  if (!identity) throw new IdentityError('This device has no encryption identity yet.');
  const pin = await getPin(identity.userId);
  if (!pin) {
    throw new IdentityError('Check your own device log before showing your code.');
  }
  return encodeContactVerifyQr({
    userId: identity.userId,
    ikSigPub: identity.ikSigPub,
    ikDhPub: identity.ikDhPub,
    genesisCommitment: fromBase64(pin.genesisCommitment),
  });
}

/**
 * The out-of-band half of verification. The commitment being compared came off
 * a camera, not off the network, so a server that substituted an identity is
 * caught here by a mismatch it cannot influence.
 *
 * One scan verifies one direction. Pinning what A scanned tells A nothing about
 * whether B has pinned A, which is why the caller has to keep asking for the
 * second scan rather than declaring the pair verified.
 */
export async function acceptScannedContact(raw: string): Promise<{ userId: string }> {
  const scanned = decodeContactVerifyQr(raw);
  const identity = await loadIdentity();
  if (identity && scanned.userId === identity.userId) {
    throw new IdentityError('That is your own code. Have the other person show theirs.');
  }

  const { identity: peer } = await resolvePeer(scanned.userId);
  if (toBase64(peer.genesisCommitment) !== toBase64(scanned.genesisCommitment)) {
    raiseSecurityAlert({
      userId: scanned.userId,
      kind: 'identity-changed',
      subject: scanned.userId,
      detail:
        'The code you scanned does not match the identity this server handed over for that account. Someone is standing between you.',
    });
    throw new IdentityError(
      'This code does not match the identity the server gave for that account. Do not send anything until you know why.',
    );
  }

  await markVerified(scanned.userId);
  return { userId: scanned.userId };
}

export async function revoke(deviceId: string): Promise<E2eeDevice> {
  const identity = await loadIdentity();
  if (!identity) throw new IdentityError('This device has no encryption identity.');

  const list = await getMyDevices();
  await verifyList(list);
  const prevHash = list.head ? fromBase64(list.head.entryHash) : null;

  const revokedAt = new Date().toISOString();
  const payload = revokeStatementBytes(identity.userId, deviceId, revokedAt);
  const log = await buildLogEntry(identity.ikSig.privateKey, payload, prevHash);

  return revokeE2eeDevice({
    deviceId,
    signerDeviceId: identity.deviceId,
    revokedAt,
    log,
  });
}

/**
 * The old device's side of a transfer: it signs the new device's bundle into the
 * log. The grant proves TOTP and policy; this signature is the actual authority,
 * which is why no amount of server access can stand in for it.
 */
export async function authorizeNewDevice(input: {
  transferId: string;
  grant: string;
  name: string;
  platform: 'web' | 'android' | 'desktop';
  ikSigPub: Uint8Array;
  ikDhPub: Uint8Array;
  bundleSig: Uint8Array;
}): Promise<E2eeDevice> {
  const identity = await loadIdentity();
  if (!identity) throw new IdentityError('This device has no encryption identity.');

  const list = await getMyDevices();
  await verifyList(list);
  const prevHash = list.head ? fromBase64(list.head.entryHash) : null;

  const bundle = {
    userId: identity.userId,
    ikSigPub: input.ikSigPub,
    ikDhPub: input.ikDhPub,
  };
  const payload = addDeviceStatementBytes(bundle, input.transferId);
  const authorizationSig = await sign(identity.ikSig.privateKey, payload);
  const log = await buildLogEntry(identity.ikSig.privateKey, payload, prevHash);

  return enrolAuthorizedDevice({
    name: input.name,
    platform: input.platform,
    ikSigPub: toBase64(input.ikSigPub),
    ikDhPub: toBase64(input.ikDhPub),
    bundleSig: toBase64(input.bundleSig),
    transferId: input.transferId,
    grant: input.grant,
    authorizedBy: identity.deviceId,
    authorizationSig: toBase64(authorizationSig),
    log,
  });
}

/** A "last seen" touch is bookkeeping; never let it fail anything real. */
export async function markDeviceSeenSafely(deviceId: string): Promise<void> {
  try {
    await markDeviceSeen(deviceId);
  } catch {
    // Ignored on purpose.
  }
}

export { loadIdentity };
