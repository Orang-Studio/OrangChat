import { describe, expect, it } from 'vitest';
import {
  CONVERSATION_KEY_BYTES,
  SYSTEM_NOTICE_KINDS,
  callNotice,
  formatCallDuration,
  isSystemNotice,
  describeSystemNotice,
  systemNoticeKind,
  DOMAIN,
  E2EE_VERSION,
  MESSAGE_NONCE,
  PAYLOAD_VERSION,
  QR_KIND,
  addDeviceStatementBytes,
  eraseKeysStatementBytes,
  bytesEqual,
  checkGossipedHead,
  decodeContactVerifyQr,
  decodeDeviceTransferInviteQr,
  decodeDeviceTransferQr,
  decodeEnvelope,
  decodeMessagePayload,
  deriveMessageKeyBytes,
  deviceBundleBytes,
  encodeMessagePayload,
  openFile,
  sealFile,
  encodeContactVerifyQr,
  encodeDeviceTransferInviteQr,
  encodeDeviceTransferQr,
  encodeEnvelope,
  encodeFields,
  fromBase64,
  genesisStatementBytes,
  groupSafetyNumber,
  logEntryHash,
  logSignatureBytes,
  messageAad,
  normalizeSafetyNumber,
  openMessage,
  pairSas,
  randomBytes,
  revokeStatementBytes,
  safetyNumber,
  safetyNumbersMatch,
  sealMessage,
  toBase64,
  toHex,
  unwrapConversationKey,
  verifyIdentity,
  verifyLogChain,
  wrapConversationKey,
  type DeviceRecord,
  type LogRecord,
  type MessageContext,
} from './e2ee.js';

const ctx: MessageContext = {
  channelId: 'chan1',
  epoch: 3,
  senderDeviceId: 'devA',
  senderUserId: 'userA',
  seq: 7,
};

async function signingPair() {
  return crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, [
    'sign',
    'verify',
  ]);
}

async function agreementPair() {
  return crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
}

async function spki(key: CryptoKey): Promise<Uint8Array> {
  return new Uint8Array(await crypto.subtle.exportKey('spki', key));
}

async function sign(key: CryptoKey, message: Uint8Array): Promise<Uint8Array> {
  return new Uint8Array(
    await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, key, message as BufferSource),
  );
}

describe('canonical encoding', () => {
  it('length-prefixes every field', () => {
    expect(toHex(encodeFields('hi', new Uint8Array()))).toBe('00000002686900000000');
  });

  it('cannot be confused by shifting a field boundary', () => {
    expect(toHex(encodeFields('ab', 'c'))).not.toBe(toHex(encodeFields('a', 'bc')));
  });

  it('encodes numbers as 8 big-endian bytes', () => {
    expect(toHex(encodeFields(1))).toBe('000000080000000000000001');
  });

  it('rejects values it cannot encode unambiguously', () => {
    expect(() => encodeFields(-1)).toThrow();
    expect(() => encodeFields(1.5)).toThrow();
  });
});

describe('nonces', () => {
  // The safety of a fixed nonce rests entirely on the message key being unique
  // per (conversation key, device, seq). Randomising it here would start
  // colliding around 2^32 messages under one key, so it is pinned.
  it('pins the message nonce to 12 zero bytes', () => {
    expect(MESSAGE_NONCE.length).toBe(12);
    expect([...MESSAGE_NONCE].every((b) => b === 0)).toBe(true);
  });

  it('does not carry the nonce on the wire, so it cannot be got wrong', () => {
    const envelope = {
      version: E2EE_VERSION,
      epoch: 1,
      seq: 0,
      senderDeviceId: 'd',
      senderUserId: 'u',
      ciphertext: new Uint8Array([1, 2, 3]),
      signature: new Uint8Array([4, 5, 6]),
    };
    const decoded = decodeEnvelope(encodeEnvelope(envelope));
    expect(Object.keys(decoded)).not.toContain('nonce');
  });
});

