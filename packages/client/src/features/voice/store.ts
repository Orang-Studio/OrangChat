import { create } from "zustand";
import type { VoiceDevicePayload, VoiceStatePayload } from "@orangchat/shared";
import { useConnectionStore } from "../../stores/connection";
import { getPrefs } from "../../lib/prefs";
import { playLeaveSound } from "../../lib/ringtone";
import { socket } from "../../lib/socket";
import { withAck } from "../../lib/socketAck";
import { stopSoundboardClips } from "../soundboard/playback";
import { getVoiceParticipants } from "./api";
import type { VideoTile } from "./livekit";

const livekit = () => import("./livekit");

export type VoiceSessionStatus = "connecting" | "connected" | "error";

export interface VoiceSession {
  channelId: string;
  channelName: string;
  serverId: string | null;
  status: VoiceSessionStatus;
  error: string | null;
  muted: boolean;
  deafened: boolean;
  video: boolean;
  screenSharing: boolean;
}

interface VoiceState {
  session: VoiceSession | null;

  participants: Record<string, Record<string, VoiceStatePayload>>;

  videoTiles: VideoTile[];

  speakingIds: string[];

  devices: VoiceDevicePayload[];

  mediaError: string | null;
}

export const useVoiceStore = create<VoiceState>(() => ({
  session: null,
  participants: {},
  videoTiles: [],
  speakingIds: [],
  devices: [],
  mediaError: null,
}));


function cameraErrorMessage(err: unknown): string {
  const name = err instanceof Error ? err.name : "";
  switch (name) {
    case "NotAllowedError":
    case "SecurityError":
      return "Camera blocked. Allow camera access for this site in your browser, then try again.";
    case "NotFoundError":
    case "OverconstrainedError":
      return "No camera found. Check the camera picked in Settings is still plugged in.";
    case "NotReadableError":
      return "Your camera is already in use by another app.";
    default:
      return err instanceof Error && err.message
        ? `Could not start the camera: ${err.message}`
        : "Could not start the camera.";
  }
}

/**
 * Dismissing the screen picker rejects with the same NotAllowedError as a real
 * refusal, and cancelling is by far the likelier one - so that case reverts the
 * button quietly rather than accusing the user of blocking anything.
 */
function screenErrorMessage(err: unknown): string | null {
  const name = err instanceof Error ? err.name : "";
  switch (name) {
    case "NotAllowedError":
      return null;
    case "NotFoundError":
      return "No screen available to share.";
    case "NotReadableError":
      return "Your screen could not be captured. Another app may be holding it.";
    default:
      return err instanceof Error && err.message
        ? `Could not share your screen: ${err.message}`
        : "Could not share your screen.";
  }
}

