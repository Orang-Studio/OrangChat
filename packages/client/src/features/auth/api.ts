import type {
  AuthResult,
  LoginInput,
  SelfUser,
  SignupInput,
  UpdateProfileInput,
} from '@orangchat/shared';
import { api } from '../../lib/api';
import type { RequestChallenge } from './webauthn';

/**
 * Password login never mints a session on its own. It either rejects with a
 * `2fa_required` 401 (the account also wants its authenticator code), asks for a
 * passkey, or mails a one-time code - and hands back whatever finishes the
 * sign-in from there.
 */
export interface LoginChallenge {
  email2faRequired?: boolean;
  loginToken?: string;
  /** Set instead of `email2faRequired` when the account has a passkey. */
  passkeyRequired?: boolean;
  challenge?: RequestChallenge;
  ceremonyToken?: string;
}

export const login = (input: LoginInput & { skipPasskey?: boolean }) =>
  api<LoginChallenge>('/auth/login', { method: 'POST', json: input });

// ── Passkey sign-in ─────────────────────────────────────
//
// `start` names no account: the credential the browser offers is what names it.
// `finish` closes both this flow and the passkey-as-second-factor one, so it is
// the only endpoint that mints a session either way.

export const passkeyStart = () =>
  api<{ challenge: RequestChallenge; ceremonyToken: string }>('/auth/passkey/start', {
    method: 'POST',
  });

export const passkeyFinish = (ceremonyToken: string, response: unknown) =>
  api<AuthResult>('/auth/passkey/finish', {
    method: 'POST',
    json: { ceremonyToken, response },
  });

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
