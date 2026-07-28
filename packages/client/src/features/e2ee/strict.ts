import { create } from 'zustand';
import type { ChannelType } from '@orangchat/shared';
import { useAuthStore } from '../../stores/auth';
import { getPin } from './keystore';

/**
 * Raised when strict mode is on and a peer in this conversation has not been
 * confirmed out of band. Nothing is sent, nothing is wrapped, and no ciphertext
 * exists for a substituted key to decrypt - which is the whole difference
 * between preventing a first-contact substitution and merely detecting one.
 */
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

interface StrictState {
  /** Per-conversation override; absent means "follow the global setting". */
  overrides: Record<string, boolean>;
}

export const useStrictStore = create<StrictState>(() => ({ overrides: loadOverrides() }));

export function strictGlobal(): boolean {
  return useAuthStore.getState().user?.e2eeStrict === true;
}

/**
 * Whether strict applies here. Groups are excluded on purpose (§6.3): one strict
 * member must not be able to block a twenty-person conversation until they have
 * personally verified nineteen people, and silently downgrading instead would be
 * worse than saying so.
 */
export function strictFor(channelId: string, type: ChannelType | undefined): boolean {
  if (type !== 'dm') return false;
  const override = useStrictStore.getState().overrides[channelId];
  return override ?? strictGlobal();
}

/** `null` clears the override and returns the conversation to the global setting. */
export function setStrictFor(channelId: string, on: boolean | null): void {
  useStrictStore.setState((prev) => {
    const overrides = { ...prev.overrides };
    if (on === null) delete overrides[channelId];
    else overrides[channelId] = on;
    try {
      localStorage.setItem(CHANNELS_KEY, JSON.stringify(overrides));
    } catch {
      // A blocked storage costs the override its persistence, not its effect.
    }
    return { overrides };
  });
}

export function strictOverrideFor(channelId: string): boolean | null {
  return useStrictStore.getState().overrides[channelId] ?? null;
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