export const voiceActions = {
  applyVoiceState(payload: VoiceStatePayload): void {
    useVoiceStore.setState((s) => {
      const channel = { ...(s.participants[payload.channelId] ?? {}) };
      if (payload.joined) channel[payload.userId] = payload;
      else delete channel[payload.userId];
      return { participants: { ...s.participants, [payload.channelId]: channel } };
    });
  },

  applyDevices(devices: VoiceDevicePayload[]): void {
    useVoiceStore.setState({ devices });
  },

  seedParticipants(channelId: string, list: VoiceStatePayload[]): void {
    useVoiceStore.setState((s) => ({
      participants: {
        ...s.participants,
        [channelId]: Object.fromEntries(list.map((p) => [p.userId, p])),
      },
    }));
  },

  async join(
    channel: { id: string; name: string | null; serverId: string | null },
    options: { video?: boolean } = {},
  ): Promise<void> {
    const previous = useVoiceStore.getState().session;
    if (previous?.channelId === channel.id && previous.status !== "error") return;
    if (previous) await voiceActions.leave();

    const prefs = getPrefs();
    const video = options.video ?? prefs.joinWithVideo;
    const muted = prefs.joinMuted;
    useVoiceStore.setState({
      mediaError: null,
      session: {
        channelId: channel.id,
        channelName: channel.name ?? "voice",
        serverId: channel.serverId,
        status: "connecting",
        error: null,
        muted,
        deafened: false,
        video,
        screenSharing: false,
      },
    });

    try {
      const creds = await withAck<{ token: string; url: string }>((ack) =>
        socket.emit("voice:join", channel.id, ack),
      );
      const lk = await livekit();
      lk.setTileSink((videoTiles) => useVoiceStore.setState({ videoTiles }));
      lk.setSpeakerSink((speakingIds) => useVoiceStore.setState({ speakingIds }));
      lk.setScreenShareEndedSink(() => voiceActions.applyScreenShareEnded());
      const { cameraError } = await lk.connectVoice(creds.url, creds.token, {
        micEnabled: !muted,
        deafened: false,
        cameraEnabled: video,
      });
      // We are in the call either way; only the camera fell over. Correct the
      // flag so the button matches reality rather than what we asked for.
      if (cameraError) {
        useVoiceStore.setState((s) => ({
          ...(s.session?.channelId === channel.id
            ? { session: { ...s.session, video: false } }
            : {}),
          mediaError: cameraErrorMessage(cameraError),
        }));
      }
      const videoOn = video && !cameraError;
      if (videoOn) socket.emit("voice:update", { channelId: channel.id, video: true });
      if (muted) socket.emit("voice:update", { channelId: channel.id, muted: true });
      // Whoever was already here muted before we arrived: their voice:state
      // fired while we were not listening, so fetch the roster once. DM calls
      // have no channel list to seed them the way voice channels do.
      getVoiceParticipants(channel.id)
        .then((list) => voiceActions.seedParticipants(channel.id, list))
        .catch(() => {
          /* live voice:state events still keep the roster roughly right */
        });
      useVoiceStore.setState((s) =>
        s.session?.channelId === channel.id
          ? { session: { ...s.session, status: "connected" } }
          : {},
      );
    } catch (err) {
      socket.emit("voice:leave", channel.id);
      const lk = await livekit();
      lk.setTileSink(null);
      lk.setSpeakerSink(null);
      lk.setScreenShareEndedSink(null);
      await lk.disconnectVoice();
      useVoiceStore.setState({ videoTiles: [], speakingIds: [] });
      useVoiceStore.setState((s) =>
        s.session?.channelId === channel.id
          ? {
              session: {
                ...s.session,
                status: "error",
                error: err instanceof Error ? err.message : "Failed to join voice",
              },
            }
          : {},
      );
    }
  },

  /**
   * Hang up. `silent` suppresses the leave cue for callers that are already
   * playing a more specific one - a decline should not also sound like a
   * departure.
   */
  async leave(options: { silent?: boolean } = {}): Promise<void> {
    const session = useVoiceStore.getState().session;
    if (!session) return;
    socket.emit("voice:leave", session.channelId);
    useVoiceStore.setState({
      session: null,
      videoTiles: [],
      speakingIds: [],
      mediaError: null,
    });
    // A clip fired as we hang up must not follow us out of the channel.
    stopSoundboardClips();
    if (!options.silent) playLeaveSound();
    const lk = await livekit();
    lk.setTileSink(null);
    lk.setSpeakerSink(null);
    lk.setScreenShareEndedSink(null);
    await lk.disconnectVoice();
  },

  /** Mute is independent of deafen: you can talk while deafened. */
  async toggleMute(): Promise<void> {
    const session = useVoiceStore.getState().session;
    if (!session) return;
    const muted = !session.muted;
    useVoiceStore.setState({ session: { ...session, muted } });
    socket.emit("voice:update", { channelId: session.channelId, muted });
    await (await livekit()).setMicEnabled(!muted);
  },

  async toggleDeafen(): Promise<void> {
    const session = useVoiceStore.getState().session;
    if (!session) return;
    const deafened = !session.deafened;
    // Deafening mutes too, Discord-style. Undeafening leaves the mic as it
    // found it - and unmuting while still deafened is allowed, so deafen only
    // ever governs what we hear, never whether we can be heard.
    const muted = deafened ? true : session.muted;
    useVoiceStore.setState({ session: { ...session, deafened, muted } });
    socket.emit("voice:update", { channelId: session.channelId, deafened, muted });
    const lk = await livekit();
    lk.setDeafened(deafened);
    await lk.setMicEnabled(!muted);
  },

  /** Publish or drop the camera. Reverts the flag if the camera refuses. */
  async toggleCamera(): Promise<void> {
    const session = useVoiceStore.getState().session;
    if (!session) return;
    const video = !session.video;
    useVoiceStore.setState({ session: { ...session, video }, mediaError: null });
    try {
      await (await livekit()).setCameraEnabled(video);
      socket.emit("voice:update", { channelId: session.channelId, video });
    } catch (err) {
      // Silence here is what makes a blocked camera look like a broken button:
      // the toggle springs back and nothing says why. Say why.
      useVoiceStore.setState((s) => ({
        ...(s.session?.channelId === session.channelId
          ? { session: { ...s.session, video: !video } }
          : {}),
        mediaError: cameraErrorMessage(err),
      }));
    }
  },

  /** Publish or drop the screen. Reverts the flag if capture never starts. */
  async toggleScreenShare(): Promise<void> {
    const session = useVoiceStore.getState().session;
    if (!session) return;
    const screenSharing = !session.screenSharing;
    // Set before publishing so the unpublish event our own stop raises finds the
    // flag already down and leaves it alone.
    useVoiceStore.setState({ session: { ...session, screenSharing }, mediaError: null });
    try {
      await (await livekit()).setScreenShareEnabled(screenSharing);
      socket.emit("voice:update", { channelId: session.channelId, screenSharing });
    } catch (err) {
      useVoiceStore.setState((s) => ({
        ...(s.session?.channelId === session.channelId
          ? { session: { ...s.session, screenSharing: !screenSharing } }
          : {}),
        mediaError: screenErrorMessage(err),
      }));
    }
  },

  /** The capture ended somewhere other than our button - usually the browser's. */
  applyScreenShareEnded(): void {
    const session = useVoiceStore.getState().session;
    if (!session?.screenSharing) return;
    useVoiceStore.setState({ session: { ...session, screenSharing: false } });
    socket.emit("voice:update", { channelId: session.channelId, screenSharing: false });
  },

  /** Hang up one of our other devices. */
  disconnectDevice(sessionId: string): void {
    socket.emit("voice:device:disconnect", sessionId);
  },

  dismissMediaError(): void {
    useVoiceStore.setState({ mediaError: null });
  },

  /**
   * The socket dropped and reconnected: the server wiped our voice presence on
   * disconnect, so re-register (LiveKit media itself is a separate connection).
   */
  restoreAfterReconnect(): void {
    const session = useVoiceStore.getState().session;
    if (!session || session.status === "error") return;
    socket.emit("voice:join", session.channelId, (res) => {
      if (res.ok) {
        socket.emit("voice:update", {
          channelId: session.channelId,
          muted: session.muted,
          deafened: session.deafened,
          video: session.video,
          screenSharing: session.screenSharing,
        });
      }
    });
  },
};

