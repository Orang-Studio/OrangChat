import type {
  AuthResult,
  LoginInput,
  SelfUser,
  SignupInput,
  UpdateProfileInput,
} from "@orangchat/shared";
import { api } from "../../lib/api";

export const login = (input: LoginInput) =>
  api<AuthResult>("/auth/login", { method: "POST", json: input });

// ── QR sign-in ──────────────────────────────────────────

export const qrStart = () =>
  api<{ token: string; expiresIn: number }>("/auth/qr/start", { method: "POST" });

/** Poll status. `session` is present only once the phone approves. */
export const qrPoll = (token: string) =>
  api<{ status: "pending" | "scanned" | "approved" | "expired"; session?: AuthResult }>(
    `/auth/qr/poll?token=${encodeURIComponent(token)}`,
  );

export const signup = (input: SignupInput) =>
  api<AuthResult>("/auth/signup", { method: "POST", json: input });

export const updateProfile = (input: UpdateProfileInput) =>
  api<SelfUser>("/auth/me", { method: "PATCH", json: input });
