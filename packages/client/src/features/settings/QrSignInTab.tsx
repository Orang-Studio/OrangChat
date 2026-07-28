import { useState } from 'react';
import { Check, ScanLine, ShieldCheck } from 'lucide-react';
import { QR_KIND } from '@orangchat/shared';
import { Button } from '../../components/ui/Button';
import { qrApprove, qrScan } from '../auth/api';
import { QrScanner } from '../e2ee/QrScanner';

function tokenFrom(raw: string): string {
  const url = new URL(raw);
  if (url.protocol !== 'orangchat:' || url.hostname !== QR_KIND.signIn) {
    throw new Error('That is not an OrangChat sign-in code.');
  }
  const token = url.searchParams.get('token')?.trim();
  if (!token) throw new Error('This sign-in code is missing its token.');
  return token;
}

/** Lets an already signed-in device authorise a web QR sign-in from Settings. */
export function QrSignInTab() {
  const [token, setToken] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [approving, setApproving] = useState(false);
  const [approved, setApproved] = useState(false);
  const [scannerKey, setScannerKey] = useState(0);

  const scan = (raw: string) => {
    setError(null);
    setApproved(false);
    try {
      const nextToken = tokenFrom(raw);
      setApproving(true);
      void qrScan(nextToken)
        .then(() => setToken(nextToken))
        .catch((cause: Error) => {
          setError(cause.message || 'Could not use that sign-in code.');
          setScannerKey((key) => key + 1);
        })
        .finally(() => setApproving(false));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not read that sign-in code.');
      setScannerKey((key) => key + 1);
    }
  };

  const approve = () => {
    if (!token) return;
    setApproving(true);
    setError(null);
    void qrApprove(token)
      .then(() => setApproved(true))
      .catch((cause: Error) => setError(cause.message || 'Could not approve this sign-in.'))
      .finally(() => setApproving(false));
  };

  if (approved) {
    return (
      <div className="space-y-4">
        <div className="rounded-xl border border-success/30 bg-success/10 p-4 text-center">
          <Check aria-hidden className="mx-auto size-7 text-success" />
          <p className="mt-2 text-sm font-medium">Sign-in approved</p>
          <p className="mt-1 text-xs text-ink-secondary">
            The other device can now finish signing in.
          </p>
        </div>
        <Button
          type="button"
          variant="secondary"
          className="w-full"
          onClick={() => setApproved(false)}
        >
          <ScanLine aria-hidden className="size-4" />
          Scan another code
        </Button>
      </div>
    );
  }

  if (token) {
    return (
      <div className="space-y-4">
        <div className="rounded-xl border border-border bg-surface-1 p-4">
          <ShieldCheck aria-hidden className="size-6 text-primary" />
          <p className="mt-2 text-sm font-medium">Approve this sign-in?</p>
          <p className="mt-1 text-xs text-ink-secondary">
            Only approve a code shown on a device you trust. This will sign that device into your
            OrangChat account.
          </p>
        </div>
        {error && (
          <p role="alert" className="text-sm text-danger">
            {error}
          </p>
        )}
        <div className="flex gap-2">
          <Button type="button" className="flex-1" loading={approving} onClick={approve}>
            <ShieldCheck aria-hidden className="size-4" />
            Approve sign-in
          </Button>
          <Button
            type="button"
            variant="ghost"
            disabled={approving}
            onClick={() => {
              setToken(null);
              setError(null);
            }}
          >
            Cancel
          </Button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div>
        <p className="text-sm font-medium">Scan a sign-in code</p>
        <p className="mt-1 text-sm text-ink-secondary">
          Point your camera at the QR code on the device you want to sign in. You will review and
          approve the request before it is allowed.
        </p>
      </div>
      <QrScanner key={scannerKey} expect={QR_KIND.signIn} onScan={scan} />
      {approving && <p className="text-xs text-ink-muted">Checking sign-in code…</p>}
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}
    </div>
  );
}
