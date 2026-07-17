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