describe('domain separation', () => {
  it('pins every domain string', () => {
    expect(DOMAIN).toEqual({
      ckWrap: 'orangchat/ck-wrap/v1',
      messageKey: 'orangchat/msg/v1',
      messageSig: 'orangchat/msg-sig/v1',
      deviceBundle: 'orangchat/device-bundle/v1',
      genesis: 'orangchat/genesis/v1',
      addDevice: 'orangchat/add-device/v1',
      revoke: 'orangchat/revoke/v1',
      eraseKeys: 'orangchat/erase-keys/v1',
      logEntry: 'orangchat/device-log/v1',
      safetyNumber: 'orangchat/safety-number/v1',
      groupSafetyNumber: 'orangchat/group-safety-number/v1',
      pairSas: 'orangchat/pair-sas/v1',
      transferBundle: 'orangchat/transfer-bundle/v1',
      attachment: 'orangchat/attachment/v1',
    });
  });

  it('gives statements over identical material different bytes', () => {
    const bundle = { userId: 'u', ikSigPub: new Uint8Array([1]), ikDhPub: new Uint8Array([2]) };
    const all = [
      toHex(deviceBundleBytes(bundle)),
      toHex(genesisStatementBytes(bundle, 'gen')),
      toHex(addDeviceStatementBytes(bundle, 'gen')),
    ];
    expect(new Set(all).size).toBe(3);
  });

  // Pinned so the Rust server and the Kotlin client cannot drift into producing
  // different bytes for the same statement, which would make every signature
  // silently unverifiable across platforms.
  it('pins the statement encodings', () => {
    const bundle = { userId: 'u', ikSigPub: new Uint8Array([1]), ikDhPub: new Uint8Array([2]) };
    expect(toHex(deviceBundleBytes(bundle))).toBe(
      '0000001a6f72616e67636861742f6465766963652d62756e646c652f7631000000017500000001010000000102',
    );
    expect(toHex(genesisStatementBytes(bundle, 'gen'))).toBe(
      '000000146f72616e67636861742f67656e657369732f76310000000175000000010100000001020000000367656e',
    );
    expect(toHex(revokeStatementBytes('u', 'd', '2026-01-01T00:00:00.000Z'))).toBe(
      '000000136f72616e67636861742f7265766f6b652f7631000000017500000001640000001' +
        '8323032362d30312d30315430303a30303a30302e3030305a',
    );
    expect(toHex(eraseKeysStatementBytes('u', 'd', '2026-01-01T00:00:00.000Z'))).toBe(
      '000000176f72616e67636861742f65726173652d6b6579732f76310000000175000000016' +
        '400000018323032362d30312d30315430303a30303a30302e3030305a',
    );
  });

  // The server takes this signature as proof that the caller holds a key and
  // erases the account's identity with no waiting period, so the one thing that
  // must never happen is an erasure signature being obtainable from something
  // else the same key already signs.
  it('cannot spend a revocation as an erasure', () => {
    expect(toHex(eraseKeysStatementBytes('u', 'd', 'now'))).not.toBe(
      toHex(revokeStatementBytes('u', 'd', 'now')),
    );
  });
});

describe('message envelopes', () => {
  it('round-trips', () => {
    const envelope = {
      version: E2EE_VERSION,
      epoch: 42,
      seq: 9,
      senderDeviceId: 'device-1',
      senderUserId: 'user-1',
      ciphertext: randomBytes(64),
      signature: randomBytes(64),
    };
    const decoded = decodeEnvelope(encodeEnvelope(envelope));
    expect(decoded.epoch).toBe(42);
    expect(decoded.seq).toBe(9);
    expect(decoded.senderDeviceId).toBe('device-1');
    expect(decoded.senderUserId).toBe('user-1');
    expect(bytesEqual(decoded.ciphertext, envelope.ciphertext)).toBe(true);
    expect(bytesEqual(decoded.signature, envelope.signature)).toBe(true);
  });

  it('refuses anything that is not an envelope', () => {
    expect(() => decodeEnvelope(new Uint8Array([1, 2, 3]))).toThrow(/not a message envelope/);
  });

  it('refuses trailing bytes', () => {
    const envelope = {
      version: E2EE_VERSION,
      epoch: 1,
      seq: 1,
      senderDeviceId: 'd',
      senderUserId: 'u',
      ciphertext: new Uint8Array([1]),
      signature: new Uint8Array([2]),
    };
    const encoded = encodeEnvelope(envelope);
    const padded = new Uint8Array(encoded.length + 1);
    padded.set(encoded);
    expect(() => decodeEnvelope(padded)).toThrow(/trailing bytes/);
  });

  it('refuses a version it does not implement', () => {
    const encoded = encodeEnvelope({
      version: E2EE_VERSION,
      epoch: 1,
      seq: 1,
      senderDeviceId: 'd',
      senderUserId: 'u',
      ciphertext: new Uint8Array([1]),
      signature: new Uint8Array([2]),
    });
    encoded[4] = 99;
    expect(() => decodeEnvelope(encoded)).toThrow(/unsupported envelope version/);
  });
});

