import { useEffect, useRef, useState } from 'react';
import { Check, Loader2, Mail, QrCodeIcon, ShieldCheck, Smartphone } from 'lucide-react';
import { QR_KIND } from '@orangchat/shared';
import { Button } from '../../components/ui/Button';
import { Dialog, DialogContent } from '../../components/ui/Dialog';
import { TextField } from '../../components/ui/TextField';
import { useAuthStore } from '../../stores/auth';
import { QrCode } from './QrCode';
import { QrScanner } from './QrScanner';
import { requestTransferEmailCode } from './api';
import {
  adoptScannedDevice,
  awaitInvitedDevice,
  awaitAdoption,
  beginDesktopInvitation,
  beginEnrolment,
  type NewDeviceHandshake,
  type OldDeviceHandshake,
  type PendingInvitation,
  type PendingEnrolment,
} from './transfer';

/**
 * Device transfer (docs/E2EE.md §4), from both ends.
 *
 * The six digits are the part that cannot be skipped. The QR gives
 * confidentiality - whoever holds the pairing secret can talk to this device -
 * but it does not prove the thing on the other end is the phone in your hand. A
 * relay attacker who photographed the code over a shoulder produces different
 * digits, and a person comparing two screens is what catches that.
 */

function Digits({ value }: { value: string }) {
  return <p className="text-center font-mono text-3xl font-semibold tracking-[0.3em]">{value}</p>;
}

function TransferProgress({ step }: { step: 1 | 2 | 3 | 4 }) {
  const labels = ['Scan', 'Compare', 'Verify', 'Finish'];
  return (
    <ol aria-label="Transfer progress" className="grid grid-cols-4 gap-1">
      {labels.map((label, index) => {
        const number = index + 1;
        const complete = number < step;
        const active = number === step;
        return (
          <li
            key={label}
            aria-current={active ? 'step' : undefined}
            className={`flex min-w-0 flex-col items-center gap-1 text-[11px] ${
              active ? 'font-semibold text-primary' : complete ? 'text-success' : 'text-ink-muted'
            }`}
          >
            <span
              className={`grid size-6 place-items-center rounded-full border ${
                active
                  ? 'border-primary bg-primary-soft'
                  : complete
                    ? 'border-success bg-success/10'
                    : 'border-border'
              }`}
            >
              {complete ? <Check aria-hidden className="size-3.5" /> : number}
            </span>
            <span className="truncate">{label}</span>
          </li>
        );
      })}
    </ol>
  );
}

function SasStep({
  sas,
  busy,
  error,
  onConfirm,
  onCancel,
  children,
}: {
  sas: string;
  busy: boolean;
  error: string | null;
  onConfirm: () => void;
  onCancel: () => void;
  children?: React.ReactNode;
}) {
  return (
    <div className="space-y-4">
      <p className="text-sm text-ink-secondary">
        Both screens should show the same six digits. If they differ, something is between your two
        devices - stop here.
      </p>
      <Digits value={sas} />
      {children}
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      <div className="flex gap-2">
        <Button type="button" className="flex-1" onClick={onConfirm} loading={busy}>
          They match
        </Button>
        <Button type="button" variant="ghost" onClick={onCancel} disabled={busy}>
          They don't
        </Button>
      </div>
    </div>
  );
}

/** This browser is the one being added: it shows the code and waits. */
function AddThisDevice({ onDone }: { onDone: () => void }) {
  const userId = useAuthStore((s) => s.user?.id);
  const [pending, setPending] = useState<PendingEnrolment | null>(null);
  const [handshake, setHandshake] = useState<NewDeviceHandshake | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const started = useRef(false);

  useEffect(() => {
    if (!userId || started.current) return;
    started.current = true;

    void (async () => {
      try {
        const enrolment = await beginEnrolment(userId);
        setPending(enrolment);
        setHandshake(await awaitAdoption(enrolment, userId));
      } catch (e) {
        setError(e instanceof Error ? e.message : 'Could not start the transfer.');
      }
    })();
  }, [userId]);

  if (error && !handshake) {
    return (
      <p role="alert" className="mt-3 text-sm text-danger">
        {error}
      </p>
    );
  }

  if (handshake) {
    if (busy) {
      return (
        <div className="mt-3 space-y-4">
          <TransferProgress step={3} />
          <div
            role="status"
            className="flex items-start gap-3 rounded-lg border border-border bg-surface-1 p-3"
          >
            <Loader2 aria-hidden className="mt-0.5 size-5 shrink-0 animate-spin text-primary" />
            <div>
              <p className="text-sm font-semibold">Digits confirmed</p>
              <p className="text-xs text-ink-muted">
                Enter the security code on the authorized device. This device is waiting for the
                encrypted history and signed authorization.
              </p>
            </div>
          </div>
        </div>
      );
    }
    return (
      <div className="mt-3">
        <TransferProgress step={2} />
        <div className="mt-4">
          <SasStep
            sas={handshake.sas}
            busy={busy}
            error={error}
            onCancel={() => {
              handshake.cancel();
              onDone();
            }}
            onConfirm={() => {
              setBusy(true);
              setError(null);
              void handshake
                .finish()
                .then(onDone)
                .catch((e: Error) => setError(e.message))
                .finally(() => setBusy(false));
            }}
          />
        </div>
      </div>
    );
  }

  return (
    <div className="mt-3 space-y-3">
      <p className="text-sm text-ink-secondary">
        On a device you're already signed in on, open Settings → Encryption and choose "Add another
        device", then point it at this code. Keep them close together - the transfer only goes over
        your local network.
      </p>
      {pending ? (
        <QrCode value={pending.qr} label="Device transfer code" />
      ) : (
        <div className="flex justify-center py-8">
          <Loader2 aria-hidden className="size-5 animate-spin text-ink-muted" />
        </div>
      )}
      <p className="flex items-center justify-center gap-1.5 text-xs text-ink-muted">
        <Smartphone aria-hidden className="size-3.5" />
        Waiting for the other device…
      </p>
    </div>
  );
}

