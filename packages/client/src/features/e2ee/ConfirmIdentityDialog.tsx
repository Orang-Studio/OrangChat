import { useEffect, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { Button } from '../../components/ui/Button';
import { Dialog, DialogContent } from '../../components/ui/Dialog';
import { PasswordField } from '../../components/ui/PasswordField';
import { TextField } from '../../components/ui/TextField';
import { useAuthStore } from '../../stores/auth';
import { confirmIdentity } from '../settings/api';
import { t } from "../../lib/i18n";

/**
 * The local re-authentication §6.5 requires before strict mode can be relaxed.
 * Turning verification *on* is free; turning it off is the direction that costs
 * something, so it has to be a person rather than a session that does it.
 */
export function ConfirmIdentityDialog({
  open,
  onOpenChange,
  onConfirmed,
  title,
  explanation,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onConfirmed: () => void;
  title: string;
  explanation: string;
}) {
  const user = useAuthStore((s) => s.user);
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');

  useEffect(() => {
    if (!open) {
      setPassword('');
      setCode('');
    }
  }, [open]);

  const confirm = useMutation({
    mutationFn: () => confirmIdentity(password, code),
    onSuccess: () => {
      onOpenChange(false);
      onConfirmed();
    },
  });

  const needsPassword = user?.hasPassword !== false;
  const needsCode = user?.twoFactorEnabled === true;

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent title={title} className="max-w-sm">
        <form
          className="mt-3 space-y-3"
          onSubmit={(event) => {
            event.preventDefault();
            confirm.mutate();
          }}
        >
          <p className="text-sm text-ink-secondary">{explanation}</p>
          {needsPassword && (
            <PasswordField
              label={t("confirmIdentityDialog.password")}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          )}
          {needsCode && (
            <TextField
              label={t("confirmIdentityDialog.authenticationCode")}
              value={code}
              onChange={(e) => setCode(e.target.value)}
              inputMode="numeric"
              maxLength={8}
            />
          )}
          {confirm.isError && (
            <p role="alert" className="text-sm text-danger">
              {confirm.error.message}
            </p>
          )}
          <Button
            type="submit"
            className="w-full"
            loading={confirm.isPending}
            disabled={needsPassword && password.length === 0}
          >
            {t("common.confirm")}
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  );
}