describe('sealing and opening', () => {
  it('pins the one-message report key across web and Android', async () => {
    const conversationKey = Uint8Array.from({ length: 32 }, (_, index) => index);
    expect(toHex(await deriveMessageKeyBytes(conversationKey, 'device-1', 7))).toBe(
      '1774769631917b635b4cf1e539494c4e995e9c8e6dd104957494366dddf0571e',
    );
    expect(
      bytesEqual(
        await deriveMessageKeyBytes(conversationKey, 'device-1', 7),
        await deriveMessageKeyBytes(conversationKey, 'device-1', 8),
      ),
    ).toBe(false);
  });

  it('round-trips a message', async () => {
    const key = randomBytes(CONVERSATION_KEY_BYTES);
    const pair = await signingPair();
    const envelope = await sealMessage(
      key,
      ctx,
      new TextEncoder().encode('hello'),
      pair.privateKey,
    );
    const plaintext = await openMessage(key, ctx.channelId, envelope, pair.publicKey);
    expect(new TextDecoder().decode(plaintext)).toBe('hello');
  });

  it('binds the ciphertext to its channel', async () => {
    const key = randomBytes(CONVERSATION_KEY_BYTES);
    const pair = await signingPair();
    const envelope = await sealMessage(key, ctx, new TextEncoder().encode('hi'), pair.privateKey);
    await expect(openMessage(key, 'other-channel', envelope, pair.publicKey)).rejects.toThrow();
  });

  it('binds the ciphertext to its epoch and sequence', async () => {
    const key = randomBytes(CONVERSATION_KEY_BYTES);
    const pair = await signingPair();
    const envelope = await sealMessage(key, ctx, new TextEncoder().encode('hi'), pair.privateKey);
    await expect(
      openMessage(key, ctx.channelId, { ...envelope, epoch: 4 }, pair.publicKey),
    ).rejects.toThrow();
    await expect(
      openMessage(key, ctx.channelId, { ...envelope, seq: 8 }, pair.publicKey),
    ).rejects.toThrow();
  });

  // The whole point of the per-sender signature: everyone in a group holds the
  // conversation key, so a GCM tag alone proves only that *someone* here wrote
  // it. Re-signing under another device's key must not produce a message that
  // opens as that device.
  it('refuses a message another member re-signed as someone else', async () => {
    const key = randomBytes(CONVERSATION_KEY_BYTES);
    const honest = await signingPair();
    const impostor = await signingPair();
    const envelope = await sealMessage(
      key,
      ctx,
      new TextEncoder().encode('hi'),
      impostor.privateKey,
    );
    await expect(openMessage(key, ctx.channelId, envelope, honest.publicKey)).rejects.toThrow(
      /signature/,
    );
  });

  it('gives every sender a private key space', () => {
    const a = toHex(messageAad({ ...ctx, senderDeviceId: 'devA' }));
    const b = toHex(messageAad({ ...ctx, senderDeviceId: 'devB' }));
    expect(a).not.toBe(b);
  });
});

