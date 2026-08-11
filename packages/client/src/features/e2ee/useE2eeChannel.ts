import { useEffect, useState } from 'react';
import type { ChannelType } from '@orangchat/shared';
import { warmChannel } from './cache';
import { rotate, syncEpochKeys } from './conversation';
import { loadIdentity } from './keystore';
import { StrictModeError } from './strict';
import { isEncrypted, refreshChannelState, useE2eeStore } from './store';

export interface ChannelEncryption {

  encrypted: boolean;

  capable: boolean;

  blocker: string | null;
}


export function useE2eeChannel(channelId: string, type: ChannelType): ChannelEncryption {
  const state = useE2eeStore((s) => s.channels[channelId]);
  const latched = useE2eeStore((s) => s.latched[channelId] === true);
  const [blocker, setBlocker] = useState<string | null>(null);

  const direct = type === 'dm' || type === 'group_dm';

  useEffect(() => {
    if (!direct) return;
    let cancelled = false;

    void (async () => {
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
