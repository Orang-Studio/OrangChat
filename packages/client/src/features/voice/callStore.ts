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

/** `outgoing` = still ringing out; `active` = at least one other person is on. */
export type CallPhase = "outgoing" | "active";

export interface ActiveCall extends DmCallPayload {
  phase: CallPhase;
}

/**
 * Ways of not answering. All of them are an answer to the call rather than a
 * lull in it, so the ringback stops and the caller is told which one happened.
 */
const REFUSALS = new Set<DmCallEndedPayload["reason"]>(["declined", "timeout", "busy"]);

/** Why an outgoing call ended without connecting, for the caller's toast. */
export interface CallNotice {
  userId: string;
  reason: DmCallEndedPayload["reason"];
}

interface CallStoreState {
  /** A call ringing at us that we have not answered yet. */
  incoming: DmCallPayload | null;
  /** The call we started, joined, or answered. */
  current: ActiveCall | null;
  /** Last failure to start/answer a call, for the UI to show. */
  error: string | null;
  /** Someone refused or missed our call; cleared when the user dismisses it. */
  notice: CallNotice | null;
}

export const useCallStore = create<CallStoreState>(() => ({
  incoming: null,
  current: null,
  error: null,
  notice: null,
}));

/** Nobody else has picked up yet, so we are still ringing out. */
function phaseFor(payload: DmCallPayload): CallPhase {
  return payload.participants.length > 1 ? "active" : "outgoing";
}

export const callActions = {
  /** Ring a DM or group DM, then join the room ourselves. */
  async start(
    channel: { id: string; name: string | null; serverId: string | null },
    options: { video?: boolean } = {},
  ): Promise<void> {
    const video = options.video ?? false;
    // A fresh call supersedes whatever the last one ended as.
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

  /** Answer the call currently ringing at us. */
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
          // The ringing payload carries no channel name; the caller is the most
          // useful label for a call anyway.
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

  /** Refuse the call ringing at us. */
  decline(): void {
    const incoming = useCallStore.getState().incoming;
    if (!incoming) return;
    stopRinging();
    useCallStore.setState({ incoming: null });
    socket.emit("dm:call:respond", { channelId: incoming.channelId, accept: false });
  },

  /** Hang up - cancels if it never connected, otherwise leaves. */
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

  // ── Server events ─────────────────────────────────────

  applyRinging(payload: DmCallPayload): void {
    // Already on a call: the server rang us anyway only if we were free, so this
    // is a stale ring. Ignore rather than stack popups.
    if (useCallStore.getState().current) return;
    useCallStore.setState({ incoming: payload });
    startIncomingRing();
  },

  applyAccepted(payload: DmCallPayload): void {
    const current = useCallStore.getState().current;
    if (current?.channelId !== payload.channelId) return;
    // The join cue comes from the LiveKit layer when their media actually
    // lands, so accepting is silent here.
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

    // The server keeps a call alive while we alone are in it, but a call with
    // nobody left to answer and nobody to talk to is over from our side. Without
    // this the caller rings out forever at a callee who already said no.
    const abandoned = ringing.length === 0 && !participants.some((u) => u !== selfId);
    if (abandoned) {
      stopRinging();
      if (refused) playDeclineSound();
      useCallStore.setState({
        current: null,
        notice: refused ? { userId: payload.userId, reason: payload.reason } : null,
      });
      // A refusal already sounded; hanging up behind it must not also thud.
      void voiceActions.leave({ silent: refused });
      return;
    }

    // The call goes on without them. Someone merely leaving is the LiveKit
    // layer's cue to play; a refusal never reached LiveKit, so it is ours.
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

  /**
   * A reconnect means we missed events while offline: any call we were tracking
   * may be long over, and a ring we never saw cannot be answered anyway.
   */
  resetAfterReconnect(): void {
    stopRinging();
    useCallStore.setState({ incoming: null });
  },
};
