import type {
  AuthResult,
  LoginInput,
  SelfUser,
  SignupInput,
  UpdateProfileInput,
} from '@orangchat/shared';
import { api } from '../../lib/api';

/**
 * Password login never mints a session on its own: it either rejects with a
 * `2fa_required` 401 (the account also wants its authenticator code) or mails a
 * one-time code and hands back the token that finishes the sign-in.
 */
export interface LoginChallenge {
  email2faRequired: boolean;
  loginToken: string;
}

export const login = (input: LoginInput) =>
  api<LoginChallenge>('/auth/login', { method: 'POST', json: input });

export const verifyEmailCode = (loginToken: string, code: string) =>
  api<AuthResult>('/auth/login/email-2fa', { method: 'POST', json: { loginToken, code } });

export const resendEmailCode = (loginToken: string) =>
  api<{ ok: boolean }>('/auth/login/email-2fa/resend', {
    method: 'POST',
    json: { loginToken },
  });

// ── QR sign-in ──────────────────────────────────────────

export const qrStart = () =>
  api<{ token: string; expiresIn: number }>('/auth/qr/start', { method: 'POST' });

/** Poll status. `session` is present only once the phone approves. */
export const qrPoll = (token: string) =>
  api<{ status: 'pending' | 'scanned' | 'approved' | 'expired'; session?: AuthResult }>(
    `/auth/qr/poll?token=${encodeURIComponent(token)}`,
  );

/** Report and approve a QR sign-in from an authenticated device. */
export const qrScan = (token: string) =>
  api<{ ok: boolean }>('/auth/qr/scan', { method: 'POST', json: { token } });

export const qrApprove = (token: string) =>
  api<{ ok: boolean }>('/auth/qr/approve', { method: 'POST', json: { token } });

export const signup = (input: SignupInput) =>
  api<AuthResult>('/auth/signup', { method: 'POST', json: input });

export const updateProfile = (input: UpdateProfileInput) =>
  api<SelfUser>('/auth/me', { method: 'PATCH', json: input });