describe('key wrapping', () => {
  it('round-trips a conversation key to one device', async () => {
    const conversationKey = randomBytes(CONVERSATION_KEY_BYTES);
    const recipient = await agreementPair();
    const wrapped = await wrapConversationKey(
      conversationKey,
      'epoch-1',
      'device-1',
      recipient.publicKey,
    );
    const unwrapped = await unwrapConversationKey(
      recipient.privateKey,
      'epoch-1',
      'device-1',
      wrapped,
    );
    expect(bytesEqual(unwrapped, conversationKey)).toBe(true);
  });

  it('will not open under another device id or epoch', async () => {
    const conversationKey = randomBytes(CONVERSATION_KEY_BYTES);
    const recipient = await agreementPair();
    const wrapped = await wrapConversationKey(
      conversationKey,
      'epoch-1',
      'device-1',
      recipient.publicKey,
    );
    await expect(
      unwrapConversationKey(recipient.privateKey, 'epoch-1', 'device-2', wrapped),
    ).rejects.toThrow();
    await expect(
      unwrapConversationKey(recipient.privateKey, 'epoch-2', 'device-1', wrapped),
    ).rejects.toThrow();
  });

  it('uses a fresh ephemeral key per wrap', async () => {
    const conversationKey = randomBytes(CONVERSATION_KEY_BYTES);
    const recipient = await agreementPair();
    const a = await wrapConversationKey(conversationKey, 'e', 'd', recipient.publicKey);
    const b = await wrapConversationKey(conversationKey, 'e', 'd', recipient.publicKey);
    expect(bytesEqual(a.ephemeralPub, b.ephemeralPub)).toBe(false);
    expect(bytesEqual(a.wrapNonce, b.wrapNonce)).toBe(false);
  });
});

describe('the device log chain', () => {
  const entry = async (seq: number, payload: string, prev: Uint8Array | null) => {
    const bytes = new TextEncoder().encode(payload);
    return {
      seq,
      payload: bytes,
      prevHash: prev,
      entryHash: await logEntryHash(prev, bytes),
    };
  };

  it('accepts an intact chain', async () => {
    const first = await entry(0, 'genesis', null);
    const second = await entry(1, 'add', first.entryHash);
    expect(await verifyLogChain([first, second])).toBeNull();
  });

  it('catches a rewritten entry', async () => {
    const first = await entry(0, 'genesis', null);
    const second = await entry(1, 'add', first.entryHash);
    const tampered = { ...second, payload: new TextEncoder().encode('different') };
    expect(await verifyLogChain([first, tampered])).toEqual({ kind: 'hash-mismatch', at: 1 });
  });

  it('catches a removed entry', async () => {
    const first = await entry(0, 'genesis', null);
    const second = await entry(1, 'add', first.entryHash);
    const third = await entry(2, 'revoke', second.entryHash);
    expect(await verifyLogChain([first, third])).toEqual({ kind: 'seq-gap', at: 1 });
  });

  it('catches a re-rooted chain', async () => {
    const first = await entry(0, 'genesis', null);
    const second = await entry(1, 'add', await logEntryHash(null, new Uint8Array([9])));
    expect(await verifyLogChain([first, second])).toEqual({ kind: 'prev-mismatch', at: 1 });
  });

  it('treats an empty log as a problem, not as a pass', async () => {
    expect(await verifyLogChain([])).toEqual({ kind: 'empty' });
  });
});

