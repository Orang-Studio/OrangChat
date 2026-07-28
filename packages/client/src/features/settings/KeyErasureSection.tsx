import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Trash2, TriangleAlert } from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { formatFullTime } from '../../lib/time';
import { cancelKeyDeletion, getKeyDeletion, requestKeyDeletion } from '../e2ee/api';
import { SectionTitle } from './controls';

/**
 * The way out for somebody locked out of their own encryption: every device that
 * held the key is gone, so no signature that could revoke them will ever exist,
 * and the account can neither add a device nor start over. Until this existed
 * the only fix was an operator deleting rows by hand.
 *
 * It is written to be slow and hard to miss rather than hard to reach. Whoever
 * stole a password can ask for this too, so the protection is not the button
 * being hidden - it is the wait, the mail that goes out the moment it is asked
 * for, and the fact that any surviving keyed device cancels it just by opening
 * the app.
 */
export function KeyErasureSection({ stuck }: { stuck: boolean }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);

  const status = useQuery({
    queryKey: ['e2ee', 'key-deletion'],
    queryFn: getKeyDeletion,
  });

  const done = () => {
    setError(null);
    setConfirming(false);
    setCode('');
    void queryClient.invalidateQueries({ queryKey: ['e2ee', 'key-deletion'] });
  };

  const request = useMutation({
    mutationFn: () => requestKeyDeletion(code.trim() || undefined),
    onSuccess: done,
    onError: (e: Error) => setError(e.message),
  });

  const cancel = useMutation({
    mutationFn: cancelKeyDeletion,
    onSuccess: done,
    onError: (e: Error) => setError(e.message),
  });

  const pending = status.data?.pending ?? false;

  // Somebody who followed the cancel link out of a warning email arrives here
  // needing to be told it worked. The request is already gone by then, so there
  // is nothing left in the status to say so.
  const justCancelled =
    !pending && new URLSearchParams(window.location.search).get('keyErasure') === 'cancelled';

  // Hidden for the ordinary case: somebody whose encryption is working has no
  // business here, and a destructive control that is always on screen gets
  // clicked eventually. A request already in flight always shows, on every
  // device, because being told is the entire point of the waiting period.
  if (!pending && !stuck && !justCancelled) return null;

  return (
    <section className="rounded-lg border border-danger/50 px-3 py-3">
      <SectionTitle>Lost every device</SectionTitle>

      {justCancelled && (
        <p className="mt-1 rounded-lg border border-success/40 bg-success/10 px-3 py-2 text-sm leading-relaxed">
          Stopped. Your encryption keys are staying where they are. If you did not ask for the
          erasure in the first place, change your password now - somebody else was signed in.
        </p>
      )}

      {pending ? (
        <>
          <div className="mt-1 flex items-start gap-3">
            <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0 text-danger" />
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium">Your encryption keys are scheduled to be erased</p>
              <p className="mt-1 text-xs leading-relaxed text-ink-muted">
                {status.data?.executeAfter
                  ? `This happens after ${formatFullTime(status.data.executeAfter)}, unless a device that still holds your keys opens OrangChat before then.`
                  : 'It has not happened yet.'}{' '}
                If you did not ask for this, cancel it now and change your password - somebody else
                is signed in to your account.
              </p>
            </div>
          </div>
          <Button
            className="mt-3"
            size="sm"
            onClick={() => cancel.mutate()}
            disabled={cancel.isPending}
          >
            {cancel.isPending ? 'Cancelling…' : 'Cancel - keep my keys'}
          </Button>
        </>
      ) : (
        <>
          <p className="mt-1 text-sm leading-relaxed text-ink-secondary">
            If every device you were signed in on is gone, nothing left can authorize a new one and
            this account is stuck. Erasing the keys clears that, and the next device you sign in on
            starts fresh.
          </p>
          <p className="mt-2 text-sm leading-relaxed text-danger">
            Every message already in your encrypted conversations becomes permanently unreadable -
            by you, by us, by anyone. Nothing brings them back.
          </p>

          {!confirming ? (
            <Button
              className="mt-3"
              size="sm"
              variant="secondary"
              onClick={() => setConfirming(true)}
            >
              <Trash2 aria-hidden className="size-4" />
              Erase my encryption keys
            </Button>
          ) : (
            <form
              className="mt-3 space-y-2"
              onSubmit={(e) => {
                e.preventDefault();
                request.mutate();
              }}
            >
              <p className="text-xs leading-relaxed text-ink-muted">
                Nothing is erased today. We email you straight away with a link to stop it, and if
                any device holding your keys opens OrangChat while it waits, it is cancelled on its
                own.
              </p>
              <label htmlFor="key-erasure-code" className="block text-sm font-medium">
                Two-factor code
              </label>
              <p className="text-xs leading-relaxed text-ink-muted">
                Leave this empty if you do not have two-factor turned on. It just makes the wait
                shorter - three hours instead of a day.
              </p>
              <input
                id="key-erasure-code"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                inputMode="numeric"
                autoComplete="one-time-code"
                placeholder="000000"
                className="h-11 w-full rounded-lg border border-border bg-surface-1 px-3 font-mono text-base tracking-widest text-ink placeholder:text-ink-muted hover:border-border-strong md:h-10 md:text-sm"
              />
              <div className="flex flex-wrap gap-2">
                <Button type="submit" size="sm" variant="danger" disabled={request.isPending}>
                  {request.isPending ? 'Scheduling…' : 'Schedule the erasure'}
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() => {
                    setConfirming(false);
                    setError(null);
                  }}
                >
                  Never mind
                </Button>
              </div>
            </form>
          )}
        </>
      )}

      {error && (
        <p role="alert" className="mt-2 text-sm text-danger">
          {error}
        </p>
      )}
    </section>
  );
}
