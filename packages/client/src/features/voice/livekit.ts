import {
  createAudioAnalyser,
  LocalAudioTrack,
  LocalVideoTrack,
  Room,
  RoomEvent,
  Track,
  type RemoteVideoTrack,
} from "livekit-client";
import { getPrefs } from "../../lib/prefs";
import { playJoinSound, playLeaveSound } from "../../lib/ringtone";



let room: Room | null = null;


export type TileSource = "camera" | "screen";


export interface VideoTile {
  identity: string;
  name: string;
  isLocal: boolean;
  source: TileSource;
  attach: (el: HTMLVideoElement) => void;
  detach: (el: HTMLVideoElement) => void;
}

let tiles: VideoTile[] = [];
let onTiles: ((tiles: VideoTile[]) => void) | null = null;
let onSpeakers: ((identities: string[]) => void) | null = null;
let onScreenShareEnded: (() => void) | null = null;


export function setScreenShareEndedSink(sink: (() => void) | null): void {
  onScreenShareEnded = sink;
}


export function setTileSink(sink: ((tiles: VideoTile[]) => void) | null): void {
  onTiles = sink;
  onTiles?.(tiles);
}


let remoteSpeakers: string[] = [];
let localSpeaking = false;

function emitSpeakers(): void {
  const identity = room?.localParticipant.identity;
  const ids = remoteSpeakers.filter((id) => id !== identity);
  if (localSpeaking && identity) ids.push(identity);
  onSpeakers?.(ids);
}


export function setSpeakerSink(sink: ((identities: string[]) => void) | null): void {
  onSpeakers = sink;
  if (!sink) return;
  emitSpeakers();
}


const SPEAKING_THRESHOLD = 0.02;

const SPEAKING_HOLD_MS = 250;

let analyserCleanup: (() => void) | null = null;
let analyserFrame: number | null = null;

function startLocalSpeaking(current: Room): void {
  stopLocalSpeaking();
  const publication = current.localParticipant.getTrackPublication(Track.Source.Microphone);
  const track = publication?.track;
  if (!(track instanceof LocalAudioTrack)) return;

  const { calculateVolume, cleanup } = createAudioAnalyser(track, { cloneTrack: false });
  analyserCleanup = () => void cleanup();

  let lastLoudAt = 0;
  const tick = () => {
    const volume = publication?.isMuted ? 0 : calculateVolume();
    const now = performance.now();
    if (volume > SPEAKING_THRESHOLD) lastLoudAt = now;
    const speaking = now - lastLoudAt < SPEAKING_HOLD_MS;
    if (speaking !== localSpeaking) {
      localSpeaking = speaking;
      emitSpeakers();
    }
    analyserFrame = requestAnimationFrame(tick);
  };
  analyserFrame = requestAnimationFrame(tick);
}

function stopLocalSpeaking(): void {
  if (analyserFrame !== null) cancelAnimationFrame(analyserFrame);
  analyserFrame = null;
  analyserCleanup?.();
  analyserCleanup = null;
  localSpeaking = false;
}


const tileKey = (identity: string, isLocal: boolean, source: TileSource) =>
  `${identity}:${isLocal ? "self" : "remote"}:${source}`;

/**
 * Tiles are rebuilt from the room snapshot, but the UI attaches the track to a
 * <video> keyed on tile identity - so a tile whose track has not changed must
 * stay the same object, or every sync tears the video element down and back up.
 */
const tileCache = new Map<string, { track: Track; tile: VideoTile }>();

function tileFor(
  track: RemoteVideoTrack | LocalVideoTrack,
  identity: string,
  name: string,
  isLocal: boolean,
  source: TileSource,
): VideoTile {
  const key = tileKey(identity, isLocal, source);
  const cached = tileCache.get(key);
  if (cached && cached.track === track && cached.tile.name === name) return cached.tile;
  const tile: VideoTile = {
    identity,
    name,
    isLocal,
    source,
    attach: (el) => void track.attach(el),
    detach: (el) => void track.detach(el),
  };
  tileCache.set(key, { track, tile });
  return tile;
}

const sourceOf = (source: Track.Source): TileSource =>
  source === Track.Source.ScreenShare ? "screen" : "camera";

/**
 * Rebuild the tile list from room state rather than patching it per event:
 * cheaper to reason about, and it cannot drift.
 *
 * A muted publication is skipped. This is the whole reason camera-off works at
 * all: `setCameraEnabled(false)` mutes the track, it does not unpublish it, so
 * the publication (and its last frame) outlives the camera being switched off.
 * Only screenshare is unpublished on disable, so `isMuted` is what "camera off"
 * actually looks like on both sides of the call.
 */
function syncVideoTiles(current: Room): void {
  const next: VideoTile[] = [];
  for (const publication of current.localParticipant.videoTrackPublications.values()) {
    const track = publication.track;
    if (track instanceof LocalVideoTrack && !publication.isMuted) {
      next.push(
        tileFor(
          track,
          current.localParticipant.identity,
          "You",
          true,
          sourceOf(publication.source),
        ),
      );
    }
  }
  for (const participant of current.remoteParticipants.values()) {
    for (const publication of participant.videoTrackPublications.values()) {
      const track = publication.track;
      if (track?.kind === Track.Kind.Video && publication.isSubscribed && !publication.isMuted) {
        next.push(
          tileFor(
            track as RemoteVideoTrack,
            participant.identity,
            participant.name || participant.identity,
            false,
            sourceOf(publication.source),
          ),
        );
      }
    }
  }
  const live = new Set(next.map((tile) => tileKey(tile.identity, tile.isLocal, tile.source)));
  for (const key of tileCache.keys()) if (!live.has(key)) tileCache.delete(key);
  tiles = next;
  onTiles?.(tiles);
}