describe('identity verification', () => {
  async function buildAccount(userId: string) {
    const sig = await signingPair();
    const dh = await agreementPair();
    const ikSigPub = await spki(sig.publicKey);
    const ikDhPub = await spki(dh.publicKey);
    const bundle = { userId, ikSigPub, ikDhPub };
    const bundleSig = await sign(sig.privateKey, deviceBundleBytes(bundle));

    const payload = genesisStatementBytes(bundle, 'generation-1');
    const entryHash = await logEntryHash(null, payload);
    const signature = await sign(sig.privateKey, logSignatureBytes(entryHash));

    const device: DeviceRecord = {
      id: 'device-genesis',
      ikSigPub,
      ikDhPub,
      bundleSig,
      authorizedBy: null,
      authorizationSig: null,
      revoked: false,
    };
    const log: LogRecord[] = [
      { seq: 0, kind: 'genesis', payload, prevHash: null, entryHash, signature },
    ];
    return { sig, dh, device, log, bundle };
  }

  it('accepts a genuine genesis device', async () => {
    const account = await buildAccount('user-1');
    const result = await verifyIdentity('user-1', [account.device], account.log);
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.identity.genesisDeviceId).toBe('device-genesis');
      expect(result.identity.authorizedDeviceIds).toEqual(['device-genesis']);
    }
  });

  it('accepts a device an authorized device signed in', async () => {
    const account = await buildAccount('user-1');
    const sig = await signingPair();
    const dh = await agreementPair();
    const ikSigPub = await spki(sig.publicKey);
    const ikDhPub = await spki(dh.publicKey);
    const bundle = { userId: 'user-1', ikSigPub, ikDhPub };
    const bundleSig = await sign(sig.privateKey, deviceBundleBytes(bundle));

    const payload = addDeviceStatementBytes(bundle, 'a'.repeat(32));
    const authorizationSig = await sign(account.sig.privateKey, payload);
    const entryHash = await logEntryHash(account.log[0]!.entryHash, payload);
    const signature = await sign(account.sig.privateKey, logSignatureBytes(entryHash));

    const second: DeviceRecord = {
      id: 'device-2',
      ikSigPub,
      ikDhPub,
      bundleSig,
      authorizedBy: 'device-genesis',
      authorizationSig,
      revoked: false,
    };
    const log: LogRecord[] = [
      ...account.log,
      {
        seq: 1,
        kind: 'add-device',
        payload,
        prevHash: account.log[0]!.entryHash,
        entryHash,
        signature,
      },
    ];

    const result = await verifyIdentity('user-1', [account.device, second], log);
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.identity.authorizedDeviceIds.sort()).toEqual(['device-2', 'device-genesis']);
    }
  });

  // The attack the whole signature graph exists to stop: an operator with full
  // database access inserts a device it holds the private key for, hoping to be
  // wrapped a conversation key. It has no authorization signature from an
  // existing device, and no amount of server access can produce one.
  it('rejects a device the server invented', async () => {
    const account = await buildAccount('user-1');
    const sig = await signingPair();
    const dh = await agreementPair();
    const ikSigPub = await spki(sig.publicKey);
    const ikDhPub = await spki(dh.publicKey);
    const bundle = { userId: 'user-1', ikSigPub, ikDhPub };

    const injected: DeviceRecord = {
      id: 'device-injected',
      ikSigPub,
      ikDhPub,
      bundleSig: await sign(sig.privateKey, deviceBundleBytes(bundle)),
      authorizedBy: 'device-genesis',
      authorizationSig: await sign(sig.privateKey, addDeviceStatementBytes(bundle, 'x'.repeat(32))),
      revoked: false,
    };

    const result = await verifyIdentity('user-1', [account.device, injected], account.log);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.problem.kind).toBe('unintroduced-device');
  });

  it('rejects a second genesis device', async () => {
    const account = await buildAccount('user-1');
    const other = await buildAccount('user-1');
    const result = await verifyIdentity(
      'user-1',
      [account.device, { ...other.device, id: 'device-other' }],
      account.log,
    );
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.problem.kind).toBe('multiple-genesis');
  });

  it('rejects a bundle signed for a different account', async () => {
    const account = await buildAccount('user-1');
    const result = await verifyIdentity('user-2', [account.device], account.log);
    expect(result.ok).toBe(false);
    if (!result.ok) expect(result.problem.kind).toBe('bad-bundle-signature');
  });
});

