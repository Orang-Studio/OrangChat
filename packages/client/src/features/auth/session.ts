import type { AuthResult, AuthTokens, PresenceDevice, SelfUser } from "@orangchat/shared";
import { authStoreActions, useAuthStore } from "../../stores/auth";
import { connectSocket, socket } from "../../lib/socket";
import { clearCachedMedia } from "../../lib/serviceWorker";
import { registerGamePresence } from "../presence/gamePresence";
import {
  activateOfflineQueryCache,
  clearOfflineQueryCache,
  restoreOfflineSession,
} from "../../lib/offlineQueryCache";



const REFRESH_MARGIN_MS = 30_000;
const RETRY_DELAY_MS = 5_000;
const CONNECT_ERROR_REFRESH_COOLDOWN_MS = 4_000;
export const clientDevice: PresenceDevice = "orangchatDesktop" in window ? "desktop" : "browser";
let refreshTimer: ReturnType<typeof setTimeout> | undefined;
let refreshInFlight: Promise<boolean> | null = null;
let bootstrapped = false;
let handlersInstalled = false;
let lastConnectErrorRefresh = 0;


export function applySession(user: SelfUser, tokens: AuthTokens): void {
  authStoreActions.setSession(user, tokens.accessToken);
  void activateOfflineQueryCache(user);
  scheduleProactiveRefresh(tokens.expiresIn);
  socket.auth = { token: tokens.accessToken, device: clientDevice };
  connectSocket();
}


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
    await fallBackToOfflineSession();
    return false;
  }

  if (!res.ok) {
    if (res.status === 401 || res.status === 403) {
      endLocalSession();
      return false;
    }
    await fallBackToOfflineSession();
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
    await fallBackToOfflineSession();
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
  registerGamePresence();
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
  clearOfflineQueryCache();
  // Cached avatars and proxied images say who this account was talking to.
  // They shouldn't be sitting on disk for whoever signs in next.
  clearCachedMedia();
}

/** Keep the last authenticated shell readable when startup has no network. */
async function fallBackToOfflineSession(): Promise<void> {
  if (!useAuthStore.getState().user) {
    const cached = await restoreOfflineSession();
    if (cached) authStoreActions.setOfflineSession(cached);
  }
  scheduleRetry();
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