/** This browser already holds the keys: show a code the phone can scan. */
function AddAnotherDevice({ onDone }: { onDone: () => void }) {
  const twoFactorEnabled = useAuthStore((state) => state.user?.twoFactorEnabled === true);
  const [handshake, setHandshake] = useState<OldDeviceHandshake | null>(null);
  const [invitation, setInvitation] = useState<PendingInvitation | null>(null);
  const [manual, setManual] = useState(false);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmed, setConfirmed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [loginToken, setLoginToken] = useState<string | null>(null);
  const [sendingEmail, setSendingEmail] = useState(false);
  const started = useRef(false);

  useEffect(() => {
    if (manual || started.current) return;
    started.current = true;
    let cancelled = false;
    void beginDesktopInvitation()
      .then((pending) => {
        if (cancelled) throw new Error('Transfer cancelled.');
        setInvitation(pending);
        return awaitInvitedDevice(pending);
      })
      .then((next) => {
        if (cancelled) next.cancel();
        else setHandshake(next);
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      });
    return () => {
      cancelled = true;
    };
  }, [manual, attempt]);

  const sendEmailCode = () => {
    setSendingEmail(true);
    setError(null);
    void requestTransferEmailCode()
      .then(({ loginToken: token }) => setLoginToken(token))
      .catch((e: Error) => setError(e.message))
      .finally(() => setSendingEmail(false));
  };

  if (!handshake) {
    if (!manual) {
      return (
        <div className="mt-3 space-y-4">
          <TransferProgress step={1} />
          <div className="rounded-lg border border-border bg-surface-1 p-3">
            <p className="flex items-center gap-2 text-sm font-semibold">
              <Smartphone aria-hidden className="size-4 text-primary" />
              Scan this with your phone
            </p>
            <ol className="mt-2 list-decimal space-y-1 pl-5 text-sm text-ink-secondary">
              <li>Update the phone to OrangChat 0.5.4 and sign in to this account.</li>
              <li>Open Settings → Encryption → Add this phone.</li>
              <li>Tap “Scan code from PC” and point OrangChat at this code.</li>
            </ol>
          </div>
          {invitation ? (
            <QrCode value={invitation.qr} label="Add phone to this account" />
          ) : (
            <div className="flex items-center justify-center gap-2 py-10 text-sm text-ink-muted">
              <Loader2 aria-hidden className="size-5 animate-spin" />
              Creating one-time code…
            </div>
          )}
          <p
            role="status"
            className="flex items-center justify-center gap-2 text-xs text-ink-muted"
          >
            <span className="relative flex size-2">
              <span className="absolute inline-flex size-full animate-ping rounded-full bg-primary opacity-50" />
              <span className="relative inline-flex size-2 rounded-full bg-primary" />
            </span>
            Waiting for your phone to scan…
          </p>
          {error && (
            <div className="space-y-2">
              <p role="alert" className="text-sm text-danger">
                {error}
              </p>
              <Button
                type="button"
                variant="secondary"
                className="w-full"
                onClick={() => {
                  started.current = false;
                  setInvitation(null);
                  setError(null);
                  setAttempt((value) => value + 1);
                }}
              >
                Try again
              </Button>
            </div>
          )}
          <Button
            type="button"
            variant="ghost"
            className="w-full"
            onClick={() => {
              setManual(true);
              setError(null);
            }}
          >
            <QrCodeIcon aria-hidden className="size-4" />
            Use a code shown by the new device instead
          </Button>
        </div>
      );
    }

    return (
      <div className="mt-3 space-y-3">
        <p className="text-sm text-ink-secondary">
          Scan the code shown on the new device, or use the paste option if this PC has no camera.
        </p>
        <QrScanner
          expect={QR_KIND.deviceTransfer}
          onCancel={onDone}
          onScan={(raw) => {
            setError(null);
            setBusy(true);
            void adoptScannedDevice(raw)
              .then(setHandshake)
              .catch((e: Error) => setError(e.message))
              .finally(() => setBusy(false));
          }}
        />
        {busy && <p className="text-xs text-ink-muted">Connecting to the other device…</p>}
        {error && (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        )}
        <Button
          type="button"
          variant="ghost"
          className="w-full"
          onClick={() => {
            setManual(false);
            started.current = false;
            setError(null);
            setAttempt((value) => value + 1);
          }}
        >
          Show a code for my phone to scan
        </Button>
      </div>
    );
  }

  if (!confirmed) {
    return (
      <div className="mt-3">
        <TransferProgress step={2} />
        <div className="mt-4">
          <SasStep
            sas={handshake.sas}
            busy={false}
            error={error}
            onCancel={() => {
              handshake.cancel();
              onDone();
            }}
            onConfirm={() => setConfirmed(true)}
          />
        </div>
      </div>
    );
  }

  return busy ? (
    <div className="mt-3 space-y-4">
      <TransferProgress step={4} />
      <div
        role="status"
        className="flex items-start gap-3 rounded-lg border border-border bg-surface-1 p-3"
      >
        <Loader2 aria-hidden className="mt-0.5 size-5 shrink-0 animate-spin text-primary" />
        <div>
          <p className="text-sm font-semibold">Authorizing your phone</p>
          <p className="text-xs text-ink-muted">
            Encrypting conversation-history keys, signing the new device, and waiting for the phone
            to store them securely…
          </p>
        </div>
      </div>
    </div>
  ) : (
    <form
      className="mt-3 space-y-3"
      onSubmit={(event) => {
        event.preventDefault();
        setBusy(true);
        setError(null);
        void handshake
          .finish(code.trim(), loginToken ?? undefined)
          .then(onDone)
          .catch((e: Error) => setError(e.message))
          .finally(() => setBusy(false));
      }}
    >
      <TransferProgress step={3} />
      <p className="text-sm text-ink-secondary">
        Adding a device is a security event, so it takes a fresh second-factor code. Every other
        device on your account will be told about it.
      </p>
      {!twoFactorEnabled && !loginToken && (
        <div className="rounded-lg border border-border bg-surface-1 p-3">
          <p className="flex items-center gap-2 text-sm font-semibold">
            <Mail aria-hidden className="size-4 text-primary" />
            Use an email code instead
          </p>
          <p className="mt-1 text-xs text-ink-secondary">
            This account has no authenticator app set up, so OrangChat will email a one-time code
            to the address on your account. Check your email before entering it here.
          </p>
          <Button
            type="button"
            variant="secondary"
            className="mt-3 w-full"
            onClick={sendEmailCode}
            loading={sendingEmail}
          >
            <Mail aria-hidden className="size-4" />
            Email me a code
          </Button>
        </div>
      )}
      {!twoFactorEnabled && loginToken && (
        <p role="status" className="flex items-center gap-2 text-xs text-ink-secondary">
          <Mail aria-hidden className="size-3.5 text-primary" />
          A one-time code is on its way to your email - it expires in 10 minutes.
        </p>
      )}
      <TextField
        label={twoFactorEnabled ? 'Authentication code' : 'Email code'}
        value={code}
        onChange={(e) => setCode(e.target.value)}
        inputMode="numeric"
        maxLength={twoFactorEnabled ? 8 : 6}
        autoFocus
      />
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
      <Button
        type="submit"
        className="w-full"
        loading={busy}
        disabled={code.trim().length === 0 || (!twoFactorEnabled && loginToken === null)}
      >
        <ShieldCheck aria-hidden className="size-4" />
        Add this device
      </Button>
      {!twoFactorEnabled && loginToken && (
        <Button
          type="button"
          variant="ghost"
          className="w-full"
          onClick={sendEmailCode}
          disabled={sendingEmail}
        >
          Resend email code
        </Button>
      )}
    </form>
  );
}

export function TransferDialog({
  open,
  onOpenChange,
  role,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  /** "new" when this browser has no identity yet, "old" when it holds the keys. */
  role: 'new' | 'old';
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        title={role === 'new' ? 'Add this device' : 'Add another device'}
        className="max-w-md"
      >
        {role === 'new' ? (
          <AddThisDevice onDone={() => onOpenChange(false)} />
        ) : (
          <AddAnotherDevice onDone={() => onOpenChange(false)} />
        )}
      </DialogContent>
    </Dialog>
  );
}
