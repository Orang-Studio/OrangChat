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
});

socket.on("connect", () => connectionActions.connected(socket.id ?? ""));
socket.on("disconnect", (reason) => connectionActions.disconnected(reason));
socket.on("connect_error", (err) => connectionActions.error(err.message));
socket.io.on("reconnect_attempt", () => connectionActions.connecting());

/** Idempotent connect - safe under StrictMode double-effects. */
export function connectSocket(): void {
  if (!socket.connected) {
    connectionActions.connecting();
    socket.connect();
  }
}
