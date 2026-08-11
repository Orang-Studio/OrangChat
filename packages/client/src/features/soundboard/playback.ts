import type { SoundboardPlayedPayload } from "@orangchat/shared";
import { getPrefs } from "../../lib/prefs";




interface ListenerSession {
  channelId: string;
  deafened: boolean;
}


type SinkAudio = HTMLAudioElement & { setSinkId?: (id: string) => Promise<void> };


const playing = new Set<HTMLAudioElement>();

export function playSoundboardClip(
  payload: SoundboardPlayedPayload,
  session: ListenerSession | null,
): void {
  if (session?.deafened) return;
  if (session?.channelId !== payload.channelId) return;

  const audio = new Audio(payload.url) as SinkAudio;
  audio.volume = Math.min(Math.max(payload.volume, 0), 1);

  const prefs = getPrefs();
  if (prefs.speakerDeviceId && prefs.speakerDeviceId !== "default") {
    void audio.setSinkId?.(prefs.speakerDeviceId).catch(() => {});
  }

  playing.add(audio);
  const forget = () => playing.delete(audio);
  audio.addEventListener("ended", forget);
  audio.addEventListener("error", forget);
  void audio.play().catch(forget);
}


export function stopSoundboardClips(): void {
  for (const audio of playing) {
    audio.pause();
    audio.currentTime = 0;
  }
  playing.clear();
}
