import { useEffect, useState } from 'react';
import type { ChannelType } from '@orangchat/shared';
import { warmChannel } from './cache';
import { rotate, syncEpochKeys } from './conversation';
import { loadIdentity } from './keystore';
import { StrictModeError } from './strict';
import { isEncrypted, refreshChannelState, useE2eeStore } from './store';

export interface ChannelEncryption {
  /** True once this conversation has ever been seen encrypted. */
  encrypted: boolean;
  /** False while some participant has no device that can hold a key. */
  capable: boolean;
  /** Set when this device cannot take part yet. */
  blocker: string | null;
}

/**
 * Brings a conversation's encryption state up to date when it is opened, and
 * turns encryption on once every participant can take part.
 *
 * Enabling is automatic rather than a setting: there is no unencrypted mode for
 * DMs, only conversations still waiting on a participant to have a device. A
 * conversation that is still plaintext is labelled plaintext, never "encrypted,
 * pending".
 */
export function useE2eeChannel(channelId: string, type: ChannelType): ChannelEncryption {
  const state = useE2eeStore((s) => s.channels[channelId]);
  const latched = useE2eeStore((s) => s.latched[channelId] === true);
  const [blocker, setBlocker] = useState<string | null>(null);

  const direct = type === 'dm' || type === 'group_dm';

  useEffect(() => {
    if (!direct) return;
    let cancelled = false;

    void (async () => {
      // Pull this conversation's decrypted history back into memory first, so a
      // reload renders and searches without re-deriving every message key.
      void warmChannel(channelId);

      try {
        const identity = await loadIdentity();
        if (!identity) {
          if (!cancelled) {
            setBlocker('Set up encryption for this device in Settings → Encryption.');
          }
          return;
        }

        const fresh = await refreshChannelState(channelId);
        if (cancelled) return;

        if (fresh.e2ee) await syncEpochKeys(channelId);
        else if (fresh.capable) await rotate(channelId);

        if (!cancelled) setBlocker(null);
      } catch (error) {
        // Strict mode refusing to mint a key is not a fault to report here: the
        // conversation is fine, the user has simply asked to verify first, and
        // VerificationNotice is where that gets said.
        if (error instanceof StrictModeError) {
          if (!cancelled) setBlocker(null);
          return;
        }
        if (!cancelled) {
          setBlocker(error instanceof Error ? error.message : 'Encryption is unavailable.');
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [channelId, direct]);

  return {
    encrypted: direct && (latched || isEncrypted(channelId)),
    capable: state?.capable ?? false,
    blocker: direct ? blocker : null,
  };
}
