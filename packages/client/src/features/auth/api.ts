import type {
  AuthResult,
  LoginInput,
  SelfUser,
  SignupInput,
  UpdateProfileInput,
} from '@orangchat/shared';
import { api } from '../../lib/api';
import type { RequestChallenge } from './webauthn';


export interface LoginChallenge {
  email2faRequired?: boolean;
  loginToken?: string;

  passkeyRequired?: boolean;
  challenge?: RequestChallenge;
  ceremonyToken?: string;

  user?: AuthResult['user'];
  tokens?: AuthResult['tokens'];
}


export const login = (input: LoginInput & { skipPasskey?: boolean; lostAuthenticator?: boolean }) =>
  api<LoginChallenge>('/auth/login', { method: 'POST', json: input });


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


export const qrStart = () =>
  api<{ token: string; expiresIn: number }>('/auth/qr/start', { method: 'POST' });


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
