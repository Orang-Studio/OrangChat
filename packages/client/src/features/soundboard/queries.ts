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


export function useUsableSounds(enabled: boolean) {
  return useQuery({
    queryKey: soundKeys.usable,
    queryFn: listUsableSounds,
    enabled,
  });
}


export function playSound(channelId: string, soundId: string): void {
  socket.emit("soundboard:play", { channelId, soundId });
}
