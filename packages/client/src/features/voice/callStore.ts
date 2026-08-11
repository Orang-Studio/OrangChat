import { create } from "zustand";
import type { DmCallEndedPayload, DmCallPayload } from "@orangchat/shared";
import { socket } from "../../lib/socket";
import { withAck } from "../../lib/socketAck";
import {
  playDeclineSound,
  startIncomingRing,
  startOutgoingRing,
  stopRinging,
} from "../../lib/ringtone";
import { voiceActions } from "./store";


export type CallPhase = "outgoing" | "active";

export interface ActiveCall extends DmCallPayload {
  phase: CallPhase;
}


const REFUSALS = new Set<DmCallEndedPayload["reason"]>(["declined", "timeout", "busy"]);


export interface CallNotice {
  userId: string;
  reason: DmCallEndedPayload["reason"];
}

interface CallStoreState {

  incoming: DmCallPayload | null;

  current: ActiveCall | null;

  error: string | null;

  notice: CallNotice | null;
}

export const useCallStore = create<CallStoreState>(() => ({
  incoming: null,
  current: null,
  error: null,
  notice: null,
}));


function phaseFor(payload: DmCallPayload): CallPhase {
  return payload.participants.length > 1 ? "active" : "outgoing";
}

export const callActions = {

  async start(
    channel: { id: string; name: string | null; serverId: string | null },
    options: { video?: boolean } = {},
  ): Promise<void> {
    const video = options.video ?? false;
    useCallStore.setState({ error: null, notice: null });
    try {
      const payload = await withAck<DmCallPayload>((ack) =>
        socket.emit("dm:call:start", { channelId: channel.id, video }, ack),
      );
      const phase = phaseFor(payload);
      useCallStore.setState({ current: { ...payload, phase } });
      if (phase === "outgoing") startOutgoingRing();
      await voiceActions.join(channel, { video });
    } catch (err) {
      stopRinging();
      useCallStore.setState({
        current: null,
        error: err instanceof Error ? err.message : "Could not start the call",
      });
    }
  },


  async accept(options: { video?: boolean } = {}): Promise<void> {
    const incoming = useCallStore.getState().incoming;
    if (!incoming) return;
    stopRinging();
    useCallStore.setState({ incoming: null, error: null });
    try {
      const payload = await withAck<DmCallPayload>((ack) =>
        socket.emit("dm:call:respond", { channelId: incoming.channelId, accept: true }, ack),
      );
      useCallStore.setState({ current: { ...payload, phase: "active" } });
      await voiceActions.join(
        {
          id: incoming.channelId,
          name: incoming.caller.displayName,
          serverId: null,
        },
        { video: options.video ?? false },
      );
    } catch (err) {
      useCallStore.setState({
        current: null,
        error: err instanceof Error ? err.message : "Could not join the call",
      });
    }
  },


  decline(): void {
    const incoming = useCallStore.getState().incoming;
    if (!incoming) return;
    stopRinging();
    useCallStore.setState({ incoming: null });
    socket.emit("dm:call:respond", { channelId: incoming.channelId, accept: false });
  },


  async leave(): Promise<void> {
    const current = useCallStore.getState().current;
    if (!current) return;
    stopRinging();
    socket.emit(
      current.phase === "outgoing" ? "dm:call:cancel" : "dm:call:end",
      current.channelId,
    );
    useCallStore.setState({ current: null });
    await voiceActions.leave();
  },

  dismissError(): void {
    useCallStore.setState({ error: null });
  },


  applyRinging(payload: DmCallPayload): void {
    if (useCallStore.getState().current) return;
    useCallStore.setState({ incoming: payload });
    startIncomingRing();
  },

  applyAccepted(payload: DmCallPayload): void {
    const current = useCallStore.getState().current;
    if (current?.channelId !== payload.channelId) return;
    stopRinging();
    useCallStore.setState({ current: { ...payload, phase: "active" } });
  },

  applyEnded(payload: DmCallEndedPayload, selfId: string | undefined): void {
    const { incoming, current } = useCallStore.getState();
    const aboutUs = payload.userId === selfId;

    if (incoming?.channelId === payload.channelId && (payload.callOver || aboutUs)) {
      stopRinging();
      useCallStore.setState({ incoming: null });
    }

    if (current?.channelId !== payload.channelId) return;
    if (payload.callOver || aboutUs) {
      stopRinging();
      useCallStore.setState({ current: null });
      void voiceActions.leave();
      return;
    }

    const ringing = current.ringing.filter((u) => u !== payload.userId);
    const participants = current.participants.filter((u) => u !== payload.userId);
    const refused = REFUSALS.has(payload.reason);

    const abandoned = ringing.length === 0 && !participants.some((u) => u !== selfId);
    if (abandoned) {
      stopRinging();
      if (refused) playDeclineSound();
      useCallStore.setState({
        current: null,
        notice: refused ? { userId: payload.userId, reason: payload.reason } : null,
      });
      void voiceActions.leave({ silent: refused });
      return;
    }

    if (refused) {
      playDeclineSound();
      useCallStore.setState({ notice: { userId: payload.userId, reason: payload.reason } });
    }
    useCallStore.setState({
      current: { ...current, ringing, participants },
    });
  },

  dismissNotice(): void {
    useCallStore.setState({ notice: null });
  },


  resetAfterReconnect(): void {
    stopRinging();
    useCallStore.setState({ incoming: null });
  },
};
