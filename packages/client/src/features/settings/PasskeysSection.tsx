import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, KeyRound, Pencil, Trash2 } from 'lucide-react';
import { Button } from '../../components/ui/Button';
import { PasswordField } from '../../components/ui/PasswordField';
import { TextField } from '../../components/ui/TextField';
import { formatFullTime } from '../../lib/time';
import { createPasskey, passkeyError, passkeysSupported } from '../auth/webauthn';
import { SectionTitle } from './controls';
import {
  deletePasskey,
  finishPasskeyRegistration,
  getPasskeys,
  getTwoFactorStatus,
  renamePasskey,
  startPasskeyRegistration,
  type Passkey,
} from './api';

/**
 * Passkeys on the account: what they are, and adding or removing one.
 *
 * Both of those are credential changes, so both are gated the same way the rest
 * of this tab gates them - password, plus a live 2FA code when the account has
 * 2FA. Renaming isn't; the worst it can do is mislabel a row.
 */
export function PasskeysSection() {
  const queryClient = useQueryClient();
  const supported = passkeysSupported();
  const { data, isPending } = useQuery({ queryKey: ['passkeys'], queryFn: getPasskeys });
  const { data: twoFactor } = useQuery({ queryKey: ['2fa'], queryFn: getTwoFactorStatus });
  const needsCode = twoFactor?.enabled ?? false;

  const [adding, setAdding] = useState(false);
  const [name, setName] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<string | null>(null);

  const refresh = () => void queryClient.invalidateQueries({ queryKey: ['passkeys'] });

  const closeAdd = () => {
    setAdding(false);
    setName('');
    setPassword('');
    setCode('');
    setError(null);
  };

  const addMutation = useMutation({
    mutationFn: async () => {
      // The password is checked here, on `start`, rather than on `finish`: it is
      // what buys the ceremony token, and the token is what finish trusts.
      const started = await startPasskeyRegistration(password, code);
      const response = await createPasskey(started.challenge);
      return finishPasskeyRegistration(started.ceremonyToken, name.trim(), response);
    },
    onSuccess: () => {
      closeAdd();
      refresh();
    },
    onError: (err) => setError(passkeyError(err) ?? "That didn't work. Try again."),
  });

  const removeMutation = useMutation({
    mutationFn: ({ id, password, code }: { id: string; password: string; code: string }) =>
      deletePasskey(id, password, code),
    onSuccess: () => {
      setRemoving(null);
      refresh();
    },
  });

  const renameMutation = useMutation({
    mutationFn: ({ id, name }: { id: string; name: string }) => renamePasskey(id, name),
    onSuccess: refresh,
  });

  const passkeys = data?.passkeys ?? [];
  const full = data ? passkeys.length >= data.max : false;

  return (
    <div className="space-y-3">
      <SectionTitle>Passkeys</SectionTitle>
      <p className="text-sm text-ink-secondary">
        Sign in with your fingerprint, face or device PIN instead of a password. A passkey only
        works on the site that created it, so it can't be phished - which is why we ask for one
        instead of an emailed code when your account has one.
      </p>

      {!supported && (
        <p className="rounded-lg bg-surface-2 px-3 py-2 text-sm text-ink-secondary">
          This browser doesn't support passkeys. Passkeys you add elsewhere will still work on your
          other devices.
        </p>
      )}

      {isPending ? (
        <div className="h-16 animate-pulse rounded-lg bg-surface-3" />
      ) : passkeys.length === 0 ? (
        <p className="text-sm text-ink-secondary">No passkeys yet.</p>
      ) : (
        <ul className="space-y-2">
          {passkeys.map((passkey) => (
            <PasskeyRow
              key={passkey.id}
              passkey={passkey}
              needsCode={needsCode}
              removing={removing === passkey.id}
              onRemoveOpen={() => setRemoving(passkey.id)}
              onRemoveCancel={() => setRemoving(null)}
              onRemove={(password, code) =>
                removeMutation.mutate({ id: passkey.id, password, code })
              }
              removePending={removeMutation.isPending}
              removeError={removeMutation.isError ? removeMutation.error.message : null}
              onRename={(next) => renameMutation.mutate({ id: passkey.id, name: next })}
            />
          ))}
        </ul>
      )}

      {adding ? (
        <form
          className="space-y-3 rounded-lg border border-border bg-surface-1 p-3"
          onSubmit={(event) => {
            event.preventDefault();
            setError(null);
            addMutation.mutate();
          }}
        >
          <TextField
            label="Name"
            placeholder="Personal laptop"
            hint="So you can tell it apart from your other devices later."
            value={name}
            onChange={(event) => setName(event.target.value.slice(0, 60))}
          />
          <PasswordField
            label="Current password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          {needsCode && (
            <TextField
              label="Authenticator code"
              inputMode="numeric"
              autoComplete="one-time-code"
              placeholder="123456"
              value={code}
              onChange={(event) => setCode(event.target.value.slice(0, 32))}
            />
          )}
          {error && <p className="text-sm text-danger">{error}</p>}
          <div className="flex gap-2">
            <Button type="submit" size="sm" loading={addMutation.isPending} disabled={!password}>
              Create passkey
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={closeAdd}>
              Cancel
            </Button>
          </div>
        </form>
      ) : (
        <Button
          type="button"
          variant="secondary"
          size="sm"
          disabled={!supported || full}
          onClick={() => setAdding(true)}
        >
          <KeyRound aria-hidden className="size-4" />
          Add a passkey
        </Button>
      )}
      {full && (
        <p className="text-sm text-ink-secondary">
          You've reached the limit of {data?.max} passkeys. Remove one to add another.
        </p>
      )}
    </div>
  );
}