/**
 * The device in this channel that is *not* the one you are looking at, if there
 * is one. This is what the green glow on a call button means: the call is up, on
 * your account, somewhere else.
 *
 * Returns undefined while the socket id is unknown (a reconnect in flight) -
 * without that guard every device would briefly count as "another device" and
 * every call button would glow at once.
 */
export function useOtherDeviceIn(channelId: string | undefined): VoiceDevicePayload | undefined {
  const devices = useVoiceStore((s) => s.devices);
  const socketId = useConnectionStore((s) => s.socketId);
  if (!channelId || !socketId) return undefined;
  return devices.find((d) => d.channelId === channelId && d.sessionId !== socketId);
}

/** Live participants of one voice channel, join-order stable. */
export function useVoiceParticipants(channelId: string): VoiceStatePayload[] {
  return useVoiceStore((s) => PARTICIPANT_CACHE.get(s.participants[channelId]));
}

// Zustand selectors must return stable references; memoize the Object.values
// projection per channel-map object.
const PARTICIPANT_CACHE = {
  map: new WeakMap<object, VoiceStatePayload[]>(),
  empty: [] as VoiceStatePayload[],
  get(channel: Record<string, VoiceStatePayload> | undefined): VoiceStatePayload[] {
    if (!channel) return this.empty;
    let list = this.map.get(channel);
    if (!list) {
      list = Object.values(channel);
      this.map.set(channel, list);
    }
    return list;
  },
};
