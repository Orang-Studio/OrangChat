/**
 * The invite link scheme, shared by everything that mints, recognises or
 * follows one: `https://chat.oranges.lt/invite/<code>`.
 */

/**
 * Hosts whose /invite/ links are ours. The deployed origin is listed outright
 * rather than inferred, so a link pasted from production still renders as an
 * invite card when the client is served from localhost in dev.
 */
const APP_HOSTS = ["chat.oranges.lt"];

const INVITE_PATH = /^\/invite\/([A-Za-z0-9_-]{1,32})\/?$/;

/** The canonical link to hand to someone. */
export function inviteUrl(code: string): string {
  return `${window.location.origin}/invite/${encodeURIComponent(code)}`;
}

function isAppHost(host: string): boolean {
  const bare = host.toLowerCase().replace(/^www\./, "");
  return bare === window.location.hostname.toLowerCase() || APP_HOSTS.includes(bare);
}

/** The invite code in a URL, or null if it isn't one of our invite links. */
export function parseInviteUrl(url: URL): string | null {
  if (!isAppHost(url.hostname)) return null;
  return INVITE_PATH.exec(url.pathname)?.[1] ?? null;
}

/**
 * The code out of whatever the user pasted - a full invite link or the bare
 * code. People paste the thing they were given, and the thing they are given is
 * now a URL.
 */
export function parseInviteInput(input: string): string | null {
  const trimmed = input.trim();
  if (!trimmed) return null;
  try {
    return parseInviteUrl(new URL(trimmed));
  } catch {
    // Not a URL, so treat it as a code - but only if it looks like one, so a
    // stray sentence fails here rather than as a puzzling 404 from the API.
    return /^[A-Za-z0-9_-]{1,32}$/.test(trimmed) ? trimmed : null;
  }
}

const PENDING_KEY = "orangchat:pending-invite";

/**
 * An invite a signed-out visitor set out to accept, parked across the sign-in
 * detour.
 *
 * Router state alone can't carry it: OAuth leaves the SPA entirely and comes
 * back on a fresh page load, so anything held in memory is gone by the time
 * they return. Session storage survives that, and being tab-scoped it expires
 * on its own rather than ambushing some later login.
 */
export function setPendingInvite(code: string): void {
  try {
    sessionStorage.setItem(PENDING_KEY, code);
  } catch {
    // Private mode or storage disabled: the in-router `from` still covers the
    // password path, and the worst case is landing on the app instead.
  }
}

/** Read and clear the parked invite - it should only ever redirect once. */
export function takePendingInvite(): string | null {
  try {
    const code = sessionStorage.getItem(PENDING_KEY);
    if (code) sessionStorage.removeItem(PENDING_KEY);
    return code;
  } catch {
    return null;
  }
}
