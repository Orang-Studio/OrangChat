import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Check,
  Copy,
  DoorOpen,
  Download,
  Eraser,
  Lock,
  ShieldCheck,
  ShieldOff,
  Trash2,
} from "lucide-react";
import { Button } from "../../components/ui/Button";
import { PasswordField } from "../../components/ui/PasswordField";
import { TextField } from "../../components/ui/TextField";
import { authStoreActions, useAuthStore } from "../../stores/auth";
import { formatFullTime } from "../../lib/time";
import { SectionTitle } from "./controls";
import { PasskeysSection } from "./PasskeysSection";
import {
  changeEmail,
  changePassword,
  deleteAccount,
  deleteAllMessages,
  disableTwoFactor,
  enableTwoFactor,
  getAccountStanding,
  getTwoFactorStatus,
  leaveAllServers,
  regenerateBackupCodes,
  setLockdown,
  startTwoFactorSetup,
} from "./api";

/**
 * Rendered client-side so the secret never rides in an image URL. qrcode is
 * ~20 kB, and only ever needed on this screen.
 */
function QrCode({ text }: { text: string }) {
  const [svg, setSvg] = useState<string | null>(null);

  useEffect(() => {
    let live = true;
    void import("qrcode")
      .then((qr) => qr.toString(text, { type: "svg", margin: 1, width: 192 }))
      .then((out) => live && setSvg(out))
      .catch(() => live && setSvg(null));
    return () => {
      live = false;
    };
  }, [text]);

  if (!svg) {
    return <div className="size-48 animate-pulse rounded-lg bg-surface-3" />;
  }
  return (
    <div
      aria-label="Two-factor QR code"
      className="size-48 rounded-lg bg-white p-2 [&>svg]:size-full"
      dangerouslySetInnerHTML={{ __html: svg }}
    />
  );
}

