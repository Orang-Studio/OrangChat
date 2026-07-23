import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, Copy, Download, ShieldCheck, ShieldOff } from "lucide-react";
import { Button } from "../../components/ui/Button";
import { PasswordField } from "../../components/ui/PasswordField";
import { TextField } from "../../components/ui/TextField";
import { authStoreActions, useAuthStore } from "../../stores/auth";
import { SectionTitle } from "./controls";
import {
  changeEmail,
  changePassword,
  disableTwoFactor,
  enableTwoFactor,
  getTwoFactorStatus,
  regenerateBackupCodes,
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
      <CredentialsSection />

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
    </div>
  );
}
