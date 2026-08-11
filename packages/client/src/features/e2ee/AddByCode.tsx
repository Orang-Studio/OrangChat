import { useCallback, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { QrCode as QrCodeIcon, ScanLine, ShieldCheck } from 'lucide-react';
import { QR_KIND, decodeContactVerifyQr } from '@orangchat/shared';
import { Button } from '../../components/ui/Button';
import { sendFriendRequestById } from '../friends/api';
import { acceptScannedContact, myContactQr } from './identity';
import { QrCode } from './QrCode';
import { QrScanner } from './QrScanner';
import { t } from "../../lib/i18n";

/**
 * Adding someone in person by scanning their code (§6.6, item 1). This is the
 * highest-value place verification can live, because it is free: the user is
 * already pointing a camera at their friend's phone, so the identity gets pinned
 * inside a gesture they were making anyway. No security chore, no explanation.
 *
 * The friend request and the verification are one action deliberately. Splitting
 * them would turn the second half into a prompt, and prompts get dismissed.
 */
export function AddByCode() {
  const client = useQueryClient();
  const [mode, setMode] = useState<'idle' | 'scan' | 'show'>('idle');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [code, setCode] = useState<string | null>(null);

  const add = useMutation({
    mutationFn: async (raw: string) => {
      const scanned = decodeContactVerifyQr(raw);
      // Verify first. If the code and the server disagree about who this is,
      // that has to surface before a friendship is built on top of it.
      await acceptScannedContact(raw);
      const result = await sendFriendRequestById(scanned.userId);
      return result;
    },
    onSuccess: (result) => {
      setError(null);
      setMode('idle');
      setNotice(
        result.accepted
          ? "You're now friends, and verified. Have them scan your code so they can verify you too."
          : "Friend request sent, and they're verified. Have them scan your code so they can verify you too.",
      );
      void client.invalidateQueries({ queryKey: ['friends'] });
      void client.invalidateQueries({ queryKey: ['e2ee'] });
    },
    onError: (e: Error) => setError(e.message),
  });

  const onScan = useCallback((raw: string) => add.mutate(raw), [add]);

  const showMine = async () => {
    setError(null);
    setNotice(null);
    try {
      setCode(await myContactQr());
      setMode('show');
    } catch (e) {
      setError(e instanceof Error ? e.message : 'No code to show yet.');
    }
  };

  return (
    <div className="space-y-3 border-t border-border pt-4">
      <p className="text-sm text-ink-secondary">
        {t("addByCode.togetherInPersonScanningTheirCode")}
      </p>

      {mode === 'idle' && (
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="secondary"
            onClick={() => {
              setNotice(null);
              setError(null);
              setMode('scan');
            }}
          >
            <ScanLine aria-hidden className="size-4" />
            {t("addByCode.scanTheirCode")}
          </Button>
          <Button type="button" variant="secondary" onClick={() => void showMine()}>
            <QrCodeIcon aria-hidden className="size-4" />
            {t("addByCode.showMyCode")}
          </Button>
        </div>
      )}

      {mode === 'scan' && (
        <QrScanner
          expect={QR_KIND.contactVerify}
          onScan={onScan}
          onCancel={() => setMode('idle')}
        />
      )}

      {mode === 'show' && code && (
        <div className="space-y-2">
          <QrCode value={code} label={t("addByCode.myContactCode")} />
          <p className="text-center text-xs text-ink-muted">
            {t("addByCode.publicInformationOnlyBeingInThe")}
          </p>
          <Button type="button" variant="ghost" className="w-full" onClick={() => setMode('idle')}>
            {t("common.done")}
          </Button>
        </div>
      )}

      {add.isPending && <p className="text-sm text-ink-muted">{t("addByCode.checkingTheirIdentity")}</p>}
      {error && (
        <p role="alert" className="rounded-lg bg-primary-soft px-3 py-2 text-sm text-danger">
          {error}
        </p>
      )}
      {notice && (
        <p className="flex items-start gap-2 rounded-lg bg-primary-soft px-3 py-2 text-sm text-success">
          <ShieldCheck aria-hidden className="mt-0.5 size-4 shrink-0" />
          {notice}
        </p>
      )}
    </div>
  );
}
