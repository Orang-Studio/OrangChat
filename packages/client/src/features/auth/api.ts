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
 * The password buys a second factor, never a session by itself - except when the
 * second factor was already supplied. The server answers with a passkey
 * challenge, a `2fa_required` 401 asking for the authenticator code, a mailed
 * one-time code, or - once a correct `totpCode` came with the request - the
 * finished session.
 */
export interface LoginChallenge {
  email2faRequired?: boolean;
  loginToken?: string;
  /** Set instead of `email2faRequired` when the account has a passkey. */
  passkeyRequired?: boolean;
  challenge?: RequestChallenge;
  ceremonyToken?: string;
  /** Present only when an authenticator code finished the sign-in outright. */
  user?: AuthResult['user'];
  tokens?: AuthResult['tokens'];
}

/**
 * `skipPasskey` and `lostAuthenticator` each step the account down one rung of
 * the second-factor ladder, towards the emailed code.
 */
export const login = (
  input: LoginInput & { skipPasskey?: boolean; lostAuthenticator?: boolean },
) => api<LoginChallenge>('/auth/login', { method: 'POST', json: input });

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
