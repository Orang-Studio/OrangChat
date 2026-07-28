import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Lock, ShieldAlert, ShieldCheck } from 'lucide-react';
import type { User } from '@orangchat/shared';
import { cn } from '../../lib/cn';
import { useSecurityAlerts } from './alerts';
import { isVerified } from './strict';
import { VerifyDialog } from './VerifyDialog';

/**
 * The persistent affordance from §6.6: three states, always available, never in
 * the way.
 *
 * Unverified is deliberately *not* decorated as a problem. Default mode is
 * secure - the key is authenticated against the transparency log and monitored
 * from then on - and badging it with a warning colour would train people to
 * ignore the one state that actually matters.
 */
export function EncryptionBadge({
  peers,
  groupName,
  channelId,
}: {
  peers: User[];
  groupName?: string | null;
  channelId?: string;
}) {
  const [open, setOpen] = useState(false);
  const peerIds = peers.map((p) => p.id);

  const changed = useSecurityAlerts((s) =>
    s.alerts.some((alert) => peerIds.includes(alert.userId)),
  );

  const verified = useQuery({
    queryKey: ['e2ee', 'verified', [...peerIds].sort()],
    queryFn: async () => {
      for (const id of peerIds) if (!(await isVerified(id))) return false;
      return peerIds.length > 0;
    },
    enabled: peerIds.length > 0,
  });

  const state = changed ? 'changed' : verified.data ? 'verified' : 'encrypted';
  const Icon = state === 'changed' ? ShieldAlert : state === 'verified' ? ShieldCheck : Lock;
  const label =
    state === 'changed'
      ? 'Something changed here - open for what it means'
      : state === 'verified'
        ? "Encrypted, and you have checked who you're talking to"
        : 'Encrypted - only the people here can read this. Open to see how';

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        title={label}
        aria-label={label}
        className={cn(
          'flex items-center gap-1 rounded-lg px-2 py-1 text-xs transition-colors hover:bg-surface-3',
          state === 'changed' ? 'text-danger' : 'text-ink-muted hover:text-ink',
        )}
      >
        <Icon aria-hidden className="size-4" />
        <span className="hidden sm:inline">
          {state === 'changed'
            ? 'Identity changed'
            : state === 'verified'
              ? 'Verified'
              : 'Encrypted'}
        </span>
      </button>
      {open && (
        <VerifyDialog
          open
          onOpenChange={setOpen}
          peers={peers}
          groupName={groupName}
          channelId={channelId}
        />
      )}
    </>
  );
}