function BackupCodeList({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    await navigator.clipboard.writeText(codes.join("\n"));
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  const download = () => {
    const url = URL.createObjectURL(new Blob([codes.join("\n")], { type: "text/plain" }));
    const a = document.createElement("a");
    a.href = url;
    a.download = "orangchat-recovery-codes.txt";
    a.click();
    URL.revokeObjectURL(url);
  };

  return (
    <div className="space-y-3">
      <SectionTitle>Recovery codes</SectionTitle>
      <p className="text-sm text-ink-secondary">
        Save these now - each works once if you lose your phone, and this is the only time
        they're shown.
      </p>
      <ul className="grid grid-cols-2 gap-2 rounded-lg border border-border bg-surface-1 p-3 font-mono text-sm">
        {codes.map((code) => (
          <li key={code}>{code}</li>
        ))}
      </ul>
      <div className="flex flex-wrap gap-2">
        <Button type="button" variant="secondary" size="sm" onClick={() => void copy()}>
          {copied ? <Check aria-hidden className="size-4" /> : <Copy aria-hidden className="size-4" />}
          {copied ? "Copied" : "Copy"}
        </Button>
        <Button type="button" variant="secondary" size="sm" onClick={download}>
          <Download aria-hidden className="size-4" />
          Download
        </Button>
        <Button type="button" size="sm" onClick={onDone}>
          I've saved them
        </Button>
      </div>
    </div>
  );
}

function EnrollFlow({ onDone }: { onDone: () => void }) {
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [codes, setCodes] = useState<string[] | null>(null);
  const hasPassword = useAuthStore((s) => s.user)?.hasPassword ?? true;

  const setup = useMutation({ mutationFn: () => startTwoFactorSetup(password) });
  const enable = useMutation({
    mutationFn: () => enableTwoFactor(code),
    onSuccess: (res) => setCodes(res.backupCodes),
  });

  if (codes) return <BackupCodeList codes={codes} onDone={onDone} />;

  if (!setup.data) {
    return (
      <form
        className="space-y-3"
        onSubmit={(e) => {
          e.preventDefault();
          setup.mutate();
        }}
      >
        <p className="text-sm text-ink-secondary">
          You'll need an authenticator app - Google Authenticator, 1Password, Aegis, or any
          other TOTP app.
        </p>
        {hasPassword && (
          <PasswordField
            label="Confirm your password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        )}
        {setup.isError && <p className="text-sm text-danger">{setup.error.message}</p>}
        <Button type="submit" loading={setup.isPending}>
          Continue
        </Button>
      </form>
    );
  }

  return (
    <form
      className="space-y-4"
      onSubmit={(e) => {
        e.preventDefault();
        enable.mutate();
      }}
    >
      <div>
        <SectionTitle>1. Scan this code</SectionTitle>
        <QrCode text={setup.data.otpauthUrl} />
      </div>
      <div>
        <SectionTitle>Can't scan?</SectionTitle>
        <p className="text-xs text-ink-secondary">Enter this key manually:</p>
        <code className="mt-1 block break-all rounded-lg border border-border bg-surface-1 px-3 py-2 font-mono text-xs">
          {setup.data.secret}
        </code>
      </div>
      <div>
        <SectionTitle>2. Enter the 6-digit code</SectionTitle>
        <TextField
          label="Code from your app"
          value={code}
          onChange={(e) => setCode(e.target.value)}
          inputMode="numeric"
          autoComplete="one-time-code"
          maxLength={6}
          placeholder="123456"
        />
      </div>
      {enable.isError && <p className="text-sm text-danger">{enable.error.message}</p>}
      <Button type="submit" loading={enable.isPending} disabled={code.length !== 6}>
        Turn on two-factor
      </Button>
    </form>
  );
}

function ManageEnabled({ backupCodesRemaining }: { backupCodesRemaining: number }) {
  const hasPassword = useAuthStore((s) => s.user)?.hasPassword ?? true;
  const queryClient = useQueryClient();
  const [mode, setMode] = useState<"idle" | "disable" | "regen">("idle");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [codes, setCodes] = useState<string[] | null>(null);

  const reset = () => {
    setMode("idle");
    setPassword("");
    setCode("");
  };

  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ["2fa"] });
  };

  const disable = useMutation({
    mutationFn: () => disableTwoFactor(password, code),
    onSuccess: () => {
      authStoreActions.patchUser({ twoFactorEnabled: false });
      refresh();
      reset();
    },
  });

  const regen = useMutation({
    mutationFn: () => regenerateBackupCodes(password, code),
    onSuccess: (res) => {
      setCodes(res.backupCodes);
      refresh();
      reset();
    },
  });

  if (codes) return <BackupCodeList codes={codes} onDone={() => setCodes(null)} />;

  const active = mode === "disable" ? disable : regen;

  return (
    <div className="space-y-4">
      <div className="flex items-start gap-3 rounded-lg border border-success/40 bg-success/10 px-3 py-2.5">
        <ShieldCheck aria-hidden className="mt-0.5 size-4 shrink-0 text-success" />
        <div>
          <p className="text-sm font-medium">Two-factor authentication is on</p>
          <p className="text-xs text-ink-secondary">
            {backupCodesRemaining} recovery code{backupCodesRemaining === 1 ? "" : "s"} left.
            {backupCodesRemaining <= 2 && " Consider generating a new set."}
          </p>
        </div>
      </div>

      {mode === "idle" ? (
        <div className="flex flex-wrap gap-2">
          <Button type="button" variant="secondary" size="sm" onClick={() => setMode("regen")}>
            New recovery codes
          </Button>
          <Button
            type="button"
            variant="ghost"
            size="sm"
            className="text-danger hover:text-danger"
            onClick={() => setMode("disable")}
          >
            <ShieldOff aria-hidden className="size-4" />
            Turn off
          </Button>
        </div>
      ) : (
        <form
          className="space-y-3 rounded-lg border border-border p-3"
          onSubmit={(e) => {
            e.preventDefault();
            active.mutate();
          }}
        >
          <p className="text-sm text-ink-secondary">
            {mode === "disable"
              ? "Turning off two-factor makes your password the only thing protecting your account."
              : "This replaces every existing recovery code."}
          </p>
          {hasPassword && (
            <PasswordField
              label="Your password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          )}
          <TextField
            label={mode === "disable" ? "Code from your app (or a recovery code)" : "Code from your app"}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            autoComplete="one-time-code"
            placeholder="123456"
          />
          {active.isError && <p className="text-sm text-danger">{active.error.message}</p>}
          <div className="flex gap-2">
            <Button
              type="submit"
              size="sm"
              variant={mode === "disable" ? "danger" : "primary"}
              loading={active.isPending}
              disabled={!code}
            >
              {mode === "disable" ? "Turn off two-factor" : "Generate codes"}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={reset}>
              Cancel
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}

/**
 * Change email / set-or-change password. Both are gated on the current password
 * (except on OAuth-only accounts, which have none) plus a 2FA code when it's on,
 * so the two forms share one credential block.
 */
function CredentialsSection() {
  const user = useAuthStore((s) => s.user);
  const hasPassword = user?.hasPassword ?? true;
  const twoFactor = user?.twoFactorEnabled ?? false;

  const [mode, setMode] = useState<"idle" | "email" | "password">("idle");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [email, setEmail] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [done, setDone] = useState<string | null>(null);

  const reset = () => {
    setMode("idle");
    setPassword("");
    setCode("");
    setEmail("");
    setNewPassword("");
    setConfirm("");
  };

  const emailMutation = useMutation({
    mutationFn: () => changeEmail(password, email, code),
    onSuccess: (res) => {
      authStoreActions.patchUser({ email: res.email });
      setDone(`Email changed to ${res.email}.`);
      reset();
    },
  });

  const passwordMutation = useMutation({
    mutationFn: () => changePassword(password, newPassword, code),
    onSuccess: (res) => {
      authStoreActions.patchUser({ hasPassword: true });
      setDone(
        res.sessionsRevoked > 0
          ? `Password changed. ${res.sessionsRevoked} other session${
              res.sessionsRevoked === 1 ? " was" : "s were"
            } signed out.`
          : "Password changed.",
      );
      reset();
    },
  });

  const active = mode === "email" ? emailMutation : passwordMutation;
  const mismatch = newPassword.length > 0 && confirm.length > 0 && newPassword !== confirm;
  const canSubmit =
    mode === "email"
      ? email.trim().length > 0
      : newPassword.length >= 8 && newPassword === confirm;

  return (
    <div className="space-y-4">
      <div>
        <SectionTitle>Email &amp; password</SectionTitle>
        <p className="text-sm text-ink-secondary">
          Signed in as <span className="text-ink">{user?.email}</span>.
        </p>
      </div>

      {done && (
        <p role="status" className="rounded-lg bg-success/10 px-3 py-2 text-sm text-success">
          {done}
        </p>
      )}

      {mode === "idle" ? (
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => {
              setDone(null);
              setMode("email");
            }}
          >
            Change email
          </Button>
          <Button
            type="button"
            variant="secondary"
            size="sm"
            onClick={() => {
              setDone(null);
              setMode("password");
            }}
          >
            {hasPassword ? "Change password" : "Set a password"}
          </Button>
        </div>
      ) : (
        <form
          className="space-y-3 rounded-lg border border-border p-3"
          onSubmit={(e) => {
            e.preventDefault();
            if (canSubmit) active.mutate();
          }}
        >
          {mode === "email" ? (
            <TextField
              label="New email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              autoComplete="email"
              // No mail transport in this deployment, so nothing confirms the
              // address afterwards - say so rather than implying a check email.
              hint="Used to sign in. There's no confirmation email, so double-check it."
            />
          ) : (
            <>
              <PasswordField
                label="New password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                autoComplete="new-password"
                hint="At least 8 characters."
              />
              <PasswordField
                label="Confirm new password"
                value={confirm}
                onChange={(e) => setConfirm(e.target.value)}
                autoComplete="new-password"
              />
              {mismatch && <p className="text-sm text-danger">Those don't match.</p>}
            </>
          )}

          {hasPassword && (
            <PasswordField
              label="Your current password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          )}
          {twoFactor && (
            <TextField
              label="Code from your app (or a recovery code)"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              autoComplete="one-time-code"
              placeholder="123456"
            />
          )}

          {mode === "password" && (
            <p className="text-xs text-ink-muted">
              Changing your password signs out every other session.
            </p>
          )}
          {active.isError && <p className="text-sm text-danger">{active.error.message}</p>}

          <div className="flex gap-2">
            <Button type="submit" size="sm" loading={active.isPending} disabled={!canSubmit}>
              {mode === "email" ? "Change email" : hasPassword ? "Change password" : "Set password"}
            </Button>
            <Button type="button" variant="ghost" size="sm" onClick={reset}>
              Cancel
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}

/**
 * Bans and live timeouts against the account. Moderation is per-server - there
 * is no instance-wide sanction - so "good standing" means no server currently
 * restricts you.
 */
function AccountStandingSection() {
  const { data, isPending } = useQuery({
    queryKey: ["account-standing"],
    queryFn: getAccountStanding,
  });

  return (
    <div className="space-y-3">
      <SectionTitle>Account standing</SectionTitle>

      {isPending ? (
        <div className="h-14 animate-pulse rounded-lg bg-surface-3" />
      ) : data?.good ? (
        <div className="flex items-start gap-3 rounded-lg border border-success/40 bg-success/10 px-3 py-2.5">
          <ShieldCheck aria-hidden className="mt-0.5 size-4 shrink-0 text-success" />
          <div>
            <p className="text-sm font-medium">Your account is in good standing</p>
            <p className="text-xs text-ink-secondary">
              No server is currently restricting you.
            </p>
          </div>
        </div>
      ) : (
        <ul className="space-y-2">
          {data?.entries.map((entry) => (
            <li
              key={`${entry.kind}-${entry.serverId}`}
              className="rounded-lg border border-danger/40 bg-danger/10 px-3 py-2.5"
            >
              <p className="text-sm font-medium">
                {entry.kind === "ban" ? "Banned from" : "Timed out in"} {entry.serverName}
              </p>
              {entry.reason && (
                <p className="text-xs text-ink-secondary">Reason: {entry.reason}</p>
              )}
              {entry.expiresAt && (
                <p className="text-xs text-ink-secondary">
                  Until {formatFullTime(entry.expiresAt)}
                </p>
              )}
              {entry.kind === "ban" && entry.createdAt && (
                <p className="text-xs text-ink-muted">{formatFullTime(entry.createdAt)}</p>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * Bulk-leaves every server the user doesn't own. Two-step: destructive enough
 * that one click shouldn't do it, cheap enough that it doesn't need a password.
 */
function LeaveAllServersSection() {
  const queryClient = useQueryClient();
  const [confirming, setConfirming] = useState(false);
  const [result, setResult] = useState<{ left: number; keptOwned: string[] } | null>(null);

  const mutation = useMutation({
    mutationFn: leaveAllServers,
    onSuccess: (res) => {
      setResult(res);
      setConfirming(false);
      void queryClient.invalidateQueries({ queryKey: ["servers"] });
    },
  });

  return (
    <div className="space-y-3">
      <SectionTitle>Leave all servers</SectionTitle>
      <p className="text-sm text-ink-secondary">
        Leaves every server you're in except the ones you own. You'll need a new
        invite to get back into any of them.
      </p>

      {result && (
        <p role="status" className="rounded-lg bg-success/10 px-3 py-2 text-sm text-success">
          Left {result.left} server{result.left === 1 ? "" : "s"}.
          {result.keptOwned.length > 0 &&
            ` Still yours: ${result.keptOwned.join(", ")}.`}
        </p>
      )}
      {mutation.isError && <p className="text-sm text-danger">{mutation.error.message}</p>}

      {confirming ? (
        <div className="flex flex-wrap gap-2">
          <Button
            type="button"
            size="sm"
            variant="danger"
            loading={mutation.isPending}
            onClick={() => mutation.mutate()}
          >
            Yes, leave them all
          </Button>
          <Button type="button" variant="ghost" size="sm" onClick={() => setConfirming(false)}>
            Cancel
          </Button>
        </div>
      ) : (
        <Button
          type="button"
          variant="secondary"
          size="sm"
          onClick={() => {
            setResult(null);
            setConfirming(true);
          }}
        >
          <DoorOpen aria-hidden className="size-4" />
          Leave all servers
        </Button>
      )}
    </div>
  );
}

/**
 * Freezes the account: nothing can sign in, no new DM reaches it, no friend
 * request lands. For "I think someone's in my account" - a step short of
 * deleting it, and reversible.
 */
function LockdownSection() {
  const user = useAuthStore((s) => s.user);
  const hasPassword = user?.hasPassword ?? true;
  const locked = user?.lockdown ?? false;

  const [confirming, setConfirming] = useState(false);
  const [password, setPassword] = useState("");
  const [revoked, setRevoked] = useState<number | null>(null);

  const mutation = useMutation({
    mutationFn: (on: boolean) => setLockdown(on, password),
    onSuccess: (res) => {
      authStoreActions.patchUser({ lockdown: res.lockdown });
      setRevoked(res.lockdown ? res.sessionsRevoked : null);
      setConfirming(false);
      setPassword("");
    },
  });

  return (
    <div className="space-y-3">
      <SectionTitle>Lockdown</SectionTitle>

      {locked ? (
        <div className="flex items-start gap-3 rounded-lg border border-warning/40 bg-warning/10 px-3 py-2.5">
          <Lock aria-hidden className="mt-0.5 size-4 shrink-0 text-warning" />
          <div>
            <p className="text-sm font-medium">Your account is locked down</p>
            <p className="text-xs text-ink-secondary">
              Nothing can sign in, and no new DMs or friend requests reach you.
              This device stays signed in.
            </p>
          </div>
        </div>
      ) : (
        <p className="text-sm text-ink-secondary">
          Freezes the account if you think someone else is in it: signs out every
          other device, blocks new sign-ins, and closes new DMs and friend
          requests until you lift it.
        </p>
      )}

      {revoked !== null && revoked > 0 && (
        <p role="status" className="rounded-lg bg-success/10 px-3 py-2 text-sm text-success">
          Signed out {revoked} other session{revoked === 1 ? "" : "s"}.
        </p>
      )}
      {mutation.isError && <p className="text-sm text-danger">{mutation.error.message}</p>}

      {confirming ? (
        <form
          className="space-y-3 rounded-lg border border-border p-3"
          onSubmit={(e) => {
            e.preventDefault();
            mutation.mutate(!locked);
          }}
        >
          {/* Lifting needs the password; turning it on deliberately doesn't, so
              nothing slows you down in the moment you actually need it. */}
          {locked && hasPassword && (
            <PasswordField
              label="Your password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          )}
          <div className="flex gap-2">
            <Button
              type="submit"
              size="sm"
              variant={locked ? "primary" : "danger"}
              loading={mutation.isPending}
            >
              {locked ? "Lift lockdown" : "Lock down my account"}
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => {
                setConfirming(false);
                setPassword("");
              }}
            >
              Cancel
            </Button>
          </div>
        </form>
      ) : (
        <Button
          type="button"
          variant={locked ? "primary" : "secondary"}
          size="sm"
          onClick={() => {
            setRevoked(null);
            setConfirming(true);
          }}
        >
          <Lock aria-hidden className="size-4" />
          {locked ? "Lift lockdown" : "Lock down my account"}
        </Button>
      )}
    </div>
  );
}

/**
 * Wipes every message the user has written, anywhere - including servers and
 * group DMs they've left. Password-gated: unlike leaving a server, none of this
 * can be undone or re-obtained.
 */
function DeleteAllMessagesSection() {
  const user = useAuthStore((s) => s.user);
  const hasPassword = user?.hasPassword ?? true;
  const twoFactor = user?.twoFactorEnabled ?? false;

  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [deleted, setDeleted] = useState<number | null>(null);

  const mutation = useMutation({
    mutationFn: () => deleteAllMessages(password, code),
    onSuccess: (res) => {
      setDeleted(res.deleted);
      setOpen(false);
      setPassword("");
      setCode("");
    },
  });

  return (
    <div className="space-y-3">
      <SectionTitle>Delete all your messages</SectionTitle>
      <p className="text-sm text-ink-secondary">
        Removes every message you've sent, in every server and group - including
        the ones you've left. Attachments you uploaded aren't removed from
        storage. This can't be undone.
      </p>

      {deleted !== null && (
        <p role="status" className="rounded-lg bg-success/10 px-3 py-2 text-sm text-success">
          Deleted {deleted} message{deleted === 1 ? "" : "s"}.
        </p>
      )}

      {!open ? (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="text-danger hover:text-danger"
          onClick={() => {
            setDeleted(null);
            setOpen(true);
          }}
        >
          <Eraser aria-hidden className="size-4" />
          Delete all my messages
        </Button>
      ) : (
        <form
          className="space-y-3 rounded-lg border border-danger/40 p-3"
          onSubmit={(e) => {
            e.preventDefault();
            mutation.mutate();
          }}
        >
          {hasPassword && (
            <PasswordField
              label="Your password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          )}
          {twoFactor && (
            <TextField
              label="Code from your app (or a recovery code)"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              autoComplete="one-time-code"
              placeholder="123456"
            />
          )}
          {mutation.isError && <p className="text-sm text-danger">{mutation.error.message}</p>}
          <div className="flex gap-2">
            <Button type="submit" size="sm" variant="danger" loading={mutation.isPending}>
              Delete them all
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => {
                setOpen(false);
                setPassword("");
                setCode("");
              }}
            >
              Cancel
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}

/**
 * Irreversible account deletion. The account is tombstoned rather than removed:
 * messages stay where they are so other people's conversations don't develop
 * holes, and everything identifying is scrubbed. Owning a server blocks it -
 * the server says which ones.
 */
function DeleteAccountSection() {
  const user = useAuthStore((s) => s.user);
  const hasPassword = user?.hasPassword ?? true;
  const twoFactor = user?.twoFactorEnabled ?? false;

  const [open, setOpen] = useState(false);
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [username, setUsername] = useState("");

  const mutation = useMutation({
    mutationFn: () => deleteAccount(password, username, code),
    // The session is already dead server-side; a full reload is the honest way
    // to land back on the sign-in screen with no stale state in memory.
    onSuccess: () => window.location.reload(),
  });

  const confirmed = username === user?.username;

  return (
    <div className="space-y-3">
      <SectionTitle>Delete account</SectionTitle>
      <p className="text-sm text-ink-secondary">
        Your messages stay in the conversations they're part of, shown as from a
        deleted user. Everything else - profile, connections, friends, server
        memberships - is erased. This can't be undone.
      </p>

      {!open ? (
        <Button
          type="button"
          variant="ghost"
          size="sm"
          className="text-danger hover:text-danger"
          onClick={() => setOpen(true)}
        >
          <Trash2 aria-hidden className="size-4" />
          Delete my account
        </Button>
      ) : (
        <form
          className="space-y-3 rounded-lg border border-danger/40 p-3"
          onSubmit={(e) => {
            e.preventDefault();
            if (confirmed) mutation.mutate();
          }}
        >
          <TextField
            label={`Type ${user?.username} to confirm`}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            autoComplete="off"
          />
          {hasPassword && (
            <PasswordField
              label="Your password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoComplete="current-password"
            />
          )}
          {twoFactor && (
            <TextField
              label="Code from your app (or a recovery code)"
              value={code}
              onChange={(e) => setCode(e.target.value)}
              autoComplete="one-time-code"
              placeholder="123456"
            />
          )}
          {mutation.isError && <p className="text-sm text-danger">{mutation.error.message}</p>}
          <div className="flex gap-2">
            <Button
              type="submit"
              size="sm"
              variant="danger"
              loading={mutation.isPending}
              disabled={!confirmed}
            >
              Permanently delete
            </Button>
            <Button
              type="button"
              variant="ghost"
              size="sm"
              onClick={() => {
                setOpen(false);
                setUsername("");
                setPassword("");
                setCode("");
              }}
            >
              Cancel
            </Button>
          </div>
        </form>
      )}
    </div>
  );
}

export function SecurityTab() {
  const queryClient = useQueryClient();
  const { data, isPending } = useQuery({
    queryKey: ["2fa"],
    queryFn: getTwoFactorStatus,
  });

  const onEnrolled = () => {
    authStoreActions.patchUser({ twoFactorEnabled: true });
    void queryClient.invalidateQueries({ queryKey: ["2fa"] });
  };

  return (
    <div className="space-y-6">
      <AccountStandingSection />

      <div className="border-t border-border pt-5">
        <CredentialsSection />
      </div>

      <div className="border-t border-border pt-5">
        <PasskeysSection />
      </div>

      <div className="border-t border-border pt-5">
        <SectionTitle>Two-factor authentication</SectionTitle>
        <p className="mb-4 text-sm text-ink-secondary">
          Ask for a code from your phone in addition to your password when you sign in.
        </p>
        {isPending ? (
          <div className="h-20 animate-pulse rounded-lg bg-surface-3" />
        ) : data?.enabled ? (
          <ManageEnabled backupCodesRemaining={data.backupCodesRemaining} />
        ) : (
          <EnrollFlow onDone={onEnrolled} />
        )}
      </div>

      <div className="border-t border-border pt-5">
        <LockdownSection />
      </div>

      <div className="border-t border-border pt-5">
        <LeaveAllServersSection />
      </div>

      <div className="border-t border-border pt-5">
        <DeleteAllMessagesSection />
      </div>

      <div className="border-t border-border pt-5">
        <DeleteAccountSection />
      </div>
    </div>
  );
}
