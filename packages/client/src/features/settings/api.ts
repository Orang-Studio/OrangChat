import type {
  AccountStanding,
  DeviceSession,
  BackupCodes,
  TwoFactorSetup,
  TwoFactorStatus,
} from "@orangchat/shared";
import { api } from "../../lib/api";

export const getTwoFactorStatus = () => api<TwoFactorStatus>("/security/2fa");

export const getAccountStanding = () => api<AccountStanding>("/security/standing");

/** Sessions live under /auth so the path-scoped refresh cookie is in scope. */
export const getSessions = () => api<{ sessions: DeviceSession[] }>("/auth/sessions");

export const revokeSession = (id: string) =>
  api<{ revoked: number }>(`/auth/sessions/${id}`, { method: "DELETE" });

/**
 * Freezes/unfreezes the account. Turning it on signs out every other device;
 * turning it off needs the password. Lives under /auth so the refresh cookie is
 * in scope and this session can be spared.
 */
export const setLockdown = (on: boolean, password: string) =>
  api<{ lockdown: boolean; sessionsRevoked: number }>("/auth/lockdown", {
    method: "POST",
    json: { on, password },
  });

/** Signs out every device but this one. */
export const revokeOtherSessions = () =>
  api<{ revoked: number }>("/auth/sessions", { method: "DELETE" });

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

/** Leaves every server you don't own; owned ones are reported back untouched. */
export const leaveAllServers = () =>
  api<{ left: number; keptOwned: string[] }>("/servers/leave-all", { method: "POST" });

/** Irreversible: wipes the user's messages everywhere, including left servers. */
export const deleteAllMessages = (password: string, code: string) =>
  api<{ deleted: number }>("/security/messages", {
    method: "DELETE",
    json: { password, code },
  });

/** Irreversible. `username` must match exactly or the server refuses. */
export const deleteAccount = (password: string, username: string, code: string) =>
  api<{ deleted: boolean }>("/security/account", {
    method: "DELETE",
    json: { password, username, code },
  });

/**
 * Proves the person at the keyboard, without changing anything. Guards settings
 * that may only be *relaxed* deliberately - see docs/E2EE.md §6.5.
 */
export const confirmIdentity = (password: string, code: string) =>
  api<{ ok: boolean }>("/security/confirm", { method: "POST", json: { password, code } });