describe('safety numbers', () => {
  it('is the same for both parties regardless of order', async () => {
    const a = randomBytes(32);
    const b = randomBytes(32);
    expect(await safetyNumber(a, b)).toBe(await safetyNumber(b, a));
  });

  it('is 12 groups of 5 digits', async () => {
    const value = await safetyNumber(randomBytes(32), randomBytes(32));
    expect(value.split(' ')).toHaveLength(12);
    expect(value.replace(/ /g, '')).toMatch(/^\d{60}$/);
  });

  it('changes when an identity is replaced', async () => {
    const mine = randomBytes(32);
    const theirs = randomBytes(32);
    expect(await safetyNumber(mine, theirs)).not.toBe(await safetyNumber(mine, randomBytes(32)));
  });

  it('does not depend on member order in a group', async () => {
    const members = [randomBytes(32), randomBytes(32), randomBytes(32)];
    const forward = await groupSafetyNumber(members);
    const backward = await groupSafetyNumber([...members].reverse());
    expect(forward).toBe(backward);
  });

  it('changes when group membership changes', async () => {
    const members = [randomBytes(32), randomBytes(32)];
    const before = await groupSafetyNumber(members);
    const after = await groupSafetyNumber([...members, randomBytes(32)]);
    expect(before).not.toBe(after);
  });
});

describe('comparing a safety number somebody read out', () => {
  it('ignores how the digits were spaced or punctuated', async () => {
    const value = await safetyNumber(randomBytes(32), randomBytes(32));
    const digits = value.replace(/ /g, '');
    expect(safetyNumbersMatch(digits, value)).toBe(true);
    expect(safetyNumbersMatch(`  ${digits.replace(/(.{4})/g, '$1-')}\n`, value)).toBe(true);
  });

  it('refuses a code that is not the whole thing', async () => {
    const value = await safetyNumber(randomBytes(32), randomBytes(32));
    const digits = value.replace(/ /g, '');
    expect(normalizeSafetyNumber(digits.slice(0, 59))).toBe(null);
    expect(safetyNumbersMatch(digits.slice(0, 59), value)).toBe(false);
    expect(safetyNumbersMatch(`${digits}0`, value)).toBe(false);
    expect(safetyNumbersMatch('', value)).toBe(false);
  });

  it('rejects a different conversation with the right shape', async () => {
    const mine = randomBytes(32);
    const ours = await safetyNumber(mine, randomBytes(32));
    const theirs = await safetyNumber(mine, randomBytes(32));
    expect(safetyNumbersMatch(theirs, ours)).toBe(false);
  });

  it('catches a single transposed digit', async () => {
    const value = await safetyNumber(randomBytes(32), randomBytes(32));
    const digits = [...value.replace(/ /g, '')];
    const at = digits.findIndex((digit, i) => i > 0 && digit !== digits[i - 1]);
    [digits[at - 1], digits[at]] = [digits[at]!, digits[at - 1]!];
    expect(safetyNumbersMatch(digits.join(''), value)).toBe(false);
  });
});

describe('pairing SAS', () => {
  it('is six digits', async () => {
    expect(await pairSas(randomBytes(32), randomBytes(32))).toMatch(/^\d{6}$/);
  });

  it('differs when the pair secret differs', async () => {
    const shared = randomBytes(32);
    expect(await pairSas(shared, randomBytes(32))).not.toBe(await pairSas(shared, randomBytes(32)));
  });
});

