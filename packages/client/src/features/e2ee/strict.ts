import { create } from 'zustand';
import type { ChannelType } from '@orangchat/shared';
import { api } from '../../lib/api';
import { useAuthStore } from '../../stores/auth';
import { getPin } from './keystore';


export class StrictModeError extends Error {
  readonly unverified: string[];

  constructor(unverified: string[]) {
    super('Verify this conversation before sending.');
    this.name = 'StrictModeError';
    this.unverified = unverified;
  }
}

const CHANNELS_KEY = 'orangchat.e2ee.strict.channels';

function loadOverrides(): Record<string, boolean> {
  try {
    const raw = localStorage.getItem(CHANNELS_KEY);
    return raw ? (JSON.parse(raw) as Record<string, boolean>) : {};
  } catch {
    return {};
  }
}

function persist(overrides: Record<string, boolean>): void {
  try {
    localStorage.setItem(CHANNELS_KEY, JSON.stringify(overrides));
  } catch {
  }
}

interface StrictState {

  overrides: Record<string, boolean>;
}


export const useStrictStore = create<StrictState>(() => ({ overrides: loadOverrides() }));


export async function loadStrictOverrides(): Promise<void> {
  try {
    const { overrides } = await api<{ overrides: Record<string, boolean> }>('/me/e2ee-strict');
    persist(overrides);
    useStrictStore.setState({ overrides });
  } catch {
  }
}

export function strictGlobal(): boolean {
  return useAuthStore.getState().user?.e2eeStrict === true;
}


export function strictFor(channelId: string, type: ChannelType | undefined): boolean {
  if (type !== 'dm') return false;
  const override = useStrictStore.getState().overrides[channelId];
  return override ?? strictGlobal();
}


export function setStrictFor(channelId: string, on: boolean | null): void {
  useStrictStore.setState((prev) => {
    const overrides = { ...prev.overrides };
    if (on === null) delete overrides[channelId];
    else overrides[channelId] = on;
    persist(overrides);
    return { overrides };
  });
  void api(`/channels/${channelId}/e2ee-strict`, { method: 'PUT', json: { on } }).catch(() => {});
}

export async function isVerified(userId: string): Promise<boolean> {
  const pin = await getPin(userId);
  return pin?.verifiedAt != null;
}

/** Everyone in the list whose identity has not been confirmed out of band. */
export async function unverifiedPeers(userIds: readonly string[]): Promise<string[]> {
  const selfId = useAuthStore.getState().user?.id;
  const out: string[] = [];
  for (const userId of new Set(userIds)) {
    if (userId === selfId) continue;
    if (!(await isVerified(userId))) out.push(userId);
  }
  return out;
}

/**
 * The gate that makes strict mode prevention rather than detection. Called
 * before a conversation key is created or wrapped, never after: a CK that
 * already exists for an unconfirmed key has already lost.
 */
export async function assertMayEncryptTo(
  channelId: string,
  type: ChannelType | undefined,
  userIds: readonly string[],
): Promise<void> {
  if (!strictFor(channelId, type)) return;
  const unverified = await unverifiedPeers(userIds);
  if (unverified.length > 0) throw new StrictModeError(unverified);
}
