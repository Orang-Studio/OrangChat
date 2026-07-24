import { useQuery } from "@tanstack/react-query";
import { socket } from "../../lib/socket";
import { listSounds, listUsableSounds } from "./api";

export const soundKeys = {
  server: (serverId: string) => ["sounds", serverId] as const,
  usable: ["sounds", "usable"] as const,
};

export function useSounds(serverId: string | undefined) {
  return useQuery({
    queryKey: soundKeys.server(serverId ?? ""),
    queryFn: () => listSounds(serverId as string),
    enabled: !!serverId,
  });
}

/**
 * Every sound the viewer can play, from all their servers. This is what the
 * soundboard shows: your sounds follow you into any voice room, the way custom
 * emoji are usable in any channel.
 */
export function useUsableSounds(enabled: boolean) {
  return useQuery({
    queryKey: soundKeys.usable,
    queryFn: listUsableSounds,
    enabled,
  });
}

/**
 * Ask the server to play a clip. Fire-and-forget: the sound arrives back over
 * `soundboard:played` like everyone else's, so there is nothing to do locally.
 * The server may refuse (rate limit, no SPEAK) and that is its business.
 */
export function playSound(channelId: string, soundId: string): void {
  socket.emit("soundboard:play", { channelId, soundId });
}
