import type { SoundboardPlayedPayload } from "@orangchat/shared";
import { getPrefs } from "../../lib/prefs";

/**
 * Play an incoming soundboard clip.
 *
 * A plain <audio> rather than a LiveKit track: the sound is a file everyone
 * already has a url for, so sending it through the SFU would re-encode it, cost
 * uplink, and arrive worse than the original.
 *
 * The listener's voice session arrives as an argument rather than being read
 * from the store here: the voice store has to call `stopSoundboardClips` when
 * you hang up, and importing it back would make the two modules a cycle.
 */

/** The parts of the voice session that decide whether a clip is ours to hear. */
interface ListenerSession {
  channelId: string;
  deafened: boolean;
}

/** setSinkId is Chromium-only and not in the DOM lib. */
type SinkAudio = HTMLAudioElement & { setSinkId?: (id: string) => Promise<void> };

/**
 * Clips still playing. Held only so leaving voice can cut them off - otherwise a
 * sound fired as you hang up would follow you out of the channel.
 */
const playing = new Set<HTMLAudioElement>();

export function playSoundboardClip(
  payload: SoundboardPlayedPayload,
  session: ListenerSession | null,
): void {
  // Deafened means "I hear nothing from this channel", and the soundboard is
  // the channel. It must not be the one thing that gets through.
  if (session?.deafened) return;
  // A clip from a channel we are not sitting in is not ours to hear; the room
  // fan-out is per voice channel, but a stale room membership should not leak.
  if (session?.channelId !== payload.channelId) return;

  const audio = new Audio(payload.url) as SinkAudio;
  audio.volume = Math.min(Math.max(payload.volume, 0), 1);

  const prefs = getPrefs();
  if (prefs.speakerDeviceId && prefs.speakerDeviceId !== "default") {
    // Unsupported on Firefox/Safari - a rejected switch must not mute the clip.
    void audio.setSinkId?.(prefs.speakerDeviceId).catch(() => {});
  }

  playing.add(audio);
  const forget = () => playing.delete(audio);
  audio.addEventListener("ended", forget);
  audio.addEventListener("error", forget);
  // Autoplay is blocked until the page has been interacted with; someone in a
  // voice call has interacted with it, so this only ever fires for real faults.
  void audio.play().catch(forget);
}

/** Cut every clip still sounding. Called when leaving voice. */
export function stopSoundboardClips(): void {
  for (const audio of playing) {
    audio.pause();
    audio.currentTime = 0;
  }
  playing.clear();
}
