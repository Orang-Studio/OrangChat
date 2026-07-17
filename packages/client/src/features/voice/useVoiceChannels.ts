import { useEffect } from "react";
import { socket } from "../../lib/socket";
import { getVoiceParticipants } from "./api";
import { voiceActions } from "./store";

/**
 * While mounted: join the socket rooms of the given voice channels (voice:state
 * fans out per channel room) and seed each channel's current participants.
 * Re-joins and re-seeds after reconnects.
 */
export function useVoiceChannels(channelIds: string[]): void {
  const key = channelIds.join(",");

  useEffect(() => {
    const ids = key ? key.split(",") : [];
    if (ids.length === 0) return;

    const sync = () => {
      for (const id of ids) {
        socket.emit("channel:join", id);
        getVoiceParticipants(id)
          .then((list) => voiceActions.seedParticipants(id, list))
          .catch(() => {
            /* channel may be gone; the sidebar will drop it */
          });
      }
    };

    if (socket.connected) sync();
    socket.on("connect", sync);

    return () => {
      socket.off("connect", sync);
      if (socket.connected) {
        for (const id of ids) socket.emit("channel:leave", id);
      }
    };
  }, [key]);
}
