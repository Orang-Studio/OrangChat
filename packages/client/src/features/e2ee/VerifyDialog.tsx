import { useCallback, useEffect, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  Check,
  Lock,
  QrCode as QrCodeIcon,
  RotateCcw,
  ScanLine,
  ShieldAlert,
  ShieldCheck,
} from 'lucide-react';
import { QR_KIND, type User } from '@orangchat/shared';
import { Button } from '../../components/ui/Button';
import { ConfirmDialog } from '../../components/ui/ConfirmDialog';
import { Dialog, DialogContent } from '../../components/ui/Dialog';
import { cn } from '../../lib/cn';
import { useAuthStore } from '../../stores/auth';
import { retryBlockedMessages } from '../chat/outbox';
import { useSecurityAlerts } from './alerts';
import { ConfirmIdentityDialog } from './ConfirmIdentityDialog';
import { EncryptionModeChoice, GroupModeNote, type EncryptionMode } from './EncryptionModeChoice';
import { HowEncryptionWorksLink } from './HowEncryptionWorks';
import {
  acceptScannedContact,
  clearVerified,
  groupSafetyNumberWith,
  myContactQr,
  safetyNumberWith,
} from './identity';
import { QrCode } from './QrCode';
import { QrScanner } from './QrScanner';
import { SafetyNumberCheck } from './SafetyNumberCheck';
import { StrictModeError, isVerified, setStrictFor, useStrictStore } from './strict';
import { rotate } from './conversation';
import { t } from "../../lib/i18n";

type Step = 'overview' | 'scan' | 'show';

/**
 * Everything a conversation's lock icon opens onto (§6.6/§6.7): what state it is
 * in, which of the two modes it is running, how to check who you are talking to,
 * and the safety code for doing that at a distance.
 *
 * Ordered for somebody who tapped the lock out of curiosity rather than intent.
 * The state and the plain-language way out come first; the safety code - a wall
 * of digits that means nothing without the sentence explaining it - comes after
 * the thing it is for.
 *
 * The dialog never claims a contact is mutually verified after one scan. A scan
 * pins what *this* device saw; the other person has pinned nothing until they
 * scan back, and pretending otherwise is exactly the bug §6.7 was written to
 * prevent.
 */
