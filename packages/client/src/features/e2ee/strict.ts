import { create } from 'zustand';
import type { ChannelType } from '@orangchat/shared';
import { api } from '../../lib/api';
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

function persist(overrides: Record<string, boolean>): void {
  try {
    localStorage.setItem(CHANNELS_KEY, JSON.stringify(overrides));
  } catch {
    // A blocked storage costs the cache, not the setting - the server has it.
  }
}

interface StrictState {
  /** Per-conversation override; absent means "follow the global setting". */
  overrides: Record<string, boolean>;
}

/**
 * The per-conversation overrides.
 *
 * The rule is enforced here, on this device - the server cannot stop a client
 * from encrypting to whoever it likes, so this is where it has to be checked.
 * It is *stored* on the server so that turning it on or off is an action the
 * server carried out and can therefore announce itself: §6.5 requires the other
 * side to be told, and a notice the peer's client typed is one the peer's client
 * could have skipped. localStorage stays as the local copy, so a reload before
 * the fetch lands still enforces what the user last chose rather than silently
 * falling back to the global default.
 */
export const useStrictStore = create<StrictState>(() => ({ overrides: loadOverrides() }));

/** Pull this account's overrides down. Called once the session is up. */
export async function loadStrictOverrides(): Promise<void> {
  try {
    const { overrides } = await api<{ overrides: Record<string, boolean> }>('/me/e2ee-strict');
    persist(overrides);
    useStrictStore.setState({ overrides });
  } catch {
    // Offline or signed out: the cached copy is still the last thing the user
    // chose, which is a better answer than the global default.
  }
}

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

/**
 * `null` clears the override and returns the conversation to the global setting.
 *
 * Applied locally first and sent on: the gate must be in force from the moment
 * the user asked for it, not from whenever the round trip lands. The server's
 * copy is what makes the change announceable; if the request fails the rule is
 * still enforced here, and the other side simply was not told.
 */
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
