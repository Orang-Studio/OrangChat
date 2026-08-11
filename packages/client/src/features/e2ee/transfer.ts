import {
  PAIR_SECRET_BYTES,
  decodeDeviceTransferQr,
  encodeDeviceTransferInviteQr,
  deviceBundleBytes,
  encodeDeviceTransferQr,
  fromBase64,
  importAgreementPublicKey,
  pairSas,
  randomBytes,
  toBase64,
  transferBundleKey,
  type E2eePlatform,
} from '@orangchat/shared';
import { getMyDevices, requestTransferGrant, startTransfer } from './api';
import {
  authorizeNewDevice,
  deviceName,
  forgetOwnPin,
  generateKeys,
  markDeviceSeenSafely,
  platform,
  selfMonitor,
  sign,
} from './identity';
import {
  allEpochKeys,
  loadIdentity,
  saveEpochKey,
  saveIdentity,
  type EpochKeyEntry,
} from './keystore';
import { openAnswerer, openOfferer, relayPut, relayTake, type Channel } from './transport';



const encoder = new TextEncoder();
const decoder = new TextDecoder();


interface Hello {
  name: string;
  platform: E2eePlatform;
  bundleSig: string;
  ikSigPub?: string;
  ikDhPub?: string;
}

interface BundleBody {
  keys: { channelId: string; epoch: number; key: string }[];
}

async function sealJson(key: CryptoKey, value: unknown): Promise<Uint8Array> {
  const nonce = randomBytes(12);
  const ciphertext = new Uint8Array(
    await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv: nonce as BufferSource },
      key,
      encoder.encode(JSON.stringify(value)) as BufferSource,
    ),
  );
  const out = new Uint8Array(nonce.length + ciphertext.length);
  out.set(nonce);
  out.set(ciphertext, nonce.length);
  return out;
}

async function openJson<T>(key: CryptoKey, bytes: Uint8Array): Promise<T> {
  const plaintext = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: bytes.subarray(0, 12) as BufferSource },
    key,
    bytes.subarray(12) as BufferSource,
  );
  return JSON.parse(decoder.decode(new Uint8Array(plaintext))) as T;
}


export interface PendingEnrolment {
  transferId: string;
  qr: string;
  pairSecret: Uint8Array;
  ikSig: CryptoKeyPair;
  ikDh: CryptoKeyPair;
  ikSigPub: Uint8Array;
  ikDhPub: Uint8Array;
  bundleSig: Uint8Array;
}


export async function beginEnrolment(userId: string): Promise<PendingEnrolment> {
  const { ikSig, ikDh, ikSigPub, ikDhPub } = await generateKeys();
  const bundleSig = await sign(ikSig.privateKey, deviceBundleBytes({ userId, ikSigPub, ikDhPub }));
  const { transferId } = await startTransfer();
  const pairSecret = randomBytes(PAIR_SECRET_BYTES);

  const hello: Hello = {
    name: deviceName(),
    platform: platform(),
    bundleSig: toBase64(bundleSig),
    ikSigPub: toBase64(ikSigPub),
    ikDhPub: toBase64(ikDhPub),
  };
  await relayPut(transferId, 'hello', encoder.encode(JSON.stringify(hello))).catch(() => {});

  return {
    transferId,
    qr: encodeDeviceTransferQr({ transferId, ikSigPub, ikDhPub, pairSecret }),
    pairSecret,
    ikSig,
    ikDh,
    ikSigPub,
    ikDhPub,
    bundleSig,
  };
}

async function channelOrRelay(open: () => Promise<Channel>): Promise<Channel | null> {
  try {
    return await open();
  } catch {
    return null;
  }
}

export interface NewDeviceHandshake {
  sas: string;

  finish: () => Promise<void>;
  cancel: () => void;
}


export async function awaitAdoption(
  pending: PendingEnrolment,
  userId: string,
): Promise<NewDeviceHandshake> {
  const channel = await channelOrRelay(() => openAnswerer(pending.transferId));

  const handshakeBytes = channel
    ? await channel.receive()
    : await relayTake(pending.transferId, 'handshake');
  const ephemeralPub = await importAgreementPublicKey(handshakeBytes);
  const shared = new Uint8Array(
    await crypto.subtle.deriveBits(
      { name: 'ECDH', public: ephemeralPub },
      pending.ikDh.privateKey,
      256,
    ),
  );

  const sas = await pairSas(shared, pending.pairSecret);

  return {
    sas,
    cancel: () => channel?.close(),
    finish: async () => {
      const key = await transferBundleKey(shared, pending.pairSecret);
      const sealed = channel
        ? await channel.receive()
        : await relayTake(pending.transferId, 'bundle');
      const bundle = await openJson<BundleBody>(key, sealed);

      for (const entry of bundle.keys) {
        await saveEpochKey(entry.channelId, entry.epoch, fromBase64(entry.key));
      }
      channel?.close();

      const mine = toBase64(pending.ikSigPub);
      let deviceId: string | null = null;
      for (let attempt = 0; attempt < 30 && deviceId === null; attempt += 1) {
        const list = await getMyDevices().catch(() => null);
        deviceId = list?.devices.find((d) => d.ikSigPub === mine)?.id ?? null;
        if (deviceId === null) await new Promise((resolve) => setTimeout(resolve, 1000));
      }
      if (deviceId === null) {
        throw new Error('The other device did not finish adding this one. Start again.');
      }

      await saveIdentity({
        userId,
        deviceId,
        identityGeneration: '',
        ikSig: pending.ikSig,
        ikDh: pending.ikDh,
        ikSigPub: pending.ikSigPub,
        ikDhPub: pending.ikDhPub,
      });
      await forgetOwnPin(userId);
      await selfMonitor(userId);
      await markDeviceSeenSafely(deviceId);
    },
  };
}