function clearTiles(): void {
  tileCache.clear();
  tiles = [];
  onTiles?.(tiles);
}

function audioContainer(): HTMLElement {
  let el = document.getElementById("voice-audio");
  if (!el) {
    el = document.createElement("div");
    el.id = "voice-audio";
    el.style.display = "none";
    document.body.appendChild(el);
  }
  return el;
}

/**
 * Join the room. Resolves with the camera failure, if there was one: a refused
 * camera must not fail the join, because an audio call we can still have beats
 * no call at all. The caller decides how to tell the user.
 */
export async function connectVoice(
  url: string,
  token: string,
  options: { micEnabled: boolean; deafened: boolean; cameraEnabled?: boolean },
): Promise<{ cameraError: unknown | null }> {
  await disconnectVoice();

  const prefs = getPrefs();
  const asConstraint = (id: string) => (id && id !== "default" ? { deviceId: id } : {});
  const next = new Room({
    audioCaptureDefaults: asConstraint(prefs.micDeviceId),
    videoCaptureDefaults: asConstraint(prefs.cameraDeviceId),
  });
  room = next;

  next
    .on(RoomEvent.TrackSubscribed, (track, _pub, participant) => {
      if (track.kind === Track.Kind.Audio) {
        audioContainer().appendChild(track.attach());
        if (isDeafened) participant.setVolume(0);
        return;
      }
      syncVideoTiles(next);
    })
    .on(RoomEvent.TrackUnsubscribed, (track) => {
      track.detach().forEach((el) => el.remove());
      syncVideoTiles(next);
    })
    // Camera off arrives as a mute, never an unpublish, so without these two the
    // tile would sit there showing the last frame the camera ever sent.
    .on(RoomEvent.TrackMuted, () => syncVideoTiles(next))
    .on(RoomEvent.TrackUnmuted, () => syncVideoTiles(next))
    .on(RoomEvent.ParticipantDisconnected, () => {
      syncVideoTiles(next);
      playLeaveSound();
    })
    // Toggling the mic republishes: the analyser has to follow the new track,
    // not the dead one it was reading.
    .on(RoomEvent.LocalTrackPublished, (publication) => {
      syncVideoTiles(next);
      if (publication.source === Track.Source.Microphone) startLocalSpeaking(next);
    })
    .on(RoomEvent.LocalTrackUnpublished, (publication) => {
      syncVideoTiles(next);
      if (publication.source === Track.Source.Microphone) {
        stopLocalSpeaking();
        emitSpeakers();
      }
      // Fires for our own toggle as well as the browser's stop-sharing bar; the
      // sink is idempotent, so it only has to matter in the second case.
      if (publication.source === Track.Source.ScreenShare) onScreenShareEnded?.();
    })
    .on(RoomEvent.ParticipantConnected, (participant) => {
      if (isDeafened) participant.setVolume(0);
      playJoinSound();
    })
    .on(RoomEvent.ActiveSpeakersChanged, (speakers) => {
      remoteSpeakers = speakers.map((participant) => participant.identity);
      emitSpeakers();
    });

  await next.connect(url, token);
  // Our own arrival. ParticipantConnected only ever fires for other people, and
  // the confirmation that we are actually in is worth hearing.
  playJoinSound();
  if (prefs.speakerDeviceId && prefs.speakerDeviceId !== "default") {
    // Unsupported on Firefox/Safari - a rejected switch must not fail the join.
    await next.switchActiveDevice("audiooutput", prefs.speakerDeviceId).catch(() => {});
  }
  isDeafened = options.deafened;
  // Deafen governs only what we hear, so it must not gate the mic here.
  await next.localParticipant.setMicrophoneEnabled(options.micEnabled);
  let cameraError: unknown = null;
  if (options.cameraEnabled) {
    try {
      await next.localParticipant.setCameraEnabled(true);
    } catch (err) {
      cameraError = err;
    }
  }
  syncVideoTiles(next);
  return { cameraError };
}

export async function disconnectVoice(): Promise<void> {
  const current = room;
  room = null;
  if (current) await current.disconnect();
  audioContainer().replaceChildren();
  clearTiles();
  stopLocalSpeaking();
  remoteSpeakers = [];
  onSpeakers?.([]);
}

export async function setMicEnabled(enabled: boolean): Promise<void> {
  await room?.localParticipant.setMicrophoneEnabled(enabled);
}

/** Publish or drop the camera track. Rejects if permission is refused. */
export async function setCameraEnabled(enabled: boolean): Promise<void> {
  await room?.localParticipant.setCameraEnabled(enabled);
  if (room) syncVideoTiles(room);
}

/**
 * Publish or drop the screen. Rejects if the picker is dismissed or the OS
 * refuses capture. `audio` is best-effort by construction: only Chromium tab
 * and window captures can carry it, and the user still has to tick the box.
 */
export async function setScreenShareEnabled(enabled: boolean): Promise<void> {
  await room?.localParticipant.setScreenShareEnabled(enabled, { audio: true });
  if (room) syncVideoTiles(room);
}

let isDeafened = false;

/** Deafen = silence everyone remote (and the caller mutes the mic separately). */
export function setDeafened(deafened: boolean): void {
  isDeafened = deafened;
  if (!room) return;
  for (const participant of room.remoteParticipants.values()) {
    participant.setVolume(deafened ? 0 : 1);
  }
}
