import { io, type Socket } from "socket.io-client";
import type {
  ClientToServerEvents,
  ServerToClientEvents,
} from "@orangchat/shared";
import { connectionActions } from "../stores/connection";

export type AppSocket = Socket<ServerToClientEvents, ClientToServerEvents>;

/**
 * Single shared socket. Same-origin: Vite proxies /socket.io to the backend
 * in dev, nginx does in production. autoConnect is off so auth (Phase 1) can
 * attach a token before the handshake.
 */
export const socket: AppSocket = io({
  path: "/socket.io",
  autoConnect: false,
  withCredentials: true,
  reconnection: true,
  reconnectionAttempts: Infinity,
  reconnectionDelay: 1_000,
  reconnectionDelayMax: 5_000,
  timeout: 20_000,
});

const PRESENCE_HEARTBEAT_MS = 60_000;
const WEB_IDLE_DELAY_MS = 10 * 60_000;
let presenceHeartbeat: ReturnType<typeof setInterval> | null = null;
let webIdleTimer: ReturnType<typeof setTimeout> | null = null;
let webIdle = false;

function stopPresenceHeartbeat(): void {
  if (presenceHeartbeat !== null) clearInterval(presenceHeartbeat);
  presenceHeartbeat = null;
}

function startPresenceHeartbeat(): void {
  stopPresenceHeartbeat();
  const beat = () => socket.emit("presence:heartbeat", webIdle ? "idle" : "online");
  beat();
  presenceHeartbeat = setInterval(beat, PRESENCE_HEARTBEAT_MS);
}

function webIsFocused(): boolean {
  return document.visibilityState === "visible" && document.hasFocus();
}

function noteWebActivity(): void {
  if (webIdleTimer !== null) clearTimeout(webIdleTimer);
  webIdleTimer = null;
  if (!webIsFocused()) {
    webIdleTimer = setTimeout(() => {
      webIdleTimer = null;
      if (webIsFocused()) return;
      webIdle = true;
      if (socket.connected) socket.emit("presence:lifecycle", "idle");
    }, WEB_IDLE_DELAY_MS);
    return;
  }
  webIdle = false;
  if (socket.connected) socket.emit("presence:lifecycle", "online");
}

window.addEventListener("focus", noteWebActivity);
window.addEventListener("blur", noteWebActivity);
document.addEventListener("visibilitychange", noteWebActivity);
noteWebActivity();

socket.on("connect", () => {
  connectionActions.connected(socket.id ?? "");
  startPresenceHeartbeat();
  socket.emit("presence:lifecycle", webIdle ? "idle" : "online");
});
socket.on("disconnect", (reason) => {
  stopPresenceHeartbeat();
  connectionActions.disconnected(reason);
});
socket.on("connect_error", (err) => connectionActions.error(err.message));
socket.io.on("reconnect_attempt", () => connectionActions.connecting());

/** Idempotent connect - safe under StrictMode double-effects. */
export function connectSocket(): void {
  if (!socket.connected) {
    connectionActions.connecting();
    socket.connect();
  }
}