export interface OldDeviceHandshake {
  sas: string;

  finish: (code: string, loginToken?: string) => Promise<void>;
  cancel: () => void;
}


export interface PendingInvitation {
  transferId: string;
  pairSecret: Uint8Array;
  qr: string;
}


export async function beginDesktopInvitation(): Promise<PendingInvitation> {
  if (!(await loadIdentity())) {
    throw new Error('This device has no encryption identity to copy from.');
  }
  const { transferId } = await startTransfer();
  const pairSecret = randomBytes(PAIR_SECRET_BYTES);
  return {
    transferId,
    pairSecret,
    qr: encodeDeviceTransferInviteQr({ transferId, pairSecret }),
  };
}

async function oldDeviceHandshake(input: {
  transferId: string;
  pairSecret: Uint8Array;
  ikSigPub: Uint8Array;
  ikDhPub: Uint8Array;
  hello: Hello;
  channel: Channel | null;
}): Promise<OldDeviceHandshake> {
  const identity = await loadIdentity();
  if (!identity) throw new Error('This device has no encryption identity to copy from.');

  const ephemeral = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, [
    'deriveBits',
  ]);
  const recipient = await importAgreementPublicKey(input.ikDhPub);
  const shared = new Uint8Array(
    await crypto.subtle.deriveBits({ name: 'ECDH', public: recipient }, ephemeral.privateKey, 256),
  );
  const ephemeralPub = new Uint8Array(await crypto.subtle.exportKey('spki', ephemeral.publicKey));

  if (input.channel) input.channel.send(ephemeralPub);
  else await relayPut(input.transferId, 'handshake', ephemeralPub);

  const sas = await pairSas(shared, input.pairSecret);
  return {
    sas,
    cancel: () => input.channel?.close(),
    finish: async (code: string, loginToken?: string) => {
      const { grant } = await requestTransferGrant({
        transferId: input.transferId,
        ikSigPub: toBase64(input.ikSigPub),
        ikDhPub: toBase64(input.ikDhPub),
        code,
        ...(loginToken ? { loginToken } : {}),
      });

      const keys: EpochKeyEntry[] = await allEpochKeys();
      const body: BundleBody = {
        keys: keys.map((entry) => ({
          channelId: entry.channelId,
          epoch: entry.epoch,
          key: toBase64(entry.key),
        })),
      };
      const key = await transferBundleKey(shared, input.pairSecret);
      const sealed = await sealJson(key, body);

      if (input.channel) input.channel.send(sealed);
      else await relayPut(input.transferId, 'bundle', sealed);

      await authorizeNewDevice({
        transferId: input.transferId,
        grant,
        name: input.hello.name,
        platform: input.hello.platform,
        ikSigPub: input.ikSigPub,
        ikDhPub: input.ikDhPub,
        bundleSig: fromBase64(input.hello.bundleSig),
      });
      input.channel?.close();
    },
  };
}


export async function awaitInvitedDevice(pending: PendingInvitation): Promise<OldDeviceHandshake> {
  const hello = await readHello(pending.transferId, 45);
  if (!hello.ikSigPub || !hello.ikDhPub) {
    throw new Error('The phone sent an incomplete identity. Update it and start again.');
  }
  return oldDeviceHandshake({
    transferId: pending.transferId,
    pairSecret: pending.pairSecret,
    ikSigPub: fromBase64(hello.ikSigPub),
    ikDhPub: fromBase64(hello.ikDhPub),
    hello,
    channel: null,
  });
}


export async function adoptScannedDevice(raw: string): Promise<OldDeviceHandshake> {
  const scanned = decodeDeviceTransferQr(raw);
  const hello = await readHello(scanned.transferId);
  const channel = await channelOrRelay(() => openOfferer(scanned.transferId));
  return oldDeviceHandshake({
    transferId: scanned.transferId,
    pairSecret: scanned.pairSecret,
    ikSigPub: scanned.ikSigPub,
    ikDhPub: scanned.ikDhPub,
    hello,
    channel,
  });
}

async function readHello(transferId: string, attempts = 10): Promise<Hello> {
  const bytes = await relayTake(transferId, 'hello', { attempts, everyMs: 2000 }).catch(() => null);
  if (!bytes) {
    throw new Error(
      'The new device did not send its own signature. Start the transfer again on both devices.',
    );
  }
  const hello = JSON.parse(decoder.decode(bytes)) as Partial<Hello>;
  if (typeof hello.bundleSig !== 'string' || hello.bundleSig.length === 0) {
    throw new Error(
      'The new device did not send its own signature. Start the transfer again on both devices.',
    );
  }
  return {
    name: typeof hello.name === 'string' && hello.name ? hello.name : 'New device',
    platform: hello.platform === 'android' || hello.platform === 'desktop' ? hello.platform : 'web',
    bundleSig: hello.bundleSig,
    ikSigPub: typeof hello.ikSigPub === 'string' ? hello.ikSigPub : undefined,
    ikDhPub: typeof hello.ikDhPub === 'string' ? hello.ikDhPub : undefined,
  };
}
