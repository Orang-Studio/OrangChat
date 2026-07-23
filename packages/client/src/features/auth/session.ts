import type { AuthResult, AuthTokens, PresenceDevice, SelfUser } from "@orangchat/shared";
import { authStoreActions, useAuthStore } from "../../stores/auth";
import { connectSocket, socket } from "../../lib/socket";

/**
 * Session lifecycle. Access token lives in memory only; the refresh token
 * rides in an httpOnly cookie, so restoring a session on reload means calling
 * POST /api/auth/refresh and rebuilding state from the response.
 *
 * Uses raw fetch (not lib/api.ts) so the 401-refresh interceptor can import
 * this module without a cycle.
 */

const REFRESH_MARGIN_MS = 30_000;
const RETRY_DELAY_MS = 5_000;
const CONNECT_ERROR_REFRESH_COOLDOWN_MS = 4_000;
export const clientDevice: PresenceDevice = "orangchatDesktop" in window ? "desktop" : "browser";
let refreshTimer: ReturnType<typeof setTimeout> | undefined;
let refreshInFlight: Promise<boolean> | null = null;
let bootstrapped = false;
let handlersInstalled = false;
let lastConnectErrorRefresh = 0;

/** Install a session: store, proactive-refresh timer, socket handshake auth. */
export function applySession(user: SelfUser, tokens: AuthTokens): void {
  authStoreActions.setSession(user, tokens.accessToken);
  scheduleProactiveRefresh(tokens.expiresIn);
  socket.auth = { token: tokens.accessToken, device: clientDevice };
  connectSocket();
}

/**
 * Exchange the refresh cookie for a new access token. Single-flight: parallel
 * 401s from the interceptor await one request. Resolves false when guest.
 */
export function refreshSession(): Promise<boolean> {
  refreshInFlight ??= doRefresh().finally(() => {
    refreshInFlight = null;
  });
  return refreshInFlight;
}

async function doRefresh(): Promise<boolean> {
  let res: Response;
  try {
    res = await fetch("/api/auth/refresh", {
      method: "POST",
      credentials: "include",
    });
  } catch {
    // network is down, not a rejected session. keep whatever we have and try
    // again shortly instead of logging the user out over a dropped wifi.
    scheduleRetry();
    return false;
  }

  // 401/403 means the refresh token itself is gone or revoked: really a guest.
  // other non-ok (5xx, proxy blips) is transient, so treat it like a network
  // error and retry rather than tearing down the session.
  if (!res.ok) {
    if (res.status === 401 || res.status === 403) {
      endLocalSession();
      return false;
    }
    scheduleRetry();
    return false;
  }

  try {
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
    scheduleRetry();
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
  installConnectivityHandlers();
  void refreshSession();
}

export async function logout(): Promise<void> {
  try {
    await fetch("/api/auth/logout", {
      method: "POST",
      credentials: "include",
    });
  } catch {
    // best effort, clear locally regardless.
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

/** Retry a refresh that failed for a transient reason, without dropping state. */
function scheduleRetry(): void {
  if (!useAuthStore.getState().user) return;
  clearTimeout(refreshTimer);
  refreshTimer = setTimeout(() => void refreshSession(), RETRY_DELAY_MS);
}

/**
 * Keep the socket alive across suspends and dropped links. socket.io retries on
 * its own, but a backgrounded tab or a dead transport often needs a nudge when
 * the app comes back, and an expired access token needs a fresh one before the
 * handshake can succeed.
 */
function installConnectivityHandlers(): void {
  if (handlersInstalled) return;
  handlersInstalled = true;

  const nudge = () => {
    if (!useAuthStore.getState().user) return;
    if (socket.connected) return;
    // reuse the current token; if it is stale the handshake fails and
    // connect_error below refreshes it.
    connectSocket();
  };

  // the handshake was rejected. only "unauthorized" is ours to fix by minting a
  // new token; anything else is transport-level and socket.io keeps retrying.
  socket.on("connect_error", (err) => {
    if (!useAuthStore.getState().user) return;
    if (!/unauthorized/i.test(err.message)) return;
    const now = Date.now();
    if (now - lastConnectErrorRefresh < CONNECT_ERROR_REFRESH_COOLDOWN_MS) return;
    lastConnectErrorRefresh = now;
    void refreshSession();
  });

  window.addEventListener("online", nudge);
  window.addEventListener("focus", nudge);
  document.addEventListener("visibilitychange", () => {
    if (!document.hidden) nudge();
  });
}