// Three QR codes now exist in the product and they look identical. One of them
// authorises a new device, so a scanner that accepts the wrong kind is how "scan
// this to add me" eventually gets someone to scan a transfer code.
describe('QR type tags', () => {
  const transfer = {
    transferId: 'a'.repeat(32),
    ikSigPub: randomBytes(8),
    ikDhPub: randomBytes(8),
    pairSecret: randomBytes(32),
  };
  const verify = {
    userId: 'user-1',
    ikSigPub: randomBytes(8),
    ikDhPub: randomBytes(8),
    genesisCommitment: randomBytes(32),
  };

  it('round-trips a transfer code', () => {
    const decoded = decodeDeviceTransferQr(encodeDeviceTransferQr(transfer));
    expect(decoded.kind).toBe(QR_KIND.deviceTransfer);
    expect(decoded.transferId).toBe(transfer.transferId);
    expect(bytesEqual(decoded.pairSecret, transfer.pairSecret)).toBe(true);
  });

  it('round-trips a desktop-first transfer invitation', () => {
    const invitation = {
      transferId: 'b'.repeat(32),
      pairSecret: randomBytes(32),
    };
    const encoded = encodeDeviceTransferInviteQr(invitation);
    const decoded = decodeDeviceTransferInviteQr(encoded);
    expect(decoded.mode).toBe('invite');
    expect(decoded.transferId).toBe(invitation.transferId);
    expect(bytesEqual(decoded.pairSecret, invitation.pairSecret)).toBe(true);
    expect(() => decodeDeviceTransferQr(encoded)).toThrow(/new device/);
  });

  it('pins the desktop invitation wire format for Android', () => {
    expect(
      encodeDeviceTransferInviteQr({
        transferId: 'a'.repeat(32),
        pairSecret: Uint8Array.from({ length: 32 }, (_, index) => index),
      }),
    ).toBe(
      'orangchat://device-transfer?v=1&m=invite&t=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' +
        '&p=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8%3D',
    );
  });

  it('round-trips a verification code', () => {
    const decoded = decodeContactVerifyQr(encodeContactVerifyQr(verify));
    expect(decoded.kind).toBe(QR_KIND.contactVerify);
    expect(decoded.userId).toBe('user-1');
    expect(bytesEqual(decoded.genesisCommitment, verify.genesisCommitment)).toBe(true);
  });

  it('refuses a transfer code offered to the verification scanner', () => {
    expect(() => decodeContactVerifyQr(encodeDeviceTransferQr(transfer))).toThrow(
      /device-transfer code, not a verify code/,
    );
  });

  it('refuses a verification code offered to the transfer scanner', () => {
    expect(() => decodeDeviceTransferQr(encodeContactVerifyQr(verify))).toThrow(
      /verify code, not a device-transfer code/,
    );
  });

  it('refuses the sign-in code to both', () => {
    const signIn = 'orangchat://login?token=abc';
    expect(() => decodeContactVerifyQr(signIn)).toThrow(/login code/);
    expect(() => decodeDeviceTransferQr(signIn)).toThrow(/login code/);
  });

  it('refuses something that is not an OrangChat code at all', () => {
    expect(() => decodeContactVerifyQr('https://example.com/?v=1')).toThrow(
      /not an OrangChat code/,
    );
  });
});

describe('message payloads', () => {
  it('round-trips everything the server is not allowed to see', () => {
    const payload = {
      v: PAYLOAD_VERSION,
      text: 'hello',
      sentAt: '2026-07-26T10:00:00.000Z',
      clientId: 'local-1',
      replyTo: 'msg-9',
      heads: [{ userId: 'u', seq: 2, entryHash: 'aGVhZA==' }],
    };
    expect(decodeMessagePayload(encodeMessagePayload(payload))).toMatchObject(payload);
  });

  // Messages sealed before the payload format existed are bare UTF-8. Failing on
  // them would turn readable history into a wall of errors.
  it('reads a pre-payload message as its own text', () => {
    const decoded = decodeMessagePayload(new TextEncoder().encode('just text'));
    expect(decoded.text).toBe('just text');
    expect(decoded.clientId).toBe('');
  });

  it('reads text that merely looks like JSON as text', () => {
    expect(decodeMessagePayload(new TextEncoder().encode('{not json')).text).toBe('{not json');
  });

  it('refuses a payload from a newer version rather than dropping its fields', () => {
    const bytes = new TextEncoder().encode(JSON.stringify({ v: 99, text: 'hi' }));
    expect(() => decodeMessagePayload(bytes)).toThrow(/newer version/);
  });
});

describe('head gossip', () => {
  const known = ['aGVhZDA=', 'aGVhZDE=', 'aGVhZDI='];

  it('agrees with a head it already replayed', () => {
    expect(checkGossipedHead({ userId: 'u', seq: 1, entryHash: 'aGVhZDE=' }, known)).toBe('agrees');
  });

  // The equivocation case: the sender was shown a different log at a sequence
  // this device has already committed to.
  it('calls a different hash at a known sequence a fork', () => {
    expect(checkGossipedHead({ userId: 'u', seq: 1, entryHash: 'b3RoZXI=' }, known)).toBe('fork');
  });

  it('reports a head beyond its own as ahead rather than as agreement', () => {
    expect(checkGossipedHead({ userId: 'u', seq: 7, entryHash: 'aGVhZDc=' }, known)).toBe('ahead');
  });
});