export function VerifyDialog({
  open,
  onOpenChange,
  peers,
  groupName,
  channelId,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** Everyone in the conversation but the viewer. */
  peers: User[];
  /** Set for a group DM, where the group safety number is what is shown. */
  groupName?: string | null;
  /** Enables the per-conversation strict override (§6.5). */
  channelId?: string;
}) {
  const queryClient = useQueryClient();
  const [step, setStep] = useState<Step>('overview');
  const [justScanned, setJustScanned] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmingOff, setConfirmingOff] = useState(false);
  const [confirmingReset, setConfirmingReset] = useState(false);
  const overrides = useStrictStore((s) => s.overrides);

  const globalStrict = useAuthStore((s) => s.user?.e2eeStrict === true);

  const isGroup = peers.length > 1;
  // Strict is DM-only in v1 (§6.3): one member must not be able to hold a group
  // hostage until they have personally verified everybody in it.
  const canOverride = !isGroup && channelId !== undefined;
  const strictHere = canOverride ? (overrides[channelId!] ?? globalStrict) : false;

  const peerIds = peers.map((p) => p.id);
  const identityChanged = useSecurityAlerts((s) =>
    s.alerts.some((alert) => peerIds.includes(alert.userId)),
  );

  /**
   * §6.5's conversion: a fresh key wrapped only to verified devices. Without the
   * rotation, switching an already-verified conversation to strict would carry
   * on under an epoch whose CK was wrapped to whatever devices existed when it
   * was minted - which is the thing strict mode exists to stop.
   *
   * With an unverified peer the rotation legitimately cannot happen, and that is
   * the state the user just asked for; `StrictModeError` is the gate working,
   * not a failure to report.
   */
  const tighten = () => {
    if (!channelId) return;
    // Both directions are announced, not just the downgrade: a rule that only
    // one side set is a rule the other side can only discover by tripping over
    // it, and a message that suddenly won't send reads as a bug. The notice is
    // written by the server when it stores the setting, so it cannot be skipped
    // by a client that would rather the other side didn't know.
    setStrictFor(channelId, true);
    void rotate(channelId).catch((error: unknown) => {
      if (error instanceof StrictModeError) return;
      setError(error instanceof Error ? error.message : 'Could not start a new key.');
    });
  };

  const relax = () => {
    if (!channelId) return;
    // §6.5: neither party can be downgraded without the other seeing it.
    setStrictFor(channelId, false);
    retryBlockedMessages();
  };

  useEffect(() => {
    if (!open) {
      setStep('overview');
      setJustScanned(null);
      setError(null);
    }
  }, [open]);

  const number = useQuery({
    queryKey: ['e2ee', 'safety-number', peers.map((p) => p.id).sort()],
    queryFn: () =>
      isGroup ? groupSafetyNumberWith(peers.map((p) => p.id)) : safetyNumberWith(peers[0]!.id),
    enabled: open && peers.length > 0,
  });

  const verified = useQuery({
    queryKey: ['e2ee', 'verified', peers.map((p) => p.id).sort()],
    queryFn: async () => {
      const out: Record<string, boolean> = {};
      for (const peer of peers) out[peer.id] = await isVerified(peer.id);
      return out;
    },
    enabled: open && peers.length > 0,
  });

  const myCode = useQuery({
    queryKey: ['e2ee', 'my-contact-qr'],
    queryFn: () => myContactQr(),
    enabled: open && step === 'show',
    retry: false,
  });

  const scanned = useMutation({
    mutationFn: (raw: string) => acceptScannedContact(raw),
    onSuccess: ({ userId }) => {
      setError(null);
      setJustScanned(userId);
      // Whatever strict mode was holding for this person can go now.
      retryBlockedMessages();
      void queryClient.invalidateQueries({ queryKey: ['e2ee'] });
      // One scan is one direction. Straight to our own code, with the ask.
      setStep('show');
    },
    onError: (e: Error) => setError(e.message),
  });

  const unverify = useMutation({
    mutationFn: async () => {
      for (const peer of peers) await clearVerified(peer.id);
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['e2ee'] }),
  });

  const onScan = useCallback((raw: string) => scanned.mutate(raw), [scanned]);

  const allVerified = peers.length > 0 && peers.every((p) => verified.data?.[p.id]);
  const scannedName = peers.find((p) => p.id === justScanned)?.displayName ?? 'them';
  const who = isGroup ? 'everyone here' : (peers[0]?.displayName ?? 'them');
  const reset = useMutation({
    mutationFn: () => {
      if (!channelId) throw new Error('No conversation selected');
      // Only the reset somebody asked for is announced. The automatic rotations
      // - a device joining, an epoch expiring - happen constantly and say
      // nothing about the conversation that anyone chose.
      return rotate(channelId, { announce: true });
    },
    onSuccess: () => setError(null),
    onError: (e: Error) => setError(e.message),
  });

  const StateIcon = identityChanged ? ShieldAlert : allVerified ? ShieldCheck : Lock;
  const title = identityChanged
    ? 'Something changed here'
    : allVerified
      ? "Encrypted, and you have checked who you're talking to"
      : 'This conversation is encrypted';

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={title} className="max-w-md">
        {step === 'overview' && (
          <div className="mt-3 space-y-4">
            <div
              className={cn(
                'flex gap-3 rounded-xl border px-3 py-3',
                identityChanged
                  ? 'border-danger bg-danger-soft'
                  : allVerified
                    ? 'border-success/40 bg-success/10'
                    : 'border-border bg-surface-1',
              )}
            >
              <StateIcon
                aria-hidden
                className={cn(
                  'mt-0.5 size-4 shrink-0',
                  identityChanged ? 'text-danger' : allVerified ? 'text-success' : 'text-ink-muted',
                )}
              />
              <div className="min-w-0 flex-1 space-y-1">
                <p className="text-sm leading-relaxed text-ink-secondary">
                  {identityChanged
                    ? `${isGroup ? 'Somebody here' : who} now has a different lock to the one your device remembers. That happens when somebody loses every device and starts over - and it is also what an attempt to read this conversation looks like. Ask them before you send anything else.`
                    : allVerified
                      ? `You have seen ${who}'s code with your own eyes, so nothing sent from here can be redirected to a lock somebody else made.`
                      : `Messages are locked on your device and only ${who} can open them. OrangChat stores them locked and cannot read them.`}
                </p>
                <HowEncryptionWorksLink />
              </div>
            </div>

            {canOverride && (
              <EncryptionModeChoice
                mode={strictHere ? 'verify-first' : 'standard'}
                onChange={(next: EncryptionMode) => {
                  if (next === 'verify-first') tighten();
                  else setConfirmingOff(true);
                }}
              />
            )}
            {isGroup && <GroupModeNote />}

            {!isGroup && (
              <div className="space-y-2">
                <p className="text-sm font-medium">
                  {allVerified ? 'Check them again' : `Check that it is really ${who}`}
                </p>
                <p className="text-xs leading-relaxed text-ink-muted">
                  {t("verifyDialog.standingTogetherScanEachOthersCodes")}
                </p>
                <Button
                  type="button"
                  className="w-full"
                  onClick={() => {
                    setError(null);
                    setStep('scan');
                  }}
                >
                  <ScanLine aria-hidden className="size-4" />
                  {t("verifyDialog.scanTheirCode")}
                </Button>
                <Button
                  type="button"
                  variant="secondary"
                  className="w-full"
                  onClick={() => setStep('show')}
                >
                  <QrCodeIcon aria-hidden className="size-4" />
                  {t("verifyDialog.showMyCode")}
                </Button>
                {allVerified && (
                  <Button
                    type="button"
                    variant="ghost"
                    className="w-full"
                    onClick={() => unverify.mutate()}
                    disabled={unverify.isPending}
                  >
                    {t("verifyDialog.forgetThatICheckedThem")}
                  </Button>
                )}
              </div>
            )}

            <SafetyNumberCheck
              peers={peers}
              number={number.data}
              loading={number.isLoading}
              isGroup={isGroup}
              groupName={groupName}
            />

            {channelId && (
              <div className="rounded-xl border border-border px-3 py-2.5">
                <p className="flex items-center gap-2 text-sm font-medium">
                  <RotateCcw aria-hidden className="size-4 shrink-0 text-ink-muted" />
                  {t("verifyDialog.startAFreshKey")}
                </p>
                <p className="mt-1 text-xs leading-relaxed text-ink-muted">
                  {t("verifyDialog.replacesTheKeyUsedFromNow")}
                </p>
                <Button
                  type="button"
                  size="sm"
                  variant="secondary"
                  className="mt-2"
                  onClick={() => setConfirmingReset(true)}
                  disabled={reset.isPending}
                >
                  {reset.isPending ? 'Making a new key…' : 'New key for this conversation'}
                </Button>
                {reset.isSuccess && !error && (
                  <p className="mt-2 text-xs text-success">
                    {t("verifyDialog.doneEverythingFromHereOnUses")}
                  </p>
                )}
              </div>
            )}

            {error && (
              <p role="alert" className="text-xs text-danger">
                {error}
              </p>
            )}
          </div>
        )}

        {step === 'scan' && (
          <div className="mt-3 space-y-3">
            <p className="text-sm text-ink-secondary">
              {t("verifyDialog.pointAtTheirCode", { name: peers[0]?.displayName ?? t("verifyDialog.their") })}
            </p>
            <QrScanner
              expect={QR_KIND.contactVerify}
              onScan={onScan}
              onCancel={() => setStep('overview')}
            />
            {(error || scanned.isPending) && (
              <p role="alert" className={error ? 'text-xs text-danger' : 'text-xs text-ink-muted'}>
                {error ?? 'Checking…'}
              </p>
            )}
          </div>
        )}

        {step === 'show' && (
          <div className="mt-3 space-y-3">
            {justScanned && (
              <p className="flex items-start gap-2 rounded-lg border border-success/40 bg-success/10 px-3 py-2 text-sm">
                <Check aria-hidden className="mt-0.5 size-4 shrink-0 text-success" />
                <span>{t("verifyDialog.youHaveCheckedName", { name: scannedName })}</span>
              </p>
            )}
            {myCode.data && <QrCode value={myCode.data} label={t("verifyDialog.myVerificationCode")} />}
            {myCode.error instanceof Error && (
              <p role="alert" className="text-xs text-danger">
                {myCode.error.message}
              </p>
            )}
            <p className="text-center text-xs leading-relaxed text-ink-muted">
              {t("verifyDialog.thisCodeHoldsNothingSecretIt")}
            </p>
            <Button
              type="button"
              variant="secondary"
              className="w-full"
              onClick={() => setStep('overview')}
            >
              {t("common.done")}
            </Button>
          </div>
        )}

        <ConfirmIdentityDialog
          open={confirmingOff}
          onOpenChange={setConfirmingOff}
          onConfirmed={relax}
          title={t("verifyDialog.sendWithoutCheckingThemFirst")}
          explanation="Going back to sending straight away is visible to the other person, and it takes more than an open session on this device."
        />
        <ConfirmDialog
          open={confirmingReset}
          onOpenChange={setConfirmingReset}
          onConfirm={() => {
            setConfirmingReset(false);
            reset.mutate();
          }}
          title={t("verifyDialog.startAFreshKeyHere")}
          description={t("verifyDialog.everythingSentFromNowOnUses")}
          confirmLabel={t("verifyDialog.makeANewKey")}
          loading={reset.isPending}
        />
      </DialogContent>
    </Dialog>
  );
}
