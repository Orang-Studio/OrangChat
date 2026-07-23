import type { BackupCodes, TwoFactorSetup, TwoFactorStatus } from "@orangchat/shared";
import { api } from "../../lib/api";

export const getTwoFactorStatus = () => api<TwoFactorStatus>("/security/2fa");

export const startTwoFactorSetup = (password: string) =>
  api<TwoFactorSetup>("/security/2fa/setup", { method: "POST", json: { password } });

export const enableTwoFactor = (code: string) =>
  api<{ enabled: boolean } & BackupCodes>("/security/2fa/enable", {
    method: "POST",
    json: { code },
  });

export const disableTwoFactor = (password: string, code: string) =>
  api<{ enabled: boolean }>("/security/2fa/disable", {
    method: "POST",
    json: { password, code },
  });

export const regenerateBackupCodes = (password: string, code: string) =>
  api<BackupCodes>("/security/2fa/backup-codes", { method: "POST", json: { password, code } });

/** `code` is ignored server-side unless 2FA is on. Signs out every session. */
export const changePassword = (password: string, newPassword: string, code: string) =>
  api<{ ok: boolean; sessionsRevoked: number }>("/security/password", {
    method: "POST",
    json: { password, newPassword, code },
  });

export const changeEmail = (password: string, email: string, code: string) =>
  api<{ email: string }>("/security/email", {
    method: "POST",
    json: { password, email, code },
  });
