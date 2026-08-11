import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Trash2, TriangleAlert } from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { formatFullTime } from '../../lib/time';
import { cancelKeyDeletion, getKeyDeletion, requestKeyDeletion } from '../e2ee/api';
import { eraseKeysNow } from '../e2ee/identity';
import { SectionTitle } from './controls';
import { t } from "../../lib/i18n";


export function KeyErasureSection({ stuck, keyed }: { stuck: boolean; keyed: boolean }) {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);

  const status = useQuery({
    queryKey: ['e2ee', 'key-deletion'],
    queryFn: getKeyDeletion,
    refetchInterval: (query) => (query.state.data?.pending ? 15_000 : false),
  });

  const done = () => {
    setError(null);
    setConfirming(false);
    setCode('');
    void queryClient.invalidateQueries({ queryKey: ['e2ee'] });
  };

  const request = useMutation({
    mutationFn: () => requestKeyDeletion(code.trim() || undefined),
    onSuccess: done,
    onError: (e: Error) => setError(e.message),
  });

  const eraseNow = useMutation({
    mutationFn: eraseKeysNow,
    onSuccess: done,
    onError: (e: Error) => setError(e.message),
  });

  const cancel = useMutation({
    mutationFn: cancelKeyDeletion,
    onSuccess: done,
    onError: (e: Error) => setError(e.message),
  });

  const pending = status.data?.pending ?? false;

  const justCancelled =
    !pending && new URLSearchParams(window.location.search).get('keyErasure') === 'cancelled';

  if (!pending && !stuck && !keyed && !justCancelled) return null;

  return (
    <section className="rounded-lg border border-danger/50 px-3 py-3">
      <SectionTitle>{keyed && !pending ? 'Start over with new keys' : 'Lost every device'}</SectionTitle>

      {justCancelled && (
        <p className="mt-1 rounded-lg border border-success/40 bg-success/10 px-3 py-2 text-sm leading-relaxed">
          {t("keyErasureSection.stoppedYourEncryptionKeysAreStaying")}
        </p>
      )}

      {pending ? (
        <>
          <div className="mt-1 flex items-start gap-3">
            <TriangleAlert aria-hidden className="mt-0.5 size-5 shrink-0 text-danger" />
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium">{t("keyErasureSection.yourEncryptionKeysAreScheduledTo")}</p>
              <p className="mt-1 text-xs leading-relaxed text-ink-muted">
                {status.data?.executeAfter
                  ? t("keyErasureSection.happensAfter", {
                      time: formatFullTime(status.data.executeAfter),
                    })
                  : t("keyErasureSection.hasNotHappenedYet")}{" "}
                {t("keyErasureSection.ifYouDidNotAskForThis")}
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
            {keyed
              ? 'This device still holds your keys, so it can throw the whole encryption identity away and start again - every device on the account is removed and the next one you sign in on sets up fresh keys.'
              : 'If every device you were signed in on is gone, nothing left can authorize a new one and this account is stuck. Erasing the keys clears that, and the next device you sign in on starts fresh.'}
          </p>
          <p className="mt-2 text-sm leading-relaxed text-danger">
            {t("keyErasureSection.everyMessageAlreadyInYourEncrypted")}
          </p>

          {!confirming ? (
            <Button
              className="mt-3"
              size="sm"
              variant="secondary"
              onClick={() => setConfirming(true)}
            >
              <Trash2 aria-hidden className="size-4" />
              {t("keyErasureSection.eraseMyEncryptionKeys")}
            </Button>
          ) : (
            <form
              className="mt-3 space-y-2"
              onSubmit={(e) => {
                e.preventDefault();
                if (keyed) eraseNow.mutate();
                else request.mutate();
              }}
            >
              <p className="text-xs leading-relaxed text-ink-muted">
                {keyed
                  ? 'This happens the moment you press the button - there is no waiting period and nothing to cancel, because this device signs for it with the key itself.'
                  : 'Nothing is erased today. We email you straight away with a link to stop it, and if any device holding your keys opens OrangChat while it waits, it is cancelled on its own.'}
              </p>
              {!keyed && (
                <>
                  <label htmlFor="key-erasure-code" className="block text-sm font-medium">
                    {t("keyErasureSection.twoFactorCode")}
                  </label>
                  <p className="text-xs leading-relaxed text-ink-muted">
                    {t("keyErasureSection.leaveThisEmptyIfYouDo")}
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
                </>
              )}
              <div className="flex flex-wrap gap-2">
                <Button
                  type="submit"
                  size="sm"
                  variant="danger"
                  disabled={keyed ? eraseNow.isPending : request.isPending}
                >
                  {keyed
                    ? eraseNow.isPending
                      ? 'Erasing…'
                      : 'Erase them now'
                    : request.isPending
                      ? 'Scheduling…'
                      : 'Schedule the erasure'}
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
                  {t("keyErasureSection.neverMind")}
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
