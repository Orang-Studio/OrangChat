import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ShieldAlert } from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { retryBlockedMessages } from '../chat/outbox';
import { dismissSecurityAlert, useSecurityAlerts, type SecurityAlert } from './alerts';
import { acceptIdentityChange } from './identity';

/**
 * The escalation moment from §6.6, and the only loud one in the product. An
 * identity reset, an unauthorized device or a forked log are not notices to be
 * swiped away: they are the difference between the server having been caught and
 * the server getting away with it, so they take the screen.
 *
 * Someone who never thought about verification meets the concept here first, so
 * the copy has to stand on its own without any of it.
 */

const HEADLINE: Record<SecurityAlert['kind'], string> = {
  'identity-changed': "This account's encryption identity changed",
  'log-fork': "This account's device history does not add up",
  'log-rollback': "This account's device history went backwards",
  'unauthorized-device': 'A device appeared that nobody authorised',
  'log-omission': "Part of this account's device history is being withheld",
};

const WHAT_NOW: Record<SecurityAlert['kind'], string> = {
  'identity-changed':
    'This happens legitimately when someone loses every device and starts over. It also happens when someone is trying to read your messages. There is no way to tell the two apart from here - ask them, on a phone call or in person, before you send anything else.',
  'log-fork':
    'A device log is append-only, so this cannot happen by accident. Do not send anything sensitive in this conversation and tell the other person out of band.',
  'log-rollback':
    'Entries are never removed from a device log. Do not send anything sensitive in this conversation and tell the other person out of band.',
  'unauthorized-device':
    'A device can only join an account when one of its existing devices signs it in. This one did not, so nothing will be encrypted to it.',
  'log-omission':
    'Somebody in this conversation can see device-log entries that this device is not being shown. Compare safety numbers in person before continuing.',
};

export function SecurityAlertOverlay() {
  const alert = useSecurityAlerts((s) => s.alerts[0]);
  const queryClient = useQueryClient();
  const [failure, setFailure] = useState<string | null>(null);

  /**
   * Accepting is the recoverable path, so it is a real action rather than a
   * second dismiss - but it stays the quieter of the two buttons. Somebody who
   * has not yet made the phone call should find "I understand" first.
   */
  const accept = useMutation({
    mutationFn: (userId: string) => acceptIdentityChange(userId),
    onSuccess: (_result, userId) => {
      setFailure(null);
      dismissSecurityAlert(userId, 'identity-changed');
      retryBlockedMessages();
      void queryClient.invalidateQueries({ queryKey: ['e2ee'] });
      void queryClient.invalidateQueries({ queryKey: ['messages'] });
    },
    onError: (e: Error) => setFailure(e.message),
  });

  if (!alert) return null;

  return (
    <div
      role="alertdialog"
      aria-modal="true"
      aria-labelledby="e2ee-alert-title"
      className="fixed inset-0 z-[60] flex items-center justify-center bg-surface-1 p-6"
    >
      <div className="max-w-md space-y-4">
        <ShieldAlert aria-hidden className="size-10 text-danger" />
        <h1 id="e2ee-alert-title" className="text-xl font-semibold">
          {HEADLINE[alert.kind]}
        </h1>
        <p className="text-sm text-ink-secondary">{alert.detail}</p>
        <p className="text-sm text-ink-secondary">{WHAT_NOW[alert.kind]}</p>
        <p className="font-mono text-xs text-ink-muted">Account {alert.subject}</p>

        {failure && (
          <p role="alert" className="text-sm text-danger">
            {failure}
          </p>
        )}

        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="secondary"
            onClick={() => dismissSecurityAlert(alert.userId, alert.kind)}
          >
            I understand
          </Button>

          {alert.kind === 'identity-changed' && (
            <Button
              type="button"
              variant="ghost"
              disabled={accept.isPending}
              onClick={() => accept.mutate(alert.userId)}
            >
              {accept.isPending ? 'Accepting…' : 'I asked them - it is really them'}
            </Button>
          )}
        </div>

        {alert.kind === 'identity-changed' && (
          <p className="text-xs leading-relaxed text-ink-muted">
            Accepting starts this contact over as unverified and lets messages flow again. Anything
            they sent before they started over stays unreadable, and it does not confirm who they
            are - compare safety numbers once you can.
          </p>
        )}
      </div>
    </div>
  );
}
