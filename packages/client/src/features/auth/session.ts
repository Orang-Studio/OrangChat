import type { AuthResult, AuthTokens, SelfUser } from "@orangchat/shared";
import { authStoreActions, useAuthStore } from "../../stores/auth";
import { connectSocket, socket } from "../../lib/socket";

/**
 * Session lifecycle. Access token lives in memory only; the refresh token
 * rides in an httpOnly cookie, so "restore session on reload" means calling
 * POST /api/auth/refresh and rebuilding state from the response.
 *
 * Uses raw fetch (not lib/api.ts) so the 401-refresh interceptor can import
 * this module without a cycle - and refresh itself must never recurse.
 */

const REFRESH_MARGIN_MS = 30_000;
let refreshTimer: ReturnType<typeof setTimeout> | undefined;
let refreshInFlight: Promise<boolean> | null = null;
let bootstrapped = false;

/** Install a session: store, proactive-refresh timer, socket handshake auth. */
export function applySession(user: SelfUser, tokens: AuthTokens): void {
  authStoreActions.setSession(user, tokens.accessToken);
  scheduleProactiveRefresh(tokens.expiresIn);
  socket.auth = { token: tokens.accessToken };
  connectSocket();
}

/**
 * Exchange the refresh cookie for a new access token. Single-flight: parallel
 * 401s from the interceptor await one request. Resolves false → now a guest.
 */
export function refreshSession(): Promise<boolean> {
  refreshInFlight ??= doRefresh().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

async function doRefresh(): Promise<boolean> {
  try {
    const res = await fetch("/api/auth/refresh", {
      method: "POST",
      credentials: "include",
    });
    if (!res.ok) {
      endLocalSession();
      return false;
    }
    // Accept either AuthResult ({ user, tokens }) or bare AuthTokens.
    const body = (await res.json()) as AuthResult | AuthTokens;
    const tokens: AuthTokens = "tokens" in body ? body.tokens : body;
    const user =
      ("user" in body ? body.user : null) ??
      useAuthStore.getState().user ??
      (await fetchMe(tokens.accessToken));
    if (!user) {
      endLocalSession();
      return false;
    }
    applySession(user, tokens);
    return true;
  } catch {
    endLocalSession();
    return false;
  }
}

async function fetchMe(accessToken: string): Promise<SelfUser | null> {
  const res = await fetch("/api/auth/me", {
    headers: { Authorization: `Bearer ${accessToken}` },
    credentials: "include",
  });
  return res.ok ? ((await res.json()) as SelfUser) : null;
}

/** App-start session restore. Idempotent (StrictMode-safe). */
export function bootstrapSession(): void {
  if (bootstrapped) return;
  bootstrapped = true;
  void refreshSession();
}

export async function logout(): Promise<void> {
  try {
    await fetch("/api/auth/logout", {
      method: "POST",
      credentials: "include",
    });
  } catch {
    // Best effort - clear locally regardless.
  }
  endLocalSession();
}

function endLocalSession(): void {
  clearTimeout(refreshTimer);
  socket.disconnect();
  authStoreActions.clear();
}

function scheduleProactiveRefresh(expiresInSeconds: number): void {
  clearTimeout(refreshTimer);
  const delay = Math.max(expiresInSeconds * 1000 - REFRESH_MARGIN_MS, 15_000);
  refreshTimer = setTimeout(() => void refreshSession(), delay);
}
