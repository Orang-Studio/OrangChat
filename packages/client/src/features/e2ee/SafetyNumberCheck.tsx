import { useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { Check, Copy, Phone, ShieldAlert, ShieldCheck } from 'lucide-react';
import { normalizeSafetyNumber, safetyNumbersMatch, type User } from '@orangchat/shared';
import { Button } from '../../components/ui/Button';
import { retryBlockedMessages } from '../chat/outbox';
import { markVerified } from './identity';
import { t } from "../../lib/i18n";

type Result =
  | { kind: 'match' }
  | { kind: 'mismatch' }
  | { kind: 'incomplete'; digits: number }
  | { kind: 'error'; message: string };

function SafetyNumber({ value }: { value: string }) {
  return (
    <p className="rounded-lg border border-border bg-surface-1 p-3 text-center font-mono text-sm leading-7 tracking-wider">
      {value.split(' ').map((group, index) => (
        <span key={`${group}-${index}`} className="mx-1 inline-block">
          {group}
        </span>
      ))}
    </p>
  );
}

/**
 * The half of verification that works at a distance (§6.6/§6.7). Scanning needs
 * two people and one room; everything else needs this.
 *
 * Reading sixty digits down a phone call and eyeballing them was already the
 * documented way out, but with nowhere to type the answer it ended there: a
 * comparison the user performed and the app never learned the result of. So a
 * remote pair could not reach verified, which in turn made verify-first mode
 * unreachable for anyone who was not standing next to their contact.
 *
 * Typing it is not a weaker check than scanning - the digits still had to travel
 * over a channel the server does not control, and it is the user's ear, not this
 * field, that authenticates the voice reading them. What it does add is that the
 * comparison is done by a machine, so a single swapped digit in the middle of a
 * long number cannot be waved through by someone who has already decided it
 * probably matches.
 */
export function SafetyNumberCheck({
  peers,
  number,
  loading,
  isGroup,
  groupName,
}: {
  peers: User[];
  /** Null once derived but unavailable - see the note the card shows for it. */
  number: string | null | undefined;
  loading: boolean;
  isGroup: boolean;
  groupName?: string | null;
}) {
  const queryClient = useQueryClient();
  const [typed, setTyped] = useState('');
  const [result, setResult] = useState<Result | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => setResult(null), [typed]);

  const copy = async () => {
    if (!number) return;
    await navigator.clipboard.writeText(number);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  /**
   * A group's number is a one-glance confirmation that everyone is in the same
   * group with the same people, and §6.3 keeps it informational - so a match
   * here says so and pins nothing. Marking every member verified off one
   * comparison would claim far more than the comparison established.
   */
  const check = useMutation({
    mutationFn: async (): Promise<Result> => {
      if (!number) return { kind: 'error', message: 'There is no code to compare yet.' };
      if (normalizeSafetyNumber(typed) === null) {
        return { kind: 'incomplete', digits: typed.replace(/\D/g, '').length };
      }
      if (!safetyNumbersMatch(typed, number)) return { kind: 'mismatch' };
      if (!isGroup && peers[0]) await markVerified(peers[0].id);
      return { kind: 'match' };
    },
    onSuccess: (outcome) => {
      setResult(outcome);
      if (outcome.kind !== 'match' || isGroup) return;
      retryBlockedMessages();
      void queryClient.invalidateQueries({ queryKey: ['e2ee'] });
    },
    onError: (e: Error) => setResult({ kind: 'error', message: e.message }),
  });

  const who = isGroup ? 'everyone here' : (peers[0]?.displayName ?? 'them');

  return (
    <div className="rounded-xl border border-border px-3 py-2.5">
      <p className="flex items-center gap-2 text-sm font-medium">
        <Phone aria-hidden className="size-4 shrink-0 text-ink-muted" />
        {t("safetyNumberCheck.notInTheSameRoom")}
      </p>
      <p className="mt-1 text-xs leading-relaxed text-ink-muted">
        {isGroup
          ? `Everyone in ${groupName ?? 'this group'} sees the same numbers, and only while they are all in the same group with the same people. Read them out to each other to confirm it.`
          : 'Read these numbers to each other on a phone call, or send them over another app you already trust. If they match, nobody is in the middle. An OrangChat call does not count - its audio goes through the servers this check is testing.'}
      </p>

      {loading && <p className="mt-2 text-xs text-ink-muted">{t("safetyNumberCheck.workingItOut")}</p>}

      {number && (
        <>
          <div className="mt-2">
            <SafetyNumber value={number} />
          </div>
          <div className="mt-2 flex justify-end">
            <Button type="button" size="sm" variant="ghost" onClick={() => void copy()}>
              {copied ? (
                <Check aria-hidden className="size-4" />
              ) : (
                <Copy aria-hidden className="size-4" />
              )}
              {copied ? 'Copied' : 'Copy'}
            </Button>
          </div>

          <form
            className="mt-2 space-y-2 border-t border-border pt-3"
            onSubmit={(e) => {
              e.preventDefault();
              check.mutate();
            }}
          >
            <label htmlFor="safety-number-typed" className="block text-sm font-medium">
              {isGroup ? 'Type the numbers somebody read out' : `Type the numbers ${who} read out`}
            </label>
            <p className="text-xs leading-relaxed text-ink-muted">
              {t("safetyNumberCheck.checkingThemHereIsSaferThan")}
            </p>
            <input
              id="safety-number-typed"
              value={typed}
              onChange={(e) => setTyped(e.target.value)}
              inputMode="numeric"
              autoComplete="off"
              spellCheck={false}
              placeholder="00000 00000 00000 …"
              aria-invalid={result?.kind === 'mismatch' || result?.kind === 'incomplete'}
              className="h-11 w-full rounded-lg border border-border bg-surface-1 px-3 font-mono text-base tracking-wider text-ink placeholder:text-ink-muted hover:border-border-strong md:h-10 md:text-sm"
            />
            <Button
              type="submit"
              variant="secondary"
              className="w-full"
              disabled={check.isPending || typed.trim() === ''}
            >
              {check.isPending ? 'Comparing…' : 'Compare'}
            </Button>
          </form>

          {result?.kind === 'incomplete' && (
            <p role="alert" className="mt-2 text-xs leading-relaxed text-ink-muted">
              {t("safetyNumberCheck.digitsOf60", { digits: result.digits })}
            </p>
          )}

          {result?.kind === 'mismatch' && (
            <p
              role="alert"
              className="mt-2 flex items-start gap-2 rounded-lg border border-danger bg-danger-soft px-3 py-2 text-xs leading-relaxed"
            >
              <ShieldAlert aria-hidden className="mt-0.5 size-4 shrink-0 text-danger" />
              <span>
                {t("safetyNumberCheck.theseDoNotMatchMostOften")}
              </span>
            </p>
          )}

          {result?.kind === 'match' && (
            <p className="mt-2 flex items-start gap-2 rounded-lg border border-success/40 bg-success/10 px-3 py-2 text-xs leading-relaxed">
              <ShieldCheck aria-hidden className="mt-0.5 size-4 shrink-0 text-success" />
              <span>
                {isGroup
                  ? 'Identical. Everyone here is in the same group with the same people, and nothing has been swapped underneath it.'
                  : `Identical, so nothing has been swapped underneath this conversation. This device has now checked ${who} - have them compare it on their side too, or their app still has nothing written down.`}
              </span>
            </p>
          )}

          {result?.kind === 'error' && (
            <p role="alert" className="mt-2 text-xs text-danger">
              {result.message}
            </p>
          )}
        </>
      )}

      {number === null && (
        <p className="mt-2 text-xs text-ink-muted">
          {t("safetyNumberCheck.theseNumbersAppearOnceBothAccounts")}
        </p>
      )}
    </div>
  );
}
