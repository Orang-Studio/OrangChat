import type {
  ChannelType,
  E2eeDevice,
  E2eeDeviceList,
  E2eeEpoch,
  E2eeEpochKey,
  E2eeTransferGrant,
} from '@orangchat/shared';
import { api } from '../../lib/api';

export interface E2eeChannelState {
  channelId: string;
  /** Strict mode is DM-only in v1 (§6.3), so the type has to travel with it. */
  channelType: ChannelType;
  e2ee: boolean;
  epochNumber: number;
  capable: boolean;
  rotationRequired: boolean;
  currentEpochCreatedAt: string | null;
  currentEpochMessageCount: number;
  memberDevices: E2eeDevice[];
}

export interface BundlePayload {
  name: string;
  platform: 'web' | 'android' | 'desktop';
  ikSigPub: string;
  ikDhPub: string;
  bundleSig: string;
}

export interface LogEntryPayload {
  payload: string;
  prevHash: string | null;
  entryHash: string;
  signature: string;
}

export const getMyDevices = (since?: number) =>
  api<E2eeDeviceList>(`/e2ee/devices${since === undefined ? '' : `?since=${since}`}`);

export const getPeerDevices = (userId: string, since?: number) =>
  api<E2eeDeviceList>(
    `/e2ee/users/${userId}/devices${since === undefined ? '' : `?since=${since}`}`,
  );

export const enrolGenesisDevice = (
  body: BundlePayload & { identityGeneration: string; log: LogEntryPayload },
) => api<E2eeDevice>('/e2ee/devices/genesis', { method: 'POST', json: body });

export const enrolAuthorizedDevice = (
  body: BundlePayload & {
    transferId: string;
    grant: string;
    authorizedBy: string;
    authorizationSig: string;
    log: LogEntryPayload;
  },
) => api<E2eeDevice>('/e2ee/devices', { method: 'POST', json: body });

export const revokeE2eeDevice = (body: {
  deviceId: string;
  signerDeviceId: string;
  revokedAt: string;
  log: LogEntryPayload;
}) => api<E2eeDevice>('/e2ee/devices/revoke', { method: 'POST', json: body });

export const markDeviceSeen = (deviceId: string) =>
  api<{ ok: boolean }>(`/e2ee/devices/${deviceId}/seen`, { method: 'POST' });

export const startTransfer = () =>
  api<{ transferId: string }>('/e2ee/transfers', { method: 'POST' });

export const requestTransferGrant = (body: {
  transferId: string;
  ikSigPub: string;
  ikDhPub: string;
  code: string;
  /** Present when the account has no authenticator: the token from requestTransferEmailCode. */
  loginToken?: string;
}) =>
  api<E2eeTransferGrant & { expiresIn: number }>('/e2ee/transfer-grant', {
    method: 'POST',
    json: body,
  });

/** For accounts without an authenticator app: emails a one-time code to use in place of TOTP. */
export const requestTransferEmailCode = () =>
  api<{ loginToken: string }>('/e2ee/transfer-grant/email-code', { method: 'POST' });

export type TransferSlot = 'hello' | 'handshake' | 'bundle';

export const putTransferBlob = (transferId: string, slot: TransferSlot, blob: string) =>
  api<{ expiresIn: number }>(`/e2ee/transfers/${transferId}/blob`, {
    method: 'POST',
    json: { blob, slot },
  });

export const takeTransferBlob = (transferId: string, slot: TransferSlot) =>
  api<{ blob: string }>(`/e2ee/transfers/${transferId}/blob?slot=${slot}`);

export const getChannelE2eeState = (channelId: string) =>
  api<E2eeChannelState>(`/e2ee/channels/${channelId}/state`);

export const mintEpoch = (
  channelId: string,
  body: {
    id: string;
    createdBy: string;
    envelopes: {
      deviceId: string;
      ephemeralPub: string;
      wrapNonce: string;
      wrapped: string;
    }[];
  },
) => api<E2eeEpoch>(`/e2ee/channels/${channelId}/epochs`, { method: 'POST', json: body });

export const getEpochKeys = (channelId: string, deviceId: string, since?: number) =>
  api<{ keys: E2eeEpochKey[] }>(
    `/e2ee/channels/${channelId}/keys?deviceId=${encodeURIComponent(deviceId)}${
      since === undefined ? '' : `&since=${since}`
    }`,
  );

export interface KeyDeletionStatus {
  pending: boolean;
  requestedAt?: string;
  executeAfter?: string;
}

export const getKeyDeletion = () => api<KeyDeletionStatus>('/e2ee/keys/deletion');

export const requestKeyDeletion = (code?: string) =>
  api<KeyDeletionStatus>('/e2ee/keys/deletion', { method: 'POST', json: { code } });

export const cancelKeyDeletion = () =>
  api<KeyDeletionStatus>('/e2ee/keys/deletion', { method: 'DELETE' });