function PasskeyRow({
  passkey,
  needsCode,
  removing,
  onRemoveOpen,
  onRemoveCancel,
  onRemove,
  removePending,
  removeError,
  onRename,
}: {
  passkey: Passkey;
  needsCode: boolean;
  removing: boolean;
  onRemoveOpen: () => void;
  onRemoveCancel: () => void;
  onRemove: (password: string, code: string) => void;
  removePending: boolean;
  removeError: string | null;
  onRename: (name: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(passkey.name);
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');

  return (
    <li className="rounded-lg border border-border bg-surface-1 p-3">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          {editing ? (
            <div className="flex items-center gap-2">
              <TextField
                label="Name"
                value={draft}
                onChange={(event) => setDraft(event.target.value.slice(0, 60))}
              />
              <Button
                type="button"
                size="sm"
                onClick={() => {
                  onRename(draft.trim());
                  setEditing(false);
                }}
              >
                <Check aria-hidden className="size-4" />
              </Button>
            </div>
          ) : (
            <p className="truncate font-medium">{passkey.name}</p>
          )}
          <p className="text-xs text-ink-secondary">
            Added {formatFullTime(passkey.createdAt)}
            {passkey.lastUsedAt
              ? ` · last used ${formatFullTime(passkey.lastUsedAt)}`
              : ' · never used'}
          </p>
          {passkey.backedUp && (
            <p className="text-xs text-ink-secondary">Synced to your device's keychain.</p>
          )}
        </div>
        <div className="flex shrink-0 gap-1">
          {!editing && (
            <Button
              type="button"
              variant="ghost"
              size="sm"
              aria-label={`Rename ${passkey.name}`}
              onClick={() => {
                setDraft(passkey.name);
                setEditing(true);
              }}
            >
              <Pencil aria-hidden className="size-4" />
            </Button>
          )}
          <Button
            type="button"
            variant="ghost"
            size="sm"
            aria-label={`Remove ${passkey.name}`}
            onClick={onRemoveOpen}
          >
            <Trash2 aria-hidden className="size-4" />
          </Button>
        </div>
      </div>

      {removing && (
        <form
          className="mt-3 space-y-3 border-t border-border pt-3"
          onSubmit={(event) => {
            event.preventDefault();
            onRemove(password, code);
          }}
        >
          <p className="text-sm text-ink-secondary">
            Removing this only affects OrangChat. Delete it from the device's own passkey settings
            too, or it will keep showing up there.
          </p>
          <PasswordField
            label="Current password"
            autoComplete="current-password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
          {needsCode && (
            <TextField
              label="Authenticator code"
              inputMode="numeric"
              autoComplete="one-time-code"
              placeholder="123456"
              value={code}
              onChange={(event) => setCode(event.target.value.slice(0, 32))}
            />
          )}
          {removeError && <p className="text-sm text-danger">{removeError}</p>}
          <div className="flex gap-2">
            <Button
              type="submit"
              size="sm"
              variant="danger"
              loading={removePending}
              disabled={!password}
            >
              Remove passkey
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={onRemoveCancel}>
              Cancel
            </Button>
          </div>
        </form>
      )}
    </li>
  );
}