describe('attachment sealing', () => {
  it('round-trips file bytes', async () => {
    const plaintext = randomBytes(1024);
    const sealed = await sealFile(plaintext);
    const opened = await openFile(sealed.key, sealed.nonce, sealed.fileId, sealed.bytes);
    expect(bytesEqual(opened, plaintext)).toBe(true);
  });

  it('binds the bytes to their file id', async () => {
    const sealed = await sealFile(randomBytes(32));
    await expect(
      openFile(sealed.key, sealed.nonce, 'a-different-file', sealed.bytes),
    ).rejects.toThrow();
  });

  it('never reuses a key or a nonce between files', async () => {
    const a = await sealFile(randomBytes(16));
    const b = await sealFile(randomBytes(16));
    expect(bytesEqual(a.key, b.key)).toBe(false);
    expect(bytesEqual(a.nonce, b.nonce)).toBe(false);
    expect(a.fileId).not.toBe(b.fileId);
  });
});

describe('base64 helpers', () => {
  it('round-trips arbitrary bytes', () => {
    const bytes = randomBytes(257);
    expect(bytesEqual(fromBase64(toBase64(bytes)), bytes)).toBe(true);
  });
});

describe('system notices', () => {
  // The kind is a wire value the server stores on the message, so it is a
  // contract in the same way the text used to be - except a client cannot write
  // one. Append-only: a stored kind is what history renders from. The Android
  // copy lives in android/.../feature/chat/SystemNotices.kt.
  it('pins the kinds', () => {
    expect(SYSTEM_NOTICE_KINDS).toEqual([
      'strictDisabled',
      'strictEnabled',
      'keyReset',
      'backgroundChanged',
      'backgroundRemoved',
      'iconChanged',
      'iconRemoved',
      'call',
    ]);
  });

  it('reads the kind off the message, never off its text', () => {
    for (const kind of SYSTEM_NOTICE_KINDS) {
      expect(systemNoticeKind({ systemNotice: kind })).toBe(kind);
    }
    // The whole point of the field: typing the sentence is just a message.
    expect(systemNoticeKind({ systemNotice: null })).toBeNull();
    expect(systemNoticeKind({})).toBeNull();
  });

  it('treats an unknown kind as a notice it cannot re-word', () => {
    expect(isSystemNotice({ systemNotice: 'somethingNewer' })).toBe(true);
    expect(systemNoticeKind({ systemNotice: 'somethingNewer' })).toBeNull();
  });

  it('names whoever it is about', () => {
    expect(describeSystemNotice('backgroundChanged', 'You')).toBe(
      'You changed the chat background.',
    );
    expect(describeSystemNotice('keyReset', 'Ada')).toBe(
      'Ada started a new encryption key for this conversation.',
    );
    // A call is a card, not a sentence.
    expect(describeSystemNotice('call', 'Ada')).toBeNull();
  });

  it('reads a call card, and refuses a malformed one', () => {
    const data = {
      callerId: 'u1',
      video: true,
      startedAt: '2026-08-06T10:00:00.000Z',
      endedAt: '2026-08-06T10:04:05.000Z',
      joined: ['u1', 'u2'],
      ringing: [],
      durationSec: 245,
      missed: false,
    };
    expect(callNotice({ systemNotice: 'call', systemData: data })).toEqual(data);
    expect(callNotice({ systemNotice: 'call', systemData: { video: true } })).toBeNull();
    expect(callNotice({ systemNotice: 'keyReset', systemData: data })).toBeNull();
  });

  it('reads a call length off a clock', () => {
    expect(formatCallDuration(5)).toBe('0:05');
    expect(formatCallDuration(245)).toBe('4:05');
    expect(formatCallDuration(3753)).toBe('1:02:33');
  });
});
