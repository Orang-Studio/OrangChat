import { create } from "zustand";

export type ConnectionStatus = "connecting" | "connected" | "disconnected";

interface ConnectionState {
  status: ConnectionStatus;
  socketId: string | null;

  error: string | null;

  attempts: number;
}

export const useConnectionStore = create<ConnectionState>(() => ({
  status: "disconnected",
  socketId: null,
  error: null,
  attempts: 0,
}));

export const connectionActions = {
  connecting: () =>
    useConnectionStore.setState((s) => ({
      status: "connecting",
      attempts: s.attempts + 1,
    })),
  connected: (socketId: string) =>
    useConnectionStore.setState({
      status: "connected",
      socketId,
      error: null,
      attempts: 0,
    }),
  disconnected: (reason?: string) =>
    useConnectionStore.setState({
      status: "disconnected",
      socketId: null,
      error: reason ?? null,
    }),
  error: (message: string) =>
    useConnectionStore.setState({ status: "disconnected", error: message }),
};
